#!/usr/bin/env bash
# Tek komutla yeni Android sürümü: sürüm numarasını artırır, imzalı APK derler,
# update.json + releases/ günceller, commit eder ve GitHub'a gönderir.
# Kullanım: tools/surum_yayinla.sh 1.4.0 "Yenilikler"
# Gerekli: android/keystore.properties (imza anahtarı), JDK 17+, Android SDK (local.properties).
set -euo pipefail
NAME="${1:?sürüm adı (örn. 1.4.0)}"; NOTES="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT/android"
[ -f keystore.properties ] || { echo "keystore.properties yok (imzasız APK güncelleme olarak kurulamaz)"; exit 1; }
GRADLE="${GRADLE:-./gradlew}"
CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts); CODE=$((CODE+1))
sed -i "s/versionCode = [0-9]*/versionCode = $CODE/; s/versionName = \"[^\"]*\"/versionName = \"$NAME\"/" app/build.gradle.kts
$GRADLE :app:testDebugUnitTest :app:assembleRelease -q
cd "$ROOT"
python3 tools/yayinla.py android/app/build/outputs/apk/release/app-release.apk --code "$CODE" --name "$NAME" --notes "$NOTES"
git add -A && git commit -qm "v$NAME: $NOTES" && git push origin main
echo "Yayınlandı: v$NAME (kod $CODE)"
