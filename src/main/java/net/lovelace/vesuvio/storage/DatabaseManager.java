package net.lovelace.vesuvio.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.lovelace.vesuvio.config.ConfigManager;

import java.nio.file.Path;
import java.sql.*;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-performance database storage engine using HikariCP.
 * Supports SQLite (default) and PostgreSQL with asynchronous batch logging.
 *
 * Author: Lovelace
 */
public final class DatabaseManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Database");

    private final HikariDataSource dataSource;
    private final Queue<ViolationRecord> violationQueue = new ConcurrentLinkedQueue<>();
    private final Queue<PunishmentRecord> punishmentQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService batchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Vesuvio-DB-Batcher");
        t.setDaemon(true);
        return t;
    });

    public DatabaseManager(ConfigManager config, Path dataFolder) {
        HikariConfig hikari = new HikariConfig();
        String type = config.getDatabaseType().toLowerCase();

        if ("postgresql".equals(type)) {
            hikari.setDriverClassName("org.postgresql.Driver");
            hikari.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s",
                    config.getPostgresHost(), config.getPostgresPort(), config.getPostgresDatabase()));
            hikari.setUsername(config.getPostgresUser());
            hikari.setPassword(config.getPostgresPassword());
        } else {
            // Default: SQLite
            try {
                java.nio.file.Files.createDirectories(dataFolder);
            } catch (java.io.IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to create data directory: " + dataFolder, e);
            }
            Path dbFile = dataFolder.resolve(config.getSqliteFileName());
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        }

        hikari.setMaximumPoolSize(config.getDatabasePoolSize());
        hikari.setMinimumIdle(2);
        hikari.setConnectionTimeout(10000);
        hikari.setPoolName("Vesuvio-Pool");

        this.dataSource = new HikariDataSource(hikari);

        initSchema();
        batchExecutor.scheduleWithFixedDelay(this::flushBatch, 5, 5, TimeUnit.SECONDS);
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS vesuvio_players (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(32) NOT NULL,
                    trust_score DOUBLE NOT NULL DEFAULT 50.0,
                    risk_score DOUBLE NOT NULL DEFAULT 10.0,
                    client_brand VARCHAR(64) DEFAULT 'unknown',
                    is_suspect BOOLEAN DEFAULT 0,
                    first_seen BIGINT NOT NULL,
                    last_seen BIGINT NOT NULL
                );
            """);

            try {
                stmt.execute("ALTER TABLE vesuvio_players ADD COLUMN is_suspect BOOLEAN DEFAULT 0;");
            } catch (SQLException ignored) {}

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS vesuvio_violations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid VARCHAR(36) NOT NULL,
                    username VARCHAR(32) NOT NULL,
                    check_name VARCHAR(64) NOT NULL,
                    vl DOUBLE NOT NULL,
                    confidence DOUBLE NOT NULL,
                    explanation TEXT,
                    details TEXT,
                    created_at BIGINT NOT NULL
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS vesuvio_punishments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid VARCHAR(36) NOT NULL,
                    username VARCHAR(32) NOT NULL,
                    action VARCHAR(32) NOT NULL,
                    reason TEXT,
                    created_at BIGINT NOT NULL
                );
            """);

            // Ban-evasion / alt-account detection: a fingerprint snapshot taken the moment a
            // player is banned, so a later join can be matched against it by IP and/or playstyle
            // (click signature). See net.lovelace.vesuvio.evasion.BanEvasionManager.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS vesuvio_ban_fingerprints (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid VARCHAR(36) NOT NULL,
                    username VARCHAR(32) NOT NULL,
                    ip_address VARCHAR(64),
                    client_brand VARCHAR(64),
                    click_signature VARCHAR(128),
                    reason TEXT,
                    created_at BIGINT NOT NULL
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_vesuvio_ban_fp_ip ON vesuvio_ban_fingerprints(ip_address);");

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize database tables", e);
        }
    }

    public record PlayerProfile(double trustScore, double riskScore, String clientBrand, boolean isSuspect) {}

    public PlayerProfile loadPlayer(UUID uuid) {
        String sql = "SELECT trust_score, risk_score, client_brand, is_suspect FROM vesuvio_players WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerProfile(
                            rs.getDouble("trust_score"),
                            rs.getDouble("risk_score"),
                            rs.getString("client_brand"),
                            rs.getBoolean("is_suspect")
                    );
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to load player profile for " + uuid, e);
        }
        return null;
    }

    public void setManualSuspect(UUID uuid, boolean isSuspect) {
        String sql = "UPDATE vesuvio_players SET is_suspect = ? WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, isSuspect);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to update suspect status for " + uuid, e);
        }
    }

    public void logViolationAsync(ViolationRecord record) {
        violationQueue.add(record);
    }

    public void logPunishmentAsync(PunishmentRecord record) {
        punishmentQueue.add(record);
    }

    public void savePlayerSync(UUID uuid, String username, double trust, double risk, String brand, boolean isSuspect) {
        String sql = """
            INSERT INTO vesuvio_players (uuid, username, trust_score, risk_score, client_brand, is_suspect, first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                username = excluded.username,
                trust_score = excluded.trust_score,
                risk_score = excluded.risk_score,
                client_brand = excluded.client_brand,
                is_suspect = excluded.is_suspect,
                last_seen = excluded.last_seen;
        """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setDouble(3, trust);
            ps.setDouble(4, risk);
            ps.setString(5, brand);
            ps.setBoolean(6, isSuspect);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to upsert player " + username, e);
        }
    }

    public void savePlayerSync(UUID uuid, String username, double trust, double risk, String brand) {
        savePlayerSync(uuid, username, trust, risk, brand, false);
    }

    public void flushBatch() {
        if (violationQueue.isEmpty() && punishmentQueue.isEmpty()) return;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // Flush violations
            if (!violationQueue.isEmpty()) {
                String vSql = "INSERT INTO vesuvio_violations (uuid, username, check_name, vl, confidence, explanation, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(vSql)) {
                    ViolationRecord r;
                    int count = 0;
                    while ((r = violationQueue.poll()) != null && count < 200) {
                        ps.setString(1, r.uuid().toString());
                        ps.setString(2, r.username());
                        ps.setString(3, r.checkName());
                        ps.setDouble(4, r.vl());
                        ps.setDouble(5, r.confidence());
                        ps.setString(6, r.explanation());
                        ps.setString(7, r.detailsJson());
                        ps.setLong(8, r.timestamp());
                        ps.addBatch();
                        count++;
                    }
                    ps.executeBatch();
                }
            }

            // Flush punishments
            if (!punishmentQueue.isEmpty()) {
                String pSql = "INSERT INTO vesuvio_punishments (uuid, username, action, reason, created_at) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(pSql)) {
                    PunishmentRecord pr;
                    int count = 0;
                    while ((pr = punishmentQueue.poll()) != null && count < 200) {
                        ps.setString(1, pr.uuid().toString());
                        ps.setString(2, pr.username());
                        ps.setString(3, pr.action());
                        ps.setString(4, pr.reason());
                        ps.setLong(5, pr.timestamp());
                        ps.addBatch();
                        count++;
                    }
                    ps.executeBatch();
                }
            }

            conn.commit();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error committing batch logs to database", e);
        }
    }

    public int getTotalFlagsCount() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM vesuvio_violations")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    /**
     * Most recent punishments, newest first. Safe to call from any thread (including the
     * embedded web server's own thread pool) - never touches Bukkit API.
     */
    public java.util.List<PunishmentRecord> getRecentPunishments(int limit) {
        java.util.List<PunishmentRecord> result = new java.util.ArrayList<>();
        String sql = "SELECT uuid, username, action, reason, created_at FROM vesuvio_punishments ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new PunishmentRecord(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("username"),
                            rs.getString("action"),
                            rs.getString("reason"),
                            rs.getLong("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to load recent punishments", e);
        }
        return result;
    }

    /**
     * Most recent violations (flags), newest first.
     */
    public java.util.List<ViolationRecord> getRecentViolations(int limit) {
        java.util.List<ViolationRecord> result = new java.util.ArrayList<>();
        String sql = "SELECT uuid, username, check_name, vl, confidence, explanation, details, created_at " +
                "FROM vesuvio_violations ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ViolationRecord(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("username"),
                            rs.getString("check_name"),
                            rs.getDouble("vl"),
                            rs.getDouble("confidence"),
                            rs.getString("explanation"),
                            rs.getString("details"),
                            rs.getLong("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to load recent violations", e);
        }
        return result;
    }

    /**
     * Persists a ban fingerprint snapshot. Call synchronously from an already-async context
     * (e.g. the virtual thread that ran the check pipeline) - this is a single infrequent
     * write (only on "ban" punishments), not worth routing through the batch queue.
     */
    public void recordBanFingerprint(BanFingerprintRecord record) {
        String sql = "INSERT INTO vesuvio_ban_fingerprints (uuid, username, ip_address, client_brand, click_signature, reason, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.uuid().toString());
            ps.setString(2, record.username());
            ps.setString(3, record.ipAddress());
            ps.setString(4, record.clientBrand());
            ps.setString(5, record.clickSignature() != null ? net.lovelace.vesuvio.data.ClickSignature.toHexString(record.clickSignature()) : null);
            ps.setString(6, record.reason());
            ps.setLong(7, record.timestamp());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to record ban fingerprint for " + record.username(), e);
        }
    }

    /**
     * Finds the most recent ban fingerprint recorded for this IP address, if any. Used to catch
     * a banned player rejoining on a fresh account from the same network.
     */
    public BanFingerprintRecord findBanFingerprintByIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) return null;
        String sql = "SELECT uuid, username, ip_address, client_brand, click_signature, reason, created_at " +
                "FROM vesuvio_ban_fingerprints WHERE ip_address = ? ORDER BY created_at DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ipAddress);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapBanFingerprint(rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to look up ban fingerprint by IP", e);
        }
        return null;
    }

    /**
     * Most recent ban fingerprints (newest first), for playstyle-signature comparison against a
     * new player. Bounded so this stays cheap even on a server with a long ban history.
     */
    public java.util.List<BanFingerprintRecord> getRecentBanFingerprints(int limit) {
        java.util.List<BanFingerprintRecord> result = new java.util.ArrayList<>();
        String sql = "SELECT uuid, username, ip_address, client_brand, click_signature, reason, created_at " +
                "FROM vesuvio_ban_fingerprints ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 5000)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapBanFingerprint(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to load recent ban fingerprints", e);
        }
        return result;
    }

    private BanFingerprintRecord mapBanFingerprint(ResultSet rs) throws SQLException {
        String sigHex = rs.getString("click_signature");
        return new BanFingerprintRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("username"),
                rs.getString("ip_address"),
                rs.getString("client_brand"),
                sigHex != null ? net.lovelace.vesuvio.data.ClickSignature.fromHexString(sigHex) : null,
                rs.getString("reason"),
                rs.getLong("created_at")
        );
    }

    @Override
    public void close() {
        batchExecutor.shutdown();
        try {
            if (!batchExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                batchExecutor.shutdownNow();
            }
        } catch (InterruptedException ignored) {}

        flushBatch();

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
