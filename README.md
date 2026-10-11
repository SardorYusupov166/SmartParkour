# SmartParkour 0.8.0 — source prototype

Minecraft Java 1.21.11 / Fabric / Java 21.

P toggles automation. This is a **source prototype, not a tested mod jar**.

Build: `gradle build` with Gradle 9 and internet access; resulting mod should be in `build/libs` after a successful build.

Known limitations:
- Exact Minecraft 1.21.11 API compatibility and dependency versions need verification by a real build.
- No start-plate coordinates or automatic reset yet.
- The nearest gray wool target heuristic can select the wrong block if unrelated gray wool is nearby.
- No physically simulated trajectory yet; 3-gap jumps and diagonal jumps need in-game tuning.
- Input override is temporary and restored on stop; screen transitions should be tested.
