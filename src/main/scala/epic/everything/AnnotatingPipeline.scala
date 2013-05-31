package epic
package everything

import java.io.File
import epic.trees._
import epic.parser.{GenerativeParser, ParserParams}
import epic.parser.ParserParams.XbarGrammar
import breeze.config.{Help, CommandLineParser}
import epic.ontonotes.{NERType, ConllOntoReader, Document}
import breeze.linalg._
import epic.framework._
import breeze.optimize._
import breeze.util.{Index, Encoder}
import epic.parser.projections.ParserChartConstraintsFactory
import epic.trees.annotations.{AddMarkovization, StripAnnotations, PipelineAnnotator}
import epic.parser.models.IndexedLexFeaturizer
import epic.trees.ProcessedTreebank
import epic.parser.features.RuleFeature
import epic.parser.models.StandardLexFeaturizer
import breeze.optimize.FirstOrderMinimizer.OptParams
import epic.parser.models.LexGrammarBundle
import collection.mutable.ArrayBuffer
import collection.immutable
import epic.lexicon.SimpleLexicon
import epic.features.{HashFeature, MultiSurfaceFeaturizer, ContextSurfaceFeaturizer, StandardSurfaceFeaturizer}
import epic.constraints.{CachedChartConstraintsFactory, LabeledSpanConstraints}
import epic.util.CacheBroker
import java.util.concurrent.atomic.AtomicInteger
import com.typesafe.scalalogging.log4j.Logging


/**
 * 
 * @author dlwh
 */
object AnnotatingPipeline extends Logging {
  case class Params(corpus: File,
                    treebank: ProcessedTreebank,
                    implicit val cache: CacheBroker,
                    nfiles: Int = 100000,
                    nthreads: Int = -1,
                    iterPerEval: Int = 20,
                    baseParser: ParserParams.XbarGrammar,
                    weightsCache: File = new File("everything.weights.gz"),
                    trainBaseModels: Boolean = true,
                    checkGradient: Boolean = false,
                    includeSRL: Boolean = false,
                    includeParsing: Boolean = true,
                    includeNER: Boolean = true,
                    lexHashFeatures: Double = 2.0,
                    @Help(text="For optimizing the base models")
                    baseOpt: OptParams,
                    @Help(text="For optimizing the joint model")
                    opt: OptParams)

