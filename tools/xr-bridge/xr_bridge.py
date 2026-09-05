#!/usr/bin/env python3
"""
OpenNebula XR Bridge — PC companion.

Phone checks TCP :38402. Video MUST listen even without mss/pillow
(earlier builds exited the video thread on ImportError → phone could never connect).
"""

from __future__ import annotations

import argparse
import io
import socket
import struct
import threading
import time
from dataclasses import dataclass

from freetrack_shm import FreeTrackWriter
from steamvr_hmd import activate_headset_session

MAGIC_U32 = 0x52584E4F  # 'ONXR' LE
DISCOVERY_PORT = 38400
POSE_PORT = 38401
VIDEO_PORT = 38402
CONTROL_PORT = 38403
OPENTRACK_PORT = 4242


@dataclass
class Pose:
    qw: float
    qx: float
    qy: float
    qz: float
    y: float


latest: Pose | None = None
latest_lock = threading.Lock()
pose_packets = 0
video_clients = 0


def hostname() -> str:
    try:
        return socket.gethostname()
    except OSError:
        return "PC"


def local_ips() -> list[str]:
    """Prefer real LAN IPs; deprioritize VPN / link-local."""
    found: list[str] = []
    try:
        for info in socket.getaddrinfo(hostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and ip not in found:
                found.append(ip)
    except OSError:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip not in found:
            found.append(ip)
    except OSError:
        pass

    def rank(ip: str) -> tuple[int, str]:
        if ip.startswith("192.168.") or ip.startswith("10.") or ip.startswith("172."):
            if ip.startswith("10.5.") or ip.startswith("10.8.") or ip.startswith("10.9."):
                return (2, ip)  # common VPN ranges
            return (0, ip)
        if ip.startswith("169.254."):
            return (3, ip)
        if ip.startswith("127."):
            return (9, ip)
        return (1, ip)

    return sorted([ip for ip in found if not ip.startswith("127.")], key=rank)


def make_vr_lobby_jpeg(width: int = 960, height: int = 540) -> bytes:
    """Bright, unmistakable placeholder so a blank glasses view means 'no TCP video'."""
    try:
        from PIL import Image, ImageDraw
        img = Image.new("RGB", (width, height), (0, 180, 80))
        draw = ImageDraw.Draw(img)
        for i in range(0, width, 40):
            draw.rectangle([i, 0, i + 20, height], fill=(0, 140, 220))
        draw.rectangle([40, 40, width - 40, height - 40], outline=(255, 255, 0), width=8)
        draw.text((60, 80), "OpenNebula VIDEO OK", fill=(255, 255, 255))
        draw.text((60, 140), "If you see this on the glasses, capture is next.", fill=(255, 255, 200))
        draw.text((60, height - 80), "Waiting for SteamVR window on PC…", fill=(255, 255, 255))
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=70)
        return buf.getvalue()
    except Exception:
        # Minimal valid JPEG
        return bytes([
            0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF, 0xDB, 0x00, 0x43,
            0x00, 0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09,
            0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12,
            0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20,
            0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29,
            0x2C, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32,
            0x3C, 0x2E, 0x33, 0x34, 0x32, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x01,
            0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xFF, 0xC4, 0x00, 0x14, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x03, 0xFF, 0xC4, 0x00, 0x14, 0x10, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
            0x7F, 0xFF, 0xD9,
        ])


def make_placeholder_jpeg(text: str = "OpenNebula XR Bridge") -> bytes:
    return make_vr_lobby_jpeg()


def grab_steamvr_or_desktop_jpeg(quality: int) -> bytes | None:
    """Prefer SteamVR Mirror / OpenTrack HMD window; else None (caller uses lobby)."""
    try:
        from steamvr_capture import grab_steamvr_frame

        jpeg, note = grab_steamvr_frame(quality)
        if jpeg and grab_steamvr_or_desktop_jpeg._note != note:
            print(f"[video] {note}")
            grab_steamvr_or_desktop_jpeg._note = note
        return jpeg
    except Exception as err:
        if grab_steamvr_or_desktop_jpeg._note != str(err):
            print(f"[video] capture error: {err}")
            grab_steamvr_or_desktop_jpeg._note = str(err)
        return None


