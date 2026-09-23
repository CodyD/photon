package dev.photon.app.storage

import org.json.JSONArray
import org.json.JSONObject

/** Normalized (0..1) rectangle in frame coordinates. */
data class NormRect(val x: Float, val y: Float, val w: Float, val h: Float)

data class FrameMeta(
    val file: String,
    /** Progress along the sweep in degrees, starting at 0 and increasing monotonically. */
    val yawDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    /** Milliseconds since capture start. */
    val t: Long,
)

data class Manifest(
    val version: Int = FORMAT_VERSION,
    val created: String,
    val device: String,
    val width: Int,
    val height: Int,
    val arcDeg: Double,
    /** +1 if the raw sensor yaw increased during the sweep, -1 if it decreased. */
    val direction: Int,
    val stepDeg: Double,
    val stabilized: Boolean,
    val selection: NormRect?,
    val frames: List<FrameMeta>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT_NAME)
        put("version", version)
        put("created", created)
        put("device", device)
        put("width", width)
        put("height", height)
        put("arcDeg", arcDeg)
        put("direction", if (direction >= 0) "cw" else "ccw")
        put("stepDeg", stepDeg)
        put("stabilized", stabilized)
        selection?.let {
            put("selection", JSONObject().put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h))
        }
        put("frames", JSONArray().apply {
            frames.forEach { f ->
                put(
                    JSONObject()
                        .put("file", f.file)
                        .put("yawDeg", f.yawDeg)
                        .put("pitchDeg", f.pitchDeg)
                        .put("rollDeg", f.rollDeg)
                        .put("t", f.t),
                )
            }
        })
    }

    companion object {
        const val FORMAT_NAME = "photon"
        const val FORMAT_VERSION = 1

        fun fromJson(json: JSONObject): Manifest {
            require(json.optString("format") == FORMAT_NAME) { "Not a photon manifest" }
            val framesJson = json.getJSONArray("frames")
            val frames = List(framesJson.length()) { i ->
                val f = framesJson.getJSONObject(i)
                FrameMeta(
                    file = f.getString("file"),
                    yawDeg = f.getDouble("yawDeg"),
                    pitchDeg = f.optDouble("pitchDeg", 0.0),
                    rollDeg = f.optDouble("rollDeg", 0.0),
                    t = f.optLong("t", 0L),
                )
            }
            val sel = json.optJSONObject("selection")?.let {
                NormRect(
                    it.getDouble("x").toFloat(),
                    it.getDouble("y").toFloat(),
                    it.getDouble("w").toFloat(),
                    it.getDouble("h").toFloat(),
                )
            }
            return Manifest(
                version = json.getInt("version"),
                created = json.optString("created"),
                device = json.optString("device"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                arcDeg = json.getDouble("arcDeg"),
                direction = if (json.optString("direction") == "ccw") -1 else 1,
                stepDeg = json.optDouble("stepDeg", 1.0),
                stabilized = json.optBoolean("stabilized", false),
                selection = sel,
                frames = frames,
            )
        }
    }
}
