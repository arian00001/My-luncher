package com.example.mylauncher

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * Live wallpaper with two layers: the back photo and a front cut-out that drifts
 * further, so the home screen and the lock screen both tilt with the phone.
 *
 * On the lock screen it also paints the launcher's own clock (the same design the
 * web home screen uses), because Android draws the lock clock from the system
 * and a launcher cannot restyle it. The clock is only painted while the device
 * is locked, so the home screen never shows it twice.
 */
class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    private inner class ParallaxEngine : Engine(), SensorEventListener {

        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val backFile = File(filesDir, "wall_back.jpg")
        private val frontFile = File(filesDir, "wall_front.png")

        private var back: Bitmap? = null
        private var front: Bitmap? = null
        private var tiltX = 0f
        private var tiltY = 0f
        private var visible = false
        private var clockFace: Typeface? = null
        private var sensorManager: SensorManager? = null
        private val draw = Runnable { drawFrame() }
        private val tick = object : Runnable {
            override fun run() {
                if (!visible) return
                drawFrame()
                handler.postDelayed(this, 15000L)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
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
                drawFrame()
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, 15000L)
            } else {
                manager?.unregisterListener(this)
                handler.removeCallbacks(draw)
                handler.removeCallbacks(tick)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(draw)
            handler.removeCallbacks(tick)
        }

        override fun onDestroy() {
            sensorManager?.unregisterListener(this)
            handler.removeCallbacks(draw)
            handler.removeCallbacks(tick)
            super.onDestroy()
        }

        private fun loadLayers() {
            back = BitmapFactory.decodeFile(backFile.path) ?: back
            front = if (frontFile.exists()) BitmapFactory.decodeFile(frontFile.path) else null
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (!visible) return
            tiltX = (event.values[0] / 9.8f).coerceIn(-1f, 1f)
            tiltY = (event.values[1] / 9.8f).coerceIn(-1f, 1f)
            handler.removeCallbacks(draw)
            handler.post(draw)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun isLocked(): Boolean {
            val manager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
            return manager.isKeyguardLocked
        }

        private fun clockTypeface(): Typeface {
            clockFace?.let { return it }
            val face = try {
                Typeface.Builder(assets, "fonts/montserrat.ttf")
                    .setFontVariationSettings("'wght' 900")
                    .build()
            } catch (_: Throwable) {
                Typeface.create("sans-serif-black", Typeface.BOLD)
            }
            clockFace = face
            return face
        }

        /** The launcher clock, right aligned, exactly as the web home screen draws it. */
        private fun drawClock(canvas: Canvas, width: Float) {
            val unit = width / 360f
            val now = Calendar.getInstance()
            val hour = if (now.get(Calendar.HOUR) == 0) 12 else now.get(Calendar.HOUR)
            val time = String.format(Locale.US, "%d.%02d", hour, now.get(Calendar.MINUTE))
            val months = arrayOf(
                "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
            )
            val date = String.format(
                Locale.US,
                "%02d %s %d",
                now.get(Calendar.DAY_OF_MONTH),
                months[now.get(Calendar.MONTH)],
                now.get(Calendar.YEAR)
            )
            val right = width - 20f * unit
            val dateBaseline = 56f * unit

            datePaint.apply {
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textSize = 12f * unit
                letterSpacing = 0.28f
                textAlign = Paint.Align.RIGHT
                color = 0xFFE8E2E2.toInt()
            }
            clockPaint.apply {
                typeface = clockTypeface()
                textSize = 76f * unit
                letterSpacing = -0.04f
                textAlign = Paint.Align.RIGHT
                color = 0xFFFCEEED.toInt()
                setShadowLayer(18f * unit, 0f, 4f * unit, 0x66000000)
            }

            canvas.drawText(date, right, dateBaseline, datePaint)
            canvas.drawText(time, right, dateBaseline + 63f * unit, clockPaint)
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
                back?.let { bitmap ->
                    canvas.save()
                    canvas.translate(-tiltX * width * 0.02f, -tiltY * height * 0.015f)
                    canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), paint)
                    canvas.restore()
                }
                front?.let { bitmap ->
                    canvas.save()
                    canvas.translate(-tiltX * width * 0.06f, -tiltY * height * 0.045f)
                    canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), paint)
                    canvas.restore()
                }
                if (isLocked()) drawClock(canvas, width)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                    /* surface gone */
                }
            }
        }
    }
}
