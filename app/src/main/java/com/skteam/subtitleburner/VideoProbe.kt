
package com.skteam.subtitleburner

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

data class VideoInfo(
    val width: Int,
    val height: Int,
    val fps: Float,
    val durationUs: Long,
    val rotation: Int,
    val videoTrackIndex: Int,
    val audioTrackIndex: Int,
    val mimeType: String
)

object VideoProbe {

    fun probe(uri: Uri): VideoInfo {
        val extractor = MediaExtractor()
        extractor.setDataSource(uri.path ?: uri.toString())

        var videoTrack = -1
        var audioTrack = -1
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrack == -1) {
                videoTrack = i
                format = f
            } else if (mime.startsWith("audio/") && audioTrack == -1) {
                audioTrack = i
            }
        }

        if (format == null) {
            extractor.release()
            throw IllegalStateException("لا يوجد مسار فيديو في الملف")
        }

        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else 0L
        val rotation = if (format.containsKey("rotation-degrees"))
            format.getInteger("rotation-degrees") else 0

        val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            val fr = format.getFloat(MediaFormat.KEY_FRAME_RATE)
            if (fr > 0) fr else 30f
        } else 30f

        val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"

        extractor.release()

        return VideoInfo(width, height, fps, duration, rotation, videoTrack, audioTrack, mime)
    }
}
