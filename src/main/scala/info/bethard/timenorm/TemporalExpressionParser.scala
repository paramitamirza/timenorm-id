package info.bethard.timenorm

import java.io.File
import java.net.URL
import java.util.logging.Logger

import scala.collection.immutable.IndexedSeq
import scala.io.Source
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.threeten.bp.DateTimeException
import org.threeten.bp.LocalDate
import org.threeten.bp.temporal.IsoFields.QUARTER_YEARS

import info.bethard.timenorm.parse.TemporalParse
import info.bethard.timenorm.parse.PeriodParse
import info.bethard.timenorm.parse.PeriodSetParse
import info.bethard.timenorm.parse.TimeSpanParse
import info.bethard.timenorm.parse.TimeSpanSetParse

import java.lang.StringBuilder;

object TemporalExpressionParser {
  
  /**
   * Runs a demo of TemporalExpressionParser that reads time expressions from standard input and
   * writes their normalized forms to standard output.
   *
   * Note: This is only provided for demonstrative purposes.
   */
  def main(args: Array[String]): Unit = {

    // create the parser, using a grammar file if specified
    val parser = args match {
      case Array() =>
        new TemporalExpressionParser
      case Array(grammarPath) =>
        new TemporalExpressionParser(new File(grammarPath).toURI.toURL)
      case _ =>
        System.err.printf("usage: %s [grammar-file]", this.getClass.getSimpleName)
        System.exit(1)
        throw new IllegalArgumentException
    }

    // use the current date as an anchor
    val now = LocalDate.now()
    val anchor = TimeSpan.of(now.getYear, now.getMonthValue, now.getDayOfMonth)
    System.out.printf("Assuming anchor: %s\n", anchor.timeMLValue)
    System.out.println("Type in a time expression (or :quit to exit)")

    // repeatedly prompt for a time expression and then try to parse it
    System.out.print(">>> ")
    for (line <- Source.stdin.getLines.takeWhile(_ != ":quit")) {
      parser.parse(line, anchor) match {
        case Failure(exception) =>
          System.out.printf("Error: %s\n", exception.getMessage)
        case Success(temporal) =>
          System.out.println(temporal.timeMLValue)
      }
      System.out.print(">>> ")
    }
  }
}

/**
 * A parser for natural language expressions of time, based on a synchronous context free grammar.
 * Typical usage:
 * {{{
 * // create a new parser (using the default English grammar)
 * val parser = new TemporalExpressionParser
 * // establish an anchor time
 * val anchor = TimeSpan.of(2013, 1, 4)
 * // parse an expression given an anchor time (assuming here that it succeeeds)
 * val Success(temporal) = parser.parse("two weeks ago", anchor)
 * // get the TimeML value ("2012-W51") from the Temporal
 * val value = temporal.timeMLValue
 * }}}
 *
 * @constructor Creates a parser from a URL to a grammar file.
 * @param grammarURL The URL of a grammar file, in [[SynchronousGrammar.fromString]] format. If not
 *        specified, the default English grammar on the classpath is used. Note that if another
 *        grammar is specified, it may be necessary to override the [[tokenize]] method.
 */
class TemporalExpressionParser(grammarURL: URL = classOf[TemporalExpressionParser].getResource("/info/bethard/timenorm/id.grammar")) {
  private val logger = Logger.getLogger(this.getClass.getName)
  //private val grammarText = Source.fromURL(grammarURL, "US-ASCII").mkString
  private val grammarText = Source.fromURL(grammarURL, "UTF-8").mkString //Paramita: Italian needs UTF-8
  private val grammar = SynchronousGrammar.fromString(grammarText)
  private val sourceSymbols = grammar.sourceSymbols()
  private val parser = new SynchronousParser(grammar)

  private final val wordBoundary = "\\b".r
  private final val letterNonLetterBoundary = "(?<=[^\\p{L}])(?=[\\p{L}])|(?<=[\\p{L}])(?=[^\\p{L}])".r
  
