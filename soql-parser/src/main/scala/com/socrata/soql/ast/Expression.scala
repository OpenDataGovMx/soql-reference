package com.socrata.soql.ast

import scala.util.parsing.input.{Position, NoPosition}

import com.socrata.soql.names.{FunctionName, ColumnName, TypeName}

sealed abstract class Expression extends Product {
  var position: Position = NoPosition
  protected def asString: String
  override final def toString = if(Expression.pretty) asString else productIterator.mkString(productPrefix + "(",",",")")
  def allColumnRefs: Set[ColumnOrAliasRef]

  def positionedAt(p: Position): this.type = {
    position = p
    this
  }

  def removeParens: Expression

  def toSyntheticIdentifierBase: String = {
    // This is clearly not optimized, but the inputs are small and it
    // mirrors the prose spec closely.

    import Expression._
    val SyntheticUnderscore = Int.MaxValue
    val StartOfString = Int.MaxValue - 1
    val EndOfString = Int.MaxValue - 2

    // First find all tokens to keep and replace "bad" characters with synthetic underscores
    val good_chars_only = findIdentsAndLiterals(this).map(replaceBadChars(_, SyntheticUnderscore))
    // Join them up with synthetic underscores
    val separated_by_underscores = joinWith(good_chars_only, SyntheticUnderscore)
    // Collapse runs of adjacent synthetic underscores
    val not_so_many = collapseRuns(separated_by_underscores, SyntheticUnderscore)
    // Remove ones that are next to real underscores or the ends
    val not_next_to = removeAdjacent(StartOfString +: not_so_many :+ EndOfString, SyntheticUnderscore, Set('_'.toInt, '-'.toInt, StartOfString, EndOfString))
    // Remove the start/end markers
    val trimmed = not_next_to.slice(1, not_next_to.length - 1)
    // Convert synthetic underscores to real ones
    val asString: String = trimmed.map {
      case SyntheticUnderscore => '_'
      case other => other.toChar
    } (scala.collection.breakOut)
    // make sure the result is a valid identifier and return it
    if(asString.isEmpty) "_"
    else if(!Character.isJavaIdentifierStart(asString.charAt(0)) && asString.charAt(0) != '-') "_" + asString
    else asString
  }
}

object Expression {
  val pretty = false

  private def collapseRuns[T](in: Seq[T], v: T): Seq[T] = {
    val r = new scala.collection.immutable.VectorBuilder[T]
    val it = in.iterator.buffered
    while(it.hasNext) {
      val here = it.next()
      if(here == v) {
        while(it.hasNext && it.head == v) it.next()
      }
      r += here
    }
    r.result
  }

  private def removeAdjacent[T](in: Seq[T], toRemove: T, ifAdjacentToThese: Set[T]): Seq[T] = {
    val r = new scala.collection.immutable.VectorBuilder[T]
    val it = in.iterator.buffered
    var lastWasAdjacentTarget = false
    while(it.hasNext) {
      val here = it.next()
      if(lastWasAdjacentTarget && here == toRemove) {
        // do nothing; lastWasAdjacentTarget will remain true
      } else {
        if(here == toRemove && it.hasNext && ifAdjacentToThese(it.head)) {
          // still do nothing because the next thing is the neighbor
        } else {
          lastWasAdjacentTarget = ifAdjacentToThese(here)
          r += here
        }
      }
    }
    r.result
  }

  private def replaceBadChars(s: String, replacement: Int): IndexedSeq[Int] = {
    s.map {
      case c if Character.isJavaIdentifierPart(c) => c
      case c@'-' => c
      case _ => replacement
    }
  }

