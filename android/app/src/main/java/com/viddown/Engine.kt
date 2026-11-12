package com.viddown

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private fun YoutubeDLRequest.add(o: YtOption): YoutubeDLRequest =
    if (o.value == null) addOption(o.name) else addOption(o.name, o.value)

/**
 * İndirme motoru: kuyruk, eşzamanlı indirme limiti, hız sınırı, metadata çözümleme,
 * yt-dlp güncelleme ve kalıcı liste. Uygulama süresince yaşar (App içinde tek örnek).
 *
 * Her indirme ayrı bir yt-dlp süreci (bağımsız iş parçacığı) olarak çalışır; arayüz yalnızca
 * [items] akışını izler, bu yüzden hiçbir koşulda donmaz.
 */
class DownloadEngine(private val app: Application) {

    sealed interface Phase {
        data object Init : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val config = ConfigManager(File(app.filesDir, "ayarlar.json"))
    private val queueFile = File(app.filesDir, "kuyruk.json")
    private val tempRoot = File(app.getExternalFilesDir(null) ?: app.filesDir, "gecici")

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    val summary: Flow<Summary> = items.map { summarize(it) }.distinctUntilChanged()

    private val _phase = MutableStateFlow<Phase>(Phase.Init)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _pendingMeta = MutableStateFlow(0)
    val pendingMeta: StateFlow<Int> = _pendingMeta.asStateFlow()

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    private val _ytdlpVersion = MutableStateFlow("")
    val ytdlpVersion: StateFlow<String> = _ytdlpVersion.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** itemId -> çalışan yt-dlp süreç kimliği */
    private val workers = ConcurrentHashMap<Int, String>()
    private val stopRequested = ConcurrentHashMap.newKeySet<Int>()
    private val exporting = ConcurrentHashMap.newKeySet<Int>()
    private val nextId = AtomicInteger(1)
    private val metaGate = Semaphore(2)

    private fun say(msg: String) { _messages.tryEmit(msg) }

    // ------------------------------------------------------------------ yaşam döngüsü

    val updater = Updater(app, config, scope) { say(it) }

    fun start() {
        config.load()
        updater.start()
        restoreQueue()
        scope.launch { persistLoop() }
        scope.launch { watchConfig() }
        scope.launch(Dispatchers.IO) { initLibs() }
    }

    fun retryInit() {
        scope.launch(Dispatchers.IO) { initLibs() }
    }

    private fun initLibs() {
        _phase.value = Phase.Init
        try {
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            _phase.value = Phase.Ready
            refreshVersion()
            maybeAutoUpdate()
            schedule()
        } catch (t: Throwable) {
            _phase.value = Phase.Failed(describeError(t.message ?: t.toString()))
        }
    }

    private fun refreshVersion() {
        _ytdlpVersion.value = try {
            val r = YoutubeDLRequest(emptyList<String>()).addOption("--version")
            YoutubeDL.getInstance().execute(r, null, false, null).out.trim().lineSequence().firstOrNull().orEmpty()
        } catch (_: Throwable) {
            YoutubeDL.getInstance().version(app) ?: ""
        }
    }

    // -------------------------------------------------------------------- yt-dlp güncelleme

    fun updateYtdlp() {
        scope.launch(Dispatchers.IO) { doUpdate(manual = true) }
    }

    private fun maybeAutoUpdate() {
        val c = config.state.value
        if (!c.autoUpdateYtdlp) return
        if (System.currentTimeMillis() - c.lastUpdateCheck < 12L * 3600_000L) return
        doUpdate(manual = false)
    }

    private fun doUpdate(manual: Boolean) {
        if (_phase.value != Phase.Ready) { if (manual) say("Motor henüz hazır değil."); return }
        if (workers.isNotEmpty() || _pendingMeta.value > 0) {
            if (manual) say("Aktif indirme veya çözümleme varken güncellenemez. Önce bitmesini bekleyin ya da durdurun.")
            return
        }
        if (_updating.value) return
        _updating.value = true
        try {
            val channel = if (config.state.value.useNightly) YoutubeDL.UpdateChannel.NIGHTLY
            else YoutubeDL.UpdateChannel.STABLE
            val status = YoutubeDL.getInstance().updateYoutubeDL(app, channel)
            config.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            refreshVersion()
            if (manual) {
                say(if (status == YoutubeDL.UpdateStatus.DONE) "yt-dlp güncellendi: ${_ytdlpVersion.value}"
                else "yt-dlp zaten güncel: ${_ytdlpVersion.value}")
            }
        } catch (t: Throwable) {
            if (manual) say("Güncelleme başarısız: ${cleanError(t.message ?: t.toString())}")
        } finally {
            _updating.value = false
            schedule()
        }
    }

