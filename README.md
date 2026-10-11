# SmartParkour 0.5 (experimental)

Fabric 1.21.11 client-only source. Requires Java 21 and Fabric API.

## New in 0.5
- Scans loaded nearby blocks for `minecraft:gray_wool` each tick.
- Tracks each continuous appearance of a gray-wool block with an internal ID.
- A visited platform is excluded only for its current appearance; if it disappears and later reappears at the same coordinates it receives a new ID.
- Stops after landing before choosing the next jump (inherited 0.4 logic).
- Optional ground-only fallback to coordinates via `config/smartparkour.properties`; when reached, returns to parkour mode.
- If another gray-wool block is visible but unreachable, waits rather than starting fallback.

## Important limitations
- When two identical platforms are already present, their intended order cannot be inferred reliably. The mod uses a distance heuristic and may skip a required platform.
- This is an untested experimental controller; compile and test carefully. It may miss jumps or move off edges. Use on a test course first.
- If a platform vanishes and respawns within a single client tick, polling may not distinguish the replacement.
- Fallback movement is ground-only and has no obstacle pathfinding.
- Key R toggles the mod and may conflict with shader reload bindings.

## Build
Upload the contents (including `.github/workflows/build.yml`) to GitHub and check Actions. The workflow builds and uploads `build/libs/*.jar`. The build has not been verified here.
