package com.example.spatial.wallpaper

import android.content.Context
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.example.spatial.data.AssetDataLoader
import com.example.spatial.gl.SpatialGLRenderer
import com.example.spatial.sensor.SpatialSensorTracker

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

        private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "wallpaper_scene_id" || key == "wallpaper_config" || key == "wallpaper_is_custom") {
                loadCurrentWallpaperScene()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)

            renderer = SpatialGLRenderer(applicationContext)
            sensorTracker = SpatialSensorTracker(applicationContext) { tiltX, tiltY ->
                renderer?.gyroX = tiltX
                renderer?.gyroY = tiltY
            }

            loadCurrentWallpaperScene()

            val prefs = applicationContext.getSharedPreferences("spatial_wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)

            glSurfaceView = WallpaperGLSurfaceView(applicationContext)
            setTouchEventsEnabled(true)
        }

        private fun loadCurrentWallpaperScene() {
            try {
                val prefs = applicationContext.getSharedPreferences("spatial_wallpaper_prefs", Context.MODE_PRIVATE)
                val isCustom = prefs.getBoolean("wallpaper_is_custom", false)
                val configStr = prefs.getString("wallpaper_config", null)
                val customConfig = if (!configStr.isNullOrEmpty()) {
                    com.example.spatial.data.SpatialSceneConfig.fromJson(configStr)
                } else null

                if (isCustom) {
                    val imgFile = java.io.File(applicationContext.filesDir, "custom_wallpaper.jpg")
                    val depthFile = java.io.File(applicationContext.filesDir, "custom_depth.bin")
                    if (imgFile.exists() && depthFile.exists()) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(imgFile.absolutePath)
                        val depthBytes = depthFile.readBytes()
                        val depthWidth = prefs.getInt("wallpaper_depth_width", 504)
                        val depthHeight = prefs.getInt("wallpaper_depth_height", 504)
                        renderer?.setSceneData(
                            bitmap,
                            depthBytes,
                            customConfig ?: com.example.spatial.data.SpatialSceneConfig(),
                            depthWidth,
                            depthHeight
                        )
                        return
                    }
                }

                val sceneId = prefs.getString("wallpaper_scene_id", "alpine") ?: "alpine"
                val fullScene = AssetDataLoader.loadPresetSceneById(applicationContext, sceneId)
                    ?: AssetDataLoader.loadPresetSceneById(applicationContext, "alpine")

                if (fullScene != null) {
                    renderer?.setSceneData(
                        fullScene.fullImageBitmap,
                        fullScene.depthData,
                        customConfig ?: fullScene.config
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("SpatialWallpaperService", "Error loading wallpaper scene", e)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                loadCurrentWallpaperScene()
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
            event?.let { glSurfaceView?.dispatchTouchEvent(it) }
        }

        override fun onDestroy() {
            super.onDestroy()
            val prefs = applicationContext.getSharedPreferences("spatial_wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            sensorTracker?.stop()
            glSurfaceView?.onDestroy()
            glSurfaceView = null
        }

        inner class WallpaperGLSurfaceView(context: Context) : GLSurfaceView(context) {
            init {
                setEGLContextClientVersion(2)
                renderer?.let { setRenderer(it) }
                renderMode = RENDERMODE_CONTINUOUSLY
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
