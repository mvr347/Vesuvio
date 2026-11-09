package net.lovelace.vesuvio.check.selflearning;

import java.io.IOException;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Isolated governance settings; invalid files fail closed instead of admitting all labels. */
final class TrainingSettings {
  static TrainingGovernance.Limits load(Path directory) {
    Path file = directory.resolve("training.yml");
    try {
      if (!Files.exists(file))
        try (var in = TrainingSettings.class.getResourceAsStream("/training.yml")) {
          if (in == null) throw new IOException("Нет training.yml в JAR");
          Files.copy(in, file);
        }
      var config = new YamlConfiguration();
      config.load(file.toFile());
      return new TrainingGovernance.Limits(
          config.getInt("per-player-domain-day", 50),
          config.getLong("window-seconds", 15) * 1000,
          config.getInt("max-records", 100000),
          config.getInt("per-player-domain-training", 100));
    } catch (Exception error) {
      throw new IllegalStateException(
          "Проверьте training.yml: контроль обучения не запущен", error);
    }
  }
}
