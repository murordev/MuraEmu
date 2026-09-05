package com.muror.muraemu

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

object Payload {

    fun isReady(ctx: Context): Boolean = Vm.treeReady(ctx)

    fun unpack(ctx: Context, onProgress: (String) -> Unit): Boolean {
        return try {
            val rootDir = Vm.root(ctx)
            val binDir = Vm.bin(ctx)

            if (!binDir.exists()) binDir.mkdirs()
            if (!rootDir.exists()) rootDir.mkdirs()

            // 1. Распаковка rootfs.payload (системное дерево Android 1.6)
            onProgress("Распаковка системы Android 1.6...")
            ctx.assets.open("rootfs.payload").use { assetIn ->
                extractTarGz(assetIn, rootDir, onProgress)
            }

            // 2. Распаковка tools.payload (вспомогательные инструменты)
            try {
                ctx.assets.open("tools.payload").use { assetIn ->
                    onProgress("Распаковка инструментов...")
                    extractTarGz(assetIn, binDir, onProgress)
                }
            } catch (_: Exception) {
                // tools.payload опционален
            }

            // 3. Подготовка критических каталогов и прав
            onProgress("Настройка прав доступа...")
            fixPermissions(rootDir)
            prepareVirtualDevices(rootDir)

            onProgress("Готово к запуску!")
            true
        } catch (e: Exception) {
            onProgress("Ошибка распаковки: ${e.message}")
            false
        }
    }

    private fun extractTarGz(input: InputStream, destDir: File, onProgress: (String) -> Unit) {
        GZIPInputStream(input.buffered()).use { gzipIn ->
            val header = ByteArray(512)
            var count = 0

            while (true) {
                val bytesRead = readFully(gzipIn, header)
                if (bytesRead < 512) break

                // Конец TAR-архива обозначается двумя пустыми блоками
                if (header.all { it == 0.toByte() }) break

                val name = parseString(header, 0, 100).trim().removePrefix("./")
                if (name.isEmpty()) continue

                val size = parseOctal(header, 124, 12)
                val type = header[156] // '0' / 0: файл, '2': симлинк, '5': каталог
                val linkName = parseString(header, 157, 100).trim()

                val targetFile = File(destDir, name)

                when (type.toInt().toChar()) {
                    '5' -> {
                        targetFile.mkdirs()
                    }
                    '2' -> {
                        // Символическая ссылка Linux
                        targetFile.parentFile?.mkdirs()
                        targetFile.delete()
                        try {
                            Files.createSymbolicLink(targetFile.toPath(), File(linkName).toPath())
                        } catch (_: Throwable) {}
                    }
                    else -> {
                        // Обычный файл
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { out ->
                            val buf = ByteArray(8192)
                            var left = size
                            while (left > 0) {
                                val toRead = Math.min(left, buf.size.toLong()).toInt()
                                val read = gzipIn.read(buf, 0, toRead)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                                left -= read
                            }
                        }
                        // Выравнивание до 512-байтного блока TAR
                        val pad = (512 - (size % 512)) % 512
                        if (pad > 0) {
                            readFully(gzipIn, ByteArray(pad.toInt()))
                        }
                    }
                }

                count++
                if (count % 300 == 0) {
                    onProgress("Извлечено $count файлов...")
                }
            }
        }
    }

    private fun readFully(inStream: InputStream, b: ByteArray): Int {
        var total = 0
        while (total < b.size) {
            val r = inStream.read(b, total, b.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun parseString(b: ByteArray, offset: Int, length: Int): String {
        var len = 0
        while (len < length && b[offset + len] != 0.toByte()) {
            len++
        }
        return String(b, offset, len, Charsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, offset: Int, length: Int): Long {
        var result = 0L
        for (i in offset until (offset + length)) {
            val byte = b[i]
            if (byte in '0'.code.toByte()..'7'.code.toByte()) {
                result = (result shl 3) + (byte - '0'.code.toByte())
            } else if (result > 0) {
                break
            }
        }
        return result
    }

    private fun fixPermissions(rootDir: File) {
        val binDir = File(rootDir, "system/bin")
        binDir.listFiles()?.forEach { it.setExecutable(true, false) }
        val xbinDir = File(rootDir, "system/xbin")
        xbinDir.listFiles()?.forEach { it.setExecutable(true, false) }
    }

    private fun prepareVirtualDevices(rootDir: File) {
        // 1. Кадровый буфер 480x800 (1536000 байт)
        val fb = File(rootDir, "dev/graphics/fb0")
        fb.parentFile?.mkdirs()
        if (!fb.exists() || fb.length() != Vm.FB_BYTES) {
            val buf = ByteArray(Vm.FB_BYTES.toInt())
            FileOutputStream(fb).use { it.write(buf) }
        }

        // 2. Виртуальный тачскрин
        val ev0 = File(rootDir, "dev/input/event0")
        ev0.parentFile?.mkdirs()
        if (!ev0.exists()) ev0.createNewFile()

        // 3. Файлы журналов логов
        val logDir = File(rootDir, "dev/log")
        logDir.mkdirs()
        listOf("main", "system", "radio", "events").forEach {
            val lf = File(logDir, it)
            if (!lf.exists()) lf.createNewFile()
        }

        // 4. Подсистема питания (wake_lock)
        val powerDir = File(rootDir, "sys/power")
        powerDir.mkdirs()
        listOf("state", "wake_lock", "wake_unlock").forEach {
            val pf = File(powerDir, it)
            if (!pf.exists()) pf.createNewFile()
        }
        val androidPower = File(rootDir, "sys/android_power")
        androidPower.mkdirs()
        listOf("acquire_partial_wake_lock", "release_wake_lock").forEach {
            val pf = File(androidPower, it)
            if (!pf.exists()) pf.createNewFile()
        }

        // 5. Виртуальная батарея (100%, статус Full, зарядка подключена)
        val battDir = File(rootDir, "sys/class/power_supply/battery")
        battDir.mkdirs()
        File(battDir, "capacity").writeText("100\n")
        File(battDir, "status").writeText("Full\n")
        File(battDir, "health").writeText("Good\n")
        File(battDir, "present").writeText("1\n")
        File(battDir, "technology").writeText("Li-ion\n")
        File(battDir, "voltage_now").writeText("4200000\n")

        val acDir = File(rootDir, "sys/class/power_supply/ac")
        acDir.mkdirs()
        File(acDir, "online").writeText("1\n")

        // 6. Папки данных
        File(rootDir, "data/local/tmp").mkdirs()
        File(rootDir, "data/dalvik-cache").mkdirs()
        File(rootDir, "data/app").mkdirs()
        File(rootDir, "data/data").mkdirs()
        File(rootDir, "dev/socket").mkdirs()
    }
}