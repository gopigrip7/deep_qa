package org.allenai.semparse.pipeline.science_data

import com.mattg.pipeline.Step
import com.mattg.util.FileUtil
import com.mattg.util.JsonHelper

import org.json4s._

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext

import org.allenai.semparse.parse.Conjunction
import org.allenai.semparse.parse.DependencyTree
import org.allenai.semparse.parse.Logic
import org.allenai.semparse.parse.LogicalFormGenerator
import org.allenai.semparse.parse.Predicate
import org.allenai.semparse.parse.StanfordParser

import java.io.File

import scala.concurrent._
import scala.util.{Success, Failure}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Here we convert sentences into logical forms, using the LogicalFormGenerator.
 *
 * INPUTS: a file containing sentences, one sentence per line.  The line could be multi-column; we
 * just output the line as is, with an additional column containing the logical form.  The actual
 * sentence must be in the first column, or the first column must be an integer, and with the
 * second column containing the sentence.
 *
 * OUTPUTS: a file identical to the second one, with an additional column containing logical forms.
 * Optionally, we can drop the line if the logical form generation failed for that line.
 */
class SentenceToLogic(
  params: JValue,
  fileUtil: FileUtil
) extends Step(Some(params), fileUtil) {
  implicit val formats = DefaultFormats
  override val name = "Sentence to Logic"

  val validParams = Seq("sentences", "logical forms", "output file", "drop errors")
  JsonHelper.ensureNoExtras(params, name, validParams)

  val dropErrors = JsonHelper.extractWithDefault(params, "drop errors", true)

  val sentencesInput = SentenceToLogic.getSentencesInput(params \ "sentences", fileUtil)
  val sentencesFile = sentencesInput._1
  val logicalFormGenerator = new LogicalFormGenerator(params \ "logical forms")

  val outputFile = JsonHelper.extractAsOption[String](params, "output file") match {
    case None => {
      new File(sentencesFile).getParent() + "logical_forms.tsv"
    }
    case Some(filename) => filename
  }

  val numPartitions = 1

  override val inputs: Set[(String, Option[Step])] = Set(sentencesInput)
  override val outputs = Set(outputFile)
  override val paramFile = outputs.head.dropRight(4) + "_params.json"
  override val inProgressFile = outputs.head.dropRight(4) + "_in_progress"

  override def _runStep() {
    val conf = new SparkConf().setAppName(s"Sentence to Logic")
      .set("spark.driver.maxResultSize", "0")
      .set("spark.network.timeout", "100000")
      .set("spark.akka.frameSize", "1028")
      .setMaster("local[*]")

    val sc = new SparkContext(conf)

    parseSentences(sc, logicalFormGenerator, outputFile)

    sc.stop()
  }

  def parseSentences(sc: SparkContext, logicalFormGenerator: LogicalFormGenerator, outputFile: String) {
    fileUtil.mkdirsForFile(outputFile)
    val lines = sc.textFile(sentencesFile)
    val sentences = lines.map(SentenceToLogic.getSentenceFromLine)
    val trees = sentences.map(SentenceToLogic.parseSentence)
    val logicalForms = trees.flatMap(sentenceAndTree => {
      val result = SentenceToLogic.runWithTimeout(2000, () => {
        val sentence = sentenceAndTree._1._1
        val line = sentenceAndTree._1._2
        val tree = sentenceAndTree._2
        val logicalForm = try {
          tree.flatMap(logicalFormGenerator.getLogicalForm)
        } catch {
          case e: Throwable => { println(sentence); tree.map(_.print()); throw e }
        }
        (sentence, line, logicalForm)
      })
      result match {
        case None => {
          println(s"Timeout while processing sentence: ${sentenceAndTree._1._1}")
          if (dropErrors) Seq() else Seq((sentenceAndTree._1._1, sentenceAndTree._1._2, None))
        }
        case Some(either) => either match {
          case Left(t) => {
            println(s"Exception thrown while processing sentence: ${sentenceAndTree._1._1} ---- ${t.getMessage}")
            if (dropErrors) Seq() else Seq((sentenceAndTree._1._1, sentenceAndTree._1._2, None))
          }
          case Right(result) => Seq(result)
        }
      }
    })
    val outputStrings = logicalForms.flatMap(sentenceAndLf => {
      val result = SentenceToLogic.runWithTimeout(2000, () => {
        SentenceToLogic.sentenceAndLogicalFormAsString(sentenceAndLf)
      })
      result match {
        case None => {
          println(s"Timeout while printing sentence: ${sentenceAndLf._1}")
          Seq()
        }
        case Some(either) => either match {
          case Left(t) => {
            println(s"Exception thrown while printing sentence: ${sentenceAndLf._1} ---- ${t.getMessage}")
            Seq()
          }
          case Right(result) => result
        }
      }
    })

    val finalOutput = outputStrings.collect()
    fileUtil.writeLinesToFile(outputFile, finalOutput)
  }
}

// This semi-ugliness is so that the spark functions are serializable.
object SentenceToLogic {
  val parser = new StanfordParser

  def getSentencesInput(params: JValue, fileUtil: FileUtil): (String, Option[Step]) = {
    val sentenceSelector = new SentenceSelectorStep(params \ "sentence selector step", fileUtil)
    // TODO(matt): I need a "type" parameter here, which says whether we get the sentences from a
    // sentence selector, or a sentence corrupter, or somewhere else.
    ("fake", None)
  }

  def runWithTimeout[T](milliseconds: Long, f: () => T): Option[Either[Throwable, T]] = {
    import scala.language.postfixOps
    val result = Future(f())
    try {
      Await.result(result, 1 seconds)
      result.value match {
        case None => None
        case Some(tryResult) => tryResult match {
          case Success(result) => Some(Right(result))
          case Failure(t) => {
            Some(Left(t))
          }
        }
      }
    } catch {
      case e: java.util.concurrent.TimeoutException => {
        None
      }
    }
  }

  def getSentenceFromLine(line: String): (String, String) = {
    val fields = line.split("\t")
    if (fields.length > 1 && fields(0).forall(Character.isDigit)) {
      (fields(1), line)
    } else {
      (fields(0), line)
    }
  }

  def parseSentence(sentenceAndLine: (String, String)): ((String, String), Option[DependencyTree]) = {
    val parse = parser.parseSentence(sentenceAndLine._1)
    val tree = parse.dependencyTree.flatMap(tree => if (shouldKeepTree(tree)) Some(tree) else None)
    (sentenceAndLine, tree)
  }

  def shouldKeepTree(tree: DependencyTree): Boolean = {
    if (tree.token.posTag.startsWith("V")) {
      tree.getChildWithLabel("nsubj") match {
        case None => return false
        case _ => return true
      }
    }
    tree.getChildWithLabel("cop") match {
      case None => return false
      case _ => return true
    }
  }

  def sentenceAndLogicalFormAsString(input: (String, String, Option[Logic])): Seq[String] = {
    val sentence = input._1
    val line = input._2
    val logicalForm = input._3
    val lfString = logicalForm.map(_.toString).mkString(" ")
    Seq(s"${line}\t${lfString}")
  }
}
