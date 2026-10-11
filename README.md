# SmartParkour 0.8.8 — precise landing

Minecraft Java 1.21.11 / Fabric. Client-side source project.

- **O**: toggle automation; **P**: settings.
- Target lock, post-landing centering, configurable delay.
- Sneak starts **only once the player feet X/Z position is inside the destination block** while descending above its top surface.
- Landing requires `onGround`, feet Y equal to `targetBlockY + 1.0` within 0.001, and feet X/Z inside the destination block.
- Keep sneak held during centering; release once centered and nearly stopped.
- No teleportation. No server-side changes.

**Note:** Source is not Gradle-built or tested in Minecraft in this environment. Sneaking in mid-air does not itself cancel momentum; flight steering includes velocity-aware braking.
