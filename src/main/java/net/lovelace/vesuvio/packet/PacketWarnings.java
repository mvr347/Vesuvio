package net.lovelace.vesuvio.packet;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Rate-limited warnings for the packet listeners. When the check queue overflows or a packet cannot be parsed, it does so for
 * every packet of every player: printing a stack trace each time floods the log and slows the netty thread, which makes the
 * overflow worse. One line per 10 seconds is enough to notice it.
 */
public final class PacketWarnings {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio");
    private static final long INTERVAL_MILLIS = 10_000L;

    private final AtomicLong lastWarnMillis = new AtomicLong(0L);
    private final AtomicLong suppressed = new AtomicLong(0L);

    /** Logs at most once per 10 s; the count of silenced warnings is appended to the next line that gets through. */
    public void warn(String message, Throwable cause) {
        warn(message, cause, System.currentTimeMillis());
    }

    void warn(String message, Throwable cause, long nowMillis) {
        long last = lastWarnMillis.get();
        if (last != 0L && nowMillis - last < INTERVAL_MILLIS || !lastWarnMillis.compareAndSet(last, nowMillis)) {
            suppressed.incrementAndGet();
            return;
        }
        long skipped = suppressed.getAndSet(0L);
        String text = "[Vesuvio] " + message + (cause != null ? ": " + cause : "")
                + (skipped > 0 ? " (ещё " + skipped + " таких сообщений за последние 10 с скрыто)" : "");
        LOGGER.warning(text);
    }
}
