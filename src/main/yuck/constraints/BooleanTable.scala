package yuck.constraints

import scala.collection._

import yuck.core._

/**
 * Given variables x[1], ..., x[n] and an m-by-n value matrix, the constraint
 * computes the distance of (s(x[1]), ..., s(x[n])) to each row of the matrix
 * and provides the minimum distance as measure of constraint violation.
 *
 * The distance of (a[1], ..., a[n]) to (b[1], ..., b[n]) is computed as
 *   bool2Int([a[1] == b[1]) + ... + bool2Int([a[n] - b[n]).
 *
 * @see [[yuck.Notation Notation]]
 *
 * @author Michael Marte
 */
final class BooleanTable
    (id: Id[Constraint], override val maybeGoal: Option[Goal],
     xs: immutable.IndexedSeq[BooleanVariable],
     rows: immutable.IndexedSeq[immutable.IndexedSeq[BooleanValue]],
     costs: BooleanVariable)
    extends Table(id, xs, rows, costs)
{
    override def createDomain(values: Set[BooleanValue]) =
        BooleanDecisionDomain.createDomain(values.contains(False), values.contains(True))
    override def computeDistance(a: BooleanValue, b: BooleanValue) = if (a.truthValue == b.truthValue) 0 else 1
}
