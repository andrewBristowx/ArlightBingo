package com.arlight.bingo.end;

import com.arlight.bingo.dungeon.AdaptiveDungeonLootManager;
import com.arlight.bingo.dungeon.DungeonMinionSpawnerManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.loot.LootTable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Ciudadela flotante del End construida después de derrotar al dragón.
 * Incluye islas exteriores, puentes, jardines de chorus, torres de cristales,
 * castillo vertical, Guardián del Vacío y una cámara final segura.
 *
 * No utiliza dragon_guardian ni la entidad Somita. El encuentro conserva un
 * entidad propia del Dragón Corrupto controlada por EndEncounterManager y ArlightBosses 1.12.0
 * lo transforma mediante su etiqueta sincronizada.
 */
final class EndFloatingCitadel {
    private static final int FLOOR_HEIGHT = 8;
    private static final int ISLAND_RADIUS = 39;
    private static final int KEEP_X = 18;
    private static final int KEEP_FRONT_Z = -3;
    private static final int KEEP_BACK_Z = 30;

    record Result(Location base, Location returnGateway, Location arrival,
                  Location bossHome, int bossFloorY, int portalFloorY,
                  Location exitPortal, int protectionRadius) { }

    private final JavaPlugin plugin;
    private final AdaptiveDungeonLootManager adaptiveLoot;
    private final DungeonMinionSpawnerManager spawners;

    EndFloatingCitadel(JavaPlugin plugin, AdaptiveDungeonLootManager adaptiveLoot,
                       DungeonMinionSpawnerManager spawners) {
        this.plugin = plugin;
        this.adaptiveLoot = adaptiveLoot;
        this.spawners = spawners;
    }

    Result build(Location requestedAnchor, int combatLevels) {
        World world = requestedAnchor.getWorld();
        if (world == null) throw new IllegalArgumentException("La ciudadela del End necesita un mundo válido");

        int cx = requestedAnchor.getBlockX();
        int cz = requestedAnchor.getBlockZ();
        int highest = highestInArea(world, cx, cz, 24);
        int lift = Math.max(12, plugin.getConfig().getInt("end-citadel.height-above-terrain", 18));
        int floorY = Math.max(requestedAnchor.getBlockY() + lift, highest + lift);
        floorY = Math.min(floorY, world.getMaxHeight() - 78);

        int bossLevel = combatLevels - 1;
        int portalLevel = combatLevels;
        int bossFloorY = floorY + bossLevel * FLOOR_HEIGHT;
        int portalFloorY = floorY + portalLevel * FLOOR_HEIGHT;
        int roofY = floorY + (portalLevel + 1) * FLOOR_HEIGHT;
        Location base = new Location(world, cx + 0.5D, floorY, cz + 0.5D);

        loadChunks(world, cx, cz, 5);
        clearVolume(world, cx, cz, floorY, roofY);
        buildMainIsland(world, cx, cz, floorY);
        buildSatelliteIslands(world, cx, cz, floorY);
        buildGatewayApproach(world, cx, cz, floorY);
        buildOuterCitadel(world, cx, cz, floorY);
        buildGardensAndRuins(world, cx, cz, floorY);
        buildCentralCastle(world, cx, cz, floorY, bossLevel, portalLevel);
        buildCrystalTowers(world, cx, cz, floorY, roofY);
        registerEncounters(world, cx, cz, floorY, bossLevel);
        placeLoot(world, cx, cz, floorY, bossLevel, portalLevel);

        Location returnGateway = new Location(world, cx + 0.5D, floorY + 2, cz - 53 + 0.5D);
        Location arrival = new Location(world, cx + 0.5D, floorY + 1, cz - 46 + 0.5D);
        Location bossHome = new Location(world, cx + 0.5D, bossFloorY + 1, cz + 15.5D);
        Location exitPortal = new Location(world, cx + 0.5D, portalFloorY + 1, cz + 17.5D);
        return new Result(base, returnGateway, arrival, bossHome, bossFloorY,
                portalFloorY, exitPortal, 66);
    }

