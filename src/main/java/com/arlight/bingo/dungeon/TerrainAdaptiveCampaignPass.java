package com.arlight.bingo.dungeon;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Random;

/**
 * Pasada visual 1.28.0.
 *
 * Esta clase ya no eleva ni rellena la ciudad completa. La adaptación de 1.27.0
 * podía convertir una diferencia natural de altura en muros macizos de decenas de
 * bloques. Ahora sólo modifica la superficie exterior, ancla cristales al terreno
 * real y construye una envolvente del Nether fuera de la huella jugable.
 */
public final class TerrainAdaptiveCampaignPass {
    private TerrainAdaptiveCampaignPass() { }

    public static void adaptOverworld(JavaPlugin plugin, Location base,
                                      AdaptiveDungeonLootManager loot) {
        if (!enabled(plugin, "terrain-adaptation.overworld.enabled")) return;
        World world = base.getWorld();
        if (world == null) return;
        Random random = new Random(world.getSeed() ^ 0x454D4552414C44L);
        int radius = Math.max(148, plugin.getConfig().getInt("terrain-adaptation.overworld.radius", 178));
        int transition = Math.max(12,
                plugin.getConfig().getInt("terrain-adaptation.overworld.transition-width", 26));

        // Frontera orgánica. Sólo se reemplaza el bloque superior de cada columna;
        // nunca se construye una columna hasta la altura del castillo.
        for (int angle = 0; angle < 360; angle += 2) {
            double radians = Math.toRadians(angle);
            int irregular = terrainWave(angle, angle * 3, 7);
            for (int width = 0; width < transition; width++) {
                int rr = radius + irregular - width;
                int x = base.getBlockX() + (int)Math.round(Math.cos(radians) * rr);
                int z = base.getBlockZ() + (int)Math.round(Math.sin(radians) * rr) - 22;
                int y = naturalSurface(world, x, z);
                Material top = width < 5 ? Material.COARSE_DIRT
                        : width < 11 ? Material.ROOTED_DIRT
                        : width < 18 ? Material.MOSS_BLOCK : Material.MOSSY_COBBLESTONE;
                world.getBlockAt(x, y, z).setType(top, false);
                if (width > 17 && angle % 16 == 0) {
                    crystalSpike(new Location(world, x, y + 1, z), Material.EMERALD_BLOCK,
                            4 + Math.floorMod(angle + width, 6), angle - 25);
                }
            }
        }

        // Cristales grandes alrededor de los barrios externos. Todos comienzan en
        // el terreno real y no en baseY.
        int[][] clusters = {
                {-132,-96},{132,-94},{-148,-18},{147,-12},
                {-128,78},{130,82},{-67,118},{70,120},{-44,-151},{46,-154}
        };
        for (int i = 0; i < clusters.length; i++) {
            int x = base.getBlockX() + clusters[i][0];
            int z = base.getBlockZ() + clusters[i][1];
            int y = naturalSurface(world, x, z) + 1;
            crystalCluster(new Location(world, x, y, z), Material.EMERALD_BLOCK,
                    7 + i % 6, random, true);
        }

        placeLootChest(surfaceLocation(base.clone().add(-126, 0, -116)), loot,
                AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.RARE);
        placeLootChest(surfaceLocation(base.clone().add(129, 0, 79)), loot,
                AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.EPIC);
    }

