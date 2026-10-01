# Logic_for_plugin

LogicTierPlugin: демо-плагин автоматической выдачи PvP-тиров (LT5 → HT1) для Paper/Purpur 1.21.

## Структура

- `plugin/` — исходники плагина (Gradle, Java 21)
  - `core` — Elo, Skill Score, тиры
  - `storage` — БД (SQLite через HikariCP, MySQL опционально)
  - `tracker` — сессии боя и метрики (точность, урон, комбо)
  - `commands` — `/tier`, `/tieradmin`, `/duel` (заглушка)
  - `adapter` — слушатели событий Bukkit
- `logic.md`, `logic_demo.md`, `realization.md` — проектные заметки

## Сборка

```bat
cd plugin
gradlew.bat shadowJar
```

Готовый jar: `plugin/build/libs/LogicTierPlugin-1.0.0-SNAPSHOT.jar`. Скопировать его в `plugins/` сервера.

## Запуск сервера

Положить `server.jar` (Paper/Purpur 1.21.x) в корень и запустить `start.bat`. Нужна Java 21+.
