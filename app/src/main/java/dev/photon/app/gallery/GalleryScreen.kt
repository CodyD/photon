package dev.photon.app.gallery

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.photon.app.storage.CaptureInfo
import dev.photon.app.share.ShareLink
import dev.photon.app.storage.CaptureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun GalleryScreen(onNewCapture: () -> Unit, onOpen: (File) -> Unit) {
    val context = LocalContext.current
    val store = remember { CaptureStore(context) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<CaptureInfo?>(null) }
    var pendingDelete by remember { mutableStateOf<CaptureInfo?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun shareLink(info: CaptureInfo) {
        sharing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    store.sharedLink(info.file)
                        ?: ShareLink.upload(info.file).also { store.saveSharedLink(info.file, it) }
                }
            }
            sharing = false
            result
                .onSuccess { url ->
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                    context.startActivity(Intent.createChooser(send, "Share capture link"))
                }
                .onFailure { Toast.makeText(context, "Couldn't share: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val captures by produceState(emptyList<Pair<CaptureInfo, ImageBitmap?>>(), refresh) {
        value = withContext(Dispatchers.IO) {
            store.list().map { it to store.readThumb(it.file)?.asImageBitmap() }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Text(
            "Photon",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (captures.isEmpty()) {
                Text(
                    "No captures yet.",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                CaptureGrid(captures, onOpen = { onOpen(it.file) }, onLongPress = { selected = it })
            }
        }
        Button(
            onClick = onNewCapture,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("New capture") }
    }

    selected?.let { info ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Capture") },
            text = { Text("Share link uploads this capture so anyone with the link can view it in a browser.") },
            confirmButton = {
                TextButton(onClick = {
                    selected = null
                    shareLink(info)
                }) { Text("Share link") }
            },
            dismissButton = {
                TextButton(onClick = {
                    selected = null
                    pendingDelete = info
                }) { Text("Delete") }
            },
        )
    }

    if (sharing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Uploading") },
            text = { Text("Creating a share link…") },
            confirmButton = {},
        )
    }

    pendingDelete?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete capture?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(info.file)
                    pendingDelete = null
                    refresh++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptureGrid(
    captures: List<Pair<CaptureInfo, ImageBitmap?>>,
    onOpen: (CaptureInfo) -> Unit,
    onLongPress: (CaptureInfo) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(captures, key = { it.first.file.path }) { (info, thumb) ->
            Box(
                Modifier
                    .aspectRatio(info.manifest.width.toFloat() / info.manifest.height.coerceAtLeast(1))
                    .background(Color.DarkGray)
                    .combinedClickable(onClick = { onOpen(info) }, onLongClick = { onLongPress(info) }),
            ) {
                thumb?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(
                    "%.0f° · %d".format(info.manifest.arcDeg, info.manifest.frames.size),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color(0x88000000)).padding(4.dp),
                )
            }
        }
    }
}
