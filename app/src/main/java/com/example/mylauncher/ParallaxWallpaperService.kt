package com.example.mylauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

/**
 * Live wallpaper with two layers: the back photo and a front cut-out that drifts
 * further, so the home screen and the lock screen both tilt with the phone.
 */
class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    private inner class ParallaxEngine : Engine(), SensorEventListener {

        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val backFile = File(filesDir, "wall_back.jpg")
        private val frontFile = File(filesDir, "wall_front.png")

        private var back: Bitmap? = null
        private var front: Bitmap? = null
        private var tiltX = 0f
        private var tiltY = 0f
        private var visible = false
        private var sensorManager: SensorManager? = null
        private val draw = Runnable { drawFrame() }

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
            } else {
                manager?.unregisterListener(this)
                handler.removeCallbacks(draw)
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
        }

        override fun onDestroy() {
            sensorManager?.unregisterListener(this)
            handler.removeCallbacks(draw)
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
