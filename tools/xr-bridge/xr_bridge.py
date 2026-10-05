#!/usr/bin/env python3
"""
OpenNebula XR Bridge — PC companion.

Phone checks TCP :38402. Video MUST listen even without mss/pillow
(earlier builds exited the video thread on ImportError → phone could never connect).
"""

from __future__ import annotations

import argparse
import io
import os
import socket
import struct
import threading
import time
from dataclasses import dataclass

from freetrack_shm import FreeTrackWriter, POSE_MAPS
from steamvr_hmd import activate_headset_session
from pathlib import Path

MAGIC_U32 = 0x52584E4F  # 'ONXR' LE
DISCOVERY_PORT = 38400
POSE_PORT = 38401
VIDEO_PORT = 38402
CONTROL_PORT = 38403
DESKTOP_VIDEO_PORT = 38404
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


def pose_loop(stop: threading.Event, opentrack: bool, pose_map: str = "legacy") -> None:
    global latest, pose_packets
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", POSE_PORT))
    sock.settimeout(0.5)
    out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM) if opentrack else None
    log_path = Path(__file__).resolve().parent / "pose_axis.csv"
    ft = FreeTrackWriter(pose_map=pose_map, log_path=log_path)
    print(
        f"[pose] UDP listening 0.0.0.0:{POSE_PORT}"
        + (" → OpenTrack :4242" if opentrack else "")
        + (f" → FreeTrack SHM OK map={ft.pose_map}" if ft.ok else f" (FreeTrack SHM fail: {ft.error})")
    )
    print(f"[pose] axis diag → {log_path}")
    print("[pose] test: nod (pitch), turn (yaw), tilt (roll) — watch [pose-axis] lines")
    try:
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
                # Same channel map as FreeTrack (degrees) for OpenTrack UDP consumers.
                from freetrack_shm import POSE_MAPS, apply_channel_map

                my, mp, mr = apply_channel_map(POSE_MAPS[ft.pose_map], yaw_deg, pitch_deg, roll_deg)
                pkt = struct.pack(
                    "<dddddd",
                    float(my),
                    float(mp),
                    float(mr),
                    0.0,
                    float(height) * 100.0,
                    0.0,
                )
                out.sendto(pkt, ("127.0.0.1", OPENTRACK_PORT))
    finally:
        sock.close()
        if out:
            out.close()
        ft.close_log()


# Shared mutable target for desktop capture (phone REFRESH / DESKTOP <hz>).
desktop_target_fps = 90.0
desktop_fps_lock = threading.Lock()


def set_desktop_target_fps(hz: float) -> float:
    global desktop_target_fps
    rate = 60.0 if hz < 75 else (90.0 if hz < 105 else 120.0)
    with desktop_fps_lock:
        desktop_target_fps = rate
    print(f"[desktop] capture target → {rate:.0f} fps (match glasses refresh; avoid duplication gap)")
    return rate


