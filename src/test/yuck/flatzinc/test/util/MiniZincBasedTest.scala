package yuck.flatzinc.test.util

import scala.collection._
import spray.json._
import yuck.BuildInfo
import yuck.annealing._
import yuck.core._
import yuck.flatzinc.ast._
import yuck.flatzinc.compiler.FlatZincCompilerResult
import yuck.flatzinc.parser._
import yuck.flatzinc.runner._
import yuck.util.arm._
import yuck.util.logging.FineLogLevel
import yuck.util.testing.{IntegrationTest, ProcessRunner}

/**
 * @author Michael Marte
 *
 */
class MiniZincBasedTest extends IntegrationTest {

    private abstract class JsNode {
        def value: JsValue
    }
    private class JsEntry(override val value: JsValue) extends JsNode
    private object JsEntry {
        def apply(value: JsValue): JsEntry = new JsEntry(value)
    }
    private class JsSection extends JsNode {
        private val fields = new mutable.HashMap[String, JsNode]
        override def value =
            JsObject(fields.iterator.map{case (name, node) => (name, node.value)}.to(immutable.TreeMap))
        def +=(field: (String, JsNode)): JsSection = {
            fields += field
            this
        }
        def ++=(fields: IterableOnce[(String, JsNode)]): JsSection = {
            this.fields ++= fields
            this
        }
    }
    private object JsSection {
        def apply(fields: (String, JsValue)*): JsSection =
            new JsSection ++= fields.iterator.map{case (name, value) => (name, JsEntry(value))}
    }

    private val jsonRoot = new JsSection
    private val envNode = new JsSection

    protected def solveWithResult(task: MiniZincTestTask): Result = {
        solve(task.copy(reusePreviousTestResult = false)).get
    }

    // Asserts when something went wrong.
    // Returns None when task.reusePreviousTestResult is true and the instance was already processed.
    protected def solve(task: MiniZincTestTask): Option[Result] = {
        logger.setThresholdLogLevel(task.logLevel)
        jsonRoot += "env" -> envNode
        logOsVersion
        logJavaVersion
        logYuckVersion
        val suitePath = task.suitePath
        val suiteName = if (task.suiteName.isEmpty) new java.io.File(suitePath).getName else task.suiteName
        val problemName = task.problemName
        val modelName = if (task.modelName.isEmpty) problemName else task.modelName
        val instanceName = if (task.instanceName.isEmpty) modelName else task.instanceName
        val (mznFilePath, dznFilePath, outputDirectoryPath) = task.directoryLayout match {
            case MiniZincExamplesLayout =>
                ("%s/problems/%s.mzn".format(suitePath, problemName),
                 "",
                 "tmp/%s/%s".format(suiteName, problemName))
            case StandardMiniZincBenchmarksLayout =>
                ("%s/problems/%s/%s.mzn".format(suitePath, problemName, modelName),
                 "%s/problems/%s/%s.dzn".format(suitePath, problemName, instanceName),
                 "tmp/%s/%s/%s/%s".format(suiteName, problemName, modelName, instanceName))
            case NonStandardMiniZincBenchmarksLayout =>
                ("%s/problems/%s/%s.mzn".format(suitePath, problemName, instanceName),
                 "",
                 "tmp/%s/%s/%s".format(suiteName, problemName, instanceName))
        }
        new java.io.File(outputDirectoryPath).mkdirs
        val fznFilePath = "%s/problem.fzn".format(outputDirectoryPath)
        val logFilePath = "%s/yuck.log".format(outputDirectoryPath)
        val jsonFilePath = "%s/yuck.json".format(outputDirectoryPath)
        if (task.reusePreviousTestResult && new java.io.File(jsonFilePath).exists() && ! task.assertWhenUnsolved) {
            None
        } else {
            val logFileHandler = new java.util.logging.FileHandler(logFilePath)
            logFileHandler.setFormatter(formatter)
            nativeLogger.addHandler(logFileHandler)
            logger.log("Processing %s".format(mznFilePath))
            logger.log("Logging into %s".format(logFilePath))
            try {
                val result =
                    trySolve(
                        task.copy(suiteName = suiteName, modelName = modelName, instanceName = instanceName),
                        mznFilePath, dznFilePath, fznFilePath, jsonFilePath)
                Some(result)
            }
            catch {
                case error: Throwable =>
                    val cause = findUltimateCause(error)
                    val errorNode = new JsSection
                    errorNode += "type" -> JsEntry(JsString(cause.getClass.getName))
                    if (cause.getMessage != null && ! cause.getMessage.isEmpty) {
                        errorNode += "message" -> JsEntry(JsString(cause.getMessage))
                    }
                    jsonRoot += "error" -> errorNode
                    handleException(task, cause)
                    None
            }
            finally {
                val jsonDoc = jsonRoot.value
                val jsonWriter = new java.io.FileWriter(jsonFilePath)
                jsonWriter.write(jsonDoc.prettyPrint)
                jsonWriter.close
            }
        }
    }

