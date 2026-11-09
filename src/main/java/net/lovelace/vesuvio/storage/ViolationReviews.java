package net.lovelace.vesuvio.storage;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Durable staff decisions about individual events, separate from ML feature labels. */
public final class ViolationReviews {
  public record Event(
      long id,
      UUID uuid,
      String name,
      String checkName,
      double vl,
      double confidence,
      String explanation,
      long timestamp,
      String verdict,
      String reviewer,
      String reviewReason,
      long reviewedAt,
      long revision) {}

  private final DataSource source;

  public ViolationReviews(DataSource source) {
    this.source = source;
    try (var c = source.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "CREATE INDEX IF NOT EXISTS idx_vesuvio_violations_review_time ON"
              + " vesuvio_violations(created_at,id)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS vesuvio_violation_reviews (violation_id BIGINT PRIMARY"
              + " KEY,verdict VARCHAR(8) NOT NULL,reviewer VARCHAR(64) NOT NULL,reason TEXT NOT"
              + " NULL,reviewed_at BIGINT NOT NULL,revision BIGINT NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS vesuvio_violation_review_history (violation_id BIGINT NOT"
              + " NULL,revision BIGINT NOT NULL,verdict VARCHAR(8) NOT NULL,reviewer VARCHAR(64)"
              + " NOT NULL,reason TEXT NOT NULL,reviewed_at BIGINT NOT NULL,PRIMARY"
              + " KEY(violation_id,revision))");
    } catch (SQLException error) {
      throw new IllegalStateException("Не удалось создать журнал разбора срабатываний", error);
    }
  }

  public List<Event> recent(int limit) {
    var events = new ArrayList<Event>();
    String sql =
        "SELECT v.*,r.verdict,r.reviewer,r.reason AS review_reason,r.reviewed_at,r.revision FROM"
            + " vesuvio_violations v LEFT JOIN vesuvio_violation_reviews r ON r.violation_id=v.id"
            + " ORDER BY v.created_at DESC,v.id DESC LIMIT ?";
    try (var c = source.getConnection();
        var q = c.prepareStatement(sql)) {
      q.setInt(1, Math.clamp(limit, 1, 200));
      try (var r = q.executeQuery()) {
        while (r.next())
          events.add(
              new Event(
                  r.getLong("id"),
                  UUID.fromString(r.getString("uuid")),
                  r.getString("username"),
                  r.getString("check_name"),
                  r.getDouble("vl"),
                  r.getDouble("confidence"),
                  r.getString("explanation"),
                  r.getLong("created_at"),
                  Objects.toString(r.getString("verdict"), ""),
                  Objects.toString(r.getString("reviewer"), ""),
                  Objects.toString(r.getString("review_reason"), ""),
                  r.getLong("reviewed_at"),
                  r.getLong("revision")));
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Журнал разбора недоступен", error);
    }
    return List.copyOf(events);
  }

  /** Revision checks prevent a stale screen from replacing another employee's decision. */
  public synchronized boolean review(
      long id, long expectedRevision, String verdict, String reviewer, String reason) {
    if (id <= 0
        || expectedRevision < 0
        || expectedRevision == Long.MAX_VALUE
        || !Set.of("cheat", "legit").contains(verdict)
        || reviewer == null
        || reviewer.isBlank()
        || reviewer.length() > 64
        || reason == null
        || reason.trim().length() < 5
        || reason.length() > 2000)
      throw new IllegalArgumentException("Укажите срабатывание, вердикт и обоснование");
    long at = System.currentTimeMillis(), next = expectedRevision + 1;
    try (var c = source.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (var q = c.prepareStatement("SELECT id FROM vesuvio_violations WHERE id=?")) {
          q.setLong(1, id);
          try (var r = q.executeQuery()) {
            if (!r.next()) {
              c.rollback();
              return false;
            }
          }
        }
        int changed;
        if (expectedRevision == 0) {
          try (var q =
              c.prepareStatement(
                  "INSERT INTO"
                      + " vesuvio_violation_reviews(violation_id,verdict,reviewer,reason,reviewed_at,revision)"
                      + " VALUES(?,?,?,?,?,1) ON CONFLICT(violation_id) DO NOTHING")) {
            q.setLong(1, id);
            q.setString(2, verdict);
            q.setString(3, reviewer);
            q.setString(4, reason.trim());
            q.setLong(5, at);
            changed = q.executeUpdate();
          }
        } else {
          try (var q =
              c.prepareStatement(
                  "UPDATE vesuvio_violation_reviews SET"
                      + " verdict=?,reviewer=?,reason=?,reviewed_at=?,revision=? WHERE"
                      + " violation_id=? AND revision=?")) {
            q.setString(1, verdict);
            q.setString(2, reviewer);
            q.setString(3, reason.trim());
            q.setLong(4, at);
            q.setLong(5, next);
            q.setLong(6, id);
            q.setLong(7, expectedRevision);
            changed = q.executeUpdate();
          }
        }
        if (changed != 1) {
          c.rollback();
          return false;
        }
        try (var q =
            c.prepareStatement(
                "INSERT INTO vesuvio_violation_review_history VALUES(?,?,?,?,?,?)")) {
          q.setLong(1, id);
          q.setLong(2, next);
          q.setString(3, verdict);
          q.setString(4, reviewer);
          q.setString(5, reason.trim());
          q.setLong(6, at);
          q.executeUpdate();
        }
        c.commit();
        return true;
      } catch (SQLException | RuntimeException error) {
        c.rollback();
        throw error;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Не удалось сохранить разбор срабатывания", error);
    }
  }
}
