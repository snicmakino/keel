package keel

import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
fun readFileAsString(path: String): String {
    val fp = fopen(path, "r") ?: throw ConfigParseException("could not read $path")
    try {
        val chunks = mutableListOf<String>()
        memScoped {
            val buffer = allocArray<ByteVar>(4096)
            while (true) {
                val bytesRead = fread(buffer, 1u, 4096u, fp)
                if (bytesRead == 0uL) break
                chunks.add(buffer.readBytes(bytesRead.toInt()).decodeToString())
            }
        }
        return chunks.joinToString("")
    } finally {
        fclose(fp)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun fileExists(path: String): Boolean {
    return access(path, F_OK) == 0
}

@OptIn(ExperimentalForeignApi::class)
fun ensureDirectory(path: String) {
    if (!fileExists(path)) {
        mkdir(path, 0b111111101u) // 0755
    }
}

@OptIn(ExperimentalForeignApi::class)
fun eprintln(msg: String) {
    val bytes = (msg + "\n").encodeToByteArray()
    bytes.usePinned { pinned ->
        write(STDERR_FILENO, pinned.addressOf(0), bytes.size.toULong())
    }
}
