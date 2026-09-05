package com.muror.muraemu

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

object Stack {
    private val procs = ConcurrentHashMap<String, Process>()
    @Volatile var isStopping = false
        private set
    @Volatile var bootAt = 0L
        private set

    fun isRunning(): Boolean = procs.values.any { it.isAlive }

    fun aliveServices(): List<String> = procs.filter { it.value.isAlive }.keys.toList()

    fun isZygoteAlive(): Boolean = procs["zygote"]?.isAlive == true

    fun boot(ctx: Context, note: (String) -> Unit) {
        if (isRunning()) {
            note("Стек уже запущен: ${aliveServices().joinToString()}")
            return
        }
        if (!Vm.treeReady(ctx)) {
            note("Дерево гостя не готово! Сначала распакуйте систему.")
            return
        }

        Props.prepare(ctx, note)
        Props.serve(ctx, note)

        isStopping = false
        bootAt = System.currentTimeMillis()
        procs.clear()

        // 0. Очистка старых сокетов
        Vm.binderSock(ctx).delete()
        Vm.credsDir(ctx).mkdirs()

        val bd = Vm.binderd(ctx)
        if (!bd.canExecute()) {
            note("Ошибка: libbinderd.so не найден или не имеет прав на исполнение")
            return
        }

        // 1. Запуск демона Binder
        note("Запуск binderd...")
        spawn(ctx, "binderd", listOf(bd.absolutePath, "-s", Vm.binderSock(ctx).absolutePath), note)

        // Ждем поднятия сокета binder.sock
        if (!waitFor(50, 100) { Vm.binderSock(ctx).exists() }) {
            note("Ошибка: сокет binder.sock не появился")
            stop(ctx, note)
            return
        }
        note("Binder успешно запущен")

        // 2. Запуск servicemanager
        note("Запуск servicemanager...")
        guest(ctx, "servicemanager", listOf("/system/bin/servicemanager"), note)
        Thread.sleep(1000L)

        // 3. Запуск SurfaceFlinger (графика)
        val sf = File(Vm.root(ctx), "system/bin/surfaceflinger")
        if (sf.isFile) {
            note("Запуск SurfaceFlinger...")
            guest(ctx, "surfaceflinger", listOf("/system/bin/surfaceflinger"), note)
            Thread.sleep(1000L)
        }

        // Запуск installd
        val inst = File(Vm.root(ctx), "system/bin/installd")
        if (inst.isFile) {
            note("Запуск installd...")
            guest(ctx, "installd", listOf("/system/bin/installd"), note)
            Thread.sleep(500L)
        }

        // Запуск mediaserver (публикует AudioFlinger)
        val ms = File(Vm.root(ctx), "system/bin/mediaserver")
        if (ms.isFile) {
            note("Запуск mediaserver...")
            guest(ctx, "mediaserver", listOf("/system/bin/mediaserver"), note)
            Thread.sleep(1000L)
        }

        // 4. Запуск Zygote (виртуальная машина Dalvik + системный сервер)
        note("Запуск Zygote (Android 1.6 System Server)...")
        guest(
            ctx,
            "zygote",
            listOf("/system/bin/app_process", "-Xzygote", "/system/bin", "--zygote", "--start-system-server"),
            note,
            extraEnv = mapOf(
                "DHD_SOCK_zygote" to "${Vm.socket(ctx, "zygote").absolutePath},0666"
            )
        )

        note("Все службы запущены: ${aliveServices().joinToString()}")
    }

    private fun guest(
        ctx: Context,
        name: String,
        argv: List<String>,
        note: (String) -> Unit,
        extraEnv: Map<String, String> = emptyMap()
    ) {
        val cmd = Runner.cmdline(ctx, argv)
        spawn(ctx, name, cmd, note, extraEnv)
    }

    private fun spawn(
        ctx: Context,
        name: String,
        cmd: List<String>,
        note: (String) -> Unit,
        extraEnv: Map<String, String> = emptyMap()
    ) {
        try {
            val pb = ProcessBuilder(cmd)
                .directory(Vm.bin(ctx))
                .redirectErrorStream(true)

            pb.environment().clear()
            pb.environment().putAll(Vm.guestEnv(ctx))
            pb.environment()["DHD_BINDER"] = Vm.binderSock(ctx).absolutePath
            pb.environment()["DHD_CREDS"] = Vm.credsDir(ctx).absolutePath
            pb.environment()["DHD_INPUT"] = Input.sock(ctx).absolutePath
            pb.environment().putAll(extraEnv)

            val p = pb.start()
            procs[name] = p

            Thread({
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).forEachLine { line ->
                        note("[$name] $line")
                    }
                } catch (_: Throwable) {}
                val code = try { p.waitFor() } catch (_: Throwable) { -1 }
                if (!isStopping) {
                    note("Служба $name завершилась (код $code)")
                }
            }, "log-$name").apply {
                isDaemon = true
                start()
            }
        } catch (e: Throwable) {
            note("Ошибка запуска $name: ${e.message}")
        }
    }

    fun stop(ctx: Context, note: (String) -> Unit) {
        isStopping = true
        note("Остановка всех служб...")
        procs.values.forEach { it.destroyForcibly() }
        procs.clear()
        Props.close()
        Vm.killLeftovers(note)
        isStopping = false
        bootAt = 0L
        note("Стек остановлен")
    }

    private inline fun waitFor(maxRetries: Int, delayMs: Long, condition: () -> Boolean): Boolean {
        for (i in 0 until maxRetries) {
            if (condition()) return true
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return false }
        }
        return false
    }
}