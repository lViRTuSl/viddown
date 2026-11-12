package com.viddown

import java.util.Locale

private val TR: Locale = Locale.forLanguageTag("tr-TR")

/** 1536 -> "1,5 KB". */
fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.size - 1) { v /= 1024.0; i++ }
    return if (i == 0) "${v.toLong()} B" else String.format(TR, "%.1f %s", v, units[i])
}

fun fmtSpeed(bytesPerSec: Long): String =
    if (bytesPerSec <= 0L) "—" else fmtSize(bytesPerSec) + "/sn"

/** Saniyeyi "mm:ss" veya "h:mm:ss" yapar. Negatif ise "—". */
fun fmtEta(seconds: Long): String {
    if (seconds < 0L) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(TR, "%d:%02d:%02d", h, m, s) else String.format(TR, "%02d:%02d", m, s)
}

fun fmtDuration(seconds: Double): String =
    if (seconds <= 0.0) "" else fmtEta(seconds.toLong())

fun fmtPercent(p: Float): String = String.format(TR, "%.1f%%", p)

private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

/** yt-dlp hata metninden son "ERROR:" satırını ayıklar. */
fun cleanError(raw: String?): String {
    val text = ANSI.replace(raw ?: "", "").trim()
    if (text.isEmpty()) return "Bilinmeyen hata"
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val err = lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.last()
    return err.removePrefix("ERROR:").trim().take(400)
}

/** Sık görülen hataları Türkçe açıklamayla zenginleştirir (özgün mesaj parantez içinde kalır). */
fun describeError(raw: String?): String {
    val base = cleanError(raw)
    val l = base.lowercase(Locale.ROOT)
    val hint: String? = when {
        "not a bot" in l || "sign in to confirm" in l ->
            "YouTube bot doğrulaması istedi. Biraz bekleyip tekrar deneyin veya Ayarlar'dan yt-dlp'yi güncelleyin."
        "login required" in l || "rate-limit reached" in l || "cookies" in l && "authentication" in l ||
            "log in" in l && ("instagram" in l || "facebook" in l || "twitter" in l) ->
            "Bu içerik giriş gerektiriyor veya site istekleri sınırladı. Ayarlar > Çerez dosyası ile oturum açmış tarayıcınızın cookies.txt dosyasını ekleyip tekrar deneyin."
        "unsupported url" in l -> "Bu bağlantı desteklenen bir videoya ait görünmüyor (profil/sayfa adresi olabilir; doğrudan gönderi/video bağlantısını deneyin)."
        "no video could be found" in l || "no video formats found" in l ->
            "Bu bağlantıda video bulunamadı (yalnızca fotoğraf/metin gönderisi olabilir)."
        "private video" in l -> "Bu video özel."
        "video unavailable" in l || "this video is not available" in l || "has been removed" in l ->
            "Video kullanılamıyor (silinmiş, özel veya bölge kısıtlı olabilir)."
        "members-only" in l || "join this channel" in l -> "Bu video yalnızca kanal üyelerine açık."
        "confirm your age" in l || "age-restricted" in l || "inappropriate for some users" in l ->
            "Yaş sınırlı içerik: oturum açmadan indirilemez."
        "requested format is not available" in l ->
            "İstenen kalite bu video için mevcut değil. Başka bir kalite deneyin."
        "premieres in" in l || "live event will begin" in l || "will begin in" in l ->
            "Yayın/prömiyer henüz başlamadı."
        "http error 429" in l || "too many requests" in l ->
            "Çok fazla istek (429). Bir süre bekleyip tekrar deneyin."
        "no space left" in l -> "Depolama alanı dolu."
        "timed out" in l || "unable to download" in l || "temporary failure" in l ||
            "network is unreachable" in l || "connection reset" in l || "getaddrinfo" in l ->
            "Bağlantı hatası. İnternet bağlantınızı kontrol edip tekrar deneyin."
        else -> null
    }
    return if (hint != null) "$hint\n(${base.take(200)})" else base
}

private val ILLEGAL = Regex("[\\\\/:*?\"<>|]+")

fun safeFileName(name: String?): String {
    val n = ILLEGAL.replace(name ?: "", "_").trim(' ', '.')
    return n.take(120).ifEmpty { "Playlist" }
}

/** MP3 için süre ve kaliteden tahmini boyut (bayt). */
fun estimateMp3Size(durationSec: Double, quality: String): Long {
    val kbps = quality.trimEnd('k', 'K').toIntOrNull() ?: return 0L
    if (durationSec <= 0.0) return 0L
    return (durationSec * kbps * 1000.0 / 8.0).toLong()
}
