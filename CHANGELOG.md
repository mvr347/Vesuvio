# Журнал изменений

## 2026-11-11 — ложные срабатывания и утечки памяти

### Системные причины (исправлены)
- Риск только рос, доверие только падало; множитель чувствительности доходил до 2.5 и ужесточал пороги (спираль). Теперь риск
  затухает (`scoring.risk.*`), доверие растёт (`scoring.trust.decay-clean-hours`), потолок множителя 1.3.
- Оконные проверки флагали на каждом пакете: единая точка `CheckPipeline#report`/`reportWindowed`, повторный флаг не чаще
  `layers.statistical.reflag-interval-ms` (4000).
- Флаг с одного события: StepUp, AirPlace, Scaffold, AutoCriticals, GhostHand, BlockReach, VehicleFly, BackTrack, SilentAim,
  KillauraAngle, GCDAim теперь копят улики.
- Снапшот окружения: «липкие» 20 тиков освобождения, новые поля (`stepHeight`, `nearLiquid`, `nearStuck`,
  `containerOpenSinceMillis`), «внутри блока» по реальному хитбоксу и форме коллизии.
- Время по часам сервера: FastBreak/FastEat/FastBow считаются в серверных тиках, FastPlace/Tower — окном событий (`RateWindow`).
- `punishments.excluded-checks` теперь действует на ВСЕ проверки (раньше только Phase и InvMove), VL не остаётся от отброшенного флага.

### Проверки
- Fly, Speed (флаги онграунда t-1/t-2, импульс прыжка), StepUp, Timer (пол по транзакциям), Blink (доказуемые тики), Phase
  («засыпан»), InvMove, Velocity.
- Бой: поворот после атаки (150 мс), WallHit по видимым точкам, не-игроки, PerfectAimLock/AimConsistency при движении
  требуемого угла, StaticAimTracking/TargetSwitch при взгляде мимо хитбокса, Reach (ближайшая точка, окно по скачку RTT),
  BackTrack (запас 300 мс), MoveDirection, ReactionTime выключен по умолчанию.
- Мир: свой отменённый блок открывает окно рассинхронизации, VehicleFly по тренду ΔY, VehicleClip по хитбоксу транспорта.

### Удалено
- X-Ray (XrayBurst, XrayStatistical), ключ `mechanics.world.xray`, подписи в i18n.

### Память и стабильность
- `StateJanitor` раз в 30 с чистит состояние вышедших игроков во всех хранилищах; утечки в `SpectateManager`, `DiscordWebhookService`.
- Предупреждения в листенерах пакетов не чаще раза в 10 с; кеш рефлексии в `LoveEssentialHook`; O(1) размер очереди БД
  и потеря 201-й записи при полной пачке исправлена.

### Рецензия
- ML только оповещает (`clickml`, `aimml` в `punishments.excluded-checks`, без штрафа доверия).
- `UserData#resetKinematicState`, ограничение оповещений `staff.smart-alerts.min-interval-ms`, `PunishmentService`.
- `task.txt` перенесён в `docs/history/`, читаемая подпись вместо сырого ключа в `i18n/Russian`.

### Важно при обновлении
- Если в рабочем `config.yml` уже есть ключ `punishments.excluded-checks`, значение по умолчанию не применится: добавьте
  `clickml` и `aimml` вручную.
