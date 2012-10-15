package com.socrata.soql.analysis

import scala.util.parsing.input.{Position, NoPosition}

import org.scalatest._
import org.scalatest.matchers.MustMatchers

import com.socrata.soql.parsing.{LexerReader, Parser}

import com.socrata.soql.{DatasetContext, ColumnName}
import com.socrata.soql.ast._

class AliasAnalysisTest extends WordSpec with MustMatchers {
  def columnName(name: String)(implicit ctx: DatasetContext) =
    new ColumnName(name)

  def columnNames(names: String*)(implicit ctx: DatasetContext) =
    Set(names.map(columnName): _*)

  def fixtureContext(cols: String*) =
    new DatasetContext {
      private implicit def dsc = this
      val locale = com.ibm.icu.util.ULocale.US
      lazy val columns = columnNames(cols: _*)
    }

  def fixturePosition(l: Int, c: Int) = new Position {
    def line = l
    def column = c
    def lineContents = " " * c
  }

  implicit def selections(e: String): Selection = {
    val parser = new Parser
    parser.selection(e) match {
      case parser.Success(parsed, _) => parsed
      case failure => fail("Unable to parse expression fixture " + e + ": " + failure)
    }
  }
  def selectionsNoPos(e: String): Selection = selections(e).unpositioned
  def expr(e: String) = {
    val parser = new Parser
    parser.expression(e) match {
      case parser.Success(parsed, _) => parsed
      case failure => fail("Unable to parse expression fixture " + e + ": " + failure)
    }
  }
  def ident(e: String) = {
    val parser = new Parser
    parser.identifier(new LexerReader(e)) match {
      case parser.Success(parsed, _) => parsed
      case failure => fail("Unable to parse expression fixture " + e + ": " + failure)
    }
  }
  def unaliased(names: String*)(pos: Position) = names.map { i => SelectedExpression(Identifier(i,false,pos), None) }

  "processing a star" should {
    implicit val ctx = fixtureContext()
    val pos = fixturePosition(4, 3)

    "expand to all input columns when there are no exceptions" in {
      AliasAnalysis.processStar(StarSelection(Seq.empty, pos), columnNames("a","b","c")) must equal (unaliased("a", "b", "c")(pos))
    }

    "expand to the empty list when there are no columns" in {
      AliasAnalysis.processStar(StarSelection(Seq.empty, pos), columnNames()) must equal (Seq.empty)
    }

    "expand with exclusions exluded" in {
      AliasAnalysis.processStar(StarSelection(Seq(Identifier("b", false, NoPosition)), pos), columnNames("a","b","c")) must equal (unaliased("a","c")(pos))
    }

    "throw an exception if an exception does not occur in the column-set" in {
      evaluating { AliasAnalysis.processStar(StarSelection(Seq(Identifier("not_there", false, NoPosition)), pos), columnNames("a","c")) } must produce[NoSuchColumnException]
    }

    "throw an exception if an exception occurs more than once" in {
      evaluating { AliasAnalysis.processStar(StarSelection(Seq(Identifier("a", false, NoPosition), Identifier("a", false, NoPosition)), pos), columnNames("a","c")) } must produce[RepeatedExceptionException]
    }
  }

  "expanding a selection" should {
    implicit val ctx = fixtureContext(":a",":b","c","d","e")
    val pos = fixturePosition(5, 12)
    val someSelections = selections("2+2,hello,avg(gnu) as average").expressions

    "return the input if there were no stars" in {
      AliasAnalysis.expandSelection(Selection(None, None, someSelections)) must equal (someSelections)
    }

    "return the system columns if there was a :*" in {
      AliasAnalysis.expandSelection(Selection(Some(StarSelection(Seq.empty, pos)), None, someSelections)) must equal (unaliased(":a",":b")(pos) ++ someSelections)
    }

    "return the un-excepted columns if there was a :*" in {
      AliasAnalysis.expandSelection(Selection(Some(StarSelection(Seq(ident(":a")), pos)), None, someSelections)) must equal (unaliased(":b")(pos) ++ someSelections)
    }

    "return the user columns if there was a *" in {
      AliasAnalysis.expandSelection(Selection(None, Some(StarSelection(Seq.empty, pos)), someSelections)) must equal (unaliased("c","d","e")(pos) ++ someSelections)
    }

    "return the un-excepted user columns if there was a *" in {
      AliasAnalysis.expandSelection(Selection(None, Some(StarSelection(Seq(ident("d"),ident("e")), pos)), someSelections)) must equal (unaliased("c")(pos) ++ someSelections)
    }

    "return the all user columns if there was a :* and a *" in {
      AliasAnalysis.expandSelection(Selection(Some(StarSelection(Seq.empty, pos)), Some(StarSelection(Seq.empty, pos)), someSelections)) must equal (unaliased(":a",":b","c","d","e")(pos) ++ someSelections)
    }
  }

