package net.lovelace.vesuvio;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import net.lovelace.vesuvio.check.onnx.ModelProvenance;
import net.lovelace.vesuvio.check.selflearning.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TrainingGovernanceTest {
  @TempDir Path folder;
  UUID player = UUID.randomUUID();
  long at = System.currentTimeMillis() - 120000;

  DatasetManager.LabeledSample sample(
      int offset, DatasetManager.LabelSource source, String session, String client) {
    float[] vector = new float[16];
    Arrays.fill(vector, offset + 1);
    return new DatasetManager.LabeledSample(
        player,
        "Test",
        vector,
        1,
        at + offset * 15000L,
        "Staff",
        "click",
        source,
        new DatasetManager.SampleContext(40, 20, "vanilla", session, client));
  }

  long id(Map<String, Object> row) {
    return ((Number) row.get("id")).longValue();
  }

  @Test
  void automaticLabelsStayQuarantinedAcrossRestart() {
    var file = folder.resolve("labels.sqlite");
    try (var ledger = new TrainingGovernance(file, TrainingGovernance.Limits.defaults())) {
      for (var source :
          List.of(
              DatasetManager.LabelSource.BAN,
              DatasetManager.LabelSource.TRUSTED,
              DatasetManager.LabelSource.TRAP))
        assertTrue(ledger.record(sample(source.ordinal(), source, "session", "26.2")));
      assertTrue(ledger.approved().isEmpty());
      assertEquals(3, ledger.recent(10).size());
    }
    try (var ledger = new TrainingGovernance(file, TrainingGovernance.Limits.defaults())) {
      assertTrue(ledger.approved().isEmpty());
      assertEquals(3, ledger.recent(10).size());
    }
  }

  @Test
  void quotaAndDuplicateWindowsSurviveRestart() {
    var file = folder.resolve("labels.sqlite");
    var limits = new TrainingGovernance.Limits(2, 15000, 100);
    try (var ledger = new TrainingGovernance(file, limits)) {
      assertTrue(ledger.record(sample(0, DatasetManager.LabelSource.BAN, "session", "26.2")));
      assertFalse(ledger.record(sample(0, DatasetManager.LabelSource.BAN, "session", "26.2")));
    }
    try (var ledger = new TrainingGovernance(file, limits)) {
      assertFalse(
          ledger.record(sample(0, DatasetManager.LabelSource.STAFF, "new-session", "26.2")));
      assertTrue(ledger.record(sample(1, DatasetManager.LabelSource.BAN, "session", "26.2")));
      assertFalse(ledger.record(sample(2, DatasetManager.LabelSource.BAN, "session", "26.2")));
      assertEquals(2, ledger.recent(10).size());
    }
  }

  @Test
  void verifiedFragmentDoesNotApproveFutureSessionsOrClients() {
    try (var ledger =
        new TrainingGovernance(
            folder.resolve("labels.sqlite"), TrainingGovernance.Limits.defaults())) {
      ledger.record(sample(0, DatasetManager.LabelSource.TRUSTED, "session-a", "26.2"));
      long first = id(ledger.recent(10).getFirst());
      assertEquals(1, ledger.verify(List.of(first), "Reviewer", "Проверена запись игры", ""));
      assertEquals(0, ledger.approved().getFirst().label());
      ledger.record(sample(1, DatasetManager.LabelSource.TRUSTED, "session-b", "26.2"));
      ledger.record(sample(2, DatasetManager.LabelSource.TRUSTED, "session-b", "26.3"));
      var ids =
          ledger.recent(10).stream()
              .filter(r -> "pending".equals(r.get("state")))
              .map(this::id)
              .toList();
      assertThrows(
          IllegalArgumentException.class,
          () -> ledger.verify(ids, "Reviewer", "Проверена запись игры", ""));
      assertEquals(
          1, ledger.approved().size()); // transaction rolled back, no partial clean approval
    }
  }

  @Test
  void appealRevokesLabelsAndBlocksLateApprovalRace() {
    var file = folder.resolve("labels.sqlite");
    try (var ledger = new TrainingGovernance(file, TrainingGovernance.Limits.defaults())) {
      ledger.record(sample(0, DatasetManager.LabelSource.BAN, "session", "26.2"));
      long id = id(ledger.recent(10).getFirst());
      assertThrows(
          IllegalArgumentException.class,
          () -> ledger.review(id, 0, "cheat", "Reviewer", "Подтверждены читы", ""));
      assertTrue(
          ledger.review(id, 0, "cheat", "Reviewer", "Подтверждены читы", "lovewebadmin:ban:4"));
      assertFalse(
          ledger.review(id, 0, "legit", "Other", "Устаревшее решение", "lovewebadmin:ban:4"));
      assertEquals(1, ledger.approved().size());
      assertEquals(1, ledger.revoke("lovewebadmin:ban:4", "System", "Апелляция принята"));
      assertEquals(0, ledger.revoke("lovewebadmin:ban:4", "System", "Повторная доставка"));
      assertTrue(ledger.approved().isEmpty());
      assertEquals(0, ledger.revoke("lovewebadmin:ban:5", "System", "Апелляция раньше вердикта"));
      ledger.record(sample(1, DatasetManager.LabelSource.BAN, "session", "26.2"));
      long second = id(ledger.recent(10).getFirst());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ledger.review(
                  second, 0, "cheat", "Reviewer", "Поздний ответ формы", "lovewebadmin:ban:5"));
    }
    try (var ledger = new TrainingGovernance(file, TrainingGovernance.Limits.defaults())) {
      assertTrue(ledger.approved().isEmpty());
      assertEquals("pending", ledger.recent(10).getLast().get("state"));
    }
  }

  @Test
  void rawExportNeverLeaksIntoTrainingAndRevocationRebuildsClassifier() throws Exception {
    var click = new OnlineClassifier(16);
    var aim = new OnlineClassifier(16);
    try (var close = new DatasetClose(folder, click, aim)) {
      var dataset = close.dataset;
      assertTrue(
          dataset.submitSample(sample(0, DatasetManager.LabelSource.BAN, "session", "26.2")).get());
      assertEquals(0, click.getTrainedSamplesCount());
      var output = folder.resolve("approved.csv");
      assertTrue(dataset.exportTrainingSnapshot(output));
      assertEquals(1, Files.readAllLines(output).size());
      dataset
          .trainingTask(
              ledger -> {
                long id = id(ledger.recent(10).getFirst());
                ledger.review(
                    id, 0, "cheat", "Reviewer", "Проверен фрагмент", "lovewebadmin:ban:7");
                dataset.rebuildClassifiers();
                return null;
              })
          .get();
      assertEquals(1, click.getTrainedSamplesCount());
      assertTrue(dataset.exportTrainingSnapshot(output));
      assertEquals(2, Files.readAllLines(output).size());
      dataset
          .trainingTask(
              ledger -> {
                ledger.revoke("lovewebadmin:ban:7", "System", "Апелляция принята");
                dataset.rebuildClassifiers();
                return null;
              })
          .get();
      assertEquals(0, click.getTrainedSamplesCount());
      assertTrue(dataset.exportTrainingSnapshot(output));
      assertEquals(1, Files.readAllLines(output).size());
      assertEquals(2, Files.readAllLines(dataset.getAutoPersistFile()).size());
    }
  }

  @Test
  void onePlayerCannotDominateTheFullTrainingSet() {
    try (var ledger =
        new TrainingGovernance(
            folder.resolve("labels.sqlite"), new TrainingGovernance.Limits(50, 15000, 100, 2))) {
      for (int i = 0; i < 4; i++)
        assertTrue(ledger.record(sample(i, DatasetManager.LabelSource.STAFF, "session", "26.2")));
      assertEquals(2, ledger.approved().size());
      assertEquals(at + 30000, ledger.approved().getFirst().timestamp());
      player = UUID.randomUUID();
      assertTrue(ledger.record(sample(4, DatasetManager.LabelSource.STAFF, "other", "26.2")));
      assertEquals(3, ledger.approved().size());
    }
  }

  @Test
  void auditFailureRollsBackStaffAdmission() throws Exception {
    try (var ledger =
        new TrainingGovernance(
            folder.resolve("labels.sqlite"), TrainingGovernance.Limits.defaults())) {
      var field = TrainingGovernance.class.getDeclaredField("connection");
      field.setAccessible(true);
      var connection = (java.sql.Connection) field.get(ledger);
      try (var statement = connection.createStatement()) {
        statement.execute(
            "CREATE TEMP TRIGGER fail_audit BEFORE INSERT ON training_reviews BEGIN SELECT"
                + " RAISE(ABORT,'test');END");
      }
      assertThrows(
          IllegalStateException.class,
          () -> ledger.record(sample(0, DatasetManager.LabelSource.STAFF, "session", "26.2")));
      assertTrue(ledger.approved().isEmpty());
      assertTrue(ledger.recent(10).isEmpty());
    }
  }

  @Test
  void duplicateFeaturesAtAnotherTimeDoNotConsumeQuota() {
    try (var ledger =
        new TrainingGovernance(
            folder.resolve("labels.sqlite"), TrainingGovernance.Limits.defaults())) {
      var first = sample(0, DatasetManager.LabelSource.BAN, "session", "26.2");
      assertTrue(ledger.record(first));
      var repeated =
          new DatasetManager.LabeledSample(
              player,
              "Test",
              first.features(),
              1,
              first.timestamp() + 30000,
              "Auto",
              "click",
              DatasetManager.LabelSource.BAN,
              first.context());
      assertFalse(ledger.record(repeated));
      assertEquals(1, ledger.recent(10).size());
    }
  }

  @Test
  void boundedQueueDrainsBeforeTheLedgerCloses() throws Exception {
    var dataset = new DatasetManager(folder);
    dataset.enableGovernance(
        TrainingGovernance.Limits.defaults(), new OnlineClassifier(16), new OnlineClassifier(16));
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var first =
        dataset.trainingTask(
            ledger -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException();
              }
              return 1;
            });
    assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
    var pending = new ArrayList<java.util.concurrent.CompletableFuture<Integer>>();
    for (int i = 0; i < 128; i++)
      pending.add(dataset.trainingTask(ledger -> ledger.recent(1).size()));
    assertTrue(dataset.trainingTask(ledger -> 0).isCompletedExceptionally());
    var close = java.util.concurrent.CompletableFuture.runAsync(dataset::close);
    release.countDown();
    close.get(10, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(1, first.join());
    for (var operation : pending) assertEquals(0, operation.join());
    assertTrue(dataset.trainingTask(ledger -> 0).isCompletedExceptionally());
  }

  @Test
  void stoppedWorkerRejectsOperationsWithoutHanging() {
    var dataset = new DatasetManager(folder);
    dataset.enableGovernance(
        TrainingGovernance.Limits.defaults(), new OnlineClassifier(16), new OnlineClassifier(16));
    dataset.close();
    assertTrue(
        dataset
            .submitSample(sample(0, DatasetManager.LabelSource.STAFF, "session", "26.2"))
            .isCompletedExceptionally());
  }

  @Test
  void provenanceSurvivesPromotionAndRejectsChangedBytes() throws Exception {
    Path source = folder.resolve("source.onnx"),
        candidate = folder.resolve("candidate.onnx"),
        live = folder.resolve("live.onnx");
    Files.writeString(source, "model");
    ModelProvenance.copy(source, candidate, 7);
    ModelProvenance.copy(candidate, live);
    assertEquals(7, ModelProvenance.revision(live));
    Files.writeString(live, "changed");
    assertThrows(java.io.IOException.class, () -> ModelProvenance.revision(live));
  }

  static class DatasetClose implements AutoCloseable {
    final DatasetManager dataset;

    DatasetClose(Path folder, OnlineClassifier click, OnlineClassifier aim) {
      dataset = new DatasetManager(folder);
      dataset.enableGovernance(TrainingGovernance.Limits.defaults(), click, aim);
    }

    @Override
    public void close() {
      dataset.close();
    }
  }
}
