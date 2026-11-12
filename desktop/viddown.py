#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ViDDoWN - Video Downloader  -  Tek dosyalık masaüstü uygulaması
=====================================================
Çalıştırmak için:   python viddown.py

* Ek kurulum GEREKMEZ: PyQt6 ve yt-dlp eksikse ilk açılışta otomatik kurulur.
* FFmpeg eksikse (MP3 dönüştürme / video+ses birleştirme için) uygulama
  ilk açılışta otomatik indirmeyi teklif eder (Windows: ffmpeg.exe+ffprobe.exe,
  Linux/macOS: imageio-ffmpeg paketi üzerinden).
* Mimari (bu dosyanın içindeki bölümler):
    1) Önyükleme (bootstrap) ve yardımcılar
    2) ConfigManager      -> JSON ayar yönetimi         (config.py karşılığı)
    3) DownloadWorker     -> yt-dlp indirme motoru      (download_worker.py karşılığı)
    4) SettingsDialog     -> Ayarlar penceresi          (settings_dialog.py karşılığı)
    5) MainWindow         -> Ana pencere, kuyruk, tablo (main_window.py karşılığı)
    6) main()             -> Giriş noktası              (main.py karşılığı)
"""
from __future__ import annotations

import sys
import os

# pythonw.exe ile (konsolsuz) çalışırken stdout/stderr None olur; yt-dlp ve
# diğer kütüphanelerin çökmemesi için boş bir cihaza yönlendiriyoruz.
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w")

import re
import json
import time
import shutil
import stat
import zipfile
import tempfile
import threading
import subprocess
import importlib
import importlib.util
import posixpath
import traceback
import urllib.request
from dataclasses import dataclass
from functools import partial
from typing import Optional
from urllib.parse import urlparse, parse_qs

APP_NAME = "ViDDoWN - Video Downloader"


# =============================================================================
# 1) ÖNYÜKLEME (BOOTSTRAP): eksik paketleri otomatik kur
# =============================================================================
def _pip_install(packages: list[str], quiet: bool = True) -> bool:
    """pip ile paket kurar/günceller. Başarılıysa True döner."""
    if getattr(sys, "frozen", False):
        # Derlenmiş .exe içinde sys.executable programın kendisidir; pip çağrılamaz.
        return False
    base = [sys.executable, "-m", "pip", "install", "--upgrade",
            "--disable-pip-version-check"]
    extra_flags = ([], ["--user"], ["--user", "--break-system-packages"])
    kwargs = {}
    if quiet:
        kwargs["stdout"] = subprocess.DEVNULL
        kwargs["stderr"] = subprocess.DEVNULL
    if os.name == "nt":
        kwargs["creationflags"] = 0x08000000  # CREATE_NO_WINDOW
    for flags in extra_flags:
        try:
            subprocess.check_call(base + flags + packages, **kwargs)
            importlib.invalidate_caches()
            return True
        except Exception:
            continue
    return False


def _bootstrap() -> None:
    """PyQt6 ve yt-dlp yoksa kurar. (PyInstaller ile paketlenmişse atlanır.)"""
    if getattr(sys, "frozen", False):
        return
    missing = []
    if importlib.util.find_spec("PyQt6") is None:
        missing.append("PyQt6")
    if importlib.util.find_spec("yt_dlp") is None:
        missing.append("yt-dlp")
    if not missing:
        return
    print("Eksik paketler kuruluyor:", ", ".join(missing), flush=True)
    if not _pip_install(missing, quiet=False):
        msg = ("Gerekli paketler otomatik kurulamadı:\n  " + ", ".join(missing) +
               "\n\nLütfen elle kurun:\n  pip install " + " ".join(missing))
        print(msg)
        try:
            import tkinter
            from tkinter import messagebox
            root = tkinter.Tk()
            root.withdraw()
            messagebox.showerror(APP_NAME, msg)
        except Exception:
            pass
        sys.exit(1)


_bootstrap()

from PyQt6.QtCore import (Qt, QThread, QThreadPool, QRunnable, QObject, QTimer,
                          QUrl, QSize, QEventLoop, pyqtSignal)
from PyQt6.QtGui import (QIcon, QPixmap, QImage, QPalette, QColor, QBrush,
                         QDesktopServices, QKeySequence, QShortcut)
from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QDialog, QVBoxLayout, QHBoxLayout,
    QFormLayout, QLineEdit, QPushButton, QComboBox, QSpinBox, QCheckBox,
    QLabel, QTableWidget, QTableWidgetItem, QHeaderView, QProgressBar,
    QAbstractItemView, QMenu, QMessageBox, QFileDialog, QDialogButtonBox,
    QProgressDialog, QStatusBar,
)

import yt_dlp
from yt_dlp.utils import DownloadError

try:
    from yt_dlp.utils import DownloadCancelled
except ImportError:  # çok eski yt-dlp sürümleri için yedek
    class DownloadCancelled(Exception):
        pass


# -----------------------------------------------------------------------------
# Sabitler
# -----------------------------------------------------------------------------
FORMATS = ["MP3", "MP4"]
MP3_QUALITIES = ["128k", "192k", "320k"]
MP4_QUALITIES = ["Best", "1080p", "720p", "480p"]

ST_READY = "Hazır"
ST_WAIT = "Bekliyor"
ST_DL = "İndiriliyor"
ST_CONV = "Dönüştürülüyor"
ST_DONE = "Tamamlandı"
ST_ERR = "Hata"
ST_STOP = "Durduruldu"
ACTIVE_STATES = {ST_DL, ST_CONV}

STATUS_COLORS = {
    ST_READY: "#4dd0e1", ST_WAIT: "#bdbdbd", ST_DL: "#64b5f6", ST_CONV: "#ba68c8",
    ST_DONE: "#66bb6a", ST_ERR: "#ef5350", ST_STOP: "#ffa726",
}


def get_app_dir() -> str:
    """Uygulamanın bulunduğu klasör (script veya paketlenmiş exe)."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def get_data_dir() -> str:
    """Ayar dosyası ve FFmpeg için kullanıcıya özel yazılabilir klasör."""
    if os.name == "nt":
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
    elif sys.platform == "darwin":
        base = os.path.expanduser("~/Library/Application Support")
    else:
        base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    path = os.path.join(base, "ViDDoWN")
    try:
        os.makedirs(path, exist_ok=True)
    except OSError:
        path = get_app_dir()
    return path


APP_DIR = get_app_dir()
DATA_DIR = get_data_dir()


# -----------------------------------------------------------------------------
# Biçimlendirme yardımcıları
# -----------------------------------------------------------------------------
def fmt_size(value) -> str:
    try:
        v = float(value)
    except (TypeError, ValueError):
        return "—"
    if v <= 0:
        return "—"
    units = ["B", "KB", "MB", "GB", "TB"]
    i = 0
    while v >= 1024 and i < len(units) - 1:
        v /= 1024.0
        i += 1
    return f"{v:.0f} {units[i]}" if i == 0 else f"{v:.1f} {units[i]}"


def fmt_speed(bps) -> str:
    try:
        if float(bps) <= 0:
            return "—"
    except (TypeError, ValueError):
        return "—"
    return fmt_size(bps) + "/s"


def fmt_eta(seconds) -> str:
    try:
        s = int(seconds)
    except (TypeError, ValueError):
        return "—"
    if s < 0:
        return "—"
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    return f"{h}:{m:02d}:{sec:02d}" if h else f"{m:02d}:{sec:02d}"


def fmt_duration(seconds) -> str:
    try:
        s = int(seconds)
    except (TypeError, ValueError):
        return ""
    if s <= 0:
        return ""
    return fmt_eta(s)


_ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


def clean_error(err) -> str:
    """yt-dlp hata metnindeki renk kodlarını ve 'ERROR:' önekini temizler."""
    msg = _ANSI_RE.sub("", str(err)).strip()
    msg = re.sub(r"^ERROR:\s*", "", msg)
    low = msg.lower()
    hint = ""
    if "login required" in low or "rate-limit reached" in low or "cookies" in low and "authentic" in low:
        hint = ("Bu içerik giriş gerektiriyor veya site istekleri sınırladı. Ayarlar > Çerez dosyası ile "
                "oturum açmış tarayıcınızdan dışa aktardığınız cookies.txt dosyasını ekleyip tekrar deneyin.\n")
    elif "unsupported url" in low:
        hint = ("Bu bağlantı desteklenen bir videoya ait görünmüyor (profil/sayfa adresi olabilir; "
                "doğrudan gönderi/video bağlantısını deneyin).\n")
    elif "no video could be found" in low or "no video formats found" in low:
        hint = "Bu bağlantıda video bulunamadı (yalnızca fotoğraf/metin gönderisi olabilir).\n"
    return ((hint + msg)[:700] or "Bilinmeyen hata")


def safe_filename(name: str) -> str:
    name = re.sub(r'[\\/:*?"<>|]+', "_", name or "").strip(" .")
    return name[:120] or "Playlist"


def estimate_mp3_size(duration: float, quality: str) -> float:
    try:
        kbps = int(quality.rstrip("k"))
        return float(duration) * kbps * 1000 / 8.0
    except (ValueError, TypeError):
        return 0.0


def estimate_size_from_info(info: dict) -> float:
    """yt-dlp info sözlüğünden tahmini dosya boyutunu hesaplar."""
    rf = info.get("requested_formats")
    if rf:
        return float(sum((f.get("filesize") or f.get("filesize_approx") or 0) for f in rf))
    return float(info.get("filesize") or info.get("filesize_approx") or 0)


def _is_none_codec(c) -> bool:
    return (not c) or str(c).lower() in ("none", "null")


def info_non_compat(info: dict) -> bool:
    """Seçilen akışlardan biri H.264 (video) / AAC (ses) değilse True."""
    fmts = info.get("requested_formats") or [info]
    for f in fmts:
        v = str(f.get("vcodec") or "").lower()
        a = str(f.get("acodec") or "").lower()
        if not _is_none_codec(v) and not v.startswith(("avc", "h264")):
            return True
        if not _is_none_codec(a) and not a.startswith(("mp4a", "aac")):
            return True
    return False


