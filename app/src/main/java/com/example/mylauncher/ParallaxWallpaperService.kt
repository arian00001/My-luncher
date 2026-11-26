package com.example.mylauncher

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

/**
 * Liquid glass parallax wallpaper.
 *
 * Layers, back to front: the photo, an ambient vignette, the frosted glass clock
 * (only while the phone is locked) and the flower cut-out. Every layer drifts and
 * tilts with the phone; the tilt is low-pass filtered and eased, so the picture
 * glides instead of shaking.
 */
class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    private inner class ParallaxEngine : Engine(), SensorEventListener {

        private val handler = Handler(Looper.getMainLooper())
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val backFile = File(filesDir, "wall_back.jpg")
        private val frontFile = File(filesDir, "wall_front.png")

        private var back: Bitmap? = null
        private var front: Bitmap? = null
        private var digits: Array<Path>? = null
        private var ambientWidth = 0

        private val prefs by lazy {
            getSharedPreferences("monolith_wallpaper", Context.MODE_PRIVATE)
        }
        private var clockAlways = prefs.getBoolean("clock_always", false)

        private var targetX = 0f
        private var targetY = 0f
        private var smoothX = 0f
        private var smoothY = 0f

        private var gravityX = 0f
        private var gravityY = 0f
        private var baseX: Float? = null
        private var baseY: Float? = null

        private var visible = false
        private var sensorManager: SensorManager? = null

