package net.lovelace.vesuvio.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.engine.physics.ClientChunk;
import net.lovelace.vesuvio.engine.physics.ClientWorld;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Tracks only packets sent to a selected recipient. No Bukkit block/chunk reads. */
public final class ClientWorldPacketListener extends PacketListenerAbstract {
    private final UserDataManager users;
    private final ConfigManager config;
    public ClientWorldPacketListener(UserDataManager users, ConfigManager config) {
        super(PacketListenerPriority.MONITOR);
        this.users = users; this.config = config;
    }

    @Override public void onPacketSend(PacketSendEvent event) {
        var type = event.getPacketType();
        boolean worldReset = type == PacketType.Play.Server.RESPAWN || type == PacketType.Play.Server.JOIN_GAME;
        if (!worldReset && type != PacketType.Play.Server.CHUNK_DATA && type != PacketType.Play.Server.BLOCK_CHANGE
                && type != PacketType.Play.Server.MULTI_BLOCK_CHANGE && type != PacketType.Play.Server.UNLOAD_CHUNK
                && type != PacketType.Play.Server.ACKNOWLEDGE_BLOCK_CHANGES) return;
        if (event.isCancelled() || event.getUser() == null || event.getUser().getUUID() == null) return;
        UserData data = users.get(event.getUser().getUUID());
        if (data == null || !data.isPhysicsObserved()) return;
        var world = data.clientWorld();
        world.synchronizeDetection(config.runtime().detectionEpoch());
        if (!config.isAnticheatEnabled()) return;
        long observation = data.getPhysicsObservationEpoch(), detection = config.runtime().detectionEpoch();
        Runnable reset = () -> world.reset("DIMENSION_CHANGE");
        if (worldReset) {
            afterSend(event, data, observation, detection, null, 0, reset);
            return;
        }
        // No guessing across ViaVersion/legacy block palettes or collision profiles.
        var version = event.getServerVersion().toClientVersion();
        if (version != ClientVersion.V_26_2 || event.getUser().getClientVersion() != version) {
            world.reset("UNSUPPORTED_PROTOCOL"); return;
        }
        try {
            int minHeight = event.getUser().getMinWorldHeight();
            int height = event.getUser().getTotalWorldHeight();
            if ((minHeight & 15) != 0 || height <= 0 || (height & 15) != 0 || height / 16 > 64)
                throw new IllegalArgumentException("Неподдерживаемая высота измерения");
            ClientWorld.Change change;
            if (type == PacketType.Play.Server.ACKNOWLEDGE_BLOCK_CHANGES) {
                change = new ClientWorld.PredictionAck(new WrapperPlayServerAcknowledgeBlockChanges(event).getSequence());
            } else if (type == PacketType.Play.Server.CHUNK_DATA) {
                var column = new WrapperPlayServerChunkData(event).getColumn();
                if (!column.isFullChunk() || column.getChunks().length != height / 16)
                    throw new IllegalArgumentException("Неполный столбец чанка");
                int[][] sections = new int[column.getChunks().length][];
                for (int section = 0; section < sections.length; section++) {
                    var chunk = column.getChunks()[section];
                    if (chunk == null) continue; // Unknown, not assumed air.
                    int[] ids = new int[4096];
                    boolean uniform = true;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        int index = (y << 8) | (z << 4) | x;
                        ids[index] = chunk.getBlockId(x, y, z);
                        if (ids[index] < 0) throw new IllegalArgumentException("Некорректная палитра блоков");
                        if (ids[index] != ids[0]) uniform = false;
                    }
                    sections[section] = uniform ? new int[]{ids[0]} : ids;
                }
                change = new ClientWorld.Load(new ClientWorld.ChunkKey(column.getX(), column.getZ()), new ClientChunk(minHeight >> 4, sections));
            } else if (type == PacketType.Play.Server.UNLOAD_CHUNK) {
                var packet = new WrapperPlayServerUnloadChunk(event);
                change = new ClientWorld.Unload(new ClientWorld.ChunkKey(packet.getChunkX(), packet.getChunkZ()));
            } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
                var packet = new WrapperPlayServerBlockChange(event);
                var pos = packet.getBlockPosition();
                change = new ClientWorld.Blocks(new ClientWorld.ChunkKey(pos.getX() >> 4, pos.getZ() >> 4),
                        List.of(new ClientWorld.BlockEdit(pos.getX(), pos.getY(), pos.getZ(), packet.getBlockId())));
            } else {
                var packet = new WrapperPlayServerMultiBlockChange(event);
                if (packet.getBlocks().length > 4096) throw new IllegalArgumentException("Превышен лимит обновлений блоков");
                var edits = new ArrayList<ClientWorld.BlockEdit>();
                for (var block : packet.getBlocks()) edits.add(new ClientWorld.BlockEdit(block.getX(), block.getY(), block.getZ(), block.getBlockId()));
                var section = packet.getChunkPosition();
                change = new ClientWorld.Blocks(new ClientWorld.ChunkKey(section.getX(), section.getZ()), edits);
            }
            afterSend(event, data, observation, detection, change.key(), change.bytes(), () -> {
                world.configure(version.getProtocolVersion(), minHeight >> 4, height / 16);
                world.stage(change);
            });
        } catch (RuntimeException badPacket) {
            afterSend(event, data, observation, detection, null, 0, () -> world.reset("WORLD_DECODE_FAILED"));
        }
    }

    private void afterSend(PacketSendEvent event, UserData data, long observation, long detection,
                           ClientWorld.ChunkKey key, long bytes, Runnable task) {
        if (!data.clientWorld().reserve(key, bytes)) return;
        event.getTasksAfterSend().add(() -> {
            synchronized (data.clientWorld()) {
                data.clientWorld().release(key, bytes);
                if (event.isCancelled() || !ChannelHelper.isOpen(event.getChannel()) || !data.isPhysicsObserved() || data.getPhysicsObservationEpoch() != observation
                        || !config.runtime().acceptsDetection(detection)) return;
                task.run();
            }
        });
    }

    @Override public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getUser() == null || event.getUser().getUUID() == null) return;
        UserData data = users.get(event.getUser().getUUID());
        if (data == null) return;
        // Also consume our late replies after disabling diagnostics; they must not resurrect state.
        if (event.getPacketType() == PacketType.Play.Client.PONG) {
            if (data.clientWorld().acknowledge(new WrapperPlayClientPong(event).getId(), System.nanoTime())) event.setCancelled(true);
            return;
        }
        if (!data.isPhysicsObserved() || !config.isAnticheatEnabled() || event.getUser().getClientVersion() != ClientVersion.V_26_2) return;
        // Even a cancelled incoming action may already have been predicted by the client.
        try {
            com.github.retrooper.packetevents.util.Vector3i position;
            int sequence;
            if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
                var packet = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging(event);
                if (packet.getAction() != com.github.retrooper.packetevents.protocol.player.DiggingAction.START_DIGGING
                        && packet.getAction() != com.github.retrooper.packetevents.protocol.player.DiggingAction.FINISHED_DIGGING) return;
                position = packet.getBlockPosition(); sequence = packet.getSequence();
            } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                var packet = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement(event);
                position = packet.getBlockPosition(); sequence = packet.getSequence();
            } else return;
            var affected = new java.util.HashSet<ClientWorld.ChunkKey>();
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                affected.add(new ClientWorld.ChunkKey((position.getX() + x) >> 4, (position.getZ() + z) >> 4));
            data.clientWorld().predict(sequence, affected, System.nanoTime());
        } catch (RuntimeException badPacket) {
            data.clientWorld().reset("WORLD_PREDICTION_DECODE_FAILED");
        }
    }

    /** Main-thread tick schedules the fence on the actual outbound event loop, once per tick. */
    public void tick(Player player) {
        UserData data = users.get(player.getUniqueId());
        if (data == null || !data.isPhysicsObserved()) return;
        data.clientWorld().synchronizeDetection(config.runtime().detectionEpoch());
        if (!config.isAnticheatEnabled()) return;
        var user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) return;
        long observation = data.getPhysicsObservationEpoch(), detection = config.runtime().detectionEpoch();
        ChannelHelper.runInEventLoop(user.getChannel(), () -> {
            if (!data.isPhysicsObserved() || data.getPhysicsObservationEpoch() != observation
                    || !config.runtime().acceptsDetection(detection)) return;
            synchronized (data.clientWorld()) {
                var fence = data.clientWorld().fence(System.nanoTime());
                if (fence.isEmpty()) return;
                try { user.sendPacketSilently(new WrapperPlayServerPing(fence.getAsInt())); }
                catch (RuntimeException sendFailed) { data.clientWorld().reset("WORLD_FENCE_FAILED"); }
            }
        });
    }
}
