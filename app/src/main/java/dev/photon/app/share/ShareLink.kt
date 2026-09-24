package dev.photon.app.share

import dev.photon.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads `.photon` bundles to the photon-viewer web app, which hosts them at a shareable URL that
 * plays the capture in any browser (yaw-driven on phones, GIF on desktops).
 */
object ShareLink {

    /** Uploads [bundle] and returns its viewer URL. Blocking; call off the main thread. */
    fun upload(bundle: File, baseUrl: String = BuildConfig.VIEWER_URL): String {
        val conn = URL("$baseUrl/api/upload").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(bundle.length())
            conn.outputStream.use { out -> bundle.inputStream().use { it.copyTo(out) } }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (code != HttpURLConnection.HTTP_CREATED || json?.has("url") != true) {
                throw IOException(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Upload failed ($code)")
            }
            return json.getString("url")
        } finally {
            conn.disconnect()
        }
    }
}
