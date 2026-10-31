#!/usr/bin/env bash
# Build a minimal static ffmpeg + ffprobe for Android arm64 (API 24).
#
# Pinned source: FFmpeg n7.1.1. LGPL-only configuration (no --enable-gpl,
# no non-free components), so it can ship in a closed-source app.
#
# Inputs (env):
#   NDK_DIR     - path to an unpacked Android NDK (r27c)
#   FFMPEG_SRC  - path to the unpacked FFmpeg source tree
# Outputs:
#   $FFMPEG_SRC/ffmpeg, $FFMPEG_SRC/ffprobe  (static, self-contained)
#
# The two programs link only against Android system libraries, so each is a
# single self-contained executable. They are meant to be placed in the APK as
# jniLibs/arm64-v8a/libffmpeg.so and libffprobe.so.
set -euo pipefail

NDK_DIR=${NDK_DIR:?set NDK_DIR}
FFMPEG_SRC=${FFMPEG_SRC:?set FFMPEG_SRC}
API=${API:-24}

TC="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TC/bin/aarch64-linux-android${API}-clang"

# ---- libmp3lame ----
# So the one bundled binary can also encode MP3 (Off's audio option). Shipping
# FFmpegKit for that alone cost about 13 MB of the download.
LAME_VERSION=${LAME_VERSION:-3.100}
LAME_PREFIX=${LAME_PREFIX:-/tmp/lame-android}
if [ ! -f "$LAME_PREFIX/lib/libmp3lame.a" ]; then
  echo "building libmp3lame $LAME_VERSION for arm64"
  curl -sSL -o /tmp/lame.tar.gz \
    "https://downloads.sourceforge.net/project/lame/lame/${LAME_VERSION}/lame-${LAME_VERSION}.tar.gz"
  rm -rf /tmp/lame-src && mkdir -p /tmp/lame-src "$LAME_PREFIX"
  tar -xzf /tmp/lame.tar.gz -C /tmp/lame-src --strip-components=1
  (
    cd /tmp/lame-src
    ./configure \
      --host=aarch64-linux-android \
      --prefix="$LAME_PREFIX" \
      --disable-shared --enable-static --disable-frontend --disable-decoder \
      CC="$CC" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" \
      CFLAGS="-Os"
    make -j"$(nproc)"
    make install
  )
fi

cd "$FFMPEG_SRC"

./configure \
  --target-os=android --arch=aarch64 --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$CC" \
  --cxx="$TC/bin/aarch64-linux-android${API}-clang++" \
  --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" \
  --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
  --sysroot="$TC/sysroot" \
  --enable-static --disable-shared --enable-small \
  --disable-doc --disable-debug --disable-programs \
  --disable-avdevice --disable-postproc --disable-network \
  --enable-ffmpeg --enable-ffprobe --disable-ffplay \
  --disable-everything \
  --enable-demuxer=mov,matroska,mp3,ogg,aac,mpegts,hls,wav \
  --enable-muxer=mp4,mov,matroska,webm,ipod,mp3,adts,wav \
  --enable-parser=aac,opus,vorbis,mpeg4video \
  --enable-decoder=aac,mp3,opus,vorbis,pcm_s16le \
  --enable-encoder=libmp3lame \
  --enable-libmp3lame \
  --enable-bsf=aac_adtstoasc,extract_extradata \
  --enable-protocol=file,pipe,data,concat,crypto \
  --enable-swresample \
  --extra-cflags="-Os -I$LAME_PREFIX/include" \
  --extra-ldflags="-L$LAME_PREFIX/lib"

make -j"$(nproc)"
"$TC/bin/llvm-strip" ffmpeg ffprobe
ls -la ffmpeg ffprobe
