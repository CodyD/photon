package dev.photon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.photon.app.capture.CaptureScreen
import dev.photon.app.gallery.GalleryScreen
import dev.photon.app.ui.PhotonTheme
import dev.photon.app.viewer.ViewerScreen
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotonTheme {
                // Route: "gallery", "capture", or a bundle path for the viewer.
                var route by rememberSaveable { mutableStateOf(GALLERY) }
                val goGallery = { route = GALLERY }

                BackHandler(enabled = route != GALLERY, onBack = goGallery)
                when (route) {
                    GALLERY -> GalleryScreen(
                        onNewCapture = { route = CAPTURE },
                        onOpen = { route = it.path },
                    )
                    CAPTURE -> CaptureScreen(onBack = goGallery, onSaved = { route = it.path })
                    else -> ViewerScreen(File(route), onBack = goGallery)
                }
            }
        }
    }

    private companion object {
        const val GALLERY = "gallery"
        const val CAPTURE = "capture"
    }
}
