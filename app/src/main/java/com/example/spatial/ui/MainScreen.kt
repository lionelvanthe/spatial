package com.example.spatial.ui


import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.spatial.R
import com.example.spatial.data.SpatialSceneConfig
import com.example.spatial.data.MotionLimits
import com.example.spatial.data.SpatialSceneItem
import com.example.spatial.gl.SpatialGLSurfaceView
import com.example.spatial.sensor.SpatialSensorTracker

enum class ScreenMode {
    GALLERY,
    SETUP,
    VIEWER
}

data class MotionPatternItem(
    val id: String,
    val name: String,
    val iconResId: Int
)

val ALL_MOTION_PATTERNS = listOf(
    MotionPatternItem("horizontal", "Horizontal", R.drawable.pattern_horizontal),
    MotionPatternItem("vertical", "Vertical", R.drawable.pattern_vertical),
    MotionPatternItem("circular_cw", "Circle CW", R.drawable.pattern_circular_cw),
    MotionPatternItem("circular_ccw", "Circle CCW", R.drawable.pattern_circular_ccw),
    MotionPatternItem("eight_horizontal", "Figure 8 H", R.drawable.pattern_eight_horizontal),
    MotionPatternItem("eight_vertical", "Figure 8 V", R.drawable.pattern_eight_vertical),
    MotionPatternItem("diagonal_top_bottom", "Diagonal Top", R.drawable.pattern_diagonal_top_bottom),
    MotionPatternItem("diagonal_bottom_top", "Diagonal Bottom", R.drawable.pattern_diagonal_bottom_top),
    MotionPatternItem("spiral_cw", "Spiral", R.drawable.pattern_spiral_cw),
    MotionPatternItem("half_circle_bottom", "Half Circle B", R.drawable.pattern_half_circle_bottom),
    MotionPatternItem("half_circle_top", "Half Circle T", R.drawable.pattern_half_circle_top),
    MotionPatternItem("horizontal_rapid", "Rapid H", R.drawable.pattern_horizontal_rapid),
    MotionPatternItem("vertical_rapid", "Rapid V", R.drawable.pattern_vertical_rapid),
    MotionPatternItem("square_cw", "Square CW", R.drawable.pattern_square_cw),
    MotionPatternItem("square_ccw", "Square CCW", R.drawable.pattern_square_ccw),
    MotionPatternItem("triangular_cw", "Triangle", R.drawable.pattern_triangular_cw),
    MotionPatternItem("zoom", "Zoom", R.drawable.ic_zoom_prop),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: SpatialViewModel) {
    val context = LocalContext.current
    val currentScene by viewModel.currentScene.collectAsState()
    val currentConfig by viewModel.currentConfig.collectAsState()
    val scenes by viewModel.scenes.collectAsState()
    val isSensorEnabled by viewModel.isSensorEnabled.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isLoadingScene by viewModel.isLoadingScene.collectAsState()
    val processProgress by viewModel.processProgress.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val isApplyingWallpaper by viewModel.isApplyingWallpaper.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.exportEvents.collect { event ->
            when (event) {
                is ExportEvent.Saved ->
                    Toast.makeText(context, "Saved: ${event.location}", Toast.LENGTH_LONG).show()
                is ExportEvent.Failed ->
                    Toast.makeText(context, "Export failed: ${event.message}", Toast.LENGTH_LONG).show()
                is ExportEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val title = if (event.mimeType == "image/gif") "Share 3D GIF" else "Share 3D video"
                    context.startActivity(Intent.createChooser(send, title))
                }
            }
        }
    }

    var screenMode by remember { mutableStateOf(ScreenMode.GALLERY) }
    var galleryTab by remember { mutableIntStateOf(0) } // 0: My photos & samples, 1: Wallpapers
    var showMenuDialog by remember { mutableStateOf(false) }
    var pendingExportShare by remember { mutableStateOf<Boolean?>(null) }

    val hasTiltSensor = remember(context) { SpatialSensorTracker.hasTiltSensor(context) }
    val defaultViewerTab = if (hasTiltSensor) 0 else 2

    // Bottom 4 Tabs: 0: Rotate (hidden if no tilt sensor), 1: Shake, 2: Animations, 3: ZoomPan
    var selectedBottomTab by remember { mutableIntStateOf(defaultViewerTab) }
    var toolbarHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var showControls by remember { mutableStateOf(false) }
    var isWallpaperFramingMode by remember { mutableStateOf(false) }
    var awaitingImportedScene by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceViewRef = remember { mutableStateOf<SpatialGLSurfaceView?>(null) }
    // Last bitmap the renderer has actually drawn; until it matches the scene, the old one is on screen.
    var drawnBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val sceneLoading = isLoadingScene ||
        currentScene?.fullImageBitmap.let { it != null && it !== drawnBitmap }

    // Handle back button when in viewer (matches rk0.x() in sample app)
    BackHandler(enabled = screenMode == ScreenMode.VIEWER) {
        if (isWallpaperFramingMode) {
            isWallpaperFramingMode = false
        } else if (showControls) {
            showControls = false
        } else {
            screenMode = ScreenMode.GALLERY
        }
    }

    // Android Photo Picker uses the platform picker where present and a safe document-picker
    // fallback on older devices; the app does not need broad media-library permission.
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            awaitingImportedScene = true
            screenMode = ScreenMode.SETUP
            viewModel.processCustomImage(it)
        }
    }

    LaunchedEffect(isProcessing, currentScene, awaitingImportedScene) {
        if (awaitingImportedScene && !isProcessing) {
            awaitingImportedScene = false
            if (currentScene?.isCustom == true) {
                selectedBottomTab = defaultViewerTab
                showControls = false
                screenMode = ScreenMode.VIEWER
            } else {
                screenMode = ScreenMode.GALLERY
            }
        }
    }

    // A shared image reaches the ViewModel directly from MainActivity.  Open its completed scene
    // just like an image picked inside the gallery.
    LaunchedEffect(currentScene, isProcessing) {
        if (!isProcessing && currentScene?.isCustom == true && !awaitingImportedScene &&
            (screenMode == ScreenMode.GALLERY || screenMode == ScreenMode.SETUP)
        ) {
            screenMode = ScreenMode.VIEWER
        }
    }

    LaunchedEffect(isProcessing) {
        if (isProcessing && screenMode == ScreenMode.GALLERY) screenMode = ScreenMode.SETUP
    }

    // Pause GL rendering and sensor tracking when backgrounded or when the viewer is hidden behind Gallery/Setup.
    val activeSurfaceView = surfaceViewRef.value
    DisposableEffect(lifecycleOwner, screenMode, activeSurfaceView) {
        val activeViewer = screenMode == ScreenMode.VIEWER
        val isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (activeViewer && isResumed) {
            activeSurfaceView?.onResume()
        } else {
            activeSurfaceView?.onPause()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (screenMode == ScreenMode.VIEWER) activeSurfaceView?.onResume()
                Lifecycle.Event.ON_PAUSE -> activeSurfaceView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Connect Sensor Tracker only while the Activity is resumed AND viewing a 3D scene.
    DisposableEffect(lifecycleOwner, isSensorEnabled, screenMode) {
        val tracker = SpatialSensorTracker(
            context = context,
            onLinearAcceleration = { ax, ay ->
                surfaceViewRef.value?.renderer?.onLinearAcceleration(ax, ay)
            }
        ) { tiltX, tiltY ->
            surfaceViewRef.value?.renderer?.let { r ->
                r.gyroX = tiltX
                r.gyroY = tiltY
            }
        }
        surfaceViewRef.value?.renderer?.let { r ->
            r.gyroX = 0f
            r.gyroY = 0f
        }
        val shouldTrack = isSensorEnabled && screenMode == ScreenMode.VIEWER
        if (shouldTrack && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            tracker.start()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (shouldTrack) tracker.start()
                Lifecycle.Event.ON_PAUSE -> tracker.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.stop()
        }
    }

    // Sync Scene & Config with GL Renderer
    LaunchedEffect(currentScene) {
        val scene = currentScene ?: return@LaunchedEffect
        surfaceViewRef.value?.renderer?.let { r ->
            r.setSceneData(scene.fullImageBitmap, scene.depthData, currentConfig, scene.depthWidth, scene.depthHeight)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1018))
    ) {
        // Fit the actual GL surface to the photo inside the space between the bars,
        // or expand to full-screen FILL preview when adjusting Live Wallpaper framing (Photos 3D sk0.z).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = with(density) { if (screenMode == ScreenMode.VIEWER && !isWallpaperFramingMode) toolbarHeightPx.toDp() else 0.dp },
                    bottom = with(density) { if (screenMode == ScreenMode.VIEWER && !isWallpaperFramingMode) bottomBarHeightPx.toDp() else 0.dp }
                ),
            contentAlignment = Alignment.Center
        ) {
            val image = currentScene?.fullImageBitmap
            val viewportModifier = if (screenMode == ScreenMode.VIEWER && !isWallpaperFramingMode && image != null) {
                Modifier.aspectRatio(image.width.coerceAtLeast(1).toFloat() / image.height.coerceAtLeast(1).toFloat())
            } else {
                Modifier.fillMaxSize()
            }
            AndroidView(
                modifier = viewportModifier,
                factory = { ctx ->
                    SpatialGLSurfaceView(ctx).also { view ->
                        surfaceViewRef.value = view
                        view.renderer.onSceneDrawn = { bitmap -> view.post { drawnBitmap = bitmap } }
                        view.renderer.activeTabMode = selectedBottomTab
                        view.renderer.isWallpaperFillMode = isWallpaperFramingMode
                        view.renderer.isWallpaperFramingTouchMode = isWallpaperFramingMode
                        view.renderer.onWallpaperTargetChanged = { x, y -> view.post { viewModel.updateWallTarget(x, y) } }
                        // Framing is scene configuration, shared with wallpaper and video export.
                        view.onTapListener = {
                            if (screenMode == ScreenMode.VIEWER && !isWallpaperFramingMode) {
                                showControls = !showControls
                            }
                        }
                        currentScene?.let { scene ->
                            view.renderer.setSceneData(scene.fullImageBitmap, scene.depthData, currentConfig, scene.depthWidth, scene.depthHeight)
                        }
                    }
                },
                update = { view ->
                    surfaceViewRef.value = view
                    view.renderer.activeTabMode = selectedBottomTab
                    view.renderer.isWallpaperFillMode = isWallpaperFramingMode
                    view.renderer.isWallpaperFramingTouchMode = isWallpaperFramingMode
                    view.renderer.onWallpaperTargetChanged = { x, y -> view.post { viewModel.updateWallTarget(x, y) } }
                    view.onTapListener = {
                        if (screenMode == ScreenMode.VIEWER && !isWallpaperFramingMode) {
                            showControls = !showControls
                        }
                    }
                    val scene = currentScene
                    if (scene != null && (view.renderer.currentBitmap == null || view.renderer.currentBitmap != scene.fullImageBitmap)) {
                        view.renderer.setSceneData(scene.fullImageBitmap, scene.depthData, currentConfig, scene.depthWidth, scene.depthHeight)
                    } else {
                        view.renderer.sceneConfig = currentConfig
                    }
                },
                onRelease = { view ->
                    view.onPause()
                    view.renderer.releaseNative()
                    if (surfaceViewRef.value === view) {
                        surfaceViewRef.value = null
                    }
                }
            )
            if (screenMode == ScreenMode.VIEWER && sceneLoading) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xFF0F1018))
                        .semantics { contentDescription = "Loading scene" },
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF00A0E9))
                }
            }
        }

        // SCREEN 1: GALLERY SCREEN (activity_main.xml clone)
        AnimatedVisibility(
            visible = screenMode == ScreenMode.GALLERY,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            GalleryScreen(
                scenes = when (galleryTab) {
                    0 -> scenes.filter { it.isCustom || it.folderPath.startsWith("samples/") }
                    else -> scenes.filterNot { it.isCustom }
                },
                selectedSceneId = currentScene?.id ?: "",
                currentTab = galleryTab,
                onTabSelect = { galleryTab = it },
                onMenuClick = { showMenuDialog = true },
                onSelectScene = { scene ->
                    viewModel.selectScene(scene)
                    showControls = false
                    selectedBottomTab = defaultViewerTab
                    screenMode = ScreenMode.VIEWER
                },
                onAddNewPhoto = {
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }
            )
        }

        if (showMenuDialog) {
            AlertDialog(
                onDismissRequest = { showMenuDialog = false },
                title = { Text("Spatial 3D") },
                text = {
                    Text(
                        "Tạo ảnh 3D/parallax từ ảnh thường bằng AI ước lượng chiều sâu trên thiết bị (model 512/256 thích ứng).\n\n" +
                            "• Nghiêng máy hoặc kéo tay để xem hiệu ứng 3D.\n" +
                            "• Chạm đúp để hồi tâm.\n" +
                            "• Dùng tab Animations để xuất video MP4 hoặc ảnh động GIF."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showMenuDialog = false }) {
                        Text("Đóng")
                    }
                }
            )
        }

        if (pendingExportShare != null) {
            val isShareAction = pendingExportShare == true
            AlertDialog(
                onDismissRequest = { pendingExportShare = null },
                title = { Text(if (isShareAction) "Chia sẻ hiệu ứng 3D" else "Lưu hiệu ứng 3D") },
                text = { Text("Chọn định dạng xuất (không watermark):") },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            pendingExportShare = null
                            viewModel.exportGif(share = isShareAction)
                        }) {
                            Text("Ảnh động GIF")
                        }
                        Button(onClick = {
                            pendingExportShare = null
                            viewModel.exportVideo(share = isShareAction)
                        }) {
                            Text("Video MP4")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingExportShare = null }) {
                        Text("Hủy")
                    }
                }
            )
        }

        AnimatedVisibility(visible = screenMode == ScreenMode.SETUP, enter = fadeIn(), exit = fadeOut()) {
            ImageSetupScreen(progress = processProgress, isProcessing = isProcessing)
        }

        // SCREEN 2: 3D VIEWER & STUDIO (activity_image_view.xml clone)
        AnimatedVisibility(
            visible = screenMode == ScreenMode.VIEWER,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            ViewerScreen(
                scene = currentScene,
                config = currentConfig,
                isSensorEnabled = isSensorEnabled,
                hasTiltSensor = hasTiltSensor,
                selectedBottomTab = selectedBottomTab,
                showControls = showControls,
                isWallpaperFramingMode = isWallpaperFramingMode,
                isApplyingWallpaper = isApplyingWallpaper,
                onToolbarHeightChanged = { toolbarHeightPx = it },
                onBottomBarHeightChanged = { bottomBarHeightPx = it },
                onSelectBottomTab = { index ->
                    val previousTab = selectedBottomTab
                    selectedBottomTab = index
                    surfaceViewRef.value?.renderer?.let { r ->
                        r.activeTabMode = index
                        r.resetTouchState(
                            preserveZoom = com.example.spatial.gl.ViewerFraming.preserveTouchZoom(previousTab, index)
                        )
                    }
                },
                onToggleControls = { showControls = !showControls },
                onBackToGallery = {
                    if (isWallpaperFramingMode) {
                        isWallpaperFramingMode = false
                    } else {
                        screenMode = ScreenMode.GALLERY
                    }
                },
                onToggleSensor = { viewModel.toggleSensor() },
                onSelectPattern = { viewModel.updatePattern(it) },
                onUpdateSpeed = { viewModel.updateSpeed(it) },
                onUpdateIntensity = { viewModel.updateIntensity(it) },
                onUpdateDepthCenter = { viewModel.updateDepthCenter(it) },
                onUpdateElasticity = { viewModel.updateElasticity(it) },
                onUpdateZoom = { zoomVal ->
                    viewModel.updateZoom(zoomVal)
                    surfaceViewRef.value?.renderer?.touchZoom = 0.0f
                },
                onUpdateZoom3D = { viewModel.updateZoom3D(it) },
                onUpdateInverted = { viewModel.updateInverted(it) },
                onDeleteOrReset = {
                    val scene = currentScene
                    if (scene != null && scene.isCustom) {
                        viewModel.deleteCustomScene(scene.id)
                        screenMode = ScreenMode.GALLERY
                        Toast.makeText(context, "Đã xóa ảnh", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.resetToDefault()
                        surfaceViewRef.value?.renderer?.resetTouchState()
                        Toast.makeText(context, "Đã đặt lại thông số mặc định", Toast.LENGTH_SHORT).show()
                    }
                },
                onSetWallpaper = {
                    showControls = false
                    isWallpaperFramingMode = true
                },
                onConfirmWallpaper = {
                    viewModel.launchSetLiveWallpaperIntent(activeTabMode = selectedBottomTab) {
                        isWallpaperFramingMode = false
                    }
                },
                onSave = { pendingExportShare = false },
                onShare = { pendingExportShare = true }
            )
        }

        if (isExporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(32.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1D2C))
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Rendering 3D video...",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { exportProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF00E5FF),
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.cancelExport() }) { Text("Cancel") }
                    }
                }
            }
        }

        // Depth Estimation / Processing Overlay
        if (isProcessing && screenMode != ScreenMode.SETUP) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(32.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1D2C))
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF00E5FF),
                            modifier = Modifier.size(54.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Estimating 3D Depth Map...",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Processing neural depth layers with AI",
                            color = Color(0xFF00E5FF),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { processProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF00E5FF),
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageSetupScreen(progress: Float, isProcessing: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F1018)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2B))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color(0xFF7CDBFF))
                Spacer(Modifier.height(18.dp))
                Text("Preparing spatial scene", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isProcessing) "Estimating depth on this device…" else "Finishing setup…",
                    color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF7CDBFF), trackColor = Color.White.copy(alpha = 0.12f)
                )
            }
        }
    }
}

