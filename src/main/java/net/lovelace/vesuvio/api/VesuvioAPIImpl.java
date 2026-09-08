package net.lovelace.vesuvio.api;

import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;

import java.util.UUID;

/**
 * Default implementation of {@link VesuvioAPI}.
 *
 * Author: Lovelace
 */
public final class VesuvioAPIImpl implements VesuvioAPI {

    private final UserDataManager userDataManager;
    private final net.lovelace.vesuvio.punishment.PunishmentWaveManager waveManager;
    private final double highRiskThreshold;

    public VesuvioAPIImpl(UserDataManager userDataManager,
                          net.lovelace.vesuvio.punishment.PunishmentWaveManager waveManager,
                          double highRiskThreshold) {
        this.userDataManager = userDataManager;
        this.waveManager = waveManager;
        this.highRiskThreshold = highRiskThreshold;
    }

    @Override
    public double getTrustScore(UUID uuid) {
        UserData data = userDataManager.get(uuid);
        return (data != null) ? data.getTrustScore() : 50.0;
    }

    @Override
    public double getRiskScore(UUID uuid) {
        UserData data = userDataManager.get(uuid);
        return (data != null) ? data.getRiskIndex() : 0.0;
    }

    @Override
    public double getViolationLevel(UUID uuid) {
        UserData data = userDataManager.get(uuid);
        return (data != null) ? data.getVl() : 0.0;
    }

    @Override
    public boolean isSuspect(UUID uuid) {
        UserData data = userDataManager.get(uuid);
        return (data != null) && (data.isManualSuspect() || data.getRiskIndex() >= highRiskThreshold || data.getVl() >= 15.0);
    }

    @Override
    public boolean isHighRisk(UUID uuid) {
        UserData data = userDataManager.get(uuid);
        return (data != null) && (data.getRiskIndex() >= highRiskThreshold);
    }

    @Override
    public boolean isQueuedForWave(UUID uuid) {
        return waveManager != null && waveManager.isQueued(uuid);
    }

    @Override
    public String getPlayerStatus(UUID uuid) {
        if (isQueuedForWave(uuid)) {
            return "Очередь бана (Lava Wave)";
        }
        if (isHighRisk(uuid)) {
            return "Высокий риск";
        }
        if (isSuspect(uuid)) {
            return "Подозрительный";
        }
        return "Чист";
    }

    @Override
    public void adjustTrust(UUID uuid, double delta) {
        UserData data = userDataManager.get(uuid);
        if (data != null) {
            data.adjustTrust(delta);
        }
    }

    @Override
    public void adjustRisk(UUID uuid, double delta) {
        UserData data = userDataManager.get(uuid);
        if (data != null) {
            data.adjustRisk(delta);
        }
    }

    @Override
    public String getClientBrand(UUID uuid) {
        UserData data = userDataManager.get(uuid);
        return (data != null) ? data.getClientBrand() : "unknown";
    }
}