  // Paramita: special function for splitting numbers in Italian
  protected def tokenizeItalianNumber(word: String): Seq[String] = {
    if (word.isEmpty) {
      Seq.empty[String]
    } else if (word.matches("^[Dd]ue(cen|mil)\\w+$")) {
      Seq("due") ++ this.tokenizeItalianNumber(word.substring(3).toLowerCase)
    } else if (word.matches("^[Tt]re(cen|mil)\\w+$")) {
      Seq("tre") ++ this.tokenizeItalianNumber(word.substring(3).toLowerCase)
    } else if (word.matches("^[Qq]uattro(cen|mil)\\w+$")) {
      Seq("quattro") ++ this.tokenizeItalianNumber(word.substring(7).toLowerCase)
    } else if (word.matches("^[Cc]inque(cen|mil)\\w+$")) {
      Seq("cinque") ++ this.tokenizeItalianNumber(word.substring(6).toLowerCase)
    } else if (word.matches("^[Ss]ei(cen|mil)\\w+$")) {
      Seq("sei") ++ this.tokenizeItalianNumber(word.substring(3).toLowerCase)
    } else if (word.matches("^[Ss]ette(cen|mil)\\w+$")) {
      Seq("sette") ++ this.tokenizeItalianNumber(word.substring(5).toLowerCase)
    } else if (word.matches("^[Oo]tto(cen|mil)\\w+$")) {
      Seq("otto") ++ this.tokenizeItalianNumber(word.substring(4).toLowerCase)
    } else if (word.matches("^[Nn]ove(cen|mil)\\w+$")) {
      Seq("nove") ++ this.tokenizeItalianNumber(word.substring(4).toLowerCase)
    } else if (word.matches("^[Cc]ento\\w+$")) {
      Seq("cento") ++ this.tokenizeItalianNumber(word.substring(5).toLowerCase)
    } else if (word.matches("^[Mm]ille\\w+$")) {
      Seq("mille") ++ this.tokenizeItalianNumber(word.substring(5).toLowerCase)
    } else if (word.matches("^[Mm]ila\\w+$")) {
      Seq("mila") ++ this.tokenizeItalianNumber(word.substring(4).toLowerCase)
    } else if (word.matches("^[Vv]ent\\w+$")) {
      if (word.matches("^[Vv]enti\\w*$")) {
        Seq("venti") ++ this.tokenizeItalianNumber(word.substring(5).toLowerCase)
      } else if (word.matches("^[Vv]entennale$")) {
        Seq("ventennale")
      } else {
        Seq("vent") ++ this.tokenizeItalianNumber(word.substring(4).toLowerCase)
      }
    } else if (word.matches("^[Tt]rent\\w+$")) {
      if (word.matches("^[Tt]renta\\w*$")) {
        Seq("trenta") ++ this.tokenizeItalianNumber(word.substring(6).toLowerCase)
      } else {
        Seq("trent") ++ this.tokenizeItalianNumber(word.substring(5).toLowerCase)
      }
    } else if (word.matches("^[Qq]uarant\\w+$")) {
      if (word.matches("^[Qq]uaranta\\w*$")) {
        Seq("quaranta") ++ this.tokenizeItalianNumber(word.substring(8).toLowerCase)
      } else {
        Seq("quarant") ++ this.tokenizeItalianNumber(word.substring(7).toLowerCase)
      }
    } else if (word.matches("^[Cc]inquant\\w+$")) {
      if (word.matches("^[Cc]inquanta\\w*$")) {
        Seq("cinquanta") ++ this.tokenizeItalianNumber(word.substring(9).toLowerCase)
      } else {
        Seq("cinquant") ++ this.tokenizeItalianNumber(word.substring(8).toLowerCase)
      }
    } else if (word.matches("^[Ss]essant\\w+$")) {
      if (word.matches("^[Ss]essanta\\w*$")) {
        Seq("sessanta") ++ this.tokenizeItalianNumber(word.substring(8).toLowerCase)
      } else {
        Seq("sessant") ++ this.tokenizeItalianNumber(word.substring(7).toLowerCase)
      }
    } else if (word.matches("^[Ss]ettant\\w+$")) {
      if (word.matches("^[Ss]ettanta\\w*$")) {
        Seq("settanta") ++ this.tokenizeItalianNumber(word.substring(8).toLowerCase)
      } else {
        Seq("settant") ++ this.tokenizeItalianNumber(word.substring(7).toLowerCase)
      }
    } else if (word.matches("^[Oo]ttant\\w+$")) {
      if (word.matches("^[Oo]ttanta\\w*$")) {
        Seq("ottanta") ++ this.tokenizeItalianNumber(word.substring(7).toLowerCase)
      } else {
        Seq("ottant") ++ this.tokenizeItalianNumber(word.substring(6).toLowerCase)
      }
    } else if (word.matches("^[Nn]ovant\\w+$")) {
      if (word.matches("^[Nn]ovanta\\w*$")) {
        Seq("novanta") ++ this.tokenizeItalianNumber(word.substring(7).toLowerCase)
      } else {
        Seq("novant") ++ this.tokenizeItalianNumber(word.substring(6).toLowerCase)
      }
    } else if (word.matches("^[Dd]ecina$")) {
      Seq("dieci", "na")
    } else if (word.matches("^[Uu]ndicina$")) {
      Seq("undici", "na")
    } else if (word.matches("^[Dd]odicina$")) {
      Seq("dodici", "na")
    } else if (word.matches("^[Tt]redicina$")) {
      Seq("tredici", "na")
    } else if (word.matches("^[Qq]uattordicina$")) {
      Seq("quattordici", "na")
    } else if (word.matches("^[Qq]uindicina$")) {
      Seq("quindici", "na")
    } else if (word.matches("^[Ss]edicina$")) {
      Seq("sedici", "na")
    } else if (word.matches("^[Dd]icissettena$")) {
      Seq("diciassette", "na")
    } else if (word.matches("^[Dd]iciottona$")) {
      Seq("diciotto", "na")
    } else if (word.matches("^[Dd]iciannovena$")) {
      Seq("diciannove", "na")
    } else {
      Seq(word)
    }
  }   
  
