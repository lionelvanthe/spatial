package com.example.spatial.ui

import androidx.compose.material3.TextButton

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
    MotionPatternItem("triangular_cw", "Triangle", R.drawable.pattern_triangular_cw),
    MotionPatternItem("zoom", "Zoom", R.drawable.ic_zoom_prop),
    MotionPatternItem("none", "Sensor Only", R.drawable.ic_speed)
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
    val processProgress by viewModel.processProgress.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.exportEvents.collect { event ->
            when (event) {
                is ExportEvent.Saved ->
                    Toast.makeText(context, "Saved video: ${event.location}", Toast.LENGTH_LONG).show()
                is ExportEvent.Failed ->
                    Toast.makeText(context, "Export failed: ${event.message}", Toast.LENGTH_LONG).show()
                is ExportEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share 3D video"))
                }
            }
        }
    }

    var screenMode by remember { mutableStateOf(ScreenMode.GALLERY) }
    var galleryTab by remember { mutableIntStateOf(0) } // 0: My photos, 1: Sample scenes
    var isFullscreen by remember { mutableStateOf(false) }

    // Sub-panels in Viewer mode: 0 = none, 1 = Patterns/Speed, 2 = Fine-Tune
    var activeViewerPanel by remember { mutableIntStateOf(0) }

    // Bottom 4 Tabs: 0: Rotate (Rotational Tilt), 1: Shake (Jelly Physics), 2: Animations, 3: ZoomPan
    var selectedBottomTab by remember { mutableIntStateOf(0) }
    var toolbarHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var showControls by remember { mutableStateOf(false) }
    var awaitingImportedScene by remember { mutableStateOf(false) }

    val surfaceViewRef = remember { mutableStateOf<SpatialGLSurfaceView?>(null) }

    // Handle back button when in viewer (matches rk0.x() in sample app)
    BackHandler(enabled = screenMode == ScreenMode.VIEWER) {
        if (isFullscreen) {
            isFullscreen = false
        } else if (showControls) {
            showControls = false
        } else if (activeViewerPanel != 0) {
            activeViewerPanel = 0
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
                selectedBottomTab = 0
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

    // Connect Sensor Tracker
    DisposableEffect(isSensorEnabled) {
        val tracker = SpatialSensorTracker(context) { tiltX, tiltY ->
            surfaceViewRef.value?.renderer?.let { r ->
                r.gyroX = tiltX
                r.gyroY = tiltY
            }
        }
        surfaceViewRef.value?.renderer?.let { r ->
            r.gyroX = 0f
            r.gyroY = 0f
        }
        if (isSensorEnabled) tracker.start()

        onDispose {
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
        // Fit the actual GL surface to the photo inside the space between the bars.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = with(density) { if (screenMode == ScreenMode.VIEWER) toolbarHeightPx.toDp() else 0.dp },
                    bottom = with(density) { if (screenMode == ScreenMode.VIEWER) bottomBarHeightPx.toDp() else 0.dp }
                ),
            contentAlignment = Alignment.Center
        ) {
            val image = currentScene?.fullImageBitmap
            val viewportModifier = if (screenMode == ScreenMode.VIEWER && image != null) {
                Modifier.aspectRatio(image.width.toFloat() / image.height)
            } else {
                Modifier.fillMaxSize()
            }
            AndroidView(
                modifier = viewportModifier,
                factory = { ctx ->
                    SpatialGLSurfaceView(ctx).also { view ->
                        surfaceViewRef.value = view
                        view.renderer.activeTabMode = selectedBottomTab
                        // Framing is scene configuration, shared with wallpaper and video export.
                        view.onTapListener = {
                            if (screenMode == ScreenMode.VIEWER) {
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
                    view.onTapListener = {
                        if (screenMode == ScreenMode.VIEWER) {
                            showControls = !showControls
                        }
                    }
                    val scene = currentScene
                    if (scene != null && (view.renderer.currentBitmap == null || view.renderer.currentBitmap != scene.fullImageBitmap)) {
                        view.renderer.setSceneData(scene.fullImageBitmap, scene.depthData, currentConfig, scene.depthWidth, scene.depthHeight)
                    } else {
                        view.renderer.sceneConfig = currentConfig
                    }
                }
            )
        }

        // SCREEN 1: GALLERY SCREEN (activity_main.xml clone)
        AnimatedVisibility(
            visible = screenMode == ScreenMode.GALLERY,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            GalleryScreen(
                scenes = when (galleryTab) {
                    0 -> scenes.filter { it.isCustom }
                    else -> scenes.filterNot { it.isCustom }
                },
                selectedSceneId = currentScene?.id ?: "",
                currentTab = galleryTab,
                onTabSelect = { galleryTab = it },
                onSelectScene = { scene ->
                    viewModel.selectScene(scene)
                    showControls = false
                    selectedBottomTab = 0
                    screenMode = ScreenMode.VIEWER
                },
                onAddNewPhoto = {
//                    imagePickerLauncher.launch(
//                        ActivityResultContracts.PickVisualMediaRequest(
//                            ActivityResultContracts.PickVisualMedia.ImageOnly
//                        )
//                    )
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
                isFullscreen = isFullscreen,
                activePanel = activeViewerPanel,
                isSensorEnabled = isSensorEnabled,
                selectedBottomTab = selectedBottomTab,
                showControls = showControls,
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
                onBackToGallery = { screenMode = ScreenMode.GALLERY },
                onToggleFullscreen = { isFullscreen = !isFullscreen },
                onToggleSensor = { viewModel.toggleSensor() },
                onTogglePanel = { panel ->
                    activeViewerPanel = if (activeViewerPanel == panel) 0 else panel
                },
                onQuickInvert = {
                    viewModel.updateInverted(!currentConfig.rInverted)
                },
                onSelectPattern = { viewModel.updatePattern(it) },
                onUpdateSpeed = { viewModel.updateSpeed(it) },
                onUpdateIntensity = { viewModel.updateIntensity(it) },
                onUpdateDepthCenter = { viewModel.updateDepthCenter(it) },
                onUpdateZoom = { zoomVal ->
                    viewModel.updateZoom(zoomVal)
                    surfaceViewRef.value?.renderer?.touchZoom = 0.0f
                },
                onUpdateInverted = { viewModel.updateInverted(it) },
                onUpdateConfig = { viewModel.updateConfig(it) },
                onReset = {
                    viewModel.resetToDefault()
                    surfaceViewRef.value?.renderer?.resetTouchState()
                },
                onSetWallpaper = { viewModel.launchSetLiveWallpaperIntent() },
                onSave = { viewModel.exportVideo(share = false) },
                onShare = { viewModel.exportVideo(share = true) }
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
                IconButton(onClick = { /* Menu */ }) {
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

@Composable
private fun LookSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(78.dp)
        )
        CustomSpatialSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            thinTrack = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 3D Viewer & Studio Screen exactly replicating activity_image_view.xml from the Photos 3D sample app
 */
@Composable
fun ViewerScreen(
    scene: SpatialSceneItem?,
    config: SpatialSceneConfig,
    isFullscreen: Boolean,
    activePanel: Int,
    isSensorEnabled: Boolean,
    selectedBottomTab: Int,
    showControls: Boolean,
    onSelectBottomTab: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onBackToGallery: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleSensor: () -> Unit,
    onTogglePanel: (Int) -> Unit,
    onQuickInvert: () -> Unit,
    onSelectPattern: (String) -> Unit,
    onUpdateSpeed: (Float) -> Unit,
    onUpdateIntensity: (Float) -> Unit,
    onUpdateDepthCenter: (Float) -> Unit,
    onUpdateZoom: (Float) -> Unit,
    onUpdateInverted: (Boolean) -> Unit,
    onUpdateConfig: ((SpatialSceneConfig) -> SpatialSceneConfig) -> Unit,
    onReset: () -> Unit,
    onSetWallpaper: () -> Unit,
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

                Spacer(modifier = Modifier.weight(1f))

                // Actions on the right (shown except in clean full-screen mode)
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

                // Delete / Trash Icon
                IconButton(onClick = onReset) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete_real),
                        contentDescription = "Delete",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // TOP SLIDERS OVERLAY PANEL (view_image_controls clone)
            AnimatedVisibility(
                visible = showControls && selectedBottomTab != 3,
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

                        // Zoom uses the same limits as the saved scene configuration.
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

                        Text("Background", color = Color.White, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth()) {
                            listOf("Dark" to 0xFF0F0F17.toInt(), "Black" to 0xFF000000.toInt(),
                                "White" to 0xFFFFFFFF.toInt(), "Blue" to 0xFF18334F.toInt()).forEach { (name, color) ->
                                TextButton(onClick = { onUpdateConfig { it.copy(backgroundColor = color) } }, modifier = Modifier.weight(1f)) {
                                    Text(name, fontSize = 10.sp, color = if (config.backgroundColor == color) Color(0xFF76CFFF) else Color.White)
                                }
                            }
                        }

                        // LOOK: depth blur, vignette and colour grade (not part of the original app)
                        if (selectedBottomTab == 0 || selectedBottomTab == 2) {
                            HorizontalDivider(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                thickness = 0.8.dp,
                                color = Color.White.copy(alpha = 0.15f)
                            )
                            LookSlider("Isometric", config.isometric, 0f..1f) { v -> onUpdateConfig { it.copy(isometric = v) } }
                            LookSlider("Dolly", config.dolly, -1f..1f) { v -> onUpdateConfig { it.copy(dolly = v) } }
                            LookSlider("Blur", config.blur, 0f..1f) { v -> onUpdateConfig { it.copy(blur = v) } }
                            LookSlider("Vignette", config.vignette, 0f..1f) { v -> onUpdateConfig { it.copy(vignette = v) } }
                            LookSlider("Saturation", config.saturation, 0f..2f) { v -> onUpdateConfig { it.copy(saturation = v) } }
                            LookSlider("Contrast", config.contrast, 0f..2f) { v -> onUpdateConfig { it.copy(contrast = v) } }
                            LookSlider("Brightness", config.brightness, 0f..2f) { v -> onUpdateConfig { it.copy(brightness = v) } }
                            LookSlider("Sepia", config.sepia, 0f..1f) { v -> onUpdateConfig { it.copy(sepia = v) } }
                        }
                    }
                }
            }
        }

        // BOTTOM CONTAINER: Motion Patterns Bar + Bottom Navigation Bar
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
                    val tabs = listOf(
                        Pair(R.drawable.ic_rotate_tab, "Shake"),
                        Pair(R.drawable.ic_shake_tab, "Rotate"),
                        Pair(R.drawable.ic_animations_tab, "Animations"),
                        Pair(R.drawable.ic_zoom_pan_tab, "ZoomPan")
                    )

                    tabs.forEachIndexed { index, tab ->
                        val isSelected = selectedBottomTab == index
                        Box(
                            modifier = Modifier
                                .width(74.dp)
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF45637A) else Color.Transparent)
                                .clickable {
                                    onSelectBottomTab(index)
                                    when (index) {
                                        0 -> {
                                            // Tab 0: Rotate (Rotational Tilt Parallax)
                                            if (!isSensorEnabled) onToggleSensor()
                                            onSelectPattern("none")
                                        }
                                        1 -> {
                                            // Tab 1: Shake (Dynamic Jelly Physics)
                                            if (!isSensorEnabled) onToggleSensor()
                                            onSelectPattern("none")
                                        }
                                        2 -> {
                                            // Tab 2: Animations mode
                                            if (config.patternId.isEmpty() || config.patternId.equals("none", ignoreCase = true)) {
                                                onSelectPattern("horizontal")
                                            }
                                        }
                                        3 -> {
                                            // Tab 3: Zoom / Pan mode (hides properties panel per rk0.s())
                                            if (isSensorEnabled) onToggleSensor()
                                            onSelectPattern("none")
                                            if (showControls) onToggleControls()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = tab.first),
                                contentDescription = tab.second,
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

