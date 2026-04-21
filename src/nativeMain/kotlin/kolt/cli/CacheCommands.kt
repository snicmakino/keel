package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.config.resolveKoltPaths
import kolt.infra.RemoveFailed
import kolt.infra.directorySize
import kolt.infra.eprintln
import kolt.infra.fileExists
import kolt.infra.formatBytes
import kolt.infra.removeDirectoryRecursive

internal data class CacheCleanArgs(val includeTools: Boolean)
internal data class CacheCleanSummary(val removedPaths: List<String>, val freedBytes: Long)

internal fun parseCacheCleanArgs(args: List<String>): Result<CacheCleanArgs, String> {
    var includeTools = false
    for (arg in args) {
        when (arg) {
            "--tools" -> includeTools = true
            else -> return Err("error: unknown flag '$arg'")
        }
    }
    return Ok(CacheCleanArgs(includeTools))
}

internal fun doCache(args: List<String>): Result<Unit, Int> {
    if (args.isEmpty()) {
        printCacheUsage()
        return Err(EXIT_CONFIG_ERROR)
    }
    return when (args[0]) {
        "clean" -> doCacheClean(args.drop(1))
        else -> {
            printCacheUsage()
            Err(EXIT_CONFIG_ERROR)
        }
    }
}

internal fun cleanCacheDirs(paths: KoltPaths, includeTools: Boolean): Result<CacheCleanSummary, RemoveFailed> {
    val targets = mutableListOf(paths.cacheBase)
    if (includeTools) targets.add(paths.toolsDir)

    val removed = mutableListOf<String>()
    var freed = 0L
    for (target in targets) {
        if (!fileExists(target)) continue
        freed += directorySize(target)
        removeDirectoryRecursive(target).getOrElse { return Err(it) }
        removed.add(target)
    }
    return Ok(CacheCleanSummary(removed, freed))
}

private fun doCacheClean(args: List<String>): Result<Unit, Int> {
    val parsed = parseCacheCleanArgs(args).getOrElse { error ->
        eprintln(error)
        printCacheUsage()
        return Err(EXIT_CONFIG_ERROR)
    }

    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_CONFIG_ERROR) }

    val summary = cleanCacheDirs(paths, parsed.includeTools).getOrElse { err ->
        eprintln("error: could not remove ${err.path}")
        return Err(EXIT_BUILD_ERROR)
    }

    for (path in summary.removedPaths) println("removed $path")
    if (summary.freedBytes == 0L) {
        println("nothing to clean")
    } else {
        println("freed ${formatBytes(summary.freedBytes)}")
    }
    return Ok(Unit)
}

private fun printCacheUsage() {
    eprintln("usage: kolt cache <subcommand>")
    eprintln("")
    eprintln("subcommands:")
    eprintln("  clean         Remove the global dependency cache")
    eprintln("")
    eprintln("flags:")
    eprintln("  --tools       Also remove cached tools (ktfmt, junit-console)")
}