def control_loop(stop: threading.Event) -> None:
    """Persistent line protocol on TCP :38403.

    LAUNCH / HELLO  → SteamVR + HMD driver
    DESKTOP [hz]    → switch primary to 1080p + warm capture, OK
    REFRESH hz      → retarget desktop capture fps to glasses panel rate
    DESKTOP_END     → restore native resolution
    PTR u v buttons [wheel]  → absolute mouse (u,v in 0..1 on primary)
    REL dx dy [buttons]      → relative mouse (trackpad)
    CLICK u v [button]
    KEY down|up vk
    """
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", CONTROL_PORT))
    except OSError as err:
        print(f"[control] FATAL cannot bind :{CONTROL_PORT}: {err}")
        return
    server.listen(4)
    server.settimeout(1.0)
    print(f"[control] TCP listening 0.0.0.0:{CONTROL_PORT} (LAUNCH / DESKTOP / REFRESH / PTR)")

    def handle_line(line: str, conn: socket.socket) -> None:
        raw = line.strip()
        if not raw:
            return
        upper = raw.upper()
        if upper.startswith("LAUNCH") or upper.startswith("HELLO"):
            msg = activate_headset_session()
            print(f"[hmd] {msg}")
            conn.sendall(b"OK SteamVR launching\n")
            return
        if upper.startswith("DESKTOP_END") or upper in ("DESKTOP END", "DESKTOP_OFF"):
            from display_mode import restore_native_resolution

            msg = restore_native_resolution()
            print(f"[desktop] {msg}")
            conn.sendall(f"OK {msg}\n".encode("utf-8"))
            return
        if upper.startswith("REFRESH"):
            parts = raw.split()
            hz = float(parts[1]) if len(parts) >= 2 else 90.0
            rate = set_desktop_target_fps(hz)
            conn.sendall(f"OK refresh {rate:.0f}\n".encode("utf-8"))
            return
        if upper.startswith("DESKTOP"):
            from desktop_capture import primary_monitor_size, capture_note
            from display_mode import ensure_capture_resolution
            import win_input

            parts = raw.split()
            if len(parts) >= 2:
                try:
                    set_desktop_target_fps(float(parts[1]))
                except ValueError:
                    set_desktop_target_fps(90.0)
            else:
                set_desktop_target_fps(90.0)
            mode_msg = ensure_capture_resolution()
            print(f"[desktop] {mode_msg}")
            primary_monitor_size()
            print(f"[desktop] {capture_note()}")
            try:
                win_input.center_on_primary()
                print("[desktop] cursor centered on primary", flush=True)
            except OSError as err:
                print(f"[desktop] center cursor failed: {err}", flush=True)
            conn.sendall(f"OK desktop ready ({mode_msg})\n".encode("utf-8"))
            return
        parts = raw.split()
        cmd = parts[0].upper()
        try:
            import win_input
        except Exception as err:
            conn.sendall(f"ERR input {err}\n".encode("utf-8"))
            return
        try:
            if cmd == "PTR" and len(parts) >= 4:
                u = float(parts[1])
                v = float(parts[2])
                buttons = int(float(parts[3]))
                wheel = int(float(parts[4])) if len(parts) >= 5 else 0
                win_input.pointer(u, v, buttons, wheel)
            elif cmd == "REL" and len(parts) >= 3:
                dx = int(float(parts[1]))
                dy = int(float(parts[2]))
                try:
                    win_input.move_rel(dx, dy)
                    n = getattr(handle_line, "_rel_n", 0) + 1
                    handle_line._rel_n = n  # type: ignore[attr-defined]
                    if n == 1 or n % 30 == 0:
                        print(f"[control] REL#{n} {dx},{dy}", flush=True)
                    try:
                        with open(
                            os.path.join(os.path.dirname(__file__), "rel_log.txt"),
                            "a",
                            encoding="utf-8",
                        ) as f:
                            f.write(f"{n} {dx} {dy}\n")
                    except OSError:
                        pass
                    if len(parts) >= 4:
                        win_input.apply_buttons(int(float(parts[3])))
                except OSError as err:
                    print(f"[control] mouse REL failed: {err}")
                    conn.sendall(f"ERR mouse {err}\n".encode("utf-8"))
                    return
            elif cmd == "WHEEL" and len(parts) >= 2:
                win_input.wheel(int(float(parts[1])))
            elif cmd == "HWHEEL" and len(parts) >= 2:
                win_input.hwheel(int(float(parts[1])))
            elif cmd == "ZOOM" and len(parts) >= 2:
                win_input.zoom(int(float(parts[1])))
            elif cmd == "CHORD" and len(parts) >= 2:
                win_input.chord(*(int(p, 0) for p in parts[1:]))
            elif cmd == "CLICK" and len(parts) >= 3:
                u = float(parts[1])
                v = float(parts[2])
                button = int(float(parts[3])) if len(parts) >= 4 else 0
                win_input.click(u, v, button)
            elif cmd == "KEY" and len(parts) >= 3:
                down = parts[1].lower() in ("down", "1", "d")
                vk = int(parts[2], 0)
                win_input.key(vk, down)
            else:
                conn.sendall(b"ERR unknown\n")
                return
        except Exception as err:
            print(f"[control] input error: {err}")
            conn.sendall(f"ERR {err}\n".encode("utf-8"))

    while not stop.is_set():
        try:
            conn, addr = server.accept()
        except socket.timeout:
            continue
        except OSError:
            break
        threading.Thread(
            target=_control_client,
            args=(conn, addr, stop, handle_line),
            daemon=True,
            name="xr-control-client",
        ).start()
    server.close()


