package net.lovelace.vesuvio.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
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
        if (!checkPipeline.isAnticheatEnabled()) return;

        var type = event.getPacketType();

        double x = 0;
        double y = 0;
        double z = 0;
        boolean onGround = false;
        boolean hasPos = false;
        float yaw = 0f;
        float pitch = 0f;
        boolean hasRot = false;
        String packetName = "UNKNOWN";

        try {
            if (type == PacketType.Play.Client.PLAYER_POSITION) {
                packetName = "PLAYER_POSITION";
                WrapperPlayClientPlayerPosition wrapper = new WrapperPlayClientPlayerPosition(event);
                var pos = wrapper.getPosition();
                x = pos.getX();
                y = pos.getY();
                z = pos.getZ();
                onGround = wrapper.isOnGround();
                hasPos = true;
            } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                packetName = "PLAYER_POSITION_AND_ROTATION";
                WrapperPlayClientPlayerPositionAndRotation wrapper = new WrapperPlayClientPlayerPositionAndRotation(event);
                var pos = wrapper.getPosition();
                x = pos.getX();
                y = pos.getY();
                z = pos.getZ();
                yaw = wrapper.getYaw();
                pitch = wrapper.getPitch();
                hasRot = true;
                onGround = wrapper.isOnGround();
                hasPos = true;
            } else if (type == PacketType.Play.Client.PLAYER_FLYING) {
                packetName = "PLAYER_FLYING";
                WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
                onGround = wrapper.isOnGround();
                hasPos = false;
            } else if (type == PacketType.Play.Client.PLAYER_ROTATION) {
                packetName = "PLAYER_ROTATION";
                WrapperPlayClientPlayerRotation wrapper = new WrapperPlayClientPlayerRotation(event);
                yaw = wrapper.getYaw();
                pitch = wrapper.getPitch();
                hasRot = true;
                onGround = wrapper.isOnGround();
                hasPos = false;
            } else {
                return;
            }
        } catch (Exception e) {
            Player player = (Player) event.getPlayer();
            String playerName = (player != null) ? player.getName() : "unknown";
            System.err.println("[Vesuvio] Не удалось разобрать " + packetName + " от " + playerName + ": " + e.getMessage());
            e.printStackTrace();
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
        final long packetReceiptNanos = System.nanoTime();
        final String finalPacketName = packetName;
        final boolean finalHasRot = hasRot;
        final float finalYaw = yaw;
        var capturedPhysics = data.getPhysicsFrame();
        net.lovelace.vesuvio.engine.physics.ClientWorld.Capture capturedWorld = null;
        if (data.isPhysicsObserved() && hasPos) {
            try {
                var world = data.clientWorld();
                synchronized (world) {
                    world.synchronizeDetection(checkPipeline.getDetectionEpoch());
                    capturedWorld = world.capture(capturedPhysics,
                            new net.lovelace.vesuvio.engine.physics.MotionVector(x, y, z), packetReceiptNanos);
                }
            } catch (IllegalArgumentException invalidCoordinates) {
                capturedPhysics = net.lovelace.vesuvio.engine.physics.PhysicsFrame.unknown("INVALID_INPUT");
            }
        }
        final var physicsAttributes = capturedPhysics;
        final var physicsWorld = capturedWorld;
        final long physicsEpoch = data.getPhysicsObservationEpoch();

        // Deliberately evaluated here, on the netty thread, and not inside the virtual-thread task
        // below: this is a comparison against the PREVIOUS packet's contents, so it is only correct
        // while the packets are still in the order the client sent them. The check tasks are
        // dispatched to virtual threads and can interleave; this cannot.
        final boolean clientHadSomethingToReport =
                data.noteReportedClientState(hasPos, x, y, z, hasRot, yaw, pitch, onGround);

        try {
            checkPipeline.submitPacketCheck(data, virtualExecutor, () -> {
                if (data.isPhysicsObserved() && physicsEpoch == data.getPhysicsObservationEpoch()) {
                    if (data.hasRecentTeleport() || data.hasRecentVelocity() || data.hasRecentGameModeChange()) {
                        data.physicsSession().reset("SERVER_TRANSITION");
                    } else {
                        try {
                            var position = finalHasPos ? new net.lovelace.vesuvio.engine.physics.MotionVector(finalX, finalY, finalZ) : null;
                            var physicsFrame = physicsWorld == null ? physicsAttributes : physicsWorld.build(
                                    id -> net.lovelace.vesuvio.engine.physics.PacketBlockGeometry.resolve(physicsWorld.protocol(), id));
                            data.physicsSession().observe(new net.lovelace.vesuvio.engine.physics.PhysicsSession.Packet(
                                    position, finalOnGround, finalHasRot, finalYaw, packetReceiptNanos), physicsFrame, data.getMovementResetNanos());
                        } catch (IllegalArgumentException invalidInput) {
                            data.physicsSession().reset("INVALID_INPUT");
                        }
                    }
                }
                checkPipeline.processMovement(player, data, finalX, finalY, finalZ, finalOnGround, finalHasPos, packetReceiptNanos, clientHadSomethingToReport);
            });
        } catch (Exception e) {
            System.err.println("[Vesuvio] Не удалось поставить проверку движения в очередь для " + player.getName() + " (" + finalPacketName + "): " + e.getMessage());
            e.printStackTrace();
        }
    }
}
