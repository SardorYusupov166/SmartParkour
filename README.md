# SmartParkour 0.8.0 (experimental)

Fabric client mod for Minecraft 1.21.11. Java 21.

## Keys
- P: settings
- O: toggle automation

## New in 0.8
- Adaptive jump input based on target distance; optional midair braking.
- Detect large position changes (server teleport) and begin recovery to configured pressure plate XYZ.
- Simple ground walking and one-block jump toward the plate; no general pathfinding.
- Wait on the plate for gray-wool platforms before restarting.
- Russian and English settings labels, version 0.8.0.

## Important limitations
- No guarantee of successful landing; actual movement depends on server physics and latency.
- Recovery works only for a safe, straightforward route. If the route is blocked, the controller stops rather than crossing a gap.
- A teleport shorter than 8 blocks is not automatically recognized.
- Pressure plate coordinates are the player position at the plate (use the in-game position button while standing on it).
- If identical gray wool platforms appear simultaneously, their intended order cannot be inferred reliably.
- Source is not yet verified with a full Gradle build or in-game test.

Upload to GitHub keeping directories intact; existing .github/workflows/build.yml can be reused.
