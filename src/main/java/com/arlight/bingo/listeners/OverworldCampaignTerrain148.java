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

/** Terrain shaping and supported roads for the clean 1.48.6 Overworld campaign. */
final class OverworldCampaignTerrain148 {
    private static final int ORGANIC_EDGE_MARGIN = 14;
    static final int WALL_FOUNDATION_DEPTH = 12;
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
                double localBoundary = Math.max(flatRadius + 18.0D,
                        organicBoundary(site, dx, dz, blendRadius));
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
                // The organic outline already supplies visible variation. Per-column
                // vertical noise created the checkerboard pits visible around 1.48.2.
                int designed = site.baseY() + terrace;
                double blend = distance <= localFlat ? 0.0D
                        : smooth((distance - localFlat) / Math.max(1.0D, localBoundary - localFlat));
                int target = (int) Math.round(designed * (1.0D - blend) + natural * blend);
                Material top = blendedSurface(world, x, natural, z,
                        designedSurface(Style.VILLAGE, x, z), blend);
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
                double localBoundary = Math.max(flatRadius + 18.0D,
                        organicBoundary(site, dx, dz, blendRadius));
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
                        designedSurface(site.style(), x, z), blend);
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

    /**
     * Anchors a building to real terrain and blends its footprint into a short apron.
     * The apron only raises low ground; it never erases an already-built street.
     */
    static void buildingPad(List<BlockEdit> out, World world, int cx, int targetY, int cz,
                            int hx, int hz, int apron, Material surface) {
        for (int dx = -hx - apron; dx <= hx + apron; dx++) {
            for (int dz = -hz - apron; dz <= hz + apron; dz++) {
                int edgeX = Math.max(0, Math.abs(dx) - hx);
                int edgeZ = Math.max(0, Math.abs(dz) - hz);
                double edgeDistance = Math.sqrt(edgeX * edgeX + edgeZ * edgeZ);
                if (edgeDistance > apron) continue;
                int x = cx + dx;
                int z = cz + dz;
                int natural = terrainY(world, x, z);
                boolean footprint = edgeX == 0 && edgeZ == 0;
                if (!footprint && natural >= targetY) continue;
                double blend = footprint ? 0.0D : smooth(edgeDistance / Math.max(1.0D, apron));
                int columnTop = (int) Math.round(targetY * (1.0D - blend) + natural * blend);
                if (!footprint && columnTop <= natural) continue;
                if (footprint) {
                    clearAndFill(out, world, x, targetY, z, surface);
                } else {
                    fillColumn(out, world, x, columnTop, z,
                            edgeDistance <= apron * 0.55D ? surface
                                    : naturalSurface(world, x, natural, z), false);
                }
            }
        }
    }

    /** Places a walkable surface and fills only the air beneath it until real ground. */
    static void supportedPathCell(List<BlockEdit> out, World world, int x, int walkY, int z,
                                  Material floor) {
        out.add(new BlockEdit(x, walkY - 1, z, floor));
        int minimum = Math.max(world.getMinHeight() + 2, walkY - 24);
        for (int y = walkY - 2; y >= minimum; y--) {
            Material existing = world.getBlockAt(x, y, z).getType();
            if (existing.isSolid() && existing != Material.ICE
                    && existing != Material.WATER && existing != Material.LAVA) break;
            out.add(new BlockEdit(x, y, z,
                    y >= walkY - 6 ? Material.COBBLESTONE : Material.STONE));
        }
    }

    static List<BlockEdit> integrateFortifications(World world, Site military, Site citadel,
                                                   Site boss, Report report,
                                                   OverworldCampaignAudit148.Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        squareWallSupport(out, world, military, 48, 6, Material.MOSSY_STONE_BRICKS);
        squareWallSupport(out, world, citadel, 55, 8, Material.STONE_BRICKS);
        for (int radius = 48; radius <= 51; radius++) {
            circleWallSupport(out, world, boss, radius, 9, Material.DEEPSLATE_BRICKS);
        }
        registry.registerFortification(new OverworldCampaignAudit148.FortificationSpec(
                "military-wall", military.x(), military.baseY() + 1, military.z(), 48,
                OverworldCampaignAudit148.FortificationShape.SQUARE));
        registry.registerFortification(new OverworldCampaignAudit148.FortificationSpec(
                "citadel-wall", citadel.x(), citadel.baseY() + 1, citadel.z(), 55,
                OverworldCampaignAudit148.FortificationShape.SQUARE));
        registry.registerFortification(new OverworldCampaignAudit148.FortificationSpec(
                "boss-wall", boss.x(), boss.baseY() + 1, boss.z(), 51,
                OverworldCampaignAudit148.FortificationShape.CIRCLE));
        report.structures += 3;
        return out;
    }

    private static void squareWallSupport(List<BlockEdit> out, World world, Site site, int radius,
                                          int height, Material material) {
        int target = site.baseY() + 1;
        for (int offset = -radius; offset <= radius; offset++) {
            supportColumn(out, world, site.x() + offset, target, site.z() - radius,
                    height, material);
            supportColumn(out, world, site.x() + offset, target, site.z() + radius,
                    height, material);
        }
        for (int offset = -radius + 1; offset < radius; offset++) {
            supportColumn(out, world, site.x() - radius, target, site.z() + offset,
                    height, material);
            supportColumn(out, world, site.x() + radius, target, site.z() + offset,
                    height, material);
        }
    }

    private static void circleWallSupport(List<BlockEdit> out, World world, Site site, int radius,
                                          int height, Material material) {
        for (int degree = 0; degree < 360; degree += 2) {
            double a = Math.toRadians(degree);
            int x = site.x() + (int) Math.round(Math.cos(a) * radius);
            int z = site.z() + (int) Math.round(Math.sin(a) * radius);
            supportColumn(out, world, x, site.baseY() + 1, z, height, material);
        }
    }

    private static void supportColumn(List<BlockEdit> out, World world, int x, int target,
                                      int z, int finishDepth, Material material) {
        int natural = terrainY(world, x, z);
        int minimumBottom = target - WALL_FOUNDATION_DEPTH;
        int bottom = Math.max(world.getMinHeight() + 2,
                Math.max(target - 48, Math.min(minimumBottom, natural - 2)));
        for (int y = target - 1; y >= bottom; y--) {
            Material existing = world.getBlockAt(x, y, z).getType();
            // A surface block is not enough evidence of a grounded wall: shallow caves
            // beneath that block caused the military and citadel foundations to fail the
            // final audit. Always seal the complete audited core before joining natural
            // terrain; below it, keep the old non-destructive early exit.
            if (y < target - WALL_FOUNDATION_DEPTH && existing.isSolid()
                    && existing != Material.ICE && existing != Material.WATER
                    && existing != Material.LAVA) break;
            out.add(new BlockEdit(x, y, z,
                    y >= target - finishDepth ? material : Material.COBBLESTONE));
        }
    }

    static void supportedGateApproach(List<BlockEdit> out, World world, String id,
                                      int cx, int walkY, int cz, int outwardX, int outwardZ,
                                      int inside, int outside, int halfWidth, Material floor,
                                      OverworldCampaignAudit148.Registry registry) {
        int sideX = -outwardZ;
        int sideZ = outwardX;
        for (int step = -inside; step <= outside; step++) {
            for (int side = -halfWidth; side <= halfWidth; side++) {
                int x = cx + outwardX * step + sideX * side;
                int z = cz + outwardZ * step + sideZ * side;
                supportColumn(out, world, x, walkY, z, 5, Material.STONE_BRICKS);
                out.add(new BlockEdit(x, walkY - 1, z,
                        Math.floorMod(step + side, 9) == 0
                                ? Material.MOSSY_STONE_BRICKS : floor));
                for (int y = walkY; y <= walkY + 6; y++) {
                    out.add(new BlockEdit(x, y, z, Material.AIR));
                }
                registry.registerRoadCell(id, x, walkY, z, floor);
            }
            if (step >= 0) {
                for (int side : new int[]{-halfWidth - 1, halfWidth + 1}) {
                    int x = cx + outwardX * step + sideX * side;
                    int z = cz + outwardZ * step + sideZ * side;
                    int natural = terrainY(world, x, z);
                    int wallTop = Math.min(walkY + 6, Math.max(walkY + 2, natural + 1));
                    for (int y = walkY - 2; y <= wallTop; y++) {
                        Material material = Math.floorMod(step + side + y, 9) == 0
                                ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
                        out.add(new BlockEdit(x, y, z, material));
                    }
                }
            }
        }
    }

    private static Material designedSurface(Style style, int x, int z) {
        int sample = Math.floorMod(x * 31 + z * 17, 100);
        return switch (style) {
            case VILLAGE, RESIDENTIAL -> sample < 9 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
            case COMMERCIAL -> sample < 62 ? Material.MUD
                    : sample < 84 ? Material.COARSE_DIRT : Material.ROOTED_DIRT;
            case MILITARY -> sample < 42 ? Material.COARSE_DIRT
                    : sample < 68 ? Material.GRAVEL
                    : sample < 86 ? Material.ANDESITE : Material.TUFF;
            case CITADEL -> sample < 48 ? Material.ANDESITE
                    : sample < 76 ? Material.TUFF
                    : sample < 90 ? Material.STONE : Material.GRAVEL;
            case BOSS -> sample < 58 ? Material.DEEPSLATE
                    : sample < 84 ? Material.TUFF : Material.STONE;
            case PORTAL -> sample < 46 ? Material.ANDESITE
                    : sample < 73 ? Material.DIORITE
                    : sample < 90 ? Material.TUFF : Material.GRAVEL;
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
            int halfWidth = Math.max(1, width / 2);
            for (int ox = -halfWidth; ox <= halfWidth; ox++) {
                for (int oz = -halfWidth; oz <= halfWidth; oz++) {
                    if (ox * ox + oz * oz > halfWidth * halfWidth + halfWidth) continue;
                    int x = cx + ox;
                    int z = cz + oz;
                    Material floor = bridge ? Material.SPRUCE_PLANKS
                            : Math.floorMod(step + ox * 3 + oz * 5, 9) == 0
                            ? secondary(path) : path;
                    out.add(new BlockEdit(x, walkY - 1, z, floor));
                    for (int y = walkY; y <= walkY + 3; y++) {
                        out.add(new BlockEdit(x, y, z, Material.AIR));
                    }
                    registry.registerRoadCell(id, x, walkY, z, floor);
                    support(out, world, x, walkY - 2, z,
                            bridge ? Material.STRIPPED_SPRUCE_LOG : Material.COBBLESTONE);
                }
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
