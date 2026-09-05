#!/usr/bin/env python3
"""Pull-friendly analyzer for OpenNebula imu_trace / imu_events / imu_summary."""
from __future__ import annotations

import csv
import statistics as st
from pathlib import Path
import sys


def f(row: dict, key: str) -> float:
    try:
        return float(row.get(key) or 0)
    except ValueError:
        return 0.0


def analyze(trace: Path, events: Path | None = None) -> str:
    rows = list(csv.DictReader(trace.open(encoding="utf-8-sig")))
    if not rows:
        return "empty trace"
    still = [r for r in rows if f(r, "gyro_mag") < 3]
    move = [r for r in rows if f(r, "gyro_mag") >= 10]
    lines = [
        f"rows={len(rows)} still={len(still)} move={len(move)} version={rows[-1].get('version','?')}",
        f"panel_m={rows[-1].get('panel_m')} last_ypr="
        f"{rows[-1].get('yaw')},{rows[-1].get('pitch')},{rows[-1].get('roll')}",
    ]
    for name, subset in (("all", rows), ("still", still), ("move", move)):
        if len(subset) < 5:
            continue
        jumps = [f(r, "obj_jump") for r in subset]
        pose = [f(r, "pose_dps") for r in subset]
        mis = [abs(f(r, "mismatch")) for r in subset]
        rest_pct = 100 * sum(1 for r in subset if r.get("rest") == "1") / len(subset)
        grav_pct = 100 * sum(1 for r in subset if r.get("grav") == "1") / len(subset)
        jumps_s = sorted(jumps)
        lines.append(
            f"{name}: rest%={rest_pct:.0f} grav%={grav_pct:.0f} "
            f"objJ p50={st.median(jumps):.4f} p95={jumps_s[int(0.95*len(jumps_s))]:.4f} "
            f"poseDps p50={st.median(pose):.2f} misAbsMean={st.mean(mis):.2f}"
        )
        if name == "still" and len(subset) > 20:
            yaw = [f(r, "yaw") for r in subset]
            pitch = [f(r, "pitch") for r in subset]
            # unwrap-ish: report max consecutive wander in 2s windows
            win = 100
            max_yaw = 0.0
            max_pitch = 0.0
            for i in range(0, len(subset) - win, win // 2):
                ys = yaw[i : i + win]
                ps = pitch[i : i + win]
                max_yaw = max(max_yaw, max(ys) - min(ys))
                max_pitch = max(max_pitch, max(ps) - min(ps))
            lines.append(f"  still_2s_wander maxΔyaw={max_yaw:.3f} maxΔpitch={max_pitch:.3f}")

    if events and events.exists():
        ev = list(csv.DictReader(events.open(encoding="utf-8-sig")))
        from collections import Counter

        c = Counter(r.get("kind") for r in ev)
        lines.append("events: " + ", ".join(f"{k}={v}" for k, v in c.most_common()))
        for r in ev[-12:]:
            lines.append(f"  {r.get('kind')}: {r.get('detail')}")
    return "\n".join(lines)


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("tmp")
    trace = root / "imu_trace.csv"
    if not trace.exists():
        # allow dir that is the file's parent or diag folder contents dumped flat
        cands = list(root.rglob("imu_trace.csv"))
        if not cands:
            print(f"no imu_trace.csv under {root}")
            return 1
        trace = cands[0]
    events = trace.with_name("imu_events.csv")
    summary = trace.with_name("imu_summary.txt")
    print(analyze(trace, events))
    if summary.exists():
        print("--- device summary ---")
        print(summary.read_text(encoding="utf-8", errors="replace"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