  // Paramita: special function for splitting numbers in Indonesian
  protected def tokenizeIndonesianNumber(word: String): Seq[String] = {
    if (word.isEmpty) {
      Seq.empty[String]
    } else if (word.matches("^[Ss]e(belas|puluh|ratus|ribu)$")) {
      Seq("se") ++ Seq(word.substring(2).toLowerCase)
    } else {
      Seq(word)
    }
  }    
  
  /**
   * Splits a string into tokens to be used as input for the synchronous parser.
   *
   * This method may be overridden by subclasses if a grammar requires different tokenization than
   * the default English grammar on the classpath.
   *
   * @param sourceText The input text.
   * @return The tokens that result from splitting the input text.
   */
  protected def tokenize(sourceText: String): IndexedSeq[String] = {
    val tokens = for (untrimmedWord <- this.wordBoundary.split(sourceText)) yield {
      val word = untrimmedWord.trim
      if (word.isEmpty) {
        Seq.empty[String]
      }
      // special case for concatenated YYYYMMDD
      else if (word.matches("^\\d{8}$")) {
        Seq(word.substring(0, 4), "-", word.substring(4, 6), "-", word.substring(6, 8))
      }
      // special case for concatenated YYMMDD
      else if (word.matches("^\\d{6}$")) {
        Seq(word.substring(0, 2), "-", word.substring(2, 4), "-", word.substring(4, 6))
      }
      // special case for concatenated HHMMTZ
      else if (word.matches("^\\d{4}[A-Z]{3,4}$")) {
        Seq(word.substring(0, 2), ":", word.substring(2, 4), word.substring(4).toLowerCase)
      }
      /** Paramita: for Italian language */
      // special case for numbers in Italian
      else if (word.matches("^[Dd]ue(cen|mil)\\w+$") || word.matches("^[Tt]re(cen|mil)\\w+$") || 
        word.matches("^[Qq]uattro(cen|mil)\\w+$") || word.matches("^[Cc]inque(cen|mil)\\w+$") || 
        word.matches("^[Ss]ei(cen|mil)\\w+$") || word.matches("^[Ss]ette(cen|mil)\\w+$") || 
        word.matches("^[Oo]tto(cen|mil)\\w+$") || word.matches("^[Nn]ove(cen|mil)\\w+$") ||
        word.matches("^[Dd]ieci(mil)\\w+$") ||
        word.matches("^[Cc]ento\\w+$") || word.matches("^[Mm]ille\\w+$") ||
        word.matches("^[Vv]ent\\w+$") || word.matches("^[Tt]rent\\w+$") || 
        word.matches("^[Qq]uarant\\w+$") || word.matches("^[Cc]inquant\\w+$") || 
        word.matches("^[Ss]essant\\w+$") || word.matches("^[Ss]ettant\\w+$") || 
        word.matches("^[Oo]ttant\\w+$") || word.matches("^[Nn]ovant\\w+$") || 
        word.matches("^[Dd]eci\\w+$") || word.matches("^[Uu]ndici\\w+$") || 
        word.matches("^[Dd]odici\\w+$") || word.matches("^[Tt]redici\\w+$") || 
        word.matches("^[Qq]uattordici\\w+$") || word.matches("^[Qq]uindici\\w+$") || 
        word.matches("^[Ss]edici\\w+$") || word.matches("^[Dd]iciassette\\w+$") || 
        word.matches("^[Dd]iciotto\\w+$") || word.matches("^[Dd]iciannove\\w+$")) {
        this.tokenizeItalianNumber(word)
      }
      else if (word.matches("^[Dd]eg?l?i?$") || word.matches("^[Dd]ell[oae]$")) {
        Seq("dell")
      }
      else if (word.matches("^[Aa]g?l?i?$") || word.matches("^[Aa]ll[oae]$")) {
        Seq("all")
      }
      else if (word.matches("^([Ii]|[Gg]li|[Ll]e)$")) {	//definite plural
        Seq("le")
      }
      else if (word.matches("^[Qq]uest[oaei]$")) {
        Seq("quest")
      }
      else if (word.matches("^[Qq]ueg?l?i?$") || word.matches("^[Qq]uell[oaei]$")) {
        Seq("quell")
      }
      else if (word.matches("^[Ss]cors[oaie]$")) {
        Seq("scorsx")
      }
      else if (word.matches("^[Pp]assat[oaie]$")) {
        Seq("passatx")
      }
      else if (word.matches("^[Uu]ltim[oaie]$")) {
        Seq("ultimx")
      }
      else if (word.matches("^[Pp]recedent[ei]$")) {
        Seq("precedentx")
      }
      else if (word.matches("^[Pp]rossim[oaie]$")) {
        Seq("prossimx")
      }
      else if (word.matches("^[Ss]uccessiv[oaie]$")) {
        Seq("successivx")
      }
      else if (word.matches("^[Ss]eguent[ei]$")) {
        Seq("seguentx")
      }
      else if (word.matches("^[Ee]ntrant[ei]$")) {
        Seq("entrantx")
      }
      else if (word.matches("^[Vv]entur[oaie]$")) {
        Seq("venturx")
      }
      else if (word.matches("^[Ff]utur[oaie]$")) {
        Seq("futurx")
      }
      else if (word.matches("^[Tt]utt[ie]$")) {
        Seq("tutti")
      }
      /***********************************/
      
      /** Paramita: for Indonesian language */
      // special case for ber-, ke-, and -nya in Indonesian
      else if (word.matches("^[Bb]er\\w+$")) {
        Seq("ber") ++ Seq(word.substring(3).toLowerCase)
      }
      else if (word.matches("^[Kk]e(se\\w+|satu|dua|tiga|empat|lima|enam|tujuh|delapan|sembilan)$")) {
        Seq("ke") ++ tokenizeIndonesianNumber(word.substring(2).toLowerCase)
      }
      else if (word.matches("^\\w+nya$")) {
        Seq(word.substring(0, word.length()-3).toLowerCase) ++ Seq("nya")
      }
      else if (word.matches("^\\w+(hari|pagi|siang|sore|malam)an$")) {
        Seq(word.substring(0, word.length()-2).toLowerCase) ++ Seq("an")
      }
      else if (word.matches("^[Ss]e(belas|puluh|ratus|ribu|detik|menit|jam|hari|minggu|pekan|bulan|tahun|windu|dekade|abad|pagi|siang|sore|malam)$")) {
        Seq("se") ++ Seq(word.substring(2).toLowerCase)
      }
      else if (word.matches("^Minggu$")) {
        Seq("mingguhari")
      }      
      /**************************************/
      
      // otherwise, split at all letter/non-letter boundaries
      else {
        this.letterNonLetterBoundary.split(word).toSeq.map(_.trim.toLowerCase).filterNot(_.isEmpty)
      }
    }
    // filter out any tokens not in the grammar
    val filteredTokens = tokens.flatten.filter { token =>
      this.sourceSymbols.contains(token) || SynchronousGrammar.isNumber(token)
    }
    filteredTokens.toIndexedSeq
  }

