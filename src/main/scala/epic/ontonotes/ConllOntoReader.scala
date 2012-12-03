package epic.ontonotes

/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
import io.Source
import collection.{IndexedSeq, Iterator}
import java.lang.String
import epic.trees.{AnnotatedLabel, Tree}
import collection.mutable.{Stack, ArrayBuffer}
import java.io.File
import epic.everything._

/**
 * Reads the Conll 2011 shared task format. See http://conll.cemantix.org/2011/data.html
 * @author dlwh
 */

object ConllOntoReader {
  def readDocuments(file: File):IndexedSeq[Document] = {
    val docIterator = new RawDocumentIterator(Source.fromFile(file).getLines())
    for ( (rawSentences_ :IndexedSeq[IndexedSeq[String]], docIndex: Int) <- docIterator.zipWithIndex.toIndexedSeq) yield {
      val rawSentences = rawSentences_.collect { case seq if seq.nonEmpty =>
        seq.map(_.split("\\s+").toIndexedSeq)
      }

    val sentences = for( (s,index) <- rawSentences.zipWithIndex) yield {
      val words = s.map(_(3))
      val tags = s.map(_(4))

      val stringTree =  {
        val parseBits = s.map(_(5))
        val b = new StringBuilder()
        for(i <- 0 until parseBits.length) {
          b ++= parseBits(i).replace("*","( "+ tags(i) + " " + words(i) + " )")
        }
        Tree.fromString(b.toString)._1
      }

      val entities = collection.mutable.Map[(Int,Int), NERType.Value]()
      var currentChunkStart = -1
      var currentChunkType = NERType.NotEntity
      for(i <- 0 until s.length) {
        val chunk = s(i)(10)
        if(chunk.startsWith("(")) {
          assert(currentChunkStart < 0)
          currentChunkStart = i
          currentChunkType = NERType.fromString(chunk.replaceAll("[()*]",""))
        }

        if(chunk.endsWith(")")) {
          assert(currentChunkStart >= 0)
          entities += ((currentChunkStart -> (i+1)) -> currentChunkType)
          currentChunkStart = -1
        }
      }

      // TODO: lemmas
      // TODO: SRL

      val mentions = collection.mutable.Map[(Int,Int), Mention]()
      // stupid nested mentions. It's not quite a stack. I don't know why they did it this way.
      // (entity id -> stack of open parens for that id
      val stack = new collection.mutable.HashMap[Int, Stack[Int]]() {
        override def default(key: Int) = getOrElseUpdate(key,new Stack())
      }
      for(i <- 0 until s.length) {
        val chunk = s(i).last
        if(chunk != "-")
          for( id <- chunk.split("\\|")) {
            val tid = id.replaceAll("[()*]","").toInt
            if(id.startsWith("(")) {
              stack(tid).push(i)
            }
            if(id.endsWith(")")) {
              val start = stack(tid).pop()
              mentions(start -> (i+1)) = Mention(tid)
            }
          }
      }

      val docId = file.getName + "-" + docIndex
      val tree = stringTree.extend { t => AnnotatedLabel(t.label) }
      val ner = Map.empty ++ entities.map { case ((beg,end),v) => DSpan(docId,index,beg,end) -> v}
      val coref = Map.empty ++ mentions.map { case ((beg,end),v) => DSpan(docId,index,beg,end) -> v}
      val speaker = s.map(_(9)).find(_ != "-")
      val annotations = OntoAnnotations(tree, ner, coref, speaker)




      Sentence(docId, index,words, annotations)
    }

      Document(file.toString + "-" + docIndex,sentences.toIndexedSeq)
    }
  }

  private class RawDocumentIterator(it: Iterator[String]) extends Iterator[IndexedSeq[IndexedSeq[String]]] {
    def hasNext = it.hasNext

    def next():IndexedSeq[IndexedSeq[String]] = {
      var doneOuter = false
      val outBuf = new ArrayBuffer[IndexedSeq[String]]
      while(it.hasNext && !doneOuter) {
        val buf = new ArrayBuffer[String]
        var done = false
        var seenSomethingNotBlank = false
        while(it.hasNext && !done) {
          val next = it.next()
          if(next.startsWith("#begin")) {
            // pass
          } else if(next.startsWith("#end")) {
            doneOuter = true
          } else if(next.trim != "") {
            seenSomethingNotBlank = true
            buf += next.trim
          } else if(seenSomethingNotBlank) {
            done = true
          }
        }
        outBuf += buf
      }
      outBuf
    }
  }
}
