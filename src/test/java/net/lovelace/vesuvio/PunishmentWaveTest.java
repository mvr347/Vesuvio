package net.lovelace.vesuvio;

import net.lovelace.vesuvio.punishment.PunishmentWaveManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PunishmentWaveTest {

    @Test
    public void testQueuedPunishmentStructure() {
        UUID uuid = UUID.randomUUID();
        PunishmentWaveManager.QueuedPunishment p = new PunishmentWaveManager.QueuedPunishment(
                uuid,
                "CheaterX",
                "ban CheaterX 7d Unfair Advantage",
                "Autoclicker detection",
                System.currentTimeMillis()
        );

        assertEquals(uuid, p.uuid());
        assertEquals("CheaterX", p.username());
        assertTrue(p.command().contains("ban CheaterX"));
        assertTrue(p.timestamp() > 0);
    }
}
