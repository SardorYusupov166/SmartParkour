# SmartParkour 0.10.1 (source)

Minecraft Java 1.21.11 / Fabric.

Changes from 0.10.0:
- On inactivity, scan for a reachable gray-wool target before switching to the home-return routine.
- New **Centering accuracy...** page in P settings: exact-center switch, independent X and Z tolerance in block pixels (0–8). Y landing tolerance is at most 0.1 block and grounded state is required.
- For center-to-center gaps of 2+ blocks, use sprint and approach toward a safe takeoff position instead of jumping from the center without sprint.
- Existing timer settings preserved.

Source archive only. Build/gameplay tests have not been performed; validate on a test course. No teleportation.
