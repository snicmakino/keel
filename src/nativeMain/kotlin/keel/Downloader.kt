package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import platform.posix.*

sealed class DownloadError {
    data class HttpFailed(val url: String, val statusCode: Int) : DownloadError()
    data class WriteFailed(val path: String) : DownloadError()
    data class NetworkError(val url: String, val message: String) : DownloadError()
}

@OptIn(ExperimentalForeignApi::class)
fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
    return withHttpClient { client -> downloadFileWith(client, url, destPath) }
}

@OptIn(ExperimentalForeignApi::class)
fun downloadFileWith(client: HttpClient, url: String, destPath: String): Result<Unit, DownloadError> {
    return runBlocking {
        val response = try {
            client.get(url)
        } catch (e: Exception) {
            return@runBlocking Err(DownloadError.NetworkError(url, e.message ?: "unknown error"))
        }
        if (!response.status.isSuccess()) {
            return@runBlocking Err(DownloadError.HttpFailed(url, response.status.value))
        }
        val bytes = response.readRawBytes()
        val fp = fopen(destPath, "wb")
            ?: return@runBlocking Err(DownloadError.WriteFailed(destPath))
        var writeSuccess = false
        try {
            val written = bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), fp)
            }
            writeSuccess = (written == bytes.size.toULong())
        } finally {
            fclose(fp)
            if (!writeSuccess) remove(destPath)
        }
        if (!writeSuccess) {
            return@runBlocking Err(DownloadError.WriteFailed(destPath))
        }
        Ok(Unit)
    }
}

fun <T> withHttpClient(block: (HttpClient) -> T): T {
    val client = HttpClient(Curl)
    try {
        return block(client)
    } finally {
        client.close()
    }
}