_SITE_NAMES = (("youtube", "YouTube"), ("twitter", "X"), ("instagram", "Instagram"), ("tiktok", "TikTok"),
               ("facebook", "Facebook"), ("reddit", "Reddit"), ("vimeo", "Vimeo"), ("twitch", "Twitch"),
               ("soundcloud", "SoundCloud"), ("dailymotion", "Dailymotion"))


def site_name(info: dict) -> str:
    k = str(info.get("extractor_key") or info.get("extractor") or "")
    for prefix, nice in _SITE_NAMES:
        if k.lower().startswith(prefix):
            return nice
    return k


def pick_thumb(info: dict) -> str:
    t = info.get("thumbnail")
    if isinstance(t, str) and t.startswith("http"):
        return t
    for th in reversed(info.get("thumbnails") or []):
        u = th.get("url") if isinstance(th, dict) else None
        if isinstance(u, str) and u.startswith("http"):
            return u
    return ""


def reveal_in_folder(path: str, fallback_dir: str = "") -> None:
    """Dosyayı gezginde seçili olarak gösterir; dosya yoksa klasörü açar."""
    try:
        if path and os.path.isfile(path):
            if os.name == "nt":
                subprocess.Popen(f'explorer /select,"{os.path.normpath(path)}"')
            elif sys.platform == "darwin":
                subprocess.Popen(["open", "-R", path])
            else:
                QDesktopServices.openUrl(QUrl.fromLocalFile(os.path.dirname(path)))
            return
        folder = os.path.dirname(path) if path else fallback_dir
        if not folder or not os.path.isdir(folder):
            folder = fallback_dir
        if folder and os.path.isdir(folder):
            QDesktopServices.openUrl(QUrl.fromLocalFile(folder))
    except Exception:
        pass


# -----------------------------------------------------------------------------
# YouTube URL çözümleme
# -----------------------------------------------------------------------------
YT_HOSTS = {
    "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
    "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com",
}


def parse_youtube_url(url: str):
    """
    URL'yi sınıflandırır. Dönüş: (tür, video_id, liste_id)
    tür: 'video' | 'playlist' | 'both' (hem video hem liste) | 'invalid'
    """
    try:
        p = urlparse(url.strip())
        host = (p.hostname or "").lower()
    except ValueError:
        return "invalid", None, None
    if host not in YT_HOSTS:
        return "invalid", None, None

    q = parse_qs(p.query)
    path = p.path or ""
    lid = q.get("list", [None])[0]
    vid = None
    if host.endswith("youtu.be"):
        vid = path.strip("/").split("/")[0] or None
    elif "v" in q:
        vid = q["v"][0]
    else:
        m = re.match(r"^/(shorts|embed|live|v)/([\w-]{6,})", path)
        if m:
            vid = m.group(2)

    if path.rstrip("/") == "/playlist" and lid:
        return "playlist", None, lid
    if vid and lid:
        # RD/UL ile başlayanlar otomatik 'mix' listeleridir: sadece videoyu al
        if lid.startswith(("RD", "UL")):
            return "video", vid, None
        return "both", vid, lid
    if vid:
        return "video", vid, None
    if lid:
        return "playlist", None, lid
    return "invalid", None, None


def classify_url(url: str):
    """
    Her site için sınıflandırma. YouTube: parse_youtube_url sonucu; diğer tüm http(s)
    bağlantıları 'other' (yt-dlp'nin desteklediği siteler). Dönüş: (tür, video_id, liste_id)
    """
    try:
        p = urlparse(url.strip())
        host = (p.hostname or "").lower()
    except ValueError:
        return "invalid", None, None
    if p.scheme not in ("http", "https") or "." not in host:
        return "invalid", None, None
    if host in YT_HOSTS:
        return parse_youtube_url(url)
    return "other", None, None


# =============================================================================
# FFmpeg bulma ve otomatik kurulum
# =============================================================================
def _ffmpeg_names() -> tuple[str, str]:
    return ("ffmpeg.exe", "ffprobe.exe") if os.name == "nt" else ("ffmpeg", "ffprobe")


def find_ffmpeg_dir(configured: str = "") -> Optional[str]:
    """
    FFmpeg'in bulunduğu klasörü döndürür (yt-dlp ffmpeg_location için).
    Sırayla: ayarlardaki yol -> uygulama verisi -> uygulama klasörü -> PATH.
    """
    ffmpeg_name = _ffmpeg_names()[0]
    candidates: list[str] = []
    if configured and configured.strip():
        c = os.path.expandvars(os.path.expanduser(configured.strip().strip('"')))
        if os.path.isfile(c):
            candidates.append(os.path.dirname(c))
        else:
            candidates += [c, os.path.join(c, "bin")]
    candidates += [
        os.path.join(DATA_DIR, "ffmpeg"),
        os.path.join(APP_DIR, "ffmpeg"),
        os.path.join(APP_DIR, "ffmpeg", "bin"),
        APP_DIR,
    ]
    for d in candidates:
        if d and os.path.isfile(os.path.join(d, ffmpeg_name)):
            return d
    found = shutil.which("ffmpeg")
    if found:
        return os.path.dirname(os.path.realpath(found))
    return None


class _Cancelled(Exception):
    """Kullanıcı işlemi iptal etti."""


class FFmpegInstaller(QThread):
    """FFmpeg'i arka planda indirip kurar (arayüzü dondurmaz)."""
    status_text = pyqtSignal(str)
    percent = pyqtSignal(int)         # 0-100, -1 = belirsiz
    succeeded = pyqtSignal(str)       # kurulum klasörü
    failed = pyqtSignal(str)

    WIN_URLS = [
        "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip",
        "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
    ]

    def __init__(self, parent=None):
        super().__init__(parent)
        self._cancel = threading.Event()

    def cancel(self) -> None:
        self._cancel.set()

    def run(self) -> None:
        target = os.path.join(DATA_DIR, "ffmpeg")
        try:
            os.makedirs(target, exist_ok=True)
            if os.name == "nt":
                self._install_windows(target)
            else:
                self._install_via_pip(target)
            if self._cancel.is_set():
                raise _Cancelled()
            if not os.path.isfile(os.path.join(target, _ffmpeg_names()[0])):
                raise RuntimeError("Kurulum tamamlandı ancak ffmpeg bulunamadı.")
            self.succeeded.emit(target)
        except _Cancelled:
            self.failed.emit("İşlem iptal edildi.")
        except Exception as exc:  # noqa: BLE001
            self.failed.emit(str(exc) or exc.__class__.__name__)

    # -- Windows: hazır derlemeyi zip olarak indir ----------------------------
    def _download(self, url: str, dest: str) -> None:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=30) as resp, open(dest, "wb") as out:
            total = int(resp.headers.get("Content-Length") or 0)
            got = 0
            while True:
                if self._cancel.is_set():
                    raise _Cancelled()
                chunk = resp.read(256 * 1024)
                if not chunk:
                    break
                out.write(chunk)
                got += len(chunk)
                txt = f"FFmpeg indiriliyor... {fmt_size(got)}"
                if total:
                    txt += f" / {fmt_size(total)}"
                    self.percent.emit(int(got * 100 / total))
                else:
                    self.percent.emit(-1)
                self.status_text.emit(txt)

    def _install_windows(self, target: str) -> None:
        last_err = None
        tmp_zip = os.path.join(tempfile.gettempdir(), "viddown_ffmpeg.zip")
        for url in self.WIN_URLS:
            try:
                self._download(url, tmp_zip)
                self.status_text.emit("FFmpeg çıkarılıyor...")
                self.percent.emit(-1)
                with zipfile.ZipFile(tmp_zip) as zf:
                    for name in zf.namelist():
                        norm = name.replace("\\", "/")
                        base = posixpath.basename(norm).lower()
                        if "/bin/" in norm and base in ("ffmpeg.exe", "ffprobe.exe"):
                            with zf.open(name) as src, open(os.path.join(target, base), "wb") as dst:
                                shutil.copyfileobj(src, dst)
                last_err = None
                break
            except _Cancelled:
                raise
            except Exception as exc:  # noqa: BLE001
                last_err = exc
        try:
            os.remove(tmp_zip)
        except OSError:
            pass
        if last_err is not None:
            raise RuntimeError(f"FFmpeg indirilemedi: {last_err}")

    # -- Linux/macOS: imageio-ffmpeg paketinden statik ffmpeg al ---------------
    def _install_via_pip(self, target: str) -> None:
        self.status_text.emit("FFmpeg paketi kuruluyor (imageio-ffmpeg)...")
        self.percent.emit(-1)
        if not _pip_install(["imageio-ffmpeg"]):
            raise RuntimeError("imageio-ffmpeg kurulamadı. Lütfen sisteminize ffmpeg kurun "
                               "(örn: sudo apt install ffmpeg / brew install ffmpeg).")
        import imageio_ffmpeg  # type: ignore
        exe = imageio_ffmpeg.get_ffmpeg_exe()
        dst = os.path.join(target, "ffmpeg")
        shutil.copy2(exe, dst)
        os.chmod(dst, os.stat(dst).st_mode | stat.S_IEXEC)


def install_ffmpeg_interactive(parent: QWidget) -> Optional[str]:
    """İlerleme penceresiyle FFmpeg kurar. Başarılıysa klasör yolunu döndürür."""
    dlg = QProgressDialog("FFmpeg hazırlanıyor...", "İptal", 0, 100, parent)
    dlg.setWindowTitle("FFmpeg Kurulumu")
    dlg.setWindowModality(Qt.WindowModality.WindowModal)
    dlg.setMinimumDuration(0)
    dlg.setAutoClose(False)
    dlg.setAutoReset(False)
    dlg.setRange(0, 0)

    installer = FFmpegInstaller()
    result: dict = {"path": None, "err": None}
    loop = QEventLoop()

    def on_percent(v: int) -> None:
        if v < 0:
            dlg.setRange(0, 0)
        else:
            dlg.setRange(0, 100)
            dlg.setValue(v)

    def on_ok(p: str) -> None:
        result["path"] = p

    def on_fail(m: str) -> None:
        result["err"] = m

    installer.status_text.connect(dlg.setLabelText)
    installer.percent.connect(on_percent)
    installer.succeeded.connect(on_ok)
    installer.failed.connect(on_fail)
    installer.finished.connect(loop.quit)
    dlg.canceled.connect(installer.cancel)

    installer.start()
    dlg.show()
    loop.exec()
    installer.wait()
    try:
        dlg.canceled.disconnect()
    except TypeError:
        pass
    dlg.close()
    dlg.deleteLater()
    installer.deleteLater()

    if result["path"]:
        return result["path"]
    if result["err"] and "iptal" not in result["err"].lower():
        QMessageBox.warning(parent, "FFmpeg", f"FFmpeg kurulamadı:\n{result['err']}")
    return None


