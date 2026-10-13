# SmartParkour 1.0.2 (experimental source)

- Recovery diagnostics in P > Return and recovery > Return logs (up to 60 events, paginated).
- Records recovery source, reason, state, movement mode, return method, target, last known platform.
- Target switching alone no longer counts as route progress.
- Logs recovery retries, timeout, failed start, empty scans, teleport failures and cancellation when platforms reappear.
- Return grace window and limited repeated recovery retries.

Logs are in memory and reset when Minecraft exits. The displayed clock is mod client ticks, not wall time.

Source only: compilation and in-game tests have NOT been completed.
