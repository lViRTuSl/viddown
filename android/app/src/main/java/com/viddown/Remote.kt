package com.viddown

import org.json.JSONObject

/** GitHub'daki update.json içindeki uygulama sürümü bilgisi. */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

/** yt-dlp eklentisi (tek .py dosyası): indirilir, SHA-256 doğrulanır, yt-dlp'ye tanıtılır. */
data class PluginSpec(val name: String, val url: String, val sha256: String)

/** Siteye özel yt-dlp ön ayarı: adresin sunucu adı [hostRegex] ile eşleşirse seçenekler eklenir. */
data class Preset(val hostRegex: String, val options: List<YtOption>)

data class RemoteManifest(
    val app: AppRelease? = null,
    val plugins: List<PluginSpec> = emptyList(),
    val presets: List<Preset> = emptyList(),
    val message: String = "",
)

/**
 * update.json ayrıştırıcısı (saf Kotlin, birim testlidir). Güvenlik: ön ayarlarda yalnızca
 * izin verilen yt-dlp seçenekleri kabul edilir; eklenti dosya adları sıkı doğrulanır.
 */
object RemoteParser {
    val ALLOWED_PRESET_OPTIONS = setOf(
        "--extractor-args", "--user-agent", "--add-header", "--referer",
        "--sleep-requests", "--sleep-interval", "--max-sleep-interval", "--retries",
    )
    private val PLUGIN_NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}\\.py$")
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val HOST = Regex("^https?://([^/?#:]+)", RegexOption.IGNORE_CASE)

    fun isSafePluginName(n: String) = PLUGIN_NAME.matches(n)
    fun isHttps(u: String) = u.startsWith("https://", ignoreCase = true)
    fun isNewer(remoteCode: Int, localCode: Int) = remoteCode > localCode

    fun parse(text: String): RemoteManifest {
        val o = JSONObject(text)
        var app: AppRelease? = null
        o.optJSONObject("app")?.let { a ->
            val code = a.optInt("versionCode", 0)
            val url = a.optString("apkUrl", "")
            val sha = a.optString("sha256", "")
            if (code > 0 && isHttps(url) && SHA256.matches(sha)) {
                app = AppRelease(code, a.optString("versionName", code.toString()), url, sha.lowercase(), a.optString("notes", ""))
            }
        }
        val plugins = ArrayList<PluginSpec>()
        o.optJSONArray("plugins")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val name = p.optString("name", "")
                val url = p.optString("url", "")
                val sha = p.optString("sha256", "")
                if (isSafePluginName(name) && isHttps(url) && SHA256.matches(sha)) plugins += PluginSpec(name, url, sha.lowercase())
            }
        }
        val presets = ArrayList<Preset>()
        o.optJSONArray("presets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val rx = p.optString("hostRegex", "")
                if (rx.isBlank() || runCatching { Regex(rx) }.isFailure) continue
                val opts = ArrayList<YtOption>()
                val oa = p.optJSONArray("options")
                if (oa != null) for (j in 0 until oa.length()) {
                    val pair = oa.optJSONArray(j) ?: continue
                    val name = pair.optString(0, "")
                    if (name !in ALLOWED_PRESET_OPTIONS) continue
                    val value = if (pair.length() > 1) pair.optString(1, "") else null
                    opts += YtOption(name, value)
                }
                if (opts.isNotEmpty()) presets += Preset(rx, opts)
            }
        }
        return RemoteManifest(app, plugins, presets, o.optString("message", "").take(300))
    }

    /** Adres için geçerli ön ayar seçenekleri. */
    fun optionsFor(m: RemoteManifest, url: String): List<YtOption> {
        val host = HOST.find(url)?.groupValues?.get(1)?.lowercase() ?: return emptyList()
        return m.presets.filter { runCatching { Regex(it.hostRegex, RegexOption.IGNORE_CASE).containsMatchIn(host) }.getOrDefault(false) }
            .flatMap { it.options }
    }
}
