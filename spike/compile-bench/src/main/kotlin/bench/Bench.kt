package bench

import java.io.File
import kotlin.system.measureTimeMillis

fun main() {
    val jars = cpProp("kotlinc.classpath")
    val fixtureCp = cpProp("fixture.classpath")
    val sources = listOf(File("fixtures/Hello.kt").absoluteFile)
    check(sources.first().exists()) { "missing fixture: ${sources.first().path}" }

    val iterations = 10

    println("=== Scenario A: shared URLClassLoader (baseline, n=$iterations) ===")
    val shared = SharedLoaderCompileDriver(jars, fixtureCp)
    val sharedTimes = runScenario(iterations) { shared.compile(sources, freshOutputDir()) }
    reportTimes(sharedTimes)

    System.gc()
    Thread.sleep(200)

    println()
    println("=== Scenario B: fresh URLClassLoader per compile (isolated, n=$iterations) ===")
    val iso = IsolatingCompileDriver(jars, fixtureCp)
    val isoTimes = runScenario(iterations) { iso.compile(sources, freshOutputDir()) }
    reportTimes(isoTimes)

    System.gc()
    Thread.sleep(200)

    val longN = 50
    println()
    println("=== Scenario C: shared loader long run (n=$longN, watch-mode simulation) ===")
    val sharedC = SharedLoaderCompileDriver(jars, fixtureCp)
    val memBefore = usedHeapMb()
    val longTimes = runScenario(longN) { sharedC.compile(sources, freshOutputDir()) }
    System.gc()
    Thread.sleep(200)
    val memAfter = usedHeapMb()
    reportTimes(longTimes)
    println("  heap before: $memBefore MB")
    println("  heap after : $memAfter MB")
    println("  heap delta : ${memAfter - memBefore} MB")
    val firstQuarter = longTimes.drop(1).take(longN / 4).average()
    val lastQuarter = longTimes.takeLast(longN / 4).average()
    println("  early warm avg : ${firstQuarter.toLong()} ms")
    println("  late  warm avg : ${lastQuarter.toLong()} ms")
    println("  drift (late - early): ${(lastQuarter - firstQuarter).toLong()} ms")

    System.gc()
    Thread.sleep(200)

    val rotatingDir = File("fixtures/rotating").absoluteFile
    val rotatingSources = rotatingDir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }
        ?.sortedBy { it.name }
        ?: error("missing rotating fixtures dir: ${rotatingDir.path}")
    check(rotatingSources.size >= 10) {
        "expected at least 10 rotating fixtures, got ${rotatingSources.size}"
    }

    // n=50 mirrors Scenario C for apples-to-apples comparison. Note that with 10 fixtures
    // the first and last quarters (12 samples each) see slightly different fixture mixes;
    // the rotation/JIT noise floor is larger than per-fixture variance so this is tolerated.
    val rotatingN = 50
    println()
    println("=== Scenario D: shared loader rotating fixtures (n=$rotatingN, ${rotatingSources.size} files) ===")
    val sharedD = SharedLoaderCompileDriver(jars, fixtureCp)
    val memBeforeD = usedHeapMb()
    val rotatingTimes = List(rotatingN) { i ->
        var result: CompileResult? = null
        val source = listOf(rotatingSources[i % rotatingSources.size])
        val ms = measureTimeMillis { result = sharedD.compile(source, freshOutputDir()) }
        check(result is CompileResult.Ok) { "rotating compile failed at i=$i (${source.first().name}): $result" }
        ms
    }
    System.gc()
    Thread.sleep(200)
    val memAfterD = usedHeapMb()
    reportTimes(rotatingTimes)
    println("  heap before: $memBeforeD MB")
    println("  heap after : $memAfterD MB")
    println("  heap delta : ${memAfterD - memBeforeD} MB")
    val firstQuarterD = rotatingTimes.drop(1).take(rotatingN / 4).average()
    val lastQuarterD = rotatingTimes.takeLast(rotatingN / 4).average()
    println("  early warm avg : ${firstQuarterD.toLong()} ms")
    println("  late  warm avg : ${lastQuarterD.toLong()} ms")
    println("  drift (late - early): ${(lastQuarterD - firstQuarterD).toLong()} ms")

    val sharedWarm = sharedTimes.drop(1).average()
    val isoWarm = isoTimes.drop(1).average()
    val overhead = (isoWarm - sharedWarm).toLong()
    val gate = 1000L
    val verdictB = if (overhead < gate) "PASS" else "FAIL"
    val driftGate = 500L
    val verdictC = if ((lastQuarter - firstQuarter) < driftGate && (memAfter - memBefore) < 200) "PASS" else "FAIL"

    val rotatingWarm = rotatingTimes.drop(1).average()
    // Gate D against C (same n=50, same shared loader, single hot file) so the ratio
    // isolates the rotation effect. A (n=10 Hello.kt) is kept as info only — comparing
    // rotating non-trivial fixtures to a trivial stub would measure fixture size, not
    // shared-loader reuse under rotation.
    val longWarm = longTimes.drop(1).average()
    val rotatingRatio = rotatingWarm / longWarm
    val rotatingRatioA = rotatingWarm / sharedWarm
    // Signed drift: we only want to FAIL on late-window regressions (leak / fragmentation).
    // A strongly negative drift means the JIT is still improving at iteration ~40, which
    // is healthy — do not switch this to abs().
    val rotatingDrift = (lastQuarterD - firstQuarterD)
    val rotatingHeapDelta = memAfterD - memBeforeD
    val rotatingRatioGate = 2.0
    val rotatingDriftGate = 200L
    val rotatingHeapGate = 50L
    val verdictD = if (
        rotatingRatio < rotatingRatioGate &&
        rotatingDrift < rotatingDriftGate &&
        rotatingHeapDelta < rotatingHeapGate
    ) "PASS" else "FAIL"

    println()
    println("=== Verdict ===")
    println("Scenario A (shared warm avg): ${sharedWarm.toLong()} ms")
    println("Scenario B (isolated warm avg): ${isoWarm.toLong()} ms")
    println("Scenario B overhead: $overhead ms  ->  kill criterion < $gate ms  ->  $verdictB")
    println("Scenario C drift: ${(lastQuarter - firstQuarter).toLong()} ms, heap delta: ${memAfter - memBefore} MB  ->  $verdictC")
    println("Scenario D (rotating warm avg): ${rotatingWarm.toLong()} ms")
    println(
        "Scenario D ratio vs C: ${"%.2f".format(rotatingRatio)}x (gate < ${rotatingRatioGate}x), " +
            "drift: ${rotatingDrift.toLong()} ms (gate < $rotatingDriftGate ms), " +
            "heap delta: $rotatingHeapDelta MB (gate < $rotatingHeapGate MB)  ->  $verdictD"
    )
    println("Scenario D ratio vs A (info only): ${"%.2f".format(rotatingRatioA)}x")
}

private fun usedHeapMb(): Long {
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
}

private fun runScenario(n: Int, block: () -> CompileResult): List<Long> = List(n) {
    var result: CompileResult? = null
    val ms = measureTimeMillis { result = block() }
    check(result is CompileResult.Ok) { "compile failed: $result" }
    ms
}

private fun reportTimes(ms: List<Long>) {
    val cold = ms.first()
    val warm2 = ms.getOrNull(1) ?: -1L
    val sorted = ms.sorted()
    val median = if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2]
    } else {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
    val avgWarm = ms.drop(1).average().toLong()
    println("  cold   : $cold ms")
    println("  warm-2 : $warm2 ms")
    println("  median : $median ms")
    println("  warm avg (excl. cold): $avgWarm ms")
    println("  all    : $ms")
}

private fun freshOutputDir(): File {
    val d = File.createTempFile("bench-out-", "").apply { delete() }
    d.mkdirs()
    d.deleteOnExit()
    return d
}

private fun cpProp(key: String): List<File> =
    (System.getProperty(key) ?: error("$key not set"))
        .split(File.pathSeparatorChar).map(::File)
