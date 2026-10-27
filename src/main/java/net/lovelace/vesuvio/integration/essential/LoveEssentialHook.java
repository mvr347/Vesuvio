package net.lovelace.vesuvio.integration.essential;

import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.*;
import net.lovelace.vesuvio.engine.TransactionManager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.*;
import org.bukkit.plugin.*;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Optional public-service adapter. All reflection/Bukkit access stays on main, never packet
 * workers.
 */
public final class LoveEssentialHook implements Listener, AutoCloseable {
    private static final String SERVICE =
            "dev.lovelace.loveessential.api.mechanics.PlayerMechanicsService";
    private static final String LEGACY = "dev.lovelace.lovecore.api.revive.DownedOracle";
    private static final Set<String> KNOWN =
            Set.of("SITTING", "LYING", "RIDING", "CARRYING", "DOWNED", "REVIVING", "GOD");
    private final Plugin plugin;
    private final UserDataManager users;
    private final TransactionManager transactions;
    private final ConfigManager config;
    private final Listener stateEvents = new Listener() {};
    private Binding binding, legacy;
    private boolean closed, warned;
    private java.util.function.Consumer<UUID> invalidateGeometry = id -> {};

    public void invalidateGeometry(java.util.function.Consumer<UUID> invalidate) {
        requireMain();
        invalidateGeometry = Objects.requireNonNull(invalidate);
    }

    private record Binding(Object service, Plugin owner, Method snapshot, Method states) {}

    public LoveEssentialHook(
            Plugin plugin,
            UserDataManager users,
            TransactionManager transactions,
            ConfigManager config) {
        this.plugin = plugin;
        this.users = users;
        this.transactions = transactions;
        this.config = config;
    }

    public void start() {
        requireMain();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        bind();
    }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("LoveEssential state must be captured on main");
    }

    private void bind() {
        requireMain();
        HandlerList.unregisterAll(stateEvents);
        binding = null;
        legacy = null;
        for (Class<?> type : Bukkit.getServicesManager().getKnownServices()) {
            var registration = Bukkit.getServicesManager().getRegistration(type);
            if (registration == null || !registration.getPlugin().getName().equals("LoveEssential"))
                continue;
            try {
                if (type.getName().equals(SERVICE)) {
                    Method snapshot = type.getMethod("snapshot", UUID.class),
                            states = snapshot.getReturnType().getMethod("states");
                    binding =
                            new Binding(
                                    registration.getProvider(),
                                    registration.getPlugin(),
                                    snapshot,
                                    states);
                } else if (type.getName().equals(LEGACY))
                    legacy =
                            new Binding(
                                    registration.getProvider(),
                                    registration.getPlugin(),
                                    type.getMethod("downed", UUID.class),
                                    null);
            } catch (ReflectiveOperationException error) {
                warn(error);
            }
        }
        Binding selected = binding;
        if (selected != null)
            try {
                Class<? extends Event> type =
                        Class.forName(
                                        "dev.lovelace.loveessential.api.mechanics.PlayerMechanicsChangeEvent",
                                        true,
                                        selected.owner.getClass().getClassLoader())
                                .asSubclass(Event.class);
                Method id = type.getMethod("getPlayerId"),
                        state = type.getMethod("getState"),
                        pulse = type.getMethod("isTransientChange");
                Bukkit.getPluginManager()
                        .registerEvent(
                                type,
                                stateEvents,
                                EventPriority.MONITOR,
                                (listener, event) -> {
                                    if (closed
                                            || binding != selected
                                            || !config.isLoveEssentialIntegrationEnabled()) return;
                                    try {
                                        UUID uuid = (UUID) id.invoke(event);
                                        UserData data = users.get(uuid);
                                        if (data != null)
                                            apply(
                                                    data,
                                                    decode(state.invoke(event), selected.states),
                                                    (Boolean) pulse.invoke(event));
                                    } catch (ReflectiveOperationException error) {
                                        warn(error);
                                    }
                                },
                                plugin);
            } catch (ReflectiveOperationException | ClassCastException error) {
                warn(error);
            }
    }

    @EventHandler
    public void registered(ServiceRegisterEvent event) {
        String name = event.getProvider().getService().getName();
        if (name.equals(SERVICE) || name.equals(LEGACY)) bind();
    }

    @EventHandler
    public void unregistered(ServiceUnregisterEvent event) {
        String name = event.getProvider().getService().getName();
        if (name.equals(SERVICE) || name.equals(LEGACY)) {
            bind();
            for (var data : users.getAllUsers()) apply(data, Set.of(), false);
        }
    }

    private Set<String> decode(Object snapshot, Method getter) throws ReflectiveOperationException {
        Object result = getter.invoke(snapshot);
        if (!(result instanceof Collection<?> values) || values.isEmpty()) return Set.of();
        Set<String> configured = config.getLoveEssentialStates();
        var states = new HashSet<String>();
        for (Object value : values) {
            String name = value.toString();
            if (KNOWN.contains(name) && configured.contains(name)) states.add(name);
        }
        return Set.copyOf(states);
    }

    public void capture(Player player, UserData data) {
        requireMain();
        if (closed) return;
        Set<String> states = Set.of();
        if (config.isLoveEssentialIntegrationEnabled())
            try {
                if (binding != null && binding.owner.isEnabled())
                    states =
                            decode(
                                    binding.snapshot.invoke(binding.service, player.getUniqueId()),
                                    binding.states);
                else if (legacy != null
                        && legacy.owner.isEnabled()
                        && config.getLoveEssentialStates().contains("DOWNED")
                        && Boolean.TRUE.equals(
                                legacy.snapshot.invoke(legacy.service, player.getUniqueId())))
                    states = Set.of("DOWNED");
            } catch (ReflectiveOperationException error) {
                warn(error);
            }
        apply(data, states, false);
    }

    private void apply(UserData data, Set<String> states, boolean pulse) {
        long grace =
                config.isLoveEssentialIntegrationEnabled()
                        ? config.getLoveEssentialTransitionMs()
                        : 0;
        double rtt = transactions.getTransactionPing(data.getUuid());
        if (grace > 0 && Double.isFinite(rtt) && rtt > 0)
            grace = Math.max(grace, Math.min(2000, (long) (2 * rtt + 100)));
        if (pulse || !data.mechanics().states().equals(states)) invalidateGeometry.accept(data.getUuid());
        data.updateMechanics(states, System.nanoTime(), grace * 1_000_000L, pulse);
    }

    private void warn(Exception error) {
        if (warned) return;
        warned = true;
        plugin.getLogger()
                .warning(
                        "LoveEssential: API состояний недоступно ("
                                + error.getClass().getSimpleName()
                                + "). Обновите LoveEssential; используется доступный"
                                + " DownedOracle.");
    }

    @Override
    public void close() {
        requireMain();
        closed = true;
        HandlerList.unregisterAll(stateEvents);
        HandlerList.unregisterAll(this);
        binding = null;
        legacy = null;
        for (var data : users.getAllUsers())
            data.updateMechanics(Set.of(), System.nanoTime(), 0, false);
    }
}
