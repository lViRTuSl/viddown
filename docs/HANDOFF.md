# ViDDoWN - Video Downloader — Devir Teslim Belgesi

Son güncelleme: 2026-11-19 · Android sürümü: 1.3.1 (versionCode 5) · Depo: https://github.com/lViRTuSl/viddown (herkese açık)
Sahip/kullanıcı: lViRTuSl (Türkçe konuşur). Hedef cihaz: Pixel 9 Pro XL (arm64, Android 15/16).

## 1. Ürün
Video/ses indirici: YouTube (video + playlist), X, Instagram, TikTok, Facebook, Reddit, Vimeo vb. (yt-dlp'nin desteklediği siteler).
Özellikler: MP3 (128k/192k/320k), MP4 (Best/1080p/720p/480p), kuyruk, eşzamanlı indirme 1-10, hız sınırı (KB/s-MB/s), durdur/devam/kaldır, ayarlar, küçük resimler, cookies.txt desteği, yt-dlp güncelleme.
Kullanıcı istekleri (karar kaynağı):
1. Android Android TV/medya oynatıcılarda "sadece ses var" sorunu → **MP4 varsayılan H.264+AAC** ("Her cihazda oynat" anahtarı; kaynak uyumsuzsa FFmpeg ile libx264/aac'a dönüştürülür).
2. Link yapıştırınca otomatik başlamasın → **"Hazır" durumu + "İndirmeye Başla" düğmesi**; format/kalite seçimi hazır öğelere canlı uygulanır.
3. YouTube dışı siteler → genel URL kabulü (`classifyUrl`), site adı/küçük resim, birden çok videolu gönderi → liste.
4. Proje adı **"ViDDoWN - Video Downloader"**; eski ad hiçbir yerde geçmemeli (kod, dosya adı, APK dahil).
5. Android uygulaması GitHub'dan **kendini güncellesin**, uzaktan **eklenti/ön ayar** alsın; **anahtar gerekmeden**, ayarları bozmadan üstüne kurulsun. Yeni sürümleri asistan derleyip GitHub'a yükler.

## 2. Depo yapısı
```
AGENTS.md                  asistan giriş noktası
README.md                  kullanıcı belgesi
update.json                Android güncelleme/eklenti manifesti (uygulama bunu okur)
releases/ViDDoWN-vX.apk    güncel APK (eski APK'lar yayın betiğiyle silinir)
tools/yayinla.py           APK + update.json üretir (sha256, eklenti ekleme)
tools/surum_yayinla.sh     tek komut: sürüm artır → test → imzalı derle → yayınla → commit/push
desktop/viddown.py         masaüstü uygulaması (tek dosya, 2039 satır)
desktop/derle.py, derle.bat PyInstaller ile tek dosya .exe
android/                   Gradle projesi (Kotlin 2.2.21, AGP 8.13.2, Gradle 8.14.3, JDK 17+, compileSdk 36, minSdk 29, targetSdk 35, arm64-v8a)
android/keystore/          sabit imza anahtarı + signing.properties (bilerek depoda)
docs/HANDOFF.md            bu belge
```
Android kaynakları (`android/app/src/main/java/com/viddown/`):
```
App.kt (14 satır)
Config.kt (189 satır)
DownloadService.kt (163 satır)
Engine.kt (587 satır)
MainActivity.kt (42 satır)
MainScreen.kt (541 satır)
Metadata.kt (160 satır)
Models.kt (94 satır)
Progress.kt (121 satır)
Remote.kt (92 satır)
SettingsScreen.kt (294 satır)
Storage.kt (155 satır)
Theme.kt (48 satır)
Updater.kt (337 satır)
UrlParser.kt (97 satır)
Util.kt (91 satır)
YtArgs.kt (99 satır)
```

## 3. Android mimarisi
- **Motor:** `io.github.junkfood02.youtubedl-android` 0.18.1 (`library` + `ffmpeg`): yt-dlp, Python, QuickJS, ffmpeg/ffprobe gömülü. `YoutubeDL.execute` bloklayıcıdır; callback yalnızca stdout satırı verir.
- **DownloadEngine (Engine.kt):** Application kapsamlı tek örnek; StateFlow'lar: items, summary, phase, pendingMeta, updating, ytdlpVersion, messages; `updater`, `hasCookies`. Akış: `resolveAndAdd` (metadata, READY ekler) → `startReady/startItem` (WAITING) → `schedule()` eşzamanlılık sınırıyla `launchJob` → `runDownload` (geçici dizin) → `finish` (Storage.export).
- **Durumlar:** READY, WAITING, DOWNLOADING, CONVERTING, DONE, ERROR, STOPPED. Kuyruk `kuyruk.json`'a yazılır; yeniden açılışta bitmemişler STOPPED, READY aynen kalır.
- **İlerleme:** `--newline` + `--progress-template` (`[ytprog]|…`, `[ytpp]|…`); `ProgressTracker` video+ses parçalarını birleştirir.
- **Hız sınırı:** `--limit-rate` süreç başına, çalışırken değişmez → değişince aktifler öldürülüp WAITING'e alınır (.part'tan sürer).
- **Depolama:** yt-dlp uygulamaya özel geçici dizine (`getExternalFilesDir/gecici/<id>`) yazar; bitince MediaStore `Download/ViDDoWN[/liste]` veya kullanıcının seçtiği SAF klasörüne kopyalanır (izin gerekmez).
- **Arka plan:** DownloadService (dataSync ön plan servisi, wake lock, "Tümünü Durdur" eylemi, Android 15 `onTimeout`).
- **MP4 uyumluluğu (YtArgs.kt):** seçici önce `bv*[vcodec~='^(avc|h264)']…+ba[acodec~='^(mp4a|aac)']`, sonra serbest yedekler. Metadata'da `requested_formats` codec'leri uyumsuzsa `DownloadItem.reencode` → `--merge-output-format mkv --recode-video mp4 --postprocessor-args "VideoConvertor:-c:v libx264 …-c:a aac…"`; uyumluysa `--remux-video mp4`. Not: YouTube playlist girdileri (flat) için reencode bayrağı bilinmez (false).
- **Çoklu site:** `classifyUrl`; genel sitelerde `--playlist-end 100`, `--no-playlist` yok; öğe URL'si olarak yapıştırılan/girdi URL'si saklanır; yinelenme anahtarı (url, format, kalite).
- **Çerezler:** Ayarlar'dan seçilen cookies.txt `filesDir/cerezler.txt`'e kopyalanır, `--cookies` olarak eklenir. Hata ipuçları Util.kt `describeError`.
- **Arayüz:** Compose Material3, sabit koyu tema, dikey kilit, fontScale 1. Ana ekran: bağlantı alanı, MP3/MP4 + kalite, "Her cihazda oynat", Listeye Ekle / Panodan Ekle, **İndirmeye Başla (N)**, kartlar (uzun basış menüsü), alt özet çubuğu, güncelleme bandı. Paylaş (SEND text/plain) ve VIEW (youtube, x, instagram, tiktok, facebook, vimeo, reddit) intent'leri → READY olarak eklenir.
- **Config (Config.kt):** `ayarlar.json`; yeni alan eklerken `optX(..., default)` ile geriye uyumlu tut (güncellemede ayarlar korunur).

## 4. Kendini güncelleme ve eklentiler (Updater.kt, Remote.kt)
- Manifest: `https://raw.githubusercontent.com/lViRTuSl/viddown/main/update.json`: `app{versionCode,versionName,apkUrl,sha256,notes}`, `plugins[{name,url,sha256}]`, `presets[{hostRegex,options[[opt,val]]}]`, `message`.
- Açılışta ≤6 saatte bir denetim. Yeni `versionCode` varsa APK indirilir, **SHA-256 doğrulanır**, PackageInstaller oturumuyla kurulur (`REQUEST_INSTALL_PACKAGES`, ilk seferde "bilinmeyen uygulamalar" izni). İndirme yokken ve izin varsa otomatik; aksi halde UI'da "Güncelle". Kullanıcı onayı gerekirse InstallReceiver bildirim bırakır.
- Eklentiler: yt-dlp `.py` eklentileri `filesDir/eklentiler/<ad>/yt_dlp_plugins/extractor/<ad>.py` olarak yazılır, `--plugin-dirs` ile verilir (yerelde yt-dlp ile doğrulandı). Ön ayarlarda yalnızca izinli seçenekler: `--extractor-args --user-agent --add-header --referer --sleep-requests --sleep-interval --max-sleep-interval --retries`.
- **İmza:** Android, aynı anahtar olmadan güncellemeyi reddeder. Anahtar `android/keystore/viddown.jks` (alias `viddown`), bilgiler `signing.properties`. **Asla değiştirme/yenileme** (tüm kurulumlar kırılır). Depo herkese açık olduğu için anahtar da açık — bilinçli kullanıcı kararı (kişisel uygulama; alternatif: özel depo + GitHub Actions secret).
- Sürüm numarası: `versionCode` her yayında artar (betik hem yerel hem update.json değerinin büyüğünü +1 alır).

## 5. Derleme ve yayın
Gerekenler: JDK 17+, Android SDK (platform 36, build-tools 35.0.0), Gradle 8.14.3 (`./gradlew` var; ağ sınırlıysa Maven Central yerine Google mirror gerekebilir), `android/local.properties` → `sdk.dir=…`.
```
cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease     # APK: app/build/outputs/apk/release/app-release.apk
tools/surum_yayinla.sh 1.4.0 "Yenilikler"                                   # sürüm artır, derle, update.json+releases güncelle, commit, push
```
Doğrulama: `apksigner verify --print-certs` çıktısındaki SHA-256 `6e750a8ade24dcf85adbbb9eccfd2d9a80af1aa41f8d423358e7f0e883e26bdd` olmalı; GitHub raw'daki APK'nın sha256'sı update.json ile aynı olmalı.
Sınırlar: Releases API'si önceki oturumda kullanılamadı → APK depoda `releases/` içinde (her sürüm geçmişi ~77 MB büyütür; ileride GitHub Releases'e geçilip `apkUrl` değiştirilebilir). Emülatör yok: arayüz/indirme cihazda hiç test edilmedi, yalnızca derleme + JVM birim testleri (`BasicTests.kt`, `RemoteTests`).
Testler: UrlParser, classifyUrl, Util, Config/QueueStore (READY dahil), YtArgs (seçici, recode/remux), Metadata (nonCompat, site), Remote (manifest, güvenlik, preset eşleşme).

