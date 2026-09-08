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

            // Zero-allocation buffer update on Netty thread
            data.getClickBuffer().addClick(now, false);

            // Offload full detection pipeline to virtual thread
            virtualExecutor.execute(() -> checkPipeline.processClick(player, data));

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
