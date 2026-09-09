
package com.skteam.subtitleburner

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SrtParser {

    fun parse(stream: InputStream): List<SubtitleCue> {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val content = reader.readText()
        reader.close()

        val blocks = content.replace("\r\n", "\n").replace("\r", "\n").trim().split(Regex("\n\\s*\n"))
        val cues = mutableListOf<SubtitleCue>()

        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.size < 2) continue

            var idx = 0
            if (lines[0].trim().matches(Regex("\\d+"))) idx = 1

            val timeLine = lines.getOrNull(idx) ?: continue
            val timeMatch = Regex("([\\d:,]+)\\s*-->\\s*([\\d:,]+)").find(timeLine) ?: continue
            val start = toMs(timeMatch.groupValues[1])
            val end = toMs(timeMatch.groupValues[2])
            val text = lines.drop(idx + 1).joinToString("\n")

            cues.add(SubtitleCue(cues.size + 1, start, end, text))
        }
        return cues
    }

    private fun toMs(t: String): Long {
        val m = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})").find(t) ?: return 0
        val hh = m.groupValues[1].toLong()
        val mm = m.groupValues[2].toLong()
        val ss = m.groupValues[3].toLong()
        val ms = m.groupValues[4].padEnd(3, '0').toLong()
        return hh * 3600000 + mm * 60000 + ss * 1000 + ms
    }

    fun findActiveCue(cues: List<SubtitleCue>, timeMs: Long): SubtitleCue? {
        for (cue in cues) {
            if (timeMs >= cue.startMs && timeMs <= cue.endMs) return cue
        }
        return null
    }
}
