package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.engine.StateJanitor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class StabilityTest {

    @Test
    void janitorHandsTheOnlineSetToEveryHolderAndSurvivesABrokenOne() {
        UUID online = UUID.randomUUID();
        List<Set<UUID>> seen = new ArrayList<>();
        List<Consumer<Set<UUID>>> sinks = List.of(
                ids -> { throw new IllegalStateException("broken holder"); },
                seen::add);
        new StateJanitor(() -> Set.of(online), sinks).run();
        assertEquals(1, seen.size(), "a holder after the broken one is still swept");
        assertEquals(Set.of(online), seen.get(0));
    }

    @Test
    void userDataManagerDropsEveryoneWhoIsNotOnline() {
        UserDataManager manager = new UserDataManager();
        UUID stays = UUID.randomUUID();
        UUID leaves = UUID.randomUUID();
        manager.getOrCreate(stays, "stays");
        manager.getOrCreate(leaves, "leaves"); // e.g. recreated by a task that reached it after PlayerQuitEvent
        Set<UUID> online = new HashSet<>();
        online.add(stays);
        manager.retainOnline(online);
        assertNotNull(manager.get(stays));
        assertNull(manager.get(leaves));
        assertEquals(1, manager.getOnlineCount());
    }
}
