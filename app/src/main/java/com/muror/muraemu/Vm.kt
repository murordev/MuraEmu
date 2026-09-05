package com.muror.muraemu

import android.content.Context
import android.os.Process
import java.io.File
import java.nio.file.Files

object Vm {
    const val FB_W = 480
    const val FB_H = 800
    const val FB_BPP = 2
    const val FB_BYTES = 1536000L // 480 * 800 * 2 * 2 (двойная буферизация)
    const val PROPS_FD = 30

    const val BOOTCLASSPATH = 
        "/system/framework/core.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/android.policy.jar:/system/framework/services.jar"

    fun bin(ctx: Context): File = File(ctx.filesDir, "bin")
    fun root(ctx: Context): File = File(ctx.filesDir, "root")
    fun fb(ctx: Context): File = File(root(ctx), "dev/graphics/fb0")
    fun props(ctx: Context): File {
        val f = File(bin(ctx), "dhd.props")
        return if (f.isFile) f else File(bin(ctx), "donut.props")
    }
    fun binderSock(ctx: Context): File = File(bin(ctx), "binder.sock")
    fun credsDir(ctx: Context): File = File(bin(ctx), "creds")
    fun socket(ctx: Context, name: String): File = File(root(ctx), "dev/socket/$name")

    private fun nativeBin(ctx: Context, name: String): File? {
        val dir = ctx.applicationInfo.nativeLibraryDir ?: return null
        val f = File(dir, name)
        return if (f.exists()) f else null
    }

    fun qemu(ctx: Context): File = nativeBin(ctx, "libqemu.so") ?: File(bin(ctx), "qemu-arm")
    fun runner(ctx: Context): File = nativeBin(ctx, "libdhdrun.so") ?: File(bin(ctx), "dhdrun")
    fun binderd(ctx: Context): File = nativeBin(ctx, "libbinderd.so") ?: File(bin(ctx), "binderd")

    fun treeReady(ctx: Context): Boolean = File(root(ctx), "system/bin/linker").isFile

    fun isLink(f: File): Boolean = try {
        Files.isSymbolicLink(f.toPath())
    } catch (_: Throwable) {
        false
    }

    fun wipeTree(f: File) {
        if (!isLink(f) && f.isDirectory) {
            f.listFiles()?.forEach { wipeTree(it) }
        }
        f.delete()
    }

    fun guestEnv(ctx: Context? = null): Map<String, String> = mapOf(
        "PATH" to "/system/bin:/system/xbin",
        "DHD_ENV_LD_LIBRARY_PATH" to "/system/lib",
        "ANDROID_ROOT" to "/system",
        "ANDROID_DATA" to "/data",
        "ANDROID_ASSETS" to "/system/app",
        "EXTERNAL_STORAGE" to "/mnt/sdcard",
        "BOOTCLASSPATH" to BOOTCLASSPATH,
        "HOME" to "/data",
        "DHD_ENV_LD_PRELOAD" to "/system/lib/libashmemshim.so",
        "ASHMEM_SHIM_DIR" to "/data/local/tmp",
        "TMPDIR" to "/data/local/tmp",
        "TZ" to "UTC"
    )

    fun killLeftovers(log: (String) -> Unit): Int {
        val mine = Process.myPid()
        val procDir = File("/proc")
        var killed = 0
        procDir.listFiles()?.forEach { f ->
            val pid = f.name.toIntOrNull()
            if (pid != null && pid != mine) {
                try {
                    val cmd = File(f, "cmdline").readBytes().toString(Charsets.ISO_8859_1).replace('\u0000', ' ')
                    if (cmd.contains("/files/") && (cmd.contains("qemu-arm") || cmd.contains("binderd") || cmd.contains("dhdrun"))) {
                        Process.sendSignal(pid, 9)
                        killed++
                    }
                } catch (_: Throwable) {}
            }
        }
        log(if (killed > 0) "Прибрано за прошлым запуском: $killed процессов" else "Чисто, остатков нет")
        return killed
    }
}