  /**
   * Tries to parse a source string into a single [[Temporal]] object.
   * Paramita: add java.text.Normalizer to decompose accentuated strings and remove the accents 
   *
   * @param sourceText The input string in the source language.
   * @param anchor The anchor time (required for resolving relative times like "today").
   * @return The most likely [[Temporal]] parse according to the parser's heuristic.
   */
  def parse(sourceText: String, anchor: TimeSpan): Try[Temporal] = {
    this.parseAll(java.text.Normalizer.normalize(sourceText, java.text.Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", ""), anchor).map(_.head)
  }

  /**
   * Try to parse a source string into possible [[Temporal]] objects.
   *
   * @param sourceText The input string in the source language.
   * @param anchor The anchor time (required for resolving relative times like "today").
   * @return A sequence of [[Temporal]] objects representing the possible parses. The sequence is
   *         sorted by a heuristic that tries to put the most promising parses first.
   */
  def parseAll(sourceText: String, anchor: TimeSpan): Try[Seq[Temporal]] = {
    // tokenize the string
    val tokens = this.tokenize(sourceText)

    // parse the tokens into TemporalParses, failing if there is a syntactic error
    val parsesTry =
      try {
        val trees = this.parser.parseAll(tokens)
        // two unique trees can generate the same TemporalParse, so remove duplicates 
        Success(trees.map(TemporalParse).toSet)
      } catch {
        case e: UnsupportedOperationException => Failure(e)
      }

    // if there was no syntactic error, convert the TemporalParses to Temporals 
    parsesTry match {
      case Failure(e) => Failure(e)

      case Success(parses) =>
        // assume that the grammar ambiguity for any expression is at most 2 
        if (parses.size > 2) {
          val message = "Expected no more than 2 parses for \"%s\", found:\n  %s"
          this.logger.warning(message.format(sourceText, parses.mkString("\n  ")))
        }

        // try to convert each TemporalParse to a Temporal
        val temporalTries = for (parse <- parses) yield {
          try {
            Success(parse match {
              case parse: PeriodParse => parse.toPeriod
              case parse: PeriodSetParse => parse.toPeriodSet
              case parse: TimeSpanParse => parse.toTimeSpan(anchor)
              case parse: TimeSpanSetParse => parse.toTimeSpanSet
            })
          } catch {
            case e @ (_: UnsupportedOperationException | _: DateTimeException) => Failure(e)
          }
        }

        // if there all TemporalParses had semantic errors, fail
        val temporals = temporalTries.collect { case Success(temporal) => temporal }
        if (temporals.isEmpty) {
          temporalTries.collect { case Failure(e) => Failure(e) }.head
        }
        // otherwise, sort the Temporals by the heuristic
        else {
          Success(temporals.toSeq.sorted(this.heuristicFor(anchor)))
        }
    }
  }

  // a heuristic for selecting between ambiguous parses
  private def heuristicFor(anchor: TimeSpan): Ordering[Temporal] = {
    val isQuarter = (timeSpan: TimeSpan) => timeSpan.period.unitAmounts.keySet == Set(QUARTER_YEARS)
    val anchorIsQuarter = isQuarter(anchor)
    Ordering.fromLessThan[Temporal] {
      // prefer time spans to periods
      case (period: Period, timeSpan: TimeSpan) => false
      case (timeSpan: TimeSpan, period: Period) => true
      // if the anchor is in quarters, prefer a result in quarters
      // otherwise, prefer earlier time spans
      case (timeSpan1: TimeSpan, timeSpan2: TimeSpan) =>
        (anchorIsQuarter, isQuarter(timeSpan1), isQuarter(timeSpan2)) match {
          case (true, true, false) => true
          case (true, false, true) => false
          case _ => timeSpan1.start.isBefore(timeSpan2.start)
        }
      // throw an exception for anything else
      case other => throw new UnsupportedOperationException("Don't know how to order " + other)
    }
  }
}