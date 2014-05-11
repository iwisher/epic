package epic.util

import java.io.File
import breeze.config.{CommandLineParser, Help}
import breeze.util._
import chalk.text.LanguagePack
import epic.preprocess.TreebankTokenizer
import scala.io.{Codec, Source}
import epic.sequences.CRF
import java.util.concurrent.{LinkedBlockingDeque, TimeUnit, ThreadPoolExecutor}

/**
 * TODO
 *
 * @author dlwh
 **/
trait ProcessTextMain[Model, AnnotatedType] {
  import ProcessTextMain._

  def render(model: Model, ann: AnnotatedType, tokens: IndexedSeq[String]):String

  def renderFailed(model: Model, tokens: IndexedSeq[String], reason: Throwable): String = {
    s"### Could not tag $tokens, because ${reason.getMessage}... ${reason.getStackTrace.take(2).mkString(";")}".replaceAll("\n", " ")
  }

  def annotate(model: Model, text: IndexedSeq[String]):AnnotatedType

  def main(args: Array[String]) = {
    val (baseConfig, files) = CommandLineParser.parseArguments(args)
    val config = baseConfig
    val params: Params = try {
      config.readIn[Params]("")
    } catch {
      case e:Exception =>
        e.printStackTrace()
        System.err.println(breeze.config.GenerateHelp[Params](config))
        sys.exit(1)
    }

    val model = readObject[Model](params.model)

    val sentenceSegmenter = LanguagePack.English.sentenceSegmenter
    val tokenizer = new TreebankTokenizer

    implicit val context = if(params.threads > 0) {
      scala.concurrent.ExecutionContext.fromExecutor(new ThreadPoolExecutor(1, params.threads, 1, TimeUnit.SECONDS, new LinkedBlockingDeque[Runnable]()))
    } else {
      scala.concurrent.ExecutionContext.global
    }

    val iter = if(files.length == 0) Iterator(Source.fromInputStream(System.in)) else files.iterator.map(Source.fromFile(_)(Codec.UTF8))

    for(src <- iter) {
      val text = src.mkString
      val queue = FIFOWorkQueue(sentenceSegmenter(text)){sent =>
        val tokens = tokenizer(sent).toIndexedSeq
        try {
          if(tokens.length > params.maxLength) {
            throw new SentenceTooLongException(tokens.length)
          }
          val tree = annotate(model, tokens)
          render(model, tree, tokens)
        } catch {
          case e: Exception =>
           renderFailed(model, tokens, e)
        }
      }
      for(result <- queue) {
        println(result)
      }
      src.close()

    }
  }

}

object ProcessTextMain {
  case class Params(model: File,
                    @Help(text="How many threads to parse with. Default is whatever Scala wants")
                    threads: Int = -1,
                    maxLength: Int = 1000)

  case class SentenceTooLongException(length: Int) extends Exception("Sentence too long: " + length)
}
