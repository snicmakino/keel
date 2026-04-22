package kolt.infra

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kotlinx.cinterop.*
import platform.linux.*
import platform.posix.*

sealed class InotifyError {
  data class InitFailed(val errno: Int) : InotifyError()

  data class WatchFailed(val path: String, val errno: Int) : InotifyError()

  data class WatchLimitExceeded(val path: String) : InotifyError()

  data class ReadFailed(val errno: Int) : InotifyError()
}

data class InotifyEvent(val wd: Int, val mask: UInt, val name: String)

private const val EVENT_BUF_SIZE = 4096
// struct inotify_event { int wd; uint32_t mask; uint32_t cookie; uint32_t len; char name[]; }
// = 16 bytes before the flexible array member (/usr/include/linux/inotify.h)
private const val EVENT_HEADER_SIZE = 16

internal val DEFAULT_WATCH_MASK: UInt =
  (IN_MODIFY or IN_CREATE or IN_DELETE or IN_MOVED_FROM or IN_MOVED_TO).toUInt()

internal val EXCLUDED_DIRS = setOf("build", ".git", ".gradle", "node_modules", ".idea")

class InotifyWatcher private constructor(private var fd: Int) {

  private val wdToPath = mutableMapOf<Int, String>()

  companion object {
    @OptIn(ExperimentalForeignApi::class)
    fun create(): Result<InotifyWatcher, InotifyError> {
      val fd = inotify_init1(IN_CLOEXEC)
      if (fd < 0) return Err(InotifyError.InitFailed(errno))
      return Ok(InotifyWatcher(fd))
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  fun addWatch(path: String, mask: UInt): Result<Int, InotifyError> {
    val wd = inotify_add_watch(fd, path, mask)
    if (wd < 0) {
      val e = errno
      return if (e == ENOSPC) {
        Err(InotifyError.WatchLimitExceeded(path))
      } else {
        Err(InotifyError.WatchFailed(path, e))
      }
    }
    wdToPath[wd] = path
    return Ok(wd)
  }

  fun pathForWd(wd: Int): String? = wdToPath[wd]

  fun addWatchRecursive(directory: String): Result<List<Int>, InotifyError> {
    val wds = mutableListOf<Int>()
    val rootWd =
      addWatch(directory, DEFAULT_WATCH_MASK).getOrElse {
        return Err(it)
      }
    wds.add(rootWd)
    addWatchSubdirs(directory, wds).let { err -> if (err != null) return Err(err) }
    return Ok(wds)
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun addWatchSubdirs(directory: String, wds: MutableList<Int>): InotifyError? {
    val dir = opendir(directory) ?: return null
    try {
      while (true) {
        val entry = readdir(dir) ?: break
        val name = entry.pointed.d_name.toKString()
        if (name == "." || name == ".." || name in EXCLUDED_DIRS) continue
        val childPath = "$directory/$name"
        if (!isDirectory(childPath)) continue
        val wd =
          addWatch(childPath, DEFAULT_WATCH_MASK).getOrElse {
            return it
          }
        wds.add(wd)
        addWatchSubdirs(childPath, wds)?.let {
          return it
        }
      }
    } finally {
      closedir(dir)
    }
    return null
  }

  @OptIn(ExperimentalForeignApi::class)
  fun pollEvents(timeoutMs: Int): Result<List<InotifyEvent>, InotifyError> {
    memScoped {
      val pfd = alloc<pollfd>()
      pfd.fd = fd
      pfd.events = POLLIN.toShort()
      pfd.revents = 0

      val ret = poll(pfd.ptr, 1u, timeoutMs)
      if (ret == 0) return Ok(emptyList())
      if (ret < 0) {
        val e = errno
        if (e == EINTR) return Ok(emptyList())
        return Err(InotifyError.ReadFailed(e))
      }
      return readPendingEvents()
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  internal fun readPendingEvents(): Result<List<InotifyEvent>, InotifyError> {
    val events = mutableListOf<InotifyEvent>()
    memScoped {
      val buf = allocArray<ByteVar>(EVENT_BUF_SIZE)
      val bytesRead = read(fd, buf, EVENT_BUF_SIZE.toULong())
      if (bytesRead < 0) {
        val e = errno
        if (e == EAGAIN || e == EINTR) return Ok(events)
        return Err(InotifyError.ReadFailed(e))
      }

      var offset = 0
      while (offset + EVENT_HEADER_SIZE <= bytesRead.toInt()) {
        val wd = (buf + offset)!!.reinterpret<IntVar>().pointed.value
        val mask = (buf + offset + 4)!!.reinterpret<UIntVar>().pointed.value
        val len = (buf + offset + 12)!!.reinterpret<UIntVar>().pointed.value.toInt()
        val name =
          if (len > 0) {
            (buf + offset + EVENT_HEADER_SIZE)!!.toKString()
          } else ""
        events.add(InotifyEvent(wd, mask, name))
        offset += EVENT_HEADER_SIZE + len
      }
    }
    return Ok(events)
  }

  @OptIn(ExperimentalForeignApi::class)
  fun close() {
    if (fd >= 0) {
      platform.posix.close(fd)
      fd = -1
    }
  }
}
