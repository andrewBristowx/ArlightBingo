package com.arlight.bingo.dungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Expansión urbana del castillo del Overworld.
 *
 * La ciudad se genera DESPUÉS de que los chunks vanilla existen y antes del castillo,
 * por lo que limpia árboles, agua, colinas y decoraciones dentro de su huella. Esto hace
 * que la estructura del minijuego tenga prioridad visual sobre el terreno aleatorio.
 */
final class OverworldInfestedCity {
    static final int MIN_X = -62;
    static final int MAX_X = 62;
    static final int MIN_Z = -84;
    static final int MAX_Z = 48;
    static final int SPAWN_Z = -72;

    record Result(Location spawn, List<Location> checkpoints) { }

    private final JavaPlugin plugin;
    private final AdaptiveDungeonLootManager adaptiveLoot;
    private final DungeonMinionSpawnerManager spawners;

    OverworldInfestedCity(JavaPlugin plugin, AdaptiveDungeonLootManager adaptiveLoot,
                          DungeonMinionSpawnerManager spawners) {
        this.plugin = plugin;
        this.adaptiveLoot = adaptiveLoot;
        this.spawners = spawners;
    }

    Result prepareAndBuild(Location base, int combatLevels) {
        loadFootprintChunks(base);
        terraformFootprint(base, combatLevels);
        blendCityIntoTerrain(base);

        buildOuterWalls(base);
        buildSouthGateAndSpawnPlaza(base);
        buildRoadNetwork(base);
        buildMarketSquare(base);
        buildWestResidentialDistrict(base);
        buildEastCraftDistrict(base);
        buildNorthDistrict(base);
        buildSewerEntrances(base);
        buildInfestationDetails(base);
        registerCityEncounters(base);
        placeCityLoot(base);

        Location spawn = centered(base.clone().add(0, 1, SPAWN_Z - 2));
        List<Location> checkpoints = new ArrayList<>();
        checkpoints.add(spawn.clone());
        checkpoints.add(centered(base.clone().add(0, 1, -57)));
        checkpoints.add(centered(base.clone().add(0, 1, -43)));
        checkpoints.add(centered(base.clone().add(-38, 1, -22)));
        checkpoints.add(centered(base.clone().add(38, 1, -22)));
        return new Result(spawn, List.copyOf(checkpoints));
    }

