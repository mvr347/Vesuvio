package net.lovelace.vesuvio.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.engine.TransactionManager;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Netty-thread listener that closes the latency-compensation loop.
 *
 * <p>Two directions:
 * <ul>
 *   <li><b>Inbound</b> Pong / Window Confirmation - the client echoing a transaction we sent.
 *       Feeds {@link TransactionManager}, which turns it into an unspoofable round-trip time and
 *       an ordering point for server-side events. Our own transactions are cancelled so the rest
 *       of the server never sees a confirmation for a window it did not open.</li>
 *   <li><b>Outbound</b> Entity Velocity - the exact knockback vector the server is about to push
 *       onto the player. Capturing it here (rather than from {@code PlayerVelocityEvent}, which
 *       only says "some velocity happened") gives the Velocity check the actual magnitude to
 *       compare the player's subsequent movement against, which is what makes anti-knockback
 *       measurable instead of merely suspicious.</li>
 * </ul>
 *
 * Author: Lovelace
 */
public final class TransactionPacketListener extends PacketListenerAbstract {

    private final UserDataManager userDataManager;
    private final TransactionManager transactionManager;

    public TransactionPacketListener(UserDataManager userDataManager, TransactionManager transactionManager) {
        super(PacketListenerPriority.LOWEST);
        this.userDataManager = userDataManager;
        this.transactionManager = transactionManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        var type = event.getPacketType();
        if (type != PacketType.Play.Client.PONG && type != PacketType.Play.Client.WINDOW_CONFIRMATION) {
            return;
        }

        UUID uuid = event.getUser() != null ? event.getUser().getUUID() : null;
        if (uuid == null) return;

        int id;
        if (type == PacketType.Play.Client.PONG) {
            id = new WrapperPlayClientPong(event).getId();
        } else {
            id = new WrapperPlayClientWindowConfirmation(event).getActionId();
        }

        if (transactionManager.onResponse(uuid, id)) {
            // It was ours - swallow it. Forwarding a confirmation the server never requested can
            // confuse inventory handling on legacy versions.
            event.setCancelled(true);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_VELOCITY) return;

        Object playerObj = event.getPlayer();
        if (!(playerObj instanceof Player player)) return;

        WrapperPlayServerEntityVelocity wrapper = new WrapperPlayServerEntityVelocity(event);
        // Only the velocity aimed at this player themselves matters; knockback the server sends
        // about other entities says nothing about this player's own movement.
        if (wrapper.getEntityId() != player.getEntityId()) return;

        UserData data = userDataManager.get(player.getUniqueId());
        if (data == null) return;

        Vector3d velocity = wrapper.getVelocity();
        long sequence = transactionManager.onServerEvent(player.getUniqueId());
        data.recordPendingVelocity(velocity.getX(), velocity.getY(), velocity.getZ(), sequence);
    }
}
