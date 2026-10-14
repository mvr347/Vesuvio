package net.lovelace.vesuvio.engine.physics;

import java.util.*;

/** Original, deliberately explicit shape catalogue. Unknown mechanics never default to a cube/air. */
public final class BlockShapeRegistry {
    private BlockShapeRegistry() {}
    private static final Set<String> EMPTY = Set.of("air", "cave_air", "void_air", "short_grass", "tall_grass", "fern", "large_fern",
            "dead_bush", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "torch", "wall_torch",
            "redstone_torch", "redstone_wall_torch", "soul_torch", "soul_wall_torch", "redstone_wire");
    private static final Set<String> FULL = Set.of("stone", "granite", "polished_granite", "diorite", "polished_diorite",
            "andesite", "polished_andesite", "dirt", "grass_block", "coarse_dirt", "rooted_dirt", "podzol", "mycelium",
            "cobblestone", "mossy_cobblestone", "bedrock", "sand", "red_sand", "gravel", "clay", "glass", "tinted_glass",
            "obsidian", "crying_obsidian", "netherrack", "end_stone", "deepslate", "cobbled_deepslate", "polished_deepslate",
            "tuff", "calcite", "dripstone_block", "blackstone", "polished_blackstone", "basalt", "smooth_basalt", "polished_basalt",
            "bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks", "nether_bricks",
            "red_nether_bricks", "end_stone_bricks", "deepslate_bricks", "deepslate_tiles", "prismarine", "prismarine_bricks",
            "dark_prismarine", "terracotta", "snow_block", "iron_block", "gold_block", "diamond_block", "emerald_block",
            "coal_block", "lapis_block", "redstone_block", "netherite_block", "quartz_block", "smooth_quartz", "quartz_pillar",
            "sandstone", "smooth_sandstone", "cut_sandstone", "chiseled_sandstone", "red_sandstone", "smooth_red_sandstone",
            "crafting_table", "furnace", "blast_furnace", "smoker", "dispenser", "dropper", "observer", "bookshelf",
            "barrel", "melon", "pumpkin", "carved_pumpkin", "jack_o_lantern", "glowstone", "sea_lantern", "moss_block",
            "crimson_stem", "warped_stem", "stripped_crimson_stem", "stripped_warped_stem", "mushroom_stem");
    private static final List<String> CUBE_SUFFIXES = List.of("_planks", "_log", "_wood", "_hyphae", "_ore",
            "_concrete", "_concrete_powder", "_terracotta", "_wool", "_stained_glass", "_leaves");