    /**
     * Carga/genera explícitamente cada chunk de la ciudad antes de editar bloques. De este
     * modo la decoración vanilla no se ejecuta después y no vuelve a plantar árboles encima.
     */
    private void loadFootprintChunks(Location base) {
        World world = base.getWorld();
        if (world == null) return;
        int minChunkX = (base.getBlockX() + MIN_X - 8) >> 4;
        int maxChunkX = (base.getBlockX() + MAX_X + 8) >> 4;
        int minChunkZ = (base.getBlockZ() + MIN_Z - 8) >> 4;
        int maxChunkZ = (base.getBlockZ() + MAX_Z + 8) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                world.getChunkAt(cx, cz).load(true);
            }
        }
    }

    /**
     * Nivela toda la huella urbana a la altura elegida por DimensionDungeonManager.
     * Se quitan árboles/colinas y se rellena agua o desniveles hasta formar tierra firme.
     */
    private void terraformFootprint(Location base, int combatLevels) {
        World world = base.getWorld();
        if (world == null) return;
        int baseY = base.getBlockY();
        int minimumClearTop = Math.min(world.getMaxHeight() - 2,
                baseY + Math.max(24, combatLevels * 8 + 20));
        int maxFillDepth = Math.max(16,
                plugin.getConfig().getInt("overworld-city.terrain.max-fill-depth", 40));

        for (int rx = MIN_X - 5; rx <= MAX_X + 5; rx++) {
            int x = base.getBlockX() + rx;
            for (int rz = MIN_Z - 5; rz <= MAX_Z + 5; rz++) {
                int z = base.getBlockZ() + rz;
                int originalTop = world.getHighestBlockYAt(x, z);
                int groundY = findNaturalGroundY(world, x, originalTop, z);

                int fillFrom = Math.max(world.getMinHeight() + 1,
                        Math.max(groundY + 1, baseY - maxFillDepth));
                for (int y = fillFrom; y < baseY; y++) {
                    Material fill = y < baseY - 5 ? Material.STONE
                            : (y == baseY - 1 ? Material.DIRT : Material.COARSE_DIRT);
                    world.getBlockAt(x, y, z).setType(fill, false);
                }

                int clearTop = Math.min(world.getMaxHeight() - 1,
                        Math.max(minimumClearTop, originalTop + 12));
                for (int y = baseY; y <= clearTop; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }

                // Superficie uniforme; calles y edificios la reemplazan después.
                world.getBlockAt(x, baseY - 1, z).setType(Material.DIRT, false);
                world.getBlockAt(x, baseY, z).setType(grassVariation(rx, rz), false);
            }
        }
    }


    /**
     * Crea taludes, terrazas y contrafuertes alrededor de la huella. La ciudad deja
     * de parecer una placa rectangular pegada sobre el bioma y se une al bosque,
     * colinas y costas existentes mediante una transición de 18 bloques.
     */
    private void blendCityIntoTerrain(Location base) {
        World world = base.getWorld();
        if (world == null) return;
        int baseY = base.getBlockY();
        int blend = Math.max(10, plugin.getConfig().getInt("overworld-city.terrain.blend-radius", 18));
        for (int rx = MIN_X - blend; rx <= MAX_X + blend; rx++) {
            for (int rz = MIN_Z - blend; rz <= MAX_Z + blend; rz++) {
                boolean inside = rx >= MIN_X - 5 && rx <= MAX_X + 5 && rz >= MIN_Z - 5 && rz <= MAX_Z + 5;
                if (inside) continue;
                int dx = rx < MIN_X ? MIN_X - rx : Math.max(0, rx - MAX_X);
                int dz = rz < MIN_Z ? MIN_Z - rz : Math.max(0, rz - MAX_Z);
                int distance = Math.max(dx, dz);
                if (distance > blend) continue;
                int x = base.getBlockX() + rx, z = base.getBlockZ() + rz;
                int natural = findNaturalGroundY(world, x, world.getHighestBlockYAt(x, z), z);
                double t = distance / (double) blend;
                int target = (int)Math.round(baseY * (1.0 - t) + natural * t);
                target = Math.max(baseY - 10, Math.min(baseY + 9, target));
                for (int y = Math.min(natural, target); y <= Math.max(natural, target); y++) {
                    if (y <= target) {
                        Material m = y == target ? ((rx + rz) % 11 == 0 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK)
                                : (y < target - 4 ? Material.STONE : Material.DIRT);
                        world.getBlockAt(x, y, z).setType(m, false);
                    } else world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
                // Contrafuertes de piedra en puntos regulares y raíces que esconden cortes verticales.
                if (distance <= 3 && Math.floorMod(rx * 5 + rz * 7, 13) == 0) {
                    for (int y = target; y <= baseY + 2; y++)
                        world.getBlockAt(x, y, z).setType(y == baseY + 2 ? Material.MOSSY_COBBLESTONE_WALL : Material.MOSSY_COBBLESTONE, false);
                }
            }
        }
    }

    private int findNaturalGroundY(World world, int x, int startY, int z) {
        int y = Math.min(startY, world.getMaxHeight() - 1);
        while (y > world.getMinHeight() + 1) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (isTerrainGround(material)) return y;
            y--;
        }
        return y;
    }

    private boolean isTerrainGround(Material material) {
        if (material == Material.WATER || material == Material.LAVA || material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR) return false;
        if (Tag.LEAVES.isTagged(material) || Tag.LOGS.isTagged(material)) return false;
        String name = material.name();
        if (name.endsWith("_SAPLING") || name.endsWith("_FLOWER") || name.endsWith("_TULIP")
                || name.endsWith("_VINES") || name.endsWith("_VINE") || name.endsWith("_GRASS")
                || name.endsWith("_BUSH") || name.endsWith("_MUSHROOM") || name.endsWith("_ROOTS")) {
            return false;
        }
        return material.isSolid();
    }

    private Material grassVariation(int x, int z) {
        int value = Math.floorMod(x * 17 + z * 13, 31);
        if (value == 0) return Material.MOSS_BLOCK;
        if (value <= 2) return Material.COARSE_DIRT;
        return Material.GRASS_BLOCK;
    }

    private void buildOuterWalls(Location base) {
        int wallY = base.getBlockY() + 1;
        for (int x = MIN_X; x <= MAX_X; x++) {
            if (Math.abs(x) > 5) {
                buildWallColumn(base.getWorld(), base.getBlockX() + x, wallY,
                        base.getBlockZ() + MIN_Z, 9);
            }
            if (Math.abs(x) > 4) {
                buildWallColumn(base.getWorld(), base.getBlockX() + x, wallY,
                        base.getBlockZ() + MAX_Z, 8);
            }
        }
        for (int z = MIN_Z; z <= MAX_Z; z++) {
            buildWallColumn(base.getWorld(), base.getBlockX() + MIN_X, wallY,
                    base.getBlockZ() + z, 8);
            buildWallColumn(base.getWorld(), base.getBlockX() + MAX_X, wallY,
                    base.getBlockZ() + z, 8);
        }

        buildCityTower(base.clone().add(MIN_X + 4, 0, MIN_Z + 4), 7, 13);
        buildCityTower(base.clone().add(MAX_X - 4, 0, MIN_Z + 4), 7, 13);
        buildCityTower(base.clone().add(MIN_X + 4, 0, MAX_Z - 4), 7, 12);
        buildCityTower(base.clone().add(MAX_X - 4, 0, MAX_Z - 4), 7, 12);

        // Puerta trasera pequeña para rutas opcionales.
        carveGate(base.clone().add(0, 0, MAX_Z), 4, 5, BlockFace.NORTH);
        for (int x = -5; x <= 5; x++) {
            base.clone().add(x, 9, MAX_Z).getBlock().setType(Material.STONE_BRICKS, false);
        }
    }

    private void buildSouthGateAndSpawnPlaza(Location base) {
        // Gran puerta de ciudad.
        for (int x = -11; x <= 11; x++) {
            for (int y = 1; y <= 14; y++) {
                boolean opening = Math.abs(x) <= 5 && y <= 8;
                boolean arch = (Math.abs(x) == 5 && y == 9)
                        || (Math.abs(x) == 4 && y == 10)
                        || (Math.abs(x) <= 3 && y == 11);
                Material material = opening || arch ? Material.AIR
                        : (y == 4 || y == 9 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                base.clone().add(x, y, MIN_Z).getBlock().setType(material, false);
            }
        }
        for (int x : new int[]{-15, 15}) {
            buildCityTower(base.clone().add(x, 0, MIN_Z + 3), 8, 16);
        }
        for (int x = -5; x <= 5; x++) {
            base.clone().add(x, 8, MIN_Z).getBlock().setType(Material.CHAIN, false);
        }
        base.clone().add(0, 13, MIN_Z - 1).getBlock().setType(Material.EMERALD_BLOCK, false);

        // Plaza de aparición dentro de la muralla: totalmente segura y sin spawners próximos.
        for (int x = -13; x <= 13; x++) {
            for (int z = -79; z <= -64; z++) {
                Location at = base.clone().add(x, 0, z);
                at.getBlock().setType(plazaMaterial(x, z), false);
                clearAbove(at, 1, 8);
            }
        }
        for (int x = -5; x <= 5; x++) for (int z = -76; z <= -68; z++) {
            base.clone().add(x, 0, z).getBlock().setType(Material.POLISHED_ANDESITE, false);
        }
        buildSpawnGazebo(base.clone().add(0, 0, SPAWN_Z));
        lamp(base.clone().add(-10, 1, -76));
        lamp(base.clone().add(10, 1, -76));
        lamp(base.clone().add(-10, 1, -66));
        lamp(base.clone().add(10, 1, -66));
    }

    private void buildSpawnGazebo(Location center) {
        for (int[] p : new int[][]{{-4,-3},{4,-3},{-4,3},{4,3}}) {
            for (int y = 1; y <= 5; y++) {
                center.clone().add(p[0], y, p[1]).getBlock().setType(Material.DARK_OAK_LOG, false);
            }
            center.clone().add(p[0], 6, p[1]).getBlock().setType(Material.LANTERN, false);
        }
        for (int x = -5; x <= 5; x++) for (int z = -4; z <= 4; z++) {
            if (Math.abs(x) == 5 || Math.abs(z) == 4) {
                Location roof = center.clone().add(x, 6, z);
                setSlab(roof, Material.DARK_OAK_SLAB, Slab.Type.TOP);
            }
        }
        center.clone().add(0, 1, 0).getBlock().setType(Material.LODESTONE, false);
        center.clone().add(0, 2, 0).getBlock().setType(Material.SEA_LANTERN, false);
    }

    private void buildRoadNetwork(Location base) {
        // Avenida principal desde la aparición hasta el castillo.
        for (int z = MIN_Z + 4; z <= 45; z++) {
            int half = z < -38 ? 5 : 4;
            for (int x = -half; x <= half; x++) {
                base.clone().add(x, 0, z).getBlock().setType(roadMaterial(x, z), false);
            }
        }
        // Calles transversales que dividen la ciudad en barrios.
        for (int z : new int[]{-60, -46, -30, -10, 14, 34}) {
            for (int x = MIN_X + 5; x <= MAX_X - 5; x++) {
                int width = Math.abs(x) <= 4 ? 0 : 1;
                for (int dz = -2 - width; dz <= 2 + width; dz++) {
                    base.clone().add(x, 0, z + dz).getBlock().setType(roadMaterial(x, z + dz), false);
                }
            }
        }
        // Dos avenidas longitudinales secundarias.
        for (int x : new int[]{-32, 32}) {
            for (int z = MIN_Z + 8; z <= MAX_Z - 5; z++) {
                for (int dx = -2; dx <= 2; dx++) {
                    base.clone().add(x + dx, 0, z).getBlock().setType(roadMaterial(x + dx, z), false);
                }
            }
        }
        for (int z = -78; z <= 40; z += 9) {
            lamp(base.clone().add(-7, 1, z));
            lamp(base.clone().add(7, 1, z));
        }
    }

    private void buildMarketSquare(Location base) {
        Location center = base.clone().add(0, 0, -56);
        for (int x = -18; x <= 18; x++) for (int z = -10; z <= 10; z++) {
            center.clone().add(x, 0, z).getBlock().setType(plazaMaterial(x, z), false);
            clearAbove(center.clone().add(x, 0, z), 1, 7);
        }
        buildMarketFountain(center);
        buildMarketStall(center.clone().add(-12, 0, -6), Material.GREEN_WOOL);
        buildMarketStall(center.clone().add(-12, 0, 5), Material.LIME_WOOL);
        buildMarketStall(center.clone().add(12, 0, -6), Material.YELLOW_WOOL);
        buildMarketStall(center.clone().add(12, 0, 5), Material.WHITE_WOOL);
        buildMarketStall(center.clone().add(-5, 0, 7), Material.GREEN_WOOL);
        buildMarketStall(center.clone().add(5, 0, 7), Material.LIME_WOOL);
    }

    private void buildMarketFountain(Location center) {
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            int edge = Math.max(Math.abs(x), Math.abs(z));
            center.clone().add(x, 1, z).getBlock().setType(
                    edge == 4 ? Material.MOSSY_STONE_BRICKS : edge == 3 ? Material.WATER : Material.AIR, false);
        }
        for (int y = 1; y <= 4; y++) center.clone().add(0, y, 0).getBlock().setType(
                y == 3 ? Material.EMERALD_BLOCK : Material.CHISELED_STONE_BRICKS, false);
        center.clone().add(0, 5, 0).getBlock().setType(Material.AMETHYST_CLUSTER, false);
    }

    private void buildMarketStall(Location center, Material canopy) {
        for (int x : new int[]{-3, 3}) for (int z : new int[]{-2, 2}) {
            for (int y = 1; y <= 4; y++) center.clone().add(x, y, z).getBlock().setType(Material.DARK_OAK_FENCE, false);
        }
        for (int x = -3; x <= 3; x++) for (int z = -2; z <= 2; z++) {
            center.clone().add(x, 5, z).getBlock().setType(
                    Math.floorMod(x + z, 2) == 0 ? canopy : Material.DARK_OAK_PLANKS, false);
        }
        for (int x = -2; x <= 2; x++) center.clone().add(x, 1, 0).getBlock().setType(Material.BARREL, false);
    }

    private void buildWestResidentialDistrict(Location base) {
        buildTownHouse(base.clone().add(-46, 0, -69), 8, 7, 7, false, Material.DARK_OAK_PLANKS);
        buildTownHouse(base.clone().add(-46, 0, -51), 8, 7, 8, true, Material.SPRUCE_PLANKS);
        buildTownHouse(base.clone().add(-47, 0, -34), 9, 7, 7, false, Material.DARK_OAK_PLANKS);
        buildTownHouse(base.clone().add(-47, 0, -16), 8, 7, 8, true, Material.SPRUCE_PLANKS);
        buildTownHouse(base.clone().add(-46, 0, 4), 9, 8, 8, false, Material.DARK_OAK_PLANKS);
        buildChapelAndCemetery(base.clone().add(-43, 0, 27));
        buildRuinedCourtyard(base.clone().add(-23, 0, 25));
    }

    private void buildEastCraftDistrict(Location base) {
        buildTownHouse(base.clone().add(47, 0, -69), 8, 7, 7, true, Material.SPRUCE_PLANKS);
        buildTownHouse(base.clone().add(47, 0, -51), 8, 7, 8, false, Material.DARK_OAK_PLANKS);
        buildBlacksmith(base.clone().add(46, 0, -33));
        buildWarehouse(base.clone().add(47, 0, -12));
        buildInfirmary(base.clone().add(46, 0, 10));
        buildGuardBarracks(base.clone().add(43, 0, 31));
        buildRuinedCourtyard(base.clone().add(23, 0, 25));
    }

    private void buildNorthDistrict(Location base) {
        buildTownHouse(base.clone().add(-27, 0, 39), 8, 6, 7, true, Material.SPRUCE_PLANKS);
        buildTownHouse(base.clone().add(27, 0, 39), 8, 6, 7, false, Material.DARK_OAK_PLANKS);
        buildWatchPost(base.clone().add(-48, 0, 40));
        buildWatchPost(base.clone().add(48, 0, 40));
    }

    private void buildTownHouse(Location center, int radiusX, int radiusZ, int height,
                                boolean ruined, Material roofMaterial) {
        for (int x = -radiusX; x <= radiusX; x++) for (int z = -radiusZ; z <= radiusZ; z++) {
            center.clone().add(x, 0, z).getBlock().setType(Material.COBBLESTONE, false);
            for (int y = 1; y <= height; y++) {
                boolean shell = Math.abs(x) == radiusX || Math.abs(z) == radiusZ;
                boolean door = z == -radiusZ && Math.abs(x) <= 1 && y <= 3;
                boolean window = shell && y >= 3 && y <= 4
                        && ((Math.abs(x) == radiusX && Math.floorMod(z, 5) == 0)
                        || (Math.abs(z) == radiusZ && Math.floorMod(x, 5) == 0));
                boolean broken = ruined && Math.floorMod(x * 13 + z * 7 + y * 5, 23) == 0;
                Material material = Material.AIR;
                if (shell && !door && !broken) {
                    material = window ? Material.IRON_BARS
                            : (y <= 2 ? Material.STONE_BRICKS : Material.DARK_OAK_PLANKS);
                }
                center.clone().add(x, y, z).getBlock().setType(material, false);
            }
        }
        for (int layer = 0; layer <= 3; layer++) {
            int rx = radiusX + 1 - layer;
            int rz = radiusZ + 1 - layer;
            if (rx < 1 || rz < 1) break;
            for (int x = -rx; x <= rx; x++) for (int z = -rz; z <= rz; z++) {
                if (Math.abs(x) == rx || Math.abs(z) == rz) {
                    if (!ruined || Math.floorMod(x * 5 + z * 11 + layer, 13) != 0) {
                        center.clone().add(x, height + 1 + layer, z).getBlock().setType(roofMaterial, false);
                    }
                }
            }
        }
        center.clone().add(-radiusX + 2, 1, radiusZ - 2).getBlock().setType(Material.BARREL, false);
        center.clone().add(radiusX - 2, 1, radiusZ - 2).getBlock().setType(Material.CRAFTING_TABLE, false);
        if (ruined) {
            center.clone().add(0, 1, 0).getBlock().setType(Material.COBWEB, false);
            center.clone().add(2, 1, 1).getBlock().setType(Material.MOSS_BLOCK, false);
        }
    }

    private void buildBlacksmith(Location center) {
        buildTownHouse(center, 9, 7, 7, true, Material.DEEPSLATE_TILES);
        center.clone().add(-4, 1, 2).getBlock().setType(Material.BLAST_FURNACE, false);
        center.clone().add(0, 1, 2).getBlock().setType(Material.SMITHING_TABLE, false);
        center.clone().add(4, 1, 2).getBlock().setType(Material.ANVIL, false);
        for (int x = -5; x <= 5; x++) center.clone().add(x, 1, -3).getBlock().setType(Material.IRON_BARS, false);
    }

    private void buildWarehouse(Location center) {
        buildTownHouse(center, 10, 8, 8, false, Material.DARK_OAK_PLANKS);
        for (int x = -7; x <= 7; x += 4) for (int z = -5; z <= 5; z += 3) {
            center.clone().add(x, 1, z).getBlock().setType(Material.BARREL, false);
            center.clone().add(x, 2, z).getBlock().setType(Material.HAY_BLOCK, false);
        }
    }

    private void buildInfirmary(Location center) {
        buildTownHouse(center, 9, 7, 7, false, Material.SPRUCE_PLANKS);
        for (int x = -6; x <= 6; x += 4) {
            center.clone().add(x, 1, 2).getBlock().setType(Material.WHITE_WOOL, false);
            center.clone().add(x, 2, 2).getBlock().setType(Material.WHITE_CARPET, false);
        }
        center.clone().add(0, 1, -2).getBlock().setType(Material.BREWING_STAND, false);
    }

    private void buildGuardBarracks(Location center) {
        buildTownHouse(center, 11, 8, 8, true, Material.DEEPSLATE_TILES);
        for (int x = -8; x <= 8; x += 4) {
            center.clone().add(x, 1, 3).getBlock().setType(Material.GREEN_WOOL, false);
            center.clone().add(x, 2, 3).getBlock().setType(Material.WHITE_CARPET, false);
        }
        for (int z = -5; z <= 5; z += 2) center.clone().add(8, 1, z).getBlock().setType(Material.IRON_BARS, false);
    }

    private void buildChapelAndCemetery(Location center) {
        buildTownHouse(center, 10, 9, 10, true, Material.DEEPSLATE_TILES);
        for (int y = 1; y <= 14; y++) center.clone().add(0, y, 7).getBlock().setType(
                y == 8 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS, false);
        center.clone().add(0, 15, 7).getBlock().setType(Material.LIGHTNING_ROD, false);
        for (int x = -15; x <= 15; x += 5) for (int z = 12; z <= 19; z += 4) {
            center.clone().add(x, 1, z).getBlock().setType(Material.STONE_BRICK_WALL, false);
            center.clone().add(x, 2, z).getBlock().setType(Material.STONE_BRICK_SLAB, false);
        }
    }

    private void buildRuinedCourtyard(Location center) {
        for (int x = -9; x <= 9; x++) for (int z = -8; z <= 8; z++) {
            center.clone().add(x, 0, z).getBlock().setType(
                    Math.floorMod(x * 3 + z * 7, 9) == 0 ? Material.MOSS_BLOCK : Material.COARSE_DIRT, false);
        }
        for (int x : new int[]{-8, 8}) for (int z : new int[]{-7, 7}) {
            for (int y = 1; y <= 5; y++) center.clone().add(x, y, z).getBlock().setType(Material.STONE_BRICKS, false);
        }
        buildBarricade(center.clone().add(0, 1, -4), false);
        buildBarricade(center.clone().add(0, 1, 4), true);
    }

    private void buildWatchPost(Location center) {
        buildCityTower(center, 6, 12);
        center.clone().add(0, 1, 0).getBlock().setType(Material.SPAWNER, false);
    }

    private void buildSewerEntrances(Location base) {
        for (int[] p : new int[][]{{-29,-47},{29,-47},{-29,14},{29,14}}) {
            Location center = base.clone().add(p[0], 0, p[1]);
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                center.clone().add(x, 0, z).getBlock().setType(Material.DEEPSLATE_BRICKS, false);
            }
            for (int y = 0; y <= 5; y++) {
                center.clone().add(0, -y, 0).getBlock().setType(Material.AIR, false);
                center.clone().add(1, -y, 0).getBlock().setType(Material.AIR, false);
                center.clone().add(-1, -y, 0).getBlock().setType(Material.LADDER, false);
            }
            center.clone().add(0, 1, 0).getBlock().setType(Material.IRON_TRAPDOOR, false);
        }
    }

    private void buildInfestationDetails(Location base) {
        int[][] patches = {
                {-53,-75}, {-41,-61}, {-24,-52}, {25,-67}, {47,-45}, {-49,-27},
                {50,-4}, {-35,9}, {36,21}, {-17,38}, {18,42}
        };
        for (int[] p : patches) {
            Location center = base.clone().add(p[0], 0, p[1]);
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                if (Math.floorMod(x * 7 + z * 11, 5) <= 1) {
                    center.clone().add(x, 1, z).getBlock().setType(Material.MOSS_CARPET, false);
                }
            }
            center.clone().add(0, 1, 0).getBlock().setType(Material.CRYING_OBSIDIAN, false);
            center.clone().add(0, 2, 0).getBlock().setType(Material.AMETHYST_CLUSTER, false);
        }
    }

    private void registerCityEncounters(Location base) {
        List<String> entrance = mobGroup("entrance", List.of(
                "emerald_zombie_minion", "emerald_skeleton_archer_minion"));
        List<String> interior = mobGroup("interior", List.of(
                "emerald_zombie_minion", "mossbound_spider_minion"));
        List<String> ambush = mobGroup("ambush", List.of("emerald_creeper_minion"));
        List<String> elite = mobGroup("elite", List.of(
                "emerald_ravager_cub_minion", "emerald_golem_sentinel_minion"));

        registerSpawner(base.clone().add(-45, 1, -54), "surface_city_residential", interior,
                Material.MOSSY_STONE_BRICKS);
        registerSpawner(base.clone().add(45, 1, -52), "surface_city_residential", entrance,
                Material.CHISELED_STONE_BRICKS);
        registerSpawner(base.clone().add(-47, 1, -17), "surface_city_ambush", ambush,
                Material.CRACKED_STONE_BRICKS);
        registerSpawner(base.clone().add(46, 1, -31), "surface_city_elite", elite,
                Material.DEEPSLATE_BRICKS);
        registerSpawner(base.clone().add(-43, 1, 31), "surface_city_cemetery", interior,
                Material.MOSSY_COBBLESTONE);
        registerSpawner(base.clone().add(43, 1, 31), "surface_city_barracks", elite,
                Material.CHISELED_STONE_BRICKS);
        registerSpawner(base.clone().add(-23, 1, 25), "surface_city_ruins", ambush,
                Material.CRACKED_STONE_BRICKS);
        registerSpawner(base.clone().add(23, 1, 25), "surface_city_ruins", entrance,
                Material.MOSSY_STONE_BRICKS);
        registerSpawner(base.clone().add(-48, 2, 40), "surface_city_watch", entrance,
                Material.STONE_BRICKS);
        registerSpawner(base.clone().add(48, 2, 40), "surface_city_watch", entrance,
                Material.STONE_BRICKS);
    }

    private void registerSpawner(Location at, String group, List<String> entities, Material pedestal) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            at.clone().add(x, -1, z).getBlock().setType(pedestal, false);
        }
        clearAbove(at, 0, 4);
        spawners.register(at, group, entities, true);
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            at.clone().add(face.getModX(), 0, face.getModZ()).getBlock().setType(Material.IRON_BARS, false);
        }
    }

    private void placeCityLoot(Location base) {
        placeChest(base.clone().add(-52, 1, -70), AdaptiveDungeonLootManager.ChestTier.COMMON);
        placeChest(base.clone().add(52, 1, -51), AdaptiveDungeonLootManager.ChestTier.COMMON);
        placeChest(base.clone().add(-48, 1, -34), AdaptiveDungeonLootManager.ChestTier.RARE);
        placeChest(base.clone().add(48, 1, -10), AdaptiveDungeonLootManager.ChestTier.RARE);
        placeChest(base.clone().add(-42, 1, 28), AdaptiveDungeonLootManager.ChestTier.EPIC);
        placeChest(base.clone().add(42, 1, 31), AdaptiveDungeonLootManager.ChestTier.EPIC);
    }

    private void placeChest(Location at, AdaptiveDungeonLootManager.ChestTier tier) {
        at.clone().add(0, -1, 0).getBlock().setType(Material.STONE_BRICKS, false);
        clearAbove(at, 0, 2);
        adaptiveLoot.placeCustomChest(at, AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD, tier);
    }

    private List<String> mobGroup(String key, List<String> fallback) {
        List<String> configured = plugin.getConfig().getStringList("overworld-castle.mob-groups." + key);
        List<String> clean = configured.stream().map(String::trim).filter(value -> !value.isBlank()).toList();
        return clean.isEmpty() ? fallback : clean;
    }

    private void buildCityTower(Location center, int radius, int height) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            center.clone().add(x, 0, z).getBlock().setType(Material.STONE_BRICKS, false);
            for (int y = 1; y <= height; y++) {
                boolean shell = Math.abs(x) == radius || Math.abs(z) == radius;
                boolean window = shell && y >= 4 && y <= 6
                        && ((x == 0 && Math.abs(z) == radius) || (z == 0 && Math.abs(x) == radius));
                center.clone().add(x, y, z).getBlock().setType(
                        shell ? (window ? Material.IRON_BARS
                                : (y == 4 || y == 9 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS))
                                : Material.AIR, false);
            }
        }
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if ((Math.abs(x) == radius || Math.abs(z) == radius) && Math.floorMod(x + z, 2) == 0) {
                center.clone().add(x, height + 1, z).getBlock().setType(Material.STONE_BRICK_WALL, false);
            }
        }
    }

    private void buildWallColumn(World world, int x, int y, int z, int height) {
        for (int dy = 0; dy < height; dy++) {
            world.getBlockAt(x, y + dy, z).setType(
                    dy == 3 || dy == 6 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS, false);
        }
        world.getBlockAt(x, y + height, z).setType(Material.STONE_BRICK_WALL, false);
    }

    private void carveGate(Location center, int halfWidth, int height, BlockFace direction) {
        boolean alongX = direction == BlockFace.NORTH || direction == BlockFace.SOUTH;
        for (int offset = -halfWidth; offset <= halfWidth; offset++) {
            for (int y = 1; y <= height; y++) {
                Location at = alongX ? center.clone().add(offset, y, 0) : center.clone().add(0, y, offset);
                at.getBlock().setType(Material.AIR, false);
            }
        }
    }

    private void buildBarricade(Location center, boolean alongZ) {
        for (int i = -3; i <= 3; i++) {
            int x = alongZ ? 0 : i;
            int z = alongZ ? i : 0;
            center.clone().add(x, 0, z).getBlock().setType(
                    i == 0 ? Material.DEEPSLATE_BRICKS : Material.DARK_OAK_PLANKS, false);
            if (Math.floorMod(i, 2) == 0) {
                center.clone().add(x, 1, z).getBlock().setType(Material.IRON_BARS, false);
            }
        }
    }

    private Material roadMaterial(int x, int z) {
        int value = Math.floorMod(x * 19 + z * 7, 29);
        if (value == 0) return Material.CRACKED_STONE_BRICKS;
        if (value <= 3) return Material.MOSSY_COBBLESTONE;
        if (value <= 8) return Material.COBBLESTONE;
        return Material.STONE_BRICKS;
    }

    private Material plazaMaterial(int x, int z) {
        int value = Math.floorMod(x * 11 + z * 17, 23);
        if (value == 0) return Material.CRACKED_STONE_BRICKS;
        if (value <= 3) return Material.MOSSY_STONE_BRICKS;
        return Material.POLISHED_ANDESITE;
    }

    private void lamp(Location at) {
        at.getBlock().setType(Material.STONE_BRICK_WALL, false);
        at.clone().add(0, 1, 0).getBlock().setType(Material.LANTERN, false);
    }

    private void clearAbove(Location at, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) at.clone().add(0, y, 0).getBlock().setType(Material.AIR, false);
    }

    private void setSlab(Location at, Material material, Slab.Type type) {
        at.getBlock().setType(material, false);
        if (at.getBlock().getBlockData() instanceof Slab slab) {
            slab.setType(type);
            slab.setWaterlogged(false);
            at.getBlock().setBlockData(slab, false);
        }
    }

    @SuppressWarnings("unused")
    private void setStair(Location at, Material material, BlockFace facing) {
        at.getBlock().setType(material, false);
        if (at.getBlock().getBlockData() instanceof Stairs stairs) {
            stairs.setFacing(facing);
            stairs.setHalf(Bisected.Half.BOTTOM);
            stairs.setShape(Stairs.Shape.STRAIGHT);
            stairs.setWaterlogged(false);
            at.getBlock().setBlockData(stairs, false);
        }
    }

    @SuppressWarnings("unused")
    private void face(Location at, BlockFace face) {
        if (at.getBlock().getBlockData() instanceof Directional directional) {
            directional.setFacing(face);
            at.getBlock().setBlockData(directional, false);
        }
    }

    private Location centered(Location location) {
        return new Location(location.getWorld(), location.getBlockX() + 0.5,
                location.getBlockY(), location.getBlockZ() + 0.5,
                location.getYaw(), location.getPitch());
    }
}