    private def trySolve(
        task: MiniZincTestTask,
        mznFilePath: String, dznFilePath: String, fznFilePath: String, jsonFilePath: String): Result =
    {
        val mzn2fznCommand = mutable.ArrayBuffer(
            "minizinc",
            "-v",
            "-c",
            "--solver", "org.minizinc.mzn-fzn",
            "-I", "resources/mzn/lib/yuck",
            "--no-output-ozn", "--output-fzn-to-file", fznFilePath)
        mzn2fznCommand += mznFilePath
        if (! dznFilePath.isEmpty) mzn2fznCommand += dznFilePath
        val (_, errorLines) =
            logger.withTimedLogScope("Flattening MiniZinc model") {
                logger.withRootLogLevel(FineLogLevel) {
                    new ProcessRunner(logger, mzn2fznCommand).call
                }
            }
        logMiniZincVersion(errorLines.head)
        val cfg =
            task.solverConfiguration.copy(
                restartLimit =
                    scala.math.min(
                        task.solverConfiguration.restartLimit,
                        task.maybeRestartLimit.getOrElse(Int.MaxValue)),
                numberOfThreads =
                    scala.math.min(
                        task.solverConfiguration.numberOfThreads,
                        task.maybeMaximumNumberOfThreads.getOrElse(Int.MaxValue)),
                maybeRoundLimit = task.maybeRoundLimit,
                maybeRuntimeLimitInSeconds = task.maybeRuntimeLimitInSeconds,
                maybeTargetObjectiveValue = task.maybeOptimum,
                maybeQualityTolerance = task.maybeQualityTolerance)
        val monitor = new StandardAnnealingMonitor(logger)
        val result =
            scoped(new ManagedShutdownHook({logger.log("Received SIGINT"); sigint.set})) {
                maybeTimeboxed(cfg.maybeRuntimeLimitInSeconds, sigint, "solver", logger) {
                    val ast =
                        logger.withTimedLogScope("Parsing FlatZinc file") {
                            new FlatZincFileParser(fznFilePath, logger).call
                        }
                    logTask(task, ast)
                    logFlatZincModelStatistics(ast)
                    scoped(monitor) {
                        new FlatZincSolverGenerator(ast, cfg, sigint, logger, monitor).call.call
                    }
                }
            }
        logger.log("Quality of best proposal: %s".format(result.costsOfBestProposal))
        logger.log("Best proposal was produced by: %s".format(result.solverName))
        if (! result.isSolution) {
            logger.withRootLogLevel(yuck.util.logging.FinerLogLevel) {
                logger.withLogScope("Violated constraints") {
                    logViolatedConstraints(result)
                }
            }
        }
        logger.withLogScope("Best proposal") {
            new FlatZincResultFormatter(result).call.foreach(logger.log(_))
        }
        logYuckModelStatistics(result.space)
        logResult(result)
        logSolverStatistics(monitor)
        assert(
            "No solution found, quality of best proposal was %s".format(result.costsOfBestProposal),
            result.isSolution || ! task.assertWhenUnsolved)
        if (result.isSolution && task.verifySolution) {
            logger.withTimedLogScope("Verifying solution") {
                logger.withRootLogLevel(FineLogLevel) {
                    assert(
                        "Solution not verified",
                        new MiniZincSolutionVerifier(task, result, logger).call)
                }
            }
        }
        result
    }