## 6. Masaüstü (desktop/viddown.py)
PyQt6 + yt-dlp, tek dosya; eksik paketleri açılışta pip ile kurar (dondurulmuş exe'de kurmaz). FFmpeg yoksa Windows'ta indirmeyi önerir. Android ile aynı özellikler: uyumlu MP4 (FFmpegVideoConvertor/Remuxer + `postprocessor_args`), Hazır durumu + "İndirmeye Başla", genel site desteği, "Site" sütunu, cookies.txt (ayarlar), hata ipuçları. Ayar dizini `%APPDATA%/ViDDoWN`. Masaüstü kendini güncellemez (yalnızca yt-dlp düğmesi; exe'de devre dışı). Testler önceki oturumda yerel HTTP sunucusu ve offscreen Qt ile yapıldı (VP9+Opus → H.264+AAC dönüşümü doğrulandı); test betikleri depoda yok.

## 7. Bilinen sınırlar / riskler
- Gerçek YouTube/X/Instagram indirmeleri hiç test edilmedi (sandbox ağı kısıtlı). Instagram/Facebook çoğu zaman cookies.txt ister.
- Dönüştürme (recode) telefonda yavaş; "Dönüştürülüyor" sırasında yüzde 99.9'da bekler (ffmpeg ilerlemesi okunmuyor).
- YouTube playlist girdileri için H.264 olmayan nadir videolar sessizce VP9 kalabilir (reencode bayrağı bilinmez). Olası çözüm: indirme sonrası ffprobe ile codec kontrolü.
- Hız sınırı değişimi aktif indirmeleri yeniden başlatır.
- Uzaktan eklenti = depodan Python kodu çalıştırma; yalnızca güvenilen kod, anahtar/depo erişimi korunmalı. Ayarlardan kapatılabilir.
- targetSdk 35'te; lint yalnızca sürüm güncelleme uyarıları veriyor.

## 8. Fikirler / yapılacaklar
Releases'e geçiş, indirme sonrası codec doğrulama, recode ilerlemesi, altyazı/küçük resim gömme, x86_64 ABI (ChromeOS), masaüstü otomatik güncelleme, GitHub Actions ile otomatik derleme (secret gerekir), uygulama ikonu/ekran görüntüleri.

## 9. Çalışma tercihleri (kullanıcı)
Türkçe konuş; kısa ve öz yanıt, az adım/araç çağrısı; dosyaların tamamını yeniden yazma, yalnızca değişeni ver; belirsizse tek kısa soru sor; kullanıcı istemeden riskli/kapsam dışı değişiklik yapma; ajan ekibi başlatma. Yeni sürümleri kullanıcı istediğinde asistan derleyip `tools/surum_yayinla.sh` ile yayınlar.

## 10. Yeni oturumda hızlı başlangıç
1. `git clone https://github.com/lViRTuSl/viddown` ve bu belgeyi oku.
2. SDK/JDK kur, `android/local.properties` yaz.
3. `./gradlew :app:testDebugUnitTest` ile ortamı doğrula.
4. Değişiklik → test → `tools/surum_yayinla.sh <sürüm> "<not>"`.
