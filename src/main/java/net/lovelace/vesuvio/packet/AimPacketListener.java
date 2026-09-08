package net.lovelace.vesuvio.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.pipeline.CheckPipeline;
import org.bukkit.entity.Player;

import java.util.concurrent.Executor;

/**
 * Netty-thread packet listener for player rotation packets.
 * Tracks yaw and pitch deltas in real-time.
 *
 * Author: Lovelace
 */
public final class AimPacketListener extends PacketListenerAbstract {

    private final UserDataManager userDataManager;
    private final CheckPipeline checkPipeline;
    private final Executor virtualExecutor;

    public AimPacketListener(UserDataManager userDataManager, CheckPipeline checkPipeline, Executor virtualExecutor) {
        super(PacketListenerPriority.LOWEST);
        this.userDataManager = userDataManager;
        this.checkPipeline = checkPipeline;
        this.virtualExecutor = virtualExecutor;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        var type = event.getPacketType();

        float yaw = 0f;
        float pitch = 0f;
        boolean hasRotation = false;

        if (type == PacketType.Play.Client.PLAYER_ROTATION) {
            WrapperPlayClientPlayerRotation wrapper = new WrapperPlayClientPlayerRotation(event);
            yaw = wrapper.getYaw();
            pitch = wrapper.getPitch();
            hasRotation = true;
        } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerPositionAndRotation wrapper = new WrapperPlayClientPlayerPositionAndRotation(event);
            yaw = wrapper.getYaw();
            pitch = wrapper.getPitch();
            hasRotation = true;
        }

        if (hasRotation) {
            Player player = (Player) event.getPlayer();
            if (player == null || player.hasMetadata("NPC")) return;

            UserData data = userDataManager.getOrCreate(player);
            long now = System.nanoTime();

            // First rotation after join/spawn/teleport - establish baseline without delta
            if (!data.hasInitialRotation()) {
                data.setLastYaw(yaw);
                data.setLastPitch(pitch);
                data.setInitialRotation(true);
                return;
            }

            float prevYaw = data.getLastYaw();
            float prevPitch = data.getLastPitch();

            // Normalized shortest angular distance across 360 wrap
            float rawDeltaYaw = Math.abs(yaw - prevYaw) % 360.0f;
            final float deltaYaw = (rawDeltaYaw > 180.0f) ? (360.0f - rawDeltaYaw) : rawDeltaYaw;
            final float deltaPitch = Math.abs(pitch - prevPitch);

            data.setLastYaw(yaw);
            data.setLastPitch(pitch);

            data.getAimBuffer().addRotation(yaw, pitch, now);

            virtualExecutor.execute(() -> checkPipeline.processAim(player, data, deltaYaw, deltaPitch));
        }
    }
}
