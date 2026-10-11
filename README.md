# SmartParkour 0.9.7 — Continue in menus

Minecraft Java 1.21.11 / Fabric.

- **O**: toggle parkour (when no screen is open).
- **P**: open settings (when no screen is open).
- **Continue parkour in menus**: new toggle, off by default. Saved in `config/smartparkour.properties` as `continueInMenus`.
- With the option enabled, the controller continues processing movement while GUI screens are open. The vanilla pause screen is also prevented from pausing singleplayer ticks via a client mixin.
- With the option disabled, automation releases controls when a screen opens.
- Settings screen remains non-pausing; opening it still resets the current jump plan. Controls continue if the toggle is enabled.

**Limitations:** No live Minecraft/Gradle test has been performed. Vanilla screen input handling may affect simulated movement in some GUIs. The keybinds O/P are intentionally handled only in normal gameplay to avoid typing conflicts.
