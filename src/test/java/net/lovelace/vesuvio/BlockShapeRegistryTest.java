package net.lovelace.vesuvio;

import net.lovelace.vesuvio.engine.physics.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockShapeRegistryTest {
    @Test void airIsDifferentFromAnUnsupportedState() {
        assertTrue(BlockShapeRegistry.resolve("minecraft:cave_air").supported());
        assertFalse(BlockShapeRegistry.resolve("minecraft:water[level=0]").supported());
        assertFalse(BlockShapeRegistry.resolve("minecraft:slime_block").supported());
        assertFalse(BlockShapeRegistry.resolve("minecraft:big_dripleaf_stem[facing=north,waterlogged=false]").supported());
        assertFalse(BlockShapeRegistry.resolve("mod:unknown_block").supported());
    }
    @Test void slabsHaveExactHalfHeightAndWaterloggedSlabsAreExcluded() {
        assertEquals(new CollisionBox(0, 0, 0, 1, .5, 1), BlockShapeRegistry.resolve("minecraft:oak_slab[type=bottom,waterlogged=false]").shapes().getFirst());
        assertEquals(new CollisionBox(0, .5, 0, 1, 1, 1), BlockShapeRegistry.resolve("minecraft:stone_slab[type=top,waterlogged=false]").shapes().getFirst());
        assertEquals(BlockGeometry.CUBE, BlockShapeRegistry.resolve("minecraft:stone_slab[type=double,waterlogged=false]"));
        assertFalse(BlockShapeRegistry.resolve("minecraft:stone_slab[type=bottom,waterlogged=true]").supported());
    }
    @Test void stairCornersHaveIndependentExpectedVolumes() {
        assertEquals(.75, volume(stair("straight", "bottom")), 1e-12);
        assertEquals(.875, volume(stair("inner_left", "bottom")), 1e-12);
        assertEquals(.625, volume(stair("outer_right", "bottom")), 1e-12);
        assertEquals(.75, volume(stair("straight", "top")), 1e-12);
        var bounds = stair("straight", "bottom").shapes();
        assertTrue(bounds.stream().filter(b -> b.minY() == .5).allMatch(b -> b.maxZ() == .5));
    }
    @Test void fencesGatesAndPanesUseConnectionsAndAreNotFullCubes() {
        var post = BlockShapeRegistry.resolve("minecraft:oak_fence[north=false,south=false,east=false,west=false,waterlogged=false]");
        assertEquals(new CollisionBox(.375, 0, .375, .625, 1.5, .625), post.shapes().getFirst());
        var connected = BlockShapeRegistry.resolve("minecraft:oak_fence[north=true,south=false,east=false,west=false,waterlogged=false]");
        assertEquals(2, connected.shapes().size());
        assertTrue(BlockShapeRegistry.resolve("minecraft:oak_fence_gate[open=true,facing=north,in_wall=false,powered=false]").shapes().isEmpty());
        var pane = BlockShapeRegistry.resolve("minecraft:glass_pane[north=false,south=false,east=true,west=true,waterlogged=false]");
        assertEquals(3, pane.shapes().size());
        assertEquals(.125, pane.shapes().getFirst().maxX() - pane.shapes().getFirst().minX());
    }
    @Test void supportedNamesCannotPromotePlantsOrSpecialBlocksIntoFullCubes() {
        assertEquals(BlockGeometry.CUBE, BlockShapeRegistry.resolve("minecraft:oak_log[axis=y]"));
        assertEquals(BlockGeometry.CUBE, BlockShapeRegistry.resolve("minecraft:crimson_stem[axis=x]"));
        assertFalse(BlockShapeRegistry.resolve("minecraft:melon_stem[age=7]").supported());
        assertFalse(BlockShapeRegistry.resolve("minecraft:honey_block").supported());
        assertFalse(BlockShapeRegistry.resolve("minecraft:oak_door[open=true]").supported());
    }
    private static BlockGeometry stair(String shape, String half) {
        return BlockShapeRegistry.resolve("minecraft:stone_stairs[facing=north,half=" + half + ",shape=" + shape + ",waterlogged=false]");
    }
    private static double volume(BlockGeometry geometry) {
        return geometry.shapes().stream().mapToDouble(b -> (b.maxX() - b.minX()) * (b.maxY() - b.minY()) * (b.maxZ() - b.minZ())).sum();
    }
}
