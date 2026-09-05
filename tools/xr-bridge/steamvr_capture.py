"""
SteamVR capture for OpenNebula XR bridge.

Nebula-style path (XREAL Air + OpenVR-OpenTrack):
  PC renders packed Side-by-Side (left | right) → phone DisplayPort →
  glasses hardware HalfSBS (MCU mode 8).

Do NOT crop to left-eye-only — that broke stereo and often streamed the
OpenTrack red HMD placeholder into one eye.
"""

from __future__ import annotations

import json
import os
from pathlib import Path


# Ultrawide SBS encode size. 1920×540 ≈ 960/eye — matches HalfSBS sharpness,
# much lower Wi‑Fi/JPEG latency than 2560×720 / 3840×1080.
SBS_OUT_W = 1920
SBS_OUT_H = 540

# Cache capture target so we don't EnumWindows every frame (huge latency).
_cached_hit: tuple | None = None
_cached_hit_mono = 0.0
_CACHE_TTL_S = 0.75
_sct = None
_last_focus_mono = 0.0
_FOCUS_COOLDOWN_S = 5.0


def steam_vrsettings_paths() -> list[Path]:
    roots: list[Path] = []
    pf = os.environ.get("ProgramFiles(x86)") or os.environ.get("ProgramFiles")
    if pf:
        roots.append(Path(pf) / "Steam" / "config" / "steamvr.vrsettings")
    return [p for p in roots if p.parent.exists()]


def ensure_steamvr_mirror() -> str:
    """Enable Desktop Mirror; both-eyes / SBS-friendly (not left-only)."""
    touched: list[str] = []
    for path in steam_vrsettings_paths():
        try:
            data: dict = {}
            if path.exists():
                data = json.loads(path.read_text(encoding="utf-8"))
            steamvr = data.setdefault("steamvr", {})
            changed = False
            for key, val in (
                ("enableHomeApp", True),
                ("showMirrorView", True),
                # 2 = both eyes when supported; 0 left-only caused mono + red-eye issues.
                ("mirrorViewEye", 2),
                ("activateMultipleDrivers", True),
            ):
                if steamvr.get(key) != val:
                    steamvr[key] = val
                    changed = True
            if changed or not path.exists():
                path.write_text(json.dumps(data, indent=2), encoding="utf-8")
                touched.append(str(path))
        except Exception as err:
            touched.append(f"{path} (failed: {err})")
    if not touched:
        return "no steamvr.vrsettings found"
    return "mirror settings (both eyes / SBS): " + "; ".join(touched)


def _exe_name(pid: int) -> str:
    try:
        import ctypes
        from ctypes import wintypes

        k32 = ctypes.windll.kernel32
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        h = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not h:
            return ""
        try:
            buf = ctypes.create_unicode_buffer(260)
            size = wintypes.DWORD(260)
            if ctypes.windll.kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
                return Path(buf.value).name.lower()
        finally:
            k32.CloseHandle(h)
    except Exception:
        pass
    return ""


def _score_window(title: str, exe: str, w: int, h: int) -> int:
    low = title.lower().strip()
    deny = (
        "input experience",
        "msctfime",
        "default ime",
        "nvidia",
        "program manager",
        "task manager",
        "cursor",
        "steam overlay",
    )
    if any(d in low for d in deny):
        return 0

    vr_exe = exe in (
        "vrcompositor.exe",
        "vrserver.exe",
        "vrmonitor.exe",
        "steamvr.exe",
        "vrstartup.exe",
    ) or "steamvr" in exe or "opentrack" in exe

    aspect = w / max(h, 1)
    # Prefer packed SBS / ultrawide OpenTrack HMD window (Nebula path).
    if "opentrack" in low or "opentrack" in exe:
        if aspect >= 1.55:
            return 140
        return 95
    if "mirror" in low and ("steam" in low or "vr" in low or "headset" in low):
        return 125 if aspect >= 1.55 else 110
    if "headset view" in low or "mirror view" in low:
        return 120 if aspect >= 1.55 else 105
    if vr_exe and "status" not in low and "settings" not in low:
        if w >= 400 and h >= 300:
            if aspect >= 1.55:
                return 100  # SBS both eyes
            if 0.7 <= aspect <= 1.6:
                return 70  # single eye — may stitch with pair
            return 55
    if "steamvr" in low and "status" not in low:
        return 65
    if "steamvr status" in low:
        return 5
    return 0


