#!/usr/bin/env bash
#
# Prepare Android sandbox assets:
#   1. Download Alpine Linux aarch64 minirootfs
#   2. Verify the source-built PRoot artifacts used by the Android runtime
#
# Usage: ./scripts/prepare_android_sandbox.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"

ALPINE_VERSION="3.21"
ALPINE_RELEASE="3.21.3"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"
ALPINE_SHA256="ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea"

mkdir -p "$ASSETS_DIR"

ROOTFS_FILE="$ASSETS_DIR/alpine-minirootfs.tar.gz"
PROOT_FILE="$ASSETS_DIR/proot-aarch64"
PROOT_JNI_FILE="$PROJECT_ROOT/src/android/app/src/main/jniLibs/arm64-v8a/libproot.so"

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# --- Alpine rootfs ---
if [ -f "$ROOTFS_FILE" ]; then
    echo "✓ Alpine rootfs already exists: $ROOTFS_FILE"
else
    echo "Downloading Alpine Linux ${ALPINE_RELEASE} aarch64 minirootfs..."
    ROOTFS_TMP="${ROOTFS_FILE}.download"
    rm -f "$ROOTFS_TMP"
    curl -fSL -o "$ROOTFS_TMP" "$ALPINE_URL"
    ACTUAL_SHA256="$(sha256_file "$ROOTFS_TMP")"
    if [ "$ACTUAL_SHA256" != "$ALPINE_SHA256" ]; then
        rm -f "$ROOTFS_TMP"
        echo "Error: Alpine rootfs SHA-256 mismatch" >&2
        exit 1
    fi
    mv "$ROOTFS_TMP" "$ROOTFS_FILE"
    echo "✓ Downloaded: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"
fi

# Verify an existing rootfs too; a partial/corrupt cached download must never
# silently enter an APK.
ACTUAL_SHA256="$(sha256_file "$ROOTFS_FILE")"
if [ "$ACTUAL_SHA256" != "$ALPINE_SHA256" ]; then
    echo "Error: cached Alpine rootfs SHA-256 mismatch: $ROOTFS_FILE" >&2
    exit 1
fi

# PRoot must come from the pinned OpenMinis fork build. A generic Termux .deb
# does not populate nativeLibraryDir, which is the path RootfsManager executes.
for required in "$PROOT_FILE" "$PROOT_JNI_FILE"; do
    if [ ! -f "$required" ]; then
        echo "Error: missing PRoot artifact: $required" >&2
        echo "Run ./deps/build_proot.sh first (Android NDK r28+ required)." >&2
        exit 1
    fi
done

if ! cmp -s "$PROOT_FILE" "$PROOT_JNI_FILE"; then
    echo "Error: PRoot asset and libproot.so differ; rebuild both together." >&2
    exit 1
fi
echo "✓ Verified PRoot binary: $PROOT_FILE ($(du -h "$PROOT_FILE" | cut -f1))"

echo ""
echo "Assets ready in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
