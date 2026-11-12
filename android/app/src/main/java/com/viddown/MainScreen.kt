@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.viddown

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class PendingChoice(val parsed: ParsedUrl, val format: MediaFormat, val quality: String)

/** Kök: ekranlar arası geçiş, bağlantı ekleme akışı, snackbar ve bildirim izni. */
@Composable
fun AppRoot(engine: DownloadEngine, incoming: MutableStateFlow<String?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by engine.config.state.collectAsStateWithLifecycle()
    val items by engine.items.collectAsStateWithLifecycle()
    val summary by engine.summary.collectAsStateWithLifecycle(initialValue = Summary())
    val phase by engine.phase.collectAsStateWithLifecycle()
    val pending by engine.pendingMeta.collectAsStateWithLifecycle()
    val updating by engine.updating.collectAsStateWithLifecycle()
    val ytVersion by engine.ytdlpVersion.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var screen by rememberSaveable { mutableIntStateOf(0) }          // 0 = ana, 1 = ayarlar
    var text by rememberSaveable { mutableStateOf("") }
    var formatName by rememberSaveable { mutableStateOf(cfg.defaultFormat.name) }
    var quality by rememberSaveable { mutableStateOf(cfg.qualityFor(cfg.defaultFormat)) }
    val choices = remember { mutableStateListOf<PendingChoice>() }

    // Ayarlarda varsayılan değişirse üstteki seçimler de güncellenir (masaüstündeki gibi).
    LaunchedEffect(cfg.defaultFormat, cfg.mp3Quality, cfg.mp4Quality) {
        formatName = cfg.defaultFormat.name
        quality = cfg.qualityFor(cfg.defaultFormat)
    }

    // Hazır öğeler, üstte seçili biçim/kaliteyle güncellenir (indirme başlamaz).
    LaunchedEffect(formatName, quality) { engine.applySelection(MediaFormat.parse(formatName), quality) }

    BackHandler(enabled = screen == 1) { screen = 0 }

    // Bildirim izni (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Motor mesajları -> snackbar
    LaunchedEffect(Unit) {
        engine.messages.collect { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long) }
    }

    fun addFromText(raw: String) {
        val urls = extractUrls(raw)
        if (urls.isEmpty()) {
            scope.launch { snackbar.showSnackbar("Geçerli bir video bağlantısı bulunamadı.") }
            return
        }
        val fmt = MediaFormat.parse(formatName)
        val invalid = ArrayList<String>()
        for (u in urls) {
            val p = classifyUrl(u)
            when (p.kind) {
                UrlKind.INVALID -> invalid += u
                UrlKind.VIDEO -> engine.resolveAndAdd(p.videoUrl(), false, fmt, quality)
                UrlKind.PLAYLIST -> engine.resolveAndAdd(p.playlistUrl(), true, fmt, quality)
                UrlKind.BOTH -> choices.add(PendingChoice(p, fmt, quality))
                UrlKind.OTHER -> engine.resolveAndAdd(u, false, fmt, quality, generic = true)
            }
        }
        if (invalid.isNotEmpty()) {
            scope.launch {
                snackbar.showSnackbar("Desteklenmeyen bağlantı: ${invalid.first().take(60)}" +
                    if (invalid.size > 1) " (+${invalid.size - 1})" else "")
            }
        }
    }

    // Paylaş / "Bununla aç" ile gelen bağlantılar
    val latestAdd by rememberUpdatedState(::addFromText)
    LaunchedEffect(Unit) {
        incoming.collect { t ->
            if (t != null) { latestAdd(t); incoming.value = null }
        }
    }

    // "Liste mi, video mu?" sorusu
    choices.firstOrNull()?.let { c ->
        AlertDialog(
            onDismissRequest = { choices.removeAt(0) },
            title = { Text("Oynatma listesi algılandı") },
            text = { Text("Bu bağlantı bir oynatma listesine ait bir video içeriyor.\nNe eklensin?") },
            confirmButton = {
                TextButton(onClick = {
                    engine.resolveAndAdd(c.parsed.playlistUrl(), true, c.format, c.quality); choices.removeAt(0)
                }) { Text("Tüm Listeyi Ekle") }
            },
            dismissButton = {
                TextButton(onClick = {
                    engine.resolveAndAdd(c.parsed.videoUrl(), false, c.format, c.quality); choices.removeAt(0)
                }) { Text("Sadece Bu Video") }
            },
        )
    }

    if (screen == 1) {
        SettingsScreen(engine, cfg, ytVersion, updating, snackbar, onBack = { screen = 0 })
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("ViDDoWN", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { screen = 1 }) { Icon(Icons.Default.Settings, contentDescription = "Ayarlar") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menü") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Tümünü Başlat (durdurulan/hazır)") }, onClick = { menuOpen = false; engine.startAll() })
                            DropdownMenuItem(text = { Text("Tümünü Durdur") }, onClick = { menuOpen = false; engine.stopAll() })
                            DropdownMenuItem(text = { Text("Hataları Yeniden Dene") }, onClick = { menuOpen = false; engine.retryFailed() })
                            DropdownMenuItem(text = { Text("Tamamlananları Temizle") }, onClick = { menuOpen = false; engine.clearDone() })
                        }
                    }
                },
            )
        },
        bottomBar = { SummaryBar(summary) },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            PhaseBanner(phase, pending, updating, onRetry = { engine.retryInit() })

            InputCard(
                text = text,
                onText = { text = it },
                format = MediaFormat.parse(formatName),
                quality = quality,
                readyCount = summary.ready,
                compat = cfg.compatMp4,
                onCompat = { v -> engine.config.update { it.copy(compatMp4 = v) } },
                onStart = {
                    val n = engine.startReady()
                    if (n == 0) scope.launch { snackbar.showSnackbar("Başlatılacak hazır öğe yok. Önce bağlantı ekleyin.") }
                },
                onFormat = { f -> formatName = f.name; quality = cfg.qualityFor(f) },
                onQuality = { quality = it },
                onAdd = { if (text.isNotBlank()) { addFromText(text); text = "" } },
                onPasteAdd = {
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cb.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    if (clip.isBlank()) scope.launch { snackbar.showSnackbar("Pano boş.") } else addFromText(clip)
                },
                onPaste = {
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cb.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    if (clip.isNotBlank()) text = clip
                },
            )

            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Henüz indirme yok.\nYouTube, X, Instagram, TikTok vb. bir bağlantı ekleyin,\n" +
                            "biçimi seçin ve İndirmeye Başla'ya dokunun\n(diğer uygulamalardan Paylaş > ViDDoWN de olur).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        DownloadCard(
                            item = item,
                            onStart = { engine.startItem(item.id) },
                            onStop = { engine.stop(item.id) },
                            onResume = { engine.resume(item.id) },
                            onOpen = {
                                if (!Storage.openFile(ctx, item))
                                    scope.launch { snackbar.showSnackbar("Dosya açılamadı (taşınmış veya silinmiş olabilir).") }
                            },
                            onFolder = {
                                if (!Storage.openFolder(ctx, item, cfg))
                                    scope.launch { snackbar.showSnackbar("Klasör açılamadı.") }
                            },
                            onCopy = {
                                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(android.content.ClipData.newPlainText("bağlantı", item.url))
                                scope.launch { snackbar.showSnackbar("Bağlantı kopyalandı.") }
                            },
                            onRemove = { engine.remove(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseBanner(phase: DownloadEngine.Phase, pending: Int, updating: Boolean, onRetry: () -> Unit) {
    val msg: String? = when {
        phase is DownloadEngine.Phase.Init -> "Motor hazırlanıyor… (ilk açılışta birkaç saniye sürebilir)"
        updating -> "yt-dlp güncelleniyor…"
        pending > 0 -> "Bağlantılar çözümleniyor: $pending"
        else -> null
    }
    if (phase is DownloadEngine.Phase.Failed) {
        Surface(color = MaterialTheme.colorScheme.errorContainerSafe(), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Motor başlatılamadı: ${phase.message}", Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = onRetry) { Text("Tekrar Dene") }
            }
        }
    } else if (msg != null) {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            Text(msg, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun androidx.compose.material3.ColorScheme.errorContainerSafe() = androidx.compose.ui.graphics.Color(0xFF4A1F1F)

@Composable
private fun InputCard(
    text: String,
    onText: (String) -> Unit,
    format: MediaFormat,
    quality: String,
    readyCount: Int,
    compat: Boolean,
    onCompat: (Boolean) -> Unit,
    onStart: () -> Unit,
    onFormat: (MediaFormat) -> Unit,
    onQuality: (String) -> Unit,
    onAdd: () -> Unit,
    onPasteAdd: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Video bağlantısı: YouTube, X, Instagram, TikTok… (birden fazla olabilir)") },
            maxLines = 3,
            trailingIcon = {
                if (text.isEmpty()) TextButton(onClick = onPaste) { Text("Yapıştır") }
                else IconButton(onClick = { onText("") }) { Icon(Icons.Default.Close, contentDescription = "Temizle") }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd() }),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.width(150.dp)) {
                MediaFormat.entries.forEachIndexed { idx, f ->
                    SegmentedButton(
                        selected = f == format,
                        onClick = { onFormat(f) },
                        shape = SegmentedButtonDefaults.itemShape(idx, MediaFormat.entries.size),
                    ) { Text(f.name) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                qualitiesFor(format).forEach { q ->
                    FilterChip(selected = q == quality, onClick = { onQuality(q) }, label = { Text(q) })
                }
            }
        }
        if (format == MediaFormat.MP4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = compat, onCheckedChange = onCompat)
                Text("  Her cihazda oynat (H.264 + AAC) — TV, medya oynatıcı", fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f)) { Text("Listeye Ekle") }
            OutlinedButton(onClick = onPasteAdd, modifier = Modifier.weight(1f)) { Text("Panodan Ekle") }
        }
        Button(onClick = onStart, enabled = readyCount > 0, modifier = Modifier.fillMaxWidth()) {
            Text(if (readyCount > 0) "İndirmeye Başla ($readyCount)" else "İndirmeye Başla")
        }
    }
}

