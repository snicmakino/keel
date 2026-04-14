package kolt.infra.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.memset
import platform.posix.socklen_t

// Linux caps AF_UNIX pathnames at 108 bytes including the NUL terminator;
// see unix(7). Shared between UnixSocket (production) and UnixEchoServer
// (test fixture) so both agree on the same pre-flight length check.
internal const val SUN_PATH_CAPACITY = 108

/**
 * Populate a pre-allocated [sockaddr_un] with an AF_UNIX filesystem path
 * and return the matching `socklen_t` for `connect` / `bind` calls.
 *
 * Callers must ensure [pathBytes] is strictly shorter than
 * [SUN_PATH_CAPACITY] (room for the trailing NUL) — the oversize case is
 * reported with path-specific error variants at the call sites, so this
 * helper does not re-validate.
 *
 * The returned length is `offsetof(sockaddr_un, sun_path) + strlen + 1`
 * rather than `sizeof(sockaddr_un)`, keeping the door open for Linux
 * abstract sockets (leading NUL in `sun_path`) later.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun fillSockaddrUn(addr: sockaddr_un, pathBytes: ByteArray): socklen_t {
    memset(addr.ptr, 0, sizeOf<sockaddr_un>().convert())
    addr.sun_family = AF_UNIX.convert()
    val sunPath = addr.sun_path
    for (i in pathBytes.indices) {
        sunPath[i] = pathBytes[i]
    }
    sunPath[pathBytes.size] = 0
    val sunPathOffset = sunPath.rawValue.toLong() - addr.ptr.rawValue.toLong()
    return (sunPathOffset.toInt() + pathBytes.size + 1).convert()
}