    // ---------------------------------------------------------------- bağlantı çözümleme

    /** Bağlantıyı arka planda çözümler (başlık, süre, boyut, playlist) ve kuyruğa ekler. */
    fun resolveAndAdd(url: String, isPlaylist: Boolean, format: MediaFormat, quality: String, generic: Boolean = false) {
        _pendingMeta.update { it + 1 }
        scope.launch(Dispatchers.IO) {
            try {
                val p = _phase.first { it !is Phase.Init }
                if (p is Phase.Failed) throw IllegalStateException(p.message)
                _updating.first { !it }
                metaGate.withPermit {
                    addProbed(probe(url, isPlaylist, format, quality, generic), format, quality, generic)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                say("Bağlantı çözümlenemedi:\n$url\n${describeError(t.message ?: t.toString())}")
            } finally {
                _pendingMeta.update { it - 1 }
            }
        }
    }

    // ------------------------------------------------------------------------ çerezler

    /** Giriş gerektiren siteler (Instagram vb.) için kullanıcının verdiği cookies.txt. */
    val cookiesFile = File(app.filesDir, "cerezler.txt")
    private val _hasCookies = MutableStateFlow(cookiesFile.isFile && cookiesFile.length() > 0)
    val hasCookies: StateFlow<Boolean> = _hasCookies.asStateFlow()

    fun importCookies(uri: android.net.Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("Dosya okunamadı.")
                if (!text.contains('\t') || text.length < 20) throw IllegalStateException("Geçerli bir cookies.txt (Netscape biçimi) değil.")
                cookiesFile.writeText(text, Charsets.UTF_8)
                _hasCookies.value = true
                say("Çerez dosyası eklendi.")
            } catch (t: Throwable) {
                say("Çerez dosyası eklenemedi: ${t.message}")
            }
        }
    }

    fun removeCookies() {
        runCatching { cookiesFile.delete() }
        _hasCookies.value = false
        say("Çerez dosyası kaldırıldı.")
    }

    private fun cookieOptions(): List<YtOption> =
        if (_hasCookies.value && cookiesFile.isFile) listOf(YtOption("--cookies", cookiesFile.absolutePath)) else emptyList()

    private fun probe(url: String, isPlaylist: Boolean, format: MediaFormat, quality: String, generic: Boolean): Probe {
        val yt = YoutubeDL.getInstance()
        val compat = config.state.value.compatMp4
        fun run(opts: List<YtOption>): String {
            val r = YoutubeDLRequest(url)
            (opts + cookieOptions() + updater.extraOptions(url)).forEach { r.add(it) }
            return yt.execute(r, null, false, null).out
        }
        if (isPlaylist) return MetadataParser.parse(run(YtArgs.probePlaylistOptions()), url)
        return try {
            MetadataParser.parse(run(YtArgs.probeSingleOptions(format, quality, true, compat, generic)), url)
        } catch (e: YoutubeDLException) {
            // Seçilen kalite çözülemediyse boyut tahmini olmadan devam et.
            if (e.message?.contains("format is not available", ignoreCase = true) == true)
                MetadataParser.parse(run(YtArgs.probeSingleOptions(format, quality, false, compat, generic)), url)
            else throw e
        }
    }

