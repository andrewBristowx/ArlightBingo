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

/** Terrain reconstruction and supported roads for the 1.48.1 Overworld campaign. */
final class OverworldCampaignTerrain148 {
    private static final int SEA_LEVEL = 62;
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

    /**
     * Rebuilds a complete legacy footprint from a sampled natural boundary. Unlike the
     * 1.45 cleanup, it does not trust the damaged terrain inside the footprint, so cut
     * towers, rectangular lakes and floating houses cannot survive as the new reference.
     */
    static List<BlockEdit> restoreLegacySite(World world, Site site, Report report,
                                             int minimumDx, int maximumDx) {
        List<BlockEdit> out = new ArrayList<>();
        BoundaryProfile profile = BoundaryProfile.sample(world, site, site.radius() + 12);
        int radius = site.radius();
        for (int dx = Math.max(-radius, minimumDx); dx <= Math.min(radius, maximumDx); dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > radius) continue;
                int x = site.x() + dx;
                int z = site.z() + dz;
                double ratio = Math.min(1.0D, distance / Math.max(1.0D, radius));
                BoundarySample boundary = profile.at(Math.atan2(dz, dx));
                double edgeBlend = smooth(Math.max(0.0D, (ratio - 0.72D) / 0.28D));
                int insideTarget = profile.centerY
                        + (int) Math.round(profile.gradientX * dx + profile.gradientZ * dz)
                        + noise(x, z, 2);
                int target = (int) Math.round(insideTarget * (1.0D - edgeBlend)
                        + boundary.y * edgeBlend);
                target = Math.max(world.getMinHeight() + 5,
                        Math.min(world.getMaxHeight() - 30, target));

                boolean restoreOcean = boundary.wet && ratio >= 0.56D;
                if (restoreOcean) {
                    double wetBlend = smooth((ratio - 0.56D) / 0.44D);
                    int oceanFloor = Math.min(SEA_LEVEL - 3, boundary.y);
                    target = (int) Math.round(target * (1.0D - wetBlend) + oceanFloor * wetBlend);
                }

                int top = Math.min(world.getMaxHeight() - 2,
                        world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE) + 48);
                for (int y = target + 1; y <= top; y++) {
                    Material current = world.getBlockAt(x, y, z).getType();
                    Material replacement = restoreOcean && y <= SEA_LEVEL ? Material.WATER : Material.AIR;
                    if (current != replacement) {
                        out.add(new BlockEdit(x, y, z, replacement));
                        if (!current.isAir() && current != Material.WATER) report.clearedBlocks++;
                    }
                }

