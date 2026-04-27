package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.isValidProjectName
import kolt.infra.ensureDirectory
import kolt.infra.eprintln
import kolt.infra.fileExists

internal fun doNew(args: List<String>): Result<Unit, Int> {
  val parsed =
    parseInitArgs(args).getOrElse { msg ->
      eprintln("error: $msg")
      return Err(EXIT_CONFIG_ERROR)
    }

  val projectName =
    parsed.projectName
      ?: run {
        eprintln("error: kolt new requires a project name")
        eprintln("usage: kolt new <name> [--lib|--app] [--target <target>] [--group <group>]")
        return Err(EXIT_CONFIG_ERROR)
      }

  if (!isValidProjectName(projectName)) {
    eprintln("error: invalid project name '$projectName'")
    eprintln(
      "  project name must start with a letter or digit and contain only letters, digits, '.', '-', '_'"
    )
    return Err(EXIT_CONFIG_ERROR)
  }

  if (fileExists(projectName)) {
    eprintln("error: '$projectName' already exists")
    return Err(EXIT_CONFIG_ERROR)
  }

  ensureDirectory(projectName).getOrElse { error ->
    eprintln("error: could not create directory ${error.path}")
    return Err(EXIT_BUILD_ERROR)
  }

  return scaffoldProject(
    projectName,
    ScaffoldOptions(projectName, parsed.kind, parsed.target, parsed.group),
  )
}
