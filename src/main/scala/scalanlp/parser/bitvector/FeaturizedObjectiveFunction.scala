package scalanlp.parser.bitvector

import scalala.Scalala._;
import scalala.tensor.Vector;
import scalala.tensor.adaptive.AdaptiveVector;
import scalala.tensor.sparse.SparseVector;
import scalala.tensor.dense.DenseVector;
import scalala.tensor.counters.Counters
import scalala.tensor.counters.Counters.DoubleCounter
import scalala.tensor.counters.Counters.PairedDoubleCounter
import scalala.tensor.counters.LogCounters
import scalala.tensor.counters.LogCounters.LogDoubleCounter
import scalala.tensor.counters.LogCounters.LogPairedDoubleCounter
import scalala.tensor.dense.DenseMatrix
import scalala.tensor.dense.DenseVector
import scalanlp.optimize.DiffFunction
import scalanlp.optimize.LBFGS
import scalanlp.util.ConsoleLogging
import scalanlp.util.Index
import scalanlp.math.Numerics;
import scalanlp.collection.mutable.Grid2
import scalanlp.collection.mutable.SparseArray
import scalanlp.concurrent.ThreadPoolRunner
import scalanlp.data.VectorBroker
import scalanlp.util.Log

abstract class FeaturizedObjectiveFunction extends DiffFunction[Int,DenseVector]  {
  type Context;
  type Decision;
  type Feature;

  protected def decisionsForContext(c: Context): Iterator[Decision]
  protected def allContexts: Iterator[Context]
  protected def features(d: Decision, c: Context):Seq[Feature];
  protected def initialFeatureWeight(f: Feature):Double;
  /** Should compute marginal likelihood and expected counts for the data */
  protected def expectedCounts(logThetas: LogPairedDoubleCounter[Context,Decision]):(Double,PairedDoubleCounter[Context,Decision]);

  val contextIndex: Index[Context] = Index(allContexts);
  protected val contextBroker = VectorBroker.fromIndex(contextIndex);

  val (decisionIndex,indexedDecisionsForContext:Seq[Seq[Int]]) = {
    val decisionIndex = Index[Decision];
    val indexedDecisionsForContext = contextBroker.mkArray[Seq[Int]];
    for( (c,cI) <- contextIndex.pairs) {
      indexedDecisionsForContext(cI) = scala.util.Sorting.stableSort(decisionsForContext(c).map(decisionIndex.index _).toSeq);
    }
    (decisionIndex,indexedDecisionsForContext:Seq[Seq[Int]]);
  }
  protected val decisionBroker = VectorBroker.fromIndex(decisionIndex);

  // feature grid is contextIndex -> decisionIndex -> Seq[feature index]
  val (featureIndex: Index[Feature], featureGrid: Array[SparseArray[Array[Int]]]) = {
    val index = Index[Feature]();
    val grid = contextBroker.fillArray(decisionBroker.fillSparseArray(Array[Int]()));
    for(cI <- 0 until contextIndex.size;
        c = contextIndex.get(cI);
        dI <- indexedDecisionsForContext(cI)) {
      val d = decisionIndex.get(dI);
      val f = features(d,c);
      if(!f.isEmpty) {
        grid(cI)(dI) = scala.util.Sorting.stableSort(f.map{index.index _});
      }
    }
    (index,grid:Array[SparseArray[Array[Int]]]);
  }

  /*
  {
    for((dIs,cI) <- featureGrid.zipWithIndex;
        c = contextIndex.get(cI);
        (dI,features) <- dIs) {
      val d = decisionIndex.get(dI);
      println( (c,d) -> features.map(featureIndex.get _ ));
    }
  }
  */

  val initWeights = Counters.aggregate(featureIndex.map{ f => (f,initialFeatureWeight(f))});
  val initIndexedWeights = VectorBroker.fromIndex(featureIndex).encodeDense(initWeights);

  private def decodeFeatures(m: DenseVector): DoubleCounter[Feature] = VectorBroker.fromIndex(featureIndex).decode(m);

  private def encodeCounts(eCounts: PairedDoubleCounter[Context,Decision]): (Array[Vector],Array[Double]) = {
    val encCounts = contextBroker.mkArray[Vector];
    val totals = contextBroker.fillArray(Double.NegativeInfinity);
    for( (c,ctr) <- eCounts.rows;
         cI = contextIndex(c);
         encCtr = decisionBroker.encode(ctr)
       ) {
      encCounts(cI) = encCtr;
      totals(cI) = ctr.total;
    }

    (encCounts,totals);
  }

  private def decodeThetas(m: Array[Vector]): LogPairedDoubleCounter[Context,Decision] = {
    val result = LogPairedDoubleCounter[Context,Decision];
    for( (vec,cI) <- m.iterator.zipWithIndex) {
      result(contextIndex.get(cI)) := decisionBroker.decode(vec);
    }
    result;
  }

