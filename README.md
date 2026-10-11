# SmartParkour 0.9.9 — Movement Fix

Minecraft Java 1.21.11 / Fabric.

- Fixed sign of camera-relative strafing in `steer()` (A/D).
- Normalized steering direction to avoid dead zones for short vectors.
- Added a departure-edge emergency takeoff threshold.
- Preserved existing target lock, adaptive jumps, landing, centering, return-home, configuration, O/P hotkeys and menu behavior.

**Not yet Gradle-built or in-game tested.** Test diagonal jumps and near-edge takeoffs before unattended use.