    public static BlockGeometry resolve(String state) {
        if (state == null) return BlockGeometry.UNKNOWN;
        int bracket = state.indexOf('[');
        String name = (bracket < 0 ? state : state.substring(0, bracket)).replace("minecraft:", "");
        Map<String, String> properties = new HashMap<>();
        if (bracket >= 0) {
            if (!state.endsWith("]")) return BlockGeometry.UNKNOWN;
            for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) properties.put(kv[0].trim(), kv[1].trim());
            }
        }
        if ("true".equals(properties.get("waterlogged"))) return BlockGeometry.UNKNOWN;
        if (EMPTY.contains(name)) return BlockGeometry.AIR;
        if (name.equals("ice") || name.equals("packed_ice") || name.equals("frosted_ice") || name.equals("blue_ice"))
            return new BlockGeometry(BlockGeometry.CUBE.shapes(), name.equals("blue_ice") ? .989 : .98, true);
        if (FULL.contains(name) || CUBE_SUFFIXES.stream().anyMatch(name::endsWith)) return BlockGeometry.CUBE;
        if (name.endsWith("_slab")) return switch (properties.getOrDefault("type", "")) {
            case "bottom" -> box(0, 0, 0, 1, .5, 1);
            case "top" -> box(0, .5, 0, 1, 1, 1);
            case "double" -> BlockGeometry.CUBE;
            default -> BlockGeometry.UNKNOWN;
        };
        if (name.endsWith("_stairs")) return stairs(properties);
        if (name.endsWith("_trapdoor") && "false".equals(properties.get("open"))) return switch (properties.getOrDefault("half", "")) {
            case "top" -> box(0, .8125, 0, 1, 1, 1);
            case "bottom" -> box(0, 0, 0, 1, .1875, 1);
            default -> BlockGeometry.UNKNOWN;
        };
        if (name.endsWith("_fence_gate")) {
            if ("true".equals(properties.get("open"))) return BlockGeometry.AIR;
            if (!"false".equals(properties.get("open"))) return BlockGeometry.UNKNOWN;
            return switch (properties.getOrDefault("facing", "")) {
                case "north", "south" -> box(0, 0, .375, 1, 1.5, .625);
                case "east", "west" -> box(.375, 0, 0, .625, 1.5, 1);
                default -> BlockGeometry.UNKNOWN;
            };
        }
        if (name.endsWith("_fence")) return connections(properties, .375, .625, 1.5);
        if (name.equals("glass_pane") || name.endsWith("_stained_glass_pane") || name.equals("iron_bars"))
            return connections(properties, .4375, .5625, 1);
        return BlockGeometry.UNKNOWN;
    }
    private static BlockGeometry connections(Map<String, String> p, double low, double high, double height) {
        for (String direction : List.of("north", "south", "east", "west"))
            if (!Set.of("true", "false").contains(p.getOrDefault(direction, ""))) return BlockGeometry.UNKNOWN;
        List<CollisionBox> boxes = new ArrayList<>();
        boxes.add(new CollisionBox(low, 0, low, high, height, high));
        if (p.get("north").equals("true")) boxes.add(new CollisionBox(low, 0, 0, high, height, .5));
        if (p.get("south").equals("true")) boxes.add(new CollisionBox(low, 0, .5, high, height, 1));
        if (p.get("west").equals("true")) boxes.add(new CollisionBox(0, 0, low, .5, height, high));
        if (p.get("east").equals("true")) boxes.add(new CollisionBox(.5, 0, low, 1, height, high));
        return new BlockGeometry(boxes, .6, true);
    }
    private static BlockGeometry stairs(Map<String, String> p) {
        String shape = p.getOrDefault("shape", ""), half = p.getOrDefault("half", "");
        if (!Set.of("straight", "inner_left", "inner_right", "outer_left", "outer_right").contains(shape)
                || !Set.of("bottom", "top").contains(half)) return BlockGeometry.UNKNOWN;
        int fx = 0, fz = 0;
        switch (p.getOrDefault("facing", "")) {
            case "north" -> fz = -1; case "south" -> fz = 1;
            case "west" -> fx = -1; case "east" -> fx = 1;
            default -> { return BlockGeometry.UNKNOWN; }
        }
        boolean top = half.equals("top");
        List<CollisionBox> boxes = new ArrayList<>();
        boxes.add(new CollisionBox(0, top ? .5 : 0, 0, 1, top ? 1 : .5, 1));
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
            int dx = 2 * x - 1, dz = 2 * z - 1;
            boolean front = dx * fx + dz * fz > 0, left = dx * fz - dz * fx > 0;
            boolean occupied = switch (shape) {
                case "inner_left" -> front || left; case "inner_right" -> front || !left;
                case "outer_left" -> front && left; case "outer_right" -> front && !left;
                default -> front;
            };
            if (occupied) boxes.add(new CollisionBox(x * .5, top ? 0 : .5, z * .5, x * .5 + .5, top ? .5 : 1, z * .5 + .5));
        }
        return new BlockGeometry(boxes, .6, true);
    }
    private static BlockGeometry box(double x, double y, double z, double maxX, double maxY, double maxZ) {
        return new BlockGeometry(List.of(new CollisionBox(x, y, z, maxX, maxY, maxZ)), .6, true);
    }
}
