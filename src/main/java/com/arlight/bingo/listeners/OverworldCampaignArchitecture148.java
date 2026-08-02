package com.arlight.bingo.listeners;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;
import static com.arlight.bingo.listeners.OverworldCampaignAudit148.*;

final class OverworldCampaignArchitecture148 {
    static final int BOSS_GATE_TOWER_OFFSET = 15;
    static final int BOSS_GATE_TOWER_RADIUS = 7;
    static final int BOSS_GATE_CLEAR_HALF_WIDTH = BOSS_GATE_TOWER_OFFSET
            - BOSS_GATE_TOWER_RADIUS - 1;

    private OverworldCampaignArchitecture148() { }

    static void house(List<BlockEdit> out, World world, Registry registry, String id,
                      int cx, int y, int cz, int width, int depth,
                      int floors, boolean roofAlongX, Material plaster, Facing front) {
        int hx = width / 2, hz = depth / 2, height = floors * 5;
        registry.registerHouse(new HouseSpec(id, cx, y, cz, hx, hz,
                height, roofAlongX, front));
        OverworldCampaignTerrain148.buildingPad(out, world, cx, y - 1, cz,
                hx + 1, hz + 1, 5, Material.GRASS_BLOCK);
        foundation(out, cx, y - 1, cz, hx + 1, hz + 1);
        for (int x = -hx; x <= hx; x++) for (int z = -hz; z <= hz; z++) {
            out.add(e(cx + x, y - 1, cz + z, ((x + z) & 7) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE));
            boolean edge = Math.abs(x) == hx || Math.abs(z) == hz;
            for (int yy = 0; yy <= height; yy++) {
                if (!edge) { if (yy > 0) out.add(e(cx + x, y + yy, cz + z, Material.AIR)); continue; }
                boolean door = entrance(front, x, z, hx, hz) && yy <= 1;
                if (door) { out.add(e(cx + x, y + yy, cz + z, Material.AIR)); continue; }
                boolean xWall = Math.abs(x) == hx;
                boolean corner = xWall && Math.abs(z) == hz;
                int alongWall = xWall ? z : x;
                int bay = Math.floorMod(alongWall, 5);
                boolean frame = corner || yy == 0 || yy == height || yy % 5 == 0
                        || bay == 0;
                boolean window = !frame && yy % 5 >= 2 && yy % 5 <= 3
                        && (bay == 2 || bay == 3);
                if (window) {
                    out.add(connectedGlassPane(cx + x, y + yy, cz + z, xWall));
                } else {
                    out.add(e(cx + x, y + yy, cz + z,
                            frame ? Material.STRIPPED_DARK_OAK_LOG : plaster));
                }
            }
        }
        for (int floor = 1; floor < floors; floor++) {
            int fy = y + floor * 5;
            int ladderLocalX = hx - 1;
            int ladderLocalZ = Math.min(2, hz - 2);
            for (int x = -hx + 1; x <= hx - 1; x++) for (int z = -hz + 1; z <= hz - 1; z++)
                if (x != ladderLocalX || z != ladderLocalZ)
                    out.add(e(cx + x, fy, cz + z, Material.SPRUCE_PLANKS));
        }
        roof(out, cx, y + height + 1, cz, hx, hz, roofAlongX, plaster);
        closeEaves(out, cx, y + height + 1, cz, hx, hz, roofAlongX);
        frontPath(out, world, registry, id, cx, y, cz, hx, hz, front);
        int frontRadius = front == Facing.NORTH || front == Facing.SOUTH ? hz : hx;
        int doorX = cx + front.dx * frontRadius;
        int doorZ = cz + front.dz * frontRadius;
        door(out, doorX, y, doorZ, front);
        out.add(e(cx - hx + 2, y, cz - hz + 2, Material.CRAFTING_TABLE));
        out.add(e(cx + hx - 2, y, cz - hz + 2, Material.BARREL));
        for (int floor = 0; floor < floors; floor++) {
            int ceiling = y + (floor + 1) * 5;
            out.add(e(cx, ceiling - 1, cz, Material.CHAIN));
            out.add(e(cx, ceiling - 2, cz, Material.LANTERN));
            int walkY = y + floor * 5 + (floor == 0 ? 0 : 1);
            out.add(e(cx - hx + 2, walkY, cz + hz - 2, Material.BOOKSHELF));
            out.add(e(cx - hx + 3, walkY, cz + hz - 2,
                    floor % 2 == 0 ? Material.CHEST : Material.BARREL));
            int lightX = Math.max(2, hx / 2);
            int lightZ = Math.max(2, hz / 2);
            for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
                out.add(data(cx + sx * lightX, ceiling - 2, cz + sz * lightZ,
                        Material.LIGHT, "minecraft:light[level=12,waterlogged=false]"));
            }
        }
        if (floors > 1) {
            int ladderX = cx + hx - 1;
            int ladderZ = cz + Math.min(2, hz - 2);
            for (int yy = y; yy <= y + (floors - 1) * 5 + 1; yy++) {
                out.add(e(cx + hx, yy, ladderZ, Material.STRIPPED_DARK_OAK_LOG));
                out.add(data(ladderX, yy, ladderZ, Material.LADDER,
                        "minecraft:ladder[facing=west,waterlogged=false]"));
            }
        }
        decorateHouseInterior(out, id, cx, y, cz, hx, hz, floors, front);
    }

    static void roof(List<BlockEdit> out, int cx, int y, int cz, int wallHx, int wallHz,
                     boolean alongX, Material plaster) {
        int hx = wallHx + 2, hz = wallHz + 2;
        int layers = alongX ? hz : hx;
        for (int layer = 0; layer <= layers; layer++) {
            if (alongX) for (int x = -hx; x <= hx; x++) {
                out.add(e(cx + x, y + layer, cz - hz + layer, Material.DARK_OAK_PLANKS));
                out.add(e(cx + x, y + layer, cz + hz - layer, Material.DARK_OAK_PLANKS));
            } else for (int z = -hz; z <= hz; z++) {
                out.add(e(cx - hx + layer, y + layer, cz + z, Material.DARK_OAK_PLANKS));
                out.add(e(cx + hx - layer, y + layer, cz + z, Material.DARK_OAK_PLANKS));
            }
        }
        if (alongX) {
            for (int side : new int[]{-wallHx, wallHx}) for (int z = -wallHz; z <= wallHz; z++) {
                int top = y + hz - Math.abs(z);
                for (int yy = y; yy < top; yy++) {
                    Material material = yy == y || z == 0 || Math.floorMod(yy + z, 5) == 0
                            ? Material.STRIPPED_DARK_OAK_LOG : plaster;
                    out.add(e(cx + side, yy, cz + z, material));
                }
            }
            for (int x = -hx; x <= hx; x++)
                out.add(e(cx + x, y + hz, cz, Material.STRIPPED_DARK_OAK_LOG));
        } else {
            for (int side : new int[]{-wallHz, wallHz}) for (int x = -wallHx; x <= wallHx; x++) {
                int top = y + hx - Math.abs(x);
                for (int yy = y; yy < top; yy++) {
                    Material material = yy == y || x == 0 || Math.floorMod(yy + x, 5) == 0
                            ? Material.STRIPPED_DARK_OAK_LOG : plaster;
                    out.add(e(cx + x, yy, cz + side, material));
                }
            }
            for (int z = -hz; z <= hz; z++)
                out.add(e(cx, y + hx, cz + z, Material.STRIPPED_DARK_OAK_LOG));
        }
    }

    private static void closeEaves(List<BlockEdit> out, int cx, int roofY, int cz,
                                    int wallHx, int wallHz, boolean alongX) {
        int hx = wallHx + 2;
        int hz = wallHz + 2;
        if (alongX) {
            for (int x = -hx; x <= hx; x++) {
                for (int z : new int[]{-wallHz - 1, wallHz + 1}) {
                    out.add(e(cx + x, roofY - 1, cz + z, Material.DARK_OAK_PLANKS));
                    if (Math.floorMod(x, 4) == 0)
                        out.add(e(cx + x, roofY - 2, cz + z, Material.STRIPPED_DARK_OAK_LOG));
                }
            }
        } else {
            for (int z = -hz; z <= hz; z++) {
                for (int x : new int[]{-wallHx - 1, wallHx + 1}) {
                    out.add(e(cx + x, roofY - 1, cz + z, Material.DARK_OAK_PLANKS));
                    if (Math.floorMod(z, 4) == 0)
                        out.add(e(cx + x, roofY - 2, cz + z, Material.STRIPPED_DARK_OAK_LOG));
                }
            }
        }
    }

    private static boolean entrance(Facing front, int x, int z, int hx, int hz) {
        return switch (front) {
            case NORTH -> z == -hz && x == 0;
            case SOUTH -> z == hz && x == 0;
            case EAST -> x == hx && z == 0;
            case WEST -> x == -hx && z == 0;
        };
    }

    private static void door(List<BlockEdit> out, int x, int y, int z, Facing facing) {
        String direction = switch (facing) {
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST -> "east";
            case WEST -> "west";
        };
        out.add(data(x, y, z, Material.SPRUCE_DOOR,
                "minecraft:spruce_door[facing=" + direction
                        + ",half=lower,hinge=left,open=false,powered=false]"));
        out.add(data(x, y + 1, z, Material.SPRUCE_DOOR,
                "minecraft:spruce_door[facing=" + direction
                        + ",half=upper,hinge=left,open=false,powered=false]"));
    }

    private static BlockEdit connectedGlassPane(int x, int y, int z, boolean xWall) {
        String connections = xWall
                ? "north=true,east=false,south=true,west=false"
                : "north=false,east=true,south=false,west=true";
        return data(x, y, z, Material.GLASS_PANE,
                "minecraft:glass_pane[" + connections + ",waterlogged=false]");
    }

    private static void frontPath(List<BlockEdit> out, World world, Registry registry,
                                  String houseId, int cx, int y, int cz,
                                  int hx, int hz, Facing front) {
        int radius = front == Facing.NORTH || front == Facing.SOUTH ? hz : hx;
        String pathId = "house-path-" + houseId;
        for (int step = 0; step <= 18; step++) {
            int px = cx + front.dx * (radius + step);
            int pz = cz + front.dz * (radius + step);
            boolean reachesStreet = step >= 5
                    && registry.hasRoadNearExcept(pathId, px, pz, 2);
            int sideRadius = step == 0 ? 0 : step < 3 ? 1 : 2;
            for (int side = -sideRadius; side <= sideRadius; side++) {
                int x = px + (front.dx == 0 ? side : 0);
                int z = pz + (front.dz == 0 ? side : 0);
                Material floor = Math.floorMod(step * 3 + side * 5, 13) < 2
                        ? Material.MOSSY_COBBLESTONE
                        : Math.abs(side) == sideRadius && step > 2
                        ? Material.ANDESITE : Material.COBBLESTONE;
                OverworldCampaignTerrain148.supportedPathCell(out, world, x, y, z, floor);
                for (int yy = 0; yy <= 3; yy++) out.add(e(x, y + yy, z, Material.AIR));
                registry.registerRoadCell(pathId, x, y, z, floor);
            }
            if (reachesStreet) {
                for (int landing = -2; landing <= 2; landing++) {
                    int x = px + (front.dx == 0 ? landing : 0);
                    int z = pz + (front.dz == 0 ? landing : 0);
                    OverworldCampaignTerrain148.supportedPathCell(out, world, x, y, z,
                            Material.POLISHED_ANDESITE);
                    registry.registerRoadCell(pathId, x, y, z, Material.POLISHED_ANDESITE);
                }
                break;
            }
        }
    }

    static void foundation(List<BlockEdit> out, int cx, int y, int cz, int hx, int hz) {
        for (int x = -hx; x <= hx; x++) for (int z = -hz; z <= hz; z++)
            for (int d = 0; d < 5; d++) out.add(e(cx + x, y - d, cz + z, d < 2 ? Material.STONE_BRICKS : Material.STONE));
    }

    static void auditedTower(List<BlockEdit> out, World world, Registry registry, String id,
                             int cx, int y, int cz, int radius, int height, Material roof) {
        auditedTower(out, world, registry, id, cx, y, cz, radius, height, roof, Facing.SOUTH);
    }

    static void auditedTower(List<BlockEdit> out, World world, Registry registry, String id,
                             int cx, int y, int cz, int radius, int height, Material roof,
                             Facing entrance) {
        registry.registerTower(new TowerSpec(id, cx, y, cz, radius, height, entrance));
        OverworldCampaignTerrain148.buildingPad(out, world, cx, y - 1, cz,
                radius + 1, radius + 1, 5, Material.STONE_BRICKS);
        tower(out, world, cx, y, cz, radius, height, roof, entrance);
    }

    static void tower(List<BlockEdit> out, World world, int cx, int y, int cz, int radius,
                      int height, Material roof, Facing entrance) {
        foundation(out, cx, y - 1, cz, radius + 1, radius + 1);
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            boolean wall = Math.abs(x) == radius || Math.abs(z) == radius;
            for (int yy = 0; yy <= height; yy++) {
                if (!wall) { if (yy > 0) out.add(e(cx + x, y + yy, cz + z, Material.AIR)); continue; }
                boolean opening = entrance(entrance, x, z, radius, radius) && yy <= 1;
                out.add(e(cx + x, y + yy, cz + z, opening ? Material.AIR
                        : ((x + z + yy) & 11) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
            }
        }
        for (int level = 6; level < height; level += 6) {
            for (int x = -radius + 1; x <= radius - 1; x++) {
                for (int z = -radius + 1; z <= radius - 1; z++) {
                    if (x == radius - 1 && z == Math.min(2, radius - 2)) continue;
                    out.add(e(cx + x, y + level, cz + z, Material.DEEPSLATE_TILES));
                }
            }
        }
        for (int level = 0; level < height; level += 6) {
            int windowY = y + Math.min(height - 1, level + 3);
            for (int yy = windowY; yy <= Math.min(y + height - 1, windowY + 1); yy++) {
                out.add(e(cx, yy, cz - radius, Material.IRON_BARS));
                out.add(e(cx - radius, yy, cz, Material.IRON_BARS));
                out.add(e(cx + radius, yy, cz, Material.IRON_BARS));
            }
            int walkY = y + level + (level == 0 ? 0 : 1);
            out.add(e(cx - radius + 2, walkY, cz - radius + 2,
                    level % 12 == 0 ? Material.SMITHING_TABLE : Material.CRAFTING_TABLE));
            out.add(e(cx - radius + 3, walkY, cz - radius + 2, Material.BARREL));
            out.add(data(cx - Math.max(1, radius / 2), windowY, cz,
                    Material.LIGHT, "minecraft:light[level=13,waterlogged=false]"));
            out.add(data(cx + Math.max(1, radius / 2), windowY, cz,
                    Material.LIGHT, "minecraft:light[level=13,waterlogged=false]"));
            decorateTowerLevel(out, cx, walkY, cz, radius, level);
        }
        int ladderZ = cz + Math.min(2, radius - 2);
        for (int yy = y; yy < y + height; yy++) {
            out.add(e(cx + radius, yy, ladderZ, Material.STONE_BRICKS));
            out.add(data(cx + radius - 1, yy, ladderZ, Material.LADDER,
                    "minecraft:ladder[facing=west,waterlogged=false]"));
        }
        towerEntranceLanding(out, world, cx, y, cz, radius, entrance);
        door(out, cx + entrance.dx * radius, y, cz + entrance.dz * radius, entrance);
        for (int x = -radius - 2; x <= radius + 2; x++) for (int z = -radius - 2; z <= radius + 2; z++)
            if (Math.abs(x) + Math.abs(z) <= radius * 2 + 2) out.add(e(cx + x, y + height + 1, cz + z, roof));
        out.add(e(cx, y + height, cz, Material.CHAIN));
        out.add(e(cx, y + height - 1, cz, Material.CHAIN));
        out.add(e(cx, y + height - 2, cz, Material.LANTERN));
    }

    private static void towerEntranceLanding(List<BlockEdit> out, World world,
                                             int cx, int y, int cz, int radius,
                                             Facing entrance) {
        int sideX = -entrance.dz;
        int sideZ = entrance.dx;
        for (int step = 0; step <= 5; step++) {
            int sideRadius = step == 0 ? 0 : 2;
            int clearance = step == 0 ? 1 : 3;
            for (int side = -sideRadius; side <= sideRadius; side++) {
                int x = cx + entrance.dx * (radius + step) + sideX * side;
                int z = cz + entrance.dz * (radius + step) + sideZ * side;
                Material floor = Math.floorMod(step + side, 7) == 0
                        ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
                OverworldCampaignTerrain148.supportedPathCell(out, world, x, y, z, floor);
                for (int yy = 0; yy <= clearance; yy++) {
                    out.add(e(x, y + yy, z, Material.AIR));
                }
            }
        }
    }

    static void well(List<BlockEdit> out, int cx, int y, int cz) {
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
            out.add(e(cx + x, y, cz + z, Math.abs(x) == 3 || Math.abs(z) == 3 ? Material.COBBLESTONE : Material.WATER));
        for (int[] p : new int[][]{{-3,-3},{3,-3},{-3,3},{3,3}}) pillar(out, cx + p[0], y + 1, cz + p[1], 4,
                Material.STRIPPED_SPRUCE_LOG, Material.LANTERN);
    }

    static void market(List<BlockEdit> out, int cx, int y, int cz) {
        for (int x = -13; x <= 13; x++) for (int z = -9; z <= 9; z++)
            out.add(e(cx + x, y - 1, cz + z, ((x + z) & 3) == 0 ? Material.MUD_BRICKS : Material.POLISHED_ANDESITE));
        for (int[] s : new int[][]{{-9,-5},{0,-5},{9,-5},{-9,5},{0,5},{9,5}}) stall(out, cx + s[0], y, cz + s[1],
                ((s[0] + s[1]) & 1) == 0);
    }

    static void stall(List<BlockEdit> out, int cx, int y, int cz, boolean red) {
        for (int x = -3; x <= 3; x++) for (int z = -2; z <= 2; z++) out.add(e(cx + x, y - 1, cz + z, Material.SPRUCE_PLANKS));
        for (int[] p : new int[][]{{-3,-2},{3,-2},{-3,2},{3,2}}) pillar(out, cx + p[0], y, cz + p[1], 4,
                Material.STRIPPED_SPRUCE_LOG, Material.LANTERN);
        for (int x = -4; x <= 4; x++) for (int z = -3; z <= 3; z++) out.add(e(cx + x, y + 4, cz + z,
                ((x + z) & 1) == 0 ? (red ? Material.RED_WOOL : Material.YELLOW_WOOL) : Material.WHITE_WOOL));
        out.add(e(cx, y, cz, Material.BARREL));
        out.add(e(cx - 2, y, cz, red ? Material.RED_GLAZED_TERRACOTTA : Material.HAY_BLOCK));
        out.add(e(cx + 2, y, cz, red ? Material.PUMPKIN : Material.MELON));
        out.add(e(cx, y, cz + 1, Material.SPRUCE_PLANKS));
        out.add(e(cx, y + 1, cz + 1, red ? Material.FLOWER_POT : Material.CAKE));
    }

    static void animalYard(List<BlockEdit> out, int cx, int y, int cz,
                           int width, int depth, Facing gate) {
        int hx = width / 2, hz = depth / 2;
        for (int x = -hx; x <= hx; x++) for (int z = -hz; z <= hz; z++) {
            double oval = (x * x) / Math.max(1.0D, hx * hx)
                    + (z * z) / Math.max(1.0D, hz * hz);
            if (oval > 1.08D) continue;
            boolean edge = oval >= 0.78D;
            boolean opening = gate == Facing.NORTH && z <= -hz + 1 && Math.abs(x) <= 2
                    || gate == Facing.SOUTH && z >= hz - 1 && Math.abs(x) <= 2
                    || gate == Facing.WEST && x <= -hx + 1 && Math.abs(z) <= 2
                    || gate == Facing.EAST && x >= hx - 1 && Math.abs(z) <= 2;
            Material base = Math.floorMod(x * 11 + z * 7, 13) < 3
                    ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
            out.add(e(cx + x, y - 1, cz + z, base));
            for (int yy = 0; yy <= 2; yy++) out.add(e(cx + x, y + yy, cz + z, Material.AIR));
            if (edge && !opening && Math.floorMod(x + z, 2) == 0)
                out.add(e(cx + x, y, cz + z, Material.SPRUCE_FENCE));
        }
        out.add(e(cx - hx / 3, y, cz, Material.HAY_BLOCK));
        out.add(e(cx - hx / 3, y + 1, cz, Material.HAY_BLOCK));
        out.add(e(cx + hx / 3, y, cz - hz / 3, Material.WATER_CAULDRON));
        for (int side : new int[]{-1, 1}) {
            pillar(out, cx + side * Math.max(3, hx - 2), y, cz,
                    3, Material.SPRUCE_FENCE, Material.LANTERN);
        }
    }

    static void serviceYard(List<BlockEdit> out, int cx, int y, int cz,
                            int width, int depth, boolean damaged) {
        int hx = width / 2, hz = depth / 2;
        for (int x = -hx; x <= hx; x++) for (int z = -hz; z <= hz; z++) {
            double oval = (x * x) / Math.max(1.0D, hx * hx)
                    + (z * z) / Math.max(1.0D, hz * hz);
            if (oval > 1.0D) continue;
            Material floor = Math.floorMod(x * 17 + z * 5, 11) < 3
                    ? Material.GRAVEL : damaged ? Material.COARSE_DIRT : Material.DIRT_PATH;
            out.add(e(cx + x, y - 1, cz + z, floor));
            for (int yy = 0; yy <= 2; yy++) out.add(e(cx + x, y + yy, cz + z, Material.AIR));
        }
        for (int[] crate : new int[][]{{-5,-2},{4,3},{1,-4}}) {
            out.add(e(cx + crate[0], y, cz + crate[1], Material.BARREL));
            out.add(e(cx + crate[0] + 1, y, cz + crate[1], Material.SPRUCE_PLANKS));
        }
        for (int z = -3; z <= 3; z++) {
            out.add(e(cx - hx + 2, y, cz + z, z % 2 == 0 ? Material.OAK_LOG : Material.SPRUCE_PLANKS));
        }
        pillar(out, cx + hx - 2, y, cz, 3, Material.SPRUCE_FENCE,
                damaged ? Material.SOUL_LANTERN : Material.LANTERN);
    }

    static void occupationCamp(List<BlockEdit> out, int cx, int y, int cz, boolean alongX) {
        for (int x = -7; x <= 7; x++) for (int z = -5; z <= 5; z++) {
            if ((x * x) / 49.0D + (z * z) / 25.0D > 1.0D) continue;
            out.add(e(cx + x, y - 1, cz + z,
                    Math.floorMod(x * 13 + z * 7, 9) < 3 ? Material.MOSS_BLOCK : Material.COARSE_DIRT));
        }
        for (int side : new int[]{-1, 1}) {
            int tx = cx + (alongX ? side * 4 : 0);
            int tz = cz + (alongX ? 0 : side * 4);
            for (int o = -2; o <= 2; o++) for (int yy = 0; yy <= 3; yy++) {
                int x = tx + (alongX ? 0 : o);
                int z = tz + (alongX ? o : 0);
                if (yy == 3 || Math.abs(o) == 2)
                    out.add(e(x, y + yy, z, side < 0 ? Material.GREEN_WOOL : Material.BROWN_WOOL));
            }
        }
        out.add(e(cx, y, cz, Material.CAMPFIRE));
        out.add(e(cx + 2, y, cz + 1, Material.BARREL));
        out.add(e(cx - 2, y, cz - 1, Material.CHEST));
        pillar(out, cx, y, cz + 4, 3, Material.POLISHED_DEEPSLATE_WALL, Material.SOUL_LANTERN);
    }

    static void trainingYard(List<BlockEdit> out, int cx, int y, int cz) {
        for (int x = -12; x <= 12; x++) for (int z = -9; z <= 9; z++)
            out.add(e(cx + x, y - 1, cz + z, ((x + z) & 7) == 0 ? Material.GRAVEL : Material.COARSE_DIRT));
        for (int x : new int[]{-8,0,8}) {
            pillar(out, cx + x, y, cz + 2, 2, Material.STRIPPED_OAK_LOG, Material.TARGET);
            out.add(e(cx + x, y, cz - 4, Material.GRINDSTONE));
        }
        for (int x : new int[]{-11, 11}) {
            out.add(e(cx + x, y, cz - 6, Material.BARREL));
            out.add(e(cx + x, y + 1, cz - 6, Material.IRON_BARS));
            out.add(e(cx + x, y + 2, cz - 6, Material.CHAIN));
        }
        out.add(e(cx, y, cz - 7, Material.SMITHING_TABLE));
        out.add(e(cx - 2, y, cz - 7, Material.ANVIL));
        out.add(e(cx + 2, y, cz - 7, Material.CHEST));
    }

    static void fortWall(List<BlockEdit> out, int cx, int y, int cz, int radius, int height, boolean southGate) {
        int gateHalf = southGate ? 6 : 0;
        for (int x = -radius; x <= radius; x++) {
            wallColumn(out, cx + x, y, cz - radius, height, true);
            if (!southGate || Math.abs(x) > gateHalf) wallColumn(out, cx + x, y, cz + radius, height, true);
        }
        for (int z = -radius + 1; z < radius; z++) {
            wallColumn(out, cx - radius, y, cz + z, height, false);
            wallColumn(out, cx + radius, y, cz + z, height, false);
        }
        for (int offset = -radius + 8; offset <= radius - 8; offset += 14) {
            wallButtress(out, cx + offset, y, cz - radius, 0, -1);
            if (!southGate || Math.abs(offset) > gateHalf + 2)
                wallButtress(out, cx + offset, y, cz + radius, 0, 1);
            if (Math.abs(offset) < radius - 6) {
                wallButtress(out, cx - radius, y, cz + offset, -1, 0);
                wallButtress(out, cx + radius, y, cz + offset, 1, 0);
            }
        }
        if (southGate) {
            for (int x = -gateHalf - 2; x <= gateHalf + 2; x++) {
                boolean column = Math.abs(x) >= gateHalf;
                for (int yy = 0; yy <= height + 2; yy++) {
                    boolean arch = yy >= height && Math.abs(x) <= gateHalf + 1;
                    if (column || arch) out.add(e(cx + x, y + yy, cz + radius,
                            Math.floorMod(x + yy, 8) == 0
                                    ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
                }
            }
        }
    }

    static void wallOpening(List<BlockEdit> out, int cx, int y, int cz,
                            boolean alongX, int halfWidth, int clearance) {
        for (int side = -halfWidth; side <= halfWidth; side++) {
            int x = cx + (alongX ? side : 0);
            int z = cz + (alongX ? 0 : side);
            out.add(e(x, y - 1, z, Material.POLISHED_ANDESITE));
            for (int yy = 0; yy <= clearance; yy++) out.add(e(x, y + yy, z, Material.AIR));
            out.add(e(x, y + clearance + 1, z,
                    Math.floorMod(side, 4) == 0 ? Material.CHISELED_STONE_BRICKS
                            : Material.STONE_BRICKS));
        }
        for (int side : new int[]{-halfWidth - 1, halfWidth + 1}) {
            int x = cx + (alongX ? side : 0);
            int z = cz + (alongX ? 0 : side);
            for (int yy = 0; yy <= clearance + 2; yy++) {
                out.add(e(x, y + yy, z,
                        Math.floorMod(side + yy, 7) == 0
                                ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
            }
        }
    }

    static void wallColumn(List<BlockEdit> out, int x, int y, int z, int height, boolean crest) {
        for (int depth = 1; depth <= 3; depth++) {
            out.add(e(x, y - depth, z, depth == 1 ? Material.STONE_BRICKS : Material.COBBLESTONE));
        }
        for (int yy = 0; yy < height; yy++) {
            Material block = ((x + z + yy) & 9) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
            out.add(e(x, y + yy, z, block));
            if (crest && yy >= height - 2 && (Math.abs(x + z + yy) & 1) == 0) {
                out.add(e(x, y + yy, z + (Math.floorMod(x + z, 2) == 0 ? 1 : -1), Material.STONE_BRICKS));
            }
        }
        out.add(e(x, y + height, z, Material.STONE_BRICK_WALL));
    }

    static void lowWall(List<BlockEdit> out, Site site, int radius, Material wall) {
        for (int degree = 0; degree < 360; degree += 3) {
            boolean gate = degree < 12 || degree > 348 || (degree > 78 && degree < 102)
                    || (degree > 168 && degree < 192) || (degree > 258 && degree < 282);
            if (gate) continue;
            double a = Math.toRadians(degree);
            out.add(e(site.x() + (int) Math.round(Math.cos(a) * radius), site.baseY() + 1,
                    site.z() + (int) Math.round(Math.sin(a) * radius), wall));
        }
    }

    static void spawnerShrines(List<BlockEdit> out, Site site, int y, Material accent) {
        for (int[] o : new int[][]{{0,0},{-20,-10},{19,13},{18,38}}) {
            int cx = site.x() + o[0], cz = site.z() + o[1];
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                out.add(e(cx + x, y - 1, cz + z, Math.abs(x) == 2 || Math.abs(z) == 2 ? Material.MOSSY_STONE_BRICKS : accent));
            out.add(e(cx, y, cz, Material.SPAWNER));
            for (int[] p : new int[][]{{-2,-2},{2,-2},{-2,2},{2,2}}) {
                out.add(e(cx + p[0], y, cz + p[1], Material.IRON_BARS));
                out.add(e(cx + p[0], y + 1, cz + p[1], Material.SOUL_LANTERN));
            }
        }
    }

    static void rewardPedestal(List<BlockEdit> out, int cx, int y, int cz, Material accent) {
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            out.add(e(cx + x, y - 1, cz + z, Math.abs(x) == 3 || Math.abs(z) == 3 ? Material.CHISELED_STONE_BRICKS : accent));
            for (int yy = 0; yy <= 3; yy++) out.add(e(cx + x, y + yy, cz + z, Material.AIR));
        }
        out.add(e(cx, y, cz, Material.CHEST));
    }

    static void gateFrame(List<BlockEdit> out, World world, Registry registry, String id,
                          int cx, int y, int cz, int halfWidth, int height) {
        auditedTower(out, world, registry, id + "-west", cx - halfWidth - 4, y, cz,
                5, height + 5, Material.DARK_OAK_PLANKS);
        auditedTower(out, world, registry, id + "-east", cx + halfWidth + 4, y, cz,
                5, height + 5, Material.DARK_OAK_PLANKS);
        for (int x = -halfWidth; x <= halfWidth; x++) for (int yy = 0; yy <= height; yy++) {
            boolean pillar = Math.abs(x) >= halfWidth - 1;
            boolean arch = yy >= height - 3 && Math.abs(x) <= halfWidth - Math.max(0, yy - (height - 3));
            if (pillar || arch) out.add(e(cx + x, y + yy, cz,
                    ((x + yy) & 7) == 0 ? Material.MOSSY_STONE_BRICKS : Material.DEEPSLATE_BRICKS));
        }
        for (int x : new int[]{-halfWidth - 1, halfWidth + 1}) {
            pillar(out, cx + x, y + 1, cz + 2, 3, Material.POLISHED_DEEPSLATE_WALL, Material.SOUL_LANTERN);
        }
    }

    static void ritualAltar(List<BlockEdit> out, int cx, int y, int cz) {
        for (int radius = 5; radius >= 1; radius--) {
            int layerY = y + 5 - radius;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
                if (Math.abs(x) == radius || Math.abs(z) == radius || radius == 1)
                    out.add(e(cx + x, layerY, cz + z, radius == 1 ? Material.EMERALD_BLOCK
                            : ((x + z) & 1) == 0 ? Material.CHISELED_DEEPSLATE : Material.POLISHED_DEEPSLATE));
        }
        out.add(e(cx, y + 5, cz, Material.RESPAWN_ANCHOR));
    }

    static void ritualSeal(List<BlockEdit> out, int cx, int y, int cz) {
        for (int x = -5; x <= 5; x++) for (int yy = 0; yy <= 7; yy++) {
            boolean edge = Math.abs(x) == 5 || yy == 0 || yy == 7;
            out.add(e(cx + x, y + yy, cz, edge ? Material.DEEPSLATE_BRICKS
                    : Math.abs(x) <= 1 && yy >= 2 && yy <= 5 ? Material.EMERALD_BLOCK : Material.IRON_BARS));
        }
    }

    static void terraces(List<BlockEdit> out, int cx, int y, int cz) {
        // Three continuous, supported seating bands. The former implementation drew
        // one-block rings at unrelated heights, which looked like cut or floating lips
        // across the arena floor. The northern ceremonial aisle remains fully open.
        for (int dx = -44; dx <= 44; dx++) {
            for (int dz = -44; dz <= 44; dz++) {
                double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (distance < 34.0D || distance > 44.5D) continue;
                boolean northOpening = dz < -18
                        && Math.abs(dx) <= BOSS_GATE_CLEAR_HALF_WIDTH + 3;
                if (northOpening) continue;

                int level = distance < 37.5D ? 0 : distance < 41.0D ? 1 : 2;
                int topY = y + level;
                for (int yy = y; yy <= topY; yy++) {
                    Material material = yy == topY
                            ? Math.floorMod(dx * 11 + dz * 7, 13) < 2
                            ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_DEEPSLATE
                            : Material.DEEPSLATE_BRICKS;
                    out.add(e(cx + dx, yy, cz + dz, material));
                }
                for (int yy = topY + 1; yy <= topY + 3; yy++) {
                    out.add(e(cx + dx, yy, cz + dz, Material.AIR));
                }
            }
        }
    }

    static void bossDais(List<BlockEdit> out, int cx, int y, int cz) {
        for (int radius = 10; radius >= 3; radius -= 2) {
            int yy = y + (10 - radius) / 2;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
                if (x * x + z * z <= radius * radius) out.add(e(cx + x, yy, cz + z,
                        radius <= 4 ? Material.EMERALD_BLOCK : Material.POLISHED_DEEPSLATE));
        }
    }

    static void circleWall(List<BlockEdit> out, int cx, int y, int cz, int radius, int height, Material material) {
        for (int degree = 0; degree < 360; degree++) {
            if (degree > 258 && degree < 282) continue;
            double a = Math.toRadians(degree);
            int x = cx + (int) Math.round(Math.cos(a) * radius), z = cz + (int) Math.round(Math.sin(a) * radius);
            for (int yy = 0; yy < height; yy++) out.add(e(x, y + yy, z,
                    yy == height - 1 && degree % 8 < 4 ? Material.DEEPSLATE_BRICK_WALL : material));
        }
    }

    static void circleFloor(List<BlockEdit> out, int cx, int y, int cz, int radius, Material a, Material b) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            if (x * x + z * z <= radius * radius) out.add(e(cx + x, y, cz + z,
                    Math.floorMod(x * 13 + z * 7, 17) < 3 ? b : a));
    }

    static void straightRoad(List<BlockEdit> out, int x1, int y, int z1, int x2, int z2, int width, Material material) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        int halfWidth = Math.max(1, width / 2);
        for (int step = 0; step <= steps; step++) {
            double t = steps == 0 ? 0.0D : step / (double) steps;
            int cx = (int) Math.round(x1 + (x2 - x1) * t), cz = (int) Math.round(z1 + (z2 - z1) * t);
            for (int ox = -halfWidth; ox <= halfWidth; ox++) {
                for (int oz = -halfWidth; oz <= halfWidth; oz++) {
                    if (ox * ox + oz * oz > halfWidth * halfWidth + halfWidth) continue;
                    out.add(e(cx + ox, y - 1, cz + oz, material));
                    for (int yy = 0; yy <= 3; yy++) {
                        out.add(e(cx + ox, y + yy, cz + oz, Material.AIR));
                    }
                }
            }
        }
    }

    static void barricade(List<BlockEdit> out, int cx, int y, int cz, boolean alongX) {
        for (int o = -7; o <= 7; o++) {
            int x = cx + (alongX ? o : 0), z = cz + (alongX ? 0 : o);
            out.add(e(x, y, z, (o & 1) == 0 ? Material.DARK_OAK_FENCE : Material.SPRUCE_PLANKS));
        }
    }

    static void lampsAround(List<BlockEdit> out, Registry registry,
                            int cx, int y, int cz, int radius, int count) {
        for (int i = 0; i < count; i++) {
            double base = Math.PI * 2.0D * i / count;
            for (int attempt = 0; attempt < 18; attempt++) {
                int wave = (attempt + 1) / 2;
                double angularOffset = attempt == 0 ? 0.0D
                        : (attempt % 2 == 0 ? 1.0D : -1.0D)
                        * wave * Math.PI / (count * 5.0D);
                int localRadius = radius + (attempt == 0 ? 0 : (attempt % 3 - 1) * 3);
                int x = cx + (int) Math.round(Math.cos(base + angularOffset) * localRadius);
                int z = cz + (int) Math.round(Math.sin(base + angularOffset) * localRadius);
                if (placeLampIfClear(out, registry, x, y, z)) break;
            }
        }
    }

    static boolean placeLampIfClear(List<BlockEdit> out, Registry registry,
                                    int x, int y, int z) {
        if (registry.blocksHouseOrEntrance(x, y, z)) return false;
        OverworldCampaignTerrain148.lamp(out, x, y, z);
        return true;
    }


    static void connectedPortcullis(List<BlockEdit> out, int cx, int y, int cz, int halfWidth, int height) {
        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int yy = 0; yy <= height; yy++) {
                boolean frame = Math.abs(x) == halfWidth || yy == height;
                Material material = frame ? Material.DEEPSLATE_BRICKS : Material.IRON_BARS;
                out.add(e(cx + x, y + yy, cz, material));
            }
        }
        for (int x = -halfWidth; x <= halfWidth; x += Math.max(2, halfWidth)) {
            for (int z = -1; z <= 1; z++) {
                out.add(e(cx + x, y + height + 1, cz + z, Material.CHAIN));
            }
        }
    }

    static void wallButtress(List<BlockEdit> out, int x, int y, int z, int dx, int dz) {
        for (int step = 0; step < 4; step++) {
            int px = x + dx * step;
            int pz = z + dz * step;
            for (int depth = 1; depth <= 3; depth++) {
                out.add(e(px, y - depth, pz,
                        depth == 1 ? Material.STONE_BRICKS : Material.COBBLESTONE));
            }
            for (int yy = 0; yy <= 3 - step; yy++) {
                out.add(e(px, y + yy, pz, yy == 3 - step ? Material.STONE_BRICK_WALL : Material.STONE_BRICKS));
            }
        }
    }
    static void crystals(List<BlockEdit> out, Site site, int count) {
        for (int i = 0; i < count; i++) {
            double a = Math.toRadians(Math.floorMod(i * 137 + site.style().ordinal() * 29, 360));
            int radius = 15 + Math.floorMod(i * 19, Math.max(16, site.radius() - 18));
            int x = site.x() + (int) Math.round(Math.cos(a) * radius), z = site.z() + (int) Math.round(Math.sin(a) * radius);
            int height = 1 + Math.floorMod(i * 7, 4);
            for (int y = 0; y < height; y++) out.add(e(x, site.baseY() + 1 + y, z,
                    y == height - 1 ? Material.VERDANT_FROGLIGHT : Material.EMERALD_BLOCK));
        }
    }

    static void corruptionCluster(List<BlockEdit> out, int cx, int y, int cz, int count) {
        for (int i = 0; i < count; i++) {
            double angle = Math.toRadians(Math.floorMod(i * 137, 360));
            int radius = 2 + Math.floorMod(i * 5, 7);
            int x = cx + (int) Math.round(Math.cos(angle) * radius);
            int z = cz + (int) Math.round(Math.sin(angle) * radius);
            int height = 1 + Math.floorMod(i * 3, 3);
            out.add(e(x, y - 1, z, Material.MOSSY_COBBLESTONE));
            for (int yy = 0; yy < height; yy++) out.add(e(x, y + yy, z,
                    yy == height - 1 ? Material.VERDANT_FROGLIGHT : Material.EMERALD_BLOCK));
        }
    }

    static void pillar(List<BlockEdit> out, int x, int y, int z, int height, Material material, Material cap) {
        for (int yy = 0; yy < height; yy++) out.add(e(x, y + yy, z, material));
        out.add(e(x, y + height, z, cap));
    }


    static void villageHouse(List<BlockEdit> out, World world, Registry registry, String id,
                             int cx, int y, int cz, int width, int depth, int floors,
                             boolean roofAlongX, Material plaster, int variant, Facing front) {
        house(out, world, registry, id, cx, y, cz, width, depth, floors,
                roofAlongX, plaster, front);
        int hx = width / 2, hz = depth / 2;
        int radius = front == Facing.NORTH || front == Facing.SOUTH ? hz : hx;
        int px = -front.dz, pz = front.dx;
        for (int side = -3; side <= 3; side++) {
            int x = cx + front.dx * (radius + 1) + px * side;
            int z = cz + front.dz * (radius + 1) + pz * side;
            // A bottom slab left a half-block trench in front of every doorway.
            // Full smooth stone keeps the porch flush with the registered path.
            out.add(e(x, y - 1, z, Material.SMOOTH_STONE));
            if (Math.abs(side) == 3) out.add(e(x, y, z, Material.SPRUCE_FENCE));
        }
        int chimneyX = cx + (variant % 2 == 0 ? -hx + 2 : hx - 2);
        int chimneyZ = cz + (variant % 3 == 0 ? hz - 2 : -hz + 2);
        int chimneyTop = y + floors * 5 + 4;
        registry.registerChimney(new ChimneySpec(id, chimneyX, y, chimneyTop, chimneyZ));
        out.add(e(chimneyX, y, chimneyZ, Material.SMOKER));
        for (int yy = y + 1; yy <= chimneyTop; yy++) {
            out.add(e(chimneyX, yy, chimneyZ, Material.BRICKS));
        }
        out.add(e(chimneyX, chimneyTop + 1, chimneyZ, Material.CAMPFIRE));
        if (variant % 3 == 0) {
            out.add(e(cx - hx + 2, y, cz + 1, Material.LECTERN));
            out.add(e(cx - hx + 2, y, cz - 1, Material.BOOKSHELF));
        } else if (variant % 3 == 1) {
            out.add(e(cx + hx - 2, y, cz, Material.SMOKER));
            out.add(e(cx + hx - 3, y, cz + 2, Material.CAULDRON));
        } else {
            out.add(e(cx + hx - 2, y, cz, Material.LOOM));
            out.add(e(cx - hx + 2, y, cz, Material.BARREL));
        }
        for (int side : new int[]{-1, 1}) {
            int x = cx + front.dx * (radius + 1) + px * side * Math.max(3,
                    (front == Facing.NORTH || front == Facing.SOUTH ? hx : hz) - 2);
            int z = cz + front.dz * (radius + 1) + pz * side * Math.max(3,
                    (front == Facing.NORTH || front == Facing.SOUTH ? hx : hz) - 2);
            Material flower = variant % 2 == 0
                    ? Material.POTTED_POPPY : Material.POTTED_CORNFLOWER;
            // A full support block finishes exactly at walk level. Bottom slabs ended
            // half a block below the pot and made every planter appear to float.
            out.add(e(x, y - 1, z, Material.SPRUCE_PLANKS));
            out.add(e(x, y, z, flower));
            registry.registerPlanter(new PlanterSpec(id + "-planter-" + side,
                    x, y, z, flower));
        }

        int style = Math.floorMod(variant, 4);
        if (style == 0) sideAwning(out, cx, y, cz, hx, hz, true);
        else if (style == 1) sideAwning(out, cx, y, cz, hx, hz, false);
        else if (style == 2 && floors > 1) balcony(out, cx, y + 5, cz, hx, hz, front);
        else rearWorkshop(out, cx, y, cz, hx, hz, front);
    }


    private static void decorateHouseInterior(List<BlockEdit> out, String id,
                                              int cx, int y, int cz, int hx, int hz,
                                              int floors, Facing front) {
        int lateralRadius = front == Facing.NORTH || front == Facing.SOUTH ? hx : hz;
        int inwardRadius = front == Facing.NORTH || front == Facing.SOUTH ? hz : hx;
        String role = id.toLowerCase(java.util.Locale.ROOT);
        for (int floor = 0; floor < floors; floor++) {
            int walkY = y + floor * 5 + (floor == 0 ? 0 : 1);
            Material carpet = role.contains("military") || role.contains("citadel")
                    ? Material.GRAY_CARPET
                    : role.contains("commercial") || role.contains("bakery")
                    ? Material.RED_CARPET : Material.BROWN_CARPET;
            interiorRug(out, cx, walkY, cz, hx, hz, floors, front, carpet);
            ceilingBeams(out, cx, walkY, cz, hx, hz, floors, front);
            interiorColumns(out, cx, walkY, cz, hx, hz, floors, front);
            sideWallStorage(out, cx, walkY, cz, hx, hz, floors, front, floor);
            if (floor > 0) {
                if (role.contains("inn") || role.contains("lodge")
                        || role.contains("home") || role.contains("barracks")) {
                    bedroom(out, cx, walkY, cz, hx, hz, floors, front,
                            role.contains("barracks") ? Material.GRAY_BED : Material.RED_BED);
                } else {
                    storageRoom(out, cx, walkY, cz, hx, hz, floors, front,
                            role.contains("warehouse") || role.contains("supply"));
                }
                continue;
            }
            if (role.contains("smithy") || role.contains("armory")) {
                workshop(out, cx, walkY, cz, hx, hz, floors, front);
            } else if (role.contains("bakery")) {
                kitchen(out, cx, walkY, cz, hx, hz, floors, front, true);
                shopCounter(out, cx, walkY, cz, hx, hz, floors, front);
            } else if (role.contains("cartographer")) {
                study(out, cx, walkY, cz, hx, hz, floors, front, true);
            } else if (role.contains("hall") || role.contains("great-hall")) {
                longTable(out, cx, walkY, cz, hx, hz, floors, front);
                study(out, cx, walkY, cz, hx, hz, floors, front, false);
                greatHallDetails(out, cx, walkY, cz, hx, hz, floors, front);
            } else if (role.contains("barn") || role.contains("warehouse")
                    || role.contains("supply")) {
                storageRoom(out, cx, walkY, cz, hx, hz, floors, front, true);
            } else if (role.contains("shop")) {
                shopCounter(out, cx, walkY, cz, hx, hz, floors, front);
                study(out, cx, walkY, cz, hx, hz, floors, front, false);
            } else if (role.contains("inn") || role.contains("lodge")) {
                longTable(out, cx, walkY, cz, hx, hz, floors, front);
                kitchen(out, cx, walkY, cz, hx, hz, floors, front, false);
            } else if (role.contains("barracks")) {
                bedroom(out, cx, walkY, cz, hx, hz, floors, front, Material.GRAY_BED);
                workshop(out, cx, walkY, cz, hx, hz, floors, front);
            } else {
                bedroom(out, cx, walkY, cz, hx, hz, floors, front, Material.RED_BED);
                kitchen(out, cx, walkY, cz, hx, hz, floors, front, false);
                diningSet(out, cx, walkY, cz, hx, hz, floors, front);
            }
        }
    }

    private static void ceilingBeams(List<BlockEdit> out, int cx, int y, int cz,
                                     int hx, int hz, int floors, Facing front) {
        int lateralMax = Math.max(2, (front == Facing.NORTH || front == Facing.SOUTH ? hx : hz) - 2);
        int inwardMax = Math.max(2, (front == Facing.NORTH || front == Facing.SOUTH ? hz : hx) - 2);
        for (int inward = -inwardMax; inward <= inwardMax; inward += 4) {
            for (int lateral = -lateralMax; lateral <= lateralMax; lateral++) {
                putInterior(out, cx, y, cz, hx, hz, floors, front,
                        lateral, inward, 4, Material.STRIPPED_SPRUCE_LOG);
            }
        }
    }

    private static void interiorColumns(List<BlockEdit> out, int cx, int y, int cz,
                                        int hx, int hz, int floors, Facing front) {
        int lateral = Math.max(2, (front == Facing.NORTH || front == Facing.SOUTH ? hx : hz) - 2);
        int inward = Math.max(2, (front == Facing.NORTH || front == Facing.SOUTH ? hz : hx) - 2);
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            for (int dy = 1; dy <= 3; dy++) {
                putInterior(out, cx, y, cz, hx, hz, floors, front,
                        sx * lateral, sz * inward, dy, Material.STRIPPED_DARK_OAK_LOG);
            }
        }
    }

    private static void sideWallStorage(List<BlockEdit> out, int cx, int y, int cz,
                                        int hx, int hz, int floors, Facing front, int floor) {
        int lateral = Math.max(2, (front == Facing.NORTH || front == Facing.SOUTH ? hx : hz) - 2);
        int inward = Math.max(2, (front == Facing.NORTH || front == Facing.SOUTH ? hz : hx) - 3);
        for (int side : new int[]{-1, 1}) {
            putInterior(out, cx, y, cz, hx, hz, floors, front,
                    side * lateral, inward, 0, floor % 2 == 0 ? Material.BARREL : Material.CHEST);
            putInterior(out, cx, y, cz, hx, hz, floors, front,
                    side * lateral, inward - 2, 0, Material.BOOKSHELF);
            putInterior(out, cx, y, cz, hx, hz, floors, front,
                    side * lateral, inward - 3, 0, Material.SPRUCE_SLAB);
        }
    }

    private static void greatHallDetails(List<BlockEdit> out, int cx, int y, int cz,
                                         int hx, int hz, int floors, Facing front) {
        int back = Math.max(3, (front == Facing.NORTH || front == Facing.SOUTH ? hz : hx) - 3);
        for (int lateral = -5; lateral <= 5; lateral += 2) {
            putInterior(out, cx, y, cz, hx, hz, floors, front,
                    lateral, back, 0, Math.abs(lateral) == 5 ? Material.BOOKSHELF : Material.CHISELED_BOOKSHELF);
        }
        for (int lateral : new int[]{-4, 4}) {
            putInterior(out, cx, y, cz, hx, hz, floors, front, lateral, 0, 0, Material.TARGET);
        }
    }

    private static void interiorRug(List<BlockEdit> out, int cx, int y, int cz,
                                    int hx, int hz, int floors, Facing front,
                                    Material carpet) {
        for (int lateral = -2; lateral <= 2; lateral++) {
            for (int inward = -1; inward <= 1; inward++) {
                putInterior(out, cx, y, cz, hx, hz, floors, front,
                        lateral, inward, 0, carpet);
            }
        }
    }

    private static void diningSet(List<BlockEdit> out, int cx, int y, int cz,
                                  int hx, int hz, int floors, Facing front) {
        putInterior(out, cx, y, cz, hx, hz, floors, front, 0, 1, 0,
                Material.DARK_OAK_FENCE);
        putInterior(out, cx, y, cz, hx, hz, floors, front, 0, 1, 1,
                Material.SPRUCE_PRESSURE_PLATE);
        putInterior(out, cx, y, cz, hx, hz, floors, front, -2, 1, 0,
                Material.SPRUCE_SLAB);
        putInterior(out, cx, y, cz, hx, hz, floors, front, 2, 1, 0,
                Material.SPRUCE_SLAB);
    }

    private static void longTable(List<BlockEdit> out, int cx, int y, int cz,
                                  int hx, int hz, int floors, Facing front) {
        for (int inward = -2; inward <= 3; inward += 2) {
            putInterior(out, cx, y, cz, hx, hz, floors, front, 0, inward, 0,
                    Material.DARK_OAK_FENCE);
            putInterior(out, cx, y, cz, hx, hz, floors, front, 0, inward, 1,
                    Material.SPRUCE_PRESSURE_PLATE);
            putInterior(out, cx, y, cz, hx, hz, floors, front, -2, inward, 0,
                    Material.SPRUCE_SLAB);
            putInterior(out, cx, y, cz, hx, hz, floors, front, 2, inward, 0,
                    Material.SPRUCE_SLAB);
        }
    }

    private static void bedroom(List<BlockEdit> out, int cx, int y, int cz,
                                int hx, int hz, int floors, Facing front,
                                Material bed) {
        placeBed(out, cx, y, cz, hx, hz, floors, front, -3, 2, bed);
        if ((front == Facing.NORTH || front == Facing.SOUTH ? hx : hz) >= 7) {
            placeBed(out, cx, y, cz, hx, hz, floors, front, 3, 2, bed);
        }
        putInterior(out, cx, y, cz, hx, hz, floors, front, 0, 3, 0,
                Material.BARREL);
        putInterior(out, cx, y, cz, hx, hz, floors, front, 0, 2, 0,
                Material.FLOWER_POT);
    }

    private static void kitchen(List<BlockEdit> out, int cx, int y, int cz,
                                int hx, int hz, int floors, Facing front,
                                boolean bakery) {
        int back = Math.max(2, (front == Facing.NORTH || front == Facing.SOUTH ? hz : hx) - 2);
        putInterior(out, cx, y, cz, hx, hz, floors, front, -2, back, 0,
                bakery ? Material.SMOKER : Material.FURNACE);
        putInterior(out, cx, y, cz, hx, hz, floors, front, 0, back, 0,
                Material.BARREL);
        putInterior(out, cx, y, cz, hx, hz, floors, front, 2, back, 0,
                Material.WATER_CAULDRON);
        putInterior(out, cx, y, cz, hx, hz, floors, front, -1, back - 1, 0,
                bakery ? Material.CAKE : Material.CRAFTING_TABLE);
    }

    private static void workshop(List<BlockEdit> out, int cx, int y, int cz,
                                 int hx, int hz, int floors, Facing front) {
        int back = Math.max(2, (front == Facing.NORTH || front == Facing.SOUTH ? hz : hx) - 2);
        putInterior(out, cx, y, cz, hx, hz, floors, front, -3, back, 0,
                Material.BLAST_FURNACE);
        putInterior(out, cx, y, cz, hx, hz, floors, front, -1, back, 0,
                Material.SMITHING_TABLE);
        putInterior(out, cx, y, cz, hx, hz, floors, front, 1, back, 0,
                Material.ANVIL);
        putInterior(out, cx, y, cz, hx, hz, floors, front, 3, back, 0,
                Material.GRINDSTONE);
        putInterior(out, cx, y, cz, hx, hz, floors, front, 0, 1, 0,
                Material.BARREL);
    }

    private static void study(List<BlockEdit> out, int cx, int y, int cz,
                              int hx, int hz, int floors, Facing front,
                              boolean cartography) {
        int back = Math.max(2, (front == Facing.NORTH || front == Facing.SOUTH ? hz : hx) - 2);
        putInterior(out, cx, y, cz, hx, hz, floors, front, -3, back, 0,
                Material.BOOKSHELF);
        putInterior(out, cx, y, cz, hx, hz, floors, front, -2, back, 0,
                Material.BOOKSHELF);
        putInterior(out, cx, y, cz, hx, hz, floors, front, -1, back, 0,
                cartography ? Material.CARTOGRAPHY_TABLE : Material.LECTERN);
        putInterior(out, cx, y, cz, hx, hz, floors, front, 1, back, 0,
                Material.LECTERN);
        putInterior(out, cx, y, cz, hx, hz, floors, front, 3, back, 0,
                Material.CHEST);
    }

    private static void storageRoom(List<BlockEdit> out, int cx, int y, int cz,
                                    int hx, int hz, int floors, Facing front,
                                    boolean bulk) {
        int back = Math.max(2, (front == Facing.NORTH || front == Facing.SOUTH ? hz : hx) - 2);
        for (int lateral = -4; lateral <= 4; lateral += 2) {
            putInterior(out, cx, y, cz, hx, hz, floors, front, lateral, back, 0,
                    Math.floorMod(lateral, 4) == 0 ? Material.BARREL : Material.CHEST);
            if (bulk) putInterior(out, cx, y, cz, hx, hz, floors, front,
                    lateral, back, 1, Material.HAY_BLOCK);
        }
        putInterior(out, cx, y, cz, hx, hz, floors, front, 0, 0, 0,
                bulk ? Material.COMPOSTER : Material.CRAFTING_TABLE);
    }

    private static void shopCounter(List<BlockEdit> out, int cx, int y, int cz,
                                    int hx, int hz, int floors, Facing front) {
        for (int lateral = -3; lateral <= 3; lateral++) {
            putInterior(out, cx, y, cz, hx, hz, floors, front, lateral, 2, 0,
                    Math.abs(lateral) == 3 ? Material.BARREL : Material.SPRUCE_PLANKS);
            putInterior(out, cx, y, cz, hx, hz, floors, front, lateral, 2, 1,
                    Material.SPRUCE_SLAB);
        }
        putInterior(out, cx, y, cz, hx, hz, floors, front, 0, 3, 0,
                Material.CHEST);
    }

    private static void placeBed(List<BlockEdit> out, int cx, int y, int cz,
                                 int hx, int hz, int floors, Facing front,
                                 int lateral, int inward, Material bed) {
        Facing bedFacing = opposite(front);
        int footX = localX(cx, front, lateral, inward);
        int footZ = localZ(cz, front, lateral, inward);
        int headX = footX + bedFacing.dx;
        int headZ = footZ + bedFacing.dz;
        if (!insideInterior(footX, footZ, cx, cz, hx, hz)
                || !insideInterior(headX, headZ, cx, cz, hx, hz)
                || ladderConflict(footX, footZ, cx, cz, hx, hz, floors)
                || ladderConflict(headX, headZ, cx, cz, hx, hz, floors)) return;
        String facing = bedFacing.name().toLowerCase(java.util.Locale.ROOT);
        String name = bed.name().toLowerCase(java.util.Locale.ROOT);
        out.add(data(footX, y, footZ, bed,
                "minecraft:" + name + "[facing=" + facing
                        + ",occupied=false,part=foot]"));
        out.add(data(headX, y, headZ, bed,
                "minecraft:" + name + "[facing=" + facing
                        + ",occupied=false,part=head]"));
    }

    private static void putInterior(List<BlockEdit> out, int cx, int y, int cz,
                                    int hx, int hz, int floors, Facing front,
                                    int lateral, int inward, int dy, Material material) {
        int x = localX(cx, front, lateral, inward);
        int z = localZ(cz, front, lateral, inward);
        if (!insideInterior(x, z, cx, cz, hx, hz)
                || ladderConflict(x, z, cx, cz, hx, hz, floors)) return;
        out.add(e(x, y + dy, z, material));
    }

    private static int localX(int cx, Facing front, int lateral, int inward) {
        return cx - front.dz * lateral - front.dx * inward;
    }

    private static int localZ(int cz, Facing front, int lateral, int inward) {
        return cz + front.dx * lateral - front.dz * inward;
    }

    private static boolean insideInterior(int x, int z, int cx, int cz, int hx, int hz) {
        return x > cx - hx && x < cx + hx && z > cz - hz && z < cz + hz;
    }

    private static boolean ladderConflict(int x, int z, int cx, int cz,
                                          int hx, int hz, int floors) {
        if (floors <= 1) return false;
        int ladderX = cx + hx - 1;
        int ladderZ = cz + Math.min(2, hz - 2);
        return Math.abs(x - ladderX) <= 1 && Math.abs(z - ladderZ) <= 1;
    }

    private static Facing opposite(Facing facing) {
        return switch (facing) {
            case NORTH -> Facing.SOUTH;
            case SOUTH -> Facing.NORTH;
            case EAST -> Facing.WEST;
            case WEST -> Facing.EAST;
        };
    }

    private static void decorateTowerLevel(List<BlockEdit> out, int cx, int y, int cz,
                                           int radius, int level) {
        out.add(e(cx + radius - 2, y, cz - radius + 2,
                level % 12 == 0 ? Material.TARGET : Material.CHEST));
        out.add(e(cx + radius - 3, y, cz - radius + 2, Material.BARREL));
        out.add(e(cx - radius + 2, y, cz + radius - 2,
                level % 12 == 0 ? Material.GRINDSTONE : Material.CAULDRON));
        for (int x = -1; x <= 1; x++) {
            out.add(e(cx + x, y, cz, level % 12 == 0
                    ? Material.GRAY_CARPET : Material.BROWN_CARPET));
        }
    }

    static void portalSanctuaryFurnishings(List<BlockEdit> out, int cx, int y, int cz) {
        for (int side : new int[]{-1, 1}) {
            int x = cx + side * 8;
            for (int z = -3; z <= 3; z += 3) {
                out.add(e(x, y, cz + z, Material.SPRUCE_SLAB));
                out.add(e(x - side, y, cz + z, Material.LECTERN));
            }
            out.add(e(cx + side * 6, y, cz + 6, Material.CHEST));
            out.add(e(cx + side * 5, y, cz + 6, Material.POLISHED_ANDESITE));
            out.add(e(cx + side * 5, y + 1, cz + 6, Material.WHITE_CANDLE));
        }
        for (int[] p : new int[][]{{-10,-7},{10,-7},{-10,7},{10,7}}) {
            out.add(e(cx + p[0], y, cz + p[1], Material.POLISHED_DEEPSLATE));
            out.add(e(cx + p[0], y + 1, cz + p[1], Material.SOUL_CAMPFIRE));
        }
        out.add(e(cx, y, cz + 7, Material.ENDER_CHEST));
        out.add(e(cx, y, cz + 5, Material.AMETHYST_BLOCK));
        out.add(e(cx, y + 1, cz + 5, Material.AMETHYST_CLUSTER));
    }

    static void arenaFurnishings(List<BlockEdit> out, int cx, int y, int cz) {
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0D * i / 8.0D;
            int x = cx + (int) Math.round(Math.cos(angle) * 27.0D);
            int z = cz + (int) Math.round(Math.sin(angle) * 27.0D);
            if (z < cz - 18 && Math.abs(x - cx) <= BOSS_GATE_CLEAR_HALF_WIDTH + 4) continue;
            out.add(e(x, y, z, Material.CHISELED_DEEPSLATE));
            out.add(e(x, y + 1, z, Material.SOUL_CAMPFIRE));
        }
        for (int side : new int[]{-1, 1}) {
            int x = cx + side * 22;
            for (int z = cz + 8; z <= cz + 16; z += 4) {
                out.add(e(x, y, z, Material.BARREL));
                out.add(e(x - side, y, z, z % 8 == 0 ? Material.CHEST : Material.GRINDSTONE));
            }
        }
    }

    private static void sideAwning(List<BlockEdit> out, int cx, int y, int cz,
                                   int hx, int hz, boolean east) {
        int side = east ? 1 : -1;
        int x = cx + side * (hx + 2);
        for (int z = -Math.min(4, hz - 1); z <= Math.min(4, hz - 1); z++) {
            out.add(e(x, y - 1, cz + z, Material.COBBLESTONE));
            out.add(e(x + side, y - 1, cz + z, Material.COBBLESTONE));
            out.add(e(x, y + 3, cz + z, Material.DARK_OAK_PLANKS));
            out.add(e(x + side, y + 3, cz + z, Material.DARK_OAK_PLANKS));
        }
        for (int z : new int[]{-Math.min(4, hz - 1), Math.min(4, hz - 1)}) {
            pillar(out, x + side, y, cz + z, 3, Material.SPRUCE_FENCE, Material.DARK_OAK_SLAB);
        }
    }

    private static void balcony(List<BlockEdit> out, int cx, int y, int cz,
                                int hx, int hz, Facing front) {
        int radius = front == Facing.NORTH || front == Facing.SOUTH ? hz : hx;
        int px = -front.dz, pz = front.dx;
        for (int depth = 0; depth <= 2; depth++) for (int side = -4; side <= 4; side++) {
            int x = cx + front.dx * (radius + depth) + px * side;
            int z = cz + front.dz * (radius + depth) + pz * side;
            out.add(e(x, y, z, Material.SPRUCE_PLANKS));
            if (depth == 2 || Math.abs(side) == 4)
                out.add(e(x, y + 1, z, Material.SPRUCE_FENCE));
        }
    }

    private static void rearWorkshop(List<BlockEdit> out, int cx, int y, int cz,
                                     int hx, int hz, Facing front) {
        int radius = front == Facing.NORTH || front == Facing.SOUTH ? hz : hx;
        for (int depth = 1; depth <= 3; depth++) for (int side = -3; side <= 3; side++) {
            int x = cx - front.dx * (radius + depth) + (front.dx == 0 ? side : 0);
            int z = cz - front.dz * (radius + depth) + (front.dz == 0 ? side : 0);
            out.add(e(x, y - 1, z, Math.floorMod(depth + side, 5) == 0
                    ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE));
            out.add(e(x, y + 3, z, Material.DARK_OAK_PLANKS));
            if (depth == 3 && Math.abs(side) == 3)
                pillar(out, x, y, z, 3, Material.STRIPPED_SPRUCE_LOG, Material.DARK_OAK_SLAB);
        }
    }

    static void villagePlaza(List<BlockEdit> out, int cx, int y, int cz) {
        for (int x = -18; x <= 18; x++) for (int z = -15; z <= 15; z++) {
            double oval = (x * x) / (18.0D * 18.0D) + (z * z) / (15.0D * 15.0D);
            if (oval > 1.0D) continue;
            Material floor = Math.floorMod(x * 11 + z * 7, 19) < 3
                    ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE;
            out.add(e(cx + x, y - 1, cz + z, floor));
            for (int yy = 0; yy <= 3; yy++) out.add(e(cx + x, y + yy, cz + z, Material.AIR));
        }
        // Chamfered basin: the water cannot escape into the plaza and every side is walkable.
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            int edge = Math.max(Math.abs(x), Math.abs(z));
            if (edge > 4 || Math.abs(x) == 4 && Math.abs(z) == 4) continue;
            out.add(e(cx + x, y - 1, cz + z,
                    edge <= 2 && (x + z & 1) == 0 ? Material.SEA_LANTERN : Material.POLISHED_ANDESITE));
            if (edge == 4) out.add(e(cx + x, y, cz + z, Material.CHISELED_STONE_BRICKS));
            else out.add(e(cx + x, y, cz + z, Material.WATER));
        }
        // A real centerpiece reads as a fountain rather than a square pool with a lamp.
        out.add(e(cx, y, cz, Material.CHISELED_STONE_BRICKS));
        out.add(e(cx, y + 1, cz, Material.ANDESITE_WALL));
        out.add(e(cx, y + 2, cz, Material.SEA_LANTERN));
        out.add(e(cx, y + 3, cz, Material.AMETHYST_CLUSTER));

        // The bell has a raised open pavilion with slender supports and a stepped roof.
        // The previous low 7x7 plank lid read as a flat wooden box and crowded the plaza.
        int bellX = cx + 11;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            if (x * x + z * z <= 18) out.add(e(bellX + x, y - 1, cz + z,
                    Math.floorMod(x * 5 + z * 7, 11) == 0
                            ? Material.MOSSY_COBBLESTONE : Material.POLISHED_ANDESITE));
        }
        for (int[] p : new int[][]{{-3,-3},{3,-3},{-3,3},{3,3}}) {
            out.add(e(bellX + p[0], y, cz + p[1], Material.CHISELED_STONE_BRICKS));
            for (int yy = 1; yy <= 5; yy++) {
                out.add(e(bellX + p[0], y + yy, cz + p[1], Material.SPRUCE_FENCE));
            }
        }
        for (int layer = 0; layer <= 4; layer++) {
            int radius = 4 - layer;
            int roofY = y + 5 + layer;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if (radius > 0 && Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                out.add(e(bellX + x, roofY, cz + z,
                        layer == 4 ? Material.DARK_OAK_SLAB : Material.DARK_OAK_PLANKS));
            }
        }
        for (int yy = 3; yy <= 8; yy++) out.add(e(bellX, y + yy, cz, Material.CHAIN));
        out.add(data(bellX, y + 2, cz, Material.BELL,
                "minecraft:bell[attachment=ceiling,facing=north,powered=false]"));
        for (int[] p : new int[][]{{-8,-7},{8,-7},{-8,7},{8,7}}) {
            pillar(out, cx + p[0], y, cz + p[1], 3, Material.SPRUCE_FENCE, Material.LANTERN);
            bench(out, cx + p[0] + (p[0] < 0 ? 1 : -1), y, cz + p[1]);
        }
        for (int[] p : new int[][]{{-14,0},{14,0},{0,-11},{0,11}}) {
            out.add(e(cx + p[0], y - 1, cz + p[1], Material.CHISELED_STONE_BRICKS));
            out.add(e(cx + p[0], y, cz + p[1], Material.SPRUCE_SLAB));
        }
        for (int[] p : new int[][]{{-13,-9},{13,-9},{-13,9},{13,9}}) {
            out.add(e(cx + p[0], y - 1, cz + p[1], Material.MOSS_BLOCK));
            out.add(e(cx + p[0], y, cz + p[1], Material.FLOWERING_AZALEA));
            out.add(e(cx + p[0] + (p[0] < 0 ? 1 : -1), y, cz + p[1], Material.SPRUCE_SLAB));
        }
    }

    static void villageStreet(List<BlockEdit> out, Registry registry,
                              int x1, int y1, int z1, int x2, int y2, int z2, int width) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        double dx = x2 - x1, dz = z2 - z1;
        double length = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        double nx = -dz / length, nz = dx / length;
        int halfWidth = Math.max(1, width / 2);
        String id = "local-street-" + x1 + "-" + z1 + "-" + x2 + "-" + z2;
        for (int step = 0; step <= steps; step++) {
            double t = steps == 0 ? 0.0D : step / (double) steps;
            double bend = Math.sin(Math.PI * t) * Math.min(2.0D, length * 0.025D);
            int cx = (int) Math.round(x1 + (x2 - x1) * t + nx * bend);
            int cy = step == steps ? y2 : (int) Math.round(y1 + (y2 - y1) * t);
            int cz = (int) Math.round(z1 + (z2 - z1) * t + nz * bend);
            for (int ox = -halfWidth; ox <= halfWidth; ox++) {
                for (int oz = -halfWidth; oz <= halfWidth; oz++) {
                    if (ox * ox + oz * oz > halfWidth * halfWidth + halfWidth) continue;
                    int x = cx + ox;
                    int z = cz + oz;
                    boolean edge = ox * ox + oz * oz >= Math.max(1, halfWidth * halfWidth - 1);
                    Material surface = edge ? Material.ANDESITE
                            : Math.floorMod(step * 3 + ox * 5 + oz * 7, 17) < 3
                            ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE;
                    out.add(e(x, cy - 1, z, surface));
                    out.add(e(x, cy - 2, z, Material.STONE_BRICKS));
                    out.add(e(x, cy - 3, z, Material.STONE));
                    for (int yy = 0; yy <= 3; yy++) out.add(e(x, cy + yy, z, Material.AIR));
                    registry.registerRoadCell(id, x, cy, z, surface);
                }
            }
            if (step % 12 == 6) {
                for (int side : new int[]{-halfWidth - 2, halfWidth + 2}) {
                    int x = (int) Math.round(cx + nx * side);
                    int z = (int) Math.round(cz + nz * side);
                    out.add(e(x, cy - 1, z, Material.MOSS_BLOCK));
                    out.add(e(x, cy, z, side < 0 ? Material.AZALEA : Material.FLOWERING_AZALEA));
                }
            }
        }
    }

    static void retainingWall(List<BlockEdit> out, int x1, int x2, int y, int z, int height) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int yy = 0; yy < height; yy++) {
                out.add(e(x, y + yy, z, Math.floorMod(x + yy, 9) == 0
                        ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
            }
            out.add(e(x, y + height, z, Material.STONE_BRICK_WALL));
        }
    }

    static void villageEntry(List<BlockEdit> out, int cx, int y, int cz, Facing travel) {
        int px = -travel.dz, pz = travel.dx;
        for (int side : new int[]{-5, 5}) {
            int x = cx + px * side;
            int z = cz + pz * side;
            for (int depth = 1; depth <= 5; depth++)
                out.add(e(x, y - depth, z, depth < 3 ? Material.STONE_BRICKS : Material.COBBLESTONE));
            pillar(out, x, y, z, 7, Material.STRIPPED_DARK_OAK_LOG, Material.LANTERN);
        }
        for (int side = -5; side <= 5; side++) {
            int x = cx + px * side;
            int z = cz + pz * side;
            out.add(e(x, y + 6 + Math.max(0, 2 - Math.abs(side) / 2), z,
                    Material.DARK_OAK_PLANKS));
        }
        for (int step = -3; step <= 3; step++) {
            int x = cx + travel.dx * step;
            int z = cz + travel.dz * step;
            out.add(e(x, y - 1, z, Material.COBBLESTONE));
            for (int yy = 0; yy <= 4; yy++) out.add(e(x, y + yy, z, Material.AIR));
        }
    }

    static void villageTree(List<BlockEdit> out, int x, int y, int z, int height) {
        for (int yy = 0; yy < height; yy++) out.add(e(x, y + yy, z, Material.OAK_LOG));
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy <= 3; dy++) {
            if (dx * dx + dz * dz + (dy - 1) * (dy - 1) > 11) continue;
            out.add(e(x + dx, y + height - 1 + dy, z + dz,
                    (dx + dz + dy) % 9 == 0 ? Material.FLOWERING_AZALEA_LEAVES : Material.OAK_LEAVES));
        }
    }

    static void bench(List<BlockEdit> out, int x, int y, int z) {
        out.add(e(x - 1, y, z, Material.SPRUCE_SLAB));
        out.add(e(x, y, z, Material.SPRUCE_SLAB));
        out.add(e(x + 1, y, z, Material.SPRUCE_SLAB));
        String backrest = "minecraft:spruce_trapdoor[facing=north,half=bottom,"
                + "open=true,powered=false,waterlogged=false]";
        out.add(data(x - 1, y + 1, z + 1, Material.SPRUCE_TRAPDOOR, backrest));
        out.add(data(x, y + 1, z + 1, Material.SPRUCE_TRAPDOOR, backrest));
        out.add(data(x + 1, y + 1, z + 1, Material.SPRUCE_TRAPDOOR, backrest));
    }

    static void outerRitualAltar(List<BlockEdit> out, int cx, int y, int cz) {
        for (int x = -10; x <= 10; x++) for (int z = -7; z <= 7; z++) {
            if ((x * x) / 100.0D + (z * z) / 49.0D > 1.0D) continue;
            out.add(e(cx + x, y - 1, cz + z,
                    Math.floorMod(x * 13 + z * 5, 17) < 3 ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_ANDESITE));
        }
        int[] pedestalX = {-6, 0, 6};
        Material[] accents = {Material.EMERALD_BLOCK, Material.GOLD_BLOCK, Material.AMETHYST_BLOCK};
        for (int i = 0; i < pedestalX.length; i++) {
            int px = cx + pedestalX[i];
            for (int radius = 2; radius >= 0; radius--) {
                int py = y + (2 - radius);
                for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                    out.add(e(px + x, py, cz + z, radius == 0 ? accents[i] : Material.CHISELED_STONE_BRICKS));
                }
            }
        }
        out.add(e(cx, y + 4, cz, Material.RESPAWN_ANCHOR));
        for (int x : new int[]{-10, 10}) pillar(out, cx + x, y, cz, 5,
                Material.POLISHED_DEEPSLATE_WALL, Material.SOUL_LANTERN);
    }

    static void invocationAltar(List<BlockEdit> out, int cx, int y, int cz) {
        for (int radius = 9; radius >= 3; radius -= 2) {
            int yy = y + (9 - radius) / 2;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radius * radius) continue;
                out.add(e(cx + x, yy, cz + z,
                        radius <= 3 ? Material.EMERALD_BLOCK
                                : Math.floorMod(x * 7 + z * 11, 13) < 2
                                ? Material.MOSSY_COBBLESTONE : Material.POLISHED_DEEPSLATE));
            }
        }
        out.add(e(cx, y + 4, cz, Material.LODESTONE));
    }

    static void monumentalArenaEntrance(List<BlockEdit> out, World world, Registry registry,
                                        int cx, int y, int cz) {
        for (int x = -23; x <= 23; x++) for (int z = -4; z <= 7; z++) {
            out.add(e(cx + x, y - 1, cz + z,
                    Math.floorMod(x * 7 + z * 11, 17) < 3
                            ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_DEEPSLATE));
        }
        for (int z = -2; z <= 6; z++) {
            for (int x = -22; x <= 22; x++) {
                int ax = Math.abs(x);
                for (int yy = 0; yy <= 18; yy++) {
                    boolean towerMass = ax >= 10;
                    boolean archCrown = yy >= 10 && yy <= 15
                            && ax <= Math.max(3, 10 - (yy - 10) * 2);
                    boolean upperBridge = yy >= 15 && yy <= 17 && ax < 10;
                    if (towerMass || archCrown || upperBridge) {
                        Material material = Math.floorMod(x + yy * 3 + z * 5, 13) < 2
                                ? Material.MOSSY_STONE_BRICKS : Material.DEEPSLATE_BRICKS;
                        out.add(e(cx + x, y + yy, cz + z, material));
                    } else if (ax <= BOSS_GATE_CLEAR_HALF_WIDTH && yy <= 9) {
                        out.add(e(cx + x, y + yy, cz + z, Material.AIR));
                    }
                }
            }
        }
        // Raised portcullis: visible and complete without blocking the playable corridor.
        for (int x = -6; x <= 6; x += 2) for (int yy = 10; yy <= 14; yy++) {
            out.add(e(cx + x, y + yy, cz + 2, Material.IRON_BARS));
        }
        for (int x = -22; x <= 22; x += 3) {
            out.add(e(cx + x, y + 19, cz + 2, Material.DEEPSLATE_BRICK_WALL));
        }
        // Curved wings join the rectangular gatehouse to the radius-50 arena wall.
        for (int side : new int[]{-1, 1}) {
            for (int offset = 20; offset <= 48; offset++) {
                int x = cx + side * offset;
                int curveZ = cz + (int) Math.round(50.0D
                        - Math.sqrt(Math.max(0.0D, 2500.0D - offset * offset)));
                for (int thick = -1; thick <= 1; thick++) {
                    for (int yy = 0; yy <= 9; yy++) {
                        out.add(e(x, y + yy, curveZ + thick,
                                Math.floorMod(offset + yy + thick, 11) == 0
                                        ? Material.MOSSY_STONE_BRICKS : Material.DEEPSLATE_BRICKS));
                    }
                    out.add(e(x, y + 10, curveZ + thick, Material.DEEPSLATE_BRICK_WALL));
                }
            }
        }
        auditedTower(out, world, registry, "boss-gate-west", cx - BOSS_GATE_TOWER_OFFSET,
                y, cz, BOSS_GATE_TOWER_RADIUS, 24, Material.DARK_OAK_PLANKS, Facing.NORTH);
        auditedTower(out, world, registry, "boss-gate-east", cx + BOSS_GATE_TOWER_OFFSET,
                y, cz, BOSS_GATE_TOWER_RADIUS, 24, Material.DARK_OAK_PLANKS, Facing.NORTH);
    }

    static BlockEdit e(int x, int y, int z, Material material) {
        return new BlockEdit(x, y, z, material);
    }

    static BlockEdit data(int x, int y, int z, Material material, String blockData) {
        return new BlockEdit(x, y, z, material, blockData);
    }
}
