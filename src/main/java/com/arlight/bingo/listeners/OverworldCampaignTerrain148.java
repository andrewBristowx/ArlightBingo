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

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;

/** Terrain shaping and supported roads for the clean 1.48.2 Overworld campaign. */
final class OverworldCampaignTerrain148 {
    private static final int ORGANIC_EDGE_MARGIN = 14;
    private static final Set<Material> TERRAIN = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK, Material.MUD,
            Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.TUFF, Material.SAND, Material.RED_SAND,
            Material.GRAVEL, Material.CLAY, Material.TERRACOTTA, Material.SNOW_BLOCK);

    private OverworldCampaignTerrain148() { }

    static int medianTerrainY(World world, int cx, int cz, int radius) {
        List<Integer> samples = new ArrayList<>();
        int step = Math.max(5, radius / 6);
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                if (dx * dx + dz * dz <= radius * radius) {
                    samples.add(terrainY(world, cx + dx, cz + dz));
                }
            }
        }
        if (samples.isEmpty()) return terrainY(world, cx, cz);
        samples.sort(Comparator.naturalOrder());
        return samples.get(samples.size() / 2);
    }

    static int terrainY(World world, int x, int z) {
        int top = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        int min = Math.max(world.getMinHeight() + 2, top - 96);
        for (int y = top; y >= min; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (TERRAIN.contains(type)) return y;
            if (type == Material.WATER || type == Material.LAVA || type == Material.ICE) continue;
        }
        return world.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR);
    }

    static List<BlockEdit> shapeVillage(World world, Site site, int flatRadius, int blendRadius,
                                        Report report, int minimumDx, int maximumDx) {
        List<BlockEdit> out = new ArrayList<>();
        int extent = blendRadius + ORGANIC_EDGE_MARGIN;
        for (int dx = Math.max(-extent, minimumDx); dx <= Math.min(extent, maximumDx); dx++) {
            for (int dz = -extent; dz <= extent; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                double localBoundary = organicBoundary(site, dx, dz, blendRadius);
                if (distance > localBoundary) continue;
                int x = site.x() + dx;
                int z = site.z() + dz;
                int natural = terrainY(world, x, z);
                double terraceLevel;
                if (dz <= -28) terraceLevel = 4.0D;
                else if (dz < -18) terraceLevel = 2.0D + 2.0D * smooth((-18.0D - dz) / 10.0D);
                else if (dz <= 14) terraceLevel = 2.0D;
                else if (dz < 24) terraceLevel = 2.0D * (1.0D - smooth((dz - 14.0D) / 10.0D));
                else terraceLevel = 0.0D;
                int terrace = (int) Math.round(terraceLevel);
                if (dx > 28) terrace = Math.max(0, terrace - 1);
                double localFlat = flatRadius + (localBoundary - blendRadius) * 0.32D;
                int designed = site.baseY() + terrace + noise(x, z,
                        distance > localFlat - 6.0D ? 1 : 0);
                double blend = distance <= localFlat ? 0.0D
                        : smooth((distance - localFlat) / Math.max(1.0D, localBoundary - localFlat));
                int target = (int) Math.round(designed * (1.0D - blend) + natural * blend);
                Material top = blendedSurface(world, x, natural, z,
                        surface(Style.VILLAGE), blend);
                clearAndFill(out, world, x, target, z, top);
                report.shapedColumns++;
            }
        }
        return out;
    }

    static List<BlockEdit> shapeSite(World world, Site site, int flatRadius, int blendRadius,
                                     Report report, int minimumDx, int maximumDx) {
        List<BlockEdit> out = new ArrayList<>();
        int extent = blendRadius + ORGANIC_EDGE_MARGIN;
        for (int dx = Math.max(-extent, minimumDx); dx <= Math.min(extent, maximumDx); dx++) {
            for (int dz = -extent; dz <= extent; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                double localBoundary = organicBoundary(site, dx, dz, blendRadius);
                if (distance > localBoundary) continue;
                int x = site.x() + dx;
                int z = site.z() + dz;
                int natural = terrainY(world, x, z);
                double localFlat = flatRadius + (localBoundary - blendRadius) * 0.30D;
                double blend = distance <= localFlat ? 0.0D
                        : smooth((distance - localFlat) / Math.max(1.0D, localBoundary - localFlat));
                int designed = site.baseY();
                if (site.style() == Style.RESIDENTIAL) designed += dz < -16 ? 1 : 0;
                if (site.style() == Style.MILITARY) designed += dz < -24 ? 2 : 0;
                if (site.style() == Style.CITADEL) designed += dz < -18 ? 1 : dz > 24 ? -1 : 0;
                if (site.style() == Style.BOSS) designed += dz < -20 ? 2 : dz > 24 ? -2 : 0;
                if (site.style() == Style.PORTAL) {
                    double axial = Math.abs(dx) * 0.55D + Math.max(0.0D, dz) * 0.95D;
                    designed += dz > 8 ? Math.max(-4, 3 - (int) Math.round(axial / 8.0D)) : 1;
                }
                int target = (int) Math.round(designed * (1.0D - blend) + natural * blend);
                Material top = blendedSurface(world, x, natural, z,
                        surface(site.style()), blend);
                clearAndFill(out, world, x, target, z, top);
                report.shapedColumns++;
            }
        }
        return out;
    }

    private static void clearAndFill(List<BlockEdit> out, World world, int x, int target, int z,
                                     Material surface) {
        int top = Math.min(world.getMaxHeight() - 2,
                world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE) + 12);
        for (int y = target + 1; y <= top; y++) out.add(new BlockEdit(x, y, z, Material.AIR));
        fillColumn(out, world, x, target, z, surface, false);
    }

    private static void fillColumn(List<BlockEdit> out, World world, int x, int target, int z,
                                   Material surface, boolean ocean) {
        int minFill = Math.max(world.getMinHeight() + 2, target - 96);
        boolean anchored = false;
        for (int y = target; y >= minFill; y--) {
            Material existing = world.getBlockAt(x, y, z).getType();
            if (y < target - 8 && existing.isSolid() && existing != Material.ICE) anchored = true;
            Material material;
            if (y == target) material = surface;
            else if (y >= target - 3) material = ocean ? Material.GRAVEL : Material.DIRT;
            else if (y >= target - 8) material = ocean ? Material.STONE : Material.COBBLESTONE;
            else material = Math.floorMod(x * 31 + y * 17 + z * 13, 11) < 2
                    ? Material.ANDESITE : Material.STONE;
            out.add(new BlockEdit(x, y, z, material));
            if (anchored && y < target - 14) break;
        }
    }

    static List<BlockEdit> integrateFortifications(World world, Site village, Site residential,
                                                   Site military, Site citadel, Site boss,
                                                   Site portal, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        ringSupport(out, world, village, 74, 4, Material.COBBLESTONE);
        ringSupport(out, world, residential, 48, 4, Material.COBBLESTONE);
        ringSupport(out, world, military, 48, 6, Material.MOSSY_STONE_BRICKS);
        ringSupport(out, world, citadel, 55, 8, Material.STONE_BRICKS);
        ringSupport(out, world, boss, 51, 9, Material.DEEPSLATE_BRICKS);
        report.structures += 5;
        return out;
    }

    private static void ringSupport(List<BlockEdit> out, World world, Site site, int radius,
                                    int height, Material material) {
        for (int degree = 0; degree < 360; degree += 2) {
            double a = Math.toRadians(degree);
            int x = site.x() + (int) Math.round(Math.cos(a) * radius);
            int z = site.z() + (int) Math.round(Math.sin(a) * radius);
            int target = site.baseY() + 1;
            int natural = terrainY(world, x, z);
            int bottom = Math.max(world.getMinHeight() + 2,
                    Math.min(target - 8, natural - 2));
            for (int y = target - 1; y >= bottom; y--) {
                Material existing = world.getBlockAt(x, y, z).getType();
                if (existing.isSolid() && existing != Material.WATER && y <= natural - 2) break;
                out.add(new BlockEdit(x, y, z, y >= target - height ? material : Material.COBBLESTONE));
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) != 1) continue;
                int sx = x + dx;
                int sz = z + dz;
                int neighborGround = terrainY(world, sx, sz);
                if (neighborGround < target - 5) {
                    for (int y = target - 2; y >= neighborGround; y--)
                        out.add(new BlockEdit(sx, y, sz, Material.COBBLESTONE));
                }
            }
        }
    }

    private static Material surface(Style style) {
        return switch (style) {
            case VILLAGE, RESIDENTIAL -> Material.GRASS_BLOCK;
            case COMMERCIAL -> Material.PACKED_MUD;
            case MILITARY -> Material.COARSE_DIRT;
            case CITADEL -> Material.STONE_BRICKS;
            case BOSS -> Material.DEEPSLATE_TILES;
            case PORTAL -> Material.POLISHED_ANDESITE;
        };
    }

    private static Material naturalSurface(World world, int x, int y, int z) {
        Material material = world.getBlockAt(x, y, z).getType();
        if (TERRAIN.contains(material) && material != Material.DEEPSLATE) return material;
        return y <= 64 ? Material.GRAVEL : Material.GRASS_BLOCK;
    }

    private static Material blendedSurface(World world, int x, int y, int z,
                                           Material designed, double blend) {
        if (blend <= 0.38D) return designed;
        Material natural = naturalSurface(world, x, y, z);
        int threshold = (int) Math.round(Math.min(1.0D,
                (blend - 0.38D) / 0.62D) * 100.0D);
        int sample = Math.floorMod(x * 31 + z * 17, 100);
        return sample < threshold ? natural : designed;
    }

    private static double organicBoundary(Site site, int dx, int dz, int nominalRadius) {
        double angle = Math.atan2(dz, dx);
        double salt = site.style().ordinal() * 0.71D + site.x() * 0.0007D
                + site.z() * 0.0009D;
        double waves = Math.sin(angle * 3.0D + salt) * 5.5D
                + Math.sin(angle * 5.0D - salt * 1.7D) * 3.5D
                + Math.sin(angle * 9.0D + salt * 0.6D) * 1.8D;
        int coarseNoise = noise(site.x() + dx / 4, site.z() + dz / 4, 2);
        return nominalRadius + waves + coarseNoise;
    }

    static List<BlockEdit> buildRoadNetwork(World world, Layout layout, Site villageCenter,
                                             Site residential, Site commercial, Site military,
                                             Site citadel, Site boss, Site portal, Report report,
                                             OverworldCampaignAudit148.Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        Location villageExit = point(world, villageCenter.x() + 72,
                villageCenter.baseY() + 3, villageCenter.z() + 4);
        road(out, world, "village-residential", villageExit,
                gateToward(world, residential, villageCenter), 5,
                Material.DIRT_PATH, 0.16D, registry);
        road(out, world, "residential-citadel", gateToward(world, residential, citadel),
                point(world, citadel.x() - 57, citadel.baseY() + 1, citadel.z() + 10), 5,
                Material.COBBLESTONE, -0.14D, registry);
        road(out, world, "commercial-citadel", gateToward(world, commercial, citadel),
                point(world, citadel.x(), citadel.baseY() + 1, citadel.z() - 57), 5,
                Material.PACKED_MUD, 0.15D, registry);
        road(out, world, "military-citadel", gateToward(world, military, citadel),
                point(world, citadel.x() + 57, citadel.baseY() + 1, citadel.z() + 10), 5,
                Material.MOSSY_COBBLESTONE, -0.17D, registry);
        road(out, world, "citadel-boss", point(world, citadel.x(),
                        citadel.baseY() + 1, citadel.z() + 60),
                point(world, boss.x(), boss.baseY() + 1, boss.z() - 68), 7,
                Material.POLISHED_ANDESITE, 0.0D, registry);

        // Two outside-rim segments keep the full route beyond the arena's radius.
        // A single diagonal chord would cross the approved floor and northern wall.
        Location northRim = point(world, boss.x() + 58,
                boss.baseY() + 1, boss.z() - 58);
        Location flank = point(world, boss.x() + 74, boss.baseY() + 1, boss.z() + 8);
        road(out, world, "boss-portal-north-rim", point(world, boss.x() + 3,
                        boss.baseY() + 1, boss.z() - 55), northRim, 6,
                Material.DEEPSLATE_TILES, -0.10D, registry);
        road(out, world, "boss-portal-east-rim", northRim, flank, 6,
                Material.DEEPSLATE_TILES, -0.10D, registry);
        road(out, world, "boss-portal-sanctuary", flank,
                point(world, portal.x(), portal.baseY() + 1, portal.z() - 28), 6,
                Material.DEEPSLATE_TILES, -0.12D, registry);
        report.roads = 8;
        return out;
    }

    private static Location gateToward(World world, Site from, Site toward) {
        double dx = toward.x() - from.x();
        double dz = toward.z() - from.z();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        int x = (int) Math.round(from.x() + dx / len * (from.radius() - 7));
        int z = (int) Math.round(from.z() + dz / len * (from.radius() - 7));
        return point(world, x, from.baseY() + 1, z);
    }

    private static Location point(World world, int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    private static void road(List<BlockEdit> out, World world, String id,
                             Location start, Location end, int width, Material path,
                             double curve, OverworldCampaignAudit148.Registry registry) {
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double length = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        int steps = Math.max(1, (int) Math.ceil(length));
        double nx = -dz / length;
        double nz = dx / length;
        int walkY = start.getBlockY();
        int endY = end.getBlockY();
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            double bend = Math.sin(Math.PI * t) * curve * Math.min(75.0D, length * 0.17D);
            int cx = (int) Math.round(start.getX() + dx * t + nx * bend);
            int cz = (int) Math.round(start.getZ() + dz * t + nz * bend);
            int natural = terrainY(world, cx, cz) + 1;
            int expected = (int) Math.round(start.getY() + (end.getY() - start.getY()) * t);
            int desired = Math.max(expected - 2, Math.min(expected + 2, natural));
            if (step == 0) {
                walkY = start.getBlockY();
            } else {
                // Reserve enough remaining blocks to reach the declared waypoint by one
                // vertical block per step. Consecutive segments therefore share one exact
                // height instead of overwriting the same endpoint at different levels.
                int remaining = steps - step;
                desired = Math.max(endY - remaining, Math.min(endY + remaining, desired));
                if (desired > walkY) walkY++;
                else if (desired < walkY) walkY--;
            }

            boolean bridge = wet(world, cx, cz) || natural < walkY - 2;
            for (int side = -width / 2; side <= width / 2; side++) {
                int x = (int) Math.round(cx + nx * side);
                int z = (int) Math.round(cz + nz * side);
                Material floor = bridge ? Material.SPRUCE_PLANKS
                        : Math.floorMod(step + side, 9) == 0 ? secondary(path) : path;
                out.add(new BlockEdit(x, walkY - 1, z, floor));
                for (int y = walkY; y <= walkY + 3; y++) out.add(new BlockEdit(x, y, z, Material.AIR));
                registry.registerRoadCell(id, x, walkY, z, floor);
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

    private static void support(List<BlockEdit> out, World world, int x, int startY, int z,
                                Material material) {
        for (int depth = 0; depth < 24; depth++) {
            int y = startY - depth;
            if (y <= world.getMinHeight() + 1) break;
            Material existing = world.getBlockAt(x, y, z).getType();
            if (existing.isSolid() && existing != Material.ICE) break;
            out.add(new BlockEdit(x, y, z, depth < 6 ? material : Material.STONE));
        }
    }

    static void lamp(List<BlockEdit> out, int x, int y, int z) {
        for (int depth = 1; depth <= 6; depth++) {
            out.add(new BlockEdit(x, y - depth, z,
                    depth <= 2 ? Material.COBBLESTONE : Material.STONE));
        }
        out.add(new BlockEdit(x, y, z, Material.COBBLESTONE_WALL));
        out.add(new BlockEdit(x, y + 1, z, Material.SPRUCE_FENCE));
        out.add(new BlockEdit(x, y + 2, z, Material.LANTERN));
    }

    private static int noise(int x, int z, int amplitude) {
        if (amplitude <= 0) return 0;
        int hash = Math.floorMod(x * 73428767 ^ z * 912931, amplitude * 2 + 1);
        return hash - amplitude;
    }

    private static double smooth(double value) {
        double t = Math.max(0.0D, Math.min(1.0D, value));
        return t * t * (3.0D - 2.0D * t);
    }

}
