package com.example.spatial

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.spatial.ui.MainScreen
import com.example.spatial.ui.SpatialViewModel
import com.example.spatial.ui.theme.SpatialTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SpatialViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importImageFromIntent(intent)
        enableEdgeToEdge()
        setContent {
            SpatialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0C0D14)
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importImageFromIntent(intent)
    }

    private fun importImageFromIntent(intent: Intent?) {
        val image: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (image != null && intent?.type?.startsWith("image/") != false) {
            viewModel.processCustomImage(image)
        }
    }
}
