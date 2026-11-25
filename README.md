# FakeTabPreview

Тестовый плагин для Paper 1.21.7: показывает, как выглядят таб и список игроков
при наведении на пинг, когда игроков много и у них донат-префиксы.

Фейки — **только** записи в табе (`PlayerInfoUpdate`/`PlayerInfoRemove`) и в player sample пинга.
Это не подключения и не сущности: в `Bukkit.getOnlinePlayers()` их нет, писать им, телепортироваться к ним
и т. п. нельзя.

## Сборка

Нужен только JDK 17+ для запуска Gradle; JDK 21 и 25 для компиляции Gradle скачает сам (toolchains).

```powershell
.\gradlew.bat build
```

Готовые jar:

| Платформа | Файл | Требования |
|---|---|---|
| Paper | `faketab-paper/build/libs/FakeTabPreview-Paper-1.0.0.jar` | Paper **1.21.7**, Java 21 |
| Velocity | `faketab-velocity/build/libs/FakeTabPreview-Velocity-1.0.0.jar` | Velocity **4.x** (API 4.3.1), Java 25 |

Пакеты отправляются напрямую через NMS (paperweight-userdev, Mojang-маппинги), **ProtocolLib не нужен**.
Обратная сторона — jar привязан к 1.21.7: после обновления Paper его нужно пересобрать
(весь NMS-код в `faketab-paper/.../packet/PacketSender.java`).

## Установка

### Paper
1. Положите `FakeTabPreview-Paper-1.0.0.jar` в `plugins/`.
2. Запустите сервер — появится `plugins/FakeTabPreview/config.yml`.
3. (Опционально) LuckPerms: при `groups.use-luckperms: true` префиксы берутся из одноимённых групп LuckPerms.

### Velocity
1. Положите `FakeTabPreview-Velocity-1.0.0.jar` в `plugins/` прокси.
2. В `plugins/faketabpreview/config.properties` укажите `backend` — имя сервера из `velocity.toml`.
3. На Paper в `ping.allowed-ips` добавьте IP прокси (обычно `127.0.0.1`), иначе backend не отдаст фейков прокси.
4. Если на прокси нужен `add-to-count=true`, включите и `ping.add-to-count: true` на Paper:
   прокси считает фейков как «онлайн backend − реальные игроки на нём».
   Paper прибавляет фейков только для адресов из `allowed-ips`.

Как это работает: Velocity-плагин раз в `sync-interval-seconds` пингует backend через
`RegisteredServer#ping()` и кэширует его player sample, а в `ProxyPingEvent` подмешивает его к пингу прокси.
MOTD прокси не трогается. Работает, даже когда на сервере нет ни одного реального игрока.

#### Альтернатива без второго плагина
В `velocity.toml`:
```toml
ping-passthrough = "all"
```
Прокси будет отдавать пинг backend-сервера целиком. Минусы:
- MOTD, иконка и версия прокси заменяются данными backend;
- backend видит IP прокси, а не клиента, поэтому фильтр `allowed-ips` на Paper не работает —
  фейков увидят **все**, включая мониторинги.

## Команды (только консоль)

Любая подкоманда `/faketab` от игрока, командного блока или RCON отклоняется: «Только консоль.»

| Команда | Что делает |
|---|---|
| `faketab on` | Плавно выйти на `default-percent`% слотов (с учётом реальных игроков) |
| `faketab off` | Плавно убрать всех фейков |
| `faketab off instant` | Убрать всех мгновенно |
| `faketab fill <percent>` | Цель = `max-players × percent% − реальные` (не меньше 0), дробные значения допустимы |
| `faketab set <count>` | Фиксированная цель |
| `faketab add <count>` / `faketab remove <count>` | Изменить цель на `count` |
| `faketab rotate now` | Сразу заменить долю `rotation.fraction` фейков |
| `faketab rotate on` / `off` | Включить/выключить периодическую ротацию |
| `faketab speed <batch> <ticks>` | Скорость появления/исчезновения на лету (не сохраняется в конфиг) |
| `faketab status` | Текущее/целевое количество, % слотов, ротация, скорость |
| `faketab reload` | Перечитать конфиг, текущие фейки сохраняются |

Примеры:
```
faketab fill 75
faketab fill 33.5
faketab speed 10 5
faketab set 300
faketab rotate now
faketab off instant
```

В режимах `on`/`fill` цель пересчитывается при входе и выходе реальных игроков
(`spawn.percent-tracks-real-players`). `set`/`add`/`remove` задают фиксированную цель.
Если цель выше `warn-threshold`, в консоль пишется предупреждение, но команда выполняется.

## Конфиг Paper (`config.yml`)

Полный файл с комментариями: [faketab-paper/src/main/resources/config.yml](faketab-paper/src/main/resources/config.yml).
Основное:

```yaml
default-percent: 50.0
warn-threshold: 500
spawn:
  batch-size: 3
  batch-interval-ticks: 20
rotation:
  enabled: true
  interval-seconds: 90
  jitter-seconds: 30
  fraction: 0.1
entry:
  latency: { min: 25, max: 140 }
  gamemode: SURVIVAL
groups:
  assign-mode: default      # default — все новые фейки в группе default-group; weighted — случайно по весам
  default-group: default
  use-luckperms: true
  list:
    default: { format: "<gray><name>", weight: 60, order: 0 }
    vip:     { format: "<green>[VIP]</green> <white><name>", weight: 18, order: 10 }
    premium: { format: "<gradient:#00c6ff:#0072ff>[Premium]</gradient> <white><name>", weight: 10, order: 20 }
ping:
  enabled: true
  sample-size: 12
  add-to-count: false
  allowed-ips: ["127.0.0.1", "0:0:0:0:0:0:0:1"]
```

- `format` — MiniMessage (градиенты, hex), `<name>` заменяется ником.
- `order` — `listOrder` записи в табе: больше — выше. Реальные игроки имеют 0.
- Ники: из пула `names.base` с вариациями (`Steve_42`, `xNovax`, `Raven2011`…), без повторов среди
  активных и без совпадений с реальными игроками онлайн. Если игрок зашёл с ником фейка, фейк заменяется.

## Пинг и мониторинги

По умолчанию фейки в пинге видят только `127.0.0.1`/`::1`. Добавьте свой IP в `ping.allowed-ips`
(и в `allowed-ips` Velocity-плагина), чтобы видеть их в своём списке серверов.
Остальные, в том числе мониторинги, видят реальный онлайн. `"*"` показывает фейков всем, но мониторинги
банят за накрутку онлайна.

## Известные ограничения

- Paper-jar работает только на 1.21.7 (NMS). Velocity-jar — только на Velocity 4.x с Java 25.
- У фейков стандартный скин (по UUID), без текстур.
- `allowed-ips` сравнивается строкой с `InetAddress#getHostAddress()`; IPv6 нужно писать в полной форме
  (`0:0:0:0:0:0:0:1`), CIDR не поддерживается.
- Velocity-плагин отделяет фейков от реальных игроков по UUID игроков, подключённых к backend через прокси.
