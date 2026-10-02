# Logic_for_plugin

LogicTierPlugin: демо-плагин автоматической выдачи PvP-тиров (LT5 → HT1) для Paper 1.21.11.

## Структура

- `plugin/` — исходники плагина (Gradle, Java 21)
  - `core` — чистая логика без Bukkit: Elo по режимам, защита от повторных матчей,
    Skill Score, тир-движок (временный тир → фиксация), симулятор
  - `storage` — SQLite/MySQL через общий диалект, асинхронная запись в отдельном потоке
  - `tracker` — метрики боя без Bukkit: взмахи, попадания, урон нанесённый/полученный,
    точность, CPS (средний и пиковый), комбо (макс., средняя и медианная длина серии).
    О боях знает только через интерфейс `ActiveFights`
  - `fight` — жизненный цикл боя (старт, победа, отмена); реализует `ActiveFights`
  - `commands` — `/tier`, `/tieradmin`, `/duel` (заглушка)
  - `adapter` — слушатели Bukkit (`FightListener`, `MetricsListener`) и `ConfigLoader`
- `logic.md`, `logic_demo.md`, `realization.md` — проектные заметки

## Сборка

```bat
cd plugin
gradlew.bat test shadowJar
```

Готовый jar: `plugin/build/libs/LogicTierPlugin-1.0.0-SNAPSHOT.jar`. Скопировать его в `plugins/` сервера.

Симулятор формул без сервера (200 виртуальных игроков × 30 дней, плюс тест фарма):

```bat
gradlew.bat runSimulation
```

## Команды

| Команда | Что делает |
|---|---|
| `/tier [игрок]` | тир, Elo, Skill, уверенность по каждому режиму (игрок может быть офлайн) |
| `/tieradmin fight <p1> <p2> [sword\|crystal\|cart]` | начать рейтинговый бой; победа — убийство соперника |
| `/tieradmin simulate [игроков] [дней]` | прогнать симулятор прямо в игре |
| `/tieradmin set <игрок> <режим> <тир>` | выставить тир вручную |
| `/tieradmin reset <игрок> [режим]` | сбросить рейтинг |
| `/tieradmin metrics <игрок> [режим]` | метрики текущего боя и сводка по последним 20 боям |
| `/tieradmin info <игрок>`, `/tieradmin stats` | диагностика |

## База данных

Таблицы `players`, `ratings` (игрок × режим), `matches`, `metrics` (игрок × матч).
По умолчанию SQLite (`plugins/LogicTierPlugin/tierplugin.db`); для MySQL — `database.type: mysql`
в `config.yml` (драйвер уже есть в Paper). Все запросы идут в отдельном потоке, главный поток сервера не блокируется.

## Запуск сервера

Положить `server.jar` (Paper 1.21.11) в корень и запустить `start.bat`. Нужна Java 21+.
