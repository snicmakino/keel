package keel.resolve

import keel.infra.DownloadError
import keel.infra.Sha256Error
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolveErrorFormatTest {

    @Test
    fun invalidDependencyFormat() {
        val error = ResolveError.InvalidDependency("bad:dep")
        assertEquals("error: invalid dependency 'bad:dep'", formatResolveError(error))
    }

    @Test
    fun sha256MismatchIncludesExpectedAndActual() {
        val error = ResolveError.Sha256Mismatch(
            groupArtifact = "com.example:lib",
            expected = "abc123",
            actual = "def456"
        )
        val result = formatResolveError(error)
        assertTrue(result.contains("sha256 mismatch for com.example:lib"))
        assertTrue(result.contains("expected: abc123"))
        assertTrue(result.contains("got:      def456"))
    }

    @Test
    fun downloadFailedFormat() {
        val error = ResolveError.DownloadFailed(
            "com.example:lib",
            DownloadError.NetworkError("https://example.com", "timeout")
        )
        assertEquals("error: failed to download com.example:lib", formatResolveError(error))
    }

    @Test
    fun hashComputeFailedFormat() {
        val error = ResolveError.HashComputeFailed(
            "com.example:lib",
            Sha256Error("/path/to/file")
        )
        assertEquals("error: failed to compute hash for com.example:lib", formatResolveError(error))
    }

    @Test
    fun directoryCreateFailedFormat() {
        val error = ResolveError.DirectoryCreateFailed("/cache/dir")
        assertEquals("error: could not create directory /cache/dir", formatResolveError(error))
    }
}
