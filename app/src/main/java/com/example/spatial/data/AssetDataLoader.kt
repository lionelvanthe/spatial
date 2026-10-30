package com.example.spatial.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.InputStream

object AssetDataLoader {
    private const val TAG = "AssetDataLoader"

    // Preset scenes from the original APK
    val PRESET_SCENE_IDS = listOf(
        "alpine" to "Alpine Mountains",
        "astronaut" to "Astronaut Cosmic",
        "beach" to "Tropical Beach",
        "cliff_village" to "Cliffside Village",
        "lion" to "Majestic Lion",
        "mountain_lake" to "Mountain Lake",
        "mountain_tree" to "Pine Tree Horizon",
        "panda" to "Giant Panda",
        "rose_skull" to "Rose & Skull",
        "sharma_dog" to "Golden Pup",
        "tiger" to "Wild Tiger",
        "tiger_astral" to "Astral Tiger",
        "unicorn" to "Mystic Unicorn",
        "winter_cat" to "Winter Kitty",
        "anime_fighter" to "Cyber Fighter",
        "woman_selfie" to "Portrait Selfie"
    )

    fun loadAllPresetScenes(context: Context): List<SpatialSceneItem> {
        val result = mutableListOf<SpatialSceneItem>()

        for ((id, title) in PRESET_SCENE_IDS) {
            // Check wallpapers first, then samples
            var folder = "wallpapers/$id"
            var descStream: InputStream? = null
            try {
                descStream = context.assets.open("$folder/${id}_desc.dat")
            } catch (e: Exception) {
                folder = "samples/$id"
                try {
                    descStream = context.assets.open("$folder/${id}_desc.dat")
                } catch (e2: Exception) {
                    Log.w(TAG, "Could not open desc for $id")
                }
            }

            var config = SpatialSceneConfig()
            if (descStream != null) {
                try {
                    val jsonStr = descStream.bufferedReader().use { it.readText() }
                    config = SpatialSceneConfig.fromJson(jsonStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing config for $id", e)
                }
            }

            // Load thumbnail eagerly for smooth gallery scroll
            var thumbBmp: Bitmap? = null
            try {
                context.assets.open("$folder/${id}_thumb.dat").use { stream ->
                    thumbBmp = BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Thumbnail not found for $id: ${e.message}")
            }

            result.add(
                SpatialSceneItem(
                    id = id,
                    title = title,
                    folderPath = folder,
                    isCustom = false,
                    config = config,
                    thumbnailBitmap = thumbBmp
                )
            )
        }

        return result
    }

    fun loadPresetSceneById(context: Context, id: String): SpatialSceneItem? {
        val preset = loadAllPresetScenes(context).firstOrNull { it.id.equals(id, ignoreCase = true) }
        return preset?.let { loadSceneFullData(context, it) }
    }

    fun loadSceneFullData(context: Context, item: SpatialSceneItem): SpatialSceneItem {
        if (item.isCustom) return item

        val folder = item.folderPath
        val id = item.id

        var fullBmp: Bitmap? = item.fullImageBitmap
        if (fullBmp == null) {
            try {
                context.assets.open("$folder/${id}_img.dat").use { stream ->
                    fullBmp = BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading full image for $id", e)
            }
        }

        var depth: ByteArray? = item.depthData
        if (depth == null) {
            try {
                context.assets.open("$folder/${id}_depth.dat").use { stream ->
                    val raw = stream.readBytes()
                    // Preset scenes from original APK have 1 header byte + 504x504 bytes stored in column-major order (x * 504 + y).
                    // We transpose them to standard row-major order (y * 504 + x) so the depth relief aligns 100% with the image.
                    depth = if (raw.size == 254017) {
                        val src = raw.copyOfRange(1, raw.size)
                        val transposed = ByteArray(504 * 504)
                        for (x in 0 until 504) {
                            val colOffset = x * 504
                            for (y in 0 until 504) {
                                transposed[y * 504 + x] = src[colOffset + y]
                            }
                        }
                        transposed
                    } else {
                        raw
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading depth for $id", e)
            }
        }

        return item.copy(
            fullImageBitmap = fullBmp,
            depthData = depth
        )
    }
}
