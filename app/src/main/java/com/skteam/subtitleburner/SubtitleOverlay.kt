
package com.skteam.subtitleburner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

class SubtitleOverlay(
    private val videoWidth: Int,
    private val videoHeight: Int
) {
    private val overlayBitmap: Bitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(overlayBitmap)
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var fontFamily: String = "sans-serif"
    var fontSizePercent: Int = 50
    var textColor: Int = Color.WHITE
    var bold: Boolean = true
    var watermarkText: String = "ترجمة فريق S.K"

    private var lastCueIndex: Int = -1

    fun needsUpdate(cue: SubtitleCue?): Boolean {
        val idx = cue?.index ?: -1
        return idx != lastCueIndex
    }

    fun renderOverlay(cue: SubtitleCue?): Bitmap {
        canvas.drawColor(Color.TRANSPARENT)

        val wmSize = (videoHeight * 0.028f).toInt().coerceAtLeast(14)
        watermarkPaint.color = Color.argb(220, 255, 255, 255)
        watermarkPaint.textSize = wmSize.toFloat()
        watermarkPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val wmX = videoWidth - (videoWidth * 0.02f)
        val wmY = videoHeight * 0.05f
        watermarkPaint.setShadowLayer(wmSize * 0.2f, 2f, 2f, Color.argb(180, 0, 0, 0))
        watermarkPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(watermarkText, wmX, wmY, watermarkPaint)
        watermarkPaint.clearShadowLayer()


        if (cue != null && cue.text.isNotBlank()) {
            val baseSize = videoHeight * (fontSizePercent / 100f / 10f)
            val fontSize = baseSize.coerceAtLeast(12f)
            val typeface = resolveTypeface(fontFamily)
            val style = if (bold) Typeface.BOLD else Typeface.NORMAL
            subtitlePaint.typeface = Typeface.create(typeface, style)
            subtitlePaint.textSize = fontSize
            subtitlePaint.color = textColor
            subtitlePaint.textAlign = Paint.Align.CENTER

            strokePaint.set(subtitlePaint)
            strokePaint.color = Color.BLACK
            strokePaint.style = Paint.Style.FILL_AND_STROKE
            strokePaint.strokeWidth = fontSize * 0.12f

            val maxWidth = videoWidth * 0.9f
            val lines = wrapText(cue.text, maxWidth)
            val lineHeight = fontSize * 1.3f
            val totalH = lines.size * lineHeight
            var y = videoHeight - (videoHeight * 0.06f) - totalH + lineHeight

            for (line in lines) {
                canvas.drawText(line, videoWidth / 2f, y, strokePaint)
                canvas.drawText(line, videoWidth / 2f, y, subtitlePaint)
                y += lineHeight
            }
        }

        lastCueIndex = cue?.index ?: -1
        return overlayBitmap
    }
    
    private fun wrapText(text: String, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (rawLine in text.split("\n")) {
            val words = rawLine.split(" ")
            var current = StringBuilder()
            for (word in words) {
                val test = if (current.isEmpty()) word else "$current $word"
                if (subtitlePaint.measureText(test) > maxWidth && current.isNotEmpty()) {
                    result.add(current.toString())
                    current = StringBuilder(word)
                } else {
                    if (current.isNotEmpty()) current.append(" ")
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) result.add(current.toString())
        }
        return result
    }

    private fun resolveTypeface(family: String): Typeface {
        return when (family) {
            "serif" -> Typeface.SERIF
            "monospace" -> Typeface.MONOSPACE
            "sans-serif-condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            else -> Typeface.SANS_SERIF
        }
    }
}