class YtdlpUpdater(QThread):
    """yt-dlp'yi pip ile günceller (YouTube sık değiştiği için faydalıdır)."""
    done = pyqtSignal(bool)

    def run(self) -> None:
        try:
            self.done.emit(_pip_install(["yt-dlp"]))
        except Exception:  # noqa: BLE001
            self.done.emit(False)


# =============================================================================
# 2) CONFIG MANAGER  (JSON tabanlı ayar yönetimi)
# =============================================================================
def _default_download_dir() -> str:
    d = os.path.join(os.path.expanduser("~"), "Downloads")
    return d if os.path.isdir(d) else os.path.expanduser("~")


DEFAULT_CONFIG = {
    "download_dir": _default_download_dir(),
    "default_format": "MP4",
    "mp3_quality": "192k",
    "mp4_quality": "1080p",
    "max_concurrent": 3,
    "speed_limit_kbps": 0,      # 0 = sınırsız (KB/s cinsinden saklanır)
    "ffmpeg_path": "",
    "ffmpeg_asked": False,
    "compat_mp4": True,         # MP4'leri H.264 + AAC (her cihazda oynar) kaydet
    "cookies_path": "",         # giriş gerektiren siteler için cookies.txt
}


class ConfigManager(QObject):
    """Ayarları JSON dosyasında saklar, yükler ve değişiklikleri duyurur."""
    changed = pyqtSignal()

    def __init__(self, path: Optional[str] = None):
        super().__init__()
        self.path = path or os.path.join(DATA_DIR, "ayarlar.json")
        self._data = dict(DEFAULT_CONFIG)

    # -- doğrulama ----------------------------------------------------------
    @staticmethod
    def _sanitize(key: str, value):
        default = DEFAULT_CONFIG[key]
        try:
            if key == "default_format":
                return value if value in FORMATS else default
            if key == "mp3_quality":
                return value if value in MP3_QUALITIES else default
            if key == "mp4_quality":
                return value if value in MP4_QUALITIES else default
            if key == "max_concurrent":
                return max(1, min(10, int(value)))
            if key == "speed_limit_kbps":
                return max(0, int(value))
            if key in ("ffmpeg_asked", "compat_mp4"):
                return bool(value)
            return str(value)
        except (TypeError, ValueError):
            return default

    # -- erişim ---------------------------------------------------------------
    def get(self, key: str):
        return self._data.get(key, DEFAULT_CONFIG.get(key))

    def set(self, key: str, value) -> None:
        if key in DEFAULT_CONFIG:
            self._data[key] = self._sanitize(key, value)

    def update(self, values: dict) -> None:
        for k, v in values.items():
            self.set(k, v)
        self.changed.emit()

    # -- dosya işlemleri ------------------------------------------------------
    def load(self) -> None:
        try:
            with open(self.path, "r", encoding="utf-8") as fh:
                raw = json.load(fh)
            if isinstance(raw, dict):
                for k in DEFAULT_CONFIG:
                    if k in raw:
                        self._data[k] = self._sanitize(k, raw[k])
        except FileNotFoundError:
            pass
        except Exception as exc:  # bozuk dosya: varsayılanlarla devam et
            print("Ayar dosyası okunamadı:", exc)

    def save(self) -> None:
        try:
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(self._data, fh, ensure_ascii=False, indent=2)
            os.replace(tmp, self.path)  # atomik yazım: yarım dosya kalmaz
        except Exception as exc:
            print("Ayarlar kaydedilemedi:", exc)


# =============================================================================
# 3) İNDİRME MOTORU
# =============================================================================
class _QuietLogger:
    """yt-dlp çıktılarını susturur (konsolsuz çalışmada çökmeyi de önler)."""

    def debug(self, msg): pass
    def info(self, msg): pass
    def warning(self, msg): pass
    def error(self, msg): pass


_H264 = "[vcodec~='^(avc|h264)']"
_AAC = "[acodec~='^(mp4a|aac)']"


def build_format_selector(fmt: str, quality: str, has_ffmpeg: bool, compat: bool = True) -> str:
    """Seçilen format/kalite için yt-dlp 'format' ifadesini üretir."""
    if fmt == "MP3":
        return "bestaudio/best"
    h = {"1080p": 1080, "720p": 720, "480p": 480}.get(quality)
    hf = f"[height<={h}]" if h else ""
    if has_ffmpeg:
        if compat:
            # Önce H.264 + AAC (TV, medya oynatıcı dahil her cihazda açılır); yoksa en iyisi
            return (f"bv*{_H264}{hf}+ba{_AAC}/bv*{_H264}{hf}+ba/b{_H264}{_AAC}{hf}/"
                    f"bv*{hf}+ba/b{hf}/b")
        if h is None:  # Best: en yüksek çözünürlük, mp4 içine birleştirilir
            return "bestvideo+bestaudio/best"
        return (f"bestvideo[height<={h}][ext=mp4]+bestaudio[ext=m4a]/"
                f"bestvideo[height<={h}]+bestaudio/best[height<={h}]/best")
    # FFmpeg yoksa birleştirme gerektirmeyen (video+ses tek dosya) formatlar
    if compat:
        return f"b{_H264}{_AAC}{hf}/b[ext=mp4]{hf}/b{hf}/b"
    if h is None:
        return "best[ext=mp4]/best"
    return f"best[height<={h}][ext=mp4]/best[height<={h}]/best"


RECODE_ARGS = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-pix_fmt", "yuv420p",
               "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"]


class DownloadWorker(QThread):
    """Tek bir indirmeyi bağımsız bir iş parçacığında yürütür."""
    progress = pyqtSignal(int, float, float, float, float)  # id, %, hız, eta, toplam
    state = pyqtSignal(int, str)                            # id, durum
    completed = pyqtSignal(int, str)                        # id, dosya yolu
    failed = pyqtSignal(int, str)                           # id, hata
    stopped = pyqtSignal(int)                               # id

    def __init__(self, item_id: int, url: str, fmt: str, quality: str,
                 out_dir: str, ratelimit_bps: int, ffmpeg_dir: Optional[str],
                 compat: bool = True, reencode: bool = False, cookies: str = "",
                 parent: Optional[QObject] = None):
        super().__init__(parent)
        self.item_id = item_id
        self.url = url
        self.fmt = fmt
        self.quality = quality
        self.out_dir = out_dir
        self.ffmpeg_dir = ffmpeg_dir
        self.compat = compat
        self.reencode = reencode
        self.cookies = cookies
        self._rate = ratelimit_bps
        self._cancel = threading.Event()
        self._ydl: Optional[yt_dlp.YoutubeDL] = None
        self._finished_bytes: dict[str, float] = {}
        self._last_emit = 0.0

    # -- dışarıdan kontrol -----------------------------------------------------
    def cancel(self) -> None:
        self._cancel.set()

    def set_ratelimit(self, bps: int) -> None:
        """Çalışırken hız sınırını canlı günceller (yt-dlp her blokta okur)."""
        self._rate = bps
        try:
            if self._ydl is not None:
                self._ydl.params["ratelimit"] = bps or None
        except Exception:
            pass

    # -- yt-dlp seçenekleri ------------------------------------------------------
    def _build_opts(self) -> dict:
        has_ff = bool(self.ffmpeg_dir)
        opts = {
            "format": build_format_selector(self.fmt, self.quality, has_ff, self.compat),
            "outtmpl": os.path.join(self.out_dir, "%(title)s.%(ext)s"),
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "noprogress": True,
            "logger": _QuietLogger(),
            "progress_hooks": [self._on_progress],
            "postprocessor_hooks": [self._on_postprocess],
            "ratelimit": self._rate or None,
            "retries": 10,
            "fragment_retries": 10,
            "socket_timeout": 30,
            "continuedl": True,
            "windowsfilenames": True,
            "trim_file_name": 150,
        }
        if has_ff:
            opts["ffmpeg_location"] = self.ffmpeg_dir
        if self.cookies and os.path.isfile(self.cookies):
            opts["cookiefile"] = self.cookies
        posts = []
        if self.fmt == "MP3":
            posts.append({
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": self.quality.rstrip("k"),
            })
        elif has_ff and self.compat and self.reencode:
            # Kaynak H.264/AAC değil: mkv'de birleştir, sonra uyumlu MP4'e dönüştür
            opts["merge_output_format"] = "mkv"
            posts.append({"key": "FFmpegVideoConvertor", "preferedformat": "mp4"})
            opts["postprocessor_args"] = {"videoconvertor": list(RECODE_ARGS)}
        else:
            opts["merge_output_format"] = "mp4"
            if has_ff and self.compat:
                posts.append({"key": "FFmpegVideoRemuxer", "preferedformat": "mp4"})
        if has_ff:
            posts.append({"key": "FFmpegMetadata", "add_metadata": True})
        opts["postprocessors"] = posts
        return opts

    # -- hook'lar ----------------------------------------------------------------
    def _on_progress(self, d: dict) -> None:
        if self._cancel.is_set():
            raise DownloadCancelled()
        status = d.get("status")
        fname = d.get("filename") or d.get("tmpfilename") or ""
        if status == "downloading":
            now = time.monotonic()
            if now - self._last_emit < 0.25:  # arayüzü boğmamak için sınırla
                return
            self._last_emit = now
            downloaded = float(d.get("downloaded_bytes") or 0)
            total_part = float(d.get("total_bytes") or d.get("total_bytes_estimate") or 0)
            info = d.get("info_dict") or {}
            rf = info.get("requested_formats") or []
            expected = float(sum((f.get("filesize") or f.get("filesize_approx") or 0) for f in rf))
            done_bytes = sum(self._finished_bytes.values())
            if expected > 0:
                total = max(expected, done_bytes + downloaded)
                pct = (done_bytes + downloaded) / total * 100.0
            else:
                nparts = max(len(rf), 1)
                idx = len(self._finished_bytes)
                frac = (downloaded / total_part) if total_part else 0.0
                pct = (idx + frac) / nparts * 100.0
                total = total_part * nparts if total_part else 0.0
            speed = float(d.get("speed") or 0)
            if speed > 0 and total > 0:
                eta = max(total - done_bytes - downloaded, 0) / speed
            else:
                eta = float(d["eta"]) if d.get("eta") is not None else -1.0
            self.progress.emit(self.item_id, min(pct, 99.9), speed, eta, total)
        elif status == "finished":
            size = float(d.get("total_bytes") or d.get("downloaded_bytes") or 0)
            self._finished_bytes[fname] = size

    def _on_postprocess(self, d: dict) -> None:
        if d.get("status") == "started":
            self.state.emit(self.item_id, ST_CONV)
            self.progress.emit(self.item_id, 99.9, 0.0, -1.0, 0.0)

    # -- sonuç dosyasını bul ---------------------------------------------------------
    def _resolve_path(self, ydl: yt_dlp.YoutubeDL, info: dict) -> str:
        try:
            for r in (info.get("requested_downloads") or []):
                fp = r.get("filepath")
                if fp and os.path.exists(fp):
                    return fp
            fp = ydl.prepare_filename(info)
            base = os.path.splitext(fp)[0]
            exts = [".mp3"] if self.fmt == "MP3" else [".mp4", ".mkv", ".webm"]
            for ext in exts + [".m4a", ".webm", ".mp4"]:
                if os.path.exists(base + ext):
                    return base + ext
            return fp
        except Exception:
            return ""

    # -- ana iş parçacığı ------------------------------------------------------------
    def run(self) -> None:
        try:
            if self.fmt == "MP3" and not self.ffmpeg_dir:
                self.failed.emit(self.item_id, "MP3 dönüştürme için FFmpeg gerekli. "
                                               "Ayarlar'dan FFmpeg'i otomatik indirebilirsiniz.")
                return
            os.makedirs(self.out_dir, exist_ok=True)
            with yt_dlp.YoutubeDL(self._build_opts()) as ydl:
                self._ydl = ydl
                info = ydl.extract_info(self.url, download=True)
                path = self._resolve_path(ydl, info) if info else ""
            if self._cancel.is_set():
                self.stopped.emit(self.item_id)
            else:
                self.progress.emit(self.item_id, 100.0, 0.0, 0.0, 0.0)
                self.completed.emit(self.item_id, path)
        except DownloadCancelled:
            self.stopped.emit(self.item_id)
        except DownloadError as exc:
            if self._cancel.is_set():
                self.stopped.emit(self.item_id)
            else:
                self.failed.emit(self.item_id, clean_error(exc))
        except Exception as exc:  # noqa: BLE001
            if self._cancel.is_set():
                self.stopped.emit(self.item_id)
            else:
                self.failed.emit(self.item_id, clean_error(exc))
        finally:
            self._ydl = None


