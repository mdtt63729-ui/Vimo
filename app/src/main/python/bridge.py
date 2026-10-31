"""On-device yt-dlp bridge for A Tube.

Ported from the Off app. Everything happens on the device: no A Tube backend and
no third-party extraction API. Network requests go straight to the target website
through yt-dlp, which is used as a fallback extractor. A Tube still keeps NewPipe
Extractor and its SABR player for YouTube, and falls back to this when they cannot
produce playable streams.

All entry points return a JSON string so the Kotlin side never has to deal with
Python objects.
"""
import json
import os
import tempfile
import threading

import yt_dlp

_jobs = {}
_cancelled = set()
_lock = threading.Lock()

# Directory holding executables named "ffmpeg" and "ffprobe". Set by
# prepare_ffmpeg(); None means yt-dlp falls back to searching PATH.
_FFMPEG_DIR = None


def prepare_ffmpeg(native_lib_dir, work_dir):
    """Expose a bundled ffmpeg/ffprobe to yt-dlp, if the app ships one.

    A Tube merges streams with its own downloader, so this is optional: it only
    matters when yt-dlp itself has to mux a video-only and an audio-only stream.
    """
    global _FFMPEG_DIR
    try:
        os.makedirs(work_dir, exist_ok=True)
        found = False
        for name in ("ffmpeg", "ffprobe"):
            src = os.path.join(native_lib_dir, "lib" + name + ".so")
            dst = os.path.join(work_dir, name)
            if not os.path.exists(src):
                continue
            found = True
            try:
                if os.path.islink(dst) or os.path.exists(dst):
                    os.remove(dst)
            except Exception:
                pass
            try:
                os.symlink(src, dst)
            except Exception:
                import shutil
                shutil.copy2(src, dst)
                os.chmod(dst, 0o755)
        _FFMPEG_DIR = work_dir if found else None
        return _FFMPEG_DIR
    except Exception:
        _FFMPEG_DIR = None
        return None


def ffmpeg_available():
    return _FFMPEG_DIR is not None


def _ffmpeg_opts():
    return {"ffmpeg_location": _FFMPEG_DIR} if _FFMPEG_DIR else {}


def _progress_file(job_id):
    return os.path.join(tempfile.gettempdir(), "atube_dl_progress_" + str(job_id) + ".json")


def _write_progress(job_id, payload):
    try:
        path = _progress_file(job_id)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f)
        os.replace(tmp, path)
    except Exception:
        pass


def _progress_hook(job_id):
    def hook(data):
        if job_id in _cancelled:
            raise yt_dlp.utils.DownloadError("Cancelled")
        status = data.get("status", "downloading")
        total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
        downloaded = data.get("downloaded_bytes") or 0
        percent = (downloaded / total * 100.0) if total else 0.0
        _write_progress(job_id, {
            "status": status,
            "percent": round(percent, 1),
            "downloaded_bytes": downloaded,
            "total_bytes": total,
            "speed": data.get("speed") or 0,
            "eta": data.get("eta"),
            "filename": data.get("filename") or "",
            "message": "Downloading" if status == "downloading" else "Processing",
        })
    return hook


def _formats(info):
    """Flatten yt-dlp's format list for the Kotlin side.

    Unlike the Off version this also carries the direct ``url`` of every format,
    because A Tube plays and downloads from resolved URLs with its own player and
    downloader instead of handing the job back to yt-dlp.
    """
    out = []
    for f in (info.get("formats") or []):
        vcodec = f.get("vcodec") or "none"
        acodec = f.get("acodec") or "none"
        has_video = vcodec != "none"
        has_audio = acodec != "none"
        if not has_video and not has_audio:
            continue
        height = f.get("height") or 0
        out.append({
            "format_id": str(f.get("format_id") or ""),
            "url": f.get("url") or "",
            "ext": f.get("ext") or "unknown",
            "resolution": f.get("resolution") or ((str(height) + "p") if height else ("audio only" if not has_video else "unknown")),
            "height": height,
            "width": f.get("width") or 0,
            "fps": f.get("fps") or 0,
            "filesize": f.get("filesize") or f.get("filesize_approx") or 0,
            "vcodec": vcodec,
            "acodec": acodec,
            "has_video": has_video,
            "has_audio": has_audio,
            "note": f.get("format_note") or "",
            "tbr": f.get("tbr") or 0,
            "abr": f.get("abr") or 0,
            "protocol": f.get("protocol") or "",
        })
    out.sort(key=lambda x: (x["has_video"], x["height"], x["has_audio"], x["tbr"]), reverse=True)
    return out


