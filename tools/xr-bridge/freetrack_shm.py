"""
Write FreeTrack 2.0 shared memory so OpenVR-OpenTrack sees head pose.

Uses Madgwick yaw/pitch/roll (degrees) from the phone.
OpenTrack FreeTrack: radians, yaw/pitch negated, translation in mm.
"""

from __future__ import annotations

import ctypes
import math
from ctypes import wintypes

FT_MEM = "FT_SharedMem"

PAGE_READWRITE = 0x04
FILE_MAP_WRITE = 0x0002
FILE_MAP_READ = 0x0004
INVALID_HANDLE_VALUE = wintypes.HANDLE(-1)


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
    def __init__(self) -> None:
        self._ok = False
        self._handle = None
        self._view = None
        self._data: FTData | None = None
        self._id = 0
        self._err = ""
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
        """Madgwick degrees → FreeTrack radians (OpenTrack convention, no axis swap)."""
        if not self._ok or self._data is None:
            return
        d2r = math.pi / 180.0
        # OpenTrack: negate yaw + pitch. Nod → Pitch, tilt → Roll (no swap).
        yaw = -yaw_deg * d2r
        pitch = -pitch_deg * d2r
        roll = roll_deg * d2r
        y_mm = y_m * 1000.0

        self._id = (self._id + 1) & 0xFFFFFFFF
        d = self._data
        d.DataID = self._id
        d.Yaw = yaw
        d.Pitch = pitch
        d.Roll = roll
        d.X = 0.0
        d.Y = y_mm
        d.Z = 0.0
        d.RawYaw = yaw
        d.RawPitch = pitch
        d.RawRoll = roll
        d.RawX = 0.0
        d.RawY = y_mm
        d.RawZ = 0.0
