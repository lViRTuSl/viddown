package com.viddown

/** yt-dlp çıktısından çıkarılan olaylar. */
sealed class ProgressEvent {
    data class Download(
        val status: String,
        val downloaded: Double?,
        val total: Double?,
        val estimate: Double?,
        val speed: Double?,
        val eta: Double?,
        val filename: String,
    ) : ProgressEvent()

    data class PostProcess(val name: String, val status: String) : ProgressEvent()

    /** "[info] ID: Downloading 2 format(s): 137+140" satırı. */
    data class Formats(val count: Int) : ProgressEvent()
}

object ProgressParser {
    private val FORMATS_RE = Regex("""^\[info]\s.*Downloading\s+(\d+)\s+format\(s\)""")

    private fun num(s: String?): Double? {
        if (s == null) return null
        val v = s.trim().toDoubleOrNull() ?: return null
        return if (v.isNaN() || v.isInfinite()) null else v
    }

    fun parse(line: String): ProgressEvent? {
        val t = line.trim()
        if (t.startsWith("[ytprog]|")) {
            val p = t.split("|", limit = 8)
            if (p.size < 7) return null
            return ProgressEvent.Download(
                status = p[1],
                downloaded = num(p[2]),
                total = num(p[3]),
                estimate = num(p[4]),
                speed = num(p[5]),
                eta = num(p[6]),
                filename = p.getOrElse(7) { "" },
            )
        }
        if (t.startsWith("[ytpp]|")) {
            val p = t.split("|", limit = 3)
            if (p.size < 3) return null
            return ProgressEvent.PostProcess(p[1], p[2])
        }
        if (t.startsWith("[info]")) {
            val m = FORMATS_RE.find(t) ?: return null
            val n = m.groupValues[1].toIntOrNull() ?: return null
            return ProgressEvent.Formats(n.coerceAtLeast(1))
        }
        return null
    }
}

/**
 * Video ve ses ayrı iniyorsa (2 akış) birleşik, tutarlı bir yüzde/hız/ETA üretir.
 * [expectedTotal]: metadata'dan gelen tahmini toplam boyut (0 = bilinmiyor).
 */
class ProgressTracker(private val expectedTotal: Long, var streams: Int) {

    data class Snapshot(val percent: Float, val speed: Long, val eta: Long, val total: Long)

    private var finishedBytes = 0.0
    private var finishedParts = 0
    private var curFile = ""
    private var curDownloaded = 0.0
    private var curTotal = 0.0

    fun update(e: ProgressEvent.Download): Snapshot {
        if (e.status == "finished") {
            finishedBytes += e.downloaded ?: e.total ?: maxOf(curTotal, curDownloaded)
            finishedParts++
            curFile = ""
            curDownloaded = 0.0
            curTotal = 0.0
            return snapshot(e.speed, e.eta)
        }
        if (e.filename != curFile) {      // yeni akış başladı
            curFile = e.filename
            curDownloaded = 0.0
            curTotal = 0.0
        }
        e.downloaded?.let { curDownloaded = it }
        (e.total ?: e.estimate)?.let { if (it > 0.0) curTotal = it }
        return snapshot(e.speed, e.eta)
    }

    private fun snapshot(speed: Double?, eta: Double?): Snapshot {
        val done = finishedBytes + curDownloaded
        val knownNow = finishedBytes + maxOf(curTotal, curDownloaded)
        val expected = if (expectedTotal > 0L) maxOf(expectedTotal.toDouble(), knownNow) else 0.0

        val pct: Double
        val total: Double
        if (expected > 0.0) {
            pct = done / expected * 100.0
            total = expected
        } else {
            val parts = maxOf(streams, finishedParts + 1)
            val frac = if (curTotal > 0.0) curDownloaded / curTotal else 0.0
            pct = (finishedParts + frac) / parts * 100.0
            total = if (curTotal > 0.0) knownNow else 0.0
        }
        val sp = speed ?: 0.0
        val etaSec = when {
            sp > 0.0 && expected > 0.0 -> ((expected - done) / sp).coerceAtLeast(0.0)
            eta != null -> eta
            else -> -1.0
        }
        return Snapshot(
            percent = pct.coerceIn(0.0, 99.9).toFloat(),
            speed = sp.toLong(),
            eta = if (etaSec < 0.0) -1L else etaSec.toLong(),
            total = total.toLong(),
        )
    }
}
