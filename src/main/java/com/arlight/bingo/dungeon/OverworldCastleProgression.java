package com.arlight.bingo.dungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Castillo invadido del Overworld, reconstruido como una mazmorra legible:
 * llegada segura -> muralla exterior -> patio -> gran salón -> pisos temáticos
 * -> arena del jefe -> tesorería/portal. Todas las escaleras tienen soporte,
 * descansos y orientación explícita para evitar tramos invertidos o flotantes.
 */
final class OverworldCastleProgression {
    private static final int KEEP_RADIUS = 15;
    private static final int FLOOR_HEIGHT = 8;
    private static final int OUTER_WALL_X = 23;
    private static final int OUTER_GATE_Z = -38;
    private static final int KEEP_GATE_Z = -15;
    private static final int BASEMENT_FLOOR = -7;

    record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) { }
    record Result(Bounds bounds, List<Location> checkpoints, int rescueY, Location spawnLocation) { }

    private final JavaPlugin plugin;
    private final AdaptiveDungeonLootManager adaptiveLoot;
    private final DungeonMinionSpawnerManager spawners;

    OverworldCastleProgression(JavaPlugin plugin, AdaptiveDungeonLootManager adaptiveLoot,
                               DungeonMinionSpawnerManager spawners) {
        this.plugin = plugin;
        this.adaptiveLoot = adaptiveLoot;
        this.spawners = spawners;
    }

    Result build(Location base, int combatLevels, Location safeArrival) {
        List<Location> checkpoints = new ArrayList<>();
        if (!enabled()) {
            Location fallbackSpawn = centered(safeArrival);
            checkpoints.add(fallbackSpawn);
            return new Result(defaultBounds(base, combatLevels), List.copyOf(checkpoints),
                    base.getBlockY() - 10, fallbackSpawn);
        }

        int bossLevel = combatLevels - 1;
        int portalLevel = combatLevels;

        OverworldInfestedCity.Result city = new OverworldInfestedCity(plugin, adaptiveLoot, spawners)
                .prepareAndBuild(base, combatLevels);
        Location integratedSpawn = city.spawn();
        checkpoints.addAll(city.checkpoints());
        OverworldLiberationExpansion.Result outerCity = new OverworldLiberationExpansion(
                plugin, adaptiveLoot, spawners).build(base);
        checkpoints.addAll(outerCity.checkpoints());
        // La campaña empieza en la plaza de acceso de la muralla exterior, no dentro
        // de la ciudad antigua. Aquí aparece Somita y se ve la avenida principal.
        integratedSpawn = centered(base.clone().add(0, 1, OverworldLiberationExpansion.MIN_Z + 13));

        buildApproach(base, integratedSpawn);
        buildOuterFortifications(base);
        buildOccupiedCourtyard(base);
        buildKeepShell(base, portalLevel + 1);
        buildGroundFloor(base);
        buildBasement(base);
        buildLibraryAndArmoryFloor(base, 1);
        if (bossLevel > 2) buildBarracksAndPrisonFloor(base, 2);
        for (int level = 3; level < bossLevel; level++) buildWatchFloor(base, level);
        buildBossFloor(base, bossLevel);
        buildPortalTreasury(base, portalLevel);
        buildFloorConnections(base, portalLevel + 1);
        buildCornerTowers(base, portalLevel + 1);
        buildRoof(base, portalLevel + 1);
        buildCorruptionAndDamage(base, bossLevel);
        registerEncounters(base, bossLevel);
        placeExplorationLoot(base, bossLevel, portalLevel);
        CampaignStructureDetails.decorateOverworld(base, adaptiveLoot, spawners);
        ReferenceDrivenCityPass.Expansion referenceCity = ReferenceDrivenCityPass.buildOverworld(
                plugin, base, adaptiveLoot, spawners);
        checkpoints.addAll(referenceCity.checkpoints());
        TerrainAdaptiveCampaignPass.adaptOverworld(plugin, base, adaptiveLoot);
        buildRouteMarkers(base, integratedSpawn, bossLevel);

        checkpoints.add(centered(base.clone().add(0, 1, -31)));
        checkpoints.add(centered(base.clone().add(0, 1, -13)));
        checkpoints.add(centered(base.clone().add(8, FLOOR_HEIGHT + 1, -3)));
        if (bossLevel > 2) checkpoints.add(centered(base.clone().add(-8, FLOOR_HEIGHT * 2 + 1, 3)));
        checkpoints.add(centered(base.clone().add(0, bossLevel * FLOOR_HEIGHT + 1, -11)));

        return new Result(defaultBounds(base, combatLevels), List.copyOf(checkpoints),
                base.getBlockY() - 10, integratedSpawn);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("overworld-castle.enabled", true);
    }

    private Bounds defaultBounds(Location base, int combatLevels) {
        int top = base.getBlockY() + (combatLevels + 1) * FLOOR_HEIGHT + 18;
        return new Bounds(base.getBlockX() - 172,
                base.getBlockX() + 172,
                base.getBlockY() - 24, top,
                base.getBlockZ() - 184,
                base.getBlockZ() + 140);
    }

    private void buildApproach(Location base, Location arrival) {
        int startZ = Math.min(arrival.getBlockZ() - base.getBlockZ() + 7, OUTER_GATE_Z - 4);
        for (int z = startZ; z <= OUTER_GATE_Z; z++) {
            int width = z < OUTER_GATE_Z - 8 ? 4 : 6;
            for (int x = -width; x <= width; x++) {
                Location at = base.clone().add(x, 0, z);
                at.clone().add(0, -1, 0).getBlock().setType(Material.STONE_BRICKS, false);
                at.getBlock().setType(roadMaterial(x, z), false);
                clear(at, 1, 6);
            }
            if (Math.floorMod(z, 6) == 0) {
                lamp(base.clone().add(-width - 2, 1, z));
                lamp(base.clone().add(width + 2, 1, z));
            }
        }
        // Dos obeliscos hacen visible la entrada desde lejos.
        for (int x : new int[]{-8, 8}) {
            for (int y = 1; y <= 7; y++) {
                base.clone().add(x, y, OUTER_GATE_Z - 5).getBlock().setType(
                        y == 5 ? Material.MOSSY_STONE_BRICKS : Material.CHISELED_STONE_BRICKS, false);
            }
            base.clone().add(x, 8, OUTER_GATE_Z - 5).getBlock().setType(Material.SOUL_LANTERN, false);
        }
    }

    private void buildOuterFortifications(Location base) {
        // Plataforma completa del patio para que nunca quede flotando en laderas.
        for (int x = -OUTER_WALL_X; x <= OUTER_WALL_X; x++) {
            for (int z = OUTER_GATE_Z; z <= KEEP_GATE_Z - 1; z++) {
                Location at = base.clone().add(x, 0, z);
                at.clone().add(0, -1, 0).getBlock().setType(Material.STONE_BRICKS, false);
                at.getBlock().setType(courtyardMaterial(x, z), false);
                clear(at, 1, 9);
            }
        }

        // Muralla frontal y laterales, con adarve transitable.
        for (int x = -OUTER_WALL_X; x <= OUTER_WALL_X; x++) {
            if (Math.abs(x) > 4) buildWallColumn(base.clone().add(x, 1, OUTER_GATE_Z), 8);
        }
        for (int z = OUTER_GATE_Z; z <= KEEP_GATE_Z - 1; z++) {
            buildWallColumn(base.clone().add(-OUTER_WALL_X, 1, z), 8);
            buildWallColumn(base.clone().add(OUTER_WALL_X, 1, z), 8);
        }
        for (int x = -OUTER_WALL_X; x <= OUTER_WALL_X; x += 6) {
            supportToGround(base.clone().add(x, -2, OUTER_GATE_Z), Material.STONE_BRICKS, 80);
        }
        for (int z = OUTER_GATE_Z; z <= KEEP_GATE_Z - 1; z += 6) {
            supportToGround(base.clone().add(-OUTER_WALL_X, -2, z), Material.STONE_BRICKS, 80);
            supportToGround(base.clone().add(OUTER_WALL_X, -2, z), Material.STONE_BRICKS, 80);
        }

        for (int x = -OUTER_WALL_X; x <= OUTER_WALL_X; x++) {
            Location walk = base.clone().add(x, 8, OUTER_GATE_Z);
            walk.getBlock().setType(Material.STONE_BRICKS, false);
            if (Math.floorMod(x, 2) == 0 && Math.abs(x) > 4) {
                walk.clone().add(0, 1, 0).getBlock().setType(Material.STONE_BRICK_WALL, false);
            }
        }

        buildGateTower(base.clone().add(-15, 0, OUTER_GATE_Z + 1), false);
        buildGateTower(base.clone().add(15, 0, OUTER_GATE_Z + 1), true);
        buildOuterGateArch(base);
    }

    private void buildOuterGateArch(Location base) {
        for (int x = -8; x <= 8; x++) {
            for (int y = 1; y <= 12; y++) {
                boolean opening = Math.abs(x) <= 4 && y <= 7;
                boolean archGap = Math.abs(x) == 4 && y == 8 || Math.abs(x) == 3 && y == 9;
                Material material = opening || archGap ? Material.AIR
                        : (y == 4 || y == 9 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                base.clone().add(x, y, OUTER_GATE_Z).getBlock().setType(material, false);
            }
        }
        for (int x = -4; x <= 4; x++) {
            base.clone().add(x, 8, OUTER_GATE_Z).getBlock().setType(Material.CHAIN, false);
        }
        base.clone().add(0, 11, OUTER_GATE_Z - 1).getBlock().setType(Material.EMERALD_BLOCK, false);
    }

    private void buildGateTower(Location center, boolean mirrored) {
        buildHollowRoom(center, 6, 5, 14, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                Material.COBBLESTONE);
        for (int x : new int[]{-5, 0, 5}) for (int z : new int[]{-4, 0, 4}) {
            supportToGround(center.clone().add(x, -1, z), Material.STONE_BRICKS, 80);
        }
        carveDoor(center.clone().add(mirrored ? -6 : 6, 0, 0), Axis.X, 3, 5);
        carveDoor(center.clone().add(0, 0, 5), Axis.Z, 3, 4);

        // El piso del adarve se coloca ANTES de abrir el hueco de escalera. En 1.16.0
        // se colocaba después y volvía a tapar la cabeza de los jugadores.
        for (int x = -5; x <= 5; x++) for (int z = -4; z <= 4; z++) {
            center.clone().add(x, FLOOR_HEIGHT, z).getBlock().setType(Material.STONE_BRICKS, false);
        }

        int x0 = mirrored ? -2 : 1;
        int stairCount = FLOOR_HEIGHT - 1;
        for (int x = x0 - 1; x <= x0 + 2; x++) {
            for (int z = -4; z <= 5; z++) {
                for (int y = 1; y <= FLOOR_HEIGHT + 3; y++) {
                    center.clone().add(x, y, z).getBlock().setType(Material.AIR, false);
                }
            }
        }
        for (int step = 0; step < stairCount; step++) {
            int z = -3 + step;
            int y = 1 + step;
            for (int dx = 0; dx < 2; dx++) {
                Location stair = center.clone().add(x0 + dx, y, z);
                setStair(stair, BlockFace.SOUTH, Material.STONE_BRICK_STAIRS);
                stair.clone().add(0, -1, 0).getBlock().setType(Material.STONE_BRICKS, false);
                clear(stair, 1, 4);
            }
        }
        int landingZ = -3 + stairCount;
        for (int x = x0 - 1; x <= x0 + 2; x++) {
            for (int z = landingZ; z <= landingZ + 2; z++) {
                center.clone().add(x, FLOOR_HEIGHT, z).getBlock().setType(Material.STONE_BRICKS, false);
                clear(center.clone().add(x, FLOOR_HEIGHT, z), 1, 4);
            }
        }

        for (int x = -5; x <= 5; x++) for (int z = -4; z <= 4; z++) {
            if ((Math.abs(x) == 5 || Math.abs(z) == 4) && Math.floorMod(x + z, 2) == 0) {
                center.clone().add(x, 15, z).getBlock().setType(Material.STONE_BRICK_WALL, false);
            }
        }
        center.clone().add(0, FLOOR_HEIGHT + 1, 0).getBlock().setType(Material.SPAWNER, false);
    }

    private void buildOccupiedCourtyard(Location base) {
        // Camino central ancho y dos rutas laterales con cobertura.
        for (int z = OUTER_GATE_Z + 2; z <= KEEP_GATE_Z - 2; z++) {
            for (int x = -5; x <= 5; x++) {
                base.clone().add(x, 0, z).getBlock().setType(roadMaterial(x, z), false);
            }
        }

        buildCorruptedFountain(base.clone().add(0, 0, -27));
        buildInvaderCamp(base.clone().add(-17, 0, -29), false);
        buildInvaderCamp(base.clone().add(17, 0, -29), true);
        buildStableRuin(base.clone().add(-17, 0, -20));
        buildWorkshopRuin(base.clone().add(17, 0, -20));

        int[][] barricades = {
                {-9, -34, 0}, {9, -32, 1}, {-11, -25, 1}, {11, -23, 0}, {-4, -19, 0}, {5, -18, 1}
        };
        for (int[] value : barricades) {
            buildBarricade(base.clone().add(value[0], 1, value[1]), value[2] == 1);
        }
    }

    private void buildCorruptedFountain(Location center) {
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            int edge = Math.max(Math.abs(x), Math.abs(z));
            Material material = edge == 4 ? Material.MOSSY_STONE_BRICKS
                    : edge == 3 ? Material.WATER : Material.AIR;
            center.clone().add(x, 1, z).getBlock().setType(material, false);
        }
        center.clone().add(0, 1, 0).getBlock().setType(Material.EMERALD_BLOCK, false);
        center.clone().add(0, 2, 0).getBlock().setType(Material.CRYING_OBSIDIAN, false);
        center.clone().add(0, 3, 0).getBlock().setType(Material.AMETHYST_CLUSTER, false);
        for (int[] p : new int[][]{{-3,-3},{3,-3},{-3,3},{3,3}}) {
            center.clone().add(p[0], 2, p[1]).getBlock().setType(Material.SOUL_LANTERN, false);
        }
    }

    private void buildInvaderCamp(Location center, boolean mirrored) {
        for (int x = -6; x <= 6; x++) for (int z = -5; z <= 5; z++) {
            center.clone().add(x, 0, z).getBlock().setType(
                    Math.floorMod(x * 5 + z * 3, 7) == 0 ? Material.PODZOL : Material.COARSE_DIRT, false);
            clear(center.clone().add(x, 0, z), 1, 6);
        }
        for (int x : new int[]{-6, 6}) for (int z : new int[]{-5, 5}) {
            for (int y = 1; y <= 5; y++) center.clone().add(x, y, z).getBlock().setType(Material.DARK_OAK_FENCE, false);
        }
        for (int x = -6; x <= 6; x++) for (int z = -5; z <= 5; z++) {
            if (Math.abs(x) + Math.abs(z) >= 8) {
                center.clone().add(x, 6, z).getBlock().setType(
                        mirrored ? Material.LIME_WOOL : Material.GREEN_WOOL, false);
            }
        }
        center.clone().add(0, 1, 0).getBlock().setType(Material.SOUL_CAMPFIRE, false);
        center.clone().add(-3, 1, 1).getBlock().setType(Material.BARREL, false);
        center.clone().add(3, 1, 1).getBlock().setType(Material.CRAFTING_TABLE, false);
        center.clone().add(0, 1, -3).getBlock().setType(Material.SPAWNER, false);
    }

    private void buildStableRuin(Location center) {
        for (int x = -5; x <= 5; x++) for (int z = -4; z <= 4; z++) {
            center.clone().add(x, 0, z).getBlock().setType(Material.MUD_BRICKS, false);
            clear(center.clone().add(x, 0, z), 1, 6);
        }
        for (int x : new int[]{-5, 0, 5}) {
            for (int y = 1; y <= 5; y++) {
                center.clone().add(x, y, -4).getBlock().setType(Material.DARK_OAK_LOG, false);
                center.clone().add(x, y, 4).getBlock().setType(Material.DARK_OAK_LOG, false);
            }
        }
        for (int x = -5; x <= 5; x++) {
            center.clone().add(x, 5, -4).getBlock().setType(Material.DARK_OAK_PLANKS, false);
            center.clone().add(x, 5, 4).getBlock().setType(Material.DARK_OAK_PLANKS, false);
        }
        center.clone().add(-3, 1, 0).getBlock().setType(Material.HAY_BLOCK, false);
        center.clone().add(3, 1, 0).getBlock().setType(Material.HAY_BLOCK, false);
    }

    private void buildWorkshopRuin(Location center) {
        for (int x = -5; x <= 5; x++) for (int z = -4; z <= 4; z++) {
            center.clone().add(x, 0, z).getBlock().setType(Material.DEEPSLATE_TILES, false);
            clear(center.clone().add(x, 0, z), 1, 6);
        }
        for (int x = -5; x <= 5; x++) for (int y = 1; y <= 5; y++) {
            if (Math.floorMod(x + y, 5) != 0) {
                center.clone().add(x, y, 4).getBlock().setType(Material.DEEPSLATE_BRICKS, false);
            }
        }
        center.clone().add(-3, 1, 1).getBlock().setType(Material.BLAST_FURNACE, false);
        center.clone().add(0, 1, 1).getBlock().setType(Material.SMITHING_TABLE, false);
        center.clone().add(3, 1, 1).getBlock().setType(Material.ANVIL, false);
        for (int z = -2; z <= 2; z++) center.clone().add(5, 1, z).getBlock().setType(Material.IRON_BARS, false);
    }

    private void buildKeepShell(Location base, int totalLevels) {
        for (int level = 0; level < totalLevels; level++) {
            int y0 = level * FLOOR_HEIGHT;
            for (int x = -KEEP_RADIUS; x <= KEEP_RADIUS; x++) {
                for (int z = -KEEP_RADIUS; z <= KEEP_RADIUS; z++) {
                    Location floor = base.clone().add(x, y0, z);
                    floor.getBlock().setType(keepFloorMaterial(x, z, level), false);
                    for (int y = 1; y < FLOOR_HEIGHT; y++) {
                        boolean edge = Math.abs(x) == KEEP_RADIUS || Math.abs(z) == KEEP_RADIUS;
                        boolean innerEdge = Math.abs(x) == KEEP_RADIUS - 1 || Math.abs(z) == KEEP_RADIUS - 1;
                        Material material = Material.AIR;
                        if (edge || innerEdge) {
                            boolean outerFace = edge;
                            boolean window = outerFace && y >= 3 && y <= 5
                                    && Math.floorMod(x * 3 + z * 5 + level, 7) == 0;
                            material = window ? Material.CYAN_STAINED_GLASS_PANE
                                    : (y == 3 || y == 6 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                        }
                        base.clone().add(x, y0 + y, z).getBlock().setType(material, false);
                    }
                }
            }
            buildLevelButtresses(base, y0);
        }

        // Entrada monumental al casco principal.
        for (int x = -5; x <= 5; x++) for (int y = 1; y <= 8; y++) {
            boolean opening = Math.abs(x) <= 3 && y <= 6;
            base.clone().add(x, y, KEEP_GATE_Z).getBlock().setType(opening ? Material.AIR
                    : (y == 4 || y == 7 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS), false);
            base.clone().add(x, y, KEEP_GATE_Z + 1).getBlock().setType(opening ? Material.AIR
                    : Material.STONE_BRICKS, false);
        }
        for (int z = KEEP_GATE_Z - 3; z <= KEEP_GATE_Z; z++) {
            for (int x = -4; x <= 4; x++) {
                base.clone().add(x, 0, z).getBlock().setType(Material.STONE_BRICKS, false);
                clear(base.clone().add(x, 0, z), 1, 6);
            }
        }
    }

    private void buildLevelButtresses(Location base, int y0) {
        for (int x : new int[]{-15, -10, 0, 10, 15}) {
            for (int y = 1; y <= 7; y++) {
                base.clone().add(x, y0 + y, -16).getBlock().setType(
                        y == 4 ? Material.MOSSY_STONE_BRICKS : Material.CHISELED_STONE_BRICKS, false);
                base.clone().add(x, y0 + y, 16).getBlock().setType(
                        y == 4 ? Material.MOSSY_STONE_BRICKS : Material.CHISELED_STONE_BRICKS, false);
            }
        }
        for (int z : new int[]{-10, 0, 10}) {
            for (int y = 1; y <= 7; y++) {
                base.clone().add(-16, y0 + y, z).getBlock().setType(Material.CHISELED_STONE_BRICKS, false);
                base.clone().add(16, y0 + y, z).getBlock().setType(Material.CHISELED_STONE_BRICKS, false);
            }
        }
    }

    private void buildGroundFloor(Location base) {
        // Gran salón central.
        buildPartition(base, 0, -7, true, -13, 11, List.of(-8, 1, 8));
        buildPartition(base, 0, 7, true, -13, 11, List.of(-8, 1, 8));
        for (int x : new int[]{-5, 5}) for (int z : new int[]{-10, -2, 6}) {
            for (int y = 1; y <= 6; y++) base.clone().add(x, y, z).getBlock().setType(
                    y == 3 ? Material.MOSSY_STONE_BRICKS : Material.CHISELED_STONE_BRICKS, false);
            base.clone().add(x, 6, z).getBlock().setType(Material.LANTERN, false);
        }
        for (int z = -12; z <= 9; z++) {
            base.clone().add(0, 1, z).getBlock().setType(
                    Math.floorMod(z, 5) == 0 ? Material.LIME_CARPET : Material.GREEN_CARPET, false);
        }

        // Comedor y cocina occidental.
        for (int z = -10; z <= 8; z += 5) {
            for (int x = -13; x <= -9; x++) base.clone().add(x, 1, z).getBlock().setType(Material.DARK_OAK_SLAB, false);
            base.clone().add(-11, 1, z - 1).getBlock().setType(Material.DARK_OAK_FENCE, false);
            base.clone().add(-11, 1, z + 1).getBlock().setType(Material.DARK_OAK_FENCE, false);
        }
        for (int z = 7; z <= 12; z += 2) {
            base.clone().add(-13, 1, z).getBlock().setType(Material.SMOKER, false);
            base.clone().add(-10, 1, z).getBlock().setType(Material.BARREL, false);
        }

        // Armería y taller oriental.
        for (int z = -10; z <= 9; z += 4) {
            base.clone().add(10, 1, z).getBlock().setType(Material.IRON_BARS, false);
            base.clone().add(12, 1, z).getBlock().setType(Material.IRON_BLOCK, false);
        }
        base.clone().add(11, 1, 10).getBlock().setType(Material.ANVIL, false);
        base.clone().add(9, 1, 10).getBlock().setType(Material.SMITHING_TABLE, false);
        base.clone().add(13, 1, 10).getBlock().setType(Material.BLAST_FURNACE, false);
    }

    private void buildBasement(Location base) {
        for (int x = -13; x <= 13; x++) for (int z = -12; z <= 12; z++) {
            base.clone().add(x, BASEMENT_FLOOR, z).getBlock().setType(
                    Math.floorMod(x + z, 5) == 0 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS,
                    false);
            for (int y = BASEMENT_FLOOR + 1; y <= -1; y++) {
                boolean shell = Math.abs(x) == 13 || Math.abs(z) == 12;
                base.clone().add(x, y, z).getBlock().setType(shell
                        ? (y == -4 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS)
                        : Material.AIR, false);
            }
        }
        // Prisión, almacén y alcantarilla.
        for (int z = -10; z <= 3; z += 4) {
            for (int x = -11; x <= -2; x++) {
                base.clone().add(x, -5, z).getBlock().setType(Material.IRON_BARS, false);
            }
            carveDoor(base.clone().add(-6, -6, z), Axis.Z, 2, 3);
        }
        for (int z = -9; z <= 9; z += 3) {
            base.clone().add(9, -6, z).getBlock().setType(Material.BARREL, false);
            base.clone().add(11, -6, z).getBlock().setType(Material.COBWEB, false);
        }
        for (int z = 5; z <= 10; z++) {
            base.clone().add(0, -6, z).getBlock().setType(Material.WATER, false);
        }

        // Escalera de sótano de 7 peldaños, soportada y orientada al este.
        for (int step = 0; step < 7; step++) {
            for (int dz = 0; dz < 2; dz++) {
                Location stair = base.clone().add(-11 + step, -6 + step, 8 + dz);
                setStair(stair, BlockFace.EAST, Material.DEEPSLATE_BRICK_STAIRS);
                stair.clone().add(0, -1, 0).getBlock().setType(Material.DEEPSLATE_BRICKS, false);
                clear(stair, 1, 3);
            }
        }
        for (int x = -11; x <= -4; x++) for (int z = 8; z <= 9; z++) {
            base.clone().add(x, 0, z).getBlock().setType(Material.AIR, false);
        }
        base.clone().add(-3, 0, 8).getBlock().setType(Material.STONE_BRICKS, false);
        base.clone().add(-3, 0, 9).getBlock().setType(Material.STONE_BRICKS, false);
    }

    private void buildLibraryAndArmoryFloor(Location base, int level) {
        int y0 = level * FLOOR_HEIGHT;
        buildPartition(base, y0, 0, false, -13, 13, List.of(-8, 0, 8));

        // Biblioteca occidental con pasillos reales entre estanterías.
        for (int x = -12; x <= -2; x += 3) {
            for (int z = -11; z <= 11; z++) {
                if (Math.abs(z) <= 1 || Math.floorMod(z, 5) == 0) continue;
                for (int y = 1; y <= 4; y++) base.clone().add(x, y0 + y, z).getBlock().setType(Material.BOOKSHELF, false);
            }
        }
        base.clone().add(-7, y0 + 1, 0).getBlock().setType(Material.ENCHANTING_TABLE, false);
        base.clone().add(-4, y0 + 1, 9).getBlock().setType(Material.LECTERN, false);

        // Sala de mapas/armas oriental.
        for (int x = 2; x <= 12; x += 3) {
            for (int z = -10; z <= 10; z += 4) {
                base.clone().add(x, y0 + 1, z).getBlock().setType(
                        Math.floorMod(x + z, 2) == 0 ? Material.CARTOGRAPHY_TABLE : Material.SMITHING_TABLE, false);
            }
        }
        for (int z = -11; z <= 11; z += 3) base.clone().add(13, y0 + 1, z).getBlock().setType(Material.IRON_BARS, false);
        chandelier(base.clone().add(0, y0 + 7, 0));
    }

    private void buildBarracksAndPrisonFloor(Location base, int level) {
        int y0 = level * FLOOR_HEIGHT;
        buildPartition(base, y0, -5, true, -13, 13, List.of(-9, 0, 9));
        buildPartition(base, y0, 5, true, -13, 13, List.of(-9, 0, 9));

        // Dormitorios delanteros.
        for (int x = -12; x <= 12; x += 4) {
            base.clone().add(x, y0 + 1, -10).getBlock().setType(Material.GREEN_WOOL, false);
            base.clone().add(x, y0 + 2, -10).getBlock().setType(Material.WHITE_CARPET, false);
            base.clone().add(x, y0 + 1, -7).getBlock().setType(Material.BARREL, false);
        }
        // Celdas traseras y almacenes.
        for (int x = -12; x <= 12; x += 4) {
            for (int z = 6; z <= 12; z++) {
                base.clone().add(x, y0 + 1, z).getBlock().setType(Material.IRON_BARS, false);
            }
        }
        for (int x = -10; x <= 10; x += 5) base.clone().add(x, y0 + 1, 9).getBlock().setType(Material.CHEST, false);
        chandelier(base.clone().add(0, y0 + 7, 0));
    }

    private void buildWatchFloor(Location base, int level) {
        int y0 = level * FLOOR_HEIGHT;
        // Galería circular abierta con puestos de vigilancia.
        for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
            if (Math.max(Math.abs(x), Math.abs(z)) >= 7) {
                base.clone().add(x, y0 + 1, z).getBlock().setType(
                        Math.floorMod(x + z, 4) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS, false);
            }
        }
        for (int[] p : new int[][]{{-10,-10},{10,-10},{-10,10},{10,10}}) {
            for (int y = 1; y <= 5; y++) base.clone().add(p[0], y0 + y, p[1]).getBlock().setType(Material.CHISELED_STONE_BRICKS, false);
            base.clone().add(p[0], y0 + 6, p[1]).getBlock().setType(Material.LANTERN, false);
        }
        buildBarricade(base.clone().add(-5, y0 + 1, -5), false);
        buildBarricade(base.clone().add(5, y0 + 1, 5), true);
    }

    private void buildBossFloor(Location base, int bossLevel) {
        int y0 = bossLevel * FLOOR_HEIGHT;
        // Arena 25x25 limpia, con columnas y un trono elevado.
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            for (int y = 1; y <= 6; y++) base.clone().add(x, y0 + y, z).getBlock().setType(Material.AIR, false);
            if (Math.floorMod(x * 3 + z * 7, 13) == 0) {
                base.clone().add(x, y0, z).getBlock().setType(Material.CRACKED_STONE_BRICKS, false);
            }
        }
        for (int x : new int[]{-11, -6, 6, 11}) for (int z : new int[]{-10, 0, 10}) {
            for (int y = 1; y <= 6; y++) base.clone().add(x, y0 + y, z).getBlock().setType(
                    y == 3 ? Material.EMERALD_BLOCK : Material.CHISELED_STONE_BRICKS, false);
        }
        for (int x = -6; x <= 6; x++) for (int z = 8; z <= 12; z++) {
            base.clone().add(x, y0 + 1, z).getBlock().setType(Material.MOSSY_STONE_BRICKS, false);
        }
        for (int x = -3; x <= 3; x++) for (int z = 10; z <= 12; z++) {
            base.clone().add(x, y0 + 2, z).getBlock().setType(Material.DARK_OAK_PLANKS, false);
        }
        base.clone().add(0, y0 + 3, 12).getBlock().setType(Material.EMERALD_BLOCK, false);
        for (int x = -10; x <= 10; x += 4) {
            base.clone().add(x, y0 + 1, -11).getBlock().setType(Material.SOUL_CAMPFIRE, false);
        }
        for (int z = -10; z <= 7; z++) base.clone().add(0, y0 + 1, z).getBlock().setType(Material.GREEN_CARPET, false);
    }

    private void buildPortalTreasury(Location base, int portalLevel) {
        int y0 = portalLevel * FLOOR_HEIGHT;
        // Sala segura, clara y sin escondites para mobs.
        for (int x = -13; x <= 13; x++) for (int z = -13; z <= 13; z++) {
            for (int y = 1; y <= 6; y++) base.clone().add(x, y0 + y, z).getBlock().setType(Material.AIR, false);
            if (Math.max(Math.abs(x), Math.abs(z)) >= 10) {
                base.clone().add(x, y0, z).getBlock().setType(Material.CHISELED_STONE_BRICKS, false);
            }
        }
        for (int[] p : new int[][]{{-10,-10},{10,-10},{-10,10},{10,10}}) {
            for (int y = 1; y <= 6; y++) base.clone().add(p[0], y0 + y, p[1]).getBlock().setType(Material.EMERALD_BLOCK, false);
            base.clone().add(p[0], y0 + 6, p[1]).getBlock().setType(Material.SEA_LANTERN, false);
        }
        // Pedestal donde se crea el portal al morir el jefe.
        for (int x = -5; x <= 5; x++) for (int z = -2; z <= 8; z++) {
            base.clone().add(x, y0 + 1, z).getBlock().setType(
                    Math.max(Math.abs(x), Math.abs(z - 3)) == 5 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS,
                    false);
        }
        for (int x = -4; x <= 4; x++) for (int z = -1; z <= 7; z++) {
            base.clone().add(x, y0 + 2, z).getBlock().setType(Material.AIR, false);
        }
        base.clone().add(0, y0 + 1, 3).getBlock().setType(Material.EMERALD_BLOCK, false);
    }

    private void buildFloorConnections(Location base, int totalLevels) {
        for (int level = 0; level < totalLevels - 1; level++) {
            int y0 = level * FLOOR_HEIGHT;
            boolean east = Math.floorMod(level, 2) == 0;
            int xStart = east ? 9 : -10;
            int zStart = east ? -11 : 11;
            int zDirection = east ? 1 : -1;
            BlockFace facing = east ? BlockFace.SOUTH : BlockFace.NORTH;
            int stairCount = FLOOR_HEIGHT - 1;
            int landingZ = zStart + zDirection * stairCount;

            // Hueco rectangular continuo. Esto evita techos bajos, bloques en medio del
            // recorrido y los "montones" de escalones que se veían en 1.16.0.
            for (int x = xStart - 1; x <= xStart + 2; x++) {
                for (int z = Math.min(zStart, landingZ) - 1; z <= Math.max(zStart, landingZ) + 2; z++) {
                    for (int y = y0 + 1; y <= y0 + FLOOR_HEIGHT + 4; y++) {
                        base.getWorld().getBlockAt(base.getBlockX() + x, base.getBlockY() + y,
                                base.getBlockZ() + z).setType(Material.AIR, false);
                    }
                }
            }

            for (int step = 0; step < stairCount; step++) {
                int z = zStart + zDirection * step;
                int y = y0 + 1 + step;
                for (int dx = 0; dx < 2; dx++) {
                    Location stair = base.clone().add(xStart + dx, y, z);
                    setStair(stair, facing, Material.STONE_BRICK_STAIRS);
                    stair.clone().add(0, -1, 0).getBlock().setType(Material.STONE_BRICKS, false);
                    clear(stair, 1, 4);
                }
                if (step % 2 == 0 && step < stairCount - 1) {
                    base.clone().add(xStart - 1, y, z).getBlock().setType(Material.STONE_BRICK_WALL, false);
                    base.clone().add(xStart + 2, y, z).getBlock().setType(Material.STONE_BRICK_WALL, false);
                }
            }

            for (int dx = -1; dx <= 2; dx++) {
                for (int dz = 0; dz <= 2; dz++) {
                    Location landing = base.clone().add(xStart + dx, y0 + FLOOR_HEIGHT,
                            landingZ + zDirection * dz);
                    landing.getBlock().setType(Material.STONE_BRICKS, false);
                    clear(landing, 1, 4);
                }
            }
            lamp(base.clone().add(east ? xStart - 2 : xStart + 3,
                    y0 + FLOOR_HEIGHT + 1, landingZ));
        }
    }

    private void buildCornerTowers(Location base, int totalLevels) {
        int height = totalLevels * FLOOR_HEIGHT + 8;
        for (int sx : new int[]{-18, 18}) for (int sz : new int[]{-18, 18}) {
            Location center = base.clone().add(sx, 0, sz);
            for (int x : new int[]{-3, 0, 3}) for (int z : new int[]{-3, 0, 3}) {
                supportToGround(center.clone().add(x, -1, z), Material.STONE_BRICKS, 96);
            }
            for (int y = 0; y <= height; y++) for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                boolean shell = Math.abs(x) == 4 || Math.abs(z) == 4;
                boolean floor = Math.floorMod(y, FLOOR_HEIGHT) == 0;
                Location at = center.clone().add(x, y, z);
                if (floor) at.getBlock().setType(Material.STONE_BRICKS, false);
                else if (shell) {
                    boolean window = Math.floorMod(y, FLOOR_HEIGHT) >= 3
                            && Math.floorMod(y, FLOOR_HEIGHT) <= 5
                            && ((x == 0 && Math.abs(z) == 4) || (z == 0 && Math.abs(x) == 4));
                    at.getBlock().setType(window ? Material.IRON_BARS
                            : (Math.floorMod(y, 8) == 4 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS), false);
                } else at.getBlock().setType(Material.AIR, false);
            }
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                if ((Math.abs(x) == 4 || Math.abs(z) == 4) && Math.floorMod(x + z, 2) == 0) {
                    center.clone().add(x, height + 1, z).getBlock().setType(Material.STONE_BRICK_WALL, false);
                }
            }
            // Pasarelas hacia el casco en cada piso.
            int dx = sx > 0 ? -1 : 1;
            int dz = sz > 0 ? -1 : 1;
            for (int level = 0; level < totalLevels; level++) {
                int y = level * FLOOR_HEIGHT + 1;
                for (int step = 4; step <= 7; step++) {
                    Location bridge = center.clone().add(dx * step, y - 1, dz * step);
                    bridge.getBlock().setType(Material.STONE_BRICKS, false);
                    clear(bridge, 1, 4);
                }
            }
        }
    }

    private void buildRoof(Location base, int totalLevels) {
        int roofY = totalLevels * FLOOR_HEIGHT;
        for (int x = -KEEP_RADIUS; x <= KEEP_RADIUS; x++) for (int z = -KEEP_RADIUS; z <= KEEP_RADIUS; z++) {
            base.clone().add(x, roofY, z).getBlock().setType(
                    Math.floorMod(x + z, 7) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS, false);
            if ((Math.abs(x) == KEEP_RADIUS || Math.abs(z) == KEEP_RADIUS) && Math.floorMod(x + z, 2) == 0) {
                base.clone().add(x, roofY + 1, z).getBlock().setType(Material.STONE_BRICK_WALL, false);
            }
        }
        // Techo central escalonado para romper la silueta de caja.
        for (int layer = 0; layer < 5; layer++) {
            int radius = 9 - layer * 2;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if (Math.abs(x) == radius || Math.abs(z) == radius) {
                    base.clone().add(x, roofY + 1 + layer, z).getBlock().setType(Material.DARK_OAK_PLANKS, false);
                }
            }
        }
        base.clone().add(0, roofY + 6, 0).getBlock().setType(Material.LIGHTNING_ROD, false);
    }

    private void buildCorruptionAndDamage(Location base, int bossLevel) {
        // Parches deliberados de daño y vegetación; no cortan la ruta principal.
        int[][] rubble = {
                {-12, 1, -13}, {-9, 1, -5}, {12, 1, 4}, {-5, 9, 11}, {6, 17, -8}, {-11, 25, 2}
        };
        for (int[] p : rubble) {
            if (p[1] >= bossLevel * FLOOR_HEIGHT) continue;
            Location at = base.clone().add(p[0], p[1], p[2]);
            at.getBlock().setType(Material.CRACKED_STONE_BRICKS, false);
            at.clone().add(1, 0, 0).getBlock().setType(Material.MOSS_BLOCK, false);
            at.clone().add(0, 1, 1).getBlock().setType(Material.MOSS_CARPET, false);
        }
        for (int y = 2; y < bossLevel * FLOOR_HEIGHT; y += 7) {
            base.clone().add(-14, y, 4).getBlock().setType(Material.MOSSY_STONE_BRICKS, false);
            base.clone().add(14, y, -4).getBlock().setType(Material.MOSSY_STONE_BRICKS, false);
        }
    }

    private void registerEncounters(Location base, int bossLevel) {
        List<String> entrance = mobGroup("entrance", List.of(
                "emerald_zombie_minion", "emerald_skeleton_archer_minion"));
        List<String> interior = mobGroup("interior", List.of(
                "emerald_zombie_minion", "mossbound_spider_minion"));
        List<String> ambush = mobGroup("ambush", List.of("emerald_creeper_minion"));
        List<String> elite = mobGroup("elite", List.of(
                "emerald_ravager_cub_minion", "emerald_golem_sentinel_minion"));
        List<String> bossSupport = mobGroup("boss-support", List.of(
                "emerald_skeleton_archer_minion", "emerald_golem_sentinel_minion"));

        // Patio y murallas.
        registerSpawner(base.clone().add(-17, 1, -32), "surface_entrance", entrance, Material.MOSSY_STONE_BRICKS, true);
        registerSpawner(base.clone().add(17, 1, -30), "surface_entrance", entrance, Material.MOSSY_STONE_BRICKS, true);
        registerSpawner(base.clone().add(0, 1, -22), "surface_entrance", entrance, Material.CHISELED_STONE_BRICKS, true);
        registerSpawner(base.clone().add(-15, 9, -37), "surface_entrance", entrance, Material.STONE_BRICKS, true);
        registerSpawner(base.clone().add(15, 9, -37), "surface_entrance", entrance, Material.STONE_BRICKS, true);

        // Planta baja y sótano.
        registerSpawner(base.clone().add(-10, 1, -4), "surface_interior", interior, Material.DARK_OAK_PLANKS, true);
        registerSpawner(base.clone().add(10, 1, 4), "surface_interior", interior, Material.DEEPSLATE_BRICKS, true);
        registerSpawner(base.clone().add(-7, -6, -5), "surface_interior", interior, Material.DEEPSLATE_BRICKS, true);
        registerSpawner(base.clone().add(8, -6, 6), "surface_ambush", ambush, Material.CRACKED_DEEPSLATE_BRICKS, true);

        // Pisos temáticos.
        if (bossLevel > 1) {
            registerSpawner(base.clone().add(-8, FLOOR_HEIGHT + 1, -8), "surface_interior", interior, Material.BOOKSHELF, true);
            registerSpawner(base.clone().add(8, FLOOR_HEIGHT + 1, 7), "surface_ambush", ambush, Material.IRON_BARS, true);
        }
        if (bossLevel > 2) {
            registerSpawner(base.clone().add(-9, FLOOR_HEIGHT * 2 + 1, -8), "surface_interior", interior, Material.DARK_OAK_PLANKS, true);
            registerSpawner(base.clone().add(9, FLOOR_HEIGHT * 2 + 1, 8), "surface_elite", elite, Material.CHISELED_STONE_BRICKS, true);
        }
        for (int level = 3; level < bossLevel; level++) {
            registerSpawner(base.clone().add(-8, level * FLOOR_HEIGHT + 1, 0), "surface_elite", elite,
                    Material.CHISELED_STONE_BRICKS, true);
            registerSpawner(base.clone().add(8, level * FLOOR_HEIGHT + 1, 0), "surface_elite", elite,
                    Material.CHISELED_STONE_BRICKS, true);
        }

        int bossY = bossLevel * FLOOR_HEIGHT + 1;
        registerSpawner(base.clone().add(-10, bossY, -7), "surface_boss_support", bossSupport,
                Material.EMERALD_BLOCK, false);
        registerSpawner(base.clone().add(10, bossY, 7), "surface_boss_support", bossSupport,
                Material.EMERALD_BLOCK, false);
    }

    private void registerSpawner(Location at, String group, List<String> entities,
                                 Material pedestal, boolean active) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            at.clone().add(x, -1, z).getBlock().setType(pedestal, false);
        }
        for (int y = 0; y <= 3; y++) clear(at, y, y);
        spawners.register(at, group, entities, active);
        at.clone().add(-1, 0, 0).getBlock().setType(Material.IRON_BARS, false);
        at.clone().add(1, 0, 0).getBlock().setType(Material.IRON_BARS, false);
        at.clone().add(0, 0, -1).getBlock().setType(Material.IRON_BARS, false);
        at.clone().add(0, 0, 1).getBlock().setType(Material.IRON_BARS, false);
    }

    private List<String> mobGroup(String key, List<String> fallback) {
        List<String> configured = plugin.getConfig().getStringList("overworld-castle.mob-groups." + key);
        List<String> clean = configured.stream().map(String::trim).filter(value -> !value.isBlank()).toList();
        return clean.isEmpty() ? fallback : clean;
    }

    private void placeExplorationLoot(Location base, int bossLevel, int portalLevel) {
        placeChest(base.clone().add(-19, 1, -31), AdaptiveDungeonLootManager.ChestTier.COMMON);
        placeChest(base.clone().add(19, 1, -29), AdaptiveDungeonLootManager.ChestTier.COMMON);
        placeChest(base.clone().add(-12, -6, 8), AdaptiveDungeonLootManager.ChestTier.RARE);
        placeChest(base.clone().add(-11, 1, 11), AdaptiveDungeonLootManager.ChestTier.RARE);
        placeChest(base.clone().add(-5, FLOOR_HEIGHT + 1, 10), AdaptiveDungeonLootManager.ChestTier.RARE);
        placeChest(base.clone().add(10, FLOOR_HEIGHT + 1, 9), AdaptiveDungeonLootManager.ChestTier.RARE);
        if (bossLevel > 2) {
            placeChest(base.clone().add(-10, FLOOR_HEIGHT * 2 + 1, -9), AdaptiveDungeonLootManager.ChestTier.EPIC);
            placeChest(base.clone().add(10, FLOOR_HEIGHT * 2 + 1, 9), AdaptiveDungeonLootManager.ChestTier.EPIC);
        }
        for (int[] p : new int[][]{{-6,-10},{6,-10},{-6,10},{6,10}}) {
            placeChest(base.clone().add(p[0], portalLevel * FLOOR_HEIGHT + 1, p[1]),
                    AdaptiveDungeonLootManager.ChestTier.LEGENDARY);
        }
    }

    private void placeChest(Location at, AdaptiveDungeonLootManager.ChestTier tier) {
        at.clone().add(0, -1, 0).getBlock().setType(Material.STONE_BRICKS, false);
        clear(at, 0, 2);
        adaptiveLoot.placeCustomChest(at, AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD, tier);
    }

    private void buildRouteMarkers(Location base, Location safeArrival, int bossLevel) {
        if (!plugin.getConfig().getBoolean("overworld-castle.safe-path-markers", true)) return;
        int startZ = safeArrival.getBlockZ() - base.getBlockZ();
        for (int z = startZ + 6; z <= KEEP_GATE_Z - 1; z += 4) {
            base.clone().add(0, 1, z).getBlock().setType(Material.YELLOW_CARPET, false);
        }
        for (int level = 0; level < bossLevel; level++) {
            boolean east = Math.floorMod(level, 2) == 0;
            int x = east ? 8 : -8;
            int z = east ? -3 : 3;
            base.clone().add(x, level * FLOOR_HEIGHT + 1, z).getBlock().setType(Material.LIME_CARPET, false);
        }
    }

    private void buildHollowRoom(Location center, int radiusX, int radiusZ, int height,
                                 Material wall, Material accent, Material floor) {
        for (int y = 0; y <= height; y++) for (int x = -radiusX; x <= radiusX; x++) for (int z = -radiusZ; z <= radiusZ; z++) {
            boolean shell = Math.abs(x) == radiusX || Math.abs(z) == radiusZ;
            Location at = center.clone().add(x, y, z);
            if (y == 0 || y == FLOOR_HEIGHT) at.getBlock().setType(floor, false);
            else if (shell) {
                boolean window = y >= 4 && y <= 6 && ((x == 0 && Math.abs(z) == radiusZ)
                        || (z == 0 && Math.abs(x) == radiusX));
                at.getBlock().setType(window ? Material.IRON_BARS : (y == 3 || y == 9 ? accent : wall), false);
            } else at.getBlock().setType(Material.AIR, false);
        }
    }

    private void buildPartition(Location base, int y0, int coordinate, boolean alongX,
                                int from, int to, List<Integer> doorCenters) {
        for (int variable = from; variable <= to; variable++) {
            int current = variable;
            boolean door = doorCenters.stream().anyMatch(center -> Math.abs(current - center) <= 1);
            for (int y = 1; y <= 5; y++) {
                int x = alongX ? coordinate : variable;
                int z = alongX ? variable : coordinate;
                base.clone().add(x, y0 + y, z).getBlock().setType(
                        door && y <= 3 ? Material.AIR
                                : (y == 4 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS), false);
            }
        }
    }

    private void buildBarricade(Location center, boolean alongZ) {
        for (int i = -3; i <= 3; i++) {
            int x = alongZ ? 0 : i;
            int z = alongZ ? i : 0;
            center.clone().add(x, 0, z).getBlock().setType(
                    i == 0 ? Material.DEEPSLATE_BRICKS : Material.DARK_OAK_PLANKS, false);
            if (Math.floorMod(i, 2) == 0) center.clone().add(x, 1, z).getBlock().setType(Material.IRON_BARS, false);
        }
    }

    private void chandelier(Location top) {
        top.getBlock().setType(Material.CHAIN, false);
        top.clone().add(0, -1, 0).getBlock().setType(Material.CHAIN, false);
        top.clone().add(0, -2, 0).getBlock().setType(Material.LANTERN, false);
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Location arm = top.clone().add(face.getModX() * 2, -1, face.getModZ() * 2);
            arm.getBlock().setType(Material.CHAIN, false);
            arm.clone().add(0, -1, 0).getBlock().setType(Material.LANTERN, false);
        }
    }

    private void carveDoor(Location center, Axis axis, int halfWidth, int height) {
        for (int offset = -halfWidth; offset <= halfWidth; offset++) {
            for (int y = 1; y <= height; y++) {
                Location at = axis == Axis.X ? center.clone().add(0, y, offset) : center.clone().add(offset, y, 0);
                at.getBlock().setType(Material.AIR, false);
            }
        }
    }

    private void buildWallColumn(Location at, int height) {
        for (int y = 0; y < height; y++) {
            at.clone().add(0, y, 0).getBlock().setType(
                    y == 3 || y == 6 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS, false);
        }
        at.clone().add(0, height, 0).getBlock().setType(Material.STONE_BRICK_WALL, false);
    }

    private Material roadMaterial(int x, int z) {
        int value = Math.floorMod(x * 17 + z * 11, 19);
        if (value == 0) return Material.CRACKED_STONE_BRICKS;
        if (value <= 3) return Material.MOSSY_COBBLESTONE;
        return Material.COBBLESTONE;
    }

    private Material courtyardMaterial(int x, int z) {
        int value = Math.floorMod(x * 13 + z * 7, 23);
        if (value == 0) return Material.CRACKED_STONE_BRICKS;
        if (value <= 3) return Material.MOSSY_STONE_BRICKS;
        return Material.COBBLESTONE;
    }

    private Material keepFloorMaterial(int x, int z, int level) {
        int value = Math.floorMod(x * 11 + z * 5 + level * 3, 29);
        if (value == 0) return Material.CRACKED_STONE_BRICKS;
        if (value <= 3) return Material.MOSSY_STONE_BRICKS;
        return level == 0 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS;
    }

    private void supportToGround(Location start, Material material, int maxDepth) {
        Location cursor = start.clone();
        for (int depth = 0; depth < maxDepth && cursor.getBlockY() > cursor.getWorld().getMinHeight(); depth++) {
            Block block = cursor.getBlock();
            if (block.getType().isSolid()) break;
            block.setType(material, false);
            cursor.subtract(0, 1, 0);
        }
    }

    private void lamp(Location at) {
        at.getBlock().setType(Material.STONE_BRICK_WALL, false);
        at.clone().add(0, 1, 0).getBlock().setType(Material.LANTERN, false);
    }

    private void clear(Location at, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) at.clone().add(0, y, 0).getBlock().setType(Material.AIR, false);
    }

    private void setStair(Location at, BlockFace facing, Material material) {
        at.getBlock().setType(material, false);
        if (at.getBlock().getBlockData() instanceof Stairs stairs) {
            stairs.setFacing(facing);
            stairs.setHalf(Bisected.Half.BOTTOM);
            stairs.setShape(Stairs.Shape.STRAIGHT);
            stairs.setWaterlogged(false);
            at.getBlock().setBlockData(stairs, false);
        }
    }

    private Location centered(Location at) {
        return at.clone().add(0.5D, 0.0D, 0.5D);
    }

    private enum Axis { X, Z }
}