/** Gallery entry point for a user's photos and bundled sample scenes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    scenes: List<SpatialSceneItem>,
    selectedSceneId: String,
    currentTab: Int,
    onTabSelect: (Int) -> Unit,
    onMenuClick: () -> Unit,
    onSelectScene: (SpatialSceneItem) -> Unit,
    onAddNewPhoto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1018))
    ) {
        // MaterialToolbar Header
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Spatial Scenes",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF))
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = Color.White
                    )
                }
            },
            actions = {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFB300).copy(alpha = 0.2f),
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PRO",
                            color = Color(0xFFFFB300),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF141622))
        )

        // Main Card Container with Gallery Grid
        Card(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF181A28)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 2-Column Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1.0f)
                ) {
                    // Item 0: Add New Photo Card (gallery_new_item.xml)
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.0f)
                                .clickable { onAddNewPhoto() },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF212538)),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                Brush.linearGradient(
                                    listOf(Color(0xFF00E5FF).copy(alpha = 0.6f), Color(0xFF3D82FF).copy(alpha = 0.3f))
                                )
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_add_photo),
                                        contentDescription = "Add Photo",
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Select Image",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Gallery / Camera",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // 3D Scene Items (gallery_item.xml)
                    items(scenes) { scene ->
                        val isSelected = scene.id == selectedSceneId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.0f)
                                .clickable { onSelectScene(scene) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF222434)),
                            border = if (isSelected) {
                                androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF00E5FF))
                            } else null
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (scene.thumbnailBitmap != null) {
                                    Image(
                                        bitmap = scene.thumbnailBitmap!!.asImageBitmap(),
                                        contentDescription = scene.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                // 3D Badge on Top Right
                                Surface(
                                    shape = RoundedCornerShape(bottomStart = 10.dp),
                                    color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                                    modifier = Modifier.align(Alignment.TopEnd)
                                ) {
                                    Text(
                                        text = "3D",
                                        color = Color(0xFF0A0F1D),
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }

                                // Bottom Title Overlay
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                            )
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = scene.title,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(6.dp)
                                            .size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Tab Navigation Bar inside Gallery Card (EvenlySpacedLayout clone)
                Surface(
                    color = Color(0xFF141624),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Tab 0: Gallery
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onTabSelect(0) }
                                .padding(horizontal = 32.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_tab_gallery),
                                contentDescription = "Gallery",
                                tint = if (currentTab == 0) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Gallery",
                                color = if (currentTab == 0) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }

                        // Tab 1: Wallpapers
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onTabSelect(1) }
                                .padding(horizontal = 32.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_tab_wallpapers),
                                contentDescription = "Wallpapers",
                                tint = if (currentTab == 1) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Wallpapers",
                                color = if (currentTab == 1) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Authentic custom Slider matching the exact Photos 3D sample app (activity_image_view.xml):
 * - Active track: #00A0E9 (@color/blue_normal)
 * - Inactive track: either solid #00A0E9 (for Trung tâm & Zoom) or light-blue #384A58 (for Cường độ, Tốc độ)
 * - Vertical bar thumb (|) in #00A0E9 (or dimmed when disabled)
 * - Supports enabled/disabled state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSpatialSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    solidTrack: Boolean = false,
    thinTrack: Boolean = false
) {
    val activeColor = if (enabled) Color(0xFF00A0E9) else Color(0xFF00A0E9).copy(alpha = 0.38f)
    val inactiveColor = when {
        !enabled -> Color(0xFF384A58).copy(alpha = 0.38f)
        solidTrack -> Color(0xFF00A0E9)
        else -> Color(0xFF384A58)
    }
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier.height(28.dp),
        thumb = {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(if (thinTrack) 16.dp else 20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(activeColor)
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                enabled = enabled,
                modifier = Modifier.height(if (thinTrack) 3.5.dp else 5.dp),
                colors = SliderDefaults.colors(
                    activeTrackColor = activeColor,
                    inactiveTrackColor = inactiveColor,
                    disabledActiveTrackColor = activeColor,
                    disabledInactiveTrackColor = inactiveColor
                ),
                drawStopIndicator = null
            )
        }
    )
}

/**
 * 3D Viewer & Studio Screen exactly replicating activity_image_view.xml from the Photos 3D sample app
 */