    private def logTask(task: MiniZincTestTask, ast: FlatZincAst): Unit = {
        val problemType =
            ast.solveGoal match {
                case Satisfy(_) => "SAT"
                case Minimize(_, _) => "MIN"
                case Maximize(_, _) => "MAX"
            }
        val taskNode = JsSection(
            "suite" -> JsString(task.suiteName),
            "problem" -> JsString(task.problemName),
            "model" -> JsString(task.modelName),
            "instance" -> JsString(task.instanceName),
            "problem-type" -> JsString(problemType)
        )
        if (task.maybeOptimum.isDefined) {
            taskNode += "optimum" -> JsEntry(JsNumber(task.maybeOptimum.get))
        }
        if (task.maybeHighScore.isDefined) {
            taskNode += "high-score" -> JsEntry(JsNumber(task.maybeHighScore.get))
        }
        jsonRoot += "task" -> taskNode
    }

    private def logOsVersion: Unit = {
        envNode +=
            "os" -> JsSection(
                "arch" -> JsString(System.getProperty("os.arch", "")),
                "name" -> JsString(System.getProperty("os.name", "")),
                "version" -> JsString(System.getProperty("os.version", ""))
            )
    }

    private def logJavaVersion: Unit = {
        val runtime = java.lang.Runtime.getRuntime
        envNode +=
            "java" -> JsSection(
                "vm" -> JsString(System.getProperty("java.vm.name", "")),
                "vendor" -> JsString(System.getProperty("java.vendor", "")),
                "version" -> JsString(System.getProperty("java.version", "")),
                "date" -> JsString(System.getProperty("java.version.date", "")),
                "number-of-virtual-cores" -> JsNumber(runtime.availableProcessors),
                "max-memory" -> JsNumber(runtime.maxMemory)
            )
    }

    private def logYuckVersion: Unit = {
        envNode +=
            "yuck" -> JsSection(
                "gitBranch" -> JsString(BuildInfo.gitBranch),
                "gitCommitHash" -> JsString(BuildInfo.gitCommitHash))
    }

    private def logMiniZincVersion(versionInfo: String): Unit = {
        // MiniZinc to FlatZinc converter, version 2.3.1, build 70205949
        val pattern = java.util.regex.Pattern.compile(".*, version ([\\.\\d]+), build (\\d+)")
        val matcher = pattern.matcher(versionInfo)
        if (matcher.matches) {
            envNode +=
                "minizinc" -> JsSection(
                    "version" -> JsString(matcher.group(1)),
                    "build" -> JsString(matcher.group(2))
                )
        }
    }

    private def logFlatZincModelStatistics(ast: FlatZincAst): Unit = {
        jsonRoot +=
            "flatzinc-model-statistics" -> JsSection(
                "number-of-predicate-declarations" -> JsNumber(ast.predDecls.size),
                "number-of-parameter-declarations" -> JsNumber(ast.paramDecls.size),
                "number-of-variable-declarations" -> JsNumber(ast.varDecls.size),
                "number-of-constraints" -> JsNumber(ast.constraints.size)
            )
    }

