package com.viddown

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Kalıcı ayarlar (masaüstü sürümündeki ayar penceresinin karşılığı). */
data class Config(
    /** Boş = Download/ViDDoWN (MediaStore). Dolu = kullanıcının seçtiği klasör (SAF ağaç URI'si). */
    val saveTreeUri: String = "",
    val defaultFormat: MediaFormat = MediaFormat.MP4,
    val mp3Quality: String = "192k",
    val mp4Quality: String = "1080p",
    val maxConcurrent: Int = 3,
    /** 0 = sınırsız. KB/sn cinsinden saklanır. */
    val speedLimitKbps: Int = 0,
    val autoUpdateYtdlp: Boolean = true,
    val useNightly: Boolean = false,
    /** MP4'leri her cihazda (TV, medya oynatıcı) açılacak H.264 + AAC biçiminde kaydet. */
    val compatMp4: Boolean = true,
    val lastUpdateCheck: Long = 0L,
    /** GitHub'dan uygulama güncellemesini otomatik denetle. */
    val autoUpdateApp: Boolean = true,
    /** GitHub'dan yt-dlp eklentilerini ve site ön ayarlarını al/uygula. */
    val remoteExtensions: Boolean = true,
    val lastAppCheck: Long = 0L,
    /** Yeni sürüm bulununca (indirme yokken) kendiliğinden indirip kur. */
    val autoInstallApp: Boolean = true,
) {
    fun sanitized(): Config = copy(
        mp3Quality = if (mp3Quality in MP3_QUALITIES) mp3Quality else "192k",
        mp4Quality = if (mp4Quality in MP4_QUALITIES) mp4Quality else "1080p",
        maxConcurrent = maxConcurrent.coerceIn(1, 10),
        speedLimitKbps = speedLimitKbps.coerceIn(0, 10_000_000),
    )

    fun qualityFor(format: MediaFormat): String =
        if (format == MediaFormat.MP3) mp3Quality else mp4Quality
}

/** Ayarları JSON dosyasında saklar, yükler; değişiklikleri StateFlow ile duyurur. */
class ConfigManager(private val file: File) {
    private val _state = MutableStateFlow(Config())
    val state: StateFlow<Config> = _state.asStateFlow()

    fun load() {
        try {
            if (file.exists()) _state.value = fromJson(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            // bozuk dosya: varsayılanlarla devam
        }
    }

    fun update(transform: (Config) -> Config) {
        _state.update { transform(it).sanitized() }
        save(_state.value)
    }

    @Synchronized
    private fun save(c: Config) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(toJson(c), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {       // atomik yazım: yarım dosya kalmasın
                file.writeText(toJson(c), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        fun toJson(c: Config): String = JSONObject().apply {
            put("save_tree_uri", c.saveTreeUri)
            put("default_format", c.defaultFormat.name)
            put("mp3_quality", c.mp3Quality)
            put("mp4_quality", c.mp4Quality)
            put("max_concurrent", c.maxConcurrent)
            put("speed_limit_kbps", c.speedLimitKbps)
            put("auto_update_ytdlp", c.autoUpdateYtdlp)
            put("use_nightly", c.useNightly)
            put("compat_mp4", c.compatMp4)
            put("auto_update_app", c.autoUpdateApp)
            put("remote_extensions", c.remoteExtensions)
            put("last_app_check", c.lastAppCheck)
            put("auto_install_app", c.autoInstallApp)
            put("last_update_check", c.lastUpdateCheck)
        }.toString(2)

        fun fromJson(text: String): Config {
            val o = JSONObject(text)
            val d = Config()
            return Config(
                saveTreeUri = o.optString("save_tree_uri", d.saveTreeUri),
                defaultFormat = MediaFormat.parse(o.optString("default_format", d.defaultFormat.name)),
                mp3Quality = o.optString("mp3_quality", d.mp3Quality),
                mp4Quality = o.optString("mp4_quality", d.mp4Quality),
                maxConcurrent = o.optInt("max_concurrent", d.maxConcurrent),
                speedLimitKbps = o.optInt("speed_limit_kbps", d.speedLimitKbps),
                autoUpdateYtdlp = o.optBoolean("auto_update_ytdlp", d.autoUpdateYtdlp),
                useNightly = o.optBoolean("use_nightly", d.useNightly),
                compatMp4 = o.optBoolean("compat_mp4", d.compatMp4),
                autoUpdateApp = o.optBoolean("auto_update_app", d.autoUpdateApp),
                remoteExtensions = o.optBoolean("remote_extensions", d.remoteExtensions),
                lastAppCheck = o.optLong("last_app_check", d.lastAppCheck),
                autoInstallApp = o.optBoolean("auto_install_app", d.autoInstallApp),
                lastUpdateCheck = o.optLong("last_update_check", d.lastUpdateCheck),
            ).sanitized()
        }
    }
}

/** İndirme listesini diske yazar/okur (uygulama kapansa da liste kaybolmasın). */
object QueueStore {

    fun toJson(items: List<DownloadItem>): String {
        val arr = JSONArray()
        for (i in items) {
            arr.put(JSONObject().apply {
                put("id", i.id)
                put("url", i.url)
                put("video_id", i.videoId)
                put("title", i.title)
                put("duration", i.duration)
                put("format", i.format.name)
                put("quality", i.quality)
                put("playlist", i.playlist ?: JSONObject.NULL)
                put("streams", i.streams)
                put("size", i.size)
                put("status", i.status.name)
                put("percent", i.percent.toDouble())
                put("error", i.error)
                put("saved_uri", i.savedUri)
                put("saved_name", i.savedName)
                put("folder_label", i.folderLabel)
                put("folder_uri", i.folderUri)
                put("site", i.site)
                put("thumb", i.thumb)
                put("reencode", i.reencode)
            })
        }
        return JSONObject().put("items", arr).toString()
    }

    /**
     * Önceki oturumdan yükler. Bitmemiş (bekleyen/aktif) öğeler "Durduruldu" olarak gelir;
     * kullanıcı "Tümünü Başlat" ile devam ettirir (yarım dosyalar kaldığı yerden sürer).
     */
    fun fromJson(text: String): List<DownloadItem> {
        val arr = JSONObject(text).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<DownloadItem>()
        for (n in 0 until arr.length()) {
            val o = arr.optJSONObject(n) ?: continue
            val status = Status.entries.firstOrNull { it.name == o.optString("status") } ?: Status.STOPPED
            val restored = when (status) {
                Status.DONE, Status.ERROR, Status.READY -> status
                else -> Status.STOPPED
            }
            out += DownloadItem(
                id = o.optInt("id"),
                url = o.optString("url"),
                videoId = o.optString("video_id"),
                title = o.optString("title"),
                duration = o.optDouble("duration", 0.0),
                format = MediaFormat.parse(o.optString("format")),
                quality = o.optString("quality"),
                playlist = if (o.isNull("playlist")) null else o.optString("playlist"),
                streams = o.optInt("streams", 1),
                size = o.optLong("size", 0L),
                status = restored,
                percent = if (restored == Status.DONE) 100f else o.optDouble("percent", 0.0).toFloat(),
                error = o.optString("error"),
                savedUri = o.optString("saved_uri"),
                savedName = o.optString("saved_name"),
                folderLabel = o.optString("folder_label"),
                folderUri = o.optString("folder_uri"),
                site = o.optString("site"),
                thumb = o.optString("thumb"),
                reencode = o.optBoolean("reencode", false),
            )
        }
        return out
    }
}
