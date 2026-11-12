# ViDDoWN - Video Downloader

YouTube, X (Twitter), Instagram, TikTok, Facebook, Reddit, Vimeo ve yt-dlp'nin desteklediği 1000'den fazla siteden video/ses indirme uygulaması. Android (arm64) ve masaüstü (Windows/Linux/macOS) sürümü.

## Özellikler
- Video ve oynatma listesi bağlantıları; birden çok bağlantı aynı anda
- MP3 (128k / 192k / 320k) ve MP4 (En iyi / 1080p / 720p / 480p)
- **Her cihazda oynayan MP4:** varsayılan olarak H.264 + AAC (Android TV, medya oynatıcılar). Uyumsuz kaynaklar indirirken dönüştürülür.
- **Elle başlatma:** bağlantılar "Hazır" olarak eklenir, ayarları seçip "İndirmeye Başla" ile indirilir
- Kuyruk, eşzamanlı indirme sınırı (1-10), hız sınırı (KB/s - MB/s), durdur / devam / kaldır
- Giriş gerektiren siteler için isteğe bağlı `cookies.txt` desteği
- yt-dlp güncelleme (Android'de otomatik)

## Android
`android/` klasörü: Kotlin + Jetpack Compose. yt-dlp, FFmpeg ve Python uygulamanın içinde gelir.
APK: [`releases/ViDDoWN-v1.2.0.apk`](releases/ViDDoWN-v1.2.0.apk) (arm64, Android 10+).

Derleme (JDK 17+, Android SDK):
```
cd android
echo "sdk.dir=/yol/android-sdk" > local.properties
./gradlew assembleRelease
```
İmzalı çıktı için `keystore.properties.example` dosyasını `keystore.properties` olarak kopyalayıp doldurun; dosya yoksa APK imzasız çıkar. Anahtar dosyasını repoya eklemeyin ve yedekleyin: güncellemeler aynı anahtarla imzalanmalıdır.

## Masaüstü
`desktop/viddown.py` tek dosyadır: `python viddown.py` (PyQt6 ve yt-dlp eksikse otomatik kurulur; FFmpeg için ilk açılışta indirme önerilir).
Tek dosyalık .exe için Windows'ta `derle.bat`, diğer sistemlerde `python derle.py`.

## Notlar
- YouTube ve diğer siteler sık değişir; indirme başarısız olursa yt-dlp'yi güncelleyin.
- Yalnızca indirme hakkına sahip olduğunuz veya izin verilen içerikler için kullanın; sitelerin kullanım koşullarına uyun.
