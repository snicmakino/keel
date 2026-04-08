package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.*
import platform.posix.*

data class OpenFailed(val path: String)

data class MkdirFailed(val path: String)

@OptIn(ExperimentalForeignApi::class)
fun readFileAsString(path: String): Result<String, OpenFailed> {
    val fp = fopen(path, "r") ?: return Err(OpenFailed(path))
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
        return Ok(chunks.joinToString(""))
    } finally {
        fclose(fp)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun fileExists(path: String): Boolean {
    return access(path, F_OK) == 0
}

@OptIn(ExperimentalForeignApi::class)
fun ensureDirectory(path: String): Result<Unit, MkdirFailed> {
    if (fileExists(path)) return Ok(Unit)
    return if (mkdir(path, 0b111111101u) == 0) { // 0755
        Ok(Unit)
    } else {
        Err(MkdirFailed(path))
    }
}

@OptIn(ExperimentalForeignApi::class)
fun eprintln(msg: String) {
    val bytes = (msg + "\n").encodeToByteArray()
    bytes.usePinned { pinned ->
        write(STDERR_FILENO, pinned.addressOf(0), bytes.size.toULong())
    }
}
