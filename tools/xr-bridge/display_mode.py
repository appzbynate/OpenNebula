"""Temporarily switch the primary display to match remote-desktop capture (1080p)."""

from __future__ import annotations

import ctypes
import threading
from ctypes import wintypes

user32 = ctypes.WinDLL("user32", use_last_error=True)

ENUM_CURRENT_SETTINGS = -1
DISP_CHANGE_SUCCESSFUL = 0
DM_BITSPERPEL = 0x00040000
DM_PELSWIDTH = 0x00080000
DM_PELSHEIGHT = 0x00100000
DM_DISPLAYFREQUENCY = 0x00400000

# Capture / stream target — keep in sync with desktop_capture.max_w + 16:9.
TARGET_W = 1920
TARGET_H = 1080


class DEVMODEW(ctypes.Structure):
    _fields_ = [
        ("dmDeviceName", ctypes.c_wchar * 32),
        ("dmSpecVersion", wintypes.WORD),
        ("dmDriverVersion", wintypes.WORD),
        ("dmSize", wintypes.WORD),
        ("dmDriverExtra", wintypes.WORD),
        ("dmFields", wintypes.DWORD),
        ("dmOrientation", wintypes.SHORT),
        ("dmPaperSize", wintypes.SHORT),
        ("dmPaperLength", wintypes.SHORT),
        ("dmPaperWidth", wintypes.SHORT),
        ("dmScale", wintypes.SHORT),
        ("dmCopies", wintypes.SHORT),
        ("dmDefaultSource", wintypes.SHORT),
        ("dmPrintQuality", wintypes.SHORT),
        ("dmColor", wintypes.SHORT),
        ("dmDuplex", wintypes.SHORT),
        ("dmYResolution", wintypes.SHORT),
        ("dmTTOption", wintypes.SHORT),
        ("dmCollate", wintypes.SHORT),
        ("dmFormName", ctypes.c_wchar * 32),
        ("dmLogPixels", wintypes.WORD),
        ("dmBitsPerPel", wintypes.DWORD),
        ("dmPelsWidth", wintypes.DWORD),
        ("dmPelsHeight", wintypes.DWORD),
        ("dmDisplayFlags", wintypes.DWORD),
        ("dmDisplayFrequency", wintypes.DWORD),
        ("dmICMMethod", wintypes.DWORD),
        ("dmICMIntent", wintypes.DWORD),
        ("dmMediaType", wintypes.DWORD),
        ("dmDitherType", wintypes.DWORD),
        ("dmReserved1", wintypes.DWORD),
        ("dmReserved2", wintypes.DWORD),
        ("dmPanningWidth", wintypes.DWORD),
        ("dmPanningHeight", wintypes.DWORD),
    ]


_lock = threading.Lock()
# Saved native mode while remote desktop holds 1080p: (w, h, bpp, freq) or None.
_saved: tuple[int, int, int, int] | None = None


def _current_mode() -> tuple[int, int, int, int]:
    dm = DEVMODEW()
    dm.dmSize = ctypes.sizeof(DEVMODEW)
    if not user32.EnumDisplaySettingsW(None, ENUM_CURRENT_SETTINGS, ctypes.byref(dm)):
        raise OSError("EnumDisplaySettingsW failed")
    return (
        int(dm.dmPelsWidth),
        int(dm.dmPelsHeight),
        int(dm.dmBitsPerPel) or 32,
        int(dm.dmDisplayFrequency) or 60,
    )


def _best_1080p() -> tuple[int, int, int] | None:
    """Return (bpp, freq, mode_index) for the best 1920x1080 mode, or None."""
    best: tuple[int, int, int] | None = None  # freq, bpp, index
    i = 0
    dm = DEVMODEW()
    dm.dmSize = ctypes.sizeof(DEVMODEW)
    while user32.EnumDisplaySettingsW(None, i, ctypes.byref(dm)):
        if int(dm.dmPelsWidth) == TARGET_W and int(dm.dmPelsHeight) == TARGET_H:
            bpp = int(dm.dmBitsPerPel) or 32
            freq = int(dm.dmDisplayFrequency) or 60
            if best is None or freq > best[0] or (freq == best[0] and bpp > best[1]):
                best = (freq, bpp, i)
        i += 1
    if best is None:
        return None
    return best[1], best[0], best[2]  # bpp, freq, index


def _apply(w: int, h: int, bpp: int, freq: int) -> None:
    dm = DEVMODEW()
    dm.dmSize = ctypes.sizeof(DEVMODEW)
    dm.dmPelsWidth = int(w)
    dm.dmPelsHeight = int(h)
    dm.dmBitsPerPel = int(bpp)
    dm.dmDisplayFrequency = int(freq)
    dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_BITSPERPEL | DM_DISPLAYFREQUENCY
    # flags=0 → temporary (does not rewrite registry); we restore on disconnect.
    rc = int(user32.ChangeDisplaySettingsW(ctypes.byref(dm), 0))
    if rc != DISP_CHANGE_SUCCESSFUL:
        raise OSError(f"ChangeDisplaySettingsW → {rc} for {w}x{h}@{freq}")


def _invalidate_capture_cache() -> None:
    try:
        import desktop_capture

        desktop_capture.reset_capture()
    except Exception:
        pass


def ensure_capture_resolution() -> str:
    """Switch primary monitor to 1920x1080 for 1:1 capture. Idempotent."""
    global _saved
    with _lock:
        try:
            cur = _current_mode()
        except Exception as err:
            return f"display query failed: {err}"

        if cur[0] == TARGET_W and cur[1] == TARGET_H:
            return f"already {TARGET_W}x{TARGET_H}@{cur[3]}"

        mode = _best_1080p()
        if mode is None:
            return f"no {TARGET_W}x{TARGET_H} mode (still {cur[0]}x{cur[1]})"

        bpp, freq, _ = mode
        if _saved is None:
            _saved = cur
        try:
            _apply(TARGET_W, TARGET_H, bpp, freq)
        except Exception as err:
            _saved = None
            return f"switch to {TARGET_W}x{TARGET_H} failed: {err}"
        _invalidate_capture_cache()
        return f"switched {cur[0]}x{cur[1]} → {TARGET_W}x{TARGET_H}@{freq}"


def restore_native_resolution() -> str:
    """Restore resolution saved by ensure_capture_resolution()."""
    global _saved
    with _lock:
        saved = _saved
        if saved is None:
            return "no saved resolution"
        _saved = None
        try:
            cur = _current_mode()
            if cur[0] == saved[0] and cur[1] == saved[1]:
                return f"already restored {saved[0]}x{saved[1]}"
            _apply(saved[0], saved[1], saved[2], saved[3])
            _invalidate_capture_cache()
            return f"restored {saved[0]}x{saved[1]}@{saved[3]}"
        except Exception as err:
            # Keep trying next time — put mode back so a later disconnect can retry.
            _saved = saved
            return f"restore failed: {err}"
