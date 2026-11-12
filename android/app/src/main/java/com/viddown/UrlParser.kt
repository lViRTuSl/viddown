package com.viddown

import java.net.URLDecoder

enum class UrlKind { VIDEO, PLAYLIST, BOTH, OTHER, INVALID }

/** Bir YouTube bağlantısının çözümlenmiş hali. */
data class ParsedUrl(val kind: UrlKind, val videoId: String? = null, val listId: String? = null) {
    fun videoUrl(): String = "https://www.youtube.com/watch?v=$videoId"
    fun playlistUrl(): String = "https://www.youtube.com/playlist?list=$listId"
}

private val YT_HOSTS = setOf(
    "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
    "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com",
)
private val URL_RE = Regex("^https?://([^/?#]+)([^?#]*)(?:\\?([^#]*))?", RegexOption.IGNORE_CASE)
private val ID_RE = Regex("^[A-Za-z0-9_-]{6,}$")
private val PATH_ID_RE = Regex("^/(shorts|embed|live|v)/([A-Za-z0-9_-]{6,})")

private fun parseQuery(q: String?): Map<String, String> {
    if (q.isNullOrEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in q.split('&')) {
        if (part.isEmpty()) continue
        val idx = part.indexOf('=')
        val k = if (idx >= 0) part.substring(0, idx) else part
        val v = if (idx >= 0) part.substring(idx + 1) else ""
        val dk = try { URLDecoder.decode(k, "UTF-8") } catch (_: Exception) { k }
        val dv = try { URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { v }
        if (dk !in out) out[dk] = dv
    }
    return out
}

/**
 * URL'yi sınıflandırır:
 *  VIDEO    -> tek video
 *  PLAYLIST -> oynatma listesi
 *  BOTH     -> hem video hem liste içeren bağlantı (kullanıcıya sorulur)
 * RD/UL ile başlayan listeler otomatik "mix" olduğu için yalnızca video sayılır.
 */
fun parseYoutubeUrl(url: String): ParsedUrl {
    val m = URL_RE.find(url.trim()) ?: return ParsedUrl(UrlKind.INVALID)
    val host = m.groupValues[1].substringBefore(':').lowercase()
    if (host !in YT_HOSTS) return ParsedUrl(UrlKind.INVALID)
    val path = m.groupValues[2]
    val q = parseQuery(m.groupValues[3])
    val list = q["list"]?.takeIf { it.isNotBlank() }

    var vid: String? = null
    if (host.endsWith("youtu.be")) {
        vid = path.trim('/').substringBefore('/').takeIf { it.isNotEmpty() }
    } else if (q["v"] != null) {
        vid = q["v"]
    } else {
        PATH_ID_RE.find(path)?.let { vid = it.groupValues[2] }
    }
    if (vid != null && !ID_RE.matches(vid!!)) vid = null

    if (path.trimEnd('/') == "/playlist" && list != null) return ParsedUrl(UrlKind.PLAYLIST, null, list)
    if (vid != null && list != null) {
        if (list.startsWith("RD") || list.startsWith("UL")) return ParsedUrl(UrlKind.VIDEO, vid, null)
        return ParsedUrl(UrlKind.BOTH, vid, list)
    }
    if (vid != null) return ParsedUrl(UrlKind.VIDEO, vid, null)
    if (list != null) return ParsedUrl(UrlKind.PLAYLIST, null, list)
    return ParsedUrl(UrlKind.INVALID)
}

/**
 * Her site için sınıflandırma: YouTube bağlantıları parseYoutubeUrl ile; diğer tüm http(s)
 * bağlantıları OTHER (yt-dlp'nin desteklediği 1000+ siteden herhangi biri olabilir).
 */
fun classifyUrl(url: String): ParsedUrl {
    val m = URL_RE.find(url.trim()) ?: return ParsedUrl(UrlKind.INVALID)
    val host = m.groupValues[1].substringBefore(':').lowercase()
    if (!host.contains('.')) return ParsedUrl(UrlKind.INVALID)
    if (host in YT_HOSTS) return parseYoutubeUrl(url)
    return ParsedUrl(UrlKind.OTHER)
}

private val FIND_URLS = Regex(
    "(?:https?://\\S+)|(?:(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be)/\\S+)",
    RegexOption.IGNORE_CASE,
)

/** Serbest metinden (paylaşılan metin, pano vb.) bağlantıları çıkarır. */
fun extractUrls(text: String?): List<String> {
    if (text.isNullOrBlank()) return emptyList()
    return FIND_URLS.findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'', '>') }
        .map { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
        .filter { it.length > 8 }
        .distinct()
        .toList()
}
