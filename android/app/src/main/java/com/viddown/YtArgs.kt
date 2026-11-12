package com.viddown

/** yt-dlp komut satırı seçeneği (değersiz bayrak için value = null). */
data class YtOption(val name: String, val value: String? = null)

/**
 * yt-dlp için biçim seçici ve komut satırı seçeneklerini üretir.
 * Saf Kotlin (Android bağımlılığı yok) olduğundan birim testle doğrulanır.
 */
object YtArgs {
    /** İlerleme satırları: makine tarafından okunabilir, '|' ile ayrılmış. */
    const val DL_TEMPLATE =
        "download:[ytprog]|%(progress.status)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
            "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(progress.filename)s"
    const val PP_TEMPLATE = "postprocess:[ytpp]|%(postprocessor)s|%(status)s"

    private const val H264 = "[vcodec~='^(avc|h264)']"
    private const val AAC = "[acodec~='^(mp4a|aac)']"

    /**
     * compat = true: önce H.264 video + AAC ses aranır (TV, medya oynatıcı, eski telefon dahil
     * her cihazda açılır); bulunamazsa en iyi mevcut biçime düşülür (indirirken dönüştürülür).
     */
    fun formatSelector(format: MediaFormat, quality: String, compat: Boolean = true): String {
        if (format == MediaFormat.MP3) return "bestaudio/best"
        val h = when (quality) { "1080p" -> 1080; "720p" -> 720; "480p" -> 480; else -> null }
        val hf = if (h != null) "[height<=$h]" else ""
        if (!compat) {
            if (h == null) return "bestvideo+bestaudio/best"
            return "bestvideo[height<=$h][ext=mp4]+bestaudio[ext=m4a]/" +
                "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best"
        }
        return "bv*$H264$hf+ba$AAC/" +
            "bv*$H264$hf+ba/" +
            "b$H264$AAC$hf/" +
            "bv*$hf+ba/b$hf/b"
    }

    const val RECODE_ARGS =
        "VideoConvertor:-c:v libx264 -preset veryfast -crf 22 -pix_fmt yuv420p " +
            "-c:a aac -b:a 192k -movflags +faststart"

    /** MP3 kalitesi: "192k" -> "192K" (yt-dlp --audio-quality). */
    fun audioQuality(quality: String): String = quality.trim().uppercase().let {
        if (it.endsWith("K")) it else it + "K"
    }

    fun downloadOptions(item: DownloadItem, tmpDir: String, speedLimitKbps: Int, compat: Boolean = true): List<YtOption> {
        val o = ArrayList<YtOption>()
        o += YtOption("-f", formatSelector(item.format, item.quality, compat))
        o += YtOption("-o", "$tmpDir/%(title)s.%(ext)s")
        o += YtOption("--no-playlist")
        o += YtOption("--newline")
        o += YtOption("--progress-template", DL_TEMPLATE)
        o += YtOption("--progress-template", PP_TEMPLATE)
        o += YtOption("--windows-filenames")
        o += YtOption("--trim-filenames", "150")
        o += YtOption("--no-mtime")
        o += YtOption("--continue")
        o += YtOption("--retries", "10")
        o += YtOption("--fragment-retries", "10")
        o += YtOption("--socket-timeout", "30")
        if (speedLimitKbps > 0) o += YtOption("--limit-rate", "${speedLimitKbps}K")
        if (item.format == MediaFormat.MP3) {
            o += YtOption("-x")
            o += YtOption("--audio-format", "mp3")
            o += YtOption("--audio-quality", audioQuality(item.quality))
        } else if (compat && item.reencode) {
            // Kaynak H.264/AAC değil: önce mkv'de birleştir, sonra uyumlu MP4'e dönüştür.
            o += YtOption("--merge-output-format", "mkv")
            o += YtOption("--recode-video", "mp4")
            o += YtOption("--postprocessor-args", RECODE_ARGS)
        } else {
            o += YtOption("--merge-output-format", "mp4")
            // Tek parça gelen webm/mov gibi kapları da mp4'e al (codec kopyalanır).
            if (compat) o += YtOption("--remux-video", "mp4")
        }
        o += YtOption("--embed-metadata")
        return o
    }

    fun probeSingleOptions(format: MediaFormat, quality: String, withFormat: Boolean, compat: Boolean = true, generic: Boolean = false): List<YtOption> {
        val o = ArrayList<YtOption>()
        o += YtOption("--dump-single-json")
        if (!generic) o += YtOption("--no-playlist")
        o += YtOption("--no-warnings")
        o += YtOption("--socket-timeout", "20")
        if (generic) o += YtOption("--playlist-end", "100")
        if (withFormat) o += YtOption("-f", formatSelector(format, quality, compat))
        return o
    }

    fun probePlaylistOptions(): List<YtOption> = listOf(
        YtOption("--flat-playlist"),
        YtOption("--dump-single-json"),
        YtOption("--no-warnings"),
        YtOption("--socket-timeout", "20"),
    )
}
