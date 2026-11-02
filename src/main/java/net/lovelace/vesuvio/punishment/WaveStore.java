package net.lovelace.vesuvio.punishment;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Durable wave queue. Execution outcomes remain available for staff review. */
public final class WaveStore implements AutoCloseable {
  public record Entry(
      String event,
      String uuid,
      String name,
      String command,
      String reason,
      long created,
      long duration,
      String state) {}

  private final Connection db;

  public WaveStore(Path file) throws SQLException {
    db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    try (var s = db.createStatement()) {
      s.execute("PRAGMA busy_timeout=5000");
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA synchronous=FULL");
      s.execute(
          "CREATE TABLE IF NOT EXISTS moderation_wave(event TEXT PRIMARY KEY,uuid TEXT NOT"
              + " NULL,name TEXT NOT NULL,command TEXT NOT NULL,reason TEXT NOT NULL,created"
              + " INTEGER NOT NULL,duration INTEGER NOT NULL,state TEXT NOT NULL)");
      s.execute("UPDATE moderation_wave SET state='REVIEW' WHERE state='APPLYING'");
    }
  }

  public synchronized void add(Entry e) throws SQLException {
    try (var q = db.prepareStatement("INSERT INTO moderation_wave VALUES(?,?,?,?,?,?,?,?)")) {
      q.setString(1, e.event);
      q.setString(2, e.uuid);
      q.setString(3, e.name);
      q.setString(4, e.command);
      q.setString(5, e.reason);
      q.setLong(6, e.created);
      q.setLong(7, e.duration);
      q.setString(8, e.state);
      q.executeUpdate();
    }
  }

  public synchronized Optional<Entry> find(String event) throws SQLException {
    try (var q = db.prepareStatement("SELECT * FROM moderation_wave WHERE event=?")) {
      q.setString(1, event);
      try (var r = q.executeQuery()) {
        return r.next()
            ? Optional.of(
                new Entry(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    r.getString(4),
                    r.getString(5),
                    r.getLong(6),
                    r.getLong(7),
                    r.getString(8)))
            : Optional.empty();
      }
    }
  }

  public synchronized List<Entry> list() throws SQLException {
    var out = new ArrayList<Entry>();
    try (var q = db.createStatement();
        var r =
            q.executeQuery(
                "SELECT * FROM moderation_wave ORDER BY CASE state WHEN 'QUEUED' THEN 0 WHEN"
                    + " 'APPLYING' THEN 1 ELSE 2 END,created DESC LIMIT 3000")) {
      while (r.next())
        out.add(
            new Entry(
                r.getString(1),
                r.getString(2),
                r.getString(3),
                r.getString(4),
                r.getString(5),
                r.getLong(6),
                r.getLong(7),
                r.getString(8)));
    }
    return List.copyOf(out);
  }

  public synchronized boolean transition(String event, String from, String to) throws SQLException {
    try (var q =
        db.prepareStatement("UPDATE moderation_wave SET state=? WHERE event=? AND state=?")) {
      q.setString(1, to);
      q.setString(2, event);
      q.setString(3, from);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized boolean edit(String event, String reason, long duration) throws SQLException {
    if (reason == null
        || reason.isBlank()
        || reason.length() > 1000
        || duration != -1 && (duration < 60 || duration > 315360000L))
      throw new IllegalArgumentException("Укажите причину и допустимый срок");
    try (var q =
        db.prepareStatement(
            "UPDATE moderation_wave SET reason=?,duration=? WHERE event=? AND state='QUEUED'")) {
      q.setString(1, reason);
      q.setLong(2, duration);
      q.setString(3, event);
      return q.executeUpdate() == 1;
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    db.close();
  }
}
