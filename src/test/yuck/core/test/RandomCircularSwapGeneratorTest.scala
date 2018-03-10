package yuck.core.test

import org.junit._

import yuck.core._
import yuck.util.testing.UnitTest

/**
 * @author Michael Marte
 *
 */
@Test
@FixMethodOrder(runners.MethodSorters.NAME_ASCENDING)
@runner.RunWith(classOf[runners.Parameterized])
final class RandomCircularSwapGeneratorTest
    (randomGenerator: RandomGenerator,
     moveSizeDistribution: Distribution,
     maybeHotSpotDistribution: Option[Distribution],
     maybeFairVariableChoiceRate: Option[Probability],
     numberOfVariables: Int)
    extends UnitTest
{

    private val domains =
        for (i <- 0 until numberOfVariables) yield new IntegerRange(Zero, IntegerValue.get(numberOfVariables - 1))
    private val (space, xs) =
        NeighbourhoodTestHelper.createSpace(logger, randomGenerator, domains)
    private val helper =
        new NeighbourhoodTestHelper(logger, xs, moveSizeDistribution, maybeHotSpotDistribution, maybeFairVariableChoiceRate)

    @Test
    def testNeighbourhood {
        val neighbourhood =
            new RandomCircularSwapGenerator(
                space, xs, randomGenerator, moveSizeDistribution, maybeHotSpotDistribution, maybeFairVariableChoiceRate)
        val result = helper.measure(neighbourhood)
        helper.checkMoveSizeFrequencies(result, 0.1, 0)
        helper.checkVariableFrequencies(result, 0.1, 0)
    }

}

/**
 * @author Michael Marte
 *
 */
final object RandomCircularSwapGeneratorTest extends NeighbourhoodTestGenerator {

    import DistributionFactory.createDistribution

    override protected val moveSizeDistributions =
        List(List(0, 90, 10), List(0, 50, 35, 10)).map(createDistribution(1, _))

}
