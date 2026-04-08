package keel

data class RunCommand(
    val args: List<String>,
    val jarPath: String
)

fun runCommand(config: KeelConfig, classpath: String? = null, appArgs: List<String> = emptyList()): RunCommand {
    val path = jarPath(config)
    val cp = if (!classpath.isNullOrEmpty()) "$path:$classpath" else path
    return RunCommand(
        args = listOf("java", "-cp", cp, config.main) + appArgs,
        jarPath = path
    )
}
