package com.example.spatial.ui

import android.app.Application
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spatial.ai.DepthEstimationManager
import com.example.spatial.data.AssetDataLoader
import com.example.spatial.data.SpatialSceneConfig
import com.example.spatial.data.SpatialSceneItem
import com.example.spatial.wallpaper.SpatialWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpatialViewModel(application: Application) : AndroidViewModel(application) {

    private val depthManager = DepthEstimationManager(application)

    private val _scenes = MutableStateFlow<List<SpatialSceneItem>>(emptyList())
    val scenes: StateFlow<List<SpatialSceneItem>> = _scenes.asStateFlow()

    private val _currentScene = MutableStateFlow<SpatialSceneItem?>(null)
    val currentScene: StateFlow<SpatialSceneItem?> = _currentScene.asStateFlow()

    private val _currentConfig = MutableStateFlow(SpatialSceneConfig())
    val currentConfig: StateFlow<SpatialSceneConfig> = _currentConfig.asStateFlow()

    private val _isSensorEnabled = MutableStateFlow(true)
    val isSensorEnabled: StateFlow<Boolean> = _isSensorEnabled.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _processProgress = MutableStateFlow(0.0f)
    val processProgress: StateFlow<Float> = _processProgress.asStateFlow()

    init {
        loadPresetScenes()
        viewModelScope.launch {
            depthManager.initialize()
        }
    }

    private fun loadPresetScenes() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = AssetDataLoader.loadAllPresetScenes(getApplication())
            _scenes.value = list

            val initial = list.find { it.id == "woman_selfie" } ?: list.firstOrNull()
            if (initial != null) {
                selectScene(initial)
            }
        }
    }

    fun selectScene(item: SpatialSceneItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val fullItem = if (!item.isCustom) {
                AssetDataLoader.loadSceneFullData(getApplication(), item)
            } else {
                item
            }
            _currentScene.value = fullItem
            _currentConfig.value = fullItem.config
            persistCurrentSceneForWallpaper(fullItem, fullItem.config)
        }
    }

    private fun persistConfigForWallpaper(config: SpatialSceneConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = getApplication<Application>().getSharedPreferences("spatial_wallpaper_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString("wallpaper_config", config.toJson())
                .apply()
        }
    }

    private fun persistCurrentSceneForWallpaper(item: SpatialSceneItem, config: SpatialSceneConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val prefs = app.getSharedPreferences("spatial_wallpaper_prefs", android.content.Context.MODE_PRIVATE)
            if (item.isCustom) {
                try {
                    val imgFile = java.io.File(app.filesDir, "custom_wallpaper.jpg")
                    item.fullImageBitmap?.let { bmp ->
                        java.io.FileOutputStream(imgFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    }
                    val depthFile = java.io.File(app.filesDir, "custom_depth.bin")
                    item.depthData?.let { bytes ->
                        java.io.FileOutputStream(depthFile).use { out ->
                            out.write(bytes)
                        }
                    }
                    prefs.edit()
                        .putBoolean("wallpaper_is_custom", true)
                        .putInt("wallpaper_depth_width", item.depthWidth)
                        .putInt("wallpaper_depth_height", item.depthHeight)
                        .putString("wallpaper_config", config.toJson())
                        .apply()
                } catch (e: Exception) {
                    Log.e("SpatialViewModel", "Failed persisting custom wallpaper", e)
                }
            } else {
                prefs.edit()
                    .putBoolean("wallpaper_is_custom", false)
                    .putString("wallpaper_scene_id", item.id)
                    .putString("wallpaper_config", config.toJson())
                    .apply()
            }
        }
    }

    fun updateIntensity(intensity: Float) {
        val newCfg = _currentConfig.value.copy(intensity = intensity)
        _currentConfig.value = newCfg
        persistConfigForWallpaper(newCfg)
    }

    fun updateDepthCenter(depthCenter: Float) {
        val newCfg = _currentConfig.value.copy(depthCenter = depthCenter)
        _currentConfig.value = newCfg
        persistConfigForWallpaper(newCfg)
    }

    fun updateSpeed(speed: Float) {
        val newCfg = _currentConfig.value.copy(speed = speed)
        _currentConfig.value = newCfg
        persistConfigForWallpaper(newCfg)
    }

    fun updateZoom(zoom: Float) {
        val newCfg = _currentConfig.value.copy(zoom = zoom)
        _currentConfig.value = newCfg
        persistConfigForWallpaper(newCfg)
    }

    fun updateZoom3D(zoom3D: Float) {
        val newCfg = _currentConfig.value.copy(zoom3D = zoom3D)
        _currentConfig.value = newCfg
        persistConfigForWallpaper(newCfg)
    }

    fun updateInverted(rInverted: Boolean) {
        val newCfg = _currentConfig.value.copy(rInverted = rInverted)
        _currentConfig.value = newCfg
        persistConfigForWallpaper(newCfg)
    }

    fun updatePattern(patternId: String) {
        val newCfg = _currentConfig.value.copy(patternId = patternId)
        _currentConfig.value = newCfg
        persistConfigForWallpaper(newCfg)
    }

    fun toggleSensor() {
        _isSensorEnabled.value = !_isSensorEnabled.value
    }

    fun resetToDefault() {
        val scene = _currentScene.value ?: return
        _currentConfig.value = scene.config
        persistConfigForWallpaper(scene.config)
    }

    fun processCustomImage(uri: Uri) {
        // Publish setup state before starting the coroutine so Compose can immediately transition
        // from the picker to the progress screen without a one-frame gallery flash.
        _isProcessing.value = true
        _processProgress.value = 0.05f
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)) { decoder, _, _ ->
                            decoder.isMutableRequired = true
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(getApplication<Application>().contentResolver, uri)
                    }
                }

                // Scale down if huge for fast processing
                val maxDim = 1280
                val scaledBmp = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val targetW = if (ratio > 1) maxDim else (maxDim * ratio).toInt()
                    val targetH = if (ratio > 1) (maxDim / ratio).toInt() else maxDim
                    Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                } else {
                    bitmap
                }

                val depthBytes = depthManager.estimateDepth(scaledBmp) { prog ->
                    _processProgress.value = prog
                }

                val customItem = SpatialSceneItem(
                    id = "custom_${System.currentTimeMillis()}",
                    title = "My 3D Spatial Photo",
                    folderPath = "",
                    isCustom = true,
                    customImageUri = uri.toString(),
                    config = SpatialSceneConfig(
                        intensity = 0.08f,
                        depthCenter = 0.5f,
                        speed = 0.35f,
                        zoom = 0.0f,
                        zoom3D = 0.0f,
                        patternId = "none"
                    ),
                    thumbnailBitmap = squareThumbnail(scaledBmp, 200),
                    fullImageBitmap = scaledBmp,
                    depthData = depthBytes
                )

                _scenes.value = listOf(customItem) + _scenes.value
                _currentScene.value = customItem
                _currentConfig.value = customItem.config
                persistCurrentSceneForWallpaper(customItem, customItem.config)

            } catch (e: Exception) {
                Log.e("SpatialViewModel", "Failed processing custom image", e)
            } finally {
                _isProcessing.value = false
                _processProgress.value = 0.0f
            }
        }
    }

    /** Centre-crops to a square before scaling, so the gallery thumbnail is never stretched. */
    private fun squareThumbnail(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val square = Bitmap.createBitmap(src, x, y, side, side)
        return Bitmap.createScaledBitmap(square, size, size, true)
    }

    fun launchSetLiveWallpaperIntent() {
        val app = getApplication<Application>()
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(app, SpatialWallpaperService::class.java)
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(app.packageManager) != null) {
                app.startActivity(intent)
                return
            }
        } catch (e: Exception) {
            Log.w("SpatialViewModel", "ACTION_CHANGE_LIVE_WALLPAPER failed, trying fallback", e)
        }

        try {
            val chooserIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (chooserIntent.resolveActivity(app.packageManager) != null) {
                app.startActivity(chooserIntent)
                return
            }
        } catch (e: Exception) {
            Log.w("SpatialViewModel", "ACTION_LIVE_WALLPAPER_CHOOSER failed", e)
        }

        try {
            // Explicit intent for Xiaomi / MIUI live wallpaper chooser
            val miuiIntent = Intent().apply {
                component = ComponentName("com.android.wallpaper.livepicker", "com.android.wallpaper.livepicker.LiveWallpaperActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (miuiIntent.resolveActivity(app.packageManager) != null) {
                app.startActivity(miuiIntent)
                return
            }
        } catch (e: Exception) {
            Log.w("SpatialViewModel", "LiveWallpaperActivity failed", e)
        }

        try {
            val fallback = Intent("android.service.wallpaper.LIVE_WALLPAPER_CHOOSER").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(fallback)
        } catch (e: Exception) {
            Log.e("SpatialViewModel", "All live wallpaper intents failed", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        depthManager.close()
    }
}
