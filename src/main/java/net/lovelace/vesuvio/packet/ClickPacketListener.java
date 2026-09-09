package net.lovelace.vesuvio.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.pipeline.CheckPipeline;
import org.bukkit.entity.Player;

import java.util.concurrent.Executor;

/**
 * Netty-thread packet listener for player arm animations and entity interactions.
 * Ingests click intervals into GC-free ring buffers with zero allocations,
 * and asynchronously offloads checks to virtual threads.
 *
 * <h2>Animation packets are not all combat clicks</h2>
 * The client sends the same arm-swing Animation packet for a left-click attack, a left-click on a
 * block (mining), and a bare left-click on air - there is nothing in the packet itself that says
 * which one just happened. The click ring buffer exists specifically to fingerprint <em>combat</em>
 * click rhythm (autoclicker/macro/drag-click detection), so feeding it from mining swings pollutes
 * it with a completely different signal: holding left-click to break a block produces its own very
 * regular, often very fast interval pattern that has nothing to do with how a player clicks in a
 * fight, and {@link net.lovelace.vesuvio.check.statistical.StatisticalClickCheck} cannot tell the
 * two apart once they are mixed into the same buffer.
 *
 * <p>Animation-driven clicks are therefore only recorded while {@link UserData#isInCombat()} is
 * true (a rolling window kept alive by real attacks - see {@link UserData#recordCombatAction()}).
 * That keeps genuine near-misses during a fight (swinging at a target that is briefly out of
 * range, or an autoclicker that does not care whether it connects) in the buffer, while a player
 * who is simply mining - not in combat at all - never feeds it. {@code setLastSwingNanos} is
 * updated unconditionally regardless of combat state: {@link
 * net.lovelace.vesuvio.check.protocol.BadPacketsCheck#checkNoSwing} only needs to know that some
 * swing happened recently before an attack, mining included.
 *
 * Author: Lovelace
 */
public final class ClickPacketListener extends PacketListenerAbstract {

    private final UserDataManager userDataManager;
    private final CheckPipeline checkPipeline;
    private final Executor virtualExecutor;

    public ClickPacketListener(UserDataManager userDataManager, CheckPipeline checkPipeline, Executor virtualExecutor) {
        super(PacketListenerPriority.LOWEST);
        this.userDataManager = userDataManager;
        this.checkPipeline = checkPipeline;
        this.virtualExecutor = virtualExecutor;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        var type = event.getPacketType();

        if (type == PacketType.Play.Client.ANIMATION) {
            Player player = (Player) event.getPlayer();
            if (player == null || player.hasMetadata("NPC")) return;

            UserData data = userDataManager.getOrCreate(player);
            long now = System.nanoTime();

            data.setLastSwingNanos(now);

            // Only a swing that happens around real combat belongs in the click-rhythm buffer -
            // see the class doc. A swing while mining, eating, or idly clicking air must not
            // touch it.
            if (data.isInCombat()) {
                // Zero-allocation buffer update on Netty thread
                data.getClickBuffer().addClick(now, false);

                // Offload full detection pipeline to virtual thread
                virtualExecutor.execute(() -> checkPipeline.processClick(player, data));
            }

        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            Player player = (Player) event.getPlayer();
            if (player == null || player.hasMetadata("NPC")) return;

            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
            if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                UserData data = userDataManager.getOrCreate(player);
                long now = System.nanoTime();
                int targetEntityId = wrapper.getEntityId();

                data.recordCombatAction();
                data.getClickBuffer().addClick(now, true);

                virtualExecutor.execute(() -> {
                    checkPipeline.processAttack(player, targetEntityId, data);
                    checkPipeline.processClick(player, data);
                });
            }
        }
    }
}
