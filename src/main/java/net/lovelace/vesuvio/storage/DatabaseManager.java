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
