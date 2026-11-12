package com.viddown

/** İndirme biçimi: MP3 (yalnızca ses) veya MP4 (video + ses). */
enum class MediaFormat {
    MP3, MP4;

    companion object {
        fun parse(s: String?): MediaFormat = entries.firstOrNull { it.name == s } ?: MP4
    }
}

val MP3_QUALITIES = listOf("128k", "192k", "320k")
val MP4_QUALITIES = listOf("Best", "1080p", "720p", "480p")

fun qualitiesFor(format: MediaFormat): List<String> =
    if (format == MediaFormat.MP3) MP3_QUALITIES else MP4_QUALITIES

/** Tablo/liste durumları (masaüstü sürümüyle aynı sözlük). */
enum class Status(val label: String) {
    READY("Hazır"),
    WAITING("Bekliyor"),
    DOWNLOADING("İndiriliyor"),
    CONVERTING("Dönüştürülüyor"),
    DONE("Tamamlandı"),
    ERROR("Hata"),
    STOPPED("Durduruldu");

    val isActive: Boolean get() = this == DOWNLOADING || this == CONVERTING
}

/** Listedeki tek bir indirme satırı. Değişmez (immutable) tutulur; güncelleme copy() iledir. */
data class DownloadItem(
    val id: Int,
    val url: String,
    val videoId: String,
    val title: String,
    val duration: Double,
    val format: MediaFormat,
    val quality: String,
    val playlist: String? = null,       // playlist ise alt klasör adı
    val streams: Int = 1,               // video+ses ayrı iniyorsa 2
    val size: Long = 0L,                // bayt (tahmini veya gerçek)
    val status: Status = Status.WAITING,
    val percent: Float = 0f,
    val speed: Long = 0L,               // bayt/sn
    val eta: Long = -1L,                // sn
    val error: String = "",
    val savedUri: String = "",
    val savedName: String = "",
    val folderLabel: String = "",
    val folderUri: String = "",
    val site: String = "",              // "YouTube", "Instagram"… (boşsa YouTube)
    val thumb: String = "",             // küçük resim adresi (boşsa YouTube kimliğinden türetilir)
    val reencode: Boolean = false,      // kaynak H.264/AAC değil: indirirken dönüştürülür
)

fun thumbUrl(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

/** Listede gösterilecek küçük resim adresi ("" = yok). */
fun DownloadItem.thumbOrNull(): String = when {
    thumb.isNotEmpty() -> thumb
    videoId.isNotEmpty() && (site.isEmpty() || site == "YouTube") -> thumbUrl(videoId)
    else -> ""
}

/** Alt çubuk ve bildirim için özet. */
data class Summary(
    val ready: Int = 0,
    val active: Int = 0,
    val waiting: Int = 0,
    val done: Int = 0,
    val error: Int = 0,
    val stopped: Int = 0,
    val speed: Long = 0L,
    val avgPercent: Int = 0,
)

fun summarize(items: List<DownloadItem>): Summary {
    var ready = 0; var active = 0; var waiting = 0; var done = 0; var error = 0; var stopped = 0
    var speed = 0L; var pctSum = 0f
    for (i in items) {
        when (i.status) {
            Status.DOWNLOADING, Status.CONVERTING -> { active++; pctSum += i.percent }
            Status.READY -> ready++
            Status.WAITING -> waiting++
            Status.DONE -> done++
            Status.ERROR -> error++
            Status.STOPPED -> stopped++
        }
        if (i.status == Status.DOWNLOADING) speed += i.speed
    }
    return Summary(ready, active, waiting, done, error, stopped, speed,
        if (active > 0) (pctSum / active).toInt().coerceIn(0, 100) else 0)
}
