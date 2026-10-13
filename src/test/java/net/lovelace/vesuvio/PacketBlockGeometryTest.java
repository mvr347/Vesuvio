package net.lovelace.vesuvio;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import net.lovelace.vesuvio.engine.physics.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketBlockGeometryTest {
    private static com.github.retrooper.packetevents.PacketEventsAPI<?> previous;
    @org.junit.jupiter.api.BeforeAll static void loadProtocolResources() {
        previous = com.github.retrooper.packetevents.PacketEvents.getAPI();
        com.github.retrooper.packetevents.PacketEvents.setAPI(new com.github.retrooper.packetevents.PacketEventsAPI<Object>() {
            public boolean isLoaded() { return true; }
            public void init() {}
            public boolean isInitialized() { return true; }
            public boolean isTerminated() { return false; }
            public Object getPlugin() { return null; }
            public com.github.retrooper.packetevents.manager.server.ServerManager getServerManager() {
                return () -> com.github.retrooper.packetevents.manager.server.ServerVersion.V_26_2;
            }
            public com.github.retrooper.packetevents.manager.protocol.ProtocolManager getProtocolManager() { throw new AssertionError("Network access in palette test"); }
            public com.github.retrooper.packetevents.manager.player.PlayerManager getPlayerManager() { throw new AssertionError("Player access in palette test"); }
            public com.github.retrooper.packetevents.netty.NettyManager getNettyManager() { throw new AssertionError("Netty access in palette test"); }
            public com.github.retrooper.packetevents.injector.ChannelInjector getInjector() { throw new AssertionError("Injection in palette test"); }
        });
    }
    @org.junit.jupiter.api.AfterAll static void restoreApi() { com.github.retrooper.packetevents.PacketEvents.setAPI(previous); }
    @Test void actualPacketEvents26PaletteDecodesIntoIndependentGeometry() {
        var version = ClientVersion.V_26_2;
        var stone = WrappedBlockState.getByString(version, "minecraft:stone");
        assertNotNull(stone);
        assertEquals(BlockGeometry.CUBE, PacketBlockGeometry.resolve(version.getProtocolVersion(), stone.getGlobalId()));
        var slab = WrappedBlockState.getByString(version, "minecraft:oak_slab[type=bottom,waterlogged=false]");
        assertNotNull(slab);
        assertEquals(.5, PacketBlockGeometry.resolve(version.getProtocolVersion(), slab.getGlobalId()).shapes().getFirst().maxY());
    }
}
