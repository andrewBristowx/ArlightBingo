package com.arlight.bingo.listeners;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.arlight.bingo.listeners.OverworldCampaignModel145.*;

final class OverworldCampaignTerrain145 {
    private static final Set<Material> TERRAIN = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK, Material.MUD,
            Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.TUFF, Material.SAND, Material.RED_SAND,
            Material.GRAVEL, Material.CLAY, Material.TERRACOTTA, Material.SNOW_BLOCK);

    private OverworldCampaignTerrain145() { }

    static int medianTerrainY(World world, int cx, int cz, int radius) {
        List<Integer> samples = new ArrayList<>();
        int step = Math.max(5, radius / 5);
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                if (dx * dx + dz * dz <= radius * radius) samples.add(terrainY(world, cx + dx, cz + dz));
            }
        }
        if (samples.isEmpty()) return terrainY(world, cx, cz);
        samples.sort(Comparator.naturalOrder());
        return samples.get(samples.size() / 2);
    }

    static int terrainY(World world, int x, int z) {
        int top = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        int min = Math.max(world.getMinHeight() + 2, top - 72);
        for (int y = top; y >= min; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (TERRAIN.contains(type)) return y;
            if (type == Material.WATER || type == Material.LAVA) continue;
        }
        return world.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR);
    }

    /**
     * Retira una huella 1.43/1.44 sin dejar plataformas ni edificios flotantes.
     * En tierra devuelve una cobertura orgánica; en las zonas que nacieron sobre
     * océano restaura agua hasta el nivel del mar.
     */
    static List<BlockEdit> retireLegacySite(World world, Site site, Report report) {
        return retireLegacySite(world, site, report, -site.radius(), site.radius());
    }

    static List<BlockEdit> retireLegacySite(World world, Site site, Report report,
                                            int minimumDx, int maximumDx) {
        List<BlockEdit> out = new ArrayList<>();
        int radius = site.radius();
        int seaLevel = 62;
        boolean ocean = surroundingOcean(world, site.x(), site.z(), radius + 8);
        for (int dx = Math.max(-radius, minimumDx); dx <= Math.min(radius, maximumDx); dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = site.x() + dx, z = site.z() + dz;
                int ground = terrainY(world, x, z);
                int top = Math.min(world.getMaxHeight() - 2,
                        world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE) + 42);
                for (int y = ground + 1; y <= top; y++) {
                    Material current = world.getBlockAt(x, y, z).getType();
                    if (!current.isAir() && current != Material.WATER && current != Material.LAVA)
                        out.add(new BlockEdit(x, y, z, Material.AIR));
                }
                if (ocean && ground < seaLevel) {
                    out.add(new BlockEdit(x, ground, z, Material.GRAVEL));
                    for (int y = ground + 1; y <= seaLevel; y++)
                        out.add(new BlockEdit(x, y, z, Material.WATER));
                } else {
                    out.add(new BlockEdit(x, ground, z,
                            Math.floorMod(x * 17 + z * 11, 19) < 3 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK));
                    for (int y = ground - 1; y >= Math.max(world.getMinHeight() + 2, ground - 3); y--)
                        out.add(new BlockEdit(x, y, z, Material.DIRT));
                }
                report.shapedColumns++;
            }
        }
        return out;
    }

    private static boolean surroundingOcean(World world, int cx, int cz, int radius) {
        int wet = 0, total = 0;
        for (int degree = 0; degree < 360; degree += 20) {
            double angle = Math.toRadians(degree);
            int x = cx + (int) Math.round(Math.cos(angle) * radius);
            int z = cz + (int) Math.round(Math.sin(angle) * radius);
            int top = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
            Material material = world.getBlockAt(x, top, z).getType();
            if (material == Material.WATER || material == Material.ICE) wet++;
            total++;
        }
        return wet * 2 >= total;
    }

    static List<BlockEdit> shapeSite(World world, Site site, int flatRadius, int blendRadius, Report report) {
        return shapeSite(world, site, flatRadius, blendRadius, report, -blendRadius, blendRadius);
    }

    static List<BlockEdit> shapeSite(World world, Site site, int flatRadius, int blendRadius,
                                     Report report, int minimumDx, int maximumDx) {
        List<BlockEdit> out = new ArrayList<>();
        int clearLimit = Math.min(world.getMaxHeight() - 2, site.baseY() + 50);
        for (int dx = Math.max(-blendRadius, minimumDx); dx <= Math.min(blendRadius, maximumDx); dx++) {
            for (int dz = -blendRadius; dz <= blendRadius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > blendRadius) continue;
                int x = site.x() + dx;
                int z = site.z() + dz;
                int natural = terrainY(world, x, z);
                double blend = distance <= flatRadius ? 0.0D
                        : (distance - flatRadius) / Math.max(1.0D, blendRadius - flatRadius);
                int target = (int) Math.round(site.baseY() * (1.0D - blend) + natural * blend);
                int top = Math.min(clearLimit, world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE) + 2);
                for (int y = target + 1; y <= top; y++) out.add(new BlockEdit(x, y, z, Material.AIR));

                int minFill = Math.max(world.getMinHeight() + 2, target - 24);
                for (int y = target; y >= minFill; y--) {
                    Material existing = world.getBlockAt(x, y, z).getType();
                    if (y < target && existing.isSolid() && existing != Material.ICE) break;
                    Material material = y == target ? surface(site.style(), distance >= flatRadius - 2.0D)
                            : y >= target - 3 ? Material.DIRT : foundation(site.style(), x, y, z);
                    out.add(new BlockEdit(x, y, z, material));
                }
                report.shapedColumns++;
            }
        }
        return out;
    }

    private static Material surface(Style style, boolean edge) {
        if (edge) return switch (style) {
            case RESIDENTIAL -> Material.MOSS_BLOCK;
            case COMMERCIAL -> Material.MUD_BRICKS;
            case MILITARY, CITADEL -> Material.MOSSY_COBBLESTONE;
            case BOSS -> Material.DEEPSLATE_BRICKS;
            case PORTAL -> Material.COBBLESTONE;
        };
        return switch (style) {
            case RESIDENTIAL -> Material.GRASS_BLOCK;
            case COMMERCIAL -> Material.PACKED_MUD;
            case MILITARY -> Material.COARSE_DIRT;
            case CITADEL -> Material.STONE_BRICKS;
            case BOSS -> Material.DEEPSLATE_TILES;
            case PORTAL -> Material.POLISHED_ANDESITE;
        };
    }

    private static Material foundation(Style style, int x, int y, int z) {
        int pattern = Math.floorMod(x * 31 + y * 17 + z * 13, 13);
        if (style == Style.BOSS) return pattern < 3 ? Material.MOSSY_COBBLESTONE : Material.DEEPSLATE;
        if (style == Style.MILITARY || style == Style.CITADEL)
            return pattern < 2 ? Material.MOSSY_STONE_BRICKS : Material.STONE;
        return pattern < 3 ? Material.COBBLESTONE : Material.STONE;
    }

    static List<BlockEdit> buildRoadNetwork(World world, Layout layout, Site villageCenter,
                                             Site residential, Site commercial, Site military,
                                             Site citadel, Site boss, Site portal, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        Location villageExit = surface(world, villageCenter.x() + 88, villageCenter.z() + 5);
        road(out, world, villageExit, gateToward(world, residential, villageCenter), 5, Material.DIRT_PATH, 0.20D);
        road(out, world, gateToward(world, residential, citadel), surface(world, citadel.x() - 57, citadel.z() + 10),
                5, Material.COBBLESTONE, -0.16D);
        road(out, world, gateToward(world, commercial, citadel), surface(world, citadel.x(), citadel.z() - 57),
                5, Material.PACKED_MUD, 0.18D);
        road(out, world, gateToward(world, military, citadel), surface(world, citadel.x() + 57, citadel.z() + 10),
                5, Material.MOSSY_COBBLESTONE, -0.20D);
        road(out, world, surface(world, citadel.x(), citadel.z() + 78), surface(world, boss.x(), boss.z() - 53),
                7, Material.POLISHED_ANDESITE, 0.0D);
        road(out, world, surface(world, boss.x(), boss.z() + 53), surface(world, portal.x(), portal.z() - 28),
                7, Material.DEEPSLATE_TILES, 0.0D);
        report.roads = 6;
        return out;
    }

    private static Location gateToward(World world, Site from, Site toward) {
        double dx = toward.x() - from.x();
        double dz = toward.z() - from.z();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        int x = (int) Math.round(from.x() + dx / len * (from.radius() - 7));
        int z = (int) Math.round(from.z() + dz / len * (from.radius() - 7));
        return surface(world, x, z);
    }

    private static Location surface(World world, int x, int z) {
        return new Location(world, x,
                world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z);
    }

    private static void road(List<BlockEdit> out, World world, Location start, Location end,
                             int width, Material path, double curve) {
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double length = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        int steps = Math.max(1, (int) Math.ceil(length));
        double nx = -dz / length;
        double nz = dx / length;
        int walkY = start.getBlockY();
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            double bend = Math.sin(Math.PI * t) * curve * Math.min(80.0D, length * 0.18D);
            int cx = (int) Math.round(start.getX() + dx * t + nx * bend);
            int cz = (int) Math.round(start.getZ() + dz * t + nz * bend);
            int natural = world.getHighestBlockYAt(cx, cz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (natural > walkY + 1) walkY++;
            else if (natural < walkY - 1) walkY--;
            else walkY = natural;

            boolean bridge = wet(world, cx, cz) || natural < walkY - 2;
            for (int side = -width / 2; side <= width / 2; side++) {
                int x = (int) Math.round(cx + nx * side);
                int z = (int) Math.round(cz + nz * side);
                Material floor = bridge ? Material.SPRUCE_PLANKS
                        : Math.floorMod(step + side, 9) == 0 ? secondary(path) : path;
                out.add(new BlockEdit(x, walkY - 1, z, floor));
                for (int y = walkY; y <= walkY + 3; y++) out.add(new BlockEdit(x, y, z, Material.AIR));
                support(out, world, x, walkY - 2, z,
                        bridge ? Material.STRIPPED_SPRUCE_LOG : Material.COBBLESTONE);
            }
            if (step > 0 && step % 18 == 0) {
                int lx = (int) Math.round(cx + nx * (width / 2 + 2));
                int lz = (int) Math.round(cz + nz * (width / 2 + 2));
                lamp(out, lx, walkY, lz);
            }
        }
    }

    private static boolean wet(World world, int x, int z) {
        int top = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        Material type = world.getBlockAt(x, top, z).getType();
        return type == Material.WATER || type == Material.LAVA || type == Material.ICE;
    }

    private static Material secondary(Material primary) {
        if (primary == Material.DIRT_PATH) return Material.COARSE_DIRT;
        if (primary == Material.PACKED_MUD) return Material.MUD_BRICKS;
        if (primary == Material.DEEPSLATE_TILES) return Material.MOSSY_STONE_BRICKS;
        return Material.MOSSY_COBBLESTONE;
    }

    private static void support(List<BlockEdit> out, World world, int x, int startY, int z, Material material) {
        for (int depth = 0; depth < 18; depth++) {
            int y = startY - depth;
            if (y <= world.getMinHeight() + 1) break;
            Material existing = world.getBlockAt(x, y, z).getType();
            if (existing.isSolid() && existing != Material.ICE) break;
            out.add(new BlockEdit(x, y, z, depth < 5 ? material : Material.STONE));
        }
    }

    static void lamp(List<BlockEdit> out, int x, int y, int z) {
        out.add(new BlockEdit(x, y - 1, z, Material.COBBLESTONE));
        out.add(new BlockEdit(x, y, z, Material.COBBLESTONE_WALL));
        out.add(new BlockEdit(x, y + 1, z, Material.SPRUCE_FENCE));
        out.add(new BlockEdit(x, y + 2, z, Material.LANTERN));
    }
}
