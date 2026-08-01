package com.arlight.bingo.listeners;

import org.bukkit.Material;

import java.util.List;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;
import static com.arlight.bingo.listeners.OverworldCampaignAudit148.*;

final class OverworldCampaignArchitecture148 {
    private OverworldCampaignArchitecture148() { }

    static void house(List<BlockEdit> out, Registry registry, String id,
                      int cx, int y, int cz, int width, int depth,
                      int floors, boolean roofAlongX, Material plaster, Facing front) {
        int hx = width / 2, hz = depth / 2, height = floors * 5;
        registry.registerHouse(new HouseSpec(id, cx, y, cz, hx, hz,
                height, roofAlongX, front));
        foundation(out, cx, y - 1, cz, hx + 1, hz + 1);
        for (int x = -hx; x <= hx; x++) for (int z = -hz; z <= hz; z++) {
            out.add(e(cx + x, y - 1, cz + z, ((x + z) & 7) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE));
            boolean edge = Math.abs(x) == hx || Math.abs(z) == hz;
            for (int yy = 0; yy <= height; yy++) {
                if (!edge) { if (yy > 0) out.add(e(cx + x, y + yy, cz + z, Material.AIR)); continue; }
                boolean door = entrance(front, x, z, hx, hz) && yy <= 2;
                if (door) { out.add(e(cx + x, y + yy, cz + z, Material.AIR)); continue; }
                boolean frame = yy == 0 || yy == height || yy % 5 == 0
                        || (Math.abs(x) == hx && Math.floorMod(z, 5) == 0)
                        || (Math.abs(z) == hz && Math.floorMod(x, 5) == 0);
                boolean window = yy % 5 >= 2 && yy % 5 <= 3
                        && ((Math.abs(x) == hx && Math.floorMod(z, 6) == 0)
                        || (Math.abs(z) == hz && Math.floorMod(x, 6) == 0));
                out.add(e(cx + x, y + yy, cz + z,
                        frame ? Material.STRIPPED_DARK_OAK_LOG : window ? Material.GLASS_PANE : plaster));
            }
        }
        for (int floor = 1; floor < floors; floor++) {
            int fy = y + floor * 5;
            for (int x = -hx + 1; x <= hx - 1; x++) for (int z = -hz + 1; z <= hz - 1; z++)
                if (!(Math.abs(x) >= hx - 3 && Math.abs(z) >= hz - 3))
                    out.add(e(cx + x, fy, cz + z, Material.SPRUCE_PLANKS));
        }
        roof(out, cx, y + height + 1, cz, hx, hz, roofAlongX, plaster);
        frontPath(out, cx, y, cz, hx, hz, front, 5);
        out.add(e(cx - hx + 2, y, cz - hz + 2, Material.CRAFTING_TABLE));
        out.add(e(cx + hx - 2, y, cz - hz + 2, Material.BARREL));
        for (int floor = 0; floor < floors; floor++) {
            int ceiling = y + (floor + 1) * 5;
            out.add(e(cx, ceiling - 1, cz, Material.CHAIN));
            out.add(e(cx, ceiling - 2, cz, Material.LANTERN));
            if (floor + 1 < floors) {
                for (int yy = y + floor * 5 + 1; yy < ceiling; yy++) {
                    out.add(e(cx + hx - 2, yy, cz - hz + 2, Material.LADDER));
                }
            }
        }
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

    private static boolean entrance(Facing front, int x, int z, int hx, int hz) {
        return switch (front) {
            case NORTH -> z == -hz && Math.abs(x) <= 1;
            case SOUTH -> z == hz && Math.abs(x) <= 1;
            case EAST -> x == hx && Math.abs(z) <= 1;
            case WEST -> x == -hx && Math.abs(z) <= 1;
        };
    }

    private static void frontPath(List<BlockEdit> out, int cx, int y, int cz,
                                  int hx, int hz, Facing front, int length) {
        int radius = front == Facing.NORTH || front == Facing.SOUTH ? hz : hx;
        for (int step = 0; step <= length; step++) {
            int px = cx + front.dx * (radius + step);
            int pz = cz + front.dz * (radius + step);
            for (int side = -1; side <= 1; side++) {
                int x = px + (front.dx == 0 ? side : 0);
                int z = pz + (front.dz == 0 ? side : 0);
                out.add(e(x, y - 1, z, Math.floorMod(step + side, 5) == 0
                        ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE));
                for (int yy = 0; yy <= 2; yy++) out.add(e(x, y + yy, z, Material.AIR));
            }
        }
    }

    static void foundation(List<BlockEdit> out, int cx, int y, int cz, int hx, int hz) {
        for (int x = -hx; x <= hx; x++) for (int z = -hz; z <= hz; z++)
            for (int d = 0; d < 5; d++) out.add(e(cx + x, y - d, cz + z, d < 2 ? Material.STONE_BRICKS : Material.STONE));
    }

    static void tower(List<BlockEdit> out, int cx, int y, int cz, int radius, int height, Material roof) {
        foundation(out, cx, y - 1, cz, radius + 1, radius + 1);
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            boolean wall = Math.abs(x) == radius || Math.abs(z) == radius;
            for (int yy = 0; yy <= height; yy++) {
                if (!wall) { if (yy > 0) out.add(e(cx + x, y + yy, cz + z, Material.AIR)); continue; }
                boolean opening = z == radius && Math.abs(x) <= 1 && yy <= 2;
                out.add(e(cx + x, y + yy, cz + z, opening ? Material.AIR
                        : ((x + z + yy) & 11) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
            }
        }
        for (int x = -radius - 2; x <= radius + 2; x++) for (int z = -radius - 2; z <= radius + 2; z++)
            if (Math.abs(x) + Math.abs(z) <= radius * 2 + 2) out.add(e(cx + x, y + height + 1, cz + z, roof));
        out.add(e(cx, y + height, cz, Material.CHAIN));
        out.add(e(cx, y + height - 1, cz, Material.CHAIN));
        out.add(e(cx, y + height - 2, cz, Material.LANTERN));
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
        for (int x : new int[]{-8,0,8}) pillar(out, cx + x, y, cz, 3, Material.STRIPPED_OAK_LOG, Material.CARVED_PUMPKIN);
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
            buttress(out, cx + offset, y, cz - radius + 2, false);
            if (!southGate || Math.abs(offset) > gateHalf + 2) buttress(out, cx + offset, y, cz + radius - 2, true);
            if (Math.abs(offset) < radius - 6) {
                buttress(out, cx - radius + 2, y, cz + offset, false);
                buttress(out, cx + radius - 2, y, cz + offset, true);
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
        for (int[] o : new int[][]{{0,0},{-20,-10},{19,13},{0,27}}) {
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

    static void gateFrame(List<BlockEdit> out, int cx, int y, int cz, int halfWidth, int height) {
        tower(out, cx - halfWidth - 4, y, cz, 5, height + 5, Material.DARK_OAK_PLANKS);
        tower(out, cx + halfWidth + 4, y, cz, 5, height + 5, Material.DARK_OAK_PLANKS);
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
        for (int radius = 35; radius <= 43; radius += 4) for (int degree = 0; degree < 360; degree += 2) {
            if (degree > 250 && degree < 290) continue;
            double a = Math.toRadians(degree);
            out.add(e(cx + (int) Math.round(Math.cos(a) * radius), y + (radius - 35) / 4,
                    cz + (int) Math.round(Math.sin(a) * radius), Material.DEEPSLATE_BRICKS));
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
        for (int step = 0; step <= steps; step++) {
            double t = steps == 0 ? 0.0D : step / (double) steps;
            int cx = (int) Math.round(x1 + (x2 - x1) * t), cz = (int) Math.round(z1 + (z2 - z1) * t);
            for (int side = -width / 2; side <= width / 2; side++) {
                out.add(e(cx + side, y - 1, cz, material));
                for (int yy = 0; yy <= 3; yy++) out.add(e(cx + side, y + yy, cz, Material.AIR));
            }
        }
    }

    static void barricade(List<BlockEdit> out, int cx, int y, int cz, boolean alongX) {
        for (int o = -7; o <= 7; o++) {
            int x = cx + (alongX ? o : 0), z = cz + (alongX ? 0 : o);
            out.add(e(x, y, z, (o & 1) == 0 ? Material.DARK_OAK_FENCE : Material.SPRUCE_PLANKS));
        }
    }

    static void lampsAround(List<BlockEdit> out, int cx, int y, int cz, int radius, int count) {
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2.0D * i / count;
            OverworldCampaignTerrain148.lamp(out, cx + (int) Math.round(Math.cos(a) * radius), y,
                    cz + (int) Math.round(Math.sin(a) * radius));
        }
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

    static void buttress(List<BlockEdit> out, int x, int y, int z, boolean alongZ) {
        for (int step = 0; step < 4; step++) {
            int px = alongZ ? x : x;
            int pz = alongZ ? z - step : z;
            if (!alongZ) px = x - step;
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


    static void villageHouse(List<BlockEdit> out, Registry registry, String id,
                             int cx, int y, int cz, int width, int depth, int floors,
                             boolean roofAlongX, Material plaster, int variant, Facing front) {
        house(out, registry, id, cx, y, cz, width, depth, floors,
                roofAlongX, plaster, front);
        int hx = width / 2, hz = depth / 2;
        int radius = front == Facing.NORTH || front == Facing.SOUTH ? hz : hx;
        int px = -front.dz, pz = front.dx;
        for (int side = -3; side <= 3; side++) {
            int x = cx + front.dx * (radius + 1) + px * side;
            int z = cz + front.dz * (radius + 1) + pz * side;
            out.add(e(x, y - 1, z, Material.SMOOTH_STONE_SLAB));
            if (Math.abs(side) == 3) out.add(e(x, y, z, Material.SPRUCE_FENCE));
        }
        int chimneyX = cx + (variant % 2 == 0 ? -hx + 2 : hx - 2);
        int chimneyZ = cz + (variant % 3 == 0 ? hz - 2 : -hz + 2);
        int chimneyBase = y + floors * 5 - 1;
        for (int yy = 0; yy <= 5; yy++) out.add(e(chimneyX, chimneyBase + yy, chimneyZ, Material.BRICKS));
        out.add(e(chimneyX, chimneyBase + 6, chimneyZ, Material.CAMPFIRE));
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
            int x = cx + front.dx * radius + px * side * Math.max(3,
                    (front == Facing.NORTH || front == Facing.SOUTH ? hx : hz) - 2);
            int z = cz + front.dz * radius + pz * side * Math.max(3,
                    (front == Facing.NORTH || front == Facing.SOUTH ? hx : hz) - 2);
            out.add(e(x, y + 1, z, Material.SPRUCE_TRAPDOOR));
            out.add(e(x, y + 2, z,
                    variant % 2 == 0 ? Material.POPPY : Material.CORNFLOWER));
        }

        int style = Math.floorMod(variant, 4);
        if (style == 0) sideAwning(out, cx, y, cz, hx, hz, true);
        else if (style == 1) sideAwning(out, cx, y, cz, hx, hz, false);
        else if (style == 2 && floors > 1) balcony(out, cx, y + 5, cz, hx, hz, front);
        else rearWorkshop(out, cx, y, cz, hx, hz, front);
    }

    private static void sideAwning(List<BlockEdit> out, int cx, int y, int cz,
                                   int hx, int hz, boolean east) {
        int side = east ? 1 : -1;
        int x = cx + side * (hx + 2);
        for (int z = -Math.min(4, hz - 1); z <= Math.min(4, hz - 1); z++) {
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
        // Closed basin: the water cannot escape into the plaza and every side is walkable.
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            int edge = Math.max(Math.abs(x), Math.abs(z));
            if (edge > 4 || Math.abs(x) == 4 && Math.abs(z) == 4) continue;
            out.add(e(cx + x, y - 1, cz + z,
                    edge <= 2 && (x + z & 1) == 0 ? Material.SEA_LANTERN : Material.POLISHED_ANDESITE));
            if (edge == 4) out.add(e(cx + x, y, cz + z, Material.CHISELED_STONE_BRICKS));
            else out.add(e(cx + x, y, cz + z, Material.WATER));
        }
        out.add(e(cx, y, cz, Material.CHISELED_STONE_BRICKS));
        out.add(e(cx, y + 1, cz, Material.STONE_BRICK_WALL));
        out.add(e(cx, y + 2, cz, Material.LANTERN));

        // The bell has its own pavilion instead of occupying or breaking the fountain.
        int bellX = cx + 11;
        for (int[] p : new int[][]{{-2,-2},{2,-2},{-2,2},{2,2}})
            pillar(out, bellX + p[0], y, cz + p[1], 4,
                    Material.STRIPPED_SPRUCE_LOG, Material.DARK_OAK_SLAB);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
            out.add(e(bellX + x, y + 4, cz + z, Material.DARK_OAK_PLANKS));
        out.add(e(bellX, y + 3, cz, Material.CHAIN));
        out.add(e(bellX, y + 2, cz, Material.BELL));
        for (int[] p : new int[][]{{-8,-7},{8,-7},{-8,7},{8,7}}) {
            pillar(out, cx + p[0], y, cz + p[1], 3, Material.SPRUCE_FENCE, Material.LANTERN);
            bench(out, cx + p[0] + (p[0] < 0 ? 1 : -1), y, cz + p[1]);
        }
    }

    static void villageStreet(List<BlockEdit> out, int x1, int y1, int z1,
                              int x2, int y2, int z2, int width) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        boolean mostlyHorizontal = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
        double dx = x2 - x1, dz = z2 - z1;
        double length = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        double nx = -dz / length, nz = dx / length;
        for (int step = 0; step <= steps; step++) {
            double t = steps == 0 ? 0.0D : step / (double) steps;
            double bend = Math.sin(Math.PI * t) * Math.min(2.5D, length * 0.035D);
            int cx = (int) Math.round(x1 + (x2 - x1) * t + nx * bend);
            int cy = (int) Math.round(y1 + (y2 - y1) * t);
            int cz = (int) Math.round(z1 + (z2 - z1) * t + nz * bend);
            for (int side = -width / 2; side <= width / 2; side++) {
                int x = mostlyHorizontal ? cx : cx + side;
                int z = mostlyHorizontal ? cz + side : cz;
                Material surface = Math.floorMod(step + side, 11) == 0
                        ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE;
                out.add(e(x, cy - 1, z, surface));
                out.add(e(x, cy - 2, z, Material.STONE_BRICKS));
                out.add(e(x, cy - 3, z, Material.STONE));
                for (int yy = 0; yy <= 3; yy++) out.add(e(x, cy + yy, z, Material.AIR));
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
        out.add(e(x - 1, y + 1, z + 1, Material.SPRUCE_TRAPDOOR));
        out.add(e(x, y + 1, z + 1, Material.SPRUCE_TRAPDOOR));
        out.add(e(x + 1, y + 1, z + 1, Material.SPRUCE_TRAPDOOR));
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

    static void monumentalArenaEntrance(List<BlockEdit> out, int cx, int y, int cz) {
        // A thicker gatehouse with connected wings prevents floating bars and open corners.
        tower(out, cx - 15, y, cz, 7, 24, Material.DARK_OAK_PLANKS);
        tower(out, cx + 15, y, cz, 7, 24, Material.DARK_OAK_PLANKS);
        for (int z = 0; z <= 5; z++) {
            for (int x = -15; x <= 15; x++) for (int yy = 0; yy <= 17; yy++) {
                boolean side = Math.abs(x) >= 11;
                boolean arch = yy >= 12 && Math.abs(x) <= 10 - Math.max(0, yy - 12);
                if (side || arch) out.add(e(cx + x, y + yy, cz + z,
                        Math.floorMod(x + yy + z, 9) == 0 ? Material.MOSSY_STONE_BRICKS : Material.DEEPSLATE_BRICKS));
            }
        }
        for (int x = -20; x <= 20; x++) {
            for (int z = -2; z <= 3; z++) {
                out.add(e(cx + x, y - 1, cz + z, Math.floorMod(x + z, 7) == 0 ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_DEEPSLATE));
            }
        }
        for (int side : new int[]{-1, 1}) {
            pillar(out, cx + side * 10, y + 1, cz + 5, 5, Material.POLISHED_DEEPSLATE_WALL, Material.SOUL_LANTERN);
        }
    }

    static BlockEdit e(int x, int y, int z, Material material) { return new BlockEdit(x, y, z, material); }
}
