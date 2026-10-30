package com.example.spatial.data

import android.graphics.Bitmap

/**
 * Representation of a selectable 3D scene / wallpaper
 */
data class SpatialSceneItem(
    val id: String,
    val title: String,
    val folderPath: String,
    val isCustom: Boolean = false,
    val customImageUri: String? = null,
    val config: SpatialSceneConfig = SpatialSceneConfig(),
    var thumbnailBitmap: Bitmap? = null,
    var fullImageBitmap: Bitmap? = null,
    var depthData: ByteArray? = null,
    val depthWidth: Int = 504,
    val depthHeight: Int = 504
)
