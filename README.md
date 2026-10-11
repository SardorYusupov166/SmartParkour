# SmartParkour v0.6.0 (experimental)

Fabric client mod for Minecraft 1.21.11.

## Controls
- **P**: settings menu (while in game)
- **O**: enable/disable automation (R is no longer used)

## Settings
- Fallback on/off and destination XYZ, with **Use my position**
- Full stop after landing on/off
- Center on gray wool before next jump on/off
- Landing delay in 50ms steps, default 350ms
- Center tolerance, default 0.15 blocks
- Save and close writes `config/smartparkour.properties` immediately

**Important:** Build is untested against the live Minecraft/Yarn environment. The parkour routing is experimental; equal gray-wool platforms appearing simultaneously cannot be reliably ordered. Centering can fail in narrow/dynamic situations; disable it if it causes trouble. No void traversal is guaranteed.

## GitHub Actions
Upload all files preserving directory structure. Existing `.github/workflows/build.yml` can remain unchanged. In Actions download the compiled `smartparkour-0.6.0.jar` (not sources).

## Changelog
- 0.6.0: in-game settings, adjustable landing delay, full stop and centering toggles, P/O keys, persistent config, version display.
- 0.5.0: platform appearance identities, coordinate reuse.
- 0.4.0: visited platform tracking.
- 0.3.0: landing stop and fallback navigation.