  "assigning (semi-)explicit aliases" should {
    implicit val ctx = fixtureContext(":a",":b","c","d","e")

    "assign aliases to simple columns" in {
      val ss = selections("2+2,hello,world,avg(gnu),(x)").expressions
      val exprs = ss.map(_.expression)
      AliasAnalysis.assignExplicitAndSemiExplicit(ss) must equal ((Map(columnName("hello") -> exprs(1),columnName("world") -> exprs(2)), exprs.take(1) ++ exprs.drop(3)))
    }

    "accept aliases" in {
      val ss = selections("2+2 as four,hello as x,avg(gnu),world,(x)").expressions
      val exprs = ss.map(_.expression)
      AliasAnalysis.assignExplicitAndSemiExplicit(ss) must equal ((Map(columnName("four") -> exprs(0),columnName("x") -> exprs(1),columnName("world") -> exprs(3)), exprs.drop(2).take(1) ++ exprs.drop(4)))
    }

    "reject duplicate aliases when one is explicit and the other semi-explicit" in {
      val ss = selections("2+2 as four, four").expressions
      evaluating { AliasAnalysis.assignExplicitAndSemiExplicit(ss) } must produce[DuplicateAliasException]
    }

    "reject duplicate aliases when both are semi-explicit" in {
      val ss = selections("four, four").expressions
      evaluating { AliasAnalysis.assignExplicitAndSemiExplicit(ss) } must produce[DuplicateAliasException]
    }

    "reject duplicate aliases when both are explicit" in {
      val ss = selections("2 + 2 as four, 1 + 3 as four").expressions
      evaluating { AliasAnalysis.assignExplicitAndSemiExplicit(ss) } must produce[DuplicateAliasException]
    }
  }

  "creating an implicit alias" should {
    implicit val ctx = fixtureContext(":a",":b","c","d","e")

    "select a name" in {
      AliasAnalysis.implicitAlias(expr("a"), Set()) must equal (columnName("a"))
      AliasAnalysis.implicitAlias(expr("a + b"), Set()) must equal (columnName("a_b"))
    }

    "select a name not in use" in {
      AliasAnalysis.implicitAlias(expr("a + b"), columnNames("a_b")) must equal (columnName("a_b_1"))
      AliasAnalysis.implicitAlias(expr("c"), Set()) must equal (columnName("c_1")) // checking against the dataset context
      AliasAnalysis.implicitAlias(expr("a + b"), columnNames("a_b", "a_b_1", "a_b_2")) must equal (columnName("a_b_3"))
    }

    "not add unnecessary underscores when suffixing a disambiguator" in {
      AliasAnalysis.implicitAlias(expr("a + b_"), Set(columnName("a_b_"))) must equal (columnName("a_b_1"))
      AliasAnalysis.implicitAlias(expr("a + `b-`"), Set(columnName("a_b-"))) must equal (columnName("a_b-1"))
    }
  }

