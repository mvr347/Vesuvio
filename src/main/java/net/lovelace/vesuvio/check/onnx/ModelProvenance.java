package net.lovelace.vesuvio.check.onnx;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/**
 * A model's label revision follows it through shadow promotion and rollback, bound to its bytes.
 */
public final class ModelProvenance {
  private ModelProvenance() {}

  private static Path marker(Path model) {
    return model.resolveSibling(model.getFileName() + ".labels");
  }

  private static String digest(Path model) throws IOException {
    try (var input = Files.newInputStream(model)) {
      var hash = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  public static long revision(Path model) throws IOException {
    if (!Files.exists(marker(model)))
      return -1; // Legacy/admin-supplied model; no claim of reviewed provenance.
    String[] parts = Files.readString(marker(model)).trim().split(":", -1);
    if (parts.length != 2 || !parts[1].equals(digest(model)))
      throw new IOException("Модель не соответствует журналу обучающих меток");
    try {
      return Long.parseLong(parts[0]);
    } catch (NumberFormatException error) {
      throw new IOException("Неверная версия меток", error);
    }
  }

  public static void copy(Path source, Path destination, long revision) throws IOException {
    // Publish the marker first: an overlapping loader rejects the old bytes rather than trusts
    // them.
    Path temporary =
        Files.createTempFile(destination.toAbsolutePath().getParent(), "model-labels-", ".tmp");
    try {
      Files.writeString(temporary, revision + ":" + digest(source));
      Files.move(
          temporary,
          marker(destination),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public static void copy(Path source, Path destination) throws IOException {
    copy(source, destination, revision(source));
  }

  public static void delete(Path model) throws IOException {
    Files.deleteIfExists(marker(model));
  }
}