# -----------------------------------------------------------------------------
# Metadata ve küçük resim çözümleyiciler (QThreadPool + QRunnable)
# -----------------------------------------------------------------------------
class MetaSignals(QObject):
    result = pyqtSignal(object)
    error = pyqtSignal(str, str)   # url, mesaj


class MetaTask(QRunnable):
    """Link için hızlıca metadata (başlık, süre, boyut tahmini) çeker."""

    def __init__(self, url: str, is_playlist: bool, fmt: str, quality: str, has_ffmpeg: bool,
                 compat: bool = True, cookies: str = "", generic: bool = False):
        super().__init__()
        self.compat = compat
        self.cookies = cookies
        self.generic = generic
        self.signals = MetaSignals()
        self.url = url
        self.is_playlist = is_playlist
        self.fmt = fmt
        self.quality = quality
        self.has_ffmpeg = has_ffmpeg

    @staticmethod
    def _watch_url(vid: str) -> str:
        return f"https://www.youtube.com/watch?v={vid}"

    def run(self) -> None:
        try:
            opts = {
                "quiet": True, "no_warnings": True, "skip_download": True,
                "socket_timeout": 20, "logger": _QuietLogger(),
                "noplaylist": not (self.is_playlist or self.generic),
            }
            if self.cookies and os.path.isfile(self.cookies):
                opts["cookiefile"] = self.cookies
            if self.generic:
                opts["playlistend"] = 100
            entries: list[dict] = []
            playlist_title = ""
            if self.is_playlist:
                opts["extract_flat"] = "in_playlist"   # hızlı: sadece liste özetleri
                with yt_dlp.YoutubeDL(opts) as ydl:
                    info = ydl.extract_info(self.url, download=False)
                playlist_title = (info or {}).get("title") or "Playlist"
                raw = (info or {}).get("entries")
                if raw is None and info:
                    raw = [info]
                for e in (raw or []):
                    if not e:
                        continue
                    vid = e.get("id")
                    title = e.get("title") or vid or ""
                    if not vid or title in ("[Private video]", "[Deleted video]"):
                        continue
                    entries.append({
                        "video_id": vid, "url": self._watch_url(vid), "title": title,
                        "duration": float(e.get("duration") or 0), "size": 0.0,
                        "thumb": "", "site": "YouTube", "non_compat": False,
                    })
            else:
                opts["format"] = build_format_selector(self.fmt, self.quality, self.has_ffmpeg, self.compat)
                try:
                    with yt_dlp.YoutubeDL(opts) as ydl:
                        info = ydl.extract_info(self.url, download=False)
                except DownloadError:
                    opts.pop("format", None)   # format çözülemezse boyutsuz devam et
                    with yt_dlp.YoutubeDL(opts) as ydl:
                        info = ydl.extract_info(self.url, download=False)
                if not info:
                    raise RuntimeError("Video bilgisi alınamadı.")
                if self.generic and info.get("_type") == "playlist":
                    # X/Instagram/TikTok vb.: birden çok videolu gönderi veya sayfa
                    playlist_title = info.get("title") or "Liste"
                    self.is_playlist = True
                    site = site_name(info)
                    for e in (info.get("entries") or []):
                        if not e or not e.get("id"):
                            continue
                        eu = e.get("webpage_url") or e.get("url") or self.url
                        entries.append({
                            "video_id": str(e["id"]), "url": eu if str(eu).startswith("http") else self.url,
                            "title": e.get("title") or str(e["id"]),
                            "duration": float(e.get("duration") or 0),
                            "size": estimate_size_from_info(e), "thumb": pick_thumb(e),
                            "site": site, "non_compat": info_non_compat(e),
                        })
                else:
                    vid = info.get("id") or ""
                    if self.generic:
                        url, vid_out = self.url, str(vid)
                    else:
                        url, vid_out = (self._watch_url(vid) if vid else self.url), vid
                    entries.append({
                        "video_id": vid_out, "url": url,
                        "title": info.get("title") or vid or self.url,
                        "duration": float(info.get("duration") or 0),
                        "size": estimate_size_from_info(info), "thumb": pick_thumb(info),
                        "site": site_name(info) or ("YouTube" if not self.generic else ""),
                        "non_compat": info_non_compat(info),
                    })
            if not entries:
                raise RuntimeError("Bu bağlantıda indirilebilir video bulunamadı.")
            self.signals.result.emit({
                "source": self.url, "is_playlist": self.is_playlist,
                "playlist_title": playlist_title, "entries": entries,
                "fmt": self.fmt, "quality": self.quality, "generic": self.generic,
            })
        except Exception as exc:  # noqa: BLE001
            self.signals.error.emit(self.url, clean_error(exc))


class ThumbSignals(QObject):
    loaded = pyqtSignal(int, QImage)


class ThumbTask(QRunnable):
    """Küçük resmi indirir (QImage iş parçacığı güvenlidir)."""

    def __init__(self, item_id: int, video_id: str, thumb_url: str = ""):
        super().__init__()
        self.signals = ThumbSignals()
        self.item_id = item_id
        self.video_id = video_id
        self.thumb_url = thumb_url

    def run(self) -> None:
        try:
            url = self.thumb_url or f"https://i.ytimg.com/vi/{self.video_id}/mqdefault.jpg"
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = resp.read()
            img = QImage.fromData(data)
            if not img.isNull():
                img = img.scaled(96, 54, Qt.AspectRatioMode.KeepAspectRatio,
                                 Qt.TransformationMode.SmoothTransformation)
                self.signals.loaded.emit(self.item_id, img)
        except Exception:
            pass  # küçük resim kritik değil


