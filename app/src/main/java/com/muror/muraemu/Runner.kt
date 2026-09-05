package com.muror.muraemu

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object Runner {
    @Volatile private var current: Process? = null

    val isRunning: Boolean
        get() = current?.isAlive == true

    data class Result(val code: Int, val out: String, val ms: Long) {
        val isOk: Boolean get() = code == 0
    }

    data class Check(
        val title: String,
        val argv: List<String>,
        val want: String,
        val timeoutMs: Long = 30000L
    )

    val CHECKS = listOf(
        Check("toolbox ls /system", listOf("/system/bin/toolbox", "ls", "/system"), "framework"),
        Check("toolbox id", listOf("/system/bin/toolbox", "id"), "uid="),
        Check("sh -c echo", listOf("/system/bin/sh", "-c", "echo RABOTAET; pwd"), "RABOTAET"),
        Check("dalvikvm -help", listOf("/system/bin/dalvikvm", "-help"), "[options] class"),
        Check("проверка версии (1.6)", listOf("/system/bin/getprop", "ro.build.version.release"), "1.6"),
        Check("Параметры тачскрина", listOf("/system/bin/getevent", "-p", "/dev/input/event0"), "events")
    )

    fun cmdline(ctx: Context, guestArgv: List<String>, propsFile: File? = null): List<String> {
        val root = Vm.root(ctx)
        val prog = guestArgv.first()
        val host = File(root, prog.removePrefix("/")).absolutePath
        val cmd = ArrayList<String>()

        val runnerBin = Vm.runner(ctx)
        val props = propsFile ?: Vm.props(ctx)
        if (runnerBin.canExecute() && props.isFile) {
            cmd.add(runnerBin.absolutePath)
            cmd.add(props.absolutePath)
            cmd.add(Vm.PROPS_FD.toString())
        }

        cmd.add(Vm.qemu(ctx).absolutePath)
        cmd.add("-L")
        cmd.add(root.absolutePath)
        cmd.add("-0")
        cmd.add(prog)
        cmd.add(host)
        cmd.addAll(guestArgv.drop(1))
        return cmd
    }

    fun run(
        ctx: Context,
        guestArgv: List<String>,
        note: (String) -> Unit,
        timeoutMs: Long = 60000L,
        maxLines: Int = 100
    ): Result {
        if (!Vm.qemu(ctx).exists()) {
            return Result(-1, "нет qemu-arm (libqemu.so)", 0L)
        }
        if (!Vm.treeReady(ctx)) {
            return Result(-1, "нет дерева гостя", 0L)
        }

        val cmd = cmdline(ctx, guestArgv)
        val pb = ProcessBuilder(cmd)
            .directory(Vm.bin(ctx))
            .redirectErrorStream(true)

        pb.environment().clear()
        pb.environment().putAll(Vm.guestEnv(ctx))
        pb.environment()["DHD_BINDER"] = Vm.binderSock(ctx).absolutePath
        pb.environment()["DHD_INPUT"] = Input.sock(ctx).absolutePath

        val t0 = System.currentTimeMillis()
        val p = pb.start()
        current = p

        val sb = StringBuilder()
        var lineCount = 0

        val readerThread = Thread({
            try {
                BufferedReader(InputStreamReader(p.inputStream)).forEachLine { line ->
                    sb.append(line).append('\n')
                    if (lineCount < maxLines) {
                        note("  | $line")
                        lineCount++
                    } else if (lineCount == maxLines) {
                        note("  | ... (вывод обрезан)")
                        lineCount++
                    }
                }
            } catch (_: Throwable) {}
        }, "mura-runner-reader").apply {
            isDaemon = true
            start()
        }

        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            note("  ! Превышен таймаут ${timeoutMs / 1000} с — принудительно останавливаю")
            p.destroyForcibly()
        }
        readerThread.join(1500L)
        val ms = System.currentTimeMillis() - t0
        current = null
        val code = try { p.exitValue() } catch (_: Throwable) { -9 }

        return Result(code, sb.toString(), ms)
    }

    fun stop() {
        current?.destroyForcibly()
        current = null
    }

    fun gate(ctx: Context, note: (String) -> Unit): String {
        note("— Тест системы Android 1.6: ${CHECKS.size} проверок —")
        val lines = ArrayList<String>()
        var pass = 0

        for (c in CHECKS) {
            note("· ${c.title}")
            val r = run(ctx, c.argv, note, c.timeoutMs, 15)
            val ok = r.out.contains(c.want)
            if (ok) pass++
            lines.add("${if (ok) "★" else "×"} ${c.title}: код=${r.code}, ${r.ms} мс${if (ok) "" else " (ожидалось «${c.want}»)"}")
        }

        val header = if (pass == CHECKS.size) "★★★ ВСЕ ТЕСТЫ ПРОЙДЕНЫ: $pass из ${CHECKS.size}" else "Пройдено: $pass из ${CHECKS.size}"
        val report = (listOf(header) + lines).joinToString("\n")
        note(report)
        return report
    }
}