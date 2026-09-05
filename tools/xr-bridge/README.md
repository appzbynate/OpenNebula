# OpenNebula XR Bridge

Open replacement for **Nebula’s SteamVR / CloudXR** tile.

## Quick start

### PC

```bat
tools\xr-bridge\start_bridge.bat
```

Leave that window open. On first phone **Go**:

1. Downloads/installs [OpenVR-OpenTrack FreeTrack](https://github.com/r57zone/OpenVR-OpenTrack) into `SteamVR\drivers\opentrack`
2. Writes FreeTrack shared memory from glasses IMU pose (no separate Opentrack app required)
3. Launches **SteamVR** and enables Desktop Mirror / Home
4. Streams Mirror (or a VR lobby placeholder) fullscreen to the glasses

Steam + SteamVR must already be installed. Allow firewall UDP/TCP **38400–38404**.

### Phone — SteamVR

1. Same Wi‑Fi as the PC (use a `192.168.x` IP, not VPN)
2. OpenNebula → glasses connected → **SteamVR** icon  
3. Enter PC IP → height → **Go**  
4. SteamVR should open on the PC; glasses show as the OpenTrack HMD  

### Phone — Remote Desktop

1. Bridge running (same as above)
2. **Remote Desktop** icon → PC IP → **Go**
3. Floating Windows desktop panel on the glasses; aim + tap to click (mouse injected on the PC)

## Protocol

- Discovery UDP `38400` beacon: `ONXR|1|<hostname>|<pose>|<video>|<control>`
- Pose UDP `38401`: LE `magic,seq,t_ns,qw,qx,qy,qz,x,y,z,height`
- Video TCP `38402`: LE `magic,u32 len` + JPEG (SteamVR SBS)
- Desktop video TCP `38404`: same framing, primary-monitor capture
- Control TCP `38403` (persistent lines):
  - `LAUNCH` / `HELLO` → SteamVR + HMD driver
  - `DESKTOP` → warm desktop capture
  - `PTR u v buttons [wheel]` → mouse (u,v 0..1 on primary)
  - `CLICK u v [button]`
  - `KEY down|up vk`
## Notes

- Gray/empty mirror usually means SteamVR has no active HMD yet — the bridge installs OpenTrack and feeds FreeTrack pose so Home can present.
- Until Mirror shows content, the phone gets an immersive lobby JPEG (not a flat gray panel).
- Optional: still run [Opentrack](https://github.com/opentrack/opentrack) with UDP input on `:4242` if you prefer that path; the bridge also forwards pose there.