  def main(args: Array[String]) {

    val params = CommandLineParser.readIn[Params](args)
    logger.info("Parameters: " + params)
    require(params.corpus.exists(), params.corpus + " does not exist!")
    import params.cache


    val corpus = params.corpus
    val nfiles = params.nfiles
    val traintest = readTrainTestSplit(corpus, nfiles)
    val train = traintest._1
    val test = traintest._2

    val weightsCache = if (params.weightsCache.exists()) {
      loadWeights(params.weightsCache)
    } else {
      Counter[String, Double]()
    }

    val (docProcessor, processedTrain) =  buildProcessor(train, params.baseParser, weightsCache, params)


    val processedTest =  test.par.map(docProcessor(_)).seq.flatMap(_.sentences).toIndexedSeq

    logger.info(s"${processedTrain.length} training documents totalling ${processedTrain.flatMap(_.sentences).length} sentences.")
    logger.info(s"${processedTest.length} test sentences.")

    val beliefsFactory = new SentenceBeliefs.Factory(docProcessor.grammar, docProcessor.nerLabelIndex, docProcessor.srlLabelIndex, docProcessor.outsideSrlLabel)

    // now build the individual models
    type Datum = FeaturizedSentence
    type Augment = SentenceBeliefs
    type CompatibleModel = EvaluableModel[Datum] { type Inference <: ProjectableInference[Datum, Augment] with AnnotatingInference[Datum]}
    val models = ArrayBuffer[CompatibleModel]()
    if (params.includeParsing)
     models += makeLexParserModel(beliefsFactory, docProcessor, processedTrain, weightsCache, params.lexHashFeatures)
    if (params.includeNER)
       models += makeNERModel(beliefsFactory, docProcessor, processedTrain, weightsCache)
    if (params.includeSRL)
      models += makeSRLModel(beliefsFactory, docProcessor, processedTrain, weightsCache)


    if (params.trainBaseModels && params.checkGradient) {
       logger.info("Checking gradients...")
      for(m <- models) {

        logger.info("Checking " + m.getClass.getName)
        val obj = new ModelObjective(m, processedTrain.flatMap(_.sentences).filter(_.words.filter(_(0).isLetterOrDigit).length <= 40), params.nthreads)
        val cachedObj = new CachedBatchDiffFunction(obj)
        GradientTester.test(cachedObj, obj.initialWeightVector(randomize = true), randFraction = 1E-4, toString={(x:Int) => m.featureIndex.get(x).toString})
      }
    }

    // initial models
    if(params.trainBaseModels) {
      logger.info("Training base models")
      for(m <- models) {
        logger.info("Training " + m.getClass.getName)
        val obj = new ModelObjective(m, processedTrain.flatMap(_.sentences).filter(_.words.filter(_(0).isLetterOrDigit).length <= 40), params.nthreads)
        val cachedObj = new CachedBatchDiffFunction(obj)
        val weights = params.baseOpt.minimize(cachedObj, obj.initialWeightVector(randomize = false))
        updateWeights(params.weightsCache, weightsCache, Encoder.fromIndex(m.featureIndex).decode(weights))
        logger.info(s"Decoding $m...")
        logger.info(s"Evaluation result for $m: " + m.evaluate(processedTest, weights))
      }
    }

    // propagation

    val allModels = ArrayBuffer[EPModel.CompatibleModel[FeaturizedSentence, SentenceBeliefs]](models:_*)

    if(params.includeNER && params.includeParsing) {
      allModels += PropertyModels.nerSyntaxModel(docProcessor, beliefsFactory)
    }

    if(params.includeSRL && params.includeParsing) {
      allModels += PropertyModels.srlSyntaxModel(docProcessor, beliefsFactory)
    }

    if(params.includeSRL && params.includeNER) {
      allModels += PropertyModels.srlNerModel(docProcessor, beliefsFactory)
    }

    // the big model!
    val epModel = new EPModel[FeaturizedSentence, SentenceBeliefs](4, epInGold = true, initFeatureValue = {f => Some(weightsCache(f.toString)).filter(_ != 0.0)})(
      allModels: _*
    )


    val obj = new ModelObjective(epModel, processedTrain.flatMap(_.sentences).filter(_.words.filter(_(0).isLetterOrDigit).length <= 40), params.nthreads)
    val cachedObj = new CachedBatchDiffFunction(obj)

    if (params.checkGradient) {
      logger.info("Checking gradients...")
      GradientTester.test(cachedObj, obj.initialWeightVector(randomize = true), randFraction = 1E-1, toString={(x:Int) => epModel.featureIndex.get(x).toString})
    }

    type OptState = FirstOrderMinimizer[DenseVector[Double], BatchDiffFunction[DenseVector[Double]]]#State
    val opt = params.opt
    for( s:OptState <- opt.iterations(cachedObj, obj.initialWeightVector(randomize = false))) {
      //      val (unregularized, deriv) = obj.calculate(s.x)
      //      bump(unregularized, deriv, s, 1000)
      //      bump(unregularized, deriv, s, 12000)
      //      bump(unregularized, deriv, s, featureICareAbout)
      //      bump(unregularized, deriv, s, featureICareAbout + 1)

      if( s.iter % params.iterPerEval == 0) {
        updateWeights(params.weightsCache, weightsCache, Encoder.fromIndex(epModel.featureIndex).decode(s.x))
        val inf = epModel.inferenceFromWeights(s.x)
        val results: immutable.Seq[immutable.IndexedSeq[EvaluationResult[_]]] = {for (d <- processedTest.par) yield {
          val epMarg = inf.marginal(d)
          for ( i <- 0 until epMarg.marginals.length) yield {
            epModel.models(i) match {
              case m: EvaluableModel[FeaturizedSentence] =>
                val casted =  inf.inferences(i).asInstanceOf[AnnotatingInference[FeaturizedSentence]]
                val newDoc = casted.annotate(d, epMarg.marginals(i).asInstanceOf[casted.Marginal])
                Some(m.evaluate(newDoc, d, true))
              case _ => None
            }
          }}.flatMap(_.iterator)
        }.seq

        val hacketyHack = results.toIndexedSeq.transpose.map(_.reduce{ (a: Object, b: Object) =>
          val aa = a.asInstanceOf[{def +(other: EvaluationResult[_]):EvaluationResult[_]}]
          (aa + b.asInstanceOf[EvaluationResult[_]]).asInstanceOf[Object]
        })

        logger.info("Joint results:")
        for ( (result, model) <- hacketyHack zip epModel.models.filter(_.isInstanceOf[EvaluableModel[FeaturizedSentence]])) {
          logger.info(model.getClass.getName + ": " + result )
        }
      }

    }

  }


