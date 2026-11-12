@file:OptIn(ExperimentalMaterial3Api::class)

package com.viddown

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    engine: DownloadEngine,
    cfg: Config,
    ytVersion: String,
    updating: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            engine.config.update { it.copy(saveTreeUri = uri.toString()) }
        }
    }

    val hasCookies by engine.hasCookies.collectAsStateWithLifecycle()
    val upState by engine.updater.state.collectAsStateWithLifecycle()
    val pluginCount by engine.updater.pluginCount.collectAsStateWithLifecycle()
    val remoteMsg by engine.updater.message.collectAsStateWithLifecycle()
    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) engine.importCookies(uri)
    }

    // Hız sınırı düzenleme durumu (Uygula'ya basılana kadar uygulanmaz)
    val useMb = cfg.speedLimitKbps >= 1024 && cfg.speedLimitKbps % 1024 == 0
    var unlimited by remember(cfg.speedLimitKbps) { mutableStateOf(cfg.speedLimitKbps == 0) }
    var mbUnit by remember(cfg.speedLimitKbps) { mutableStateOf(useMb) }
    var limitText by remember(cfg.speedLimitKbps) {
        mutableStateOf(
            when {
                cfg.speedLimitKbps == 0 -> "500"
                useMb -> (cfg.speedLimitKbps / 1024).toString()
                else -> cfg.speedLimitKbps.toString()
            }
        )
    }
    var slider by remember(cfg.maxConcurrent) { mutableFloatStateOf(cfg.maxConcurrent.toFloat()) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // --- Kayıt klasörü
            Section("Kayıt klasörü")
            Text(
                if (cfg.saveTreeUri.isEmpty()) "Download/ViDDoWN (varsayılan)" else Storage.describeFolder(cfg.saveTreeUri),
                fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(null) }) { Text("Klasör Seç") }
                OutlinedButton(
                    onClick = { engine.config.update { it.copy(saveTreeUri = "") } },
                    enabled = cfg.saveTreeUri.isNotEmpty(),
                ) { Text("Varsayılana Dön") }
            }
            HorizontalDivider()

            // --- Varsayılan biçim
            Section("Varsayılan biçim ve kalite")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaFormat.entries.forEach { f ->
                    FilterChip(
                        selected = cfg.defaultFormat == f,
                        onClick = { engine.config.update { it.copy(defaultFormat = f) } },
                        label = { Text(f.name) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cfg.compatMp4, onCheckedChange = { v -> engine.config.update { it.copy(compatMp4 = v) } })
                Text("  Uyumlu MP4: her cihazda oynat (H.264 + AAC)", fontSize = 14.sp)
            }
            Text(
                "Android TV, medya oynatıcı ve eski cihazlarda \"sadece ses var\" sorununu önler. " +
                    "Kaynak uyumsuz bir kodekse (ör. VP9/AV1) indirirken dönüştürülür; bu işlem telefonda yavaş olabilir.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("MP3 kalitesi", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MP3_QUALITIES.forEach { q ->
                    FilterChip(selected = cfg.mp3Quality == q, onClick = { engine.config.update { it.copy(mp3Quality = q) } }, label = { Text(q) })
                }
            }
            Text("MP4 kalitesi", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MP4_QUALITIES.forEach { q ->
                    FilterChip(selected = cfg.mp4Quality == q, onClick = { engine.config.update { it.copy(mp4Quality = q) } }, label = { Text(q) })
                }
            }
            HorizontalDivider()

            // --- Eşzamanlı indirme
            Section("Eşzamanlı indirme sayısı: ${slider.toInt()}")
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { engine.config.update { it.copy(maxConcurrent = slider.toInt()) } },
                valueRange = 1f..10f,
                steps = 8,
            )
            HorizontalDivider()

            // --- Hız sınırı
            Section("Hız sınırı")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = unlimited, onCheckedChange = { unlimited = it })
                Text("  Sınırsız", fontSize = 14.sp)
            }
            if (!unlimited) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { limitText = it.filter(Char::isDigit).take(7) },
                        modifier = Modifier.width(120.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    FilterChip(selected = !mbUnit, onClick = { mbUnit = false }, label = { Text("KB/sn") })
                    FilterChip(selected = mbUnit, onClick = { mbUnit = true }, label = { Text("MB/sn") })
                }
            }
            Button(onClick = {
                val kbps = if (unlimited) 0 else {
                    val v = limitText.toIntOrNull() ?: 0
                    (if (mbUnit) v * 1024L else v.toLong()).coerceIn(0, 10_000_000).toInt()
                }
                engine.config.update { it.copy(speedLimitKbps = kbps) }
            }) { Text("Uygula") }
            Text(
                "Not: Sınır değişince etkin indirmeler yeniden başlatılır ve kaldıkları yerden devam eder.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            // --- Çerezler
            Section("Giriş gerektiren siteler (çerez dosyası)")
            Text(
                if (hasCookies) "cookies.txt ekli." else "Ekli değil. YouTube, X, TikTok, Vimeo vb. çoğu bağlantı çerezsiz çalışır; " +
                    "Instagram, Facebook ve yaşa/üyeliğe bağlı içerikler için oturum açmış tarayıcıdan dışa aktarılan " +
                    "cookies.txt (Netscape biçimi) dosyasını seçin.",
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { cookiePicker.launch(arrayOf("*/*")) }) { Text("cookies.txt Seç") }
                OutlinedButton(onClick = { engine.removeCookies() }, enabled = hasCookies) { Text("Kaldır") }
            }
            HorizontalDivider()

            // --- Uygulama güncellemesi ve eklentiler
            Section("Güncelleme ve eklentiler (GitHub)")
            Text("Sürüm: ${BuildConfig.VERSION_NAME}", fontSize = 14.sp)
            Text(
                when (val s = upState) {
                    is Updater.State.Checking -> "Denetleniyor…"
                    is Updater.State.UpToDate -> "Güncel."
                    is Updater.State.Available -> "Yeni sürüm hazır: ${s.release.versionName}"
                    is Updater.State.Downloading -> "İndiriliyor… %${s.percent}"
                    is Updater.State.Installing -> "Kuruluyor…"
                    is Updater.State.Failed -> s.message
                    else -> "Son denetim sonucu yok."
                },
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { engine.updater.check(manual = true) }) { Text("Güncellemeyi Denetle") }
                if (upState is Updater.State.Available)
                    Button(onClick = { engine.updater.downloadAndInstall() }) { Text("Güncelle") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cfg.autoUpdateApp, onCheckedChange = { v -> engine.config.update { it.copy(autoUpdateApp = v) } })
                Text("  Açılışta otomatik denetle (6 saatte bir)", fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cfg.remoteExtensions, onCheckedChange = { v -> engine.config.update { it.copy(remoteExtensions = v) } })
                Text("  GitHub'dan eklenti ve site ön ayarlarını uygula", fontSize = 14.sp)
            }
            Text(
                "Yüklü eklenti: $pluginCount. Eklentiler yeni site desteği veya düzeltmeler getirir; yalnızca resmi depodan, " +
                    "SHA-256 doğrulamasıyla alınır.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (remoteMsg.isNotBlank()) Text(remoteMsg, fontSize = 13.sp)
            HorizontalDivider()

            // --- FFmpeg
            Section("FFmpeg")
            Text("Uygulamayla birlikte gelir (ek kurulum gerekmez).", fontSize = 14.sp)
            HorizontalDivider()

            // --- yt-dlp
            Section("yt-dlp")
            Text("Sürüm: ${ytVersion.ifEmpty { "—" }}", fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cfg.autoUpdateYtdlp, onCheckedChange = { v -> engine.config.update { it.copy(autoUpdateYtdlp = v) } })
                Text("  Otomatik güncelle (12 saatte bir)", fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cfg.useNightly, onCheckedChange = { v -> engine.config.update { it.copy(useNightly = v) } })
                Text("  Nightly kanalı kullan", fontSize = 14.sp)
            }
            Button(onClick = { engine.updateYtdlp() }, enabled = !updating) {
                Text(if (updating) "Güncelleniyor…" else "Şimdi Güncelle")
            }
            Text(
                "YouTube sık değişir; indirme başarısız olursa önce yt-dlp'yi güncelleyin.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(" ")
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp))
}
