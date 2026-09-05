"""
Write FreeTrack 2.0 shared memory so OpenVR-OpenTrack sees head pose.

Phone sends Madgwick yaw/pitch/roll (degrees). FreeTrack wants radians with
OpenTrack's usual yaw/pitch negation. Axis mix-ups are handled by PoseMap.
"""

from __future__ import annotations

import csv
import ctypes
import math
import time
from ctypes import wintypes
from pathlib import Path

FT_MEM = "FT_SharedMem"

# Nod below horizon → slight left tilt: OpenVR-OpenTrack Euler(FT.Roll,-FT.Yaw,FT.Pitch)
# couples negative pitch into +roll (left). Cancel only when looking down.
PITCH_ROLL_CROSS = 0.18

PAGE_READWRITE = 0x04
FILE_MAP_WRITE = 0x0002
FILE_MAP_READ = 0x0004
INVALID_HANDLE_VALUE = wintypes.HANDLE(-1)

# Phone y,p,r → FreeTrack degrees BEFORE OpenTrack negation (-yaw, -pitch, +roll).
# Names describe phone→FT channel routing.
POSE_MAPS: dict[str, str] = {
    # Previous bridge behavior (SteamVR felt: pitch dead / yaw couples roll).
    "legacy": "y,p,r",
    # Nod↔tilt swap — first attempt.
    "swap_pr": "y,r,p",
    "swap_pr_invr": "y,r,-p",
    # Turn↔nod swap (current symptom after swap_pr).
    "swap_yp": "p,y,r",
    "swap_yp_inv": "-p,-y,r",
    "swap_yp_n": "p,-y,r",
    # Combined: yaw↔pitch and pitch↔roll from phone.
    "air": "p,r,y",
    "air2": "r,y,p",
    "inv_p": "y,-p,r",
    "inv_r": "y,p,-r",
    "inv_yr": "-y,p,-r",
    "raw": "y,p,r",  # same channels; still apply FT negations unless noted
}


def apply_channel_map(spec: str, yaw: float, pitch: float, roll: float) -> tuple[float, float, float]:
    """
    Map phone (yaw,pitch,roll) → (Y,P,R) degrees for FreeTrack input.
    Spec is three comma-separated tokens from {y,p,r,-y,-p,-r}.
    """
    src = {"y": yaw, "p": pitch, "r": roll, "-y": -yaw, "-p": -pitch, "-r": -roll}
    parts = [p.strip().lower() for p in spec.split(",")]
    if len(parts) != 3 or any(p not in src for p in parts):
        raise ValueError(f"bad pose map spec {spec!r}")
    return src[parts[0]], src[parts[1]], src[parts[2]]