def search(query, limit=20):
    """Search through yt-dlp's ``ytsearch`` pseudo-extractor.

    This is the fallback for the search screen and for the home feed's trending
    candidates when the primary extractor returns nothing.
    """
    query = (query or "").strip()
    if not query:
        return json.dumps({"ok": False, "error": "Empty search query.", "items": []})
    try:
        count = max(1, min(int(limit), 40))
    except Exception:
        count = 20
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "extract_flat": "in_playlist",
        "noplaylist": False,
        "socket_timeout": 20,
        "retries": 2,
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info("ytsearch%d:%s" % (count, query), download=False)
        items = []
        for entry in ((info or {}).get("entries") or []):
            if not entry:
                continue
            video_id = entry.get("id") or ""
            if not video_id:
                continue
            thumb = entry.get("thumbnail") or ""
            if not thumb:
                thumbs = entry.get("thumbnails") or []
                if thumbs:
                    thumb = thumbs[-1].get("url") or ""
            items.append({
                "id": video_id,
                "title": entry.get("title") or "",
                "duration": entry.get("duration") or 0,
                "uploader": entry.get("uploader") or entry.get("channel") or "",
                "channel_id": entry.get("channel_id") or "",
                "thumbnail": thumb,
                "views": entry.get("view_count") or 0,
                "live": bool(entry.get("is_live")),
            })
        return json.dumps({"ok": True, "items": items})
    except Exception as e:
        return json.dumps({"ok": False, "error": (str(e).strip() or "Search failed.")[:400], "items": []})


def extract_info(url):
    url = (url or "").strip()
    if not url.startswith(("https://", "http://")):
        return json.dumps({"ok": False, "error": "Enter a valid http or https URL."})
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "ignoreerrors": False,
        "socket_timeout": 20,
        "retries": 2,
    }
    opts.update(_ffmpeg_opts())
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        if not info:
            return json.dumps({"ok": False, "error": "No video metadata was returned."})
        return json.dumps({
            "ok": True,
            "id": info.get("id") or "",
            "title": info.get("title") or "Untitled video",
            "description": info.get("description") or "",
            "duration": info.get("duration") or 0,
            "thumbnail": info.get("thumbnail") or "",
            "uploader": info.get("uploader") or info.get("channel") or info.get("uploader_id") or "",
            "extractor": info.get("extractor_key") or info.get("extractor") or "yt-dlp",
            "webpage_url": info.get("webpage_url") or url,
            "is_live": bool(info.get("is_live")),
            "formats": _formats(info),
        })
    except Exception as e:
        return json.dumps({
            "ok": False,
            "error": (str(e).strip() or "Could not read video metadata.")[:400],
        })


