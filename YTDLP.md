# The on-device yt-dlp engine

A Tube resolves videos with its own SABR player, NewPipe Extractor and the Piped
API. This module adds the extractor from the Off app as a **fallback**: when none
of the above can produce playable streams, yt-dlp reads the page and hands back
direct stream URLs.

Everything runs on the phone. There is no A Tube backend and no third-party
extraction API.

## What is where

| Path | Purpose |
| --- | --- |
| `app/src/main/python/bridge.py` | The Python side. `extract_info()` returns metadata plus every format with its direct URL; `start_download()` is there for links only yt-dlp understands. |
| `app/src/main/java/com/atube/app/api/ytdlp/YtDlpEngine.kt` | Starts the Chaquopy interpreter lazily and exposes the bridge to Kotlin. Blocking, so call it off the main thread. |
| `app/src/main/java/com/atube/app/api/ytdlp/YtDlpStreamResolver.kt` | Maps yt-dlp's format list onto A Tube's `Streams` / `PipedStream`, so the existing player and downloader need no changes. |
| `app/src/main/java/com/atube/app/api/ytdlp/YtDlpFallbackRepository.kt` | Decorates the active `MediaServiceRepository` and only calls yt-dlp when the primary extractor failed. |

## Build requirements

yt-dlp is Python, so the build uses [Chaquopy](https://chaquo.com/chaquopy/)
(`com.chaquo.python`, version 17.2.0) to bundle a CPython runtime and install the
pinned `yt-dlp` wheel into it.

1. **Python 3.11 on the build machine.** Chaquopy needs the same minor version as
   the runtime it bundles. Either have `python3.11` on `PATH`, or point
   `BUILD_PYTHON` at an interpreter:

   ```bash
   BUILD_PYTHON=/usr/bin/python3.11 ./gradlew assembleDebug
   ```

2. **Only `arm64-v8a` is built for now**, set in `app/build.gradle.kts`. Chaquopy
   builds a Python runtime per ABI, and four of them would roughly quadruple the
   APK. `x86_64` (the emulator) and the 32-bit ABIs can be added back to
   `abiFilters` later, at a large size cost.

3. **Release APKs are unsigned.** `build-release.yml` builds `assembleRelease` and
   attaches `app-release-unsigned.apk` to the GitHub release without signing it.
   To sign again, restore the `ilharp/sign-android-release` step and its four
   `ANDROID_RELEASE_*` secrets.

4. **CI.** Both workflows run `actions/setup-python` with `3.11` before Gradle. If
   you move to a different Python version, change it in `chaquopy { }` and in both
   workflows together.

## Updating yt-dlp

Bump the pin in `app/build.gradle.kts`:

```kotlin
pip {
    install("yt-dlp==2026.10.21")
}
```

yt-dlp publishes a pure-Python wheel, so no Android-specific wheel is involved and
any recent version works with the same runtime.

## Notes

- The engine starts only when it is needed, and only once per process. If the
  interpreter cannot start (for example a missing ABI), the app logs it and falls
  back to whatever the primary extractor returned.
- Merging a video-only and an audio-only stream needs ffmpeg. A Tube does not ship
  one, so it plays and downloads the separate streams with its own player and
  downloader instead of asking yt-dlp to mux them.
- yt-dlp's direct URLs expire, which is exactly why this is a fallback: the player
  keeps using A Tube's own extraction whenever that works.