@Composable
private fun SummaryBar(s: Summary) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Hazır ${s.ready}  ·  Aktif ${s.active}  ·  Bekleyen ${s.waiting}  ·  Biten ${s.done}  ·  Hata ${s.error}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Toplam hız: ${fmtSpeed(s.speed)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onFolder: () -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val color = statusColor(item.status)

    Box {
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true }),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    val thumbModel = item.thumbOrNull()
                    if (thumbModel.isNotEmpty()) {
                        AsyncImage(
                            model = thumbModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "#${item.id}  ·  ${if (item.site.isNotEmpty()) item.site + "  ·  " else ""}${item.format.name} / ${item.quality}  ·  ${fmtSize(item.size)}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.playlist?.let {
                            Text("Liste: $it", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { (item.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = color,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(fmtPercent(item.percent), Modifier.width(54.dp), fontSize = 12.sp, textAlign = TextAlign.End)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.status.label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (item.status == Status.DOWNLOADING) {
                        Text("Hız ${fmtSpeed(item.speed)}  ·  ETA ${fmtEta(item.eta)}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (item.status == Status.DONE && item.folderLabel.isNotEmpty()) {
                        Text(item.folderLabel, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(190.dp), textAlign = TextAlign.End)
                    }
                }
                if (item.status == Status.ERROR && item.error.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(item.error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val pad = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    when (item.status) {
                        Status.READY -> TextButton(onClick = onStart, contentPadding = pad) { Text("Başlat") }
                        Status.DOWNLOADING, Status.CONVERTING, Status.WAITING ->
                            TextButton(onClick = onStop, contentPadding = pad) { Text("Durdur") }
                        Status.STOPPED -> TextButton(onClick = onResume, contentPadding = pad) { Text("Devam Et") }
                        Status.ERROR -> TextButton(onClick = onResume, contentPadding = pad) { Text("Yeniden Dene") }
                        Status.DONE -> TextButton(onClick = onOpen, contentPadding = pad) { Text("Aç") }
                    }
                    TextButton(onClick = onFolder, contentPadding = pad) { Text("Klasör") }
                    TextButton(onClick = onRemove, contentPadding = pad) { Text("Kaldır", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        // Uzun basma: "sağ tık" bağlam menüsünün karşılığı
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Durdur / İptal Et") },
                enabled = item.status.isActive || item.status == Status.WAITING,
                onClick = { menu = false; onStop() },
            )
            DropdownMenuItem(
                text = { Text("Başlat / Devam Et / Yeniden Dene") },
                enabled = item.status == Status.STOPPED || item.status == Status.ERROR || item.status == Status.READY,
                onClick = { menu = false; if (item.status == Status.READY) onStart() else onResume() },
            )
            DropdownMenuItem(text = { Text("Dosyayı Aç") }, enabled = item.status == Status.DONE, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("Klasörde Göster") }, onClick = { menu = false; onFolder() })
            DropdownMenuItem(text = { Text("Bağlantıyı Kopyala") }, onClick = { menu = false; onCopy() })
            DropdownMenuItem(text = { Text("Listeden Kaldır") }, onClick = { menu = false; onRemove() })
        }
    }
}
