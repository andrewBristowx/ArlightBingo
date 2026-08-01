package com.arlight.bingo.listeners;

import org.bukkit.Material;

import java.util.List;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;

final class OverworldCampaignArchitecture146 {
    private OverworldCampaignArchitecture146() { }

    static void house(List<BlockEdit> out, int cx, int y, int cz, int width, int depth,
                              int floors, boolean roofAlongX, Material plaster) {
        int hx = width / 2, hz = depth / 2, height = floors * 5;
        foundation(out, cx, y - 1, cz, hx + 1, hz + 1);
        for (int x = -hx; x <= hx; x++) for (int z = -hz; z <= hz; z++) {
            out.add(e(cx + x, y - 1, cz + z, ((x + z) & 7) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE));
            boolean edge = Math.abs(x) == hx || Math.abs(z) == hz;
            for (int yy = 0; yy <= height; yy++) {
                if (!edge) { if (yy > 0) out.add(e(cx + x, y + yy, cz + z, Material.AIR)); continue; }
                boolean door = z == hz && Math.abs(x) <= 1 && yy <= 2;
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
                if (!(Math.abs(x) <= 1 && z >= hz - 3)) out.add(e(cx + x, fy, cz + z, Material.SPRUCE_PLANKS));
        }
        roof(out, cx, y + height + 1, cz, hx + 2, hz + 2, roofAlongX);
        out.add(e(cx - hx + 2, y, cz - hz + 2, Material.CRAFTING_TABLE));
        out.add(e(cx + hx - 2, y, cz - hz + 2, Material.BARREL));
        out.add(e(cx, y + 3, cz, Material.LANTERN));
    }

    static void roof(List<BlockEdit> out, int cx, int y, int cz, int hx, int hz, boolean alongX) {
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

    static void garden(List<BlockEdit> out, int cx, int y, int cz, int width, int depth) {
        int hx = width / 2, hz = depth / 2;
        for (int x = -hx; x <= hx; x++) for (int z = -hz; z <= hz; z++) {
            boolean edge = Math.abs(x) == hx || Math.abs(z) == hz;
            out.add(e(cx + x, y - 1, cz + z, edge ? Material.OAK_PLANKS : Material.FARMLAND));
            if (!edge && (x + z) % 4 == 0) out.add(e(cx + x, y, cz + z, Material.CARROTS));
            else if (!edge && (x - z) % 5 == 0) out.add(e(cx + x, y, cz + z, Material.WHEAT));
        }
        for (int z = -hz + 1; z <= hz - 1; z++) out.add(e(cx, y - 1, cz + z, Material.WATER));
    }

    static void trainingYard(List<BlockEdit> out, int cx, int y, int cz) {
        for (int x = -12; x <= 12; x++) for (int z = -9; z <= 9; z++)
            out.add(e(cx + x, y - 1, cz + z, ((x + z) & 7) == 0 ? Material.GRAVEL : Material.COARSE_DIRT));
        for (int x : new int[]{-8,0,8}) pillar(out, cx + x, y, cz, 3, Material.STRIPPED_OAK_LOG, Material.CARVED_PUMPKIN);
    }

    static void fortWall(List<BlockEdit> out, int cx, int y, int cz, int radius, int height, boolean southGate) {
        for (int x = -radius; x <= radius; x++) {
            wallColumn(out, cx + x, y, cz - radius, height);
            if (!southGate || Math.abs(x) > 6) wallColumn(out, cx + x, y, cz + radius, height);
        }
        for (int z = -radius + 1; z < radius; z++) {
            wallColumn(out, cx - radius, y, cz + z, height);
            wallColumn(out, cx + radius, y, cz + z, height);
        }
    }

    static void wallColumn(List<BlockEdit> out, int x, int y, int z, int height) {
        for (int yy = 0; yy < height; yy++) out.add(e(x, y + yy, z,
                ((x + z + yy) & 9) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
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
        for (int x = -halfWidth; x <= halfWidth; x++) for (int yy = 0; yy <= height; yy++) {
            boolean pillar = Math.abs(x) >= halfWidth - 2, arch = yy >= height - 2;
            if (pillar || arch) out.add(e(cx + x, y + yy, cz,
                    ((x + yy) & 7) == 0 ? Material.MOSSY_STONE_BRICKS : Material.DEEPSLATE_BRICKS));
        }
        tower(out, cx - halfWidth - 3, y, cz, 4, height + 4, Material.DARK_OAK_PLANKS);
        tower(out, cx + halfWidth + 3, y, cz, 4, height + 4, Material.DARK_OAK_PLANKS);
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
            OverworldCampaignTerrain146.lamp(out, cx + (int) Math.round(Math.cos(a) * radius), y,
                    cz + (int) Math.round(Math.sin(a) * radius));
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

    static void pillar(List<BlockEdit> out, int x, int y, int z, int height, Material material, Material cap) {
        for (int yy = 0; yy < height; yy++) out.add(e(x, y + yy, z, material));
        out.add(e(x, y + height, z, cap));
    }


    static void villageHouse(List<BlockEdit> out, int cx, int y, int cz,
                             int width, int depth, int floors, boolean roofAlongX,
                             Material plaster, int variant) {
        house(out, cx, y, cz, width, depth, floors, roofAlongX, plaster);
        int hx = width / 2, hz = depth / 2;
        // Porch and exterior identity.
        for (int x = -3; x <= 3; x++) {
            out.add(e(cx + x, y - 1, cz + hz + 1, Material.SMOOTH_STONE_SLAB));
            if (Math.abs(x) == 3) out.add(e(cx + x, y, cz + hz + 1, Material.SPRUCE_FENCE));
        }
        out.add(e(cx, y, cz + hz + 1, Material.OAK_DOOR));
        int chimneyX = cx + (variant % 2 == 0 ? -hx + 2 : hx - 2);
        int chimneyZ = cz - hz + 2;
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
        // Flower boxes and wall texture.
        for (int sx : new int[]{-1, 1}) {
            out.add(e(cx + sx * Math.max(3, hx - 2), y + 1, cz + hz + 1, Material.SPRUCE_TRAPDOOR));
            out.add(e(cx + sx * Math.max(3, hx - 2), y + 2, cz + hz + 1,
                    variant % 2 == 0 ? Material.POPPY : Material.CORNFLOWER));
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
        // Central bell pavilion and fountain.
        for (int radius = 5; radius >= 2; radius--) {
            int yy = y + (5 - radius) / 2;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if (Math.abs(x) == radius || Math.abs(z) == radius) {
                    out.add(e(cx + x, yy, cz + z, radius == 2 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS));
                }
            }
        }
        out.add(e(cx, y + 1, cz, Material.WATER));
        out.add(e(cx, y + 2, cz, Material.BELL));
        for (int[] p : new int[][]{{-8,-7},{8,-7},{-8,7},{8,7}}) {
            pillar(out, cx + p[0], y, cz + p[1], 3, Material.SPRUCE_FENCE, Material.LANTERN);
            bench(out, cx + p[0] + (p[0] < 0 ? 1 : -1), y, cz + p[1]);
        }
    }

    static void villageStreet(List<BlockEdit> out, int x1, int y, int z1, int x2, int z2, int width) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int step = 0; step <= steps; step++) {
            double t = steps == 0 ? 0.0D : step / (double) steps;
            int cx = (int) Math.round(x1 + (x2 - x1) * t);
            int cz = (int) Math.round(z1 + (z2 - z1) * t);
            for (int side = -width / 2; side <= width / 2; side++) {
                int x = Math.abs(x2 - x1) >= Math.abs(z2 - z1) ? cx : cx + side;
                int z = Math.abs(x2 - x1) >= Math.abs(z2 - z1) ? cz + side : cz;
                out.add(e(x, y - 1, z, Math.floorMod(step + side, 11) == 0
                        ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE));
                for (int yy = 0; yy <= 3; yy++) out.add(e(x, y + yy, z, Material.AIR));
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

    static void villageGate(List<BlockEdit> out, int cx, int y, int cz) {
        for (int x : new int[]{-8, 8}) {
            tower(out, cx + x, y, cz, 4, 12, Material.DARK_OAK_PLANKS);
        }
        for (int x = -4; x <= 4; x++) {
            for (int yy = 0; yy <= 8; yy++) {
                boolean arch = yy >= 7;
                if (arch) out.add(e(cx + x, y + yy, cz, Material.DARK_OAK_PLANKS));
                else if (Math.abs(x) == 4) out.add(e(cx + x, y + yy, cz, Material.STRIPPED_DARK_OAK_LOG));
            }
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
        // Two towers form the only entrance. The ritual seal occupies the central arch.
        tower(out, cx - 14, y, cz, 7, 23, Material.DARK_OAK_PLANKS);
        tower(out, cx + 14, y, cz, 7, 23, Material.DARK_OAK_PLANKS);
        for (int x = -7; x <= 7; x++) for (int yy = 0; yy <= 16; yy++) {
            boolean side = Math.abs(x) >= 6;
            boolean arch = yy >= 13 && Math.abs(x) <= 7 - (yy - 13);
            if (side || arch) out.add(e(cx + x, y + yy, cz,
                    Math.floorMod(x + yy, 9) == 0 ? Material.MOSSY_STONE_BRICKS : Material.DEEPSLATE_BRICKS));
        }
        for (int x = -20; x <= 20; x++) {
            out.add(e(cx + x, y - 1, cz - 2, Material.POLISHED_DEEPSLATE));
            out.add(e(cx + x, y - 1, cz - 1, Material.POLISHED_DEEPSLATE));
        }
    }

    static BlockEdit e(int x, int y, int z, Material material) { return new BlockEdit(x, y, z, material); }
}
