package com.example.spatial.data

/** Rectangular, row-major depth data.  Dimensions are persisted; never inferred from byte count. */
data class DepthMap(val width: Int, val height: Int, val bytes: ByteArray) {
    init { require(width > 0 && height > 0 && bytes.size == width * height) }

    companion object {
        fun orNull(bytes: ByteArray?, width: Int, height: Int): DepthMap? =
            if (bytes != null && width > 0 && height > 0 && bytes.size == width * height) DepthMap(width, height, bytes) else null
    }
}
