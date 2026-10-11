# SmartParkour 0.9.5 — Center Timeout

Minecraft Java 1.21.11 / Fabric.

- Landing centering has a hard 20-client-tick (~1 second at 20 TPS) timeout.
- If centering succeeds sooner, the original stable-center behavior remains.
- If time runs out, simulated movement and sneak are released, and the mod continues into the normal between-jumps delay from the player’s actual position.
- The timeout is per CENTER phase and is not reset by minor motion.
- All other behavior from 0.9.4 is retained.

Not verified with Gradle or an in-game test.


## 0.9.6 — Спасение от бездействия
В меню P добавлен переключатель «Спасение от бездействия» (по умолчанию выключен). При отсутствии движения, смены платформы или состояния в течение 100 клиентских тиков алгоритм сбрасывается и начинает поиск заново. Намеренная пауза между прыжками не считается зависанием. Значение хранится в `config/smartparkour.properties`.
