package com.muror.muraemu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class GuestView(ctx: Context) : View(ctx) {
    private val w = Vm.FB_W
    private val h = Vm.FB_H
    private val needBytes = Vm.FB_BYTES

    var fbFile: File? = Vm.fb(ctx)
    var onLog: ((String) -> Unit)? = null

    @Volatile var fps: Float = 0f
        private set
    @Volatile var status: String = "кадра ещё нет"
        private set

    private var bmp: Bitmap? = null
    private var buf: MappedByteBuffer? = null
    private var raf: RandomAccessFile? = null
    private var poller: Thread? = null
    @Volatile private var live = false

    private var lastHash = 0L
    private var changedAt = 0L
    private var changedFrames = 0L
    private var quiet = 0

    private var dstX = 0
    private var dstY = 0
    private var dstW = 0
    private var dstH = 0

    private val bmpPaint = Paint().apply { isFilterBitmap = true }
    private val textPaint = Paint().apply {
        color = Color.rgb(200, 200, 200)
        textSize = 28f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    fun start() {
        if (live) return
        live = true
        poller = Thread({
            while (live) {
                try {
                    tick()
                } catch (e: Throwable) {
                    status = "кадр не читается: ${e.message}"
                }
                try {
                    Thread.sleep(if (quiet > 8) 100L else 33L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "donut-guest-frame").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        live = false
        poller?.interrupt()
        poller = null
    }

    fun clear() {
        stop()
        try { raf?.close() } catch (_: Throwable) {}
        raf = null
        buf = null
        bmp = null
        lastHash = 0L
        fps = 0f
        status = "гость не рисует"
        postInvalidate()
    }

    private fun tick() {
        val f = fbFile ?: return
        if (!f.isFile || f.length() < needBytes) {
            status = "подготовка буфера..."
            return
        }

        if (buf == null) {
            try {
                val r = RandomAccessFile(f, "r")
                raf = r
                buf = r.channel.map(FileChannel.MapMode.READ_ONLY, 0L, needBytes)
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            } catch (_: Throwable) {
                return
            }
        }

        val b = buf ?: return
        val bitmap = bmp ?: return

        try {
            var hash = 0L
            val limit = (needBytes.toInt() - 4).coerceAtMost(b.capacity() - 4)
            var i = 0
            while (i <= limit) {
                hash = 31L * hash + b.getInt(i)
                i += 2048
            }

            quiet++
            val force = quiet > 30

            if (hash != lastHash || force) {
                if (force) quiet = 0
                b.rewind()
                bitmap.copyPixelsFromBuffer(b)

                if (hash != lastHash) {
                    quiet = 0
                    lastHash = hash
                    changedFrames++
                    val now = System.currentTimeMillis()
                    if (changedAt == 0L) changedAt = now
                    val dt = now - changedAt
                    if (dt > 1000) {
                        fps = (changedFrames * 1000f) / dt
                        changedFrames = 0L
                        changedAt = now
                    }
                    status = String.format("кадр живой · %.1f кадр/с", fps)
                }
                postInvalidate()
            }
        } catch (_: Throwable) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        val bitmap = bmp
        if (bitmap != null) {
            val scale = Math.min(width.toFloat() / w, height.toFloat() / h)
            val dw = (w * scale).toInt()
            val dh = (h * scale).toInt()
            val left = (width - dw) / 2
            val top = (height - dh) / 2

            dstX = left
            dstY = top
            dstW = dw
            dstH = dh

            canvas.drawBitmap(bitmap, null, Rect(left, top, left + dw, top + dh), bmpPaint)
        }

        canvas.drawText(status, 20f, height - 20f, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dstW <= 0 || dstH <= 0) return false

        val pts = ArrayList<Input.TouchPoint>()
        val up = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        val goneIdx = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1

        if (!up) {
            for (i in 0 until e.pointerCount) {
                if (i != goneIdx) {
                    val gx = (((e.getX(i) - dstX) * w) / dstW).toInt().coerceIn(0, w - 1)
                    val gy = (((e.getY(i) - dstY) * h) / dstH).toInt().coerceIn(0, h - 1)
                    pts.add(Input.TouchPoint(e.getPointerId(i), gx, gy))
                }
            }
        }

        if (pts.isNotEmpty()) {
            val first = pts.first()
            onLog?.invoke("Тач: пальцев=${pts.size} гость=[${first.x}, ${first.y}]")
        }

        Input.touch(pts)
        return true
    }
}