    public static void adaptNether(JavaPlugin plugin, Location base,
                                   AdaptiveDungeonLootManager loot) {
        if (!enabled(plugin, "terrain-adaptation.nether.enabled")) return;
        World world = base.getWorld();
        if (world == null) return;
        Random random = new Random(world.getSeed() ^ 0x474F4C444E455448L);
        int radiusX = Math.max(150, plugin.getConfig().getInt("terrain-adaptation.nether.radius-x", 174));
        int radiusZ = Math.max(136, plugin.getConfig().getInt("terrain-adaptation.nether.radius-z", 158));
        int floorY = base.getBlockY() - 5;
        int roofY = Math.min(world.getMaxHeight() - 8,
                base.getBlockY() + plugin.getConfig().getInt("terrain-adaptation.nether.cavern-height", 88));

        // Envolvente elíptica sólo en la banda exterior. El centro no se rellena.
        // Esto crea una caverna monumental sin levantar pilares dentro de las calles.
        for (int x = -radiusX; x <= radiusX; x++) {
            for (int z = -radiusZ; z <= radiusZ; z++) {
                double nx = x / (double)radiusX;
                double nz = z / (double)radiusZ;
                double edge = nx * nx + nz * nz;
                if (edge < .82D || edge > 1.03D) continue;
                int localFloor = floorY + terrainWave(x, z, 5);
                int localRoof = roofY - Math.max(0, (int)Math.round((edge - .82D) * 34.0D));
                Material wall = Math.floorMod(x * 7 + z * 11, 17) == 0
                        ? Material.BASALT : Material.NETHERRACK;
                for (int y = localFloor - 9; y <= localRoof; y++) {
                    world.getBlockAt(base.getBlockX() + x, y, base.getBlockZ() + z)
                            .setType(y % 13 == 0 ? Material.BLACKSTONE : wall, false);
                }
            }
        }

        // Suelo rocoso de seguridad en las zonas exteriores, sin tocar el núcleo.
        for (int x = -radiusX + 12; x <= radiusX - 12; x++) {
            for (int z = -radiusZ + 12; z <= radiusZ - 12; z++) {
                double edge = Math.pow(x / (double)radiusX, 2) + Math.pow(z / (double)radiusZ, 2);
                if (edge < .47D || edge > .80D || Math.abs(x) < 58 && z > -76 && z < 72) continue;
                Location at = base.clone().add(x, -4, z);
                at.getBlock().setType(Material.LAVA, false);
                if (Math.floorMod(x * 13 + z * 7, 41) == 0) {
                    at.clone().add(0, 1, 0).getBlock().setType(Material.MAGMA_BLOCK, false);
                }
            }
        }

        int[][] clusters = {
                {-142,-101},{141,-98},{-151,28},{150,34},
                {-112,101},{115,98},{-74,-132},{78,-131}
        };
        for (int i = 0; i < clusters.length; i++) {
            Location at = base.clone().add(clusters[i][0], 1 + i % 4, clusters[i][1]);
            goldCluster(at, 8 + i % 6, random);
        }
        placeLootChest(base.clone().add(-137, 22, -42), loot,
                AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.EPIC);
        placeLootChest(base.clone().add(138, 29, 31), loot,
                AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.LEGENDARY);
    }

    public static void adaptEnd(JavaPlugin plugin, Location city, Location dragonIsland,
                                AdaptiveDungeonLootManager loot) {
        if (!enabled(plugin, "terrain-adaptation.end.enabled")) return;
        World world = city.getWorld();
        if (world == null) return;
        Random random = new Random(world.getSeed() ^ 0x414D455448595354L);

        sculptEndUnderside(city, 94, 84, 0x31);
        sculptEndUnderside(dragonIsland, 92, 82, 0x59);
        int[][] satellites = {
                {-145,-58},{144,-54},{-139,73},{142,77},
                {-92,132},{96,130},{-40,-137},{43,-140}
        };
        for (int i = 0; i < satellites.length; i++) {
            Location satellite = city.clone().add(satellites[i][0], -4 - i % 4, satellites[i][1]);
            buildEndSatellite(satellite, 17 + i % 6, random);
            buildEndBridge(endCityEdge(city, satellite), satellite.clone().add(0, 2, 0), 2);
        }
        int[][] crystals = {
                {-78,-67},{80,-64},{-88,31},{87,38},
                {-48,73},{51,76},{-20,-92},{22,-94}
        };
        for (int i = 0; i < crystals.length; i++) {
            Location at = city.clone().add(crystals[i][0], 2, crystals[i][1]);
            crystalCluster(at, Material.AMETHYST_BLOCK, 9 + i % 7, random, false);
        }
        placeLootChest(city.clone().add(-74, 11, 58), loot,
                AdaptiveDungeonLootManager.DimensionGroup.END,
                AdaptiveDungeonLootManager.ChestTier.EPIC);
        placeLootChest(city.clone().add(76, 15, 58), loot,
                AdaptiveDungeonLootManager.DimensionGroup.END,
                AdaptiveDungeonLootManager.ChestTier.LEGENDARY);
    }

    private static boolean enabled(JavaPlugin plugin, String path) {
        return plugin.getConfig().getBoolean(path, true);
    }