@Composable
fun ViewerScreen(
    scene: SpatialSceneItem?,
    config: SpatialSceneConfig,
    isSensorEnabled: Boolean,
    hasTiltSensor: Boolean = true,
    selectedBottomTab: Int,
    showControls: Boolean,
    isWallpaperFramingMode: Boolean = false,
    isApplyingWallpaper: Boolean = false,
    onSelectBottomTab: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onBackToGallery: () -> Unit,
    onToggleSensor: () -> Unit,
    onSelectPattern: (String) -> Unit,
    onUpdateSpeed: (Float) -> Unit,
    onUpdateIntensity: (Float) -> Unit,
    onUpdateDepthCenter: (Float) -> Unit,
    @Suppress("UNUSED_PARAMETER") onUpdateElasticity: (Float) -> Unit,
    onUpdateZoom: (Float) -> Unit,
    onUpdateZoom3D: (Float) -> Unit,
    onUpdateInverted: (Boolean) -> Unit,
    onDeleteOrReset: () -> Unit,
    onSetWallpaper: () -> Unit,
    onConfirmWallpaper: () -> Unit = onSetWallpaper,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onToolbarHeightChanged: (Int) -> Unit,
    onBottomBarHeightChanged: (Int) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // TOP OVERLAY: Header Bar + Controls Sliders (view_image_controls)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            // TOP BAR (MaterialToolbar clone)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("viewer_toolbar")
                    .onSizeChanged { onToolbarHeightChanged(it.height) }
                    .background(Color.Black.copy(alpha = 0.85f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back arrow <-
                IconButton(onClick = onBackToGallery) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                if (isWallpaperFramingMode) {
                    Column(modifier = Modifier.padding(start = 6.dp)) {
                        Text(
                            text = "Căn chỉnh hình nền",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Kéo ảnh để chọn vùng hiển thị",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                if (!isWallpaperFramingMode) {
                    // Actions on the right
                    if (selectedBottomTab != 3) {
                        // Wallpaper
                        IconButton(onClick = onSetWallpaper) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_wallpaper_phone),
                                contentDescription = "Set Wallpaper",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Download / Save (Shown ONLY in Tab 2 Animations)
                        if (selectedBottomTab == 2) {
                            IconButton(onClick = onSave) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_save_real),
                                    contentDescription = "Save",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Share (Shown ONLY in Tab 2 Animations)
                            IconButton(onClick = onShare) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_share_real),
                                    contentDescription = "Share",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Tune / Properties Sliders Toggle Icon
                        IconButton(onClick = onToggleControls) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_properties),
                                contentDescription = "Tune",
                                tint = if (showControls) Color(0xFF00A0E9) else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Delete custom photo or reset parameters for preset scene
                    IconButton(onClick = onDeleteOrReset) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete_real),
                            contentDescription = if (scene?.isCustom == true) "Delete" else "Reset",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // TOP SLIDERS OVERLAY PANEL (view_image_controls clone)
            AnimatedVisibility(
                visible = showControls && !isWallpaperFramingMode,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it }
            ) {
                Surface(
                    color = Color(0xD916181B),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        // ROW 1: CƯỜNG ĐỘ (Intensity: 0.0 .. MotionLimits.MAX_INTENSITY) + [CHECKBOX ĐẢO (Invert) in Tab 0]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_intensity_bolt),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Cường độ:",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            CustomSpatialSlider(
                                value = config.intensity,
                                onValueChange = onUpdateIntensity,
                                valueRange = 0.0f..MotionLimits.MAX_INTENSITY,
                                modifier = Modifier.weight(5f)
                            )

                            // Square Checkbox with "Đảo" text (Shown ONLY in Tab 0 - Rotate mode, weight 1 of 6)
                            if (selectedBottomTab == 0) {
                                Spacer(modifier = Modifier.width(10.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onUpdateInverted(!config.rInverted) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .border(
                                                width = 1.5.dp,
                                                color = if (config.rInverted) Color(0xFF00A0E9) else Color.White.copy(alpha = 0.7f),
                                                shape = RoundedCornerShape(2.dp)
                                            )
                                            .background(if (config.rInverted) Color(0xFF00A0E9) else Color.Transparent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (config.rInverted) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.Black,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Đảo",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                }
                            }
                        }

                        // ROW 2: TRUNG TÂM (Depth Center: 0.0 .. 1.0, solid blue track)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_depth_squares),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Trung tâm:",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            CustomSpatialSlider(
                                value = config.depthCenter,
                                onValueChange = onUpdateDepthCenter,
                                valueRange = 0.0f..1.0f,
                                solidTrack = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Zoom & Zoom 3D: visible on Tab 0 (Rotate) and Tab 2 (Animations), hidden on Tab 1 (Shake)
                        // matching Photos 3D 1.1.6 ImageViewActivity.O().
                        if (selectedBottomTab == 0 || selectedBottomTab == 2) {
                            HorizontalDivider(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                thickness = 0.8.dp,
                                color = Color.White.copy(alpha = 0.15f)
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                // Zoom (weight 3)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_zoom_corners),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Zoom:",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    CustomSpatialSlider(
                                        value = config.zoom,
                                        onValueChange = onUpdateZoom,
                                        valueRange = MotionLimits.MIN_ZOOM..MotionLimits.MAX_ZOOM,
                                        solidTrack = true,
                                        modifier = Modifier.fillMaxWidth().testTag("viewer_zoom")
                                    )
                                }

                                // Zoom 3D (depth-weighted zoom blend: 0.0 .. 1.0, Photos 3D 1.1.6: disabled when |zoom| < 0.1)
                                val zoom3DEnabled = kotlin.math.abs(config.zoom) >= 0.1f
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_zoom_prop),
                                        contentDescription = null,
                                        tint = if (zoom3DEnabled) Color.White else Color.White.copy(alpha = 0.45f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Zoom 3D:",
                                        color = if (zoom3DEnabled) Color.White else Color.White.copy(alpha = 0.45f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    CustomSpatialSlider(
                                        value = config.zoom3D,
                                        onValueChange = onUpdateZoom3D,
                                        valueRange = 0.0f..MotionLimits.MAX_ZOOM_3D,
                                        enabled = zoom3DEnabled,
                                        thinTrack = true,
                                        modifier = Modifier.fillMaxWidth().testTag("viewer_zoom_3d")
                                    )
                                }

                            }
                        }

                        // ROW 4: TỐC ĐỘ (Speed: 0.0 .. 1.0) - Visible ONLY when Animations Tab (Tab 2) is active
                        if (selectedBottomTab == 2) {
                            HorizontalDivider(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                thickness = 0.8.dp,
                                color = Color.White.copy(alpha = 0.15f)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_speed_meter),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Tốc độ:",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                CustomSpatialSlider(
                                    value = config.speed,
                                    onValueChange = onUpdateSpeed,
                                    valueRange = 0.0f..1.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }

        // WALLPAPER FRAMING CONFIRM FAB (Photos 3D activity_image_view_set_wallpaper_floating_button)
        AnimatedVisibility(
            visible = isWallpaperFramingMode,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { if (!isApplyingWallpaper) onConfirmWallpaper() },
                containerColor = Color(0xFF00A0E9),
                contentColor = Color.White,
                modifier = Modifier.testTag("viewer_confirm_wallpaper_fab")
            ) {
                if (isApplyingWallpaper) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Đang chuẩn bị...", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_wallpaper_phone),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Đặt hình nền", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // BOTTOM CONTAINER: Motion Patterns Bar + Bottom Navigation Bar
        if (!isWallpaperFramingMode) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            // PATTERNS ROW (PatternsRecyclerView clone) - directly above bottom bar
            AnimatedVisibility(
                visible = selectedBottomTab == 2,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                Surface(
                    color = Color(0xDD15171C),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(ALL_MOTION_PATTERNS) { item ->
                            val isSelected = config.patternId.equals(item.id, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isSelected) Color(0xFF00A0E9).copy(alpha = 0.25f)
                                        else Color.Transparent
                                    )
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.dp,
                                        color = if (isSelected) Color(0xFF00A0E9) else Color.Transparent,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onSelectPattern(item.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = item.iconResId),
                                    contentDescription = item.name,
                                    modifier = Modifier.size(26.dp),
                                    colorFilter = ColorFilter.tint(
                                        if (isSelected) Color(0xFF00A0E9) else Color.White.copy(alpha = 0.85f)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // BOTTOM BAR: EvenlySpacedLayout clone with 4 tabs
            Surface(
                color = Color(0xFF15171C),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("viewer_bottom_bar")
                    .onSizeChanged { onBottomBarHeightChanged(it.height) }
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabs = buildList {
                        if (hasTiltSensor) {
                            add(Triple(0, R.drawable.ic_rotate_tab, "Rotate"))
                        }
                        add(Triple(1, R.drawable.ic_shake_tab, "Shake"))
                        add(Triple(2, R.drawable.ic_animations_tab, "Animations"))
                        add(Triple(3, R.drawable.ic_zoom_pan_tab, "ZoomPan"))
                    }

                    tabs.forEach { (tabIndex, iconRes, label) ->
                        val isSelected = selectedBottomTab == tabIndex
                        Box(
                            modifier = Modifier
                                .width(74.dp)
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF45637A) else Color.Transparent)
                                .clickable {
                                    onSelectBottomTab(tabIndex)
                                    when (tabIndex) {
                                        0 -> {
                                            if (!isSensorEnabled) onToggleSensor()
                                            onSelectPattern("none")
                                        }
                                        1 -> {
                                            if (!isSensorEnabled) onToggleSensor()
                                            onSelectPattern("none")
                                        }
                                        2 -> {
                                            if (config.patternId.isEmpty() || config.patternId.equals("none", ignoreCase = true)) {
                                                onSelectPattern("horizontal")
                                            }
                                        }
                                        3 -> {
                                            if (showControls) onToggleControls()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = label,
                                tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

