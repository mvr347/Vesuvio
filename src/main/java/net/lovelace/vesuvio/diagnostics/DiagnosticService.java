package net.lovelace.vesuvio.diagnostics;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.engine.TransactionManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread history and immutable incident text; file IO belongs exclusively to DiagnosticLog. */
public final class DiagnosticService implements AutoCloseable, org.bukkit.event.Listener {
    private final Plugin plugin;
    private final ConfigManager config;
    private final UserDataManager users;
    private final TransactionManager transactions;
    private final DiagnosticLog log;
    private final Map<UUID, ArrayDeque<String>> history = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<String>> recentEvents = new ConcurrentHashMap<>();
    private volatile String serverState = "ещё нет снимка";

    public DiagnosticService(Plugin plugin, ConfigManager config, UserDataManager users, TransactionManager transactions) {
        this.plugin = plugin; this.config = config; this.users = users; this.transactions = transactions;
        this.log = new DiagnosticLog(plugin.getDataFolder().toPath().resolve("diagnostics"), config.useVirtualThreads());
    }

    /** Called by the administrative command on the server thread. No credentials/config dump. */
    public Path start() throws IOException {
        if (log.enabled()) return log.status().path();
        StringBuilder metadata = new StringBuilder("Формат: Vesuvio diagnostics v1\n");
        field(metadata, "Сервер", Bukkit.getVersion());
        field(metadata, "Java", System.getProperty("java.version"));
        field(metadata, "Vesuvio", plugin.getDescription().getVersion());
        field(metadata, "Плагины", Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(p -> p.getName() + " " + p.getDescription().getVersion()).toList());
        field(metadata, "Виртуальные потоки (конфигурация)", config.useVirtualThreads());
        field(metadata, "Проверки", config.isAnticheatEnabled());
        field(metadata, "Наказания", config.isPunishmentEnabled());
        // Allowlist only check parameters: deliberately exclude storage/web/Discord/commands.
        for (String section : List.of("mechanics", "layers.statistical", "layers.onnx.models", "layers.self-learning")) {
            var values = plugin.getConfig().getConfigurationSection(section);
            if (values != null) for (var entry : values.getValues(true).entrySet()) {
                if (!(entry.getValue() instanceof org.bukkit.configuration.ConfigurationSection))
                    field(metadata, "Настройка " + section + "." + entry.getKey(), entry.getValue());
            }
        }
        Path path = log.start(metadata.toString());
        history.clear(); recentEvents.clear();
        sample();
        return path;
    }

    public DiagnosticLog.Status status() { return log.status(); }
    public void stop() { log.stop(); history.clear(); recentEvents.clear(); }

