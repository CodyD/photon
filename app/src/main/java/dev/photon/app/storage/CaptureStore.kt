package dev.photon.app.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Summary of a saved capture for the gallery. */
data class CaptureInfo(
    val file: File,
    val manifest: Manifest,
)

/**
 * Reads and writes `.photon` bundles in app-private storage.
 *
 * A bundle is a zip holding `manifest.json`, `thumb.jpg` and `frames/NNN.jpg`. Entries are STORED
 * (JPEGs don't compress further) so frames can be read directly.
 */
class CaptureStore(context: Context) {

    private val capturesDir = File(context.filesDir, "captures").apply { mkdirs() }
    private val workDir = File(context.cacheDir, "capture-work").apply { mkdirs() }

    /** Fresh scratch directory for an in-progress capture. */
    fun newWorkDir(id: String): File = File(workDir, id).apply {
        deleteRecursively()
        mkdirs()
        File(this, FRAMES_DIR).mkdirs()
    }

    fun list(): List<CaptureInfo> =
        capturesDir.listFiles { f -> f.extension == EXTENSION }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f -> runCatching { CaptureInfo(f, readManifest(f)) }.getOrNull() }

    fun readManifest(bundle: File): Manifest = ZipFile(bundle).use { zip ->
        val entry = zip.getEntry(MANIFEST) ?: error("Missing manifest")
        Manifest.fromJson(JSONObject(zip.getInputStream(entry).bufferedReader().readText()))
    }

    fun readThumb(bundle: File): Bitmap? = ZipFile(bundle).use { zip ->
        zip.getEntry(THUMB)?.let { e -> zip.getInputStream(e).use { BitmapFactory.decodeStream(it) } }
    }

    /** Decodes every frame, downsampled so the long edge is at most [maxEdge]. */
    fun readFrames(bundle: File, manifest: Manifest, maxEdge: Int): List<Bitmap> = ZipFile(bundle).use { zip ->
        var sample = 1
        while (maxOf(manifest.width, manifest.height) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        manifest.frames.map { f ->
            val entry = zip.getEntry(f.file) ?: error("Missing ${f.file}")
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("Could not decode ${f.file}")
        }
    }

    /** Packs a finished work directory into a bundle, removes the work directory, and returns the bundle file. */
    fun finalize(id: String, work: File, manifest: Manifest, thumb: Bitmap): File {
        File(work, THUMB).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        File(work, MANIFEST).writeText(manifest.toJson().toString(2))

        val tmp = File(capturesDir, "$id.$EXTENSION.tmp")
        ZipOutputStream(FileOutputStream(tmp).buffered()).use { zip ->
            putStored(zip, MANIFEST, File(work, MANIFEST))
            putStored(zip, THUMB, File(work, THUMB))
            manifest.frames.forEach { putStored(zip, it.file, File(work, it.file)) }
        }
        val out = File(capturesDir, "$id.$EXTENSION")
        check(tmp.renameTo(out)) { "Could not move bundle into place" }
        work.deleteRecursively()
        return out
    }

    fun delete(bundle: File) {
        bundle.delete()
    }

    private fun putStored(zip: ZipOutputStream, name: String, file: File) {
        val bytes = file.readBytes()
        val crc = CRC32().apply { update(bytes) }
        zip.putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
            },
        )
        zip.write(bytes)
        zip.closeEntry()
    }

    companion object {
        const val EXTENSION = "photon"
        const val MANIFEST = "manifest.json"
        const val THUMB = "thumb.jpg"
        const val FRAMES_DIR = "frames"

        fun frameName(index: Int) = "$FRAMES_DIR/${index.toString().padStart(3, '0')}.jpg"
    }
}
