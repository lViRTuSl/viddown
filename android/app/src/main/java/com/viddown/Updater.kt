package com.viddown

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * GitHub tabanlı güncelleme ve eklenti sistemi.
 *  - Uygulama güncellemesi: update.json'daki sürüm yeniyse APK indirilir (SHA-256 doğrulanır) ve
 *    sistemin paket yükleyicisiyle kurulur (imza aynı olmalıdır; Android bunu zorunlu kılar).
 *  - yt-dlp eklentileri: .py dosyaları indirilir, SHA-256 doğrulanır, yt-dlp'ye --plugin-dirs ile tanıtılır.
 *  - Site ön ayarları: izin verilen yt-dlp seçenekleri siteye göre otomatik eklenir.
 */
class Updater(
    private val app: Application,
    private val config: ConfigManager,
    private val scope: CoroutineScope,
    private val busy: () -> Boolean,
    private val say: (String) -> Unit,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: AppRelease) : State
        data class Downloading(val release: AppRelease, val percent: Int) : State
        data class Installing(val release: AppRelease) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _message = MutableStateFlow("")
    /** update.json'daki duyuru metni. */
    val message: StateFlow<String> = _message.asStateFlow()

    private val _plugins = MutableStateFlow(0)
    val pluginCount: StateFlow<Int> = _plugins.asStateFlow()

    @Volatile private var manifest = RemoteManifest()
    private val cacheFile = File(app.filesDir, "uzak.json")
    private val pluginRoot = File(app.filesDir, "eklentiler")
    private val updateDir = File(app.cacheDir, "guncelleme")

    private val localCode: Int get() = BuildConfig.VERSION_CODE

    fun start() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (cacheFile.isFile) manifest = RemoteParser.parse(cacheFile.readText(Charsets.UTF_8))
                _message.value = manifest.message
                _plugins.value = installedPluginNames().size
            }
            cleanOldDownloads()
            val c = config.state.value
            val due = System.currentTimeMillis() - c.lastAppCheck > CHECK_INTERVAL_MS
            if ((c.autoUpdateApp || c.remoteExtensions) && due) check(manual = false)
        }
    }

    /** Elle (manual) veya otomatik denetim. */
    fun check(manual: Boolean) {
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        scope.launch(Dispatchers.IO) { doCheck(manual) }
    }

    private fun doCheck(manual: Boolean) {
        _state.value = State.Checking
        try {
            val text = httpGetText(MANIFEST_URL)
            val m = RemoteParser.parse(text)
            manifest = m
            runCatching { cacheFile.writeText(text, Charsets.UTF_8) }
            _message.value = m.message
            config.update { it.copy(lastAppCheck = System.currentTimeMillis()) }
            if (config.state.value.remoteExtensions) syncPlugins(m)
            val rel = m.app
            if (rel != null && RemoteParser.isNewer(rel.versionCode, localCode)) {
                _state.value = State.Available(rel)
                if (manual) say("Yeni sürüm var: ${rel.versionName}")
                // Otomatik kurulum: yalnızca izin verilmişse ve indirme çalışmıyorsa
                else if (config.state.value.autoInstallApp && !busy() && app.packageManager.canRequestPackageInstalls()) {
                    downloadAndInstall()
                }
            } else {
                _state.value = State.UpToDate
                if (manual) say("ViDDoWN güncel (${BuildConfig.VERSION_NAME}).")
            }
        } catch (t: Throwable) {
            _state.value = if (manual) State.Failed("Güncelleme denetlenemedi: ${t.message ?: t.javaClass.simpleName}") else State.Idle
            if (manual) say("Güncelleme denetlenemedi: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ----------------------------------------------------------------------- uygulama güncellemesi

    fun downloadAndInstall() {
        val rel = (_state.value as? State.Available)?.release ?: return
        if (!app.packageManager.canRequestPackageInstalls()) {
            say("Önce bu uygulamaya \"bilinmeyen uygulamaları yükleme\" izni verin, sonra Güncelle'ye tekrar dokunun.")
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                _state.value = State.Downloading(rel, 0)
                val apk = download(rel)
                _state.value = State.Installing(rel)
                install(apk)
            } catch (t: Throwable) {
                _state.value = State.Failed("Güncelleme başarısız: ${t.message ?: t.javaClass.simpleName}")
                say("Güncelleme başarısız: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun download(rel: AppRelease): File {
        updateDir.mkdirs()
        val out = File(updateDir, "ViDDoWN-${rel.versionCode}.apk")
        val md = MessageDigest.getInstance("SHA-256")
        val conn = open(rel.apkUrl, 30_000)
        try {
            val total = conn.contentLengthLong
            var done = 0L
            var last = 0
            conn.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; _state.value = State.Downloading(rel, p) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (hex != rel.sha256) {
            out.delete()
            throw IllegalStateException("APK doğrulanamadı (SHA-256 uyuşmuyor).")
        }
        return out
    }

    private fun install(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { ins ->
                session.openWrite("viddown.apk", 0, apk.length()).use { os ->
                    ins.copyTo(os)
                    session.fsync(os)
                }
            }
            val intent = Intent(app, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            val pi = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }

    /** Yükleyici sonucu (InstallReceiver'dan). Başarıda uygulama zaten yeniden başlatılır/kapanır. */
    fun onInstallResult(status: Int, msg: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS) return
        val rel = (_state.value as? State.Installing)?.release
        val text = when (status) {
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "Güncelleme kurulamadı: imza uyuşmuyor. Eski sürümü kaldırıp yeni APK'yı elle kurun."
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Güncelleme iptal edildi."
            else -> "Güncelleme kurulamadı: ${msg ?: "kod $status"}"
        }
        _state.value = if (rel != null) State.Available(rel) else State.Idle
        say(text)
    }

    private fun cleanOldDownloads() {
        updateDir.listFiles()?.forEach { f -> runCatching { f.delete() } }
    }

    // ------------------------------------------------------------------------------- eklentiler

    private fun installedPluginNames(): List<String> =
        pluginRoot.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

    private fun pluginFile(name: String) =
        File(pluginRoot, "${name.removeSuffix(".py")}/yt_dlp_plugins/extractor/$name")

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(32 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun syncPlugins(m: RemoteManifest) {
        var changed = 0
        val wanted = m.plugins.map { it.name.removeSuffix(".py") }.toSet()
        // Listeden kalkan eklentileri sil
        pluginRoot.listFiles()?.forEach { d -> if (d.isDirectory && d.name !in wanted) { d.deleteRecursively(); changed++ } }
        for (p in m.plugins) {
            try {
                val f = pluginFile(p.name)
                if (f.isFile && sha256Of(f) == p.sha256) continue
                val bytes = httpGetBytes(p.url, maxBytes = 512 * 1024)
                val md = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (md != p.sha256) throw IllegalStateException("SHA-256 uyuşmuyor")
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                changed++
            } catch (t: Throwable) {
                say("Eklenti alınamadı (${p.name}): ${t.message}")
            }
        }
        _plugins.value = installedPluginNames().size
        if (changed > 0) say("Eklentiler güncellendi ($changed değişiklik).")
    }

    /** yt-dlp'ye eklenecek ek seçenekler: eklenti dizini + siteye özel ön ayarlar. */
    fun extraOptions(url: String): List<YtOption> {
        if (!config.state.value.remoteExtensions) return emptyList()
        val o = ArrayList<YtOption>()
        if (installedPluginNames().isNotEmpty()) o += YtOption("--plugin-dirs", pluginRoot.absolutePath)
        o += RemoteParser.optionsFor(manifest, url)
        return o
    }

    // ----------------------------------------------------------------------------------- ağ

    private fun open(url: String, readTimeout: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = readTimeout
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "ViDDoWN/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("Cache-Control", "no-cache")
        if (c.responseCode !in 200..299) { c.disconnect(); throw IllegalStateException("HTTP ${c.responseCode}") }
        return c
    }

    private fun httpGetBytes(url: String, maxBytes: Int): ByteArray {
        val c = open(url, 20_000)
        try {
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IllegalStateException("dosya çok büyük")
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    private fun httpGetText(url: String): String = String(httpGetBytes(url, 256 * 1024), Charsets.UTF_8)

    companion object {
        const val MANIFEST_URL = "https://raw.githubusercontent.com/lViRTuSl/viddown/main/update.json"
        private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}

/** PackageInstaller sonuçlarını alır; kullanıcı onayı gerekiyorsa sistem ekranını açar. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Arka planda etkinlik açılamayabilir: dokunulabilir bildirim de bırak.
                runCatching { notifyConfirm(context, it) }
                runCatching { context.startActivity(it) }
            }
            return
        }
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        (context.applicationContext as? App)?.engine?.updater?.onInstallResult(status, msg)
    }

    private fun notifyConfirm(context: Context, confirm: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(
            android.app.NotificationChannel("guncelleme", "Uygulama güncellemesi", android.app.NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(context, 7, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(context, "guncelleme")
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("ViDDoWN güncellemesi hazır")
            .setContentText("Kurmak için dokunun")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(1003, n)
    }

    companion object { const val ACTION = "com.viddown.INSTALL_RESULT" }
}
