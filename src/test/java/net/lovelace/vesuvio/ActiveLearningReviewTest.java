package net.lovelace.vesuvio;

import static org.junit.jupiter.api.Assertions.*;

import com.github.benmanes.caffeine.cache.Cache;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.lovelace.vesuvio.check.selflearning.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ActiveLearningReviewTest {
  @TempDir Path folder;

  @SuppressWarnings("unchecked")
  private ActiveLearning sample() throws Exception {
    var learning = new ActiveLearning();
    var field = ActiveLearning.class.getDeclaredField("pendingReviews");
    field.setAccessible(true);
    ((Cache<String, ActiveLearning.ReviewSample>) field.get(learning))
        .put(
            "S101",
            new ActiveLearning.ReviewSample(
                "S101",
                UUID.randomUUID(),
                "Test",
                new float[] {1, 2, 3},
                0.65,
                System.currentTimeMillis(),
                "aim"));
    return learning;
  }

  @Test
  void invalidVerdictDoesNotConsumeSample() throws Exception {
    var learning = sample();
    assertThrows(
        IllegalArgumentException.class,
        () -> learning.submitVerdict("S101", "typo", "Staff", null, null, null));
    assertNotNull(learning.getSample("S101"));
  }

  @Test
  void concurrentReviewsTrainExactlyOnce() throws Exception {
    var learning = sample();
    var classifier = new OnlineClassifier(3);
    var dataset = new DatasetManager(folder);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 30; i++)
        results.add(
            workers.submit(
                () -> learning.submitVerdict("S101", "legit", "Staff", classifier, dataset, null)));
      int wins = 0;
      for (var result : results) if (result.get()) wins++;
      assertEquals(1, wins);
      assertNull(learning.getSample("S101"));
      assertEquals(1, classifier.getTrainedSamplesCount());
    } finally {
      dataset.close();
    }
  }

  @Test
  void unavailableManagementFailsExplicitlyAndHasNoWebListener() {
    var api = new net.lovelace.vesuvio.api.VesuvioAPIImpl(null, null, 50, null, null, null, null);
    assertEquals(2, api.getIntegrationVersion());
    assertTrue(api.getCapabilities().contains("reviews"));
    assertTrue(api.getPendingReviews(1000).isEmpty());
    assertThrows(CompletionException.class, () -> api.reloadModels().join());
    assertThrows(
        CompletionException.class, () -> api.submitReview("S101", "legit", "Staff").join());
  }
}
