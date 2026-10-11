# SmartParkour 0.7.0 (Fabric 1.21.11)

**P** — settings / настройки. **O** — enable/disable / включить-выключить.

## Changes in 0.7.0
- Added an AIM phase: stop, rotate toward the target, confirm angle, then approach/jump.
- Configurable turn speed (5–90 degrees/tick) and angle tolerance (1–15 degrees).
- Russian and English language resources, selected automatically by Minecraft.
- Preserved landing delay, full stop, centering, fallback XYZ, and gray-wool lifecycle tracking.
- Mod version updated to 0.7.0.

## Build
Upload the project preserving paths and run GitHub Actions Gradle build. The `.github/workflows/build.yml` can remain unchanged. Download the non-sources JAR from the build artifacts.

**Experimental:** the source has not been compiled or tested in Minecraft here. Two indistinguishable platforms appearing simultaneously still cannot be ordered reliably.
