package yuck.constraints

import scala.collection._

import yuck.core._

/**
 * A data structure to provide a single task to a [[yuck.constraints.Cumulative Cumulative]] constraint.
 *
 * @author Michael Marte
 *
 */
final class CumulativeTask(
    val s: Variable[IntegerValue], val d: Variable[IntegerValue], val c: Variable[IntegerValue])
{
    override def toString = "(%s, %s, %s)".format(s, d, c)
}

/**
 * Implements the ''cumulative'' constraint as specified by MiniZinc.
 *
 * Keeps track of resource consumption for each time slot in order to provide the amount
 * of unsatisfied requirements (summed up over time) as measure of constraint violation.
 *
 * @author Michael Marte
 */
final class Cumulative
    (id: Id[Constraint], goal: Goal,
     tasks: immutable.Seq[CumulativeTask], ub: Variable[IntegerValue],
     costs: Variable[IntegerValue])
    extends Constraint(id, goal)
{

    override def toString = "cumulative([%s], %s, %s)".format(tasks.mkString(", "), ub, costs)
    override def inVariables = id2Task.keysIterator ++ List(ub).toIterator
    override def outVariables = List(costs)

    private val id2Task =
        new immutable.HashMap[AnyVariable, CumulativeTask] ++
        (tasks.map(_.s).zip(tasks)) ++ (tasks.map(_.d).zip(tasks)) ++ (tasks.map(_.c).zip(tasks))
    private val effects = List(new ReusableEffectWithFixedVariable[IntegerValue](costs))
    private val effect = effects.head
    private type Profile = immutable.HashMap[Int, Int] // time slot -> resource consumption
    private var currentProfile: Profile = null
    private var futureProfile: Profile = null
    private var currentCosts = 0
    private var futureCosts = 0
    private def computeCosts(profile: Profile, ub: Int): Int =
        profile.toIterator.map{case (_, c) => computeLocalCosts(c, ub)}.sum
    @inline private def computeLocalCosts(c: Int, ub: Int): Int = scala.math.max(c - ub, 0)

    override def initialize(now: SearchState) = {
        currentProfile = new Profile
        for (t <- tasks) {
            val s = now.value(t.s).value
            val d = now.value(t.d).value
            val c = now.value(t.c).value
            for (i <- s until s + d) {
                currentProfile = currentProfile.updated(i, currentProfile.get(i).getOrElse(0) + c)
            }
        }
        currentCosts = computeCosts(currentProfile, now.value(ub).value)
        assert(currentCosts >= 0)
        effect.a = IntegerValue.get(currentCosts)
        effects
    }

    override def consult(before: SearchState, after: SearchState, move: Move) = {
        futureProfile = currentProfile
        futureCosts = currentCosts
        val ubChanged = move.involves(ub)
        val visited = new mutable.HashSet[Variable[IntegerValue]] // start variable
        for (t <- move.involvedVariables.filter(x => x != ub).map(id2Task) if ! visited.contains(t.s)) {
            visited += t.s
            val s0 = before.value(t.s).value
            val d0 = before.value(t.d).value
            val c0 = before.value(t.c).value
            val ub0 = before.value(ub).value
            val s1 = after.value(t.s).value
            val d1 = after.value(t.d).value
            val c1 = after.value(t.c).value
            val ub1 = after.value(ub).value
            def subtractTask(i: Int) {
                val r0 = futureProfile(i)
                val r1 = r0 - c0
                futureProfile = futureProfile.updated(i, r1)
                if (! ubChanged) {
                    futureCosts -= computeLocalCosts(r0, ub0)
                    futureCosts += computeLocalCosts(r1, ub0)
                }
            }
            def addTask(i: Int) {
                val r0 = futureProfile.get(i).getOrElse(0)
                val r1 = r0 + c1
                futureProfile = futureProfile.updated(i, r1)
                if (! ubChanged) {
                    futureCosts -= computeLocalCosts(r0, ub1)
                    futureCosts += computeLocalCosts(r1, ub1)
                }
            }
            // When only the task start changes, the old and the new rectangle are expected to overlap
            // by about 50% on average.
            if (d0 == d1 && c0 == c1 && s1 > s0 && s1 < s0 + d0) {
                // duration and resource consumption did not change, old and new rectangles overlap
                // s0 ********** s0 + d0
                //       s1 ********** s1 + d1 (== d0)
                //          ^^^^
                //          Nothing changes where the rectangles overlap!
                for (i <- s0 until s1) subtractTask(i)
                for (i <- s0 + d0 until s1 + d1) addTask(i)
            }
            else if (d0 == d1 && c0 == c1 && s0 > s1 && s0 < s1 + d1) {
                // symmetrical case
                //       s0 ********** s0 + d0
                // s1 ********** s1 + d1 (== d0)
                //          ^^^^
                //          Nothing changes where the rectangles overlap!
                for (i <- s1 until s0) addTask(i)
                for (i <- s1 + d1 until s0 + d0) subtractTask(i)
            }
            else {
                for (i <- s0 until s0 + d0) subtractTask(i)
                for (i <- s1 until s1 + d1) addTask(i)
            }
        }
        if (ubChanged) {
            futureCosts = computeCosts(futureProfile, after.value(ub).value)
        }
        assert(futureCosts >= 0)
        effect.a = IntegerValue.get(futureCosts)
        effects
    }

    override def commit(before: SearchState, after: SearchState, move: Move) = {
        currentProfile = futureProfile
        currentCosts = futureCosts
        effects
    }

}