def start_download(url, format_selector, output_dir, job_id):
    """Download into app-private storage. Kotlin polls get_progress(job_id).

    Blocks until finished, so call it on a background thread.
    """
    job_id = str(job_id)
    os.makedirs(output_dir, exist_ok=True)
    output_template = os.path.join(output_dir, "%(extractor)s_%(title).120B_%(id)s.%(ext)s")
    selector = (format_selector or "").strip() or "bv*+ba/b"
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "format": selector,
        "outtmpl": output_template,
        "progress_hooks": [_progress_hook(job_id)],
        # Speed: pull fragmented streams in parallel and read in large chunks.
        # yt-dlp defaults to one connection and 1 KB reads, which is what made
        # downloads crawl on a fast link.
        "concurrent_fragment_downloads": 8,
        "http_chunk_size": 10485760,
        "buffersize": 1048576,
        "retries": 10,
        "fragment_retries": 10,
        "continuedl": True,
        "overwrites": False,
        "socket_timeout": 20,
        "windowsfilenames": True,
        "merge_output_format": "mp4",
    }
    opts.update(_ffmpeg_opts())
    _write_progress(job_id, {"status": "starting", "percent": 0, "message": "Starting download"})
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            code = ydl.download([url])
        if job_id in _cancelled:
            _write_progress(job_id, {"status": "cancelled", "message": "Cancelled"})
            return json.dumps({"ok": False, "error": "Cancelled"})
        if code == 0:
            _write_progress(job_id, {
                "status": "finished",
                "percent": 100,
                "message": "Download complete",
                "files": sorted(os.listdir(output_dir)),
            })
            return json.dumps({"ok": True})
        _write_progress(job_id, {"status": "error", "message": "The download did not complete"})
        return json.dumps({"ok": False, "error": "The download did not complete."})
    except Exception as e:
        cancelled = job_id in _cancelled
        _write_progress(job_id, {
            "status": "cancelled" if cancelled else "error",
            "message": "Cancelled" if cancelled else str(e)[:400],
        })
        return json.dumps({"ok": False, "error": "Cancelled" if cancelled else str(e)[:400]})


def start_audio_download(url, output_dir, job_id):
    """Download the audio track and convert it to MP3 with the bundled ffmpeg.

    Blocks until finished, so call it on a background thread. Progress is read
    with get_progress(job_id), exactly like start_download.
    """
    job_id = str(job_id)
    os.makedirs(output_dir, exist_ok=True)
    output_template = os.path.join(output_dir, "%(title).120B_%(id)s.%(ext)s")
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "format": "bestaudio/best",
        "outtmpl": output_template,
        "progress_hooks": [_progress_hook(job_id)],
        "retries": 10,
        "fragment_retries": 10,
        "socket_timeout": 20,
        "windowsfilenames": True,
        "continuedl": True,
        "overwrites": False,
        "postprocessors": [{
            "key": "FFmpegExtractAudio",
            "preferredcodec": "mp3",
            "preferredquality": "192",
        }],
    }
    opts.update(_ffmpeg_opts())
    _write_progress(job_id, {"status": "starting", "percent": 0, "message": "Preparing audio"})
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            code = ydl.download([url])
        if job_id in _cancelled:
            _write_progress(job_id, {"status": "cancelled", "message": "Cancelled"})
            return json.dumps({"ok": False, "error": "Cancelled"})
        if code == 0:
            _write_progress(job_id, {
                "status": "finished",
                "percent": 100,
                "message": "Download complete",
                "files": sorted(os.listdir(output_dir)),
            })
            return json.dumps({"ok": True})
        _write_progress(job_id, {"status": "error", "message": "The conversion did not complete"})
        return json.dumps({"ok": False, "error": "The conversion did not complete."})
    except Exception as e:
        cancelled = job_id in _cancelled
        _write_progress(job_id, {
            "status": "cancelled" if cancelled else "error",
            "message": "Cancelled" if cancelled else str(e)[:400],
        })
        return json.dumps({"ok": False, "error": "Cancelled" if cancelled else str(e)[:400]})


def cancel_job(job_id):
    _cancelled.add(str(job_id))
    return True


def get_progress(job_id):
    try:
        with open(_progress_file(job_id), "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return json.dumps({"status": "starting", "percent": 0, "message": "Preparing download"})


def cleanup_progress(job_id):
    job_id = str(job_id)
    _cancelled.discard(job_id)
    try:
        os.remove(_progress_file(job_id))
    except Exception:
        pass
    return True


def version():
    return yt_dlp.version.__version__