# =============================================================================
# 4) AYARLAR PENCERESİ
# =============================================================================
class SettingsDialog(QDialog):
    def __init__(self, config: ConfigManager, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.config = config
        self.setWindowTitle("Ayarlar")
        self.setMinimumWidth(560)
        self._updater: Optional[YtdlpUpdater] = None
        self._q = {"MP3": config.get("mp3_quality"), "MP4": config.get("mp4_quality")}
        self._cur_fmt = config.get("default_format")
        self._build_ui()
        self._load_values()

    # -- arayüz --------------------------------------------------------------------
    def _build_ui(self) -> None:
        root = QVBoxLayout(self)
        form = QFormLayout()
        form.setLabelAlignment(Qt.AlignmentFlag.AlignRight)

        # Kayıt klasörü
        self.dir_edit = QLineEdit()
        btn_dir = QPushButton("Gözat...")
        btn_dir.clicked.connect(self._browse_dir)
        row = QHBoxLayout()
        row.addWidget(self.dir_edit, 1)
        row.addWidget(btn_dir)
        form.addRow("Kayıt klasörü:", row)

        # Varsayılan format / kalite
        self.fmt_combo = QComboBox()
        self.fmt_combo.addItems(FORMATS)
        self.fmt_combo.currentTextChanged.connect(self._on_fmt_changed)
        self.q_combo = QComboBox()
        row2 = QHBoxLayout()
        row2.addWidget(self.fmt_combo)
        row2.addWidget(QLabel("Kalite:"))
        row2.addWidget(self.q_combo)
        row2.addStretch(1)
        form.addRow("Varsayılan format:", row2)

        # Uyumlu MP4
        self.compat_chk = QCheckBox("Her cihazda oynat: MP4'ü H.264 + AAC olarak kaydet "
                                    "(TV, medya oynatıcı, telefon)")
        self.compat_chk.setToolTip("Uyumsuz kodek (VP9/AV1/Opus) kaynaklar indirilirken dönüştürülür; "
                                   "bu işlem yavaş olabilir. FFmpeg gerekir.")
        form.addRow("MP4 uyumluluğu:", self.compat_chk)

        # Eşzamanlı indirme
        self.conc_spin = QSpinBox()
        self.conc_spin.setRange(1, 10)
        form.addRow("Eşzamanlı indirme:", self.conc_spin)

        # Hız sınırı
        self.unlimited_chk = QCheckBox("Sınırsız")
        self.unlimited_chk.toggled.connect(self._on_unlimited)
        self.speed_spin = QSpinBox()
        self.speed_spin.setRange(1, 1_000_000)
        self.unit_combo = QComboBox()
        self.unit_combo.addItems(["KB/s", "MB/s"])
        row3 = QHBoxLayout()
        row3.addWidget(self.unlimited_chk)
        row3.addWidget(self.speed_spin)
        row3.addWidget(self.unit_combo)
        row3.addStretch(1)
        form.addRow("İndirme hızı limiti:", row3)

        # FFmpeg
        self.ff_edit = QLineEdit()
        self.ff_edit.setPlaceholderText("Boş bırakılırsa otomatik bulunur (ffmpeg.exe veya klasör)")
        self.ff_edit.textChanged.connect(self._refresh_ffmpeg_status)
        btn_ff_file = QPushButton("Dosya...")
        btn_ff_file.clicked.connect(self._browse_ffmpeg_file)
        btn_ff_dir = QPushButton("Klasör...")
        btn_ff_dir.clicked.connect(self._browse_ffmpeg_dir)
        row4 = QHBoxLayout()
        row4.addWidget(self.ff_edit, 1)
        row4.addWidget(btn_ff_file)
        row4.addWidget(btn_ff_dir)
        form.addRow("FFmpeg yolu:", row4)

        # Çerez dosyası (giriş gerektiren siteler)
        self.cookie_edit = QLineEdit()
        self.cookie_edit.setPlaceholderText("İsteğe bağlı: Instagram/Facebook gibi giriş isteyen siteler için cookies.txt")
        btn_cookie = QPushButton("Gözat...")
        btn_cookie.clicked.connect(self._browse_cookies)
        row_c = QHBoxLayout()
        row_c.addWidget(self.cookie_edit, 1)
        row_c.addWidget(btn_cookie)
        form.addRow("Çerez dosyası:", row_c)

        self.ff_status = QLabel()
        self.ff_status.setWordWrap(True)
        btn_ff_auto = QPushButton("FFmpeg'i Otomatik İndir")
        btn_ff_auto.clicked.connect(self._auto_ffmpeg)
        row5 = QHBoxLayout()
        row5.addWidget(self.ff_status, 1)
        row5.addWidget(btn_ff_auto)
        form.addRow("", row5)

        # yt-dlp sürümü / güncelleme
        ver = getattr(getattr(yt_dlp, "version", None), "__version__", "?")
        self.ver_label = QLabel(f"yt-dlp sürümü: {ver}")
        self.btn_update = QPushButton("yt-dlp'yi Güncelle")
        self.btn_update.clicked.connect(self._update_ytdlp)
        if getattr(sys, "frozen", False):
            # Derlenmiş sürümde yt-dlp içeri gömülüdür; güncelleme için yeniden derleyin.
            self.btn_update.setEnabled(False)
            self.btn_update.setToolTip("Derlenmiş sürümde yt-dlp gömülüdür. Güncellemek için "
                                       "derle.bat ile programı yeniden derleyin.")
        row6 = QHBoxLayout()
        row6.addWidget(self.ver_label, 1)
        row6.addWidget(self.btn_update)
        form.addRow("", row6)

        root.addLayout(form)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save |
                                   QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Save).setText("Kaydet")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("İptal")
        buttons.accepted.connect(self._save)
        buttons.rejected.connect(self.reject)
        root.addWidget(buttons)

    # -- değerleri yükle ---------------------------------------------------------------
    def _load_values(self) -> None:
        c = self.config
        self.dir_edit.setText(c.get("download_dir"))
        self.fmt_combo.blockSignals(True)
        self.fmt_combo.setCurrentText(self._cur_fmt)
        self.fmt_combo.blockSignals(False)
        self._fill_quality(self._cur_fmt)
        self.conc_spin.setValue(int(c.get("max_concurrent")))
        kbps = int(c.get("speed_limit_kbps"))
        self.unlimited_chk.setChecked(kbps <= 0)
        if kbps >= 1024 and kbps % 1024 == 0:
            self.unit_combo.setCurrentText("MB/s")
            self.speed_spin.setValue(kbps // 1024)
        else:
            self.unit_combo.setCurrentText("KB/s")
            self.speed_spin.setValue(kbps if kbps > 0 else 500)
        self._on_unlimited(self.unlimited_chk.isChecked())
        self.ff_edit.setText(c.get("ffmpeg_path"))
        self.compat_chk.setChecked(bool(c.get("compat_mp4")))
        self.cookie_edit.setText(c.get("cookies_path"))
        self._refresh_ffmpeg_status()

    def _fill_quality(self, fmt: str) -> None:
        self.q_combo.blockSignals(True)
        self.q_combo.clear()
        self.q_combo.addItems(MP3_QUALITIES if fmt == "MP3" else MP4_QUALITIES)
        self.q_combo.setCurrentText(self._q.get(fmt, ""))
        self.q_combo.blockSignals(False)

    # -- olaylar ---------------------------------------------------------------------------
    def _on_fmt_changed(self, new_fmt: str) -> None:
        self._q[self._cur_fmt] = self.q_combo.currentText()  # eski formatın seçimini sakla
        self._cur_fmt = new_fmt
        self._fill_quality(new_fmt)

    def _on_unlimited(self, checked: bool) -> None:
        self.speed_spin.setEnabled(not checked)
        self.unit_combo.setEnabled(not checked)

    def _browse_dir(self) -> None:
        d = QFileDialog.getExistingDirectory(self, "Kayıt klasörünü seç", self.dir_edit.text())
        if d:
            self.dir_edit.setText(d)

    def _browse_cookies(self) -> None:
        f, _ = QFileDialog.getOpenFileName(self, "cookies.txt seçin", self.cookie_edit.text() or "",
                                           "Metin dosyası (*.txt);;Tüm dosyalar (*)")
        if f:
            self.cookie_edit.setText(f)

    def _browse_ffmpeg_file(self) -> None:
        f, _ = QFileDialog.getOpenFileName(self, "ffmpeg dosyasını seç", "",
                                           "ffmpeg (ffmpeg.exe ffmpeg);;Tümü (*)")
        if f:
            self.ff_edit.setText(f)

    def _browse_ffmpeg_dir(self) -> None:
        d = QFileDialog.getExistingDirectory(self, "FFmpeg klasörünü seç", self.ff_edit.text())
        if d:
            self.ff_edit.setText(d)

    def _refresh_ffmpeg_status(self) -> None:
        d = find_ffmpeg_dir(self.ff_edit.text())
        if d:
            self.ff_status.setText(f"<span style='color:#66bb6a'>FFmpeg bulundu:</span> {d}")
        else:
            self.ff_status.setText("<span style='color:#ef5350'>FFmpeg bulunamadı.</span> "
                                   "MP3 dönüştürme için gereklidir.")

    def _auto_ffmpeg(self) -> None:
        path = install_ffmpeg_interactive(self)
        if path:
            self.ff_edit.setText(path)
            self._refresh_ffmpeg_status()

    def _update_ytdlp(self) -> None:
        self.btn_update.setEnabled(False)
        self.btn_update.setText("Güncelleniyor...")
        self._updater = YtdlpUpdater()
        self._updater.done.connect(self._on_update_done)
        self._updater.start()

    def _on_update_done(self, ok: bool) -> None:
        self.btn_update.setEnabled(True)
        self.btn_update.setText("yt-dlp'yi Güncelle")
        if ok:
            QMessageBox.information(self, "Güncelleme", "yt-dlp güncellendi.\n"
                                    "Yeni sürümün devreye girmesi için uygulamayı yeniden başlatın.")
        else:
            QMessageBox.warning(self, "Güncelleme", "yt-dlp güncellenemedi. İnternet bağlantınızı "
                                "kontrol edip tekrar deneyin.")
        if self._updater is not None:
            self._updater.wait()
            self._updater.deleteLater()
            self._updater = None

    def reject(self) -> None:
        self._wait_updater()
        super().reject()

    def _wait_updater(self) -> None:
        if self._updater is not None and self._updater.isRunning():
            self._updater.wait(60000)

    # -- kaydet --------------------------------------------------------------------------------
    def _save(self) -> None:
        directory = self.dir_edit.text().strip()
        if not directory:
            QMessageBox.warning(self, "Ayarlar", "Lütfen bir kayıt klasörü belirtin.")
            return
        try:
            os.makedirs(directory, exist_ok=True)
        except OSError as exc:
            QMessageBox.warning(self, "Ayarlar", f"Klasör oluşturulamadı:\n{exc}")
            return

        ff_text = self.ff_edit.text().strip()
        if ff_text:
            resolved = find_ffmpeg_dir(ff_text)
            if not resolved:
                QMessageBox.warning(self, "Ayarlar", "Belirtilen FFmpeg yolunda ffmpeg bulunamadı.\n"
                                    "Boş bırakırsanız otomatik aranır.")
                return
            ff_text = resolved

        self._q[self._cur_fmt] = self.q_combo.currentText()
        if self.unlimited_chk.isChecked():
            kbps = 0
        else:
            kbps = self.speed_spin.value() * (1024 if self.unit_combo.currentText() == "MB/s" else 1)

        self._wait_updater()
        self.config.update({
            "download_dir": directory,
            "default_format": self.fmt_combo.currentText(),
            "mp3_quality": self._q["MP3"],
            "mp4_quality": self._q["MP4"],
            "max_concurrent": self.conc_spin.value(),
            "speed_limit_kbps": kbps,
            "ffmpeg_path": ff_text,
            "compat_mp4": self.compat_chk.isChecked(),
            "cookies_path": self.cookie_edit.text().strip(),
        })
        self.config.save()
        self.accept()


# =============================================================================
# 5) ANA PENCERE
# =============================================================================
@dataclass
class DownloadItem:
    id: int
    url: str
    video_id: str
    title: str
    duration: float
    fmt: str
    quality: str
    out_dir: str
    size: float = 0.0
    status: str = ST_WAIT
    percent: float = 0.0
    speed: float = 0.0
    eta: float = -1.0
    filepath: str = ""
    error: str = ""
    site: str = ""
    thumb: str = ""
    reencode: bool = False


COL_ID, COL_TITLE, COL_SITE, COL_FMT, COL_SIZE, COL_PROG, COL_SPEED, COL_ETA, COL_STATUS = range(9)
HEADERS = ["ID", "Başlık", "Site", "Format/Kalite", "Boyut", "İlerleme", "Hız",
           "Kalan Süre (ETA)", "Durum"]


class MainWindow(QMainWindow):
    def __init__(self, config: ConfigManager):
        super().__init__()
        self.config = config
        self.items: dict[int, DownloadItem] = {}
        self.workers: dict[int, DownloadWorker] = {}
        self._cells: dict[int, dict] = {}
        self._next_id = 1
        self._pending_meta = 0

        self.meta_pool = QThreadPool()
        self.meta_pool.setMaxThreadCount(2)
        self.thumb_pool = QThreadPool()
        self.thumb_pool.setMaxThreadCount(4)

        self.setWindowTitle(APP_NAME)
        self.resize(1180, 640)
        self._build_ui()

        self.config.changed.connect(self._on_config_changed)
        self.timer = QTimer(self)
        self.timer.timeout.connect(self._update_summary)
        self.timer.start(500)
        QTimer.singleShot(400, self._startup_checks)

    # ------------------------------------------------------------------ arayüz
    def _build_ui(self) -> None:
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)

        # Üst çubuk: link girişi + format + kalite
        top = QHBoxLayout()
        self.url_edit = QLineEdit()
        self.url_edit.setPlaceholderText("Video bağlantısını yapıştırın: YouTube, X, Instagram, TikTok, Facebook... "
                                         "(birden fazla link girebilirsiniz)")
        self.url_edit.setClearButtonEnabled(True)
        self.url_edit.returnPressed.connect(self._on_add)
        self.fmt_combo = QComboBox()
        self.fmt_combo.addItems(FORMATS)
        self.fmt_combo.setCurrentText(self.config.get("default_format"))
        self.fmt_combo.currentTextChanged.connect(lambda _t: (self._populate_quality(), self._apply_selection()))
        self.quality_combo = QComboBox()
        self.quality_combo.setToolTip("MP4 'Best': en yüksek çözünürlük (FFmpeg ile birleştirilir).\n"
                                      "Sabit çözünürlüklerde uyumlu H.264 MP4 tercih edilir.")
        self._populate_quality()
        self.quality_combo.currentTextChanged.connect(lambda _t: self._apply_selection())
        btn_add = QPushButton("Listeye Ekle")
        btn_add.clicked.connect(lambda: self._on_add())
        btn_paste = QPushButton("Panodan Ekle")
        btn_paste.clicked.connect(self._on_add_clipboard)
        self.btn_start = QPushButton("İndirmeye Başla")
        self.btn_start.setStyleSheet("QPushButton{background:#3d8bfd;color:white;font-weight:bold;padding:4px 14px;}"
                                     "QPushButton:disabled{background:#3a3a3e;color:#888;}")
        self.btn_start.setToolTip("Listedeki 'Hazır' öğeleri, seçili format/kaliteyle indirmeye başlatır.")
        self.btn_start.clicked.connect(self._act_start_ready)
        self.btn_start.setEnabled(False)
        self.compat_chk = QCheckBox("Her cihazda oynat (H.264+AAC)")
        self.compat_chk.setToolTip("MP4'ü TV, medya oynatıcı ve her cihazın açabildiği H.264 + AAC olarak kaydeder.\n"
                                   "Kapatırsanız en yüksek kalitedeki ham biçim alınır (bazı TV'lerde görüntü olmayabilir).")
        self.compat_chk.setChecked(bool(self.config.get("compat_mp4")))
        self.compat_chk.toggled.connect(lambda v: self.config.set("compat_mp4", v) or self.config.save())
        top.addWidget(self.url_edit, 1)
        top.addWidget(QLabel("Format:"))
        top.addWidget(self.fmt_combo)
        top.addWidget(QLabel("Kalite:"))
        top.addWidget(self.quality_combo)
        top.addWidget(btn_add)
        top.addWidget(btn_paste)
        top.addWidget(self.btn_start)
        root.addLayout(top)
        root.addWidget(self.compat_chk)

        # Tablo
        self.table = QTableWidget(0, len(HEADERS))
        self.table.setHorizontalHeaderLabels(HEADERS)
        self.table.verticalHeader().setVisible(False)
        self.table.verticalHeader().setDefaultSectionSize(48)
        self.table.setIconSize(QSize(64, 36))
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.setAlternatingRowColors(True)
        self.table.setShowGrid(False)
        self.table.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.table.customContextMenuRequested.connect(self._show_context_menu)
        hdr = self.table.horizontalHeader()
        hdr.setSectionResizeMode(COL_TITLE, QHeaderView.ResizeMode.Stretch)
        for col, w in ((COL_ID, 48), (COL_SITE, 90), (COL_FMT, 110), (COL_SIZE, 90), (COL_PROG, 190),
                       (COL_SPEED, 100), (COL_ETA, 110), (COL_STATUS, 120)):
            hdr.setSectionResizeMode(col, QHeaderView.ResizeMode.Interactive)
            self.table.setColumnWidth(col, w)
        self.table.doubleClicked.connect(lambda _i: self._act_show_folder())
        sc = QShortcut(QKeySequence("Delete"), self.table)
        sc.setContext(Qt.ShortcutContext.WidgetShortcut)
        sc.activated.connect(self._act_remove)
        root.addWidget(self.table, 1)

        # Alt çubuk: aksiyon butonları
        bottom = QHBoxLayout()
        for text, slot in (("Tümünü Başlat", self._act_start_all),
                           ("Tümünü Durdur", self._act_stop_all),
                           ("Seçiliyi Durdur", self._act_stop),
                           ("Seçiliyi Başlat/Devam", self._act_resume),
                           ("Klasörde Göster", self._act_show_folder),
                           ("Seçiliyi Kaldır", self._act_remove),
                           ("Tamamlananları Temizle", self._act_clear_done)):
            b = QPushButton(text)
            b.clicked.connect(slot)
            bottom.addWidget(b)
        bottom.addStretch(1)
        btn_settings = QPushButton("Ayarlar")
        btn_settings.clicked.connect(self._open_settings)
        bottom.addWidget(btn_settings)
        root.addLayout(bottom)

        # Durum çubuğu
        self.summary = QLabel("")
        sb = QStatusBar()
        sb.addPermanentWidget(self.summary)
        self.setStatusBar(sb)
        self.setStyleSheet(
            "QProgressBar{border:1px solid #555;border-radius:4px;text-align:center;"
            "background:#2b2b2e;color:white;}"
            "QProgressBar::chunk{background:#3d8bfd;border-radius:3px;}"
            "QTableWidget{gridline-color:#444;}"
        )

    def _populate_quality(self) -> None:
        fmt = self.fmt_combo.currentText()
        self.quality_combo.blockSignals(True)
        self.quality_combo.clear()
        if fmt == "MP3":
            self.quality_combo.addItems(MP3_QUALITIES)
            self.quality_combo.setCurrentText(self.config.get("mp3_quality"))
        else:
            self.quality_combo.addItems(MP4_QUALITIES)
            self.quality_combo.setCurrentText(self.config.get("mp4_quality"))
        self.quality_combo.blockSignals(False)

    # --------------------------------------------------------- açılış kontrolleri
    def _ffmpeg_dir(self) -> Optional[str]:
        return find_ffmpeg_dir(self.config.get("ffmpeg_path"))

    def _startup_checks(self) -> None:
        if not self._ffmpeg_dir() and not self.config.get("ffmpeg_asked"):
            self.config.set("ffmpeg_asked", True)
            self.config.save()
            self._offer_ffmpeg()
        if not self._ffmpeg_dir():
            self.statusBar().showMessage("FFmpeg bulunamadı: MP3 dönüştürme ve yüksek kalite "
                                         "video birleştirme devre dışı.", 10000)

    def _offer_ffmpeg(self) -> bool:
        """FFmpeg kurulumunu teklif eder. Sonunda FFmpeg varsa True döner."""
        ans = QMessageBox.question(
            self, "FFmpeg gerekli",
            "MP3 dönüştürme ve video+ses birleştirme için FFmpeg gerekir; bilgisayarınızda bulunamadı.\n\n"
            "Şimdi otomatik olarak indirilip kurulsun mu? (yaklaşık 100 MB)")
        if ans == QMessageBox.StandardButton.Yes:
            path = install_ffmpeg_interactive(self)
            if path:
                self.config.set("ffmpeg_path", path)
                self.config.save()
        return bool(self._ffmpeg_dir())

    # ------------------------------------------------------------ link ekleme
    def _on_add_clipboard(self) -> None:
        self._on_add(QApplication.clipboard().text() or "")

    def _on_add(self, text: Optional[str] = None) -> None:
        from_edit = text is None
        text = self.url_edit.text() if from_edit else text
        urls = re.findall(r"https?://\S+", text or "")
        if not urls:
            QMessageBox.information(self, APP_NAME, "Geçerli bir bağlantı bulunamadı.")
            return
        fmt = self.fmt_combo.currentText()
        if fmt == "MP3" and not self._ffmpeg_dir():
            if not self._offer_ffmpeg():
                QMessageBox.information(self, APP_NAME, "MP3 için FFmpeg gerekli. "
                                        "İsterseniz MP4 formatını seçebilirsiniz.")
                return
        invalid, seen = [], set()
        for u in urls:
            if u in seen:
                continue
            seen.add(u)
            if not self._submit_url(u):
                invalid.append(u)
        if invalid:
            QMessageBox.warning(self, APP_NAME, "Desteklenmeyen veya geçersiz bağlantılar:\n\n"
                                + "\n".join(invalid[:10]))
        if from_edit:
            self.url_edit.clear()

    def _submit_url(self, url: str) -> bool:
        url = url.rstrip(".,;)]}\"'>")
        kind, vid, lid = classify_url(url)
        if kind == "invalid":
            return False
        generic = kind == "other"
        is_playlist = kind == "playlist"
        if kind == "both":
            box = QMessageBox(self)
            box.setWindowTitle("Oynatma listesi algılandı")
            box.setText("Bu bağlantı bir oynatma listesine ait bir video içeriyor.\nNe eklensin?")
            b_list = box.addButton("Tüm Listeyi Ekle", QMessageBox.ButtonRole.AcceptRole)
            b_one = box.addButton("Sadece Bu Videoyu Ekle", QMessageBox.ButtonRole.ActionRole)
            box.addButton("İptal", QMessageBox.ButtonRole.RejectRole)
            box.exec()
            clicked = box.clickedButton()
            if clicked is b_list:
                is_playlist = True
            elif clicked is b_one:
                is_playlist = False
            else:
                return True  # kullanıcı vazgeçti (hata değil)
        if is_playlist and lid:
            target = f"https://www.youtube.com/playlist?list={lid}"
        elif vid:
            target = f"https://www.youtube.com/watch?v={vid}"
        else:
            target = url

        task = MetaTask(target, is_playlist, self.fmt_combo.currentText(),
                        self.quality_combo.currentText(), bool(self._ffmpeg_dir()),
                        compat=bool(self.config.get("compat_mp4")),
                        cookies=self.config.get("cookies_path"), generic=generic)
        task.signals.result.connect(self._on_meta_result)
        task.signals.error.connect(self._on_meta_error)
        self._pending_meta += 1
        self.meta_pool.start(task)
        return True

    def _on_meta_result(self, res: dict) -> None:
        self._pending_meta = max(0, self._pending_meta - 1)
        base = self.config.get("download_dir")
        out_dir = base
        if res["is_playlist"]:
            out_dir = os.path.join(base, safe_filename(res["playlist_title"]))
        # Ana ekranda şu an seçili olan biçim/kalite geçerlidir (çözümleme sırasında değişmiş olabilir)
        fmt, quality = self.fmt_combo.currentText(), self.quality_combo.currentText()
        generic = bool(res.get("generic"))
        known = {(i.url, i.fmt, i.quality) for i in self.items.values()}
        added = dup = 0
        for e in res["entries"]:
            if (e["url"], fmt, quality) in known:
                dup += 1
                continue
            size = e["size"]
            if fmt == "MP3":
                size = estimate_mp3_size(e["duration"], quality)
            item = DownloadItem(
                id=self._next_id, url=e["url"], video_id=e["video_id"], title=e["title"],
                duration=e["duration"], fmt=fmt, quality=quality, out_dir=out_dir, size=size,
                status=ST_READY, site=e.get("site", ""), thumb=e.get("thumb", ""),
                reencode=(res["fmt"] == "MP4" and bool(e.get("non_compat"))))
            known.add((item.url, fmt, quality))
            self._next_id += 1
            self.items[item.id] = item
            self._add_row(item)
            added += 1
            if item.thumb or (item.video_id and not generic):
                t = ThumbTask(item.id, item.video_id, item.thumb)
                t.signals.loaded.connect(self._on_thumb)
                self.thumb_pool.start(t)
        if added:
            msg = f"{added} öğe listeye eklendi. Format/kaliteyi seçip 'İndirmeye Başla'ya basın."
            if dup:
                msg += f" ({dup} tanesi zaten listedeydi)"
        else:
            msg = "Zaten listede: aynı video/biçim tekrar eklenmedi."
        self.statusBar().showMessage(msg, 8000)

    def _on_meta_error(self, url: str, msg: str) -> None:
        self._pending_meta = max(0, self._pending_meta - 1)
        QMessageBox.warning(self, "Bağlantı çözümlenemedi", f"{url}\n\n{msg}")

    def _on_thumb(self, item_id: int, img: QImage) -> None:
        c = self._cells.get(item_id)
        if c:
            c["title"].setIcon(QIcon(QPixmap.fromImage(img)))

    # ----------------------------------------------------------------- tablo
    def _add_row(self, item: DownloadItem) -> None:
        row = self.table.rowCount()
        self.table.insertRow(row)

        def cell(text: str, center: bool = True) -> QTableWidgetItem:
            it = QTableWidgetItem(text)
            if center:
                it.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
            return it

        c = {
            "id": cell(str(item.id)),
            "title": cell(item.title, center=False),
            "site": cell(item.site or "—"),
            "fmt": cell(f"{item.fmt} / {item.quality}"),
            "size": cell(fmt_size(item.size)),
            "speed": cell("—"),
            "eta": cell("—"),
            "status": cell(item.status),
        }
        d = fmt_duration(item.duration)
        tip = item.title + (f"\nSüre: {d}" if d else "") + f"\n{item.url}"
        c["title"].setToolTip(tip)
        bar = QProgressBar()
        bar.setRange(0, 1000)
        bar.setValue(0)
        bar.setFormat("0.0%")
        c["bar"] = bar

        self.table.setItem(row, COL_ID, c["id"])
        self.table.setItem(row, COL_TITLE, c["title"])
        self.table.setItem(row, COL_SITE, c["site"])
        self.table.setItem(row, COL_FMT, c["fmt"])
        self.table.setItem(row, COL_SIZE, c["size"])
        self.table.setCellWidget(row, COL_PROG, bar)
        self.table.setItem(row, COL_SPEED, c["speed"])
        self.table.setItem(row, COL_ETA, c["eta"])
        self.table.setItem(row, COL_STATUS, c["status"])
        self._cells[item.id] = c
        self._refresh_row(item)

    def _refresh_row(self, item: DownloadItem) -> None:
        c = self._cells.get(item.id)
        if not c:
            return
        c["size"].setText(fmt_size(item.size))
        c["fmt"].setText(f"{item.fmt} / {item.quality}")
        downloading = item.status == ST_DL
        c["speed"].setText(fmt_speed(item.speed) if downloading else "—")
        c["eta"].setText(fmt_eta(item.eta) if downloading else "—")
        c["status"].setText(item.status)
        c["status"].setForeground(QBrush(QColor(STATUS_COLORS.get(item.status, "#ffffff"))))
        c["status"].setToolTip(item.error if item.status == ST_ERR else "")
        c["bar"].setValue(int(max(0.0, min(100.0, item.percent)) * 10))
        c["bar"].setFormat(f"{item.percent:.1f}%")

    def _selected_items(self) -> list[DownloadItem]:
        out = []
        for idx in self.table.selectionModel().selectedRows():
            id_item = self.table.item(idx.row(), COL_ID)
            if id_item:
                it = self.items.get(int(id_item.text()))
                if it:
                    out.append(it)
        return out

    # ------------------------------------------------------------ kuyruk mantığı
    def _ratelimit_bps(self) -> int:
        kb = int(self.config.get("speed_limit_kbps"))
        return kb * 1024 if kb > 0 else 0

    def _schedule(self) -> None:
        """Eşzamanlı indirme limitine göre bekleyen öğeleri başlatır."""
        limit = int(self.config.get("max_concurrent"))
        for item in list(self.items.values()):
            if len(self.workers) >= limit:
                break
            if item.status == ST_WAIT and item.id not in self.workers:
                self._start_item(item)

    def _start_item(self, item: DownloadItem) -> None:
        worker = DownloadWorker(item.id, item.url, item.fmt, item.quality, item.out_dir,
                                self._ratelimit_bps(), self._ffmpeg_dir(),
                                compat=bool(self.config.get("compat_mp4")), reencode=item.reencode,
                                cookies=self.config.get("cookies_path"))
        worker.progress.connect(self._on_progress)
        worker.state.connect(self._on_state)
        worker.completed.connect(self._on_completed)
        worker.failed.connect(self._on_failed)
        worker.stopped.connect(self._on_stopped)
        worker.finished.connect(partial(self._on_worker_finished, item.id))
        item.status = ST_DL
        item.percent, item.speed, item.eta, item.error = 0.0, 0.0, -1.0, ""
        self.workers[item.id] = worker
        self._refresh_row(item)
        worker.start()

    def _on_worker_finished(self, item_id: int) -> None:
        w = self.workers.pop(item_id, None)
        if w is not None:
            w.deleteLater()      # iş parçacığı nesnesini güvenle serbest bırak
        self._schedule()

    # -- worker sinyalleri (hepsi ana iş parçacığında çalışır) ------------------
    def _on_progress(self, item_id: int, pct: float, speed: float, eta: float, total: float) -> None:
        item = self.items.get(item_id)
        if not item or item.status not in ACTIVE_STATES:
            return
        item.percent, item.speed, item.eta = pct, speed, eta
        if total > 0:
            item.size = total
        self._refresh_row(item)

    def _on_state(self, item_id: int, status: str) -> None:
        item = self.items.get(item_id)
        if item and item.status in ACTIVE_STATES:
            item.status = status
            self._refresh_row(item)

    def _on_completed(self, item_id: int, path: str) -> None:
        item = self.items.get(item_id)
        if not item or item.status not in ACTIVE_STATES:
            return
        item.status, item.percent, item.speed, item.eta = ST_DONE, 100.0, 0.0, 0.0
        item.filepath = path
        try:
            if path and os.path.isfile(path):
                item.size = float(os.path.getsize(path))
        except OSError:
            pass
        self._refresh_row(item)

    def _on_failed(self, item_id: int, msg: str) -> None:
        item = self.items.get(item_id)
        if not item or item.status not in ACTIVE_STATES:
            return
        item.status, item.error, item.speed, item.eta = ST_ERR, msg, 0.0, -1.0
        self._refresh_row(item)
        self.statusBar().showMessage(f"Hata: {item.title[:50]} - {msg[:120]}", 10000)

    def _on_stopped(self, item_id: int) -> None:
        item = self.items.get(item_id)
        if item and item.status in ACTIVE_STATES:
            item.status, item.speed, item.eta = ST_STOP, 0.0, -1.0
            self._refresh_row(item)

    # ------------------------------------------------------------- aksiyonlar
    def _stop_item(self, item: DownloadItem) -> None:
        if item.status in ACTIVE_STATES:
            w = self.workers.get(item.id)
            if w:
                w.cancel()
            item.status, item.speed, item.eta = ST_STOP, 0.0, -1.0
        elif item.status == ST_WAIT:
            item.status = ST_STOP
        self._refresh_row(item)

    def _apply_selection(self) -> None:
        """'Hazır' öğelere üstte seçili format/kaliteyi uygular (indirme başlamaz)."""
        fmt, quality = self.fmt_combo.currentText(), self.quality_combo.currentText()
        if not quality:
            return
        for it in self.items.values():
            if it.status == ST_READY and (it.fmt != fmt or it.quality != quality):
                it.fmt, it.quality = fmt, quality
                if fmt == "MP3":
                    it.size = estimate_mp3_size(it.duration, quality)
                else:
                    it.size = 0.0
                self._refresh_row(it)

    def _act_start_ready(self) -> None:
        n = 0
        if any(i.status == ST_READY and i.fmt == "MP3" for i in self.items.values()) and not self._ffmpeg_dir():
            if not self._offer_ffmpeg():
                QMessageBox.information(self, APP_NAME, "MP3 için FFmpeg gerekli. "
                                        "İsterseniz MP4 formatını seçebilirsiniz.")
                return
        for it in list(self.items.values()):
            if it.status == ST_READY:
                it.status = ST_WAIT
                self._refresh_row(it)
                n += 1
        if n:
            self.statusBar().showMessage(f"{n} öğe indirme kuyruğuna alındı.", 5000)
        self._schedule()

    def _resume_item(self, item: DownloadItem) -> None:
        if item.status == ST_READY:
            item.status = ST_WAIT
            self._refresh_row(item)
        elif item.status in (ST_STOP, ST_ERR):
            item.status, item.error, item.percent = ST_WAIT, "", 0.0
            self._refresh_row(item)

    def _remove_item(self, item: DownloadItem) -> None:
        w = self.workers.get(item.id)
        if w:
            w.cancel()    # iş parçacığı kendiliğinden kapanır, sonra bellekten silinir
        c = self._cells.pop(item.id, None)
        if c:
            row = self.table.row(c["id"])
            if row >= 0:
                self.table.removeRow(row)
        self.items.pop(item.id, None)

    def _act_stop(self) -> None:
        for it in self._selected_items():
            self._stop_item(it)

    def _act_resume(self) -> None:
        for it in self._selected_items():
            self._resume_item(it)
        self._schedule()

    def _act_remove(self) -> None:
        for it in self._selected_items():
            self._remove_item(it)

    def _act_show_folder(self) -> None:
        sel = self._selected_items()
        if not sel:
            return
        it = sel[0]
        reveal_in_folder(it.filepath, it.out_dir)

    def _act_copy_link(self) -> None:
        sel = self._selected_items()
        if sel:
            QApplication.clipboard().setText("\n".join(i.url for i in sel))

    def _act_start_all(self) -> None:
        for it in list(self.items.values()):
            self._resume_item(it)
        self._schedule()

    def _act_stop_all(self) -> None:
        for it in list(self.items.values()):
            self._stop_item(it)

    def _act_clear_done(self) -> None:
        for it in [i for i in self.items.values() if i.status == ST_DONE]:
            self._remove_item(it)

    def _show_context_menu(self, pos) -> None:
        idx = self.table.indexAt(pos)
        if idx.isValid() and not self.table.selectionModel().isRowSelected(idx.row()):
            self.table.selectRow(idx.row())
        sel = self._selected_items()
        if not sel:
            return
        menu = QMenu(self)
        a_stop = menu.addAction("Durdur / İptal Et")
        a_stop.setEnabled(any(i.status in ACTIVE_STATES or i.status == ST_WAIT for i in sel))
        a_resume = menu.addAction("Başlat / Devam Et / Yeniden Dene")
        a_resume.setEnabled(any(i.status in (ST_STOP, ST_ERR, ST_READY) for i in sel))
        menu.addSeparator()
        a_show = menu.addAction("Klasörde Göster")
        a_copy = menu.addAction("Bağlantıyı Kopyala")
        menu.addSeparator()
        a_remove = menu.addAction("Listeden Kaldır")
        chosen = menu.exec(self.table.viewport().mapToGlobal(pos))
        if chosen is a_stop:
            self._act_stop()
        elif chosen is a_resume:
            self._act_resume()
        elif chosen is a_show:
            self._act_show_folder()
        elif chosen is a_copy:
            self._act_copy_link()
        elif chosen is a_remove:
            self._act_remove()

    # ------------------------------------------------------- ayarlar / durum
    def _open_settings(self) -> None:
        SettingsDialog(self.config, self).exec()

    def _on_config_changed(self) -> None:
        # Varsayılanlar değiştiyse üst çubuktaki seçimleri güncelle
        self.fmt_combo.blockSignals(True)
        self.fmt_combo.setCurrentText(self.config.get("default_format"))
        self.fmt_combo.blockSignals(False)
        self._populate_quality()
        self.compat_chk.blockSignals(True)
        self.compat_chk.setChecked(bool(self.config.get("compat_mp4")))
        self.compat_chk.blockSignals(False)
        self._apply_selection()
        # Çalışan indirmelere yeni hız sınırını canlı uygula
        bps = self._ratelimit_bps()
        for w in self.workers.values():
            w.set_ratelimit(bps)
        # Eşzamanlı limit artmış olabilir
        self._schedule()

    def _update_summary(self) -> None:
        act = wait = done = err = ready = 0
        total_speed = 0.0
        for i in self.items.values():
            if i.status == ST_READY:
                ready += 1
            elif i.status in ACTIVE_STATES:
                act += 1
            elif i.status == ST_WAIT:
                wait += 1
            elif i.status == ST_DONE:
                done += 1
            elif i.status == ST_ERR:
                err += 1
            if i.status == ST_DL:
                total_speed += i.speed
        self.btn_start.setEnabled(ready > 0)
        self.btn_start.setText(f"İndirmeye Başla ({ready})" if ready else "İndirmeye Başla")
        txt = (f"Hazır: {ready}  |  Aktif: {act}  |  Bekleyen: {wait}  |  Tamamlanan: {done}  |  Hata: {err}  |  "
               f"Toplam hız: {fmt_speed(total_speed)}")
        if self._pending_meta:
            txt += f"  |  Çözümleniyor: {self._pending_meta}"
        self.summary.setText(txt)

    # ------------------------------------------------------------- kapanış
    def closeEvent(self, event) -> None:
        if any(i.status in ACTIVE_STATES for i in self.items.values()):
            ans = QMessageBox.question(
                self, "Çıkış", "Devam eden indirmeler var. Çıkmak istiyor musunuz?\n"
                "(Yarım kalan dosyalar sonra devam ettirilebilir.)")
            if ans != QMessageBox.StandardButton.Yes:
                event.ignore()
                return
        self.timer.stop()
        for w in list(self.workers.values()):
            w.cancel()
        for w in list(self.workers.values()):
            if not w.wait(8000):       # son çare: takılan iş parçacığını sonlandır
                w.terminate()
                w.wait(1000)
        self.workers.clear()
        self.meta_pool.clear()
        self.thumb_pool.clear()
        self.meta_pool.waitForDone(3000)
        self.thumb_pool.waitForDone(3000)
        self.config.save()
        super().closeEvent(event)


