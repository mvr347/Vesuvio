package net.lovelace.vesuvio.command;

import net.lovelace.vesuvio.i18n.Russian;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lovelace.vesuvio.check.onnx.MLManager;
import net.lovelace.vesuvio.check.selflearning.ActiveLearning;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.ClickSignature;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.staff.SmartAlertService;
import net.lovelace.vesuvio.staff.SpectateManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.*;

/**
 * Administrative command executor for /vesuvio.
 *
 * Author: Lovelace
 */
public final class VesuvioCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final ConfigManager config;
    private final UserDataManager userDataManager;
    private final net.lovelace.vesuvio.storage.DatabaseManager databaseManager;
    private final MLManager mlManager;
    private final SelfLearningManager selfLearning;
    private final SmartAlertService alertService;
    private final SpectateManager spectateManager;
    private final net.lovelace.vesuvio.punishment.PunishmentWaveManager waveManager;
    private final net.lovelace.vesuvio.config.PresetManager presetManager;
    private final net.lovelace.vesuvio.staff.DebugOverlayManager debugOverlayManager;
    private final net.lovelace.vesuvio.check.onnx.ModelAutoTrainer modelAutoTrainer;
    private final net.lovelace.vesuvio.diagnostics.DiagnosticService diagnostics;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public VesuvioCommand(Plugin plugin,
                          ConfigManager config,
                          UserDataManager userDataManager,
                          net.lovelace.vesuvio.storage.DatabaseManager databaseManager,
                          MLManager mlManager,
                          SelfLearningManager selfLearning,
                          SmartAlertService alertService,
                          SpectateManager spectateManager,
                          net.lovelace.vesuvio.punishment.PunishmentWaveManager waveManager,
                          net.lovelace.vesuvio.config.PresetManager presetManager,
                          net.lovelace.vesuvio.staff.DebugOverlayManager debugOverlayManager,
                          net.lovelace.vesuvio.check.onnx.ModelAutoTrainer modelAutoTrainer,
                          net.lovelace.vesuvio.diagnostics.DiagnosticService diagnostics) {
        this.diagnostics = diagnostics;
        this.plugin = plugin;
        this.config = config;
        this.userDataManager = userDataManager;
        this.databaseManager = databaseManager;
        this.mlManager = mlManager;
        this.selfLearning = selfLearning;
        this.alertService = alertService;
        this.spectateManager = spectateManager;
        this.waveManager = waveManager;
        this.presetManager = presetManager;
        this.modelAutoTrainer = modelAutoTrainer;
        this.debugOverlayManager = debugOverlayManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                sender.sendMessage(mm.deserialize("<red>У вас нет права выполнять эту команду.</red>"));
                return true;
            }
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "log" -> {
                if (!sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(Component.text("Нужно право vesuvio.admin."));
                    return true;
                }
                String action = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
                if (args.length > 2 || !List.of("on", "off", "status").contains(action)) {
                    sender.sendMessage(Component.text("Использование: /vesuvio log [on|off|status]"));
                    return true;
                }
                if (action.equals("on")) {
                    try {
                        Path path = diagnostics.start();
                        sender.sendMessage(Component.text("[Vesuvio] Запись срабатываний включена для всех игроков. Файл: " + path
                                + "\nДля теста без наказаний: /vesuvio punishment off. Завершить запись: /vesuvio log off."));
                    } catch (java.io.IOException failure) {
                        sender.sendMessage(Component.text("[Vesuvio] Не удалось включить журнал: " + failure.getMessage()));
                    }
                } else if (action.equals("off")) {
                    diagnostics.stop();
                    sender.sendMessage(Component.text("[Vesuvio] Приём новых записей остановлен. Оставшиеся записи сохраняются; проверьте /vesuvio log status перед отправкой файла."));
                }
                var status = diagnostics.status();
                sender.sendMessage(Component.text("[Vesuvio] Журнал: " + (status.enabled() ? "ВКЛ" : status.finished() ? "сохранён / выключен" : "сохраняется")
                        + " | записей: " + status.written() + " | пропущено: " + status.dropped()
                        + "\nФайл: " + (status.path() == null ? "ещё не создан" : status.path())
                        + (status.error().isEmpty() ? "" : "\n" + status.error())));
            }

            case "alerts" -> {
                if (!sender.hasPermission("vesuvio.alerts") && !sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>Недостаточно прав (vesuvio.alerts).</red>"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Эта команда доступна только игрокам.");
                    return true;
                }
                boolean enabled = alertService.toggleAlerts(player.getUniqueId());
                player.sendMessage(mm.deserialize(String.format(
                        "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <gray>Оповещения %s</gray>",
                        enabled ? "<green><b>ВКЛЮЧЕНЫ</b></green>" : "<red><b>ОТКЛЮЧЕНЫ</b></red>")));
            }

            case "toggle", "punishment", "status" -> {
                if (!sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>Нужно право vesuvio.admin.</red>"));
                    return true;
                }
                if ("status".equals(sub) || ("punishment".equals(sub) && args.length == 1)) {
                    sendStatus(sender);
                    return true;
                }
                boolean punishment = "punishment".equals(sub);
                String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
                if (!List.of("toggle", "on", "off", "status").contains(action) || args.length > 2) {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio " + sub + " [toggle|on|off|status]</red>"));
                    return true;
                }
                if ("status".equals(action)) { sendStatus(sender); return true; }
                boolean old = punishment ? config.isPunishmentEnabled() : config.isAnticheatEnabled();
                boolean enabled = "toggle".equals(action) ? !old : "on".equals(action);
                if (punishment) config.setPunishmentEnabled(enabled);
                else config.setAnticheatEnabled(enabled);
                if (enabled != old) {
                    // Never execute bans accumulated before an operator paused enforcement.
                    waveManager.clearQueue();
                    if (!punishment) {
                        for (UserData data : userDataManager.getAllUsers()) {
                            data.recordTeleport();
                            data.clientWorld().reset(enabled ? "WAIT_CHUNKS" : "OFF");
                        }
                    }
                }
                String message = "[Vesuvio] " + (punishment ? "Наказания" : "Античит")
                        + (enabled ? " включены" : " выключены") + ": " + sender.getName();
                var component = net.kyori.adventure.text.Component.text(message);
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("vesuvio.alerts") || staff.hasPermission("vesuvio.admin") || staff.isOp()) staff.sendMessage(component);
                }
                if (!(sender instanceof Player)) sender.sendMessage(component);
                plugin.getLogger().warning(message);
                sendStatus(sender);
            }

            case "spectate" -> {
                if (!sender.hasPermission("vesuvio.spectate") && !sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>Недостаточно прав (vesuvio.spectate).</red>"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Эта команда доступна только игрокам.");
                    return true;
                }
                if (spectateManager.isSpectating(player.getUniqueId())) {
                    spectateManager.stopSpectating(player);
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(mm.deserialize("<red>Использование: /vesuvio spectate <игрок></red>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(mm.deserialize("<red>Игрок не найден.</red>"));
                    return true;
                }
                spectateManager.startSpectating(player, target);
            }

            case "review" -> {
                if (!sender.hasPermission("vesuvio.review") && !sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>Недостаточно прав (vesuvio.review).</red>"));
                    return true;
                }
                // /vesuvio review <sampleId> <legit|cheat>
                if (args.length < 3) {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio review <образец> <legit|cheat></red>"));
                    return true;
                }
                String sampleId = args[1];
                String verdict = args[2].toLowerCase();

                ActiveLearning.ReviewSample sample = selfLearning.getActiveLearning().getSample(sampleId);
                UserData targetData = (sample != null) ? userDataManager.get(sample.playerUuid()) : null;

                boolean success = selfLearning.getActiveLearning().submitVerdict(
                        sampleId, verdict, sender.getName(),
                        selfLearning.getOnlineClassifier(),
                        selfLearning.getDatasetManager(),
                        targetData
                );

                if (success) {
                    sender.sendMessage(mm.deserialize(String.format(
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Вердикт <gold>%s</gold> записан для образца <yellow>%s</yellow>. Веса модели обновлены.</green>",
                            Russian.label(verdict), sampleId)));
                } else {
                    sender.sendMessage(mm.deserialize("<red>Образец не найден или устарел.</red>"));
                }
            }

            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio info <игрок></red>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(mm.deserialize("<red>Игрок не найден.</red>"));
                    return true;
                }
                UserData data = userDataManager.getOrCreate(target);
                String sigHex = ClickSignature.toHexString(data.getClickBuffer().getSignature());

                sender.sendMessage(mm.deserialize(String.format(Locale.US,
                        "<gradient:#ff4500:#ff8c00><b>--- Vesuvio: показатели %s ---</b></gradient><newline>"
                        + "<gray>Нарушения:</gray> <yellow>%.1f</yellow> <gray>| Риск:</gray> <red>%.1f</red> <gray>| Доверие:</gray> <green>%.1f</green><newline>"
                        + "<gray>Клиент:</gray> <aqua>%s</aqua> <gray>| Чувствительность:</gray> <yellow>%.2fx</yellow><newline>"
                        + "<gray>Кликов/с:</gray> <white>%.1f</white> <gray>| ONNX:</gray> <yellow>%.1f%%</yellow> <gray>| Самообучение:</gray> <yellow>%.1f%%</yellow> <gray>(%d образцов)</gray><newline>"
                        + "<gray>Профиль кликов:</gray> <dark_gray>%s</dark_gray>",
                        target.getName(), data.getVl(), data.getRiskIndex(), data.getTrustScore(),
                        data.getClientBrand(), data.getSensitivityMultiplier(),
                        data.getLastCalculatedCPS(), data.getLastMLProbability() * 100,
                        data.getLastSelfLearnProbability() * 100, selfLearning.getOnlineClassifier().getTrainedSamplesCount(),
                        sigHex.substring(0, Math.min(32, sigHex.length())) + "...")));
            }

            case "physics" -> {
                if (!sender.hasPermission("vesuvio.admin")) {
                    sender.sendMessage(Component.text("Требуется vesuvio.admin."));
                    return true;
                }
                String action = args.length == 3 ? args[2].toLowerCase(Locale.ROOT) : "status";
                if (args.length < 2 || args.length > 3 || !List.of("on", "off", "status").contains(action)) {
                    sender.sendMessage(Component.text("/vesuvio physics <игрок> [on|off|status]"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("Игрок не найден."));
                    return true;
                }
                UserData data = userDataManager.getOrCreate(target);
                if ("on".equals(action)) {
                    long observed = userDataManager.getAllUsers().stream().filter(UserData::isPhysicsObserved).count();
                    if (!data.isPhysicsObserved() && observed >= 4) {
                        sender.sendMessage(Component.text("Лимит диагностики: 4 игрока. Сначала выключите её для другого игрока."));
                        return true;
                    }
                    data.setPhysicsObserved(true);
                } else if ("off".equals(action)) {
                    data.setPhysicsObserved(false);
                }
                var report = data.physicsSession().report();
                var world = data.clientWorld().stats();
                String residual = Double.isFinite(report.residual()) ? String.format(Locale.US, "%.5f", report.residual()) : "—";
                sender.sendMessage(Component.text(String.format(Locale.US,
                        "[Vesuvio] Физика %s: %s | проверка пакетов: %s\n"
                        + "Только диагностика, без начисления нарушений и наказаний. Состояние: %s\n"
                        + "Сравнений: %d | расхождений: %d | пропусков: %d\n"
                        + "Ошибка: %s блока | максимум: %.5f | кандидатов: %d\n%s",
                        target.getName(), data.isPhysicsObserved() ? "ВКЛ" : "ВЫКЛ", config.isAnticheatEnabled() ? "ВКЛ" : "ВЫКЛ",
                        Russian.label(report.status()), report.compared(), report.mismatched(), report.skipped(), residual,
                        report.maximumResidual(), report.candidates(), report.input())));
                sender.sendMessage(Component.text(String.format(Locale.US,
                        "Копия мира: %s | чанков: %d | изменений в очереди: %d | запросов подтверждения: %d | подтверждений: %d | данные: %.2f МиБ | действий клиента в ожидании: %d",
                        Russian.label(world.state()), world.chunks(), world.pendingChanges(), world.pendingFences(), world.acknowledgedBatches(),
                        world.payloadBytes() / (1024.0 * 1024.0), world.clientPredictions())));
                if ("on".equals(action)) sender.sendMessage(Component.text(
                        "Копия заполняется новыми пакетами чанков. Для первого прогона перейдите в новую область или выполните RTP после включения диагностики."));
            }

            case "debug" -> {
                if (!sender.hasPermission("vesuvio.debug") && !sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>Недостаточно прав (vesuvio.debug).</red>"));
                    return true;
                }
                if (!(sender instanceof Player staff)) {
                    sender.sendMessage("Эта команда доступна только игрокам.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio debug <игрок></red>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(mm.deserialize("<red>Игрок не найден.</red>"));
                    return true;
                }
                boolean enabled = debugOverlayManager.toggle(staff, target);
                staff.sendMessage(mm.deserialize(String.format(
                        "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <gray>Показатели отладки игрока <gold>%s</gold>: %s</gray>",
                        target.getName(), enabled ? "<green><b>ВКЛЮЧЕНЫ</b></green>" : "<red><b>ОТКЛЮЧЕНЫ</b></red>")));
            }

            case "reset" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio reset <игрок></red>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(mm.deserialize("<red>Игрок не найден.</red>"));
                    return true;
                }
                UserData data = userDataManager.get(target.getUniqueId());
                if (data != null) {
                    data.resetVl();
                    data.adjustRisk(-30.0);
                    data.setManualSuspect(false);
                }
                databaseManager.setManualSuspect(target.getUniqueId(), false);
                sender.sendMessage(mm.deserialize("<green>Сброшены нарушения и снижен риск для " + target.getName() + ".</green>"));
            }

            case "suspect" -> {
                if (!sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>У вас нет прав (vesuvio.admin).</red>"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio suspect <игрок> [add|remove|check]</red>"));
                    return true;
                }
                String targetName = args[1];
                Player target = Bukkit.getPlayer(targetName);
                UUID targetUuid = (target != null) ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();
                UserData data = (target != null) ? userDataManager.get(targetUuid) : null;

                String action = (args.length >= 3) ? args[2].toLowerCase() : "check";

                switch (action) {
                    case "add" -> {
                        if (data != null) {
                            data.setManualSuspect(true);
                            data.setRiskIndex(Math.max(data.getRiskIndex(), 85.0));
                            data.adjustTrust(-30.0);
                        }
                        databaseManager.setManualSuspect(targetUuid, true);
                        sender.sendMessage(mm.deserialize(String.format(
                                "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <red>Игрок <gold>%s</gold> вручную помечен как <b>ПОДОЗРЕВАЕМЫЙ</b>! Статус сохранен в базу данных и сохранится при перезаходах.</red>",
                                targetName)));
                    }
                    case "remove", "clear" -> {
                        if (data != null) {
                            data.setManualSuspect(false);
                            data.setRiskIndex(10.0);
                            data.resetVl();
                        }
                        databaseManager.setManualSuspect(targetUuid, false);
                        sender.sendMessage(mm.deserialize(String.format(
                                "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Подозрение с игрока <gold>%s</gold> снято в базе данных и памяти.</green>",
                                targetName)));
                    }
                    default -> {
                        boolean isSusp = (data != null) && data.isManualSuspect();
                        if (!isSusp) {
                            var prof = databaseManager.loadPlayer(targetUuid);
                            if (prof != null) isSusp = prof.isSuspect();
                        }
                        sender.sendMessage(mm.deserialize(String.format(
                                "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <gray>Игрок <gold>%s</gold>: статус подозрения = %s</gray>",
                                targetName, isSusp ? "<red><b>ДА (SUSPECT)</b></red>" : "<green>НЕТ (CLEAN)</green>")));
                    }
                }
            }

            case "reload" -> {
                // Deliberately does NOT touch the /vesuvio toggle kill-switch (see
                // ConfigManager#isAnticheatEnabled): reload re-reads config.yml, an unrelated
                // concern from a live operational pause a staff member chose on purpose. An admin
                // who disabled detection to chase a false positive should not have a routine
                // "tweak a threshold and reload" action silently switch it back on mid-investigation.
                config.reload();
                Path modelsDir = plugin.getDataFolder().toPath().resolve("models");
                mlManager.hotReload("click_model", modelsDir.resolve("click_model.onnx"));
                mlManager.hotReload("aim_model", modelsDir.resolve("aim_model.onnx"));
                sender.sendMessage(mm.deserialize("<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Конфигурация перезагружена, фоновая перезагрузка моделей ONNX запущена. Настройки потоков применятся после перезапуска сервера.</green>"));
            }

            case "retrain" -> {
                if (!sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>Недостаточно прав (vesuvio.admin).</red>"));
                    return true;
                }
                modelAutoTrainer.triggerNow();
                sender.sendMessage(mm.deserialize(
                        "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Запущено обучение ONNX — следите за ходом в консоли "
                        + "(<yellow>Vesuvio-AutoTrain</yellow>). После успешного обучения модели перезагрузятся автоматически.</green>"));
            }

            case "model" -> {
                if (!sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>Недостаточно прав (vesuvio.admin).</red>"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio model [shadow|promote <click|aim>|discard <click|aim>]</red>"));
                    return true;
                }
                String action = args[1].toLowerCase();

                if ("shadow".equals(action)) {
                    var evaluator = modelAutoTrainer.getShadowEvaluator();
                    sender.sendMessage(mm.deserialize(
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <gold>Кандидаты в теневом режиме</gold>"));
                    boolean any = false;
                    for (String domain : net.lovelace.vesuvio.check.onnx.ModelAutoTrainer.domains()) {
                        String modelName = domain + "_model";
                        if (evaluator.get(modelName) == null) continue;
                        any = true;
                        sender.sendMessage(mm.deserialize("<gray>" + evaluator.describe(modelName) + "</gray>"));
                    }
                    if (!any) {
                        sender.sendMessage(mm.deserialize(
                                "<gray>Кандидатов в теневом режиме пока нет. Включите "
                                + "<yellow>layers.onnx.auto-retrain.shadow-mode</yellow> и дождитесь следующего обучения.</gray>"));
                    } else {
                        // The ratio is what an operator actually decides on: it translates straight
                        // into how much more (or less) work the candidate would create for staff.
                        sender.sendMessage(mm.deserialize(
                                "<gray>Отношение около <yellow>1.00x</yellow> означает, что кандидат срабатывает примерно так же часто, как "
                                + "действующая модель. Для публикации: <yellow>/vesuvio model promote <click|aim></yellow>.</gray>"));
                    }
                } else if ("promote".equals(action) || "discard".equals(action)) {
                    if (args.length < 3) {
                        sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio model " + action + " <click|aim></red>"));
                        return true;
                    }
                    String domain = args[2].toLowerCase();
                    String result = "promote".equals(action)
                            ? modelAutoTrainer.promoteCandidate(domain)
                            : modelAutoTrainer.discardCandidate(domain);
                    sender.sendMessage(mm.deserialize("<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>"
                            + result.replace("<", "&lt;") + "</green>"));
                } else {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio model [shadow|promote <click|aim>|discard <click|aim>]</red>"));
                }
            }

            case "dataset" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio dataset [export|import|stats]</red>"));
                    return true;
                }
                String action = args[1].toLowerCase();
                Path dest = plugin.getDataFolder().toPath().resolve("datasets/dataset.csv");

                if ("export".equals(action)) {
                    boolean ok = selfLearning.getDatasetManager().exportToCsv(dest);
                    if (ok) {
                        sender.sendMessage(mm.deserialize("<green>Экспортировано " + selfLearning.getDatasetManager().getDatasetSize() + " образцов в " + dest + "</green>"));
                    } else {
                        sender.sendMessage(mm.deserialize("<red>Не удалось экспортировать. Подробности в журнале.</red>"));
                    }
                } else if ("import".equals(action)) {
                    int count = selfLearning.getDatasetManager().importFromCsv(dest);
                    sender.sendMessage(mm.deserialize("<green>Импортировано " + count + " образцов в набор данных.</green>"));
                } else {
                    sender.sendMessage(mm.deserialize(String.format(
                            "<gray>Набор данных: <yellow>%d</yellow> образцов | Образцов обучения классификатора: <yellow>%d</yellow></gray>",
                            selfLearning.getDatasetManager().getDatasetSize(),
                            selfLearning.getOnlineClassifier().getTrainedSamplesCount())));
                }
            }

            case "wave" -> {
                if (!sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>Нужно право vesuvio.admin.</red>"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio wave [trigger|list|clear]</red>"));
                    return true;
                }
                String action = args[1].toLowerCase();
                if ("trigger".equals(action)) {
                    if (!config.mayPunish()) { sendStatus(sender); return true; }
                    int count = waveManager.executeWave();
                    sender.sendMessage(mm.deserialize(String.format(
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Волна наказаний запланирована: <red><b>%d</b></red> игроков в очереди.</green>", count)));
                } else if ("list".equals(action)) {
                    var queued = waveManager.getQueued();
                    sender.sendMessage(mm.deserialize(String.format(
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <gray>Очередь волны наказаний: <yellow>%d</yellow> игроков:</gray>", queued.size())));
                    for (var p : queued) {
                        sender.sendMessage(mm.deserialize(String.format(
                                "<gray> - <white>%s</white> <dark_gray>(%s)</dark_gray> » <yellow>%s</yellow></gray>",
                                p.username(), p.uuid(), p.reason())));
                    }
                } else if ("clear".equals(action)) {
                    waveManager.clearQueue();
                    sender.sendMessage(mm.deserialize("<green>Очередь волны наказаний очищена.</green>"));
                } else {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio wave [trigger|list|clear]</red>"));
                }
            }

            case "learn" -> {
                if (!sender.hasPermission("vesuvio.review") && !sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>У вас нет прав (vesuvio.review).</red>"));
                    return true;
                }
                // /vesuvio learn <player> <legit|cheat>
                if (args.length < 3) {
                    sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio learn <игрок> <legit|cheat></red>"));
                    return true;
                }
                String targetName = args[1];
                String verdict = args[2].toLowerCase();
                Player target = Bukkit.getPlayer(targetName);
                UserData data = (target != null) ? userDataManager.getOrCreate(target) : null;

                if (data == null) {
                    sender.sendMessage(mm.deserialize("<red>Игрок " + targetName + " не найден в сети.</red>"));
                    return true;
                }

                int targetLabel = ("cheat".equals(verdict) || "чит".equals(verdict)) ? 1 : 0;
                float[] features = net.lovelace.vesuvio.feature.ClickFeatureExtractor.extract(data.getClickBuffer());

                // 1. Train online classifier via SGD
                selfLearning.getOnlineClassifier().train(features, targetLabel);

                // 2. Persist to training dataset
                selfLearning.getDatasetManager().addSample(new net.lovelace.vesuvio.check.selflearning.DatasetManager.LabeledSample(
                        data.getUuid(),
                        data.getUsername(),
                        features,
                        targetLabel,
                        System.currentTimeMillis(),
                        sender.getName()
                ));

                // 3. Adjust player state
                if (targetLabel == 0) {
                    data.adjustTrust(20.0);
                    data.adjustRisk(-25.0);
                    data.resetGcdStreak();
                    data.resetVl();
                    data.setManualSuspect(false);
                    databaseManager.setManualSuspect(data.getUuid(), false);
                    sender.sendMessage(mm.deserialize(String.format(
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio: обучение]</b></gradient> <green>Образец игрока <gold>%s</gold> помечен как <white><b>ЧЕСТНАЯ ИГРА</b></white>. Веса модели адаптированы (SGD), подозрительность снята.</green>",
                            data.getUsername())));
                } else {
                    data.adjustRisk(30.0);
                    data.adjustTrust(-30.0);
                    data.addVl(20.0);
                    data.setManualSuspect(true);
                    databaseManager.savePlayerSync(
                            data.getUuid(),
                            data.getUsername(),
                            data.getTrustScore(),
                            data.getRiskIndex(),
                            data.getClientBrand(),
                            true
                    );
                    sender.sendMessage(mm.deserialize(String.format(
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio: обучение]</b></gradient> <red>Образец игрока <gold>%s</gold> помечен как <white><b>ЧИТ</b></white>. Модель обучена, Индекс риска повышен до %.0f, статус сохранен в БД.</red>",
                            data.getUsername(), data.getRiskIndex())));
                }
            }

            case "preset" -> {
                if (!sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>У вас нет прав (vesuvio.admin).</red>"));
                    return true;
                }
                // /vesuvio preset <list|apply|save>
                if (args.length < 2 || "list".equalsIgnoreCase(args[1])) {
                    List<String> available = presetManager.getAvailablePresets();
                    sender.sendMessage(mm.deserialize("<gradient:#ff4500:#ff8c00><b>--- Доступные пресеты Vesuvio ---</b></gradient>"));
                    for (String p : available) {
                        String desc = presetManager.getPresetDescription(p);
                        sender.sendMessage(mm.deserialize(String.format(
                                "<gold>• <b>%s</b></gold> <dark_gray>-</dark_gray> <gray>%s</gray> "
                                + "<green>[<click:run_command:'/vesuvio preset %s'><hover:show_text:'<green>Нажмите, чтобы применить %s</green>'>Применить</click>]</green>",
                                p, desc, p, p)));
                    }
                    sender.sendMessage(mm.deserialize("<gray>Применить: <gold>/vesuvio preset <название></gold> | Сохранить текущий: <gold>/vesuvio preset save <название></gold></gray>"));
                    return true;
                }

                if ("save".equalsIgnoreCase(args[1])) {
                    if (args.length < 3) {
                        sender.sendMessage(mm.deserialize("<red>Использование: /vesuvio preset save <название></red>"));
                        return true;
                    }
                    String saveName = args[2].toLowerCase();
                    boolean saved = presetManager.savePreset(saveName);
                    if (saved) {
                        sender.sendMessage(mm.deserialize(String.format(
                                "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Текущая конфигурация успешно сохранена как пресет <gold><b>%s</b></gold> в папке <yellow>presets/%s.yml</yellow>!</green>",
                                saveName, saveName)));
                    } else {
                        sender.sendMessage(mm.deserialize("<red>Ошибка при сохранении пресета.</red>"));
                    }
                    return true;
                }

                String presetName = args[1].toLowerCase();
                boolean applied = presetManager.applyPreset(presetName);
                if (applied) {
                    String desc = presetManager.getPresetDescription(presetName);
                    sender.sendMessage(mm.deserialize(String.format(
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Пресет <gold><b>%s</b></gold> успешно активирован и применен в config.yml!</green><newline><gray>Описание: %s</gray>",
                            presetName, desc)));
                } else {
                    sender.sendMessage(mm.deserialize(String.format(
                            "<red>Пресет '%s' не найден. Используйте <gold>/vesuvio preset list</gold> для списка доступных.</red>",
                            presetName)));
                }
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(net.kyori.adventure.text.Component.text(
                "[Vesuvio] Проверки: " + (config.isAnticheatEnabled() ? "ВКЛ" : "ВЫКЛ")
                + " | Наказания: " + (config.isPunishmentEnabled() ? "ВКЛ" : "ВЫКЛ")
                + " | Применение наказаний: " + (config.mayPunish() ? "ВКЛ" : "ВЫКЛ")
                + " | Очередь банов: " + waveManager.getQueueSize()));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(mm.deserialize(
                "<gradient:#ff4500:#ff8c00><b>================ VESUVIO 26.2 ================</b></gradient><newline>"
                + "<gold>/vesuvio toggle</gold> <gray>- <red><b>Полностью</b></red> включить/выключить детект (после перезапуска снова включён)</gray><newline>"
                + "<gold>/vesuvio punishment [toggle|on|off]</gold> <gray>- Наказания при работающих проверках</gray><newline>"
                + "<gold>/vesuvio status</gold> <gray>- Статус проверок и наказаний</gray><newline>"
                + "<gold>/vesuvio physics игрок [on|off|status]</gold> <gray>- Диагностика новой физики без наказаний</gray><newline>"
                + "<gold>/vesuvio log [on|off|status]</gold> <gray>- Подробный журнал срабатываний в файл</gray><newline>"
                + "<gold>/vesuvio alerts</gold> <gray>- Переключить умные оповещения в чате</gray><newline>"
                + "<gold>/vesuvio spectate <игрок></gold> <gray>- Наблюдение в реальном времени с оверлеем</gray><newline>"
                + "<gold>/vesuvio debug <игрок></gold> <gray>- Текущие показатели проверок над панелью предметов</gray><newline>"
                + "<gold>/vesuvio learn <игрок> <legit|cheat></gold> <gray>- Обучить модель на текущем поведении</gray><newline>"
                + "<gold>/vesuvio suspect <игрок> [add|remove|check]</gold> <gray>- Установка/снятие подозрения с сохранением в БД</gray><newline>"
                + "<gold>/vesuvio preset [list|название|save]</gold> <gray>- Управление профилями (balanced, strict, lenient, anarchy)</gray><newline>"
                + "<gold>/vesuvio review <id> <legit|cheat></gold> <gray>- Вердикт для проверки обучающих образцов</gray><newline>"
                + "<gold>/vesuvio info <игрок></gold> <gray>- Биометрический профиль угрозы</gray><newline>"
                + "<gold>/vesuvio reset <игрок></gold> <gray>- Сбросить уровень нарушений (нарушения/риск)</gray><newline>"
                + "<gold>/vesuvio wave [trigger|list|clear]</gold> <gray>- Управление волной банов волны наказаний</gray><newline>"
                + "<gold>/vesuvio reload</gold> <gray>- Перезагрузить конфиг и модели ONNX</gray><newline>"
                + "<gold>/vesuvio retrain</gold> <gray>- Запустить переобучение ONNX-моделей на текущем датасете вручную</gray><newline>"
                + "<gold>/vesuvio dataset [export|import|stats]</gold> <gray>- Управление датасетом обучения</gray><newline>"
                + "<gold>/vesuvio model [shadow|promote|discard]</gold> <gray>- Кандидат в теневом режиме: сравнение с боевой моделью и публикация</gray><newline>"
                + "<gradient:#ff4500:#ff8c00><b>==============================================</b></gradient>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("toggle", "punishment", "status", "physics", "log", "alerts", "spectate", "debug", "review", "learn", "suspect", "preset", "info", "reset", "wave", "reload", "retrain", "dataset", "model"), args[0]);
        }
        if (args.length == 2) {
            if ("log".equalsIgnoreCase(args[0]) && sender.hasPermission("vesuvio.admin"))
                return filter(List.of("on", "off", "status"), args[1]);
            if ("physics".equalsIgnoreCase(args[0]) && sender.hasPermission("vesuvio.admin")) {
                return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            }
            if ("punishment".equalsIgnoreCase(args[0]) || "toggle".equalsIgnoreCase(args[0])) {
                return filter(List.of("toggle", "on", "off", "status"), args[1]);
            }
            if ("spectate".equalsIgnoreCase(args[0]) || "debug".equalsIgnoreCase(args[0]) || "info".equalsIgnoreCase(args[0]) || "reset".equalsIgnoreCase(args[0]) || "learn".equalsIgnoreCase(args[0]) || "suspect".equalsIgnoreCase(args[0])) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                return filter(names, args[1]);
            }
            if ("preset".equalsIgnoreCase(args[0])) {
                List<String> presets = new ArrayList<>(presetManager.getAvailablePresets());
                presets.add("list");
                presets.add("save");
                return filter(presets, args[1]);
            }
            if ("model".equalsIgnoreCase(args[0])) {
                return filter(List.of("shadow", "promote", "discard"), args[1]);
            }
            if ("dataset".equalsIgnoreCase(args[0])) {
                return filter(List.of("export", "import", "stats"), args[1]);
            }
            if ("wave".equalsIgnoreCase(args[0])) {
                return filter(List.of("trigger", "list", "clear"), args[1]);
            }
        }
        if (args.length == 3) {
            if ("physics".equalsIgnoreCase(args[0]) && sender.hasPermission("vesuvio.admin")) {
                return filter(List.of("on", "off", "status"), args[2]);
            }
            if ("review".equalsIgnoreCase(args[0]) || "learn".equalsIgnoreCase(args[0])) {
                return filter(List.of("legit", "cheat"), args[2]);
            }
            if ("suspect".equalsIgnoreCase(args[0])) {
                return filter(List.of("add", "remove", "check"), args[2]);
            }
            if ("model".equalsIgnoreCase(args[0])
                    && ("promote".equalsIgnoreCase(args[1]) || "discard".equalsIgnoreCase(args[1]))) {
                return filter(List.of(net.lovelace.vesuvio.check.onnx.ModelAutoTrainer.domains()), args[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String query) {
        String q = query.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(q)) matches.add(s);
        }
        return matches;
    }
}