def find_capture_hwnds():
    """Ranked SteamVR / OpenTrack windows: (score, hwnd, left, top, w, h, title, exe)."""
    try:
        import ctypes
        from ctypes import wintypes

        user32 = ctypes.windll.user32
        hits: list[tuple[int, int, int, int, int, int, str, str]] = []

        @ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
        def _enum(hwnd, _lp):
            if not user32.IsWindowVisible(hwnd):
                return True
            length = user32.GetWindowTextLengthW(hwnd)
            buf = ctypes.create_unicode_buffer(max(length, 0) + 1)
            if length > 0:
                user32.GetWindowTextW(hwnd, buf, length + 1)
            title = buf.value
            pid = wintypes.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            exe = _exe_name(int(pid.value))
            rc = wintypes.RECT()
            if not user32.GetWindowRect(hwnd, ctypes.byref(rc)):
                return True
            w = rc.right - rc.left
            h = rc.bottom - rc.top
            if w < 320 or h < 240:
                return True
            score = _score_window(title, exe, w, h)
            if score <= 0:
                return True
            hits.append((score, int(hwnd), rc.left, rc.top, w, h, title or "(untitled)", exe))
            return True

        user32.EnumWindows(_enum, 0)
        hits.sort(key=lambda t: (t[0], t[4] * t[5]), reverse=True)
        return hits
    except Exception:
        return []


def find_capture_rect():
    hits = find_capture_hwnds()
    if not hits:
        return None, "no SteamVR window — will try eye-pair / primary"
    score, _hwnd, left, top, w, h, title, exe = hits[0]
    return (
        {"left": left, "top": top, "width": w, "height": h},
        f"capturing '{title}'/{exe} ({w}x{h}) score={score}",
    )


def printwindow_bitmap(hwnd: int, width: int, height: int):
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32
    PW_RENDERFULLCONTENT = 0x00000002

    hdc_win = user32.GetDC(hwnd)
    if not hdc_win:
        return None
    hdc_mem = gdi32.CreateCompatibleDC(hdc_win)
    hbmp = gdi32.CreateCompatibleBitmap(hdc_win, width, height)
    old = gdi32.SelectObject(hdc_mem, hbmp)
    ok = user32.PrintWindow(hwnd, hdc_mem, PW_RENDERFULLCONTENT)
    if not ok:
        ok = user32.PrintWindow(hwnd, hdc_mem, 0)
    if not ok:
        gdi32.SelectObject(hdc_mem, old)
        gdi32.DeleteObject(hbmp)
        gdi32.DeleteDC(hdc_mem)
        user32.ReleaseDC(hwnd, hdc_win)
        return None

    class BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [
            ("biSize", wintypes.DWORD),
            ("biWidth", wintypes.LONG),
            ("biHeight", wintypes.LONG),
            ("biPlanes", wintypes.WORD),
            ("biBitCount", wintypes.WORD),
            ("biCompression", wintypes.DWORD),
            ("biSizeImage", wintypes.DWORD),
            ("biXPelsPerMeter", wintypes.LONG),
            ("biYPelsPerMeter", wintypes.LONG),
            ("biClrUsed", wintypes.DWORD),
            ("biClrImportant", wintypes.DWORD),
        ]

    class BITMAPINFO(ctypes.Structure):
        _fields_ = [("bmiHeader", BITMAPINFOHEADER), ("bmiColors", wintypes.DWORD * 3)]

    bmi = BITMAPINFO()
    bmi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bmi.bmiHeader.biWidth = width
    bmi.bmiHeader.biHeight = -height
    bmi.bmiHeader.biPlanes = 1
    bmi.bmiHeader.biBitCount = 32
    bmi.bmiHeader.biCompression = 0
    buf = (ctypes.c_ubyte * (width * height * 4))()
    gdi32.GetDIBits(hdc_mem, hbmp, 0, height, ctypes.byref(buf), ctypes.byref(bmi), 0)

    gdi32.SelectObject(hdc_mem, old)
    gdi32.DeleteObject(hbmp)
    gdi32.DeleteDC(hdc_mem)
    user32.ReleaseDC(hwnd, hdc_win)

    from PIL import Image

    img = Image.frombuffer("RGBA", (width, height), bytes(buf), "raw", "BGRA", 0, 1)
    return img.convert("RGB")


def frame_is_blank(img, max_range: int = 14) -> bool:
    gray = img.convert("L")
    extrema = gray.getextrema()
    if not extrema:
        return True
    if (extrema[1] - extrema[0]) < max_range:
        return True
    # Empty OpenTrack/compositor often looks near-black with tiny noise — not "content".
    hist = gray.resize((64, 36)).histogram()
    total = sum(hist) or 1
    mean = sum(i * hist[i] for i in range(256)) / total
    return mean < 12.0