    private fun addProbed(probe: Probe, format: MediaFormat, quality: String, generic: Boolean) {
        val known = _items.value.map { Triple(it.url, it.format, it.quality) }.toMutableSet()
        val fresh = ArrayList<DownloadItem>()
        var dup = 0
        val reencodeOn = format == MediaFormat.MP4

        fun make(
            id: String, url: String, title: String, dur: Double, size: Long, streams: Int,
            playlist: String?, thumb: String, site: String, nonCompat: Boolean,
        ) {
            if (!known.add(Triple(url, format, quality))) { dup++; return }
            fresh += DownloadItem(
                id = nextId.getAndIncrement(),
                url = url,
                videoId = if (generic) "" else id,
                title = title, duration = dur,
                format = format, quality = quality, playlist = playlist,
                streams = if (format == MediaFormat.MP3) 1 else streams,
                size = if (format == MediaFormat.MP3) estimateMp3Size(dur, quality) else size,
                status = Status.READY,
                site = site, thumb = thumb,
                reencode = reencodeOn && nonCompat,
            )
        }

        when (probe) {
            is Probe.Video -> probe.meta.let {
                val url = if (generic) it.url else "https://www.youtube.com/watch?v=${it.id}"
                make(it.id, url, it.title, it.duration, it.size, it.streams, null, it.thumb, if (generic) it.site else "YouTube", it.nonCompat)
            }
            is Probe.Playlist -> {
                if (probe.meta.entries.isEmpty()) throw IllegalStateException("Bu bağlantıda indirilebilir video bulunamadı.")
                for (e in probe.meta.entries) {
                    val url = if (generic) e.url else "https://www.youtube.com/watch?v=${e.id}"
                    make(e.id, url, e.title, e.duration, e.size, e.streams, probe.meta.title, e.thumb,
                        if (generic) probe.meta.site else "YouTube", e.nonCompat)
                }
            }
        }
        if (fresh.isNotEmpty()) _items.update { it + fresh }
        say(
            when {
                fresh.isEmpty() -> "Zaten listede: aynı video/biçim tekrar eklenmedi."
                dup > 0 -> "${fresh.size} öğe listeye eklendi ($dup tanesi zaten listedeydi). Ayarları seçip İndirmeye Başla'ya dokun."
                else -> "${fresh.size} öğe listeye eklendi. Ayarları seçip İndirmeye Başla'ya dokun."
            }
        )
        saveQueueNow()
    }

    // -------------------------------------------------------------------------- kuyruk

    private fun find(id: Int): DownloadItem? = _items.value.firstOrNull { it.id == id }

    private fun mutate(id: Int, f: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    /** Eşzamanlı indirme limitine göre bekleyen öğeleri başlatır. */
    @Synchronized
    fun schedule() {
        if (_phase.value != Phase.Ready || _updating.value) return
        val limit = config.state.value.maxConcurrent
        var running = workers.size
        for (item in _items.value) {
            if (running >= limit) break
            if (item.status == Status.WAITING && !workers.containsKey(item.id)) {
                launchJob(item)
                running++
            }
        }
    }

    private fun launchJob(item: DownloadItem) {
        val id = item.id
        val processId = "yt-$id-${System.nanoTime()}"
        workers[id] = processId
        mutate(id) { it.copy(status = Status.DOWNLOADING, percent = 0f, speed = 0, eta = -1, error = "") }
        ensureService()
        scope.launch(Dispatchers.IO) {
            try {
                runDownload(id, processId)
            } catch (t: Throwable) {
                if (id !in stopRequested) setError(id, describeError(t.message ?: t.toString()))
            } finally {
                workers.remove(id)
                stopRequested.remove(id)
                schedule()
            }
        }
    }

    private fun ensureService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (_: Throwable) {
            // Arka plandan başlatma kısıtı: indirme yine de süreç yaşadığı sürece devam eder.
        }
    }