                Material topMaterial;
                if (restoreOcean) topMaterial = ratio > 0.78D ? Material.GRAVEL : Material.SAND;
                else if (ratio > 0.84D && boundary.wet) topMaterial = Material.SAND;
                else topMaterial = Math.floorMod(x * 17 + z * 11, 23) < 4
                        ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
                fillColumn(out, world, x, target, z, topMaterial, restoreOcean);
                if (restoreOcean) {
                    for (int y = target + 1; y <= SEA_LEVEL; y++) out.add(new BlockEdit(x, y, z, Material.WATER));
                }
                report.restoredColumns++;
            }
        }
        return out;
    }

    static List<BlockEdit> shapeVillage(World world, Site site, int flatRadius, int blendRadius,
                                        Report report, int minimumDx, int maximumDx) {
        List<BlockEdit> out = new ArrayList<>();
        for (int dx = Math.max(-blendRadius, minimumDx); dx <= Math.min(blendRadius, maximumDx); dx++) {
            for (int dz = -blendRadius; dz <= blendRadius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > blendRadius) continue;
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
                int designed = site.baseY() + terrace + noise(x, z, distance > flatRadius - 6 ? 1 : 0);
                double blend = distance <= flatRadius ? 0.0D
                        : smooth((distance - flatRadius) / Math.max(1.0D, blendRadius - flatRadius));
                int target = (int) Math.round(designed * (1.0D - blend) + natural * blend);
                clearAndFill(out, world, x, target, z, Style.VILLAGE, distance >= flatRadius - 3.0D);
                report.shapedColumns++;
            }
        }
        return out;
    }

    static List<BlockEdit> shapeSite(World world, Site site, int flatRadius, int blendRadius,
                                     Report report, int minimumDx, int maximumDx) {
        List<BlockEdit> out = new ArrayList<>();
        for (int dx = Math.max(-blendRadius, minimumDx); dx <= Math.min(blendRadius, maximumDx); dx++) {
            for (int dz = -blendRadius; dz <= blendRadius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > blendRadius) continue;
                int x = site.x() + dx;
                int z = site.z() + dz;
                int natural = terrainY(world, x, z);
                double blend = distance <= flatRadius ? 0.0D
                        : smooth((distance - flatRadius) / Math.max(1.0D, blendRadius - flatRadius));
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
                clearAndFill(out, world, x, target, z, site.style(), distance >= flatRadius - 2.0D);
                report.shapedColumns++;
            }
        }
        return out;
    }

    private static void clearAndFill(List<BlockEdit> out, World world, int x, int target, int z,
                                     Style style, boolean edge) {
        int top = Math.min(world.getMaxHeight() - 2,
                world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE) + 12);
        for (int y = target + 1; y <= top; y++) out.add(new BlockEdit(x, y, z, Material.AIR));
        fillColumn(out, world, x, target, z, surface(style, edge), false);
        stabilizeSlope(out, world, x, target, z, style, edge);
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

    private static void stabilizeSlope(List<BlockEdit> out, World world, int x, int target, int z,
                                      Style style, boolean edge) {
        if (!edge) return;
        Material facing = style == Style.CITADEL || style == Style.BOSS || style == Style.MILITARY
                ? Material.MOSSY_STONE_BRICKS : Material.COBBLESTONE;
        for (int y = target - 1; y >= Math.max(world.getMinHeight() + 2, target - 6); y--) {
            Material below = world.getBlockAt(x, y, z).getType();
            if (below.isAir() || below == Material.WATER) out.add(new BlockEdit(x, y, z, facing));
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
        reinforceFoundation(out, world, citadel, 48, Material.STONE_BRICKS);
        reinforceFoundation(out, world, portal, 24, Material.COBBLESTONE);
        report.structures += 7;
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

    private static void reinforceFoundation(List<BlockEdit> out, World world, Site site,
                                            int radius, Material facing) {
        for (int degree = 0; degree < 360; degree += 45) {
            double angle = Math.toRadians(degree);
            int nx = (int) Math.round(Math.cos(angle));
            int nz = (int) Math.round(Math.sin(angle));
            for (int step = 0; step <= 10; step++) {
                int half = Math.max(1, 4 - step / 3);
                int cx = site.x() + nx * (radius + step);
                int cz = site.z() + nz * (radius + step);
                int target = site.baseY() - step / 2;
                for (int side = -half; side <= half; side++) {
                    int x = cx + (nz == 0 ? 0 : side);
                    int z = cz + (nx == 0 ? 0 : side);
                    int natural = terrainY(world, x, z);
                    for (int y = target; y >= Math.max(natural - 1, target - 36); y--) {
                        out.add(new BlockEdit(x, y, z,
                                y >= target - 4 ? facing : Material.COBBLESTONE));
                    }
                }
            }
        }
    }

    static List<BlockEdit> polishCoastlines(World world, Site village, Site portal, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        adaptiveShoreline(out, world, village.x(), village.z(), 78, 103);
        adaptiveShoreline(out, world, portal.x(), portal.z(), 27, 45);
        reinforceFoundation(out, world, portal, 24, Material.MOSSY_COBBLESTONE);
        report.restoredColumns += 96;
        return out;
    }

    private static void adaptiveShoreline(List<BlockEdit> out, World world,
                                          int cx, int cz, int inner, int outer) {
        for (int dx = -outer; dx <= outer; dx++) for (int dz = -outer; dz <= outer; dz++) {
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance < inner || distance > outer) continue;
            int x = cx + dx;
            int z = cz + dz;
            int current = terrainY(world, x, z);
            int[] neighbors = {
                    terrainY(world, x - 3, z), terrainY(world, x + 3, z),
                    terrainY(world, x, z - 3), terrainY(world, x, z + 3)
            };
            java.util.Arrays.sort(neighbors);
            int median = (neighbors[1] + neighbors[2]) / 2;
            if (current > SEA_LEVEL + 14 && median > SEA_LEVEL + 14) continue;
            int target = Math.max(current - 2, Math.min(current + 2, median));
            boolean shore = target <= SEA_LEVEL + 2;
            Material top = shore ? (Math.floorMod(x + z, 5) == 0
                    ? Material.GRAVEL : Material.SAND) : Material.GRASS_BLOCK;
            int highest = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
            for (int y = target + 1; y <= Math.min(highest + 2, target + 8); y++) {
                out.add(new BlockEdit(x, y, z, y <= SEA_LEVEL ? Material.WATER : Material.AIR));
            }
            fillColumn(out, world, x, target, z, top, shore);
            if (target < SEA_LEVEL) for (int y = target + 1; y <= SEA_LEVEL; y++)
                out.add(new BlockEdit(x, y, z, Material.WATER));
        }
    }

    private static Material surface(Style style, boolean edge) {
        if (edge) return switch (style) {
            case VILLAGE, RESIDENTIAL -> Material.MOSS_BLOCK;
            case COMMERCIAL -> Material.MUD_BRICKS;
            case MILITARY, CITADEL -> Material.MOSSY_COBBLESTONE;
            case BOSS -> Material.DEEPSLATE_BRICKS;
            case PORTAL -> Material.COBBLESTONE;
        };
        return switch (style) {
            case VILLAGE, RESIDENTIAL -> Material.GRASS_BLOCK;
            case COMMERCIAL -> Material.PACKED_MUD;
            case MILITARY -> Material.COARSE_DIRT;
            case CITADEL -> Material.STONE_BRICKS;
            case BOSS -> Material.DEEPSLATE_TILES;
            case PORTAL -> Material.POLISHED_ANDESITE;
        };
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

    private record BoundarySample(int y, boolean wet) { }

    private static final class BoundaryProfile {
        private static final int SAMPLES = 32;
        private final BoundarySample[] samples;
        private final int centerY;
        private final double gradientX;
        private final double gradientZ;

        private BoundaryProfile(BoundarySample[] samples, int centerY,
                                double gradientX, double gradientZ) {
            this.samples = samples;
            this.centerY = centerY;
            this.gradientX = gradientX;
            this.gradientZ = gradientZ;
        }

        static BoundaryProfile sample(World world, Site site, int radius) {
            BoundarySample[] samples = new BoundarySample[SAMPLES];
            List<Integer> dry = new ArrayList<>();
            List<Integer> all = new ArrayList<>();
            for (int index = 0; index < SAMPLES; index++) {
                double angle = Math.PI * 2.0D * index / SAMPLES;
                int x = site.x() + (int) Math.round(Math.cos(angle) * radius);
                int z = site.z() + (int) Math.round(Math.sin(angle) * radius);
                int y = terrainY(world, x, z);
                boolean wet = wet(world, x, z);
                samples[index] = new BoundarySample(y, wet);
                all.add(y);
                if (!wet) dry.add(y);
            }
            List<Integer> reference = dry.size() >= 6 ? dry : all;
            reference.sort(Comparator.naturalOrder());
            int centerY = reference.get(reference.size() / 2);
            int east = samples[0].y;
            int west = samples[SAMPLES / 2].y;
            int south = samples[SAMPLES / 4].y;
            int north = samples[SAMPLES * 3 / 4].y;
            return new BoundaryProfile(samples, centerY,
                    (east - west) / (2.0D * radius),
                    (south - north) / (2.0D * radius));
        }

        BoundarySample at(double angle) {
            double normalized = angle < 0.0D ? angle + Math.PI * 2.0D : angle;
            double position = normalized / (Math.PI * 2.0D) * SAMPLES;
            int first = Math.floorMod((int) Math.floor(position), SAMPLES);
            int second = (first + 1) % SAMPLES;
            double fraction = position - Math.floor(position);
            int y = (int) Math.round(samples[first].y * (1.0D - fraction)
                    + samples[second].y * fraction);
            boolean wet = fraction < 0.5D ? samples[first].wet : samples[second].wet;
            return new BoundarySample(y, wet);
        }
    }
}
