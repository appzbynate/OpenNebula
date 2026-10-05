"""Windows desktop capture for OpenNebula remote-desktop panel."""

from __future__ import annotations

import ctypes
import io
import threading
from ctypes import wintypes

_sct = None
_lock = threading.Lock()
_note = ""
_monitor = None  # mss monitor dict

user32 = ctypes.WinDLL("user32", use_last_error=True)
gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)

user32.GetCursorInfo.argtypes = [ctypes.c_void_p]
user32.GetCursorInfo.restype = wintypes.BOOL
user32.GetIconInfo.argtypes = [wintypes.HANDLE, ctypes.c_void_p]
user32.GetIconInfo.restype = wintypes.BOOL
user32.DrawIconEx.argtypes = [
    wintypes.HDC, ctypes.c_int, ctypes.c_int, wintypes.HANDLE,
    ctypes.c_int, ctypes.c_int, wintypes.UINT, wintypes.HANDLE, wintypes.UINT,
]
user32.DrawIconEx.restype = wintypes.BOOL
user32.GetDC.argtypes = [wintypes.HWND]
user32.GetDC.restype = wintypes.HDC
user32.ReleaseDC.argtypes = [wintypes.HWND, wintypes.HDC]
user32.ReleaseDC.restype = ctypes.c_int

CURSOR_SHOWING = 0x00000001
DI_NORMAL = 0x0003


class POINT(ctypes.Structure):
    _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG)]


class CURSORINFO(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("flags", wintypes.DWORD),
        ("hCursor", wintypes.HANDLE),
        ("ptScreenPos", POINT),
    ]


class ICONINFO(ctypes.Structure):
    _fields_ = [
        ("fIcon", wintypes.BOOL),
        ("xHotspot", wintypes.DWORD),
        ("yHotspot", wintypes.DWORD),
        ("hbmMask", wintypes.HBITMAP),
        ("hbmColor", wintypes.HBITMAP),
    ]


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


def reset_capture() -> None:
    """Drop mss handle after a display-mode change so monitor geometry refreshes."""
    global _sct, _monitor, _note
    with _lock:
        try:
            if _sct is not None:
                _sct.close()
        except Exception:
            pass
        _sct = None
        _monitor = None
        _note = "capture reset (display mode changed)"


def capture_note() -> str:
    return _note or "desktop capture idle"


def primary_monitor_size() -> tuple[int, int, int, int]:
    """Return (left, top, width, height) of primary monitor in virtual-screen coords."""
    global _monitor, _note, _sct
    with _lock:
        try:
            import mss

            if _sct is None:
                _sct = mss.mss()
            # monitors[1] is primary; [0] is virtual desktop union
            mon = _sct.monitors[1]
            _monitor = mon
            _note = f"primary {mon['width']}x{mon['height']} @ ({mon['left']},{mon['top']})"
            return int(mon["left"]), int(mon["top"]), int(mon["width"]), int(mon["height"])
        except Exception as err:
            _note = f"mss unavailable: {err}"
            return 0, 0, 1920, 1080


