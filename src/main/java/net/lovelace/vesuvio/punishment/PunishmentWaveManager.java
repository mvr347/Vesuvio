package net.lovelace.vesuvio.punishment;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.storage.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Advanced Punishment Wave Manager («Lava Wave»).
 * Queues ban punishments instead of banning instantly, preventing cheat developers
 * from iteratively testing bypass configurations against the server.
 *
 * Author: Lovelace
 */
public final class PunishmentWaveManager implements org.bukkit.event.Listener {
    private WaveStore store;

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Wave");

    public record QueuedPunishment(
            UUID uuid,
            String username,
            String command,
            String reason,
            long timestamp
    ) {}

    private final Plugin plugin;
    private final ConfigManager config;
    private final DatabaseManager databaseManager;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private net.lovelace.vesuvio.integration.hunt.LoveHuntHook loveHuntHook;

    private final Map<UUID, QueuedPunishment> queue = new ConcurrentHashMap<>();

    public PunishmentWaveManager(Plugin plugin, ConfigManager config, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.config = config;
        this.databaseManager = databaseManager;
    }

    public PunishmentWaveManager(Plugin plugin,ConfigManager config,DatabaseManager databaseManager,WaveStore store) {
        this(plugin,config,databaseManager);this.store=Objects.requireNonNull(store);
    }
    public synchronized void start() {
        if(store!=null)return;
        try {
            java.nio.file.Files.createDirectories(plugin.getDataFolder().toPath());
            store=new WaveStore(plugin.getDataFolder().toPath().resolve("moderation-wave.db"));
            for(var e:store.list())if(e.state().equals("QUEUED"))queue.put(UUID.fromString(e.uuid()),new QueuedPunishment(UUID.fromString(e.uuid()),e.name(),e.command(),e.reason(),e.created()));
        } catch(Exception error) {throw new IllegalStateException("Не открыта очередь волн",error);}
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }

    public void setLoveHuntHook(net.lovelace.vesuvio.integration.hunt.LoveHuntHook loveHuntHook) {
        this.loveHuntHook = loveHuntHook;
    }

    public synchronized void queuePunishment(UUID uuid, String username, String command, String reason) {
        if (!config.mayPunish()) return;
        if(queue.containsKey(uuid))return;
        if(store==null)start();
        if(queue.size()>=1000)throw new IllegalStateException("Очередь волн заполнена");
        try {store.add(new WaveStore.Entry(UUID.randomUUID().toString(),uuid.toString(),username,Objects.toString(command,""),reason,System.currentTimeMillis(),-1,"QUEUED"));refresh();}
        catch(java.sql.SQLException error){throw new IllegalStateException("Волна не сохранена",error);}
        LOGGER.info(String.format("[Vesuvio] Игрок %s добавлен в волну наказаний. Игроков в очереди: %d", username, queue.size()));
        if (loveHuntHook != null) {
            loveHuntHook.createLavaBounty(uuid, username, reason);
        }
    }

    public boolean isQueued(UUID uuid) {
        return queue.containsKey(uuid);
    }

    public Collection<QueuedPunishment> getQueued() {
        return List.copyOf(queue.values());
    }

    public int getQueueSize() {
        return queue.size();
    }

    public void clearQueue() {
        try {for(var e:store.list())store.transition(e.event(),"QUEUED","REMOVED");refresh();}
        catch(java.sql.SQLException error){throw new IllegalStateException(error);}
    }

    /**
     * Executes the Lava Wave, banning all queued cheaters simultaneously.
     */
    public int executeWave() {
        if (!config.mayPunish() || queue.isEmpty()) return 0;
        long actionEpoch = config.runtime().actionEpoch();

        final List<WaveStore.Entry> toExecute;
        try {toExecute=store.list().stream().filter(e->e.state().equals("QUEUED")).toList();}
        catch(java.sql.SQLException error){throw new IllegalStateException(error);}
        // The queue stays durable until the action is actually accepted on the server thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!config.runtime().acceptsAction(actionEpoch)) return;
            int executed=0;
            for(var entry:toExecute) {
                if(!config.runtime().acceptsAction(actionEpoch))break;
                try {
                    if(!store.transition(entry.event(),"QUEUED","APPLYING"))continue;
                    // Read current values after the CAS: staff may have edited a queued entry.
                    var current=store.find(entry.event()).orElseThrow();
                    var admin=Bukkit.getPluginManager().getPlugin("LoveWebAdmin");
                    boolean applied;
                    if(admin!=null&&admin.isEnabled()) {
                        var moderation=admin.getClass().getMethod("getModerationService").invoke(admin);
                        applied=Boolean.TRUE.equals(moderation.getClass().getMethod("applyAnticheatWave",String.class,String.class,String.class,long.class).invoke(moderation,current.event(),current.name(),current.reason(),current.duration()));
                    } else {
                        applied=!current.command().isBlank()&&Bukkit.dispatchCommand(Bukkit.getConsoleSender(),current.command());
                    }
                    store.transition(current.event(),"APPLYING",applied?"APPLIED":"REVIEW");
                    if(applied) {
                        executed++;
                        databaseManager.logPunishmentAsync(new net.lovelace.vesuvio.storage.PunishmentRecord(UUID.fromString(current.uuid()),current.name(),"WAVE_BAN",current.reason(),System.currentTimeMillis()));
                    }
                } catch(Exception error) {
                    try {store.transition(entry.event(),"APPLYING","REVIEW");}catch(java.sql.SQLException ignored){}
                    LOGGER.log(java.util.logging.Level.WARNING,"Наказание волны требует проверки: "+entry.event(),error);
                }
            }
            try {refresh();}catch(java.sql.SQLException error){LOGGER.warning("Не обновлена очередь волны");}

            if (config.isWaveBroadcastEnabled() && executed > 0) {
                String announcement = String.format(Locale.US,
                        "<newline><gradient:#ff4500:#ff8c00><b>🌋 [Vesuvio] Волна банов Lava Wave изверглась!</b></gradient><newline>"
                        + "<gray>Массовая зачистка античита заблокировала <red><b>%d</b></red> нарушителей. Играйте честно!</gray><newline>",
                        executed);
                // Staff only: the public text told every player how many were banned and when, which only helps cheaters time their next wave.
                Bukkit.broadcast(mm.deserialize(announcement), "vesuvio.alerts");
            }
        });

        return toExecute.size();
    }
    private synchronized void refresh()throws java.sql.SQLException {
        queue.clear();for(var e:store.list())if(e.state().equals("QUEUED"))queue.put(UUID.fromString(e.uuid()),new QueuedPunishment(UUID.fromString(e.uuid()),e.name(),e.command(),e.reason(),e.created()));
    }
    public List<WaveStore.Entry> panelQueue() {try{return store.list();}catch(java.sql.SQLException error){throw new IllegalStateException(error);}}
    public synchronized boolean edit(String event,String reason,long duration) {try{boolean changed=store.edit(event,reason,duration);refresh();return changed;}catch(java.sql.SQLException error){throw new IllegalStateException(error);}}
    public synchronized boolean remove(String event) {try{boolean changed=store.transition(event,"QUEUED","REMOVED");refresh();return changed;}catch(java.sql.SQLException error){throw new IllegalStateException(error);}}
    @org.bukkit.event.EventHandler public void disabled(org.bukkit.event.server.PluginDisableEvent event) {
        if(event.getPlugin()!=plugin)return;
        try {if(store!=null)store.close();}catch(java.sql.SQLException error){LOGGER.warning("Не закрыта очередь волн");}
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

}