def _control_client(conn: socket.socket, addr, stop: threading.Event, handle_line) -> None:
    print(f"[control] client {addr[0]}")
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    conn.settimeout(1.0)
    buf = ""
    try:
        while not stop.is_set():
            try:
                chunk = conn.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            if not chunk:
                break
            buf += chunk.decode("utf-8", errors="ignore")
            while "\n" in buf:
                line, buf = buf.split("\n", 1)
                handle_line(line, conn)
    except OSError as err:
        print(f"[control] client error: {err}")
    finally:
        try:
            import win_input
            win_input.release_all()
        except Exception:
            pass
        try:
            from display_mode import restore_native_resolution

            msg = restore_native_resolution()
            if msg != "no saved resolution":
                print(f"[desktop] {msg}")
        except Exception:
            pass
        conn.close()
        print(f"[control] client {addr[0]} gone")


def _latest_frame_server(
    stop: threading.Event,
    port: int,
    label: str,
    grab_fn,
    fps: float,
    quality: int,
) -> None:
    """Shared latest-frame MJPEG TCP server (SteamVR + desktop)."""
    global video_clients
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", port))
    except OSError as err:
        print(f"[{label}] FATAL cannot bind :{port}: {err}")
        return
    server.listen(2)
    server.settimeout(1.0)
    lobby = make_vr_lobby_jpeg()
    # Desktop: start at 120 fps target; REFRESH/DESKTOP lines retarget dynamically.
    # SteamVR / --fps 0: uncapped (period 0).
    initial = float(fps)
    if label == "desktop" and initial <= 0:
        initial = desktop_target_fps
    period_holder = [0.0 if initial <= 0 else 1.0 / max(initial, 1.0)]
    print(
        f"[{label}] TCP listening 0.0.0.0:{port}  "
        f"target={'uncapped' if period_holder[0] <= 0 else f'{1.0 / period_holder[0]:.0f} fps'} q={quality}"
    )
    while not stop.is_set():
        try:
            conn, addr = server.accept()
        except socket.timeout:
            continue
        except OSError:
            break
        video_clients += 1
        print(f"[{label}] phone connected from {addr[0]}")
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            try:
                conn.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 64 * 1024)
            except OSError:
                pass
            latest_jpeg: list[bytes | None] = [lobby]
            latest_lock_v = threading.Lock()
            gen = [0]
            frame_ready = threading.Event()
            conn_stop = threading.Event()

            def capture_worker() -> None:
                while not stop.is_set() and not conn_stop.is_set():
                    t0 = time.time()
                    jpeg = grab_fn(quality) or lobby
                    with latest_lock_v:
                        latest_jpeg[0] = jpeg
                        gen[0] += 1
                    frame_ready.set()
                    if label == "desktop":
                        with desktop_fps_lock:
                            tgt = desktop_target_fps
                        period_holder[0] = 0.0 if tgt <= 0 else 1.0 / max(tgt, 1.0)
                    period = period_holder[0]
                    if period > 0:
                        remain = period - (time.time() - t0)
                        if remain > 0.0005:
                            time.sleep(remain)
                    # else: full-tilt — no sleep

            cap_t = threading.Thread(target=capture_worker, name=f"{label}-cap", daemon=True)
            cap_t.start()
            sent_gen = -1
            try:
                while not stop.is_set():
                    frame_ready.wait(timeout=0.05)
                    frame_ready.clear()
                    with latest_lock_v:
                        g = gen[0]
                        jpeg = latest_jpeg[0]
                    if jpeg is None or g == sent_gen:
                        continue
                    sent_gen = g
                    header = struct.pack("<II", MAGIC_U32, len(jpeg))
                    conn.sendall(header + jpeg)
            finally:
                conn_stop.set()
        except OSError as err:
            print(f"[{label}] phone disconnected: {err}")
        finally:
            conn.close()
    server.close()


