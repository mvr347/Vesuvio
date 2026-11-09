package net.lovelace.vesuvio.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class ViolationReviewsTest {
  @TempDir Path folder;
  SQLiteDataSource source;
  ViolationReviews store;

  @BeforeEach
  void setup() throws Exception {
    source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + folder.resolve("events.db"));
    try (var c = source.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "CREATE TABLE vesuvio_violations(id INTEGER PRIMARY KEY,uuid TEXT,username"
              + " TEXT,check_name TEXT,vl DOUBLE,confidence DOUBLE,explanation TEXT,created_at"
              + " BIGINT)");
      s.execute(
          "INSERT INTO vesuvio_violations VALUES(1,'"
              + UUID.randomUUID()
              + "','Walker','Baritone',4,0.85,'Aligned movement',1794220000000)");
    }
    store = new ViolationReviews(source);
  }

  @Test
  void decisionsSurviveRestartAndStaleEditsCannotReplaceThem() throws Exception {
    assertTrue(store.review(1, 0, "legit", "Staff", "Обычная ходьба вперёд"));
    store = new ViolationReviews(source);
    var row = store.recent(10).getFirst();
    assertEquals("legit", row.verdict());
    assertEquals(1, row.revision());
    assertEquals("Staff", row.reviewer());
    assertEquals(1794220000000L, row.timestamp());
    assertFalse(store.review(1, 0, "cheat", "Other", "Другой сотрудник со старым экраном"));
    assertTrue(store.review(1, 1, "cheat", "Other", "Новые доказательства"));
    assertEquals(2, store.recent(10).getFirst().revision());
    assertEquals(2, count("vesuvio_violation_review_history"));
  }

  @Test
  void invalidAndMissingEventsDoNotCreateDecisions() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.review(1, 0, "invalid", "Staff", "Достаточное обоснование"));
    assertThrows(IllegalArgumentException.class, () -> store.review(1, 0, "legit", "Staff", ""));
    assertFalse(store.review(999, 0, "legit", "Staff", "Событие отсутствует"));
    assertEquals(0, count("vesuvio_violation_reviews"));
  }

  @Test
  void auditWriteFailureRollsBackDecision() throws Exception {
    try (var c = source.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "CREATE TRIGGER fail_audit BEFORE INSERT ON vesuvio_violation_review_history BEGIN SELECT"
              + " RAISE(ABORT,'audit failure');END");
    }
    assertThrows(
        IllegalStateException.class,
        () -> store.review(1, 0, "legit", "Staff", "Ложное срабатывание"));
    assertEquals(0, count("vesuvio_violation_reviews"));
    assertEquals("", store.recent(5).getFirst().verdict());
  }

  @Test
  void concurrentFirstReviewsCommitExactlyOnce() throws Exception {
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 20; i++)
        futures.add(
            pool.submit(() -> store.review(1, 0, "legit", "Staff", "Проверено сотрудником")));
      int wins = 0;
      for (var result : futures) if (result.get()) wins++;
      assertEquals(1, wins);
      assertEquals(1, count("vesuvio_violation_review_history"));
    }
  }

  int count(String table) throws Exception {
    try (var c = source.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      r.next();
      return r.getInt(1);
    }
  }
}
