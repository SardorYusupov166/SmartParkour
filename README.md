# SmartParkour 0.10.7 — source

Fabric / Minecraft 1.21.11. Settings: P.

Movement mode switch on the main settings screen:
- **Осторожный** (default): original positioning, landing centering and configurable delay.
- **Сбалансированный**: allows a safe off-center takeoff within 0.30 blocks of platform center when momentum is low; uses sprint from 1.55 blocks, advances takeoff timing for long jumps with sufficient momentum; skips landing centering only when landing within 0.28 blocks of center and horizontal speed <0.095; skips the post-landing wait. Otherwise falls back to cautious positioning.

Mode is saved to `config/smartparkour.properties` as `movementMode=CAUTIOUS` or `BALANCED`. Switching modes resets the controller to avoid carrying over stale movement state. Recovery and `/warp parkour` remain unchanged.

This is an experimental source patch; not tested in Minecraft. Build with Java 21 and Gradle/Fabric Loom.

- **Читерский** (`CHEAT`): client-side `setPos` to the center of the next target, with feet at `blockY + 1.5` (half a block above the block's top). Clears velocity and waits for gravity before planning another target. This does NOT bypass server anti-cheat or guarantee server acceptance; rubberbanding is possible.

Mode cycle: Осторожный → Сбалансированный → Читерский → Осторожный.
