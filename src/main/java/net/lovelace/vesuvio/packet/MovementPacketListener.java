package net.lovelace.vesuvio.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.pipeline.CheckPipeline;
import org.bukkit.entity.Player;

import java.util.concurrent.Executor;

/**
 * Netty-thread packet listener for player movement and position packets.
 * Feeds position deltas into the movement check engine (Fly, Speed, NoFall, Timer).
 *
 * Author: Lovelace
 */
public final class MovementPacketListener extends PacketListenerAbstract {

    private final UserDataManager userDataManager;
    private final CheckPipeline checkPipeline;
    private final Executor virtualExecutor;

    public MovementPacketListener(UserDataManager userDataManager, CheckPipeline checkPipeline, Executor virtualExecutor) {
        super(PacketListenerPriority.LOWEST);
        this.userDataManager = userDataManager;
        this.checkPipeline = checkPipeline;
        this.virtualExecutor = virtualExecutor;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        var type = event.getPacketType();

        double x = 0;
        double y = 0;
        double z = 0;
        boolean onGround = false;
        boolean hasPos = false;

        if (type == PacketType.Play.Client.PLAYER_POSITION) {
            WrapperPlayClientPlayerPosition wrapper = new WrapperPlayClientPlayerPosition(event);
            var pos = wrapper.getPosition();
            x = pos.getX();
            y = pos.getY();
            z = pos.getZ();
            onGround = wrapper.isOnGround();
            hasPos = true;
        } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerPositionAndRotation wrapper = new WrapperPlayClientPlayerPositionAndRotation(event);
            var pos = wrapper.getPosition();
            x = pos.getX();
            y = pos.getY();
            z = pos.getZ();
            onGround = wrapper.isOnGround();
            hasPos = true;
        } else if (type == PacketType.Play.Client.PLAYER_FLYING) {
            WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
            onGround = wrapper.isOnGround();
            hasPos = false;
        } else {
            return;
        }

        Player player = (Player) event.getPlayer();
        if (player == null || player.hasMetadata("NPC")) return;

        UserData data = userDataManager.getOrCreate(player);

        final double finalX = x;
        final double finalY = y;
        final double finalZ = z;
        final boolean finalOnGround = onGround;
        final boolean finalHasPos = hasPos;

        virtualExecutor.execute(() -> checkPipeline.processMovement(player, data, finalX, finalY, finalZ, finalOnGround, finalHasPos));
    }
}
