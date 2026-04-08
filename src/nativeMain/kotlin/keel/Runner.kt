package keel

data class RunCommand(
    val args: List<String>,
    val jarPath: String
)

fun runCommand(config: KeelConfig): RunCommand {
    val path = jarPath(config)
    return RunCommand(
        args = listOf("java", "-jar", path),
        jarPath = path
    )
}
