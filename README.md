# SmartParkour 0.10.3 — Warp + walk to plate

Minecraft Java 1.21.11 / Fabric client mod source.

Recovery sequence: scan for jump targets -> send `/warp parkour` once -> wait for server teleport -> walk to configurable plate coordinates (default -65 84 306) -> wait for gray wool platforms -> resume parkour.

Settings (P) > All timers > Recovery timers: warp wait, walk-to-plate timeout, start wait. Timers saved in `config/smartparkour.properties`.

Limitations: The walk is direct and checks for solid ground ahead; it cannot navigate around walls or jump gaps. If warp lands less than 3 blocks from the prior position, arrival may not be detected. Source not compiled or play-tested.