  private def findIdentsAndLiterals(e: Expression): Seq[String] = e match {
    case v: Literal => Vector(v.asString)
    case ColumnOrAliasRef(name) => Vector(name.canonicalName)
    case fc: FunctionCall =>
      fc match {
        case FunctionCall(SpecialFunctions.StarFunc(base), Seq()) => Vector(base)
        case FunctionCall(SpecialFunctions.Operator(_), args) => args.flatMap(findIdentsAndLiterals)
        case FunctionCall(SpecialFunctions.IsNull, args) => args.flatMap(findIdentsAndLiterals) ++ Vector("IS", "NULL")
        case FunctionCall(SpecialFunctions.IsNotNull, args) => args.flatMap(findIdentsAndLiterals) ++ Vector("IS", "NOT", "NULL")
        case FunctionCall(SpecialFunctions.Between, Seq(a,b,c)) =>
          findIdentsAndLiterals(a) ++ Vector("BETWEEN") ++ findIdentsAndLiterals(b) ++ Vector("AND") ++ findIdentsAndLiterals(c)
        case FunctionCall(SpecialFunctions.NotBetween, Seq(a,b,c)) =>
          findIdentsAndLiterals(a) ++ Vector("NOT", "BETWEEN") ++ findIdentsAndLiterals(b) ++ Vector("AND") ++ findIdentsAndLiterals(c)
        case FunctionCall(other, args) => Vector(other.canonicalName) ++ args.flatMap(findIdentsAndLiterals)
      }
    case Cast(expr, targetType) =>
      findIdentsAndLiterals(expr) :+ targetType.canonicalName
  }

  private def joinWith[T](xs: Seq[Seq[T]], i: T): Seq[T] = {
    val r = new scala.collection.immutable.VectorBuilder[T]
    val it = xs.iterator
    while(it.hasNext) {
      r ++= it.next()
      if(it.hasNext) r += i
    }
    r.result
  }
}

object SpecialFunctions {
  object StarFunc {
    def apply(f: String) = FunctionName(f + "/*")
    def unapply(f: FunctionName) = f.name match {
      case Regex(x) => Some(x)
      case _ => None
    }
    val Regex = """^(.*)/\*$""".r
  }
  val IsNull = FunctionName("#IS_NULL")
  val Between = FunctionName("#BETWEEN")
  val IsNotNull = FunctionName("#IS_NOT_NULL") // redundant but needed for synthetic identifiers
  val NotBetween = FunctionName("#NOT_BETWEEN") // ditto
  val Subscript = FunctionName("op$[]")
  object Operator {
    def apply(op: String) = FunctionName("op$" + op)
    def unapply(f: FunctionName) = f.name match {
      case Regex(x) => Some(x)
      case _ => None
    }
    val Regex = """^op\$(.*)$""".r
  }

  // this exists only so that selecting "(foo)" is never semi-explicitly aliased.
  // it's stripped out by the typechecker with removeParens.
  val Parens = Operator("()")
}

case class ColumnOrAliasRef(column: ColumnName) extends Expression {
  protected def asString = column.toString
  def allColumnRefs = Set(this)
  def removeParens = this
}

sealed abstract class Literal extends Expression {
  def allColumnRefs = Set.empty
  def removeParens = this
}
case class NumberLiteral(value: BigDecimal) extends Literal {
  protected def asString = value.toString
}
case class StringLiteral(value: String) extends Literal {
  protected def asString = "'" + value.replaceAll("'", "''") + "'"
}
case class BooleanLiteral(value: Boolean) extends Literal {
  protected def asString = value.toString.toUpperCase
}
case class NullLiteral() extends Literal {
  override final def asString = "NULL"
}

case class FunctionCall(functionName: FunctionName, parameters: Seq[Expression]) extends Expression {
  var functionNamePosition: Position = NoPosition
  protected def asString = parameters.mkString(functionName.toString + "(", ",", ")")
  lazy val allColumnRefs = parameters.foldLeft(Set.empty[ColumnOrAliasRef])(_ ++ _.allColumnRefs)

  def functionNameAt(p: Position): this.type = {
    functionNamePosition = p
    this
  }
  def removeParens =
    if(functionName == SpecialFunctions.Parens) parameters(0).removeParens
    else copy(parameters = parameters.map(_.removeParens)).positionedAt(position).functionNameAt(functionNamePosition)
}

case class Cast(expression: Expression, targetType: TypeName) extends Expression {
  var operatorPosition: Position = NoPosition
  var targetTypePosition: Position = NoPosition
  protected def asString = "::("+targetType+")"
  def allColumnRefs = expression.allColumnRefs
  def removeParens = copy(expression = expression.removeParens).positionedAt(position).operatorAndTypeAt(operatorPosition, targetTypePosition)

  def operatorAndTypeAt(opPos: Position, typePos: Position): this.type = {
    operatorPosition = opPos
    targetTypePosition = typePos
    this
  }
}
