#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
viddown.py dosyasını tek bir çalıştırılabilir dosyaya (.exe) derler.

Kullanım:   python derle.py
Sonuç:      derlenmis/ViDDoWN.exe   (Linux/macOS'ta uzantısız)

Gereksinim: Python 3.10+ (derleme için gerekli paketleri bu betik kendisi kurar).
Derlenen program, Python kurulu olmayan bilgisayarlarda da çalışır.
"""
import os
import sys
import shutil
import subprocess
import tempfile

BASE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(BASE, "viddown.py")
APP_NAME = "ViDDoWN"
OUT_DIR = os.path.join(BASE, "derlenmis")
ICON = os.path.join(BASE, "icon.ico")          # varsa otomatik kullanılır (isteğe bağlı)
REQUIRED = ["pyinstaller", "PyQt6", "yt-dlp"]


def fail(msg: str) -> None:
    print("\nHATA:", msg)
    sys.exit(1)


def pip_install(packages: list) -> None:
    """Paketleri kurar/günceller; yönetilen ortamlar için yedek bayraklar dener."""
    base = [sys.executable, "-m", "pip", "install", "--upgrade", "--disable-pip-version-check"]
    for flags in ([], ["--user"], ["--user", "--break-system-packages"]):
        if subprocess.call(base + flags + packages) == 0:
            return
    fail("Gerekli paketler kurulamadı: " + ", ".join(packages))


def main() -> None:
    if sys.version_info < (3, 10):
        fail("Python 3.10 veya üzeri gerekli. Mevcut: " + sys.version.split()[0])
    if not os.path.isfile(SCRIPT):
        fail("viddown.py bu betikle aynı klasörde olmalı.")

    print("[1/3] Derleme araçları kontrol ediliyor/kuruluyor...")
    pip_install(REQUIRED)

    print("[2/3] Derleniyor (birkaç dakika sürebilir)...")
    work = tempfile.mkdtemp(prefix="yt_build_")
    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm", "--clean",
        "--onefile",                        # tek dosya
        "--windowed",                       # konsol penceresi açma
        "--name", APP_NAME,
        "--distpath", OUT_DIR,
        "--workpath", os.path.join(work, "build"),
        "--specpath", work,
        "--collect-all", "yt_dlp",          # yt-dlp'nin tüm çıkarıcıları dinamik yüklenir
        "--exclude-module", "tkinter",      # derlenen sürümde gerekmez
    ]
    if os.path.isfile(ICON):
        cmd += ["--icon", ICON]
    cmd.append(SCRIPT)

    try:
        code = subprocess.call(cmd, cwd=BASE)
    finally:
        shutil.rmtree(work, ignore_errors=True)   # geçici derleme dosyalarını temizle
    if code != 0:
        fail("PyInstaller derlemesi başarısız oldu (yukarıdaki mesajlara bakın).")

    exe = os.path.join(OUT_DIR, APP_NAME + (".exe" if os.name == "nt" else ""))
    if not os.path.isfile(exe):
        fail("Derleme bitti ancak çıktı dosyası bulunamadı: " + exe)

    size_mb = os.path.getsize(exe) / (1024 * 1024)
    print("\n[3/3] TAMAMLANDI")
    print(f"      Dosya : {exe}")
    print(f"      Boyut : {size_mb:.0f} MB")
    print("      Bu dosyayı istediğiniz bilgisayara kopyalayıp çalıştırabilirsiniz.")
    print("      FFmpeg ilk açılışta otomatik indirilmesi teklif edilir.")


if __name__ == "__main__":
    main()
