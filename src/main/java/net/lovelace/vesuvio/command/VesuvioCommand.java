package net.lovelace.vesuvio.command;

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
                          net.lovelace.vesuvio.staff.DebugOverlayManager debugOverlayManager) {
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
        this.debugOverlayManager = debugOverlayManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                sender.sendMessage(mm.deserialize("<red>You do not have permission to execute this command.</red>"));
                return true;
            }
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "alerts" -> {
                if (!sender.hasPermission("vesuvio.alerts") && !sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>You do not have permission (vesuvio.alerts).</red>"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("This command can only be run by a player.");
                    return true;
                }
                boolean enabled = alertService.toggleAlerts(player.getUniqueId());
                player.sendMessage(mm.deserialize(String.format(
                        "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <gray>Alerts are now %s</gray>",
                        enabled ? "<green><b>ENABLED</b></green>" : "<red><b>DISABLED</b></red>")));
            }

            case "spectate" -> {
                if (!sender.hasPermission("vesuvio.spectate") && !sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>You do not have permission (vesuvio.spectate).</red>"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("This command can only be run by a player.");
                    return true;
                }
                if (spectateManager.isSpectating(player.getUniqueId())) {
                    spectateManager.stopSpectating(player);
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(mm.deserialize("<red>Usage: /vesuvio spectate <player></red>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(mm.deserialize("<red>Player not found.</red>"));
                    return true;
                }
                spectateManager.startSpectating(player, target);
            }

            case "review" -> {
                if (!sender.hasPermission("vesuvio.review") && !sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>You do not have permission (vesuvio.review).</red>"));
                    return true;
                }
                // /vesuvio review <sampleId> <legit|cheat>
                if (args.length < 3) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /vesuvio review <sampleId> <legit|cheat></red>"));
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
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Verdict <gold>%s</gold> recorded for sample <yellow>%s</yellow>. Model weights updated.</green>",
                            verdict.toUpperCase(), sampleId)));
                } else {
                    sender.sendMessage(mm.deserialize("<red>Sample not found or has expired.</red>"));
                }
            }

            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /vesuvio info <player></red>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(mm.deserialize("<red>Player not found.</red>"));
                    return true;
                }
                UserData data = userDataManager.getOrCreate(target);
                String sigHex = ClickSignature.toHexString(data.getClickBuffer().getSignature());

                sender.sendMessage(mm.deserialize(String.format(Locale.US,
                        "<gradient:#ff4500:#ff8c00><b>--- Vesuvio Biometrics: %s ---</b></gradient><newline>"
                        + "<gray>VL:</gray> <yellow>%.1f</yellow> <gray>| Risk:</gray> <red>%.1f</red> <gray>| Trust:</gray> <green>%.1f</green><newline>"
                        + "<gray>Brand:</gray> <aqua>%s</aqua> <gray>| Sensitivity:</gray> <yellow>%.2fx</yellow><newline>"
                        + "<gray>Last CPS:</gray> <white>%.1f</white> <gray>| ML Prob:</gray> <yellow>%.1f%%</yellow><newline>"
                        + "<gray>Click Signature:</gray> <dark_gray>%s</dark_gray>",
                        target.getName(), data.getVl(), data.getRiskIndex(), data.getTrustScore(),
                        data.getClientBrand(), data.getSensitivityMultiplier(),
                        data.getLastCalculatedCPS(), data.getLastMLProbability() * 100,
                        sigHex.substring(0, Math.min(32, sigHex.length())) + "...")));
            }

            case "debug" -> {
                if (!sender.hasPermission("vesuvio.debug") && !sender.hasPermission("vesuvio.admin") && !sender.isOp()) {
                    sender.sendMessage(mm.deserialize("<red>You do not have permission (vesuvio.debug).</red>"));
                    return true;
                }
                if (!(sender instanceof Player staff)) {
                    sender.sendMessage("This command can only be run by a player.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /vesuvio debug <player></red>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(mm.deserialize("<red>Player not found.</red>"));
                    return true;
                }
                boolean enabled = debugOverlayManager.toggle(staff, target);
                staff.sendMessage(mm.deserialize(String.format(
                        "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <gray>Live debug telemetry for <gold>%s</gold> is now %s</gray>",
                        target.getName(), enabled ? "<green><b>ENABLED</b></green>" : "<red><b>DISABLED</b></red>")));
            }

            case "reset" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /vesuvio reset <player></red>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(mm.deserialize("<red>Player not found.</red>"));
                    return true;
                }
                UserData data = userDataManager.get(target.getUniqueId());
                if (data != null) {
                    data.resetVl();
                    data.adjustRisk(-30.0);
                    data.setManualSuspect(false);
                }
                databaseManager.setManualSuspect(target.getUniqueId(), false);
                sender.sendMessage(mm.deserialize("<green>Reset VL and reduced threat risk for " + target.getName() + ".</green>"));
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
                config.reload();
                Path modelsDir = plugin.getDataFolder().toPath().resolve("models");
                mlManager.hotReload("click_model", modelsDir.resolve("click_model.onnx"));
                mlManager.hotReload("aim_model", modelsDir.resolve("aim_model.onnx"));
                sender.sendMessage(mm.deserialize("<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Configuration reloaded and ONNX model hot-reload dispatched asynchronously.</green>"));
            }

            case "dataset" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /vesuvio dataset [export|import|stats]</red>"));
                    return true;
                }
                String action = args[1].toLowerCase();
                Path dest = plugin.getDataFolder().toPath().resolve("datasets/dataset.csv");

                if ("export".equals(action)) {
                    boolean ok = selfLearning.getDatasetManager().exportToCsv(dest);
                    if (ok) {
                        sender.sendMessage(mm.deserialize("<green>Exported " + selfLearning.getDatasetManager().getDatasetSize() + " samples to " + dest + "</green>"));
                    } else {
                        sender.sendMessage(mm.deserialize("<red>Export failed. Check logs.</red>"));
                    }
                } else if ("import".equals(action)) {
                    int count = selfLearning.getDatasetManager().importFromCsv(dest);
                    sender.sendMessage(mm.deserialize("<green>Imported " + count + " samples into dataset.</green>"));
                } else {
                    sender.sendMessage(mm.deserialize(String.format(
                            "<gray>Dataset size: <yellow>%d</yellow> samples | Online classifier trained samples: <yellow>%d</yellow></gray>",
                            selfLearning.getDatasetManager().getDatasetSize(),
                            selfLearning.getOnlineClassifier().getTrainedSamplesCount())));
                }
            }

            case "wave" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /vesuvio wave [trigger|list|clear]</red>"));
                    return true;
                }
                String action = args[1].toLowerCase();
                if ("trigger".equals(action)) {
                    int count = waveManager.executeWave();
                    sender.sendMessage(mm.deserialize(String.format(
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <green>Lava Wave executed! Banned <red><b>%d</b></red> queued cheaters.</green>", count)));
                } else if ("list".equals(action)) {
                    var queued = waveManager.getQueued();
                    sender.sendMessage(mm.deserialize(String.format(
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio]</b></gradient> <gray>Lava Wave Queue (<yellow>%d</yellow> players pending):</gray>", queued.size())));
                    for (var p : queued) {
                        sender.sendMessage(mm.deserialize(String.format(
                                "<gray> - <white>%s</white> <dark_gray>(%s)</dark_gray> » <yellow>%s</yellow></gray>",
                                p.username(), p.uuid(), p.reason())));
                    }
                } else if ("clear".equals(action)) {
                    waveManager.clearQueue();
                    sender.sendMessage(mm.deserialize("<green>Lava Wave queue has been cleared.</green>"));
                } else {
                    sender.sendMessage(mm.deserialize("<red>Usage: /vesuvio wave [trigger|list|clear]</red>"));
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
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio ML]</b></gradient> <green>Образец игрока <gold>%s</gold> помечен как <white><b>ЧИСТО (LEGIT)</b></white>. Веса модели адаптированы (SGD), подозрительность снята.</green>",
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
                            "<gradient:#ff4500:#ff8c00><b>[Vesuvio ML]</b></gradient> <red>Образец игрока <gold>%s</gold> помечен как <white><b>ЧИТ (CHEAT)</b></white>. Модель обучена, Risk Index повышен до %.0f, статус сохранен в БД.</red>",
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

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(mm.deserialize(
                "<gradient:#ff4500:#ff8c00><b>================ VESUVIO 26.2 ================</b></gradient><newline>"
                + "<gold>/vesuvio alerts</gold> <gray>- Переключить умные оповещения в чате</gray><newline>"
                + "<gold>/vesuvio spectate <игрок></gold> <gray>- Наблюдение в реальном времени с оверлеем</gray><newline>"
                + "<gold>/vesuvio debug <игрок></gold> <gray>- Живая ActionBar телеметрия чеков (CPS/StdDev/AirTicks/VL...)</gray><newline>"
                + "<gold>/vesuvio learn <игрок> <legit|cheat></gold> <gray>- Обучить модель на текущем поведении</gray><newline>"
                + "<gold>/vesuvio suspect <игрок> [add|remove|check]</gold> <gray>- Установка/снятие подозрения с сохранением в БД</gray><newline>"
                + "<gold>/vesuvio preset [list|название|save]</gold> <gray>- Управление профилями (balanced, strict, lenient, anarchy)</gray><newline>"
                + "<gold>/vesuvio review <id> <legit|cheat></gold> <gray>- Вердикт для Active Learning</gray><newline>"
                + "<gold>/vesuvio info <игрок></gold> <gray>- Биометрический профиль угрозы</gray><newline>"
                + "<gold>/vesuvio reset <игрок></gold> <gray>- Сбросить уровень нарушений (VL/Risk)</gray><newline>"
                + "<gold>/vesuvio wave [trigger|list|clear]</gold> <gray>- Управление волной банов Lava Wave</gray><newline>"
                + "<gold>/vesuvio reload</gold> <gray>- Перезагрузить конфиг и модели ONNX</gray><newline>"
                + "<gold>/vesuvio dataset [export|import|stats]</gold> <gray>- Управление датасетом обучения</gray><newline>"
                + "<gradient:#ff4500:#ff8c00><b>==============================================</b></gradient>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("alerts", "spectate", "debug", "review", "learn", "suspect", "preset", "info", "reset", "wave", "reload", "dataset"), args[0]);
        }
        if (args.length == 2) {
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
            if ("dataset".equalsIgnoreCase(args[0])) {
                return filter(List.of("export", "import", "stats"), args[1]);
            }
            if ("wave".equalsIgnoreCase(args[0])) {
                return filter(List.of("trigger", "list", "clear"), args[1]);
            }
        }
        if (args.length == 3) {
            if ("review".equalsIgnoreCase(args[0]) || "learn".equalsIgnoreCase(args[0])) {
                return filter(List.of("legit", "cheat"), args[2]);
            }
            if ("suspect".equalsIgnoreCase(args[0])) {
                return filter(List.of("add", "remove", "check"), args[2]);
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
