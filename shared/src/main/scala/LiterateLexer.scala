package vyxal

import vyxal.impls.Elements

import java.util.regex.Pattern
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Queue
import scala.util.matching.Regex
import scala.util.parsing.combinator.*

import LiterateToken.*

enum LiterateToken:
  // Object instead of String like the normal lexer because it's way easier
  case Word(value: String)
  case AlreadyCode(value: String)
  // This is for strings that are already in SBCS form
  case Number(value: String)
  case Variable(value: String)
  case Newline(value: String)
  case Group(value: List[LiterateToken])
  case LitComment(value: String)
  case LambdaBlock(value: List[LiterateToken])
  case ListToken(value: List[LiterateToken])
  case Other(value: String)

/** For parsing code written in literate mode. Use [[LiterateLexer.apply]] */
object LiterateLexer:
  private val lambdaKeywords = Map(
    "lambda" -> "λ",
    "lam" -> "λ",
  )

  private val endKeywords = List(
    "endfor",
    "end-for",
    "endwhile",
    "end-while",
    "endlambda",
    "end-lambda",
    "end",
  ).map(_ -> "}").toMap

  private val branchKeywords = List(
    "else",
    "elif",
    "else-if",
    "body",
    "do",
    "branch",
    "->",
    "then",
    "in",
    "using",
  ).map(_ -> "|").toMap

  private val hardcodedKeywords = Map(
    // These can't go in the big map, because that's autogenerated
    "?" -> "[",
    "?->" -> "[",
    "if" -> "#{",
    "for" -> "(",
    "do-to-each" -> "(",
    "while" -> "{",
    "is-there?" -> "Ḍ",
    "does-exist?" -> "Ḍ",
    "is-there" -> "Ḍ",
    "does-exist" -> "Ḍ",
    "any-in" -> "Ḍ",
    "relation" -> "Ṇ",
    "generate-from" -> "Ṇ",
    "generate" -> "Ṇ",
    "map-lambda" -> "ƛ",
    "map-lam" -> "ƛ",
    "filter-lambda" -> "Ω",
    "filter-lam" -> "Ω",
    "sort-lambda" -> "µ",
    "sort-lam" -> "µ",
    "reduce-lambda" -> "₳",
    "reduce-lam" -> "₳",
    "fold-lambda" -> "₳",
    "fold-lam" -> "₳",
    "close-all" -> "]"
  ) ++ lambdaKeywords ++ endKeywords ++ branchKeywords

  lazy val literateModeMappings: Map[String, String] =
    Elements.elements.values.view.flatMap { elem =>
      elem.keywords.map(_ -> elem.symbol)
    }.toMap ++ Modifiers.modifiers.view.flatMap { (symbol, mod) =>
      mod.keywords.map(_ -> symbol)
    }.toMap ++ hardcodedKeywords

  /** Tokenize a piece of code in literate mode */
  def apply(code: String): Either[VyxalCompilationError, List[LiterateToken]] =
    import LiterateParsers.*
    (parse(tokens, code): @unchecked) match
      case NoSuccess(msg, next)  => Left(VyxalCompilationError(msg))
      case Success(result, next) => Right(postProcess(result))

  private def postProcess(tokenList: List[LiterateToken]): List[LiterateToken] =
    // Make sure lambda arguments are untranslated in the sbcsification process
    val ret = ListBuffer[LiterateToken]()
    val tokenQueue = tokenList.to(Queue)

    while tokenQueue.nonEmpty do
      val token = tokenQueue.dequeue()
      token match
        case Word(value) =>
          if lambdaKeywords.contains(value) && tokenQueue.nonEmpty then
            // Scan until we either find a branch, or the end of the lambda
            ret += token
            val lambdaArgs = ListBuffer[LiterateToken]()
            var depth = 1
            var continue = true
            while continue && tokenQueue.nonEmpty && depth > 0 do
              val subtoken = tokenQueue.dequeue()
              subtoken match
                case Word(value) if endKeywords.contains(value) =>
                  if depth == 1 then continue = false else depth -= 1
                case Word(value) if branchKeywords.contains(value) =>
                  if depth == 1 then continue = false
                case Word(value) if lambdaKeywords.contains(value) =>
                  depth += 1
                case AlreadyCode(value) if value == "|" =>
                  if depth == 1 then continue = false
                case _ => ()
              lambdaArgs += subtoken
            if lambdaArgs.nonEmpty && depth == 1 then
              lambdaArgs.last match
                case Word(value) if endKeywords.contains(value) =>
                  ret ++= postProcess(lambdaArgs.init.toList)
                  ret += lambdaArgs.last
                case Word(value) if branchKeywords.contains(value) =>
                  ret += AlreadyCode(
                    postProcess(lambdaArgs.init.toList)
                      .map(stringify)
                      .filter(_ != ",")
                      .mkString(",")
                  )
                  ret += lambdaArgs.last
                case AlreadyCode(value) if value == "|" =>
                  ret += AlreadyCode(
                    postProcess(lambdaArgs.init.toList)
                      .map(stringify)
                      .filter(_ != ",")
                      .mkString(",")
                  )
                  ret += lambdaArgs.last
                case _ =>
                  ret ++= postProcess(lambdaArgs.toList)
            else ret ++= postProcess(lambdaArgs.toList)
          else ret += token
        case _ => ret += token
      end match
    end while

    ret.toList
  end postProcess

  private def stringify(token: LiterateToken): String =
    token match
      case Word(value)        => value
      case AlreadyCode(value) => value
      case LitComment(value)  => value
      case Number(value)      => value
      case Variable(value)    => value
      case Group(value)       => value.map(stringify).mkString("(", " ", ")")
      case LambdaBlock(value) => value.map(stringify).mkString("λ", " ", "}")
      case ListToken(value)   => value.map(stringify).mkString("[", "|", "]")
      case Newline(value)     => value
      case Other(value)       => value

  /** Translate literate mode code into normal SBCS-using code */
  def processLit(code: String): Either[VyxalCompilationError, String] =
    LiterateLexer(code).map(sbcsify)

  private def sbcsify(tokens: List[LiterateToken]): String =
    val out = StringBuilder()

    for i <- tokens.indices do
      val token = tokens(i)
      val next = if i == tokens.length - 1 then None else Some(tokens(i + 1))
      token match
        case Word(value) =>
          out.append(literateModeMappings.getOrElse(value, value))
        case Number(value) =>
          if value == "0" then out.append("0")
          else
            next match
              case Some(Number(nextNumber)) =>
                if nextNumber == "." && value.endsWith(".") then
                  out.append(value)
                else out.append(value + " ")
              case Some(Group(items)) =>
                if items.length == 1 &&
                  LiterateLexer(items.head.asInstanceOf[Other].value)
                    .getOrElse(Nil)
                    .head
                    .isInstanceOf[Number]
                then out.append(value + " ")
                else out.append(value)
              case _ => out.append(value)
        case Variable(value) =>
          next match
            case Some(Number(_)) => out.append(value + " ")
            case Some(Word(w)) =>
              if "[a-zA-Z0-9_]+".r.matches(
                  literateModeMappings.getOrElse(w, "")
                )
              then out.append(value + " ")
              else out.append(value)
            case _ => out.append(value)
        case AlreadyCode(value) => out.append(value)
        case Newline(value)     => out.append(value)
        case Group(value) =>
          out.append(value.map(sbcsifySingle).mkString)
        case LitComment(value) =>
        case LambdaBlock(value) =>
          out.append(value.map(sbcsifySingle).mkString("λ", "", "}"))
        case ListToken(value) =>
          out.append(value.map(sbcsifySingle).mkString("#[", "|", "#]"))
        case Other(value) =>
          out.append(value)
      end match
    end for

    out.toString
  end sbcsify

  private def sbcsifySingle(token: LiterateToken): String =
    token match
      case Word(value)        => literateModeMappings.getOrElse(value, value)
      case AlreadyCode(value) => value
      case Variable(value)    => value
      case Number(value)      => value
      case Group(value)       => value.map(sbcsifySingle).mkString
      case Newline(_)         => ""
      case LitComment(value)  => ""
      case LambdaBlock(value) => value.map(sbcsifySingle).mkString("λ", "", "}")
      case ListToken(value) =>
        value.map(sbcsifySingle).mkString("#[", "|", "#]")
      case Other(value) => processLit(value).toOption.get

  def isList(code: String): Boolean =
    apply(code).getOrElse(Nil).exists {
      case ListToken(_) => true
      case _            => false
    }

  private object LiterateParsers extends RegexParsers:

    override def skipWhitespace = true
    override val whiteSpace: Regex = "[ \t\r\f]+".r

    private def decimalRegex =
      raw"(-?((0|[1-9][0-9_]*)?\.[0-9]*|0|[1-9][0-9_]*))"
    def number: Parser[LiterateToken] =
      raw"(${decimalRegex}i(-|$decimalRegex)?)|(i$decimalRegex)|$decimalRegex|(i( |$$))".r ^^ {
        value =>
          val temp = value.replace("i", "ı").replace("_", "")
          val parts =
            if !temp.endsWith("ı") then temp.split("ı").toSeq
            else temp.init.split("ı").toSeq :+ ""
          Number(
            parts
              .map(x => if x.startsWith("-") then x.tail + "_" else x)
              .mkString("ı")
          )
      }

    def string: Parser[LiterateToken] = raw"""("(?:[^"\\]|\\.)*["])""".r ^^ {
      value => AlreadyCode(value)
    }

    def singleCharString: Parser[LiterateToken] = """'.""".r ^^ { value =>
      AlreadyCode(value)
    }

    def comment: Parser[LiterateToken] = """##[^\n]*""".r ^^ { value =>
      LitComment(value)
    }

    def contextIndex: Parser[LiterateToken] = """`[0-9]*`""".r ^^ { value =>
      AlreadyCode(value.tail.init + "¤")
    }

    def parseOther(p: Parser[String]): Parser[LiterateToken] = p ^^ { value =>
      Other(value)
    }

    def lambdaBlock: Parser[LiterateToken] =
      "{" ~> rep(lambdaBlock | parseOther("""(#}|[^{}])+""".r)) <~ "}" ^^ {
        body =>
          LambdaBlock(body)
      }
    def normalGroup: Parser[LiterateToken] =
      "(" ~> rep(normalGroup | parseOther("""[^()]+""".r)) <~ ")" ^^ { body =>
        Group(body)
      }

    def list: Parser[LiterateToken] =
      "[" ~> repsep(list | parseOther("""[^\]\[|,]+""".r), "[|,]".r) <~ "]" ^^ {
        body =>
          ListToken(body)
      }

    def word: Parser[LiterateToken] =
      """[a-zA-Z?!*+=&%><-][a-zA-Z0-9?!*+=&%><-]*""".r ^^ { value =>
        Word(value)
      }

    def varGet: Parser[LiterateToken] = """\$([_a-zA-Z][_a-zA-Z0-9]*)?""".r ^^ {
      value => Variable("#" + value)
    }

    def varSet: Parser[LiterateToken] = """:=([_a-zA-Z][_a-zA-Z0-9]*)?""".r ^^ {
      value => Variable("#" + value.substring(1))
    }

    def constSet: Parser[LiterateToken] =
      """:!=([_a-zA-Z][_a-zA-Z0-9]*)?""".r ^^ { value =>
        Variable("#!" + value.substring(3))
      }

    def augVar: Parser[LiterateToken] = """:>([a-zA-Z][_a-zA-Z0-9]*)?""".r ^^ {
      value => Variable("#>" + value.substring(2))
    }

    def unpackVar: Parser[LiterateToken] = ":=" ~> list ^^ { value =>
      (value: @unchecked) match
        case ListToken(value) =>
          AlreadyCode("#:" + value.map(stringify).mkString("[", "|", "]"))
    }

    def newline: Parser[LiterateToken] = "\n" ^^^ Newline("\n")

    def branch: Parser[LiterateToken] = "|" ^^^ AlreadyCode("|")

    def tilde: Parser[LiterateToken] = "~" ^^^ AlreadyCode("!")

    def colon: Parser[LiterateToken] = ":" ^^^ AlreadyCode("|")

    def comma: Parser[LiterateToken] = "," ^^^ AlreadyCode(",")
    def rawCode: Parser[LiterateToken] = "#([^#]|#[^}])*#}".r ^^ { value =>
      AlreadyCode(value.substring(1, value.length - 2))
    }

    def tokens: Parser[List[LiterateToken]] = phrase(
      rep(
        number | string | singleCharString | comment | rawCode | list | contextIndex | lambdaBlock | normalGroup |
          unpackVar | varGet | varSet | constSet | augVar | word | branch | newline | tilde | colon |
          comma
      )
    )
  end LiterateParsers
end LiterateLexer