  "assigning implicits" should {
    implicit val ctx = fixtureContext(":a",":b","c","d","e")

    val startingAliases = Map(
      columnName("c") -> expr(":a + 1"),
      columnName("c_minus_d") -> expr("c - d"),
      columnName("d_1") -> expr("e")
    )

    "assign non-conflicting aliases" in {
      AliasAnalysis.assignImplicit(startingAliases, Seq(expr(":a + 1"), expr("-c"), expr("-d"))) must equal (Map(
        columnName("a_1") -> expr(":a + 1"),
        columnName("c_1") -> expr("-c"),
        columnName("d_2") -> expr("-d")
      ) ++ startingAliases)
    }

    "reject assigning to a straight identifier" in {
      evaluating { AliasAnalysis.assignImplicit(Map.empty, Seq(expr("q"))) } must produce[AssertionError]
    }
  }

  "the whole flow" should {
    implicit val ctx = fixtureContext(":a",":b","c","d","e")

    "expand *" in {
      AliasAnalysis(selections("*").unpositioned) must equal(Map(
        columnName("c") -> expr("c").unpositioned,
        columnName("d") -> expr("d").unpositioned,
        columnName("e") -> expr("e").unpositioned
      ))
    }

    "expand :*" in {
      AliasAnalysis(selections(":*").unpositioned) must equal(Map(
        columnName(":a") -> expr(":a").unpositioned,
        columnName(":b") -> expr(":b").unpositioned
      ))
    }

    "expand :*,*" in {
      AliasAnalysis(selections(":*,*").unpositioned) must equal(Map(
        columnName(":a") -> expr(":a").unpositioned,
        columnName(":b") -> expr(":b").unpositioned,
        columnName("c") -> expr("c").unpositioned,
        columnName("d") -> expr("d").unpositioned,
        columnName("e") -> expr("e").unpositioned
      ))
    }

    "not select an alias the same as a column in the dataset context" in {
      AliasAnalysis(selections("(c)").unpositioned) must equal(Map(
        columnName("c_1") -> expr("(c)").unpositioned
      ))
    }

    "not select an alias the same as an expression in the dataset context" in {
      AliasAnalysis(selections("c as c_d, c - d").unpositioned) must equal(Map(
        columnName("c_d") -> expr("c").unpositioned,
        columnName("c_d_1") -> expr("c - d").unpositioned
      ))
    }

    "never infer the same alias twice" in {
      AliasAnalysis(selections("c + d, c - d").unpositioned) must equal(Map(
        columnName("c_d") -> expr("c + d").unpositioned,
        columnName("c_d_1") -> expr("c - d").unpositioned
      ))
    }

    "allow hiding a column if it's not selected" in {
      AliasAnalysis(selections("c as d").unpositioned) must equal(Map(
        columnName("d") -> expr("c").unpositioned
      ))
    }

    "forbid hiding a column if it's selected via *" in {
      evaluating { AliasAnalysis(selections("*, c as d")) } must produce[DuplicateAliasException]
    }

    "forbid hiding a column if it's selected explicitly" in {
      evaluating { AliasAnalysis(selections("d, c as d")) } must produce[DuplicateAliasException]
    }

    "forbid hiding giving two expressions the same alias" in {
      evaluating { AliasAnalysis(selections("e as q, c as q")) } must produce[DuplicateAliasException]
    }

    "allow hiding a column if it's excluded via *" in {
      AliasAnalysis(selections("* (except d), c as d").unpositioned) must equal(Map(
        columnName("c") -> expr("c").unpositioned,
        columnName("d") -> expr("c").unpositioned,
        columnName("e") -> expr("e").unpositioned
      ))
    }

    "forbid excluding the same user column twice" in {
      evaluating { AliasAnalysis(selections("* (except c, c)")) } must produce[RepeatedExceptionException]
    }

    "forbid excluding the same system column twice" in {
      evaluating { AliasAnalysis(selections(":* (except :b, :b)")) } must produce[RepeatedExceptionException]
    }

    "forbid excluding a non-existant user column" in {
      evaluating { AliasAnalysis(selections("* (except gnu)")) } must produce[NoSuchColumnException]
    }

    "forbid excluding a non-existant system column" in {
      evaluating { AliasAnalysis(selections(":* (except :gnu)")) } must produce[NoSuchColumnException]
    }
  }
}