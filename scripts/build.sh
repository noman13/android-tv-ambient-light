#!/usr/bin/env bash
# Builds the release APK inside Docker and copies it to dist/tv-ambient-<version>.apk.
#
#   scripts/build.sh                      # release APK (debug-signed unless a keystore is given)
#   KEYSTORE_DIR=/path/to/keys scripts/build.sh   # sign with keys/keystore.properties (see README)
#
# Needs Docker only. The first run builds the image (JDK 17 + Android SDK 34, a few minutes) and Gradle fills the
# tv-ambient-gradle volume; later builds take well under a minute.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${IMAGE:-tv-ambient-build}"
VOLUME="${GRADLE_VOLUME:-tv-ambient-gradle}"

command -v docker >/dev/null || { echo "Docker is not installed or not on PATH." >&2; exit 1; }

SRC="$ROOT"
USER_ARGS=()
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) export MSYS_NO_PATHCONV=1; SRC="$(cd "$ROOT" && pwd -W)" ;;
  *) USER_ARGS=(--user "$(id -u):$(id -g)") ;;
esac

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Building the $IMAGE image once..."
  docker build --platform linux/amd64 -t "$IMAGE" "$SRC/docker"
fi

KEY_ARGS=()
GRADLE_ARGS=(--no-daemon --console=plain assembleRelease)
if [ -n "${KEYSTORE_DIR:-}" ]; then
  [ -f "$KEYSTORE_DIR/keystore.properties" ] || { echo "No keystore.properties in $KEYSTORE_DIR" >&2; exit 1; }
  KDIR="$(cd "$KEYSTORE_DIR" && (pwd -W 2>/dev/null || pwd))"
  KEY_ARGS=(-v "$KDIR:/keys:ro")
  GRADLE_ARGS+=(-PkeystoreProperties=/keys/keystore.properties)
  echo "Signing with $KEYSTORE_DIR/keystore.properties"
else
  echo "No KEYSTORE_DIR: the APK is signed with the build container's debug key."
fi

docker run --rm --platform linux/amd64 ${USER_ARGS[@]+"${USER_ARGS[@]}"} ${KEY_ARGS[@]+"${KEY_ARGS[@]}"} \
  -v "$SRC:/src" -v "$VOLUME:/gradle" -w /src -e ANDROID_USER_HOME=/gradle/.android \
  "$IMAGE" sh ./gradlew "${GRADLE_ARGS[@]}"

VERSION="$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" "$ROOT/app/build.gradle")"
mkdir -p "$ROOT/dist"
cp "$ROOT/app/build/outputs/apk/release/app-release.apk" "$ROOT/dist/tv-ambient-$VERSION.apk"
echo "Built dist/tv-ambient-$VERSION.apk"
