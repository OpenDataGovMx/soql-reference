package com.socrata.soql.analysis

import scala.util.parsing.input.Position

import com.socrata.soql.names.{ColumnName, FunctionName}
import com.socrata.soql.analysis.typed._

class AggregateInUngroupedContext(val function: FunctionName, clause: String, val position: Position) extends Exception("Cannot use an aggregate function in " + clause + ":\n" + position.longString)
class ColumnNotInGroupBys(val column: ColumnName, val position: Position) extends Exception("Column not in group bys:\n" + position.longString)

class AggregateChecker[Type] {
  type Expr = TypedFF[Type]

  /** Check that aggregates and column-names are used as appropriate for
    * the query.
    *
    * @param outputs The selected expressions for the query
    * @param where The WHERE clause for the query, if present
    * @param groupBy The GROUP BY expressions, if the clause is present
    * @param having The HAVING clause for the query, if present
    * @param orderBy The ORDER BY expressions for the query, minus sorting options
    * @return `true` if this was a grouped query or `false` otherwise.
    */
  def apply(outputs: Seq[Expr], where: Option[Expr], groupBy: Option[Seq[Expr]], having: Option[Expr], orderBy: Seq[Expr]): Boolean = {
    if(groupBy.isDefined || having.isDefined) { // ok, definitely a grouped query
      checkGrouped(outputs, where, groupBy.getOrElse(Nil), having, orderBy)
      true
    } else {
      // neither GROUP BY nor HAVING are set.  It could still be a grouped
      // query if there are aggregate calls in outputs or order by though...
      try {
        outputs.foreach(checkPregroupExpression("selected columns", _))
      } catch {
        case _: AggregateInUngroupedContext =>
          checkGrouped(outputs, where, Nil, None, orderBy)
          return true
      }
      where.foreach(checkPregroupExpression("WHERE", _))
      try {
        orderBy.foreach(checkPregroupExpression("ORDER BY", _))
      } catch {
        case _: AggregateInUngroupedContext =>
          checkGrouped(outputs, where, Nil, None, orderBy)
          return true
      }
      false
    }
  }

  def checkGrouped(outputs: Seq[Expr], where: Option[Expr], groupBy: Seq[Expr], having: Option[Expr], orderBy: Seq[Expr]) {
    outputs.foreach(checkPostgroupExpression("selected columns", _, groupBy))
    where.foreach(checkPregroupExpression("WHERE", _))
    groupBy.foreach(checkPregroupExpression("GROUP BY", _))
    having.foreach(checkPostgroupExpression("HAVING", _, groupBy))
    orderBy.foreach(checkPostgroupExpression("ORDER BY", _, groupBy))
  }

  def checkPregroupExpression(clause: String, e: Expr) {
    e match {
      case FunctionCall(function, _) if function.isAggregate =>
        throw new AggregateInUngroupedContext(function.name, clause, e.position)
      case FunctionCall(_, params) =>
        params.foreach(checkPregroupExpression(clause, _))
      case _: ColumnRef[_] | _: TypedLiteral[_] =>
        // ok, these are always good
    }
  }

  def checkPostgroupExpression(clause: String, e: Expr, groupExpressions: Iterable[Expr]) {
    if(!isGroupExpression(e, groupExpressions)) {
      e match {
        case FunctionCall(function, params) if function.isAggregate =>
          params.foreach(checkPregroupExpression(clause, _))
        case FunctionCall(_, params) =>
          params.foreach(checkPostgroupExpression(clause, _, groupExpressions))
        case _: TypedLiteral[_] =>
          // ok, this is always good
        case col: ColumnRef[_] =>
          throw new ColumnNotInGroupBys(col.column, col.position)
      }
    }
  }

  def isGroupExpression(e: TypedFF[Type], groupExpressions: Iterable[TypedFF[Type]]): Boolean =
    // This function doesn't take a Set because, while we're doing set-membership, we don't want to
    // re-compute our hashcodes over and over and over.  This should also be a "small" set.  So let's
    // just do a linear probe.
    groupExpressions.exists(_ == e)
}
