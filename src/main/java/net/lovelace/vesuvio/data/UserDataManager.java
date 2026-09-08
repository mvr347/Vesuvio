package net.lovelace.vesuvio.data;

import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all online UserData instances with lock-free concurrency.
 *
 * Author: Lovelace
 */
public final class UserDataManager {

    private final Map<UUID, UserData> users = new ConcurrentHashMap<>();

    public UserData getOrCreate(UUID uuid, String name) {
        return users.computeIfAbsent(uuid, id -> new UserData(id, name));
    }

    public UserData getOrCreate(Player player) {
        return getOrCreate(player.getUniqueId(), player.getName());
    }

    public UserData get(UUID uuid) {
        return users.get(uuid);
    }

    public void remove(UUID uuid) {
        UserData data = users.remove(uuid);
        if (data != null) {
            data.getClickBuffer().reset();
            data.getAimBuffer().reset();
        }
    }

    public Collection<UserData> getAllUsers() {
        return Collections.unmodifiableCollection(users.values());
    }

    public int getOnlineCount() {
        return users.size();
    }

    /**
     * Returns suspects sorted by Risk Index (descending).
     */
    public List<UserData> getSuspects(double minRisk) {
        List<UserData> suspects = new ArrayList<>();
        for (UserData data : users.values()) {
            if (data.isManualSuspect() || data.getRiskIndex() >= minRisk || data.getVl() >= 15.0) {
                suspects.add(data);
            }
        }
        suspects.sort((a, b) -> Double.compare(b.getRiskIndex(), a.getRiskIndex()));
        return suspects;
    }

    /**
     * Periodic decay for all active users.
     */
    public void performDecay(double decayAmount) {
        for (UserData data : users.values()) {
            if (data.getVl() > 0) {
                data.decayVl(decayAmount);
            }
        }
    }

    public void clear() {
        users.clear();
    }
}