def _cursor_rgba(size: int = 48) -> tuple[object, int, int] | None:
    """Return (PIL RGBA cursor, hotspot_x, hotspot_y) or None if hidden."""
    from PIL import Image

    ci = CURSORINFO()
    ci.cbSize = ctypes.sizeof(CURSORINFO)
    if not user32.GetCursorInfo(ctypes.byref(ci)):
        return None
    if not (ci.flags & CURSOR_SHOWING) or not ci.hCursor:
        return None

    ii = ICONINFO()
    if not user32.GetIconInfo(ci.hCursor, ctypes.byref(ii)):
        return None
    try:
        hdc_screen = user32.GetDC(None)
        if not hdc_screen:
            return None
        try:
            hdc = gdi32.CreateCompatibleDC(hdc_screen)
            if not hdc:
                return None
            bmi = BITMAPINFO()
            bmi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
            bmi.bmiHeader.biWidth = size
            bmi.bmiHeader.biHeight = -size  # top-down
            bmi.bmiHeader.biPlanes = 1
            bmi.bmiHeader.biBitCount = 32
            bmi.bmiHeader.biCompression = 0  # BI_RGB
            bits = ctypes.c_void_p()
            hbmp = gdi32.CreateDIBSection(
                hdc_screen,
                ctypes.byref(bmi),
                0,  # DIB_RGB_COLORS
                ctypes.byref(bits),
                None,
                0,
            )
            if not hbmp or not bits:
                gdi32.DeleteDC(hdc)
                return None
            old = gdi32.SelectObject(hdc, hbmp)
            ctypes.memset(bits, 0, size * size * 4)
            user32.DrawIconEx(hdc, 0, 0, ci.hCursor, size, size, 0, None, DI_NORMAL)
            gdi32.SelectObject(hdc, old)
            buf = ctypes.string_at(bits, size * size * 4)
            # Windows DIB is BGRA; convert to RGBA for PIL paste.
            img = Image.frombytes("RGBA", (size, size), buf, "raw", "BGRA")
            gdi32.DeleteObject(hbmp)
            gdi32.DeleteDC(hdc)
            return img, int(ii.xHotspot), int(ii.yHotspot)
        finally:
            user32.ReleaseDC(None, hdc_screen)
    finally:
        if ii.hbmMask:
            gdi32.DeleteObject(ii.hbmMask)
        if ii.hbmColor:
            gdi32.DeleteObject(ii.hbmColor)


def _paste_system_cursor(img, mon_left: int, mon_top: int):
    """Draw a stable arrow at the real cursor (mss omits hardware cursor).

    Avoid DrawIconEx compositing — it flickered badly when GetCursorPos was
    broken and still jitters with mask/XOR cursors.
    """
    from PIL import ImageDraw

    ci = CURSORINFO()
    ci.cbSize = ctypes.sizeof(CURSORINFO)
    if not user32.GetCursorInfo(ctypes.byref(ci)):
        return img
    if not (ci.flags & CURSOR_SHOWING):
        return img

    x = int(ci.ptScreenPos.x - mon_left)
    y = int(ci.ptScreenPos.y - mon_top)
    if x < -4 or y < -4 or x > img.width + 4 or y > img.height + 4:
        return img

    draw = ImageDraw.Draw(img)
    tip = [
        (x, y),
        (x, y + 17),
        (x + 5, y + 13),
        (x + 11, y + 20),
        (x + 14, y + 18),
        (x + 7, y + 11),
        (x + 13, y + 9),
    ]
    draw.polygon(tip, fill=(248, 248, 250), outline=(18, 20, 24))
    return img


def grab_desktop_jpeg(quality: int = 52, max_w: int = 1920) -> bytes | None:
    """Grab primary monitor → JPEG (fast path for gaming latency + system cursor)."""
    global _note, _sct, _monitor
    with _lock:
        try:
            import mss
            from PIL import Image

            if _sct is None:
                _sct = mss.mss()
            mon = _sct.monitors[1]
            _monitor = mon
            left = int(mon["left"])
            top = int(mon["top"])
            shot = _sct.grab(mon)
            img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
            try:
                img = _paste_system_cursor(img, left, top)
            except Exception:
                pass
            w, h = img.size
            if w > max_w:
                nh = max(2, int(h * (max_w / float(w))))
                resample = getattr(getattr(Image, "Resampling", Image), "BILINEAR", Image.BILINEAR)
                img = img.resize((max_w, nh), resample)
            buf = io.BytesIO()
            # 4:2:0 + moderate quality: big win for encode + Wi‑Fi vs 4:4:4 q80.
            img.save(
                buf,
                format="JPEG",
                quality=int(quality),
                optimize=False,
                subsampling=2,
            )
            return buf.getvalue()
        except Exception as err:
            _note = f"desktop grab failed: {err}"
            return None


def monitor_origin_size() -> tuple[int, int, int, int]:
    """Cached (left, top, width, height) for input mapping."""
    if _monitor is not None:
        m = _monitor
        return int(m["left"]), int(m["top"]), int(m["width"]), int(m["height"])
    return primary_monitor_size()
