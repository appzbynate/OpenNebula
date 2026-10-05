"""Low-latency Windows mouse/keyboard injection for remote desktop."""

from __future__ import annotations

import ctypes
from ctypes import wintypes

from desktop_capture import monitor_origin_size

user32 = ctypes.WinDLL("user32", use_last_error=True)

# 64-bit Windows: without argtypes, GetCursorPos/SetCursorPos silently fail
# (often read as 0,0 and never move) — trackpad looked "dead" with a flickering overlay.
user32.GetCursorPos.argtypes = [ctypes.POINTER(wintypes.POINT)]
user32.GetCursorPos.restype = wintypes.BOOL
user32.SetCursorPos.argtypes = [ctypes.c_int, ctypes.c_int]
user32.SetCursorPos.restype = wintypes.BOOL
user32.SendInput.argtypes = [wintypes.UINT, ctypes.c_void_p, ctypes.c_int]
user32.SendInput.restype = wintypes.UINT

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x01000
MOUSEEVENTF_ABSOLUTE = 0x8000
MOUSEEVENTF_VIRTUALDESK = 0x4000
WHEEL_DELTA = 120

INPUT_MOUSE = 0
INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002

VK_CONTROL = 0x11
VK_MENU = 0x12  # Alt
VK_SHIFT = 0x10
VK_LWIN = 0x5B
VK_TAB = 0x09
VK_LEFT = 0x25
VK_RIGHT = 0x27
VK_UP = 0x26
VK_DOWN = 0x28
VK_D = 0x44
VK_S = 0x53


class MOUSEINPUT(ctypes.Structure):
    _fields_ = (
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    )


class KEYBDINPUT(ctypes.Structure):
    _fields_ = (
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    )


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = (
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    )


class INPUT_UNION(ctypes.Union):
    _fields_ = (("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT))


class INPUT(ctypes.Structure):
    _fields_ = (("type", wintypes.DWORD), ("union", INPUT_UNION))


_buttons = 0  # bit0 LMB, bit1 RMB, bit2 MMB


def uv_to_screen(u: float, v: float) -> tuple[int, int]:
    left, top, width, height = monitor_origin_size()
    u = 0.0 if u < 0.0 else 1.0 if u > 1.0 else u
    v = 0.0 if v < 0.0 else 1.0 if v > 1.0 else v
    x = left + int(u * max(width - 1, 0))
    y = top + int(v * max(height - 1, 0))
    return x, y


def move_to_uv(u: float, v: float) -> None:
    x, y = uv_to_screen(u, v)
    if not user32.SetCursorPos(int(x), int(y)):
        raise ctypes.WinError(ctypes.get_last_error())


def _mouse_flags(flags: int, data: int = 0) -> None:
    inp = INPUT()
    inp.type = INPUT_MOUSE
    inp.union.mi = MOUSEINPUT(0, 0, data, flags, 0, None)
    n = user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))
    if n != 1:
        raise ctypes.WinError(ctypes.get_last_error())


def apply_buttons(mask: int) -> None:
    """Sync button downs/ups to [mask] bitfield."""
    global _buttons
    mask &= 0x7
    changed = _buttons ^ mask
    if changed & 0x1:
        _mouse_flags(MOUSEEVENTF_LEFTDOWN if mask & 0x1 else MOUSEEVENTF_LEFTUP)
    if changed & 0x2:
        _mouse_flags(MOUSEEVENTF_RIGHTDOWN if mask & 0x2 else MOUSEEVENTF_RIGHTUP)
    if changed & 0x4:
        _mouse_flags(MOUSEEVENTF_MIDDLEDOWN if mask & 0x4 else MOUSEEVENTF_MIDDLEUP)
    _buttons = mask


def ensure_on_primary() -> tuple[int, int]:
    """If the cursor is off the captured primary monitor, warp it to the center."""
    left, top, width, height = monitor_origin_size()
    pt = wintypes.POINT()
    if not user32.GetCursorPos(ctypes.byref(pt)):
        raise ctypes.WinError(ctypes.get_last_error())
    x, y = int(pt.x), int(pt.y)
    right = left + max(width, 1)
    bottom = top + max(height, 1)
    if x < left or x >= right or y < top or y >= bottom:
        x = left + width // 2
        y = top + height // 2
        if not user32.SetCursorPos(x, y):
            raise ctypes.WinError(ctypes.get_last_error())
    return x, y


def move_rel(dx: int, dy: int) -> None:
    """Pixel-accurate relative move on the primary (captured) monitor."""
    if dx == 0 and dy == 0:
        return
    left, top, width, height = monitor_origin_size()
    x, y = ensure_on_primary()
    nx = max(left, min(left + max(width, 1) - 1, x + int(dx)))
    ny = max(top, min(top + max(height, 1) - 1, y + int(dy)))
    if not user32.SetCursorPos(nx, ny):
        raise ctypes.WinError(ctypes.get_last_error())


def center_on_primary() -> None:
    """Place cursor at primary center (call when Remote Desktop session starts)."""
    left, top, width, height = monitor_origin_size()
    x = left + max(width, 1) // 2
    y = top + max(height, 1) // 2
    if not user32.SetCursorPos(int(x), int(y)):
        raise ctypes.WinError(ctypes.get_last_error())


def wheel(delta: int) -> None:
    if not delta:
        return
    _mouse_flags(MOUSEEVENTF_WHEEL, int(delta) * WHEEL_DELTA)


def hwheel(delta: int) -> None:
    """Horizontal wheel (two-finger sideways scroll)."""
    if not delta:
        return
    _mouse_flags(MOUSEEVENTF_HWHEEL, int(delta) * WHEEL_DELTA)


def zoom(ticks: int) -> None:
    """Ctrl + wheel — browser/map zoom like a precision trackpad pinch."""
    if not ticks:
        return
    key(VK_CONTROL, True)
    try:
        wheel(int(ticks))
    finally:
        key(VK_CONTROL, False)


def chord(*vks: int) -> None:
    """Press all keys then release in reverse (Win+Tab, Alt+Tab, …)."""
    ordered = [int(v) & 0xFF for v in vks if int(v) != 0]
    if not ordered:
        return
    for vk in ordered:
        key(vk, True)
    for vk in reversed(ordered):
        key(vk, False)


def pointer(u: float, v: float, buttons: int = 0, wheel_ticks: int = 0) -> None:
    move_to_uv(u, v)
    apply_buttons(buttons)
    if wheel_ticks:
        wheel(wheel_ticks)


def click(u: float, v: float, button: int = 0) -> None:
    move_to_uv(u, v)
    bit = 1 << max(0, min(button, 2))
    apply_buttons(_buttons | bit)
    apply_buttons(_buttons & ~bit)


def key(vk: int, down: bool) -> None:
    inp = INPUT()
    inp.type = INPUT_KEYBOARD
    inp.union.ki = KEYBDINPUT(int(vk) & 0xFF, 0, 0 if down else KEYEVENTF_KEYUP, 0, None)
    n = user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))
    if n != 1:
        raise ctypes.WinError(ctypes.get_last_error())


def release_all() -> None:
    apply_buttons(0)
