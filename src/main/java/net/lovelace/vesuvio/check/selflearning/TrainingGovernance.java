package net.lovelace.vesuvio.check.selflearning;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Durable admission and human review of feature windows; never a player exemption list. */
public final class TrainingGovernance implements AutoCloseable {
  public record Limits(
      int perPlayerDomainDay, long windowMillis, int maxRecords, int perPlayerDomainTraining) {
    public Limits(int perPlayerDomainDay, long windowMillis, int maxRecords) {
      this(perPlayerDomainDay, windowMillis, maxRecords, 100);
    }

    public Limits {
      if (perPlayerDomainDay < 1
          || perPlayerDomainDay > 1000
          || windowMillis < 1000
          || windowMillis > 600000
          || maxRecords < 100
          || maxRecords > 1000000
          || perPlayerDomainTraining < 1
          || perPlayerDomainTraining > 1000)
        throw new IllegalArgumentException("Недопустимые ограничения обучающих данных");
    }

    public static Limits defaults() {
      return new Limits(50, 15000, 100000);
    }
  }

  private final Connection connection;
  private final Limits limits;

  public TrainingGovernance(Path file, Limits limits) {
    this.limits = limits;
    try {
      connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
      try (var s = connection.createStatement()) {
        s.execute("PRAGMA busy_timeout=3000");
        s.execute("PRAGMA journal_mode=WAL");
        s.execute(
            "CREATE TABLE IF NOT EXISTS training_windows (id INTEGER PRIMARY KEY,uuid TEXT NOT"
                + " NULL,name TEXT NOT NULL,session TEXT NOT NULL,client TEXT NOT NULL,domain TEXT"
                + " NOT NULL,label INTEGER NOT NULL,captured INTEGER NOT NULL,source TEXT NOT"
                + " NULL,features BLOB NOT NULL,state TEXT NOT NULL,reviewer TEXT NOT NULL,reason"
                + " TEXT NOT NULL DEFAULT '',reference TEXT NOT NULL DEFAULT '',revision INTEGER"
                + " NOT NULL DEFAULT 0,day INTEGER NOT NULL,bucket INTEGER NOT NULL,fingerprint"
                + " TEXT NOT NULL,ping INTEGER NOT NULL,tps REAL NOT NULL,brand TEXT NOT NULL,"
                + " UNIQUE(uuid,domain,bucket),UNIQUE(uuid,domain,day,fingerprint))");
        s.execute(
            "CREATE INDEX IF NOT EXISTS training_player_day ON training_windows(uuid,domain,day)");
        s.execute("CREATE INDEX IF NOT EXISTS training_reference ON training_windows(reference)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS revoked_training_references(reference TEXT PRIMARY"
                + " KEY,reviewer TEXT NOT NULL,reason TEXT NOT NULL,reviewed_at INTEGER NOT NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS training_reviews (id INTEGER PRIMARY KEY,window_id INTEGER"
                + " NOT NULL,revision INTEGER NOT NULL,state TEXT NOT NULL,label INTEGER NOT"
                + " NULL,reviewer TEXT NOT NULL,reason TEXT NOT NULL,reference TEXT NOT"
                + " NULL,reviewed_at INTEGER NOT NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS verified_fragment_windows(fragment_id INTEGER NOT"
                + " NULL,window_id INTEGER NOT NULL,PRIMARY KEY(fragment_id,window_id))");
        s.execute(
            "CREATE TABLE IF NOT EXISTS verified_fragments (id INTEGER PRIMARY KEY,uuid TEXT NOT"
                + " NULL,session TEXT NOT NULL,client TEXT NOT NULL,reviewer TEXT NOT NULL,reason"
                + " TEXT NOT NULL,started INTEGER NOT NULL,ended INTEGER NOT NULL,reviewed_at"
                + " INTEGER NOT NULL,reference TEXT NOT NULL)");
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Хранилище обучающих меток недоступно", e);
    }
  }

  /** A rejected duplicate/quota never consumes RAM, CSV space, or an SGD update. */
  public synchronized boolean record(DatasetManager.LabeledSample sample) {
    Objects.requireNonNull(sample.playerUuid());
    if (!Set.of("click", "aim").contains(sample.domain())
        || sample.label() < 0
        || sample.label() > 1
        || sample.timestamp() < 0
        || sample.timestamp() > System.currentTimeMillis() + 60000)
      throw new IllegalArgumentException("Некорректный образец");
    float[] features = sample.features();
    if (features == null || features.length < 16 || features.length > 20)
      throw new IllegalArgumentException("Некорректный вектор");
    var bytes = ByteBuffer.allocate(features.length * Float.BYTES);
    for (float value : features) {
      if (!Float.isFinite(value)) throw new IllegalArgumentException("Некорректный признак");
      bytes.putFloat(value);
    }
    var ctx = sample.context() == null ? DatasetManager.SampleContext.UNKNOWN : sample.context();
    long day = sample.timestamp() / 86400000;
    try {
      try (var q =
          connection.prepareStatement(
              "SELECT (SELECT COUNT(*) FROM training_windows),COUNT(*) FROM training_windows WHERE"
                  + " uuid=? AND domain=? AND day=?")) {
        q.setString(1, sample.playerUuid().toString());
        q.setString(2, sample.domain());
        q.setLong(3, day);
        try (var r = q.executeQuery()) {
          if (r.next()
              && (r.getInt(1) >= limits.maxRecords() || r.getInt(2) >= limits.perPlayerDomainDay()))
            return false;
        }
      }
      // Even a packet trap stays reviewable. Only an explicit employee label is admitted directly.
      boolean approved =
          sample.source() == DatasetManager.LabelSource.STAFF && !blank(sample.reviewer());
      return transaction(
          () -> {
            try (var q =
                connection.prepareStatement(
                    "INSERT OR IGNORE INTO"
                        + " training_windows(uuid,name,session,client,domain,label,captured,source,features,state,reviewer,day,bucket,fingerprint,ping,tps,brand)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
              int i = 1;
              q.setString(i++, sample.playerUuid().toString());
              q.setString(i++, safe(sample.playerName(), 64));
              q.setString(i++, safe(ctx.sessionId(), 64));
              q.setString(i++, safe(ctx.clientVersion(), 64));
              q.setString(i++, sample.domain());
              q.setInt(i++, sample.label());
              q.setLong(i++, sample.timestamp());
              q.setString(i++, Objects.toString(sample.source(), "UNKNOWN"));
              q.setBytes(i++, bytes.array());
              q.setString(i++, approved ? "approved" : "pending");
              q.setString(i++, safe(sample.reviewer(), 64));
              q.setLong(i++, day);
              q.setLong(i++, sample.timestamp() / limits.windowMillis());
              q.setString(i++, fingerprint(bytes.array()));
              q.setInt(i++, ctx.pingMs());
              q.setDouble(i++, ctx.tps());
              q.setString(i, ctx.safeBrand());
              boolean inserted = q.executeUpdate() == 1;
              if (inserted && approved) {
                try (var latest = connection.createStatement();
                    var row = latest.executeQuery("SELECT last_insert_rowid()")) {
                  row.next();
                  audit(row.getLong(1));
                }
              }
              return inserted;
            }
          });
    } catch (SQLException e) {
      throw new IllegalStateException("Не удалось сохранить образец", e);
    }
  }

  public synchronized List<Map<String, Object>> recent(int limit) {
    var rows = new ArrayList<Map<String, Object>>();
    try (var q =
        connection.prepareStatement(
            "SELECT * FROM training_windows ORDER BY captured DESC,id DESC LIMIT ?")) {
      q.setInt(1, Math.clamp(limit, 1, 200));
      try (var r = q.executeQuery()) {
        while (r.next()) {
          var row = new LinkedHashMap<String, Object>();
          for (String key :
              List.of(
                  "id",
                  "uuid",
                  "name",
                  "session",
                  "client",
                  "domain",
                  "label",
                  "captured",
                  "source",
                  "state",
                  "reviewer",
                  "reason",
                  "reference",
                  "revision")) row.put(key, r.getObject(key));
          rows.add(Collections.unmodifiableMap(row));
        }
      }
      return List.copyOf(rows);
    } catch (SQLException e) {
      throw new IllegalStateException("Журнал обучения недоступен", e);
    }
  }

  /** An optimistic revision prevents a stale tab from overwriting a newer review. */
  public synchronized boolean review(
      long id, long revision, String verdict, String actor, String reason, String reference) {
    if (!Set.of("cheat", "legit", "pending").contains(verdict)
        || blank(actor)
        || blank(reason)
        || actor.length() > 64
        || reason.length() > 1000
        || reference == null
        || reference.length() > 128
        || revision < 0
        || revision == Long.MAX_VALUE)
      throw new IllegalArgumentException("Укажите сотрудника, причину и вердикт");
    return transaction(
        () -> {
          String state = "pending".equals(verdict) ? "pending" : "approved";
          if (!"pending".equals(verdict)) checkReference(reference);
          try (var q =
              connection.prepareStatement(
                  "SELECT source,reference FROM training_windows WHERE id=?")) {
            q.setLong(1, id);
            try (var r = q.executeQuery()) {
              if (r.next()) {
                if ("BAN".equals(r.getString("source"))
                    && "cheat".equals(verdict)
                    && blank(reference))
                  throw new IllegalArgumentException(
                      "Для кандидата от автобана укажите окончательный бан");
                if (blank(reference) && !blank(r.getString("reference")))
                  throw new IllegalArgumentException("Сохраните связь с исходным наказанием");
              }
            }
          }
          try (var q =
              connection.prepareStatement(
                  "UPDATE training_windows SET state=?,label=CASE WHEN ?='pending' THEN label ELSE"
                      + " ? END,reviewer=?,reason=?,reference=?,revision=revision+1 WHERE id=? AND"
                      + " revision=?")) {
            q.setString(1, state);
            q.setString(2, verdict);
            q.setInt(3, "cheat".equals(verdict) ? 1 : 0);
            q.setString(4, actor);
            q.setString(5, reason);
            q.setString(6, reference);
            q.setLong(7, id);
            q.setLong(8, revision);
            if (q.executeUpdate() != 1) return false;
          }
          audit(id);
          return true;
        });
  }

  /** Verify only already captured, explicitly selected windows from ONE observed client session. */
  public synchronized int verify(List<Long> ids, String actor, String reason, String reference) {
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 100
        || new HashSet<>(ids).size() != ids.size()
        || blank(actor)
        || actor.length() > 64
        || blank(reason)
        || reason.length() > 1000
        || reference == null
        || reference.length() > 128)
      throw new IllegalArgumentException("Выберите от 1 до 100 фрагментов и укажите причину");
    return transaction(
        () -> {
          checkReference(reference);
          String uuid = null, session = null, client = null;
          long start = Long.MAX_VALUE, end = 0;
          for (long id : ids)
            try (var q =
                connection.prepareStatement(
                    "SELECT * FROM training_windows WHERE id=? AND state='pending'")) {
              q.setLong(1, id);
              try (var r = q.executeQuery()) {
                if (!r.next()
                    || blank(r.getString("session"))
                    || blank(r.getString("client"))
                    || "unknown".equalsIgnoreCase(r.getString("client")))
                  throw new IllegalArgumentException(
                      "Нужны непроверенные окна с известной версией клиента");
                if (uuid == null) {
                  uuid = r.getString("uuid");
                  session = r.getString("session");
                  client = r.getString("client");
                }
                if (!uuid.equals(r.getString("uuid"))
                    || !session.equals(r.getString("session"))
                    || !client.equals(r.getString("client")))
                  throw new IllegalArgumentException("Выберите один вход и одну версию клиента");
                start = Math.min(start, r.getLong("captured"));
                end = Math.max(end, r.getLong("captured"));
              }
            }
          if (end - start > 1800000)
            throw new IllegalArgumentException("Проверенный фрагмент не может превышать 30 минут");
          try (var q =
              connection.prepareStatement(
                  "INSERT INTO"
                      + " verified_fragments(uuid,session,client,reviewer,reason,started,ended,reviewed_at,reference)"
                      + " VALUES(?,?,?,?,?,?,?,?,?)")) {
            q.setString(1, uuid);
            q.setString(2, session);
            q.setString(3, client);
            q.setString(4, actor);
            q.setString(5, reason);
            q.setLong(6, start);
            q.setLong(7, end);
            q.setLong(8, System.currentTimeMillis());
            q.setString(9, reference);
            q.executeUpdate();
          }
          long fragmentId;
          try (var q = connection.createStatement();
              var row = q.executeQuery("SELECT last_insert_rowid()")) {
            row.next();
            fragmentId = row.getLong(1);
          }
          for (long id : ids) {
            try (var q =
                connection.prepareStatement("INSERT INTO verified_fragment_windows VALUES(?,?)")) {
              q.setLong(1, fragmentId);
              q.setLong(2, id);
              q.executeUpdate();
            }
            try (var q =
                connection.prepareStatement(
                    "UPDATE training_windows SET"
                        + " state='approved',label=0,reviewer=?,reason=?,reference=?,revision=revision+1"
                        + " WHERE id=?")) {
              q.setString(1, actor);
              q.setString(2, reason);
              q.setString(3, reference);
              q.setLong(4, id);
              q.executeUpdate();
            }
            audit(id);
          }
          return ids.size();
        });
  }

  /** Successful appeal: revoke the linked labels, never automatically relabel them as clean. */
  public synchronized int revoke(String reference, String actor, String reason) {
    if (blank(reference)
        || reference.length() > 128
        || blank(actor)
        || actor.length() > 64
        || blank(reason)
        || reason.length() > 1000)
      throw new IllegalArgumentException("Укажите основание пересмотра");
    return transaction(
        () -> {
          try (var q =
              connection.prepareStatement(
                  "INSERT INTO revoked_training_references VALUES(?,?,?,?) ON CONFLICT(reference)"
                      + " DO NOTHING")) {
            q.setString(1, reference);
            q.setString(2, actor);
            q.setString(3, reason);
            q.setLong(4, System.currentTimeMillis());
            q.executeUpdate();
          }
          var ids = new ArrayList<Long>();
          try (var q =
              connection.prepareStatement(
                  "SELECT id FROM training_windows WHERE reference=? AND state!='pending'")) {
            q.setString(1, reference);
            try (var r = q.executeQuery()) {
              while (r.next()) ids.add(r.getLong(1));
            }
          }
          for (long id : ids) {
            try (var q =
                connection.prepareStatement(
                    "UPDATE training_windows SET"
                        + " state='pending',reviewer=?,reason=?,revision=revision+1 WHERE id=?")) {
              q.setString(1, actor);
              q.setString(2, reason);
              q.setLong(3, id);
              q.executeUpdate();
            }
            audit(id);
          }
          return ids.size();
        });
  }

  public synchronized List<DatasetManager.LabeledSample> approved() {
    var samples = new ArrayList<DatasetManager.LabeledSample>();
    try (var q = connection.createStatement();
        var r =
            q.executeQuery(
                "SELECT * FROM (SELECT *,ROW_NUMBER() OVER(PARTITION BY uuid,domain ORDER BY"
                    + " captured DESC,id DESC) AS contribution FROM training_windows WHERE"
                    + " state='approved') WHERE contribution<="
                    + limits.perPlayerDomainTraining()
                    + " ORDER BY captured,id")) {
      while (r.next()) {
        var b = ByteBuffer.wrap(r.getBytes("features"));
        float[] vector = new float[b.remaining() / 4];
        for (int i = 0; i < vector.length; i++) vector[i] = b.getFloat();
        samples.add(
            new DatasetManager.LabeledSample(
                UUID.fromString(r.getString("uuid")),
                r.getString("name"),
                vector,
                r.getInt("label"),
                r.getLong("captured"),
                r.getString("reviewer"),
                r.getString("domain"),
                DatasetManager.LabelSource.STAFF,
                new DatasetManager.SampleContext(
                    r.getInt("ping"),
                    r.getDouble("tps"),
                    r.getString("brand"),
                    r.getString("session"),
                    r.getString("client"))));
      }
      return List.copyOf(samples);
    } catch (SQLException e) {
      throw new IllegalStateException("Подтверждённые данные недоступны", e);
    }
  }

  public synchronized long revision() {
    try (var s = connection.createStatement();
        var r = s.executeQuery("SELECT COALESCE(MAX(id),0) FROM training_reviews")) {
      r.next();
      return r.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void checkReference(String reference) throws SQLException {
    if (blank(reference)) return;
    try (var q =
        connection.prepareStatement(
            "SELECT 1 FROM revoked_training_references WHERE reference=?")) {
      q.setString(1, reference);
      try (var r = q.executeQuery()) {
        if (r.next())
          throw new IllegalArgumentException(
              "Наказание пересмотрено; его метки требуют нового основания");
      }
    }
  }

  private void audit(long id) throws SQLException {
    try (var q =
        connection.prepareStatement(
            "INSERT INTO"
                + " training_reviews(window_id,revision,state,label,reviewer,reason,reference,reviewed_at)"
                + " SELECT id,revision,state,label,reviewer,reason,reference,? FROM"
                + " training_windows WHERE id=?")) {
      q.setLong(1, System.currentTimeMillis());
      q.setLong(2, id);
      q.executeUpdate();
    }
  }

  private interface SqlAction<T> {
    T run() throws SQLException;
  }

  private <T> T transaction(SqlAction<T> action) {
    try {
      connection.setAutoCommit(false);
      try {
        T result = action.run();
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Изменения обучающих меток не сохранены", e);
    }
  }

  private static String fingerprint(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String safe(String value, int max) {
    return value == null ? "" : value.substring(0, Math.min(value.length(), max));
  }

  @Override
  public synchronized void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
