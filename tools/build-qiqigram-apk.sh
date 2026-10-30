#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export CMAKE_BUILD_PARALLEL_LEVEL=6
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd /work/Telegram
mkdir -p /work/build-logs /work/artifacts
test -s /root/.config/qiqigram/signing.properties
test -s /root/.config/qiqigram/qiqigram.jks

# Persist a successful-build counter outside the checkout. Lock the build so
# simultaneous invocations cannot reserve the same number.
mkdir -p /work/build-state
exec 9>/work/build-state/qiqigram.lock
flock 9
python3 tools/prepare-firebase.py "${QIQIGRAM_FIREBASE_CONFIG:-/root/.config/qiqigram/google-services.json}"
last_build=0
if [[ -f /work/build-state/qiqigram-build-number ]]; then
  read -r last_build < /work/build-state/qiqigram-build-number
fi
[[ "$last_build" =~ ^[0-9]+$ ]] || { echo "Invalid QiQiGram build counter"; exit 1; }
build_number=$((10#$last_build + 1))
printf -v fork_version '%d.%02d' "$((build_number / 100))" "$((build_number % 100))"
echo "QiQiGram version: $fork_version"

build_revision="$(git rev-parse --short=12 HEAD)"
build_log="/work/build-logs/qiqigram-${build_revision}-$(date +%Y%m%d-%H%M%S).log"
echo "Building signed QiQiGram beta from $build_revision"
echo "Build log: $build_log"

if ! bash ./gradlew :TMessagesProj_App:assembleAfatRelease \
  -PqiqigramBuildNumber="$build_number" \
  -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.injected.testOnly=false \
  --max-workers=6 --console=plain --stacktrace \
  '-Dorg.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' \
  >"$build_log" 2>&1; then
  tail -n 100 "$build_log"
  exit 1
fi

tail -n 24 "$build_log"
apk=TMessagesProj_App/build/outputs/apk/afat/release/app.apk
if [[ ! -s "$apk" ]]; then
  apk=TMessagesProj_App/build/intermediates/apk/afat/release/app.apk
fi
test -s "$apk"

artifact=/work/artifacts/qiqigram-13.1.1-beta-arm64-release.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$apk" \
  > /work/build-logs/qiqigram-apk-signature.txt
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 "$apk"
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$apk" \
  > /work/build-logs/qiqigram-apk-badging.txt
grep -q "package: name='net.arcuata.qiqigram.beta'" /work/build-logs/qiqigram-apk-badging.txt
if grep -q "^testOnly='-1'" /work/build-logs/qiqigram-apk-badging.txt; then
  echo "Release APK still has android:testOnly=true"
  exit 1
fi
cp "$apk" "$artifact"
printf '%s\n' "$build_number" > /work/build-state/qiqigram-build-number.tmp
mv /work/build-state/qiqigram-build-number.tmp /work/build-state/qiqigram-build-number
sha256sum "$artifact"
head -n 3 /work/build-logs/qiqigram-apk-badging.txt
grep -E 'Signer #1 certificate SHA-256 digest|Verified using v[123]' \
  /work/build-logs/qiqigram-apk-signature.txt || true
git status --short
