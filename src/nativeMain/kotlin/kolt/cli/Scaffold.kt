package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.config.ScaffoldKind
import kolt.config.generateGitignore
import kolt.config.generateKoltToml
import kolt.config.generateLibKt
import kolt.config.generateLibTestKt
import kolt.config.generateMainKt
import kolt.config.generateTestKt
import kolt.infra.ensureDirectory
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kolt.infra.executeCommandQuiet
import kolt.infra.fileExists
import kolt.infra.formatProcessError
import kolt.infra.writeFileAsString

internal data class ScaffoldOptions(
  val projectName: String,
  val kind: ScaffoldKind = ScaffoldKind.APP,
)

internal data class ParsedInitArgs(
  val projectName: String?,
  val kind: ScaffoldKind = ScaffoldKind.APP,
)

internal fun parseInitArgs(args: List<String>): Result<ParsedInitArgs, String> {
  var projectName: String? = null
  var kind: ScaffoldKind? = null
  for (arg in args) {
    when (arg) {
      "--lib",
      "--app" -> {
        val newKind = if (arg == "--lib") ScaffoldKind.LIB else ScaffoldKind.APP
        if (kind != null && kind != newKind) {
          return Err("--app and --lib are mutually exclusive")
        }
        kind = newKind
      }
      else -> {
        if (arg.startsWith("--")) return Err("unknown flag '$arg'")
        if (projectName != null) return Err("unexpected positional argument '$arg'")
        projectName = arg
      }
    }
  }
  return Ok(ParsedInitArgs(projectName, kind ?: ScaffoldKind.APP))
}

private const val SRC_DIR = "src"
private const val TEST_DIR = "test"
private const val GITIGNORE = ".gitignore"
private const val GIT_DIR = ".git"

internal fun scaffoldProject(targetDir: String, options: ScaffoldOptions): Result<Unit, Int> {
  // Contract: targetDir must already exist. doInit passes "." (always exists);
  // doNew creates `<name>/` itself before delegating, because the precondition
  // "<name>/ must not exist" cannot be enforced from inside ensureDirectory.
  if (!targetDir.isCurrent() && !fileExists(targetDir)) {
    eprintln("error: target directory '$targetDir' does not exist")
    return Err(EXIT_BUILD_ERROR)
  }

  val tomlPath = resolveInTarget(targetDir, KOLT_TOML)
  writeFileAsString(tomlPath, generateKoltToml(options.projectName, options.kind)).getOrElse { error
    ->
    eprintln("error: could not write ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }
  println("created $tomlPath")

  val srcDir = resolveInTarget(targetDir, SRC_DIR)
  if (!fileExists(srcDir)) {
    ensureDirectory(srcDir).getOrElse { error ->
      eprintln("error: could not create directory ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
  }

  val (sourceFileName, sourceContent) =
    when (options.kind) {
      ScaffoldKind.APP -> "Main.kt" to generateMainKt()
      ScaffoldKind.LIB -> "Lib.kt" to generateLibKt()
    }
  val sourcePath = "$srcDir/$sourceFileName"
  if (!fileExists(sourcePath)) {
    writeFileAsString(sourcePath, sourceContent).getOrElse { error ->
      eprintln("error: could not write ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
    println("created $sourcePath")
  }

  val testDir = resolveInTarget(targetDir, TEST_DIR)
  if (!fileExists(testDir)) {
    ensureDirectory(testDir).getOrElse { error ->
      eprintln("error: could not create directory ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
  }

  val (testFileName, testContent) =
    when (options.kind) {
      ScaffoldKind.APP -> "MainTest.kt" to generateTestKt()
      ScaffoldKind.LIB -> "LibTest.kt" to generateLibTestKt()
    }
  val testPath = "$testDir/$testFileName"
  if (!fileExists(testPath)) {
    writeFileAsString(testPath, testContent).getOrElse { error ->
      eprintln("error: could not write ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
    println("created $testPath")
  }

  val gitignorePath = resolveInTarget(targetDir, GITIGNORE)
  if (!fileExists(gitignorePath)) {
    writeFileAsString(gitignorePath, generateGitignore()).getOrElse { error ->
      eprintln("error: could not write ${error.path}")
      return Err(EXIT_BUILD_ERROR)
    }
    println("created $gitignorePath")
  }

  val gitDirPath = resolveInTarget(targetDir, GIT_DIR)
  if (!fileExists(gitDirPath) && !isInsideExistingGitWorktree(targetDir)) {
    val cmd =
      if (targetDir.isCurrent()) listOf("git", "init", "-q")
      else listOf("git", "-C", targetDir, "init", "-q")
    val err = executeCommand(cmd).getError()
    if (err == null) {
      println("initialized git repository")
    } else {
      eprintln("warning: could not run git init (${formatProcessError(err, "git init")})")
    }
  }

  println("initialized project '${options.projectName}'")
  return Ok(Unit)
}

private fun resolveInTarget(targetDir: String, name: String): String =
  if (targetDir.isCurrent()) name else "$targetDir/$name"

private fun String.isCurrent(): Boolean = isEmpty() || this == "."

// Without explicit suppression git prints "fatal: not a git repository ..."
// on the negative path (= the common "kolt init outside any worktree" case).
// `executeCommandQuiet` redirects child stderr to /dev/null so the
// expected non-zero-exit doesn't noise up the user's terminal. Any
// failure (missing git, permission denied) is treated as "not in a
// worktree" — false negatives are safe (we'd just create a fresh `.git/`);
// false positives would be worse.
private fun isInsideExistingGitWorktree(targetDir: String): Boolean {
  val cmd =
    if (targetDir.isCurrent()) listOf("git", "rev-parse", "--is-inside-work-tree")
    else listOf("git", "-C", targetDir, "rev-parse", "--is-inside-work-tree")
  return executeCommandQuiet(cmd).getError() == null
}
