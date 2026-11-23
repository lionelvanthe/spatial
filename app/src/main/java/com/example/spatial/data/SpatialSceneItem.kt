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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpatialSceneItem) return false
        return id == other.id &&
            title == other.title &&
            folderPath == other.folderPath &&
            isCustom == other.isCustom &&
            customImageUri == other.customImageUri &&
            config == other.config &&
            thumbnailBitmap === other.thumbnailBitmap &&
            fullImageBitmap === other.fullImageBitmap &&
            depthData.contentEquals(other.depthData) &&
            depthWidth == other.depthWidth &&
            depthHeight == other.depthHeight
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + folderPath.hashCode()
        result = 31 * result + isCustom.hashCode()
        result = 31 * result + (customImageUri?.hashCode() ?: 0)
        result = 31 * result + config.hashCode()
        result = 31 * result + (thumbnailBitmap?.hashCode() ?: 0)
        result = 31 * result + (fullImageBitmap?.hashCode() ?: 0)
        result = 31 * result + (depthData?.contentHashCode() ?: 0)
        result = 31 * result + depthWidth
        result = 31 * result + depthHeight
        return result
    }
}
