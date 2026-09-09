
package com.skteam.subtitleburner

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class SubtitleBurner(
    private val inputUri: Uri,
    private val outputFile: File,
    private val cues: List<SubtitleCue>,
    private val overlay: SubtitleOverlay,
    private val onProgress: (Float) -> Unit
) {

    companion object {
        private const val TAG = "SubtitleBurner"
        private const val TIMEOUT_US = 10000L
    }

    private var overlayTexId = 0

    fun burn(): Boolean {
        val info = VideoProbe.probe(inputUri)
        Log.d(TAG, "Source: ${info.width}x${info.height} @ ${info.fps}fps, rotation=${info.rotation}")

        val rotatedWidth = if (info.rotation == 90 || info.rotation == 270) info.height else info.width
        val rotatedHeight = if (info.rotation == 90 || info.rotation == 270) info.width else info.height

        val extractor = MediaExtractor()
        extractor.setDataSource(inputUri.path ?: inputUri.toString())

        // --- Encoder ---
        val format = MediaFormat.createVideoFormat("video/avc", rotatedWidth, rotatedHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, calcBitrate(rotatedWidth, rotatedHeight, info.fps))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, info.fps.toInt())
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        val inputSurface = InputSurface(encoderInputSurface)
        inputSurface.makeCurrent()
        TextureRenderer.init()

        // --- Output surface (shared context) ---
        val outputSurface = OutputSurface(EGL14.eglGetCurrentContext(), rotatedWidth, rotatedHeight)
        val videoTexId = outputSurface.textureId

        // --- Decoder ---
        extractor.selectTrack(info.videoTrackIndex)
        val decoderFormat = extractor.getTrackFormat(info.videoTrackIndex)
        val decoder = MediaCodec.createDecoderByType(info.mimeType)
        decoder.configure(decoderFormat, Surface(outputSurface.surfaceTexture), null, 0)
        decoder.start()

        // --- Muxer ---
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var audioMuxTrackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false

        try {
            while (!decoderDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inputBuf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { }
                    outIdx >= 0 -> {
                        val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (bufferInfo.size > 0 || eos) {
                            if (outputSurface.awaitNewImage()) {
                                val cue = SrtParser.findActiveCue(cues, bufferInfo.presentationTimeUs / 1000)

                                // Switch to input surface to render
                                inputSurface.makeCurrent()
                                GLES20.glViewport(0, 0, rotatedWidth, rotatedHeight)
                                GLES20.glClearColor(0f, 0f, 0f, 1f)
                                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                                TextureRenderer.drawVideoTexture(videoTexId)

                                if (overlay.needsUpdate(cue)) {
                                    val bmp = overlay.renderOverlay(cue)
                                    uploadOverlayTexture(bmp)
                                }
                                TextureRenderer.drawOverlayTexture(overlayTexId)

                                inputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                                inputSurface.swapBuffers()

                                if (info.durationUs > 0) {
                                    onProgress(bufferInfo.presentationTimeUs.toFloat() / info.durationUs)
                                }
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                        }
                        if (eos) decoderDone = true
                    }
                }
            }

            encoder.signalEndOfInputStream()

            var encoderDone = false
            while (!encoderDone) {
                val encIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { }
                    encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        if (info.audioTrackIndex >= 0) {
                            audioMuxTrackIndex = muxer.addTrack(extractor.getTrackFormat(info.audioTrackIndex))
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    encIdx >= 0 -> {
                        val encBuf = encoder.getOutputBuffer(encIdx)!!
                        if (bufferInfo.size > 0 && muxerStarted) {
                            encBuf.position(bufferInfo.offset)
                            encBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encBuf, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                    }
                }
            }

            // Copy audio directly
            if (info.audioTrackIndex >= 0 && muxerStarted) {
                extractor.unselectTrack(info.videoTrackIndex)
                extractor.selectTrack(info.audioTrackIndex)
                val audioBuf = ByteBuffer.allocate(64 * 1024)
                val audioInfo = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(audioBuf, 0)
                    if (size < 0) break
                    audioInfo.offset = 0
                    audioInfo.size = size
                    audioInfo.presentationTimeUs = extractor.sampleTime
                    audioInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(audioMuxTrackIndex, audioBuf, audioInfo)
                    extractor.advance()
                }
            }

            onProgress(1f)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Burn failed", e)
            return false
        } finally {
            try { if (muxerStarted) muxer.stop() } catch (e: Exception) {}
            muxer.release()
            decoder.stop(); decoder.release()
            encoder.stop(); encoder.release()
            outputSurface.release()
            inputSurface.release()
            extractor.release()
        }
    }

    private fun uploadOverlayTexture(bmp: android.graphics.Bitmap) {
        if (overlayTexId == 0) overlayTexId = TextureRenderer.bitmapToTexture(bmp)
        else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
    }

    private fun calcBitrate(w: Int, h: Int, fps: Float): Int {
        val raw = (w * h * fps * 0.07).toInt()
        return raw.coerceIn(2_000_000, 20_000_000)
    }
}
