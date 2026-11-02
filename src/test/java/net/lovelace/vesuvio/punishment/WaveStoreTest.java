package net.lovelace.vesuvio.punishment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaveStoreTest {
  @TempDir Path root;

  @Test
  void interruptedExecutionRequiresReviewInsteadOfRepeating() throws Exception {
    var file = root.resolve("wave.db");
    try (var store = new WaveStore(file)) {
      store.add(
          new WaveStore.Entry("one", "uuid", "Ada123", "ban Ada123", "Причина", 1, -1, "QUEUED"));
      assertTrue(store.transition("one", "QUEUED", "APPLYING"));
      assertFalse(store.transition("one", "QUEUED", "APPLYING"));
    }
    try (var store = new WaveStore(file)) {
      assertEquals("REVIEW", store.list().getFirst().state());
      assertFalse(store.edit("one", "новая", 60));
    }
  }

  @Test
  void staffRemovalAndEditsPersistAndCannotModifyAppliedPunishment() throws Exception {
    var file = root.resolve("wave.db");
    try (var store = new WaveStore(file)) {
      store.add(
          new WaveStore.Entry("two", "uuid", "Ada123", "ban Ada123", "Причина", 1, -1, "QUEUED"));
      assertTrue(store.edit("two", "Пересмотр", 3600));
      assertTrue(store.transition("two", "QUEUED", "REMOVED"));
      assertFalse(store.transition("two", "QUEUED", "APPLYING"));
      assertThrows(IllegalArgumentException.class, () -> store.edit("two", "", 1));
    }
    try (var store = new WaveStore(file)) {
      var entry = store.list().getFirst();
      assertEquals(3600, entry.duration());
      assertEquals("REMOVED", entry.state());
    }
  }
}
