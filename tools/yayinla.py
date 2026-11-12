#!/usr/bin/env python3
"""
Yeni sürümü yayınlar: APK'yı releases/ klasörüne koyar, update.json'u (sürüm, SHA-256, notlar,
eklentiler, ön ayarlar) günceller. Sonra `git add -A && git commit && git push` yeterlidir;
telefondaki uygulamalar bir sonraki denetimde yeni sürümü görür.

Kullanım:
  python tools/yayinla.py <apk> --code 5 --name 1.4.0 --notes "Yenilikler"
Eklenti (yt-dlp .py dosyası) ve ön ayarlar için update.json'daki "plugins" ve "presets"
alanlarını elle düzenleyebilirsiniz (python tools/yayinla.py --plugin dosya.py ile hash eklenir).
"""
import argparse, hashlib, json, os, shutil, sys

REPO = "lViRTuSl/viddown"
RAW = f"https://raw.githubusercontent.com/{REPO}/main"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(ROOT, "update.json")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load():
    try:
        with open(MANIFEST, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return {"app": {}, "plugins": [], "presets": [], "message": ""}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", nargs="?")
    ap.add_argument("--code", type=int)
    ap.add_argument("--name")
    ap.add_argument("--notes", default="")
    ap.add_argument("--message")
    ap.add_argument("--plugin", action="append", default=[], help="eklenti .py dosyası (plugins/ klasörüne kopyalanır)")
    a = ap.parse_args()
    m = load()
    if a.apk:
        if not (a.code and a.name):
            sys.exit("--code ve --name gerekli")
        os.makedirs(os.path.join(ROOT, "releases"), exist_ok=True)
        for old in os.listdir(os.path.join(ROOT, "releases")):
            if old.endswith(".apk"):
                os.remove(os.path.join(ROOT, "releases", old))   # depoyu şişirmemek için eski APK'lar silinir
        fname = f"ViDDoWN-v{a.name}.apk"
        shutil.copy(a.apk, os.path.join(ROOT, "releases", fname))
        m["app"] = {"versionCode": a.code, "versionName": a.name, "notes": a.notes,
                    "apkUrl": f"{RAW}/releases/{fname}", "sha256": sha256(a.apk)}
    for p in a.plugin:
        name = os.path.basename(p)
        os.makedirs(os.path.join(ROOT, "plugins"), exist_ok=True)
        shutil.copy(p, os.path.join(ROOT, "plugins", name))
        m["plugins"] = [x for x in m.get("plugins", []) if x["name"] != name] + [
            {"name": name, "url": f"{RAW}/plugins/{name}", "sha256": sha256(p)}]
    if a.message is not None:
        m["message"] = a.message
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("update.json güncellendi:", json.dumps(m["app"], ensure_ascii=False))


if __name__ == "__main__":
    main()
