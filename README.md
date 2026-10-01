# Logic_for_plugin

LogicTierPlugin: демо-плагин автоматической выдачи PvP-тиров (LT5 → HT1) для Paper 1.21.11.

## Структура

- `plugin/` — исходники плагина (Gradle, Java 21)
  - `core` — Elo, Skill Score, тиры; без зависимостей от Bukkit, конфиг приходит как `CoreConfig`
  - `storage` — БД (SQLite через HikariCP, MySQL опционально)
  - `tracker` — сессии боя и метрики (точность, урон, комбо)
  - `commands` — `/tier`, `/tieradmin`, `/duel` (заглушка)
  - `adapter` — слушатели событий Bukkit и `ConfigLoader` (config.yml → `CoreConfig`)
- `logic.md`, `logic_demo.md`, `realization.md` — проектные заметки

## Сборка

```bat
cd plugin
gradlew.bat test shadowJar
```

Готовый jar: `plugin/build/libs/LogicTierPlugin-1.0.0-SNAPSHOT.jar`. Скопировать его в `plugins/` сервера.

## Запуск сервера

Положить `server.jar` (Paper 1.21.11) в корень и запустить `start.bat`. Нужна Java 21+.
