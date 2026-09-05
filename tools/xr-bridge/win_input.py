"""Low-latency Windows mouse/keyboard injection for remote desktop."""

from __future__ import annotations

import ctypes
from ctypes import wintypes

from desktop_capture import monitor_origin_size

user32 = ctypes.windll.user32

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
WHEEL_DELTA = 120

INPUT_MOUSE = 0
INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002


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
    user32.SetCursorPos(x, y)


def _mouse_flags(flags: int, data: int = 0) -> None:
    inp = INPUT()
    inp.type = INPUT_MOUSE
    inp.union.mi = MOUSEINPUT(0, 0, data, flags, 0, None)
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))


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


def move_rel(dx: int, dy: int) -> None:
    """Relative mouse move (laptop trackpad style)."""
    if dx == 0 and dy == 0:
        return
    inp = INPUT()
    inp.type = INPUT_MOUSE
    inp.union.mi = MOUSEINPUT(int(dx), int(dy), 0, MOUSEEVENTF_MOVE, 0, None)
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))


def wheel(delta: int) -> None:
    if not delta:
        return
    _mouse_flags(MOUSEEVENTF_WHEEL, int(delta) * WHEEL_DELTA)


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
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))


def release_all() -> None:
    apply_buttons(0)
