package com.muror.muraemu

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

object Input {
    private const val EV_SYN = 0
    private const val EV_KEY = 1
    private const val EV_ABS = 3

    private const val SYN_REPORT = 0
    private const val SYN_MT_REPORT = 2
    private const val BTN_TOUCH = 330

    private const val ABS_X = 0
    private const val ABS_Y = 1
    private const val ABS_PRESSURE = 24

    private const val ABS_MT_TOUCH_MAJOR = 48
    private const val ABS_MT_POSITION_X = 53
    private const val ABS_MT_POSITION_Y = 54
    private const val ABS_MT_TRACKING_ID = 57
    private const val ABS_MT_PRESSURE = 58

    // Аппаратные кнопки Android 1.6
    const val KEY_HOME = 102
    const val KEY_MENU = 139
    const val KEY_BACK = 158
    const val KEY_POWER = 116
    const val KEY_SEARCH = 217
    const val KEY_VOLUMEDOWN = 114
    const val KEY_VOLUMEUP = 115

    data class TouchPoint(val id: Int, val x: Int, val y: Int)

    private class Client(val out: OutputStream, val pid: Int)

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var serverSock: LocalSocket? = null
    @Volatile var connected: Int = 0
        private set
    private var wasDown = false

    fun sock(ctx: Context): File = File(Vm.bin(ctx), "input.sock")

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
                        val clientSock = s.accept()
                        cloexec(clientSock.fileDescriptor)
                        val pid = try { clientSock.peerCredentials.pid } catch (_: Throwable) { -1 }
                        val out = clientSock.outputStream
                        clients.add(Client(out, pid))
                        connected = clients.size
                        log("Ввод: гость подключился (клиентов $connected)")
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "donut-input-accept").apply {
                isDaemon = true
                start()
            }

            log("Ввод: слушаю ${f.absolutePath}")
        } catch (e: Throwable) {
            log("Ввод: ошибка запуска сокета: $e")
        }
    }

    private fun cloexec(fd: FileDescriptor?) {
        if (fd == null) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                Os.fcntlInt(fd, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
            } catch (_: Throwable) {}
        }
    }

    private fun buf(count: Int): ByteBuffer =
        ByteBuffer.allocate(count * 16).order(ByteOrder.LITTLE_ENDIAN)

    private fun put(b: ByteBuffer, type: Int, code: Int, value: Int) {
        val t = System.nanoTime()
        b.putInt((t / 1_000_000_000).toInt())
        b.putInt(((t % 1_000_000_000) / 1000).toInt())
        b.putShort(type.toShort())
        b.putShort(code.toShort())
        b.putInt(value)
    }

    private fun flush(b: ByteBuffer) {
        if (b.position() == 0) return
        val bytes = ByteArray(b.position())
        b.flip()
        b.get(bytes)
        b.clear()

        val dead = ArrayList<Client>()
        for (c in clients) {
            try {
                c.out.write(bytes)
                c.out.flush()
            } catch (_: Exception) {
                dead.add(c)
            }
        }
        if (dead.isNotEmpty()) {
            clients.removeAll(dead)
            connected = clients.size
        }
    }

    @Synchronized
    fun touch(pts: List<TouchPoint>) {
        if (clients.isEmpty()) return
        val b = buf((pts.size * 6) + 3)

        for (p in pts) {
            put(b, 3 /* EV_ABS */, 57 /* ABS_MT_TRACKING_ID */, p.id.coerceIn(0, 31))
            put(b, 3 /* EV_ABS */, 53 /* ABS_MT_POSITION_X */, p.x.coerceIn(0, 479))
            put(b, 3 /* EV_ABS */, 54 /* ABS_MT_POSITION_Y */, p.y.coerceIn(0, 799))
            put(b, 3 /* EV_ABS */, 48 /* ABS_MT_TOUCH_MAJOR */, 40)
            put(b, 3 /* EV_ABS */, 58 /* ABS_MT_PRESSURE */, 64)
            put(b, 0 /* EV_SYN */, 2 /* SYN_MT_REPORT */, 0)
        }

        val down = pts.isNotEmpty()
        if (down != wasDown) {
            put(b, 1 /* EV_KEY */, 330 /* BTN_TOUCH */, if (down) 1 else 0)
            wasDown = down
        }

        put(b, 0 /* EV_SYN */, 0 /* SYN_REPORT */, 0)
        flush(b)
    }

    fun key(code: Int) {
        if (clients.isEmpty()) return
        Thread({
            val bDown = buf(2)
            put(bDown, EV_KEY, code, 1)
            put(bDown, EV_SYN, SYN_REPORT, 0)
            flush(bDown)

            try { Thread.sleep(100L) } catch (_: Throwable) {}

            val bUp = buf(2)
            put(bUp, EV_KEY, code, 0)
            put(bUp, EV_SYN, SYN_REPORT, 0)
            flush(bUp)
        }, "donut-key-press").start()
    }
}