  def readTrainTestSplit(corpus: File, nfiles: Int): (IndexedSeq[Document], IndexedSeq[Document]) = {

    val instances = for {
      file <- corpus.listFiles.sortBy(_.getName) take nfiles
      doc <- ConllOntoReader.readDocuments(file)
    } yield doc
    val train = instances.take(instances.length * 9 / 10)
    val test = instances.drop(instances.length * 9 / 10)
    (train.toIndexedSeq, test.toIndexedSeq)

  }


  def buildProcessor(docs: IndexedSeq[Document], xbar: XbarGrammar, weightsCache: Counter[String, Double], params: AnnotatingPipeline.Params)(implicit broker: CacheBroker): (FeaturizedDocument.Factory, IndexedSeq[FeaturizedDocument]) = {
    val train = docs.flatMap(_.sentences.map(_.nerSegmentation))
    val maxLengthMap = train.flatMap(_.segments.iterator).groupBy(_._1).mapValues(arr => arr.map(_._2.length).max)
    val labelIndex = Index(Iterator(NERType.OutsideSentence, NERType.NotEntity) ++ train.iterator.flatMap(_.label.map(_._1)))
    val maxLengthArray: Array[Int] = Encoder.fromIndex(labelIndex).tabulateArray(maxLengthMap.getOrElse(_, 0))
    logger.info("Max lengths for NER segments: " + maxLengthMap)
    val nerCounts: Counter2[NERType.Value, String, Double] = Counter2.count(train.map(_.asFlatTaggedSequence(NERType.NotEntity)).map{seg => seg.label zip seg.words}.flatten).mapValues(_.toDouble)
    val nerLexicon = new SimpleLexicon(labelIndex, nerCounts, openTagThreshold = 10, closedWordThreshold = 20)
    val allowedNerClassifier = new LabeledSpanConstraints.LayeredTagConstraintsFactory(nerLexicon, maxLengthArray)

    logger.info("Building basic parsing model...")
    val trainTrees = for (d <- docs; s <- d.sentences) yield {
      params.treebank.makeTreeInstance(s.id, s.tree.map(_.label), s.words, removeUnaries = true)
    }

    val annotator = new PipelineAnnotator[AnnotatedLabel, String](Seq(StripAnnotations(), AddMarkovization()))
    val pruningParser =  GenerativeParser.annotated(xbar, annotator, trainTrees)
    val parseConstrainer = new CachedChartConstraintsFactory(new ParserChartConstraintsFactory(pruningParser.augmentedGrammar, {(_:AnnotatedLabel).isIntermediate}))
    logger.info("Building constraints")
    val count = new AtomicInteger(0)
    trainTrees.par.foreach{t =>
      parseConstrainer.constraints(t.words)
      val c = count.incrementAndGet()
      if(c % 100 == 0 || c == trainTrees.length)
        logger.info(s"$c/${trainTrees.length} sentences parsed.")
    }
    broker.commit()


    val standardFeaturizer = new StandardSurfaceFeaturizer(sum(nerCounts, Axis._0))
    val featurizers =new ContextSurfaceFeaturizer[String](standardFeaturizer, 3, 3)
    val featurizer = new MultiSurfaceFeaturizer[String](featurizers)

    FeaturizedDocument.makeFactory(params.treebank.process,
    featurizer,
     pruningParser.grammar, pruningParser.lexicon,
      parseConstrainer,
      labelIndex,
      allowedNerClassifier, null)(docs)
  }