class PoseAxisLog:
    """CSV of phone euler → mapped → FreeTrack radians for diagnosing axis mixups."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self._n = 0
        self._last_flush = 0.0
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = self.path.open("w", newline="", encoding="utf-8")
        self._w = csv.writer(self._fh)
        self._w.writerow(
            [
                "t_mono",
                "n",
                "map",
                "phone_yaw",
                "phone_pitch",
                "phone_roll",
                "map_yaw",
                "map_pitch",
                "map_roll",
                "ft_yaw_rad",
                "ft_pitch_rad",
                "ft_roll_rad",
                "height_m",
            ]
        )
        self._fh.flush()

    def write(self, row: list) -> None:
        self._n += 1
        self._w.writerow(row)
        now = time.monotonic()
        # Flush often so a short test still leaves a readable file.
        if self._n < 40 or self._n % 15 == 0 or (now - self._last_flush) > 0.5:
            self._fh.flush()
            self._last_flush = now

    def close(self) -> None:
        try:
            self._fh.flush()
            self._fh.close()
        except Exception:
            pass


class FTData(ctypes.Structure):
    _fields_ = [
        ("DataID", ctypes.c_uint32),
        ("CamWidth", ctypes.c_int32),
        ("CamHeight", ctypes.c_int32),
        ("Yaw", ctypes.c_float),
        ("Pitch", ctypes.c_float),
        ("Roll", ctypes.c_float),
        ("X", ctypes.c_float),
        ("Y", ctypes.c_float),
        ("Z", ctypes.c_float),
        ("RawYaw", ctypes.c_float),
        ("RawPitch", ctypes.c_float),
        ("RawRoll", ctypes.c_float),
        ("RawX", ctypes.c_float),
        ("RawY", ctypes.c_float),
        ("RawZ", ctypes.c_float),
        ("X1", ctypes.c_float),
        ("Y1", ctypes.c_float),
        ("X2", ctypes.c_float),
        ("Y2", ctypes.c_float),
        ("X3", ctypes.c_float),
        ("Y3", ctypes.c_float),
        ("X4", ctypes.c_float),
        ("Y4", ctypes.c_float),
    ]


class FreeTrackWriter:
    def __init__(self, pose_map: str = "legacy", log_path: Path | None = None) -> None:
        self._ok = False
        self._handle = None
        self._view = None
        self._data: FTData | None = None
        self._id = 0
        self._err = ""
        self.pose_map = pose_map if pose_map in POSE_MAPS else "legacy"
        self._spec = POSE_MAPS[self.pose_map]
        self._log = PoseAxisLog(log_path) if log_path else None
        self._print_every = 0
        try:
            k32 = ctypes.WinDLL("kernel32", use_last_error=True)
            k32.CreateFileMappingW.argtypes = [
                wintypes.HANDLE,
                ctypes.c_void_p,
                wintypes.DWORD,
                wintypes.DWORD,
                wintypes.DWORD,
                wintypes.LPCWSTR,
            ]
            k32.CreateFileMappingW.restype = wintypes.HANDLE
            k32.MapViewOfFile.argtypes = [
                wintypes.HANDLE,
                wintypes.DWORD,
                wintypes.DWORD,
                wintypes.DWORD,
                ctypes.c_size_t,
            ]
            k32.MapViewOfFile.restype = ctypes.c_void_p

            size = max(ctypes.sizeof(FTData), 256)
            handle = k32.CreateFileMappingW(
                INVALID_HANDLE_VALUE,
                None,
                PAGE_READWRITE,
                0,
                size,
                FT_MEM,
            )
            if not handle:
                self._err = f"CreateFileMapping err={ctypes.get_last_error()}"
                return
            ptr = k32.MapViewOfFile(handle, FILE_MAP_WRITE | FILE_MAP_READ, 0, 0, size)
            if not ptr:
                self._err = f"MapViewOfFile err={ctypes.get_last_error()}"
                return
            self._handle = handle
            self._view = ptr
            self._data = FTData.from_address(ptr)
            self._data.CamWidth = 100
            self._data.CamHeight = 100
            self._ok = True
        except Exception as err:
            self._err = str(err)
            self._ok = False

    @property
    def ok(self) -> bool:
        return self._ok

    @property
    def error(self) -> str:
        return self._err

    def write_euler(self, yaw_deg: float, pitch_deg: float, roll_deg: float, y_m: float) -> None:
        """
        Phone degrees → FreeTrack SHM.

        Rotation: same OpenTrack-style mapping that was working (yaw/roll correct),
        with pitch sign flipped only.
        Height: OpenVR-OpenTrack reads up-axis from FreeTrack Z (mm), not Y.
        """
        if not self._ok or self._data is None:
            return
        my, mp, mr = apply_channel_map(self._spec, yaw_deg, pitch_deg, roll_deg)
        d2r = math.pi / 180.0
        # OpenTrack FreeTrack convention (was correct for yaw + roll):
        yaw = -my * d2r
        # Pitch only: invert vs previous (-mp) so nod matches look direction.
        pitch = mp * d2r
        # Below horizon (mp < 0): cancel left-tilt leak. Looking up: leave roll alone.
        # roll_out = mr + CROSS * min(mp, 0)  → looking down subtracts left (+roll).
        mr_out = mr + PITCH_ROLL_CROSS * min(mp, 0.0)
        roll = mr_out * d2r

        height_m = y_m if y_m >= 1.55 else 1.75
        z_mm = height_m * 1000.0  # SteamVR up-axis (driver: pos.y = FT.Z * 0.001)

        self._id = (self._id + 1) & 0xFFFFFFFF
        d = self._data
        d.DataID = self._id
        d.Yaw = yaw
        d.Pitch = pitch
        d.Roll = roll
        d.X = 0.0
        d.Y = 0.0
        d.Z = z_mm
        d.RawYaw = yaw
        d.RawPitch = pitch
        d.RawRoll = roll
        d.RawX = 0.0
        d.RawY = 0.0
        d.RawZ = z_mm

        if self._log is not None:
            self._log.write(
                [
                    f"{time.monotonic():.3f}",
                    self._id,
                    self.pose_map,
                    f"{yaw_deg:.3f}",
                    f"{pitch_deg:.3f}",
                    f"{roll_deg:.3f}",
                    f"{my:.3f}",
                    f"{mp:.3f}",
                    f"{mr_out:.3f}",
                    f"{yaw:.5f}",
                    f"{pitch:.5f}",
                    f"{roll:.5f}",
                    f"{height_m:.3f}",
                ]
            )
        self._print_every += 1
        if self._print_every == 1 or self._print_every % 120 == 0:
            print(
                f"[pose-axis] map={self.pose_map} phone ypr="
                f"{yaw_deg:+.1f},{pitch_deg:+.1f},{roll_deg:+.1f} → "
                f"ft ypr_deg={-my:+.1f},{mp:+.1f},{mr_out:+.1f} heightZ={height_m:.2f}m"
            )

    def close_log(self) -> None:
        if self._log is not None:
            self._log.close()
            self._log = None