    private int highestInArea(World world, int cx, int cz, int radius) {
        int highest = world.getMinHeight();
        for (int x = -radius; x <= radius; x += 8) {
            for (int z = -radius; z <= radius; z += 8) {
                highest = Math.max(highest, world.getHighestBlockYAt(cx + x, cz + z));
            }
        }
        return highest;
    }

    private void loadChunks(World world, int cx, int cz, int radius) {
        int chunkX = cx >> 4;
        int chunkZ = cz >> 4;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) world.getChunkAt(chunkX + x, chunkZ + z).load(true);
        }
    }

    private void clearVolume(World world, int cx, int cz, int floorY, int roofY) {
        if (!plugin.getConfig().getBoolean("end-citadel.clear-volume", true)) return;
        for (int x = -54; x <= 54; x++) for (int z = -58; z <= 52; z++) {
            for (int y = floorY - 2; y <= roofY + 18; y++) {
                world.getBlockAt(cx + x, y, cz + z).setType(Material.AIR, false);
            }
        }
    }

    private void buildMainIsland(World world, int cx, int cz, int floorY) {
        // Isla de varias capas con borde irregular y núcleo de obsidiana.
        for (int depth = 0; depth <= 10; depth++) {
            int radius = ISLAND_RADIUS - depth * 2;
            int y = floorY - depth;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                double ellipse = (x * x) / (double) (radius * radius)
                        + (z * z) / (double) ((radius + 5) * (radius + 5));
                int rough = Math.floorMod(x * 31 + z * 17 + depth * 13, 11);
                if (ellipse > 1.0D || (ellipse > 0.88D && rough < 3)) continue;
                Material material;
                if (depth >= 7) material = Material.OBSIDIAN;
                else if (((x + z + depth) & 11) == 0) material = Material.PURPUR_BLOCK;
                else material = Material.END_STONE;
                world.getBlockAt(cx + x, y, cz + z).setType(material, false);
            }
        }
        for (int y = floorY - 11; y >= floorY - 23; y--) {
            int radius = Math.max(2, (floorY - y) / 4);
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z <= radius * radius) {
                    world.getBlockAt(cx + x, y, cz + z).setType(
                            ((x + z + y) & 3) == 0 ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN, false);
                }
            }
        }
    }

    private void buildSatelliteIslands(World world, int cx, int cz, int floorY) {
        int[][] islands = {{-47, -9, 10}, {47, -9, 10}, {-44, 28, 9}, {44, 28, 9}};
        for (int[] island : islands) {
            buildSmallIsland(world, cx + island[0], cz + island[1], floorY + 2, island[2]);
            buildBridge(world, cx, cz, floorY + 1, island[0], island[1]);
            buildCrystalObelisk(world, cx + island[0], floorY + 3, cz + island[1], 12);
        }
    }

    private void buildSmallIsland(World world, int cx, int cz, int y, int radius) {
        for (int depth = 0; depth <= 5; depth++) {
            int r = Math.max(3, radius - depth);
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;
                world.getBlockAt(cx + x, y - depth, cz + z).setType(
                        depth >= 4 ? Material.OBSIDIAN
                                : (((x + z + depth) & 5) == 0 ? Material.PURPUR_BLOCK : Material.END_STONE), false);
            }
        }
    }

    private void buildBridge(World world, int cx, int cz, int y, int targetX, int targetZ) {
        int steps = Math.max(Math.abs(targetX), Math.abs(targetZ));
        for (int step = 0; step <= steps; step++) {
            double progress = step / (double) steps;
            int x = (int) Math.round(targetX * progress);
            int z = (int) Math.round(targetZ * progress);
            boolean alongX = Math.abs(targetX) >= Math.abs(targetZ);
            for (int width = -2; width <= 2; width++) {
                int bx = cx + x + (alongX ? 0 : width);
                int bz = cz + z + (alongX ? width : 0);
                world.getBlockAt(bx, y, bz).setType(
                        ((step + width) & 3) == 0 ? Material.PURPUR_BLOCK : Material.END_STONE_BRICKS, false);
                for (int clear = 1; clear <= 4; clear++) world.getBlockAt(bx, y + clear, bz).setType(Material.AIR, false);
            }
            if (step % 6 == 0) {
                int side = alongX ? 3 : 0;
                int other = alongX ? 0 : 3;
                world.getBlockAt(cx + x + side, y + 1, cz + z + other).setType(Material.PURPUR_PILLAR, false);
                world.getBlockAt(cx + x - side, y + 1, cz + z - other).setType(Material.PURPUR_PILLAR, false);
            }
        }
    }

    private void buildGatewayApproach(World world, int cx, int cz, int floorY) {
        buildSmallIsland(world, cx, cz - 53, floorY, 10);
        for (int z = -49; z <= -35; z++) for (int x = -5; x <= 5; x++) {
            world.getBlockAt(cx + x, floorY, cz + z).setType(
                    ((x + z) & 3) == 0 ? Material.PURPUR_BLOCK : Material.END_STONE_BRICKS, false);
            for (int y = 1; y <= 6; y++) world.getBlockAt(cx + x, floorY + y, cz + z).setType(Material.AIR, false);
            if (Math.abs(x) == 5) world.getBlockAt(cx + x, floorY + 1, cz + z).setType(Material.PURPUR_PILLAR, false);
        }
        for (int z = -48; z <= -36; z += 4) {
            world.getBlockAt(cx - 7, floorY + 1, cz + z).setType(Material.END_ROD, false);
            world.getBlockAt(cx + 7, floorY + 1, cz + z).setType(Material.END_ROD, false);
        }
    }

    private void buildOuterCitadel(World world, int cx, int cz, int floorY) {
        int wallX = 30;
        int frontZ = -34;
        int backZ = 35;
        for (int x = -wallX; x <= wallX; x++) {
            if (Math.abs(x) > 5) wallColumn(world, cx + x, floorY, cz + frontZ, 10);
            wallColumn(world, cx + x, floorY, cz + backZ, 10);
        }
        for (int z = frontZ; z <= backZ; z++) {
            wallColumn(world, cx - wallX, floorY, cz + z, 10);
            wallColumn(world, cx + wallX, floorY, cz + z, 10);
        }
        for (int x = -10; x <= 10; x++) for (int y = 1; y <= 14; y++) {
            boolean opening = Math.abs(x) <= 5 && y <= 8;
            world.getBlockAt(cx + x, floorY + y, cz + frontZ).setType(opening
                    ? Material.AIR : (y == 5 || y == 10 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS), false);
        }
        world.getBlockAt(cx, floorY + 12, cz + frontZ - 1).setType(Material.CRYING_OBSIDIAN, false);
        buildOuterTower(world, cx - 20, floorY, cz + frontZ + 2);
        buildOuterTower(world, cx + 20, floorY, cz + frontZ + 2);
        buildOuterTower(world, cx - 25, floorY, cz + 28);
        buildOuterTower(world, cx + 25, floorY, cz + 28);
    }

    private void buildOuterTower(World world, int cx, int floorY, int cz) {
        hollowRoom(world, cx, floorY, cz, 7, 7, 17,
                Material.END_STONE_BRICKS, Material.AMETHYST_BLOCK, Material.PURPUR_BLOCK);
        for (int y = 4; y <= 14; y += 5) {
            world.getBlockAt(cx - 6, floorY + y, cz).setType(Material.PURPLE_STAINED_GLASS_PANE, false);
            world.getBlockAt(cx + 6, floorY + y, cz).setType(Material.PURPLE_STAINED_GLASS_PANE, false);
            world.getBlockAt(cx, floorY + y, cz - 6).setType(Material.PURPLE_STAINED_GLASS_PANE, false);
            world.getBlockAt(cx, floorY + y, cz + 6).setType(Material.PURPLE_STAINED_GLASS_PANE, false);
        }
    }

    private void buildGardensAndRuins(World world, int cx, int cz, int floorY) {
        // Plaza central y caminos de color contrastante.
        for (int x = -9; x <= 9; x++) for (int z = -32; z <= -4; z++) {
            world.getBlockAt(cx + x, floorY, cz + z).setType(
                    ((x + z) & 3) == 0 ? Material.PURPUR_BLOCK : Material.END_STONE_BRICKS, false);
        }
        buildChorusGarden(world, cx - 20, floorY, cz - 13, 8);
        buildChorusGarden(world, cx + 20, floorY, cz - 13, 8);
        buildCrystalCourt(world, cx - 20, floorY, cz + 10);
        buildCrystalCourt(world, cx + 20, floorY, cz + 10);
        buildEndLibraryRuins(world, cx, floorY, cz + 30);
    }

    private void buildChorusGarden(World world, int cx, int floorY, int cz, int radius) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (x * x + z * z > radius * radius) continue;
            world.getBlockAt(cx + x, floorY, cz + z).setType(Material.END_STONE, false);
            for (int y = 1; y <= 5; y++) world.getBlockAt(cx + x, floorY + y, cz + z).setType(Material.AIR, false);
            if (Math.floorMod(x * 5 + z * 7, 19) == 0) {
                world.getBlockAt(cx + x, floorY + 1, cz + z).setType(Material.CHORUS_PLANT, false);
                world.getBlockAt(cx + x, floorY + 2, cz + z).setType(Material.CHORUS_FLOWER, false);
            }
        }
        for (int angle = 0; angle < 16; angle++) {
            double radians = Math.PI * 2.0D * angle / 16.0D;
            int x = (int) Math.round(Math.cos(radians) * (radius + 1));
            int z = (int) Math.round(Math.sin(radians) * (radius + 1));
            world.getBlockAt(cx + x, floorY + 1, cz + z).setType(Material.PURPUR_PILLAR, false);
        }
    }

    private void buildCrystalCourt(World world, int cx, int floorY, int cz) {
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            world.getBlockAt(cx + x, floorY, cz + z).setType(
                    ((x + z) & 1) == 0 ? Material.CALCITE : Material.SMOOTH_BASALT, false);
            for (int y = 1; y <= 6; y++) world.getBlockAt(cx + x, floorY + y, cz + z).setType(Material.AIR, false);
        }
        for (int[] p : new int[][]{{-5, -5}, {5, -5}, {-5, 5}, {5, 5}, {0, 0}}) {
            buildCrystalObelisk(world, cx + p[0], floorY + 1, cz + p[1], p[0] == 0 ? 10 : 7);
        }
    }

    private void buildCrystalObelisk(World world, int x, int y, int z, int height) {
        for (int dy = 0; dy < height; dy++) {
            int taper = dy > height - 3 ? 0 : 1;
            for (int dx = -taper; dx <= taper; dx++) for (int dz = -taper; dz <= taper; dz++) {
                world.getBlockAt(x + dx, y + dy, z + dz).setType(
                        dy % 4 == 0 ? Material.BUDDING_AMETHYST : Material.AMETHYST_BLOCK, false);
            }
        }
        world.getBlockAt(x, y + height, z).setType(Material.AMETHYST_CLUSTER, false);
    }

    private void buildEndLibraryRuins(World world, int cx, int floorY, int cz) {
        hollowRoom(world, cx, floorY, cz, 12, 7, 8,
                Material.PURPUR_BLOCK, Material.CRYING_OBSIDIAN, Material.END_STONE_BRICKS);
        carveDoorOnZ(world, cx, floorY, cz - 7, 4, 5);
        for (int x = -9; x <= 9; x += 3) {
            world.getBlockAt(cx + x, floorY + 1, cz + 4).setType(Material.CHISELED_BOOKSHELF, false);
            world.getBlockAt(cx + x, floorY + 2, cz + 4).setType(Material.BOOKSHELF, false);
        }
        // Rupturas deliberadas para que no parezca una caja perfecta.
        for (int y = 4; y <= 8; y++) {
            world.getBlockAt(cx - 12, floorY + y, cz + 2).setType(Material.AIR, false);
            world.getBlockAt(cx + 12, floorY + y, cz - 3).setType(Material.AIR, false);
        }
    }

    private void buildCentralCastle(World world, int cx, int cz, int floorY,
                                    int bossLevel, int portalLevel) {
        for (int level = 0; level <= portalLevel; level++) {
            buildKeepLevel(world, cx, cz, floorY + level * FLOOR_HEIGHT,
                    level, bossLevel, portalLevel);
        }
        for (int level = 0; level < portalLevel; level++) buildKeepStairs(world, cx, cz, floorY, level);
        int roofY = floorY + (portalLevel + 1) * FLOOR_HEIGHT;
        for (int x = -KEEP_X; x <= KEEP_X; x++) for (int z = KEEP_FRONT_Z; z <= KEEP_BACK_Z; z++) {
            world.getBlockAt(cx + x, roofY, cz + z).setType(Material.END_STONE_BRICKS, false);
            if ((Math.abs(x) == KEEP_X || z == KEEP_FRONT_Z || z == KEEP_BACK_Z) && ((x + z) & 1) == 0) {
                world.getBlockAt(cx + x, roofY + 1, cz + z).setType(Material.PURPUR_PILLAR, false);
            }
        }
    }

    private void buildKeepLevel(World world, int cx, int cz, int y0,
                                int level, int bossLevel, int portalLevel) {
        for (int x = -KEEP_X; x <= KEEP_X; x++) for (int z = KEEP_FRONT_Z; z <= KEEP_BACK_Z; z++) {
            world.getBlockAt(cx + x, y0, cz + z).setType(
                    ((x + z + level) & 3) == 0 ? Material.PURPUR_BLOCK : Material.END_STONE_BRICKS, false);
            for (int y = 1; y < FLOOR_HEIGHT; y++) {
                boolean edge = Math.abs(x) >= KEEP_X - 1 || z <= KEEP_FRONT_Z + 1 || z >= KEEP_BACK_Z - 1;
                Material material = Material.AIR;
                if (edge) {
                    boolean window = y >= 3 && y <= 4 && ((x + z) % 6 == 0);
                    material = window ? Material.MAGENTA_STAINED_GLASS_PANE
                            : (y == 2 || y == 6 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS);
                }
                world.getBlockAt(cx + x, y0 + y, cz + z).setType(material, false);
            }
        }
        carveDoorOnZ(world, cx, y0, cz + KEEP_FRONT_Z, 4, 5);

        if (level < bossLevel) {
            // Salas de shulkers, observatorio, archivos y pasillos flotantes.
            for (int x = -KEEP_X + 2; x <= KEEP_X - 2; x++) {
                if (Math.abs(x) <= 4) continue;
                for (int y = 1; y <= 4; y++) world.getBlockAt(cx + x, y0 + y, cz + 13).setType(
                        y == 4 ? Material.AMETHYST_BLOCK : Material.PURPUR_BLOCK, false);
            }
            for (int z = 3; z <= KEEP_BACK_Z - 3; z += 9) {
                for (int y = 1; y <= 4; y++) for (int x = -KEEP_X + 2; x <= KEEP_X - 2; x++) {
                    if (Math.abs(x) <= 3) continue;
                    world.getBlockAt(cx + x, y0 + y, cz + z).setType(
                            y == 4 ? Material.CRYING_OBSIDIAN : Material.END_STONE_BRICKS, false);
                }
            }
            for (int[] p : new int[][]{{-14, 6}, {14, 6}, {-14, 24}, {14, 24}}) {
                world.getBlockAt(cx + p[0], y0 + 1, cz + p[1]).setType(Material.END_ROD, false);
            }
        } else if (level == bossLevel) {
            for (int z = 21; z <= 28; z++) for (int x = -9; x <= 9; x++) {
                world.getBlockAt(cx + x, y0 + 1, cz + z).setType(
                        ((x + z) & 1) == 0 ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN, false);
            }
            for (int y = 1; y <= 6; y++) {
                world.getBlockAt(cx - 10, y0 + y, cz + 26).setType(Material.PURPUR_PILLAR, false);
                world.getBlockAt(cx + 10, y0 + y, cz + 26).setType(Material.PURPUR_PILLAR, false);
            }
            buildCrystalObelisk(world, cx, y0 + 1, cz + 27, 8);
            for (int x = -14; x <= 14; x += 4) world.getBlockAt(cx + x, y0 + 1, cz + 1)
                    .setType(Material.SOUL_FIRE, false);
        } else if (level == portalLevel) {
            for (int x = -14; x <= 14; x++) for (int z = 3; z <= 27; z++) {
                if (Math.abs(x) == 14 || z == 3 || z == 27) {
                    world.getBlockAt(cx + x, y0 + 1, cz + z).setType(Material.AMETHYST_BLOCK, false);
                }
            }
            for (int[] p : new int[][]{{-10, 7}, {10, 7}, {-10, 23}, {10, 23}}) {
                world.getBlockAt(cx + p[0], y0 + 1, cz + p[1]).setType(Material.END_ROD, false);
            }
        }
    }

    private void buildKeepStairs(World world, int cx, int cz, int floorY, int level) {
        int y0 = floorY + level * FLOOR_HEIGHT;
        boolean east = (level & 1) == 0;
        int x0 = cx + (east ? 12 : -13);
        int z0 = cz + 3;
        for (int x = x0 - 1; x <= x0 + 2; x++) for (int z = z0 - 1; z <= z0 + 9; z++) {
            for (int y = y0 + 1; y <= y0 + FLOOR_HEIGHT + 4; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
        }
        for (int step = 0; step < FLOOR_HEIGHT; step++) {
            int z = z0 + step;
            int y = y0 + 1 + step;
            for (int dx = 0; dx < 2; dx++) {
                Block block = world.getBlockAt(x0 + dx, y, z);
                setStair(block, BlockFace.SOUTH, Material.PURPUR_STAIRS);
                world.getBlockAt(x0 + dx, y - 1, z).setType(Material.PURPUR_BLOCK, false);
                for (int clear = 1; clear <= 4; clear++) world.getBlockAt(x0 + dx, y + clear, z).setType(Material.AIR, false);
            }
            world.getBlockAt(east ? x0 - 1 : x0 + 2, y, z).setType(Material.PURPUR_PILLAR, false);
        }
    }

    private void buildCrystalTowers(World world, int cx, int cz, int floorY, int roofY) {
        for (int x : new int[]{-27, 27}) for (int z : new int[]{-27, 27}) {
            int towerHeight = Math.max(22, roofY - floorY - 4);
            hollowRoom(world, cx + x, floorY, cz + z, 6, 6, towerHeight,
                    Material.END_STONE_BRICKS, Material.AMETHYST_BLOCK, Material.PURPUR_BLOCK);
            for (int y = 5; y < towerHeight; y += 6) {
                world.getBlockAt(cx + x - 5, floorY + y, cz + z).setType(Material.PURPLE_STAINED_GLASS_PANE, false);
                world.getBlockAt(cx + x + 5, floorY + y, cz + z).setType(Material.PURPLE_STAINED_GLASS_PANE, false);
                world.getBlockAt(cx + x, floorY + y, cz + z - 5).setType(Material.PURPLE_STAINED_GLASS_PANE, false);
                world.getBlockAt(cx + x, floorY + y, cz + z + 5).setType(Material.PURPLE_STAINED_GLASS_PANE, false);
            }
            buildCrystalObelisk(world, cx + x, floorY + towerHeight + 1, cz + z, 8);
        }
    }

    private void registerEncounters(World world, int cx, int cz, int floorY, int bossLevel) {
        register(new Location(world, cx, floorY + 1, cz - 43), "end_outer_islands",
                group("outer-islands", List.of("amethyst_phantom_minion", "amethyst_eye_minion")), true);
        register(new Location(world, cx - 20, floorY + 1, cz - 13), "end_chorus_garden",
                group("chorus-gardens", List.of("void_enderman_minion", "corrupted_ender_mite_minion")), true);
        register(new Location(world, cx + 20, floorY + 1, cz - 13), "end_chorus_garden",
                group("chorus-gardens", List.of("void_enderman_minion", "corrupted_ender_mite_minion")), true);
        register(new Location(world, cx - 20, floorY + 1, cz + 10), "end_crystal_court",
                group("crystal-courts", List.of("amethyst_guardian_shard_minion", "amethyst_eye_minion",
                        "amethyst_shulker_minion")), true);
        register(new Location(world, cx + 20, floorY + 1, cz + 10), "end_crystal_court",
                group("crystal-courts", List.of("amethyst_guardian_shard_minion", "amethyst_eye_minion",
                        "amethyst_shulker_minion")), true);
        register(new Location(world, cx - 20, floorY + 1, cz - 31), "end_gate_sentinel",
                group("gate", List.of("void_enderman_sentinel_minion", "amethyst_shulker_minion")), true);
        register(new Location(world, cx + 20, floorY + 1, cz - 31), "end_gate_sentinel",
                group("gate", List.of("void_enderman_sentinel_minion", "amethyst_shulker_minion")), true);

        int perFloor = Math.max(1, Math.min(4,
                plugin.getConfig().getInt("end-citadel.spawners-per-floor", 2)));
        int[][] points = {{-12, 6}, {12, 6}, {-12, 23}, {12, 23}};
        List<String> interior = group("interior", List.of(
                "void_enderman_minion", "amethyst_shulker_minion", "corrupted_ender_mite_minion"));
        List<String> elite = group("elite", List.of(
                "void_enderman_sentinel_minion", "amethyst_phantom_minion",
                "amethyst_guardian_shard_minion"));
        for (int level = 0; level < bossLevel; level++) {
            List<String> pool = level >= bossLevel - 2 ? elite : interior;
            for (int i = 0; i < perFloor; i++) {
                register(new Location(world, cx + points[i][0], floorY + level * FLOOR_HEIGHT + 1,
                                cz + points[i][1]), "end_keep_" + level, pool, true);
            }
        }
        List<String> support = group("boss-support", List.of(
                "void_enderman_sentinel_minion", "amethyst_guardian_shard_minion"));
        register(new Location(world, cx - 12, floorY + bossLevel * FLOOR_HEIGHT + 1, cz + 7),
                "end_boss_support", support, false);
        register(new Location(world, cx + 12, floorY + bossLevel * FLOOR_HEIGHT + 1, cz + 7),
                "end_boss_support", support, false);
    }

    private void placeLoot(World world, int cx, int cz, int floorY, int bossLevel, int portalLevel) {
        chest(new Location(world, cx - 47, floorY + 3, cz - 9), "minecraft:chests/simple_dungeon",
                AdaptiveDungeonLootManager.ChestTier.RARE);
        chest(new Location(world, cx + 47, floorY + 3, cz - 9), "minecraft:chests/simple_dungeon",
                AdaptiveDungeonLootManager.ChestTier.RARE);
        chest(new Location(world, cx - 44, floorY + 3, cz + 28), "minecraft:chests/stronghold_corridor",
                AdaptiveDungeonLootManager.ChestTier.EPIC);
        chest(new Location(world, cx + 44, floorY + 3, cz + 28), "minecraft:chests/stronghold_corridor",
                AdaptiveDungeonLootManager.ChestTier.EPIC);
        for (int level = 0; level < bossLevel; level++) {
            AdaptiveDungeonLootManager.ChestTier tier = level == 0
                    ? AdaptiveDungeonLootManager.ChestTier.COMMON
                    : level >= bossLevel - 2 ? AdaptiveDungeonLootManager.ChestTier.EPIC
                    : AdaptiveDungeonLootManager.ChestTier.RARE;
            chest(new Location(world, cx - 14, floorY + level * FLOOR_HEIGHT + 1, cz + 26),
                    "minecraft:chests/stronghold_corridor", tier);
            chest(new Location(world, cx + 14, floorY + level * FLOOR_HEIGHT + 1, cz + 26),
                    "minecraft:chests/stronghold_corridor", tier);
        }
        String finalTable = plugin.getConfig().getString(
                "end-dungeon.final-loot-table", "minecraft:chests/end_city_treasure");
        for (int[] p : new int[][]{{-11, 7}, {11, 7}, {-11, 23}, {11, 23}}) {
            chest(new Location(world, cx + p[0], floorY + portalLevel * FLOOR_HEIGHT + 1, cz + p[1]),
                    finalTable, AdaptiveDungeonLootManager.ChestTier.LEGENDARY);
        }
    }

    private List<String> group(String key, List<String> fallback) {
        List<String> configured = plugin.getConfig().getStringList("end-citadel.mob-groups." + key);
        return configured.isEmpty() ? fallback : configured;
    }

    private void register(Location at, String group, List<String> ids, boolean active) {
        if (spawners != null) spawners.register(at, group, ids, active);
    }

    private void chest(Location at, String tableId, AdaptiveDungeonLootManager.ChestTier tier) {
        at.clone().add(0, -1, 0).getBlock().setType(Material.END_STONE_BRICKS, false);
        for (int y = 1; y <= 2; y++) at.clone().add(0, y, 0).getBlock().setType(Material.AIR, false);
        if (adaptiveLoot != null) adaptiveLoot.placeCustomChest(
                at, AdaptiveDungeonLootManager.DimensionGroup.END, tier);
    }

    private void wallColumn(World world, int x, int floorY, int z, int height) {
        for (int y = 1; y <= height; y++) world.getBlockAt(x, floorY + y, z).setType(
                y == 4 || y == 8 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS, false);
    }

    private void hollowRoom(World world, int cx, int floorY, int cz,
                            int radiusX, int radiusZ, int height,
                            Material wall, Material accent, Material floor) {
        for (int x = -radiusX; x <= radiusX; x++) for (int z = -radiusZ; z <= radiusZ; z++) {
            world.getBlockAt(cx + x, floorY, cz + z).setType(
                    ((x + z) & 7) == 0 ? accent : floor, false);
            for (int y = 1; y <= height; y++) {
                boolean edge = Math.abs(x) == radiusX || Math.abs(z) == radiusZ || y == height;
                world.getBlockAt(cx + x, floorY + y, cz + z).setType(
                        edge ? (y == 4 || y == height ? accent : wall) : Material.AIR, false);
            }
        }
    }

    private void carveDoorOnZ(World world, int cx, int floorY, int wallZ, int halfWidth, int height) {
        for (int x = -halfWidth; x <= halfWidth; x++) for (int y = 1; y <= height; y++) {
            world.getBlockAt(cx + x, floorY + y, wallZ).setType(Material.AIR, false);
        }
    }

    private void setStair(Block block, BlockFace facing, Material material) {
        block.setType(material, false);
        if (block.getBlockData() instanceof Stairs stairs) {
            stairs.setFacing(facing);
            stairs.setHalf(Bisected.Half.BOTTOM);
            stairs.setShape(Stairs.Shape.STRAIGHT);
            block.setBlockData(stairs, false);
        }
    }
}
