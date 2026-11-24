package com.example.spatial.wallpaper

import android.content.Context
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.example.spatial.data.AssetDataLoader
import com.example.spatial.gl.SpatialGLRenderer
import com.example.spatial.sensor.SpatialSensorTracker
import java.util.concurrent.Executors

/**
 * Interactive 3D Spatial Live Wallpaper Service.
 */
class SpatialWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return SpatialWallpaperEngine()
    }

    inner class SpatialWallpaperEngine : Engine() {
        private var glSurfaceView: WallpaperGLSurfaceView? = null
        private var renderer: SpatialGLRenderer? = null
        private var sensorTracker: SpatialSensorTracker? = null
        private val loaderExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SpatialWallpaperLoader")
        }
        @Volatile
        private var isDestroyed = false
        @Volatile
        private var lastLoadedSignature: String? = null

        private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "wallpaper_scene_id" ||
                key == "wallpaper_config" ||
                key == "wallpaper_is_custom" ||
                key == "wallpaper_tab_mode" ||
                key == "wallpaper_updated_at"
            ) {
                scheduleLoadWallpaperScene(force = true)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)

            renderer = SpatialGLRenderer(applicationContext).apply {
                isWallpaperFillMode = true
            }
            sensorTracker = SpatialSensorTracker(
                context = applicationContext,
                onSensorUpdate = { tiltX, tiltY ->
                    renderer?.gyroX = tiltX
                    renderer?.gyroY = tiltY
                },
                onLinearAcceleration = { ax, ay ->
                    renderer?.onLinearAcceleration(ax, ay)
                }
            )

            scheduleLoadWallpaperScene(force = true)

            val prefs = applicationContext.getSharedPreferences("spatial_wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)

            glSurfaceView = WallpaperGLSurfaceView(applicationContext)
            setTouchEventsEnabled(true)
        }

        private fun scheduleLoadWallpaperScene(force: Boolean = false) {
            if (isDestroyed || loaderExecutor.isShutdown) return
            loaderExecutor.execute {
                if (isDestroyed) return@execute
                loadCurrentWallpaperScene(force)
            }
        }

        private fun loadCurrentWallpaperScene(force: Boolean) {
            try {
                val prefs = applicationContext.getSharedPreferences("spatial_wallpaper_prefs", Context.MODE_PRIVATE)
                val isCustom = prefs.getBoolean("wallpaper_is_custom", false)
                val sceneId = prefs.getString("wallpaper_scene_id", "alpine") ?: "alpine"
                val configStr = prefs.getString("wallpaper_config", null)
                val tabMode = prefs.getInt("wallpaper_tab_mode", 0).let { if (it in 0..2) it else 0 }
                val updatedAt = prefs.getLong("wallpaper_updated_at", 0L)
                val customImgFile = java.io.File(applicationContext.filesDir, "custom_wallpaper.jpg")
                val signature = "$isCustom|$sceneId|$configStr|$tabMode|$updatedAt|${if (isCustom) customImgFile.lastModified() else 0L}"
                if (!force && signature == lastLoadedSignature) return

                renderer?.activeTabMode = tabMode

                val customConfig = if (!configStr.isNullOrEmpty()) {
                    com.example.spatial.data.SpatialSceneConfig.fromJson(configStr)
                } else null

                if (isCustom) {
                    val depthFile = java.io.File(applicationContext.filesDir, "custom_depth.bin")
                    if (customImgFile.exists() && depthFile.exists()) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(customImgFile.absolutePath)
                        val depthBytes = depthFile.readBytes()
                        val depthWidth = prefs.getInt("wallpaper_depth_width", 504)
                        val depthHeight = prefs.getInt("wallpaper_depth_height", 504)
                        if (bitmap != null && !isDestroyed) {
                            renderer?.setSceneData(
                                bitmap,
                                depthBytes,
                                customConfig ?: com.example.spatial.data.SpatialSceneConfig(),
                                depthWidth,
                                depthHeight
                            )
                            lastLoadedSignature = signature
                            return
                        }
                    }
                }

                val fullScene = AssetDataLoader.loadPresetSceneById(applicationContext, sceneId)
                    ?: AssetDataLoader.loadPresetSceneById(applicationContext, "alpine")

                if (fullScene != null && !isDestroyed) {
                    renderer?.setSceneData(
                        fullScene.fullImageBitmap,
                        fullScene.depthData,
                        customConfig ?: fullScene.config,
                        fullScene.depthWidth,
                        fullScene.depthHeight
                    )
                    lastLoadedSignature = signature
                }
            } catch (e: Exception) {
                android.util.Log.e("SpatialWallpaperService", "Error loading wallpaper scene", e)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                scheduleLoadWallpaperScene(force = false)
                sensorTracker?.start()
                glSurfaceView?.onResume()
            } else {
                sensorTracker?.stop()
                glSurfaceView?.onPause()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            android.util.Log.d("SpatialWallpaper", "Engine.onSurfaceCreated")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            android.util.Log.d("SpatialWallpaper", "Engine.onSurfaceChanged: ${width}x${height}")
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            android.util.Log.d("SpatialWallpaper", "Engine.onSurfaceDestroyed")
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            event?.let { glSurfaceView?.handleWallpaperTouch(it) }
        }

        override fun onDestroy() {
            isDestroyed = true
            super.onDestroy()
            val prefs = applicationContext.getSharedPreferences("spatial_wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            sensorTracker?.stop()
            loaderExecutor.shutdownNow()
            val activeRenderer = renderer
            val activeView = glSurfaceView
            activeView?.onDestroy()
            activeRenderer?.releaseNative()
            glSurfaceView = null
            renderer = null
        }

        inner class WallpaperGLSurfaceView(context: Context) : GLSurfaceView(context) {
            private var prevX = 0f
            private var prevY = 0f

            init {
                setEGLContextClientVersion(2)
                preserveEGLContextOnPause = true
                renderer?.let { setRenderer(it) }
                renderMode = RENDERMODE_CONTINUOUSLY
            }

            fun handleWallpaperTouch(event: MotionEvent) {
                val r = renderer ?: return
                val w = width.toFloat().coerceAtLeast(1f)
                val h = height.toFloat().coerceAtLeast(1f)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        prevX = event.x
                        prevY = event.y
                        r.onTouchDown(event.x, event.y)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.x - prevX) / w
                        val dy = (event.y - prevY) / h
                        r.onTouchMove(dx, dy, event.x, event.y)
                        prevX = event.x
                        prevY = event.y
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        r.onTouchUp(0f, 0f)
                    }
                }
            }

            override fun getHolder(): SurfaceHolder {
                return this@SpatialWallpaperEngine.surfaceHolder
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                android.util.Log.d("SpatialWallpaper", "GLSurfaceView.surfaceCreated")
                super.surfaceCreated(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                android.util.Log.d("SpatialWallpaper", "GLSurfaceView.surfaceChanged: ${w}x${h}")
                super.surfaceChanged(holder, format, w, h)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                android.util.Log.d("SpatialWallpaper", "GLSurfaceView.surfaceDestroyed")
                super.surfaceDestroyed(holder)
            }

            fun onDestroy() {
                super.onDetachedFromWindow()
            }
        }
    }
}