    private static int naturalSurface(World world, int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private static Location surfaceLocation(Location desired) {
        if (desired == null || desired.getWorld() == null) return desired;
        int y = naturalSurface(desired.getWorld(), desired.getBlockX(), desired.getBlockZ()) + 1;
        return new Location(desired.getWorld(), desired.getBlockX(), y, desired.getBlockZ());
    }

    private static int terrainWave(int x, int z, int amplitude) {
        return (int)Math.round(Math.sin(x * .075D) * amplitude * .45D
                + Math.cos(z * .061D) * amplitude * .55D);
    }

    private static void crystalCluster(Location base, Material crystal, int height,
                                       Random random, boolean mossBase) {
        crystalSpike(base, crystal, height, random.nextInt(360));
        crystalSpike(base.clone().add(3, 0, 1), crystal, Math.max(4, height - 2), 35);
        crystalSpike(base.clone().add(-3, 0, 2), crystal, Math.max(3, height - 4), -30);
        crystalSpike(base.clone().add(1, 0, -3), crystal, Math.max(3, height - 5), 12);
        base.clone().add(0, 0, -2).getBlock().setType(mossBase ? Material.MOSS_BLOCK : Material.CALCITE, false);
        base.clone().add(1, 0, -2).getBlock().setType(Material.CALCITE, false);
    }

    private static void crystalSpike(Location base, Material crystal, int height, int angle) {
        double radians = Math.toRadians(angle);
        for (int y = 0; y < height; y++) {
            int x = (int)Math.round(Math.cos(radians) * y * .30D);
            int z = (int)Math.round(Math.sin(radians) * y * .30D);
            int radius = y < height * .52D ? 1 : 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    base.clone().add(x + dx, y, z + dz).getBlock().setType(crystal, false);
                }
            }
        }
    }

    private static void goldCluster(Location base, int height, Random random) {
        crystalSpike(base, Material.GOLD_BLOCK, height, 70 + random.nextInt(70));
        crystalSpike(base.clone().add(-3, 0, 1), Material.RAW_GOLD_BLOCK,
                Math.max(4, height - 2), 115);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (x * x + z * z > 13) continue;
                base.clone().add(x, -1, z).getBlock().setType(
                        Math.floorMod(x + z, 3) == 0 ? Material.MAGMA_BLOCK : Material.GILDED_BLACKSTONE,
                        false);
            }
        }
    }

    private static void sculptEndUnderside(Location center, int radiusX, int radiusZ, int seed) {
        for (int x = -radiusX; x <= radiusX; x += 2) {
            for (int z = -radiusZ; z <= radiusZ; z += 2) {
                double radial = x * x / (double)(radiusX * radiusX)
                        + z * z / (double)(radiusZ * radiusZ);
                if (radial > .98D || radial < .20D) continue;
                int length = 5 + (int)((1.0D - radial) * 22.0D)
                        + Math.floorMod(x * 7 + z * 11 + seed, 8);
                Location top = center.clone().add(x, -4, z);
                for (int y = 0; y < length; y++) {
                    Material material = y > length * .68D ? Material.OBSIDIAN
                            : Math.floorMod(x + z + y, 9) == 0
                            ? Material.AMETHYST_BLOCK : Material.END_STONE;
                    top.clone().add(0, -y, 0).getBlock().setType(material, false);
                }
            }
        }
    }

    private static Location endCityEdge(Location city, Location target) {
        double dx = target.getX() - city.getX();
        double dz = target.getZ() - city.getZ();
        double denominator = Math.sqrt((dx * dx) / (106.0D * 106.0D)
                + (dz * dz) / (84.0D * 84.0D));
        double scale = denominator <= 0.0001D ? 0.0D : 0.92D / denominator;
        return city.clone().add(dx * scale, 2.0D, dz * scale);
    }

    private static void buildEndBridge(Location from, Location to, int halfWidth) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null
                || from.getWorld() != to.getWorld()) return;
        int dx = to.getBlockX() - from.getBlockX();
        int dz = to.getBlockZ() - from.getBlockZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        boolean alongX = Math.abs(dx) >= Math.abs(dz);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double)Math.max(1, steps);
            int x = (int)Math.round(from.getBlockX() + dx * t);
            int z = (int)Math.round(from.getBlockZ() + dz * t);
            int y = (int)Math.round(from.getBlockY() + (to.getBlockY() - from.getBlockY()) * t);
            for (int width = -halfWidth; width <= halfWidth; width++) {
                int wx = alongX ? x : x + width;
                int wz = alongX ? z + width : z;
                Location at = new Location(from.getWorld(), wx, y, wz);
                at.getBlock().setType(Math.floorMod(i + width, 13) == 0
                        ? Material.AMETHYST_BLOCK : Material.PURPUR_BLOCK, false);
                if (Math.abs(width) == halfWidth && i % 2 == 0) {
                    at.clone().add(0, 1, 0).getBlock().setType(Material.END_STONE_BRICK_WALL, false);
                }
            }
        }
    }

    private static void buildEndSatellite(Location center, int radius, Random random) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int distance = x * x + z * z;
                if (distance > radius * radius) continue;
                int depth = 4 + Math.max(0,
                        (radius * radius - distance) / Math.max(8, radius * 2));
                for (int y = -depth; y <= 0; y++) {
                    center.clone().add(x, y, z).getBlock().setType(
                            y == 0 ? Material.END_STONE
                                    : y < -depth * .58D ? Material.OBSIDIAN : Material.END_STONE_BRICKS,
                            false);
                }
            }
        }
        crystalCluster(center.clone().add(0, 1, 0), Material.AMETHYST_BLOCK,
                10 + random.nextInt(7), random, false);
    }

    private static void placeLootChest(Location at, AdaptiveDungeonLootManager loot,
                                       AdaptiveDungeonLootManager.DimensionGroup dimension,
                                       AdaptiveDungeonLootManager.ChestTier tier) {
        if (at == null || at.getWorld() == null || loot == null) return;
        loot.placeCustomChest(at, dimension, tier);
    }
}
