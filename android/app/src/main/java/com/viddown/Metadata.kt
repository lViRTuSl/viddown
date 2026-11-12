package com.viddown

import org.json.JSONObject

data class VideoMeta(
    val id: String,
    val title: String,
    val duration: Double,
    val size: Long,        // seçilen biçimlerin toplam tahmini boyutu (0 = bilinmiyor)
    val streams: Int,      // video+ses ayrı iniyorsa 2
    val url: String = "",  // indirilecek sayfa adresi (YouTube dışı siteler için)
    val thumb: String = "",
    val site: String = "",
    val nonCompat: Boolean = false,  // seçilen biçim H.264/AAC değil
)

data class PlaylistEntry(
    val id: String,
    val title: String,
    val duration: Double,
    val url: String = "",
    val thumb: String = "",
    val size: Long = 0L,
    val streams: Int = 2,
    val nonCompat: Boolean = false,
)
data class PlaylistMeta(val title: String, val entries: List<PlaylistEntry>, val site: String = "")

sealed class Probe {
    data class Video(val meta: VideoMeta) : Probe()
    data class Playlist(val meta: PlaylistMeta) : Probe()
}

/** yt-dlp --dump-single-json çıktısını ayrıştırır. */
object MetadataParser {

    fun parse(raw: String, originalUrl: String = ""): Probe {
        val start = raw.indexOf('{')
        if (start < 0) throw IllegalArgumentException("Video bilgisi alınamadı.")
        val o = JSONObject(raw.substring(start))
        return if (o.optString("_type") == "playlist") Probe.Playlist(parsePlaylist(o, originalUrl))
        else Probe.Video(parseVideo(o, originalUrl))
    }

    private fun sizeOf(o: JSONObject): Long {
        val exact = o.optDouble("filesize", 0.0)
        val v = if (exact > 0.0) exact else o.optDouble("filesize_approx", 0.0)
        return if (v.isNaN() || v < 0.0) 0L else v.toLong()
    }

    private fun duration(o: JSONObject): Double {
        val d = o.optDouble("duration", 0.0)
        return if (d.isNaN() || d < 0.0) 0.0 else d
    }

    private fun str(o: JSONObject, key: String): String {
        if (o.isNull(key)) return ""
        return o.optString(key, "").trim()
    }

    /** İnsan okunur site adı. */
    fun siteName(o: JSONObject): String {
        val k = str(o, "extractor_key").ifEmpty { str(o, "extractor") }
        val l = k.lowercase()
        return when {
            k.isEmpty() -> ""
            l.startsWith("youtube") -> "YouTube"
            l.startsWith("twitter") -> "X"
            l.startsWith("instagram") -> "Instagram"
            l.startsWith("tiktok") -> "TikTok"
            l.startsWith("facebook") -> "Facebook"
            l.startsWith("reddit") -> "Reddit"
            l.startsWith("vimeo") -> "Vimeo"
            l.startsWith("twitch") -> "Twitch"
            l.startsWith("soundcloud") -> "SoundCloud"
            l.startsWith("dailymotion") -> "Dailymotion"
            else -> k
        }
    }

    private fun thumbOf(o: JSONObject): String {
        val t = str(o, "thumbnail")
        if (t.startsWith("http")) return t
        val arr = o.optJSONArray("thumbnails")
        if (arr != null) {
            for (i in arr.length() - 1 downTo 0) {
                val u = arr.optJSONObject(i)?.let { str(it, "url") } ?: ""
                if (u.startsWith("http")) return u
            }
        }
        return ""
    }

    private fun isH264(c: String) = c.startsWith("avc") || c.startsWith("h264")
    private fun isAac(c: String) = c.startsWith("mp4a") || c.startsWith("aac")
    private fun none(c: String) = c.isEmpty() || c == "none" || c == "null"

    /** Seçilen akışlardan biri H.264 (video) / AAC (ses) değilse true. */
    fun nonCompat(o: JSONObject): Boolean {
        val rf = o.optJSONArray("requested_formats")
        val list = ArrayList<JSONObject>()
        if (rf != null && rf.length() > 0) {
            for (i in 0 until rf.length()) rf.optJSONObject(i)?.let { list += it }
        } else list += o
        for (f in list) {
            val v = str(f, "vcodec").lowercase()
            val a = str(f, "acodec").lowercase()
            if (!none(v) && !isH264(v)) return true
            if (!none(a) && !isAac(a)) return true
        }
        return false
    }

    private fun sizeAndStreams(o: JSONObject): Pair<Long, Int> {
        val rf = o.optJSONArray("requested_formats")
        var size = 0L
        var streams = 1
        if (rf != null && rf.length() > 0) {
            streams = rf.length()
            for (i in 0 until rf.length()) rf.optJSONObject(i)?.let { size += sizeOf(it) }
        } else {
            size = sizeOf(o)
        }
        return size to streams
    }

    private fun parseVideo(o: JSONObject, originalUrl: String): VideoMeta {
        val id = str(o, "id")
        if (id.isBlank()) throw IllegalArgumentException("Video bilgisi alınamadı.")
        val title = str(o, "title").ifBlank { id }
        val (size, streams) = sizeAndStreams(o)
        val url = originalUrl.ifEmpty { str(o, "webpage_url") }
        return VideoMeta(id, title, duration(o), size, streams, url, thumbOf(o), siteName(o), nonCompat(o))
    }

    private fun parsePlaylist(o: JSONObject, originalUrl: String): PlaylistMeta {
        val title = str(o, "title").ifBlank { "Playlist" }
        val arr = o.optJSONArray("entries")
        val list = ArrayList<PlaylistEntry>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = str(e, "id")
                val t = str(e, "title").ifBlank { id }
                if (id.isBlank() || t == "[Private video]" || t == "[Deleted video]") continue
                val eu = str(e, "webpage_url").ifEmpty { str(e, "url") }
                val (size, streams) = sizeAndStreams(e)
                list += PlaylistEntry(
                    id, t, duration(e),
                    url = if (eu.startsWith("http")) eu else originalUrl,
                    thumb = thumbOf(e),
                    size = size,
                    streams = if (e.optJSONArray("requested_formats") != null) streams else 2,
                    nonCompat = if (e.has("vcodec") || e.has("requested_formats")) nonCompat(e) else false,
                )
            }
        }
        return PlaylistMeta(title, list, siteName(o))
    }
}