# =============================================================================
# 6) GİRİŞ NOKTASI
# =============================================================================
def apply_dark_theme(app: QApplication) -> None:
    app.setStyle("Fusion")
    p = QPalette()
    R = QPalette.ColorRole
    p.setColor(R.Window, QColor(37, 37, 40))
    p.setColor(R.WindowText, QColor(230, 230, 230))
    p.setColor(R.Base, QColor(30, 30, 32))
    p.setColor(R.AlternateBase, QColor(42, 42, 46))
    p.setColor(R.ToolTipBase, QColor(50, 50, 55))
    p.setColor(R.ToolTipText, QColor(230, 230, 230))
    p.setColor(R.Text, QColor(230, 230, 230))
    p.setColor(R.PlaceholderText, QColor(140, 140, 145))
    p.setColor(R.Button, QColor(52, 52, 57))
    p.setColor(R.ButtonText, QColor(230, 230, 230))
    p.setColor(R.BrightText, QColor(255, 80, 80))
    p.setColor(R.Highlight, QColor(61, 139, 253))
    p.setColor(R.HighlightedText, QColor(255, 255, 255))
    for role in (R.Text, R.ButtonText, R.WindowText):
        p.setColor(QPalette.ColorGroup.Disabled, role, QColor(120, 120, 125))
    app.setPalette(p)


def _install_excepthook() -> None:
    """PyQt6'da yakalanmamış istisnalar uygulamayı sonlandırır; bunun yerine logla."""
    log_path = os.path.join(DATA_DIR, "hata.log")

    def hook(exc_type, exc, tb):
        text = "".join(traceback.format_exception(exc_type, exc, tb))
        try:
            with open(log_path, "a", encoding="utf-8") as fh:
                fh.write(time.strftime("[%Y-%m-%d %H:%M:%S]\n") + text + "\n")
        except OSError:
            pass
        print(text, file=sys.stderr)

    sys.excepthook = hook


def main() -> int:
    _install_excepthook()
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    apply_dark_theme(app)
    config = ConfigManager()
    config.load()
    window = MainWindow(config)
    window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