    private fun runDownload(id: Int, processId: String) {
        val item = find(id) ?: return
        if (id in stopRequested) return
        val cfg = config.state.value
        val tmp = File(tempRoot, id.toString()).also { it.mkdirs() }

        val req = YoutubeDLRequest(item.url)
        (YtArgs.downloadOptions(item, tmp.absolutePath, cfg.speedLimitKbps, cfg.compatMp4) + cookieOptions() + updater.extraOptions(item.url)).forEach { req.add(it) }
        val tracker = ProgressTracker(item.size, item.streams)
        var lastEmit = 0L
        val yt = YoutubeDL.getInstance()

        try {
            yt.execute(req, processId, false) { _, _, line ->
                if (id in stopRequested) {
                    yt.destroyProcessById(processId)
                    return@execute
                }
                when (val ev = ProgressParser.parse(line)) {
                    is ProgressEvent.Formats -> tracker.streams = ev.count
                    is ProgressEvent.Download -> {
                        val snap = tracker.update(ev)
                        val now = SystemClock.elapsedRealtime()
                        if (ev.status == "finished" || now - lastEmit >= 250L) {
                            lastEmit = now
                            mutate(id) { cur ->
                                if (cur.status != Status.DOWNLOADING) cur
                                else cur.copy(
                                    percent = snap.percent, speed = snap.speed, eta = snap.eta,
                                    size = if (cur.format == MediaFormat.MP4 && snap.total > 0) snap.total else cur.size,
                                )
                            }
                        }
                    }
                    is ProgressEvent.PostProcess -> if (ev.status == "started") {
                        mutate(id) { cur ->
                            if (!cur.status.isActive) cur
                            else cur.copy(status = Status.CONVERTING, percent = 99.9f, speed = 0, eta = -1)
                        }
                    }
                    null -> Unit
                }
            }
        } catch (_: YoutubeDL.CanceledException) {
            return
        } catch (_: InterruptedException) {
            return
        } catch (e: YoutubeDLException) {
            if (id !in stopRequested) setError(id, describeError(e.message))
            return
        }

        if (id in stopRequested) return
        finish(id, tmp, item)
    }

    private val tempName = Regex("""\.(part|ytdl|temp|tmp)(-Frag\d+)?$|\.f\d+\.[A-Za-z0-9]+$""")

    private fun findOutput(dir: File): File? =
        dir.listFiles()?.filter { it.isFile && !tempName.containsMatchIn(it.name) }?.maxByOrNull { it.length() }

    private fun finish(id: Int, tmp: File, item: DownloadItem) {
        val out = findOutput(tmp)
        if (out == null) { setError(id, "Çıktı dosyası bulunamadı."); return }
        val bytes = out.length()
        exporting.add(id)
        mutate(id) { if (it.status.isActive) it.copy(status = Status.CONVERTING, percent = 99.9f, speed = 0, eta = -1) else it }
        try {
            val saved = Storage.export(app, out, config.state.value, item.playlist)
            tmp.deleteRecursively()
            mutate(id) {
                it.copy(
                    status = Status.DONE, percent = 100f, speed = 0, eta = 0, size = bytes, error = "",
                    savedUri = saved.fileUri.toString(), savedName = saved.name,
                    folderLabel = saved.folderLabel, folderUri = saved.folderUri?.toString().orEmpty(),
                )
            }
            saveQueueNow()
        } catch (t: Throwable) {
            setError(id, describeError(t.message ?: t.toString()))
        } finally {
            exporting.remove(id)
        }
    }

    private fun setError(id: Int, msg: String) {
        mutate(id) { it.copy(status = Status.ERROR, error = msg, speed = 0, eta = -1) }
        find(id)?.let { say("Hata: ${it.title.take(40)}") }
        saveQueueNow()
    }

    private fun killProcess(id: Int) {
        workers[id]?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
    }

    // ------------------------------------------------------------------ kullanıcı eylemleri

    fun stop(id: Int) {
        val it = find(id) ?: return
        if (id in exporting) return
        when (it.status) {
            Status.DOWNLOADING, Status.CONVERTING -> {
                stopRequested.add(id)
                killProcess(id)
                mutate(id) { c -> c.copy(status = Status.STOPPED, speed = 0, eta = -1) }
            }
            Status.WAITING -> mutate(id) { c -> c.copy(status = Status.STOPPED) }
            else -> Unit
        }
        saveQueueNow()
    }

    fun resume(id: Int) {
        val it = find(id) ?: return
        if (it.status == Status.STOPPED || it.status == Status.ERROR) {
            mutate(id) { c -> c.copy(status = Status.WAITING, error = "") }
            schedule()
        }
    }

    fun remove(id: Int) {
        val it = find(id) ?: return
        if (it.status.isActive && id !in exporting) {
            stopRequested.add(id)
            killProcess(id)
        }
        _items.update { list -> list.filter { x -> x.id != id } }
        scope.launch(Dispatchers.IO) {
            delay(1500)       // süreç kapanırken dosyalara dokunma
            File(tempRoot, id.toString()).deleteRecursively()
        }
        saveQueueNow()
        schedule()
    }