        private val frame = object : Runnable {
            override fun run() {
                if (!visible) return
                smoothX += (targetX - smoothX) * 0.10f
                smoothY += (targetY - smoothY) * 0.10f
                drawFrame()
                handler.postDelayed(this, 33L)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = 1.45f
            strokePaint.strokeJoin = Paint.Join.ROUND
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            val manager = sensorManager
            if (isVisible) {
                loadLayers()
                manager?.registerListener(
                    this,
                    manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_GAME
                )
                handler.removeCallbacks(frame)
                handler.post(frame)
            } else {
                manager?.unregisterListener(this)
                handler.removeCallbacks(frame)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            ambientWidth = 0
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(frame)
        }

        override fun onDestroy() {
            sensorManager?.unregisterListener(this)
            handler.removeCallbacks(frame)
            super.onDestroy()
        }

        /** Double tap on the wallpaper keeps the big clock on everywhere (AOD style). */
        override fun onCommand(
            what: Int,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Boolean {
            if (what == WallpaperManager.COMMAND_TAP) {
                clockAlways = !clockAlways
                prefs.edit().putBoolean("clock_always", clockAlways).apply()
                drawFrame()
                return true
            }
            return super.onCommand(what, x, y, z, extras, resultRequested)
        }

        private fun loadLayers() {
            back = BitmapFactory.decodeFile(backFile.path) ?: back
            front = if (frontFile.exists()) BitmapFactory.decodeFile(frontFile.path) else null
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (!visible) return
            // Low-pass the accelerometer: raw readings are what make layers shake.
            gravityX += (event.values[0] - gravityX) * 0.12f
            gravityY += (event.values[1] - gravityY) * 0.12f

            if (baseX == null) {
                baseX = gravityX
                baseY = gravityY
            }

            val dx = (gravityX - (baseX ?: gravityX)).coerceIn(-3.5f, 3.5f)
            val dy = (gravityY - (baseY ?: gravityY)).coerceIn(-3.5f, 3.5f)
            targetX = (-dx / 3.5f).coerceIn(-1f, 1f)
            targetY = (-dy / 3.5f).coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun isLocked(): Boolean {
            val manager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
            return manager.isKeyguardLocked
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            val canvas: Canvas = try {
                holder.lockCanvas() ?: return
            } catch (_: Exception) {
                return
            }
            try {
                canvas.drawColor(Color.BLACK)
                val width = canvas.width.toFloat()
                val height = canvas.height.toFloat()

                drawLayer(canvas, back, width, height, 22f, 1.18f)
                drawAmbient(canvas, width, height)
                if (isLocked() || clockAlways) drawClock(canvas, width, height)
                drawLayer(canvas, front, width, height, 95f, 1.18f)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                    /* surface gone */
                }
            }
        }

        private fun drawLayer(
            canvas: Canvas,
            bitmap: Bitmap?,
            width: Float,
            height: Float,
            depth: Float,
            scale: Float
        ) {
            if (bitmap == null) return
            canvas.save()
            canvas.translate(width / 2f - smoothX * depth, height / 2f - smoothY * depth)
            canvas.rotate(smoothX * depth * 0.05f)
            canvas.scale(scale, scale)
            canvas.drawBitmap(bitmap, null, RectF(-width / 2f, -height / 2f, width / 2f, height / 2f), bitmapPaint)
            canvas.restore()
        }

        private fun drawAmbient(canvas: Canvas, width: Float, height: Float) {
            if (ambientWidth != canvas.width) {
                ambientPaint.shader = RadialGradient(
                    width / 2f,
                    height * 0.36f,
                    maxOf(width, height) * 0.75f,
                    intArrayOf(0x0AFFFFFF, 0x52050A16),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                ambientWidth = canvas.width
            }
            canvas.drawRect(0f, 0f, width, height, ambientPaint)
        }

        private fun drawClock(canvas: Canvas, width: Float, height: Float) {
            val unit = width / 360f
            val clockWidth = 275f * unit
            val scale = clockWidth / 360f
            val clockHeight = 300f * scale
            val left = width / 2f - clockWidth / 2f - smoothX * 52f
            val top = height * 0.37f - clockHeight / 2f - smoothY * 52f

            val now = Calendar.getInstance()
            val clock = String.format(Locale.US, "%02d", now.get(Calendar.HOUR_OF_DAY)) +
                String.format(Locale.US, "%02d", now.get(Calendar.MINUTE))

            canvas.save()
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            canvas.rotate(smoothX * 2.0f, 180f, 150f)

            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, 300f,
                intArrayOf(0xB8E2E8F0.toInt(), 0x94F1F5F9.toInt(), 0xBDFAE8FF.toInt(), 0xE6FFFFFF.toInt()),
                floatArrayOf(0f, 0.45f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
            strokePaint.shader = LinearGradient(
                0f, 0f, 0f, 300f,
                intArrayOf(0xC7FFFFFF.toInt(), 0x61FFFFFF.toInt(), 0xE0FFFFFF.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )

            val paths = digits ?: buildDigitPaths().also { digits = it }
            val positions = floatArrayOf(18f, 86f, 198f, 266f)

            for (index in 0 until 4) {
                val character = clock[index]
                if (character == ' ') continue
                val value = character - '0'
                if (value < 0 || value > 9) continue
                canvas.save()
                canvas.translate(positions[index], 10f)
                canvas.drawPath(paths[value], fillPaint)
                canvas.drawPath(paths[value], strokePaint)
                canvas.restore()
            }

            // The two colon dots breathe, exactly like the reference design.
            val phase = (SystemClock.uptimeMillis() % 2000L) / 2000f
            val pulse = 0.78f + 0.11f * (cos(phase * 2.0 * Math.PI).toFloat() + 1f)
            fillPaint.alpha = (255 * pulse).toInt().coerceIn(0, 255)
            strokePaint.alpha = fillPaint.alpha
            canvas.drawCircle(180f, 102f, 13.5f, fillPaint)
            canvas.drawCircle(180f, 102f, 13.5f, strokePaint)
            canvas.drawCircle(180f, 204f, 13.5f, fillPaint)
            canvas.drawCircle(180f, 204f, 13.5f, strokePaint)
            fillPaint.alpha = 255
            strokePaint.alpha = 255

            canvas.restore()

            val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            val months = arrayOf(
                "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
            )
            val date = days[now.get(Calendar.DAY_OF_WEEK) - 1] + " " +
                months[now.get(Calendar.MONTH)] + " " +
                now.get(Calendar.DAY_OF_MONTH)

            datePaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            datePaint.textSize = 18.4f * unit
            datePaint.color = Color.WHITE
            datePaint.textAlign = Paint.Align.CENTER
            datePaint.setShadowLayer(9f * unit, 0f, 2f * unit, 0x7A000000)
            canvas.drawText(
                date,
                width / 2f - smoothX * 52f,
                top - 4f * unit - smoothY * 52f,
                datePaint
            )
        }
    }
}

private val DIGIT_PATHS = arrayOf(
    "M 34 11.2 C 51 11.2, 60 22, 60 44 L 60 246.8 C 60 268.8, 51 279.6, 34 279.6 C 17 279.6, 8 268.8, 8 246.8 L 8 44 C 8 22, 17 11.2, 34 11.2 Z M 34 35.5 C 28 35.5, 25 40, 25 48 L 25 242.8 C 25 250.8, 28 255.3, 34 255.3 C 40 255.3, 43 250.8, 43 242.8 L 43 48 C 43 40, 40 35.5, 34 35.5 Z",
    "M 29 14 C 30.8 12.2, 33.2 11.2, 36.2 11.2 L 48.5 11.2 C 51.8 11.2, 54 13.4, 54 16.8 L 54 274 C 54 277.4, 51.8 279.6, 48.5 279.6 L 35.5 279.6 C 32.2 279.6, 30 277.4, 30 274 L 30 47.5 L 12.5 60.2 C 10.2 61.9, 7.2 60.3, 7.2 57.4 L 7.2 41.2 C 7.2 39.2, 8.1 37.3, 9.7 36.1 Z",
    "M 9 46 C 9 23, 18.5 11.2, 34.5 11.2 C 50.5 11.2, 60 22.5, 60 44 L 60 88 C 60 112, 52 128, 36 162 L 26 184 L 26 255.3 L 55 255.3 C 58 255.3, 60 257.3, 60 260.3 L 60 274.6 C 60 277.6, 58 279.6, 55 279.6 L 14 279.6 C 10.8 279.6, 9 277.4, 9 274 L 9 180 C 9 166, 13 154, 23 134 L 37 106 C 41.5 97, 43 89, 43 78 L 43 46 C 43 38.5, 40 35.5, 34.5 35.5 C 29 35.5, 26 38.5, 26 46 L 26 64 C 26 67, 24 69, 21 69 L 14 69 C 11 69, 9 67, 9 64 Z",
    "M 9 44 C 9 22, 18.5 11.2, 34.5 11.2 C 50.5 11.2, 60 22, 60 44 L 60 114 C 60 128, 55 137, 46 142 C 55 147, 60 156, 60 170 L 60 246.8 C 60 268.8, 50.5 279.6, 34.5 279.6 C 18.5 279.6, 9 268.8, 9 246.8 L 9 226 C 9 223, 11 221, 14 221 L 21 221 C 24 221, 26 223, 26 226 L 26 245 C 26 252, 29 255.3, 34.5 255.3 C 40 255.3, 43 252, 43 245 L 43 168 C 43 158, 39.5 154, 32 154 L 24 154 C 21 154, 19 152, 19 149 L 19 135 C 19 132, 21 130, 24 130 L 32 130 C 39.5 130, 43 126, 43 116 L 43 45.8 C 43 38.8, 40 35.5, 34.5 35.5 C 29 35.5, 26 38.8, 26 45.8 L 26 62 C 26 65, 24 67, 21 67 L 14 67 C 11 67, 9 65, 9 62 Z",
    "M 37 11.2 L 49 11.2 C 52 11.2, 54 13.2, 54 16.2 L 54 198 L 58 198 C 60.5 198, 62 199.5, 62 202 L 62 217 C 62 219.5, 60.5 221, 58 221 L 54 221 L 54 274.6 C 54 277.6, 52 279.6, 49 279.6 L 41 279.6 C 38 279.6, 36 277.6, 36 274.6 L 36 221 L 11 221 C 8 221, 6 219, 6 216 L 6 201 C 6 198, 6.6 195, 7.6 191 L 32.5 15.2 C 33 12.6, 34.8 11.2, 37 11.2 Z M 36 198 L 36 78 L 22 198 Z",
    "M 14 11.2 L 53 11.2 C 56 11.2, 58 13.2, 58 16.2 L 58 30.5 C 58 33.5, 56 35.5, 53 35.5 L 26 35.5 L 26 118 C 29.5 113.5, 34.5 111.5, 40.5 111.5 C 52.5 111.5, 60 122, 60 143 L 60 246.8 C 60 268.8, 50.5 279.6, 34.5 279.6 C 18.5 279.6, 9 268.8, 9 246.8 L 9 224 C 9 221, 11 219, 14 219 L 21 219 C 24 219, 26 221, 26 224 L 26 245 C 26 252, 29 255.3, 34.5 255.3 C 40 255.3, 43 252, 43 245 L 43 145 C 43 137.5, 40 134.5, 34.5 134.5 C 29 134.5, 26 137.5, 26 144 L 26 150 C 26 153, 24 155, 21 155 L 14 155 C 11 155, 9 153, 9 150 L 9 16.2 C 9 13.2, 11 11.2, 14 11.2 Z",
    "M 34.5 11.2 C 50.5 11.2, 60 22, 60 44 L 60 66 C 60 69, 58 71, 55 71 L 48 71 C 45 71, 43 69, 43 66 L 43 45.8 C 43 38.8, 40 35.5, 34.5 35.5 C 29 35.5, 26 38.8, 26 45.8 L 26 122 C 29.5 117.5, 34.5 115.5, 40.5 115.5 C 52.5 115.5, 60 126, 60 147 L 60 246.8 C 60 268.8, 50.5 279.6, 34.5 279.6 C 18.5 279.6, 9 268.8, 9 246.8 L 9 44 C 9 22, 18.5 11.2, 34.5 11.2 Z M 34.5 138.5 C 29 138.5, 26 141.5, 26 149 L 26 245 C 26 252, 29 255.3, 34.5 255.3 C 40 255.3, 43 252, 43 245 L 43 149 C 43 141.5, 40 138.5, 34.5 138.5 Z",
    "M 13 11.2 L 55 11.2 C 58 11.2, 60 13.2, 60 16.2 L 60 36 C 60 39, 59.5 42, 58.6 46 L 34.2 275.2 C 33.8 278, 32 279.6, 29 279.6 L 20.5 279.6 C 17.5 279.6, 15.8 277.6, 16.2 274.4 L 41.5 35.5 L 13 35.5 C 10 35.5, 8 33.5, 8 30.5 L 8 16.2 C 8 13.2, 10 11.2, 13 11.2 Z",
    "M 34.5 11.2 C 50.5 11.2, 60 22, 60 44 L 60 112 C 60 126, 55.5 135.5, 47.5 141 C 55.5 146.5, 60 156, 60 170 L 60 246.8 C 60 268.8, 50.5 279.6, 34.5 279.6 C 18.5 279.6, 9 268.8, 9 246.8 L 9 170 C 9 156, 13.5 146.5, 21.5 141 C 13.5 135.5, 9 126, 9 112 L 9 44 C 9 22, 18.5 11.2, 34.5 11.2 Z M 34.5 35.5 C 29 35.5, 26 38.8, 26 45.8 L 26 114 C 26 123, 29 128, 34.5 128 C 40 128, 43 123, 43 114 L 43 45.8 C 43 38.8, 40 35.5, 34.5 35.5 Z M 34.5 153 C 29 153, 26 158, 26 168 L 26 245 C 26 252, 29 255.3, 34.5 255.3 C 40 255.3, 43 252, 43 245 L 43 168 C 43 158, 40 153, 34.5 153 Z",
    "M 34.5 11.2 C 50.5 11.2, 60 22, 60 44 L 60 246.8 C 60 268.8, 50.5 279.6, 34.5 279.6 C 18.5 279.6, 9 268.8, 9 246.8 L 9 224.8 C 9 221.8, 11 219.8, 14 219.8 L 21 219.8 C 24 219.8, 26 221.8, 26 224.8 L 26 245 C 26 252, 29 255.3, 34.5 255.3 C 40 255.3, 43 252, 43 245 L 43 168.8 C 39.5 173.3, 34.5 175.3, 28.5 175.3 C 16.5 175.3, 9 164.8, 9 143.8 L 9 44 C 9 22, 18.5 11.2, 34.5 11.2 Z M 34.5 35.5 C 29 35.5, 26 38.8, 26 45.8 L 26 141.8 C 26 149.3, 29 152.3, 34.5 152.3 C 40 152.3, 43 149.3, 43 141.8 L 43 45.8 C 43 38.8, 40 35.5, 34.5 35.5 Z"
)

private val PATH_TOKEN = Regex("([MCLZmclz])|(-?[0-9]*\\.?[0-9]+)")

private fun buildDigitPaths(): Array<Path> = DIGIT_PATHS.map { parseSvgPath(it) }.toTypedArray()

private fun parseSvgPath(source: String): Path {
    val path = Path()
    val tokens = PATH_TOKEN.findAll(source).map { it.value }.toList()
    var index = 0
    var command = 'M'

    while (index < tokens.size) {
        val token = tokens[index]
        if (token.length == 1 && token[0].isLetter()) {
            command = token[0]
            index++
            if (command == 'Z' || command == 'z') path.close()
            continue
        }

        when (command) {
            'M', 'L' -> {
                if (index + 1 >= tokens.size) break
                val x = tokens[index].toFloat()
                val y = tokens[index + 1].toFloat()
                if (command == 'M') {
                    path.moveTo(x, y)
                    command = 'L'
                } else {
                    path.lineTo(x, y)
                }
                index += 2
            }
            'C' -> {
                if (index + 5 >= tokens.size) break
                path.cubicTo(
                    tokens[index].toFloat(),
                    tokens[index + 1].toFloat(),
                    tokens[index + 2].toFloat(),
                    tokens[index + 3].toFloat(),
                    tokens[index + 4].toFloat(),
                    tokens[index + 5].toFloat()
                )
                index += 6
            }
            else -> index++
        }
    }

    return path
}
