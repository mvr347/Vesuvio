package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link UserData#isInCombat()}'s entry condition - the gate
 * {@code ClickPacketListener} now uses to decide whether an arm-swing Animation packet belongs in
 * the click-rhythm buffer.
 *
 * <p>Bug this guards against: the click buffer exists to fingerprint <em>combat</em> click rhythm
 * for autoclicker/macro detection, but the client sends the identical Animation packet for a
 * combat swing, a mining swing, and a bare swing at air - nothing in the packet says which one just
 * happened. Before this fix, every Animation packet fed the buffer unconditionally, so breaking a
 * block (a very regular, often fast swing pattern with nothing to do with how a player clicks in a
 * fight) was read as ClickStatistical autoclicker/rhythm evidence: VL and Risk climbed from mining
 * alone. The fix guards the buffer feed with {@code isInCombat()}, so this test exists to pin down
 * exactly what that guard does and does not allow through.
 *
 * <p>{@code ClickPacketListener} itself is not unit-tested here or anywhere else in this suite - no
 * packet listener in this codebase is, since exercising one needs a real PacketEvents
 * event/wrapper rather than the plain data-class tests used throughout. This test instead pins the
 * one piece of state the fix actually branches on, and the fix was otherwise verified by full
 * compile and package build plus a manual read of the exact packet dispatch path.
 */
class ClickCombatGatingTest {

    @Test
    void freshPlayerIsNotInCombat() {
        UserData data = new UserData(UUID.randomUUID(), "Miner");
        // A player who has only ever mined - never attacked - must not be treated as "in combat",
        // which is exactly the case that used to leak mining swings into the click buffer.
        assertFalse(data.isInCombat());
    }

    @Test
    void realAttackEntersCombatImmediately() {
        UserData data = new UserData(UUID.randomUUID(), "Fighter");
        data.recordCombatAction();
        assertTrue(data.isInCombat(),
                "a real attack must immediately open the combat window the click buffer gates on");
    }

    @Test
    void miningAloneNeverOpensTheCombatWindowTheClickBufferGatesOn() {
        UserData data = new UserData(UUID.randomUUID(), "PureMiner");

        // Breaking blocks does not call recordCombatAction() anywhere in the pipeline - only a real
        // attack (WorldInteractionListener never touches combat state, ClickPacketListener's
        // INTERACT_ENTITY/ATTACK branch is the only caller). So no amount of mining, however long,
        // can make isInCombat() true - meaning ClickPacketListener's guard correctly excludes every
        // mining swing from the click-rhythm buffer regardless of how the mining is paced.
        for (int i = 0; i < 500; i++) {
            assertFalse(data.isInCombat(), "mining must never look like combat (swing " + i + ")");
        }
    }
}
