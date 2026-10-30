package com.example.spatial.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class SpatialGLSurfaceView(
    context: Context,
    val renderer: SpatialGLRenderer = SpatialGLRenderer(context)
) : GLSurfaceView(context) {

    var onTapListener: (() -> Unit)? = null

    private var previousX = 0.0f
    private var previousY = 0.0f
    private var downX = 0.0f
    private var downY = 0.0f
    private var downTime = 0L
    private var isDragging = false

    private var lastMoveTime = 0L
    private var velocityX = 0.0f
    private var velocityY = 0.0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(12.0f)

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            renderer.resetTouchState()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            renderer.touchZoom = (renderer.touchZoom + (scaleFactor - 1.0f)).coerceIn(-0.5f, 1.5f)
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            isDragging = false
            return true
        }

        val x = event.x
        val y = event.y
        val now = SystemClock.uptimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                android.util.Log.d("SpatialGLSurfaceView", "DOWN: ($x, $y)")
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = x
                downY = y
                previousX = x
                previousY = y
                downTime = now
                lastMoveTime = now
                velocityX = 0.0f
                velocityY = 0.0f
                isDragging = false

                renderer.onTouchDown(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (event.pointerCount == 1) {
                    val totalDist = hypot(x - downX, y - downY)
                    if (totalDist > touchSlop) {
                        isDragging = true
                    }

                    val dx = (x - previousX) / width.toFloat().coerceAtLeast(1.0f)
                    val dy = (y - previousY) / height.toFloat().coerceAtLeast(1.0f)

                    val dt = ((now - lastMoveTime) / 1000.0f).coerceIn(0.001f, 0.1f)
                    velocityX = dx / dt
                    velocityY = dy / dt

                    if (isDragging) {
                        renderer.onTouchMove(dx, dy, x, y)
                        android.util.Log.d("SpatialGLSurfaceView", "MOVE: ($x, $y) dx=$dx dy=$dy")
                    }

                    previousX = x
                    previousY = y
                    lastMoveTime = now
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = now - downTime
                val totalDist = hypot(x - downX, y - downY)
                android.util.Log.d("SpatialGLSurfaceView", "UP: isDragging=$isDragging totalDist=$totalDist")

                if (!isDragging && totalDist < touchSlop && duration < 350) {
                    // Clean tap gesture: toggle overlay
                    onTapListener?.invoke()
                    renderer.onTouchUp(0.0f, 0.0f)
                } else {
                    // Release flick
                    renderer.onTouchUp(velocityX, velocityY)
                }
                isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                android.util.Log.d("SpatialGLSurfaceView", "CANCEL")
                isDragging = false
                renderer.onTouchUp(0.0f, 0.0f)
            }
        }
        return true
    }
}

