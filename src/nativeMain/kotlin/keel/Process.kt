package keel

import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
fun executeCommand(args: List<String>): Int {
    if (args.isEmpty()) return -1

    val pid = fork()
    if (pid < 0) {
        return -1
    }
    if (pid == 0) {
        // child process
        memScoped {
            val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
            for (i in args.indices) {
                argv[i] = args[i].cstr.ptr
            }
            argv[args.size] = null
            execvp(args[0], argv)
            // execvp only returns on error
            _exit(127)
        }
    }
    // parent process
    memScoped {
        val status = alloc<IntVar>()
        while (waitpid(pid, status.ptr, 0) == -1) {
            if (errno != EINTR) return -1
        }
        val raw = status.value
        // WIFEXITED: (status & 0x7F) == 0
        return if ((raw and 0x7F) == 0) {
            (raw shr 8) and 0xFF  // WEXITSTATUS
        } else {
            -1  // killed by signal
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun executeAndCapture(command: String): Pair<Int, String> {
    val fp = popen(command, "r") ?: return Pair(-1, "")
    val output = StringBuilder()
    memScoped {
        val buffer = allocArray<ByteVar>(4096)
        while (true) {
            val line = fgets(buffer, 4096, fp) ?: break
            output.append(line.toKString())
        }
    }
    val status = pclose(fp)
    // WIFEXITED check
    val exitCode = if ((status and 0x7F) == 0) {
        (status shr 8) and 0xFF
    } else {
        -1
    }
    return Pair(exitCode, output.toString())
}