def _redish_ratio(img) -> float:
    small = img.resize((32, 18))
    try:
        pixels = list(small.get_flattened_data())
        # Pillow 14+: flattened RGB triples as flat list — fall back.
        if pixels and not isinstance(pixels[0], tuple):
            pixels = list(small.getdata())
    except Exception:
        pixels = list(small.getdata())
    if not pixels:
        return 1.0
    redish = 0
    for pix in pixels:
        if not isinstance(pix, tuple):
            continue
        r, g, b = pix[0], pix[1], pix[2]
        if r > 140 and r > g + 40 and r > b + 40:
            redish += 1
        elif r > 120 and b > 120 and g < 80:
            redish += 1
    return redish / max(len(pixels), 1)


def frame_is_red_placeholder(img) -> bool:
    """
    OpenTrack / SteamVR monitor-mode often shows a solid red (or magenta) eye
    placeholder. Streaming that = 'left eye red screen'.
    Check full frame AND each SBS half — half-red used to slip under 55%.
    """
    if _redish_ratio(img) >= 0.40:
        return True
    w, h = img.size
    if w / max(h, 1) >= 1.55:
        mid = w // 2
        if _redish_ratio(img.crop((0, 0, mid, h))) >= 0.40:
            return True
        if _redish_ratio(img.crop((mid, 0, w, h))) >= 0.40:
            return True
    return False


def frame_is_unusable(img) -> bool:
    return frame_is_blank(img) or frame_is_red_placeholder(img)


def ensure_packed_sbs(img):
    """
    Return a packed SBS image (left | right).
    - Already wide → keep as SBS (optionally letterbox to 16:9).
    - Square / single-eye → duplicate into both halves (flat but both eyes lit).
    """
    from PIL import Image

    w, h = img.size
    aspect = w / max(h, 1)
    if aspect >= 1.55:
        # Already SBS (or ultrawide dual-pane).
        return img, "sbs-packed"
    # Single-eye: duplicate so HalfSBS doesn't leave one eye on garbage/red.
    half = Image.new("RGB", (w * 2, h))
    half.paste(img, (0, 0))
    half.paste(img, (w, 0))
    return half, "mono→dual"


def stitch_eye_pair(hits, grab_fn):
    """
    If OpenTrack opened two same-size eye windows, stitch left|right into SBS.
    """
    vr = [
        h
        for h in hits
        if h[0] >= 50
        and (
            "vr" in h[7]
            or "steam" in h[7]
            or "opentrack" in h[7]
            or "mirror" in h[6].lower()
            or "opentrack" in h[6].lower()
        )
    ]
    if len(vr) < 2:
        return None, ""
    from PIL import Image

    for i, a in enumerate(vr):
        for b in vr[i + 1 :]:
            if abs(a[4] - b[4]) >= 40 or abs(a[5] - b[5]) >= 40:
                continue
            left_h, right_h = (a, b) if a[2] <= b[2] else (b, a)
            # Prefer single-eye aspects for pairing.
            if (left_h[4] / max(left_h[5], 1)) > 1.55:
                continue
            try:
                left_img = grab_fn(left_h)
                right_img = grab_fn(right_h)
            except Exception:
                continue
            if left_img is None or right_img is None:
                continue
            if frame_is_blank(left_img) or frame_is_blank(right_img):
                continue
            if frame_is_red_placeholder(left_img) or frame_is_red_placeholder(right_img):
                continue
            if frame_is_unusable(left_img) or frame_is_unusable(right_img):
                continue
            w = max(left_img.width, right_img.width)
            h = max(left_img.height, right_img.height)
            left_img = left_img.resize((w, h))
            right_img = right_img.resize((w, h))
            out = Image.new("RGB", (w * 2, h))
            out.paste(left_img, (0, 0))
            out.paste(right_img, (w, 0))
            note = (
                f"stitched '{left_h[6]}'+'{right_h[6]}' "
                f"({w}x{h})×2"
            )
            return out, note
    return None, ""