    /** Four samples/second, twenty retained samples per online player, only while enabled. */
    public void sample() {
        if (!log.enabled()) { history.clear(); recentEvents.clear(); return; }
        serverState = "TPS=" + Arrays.toString(Bukkit.getTPS()) + ", MSPT=" + Bukkit.getAverageTickTime()
                + ", онлайн=" + Bukkit.getOnlinePlayers().size();
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId(); online.add(id);
            UserData data = users.get(id);
            if (data == null) continue;
            var location = player.getLocation();
            var item = player.getInventory().getItemInMainHand();
            String sample = Instant.now() + " мир=" + safe(player.getWorld().getName()) + " сервернаяПозиция="
                    + location.toVector() + " режим=" + player.getGameMode() + " BukkitPing=" + player.getPing()
                    + " предмет=" + item.getType() + " зачарования=" + item.getEnchantments()
                    + " эффекты=" + player.getActivePotionEffects() + " " + motion(data);
            var buffer = history.computeIfAbsent(id, ignored -> new ArrayDeque<>());
            synchronized (buffer) { if (buffer.size() == 20) buffer.removeFirst(); buffer.addLast(sample); }
        }
        history.keySet().retainAll(online);
        recentEvents.keySet().retainAll(online);
    }

    /** May run on a check worker: uses cached values, never reads the live Bukkit world. */
    public void flag(UserData data, CheckResult result, String disposition) {
        if (!log.enabled()) return;
        var recording = log.status();
        if (!recording.enabled()) return;
        try {
            StringBuilder out = new StringBuilder("СРАБАТЫВАНИЕ\n");
            field(out, "Время события", Instant.now());
            field(out, "Игрок", data.getUsername()); field(out, "UUID", data.getUuid());
            field(out, "Клиент", data.getClientBrand()); field(out, "Результат обработки", disposition);
            field(out, "Проверка", result.checkName()); field(out, "Причина", result.explanation());
            field(out, "Уверенность", result.confidence()); field(out, "Добавление нарушений", result.vl());
            field(out, "Подробности проверки", result.details());
            field(out, "Нарушения/риск/доверие", data.getVl() + "/" + data.getRiskIndex() + "/" + data.getTrustScore());
            field(out, "Переключатели", "проверки=" + config.isAnticheatEnabled() + ", наказания=" + config.isPunishmentEnabled());
            field(out, "Сервер (последний снимок)", serverState);
            field(out, "Движение", motion(data));
            field(out, "Окружение", data.getEnvironment());
            field(out, "Пинг транзакций", transactions.getTransactionPing(data.getUuid()));
            field(out, "Подтверждения", "ожидает=" + transactions.getPendingCount(data.getUuid())
                    + ", возрастМс=" + transactions.getOldestPendingAgeMs(data.getUuid()));
            field(out, "Атака", "цель=" + data.getLastAttackTargetId() + ", yaw=" + data.getLastAttackYaw()
                    + ", pitch=" + data.getLastAttackPitch() + ", наносекунды=" + data.getLastAttackNanos());
            field(out, "Клики", "CPS=" + data.getLastCalculatedCPS() + ", std=" + data.getLastStdDevMs()
                    + ", повторы=" + data.getLastDupRatio() + ", энтропия=" + data.getLastEntropy());
            long[] intervals = new long[64]; data.getClickBuffer().copyIntervals(intervals);
            field(out, "Интервалы кликов (наносекунды), заполнено " + data.getClickBuffer().getCount(), Arrays.toString(intervals));
            float[] yaw = new float[64], pitch = new float[64];
            data.getAimBuffer().copyDeltas(yaw, pitch);
            field(out, "Изменения yaw, заполнено " + data.getAimBuffer().getCount(), Arrays.toString(yaw));
            field(out, "Изменения pitch", Arrays.toString(pitch));
            field(out, "Вероятности моделей", "ONNX=" + data.getLastMLProbability() + ", клики=" + data.getLastSelfLearnProbability()
                    + ", прицел=" + data.getLastAimSelfLearnProbability());
            field(out, "Диагностика физики включена", data.isPhysicsObserved());
            field(out, "Физика", data.physicsSession().report());
            field(out, "Копия мира", data.clientWorld().stats());
            var events = recentEvents.get(data.getUuid());
            if (events != null) synchronized (events) {
                out.append("Последние события (до 20, с точным временем):\n");
                for (String event : events) out.append(event).append('\n');
            }
            var buffer = history.get(data.getUuid());
            if (buffer != null) synchronized (buffer) {
                out.append("История: до 20 снимков с интервалом 5 тактов (около 5 секунд при 20 TPS)\n");
                for (String sample : buffer) out.append(sample).append('\n');
            }
            log.record(recording.path(), out.toString());
        } catch (RuntimeException failure) {
            // A diagnostic failure must never suppress a detection or punishment.
            log.record(recording.path(), "ОШИБКА СБОРА КОНТЕКСТА\n" + safe(failure.toString()));
        }
    }

    private void event(Player player, String description) {
        if (!log.enabled()) return;
        var events = recentEvents.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        synchronized (events) {
            if (events.size() == 20) events.removeFirst();
            events.addLast(Instant.now() + " " + safe(description));
        }
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (log.enabled()) event(event.getPlayer(), "Телепортация: причина=" + event.getCause()
                + " отменена=" + event.isCancelled() + " от=" + event.getFrom() + " до=" + event.getTo());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onDamage(org.bukkit.event.block.BlockDamageEvent event) {
        if (log.enabled()) event(event.getPlayer(), "Начало добычи: " + event.getBlock().getType()
                + " позиция=" + event.getBlock().getLocation() + " инструмент=" + event.getItemInHand().getType()
                + " мгновенно=" + event.getInstaBreak());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (log.enabled()) event(event.getPlayer(), "Попытка разрушения: " + event.getBlock().getType()
                + " позиция=" + event.getBlock().getLocation() + " инструмент=" + event.getPlayer().getInventory().getItemInMainHand().getType());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onVelocity(org.bukkit.event.player.PlayerVelocityEvent event) {
        if (log.enabled()) event(event.getPlayer(), "Импульс: " + event.getVelocity() + " отменён=" + event.isCancelled());
    }

    private String motion(UserData data) {
        return "позицияПакета=" + data.getLastX() + "," + data.getLastY() + "," + data.getLastZ()
                + " yaw=" + data.getLastYaw() + " pitch=" + data.getLastPitch()
                + " deltaY=" + data.getLastDeltaY() + " deltaXZ=" + data.getLastDeltaXZ()
                + " наЗемле=" + data.isLastOnGround() + " воздухТакты=" + data.getAirTicks()
                + " телепортНедавно=" + data.hasRecentTeleport() + " сменаРежима=" + data.hasRecentGameModeChange()
                + " импульсНедавно=" + data.hasRecentVelocity() + " импульс=" + data.getPendingVelX()
                + "," + data.getPendingVelY() + "," + data.getPendingVelZ() + " сбросНаносекунды=" + data.getMovementResetNanos();
    }
    private static String safe(Object value) { return String.valueOf(value).replace("\r", "\\r").replace("\n", "\\n"); }
    private static void field(StringBuilder out, String name, Object value) { out.append(name).append(": ").append(safe(value)).append('\n'); }
    @Override public void close() { log.close(); history.clear(); recentEvents.clear(); }
}
