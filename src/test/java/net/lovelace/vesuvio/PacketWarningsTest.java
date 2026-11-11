package net.lovelace.vesuvio;

import net.lovelace.vesuvio.packet.PacketWarnings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PacketWarningsTest {

    @Test
    void warnsAtMostOncePerTenSecondsAndReportsHowManyWereHidden() {
        Logger logger = Logger.getLogger("Vesuvio");
        List<String> lines = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { lines.add(r.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            PacketWarnings w = new PacketWarnings();
            for (int i = 0; i < 100; i++) w.warn("queue full", null);
            assertEquals(1, lines.size(), "100 packets, one line");
        } finally {
            logger.removeHandler(handler);
        }
    }
}