  private def computeLogThetas(weights: DenseVector) = {
    val thetas = contextBroker.mkArray[Vector];
    for((dIs,cI) <- featureGrid.zipWithIndex) {
      thetas(cI) = decisionBroker.mkVector(Double.NegativeInfinity);
      for((dI,features) <- dIs) {
        val score = sumWeights(features,weights);
        thetas(cI)(dI) = score;
      }
    }
    logNormalizeRows(thetas);
  }

  private def logNormalizeRows(thetas: Array[Vector])  = {
    for( (arr,c) <- thetas.zipWithIndex) arr.asInstanceOf[AdaptiveVector].innerVector match {
    case v: DenseVector =>
      val max = v.data.filter(_ !=0).reduceLeft(_ max _);
      val logSum = Numerics.logSum(v.data.iterator,max);
      v -= logSum;
    case v: SparseVector =>
      val max = v.data.take(v.used).reduceLeft(_ max _);
      val logSum = Numerics.logSum(v.data.iterator.take(v.used),max);
      v -= logSum;
    }
    thetas;
  }

  private def sumWeights(indices: Array[Int], weights: DenseVector) = {
    var i = 0;
    var sum = 0.0;
    while(i < indices.length) {
      val f = indices(i);
      sum += weights(f);
      i += 1;
    }
    sum;
  }

  override def calculate(weights: DenseVector) = {
    val encodedThetas = computeLogThetas(weights);
    val logThetas = decodeThetas(encodedThetas);
    val (marginalLogProb,eCounts) = expectedCounts(logThetas);

    val (encodedCounts,encodedTotals) = encodeCounts(eCounts);
    val (expCompleteLogProb,grad) = computeGradient(encodedThetas, encodedCounts, encodedTotals);
    (-marginalLogProb,grad);
  }

  // computes expComplete log Likelihood and gradient
  private def computeGradient(logThetas: Array[Vector], eCounts: Array[Vector], eTotals: Array[Double]) = {
    // gradient is \sum_{d,c} e(d,c) * (f(d,c) - \sum_{d'} exp(logTheta(c,d')) f(d',c))
    // = \sum_{d,c} (e(d,c)  - e(*,c) exp(logTheta(d,c))) f(d,c)
    // = \sum_{d,c} margin(d,c) * f(d,c)
    //
    // e(*,c) = \sum_d e(d,c) == eCounts(c).total
    val featureGrad = VectorBroker.fromIndex(featureIndex).mkDenseVector(0.0);
    var logProb = 0.0;

    for( (vec,c) <- eCounts.zipWithIndex) {
      val cTheta = logThetas(c);
      val logTotal = Math.log(eTotals(c));
      val vec2 = vec match {
        case v: AdaptiveVector => v.innerVector;
        case v => v
      }
      vec2 match {
        case vec: SparseVector =>
          var i = 0;
          while(i < vec.used) {
            val d = vec.index(i);
            val e = vec.data(i);
            val lT = cTheta(d);
            logProb += e * lT;

            val margin = e - Math.exp(logTotal + lT);

            var j = 0;
            val grid = featureGrid(c)(d);
            while(j < grid.size) {
              val f = grid(j);
              featureGrad(f) += margin;
              j += 1;
            }
            i += 1;
          }
        case _ =>
          for((d,e) <- vec.activeElements) {
            val lT = cTheta(d);
            logProb += e * lT;

            val margin = e - Math.exp(logTotal + lT);

            for( f <- featureGrid(c)(d))
              featureGrad(f) += margin;
          }
      }
    }


    (-logProb,-featureGrad value);
  }

  class mStepObjective(eCounts: PairedDoubleCounter[Context,Decision]) extends DiffFunction[Int,DenseVector]   {
    val (encodedCounts,encodedTotals) = encodeCounts(eCounts);
    override def calculate(weights: DenseVector) = {
      val logThetas = computeLogThetas(weights);
      computeGradient(logThetas,encodedCounts,encodedTotals);
    }

  }

  final case class State(encodedWeights: DenseVector, marginalLikelihood: Double) {
    lazy val logThetas = decodeThetas(computeLogThetas(encodedWeights));
  }

  def emIterations(initialWeights: DoubleCounter[Feature] = initWeights, maxMStepIterations: Int=90): Iterator[State] = {
    val log = Log.globalLog;

    val optimizer = new LBFGS[Int,DenseVector](maxMStepIterations,5) with ConsoleLogging;
    val weightsIterator = Iterator.iterate(State(initIndexedWeights,Double.NegativeInfinity)) { state =>
      log(Log.INFO)("E step");
      val (marginalLogProb,eCounts) = expectedCounts(state.logThetas);
      log(Log.INFO)("M step");
      val obj = new mStepObjective(eCounts);
      val newWeights = optimizer.minimize(obj, state.encodedWeights);
      val nrm = norm(state.encodedWeights - newWeights,2) / newWeights.size;
      log(Log.INFO)("M Step finished: " + nrm);
      State(newWeights,marginalLogProb);
    }

    weightsIterator drop 1 // initial iteration is crap
  }


}
