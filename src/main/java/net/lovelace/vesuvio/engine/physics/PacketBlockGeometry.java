package net.lovelace.vesuvio.engine.physics;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import java.util.LinkedHashMap;
import java.util.Map;

/** PacketEvents is used only to decode the protocol's block-state names/properties. */
public final class PacketBlockGeometry {
    private PacketBlockGeometry() {}
    private static final Map<Long, BlockGeometry> CACHE = new LinkedHashMap<>();
    public static synchronized BlockGeometry resolve(int protocol, int stateId) {
        if (protocol < 0 || stateId < 0) return BlockGeometry.UNKNOWN;
        long key = ((long) protocol << 32) | Integer.toUnsignedLong(stateId);
        BlockGeometry known = CACHE.get(key);
        if (known != null) return known;
        BlockGeometry result;
        try {
            var state = WrappedBlockState.getByGlobalId(ClientVersion.getById(protocol), stateId, false);
            result = state.getGlobalId() == stateId ? BlockShapeRegistry.resolve(state.toString()) : BlockGeometry.UNKNOWN;
        } catch (RuntimeException invalidState) {
            result = BlockGeometry.UNKNOWN;
        }
        if (CACHE.size() >= 8192) CACHE.remove(CACHE.keySet().iterator().next());
        CACHE.put(key, result);
        return result;
    }
}