grab_steamvr_or_desktop_jpeg._note = ""


def beacon_loop(stop: threading.Event) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    # Also unicast to common LAN broadcast if possible
    msg = f"ONXR|1|{hostname()}|{POSE_PORT}|{VIDEO_PORT}|{CONTROL_PORT}".encode("utf-8")
    while not stop.is_set():
        try:
            sock.sendto(msg, ("255.255.255.255", DISCOVERY_PORT))
            for ip in local_ips():
                parts = ip.split(".")
                if len(parts) == 4 and parts[0] in ("192", "10", "172"):
                    bcast = ".".join(parts[:3] + ["255"])
                    sock.sendto(msg, (bcast, DISCOVERY_PORT))
        except OSError:
            pass
        stop.wait(1.0)
    sock.close()


def pose_loop(stop: threading.Event, opentrack: bool) -> None:
    global latest, pose_packets
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", POSE_PORT))
    sock.settimeout(0.5)
    out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM) if opentrack else None
    ft = FreeTrackWriter()
    print(
        f"[pose] UDP listening 0.0.0.0:{POSE_PORT}"
        + (" → OpenTrack :4242" if opentrack else "")
        + (f" → FreeTrack SHM OK" if ft.ok else f" (FreeTrack SHM fail: {ft.error})")
    )
    while not stop.is_set():
        try:
            data, addr = sock.recvfrom(64)
        except socket.timeout:
            continue
        except OSError:
            break
        if len(data) < 48:
            continue
        magic, seq, t_ns, qw, qx, qy, qz, yaw_deg, pitch_deg, roll_deg, height = struct.unpack(
            "<IIqffffffff", data[:48]
        )
        if magic != MAGIC_U32:
            continue
        pose_packets += 1
        if pose_packets == 1 or pose_packets % 500 == 0:
            print(
                f"[pose] packets={pose_packets} from {addr[0]} "
                f"ypr={yaw_deg:.1f},{pitch_deg:.1f},{roll_deg:.1f}"
            )
        pose = Pose(qw, qx, qy, qz, height)
        with latest_lock:
            latest = pose
        # Prefer phone Madgwick euler (x/y/z slots). Fallback if old app sent height in y.
        if abs(yaw_deg) < 0.01 and abs(pitch_deg) < 0.01 and abs(roll_deg) < 0.01 and abs(height) > 0.5:
            ft.write_euler(0.0, 0.0, 0.0, height)
        else:
            ft.write_euler(yaw_deg, pitch_deg, roll_deg, height)
        if out is not None:
            # OpenTrack UDP expects degrees; same axes as FreeTrack (no pitch/roll swap).
            pkt = struct.pack(
                "<dddddd",
                float(yaw_deg),
                float(pitch_deg),
                float(roll_deg),
                0.0,
                float(height) * 100.0,
                0.0,
            )
            out.sendto(pkt, ("127.0.0.1", OPENTRACK_PORT))
    sock.close()
    if out:
        out.close()


