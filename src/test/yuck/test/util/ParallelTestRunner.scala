package yuck.test.util

import org.junit.runners.BlockJUnit4ClassRunner

/**
 * @author Michael Marte
 *
 */
class ParallelTestRunner(val klass: Class[_]) extends BlockJUnit4ClassRunner(klass) {
    setScheduler(new ParallelTestScheduler)
}