  private def makeNERModel(beliefsFactory: SentenceBeliefs.Factory, processor: FeaturizedDocument.Factory, docs: IndexedSeq[FeaturizedDocument], weightsCache: Counter[String, Double]) = {
    new ChainNER.ModelFactory(beliefsFactory, processor, weights={(f: Feature)=>Some(weightsCache(f.toString)).filter(_ != 0)}).makeModel(docs.flatMap(_.sentences))
  }


  private def makeSRLModel(beliefsFactory: SentenceBeliefs.Factory, processor: FeaturizedDocument.Factory, docs: IndexedSeq[FeaturizedDocument], weightsCache: Counter[String, Double]) = {
    new SRL.ModelFactory(beliefsFactory, processor, weights={(f: Feature)=>Some(weightsCache(f.toString)).filter(_ != 0)}).makeModel(docs.flatMap(_.sentences))
  }

  def makeLexParserModel(beliefsFactory: SentenceBeliefs.Factory,
                         docProcessor: FeaturizedDocument.Factory,
                         train: IndexedSeq[FeaturizedDocument],
                         weightsCache: Counter[String, Double],
                         lexHashFeatures: Double): SentLexParser.Model = {
    val trainTrees = train.flatMap(_.sentences).map(_.treeInstance)
    val trees = trainTrees.map(StripAnnotations())
    val (initLexicon, initBinaries, initUnaries) = GenerativeParser.extractCounts(trees)

    val wordIndex: Index[String] = Index(trainTrees.iterator.flatMap(_.words))

    def ruleGen(r: Rule[AnnotatedLabel]) = IndexedSeq(RuleFeature(r))

    val headFinder = HeadFinder.collins
    val feat = new StandardLexFeaturizer(
      docProcessor.grammar.labelIndex,
      docProcessor.grammar.index,
      ruleGen
    )

    val indexed = IndexedLexFeaturizer.extract[AnnotatedLabel, TreeInstance[AnnotatedLabel, String], String](feat,
      docProcessor.featurizer,
      headFinder,
      docProcessor.grammar.index,
      docProcessor.grammar.labelIndex,
//    2,
      HashFeature.Relative(lexHashFeatures),
      trees)

    val bundle = new LexGrammarBundle[AnnotatedLabel, String](docProcessor.grammar,
      docProcessor.lexicon,
      headFinder,
      wordIndex
    )

    def reannotate(tree: BinarizedTree[AnnotatedLabel], words: Seq[String]) = tree.map(_.baseAnnotatedLabel)
    new SentLexParser.Model(beliefsFactory, bundle, reannotate, indexed, {(f: Feature) => Some(weightsCache(f.toString))})
  }


  private def updateWeights(out: File, weightsCache: Counter[String, Double], newWeights: Counter[Feature, Double]) {
    for ( (f,w) <- newWeights.activeIterator) {
      weightsCache(f.toString) = w
    }
//    if(out.exists()) {
//     .copy(out, new File(out.toString +".backup"))
//    }
    breeze.util.writeObject(out, weightsCache)
  }

  private def loadWeights(in: File) = {
    breeze.util.readObject[Counter[String,Double]](in)
  }
}