"""
Launch SteamVR and install OpenVR-OpenTrack FreeTrack as an HMD.

SteamVR Fail (422) here is VRInitError_Compositor_InvalidOutputDesktop:
OpenTrack's HMD window must sit on a real DXGI desktop output (primary monitor).
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

CACHE_DIR = Path(__file__).resolve().parent / "hmd_driver"
OPENVR_OPENTRACK_URL = (
    "https://github.com/r57zone/OpenVR-OpenTrack/releases/download/1.1/"
    "SteamVR.OpenTrack.FreeTrack.1.1.zip"
)

_lock = threading.Lock()
_last_launch_mono = 0.0


def steam_root() -> Path | None:
    pf = os.environ.get("ProgramFiles(x86)") or os.environ.get("ProgramFiles")
    if not pf:
        return None
    p = Path(pf) / "Steam"
    return p if p.exists() else None


def steamvr_root() -> Path | None:
    root = steam_root()
    if not root:
        return None
    p = root / "steamapps" / "common" / "SteamVR"
    return p if p.exists() else None


def steamvr_bin() -> Path | None:
    root = steamvr_root()
    if not root:
        return None
    for sub in ("bin/win64", "bin/win32"):
        b = root / sub
        if (b / "vrstartup.exe").exists():
            return b
    return None


def primary_monitor_size() -> tuple[int, int]:
    try:
        import ctypes
        user32 = ctypes.windll.user32
        return int(user32.GetSystemMetrics(0)), int(user32.GetSystemMetrics(1))
    except Exception:
        return 1920, 1080


def _process_running(*names: str) -> bool:
    try:
        out = subprocess.check_output(
            ["tasklist", "/FO", "CSV", "/NH"],
            text=True,
            errors="ignore",
            timeout=8,
        ).lower()
    except Exception:
        return False
    return any(n.lower() in out for n in names)


def steamvr_running() -> bool:
    return _process_running("vrserver.exe", "vrmonitor.exe", "vrcompositor.exe")


def kill_steamvr() -> str:
    names = (
        "vrserver", "vrcompositor", "vrmonitor", "vrstartup",
        "vrdashboard", "vrwebhelper", "steamvr_room_setup",
    )
    for n in names:
        subprocess.run(
            ["taskkill", "/F", "/IM", f"{n}.exe"],
            check=False,
            capture_output=True,
        )
    time.sleep(1.5)
    return "SteamVR processes stopped" if not steamvr_running() else "SteamVR still running"


def fix_openvr_paths() -> str:
    """Remove duplicate / conflicting external driver entries."""
    path = Path(os.environ.get("LOCALAPPDATA", "")) / "openvr" / "openvrpaths.vrpath"
    if not path.exists():
        return "openvrpaths.vrpath missing"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        ext = data.get("external_drivers") or []
        cleaned = []
        for e in ext:
            low = str(e).lower().replace("/", "\\")
            # Keep Virtual Desktop disabled in settings; drop from path to avoid races.
            if "virtual desktop" in low:
                continue
            # opentrack already lives under SteamVR/drivers — duplicate external path
            # contributes to "Skipping duplicate" + flaky compositor init.
            if low.endswith("\\drivers\\opentrack") or low.endswith("\\opentrack"):
                continue
            if e not in cleaned:
                cleaned.append(e)
        data["external_drivers"] = cleaned
        path.write_text(json.dumps(data, indent="\t"), encoding="utf-8")
        return f"openvrpaths external_drivers={cleaned}"
    except Exception as err:
        return f"openvrpaths failed: {err}"


def ensure_opentrack_driver() -> str:
    """Install FreeTrack OpenVR HMD driver under SteamVR/drivers/opentrack."""
    svr = steamvr_root()
    if not svr:
        return "SteamVR not installed — install SteamVR, then tap Go again"
    dest = svr / "drivers" / "opentrack"
    manifest = dest / "driver.vrdrivermanifest"
    if not manifest.exists():
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        zip_path = CACHE_DIR / "SteamVR.OpenTrack.FreeTrack.1.1.zip"
        try:
            if not zip_path.exists():
                print("[hmd] downloading OpenVR-OpenTrack FreeTrack 1.1…")
                urllib.request.urlretrieve(OPENVR_OPENTRACK_URL, zip_path)
            extract = CACHE_DIR / "extract"
            if extract.exists():
                shutil.rmtree(extract, ignore_errors=True)
            extract.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(zip_path, "r") as zf:
                zf.extractall(extract)
            candidates = list(extract.rglob("driver.vrdrivermanifest"))
            if not candidates:
                return "driver zip missing driver.vrdrivermanifest"
            src_root = candidates[0].parent
            if dest.exists():
                shutil.rmtree(dest, ignore_errors=True)
            shutil.copytree(src_root, dest)
        except Exception as err:
            return f"driver install failed: {err}"

    # Window must land on a real DXGI output (Fail 422 = InvalidOutputDesktop).
    mon_w, mon_h = primary_monitor_size()
    win_w = min(1920, max(1280, mon_w // 2))
    win_h = min(1080, max(720, mon_h - 80))
    if win_w > mon_w - 40:
        win_w = max(800, mon_w - 80)
    if win_h > mon_h - 40:
        win_h = max(600, mon_h - 80)

    settings = dest / "resources" / "settings" / "default.vrsettings"
    try:
        if settings.exists():
            data = json.loads(settings.read_text(encoding="utf-8"))
            for key in ("opentrack", "driver_opentrack"):
                block = data.setdefault(key, {})
                if not isinstance(block, dict):
                    continue
                block["enable"] = True
                block["DebugMode"] = False
                block["windowWidth"] = win_w
                block["windowHeight"] = win_h
                block["renderWidth"] = win_w
                block["renderHeight"] = win_h
                block["DistanceBetweenEyes"] = 0
                block["windowX"] = 0
                block["windowY"] = 0
                block["ScreenOffsetX"] = 0
                block["FOV"] = 57.296
                block["displayFrequency"] = 60
                block["ZoomWidth"] = 1.0
                block["ZoomHeight"] = 1.0
            settings.write_text(json.dumps(data, indent=2), encoding="utf-8")
    except Exception:
        pass
    return f"HMD driver ready at {dest} window={win_w}x{win_h}@0,0 (monitor {mon_w}x{mon_h})"


def enable_force_hmd() -> str:
    """SteamVR settings that avoid InvalidOutputDesktop / bad mirror state."""
    root = steam_root()
    if not root:
        return "Steam not found"
    settings = root / "config" / "steamvr.vrsettings"
    try:
        data = {}
        if settings.exists():
            data = json.loads(settings.read_text(encoding="utf-8"))
        steamvr = data.setdefault("steamvr", {})
        steamvr["requireHmd"] = True
        steamvr["activateMultipleDrivers"] = False
        # Home often triggers compositor races with desktop HMDs.
        steamvr["enableHomeApp"] = False
        steamvr["showMirrorView"] = True
        steamvr["mirrorViewEye"] = 0
        steamvr["forcedDriver"] = "opentrack"
        # Clear broken mirror geometry if present.
        for bad in ("mirrorWindowX", "mirrorWindowY", "mirrorWindowWidth", "mirrorWindowHeight"):
            steamvr.pop(bad, None)
        data.setdefault("driver_null", {})["enable"] = False
        data.setdefault("driver_opentrack", {})["enable"] = True
        for name in ("driver_VirtualDesktop", "driver_vrlink"):
            data.setdefault(name, {})["enable"] = False
        # DesktopUI pairing/settings sometimes store invalid mirror rects.
        desktop = data.get("DesktopUI")
        if isinstance(desktop, dict):
            desktop.pop("mirror", None)
        settings.parent.mkdir(parents=True, exist_ok=True)
        settings.write_text(json.dumps(data, indent=2), encoding="utf-8")
        return f"updated {settings}"
    except Exception as err:
        return f"steamvr.vrsettings failed: {err}"


def launch_steamvr() -> str:
    global _last_launch_mono
    if steamvr_running():
        return "SteamVR already running (skip relaunch)"
    now = time.monotonic()
    if now - _last_launch_mono < 25.0:
        return "SteamVR launch debounced"
    _last_launch_mono = now
    bin64 = steamvr_bin()
    # Prefer vrstartup directly (more reliable than steam:// when repairing).
    if bin64:
        exe = bin64 / "vrstartup.exe"
        try:
            subprocess.Popen([str(exe)], cwd=str(bin64))
            for _ in range(50):
                time.sleep(0.2)
                if steamvr_running():
                    return f"launched {exe.name} (vrserver up)"
            return f"launched {exe.name} (vrserver not seen yet)"
        except Exception as err:
            return f"vrstartup failed: {err}"
    try:
        os.startfile("steam://rungameid/250820")  # type: ignore[attr-defined]
        return "launched steam://rungameid/250820"
    except Exception as err:
        return f"SteamVR launch failed: {err}"


def activate_headset_session(repair: bool = True) -> str:
    """Repair display/driver config, then launch SteamVR once."""
    with _lock:
        lines: list[str] = []
        if repair:
            if steamvr_running():
                # Stuck Fail 422 sessions need a full restart after config repair.
                lines.append(kill_steamvr())
            lines.append(fix_openvr_paths())
        lines.append(ensure_opentrack_driver())
        lines.append(enable_force_hmd())
        lines.append(launch_steamvr())
        return " | ".join(lines)