def video_loop(stop: threading.Event, fps: float, quality: int) -> None:
    has_cap = grab_steamvr_or_desktop_jpeg(quality) is not None
    try:
        from steamvr_capture import ensure_steamvr_mirror, find_capture_rect
        mirror_msg = ensure_steamvr_mirror()
        _rect, cap_note = find_capture_rect()
    except Exception as err:
        mirror_msg = str(err)
        cap_note = "capture helper unavailable"
    print(f"[video] {mirror_msg}")
    print(f"[video] {cap_note}  live_steamvr_frame={'yes' if has_cap else 'no — lobby until Mirror visible'}")

    def grab(q: int):
        return grab_steamvr_or_desktop_jpeg(q)

    _latest_frame_server(stop, VIDEO_PORT, "video", grab, fps, quality)


def desktop_video_loop(stop: threading.Event, fps: float, quality: int) -> None:
    from desktop_capture import grab_desktop_jpeg, primary_monitor_size, capture_note

    primary_monitor_size()
    print(f"[desktop] {capture_note()}")
    # Prefer glasses-matched rate (default 90). Uncapped CLI --fps 0 starts at 90 for desktop.
    if fps <= 0:
        set_desktop_target_fps(90.0)
        fps = 90.0
    else:
        set_desktop_target_fps(fps)

    def grab(q: int):
        # Slightly lighter encode when chasing 120 so capture can keep up.
        with desktop_fps_lock:
            tgt = desktop_target_fps
        max_w = 1600 if tgt >= 110 else 1920
        return grab_desktop_jpeg(quality=q, max_w=max_w)

    _latest_frame_server(stop, DESKTOP_VIDEO_PORT, "desktop", grab, fps, quality)


def main() -> None:
    ap = argparse.ArgumentParser(description="OpenNebula XR Bridge PC companion")
    ap.add_argument("--no-opentrack", action="store_true")
    ap.add_argument("--no-video", action="store_true")
    # 0 = uncapped (lowest latency). Capture/encode is the limiter.
    ap.add_argument("--fps", type=float, default=0.0)
    ap.add_argument("--quality", type=int, default=35)
    ap.add_argument(
        "--pose-map",
        default="legacy",
        choices=sorted(POSE_MAPS.keys()),
        help="Phone→FreeTrack axis map (default legacy = yaw/pitch/roll straight)",
    )
    args = ap.parse_args()

    stop = threading.Event()
    threads = [
        threading.Thread(target=beacon_loop, args=(stop,), daemon=True),
        threading.Thread(
            target=pose_loop,
            args=(stop, not args.no_opentrack, args.pose_map),
            daemon=True,
        ),
        threading.Thread(target=control_loop, args=(stop,), daemon=True),
    ]
    if not args.no_video:
        threads.append(threading.Thread(target=video_loop, args=(stop, args.fps, args.quality), daemon=True))
        # Desktop: gaming latency — 1080p + moderate JPEG 4:2:0 (was q75–88 4:4:4).
        desk_q = min(max(args.quality + 15, 48), 58)
        threads.append(
            threading.Thread(target=desktop_video_loop, args=(stop, args.fps, desk_q), daemon=True),
        )
    for t in threads:
        t.start()
    time.sleep(0.3)
    lan = [ip for ip in local_ips() if ip.startswith("192.168.") or (ip.startswith("10.") and not ip.startswith("10.5."))]
    print("=" * 60)
    print("  OpenNebula XR Bridge RUNNING — leave this window open")
    print("  Phone → SteamVR  OR  Remote Desktop → PC IP → Go")
    print(f"  Pose map: {args.pose_map}  ({POSE_MAPS[args.pose_map]})  log: pose_axis.csv")
    print("  SteamVR: immersive SBS on :%d   Desktop: floating panel on :%d" % (VIDEO_PORT, DESKTOP_VIDEO_PORT))
    for ip in (lan or local_ips() or ["(run ipconfig)"]):
        print(f"      {ip}")
    print(f"  Ports: UDP {DISCOVERY_PORT}/{POSE_PORT}  TCP {VIDEO_PORT}/{CONTROL_PORT}/{DESKTOP_VIDEO_PORT}")
    print("=" * 60)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        stop.set()
        try:
            from display_mode import restore_native_resolution

            print(f"[desktop] {restore_native_resolution()}")
        except Exception:
            pass
        print("bye")


if __name__ == "__main__":
    main()