    private def logYuckModelStatistics(space: Space): Unit = {
        jsonRoot +=
            "yuck-model-statistics" -> JsSection(
                "number-of-search-variables" -> JsNumber(space.searchVariables.size),
                "number-of-channel-variables" -> JsNumber(space.channelVariables.size),
                // dangling variables are not readily available
                "number-of-constraints" -> JsNumber(space.numberOfConstraints),
                "number-of-implicit-constraints" -> JsNumber(space.numberOfImplicitConstraints)
            )
    }

    private def logResult(result: Result): Unit = {
        val compilerResult = result.maybeUserData.get.asInstanceOf[FlatZincCompilerResult]
        val resultNode = new JsSection
        resultNode += "solved" -> JsEntry(JsBoolean(result.isSolution))
        if (! result.isSolution) {
            resultNode += "violation" -> JsEntry(JsNumber(result.bestProposal.value(compilerResult.costVar).violation))
        }
        if (compilerResult.maybeObjectiveVar.isDefined) {
            resultNode += "quality" -> JsEntry(JsNumber(result.bestProposal.value(compilerResult.maybeObjectiveVar.get).value))
        }
        jsonRoot += "result" -> resultNode
    }

    private def logSolverStatistics(monitor: StandardAnnealingMonitor): Unit = {
        if (monitor.wasSearchRequired) {
            jsonRoot +=
                "solver-statistics" -> JsSection(
                    "number-of-restarts" -> JsNumber(monitor.numberOfRestarts),
                    "runtime-in-seconds" -> JsNumber(monitor.runtimeInSeconds),
                    "moves-per-second" -> JsNumber(monitor.movesPerSecond),
                    "consultations-per-second" -> JsNumber(monitor.consultationsPerSecond),
                    "consultations-per-move" -> JsNumber(monitor.consultationsPerMove),
                    "commitments-per-second" -> JsNumber(monitor.commitmentsPerSecond),
                    "commitments-per-move" -> JsNumber(monitor.commitmentsPerMove)
                )
        }
    }

    private def logViolatedConstraints(result: Result): Unit = {
        val visited = new mutable.HashSet[AnyVariable]
        val compilerResult = result.maybeUserData.get.asInstanceOf[FlatZincCompilerResult]
        result.space.definingConstraint(compilerResult.costVar).get match {
            case sum: yuck.constraints.Sum[BooleanValue @ unchecked] =>
                for (x <- sum.xs if result.space.searchState.value(x) > True) {
                    logViolatedConstraints(result, x, visited)
                }
        }
    }

    private def logViolatedConstraints(
        result: Result, x: AnyVariable, visited: mutable.Set[AnyVariable]): Unit =
    {
        val a = result.bestProposal.anyValue(x)
        if (! visited.contains(x)) {
            visited += x
            val maybeConstraint = result.space.definingConstraint(x)
            if (maybeConstraint.isDefined) {
                val constraint = maybeConstraint.get
                logger.withLogScope("%s = %s computed by %s [%s]".format(x, a, constraint, constraint.maybeGoal)) {
                    for (x <- constraint.inVariables) {
                        logViolatedConstraints(result, x, visited)
                    }
                }
             } else if (! result.space.isProblemParameter(x)) {
                logger.logg("%s = %s".format(x, a, visited))
            }
        }
    }

    private def handleException(task: MiniZincTestTask, error: Throwable) = error match {
        case error: FlatZincParserException =>
            nativeLogger.info(error.getMessage)
            throw error
        case error: InconsistentProblemException =>
            nativeLogger.info(error.getMessage)
            nativeLogger.info(FlatZincInconsistentProblemIndicator)
            throw error
        case error: SolverInterruptedException =>
            nativeLogger.info(error.getMessage)
            nativeLogger.info(FlatZincNoSolutionFoundIndicator)
            assert(error.getMessage, ! task.assertWhenUnsolved)
        case error: Throwable =>
            nativeLogger.log(java.util.logging.Level.SEVERE, "", error)
            throw error
    }

    private def findUltimateCause(error: Throwable): Throwable =
        if (error.getCause == null) error else findUltimateCause(error.getCause)

}