def focus_headset_window(force: bool = False) -> str:
    """
    OpenTrack red screen fix: bring 'Headset Window' to the foreground so the
    compositor actually paints SBS into it (otherwise DXGI grabs black/red).
    """
    global _last_focus_mono
    import time

    now = time.monotonic()
    if not force and (now - _last_focus_mono) < _FOCUS_COOLDOWN_S:
        return "focus cooldown"
    _last_focus_mono = now
    try:
        import ctypes
        from ctypes import wintypes

        user32 = ctypes.windll.user32
        found = []

        @ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
        def _enum(hwnd, _lp):
            if not user32.IsWindowVisible(hwnd):
                return True
            length = user32.GetWindowTextLengthW(hwnd)
            if length <= 0:
                return True
            buf = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, buf, length + 1)
            title = (buf.value or "").lower()
            if "headset window" in title or (
                "opentrack" in title and "status" not in title
            ):
                rc = wintypes.RECT()
                if user32.GetWindowRect(hwnd, ctypes.byref(rc)):
                    if (rc.right - rc.left) >= 320 and (rc.bottom - rc.top) >= 240:
                        found.append(int(hwnd))
                        return False
            return True

        user32.EnumWindows(_enum, 0)
        if not found:
            return "no Headset Window to focus"
        hwnd = found[0]
        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        user32.SetForegroundWindow(hwnd)
        HWND_TOPMOST = -1
        HWND_NOTOPMOST = -2
        SWP_NOMOVE = 0x0002
        SWP_NOSIZE = 0x0001
        SWP_SHOWWINDOW = 0x0040
        user32.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
        user32.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW)
        return f"focused Headset Window hwnd={hwnd}"
    except Exception as err:
        return f"focus failed: {err}"


def grab_steamvr_frame(quality: int = 70):
    """Return (jpeg_bytes, note) — packed SBS for glasses HalfSBS."""
    global _cached_hit, _cached_hit_mono, _sct
    try:
        from mss import mss
        from PIL import Image
        import io
        import time
    except Exception as err:
        return None, f"mss/pillow missing: {err}"

    def to_jpeg(img, note: str):
        if frame_is_unusable(img):
            return None, f"{note} [skip blank/red]"
        img, pack_note = ensure_packed_sbs(img)
        if pack_note != "sbs-packed":
            note = f"{note} [{pack_note}]"
        if frame_is_unusable(img):
            return None, f"{note} [skip blank/red after pack]"
        if img.size != (SBS_OUT_W, SBS_OUT_H):
            img = img.resize((SBS_OUT_W, SBS_OUT_H), Image.BILINEAR)
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=quality, optimize=False, subsampling=2)
        return buf.getvalue(), note

    if _sct is None:
        _sct = mss()

    now = time.monotonic()
    hits = None
    if _cached_hit is None or (now - _cached_hit_mono) > _CACHE_TTL_S:
        hits = find_capture_hwnds()
        # Don't lock onto a window until we prove it has real pixels.
        _cached_hit = None

    def grab_hit(hit):
        _score, hwnd, left, top, w, h, _title, _exe = hit
        try:
            shot = _sct.grab({"left": left, "top": top, "width": w, "height": h})
            img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
            if not frame_is_unusable(img):
                return img
            pw = printwindow_bitmap(hwnd, w, h)
            if pw is not None and not frame_is_unusable(pw):
                return pw
        except Exception:
            return None
        return None

    # Hot path: reuse cached best window only if it still has content.
    if _cached_hit is not None:
        score, hwnd, left, top, w, h, title, exe = _cached_hit
        try:
            shot = _sct.grab({"left": left, "top": top, "width": w, "height": h})
            img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
            if not frame_is_unusable(img):
                jpeg, note = to_jpeg(img, f"cached '{title}'/{exe} ({w}x{h})")
                if jpeg:
                    return jpeg, note
            pw = printwindow_bitmap(hwnd, w, h)
            if pw is not None and not frame_is_unusable(pw):
                jpeg, note = to_jpeg(pw, f"cached-pw '{title}'/{exe} ({w}x{h})")
                if jpeg:
                    return jpeg, note
            _cached_hit = None
        except Exception:
            _cached_hit = None

    if hits is None:
        hits = find_capture_hwnds()

    # If Headset Window is black/red, try focusing it once per cache miss.
    focus_headset_window()

    stitched, stitch_note = stitch_eye_pair(hits, grab_hit)
    if stitched is not None:
        jpeg, note = to_jpeg(stitched, stitch_note)
        if jpeg:
            return jpeg, note

    for score, hwnd, left, top, w, h, title, exe in hits[:8]:
        try:
            shot = _sct.grab({"left": left, "top": top, "width": w, "height": h})
            img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
            src = "dxgi"
            if frame_is_unusable(img):
                pw = printwindow_bitmap(hwnd, w, h)
                if pw is None or frame_is_unusable(pw):
                    continue
                img = pw
                src = "printwindow"
            jpeg, note = to_jpeg(img, f"{src} '{title}'/{exe} ({w}x{h})")
            if jpeg:
                _cached_hit = (score, hwnd, left, top, w, h, title, exe)
                _cached_hit_mono = time.monotonic()
                return jpeg, note
        except Exception:
            continue

    # No usable HMD/mirror pixels — do NOT stream the desktop (looks broken / can
    # look "red" if a SteamVR error dialog is on-screen). Caller shows lobby.
    return None, "no usable SteamVR pixels — is Headset Window open? Click it if red."