def control_loop(stop: threading.Event) -> None:
    """Phone sends LAUNCH / HELLO on TCP :38403 → start SteamVR + HMD driver."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", CONTROL_PORT))
    except OSError as err:
        print(f"[control] FATAL cannot bind :{CONTROL_PORT}: {err}")
        return
    server.listen(2)
    server.settimeout(1.0)
    print(f"[control] TCP listening 0.0.0.0:{CONTROL_PORT} (LAUNCH → SteamVR + HMD)")
    while not stop.is_set():
        try:
            conn, addr = server.accept()
        except socket.timeout:
            continue
        except OSError:
            break
        try:
            conn.settimeout(2.0)
            raw = conn.recv(256).decode("utf-8", errors="ignore").strip().upper()
            print(f"[control] {addr[0]} → {raw or '(empty)'}")
            if raw.startswith("LAUNCH") or raw.startswith("HELLO") or raw == "":
                msg = activate_headset_session()
                print(f"[hmd] {msg}")
                conn.sendall(b"OK SteamVR launching\n")
            else:
                conn.sendall(b"ERR unknown\n")
        except OSError as err:
            print(f"[control] client error: {err}")
        finally:
            conn.close()
    server.close()


def video_loop(stop: threading.Event, fps: float, quality: int) -> None:
    global video_clients
    # ALWAYS bind — phone probes this port before connecting.
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", VIDEO_PORT))
    except OSError as err:
        print(f"[video] FATAL cannot bind :{VIDEO_PORT}: {err}")
        return
    server.listen(2)
    server.settimeout(1.0)
    has_cap = grab_steamvr_or_desktop_jpeg(quality) is not None
    try:
        from steamvr_capture import ensure_steamvr_mirror, find_capture_rect
        mirror_msg = ensure_steamvr_mirror()
        _rect, cap_note = find_capture_rect()
    except Exception as err:
        mirror_msg = str(err)
        cap_note = "capture helper unavailable"
    print(f"[video] TCP listening 0.0.0.0:{VIDEO_PORT}")
    print(f"[video] {mirror_msg}")
    print(f"[video] {cap_note}  live_steamvr_frame={'yes' if has_cap else 'no — showing lobby until Mirror is visible'}")
    lobby = make_vr_lobby_jpeg()
    period = 1.0 / max(fps, 1.0)
    while not stop.is_set():
        try:
            conn, addr = server.accept()
        except socket.timeout:
            continue
        video_clients += 1
        print(f"[video] phone connected from {addr[0]} — immersive stream")
        # Do NOT launch SteamVR here — phone already sent LAUNCH on :38403.
        # Double-start caused SteamVR Fail (422) / Shared IPC Compositor 306.
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            while not stop.is_set():
                t0 = time.time()
                jpeg = grab_steamvr_or_desktop_jpeg(quality) or lobby
                header = struct.pack("<II", MAGIC_U32, len(jpeg))
                conn.sendall(header + jpeg)
                dt = time.time() - t0
                if dt < period:
                    time.sleep(period - dt)
        except OSError as err:
            print(f"[video] phone disconnected: {err}")
        finally:
            conn.close()
    server.close()


def main() -> None:
    ap = argparse.ArgumentParser(description="OpenNebula XR Bridge PC companion")
    ap.add_argument("--no-opentrack", action="store_true")
    ap.add_argument("--no-video", action="store_true")
    ap.add_argument("--fps", type=float, default=24.0)
    ap.add_argument("--quality", type=int, default=65)
    args = ap.parse_args()

    stop = threading.Event()
    threads = [
        threading.Thread(target=beacon_loop, args=(stop,), daemon=True),
        threading.Thread(target=pose_loop, args=(stop, not args.no_opentrack), daemon=True),
        threading.Thread(target=control_loop, args=(stop,), daemon=True),
    ]
    if not args.no_video:
        threads.append(threading.Thread(target=video_loop, args=(stop, args.fps, args.quality), daemon=True))
    for t in threads:
        t.start()
    time.sleep(0.3)
    lan = [ip for ip in local_ips() if ip.startswith("192.168.") or (ip.startswith("10.") and not ip.startswith("10.5."))]
    print("=" * 60)
    print("  OpenNebula XR Bridge RUNNING — leave this window open")
    print("  Phone → SteamVR → 192.168.x IP below → Go")
    print("  On Go: installs OpenTrack HMD driver, launches SteamVR, streams Home/Mirror")
    for ip in (lan or local_ips() or ["(run ipconfig)"]):
        print(f"      {ip}")
    print(f"  Ports: UDP {DISCOVERY_PORT}/{POSE_PORT}  TCP {VIDEO_PORT}/{CONTROL_PORT}")
    print("=" * 60)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        stop.set()
        print("bye")


if __name__ == "__main__":
    main()
