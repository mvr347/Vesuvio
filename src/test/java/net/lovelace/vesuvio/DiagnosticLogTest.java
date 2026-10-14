package net.lovelace.vesuvio;

import net.lovelace.vesuvio.diagnostics.DiagnosticLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticLogTest {
    @TempDir Path directory;

    @Test void stopDrainsRecordsAndEachSessionHasItsOwnFile() throws Exception {
        try (var log = new DiagnosticLog(directory)) {
            Path first = log.start("Сервер: тест");
            assertEquals(first, log.start("Повторная команда"));
            log.record("Первое срабатывание");
            log.record("Второе срабатывание");
            assertEquals(first, log.stop().get(5, TimeUnit.SECONDS));
            String contents = Files.readString(first);
            assertTrue(contents.contains("Сервер: тест"));
            assertTrue(contents.indexOf("Первое срабатывание") < contents.indexOf("Второе срабатывание"));
            assertTrue(contents.contains("КОНЕЦ СЕССИИ"));
            assertEquals(0, log.status().dropped());
            assertFalse(log.status().enabled());
            assertTrue(log.status().finished());
            Path second = log.start("Новая сессия");
            assertNotEquals(first, second);
            log.record(first, "Запоздавшее событие предыдущей сессии");
            log.record(second, "Новое событие");
            log.stop().get(5, TimeUnit.SECONDS);
            assertFalse(Files.readString(second).contains("Запоздавшее"));
            assertTrue(Files.readString(second).contains("Новое событие"));
            assertEquals(contents, Files.readString(first));
        }
    }

    @Test void sizeLimitStopsCaptureWithoutDeletingOldRecords() throws Exception {
        try (var log = new DiagnosticLog(directory, 4096, 16)) {
            Path path = log.start("Заголовок");
            log.record("Я".repeat(4000));
            log.stop().get(5, TimeUnit.SECONDS);
            assertTrue(Files.size(path) <= 4096);
            assertTrue(Files.readString(path).contains("Заголовок"));
            assertTrue(log.status().error().contains("лимит"));
            assertTrue(log.status().dropped() > 0);
        }
    }

    @Test void concurrentProducersDoNotCorruptOrLoseAcceptedRecords() throws Exception {
        try (var log = new DiagnosticLog(directory, 1024 * 1024, 512);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Path path = log.start("Параллельный тест");
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++) {
                int index = i;
                futures.add(workers.submit(() -> log.record("Событие-" + index + "-конец")));
            }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            log.stop().get(5, TimeUnit.SECONDS);
            String contents = Files.readString(path);
            for (int i = 0; i < 100; i++) assertTrue(contents.contains("Событие-" + i + "-конец"));
            assertEquals(101, log.status().written());
            assertEquals(0, log.status().dropped());
        }
    }

    @Test void directoryBudgetRefusesNewSessionWithoutDeletingFiles() throws Exception {
        Path old = directory.resolve("diagnostic-old.log");
        Files.writeString(old, "x".repeat(13000));
        try (var log = new DiagnosticLog(directory, 4096, 8)) {
            assertThrows(java.io.IOException.class, () -> log.start("Новая сессия"));
            assertEquals(13000, Files.size(old));
            assertFalse(log.enabled());
        }
    }

    @Test void disabledLoggerDoesNotCreateAnyFiles() throws Exception {
        try (var log = new DiagnosticLog(directory)) {
            log.record("Не должно записаться");
            assertNull(log.stop().get(5, TimeUnit.SECONDS));
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        }
    }
}
