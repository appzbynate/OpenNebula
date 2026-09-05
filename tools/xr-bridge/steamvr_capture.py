"""
SteamVR capture for OpenNebula XR bridge.

Goal: one eye's SteamVR Home view fullscreen on the glasses — not a desktop
mirror of two eye windows side-by-side.
"""

from __future__ import annotations

import json
import os
from pathlib import Path


def steam_vrsettings_paths() -> list[Path]:
    roots: list[Path] = []
    pf = os.environ.get("ProgramFiles(x86)") or os.environ.get("ProgramFiles")
    if pf:
        roots.append(Path(pf) / "Steam" / "config" / "steamvr.vrsettings")
    return [p for p in roots if p.parent.exists()]


def ensure_steamvr_mirror() -> str:
    """Single-eye Desktop Mirror (left eye) — immersive stream source."""
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
                ("mirrorViewEye", 0),  # left eye only
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
    return "mirror settings (left eye): " + "; ".join(touched)


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
            # QueryFullProcessImageNameW
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

    # Prefer SteamVR's single-eye Mirror / Headset View.
    if "mirror" in low and ("steam" in low or "vr" in low or "headset" in low):
        return 120
    if "headset view" in low or "mirror view" in low:
        return 115
    if "opentrack" in low:
        return 100
    if vr_exe and "status" not in low and "settings" not in low:
        # OpenTrack / compositor eye surfaces (often untitled or generic).
        if w >= 400 and h >= 300:
            aspect = w / max(h, 1)
            # Prefer roughly square / single-eye (not ultrawide desktop).
            if 0.7 <= aspect <= 1.6:
                return 85
            if aspect > 1.6:
                return 60  # likely SBS both eyes — we'll crop left
            return 50
    if "steamvr" in low and "status" not in low:
        return 70
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
    extrema = img.convert("L").getextrema()
    if not extrema:
        return True
    return (extrema[1] - extrema[0]) < max_range


def crop_to_left_eye(img):
    """
    If the frame is side-by-side stereo (two eye views), keep the left eye only
    so the glasses get one immersive image instead of a desktop dual-pane.
    """
    w, h = img.size
    if w >= int(h * 1.55):
        # Classic SBS: left | right
        return img.crop((0, 0, w // 2, h)), "sbs→left"
    return img, "mono"


def pick_left_eye_of_pair(hits):
    """
    OpenTrack often opens two similar eye windows. Prefer the leftmost of a
    same-size pair from vr/steam processes.
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
        return None
    # Group by similar size
    for i, a in enumerate(vr):
        for b in vr[i + 1 :]:
            if abs(a[4] - b[4]) < 40 and abs(a[5] - b[5]) < 40:
                left = a if a[2] <= b[2] else b
                return left
    # Fallback: leftmost VR window with single-eye aspect
    singles = [h for h in vr if 0.7 <= (h[4] / max(h[5], 1)) <= 1.35]
    if singles:
        return min(singles, key=lambda h: h[2])
    return min(vr, key=lambda h: h[2])


def grab_steamvr_frame(quality: int = 70):
    """Return (jpeg_bytes, note) — single-eye SteamVR view when possible."""
    try:
        from mss import mss
        from PIL import Image
        import io
    except Exception as err:
        return None, f"mss/pillow missing: {err}"

    def to_jpeg(img, note: str):
        img, crop_note = crop_to_left_eye(img)
        if crop_note != "mono":
            note = f"{note} [{crop_note}]"
        img = img.resize((960, 540))
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=quality)
        return buf.getvalue(), note

    hits = find_capture_hwnds()
    with mss() as sct:
        # 1) Explicit left-eye of a dual-window pair
        pair = pick_left_eye_of_pair(hits)
        candidates = []
        if pair is not None:
            candidates.append(pair)
        for h in hits[:6]:
            if pair is None or h[1] != pair[1]:
                candidates.append(h)

        for score, hwnd, left, top, w, h, title, exe in candidates[:6]:
            try:
                shot = sct.grab({"left": left, "top": top, "width": w, "height": h})
                img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
                if not frame_is_blank(img):
                    return to_jpeg(img, f"dxgi '{title}'/{exe} ({w}x{h})")
                pw = printwindow_bitmap(hwnd, w, h)
                if pw is not None and not frame_is_blank(pw):
                    return to_jpeg(pw, f"printwindow '{title}'/{exe} ({w}x{h})")
            except Exception:
                continue

        # 2) Primary monitor — crop left half if it looks like dual-eye SBS layout
        try:
            mon = sct.monitors[1]
            shot = sct.grab(mon)
            img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
            # Ultrawide desktop with two eye panes: take left ~half of content area
            return to_jpeg(img, f"primary {img.width}x{img.height}")
        except Exception as err:
            return None, f"primary grab failed: {err}"
