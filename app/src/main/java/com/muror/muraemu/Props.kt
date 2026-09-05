package com.muror.muraemu

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object Props {
    private const val MAGIC = 1347375696 // "PROP" (0x50524F50)
    private const val VERSION = 1162039158
    private const val VALUE_MAX = 92

    private var area: MappedByteBuffer? = null
    private var chan: FileChannel? = null
    private var server: LocalServerSocket? = null
    private var serverSock: LocalSocket? = null

    // Недостающие свойства, необходимые для ActivityManagerService
    private val DEFAULT_AM_PROPS = listOf(
        // Брендинг MuraEmu
        "ro.product.model" to "MuraEmu",
        "ro.product.brand" to "Mura",
        "ro.product.name" to "MuraEmu",
        "ro.product.device" to "muraemu",
        "ro.product.board" to "mura",
        "ro.product.manufacturer" to "Muror",
        "ro.build.display.id" to "MuraEmu 1.6-r1",
        "ro.build.version.release" to "1.6",
        "ro.build.description" to "MuraEmu 1.6 Donut by Muror",
        "ro.build.fingerprint" to "Mura/MuraEmu/muraemu:1.6/DRC79/20260905:user/release-keys",

        // Числовые свойства для ActivityManagerService
        "ro.FOREGROUND_APP_ADJ" to "0",
        "ro.VISIBLE_APP_ADJ" to "1",
        "ro.SECONDARY_SERVER_ADJ" to "2",
        "ro.BACKUP_APP_ADJ" to "2",
        "ro.HOME_APP_ADJ" to "4",
        "ro.HIDDEN_APP_MIN_ADJ" to "7",
        "ro.CONTENT_PROVIDER_ADJ" to "14",
        "ro.CONTENT_PROVIDER_AM_ADJ" to "14",
        "ro.EMPTY_APP_ADJ" to "15",
        "ro.FOREGROUND_APP_MEM" to "1536",
        "ro.VISIBLE_APP_MEM" to "2048",
        "ro.SECONDARY_SERVER_MEM" to "4096",
        "ro.BACKUP_APP_MEM" to "4096",
        "ro.HOME_APP_MEM" to "4096",
        "ro.HIDDEN_APP_MEM" to "5120",
        "ro.CONTENT_PROVIDER_AM_MEM" to "5632",
        "ro.EMPTY_APP_MEM" to "6144"
    )

    fun sock(ctx: Context): File = File(Vm.root(ctx), "dev/socket/property_service")

    @Synchronized
    fun prepare(ctx: Context, log: (String) -> Unit) {
        val f = Vm.props(ctx)
        if (!f.isFile) {
            log("Свойства: нет файла ${f.name}")
            return
        }
        close()

        try {
            val ch = RandomAccessFile(f, "rw").channel
            val m = ch.map(FileChannel.MapMode.READ_WRITE, 0L, f.length())
            m.order(ByteOrder.LITTLE_ENDIAN)

            if (m.getInt(8) != MAGIC) {
                log("Свойства: неверный MAGIC")
                ch.close()
                return
            }

            chan = ch
            area = m

            // Внедряем недостающие свойства для ActivityManagerService
            var added = 0
            for ((k, v) in DEFAULT_AM_PROPS) {
                if (put(k, v)) added++
            }
            area?.force()

            log("Свойства: область готова, записей ${count()} (добавлено $added для ActivityManager)")
        } catch (e: Throwable) {
            log("Свойства: ошибка отображения: ${e.message}")
        }
    }

    @Synchronized
    fun serve(ctx: Context, log: (String) -> Unit) {
        val f = sock(ctx)
        if (server != null && f.exists()) return

        try {
            f.parentFile?.mkdirs()
            f.delete()

            val ls = LocalSocket(LocalSocket.SOCKET_STREAM)
            ls.bind(LocalSocketAddress(f.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            cloexec(ls.fileDescriptor)
            serverSock = ls

            val s = LocalServerSocket(ls.fileDescriptor)
            server = s

            Thread({
                while (true) {
                    try {
                        val c = s.accept()
                        cloexec(c.fileDescriptor)
                        Thread({ serveOne(c, log) }, "donut-prop-client").apply {
                            isDaemon = true
                            start()
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "donut-prop-accept").apply {
                isDaemon = true
                start()
            }

            log("Свойства: слушаю сокет property_service")
        } catch (e: Throwable) {
            log("Свойства: ошибка запуска сокета: ${e.message}")
        }
    }

    private fun serveOne(c: LocalSocket, log: (String) -> Unit) {
        try {
            val din = DataInputStream(c.inputStream)
            val body = ByteArray(128)
            din.readFully(body)
            val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int
            val name = cstr(body, 4, 32)
            val value = cstr(body, 36, VALUE_MAX)

            if (cmd == 1) { // PROP_MSG_SETPROP
                put(name, value)
            }
        } catch (_: Throwable) {
        } finally {
            try { c.close() } catch (_: Throwable) {}
        }
    }

    fun count(): Int = area?.getInt(0) ?: 0

    @Synchronized
    fun put(name: String, value: String): Boolean {
        val a = area ?: return false
        val nb = name.toByteArray(Charsets.UTF_8)
        var vb = value.toByteArray(Charsets.UTF_8)

        if (nb.isEmpty() || nb.size >= 32) return false
        if (vb.size >= VALUE_MAX) {
            vb = vb.copyOf(VALUE_MAX - 1)
        }

        val off = find(a, name)
        if (off != null) {
            val s0 = a.getInt(off + 32)
            a.putInt(off + 32, s0 or 1)
            for (i in vb.indices) {
                a.put(off + 32 + 4 + i, vb[i])
            }
            a.put(off + 32 + 4 + vb.size, 0.toByte())
            a.putInt(off + 32, (vb.size shl 24) or (((s0 or 1) + 1) and 0xFFFFFF))
            a.force()
            return true
        }

        val n = count()
        val at = nextInfo(a, n)
        if (((n + 1) * 4) + 32 > firstInfo(a, n) || at + 128 > a.capacity()) {
            return false
        }

        for (i in 0 until 128) a.put(at + i, 0.toByte())
        for (i in nb.indices) a.put(at + i, nb[i])
        for (i in vb.indices) a.put(at + 32 + 4 + i, vb[i])

        a.putInt(at + 32, vb.size shl 24)
        a.putInt((n * 4) + 32, (nb.size shl 24) or at)
        a.putInt(0, n + 1)
        a.putInt(4, a.getInt(4) + 1)
        a.force()
        return true
    }

    private fun find(a: MappedByteBuffer, name: String): Int? {
        val nb = name.toByteArray(Charsets.UTF_8)
        val count = a.getInt(0)
        for (i in 0 until count) {
            val e = a.getInt((i * 4) + 32)
            if ((e ushr 24) == nb.size) {
                val off = 0xFFFFFF and e
                var same = true
                for (j in nb.indices) {
                    if (a.get(off + j) != nb[j]) {
                        same = false
                        break
                    }
                }
                if (same && a.get(nb.size + off) == 0.toByte()) {
                    return off
                }
            }
        }
        return null
    }

    private fun firstInfo(a: MappedByteBuffer, n: Int): Int {
        if (n == 0) return 4096
        var m = Int.MAX_VALUE
        for (i in 0 until n) {
            m = minOf(m, a.getInt((i * 4) + 32) and 0xFFFFFF)
        }
        return m
    }

    private fun nextInfo(a: MappedByteBuffer, n: Int): Int {
        if (n == 0) return firstInfo(a, 0)
        var m = 0
        for (i in 0 until n) {
            m = maxOf(m, a.getInt((i * 4) + 32) and 0xFFFFFF)
        }
        return m + 128
    }

    private fun cstr(b: ByteArray, offset: Int, max: Int): String {
        var len = 0
        while (len < max && offset + len < b.size && b[offset + len] != 0.toByte()) {
            len++
        }
        return String(b, offset, len, Charsets.UTF_8)
    }

    private fun cloexec(fd: FileDescriptor?) {
        if (fd == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Os.fcntlInt(fd, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
            } catch (_: Throwable) {}
        }
    }

    @Synchronized
    fun close() {
        area = null
        try { chan?.close() } catch (_: Throwable) {}
        chan = null
    }
}