    fun startAll() {
        _items.update { l ->
            l.map { if (it.status == Status.STOPPED || it.status == Status.ERROR || it.status == Status.READY) it.copy(status = Status.WAITING, error = "") else it }
        }
        schedule()
    }

    /** Listedeki "Hazır" öğelere ana ekrandaki biçim/kalite seçimini uygular (indirme başlamaz). */
    fun applySelection(format: MediaFormat, quality: String) {
        _items.update { l ->
            l.map {
                if (it.status != Status.READY || (it.format == format && it.quality == quality)) it
                else it.copy(
                    format = format, quality = quality,
                    size = if (format == MediaFormat.MP3) estimateMp3Size(it.duration, quality) else 0L,
                    streams = if (format == MediaFormat.MP3) 1 else 2,
                )
            }
        }
    }

    /** "İndirmeye Başla": hazır öğeleri kuyruğa alır (eşzamanlı limit schedule() ile korunur). */
    fun startReady(): Int {
        var n = 0
        _items.update { l -> l.map { if (it.status == Status.READY) { n++; it.copy(status = Status.WAITING, error = "") } else it } }
        saveQueueNow()
        schedule()
        return n
    }

    fun startItem(id: Int) {
        val it = find(id) ?: return
        if (it.status == Status.READY) {
            mutate(id) { c -> c.copy(status = Status.WAITING, error = "") }
            saveQueueNow()
            schedule()
        }
    }

    fun stopAll() {
        _items.value.filter { it.status.isActive || it.status == Status.WAITING }.forEach { stop(it.id) }
    }

    fun retryFailed() {
        _items.update { l -> l.map { if (it.status == Status.ERROR) it.copy(status = Status.WAITING, error = "") else it } }
        schedule()
    }

    fun clearDone() {
        _items.value.filter { it.status == Status.DONE }.forEach { remove(it.id) }
    }

    // --------------------------------------------------------------------- ayar değişimi

    private suspend fun watchConfig() {
        var prev = config.state.value
        config.state.drop(1).collect { c ->
            if (c.speedLimitKbps != prev.speedLimitKbps) applyRateLimitNow()
            if (c.maxConcurrent != prev.maxConcurrent) schedule()
            prev = c
        }
    }

    /**
     * yt-dlp çalışırken hız sınırı değiştirilemez; aktif indirmeler yeni limitle kaldıkları yerden
     * (.part dosyası) yeniden başlatılır. Dönüştürme/kaydetme aşamasındakilere dokunulmaz.
     */
    private fun applyRateLimitNow() {
        val targets = _items.value.filter { it.status == Status.DOWNLOADING && it.id !in exporting }.map { it.id }
        if (targets.isEmpty()) return
        for (id in targets) {
            stopRequested.add(id)
            killProcess(id)
            mutate(id) { it.copy(status = Status.WAITING, speed = 0, eta = -1) }
        }
        say("Hız limiti değişti: aktif indirmeler yeni limitle kaldığı yerden sürdürülüyor.")
    }

    // --------------------------------------------------------------------- kalıcı liste

    private fun restoreQueue() {
        try {
            if (queueFile.exists()) {
                val list = QueueStore.fromJson(queueFile.readText(Charsets.UTF_8))
                _items.value = list
                nextId.set((list.maxOfOrNull { it.id } ?: 0) + 1)
            }
        } catch (_: Exception) {
            // bozuk dosya: boş listeyle devam
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun persistLoop() {
        _items.sample(3000).collect { writeQueue(it) }
    }

    private fun saveQueueNow() {
        val snapshot = _items.value
        scope.launch(Dispatchers.IO) { writeQueue(snapshot) }
    }

    @Synchronized
    private fun writeQueue(items: List<DownloadItem>) {
        try {
            val tmp = File(queueFile.path + ".tmp")
            tmp.writeText(QueueStore.toJson(items), Charsets.UTF_8)
            if (!tmp.renameTo(queueFile)) { queueFile.writeText(QueueStore.toJson(items), Charsets.UTF_8); tmp.delete() }
        } catch (_: Exception) {
        }
    }
}
