package com.example.spatial.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.pow

/**
 * 3D deformable grid mesh for Spatial Scenes.
 * Computes smooth depth parallax deformation per-vertex without tearing or distortion.
 */
class SpatialMesh(
    private val gridWidth: Int = 56,
    private val gridHeight: Int = 56
) {
    // Each vertex: X, Y, Z, U, V (5 floats, 20 bytes)
    private val vertexCount = (gridWidth + 1) * (gridHeight + 1)
    val indicesCount = gridWidth * gridHeight * 6

    private val depthValues = FloatArray(vertexCount)
    private val smoothedDepth = FloatArray(vertexCount)

    private val vertexArray = FloatArray(vertexCount * 5)
    private val vertexBuffer: FloatBuffer
    private val indexBuffer: ShortBuffer

    var isInitialized = false
        private set

    init {
        // Allocate direct buffers for OpenGL
        val bb = ByteBuffer.allocateDirect(vertexArray.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()

        val ib = ByteBuffer.allocateDirect(indicesCount * 2)
        ib.order(ByteOrder.nativeOrder())
        indexBuffer = ib.asShortBuffer()

        buildBaseGridAndIndices()
    }

    private fun buildBaseGridAndIndices() {
        for (i in depthValues.indices) {
            depthValues[i] = 0.5f
            smoothedDepth[i] = 0.5f
        }

        // Build index buffer for triangles
        val indices = ShortArray(indicesCount)
        var iIndex = 0
        val rowStride = gridWidth + 1

        for (y in 0 until gridHeight) {
            for (x in 0 until gridWidth) {
                val topLeft = (y * rowStride + x).toShort()
                val topRight = (topLeft + 1).toShort()
                val bottomLeft = ((y + 1) * rowStride + x).toShort()
                val bottomRight = (bottomLeft + 1).toShort()

                // Triangle 1: topLeft -> bottomLeft -> topRight
                indices[iIndex++] = topLeft
                indices[iIndex++] = bottomLeft
                indices[iIndex++] = topRight

                // Triangle 2: topRight -> bottomLeft -> bottomRight
                indices[iIndex++] = topRight
                indices[iIndex++] = bottomLeft
                indices[iIndex++] = bottomRight
            }
        }

        indexBuffer.position(0)
        indexBuffer.put(indices)
        indexBuffer.position(0)

        updateMesh(
            meshWidth = 2.0f,
            meshHeight = 2.0f,
            intensity = 1.0f,
            depthCenter = 0.5f,
            shiftZ = 0.0f,
            zoom3D = 0.0f
        )
        isInitialized = true
    }

    /**
     * Samples depth map into vertex depth values using bilinear filtering
     * and a 3x3 smoothing pass to eliminate edge ripple artifacts.
     */
    fun setDepthMap(depthMap: ByteArray?, depthW: Int = 504, depthH: Int = 504) {
        if (depthMap == null || depthMap.isEmpty()) {
            depthValues.fill(0.5f)
            smoothedDepth.fill(0.5f)
            return
        }

        // Percentile contrast stretch so near and far use the full 0..1 range.
        val hist = IntArray(256)
        for (b in depthMap) hist[b.toInt() and 0xFF]++
        val lowTarget = depthMap.size * 0.02f
        val highTarget = depthMap.size * 0.98f
        var acc = 0
        var lo = 0
        var hi = 255
        var loFound = false
        for (i in 0..255) {
            acc += hist[i]
            if (!loFound && acc >= lowTarget) { lo = i; loFound = true }
            if (acc >= highTarget) { hi = i; break }
        }
        val range = (hi - lo).coerceAtLeast(16).toFloat()

        fun getPixel(px: Int, py: Int): Float {
            val cx = px.coerceIn(0, depthW - 1)
            val cy = py.coerceIn(0, depthH - 1)
            val idx = cy * depthW + cx
            return if (idx in depthMap.indices) {
                (((depthMap[idx].toInt() and 0xFF) - lo) / range).coerceIn(0.0f, 1.0f)
            } else {
                0.5f
            }
        }

        // 1. Bilinear sampling
        var vIndex = 0
        for (y in 0..gridHeight) {
            val v = y.toFloat() / gridHeight.toFloat()
            val fy = v * (depthH - 1).toFloat()
            val y0 = fy.toInt()
            val y1 = min(depthH - 1, y0 + 1)
            val ty = fy - y0.toFloat()

            for (x in 0..gridWidth) {
                val u = x.toFloat() / gridWidth.toFloat()
                val fx = u * (depthW - 1).toFloat()
                val x0 = fx.toInt()
                val x1 = min(depthW - 1, x0 + 1)
                val tx = fx - x0.toFloat()

                val c00 = getPixel(x0, y0)
                val c10 = getPixel(x1, y0)
                val c01 = getPixel(x0, y1)
                val c11 = getPixel(x1, y1)

                val top = c00 * (1.0f - tx) + c10 * tx
                val bottom = c01 * (1.0f - tx) + c11 * tx
                depthValues[vIndex] = top * (1.0f - ty) + bottom * ty
                vIndex++
            }
        }

        val stride = gridWidth + 1

        // 1a. Edge-preserving flattening (iterated bilateral filter). Depth inside an object becomes
        // nearly constant, so each object moves as one rigid piece instead of warping, while the
        // large depth steps at silhouettes are kept.
        val flatOut = FloatArray(depthValues.size)
        for (iter in 0 until FLATTEN_ITERATIONS) {
            for (y in 0..gridHeight) {
                for (x in 0..gridWidth) {
                    val c = depthValues[y * stride + x]
                    var sum = 0.0f
                    var wSum = 0.0f
                    for (dy in -FLATTEN_RADIUS..FLATTEN_RADIUS) {
                        val yy = (y + dy).coerceIn(0, gridHeight)
                        for (dx in -FLATTEN_RADIUS..FLATTEN_RADIUS) {
                            val v = depthValues[yy * stride + (x + dx).coerceIn(0, gridWidth)]
                            val dd = (dx * dx + dy * dy).toFloat()
                            val dv = v - c
                            val w = kotlin.math.exp(-dd / (2.0f * FLATTEN_SPATIAL * FLATTEN_SPATIAL) -
                                dv * dv / (2.0f * FLATTEN_RANGE * FLATTEN_RANGE))
                            sum += v * w
                            wSum += w
                        }
                    }
                    flatOut[y * stride + x] = sum / wSum
                }
            }
            System.arraycopy(flatOut, 0, depthValues, 0, depthValues.size)
        }

        // 1b. Dilate near depth outward (separable max filter). The disocclusion stretch then
        // happens on the background just outside the silhouette, so the foreground edge stays
        // crisp and only plain background texture is stretched instead of a foreground/background smear.
        val tmp = FloatArray(depthValues.size)
        for (y in 0..gridHeight) {
            for (x in 0..gridWidth) {
                var m = 0.0f
                for (k in -DILATE_RADIUS..DILATE_RADIUS) {
                    val v = depthValues[y * stride + (x + k).coerceIn(0, gridWidth)]
                    if (v > m) m = v
                }
                tmp[y * stride + x] = m
            }
        }
        for (y in 0..gridHeight) {
            for (x in 0..gridWidth) {
                var m = 0.0f
                for (k in -DILATE_RADIUS..DILATE_RADIUS) {
                    val v = tmp[(y + k).coerceIn(0, gridHeight) * stride + x]
                    if (v > m) m = v
                }
                depthValues[y * stride + x] = m
            }
        }

        // 2. Gentle 3x3 smoothing pass to prevent mesh tearing at sharp silhouette edges
        for (y in 0..gridHeight) {
            for (x in 0..gridWidth) {
                val idx = y * stride + x
                var sum = depthValues[idx] * 4.0f
                var weight = 4.0f

                if (x > 0) { sum += depthValues[idx - 1] * 2.0f; weight += 2.0f }
                if (x < gridWidth) { sum += depthValues[idx + 1] * 2.0f; weight += 2.0f }
                if (y > 0) { sum += depthValues[idx - stride] * 2.0f; weight += 2.0f }
                if (y < gridHeight) { sum += depthValues[idx + stride] * 2.0f; weight += 2.0f }

                smoothedDepth[idx] = sum / weight
            }
        }

        // 3. Elastic gradient limiting pass.  A depth step must be distributed over several
        // cells: otherwise a triangle straddling a foreground silhouette has one vertex moving
        // with the subject and the next moving with the background.  Interpolating the source
        // texture across that triangle is what produced the visible stretched/smeared seam while
        // swiping.  Keep the per-cell relief slope small enough that adjacent triangles remain
        // locally affine, then let the dilation above put this transition on background pixels.
        val maxGrad = MAX_DEPTH_GRADIENT
        for (pass in 0 until 4) {
            // Horizontal passes
            for (y in 0..gridHeight) {
                val rowOffset = y * stride
                for (x in 1..gridWidth) {
                    val curr = smoothedDepth[rowOffset + x]
                    val prev = smoothedDepth[rowOffset + x - 1]
                    val diff = curr - prev
                    if (diff > maxGrad) smoothedDepth[rowOffset + x] = prev + maxGrad
                    else if (diff < -maxGrad) smoothedDepth[rowOffset + x] = prev - maxGrad
                }
                for (x in gridWidth - 1 downTo 0) {
                    val curr = smoothedDepth[rowOffset + x]
                    val next = smoothedDepth[rowOffset + x + 1]
                    val diff = curr - next
                    if (diff > maxGrad) smoothedDepth[rowOffset + x] = next + maxGrad
                    else if (diff < -maxGrad) smoothedDepth[rowOffset + x] = next - maxGrad
                }
            }
            // Vertical passes
            for (x in 0..gridWidth) {
                for (y in 1..gridHeight) {
                    val curr = smoothedDepth[y * stride + x]
                    val prev = smoothedDepth[(y - 1) * stride + x]
                    val diff = curr - prev
                    if (diff > maxGrad) smoothedDepth[y * stride + x] = prev + maxGrad
                    else if (diff < -maxGrad) smoothedDepth[y * stride + x] = prev - maxGrad
                }
                for (y in gridHeight - 1 downTo 0) {
                    val curr = smoothedDepth[y * stride + x]
                    val next = smoothedDepth[(y + 1) * stride + x]
                    val diff = curr - next
                    if (diff > maxGrad) smoothedDepth[y * stride + x] = next + maxGrad
                    else if (diff < -maxGrad) smoothedDepth[y * stride + x] = next - maxGrad
                }
            }
        }

    }

    /** Sets a rigid depth plane. Used by layered rendering so an object cannot shear internally. */
    fun setUniformDepth(depth: Float) {
        depthValues.fill(depth.coerceIn(0.0f, 1.0f))
        smoothedDepth.fill(depth.coerceIn(0.0f, 1.0f))
    }

    /**
     * Updates vertex positions based on parallax displacement and dynamic jelly physics.
     * @param meshWidth Physical width of the quad in world space.
     * @param meshHeight Physical height of the quad in world space.
     * @param intensity Depth displacement magnitude.
     * @param depthCenter Focal plane depth (this plane stays motionless).
     * @param shiftZ Cumulative Z shift (zoom pattern).
     * @param zoom3D 3D depth perspective scaling.
     * @param isJellyMode Whether to apply dynamic jelly elasticity & wave ripple.
     * @param jellySpeed Current speed of the jelly spring-mass oscillator.
     * @param jellyWavePhase Current phase of the harmonic jelly surface wave.
     * @param parallaxX Horizontal viewer displacement, in normalized gesture space.
     * @param parallaxY Vertical viewer displacement, in normalized gesture space.
     */
    fun updateMesh(
        meshWidth: Float,
        meshHeight: Float,
        intensity: Float,
        depthCenter: Float,
        shiftZ: Float,
        zoom3D: Float,
        isJellyMode: Boolean = false,
        jellySpeed: Float = 0.0f,
        jellyWavePhase: Float = 0.0f,
        cameraDistance: Float = 2.4f,
        parallaxX: Float = 0.0f,
        parallaxY: Float = 0.0f
    ) {
        var arrIdx = 0
        val z3d = zoom3D.coerceIn(0.0f, 1.0f)
        val stride = gridWidth + 1

        for (y in 0..gridHeight) {
            val v = y.toFloat() / gridHeight.toFloat()
            val baseY = (0.5f - v) * meshHeight

            for (x in 0..gridWidth) {
                val u = x.toFloat() / gridWidth.toFloat()
                val baseX = (u - 0.5f) * meshWidth

                val idx = y * stride + x
                val rawDepth = smoothedDepth[idx].coerceIn(0.0f, 1.0f)
                val curvedDepth = DepthProjection.curvedDepth(rawDepth, intensity)

                // Pin the photo frame: flatten the relief to zero at the mesh border so the
                // outline stays a clean rectangle instead of warping with foreground objects.
                val edgeX = (kotlin.math.min(u, 1.0f - u) / EDGE_BAND).coerceIn(0.0f, 1.0f)
                val edgeY = (kotlin.math.min(v, 1.0f - v) / EDGE_BAND).coerceIn(0.0f, 1.0f)
                val edgeFade = (edgeX * edgeX * (3.0f - 2.0f * edgeX)) * (edgeY * edgeY * (3.0f - 2.0f * edgeY))

                // Gesture parallax is lateral only.  A single textured mesh cannot reveal the
                // background newly exposed behind a foreground silhouette, so giving it a large
                // Z relief merely magnifies that texture and reads as a stretched photo.  Depth
                // remains in the lateral weighting below; Z is reserved for explicit 3D zoom.
                val baseZ = 0.0f
                // Far depth is anchored.  As depth increases, the point receives more of the
                // viewer displacement, giving foreground objects their own motion while keeping
                // the image frame and distant background rigid.
                val nearWeight = curvedDepth.pow(1.2f)
                val parallaxWeight =
                    DepthProjection.depthScale(intensity) *
                        (curvedDepth - depthCenter * 0.35f) *
                        nearWeight *
                        edgeFade
                val finalX = baseX + parallaxX * parallaxWeight * PARALLAX_RANGE
                val finalY = baseY + parallaxY * parallaxWeight * PARALLAX_RANGE

                val deltaZ = DepthProjection.zoomOffset(curvedDepth, shiftZ, z3d)
                var finalZ = (baseZ + deltaZ).coerceIn(-cameraDistance * 0.5f, cameraDistance * 0.45f)

                // Dynamic Jelly elasticity in 3D (ripple wave on depth)
                if (isJellyMode && jellySpeed > 0.005f) {
                    val dx = u - 0.5f
                    val dy = v - 0.5f
                    val dist = kotlin.math.hypot(dx, dy)
                    val ripple = kotlin.math.sin(dist * 9.0f - jellyWavePhase) * (jellySpeed * 0.015f).coerceAtMost(0.05f)
                    finalZ += ripple
                }

                // Interleaved: X, Y, Z, U, V
                vertexArray[arrIdx++] = finalX
                vertexArray[arrIdx++] = finalY
                vertexArray[arrIdx++] = finalZ
                vertexArray[arrIdx++] = u
                vertexArray[arrIdx++] = v
            }
        }

        vertexBuffer.position(0)
        vertexBuffer.put(vertexArray)
        vertexBuffer.position(0)
    }

    private companion object {
        // Fraction of the mesh (from each edge) over which parallax fades out.
        const val EDGE_BAND = 0.12f

        // Grid cells by which near depth is grown over the background at silhouettes.  This
        // gives the depth transition room to happen behind the foreground contour.
        const val DILATE_RADIUS = 3

        // Limit discontinuities without turning the entire image into a broad, stretchy ramp.
        const val MAX_DEPTH_GRADIENT = 0.06f

        // World-space lateral travel at a full gesture.  Kept intentionally small: the depth
        // separation, not a whole-image translation or scale, must create the 3D impression.
        const val PARALLAX_RANGE = 0.12f

        // Bilateral flattening of the depth grid: window radius (cells), spatial sigma (cells),
        // depth-range sigma (0..1; smaller keeps more detail) and number of passes.
        const val FLATTEN_RADIUS = 3
        const val FLATTEN_SPATIAL = 2.0f
        const val FLATTEN_RANGE = 0.10f
        const val FLATTEN_ITERATIONS = 8
    }

    fun draw(posHandle: Int, texCoordHandle: Int) {
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            posHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(posHandle)

        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(
            texCoordHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        indexBuffer.position(0)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES, indicesCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer
        )

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }
}




