package vyxal.lexer

import scala.language.strictEquality

import vyxal.lexer.Common.{parseToken, withRange}
import vyxal.lexer.Common.given // For custom whitespace
import vyxal.lexer.TokenType.*
import vyxal.Elements
import vyxal.Modifiers

import fastparse.*

/** Lexer for literate mode */
private[lexer] object LiterateLexer extends Lexer:
  private val endKeywords = List(
    "endfor",
    "end-for",
    "endwhile",
    "end-while",
    "endlambda",
    "end-lambda",
    "end",
  )

  private val branchKeywords = List(
    "else",
    "elif",
    "else-if",
    "body",
    "do",
    "branch",
    "then",
    "in",
    "using",
  )

  /** Map keywords to their token types */
  private val keywords = Map(
    "close-all" -> TokenType.StructureAllClose
  )

  private val lambdaOpeners = Map(
    "lambda" -> StructureType.Lambda,
    "lam" -> StructureType.Lambda,
    "map-lambda" -> StructureType.LambdaMap,
    "map-lam" -> StructureType.LambdaMap,
    "filter-lambda" -> StructureType.LambdaFilter,
    "filter-lam" -> StructureType.LambdaFilter,
    "sort-lambda" -> StructureType.LambdaSort,
    "sort-lam" -> StructureType.LambdaSort,
    "reduce-lambda" -> StructureType.LambdaReduce,
    "reduce-lam" -> StructureType.LambdaReduce,
    "fold-lambda" -> StructureType.LambdaReduce,
    "fold-lam" -> StructureType.LambdaReduce
  )

  /** Keywords for opening structures. Has to be a separate map because while
    * all of them have the same [[TokenType]], they have different values
    * depending on the kind of structure
    */
  private val structOpeners = Map(
    // These can't go in the big map, because that's autogenerated
    "?" -> StructureType.Ternary,
    "?->" -> StructureType.Ternary,
    "if" -> StructureType.IfStatement,
    "for" -> StructureType.For,
    "do-to-each" -> StructureType.For,
    "while" -> StructureType.While,
    "is-there?" -> StructureType.DecisionStructure,
    "does-exist?" -> StructureType.DecisionStructure,
    "is-there" -> StructureType.DecisionStructure,
    "does-exist" -> StructureType.DecisionStructure,
    "any-in" -> StructureType.DecisionStructure,
    "relation" -> StructureType.GeneratorStructure,
    "generate-from" -> StructureType.GeneratorStructure,
    "generate" -> StructureType.GeneratorStructure,
  )

  lazy val literateModeMappings: Map[String, String] =
    Elements.elements.values.view.flatMap { elem =>
      elem.keywords.map(_ -> elem.symbol)
    }.toMap ++ Modifiers.modifiers.view.flatMap { (symbol, mod) =>
      mod.keywords.map(_ -> symbol)
    }.toMap ++ keywords.map { (kw, typ) =>
      kw -> typ.canonicalSBCS.get
    }.toMap ++ endKeywords
      .map(_ -> TokenType.StructureClose.canonicalSBCS.get)
      .toMap ++ branchKeywords
      .map(_ -> TokenType.Branch.canonicalSBCS.get)
      .toMap ++ (lambdaOpeners ++ structOpeners).map { (kw, typ) =>
      kw -> typ.open
    }

  def wordChar[$: P]: P[String] = P(CharIn("a-z\\-?!").!)

  def word[$: P]: P[String] = P((CharIn("a-z") ~~ wordChar.repX).!)

  def litInt[$: P]: P[String] = P(
    ("0" | (CharIn("1-9") ~~ CharsWhileIn("_0-9", 0))).!
  )
  def litDigits[$: P]: P[String] = P(CharsWhileIn("_0-9", 0).!)
  def litDecimal[$: P]: P[String] =
    ("-".? ~~ (litInt ~~ ("." ~~ litDigits).? | "." ~~ litDigits)).!
  def litNumber[$: P]: P[Token] =
    parseToken(
      Number,
      ((litDecimal ~~ ("ı" ~~ litDecimal.? | "i" ~~ (litDecimal | !wordChar)).?)
        | "i" ~~ (litDecimal | !wordChar)
        | "ı" ~~ litDecimal.?).!
    ).opaque("<number (literate)>")
      .map { case Token(_, value, range) =>
        val temp = value.replace("i", "ı").replace("_", "")
        val parts =
          if !temp.endsWith("ı") then temp.split("ı").toSeq
          else temp.init.split("ı").toSeq :+ ""
        Token(
          Number,
          parts
            .map(x => if x.startsWith("-") then x.substring(1) + "_" else x)
            .mkString("ı"),
          range
        )
      }
  end litNumber

  def contextIndex[$: P]: P[Token] =
    parseToken(ContextIndex, "`" ~~ CharsWhileIn("0-9", 0).! ~~ "`")

  val lambdaOpenerSet = lambdaOpeners.keys.toSet
  def lambdaBlock[$: P]: P[Seq[Token]] =
    P(
      withRange("{".! | Common.lambdaOpen | word.filter(lambdaOpenerSet).!)
        ~~/ ( // Keep going until the branch indicating params end, but don't stop at ","
          (parseToken(Command, ",".!).map(Seq(_))
            | !litBranch ~ NoCut(singleToken)).rep.map(_.flatten) ~ litBranch
        ).?
        ~ (
          !(
            litStructClose | SBCSLexer.structureSingleClose | SBCSLexer.structureDoubleClose | SBCSLexer.structureAllClose
          ) ~ NoCut(singleToken)
        ).rep.map(_.flatten)
        ~ (End | litStructClose | SBCSLexer.structureSingleClose
          | SBCSLexer.structureDoubleClose | &(SBCSLexer.structureAllClose))
    ).map { case (opener, openRange, possibleParams, body, endTok) =>
      val openerTok =
        Token(
          StructureOpen,
          // If it's a keyword, map it to SBCS
          if opener == "{" then StructureType.Lambda.open
          else lambdaOpeners.get(opener).map(_.open).getOrElse(opener),
          openRange
        )
      val possParams = possibleParams match
        case Some((params, branch)) =>
          // Branches get turned into `|` when sbcsifying. To preserve commas, turn them into Commands instead
          val paramsWithCommas = params.map(tok =>
            if tok.value == "," then Token(Command, ",", tok.range)
            else if tok.tokenType == Command then tok.copy(tokenType = Param)
            else tok
          )
          paramsWithCommas :+ branch
        case None => Nil
      val withoutEnd = openerTok +: (possParams ++ body)
      endTok match
        case tok: Token => withoutEnd :+ tok
        case _ =>
          // This means there was a StructureAllClose or we hit EOF
          withoutEnd
    }
  end lambdaBlock

  def litString[$: P]: P[Token] =
    parseToken(Str, "\"" ~~ ("\\" ~~ AnyChar | !"\"" ~ AnyChar).repX.! ~~ "\"")

  def normalGroup[$: P]: P[List[Token]] = P("(" ~~/ tokens ~ ")")

  def keywordsParser[$: P](
      keywords: Iterable[String]
  ): P[String] =
    // TODO(user): Make this not use filter
    val isKeyword = keywords.toSet
    word.filter(isKeyword)

  def elementKeyword[$: P]: P[Token] =
    parseToken(
      Command,
      keywordsParser(Elements.elements.values.flatMap(_.keywords))
    ).opaque("<element keyword>")

  def modifierKeyword[$: P]: P[Token] =
    withRange(
      keywordsParser(
        Modifiers.modifiers.values.flatMap(_.keywords)
      )
    ).opaque("<modifier keyword>")
      .map { case (keyword, range) =>
        val mod =
          Modifiers.modifiers.values.find(_.keywords.contains(keyword)).get
        val tokenType = mod.arity match
          case 1 => MonadicModifier
          case 2 => DyadicModifier
          case 3 => TriadicModifier
          case 4 => TetradicModifier
          case _ => SpecialModifier
        Token(tokenType, keyword, range)
      }

  def structOpener[$: P]: P[Token] =
    withRange(keywordsParser(structOpeners.keys))
      .opaque("<struct opener>")
      .map { case (word, range) =>
        val sbcs = structOpeners(word).open
        Token(StructureOpen, sbcs, range)
      }

  def otherKeyword[$: P]: P[Token] =
    withRange(keywordsParser(keywords.keys)).opaque("<other keyword>").map {
      case (word, range) =>
        Token(keywords(word), word, range)
    }

  def litGetVariable[$: P]: P[Token] =
    parseToken(GetVar, "$" ~/ Common.varName.?.!)

  def litSetVariable[$: P]: P[Token] =
    parseToken(SetVar, ":=" ~ Common.varName.?.!)

  def litSetConstant[$: P]: P[Token] =
    parseToken(Constant, ":!=" ~/ Common.varName.?.!)

  def litAugVariable[$: P]: P[Token] =
    parseToken(AugmentVar, (":>") ~/ Common.varName.?.!)

  def unpackVar[$: P]: P[Seq[Token]] =
    P(withRange(":=") ~ list).map { case (_, unpackRange, listTokens) =>
      (Token(SyntaxTrigraph, "#:[", unpackRange)
        +: listTokens.slice(1, listTokens.size - 1))
        :+ Token(UnpackClose, "]", listTokens.last.range)
    }

  def list[$: P]: P[Seq[Token]] =
    P(
      parseToken(ListOpen, "[".!)
        ~~/ (litBranch |
          !"]" ~ singleToken ~ litBranch.?).rep
        // NoCut(singleToken).filter(toks =>
        // toks.size != 1 || toks.head.tokenType != ListClose
        // ) ~ litBranch.?).rep
        ~ parseToken(ListClose, "]".!)
    ).map { case (startTok, elems, endTok) =>
      val middle = elems.flatMap {
        case branch: Token => List(branch)
        case (elem, branch) => elem ++ branch
      }
      (startTok +: middle) :+ endTok
    }

  def litBranch[$: P]: P[Token] =
    P(
      SBCSLexer.branch
        | parseToken(Branch, StringIn(":", ",", "->").!)
        | parseToken(Branch, keywordsParser(branchKeywords)).opaque(
          "<branch keyword>"
        )
    )

  def litStructClose[$: P]: P[Token] =
    parseToken(StructureClose, endKeywords.map(_.!).reduce(_ | _))
      .opaque("<end keyword>")

  def rawCode[$: P]: P[Seq[Token]] =
    P("#" ~ Index ~ ((!"#}" ~ AnyChar).rep.!) ~ "#}").map {
      case (offset, value) =>
        SBCSLexer.lex(value) match
          case Right(tokens) =>
            tokens.map { tok =>
              tok.copy(range =
                Range(
                  startOffset = offset + tok.range.startOffset,
                  endOffset = offset + tok.range.endOffset
                )
              )
            }
          case Left(err) => throw new RuntimeException(err.toString)
    }

  def singleToken[$: P]: P[Seq[Token]] =
    P(
      lambdaBlock | list | unpackVar
        | (contextIndex | litGetVariable | litSetVariable | litSetConstant | litAugVariable
          | elementKeyword | modifierKeyword | structOpener | otherKeyword
          | litBranch | litStructClose | litNumber | litString)
          .map(Seq(_))
        | normalGroup | rawCode | SBCSLexer.token.map(Seq(_))
    )

  def tokens[$: P]: P[List[Token]] =
    P(singleToken.rep).map(_.flatten.toList)

  override def parseAll[$: P]: P[List[Token]] = P(tokens ~ End)
end LiterateLexer
