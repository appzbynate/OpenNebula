"""Windows desktop capture for OpenNebula remote-desktop panel."""

from __future__ import annotations

import io
import threading

_sct = None
_lock = threading.Lock()
_note = ""
_monitor = None  # mss monitor dict


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


def grab_desktop_jpeg(quality: int = 40, max_w: int = 1280) -> bytes | None:
    """Grab primary monitor → JPEG. Scaled for Wi‑Fi latency."""
    global _note, _sct, _monitor
    with _lock:
        try:
            import mss
            from PIL import Image

            if _sct is None:
                _sct = mss.mss()
            mon = _sct.monitors[1]
            _monitor = mon
            shot = _sct.grab(mon)
            img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
            w, h = img.size
            if w > max_w:
                nh = max(2, int(h * (max_w / float(w))))
                img = img.resize((max_w, nh), Image.BILINEAR)
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=int(quality), optimize=False)
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
