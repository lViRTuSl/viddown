# ViDDoWN - Video Downloader — yapay zeka asistanı giriş noktası

Bu depoyu devralan asistan: önce **docs/HANDOFF.md** dosyasını baştan sona oku (mimari, kararlar, derleme/yayın, bilinen sınırlar, yapılacaklar).

Kısa özet
- `android/`: Kotlin + Jetpack Compose uygulaması (youtubedl-android: yt-dlp + FFmpeg + Python gömülü). Paket `com.viddown.app`.
- `desktop/viddown.py`: tek dosyalık PyQt6 + yt-dlp masaüstü uygulaması; `derle.py`/`derle.bat` ile .exe.
- `update.json` + `releases/`: Android uygulamasının kendini güncellediği GitHub tabanlı kanal. Her sürümde `tools/surum_yayinla.sh` kullan.
- Kullanıcı Türkçe konuşur; arayüz metinleri Türkçe. Kısa yanıt, az adım, onaysız riskli değişiklik yok.
- İmza anahtarı bilerek depodadır (`android/keystore/`); değiştirme: değişirse telefonlardaki kurulumlar güncellenemez.
