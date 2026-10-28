package net.lovelace.vesuvio.api;

import net.lovelace.vesuvio.check.onnx.MLManager;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.storage.DatabaseManager;
import net.lovelace.vesuvio.storage.PunishmentRecord;
import net.lovelace.vesuvio.storage.ViolationRecord;

import java.util.ArrayList;
import java.util.List;
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

    // Optional - only needed for the richer web-panel methods. Null-safe: a plugin only using
    // the original trust/risk/vl methods can still construct this without them.
    private final DatabaseManager databaseManager;
    private final MLManager mlManager;
    private final SelfLearningManager selfLearningManager;
    private final ConfigManager config;

    public VesuvioAPIImpl(UserDataManager userDataManager,
                          net.lovelace.vesuvio.punishment.PunishmentWaveManager waveManager,
                          double highRiskThreshold,
                          DatabaseManager databaseManager,
                          MLManager mlManager,
                          SelfLearningManager selfLearningManager,
                          ConfigManager config) {
        this.userDataManager = userDataManager;
        this.waveManager = waveManager;
        this.highRiskThreshold = highRiskThreshold;
        this.databaseManager = databaseManager;
        this.mlManager = mlManager;
        this.selfLearningManager = selfLearningManager;
        this.config = config;
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

    // -------------------------------------------------------------
    // Web panel integration
    // -------------------------------------------------------------

    @Override
    public List<WebPanelModels.SuspectInfo> getSuspects() {
        List<WebPanelModels.SuspectInfo> result = new ArrayList<>();
        double threshold = (config != null) ? config.getHighRiskThreshold() : highRiskThreshold;
        for (UserData data : userDataManager.getSuspects(threshold)) {
            result.add(new WebPanelModels.SuspectInfo(
                    data.getUuid(), data.getUsername(), data.getVl(), data.getRiskIndex(), data.getTrustScore(),
                    data.getClientBrand(), getPlayerStatus(data.getUuid()), data.getLastTriggeredCheck(),
                    data.isManualSuspect()
            ));
        }
        return result;
    }

    @Override
    public List<WebPanelModels.PunishmentInfo> getRecentPunishments(int limit) {
        List<WebPanelModels.PunishmentInfo> result = new ArrayList<>();
        if (databaseManager == null) return result;
        for (PunishmentRecord r : databaseManager.getRecentPunishments(Math.clamp(limit,1,200))) {
            result.add(new WebPanelModels.PunishmentInfo(r.uuid(), r.username(), r.action(), r.reason(), r.timestamp()));
        }
        return result;
    }

    @Override
    public List<WebPanelModels.ViolationInfo> getRecentViolations(int limit) {
        List<WebPanelModels.ViolationInfo> result = new ArrayList<>();
        if (databaseManager == null) return result;
        for (ViolationRecord r : databaseManager.getRecentViolations(Math.clamp(limit,1,200))) {
            result.add(new WebPanelModels.ViolationInfo(
                    r.uuid(), r.username(), r.checkName(), r.vl(), r.confidence(), r.explanation(), r.timestamp()
            ));
        }
        return result;
    }

    @Override
    public WebPanelModels.PlayerDetail getPlayerDetail(UUID uuid) {
        UserData data = userDataManager.get(uuid);
        if (data == null) return null;

        synchronized(data) { return new WebPanelModels.PlayerDetail(
                data.getUuid(), data.getUsername(), data.getVl(), data.getRiskIndex(), data.getTrustScore(),
                data.getSensitivityMultiplier(), data.getClientBrand(), data.getLastTriggeredCheck(), data.isManualSuspect(),
                data.getLastCalculatedCPS(), data.getLastStdDevMs(), data.getLastDupRatio(), data.getLastEntropy(),
                data.getLastMLProbability(), data.getLastSelfLearnProbability(),
                data.getAirTicks(), data.getLastDeltaY(), data.isLastOnGround(),
                data.getClickBuffer().getCount(), data.getAimBuffer().getCount(),
                data.getGcdSuspiciousStreak(), data.getPerfectAimStreak(), data.isInCombat()
        ); }
    }

    @Override
    public WebPanelModels.EngineStatus getEngineStatus() {
        boolean clickLoaded = mlManager != null && mlManager.isModelLoaded("click_model");
        boolean aimLoaded = mlManager != null && mlManager.isModelLoaded("aim_model");
        long onnxInferences = mlManager != null ? mlManager.getTotalInferences() : 0L;

        int datasetSize = 0;
        long trainedSamples = 0L;
        boolean selfLearnEnabled = false;
        boolean autoCollect = false;
        if (selfLearningManager != null) {
            datasetSize = selfLearningManager.getDatasetManager().getDatasetSize();
            trainedSamples = selfLearningManager.getOnlineClassifier().getTrainedSamplesCount();
        }
        if (config != null) {
            selfLearnEnabled = config.isSelfLearningEnabled();
            autoCollect = config.isAutoCollectionEnabled();
        }

        return new WebPanelModels.EngineStatus(
                config == null || config.isStatisticalEnabled(),
                config == null || config.isOnnxEnabled(),
                selfLearnEnabled,
                clickLoaded,
                aimLoaded,
                onnxInferences,
                userDataManager.getOnlineCount(),
                userDataManager.getSuspects(highRiskThreshold).size(),
                datasetSize,
                trainedSamples,
                autoCollect,
                false,
                databaseManager != null ? databaseManager.getTotalFlagsCount() : 0,
                config != null ? config.getPunishmentRules().size() : 0
        );
    }

    @Override
    public void resetViolationLevel(UUID uuid) {
        UserData data = userDataManager.get(uuid);
        if (data != null) {
            data.resetVl();
        }
    }

    @Override
    public void setManualSuspect(UUID uuid, boolean suspect) {
        UserData data = userDataManager.get(uuid);
        if (data != null) {
            data.setManualSuspect(suspect);
            if (!suspect) {
                data.setRiskIndex(10.0);
                data.resetVl();
            }
        }
        if (databaseManager != null) {
            databaseManager.setManualSuspect(uuid, suspect);
        }
    }

    private java.util.concurrent.Executor managementExecutor;
    private java.nio.file.Path modelDirectory;
    private final java.util.concurrent.atomic.AtomicLong lastReload=new java.util.concurrent.atomic.AtomicLong();

    public void configureManagement(java.util.concurrent.Executor executor, java.nio.file.Path models) {
        this.managementExecutor=java.util.Objects.requireNonNull(executor);
        this.modelDirectory=java.util.Objects.requireNonNull(models);
    }
    @Override public int getIntegrationVersion() { return 2; }
    @Override public java.util.Set<String> getCapabilities() {
        return java.util.Set.of("overview","players","violations","reviews","reload-models");
    }
    @Override public List<WebPanelModels.ReviewInfo> getPendingReviews(int limit) {
        if(selfLearningManager==null)return List.of();
        return selfLearningManager.getActiveLearning().getPendingReviews().stream()
            .sorted(java.util.Comparator.comparingLong(net.lovelace.vesuvio.check.selflearning.ActiveLearning.ReviewSample::timestamp).reversed())
            .limit(Math.clamp(limit,1,200)).map(sample->{
                var features=new ArrayList<Float>();for(float feature:sample.features())features.add(Float.isFinite(feature)?feature:0f);
                return new WebPanelModels.ReviewInfo(sample.id(),sample.playerUuid(),sample.playerName(),sample.domain(),sample.probability(),sample.timestamp(),features);
            }).toList();
    }
    @Override public java.util.concurrent.CompletableFuture<Boolean> submitReview(String id,String verdict,String reviewer) {
        if(!java.util.Set.of("cheat","legit").contains(verdict)||reviewer==null||reviewer.isBlank())
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Укажите вердикт и сотрудника"));
        if(managementExecutor==null||selfLearningManager==null)return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Обучение недоступно"));
        return net.lovelace.vesuvio.engine.AsyncTasks.supply(()->{
            var learning=selfLearningManager.getActiveLearning();var sample=learning.getSample(id);
            if(sample==null)return false;
            var classifier="aim".equals(sample.domain())?selfLearningManager.getAimClassifier():selfLearningManager.getOnlineClassifier();
            return learning.submitVerdict(id,verdict,reviewer,classifier,selfLearningManager.getDatasetManager(),userDataManager.get(sample.playerUuid()));
        },managementExecutor);
    }
    @Override public java.util.concurrent.CompletableFuture<Boolean> reloadModels() {
        if(mlManager==null||modelDirectory==null)return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Модели недоступны"));
        long now=System.nanoTime(),previous=lastReload.get();
        if(previous!=0&&now-previous<java.util.concurrent.TimeUnit.SECONDS.toNanos(60)||!lastReload.compareAndSet(previous,now))
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Перезагрузка уже выполнялась; повторите через минуту"));
        return mlManager.hotReload("click_model",modelDirectory.resolve("click_model.onnx"))
            .thenCombine(mlManager.hotReload("aim_model",modelDirectory.resolve("aim_model.onnx")),(click,aim)->click&&aim);
    }
}
