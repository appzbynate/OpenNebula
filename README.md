# OpenNebula [Work in Progress]

OpenNebula is an open-source spatial computing runtime, driver interface, and multi-window workspace for **Nreal / XREAL Air** AR glasses on Android.

It provides a lightweight, independent alternative to proprietary AR companion runtimes—interfacing directly with hardware over USB to deliver high-rate IMU tracking, low-latency stereoscopic rendering, and spatial desktop windowing.

---

## Key Features & Capabilities

- **Direct Hardware Interfacing**:
  - Direct communication over USB HID endpoints with the glasses' MCU and IMU subsystems (VID `0x3318`, PID `0x0424`).
  - High-speed 1,000 Hz raw sensor packet streaming (ICM-20602 IMU).
  - Hardware display mode configuration (1080p 2D mirror, 72 Hz / 90 Hz / 120 Hz, and Half-SBS 3D mode 8).
  - EEPROM extraction and parsing of factory calibration matrices (`accel_q_gyro` misalignment quaternion, gyro/accel scale factors, biases).

- **High-Precision Sensor Fusion (AHRS)**:
  - **Lie Group SO(3) Integration**: Closed-form exponential map integration preserving quaternion norm ($\|\Delta q\| \equiv 1$) across high angular velocities.
  - **Monado Reference Pipeline Alignment**: Factory die misalignment rotation and sensitivity compensation.
  - **Temporal Zero-Velocity Update (ZUPT)**: 100 ms sustained stillness detector that clamps micro-tremor and drift at rest without eating zero-crossing turnarounds during rapid head shakes.
  - **Continuous Bayesian Gravity Horizon**: Automatic tilt correction balancing pure gyro integration during rapid glances with continuous Earth gravity lock at rest.

- **Low-Latency Spatial Rendering**:
  - **Asynchronous TimeWarp (ATW-Lite)**: Refresh-adaptive pose forward extrapolation compensating for Android `SurfaceFlinger` and DisplayPort Alt Mode scanout latency.
  - **Stereoscopic 3D Projection**: Custom off-axis projection matrices matching optical FOV (~46° diagonal, 16:9 aspect per eye) with adjustable IPD.
  - **Physiological Neck Pivot Modeling**: Compensates for cervical spine C1/C2 rotation offset (~10 cm below, ~8 cm behind eyes).

- **Spatial Workspace & Productivity**:
  - Floating 3D spatial browser panels.
  - Windows desktop streaming and screen mirror projection.
  - Low-latency phone-as-trackpad and laser pointer interaction.

---

## Architecture Overview

```
                      [ Nreal / XREAL Air Glasses ]
                                  │
                          (USB-C / DP Alt Mode)
                                  ▼
               ┌──────────────────────────────────────┐
               │         USB HID Subsystem            │
               │  - 1000 Hz Bulk/Interrupt In Reader  │
               │  - Kernel Buffer Drain Loop          │
               │  - Factory Calibration Parser        │
               └──────────────────┬───────────────────┘
                                  │ Raw ImuSample
                                  ▼
               ┌──────────────────────────────────────┐
               │          IMU Fusion Pipeline         │
               │  - Monado Die Alignment & Scaling    │
               │  - Thermal Bias Model (x-io Offset)  │
               │  - Temporal ZUPT Stillness Detector  │
               │  - Lie Group SO(3) Exponential Map   │
               │  - Bayesian Gravity Tilt Feedback    │
               └──────────────────┬───────────────────┘
                                  │ Orientation Quat + Gyro
                                  ▼
               ┌──────────────────────────────────────┐
               │       OpenGL ES World Renderer       │
               │  - Refresh-Adaptive ATW Extrapolation│
               │  - Off-Axis Stereo Projection        │
               │  - Neck Pivot Parallax Compensation  │
               │  - Secondary Display Presentation    │
               └──────────────────────────────────────┘
```

---

## Tech Stack

- **Platform**: Android SDK / NDK (Kotlin, Java)
- **Graphics**: OpenGL ES 2.0 / 3.0, GLSL Shaders
- **Hardware Protocols**: USB HID, DisplayPort Alt Mode, USB OTG
- **Companion Tools**: Python (`pyusb`, `hidapi`), Windows remote streaming bridge

---

## Status

**Active Work in Progress.** OpenNebula is an experimental project exploring the practical limits of spatial computing and driver stability on tethered AR glasses.
