package net.lovelace.vesuvio.diagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** A bounded asynchronous session log. Never deletes previous sessions or blocks flag producers. */
public final class DiagnosticLog implements AutoCloseable {
    private static final long DEFAULT_LIMIT = 64L * 1024 * 1024;
    private final Path directory;
    private final long fileLimit;
    private final int queueCapacity;
    private final boolean virtual;
    private volatile Session session;

    public record Status(boolean enabled, boolean finished, Path path, long written, long dropped, String error) {}

    public DiagnosticLog(Path directory) { this(directory, DEFAULT_LIMIT, 256, true); }
    public DiagnosticLog(Path directory, boolean virtual) { this(directory, DEFAULT_LIMIT, 256, virtual); }
    public DiagnosticLog(Path directory, long fileLimit, int queueCapacity) { this(directory, fileLimit, queueCapacity, true); }
    private DiagnosticLog(Path directory, long fileLimit, int queueCapacity, boolean virtual) {
        this.virtual = virtual;
        if (fileLimit < 4096 || queueCapacity < 1) throw new IllegalArgumentException("Недопустимые лимиты журнала");
        this.directory = directory;
        this.fileLimit = fileLimit;
        this.queueCapacity = queueCapacity;
    }

    public synchronized Path start(String metadata) throws IOException {
        if (session != null && !session.done.isDone()) {
            if (session.accepting) return session.path;
            throw new IOException("Предыдущий журнал ещё сохраняется. Повторите через секунду.");
        }
        Files.createDirectories(directory);
        long existing = 0;
        try (var files = Files.newDirectoryStream(directory, "diagnostic-*.log")) {
            for (Path file : files) existing += Files.size(file);
        }
        if (existing > 4 * fileLimit - fileLimit)
            throw new IOException("Лимит каталога диагностики исчерпан. Скачайте и перенесите старые журналы.");
        Path path = directory.resolve("diagnostic-" + Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID() + ".log");
        OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Session next = new Session(path);
        next.queue.add("НАЧАЛО СЕССИИ\n" + metadata);
        session = next;
        Thread.Builder builder = virtual ? Thread.ofVirtual() : Thread.ofPlatform().daemon(true);
        next.worker = builder.name("Vesuvio-DiagnosticLog").unstarted(() -> write(next, output));
        next.worker.setContextClassLoader(getClass().getClassLoader());
        next.worker.start();
        return path;
    }

    public boolean enabled() { Session current = session; return current != null && current.accepting; }

    public void record(String record) { record(null, record); }

    /** expectedPath fences a context assembled before an off/on session transition. */
    public void record(Path expectedPath, String record) {
        Session current = session;
        if (current == null || (expectedPath != null && !current.path.equals(expectedPath))) return;
        synchronized (current) {
            if (!current.accepting) return;
            String bounded = record.length() > 32768 ? record.substring(0, 32768) + "\n[Запись сокращена по лимиту размера]" : record;
            if (!current.queue.offer(bounded)) current.dropped.incrementAndGet();
        }
    }

    public CompletableFuture<Path> stop() {
        Session current = session;
        if (current == null) return CompletableFuture.completedFuture(null);
        synchronized (current) { current.accepting = false; }
        return current.done;
    }

    public Status status() {
        Session current = session;
        return current == null ? new Status(false, true, null, 0, 0, "")
                : new Status(current.accepting, current.done.isDone(), current.path,
                    current.written.get(), current.dropped.get(), current.error);
    }

    private void write(Session current, OutputStream output) {
        long bytes = 0;
        try (output) {
            while (current.accepting || !current.queue.isEmpty()) {
                String record = current.queue.poll(200, TimeUnit.MILLISECONDS);
                if (record == null) continue;
                byte[] encoded = ("\n===== " + Instant.now() + " =====\n" + record + "\n").getBytes(StandardCharsets.UTF_8);
                if (bytes + encoded.length > fileLimit - 1024) {
                    synchronized (current) {
                        current.accepting = false;
                        current.dropped.addAndGet(1L + current.queue.size());
                        current.queue.clear();
                    }
                    current.error = "Достигнут лимит файла. Запись остановлена.";
                    break;
                }
                output.write(encoded);
                output.flush();
                bytes += encoded.length;
                current.written.incrementAndGet();
            }
            output.write(("\n===== КОНЕЦ СЕССИИ =====\nЗаписей: " + current.written.get()
                    + "\nПропущено: " + current.dropped.get() + "\nСостояние: " + current.error + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            synchronized (current) {
                current.accepting = false;
                current.dropped.addAndGet(1L + current.queue.size());
                current.queue.clear();
            }
            current.error = "Ошибка записи: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
            java.util.logging.Logger.getLogger("Vesuvio").log(java.util.logging.Level.WARNING, "Не удалось сохранить диагностический журнал", failure);
        } finally {
            synchronized (current) { current.accepting = false; }
            current.done.complete(current.path);
        }
    }

    @Override public void close() {
        try { stop().get(3, TimeUnit.SECONDS); }
        catch (Exception failure) {
            java.util.logging.Logger.getLogger("Vesuvio").warning("Диагностический журнал не успел завершить запись: " + failure);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private final class Session {
        final Path path;
        final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(queueCapacity);
        final CompletableFuture<Path> done = new CompletableFuture<>();
        final AtomicLong written = new AtomicLong(), dropped = new AtomicLong();
        volatile boolean accepting = true;
        volatile String error = "";
        Thread worker;
        Session(Path path) { this.path = path; }
    }
}
