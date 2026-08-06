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

/** Terrain shaping and supported roads for the clean 1.48.28 Overworld campaign. */
final class OverworldCampaignTerrain148 {
    private static final int ORGANIC_EDGE_MARGIN = 14;
    private static final int CITADEL_GATE_TRANSITION_LENGTH = 22;
    private static final int CITADEL_INTERIOR_RADIUS = 54;
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
        if (site.style() == Style.CITADEL) {
            return shapeCitadelSite(world, site, report, minimumDx, maximumDx);
        }
        if (site.style() == Style.BOSS) {
            return shapeBossArena(world, site, flatRadius, blendRadius,
                    report, minimumDx, maximumDx);
        }
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
                double designed = site.baseY();
                if (site.style() == Style.RESIDENTIAL) designed += dz < -16 ? 1 : 0;
                if (site.style() == Style.MILITARY) designed += dz < -24 ? 2 : 0;
                if (site.style() == Style.CITADEL) designed += dz < -18 ? 1 : dz > 24 ? -1 : 0;
                if (site.style() == Style.BOSS) {
                    // Keep the northern gate and its road on the same grade. A hard +2
                    // offset previously turned the whole approach into a deep trench and
                    // produced the bare, binary dirt terraces visible in 1.48.6.
                    designed -= smooth((dz - 22.0D) / 28.0D);
                }
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

    /**
     * Shapes only the square fortress footprint. Earlier circular blending modified almost one
     * hundred blocks around the citadel and produced the huge grey/red terraces visible in
     * testing. The courtyard and the three-block wall belt are authoritative; everything beyond
     * the wall remains natural and is joined locally by the four graded gate transitions.
     */
    private static List<BlockEdit> shapeCitadelSite(World world, Site site, Report report,
                                                     int minimumDx, int maximumDx) {
        List<BlockEdit> out = new ArrayList<>();
        int extent = 56;
        for (int dx = Math.max(-extent, minimumDx); dx <= Math.min(extent, maximumDx); dx++) {
            for (int dz = -extent; dz <= extent; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if (ring > extent) continue;
                int x = site.x() + dx;
                int z = site.z() + dz;
                Material top = ring <= CITADEL_INTERIOR_RADIUS
                        ? (Math.floorMod(x * 13 + z * 7, 17) < 3
                        ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS)
                        : Material.STONE_BRICKS;
                clearAndFill(out, world, x, site.baseY(), z, top);
                report.shapedColumns++;
            }
        }
        return out;
    }


    /**
     * Flattens only the playable arena core. The narrow outer ring may raise low ground to
     * support the wall, but it never cuts a natural hill. This prevents the enormous bare
     * plateau seen around the boss in 1.48.10 while keeping the arena floor walkable.
     */
    private static List<BlockEdit> shapeBossArena(World world, Site site,
                                                   int flatRadius, int blendRadius,
                                                   Report report, int minimumDx,
                                                   int maximumDx) {
        List<BlockEdit> out = new ArrayList<>();
        int extent = blendRadius;
        for (int dx = Math.max(-extent, minimumDx); dx <= Math.min(extent, maximumDx); dx++) {
            for (int dz = -extent; dz <= extent; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > blendRadius) continue;
                int x = site.x() + dx;
                int z = site.z() + dz;
                int natural = terrainY(world, x, z);
                if (distance <= flatRadius) {
                    Material top = designedSurface(Style.BOSS, x, z);
                    clearAndFill(out, world, x, site.baseY(), z, top);
                    report.shapedColumns++;
                    continue;
                }
                double blend = smooth((distance - flatRadius)
                        / Math.max(1.0D, blendRadius - flatRadius));
                int target = (int) Math.round(site.baseY() * (1.0D - blend)
                        + natural * blend);
                // Outside the arena floor, only support low ground. High natural terrain is
                // preserved instead of being excavated into a circular dirt shelf.
                if (target <= natural) continue;
                Material top = blendedSurface(world, x, natural, z,
                        designedSurface(Style.BOSS, x, z), blend);
                fillColumn(out, world, x, target, z, top, false);
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
                    boolean perimeter = Math.abs(dx) >= hx - 1 || Math.abs(dz) >= hz - 1;
                    if (perimeter && targetY - natural >= 4) {
                        addBuildingRetainingFace(out, x, natural, targetY, z, dx, dz, hx, hz);
                    }
                } else {
                    fillColumn(out, world, x, columnTop, z,
                            edgeDistance <= apron * 0.55D ? surface
                                    : naturalSurface(world, x, natural, z), false);
                }
            }
        }
    }

    private static void addBuildingRetainingFace(List<BlockEdit> out, int x, int natural,
                                                  int targetY, int z, int dx, int dz,
                                                  int hx, int hz) {
        boolean exposedX = Math.abs(dx) >= hx - 1;
        boolean exposedZ = Math.abs(dz) >= hz - 1;
        if (!exposedX && !exposedZ) return;
        int bottom = Math.max(natural + 1, targetY - 14);
        for (int y = bottom; y <= targetY; y++) {
            int sample = Math.floorMod(x * 13 + y * 7 + z * 17, 11);
            Material wall = y >= targetY - 2 ? Material.STONE_BRICKS
                    : sample < 2 ? Material.MOSSY_STONE_BRICKS : Material.COBBLESTONE;
            out.add(new BlockEdit(x, y, z, wall));
        }
        if (targetY - natural >= 8 && Math.floorMod(x + z, 4) == 0) {
            out.add(new BlockEdit(x, targetY + 1, z, Material.STONE_BRICK_WALL));
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

    static boolean isTerrainLike(Material material) {
        return TERRAIN.contains(material) || material == Material.GRASS_BLOCK
                || material == Material.DIRT_PATH || material == Material.SNOW
                || material == Material.SHORT_GRASS || material == Material.TALL_GRASS
                || material == Material.FERN || material == Material.LARGE_FERN;
    }

    /**
     * Levels natural ground inside the citadel and, independently, supports every designed
     * courtyard/building column from below. Previous revisions skipped structure footprints and
     * non-terrain tops completely; that left the long green trenches and floating plaza edges
     * visible beside towers and walls.
     */
    static List<BlockEdit> finalizeCitadelGround(World world, Site citadel,
                                                  OverworldCampaignAudit148.Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        int target = citadel.baseY();
        for (int dx = -CITADEL_INTERIOR_RADIUS; dx <= CITADEL_INTERIOR_RADIUS; dx++) {
            for (int dz = -CITADEL_INTERIOR_RADIUS; dz <= CITADEL_INTERIOR_RADIUS; dz++) {
                int x = citadel.x() + dx;
                int z = citadel.z() + dz;
                supportCitadelSubsurface(out, world, x, target - 1, z);

                boolean structure = registry.blocksStructureBody(x, target + 1, z);
                int top = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                Material topType = world.getBlockAt(x, top, z).getType();
                if (structure || !isTerrainLike(topType)) continue;
                if (top > target) {
                    for (int y = target + 1; y <= top; y++) {
                        Material current = world.getBlockAt(x, y, z).getType();
                        if (isTerrainLike(current) || current.isAir()) {
                            out.add(new BlockEdit(x, y, z, Material.AIR));
                        }
                    }
                }
                fillColumn(out, world, x, target, z,
                        Math.floorMod(x * 13 + z * 7, 17) < 3
                                ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS,
                        false);
            }
        }
        return out;
    }

    private static void supportCitadelSubsurface(List<BlockEdit> out, World world,
                                                  int x, int startY, int z) {
        int minimum = Math.max(world.getMinHeight() + 2, startY - 36);
        for (int y = startY; y >= minimum; y--) {
            Material existing = world.getBlockAt(x, y, z).getType();
            if (existing.isSolid() && existing != Material.ICE
                    && existing != Material.WATER && existing != Material.LAVA) break;
            out.add(new BlockEdit(x, y, z, y >= startY - 5
                    ? Material.STONE_BRICKS : Material.COBBLESTONE));
        }
    }

    /**
     * Final authoritative pass for the citadel and boss arena. It runs after roads, towers,
     * house shells and decorations so no later phase can reopen the wall aprons, erase the
     * approach or leave the circular arena split into disconnected pieces.
     */
    static List<BlockEdit> finalizeFortressAndColiseum(World world, Site citadel, Site boss,
                                                        Location ritualGate,
                                                        OverworldCampaignAudit148.Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        out.addAll(finalizeCitadelGround(world, citadel, registry));
        anchorCitadelWallBase(out, world, citadel);
        blendCitadelWallEdge(out, world, citadel, registry);
        groundCitadelButtresses(out, world, citadel);
        rebuildCitadelGateTransition(out, world, registry, "citadel-north-gate",
                citadel.x(), citadel.baseY() + 1, citadel.z() - 55, 0, -1);
        rebuildCitadelGateTransition(out, world, registry, "citadel-south-approach",
                citadel.x(), citadel.baseY() + 1, citadel.z() + 55, 0, 1);
        rebuildCitadelGateTransition(out, world, registry, "citadel-west-gate",
                citadel.x() - 55, citadel.baseY() + 1, citadel.z() + 10, -1, 0);
        rebuildCitadelGateTransition(out, world, registry, "citadel-east-gate",
                citadel.x() + 55, citadel.baseY() + 1, citadel.z() + 10, 1, 0);
        sealCitadelTowerCorridors(out, world, citadel, registry);
        polishBossNorthApproach(out, world, boss, ritualGate, registry);
        return out;
    }

    /**
     * Grounds only the actual wall belt. Earlier revisions flattened a 10-block square apron
     * and then a 23-block outer ring, producing the giant artificial platforms visible in
     * testing. The wall now keeps its masonry foundation while the surrounding terrain stays
     * natural.
     */
    private static void anchorCitadelWallBase(List<BlockEdit> out, World world, Site citadel) {
        int target = citadel.baseY();
        for (int dx = -56; dx <= 56; dx++) {
            for (int dz = -56; dz <= 56; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if (ring < 54 || ring > 56) continue;
                int x = citadel.x() + dx;
                int z = citadel.z() + dz;
                int natural = terrainY(world, x, z);
                int bottom = Math.max(world.getMinHeight() + 2,
                        Math.min(target - 1, natural + 1));
                for (int y = bottom; y <= target; y++) {
                    out.add(new BlockEdit(x, y, z, y >= target - 3
                            ? Material.STONE_BRICKS : Material.COBBLESTONE));
                }
            }
        }
    }

    /**
     * Blends at most eight blocks outside the wall and rises/falls by no more than one block
     * per band. Large height differences remain honest retaining foundations instead of being
     * converted into a square mountain or a vertical dirt cliff.
     */
    private static void blendCitadelWallEdge(List<BlockEdit> out, World world, Site citadel,
                                              OverworldCampaignAudit148.Registry registry) {
        // Only a four-block masonry skirt is allowed outside the wall. Never cut high natural
        // ground and never paint a grass platform around the complete square fortress.
        int inner = 57;
        int outer = 60;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if (ring < inner || ring > outer) continue;
                int x = citadel.x() + dx;
                int z = citadel.z() + dz;
                if (registry.blocksStructureBody(x, citadel.baseY() + 1, z)) continue;
                int natural = terrainY(world, x, z);
                int target = citadel.baseY() - (ring - inner + 1);
                if (natural >= target) continue;
                fillColumn(out, world, x, target, z,
                        ring <= 58 ? Material.ANDESITE : Material.STONE, false);
            }
        }
    }

    private static void groundCitadelButtresses(List<BlockEdit> out, World world, Site citadel) {
        int y = citadel.baseY() + 1;
        int radius = 55;
        for (int offset = -47; offset <= 47; offset += 14) {
            if (Math.abs(offset) > 9) {
                groundedButtress(out, world, citadel.x() + offset, y,
                        citadel.z() - radius, 0, -1);
                groundedButtress(out, world, citadel.x() + offset, y,
                        citadel.z() + radius, 0, 1);
            }
            if (Math.abs(offset - 10) > 9) {
                groundedButtress(out, world, citadel.x() - radius, y,
                        citadel.z() + offset, -1, 0);
                groundedButtress(out, world, citadel.x() + radius, y,
                        citadel.z() + offset, 1, 0);
            }
        }
    }

    private static void groundedButtress(List<BlockEdit> out, World world, int x, int y, int z,
                                          int outwardX, int outwardZ) {
        int sideX = -outwardZ;
        int sideZ = outwardX;
        // Compact three-block projection. The previous seven-block stair profile looked like a
        // second public staircase and created rectangular grass shelves beside the wall.
        for (int depth = 0; depth <= 3; depth++) {
            int px = x + outwardX * depth;
            int pz = z + outwardZ * depth;
            int crown = y + 3 - depth;
            for (int width = -1; width <= 1; width++) {
                int bx = px + sideX * width;
                int bz = pz + sideZ * width;
                int natural = terrainY(world, bx, bz);
                if (natural >= crown) continue;
                int bottom = Math.max(world.getMinHeight() + 2, natural + 1);
                for (int yy = bottom; yy <= crown; yy++) {
                    Material material = yy <= y - 2 ? Material.COBBLESTONE
                            : Math.floorMod(bx + bz + yy, 9) == 0
                            ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
                    out.add(new BlockEdit(bx, yy, bz, material));
                }
            }
        }
    }

    private static void rebuildCitadelGateTransition(List<BlockEdit> out, World world,
                                                       OverworldCampaignAudit148.Registry registry,
                                                       String id, int gateX, int walkY, int gateZ,
                                                       int outwardX, int outwardZ) {
        registry.clearRoute(id);
        int sideX = -outwardZ;
        int sideZ = outwardX;
        int gateHalfWidth = 4;
        Location outer = citadelGateOuterPoint(world, gateX, walkY, gateZ, outwardX, outwardZ);
        int outerY = outer.getBlockY();
        for (int step = -8; step <= CITADEL_GATE_TRANSITION_LENGTH; step++) {
            int cx = gateX + outwardX * step;
            int cz = gateZ + outwardZ * step;
            int cellY = step <= 0 ? walkY
                    : gradedWalkY(walkY, outerY, step, CITADEL_GATE_TRANSITION_LENGTH);
            // The gate starts nine blocks wide but joins the five-block public road at the
            // outer end. Keeping the full width for all 18 blocks created the raised stone
            // walls visible on both sides of the fortress.
            int halfWidth = step <= 0 ? gateHalfWidth
                    : Math.max(2, gateHalfWidth
                    - (int) Math.floor(step * 1.5D / CITADEL_GATE_TRANSITION_LENGTH));
            for (int lateral = -halfWidth; lateral <= halfWidth; lateral++) {
                int x = cx + sideX * lateral;
                int z = cz + sideZ * lateral;
                Material floor = Math.floorMod(step * 5 + lateral * 7, 17) < 3
                        ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_ANDESITE;
                supportedPathCell(out, world, x, cellY, z, floor);
                for (int yy = cellY; yy <= cellY + 6; yy++) {
                    out.add(new BlockEdit(x, yy, z, Material.AIR));
                }
                registry.registerAuthoritativeRoadCell(id, x, cellY, z, floor);
            }
            shapeGateShoulders(out, world, gateX, cellY, gateZ,
                    outwardX, outwardZ, sideX, sideZ, step, halfWidth);
        }
        restoreCitadelGateArch(out, gateX, walkY, gateZ,
                outwardX, outwardZ, sideX, sideZ);
    }

    private static Location citadelGateOuterPoint(World world, int gateX, int walkY, int gateZ,
                                                   int outwardX, int outwardZ) {
        int x = gateX + outwardX * CITADEL_GATE_TRANSITION_LENGTH;
        int z = gateZ + outwardZ * CITADEL_GATE_TRANSITION_LENGTH;
        int natural = terrainY(world, x, z) + 1;
        int maximumDelta = 6;
        int delta = Math.max(-maximumDelta, Math.min(maximumDelta, natural - walkY));
        return point(world, x, walkY + delta, z);
    }

    private static int gradedWalkY(int startY, int endY, int step, int totalSteps) {
        if (step <= 0 || totalSteps <= 0) return startY;
        if (step >= totalSteps) return endY;
        return startY + (int) Math.round((endY - startY)
                * (step / (double) totalSteps));
    }

    private static void shapeGateShoulders(List<BlockEdit> out, World world,
                                            int gateX, int walkY, int gateZ,
                                            int outwardX, int outwardZ,
                                            int sideX, int sideZ,
                                            int step, int halfWidth) {
        for (int direction : new int[]{-1, 1}) {
            for (int shoulder = 1; shoulder <= 3; shoulder++) {
                int lateral = direction * (halfWidth + shoulder);
                int x = gateX + outwardX * step + sideX * lateral;
                int z = gateZ + outwardZ * step + sideZ * lateral;
                int natural = terrainY(world, x, z);
                int target = walkY - 1 - Math.min(2, shoulder - 1);
                if (natural < target) {
                    fillColumn(out, world, x, target, z, shoulder == 1
                            ? Material.STONE_BRICKS
                            : shoulder == 2 ? Material.ANDESITE : Material.STONE, false);
                } else if (natural > target + 2 && shoulder == 1) {
                    // Cut only the first shoulder beside the paved surface. This removes the
                    // dirt lips over the stairs while leaving the surrounding hill natural.
                    for (int yy = target + 1; yy <= Math.min(natural, target + 6); yy++) {
                        Material current = world.getBlockAt(x, yy, z).getType();
                        if (isTerrainLike(current) || current.isAir()) {
                            out.add(new BlockEdit(x, yy, z, Material.AIR));
                        }
                    }
                    out.add(new BlockEdit(x, target, z, Material.STONE_BRICKS));
                }
            }
        }
    }

    private static void restoreCitadelGateArch(List<BlockEdit> out, int gateX, int walkY,
                                                int gateZ, int outwardX, int outwardZ,
                                                int sideX, int sideZ) {
        int half = 5;
        // Rebuild a real seven-block-deep tunnel through the wall instead of clearing only the
        // central plane. This prevents the staircase from terminating in a stone/terrain face.
        for (int depth = -3; depth <= 3; depth++) {
            int centerX = gateX + outwardX * depth;
            int centerZ = gateZ + outwardZ * depth;
            for (int lateral = -half; lateral <= half; lateral++) {
                int x = centerX + sideX * lateral;
                int z = centerZ + sideZ * lateral;
                out.add(new BlockEdit(x, walkY - 1, z, Material.POLISHED_ANDESITE));
                for (int yy = 0; yy <= 6; yy++) {
                    out.add(new BlockEdit(x, walkY + yy, z, Material.AIR));
                }
                out.add(new BlockEdit(x, walkY + 7, z,
                        Math.floorMod(lateral + depth, 5) == 0
                                ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS));
            }
            for (int lateral : new int[]{-half - 1, half + 1}) {
                int x = centerX + sideX * lateral;
                int z = centerZ + sideZ * lateral;
                for (int yy = -1; yy <= 9; yy++) {
                    out.add(new BlockEdit(x, walkY + yy, z,
                            Math.floorMod(lateral + yy + depth, 9) == 0
                                    ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
                }
            }
            if (depth % 2 == 0) {
                out.add(new BlockEdit(centerX, walkY + 4, centerZ, Material.LIGHT,
                        "minecraft:light[level=14,waterlogged=false]"));
            }
        }
    }

    private static void clearNaturalAbove(List<BlockEdit> out, World world, int x, int target,
                                           int z, int maximumY) {
        int top = Math.min(maximumY,
                world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE));
        for (int yy = target + 1; yy <= top; yy++) {
            Material material = world.getBlockAt(x, yy, z).getType();
            if (isTerrainLike(material) || material.isAir()) {
                out.add(new BlockEdit(x, yy, z, Material.AIR));
            }
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
            int cellY = "boss-north-approach".equals(id) && step >= 0
                    ? bossApproachWalkY(walkY, step) : walkY;
            for (int side = -halfWidth; side <= halfWidth; side++) {
                int x = cx + outwardX * step + sideX * side;
                int z = cz + outwardZ * step + sideZ * side;
                supportColumn(out, world, x, cellY, z, 5, Material.STONE_BRICKS);
                Material pathFloor = Math.floorMod(step + side, 9) == 0
                        ? Material.MOSSY_STONE_BRICKS : floor;
                out.add(new BlockEdit(x, cellY - 1, z, pathFloor));
                for (int clearY = cellY; clearY <= cellY + 6; clearY++) {
                    out.add(new BlockEdit(x, clearY, z, Material.AIR));
                }
                registry.registerRoadCell(id, x, cellY, z, pathFloor);
            }
            if (step >= 0 && "boss-north-approach".equals(id)) {
                landscapeBossApproachShoulders(out, world, cx, cellY, cz,
                        outwardX, outwardZ, sideX, sideZ, step, halfWidth);
            }
            // Other approaches stay flush with the terrain. Previous versions built tall
            // side walls here, creating the boxed platforms and duplicate ramps in testing.
        }
    }

    static int bossApproachWalkY(int gateY, int outwardStep) {
        if (outwardStep <= 1) return gateY;
        return gateY - Math.min(2, (outwardStep - 1) / 10);
    }

    private static void landscapeBossApproachShoulders(List<BlockEdit> out, World world,
                                                       int cx, int walkY, int cz,
                                                       int outwardX, int outwardZ,
                                                       int sideX, int sideZ,
                                                       int step, int halfWidth) {
        for (int direction : new int[]{-1, 1}) {
            for (int shoulder = 1; shoulder <= 3; shoulder++) {
                int side = direction * (halfWidth + shoulder);
                int x = cx + outwardX * step + sideX * side;
                int z = cz + outwardZ * step + sideZ * side;
                int natural = terrainY(world, x, z);
                int target = walkY - 1 - Math.min(1, shoulder - 1);
                if (natural < target) {
                    fillColumn(out, world, x, target, z, shoulder == 1
                            ? Material.MOSSY_STONE_BRICKS : Material.ANDESITE, false);
                } else if (natural > target + 2 && shoulder == 1) {
                    for (int y = target; y <= Math.min(natural, target + 4); y++) {
                        out.add(new BlockEdit(x, y, z, Math.floorMod(step + y, 7) == 0
                                ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
                    }
                }
            }
        }
    }

    private static void replaceNaturalTop(List<BlockEdit> out, World world,
                                          int x, int natural, int z, Material preferred) {
        Material current = world.getBlockAt(x, natural, z).getType();
        if (current == Material.DIRT || current == Material.COARSE_DIRT
                || current == Material.ROOTED_DIRT || current == Material.STONE
                || current == Material.GRAVEL || current == Material.ANDESITE) {
            Material surface = preferred == Material.MOSSY_STONE_BRICKS
                    ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
            out.add(new BlockEdit(x, natural, z, surface));
        }
    }

    private static Material designedSurface(Style style, int x, int z) {
        int sample = Math.floorMod(x * 31 + z * 17, 100);
        return switch (style) {
            case VILLAGE, RESIDENTIAL -> clusteredVillageSurface(x, z);
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

    private static Material clusteredVillageSurface(int x, int z) {
        // One small round patch per selected 11x11 cell produces natural clusters.
        // Hashing every block independently created the regular dirt checkerboard in 1.48.6.
        int size = 11;
        int cellX = Math.floorDiv(x, size);
        int cellZ = Math.floorDiv(z, size);
        int seed = Math.floorMod(cellX * 73428767 ^ cellZ * 912931, 9973);
        if (Math.floorMod(seed, 3) == 0) return Material.GRASS_BLOCK;
        int centerX = cellX * size + 2 + Math.floorMod(seed, size - 4);
        int centerZ = cellZ * size + 2 + Math.floorMod(seed / 17, size - 4);
        int dx = x - centerX;
        int dz = z - centerZ;
        int radius = 1 + Math.floorMod(seed / 31, 2);
        if (dx * dx + dz * dz > radius * radius) return Material.GRASS_BLOCK;
        return Math.floorMod(seed + dx * 7 + dz * 11, 5) == 0
                ? Material.MOSS_BLOCK : Material.COARSE_DIRT;
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
        // Hashing every individual block produced an uncovered, "salt and pepper" mix of
        // single raw-dirt/gravel blocks scattered through the shaped stone terraces near
        // every site's edge (visible as unfinished patches around the stairs). Group the
        // choice into small coherent cells instead, the same fix already applied to
        // clusteredVillageSurface, so the natural material shows up as whole patches.
        int cell = 4;
        int cellX = Math.floorDiv(x, cell);
        int cellZ = Math.floorDiv(z, cell);
        int sample = Math.floorMod(cellX * 668265263 ^ cellZ * 374761393, 100);
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
        Location westGateOuter = citadelGateOuterPoint(world, citadel.x() - 55,
                citadel.baseY() + 1, citadel.z() + 10, -1, 0);
        Location northGateOuter = citadelGateOuterPoint(world, citadel.x(),
                citadel.baseY() + 1, citadel.z() - 55, 0, -1);
        Location eastGateOuter = citadelGateOuterPoint(world, citadel.x() + 55,
                citadel.baseY() + 1, citadel.z() + 10, 1, 0);
        Location southGateOuter = citadelGateOuterPoint(world, citadel.x(),
                citadel.baseY() + 1, citadel.z() + 55, 0, 1);

        road(out, world, "village-residential", villageExit,
                gateToward(world, residential, villageCenter), 5,
                Material.DIRT_PATH, 0.16D, registry);
        road(out, world, "residential-citadel", gateToward(world, residential, citadel),
                westGateOuter, 5, Material.COBBLESTONE, -0.14D, registry);
        road(out, world, "commercial-citadel", gateToward(world, commercial, citadel),
                northGateOuter, 5, Material.PACKED_MUD, 0.15D, registry);
        road(out, world, "military-citadel", gateToward(world, military, citadel),
                eastGateOuter, 5, Material.MOSSY_COBBLESTONE, -0.17D, registry);

        Location bossApproachOuter = point(world, boss.x(),
                bossApproachWalkY(boss.baseY() + 1, 24), boss.z() - 74);
        road(out, world, "citadel-boss", southGateOuter, bossApproachOuter, 7,
                Material.POLISHED_ANDESITE, 0.0D, registry);

        // The approach and the northern rim used to stop twelve blocks apart. Register a real
        // graded connector so the descent from the citadel reaches the rim without a raw hole.
        Location rimStart = point(world, boss.x() + 3,
                boss.baseY() + 1, boss.z() - 62);
        road(out, world, "boss-approach-rim-connector", bossApproachOuter, rimStart, 7,
                Material.DEEPSLATE_TILES, 0.0D, registry);

        // Three strict-grade outside-rim segments stay outside the arena and never dip/rise
        // between equal-height endpoints.
        Location northRim = point(world, boss.x() + 58,
                boss.baseY() + 1, boss.z() - 62);
        Location flank = point(world, boss.x() + 74, boss.baseY() + 1, boss.z() + 8);
        road(out, world, "boss-portal-north-rim", rimStart, northRim, 6,
                Material.DEEPSLATE_TILES, -0.10D, registry);
        road(out, world, "boss-portal-east-rim", northRim, flank, 6,
                Material.DEEPSLATE_TILES, -0.10D, registry);
        road(out, world, "boss-portal-sanctuary", flank,
                point(world, portal.x(), portal.baseY() + 1, portal.z() - 28), 6,
                Material.DEEPSLATE_TILES, -0.12D, registry);
        report.roads = 9;
        return out;
    }

    static List<BlockEdit> polishCriticalFortressZones(World world, Site citadel, Site boss,
                                                        Location ritualGate,
                                                        OverworldCampaignAudit148.Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        polishBossNorthApproach(out, world, boss, ritualGate, registry);
        furnishCitadelCourtyard(out, world, citadel, registry);
        return out;
    }

    static List<BlockEdit> reinforceBossWallFoundation(World world, Site boss) {
        List<BlockEdit> out = new ArrayList<>();
        int topY = boss.baseY();
        int bottom = Math.max(world.getMinHeight() + 2, topY - WALL_FOUNDATION_DEPTH - 4);
        // Run after every arena, road and decoration phase. This final foundation belt cannot
        // be erased by later terrain work and guarantees the exact radius checked by audit.
        for (int radius = 48; radius <= 51; radius++) {
            for (int degree = 0; degree < 360; degree++) {
                double angle = Math.toRadians(degree);
                int x = boss.x() + (int) Math.round(Math.cos(angle) * radius);
                int z = boss.z() + (int) Math.round(Math.sin(angle) * radius);
                for (int y = topY; y >= bottom; y--) {
                    Material material = y >= topY - 4
                            ? Material.DEEPSLATE_BRICKS : Material.COBBLESTONE;
                    out.add(new BlockEdit(x, y, z, material));
                }
            }
        }
        return out;
    }

    /**
     * Rebuilds the complete monumental north stair after every gatehouse, tower and wall phase.
     * This is intentionally callable from the final build pass and from auto-repair, because
     * masonry integration may otherwise refill the registered walking volume with cobblestone.
     */
    static List<BlockEdit> restoreBossNorthMonumentalStairs(
            World world, Site boss, Location ritualGate,
            OverworldCampaignAudit148.Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        polishBossNorthApproach(out, world, boss, ritualGate, registry);
        return out;
    }

    private static void polishBossNorthApproach(List<BlockEdit> out, World world, Site boss,
                                                Location ritualGate,
                                                OverworldCampaignAudit148.Registry registry) {
        int cx = ritualGate.getBlockX();
        int gateY = ritualGate.getBlockY();
        int cz = ritualGate.getBlockZ();
        int roadY = bossApproachWalkY(gateY, 24);
        int startZ = cz - 24;
        int endZ = cz + 8;
        int halfWidth = 5;
        registry.clearRoute("boss-north-approach");
        registry.clearRoute("boss-north-monumental-stairs");

        // Remove only the temporary wooden obstruction in the ceremonial axis. Gate towers
        // sit outside this protected central strip and are restored again by the last phase.
        for (int x = cx - 10; x <= cx + 10; x++) {
            for (int z = startZ - 2; z <= endZ + 2; z++) {
                for (int y = roadY - 2; y <= gateY + 7; y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    String name = type.name();
                    if (name.contains("PLANK") || name.contains("WOOD")
                            || name.contains("LOG") || name.contains("STAIRS")
                            || name.contains("SLAB") || name.contains("FENCE")) {
                        out.add(new BlockEdit(x, y, z, Material.AIR));
                    }
                }
            }
        }

        for (int z = startZ; z <= endZ; z++) {
            int progress = z - startZ;
            int walkY = z <= cz ? roadY + Math.min(gateY - roadY, progress / 6) : gateY;
            for (int x = cx - halfWidth; x <= cx + halfWidth; x++) {
                Material floor = Math.floorMod(x * 7 + z * 11, 17) < 3
                        ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_ANDESITE;
                supportColumn(out, world, x, walkY, z, 7, Material.STONE_BRICKS);
                out.add(new BlockEdit(x, walkY - 1, z, floor));
                for (int y = walkY; y <= walkY + 6; y++) {
                    out.add(new BlockEdit(x, y, z, Material.AIR));
                }
                registry.registerAuthoritativeRoadCell("boss-north-approach", x, walkY, z, floor);
            }

            for (int direction : new int[]{-1, 1}) {
                for (int shoulder = 1; shoulder <= 3; shoulder++) {
                    int x = cx + direction * (halfWidth + shoulder);
                    int natural = terrainY(world, x, z);
                    int target = walkY - 1 - Math.min(1, shoulder - 1);
                    if (natural < target) {
                        fillColumn(out, world, x, target, z, shoulder == 1
                                ? Material.STONE_BRICKS : Material.ANDESITE, false);
                    } else if (natural > target + 2 && shoulder == 1) {
                        for (int y = target; y <= Math.min(natural, target + 4); y++) {
                            out.add(new BlockEdit(x, y, z, Material.STONE_BRICKS));
                        }
                    }
                }
            }
        }

        finishBossApproachCut(out, world, cx, gateY, cz, startZ, roadY, halfWidth);

        // Add a low safety rail only over an actual drop. Continuous rails on both sides made
        // the approach look like a thin wall laid over the terrain instead of a usable road.
        for (int z = startZ; z <= cz + 4; z++) {
            int progress = z - startZ;
            int walkY = z <= cz ? roadY + Math.min(gateY - roadY, progress / 6) : gateY;
            for (int side : new int[]{-1, 1}) {
                int edgeX = cx + side * (halfWidth + 1);
                int outsideY = terrainY(world, edgeX + side * 2, z);
                out.add(new BlockEdit(edgeX, walkY - 1, z, Material.STONE_BRICKS));
                if (outsideY < walkY - 2) {
                    out.add(new BlockEdit(edgeX, walkY, z, Material.STONE_BRICK_WALL));
                } else {
                    out.add(new BlockEdit(edgeX, walkY, z, Material.AIR));
                }
            }
        }
    }

    /**
     * Opens and dresses the terrain cut in front of the boss gate. The old corridor cleared only
     * six blocks above its floor, leaving a dirt ceiling across the monumental stairs and dark
     * pockets beside the towers. This pass opens the approach to the surface and turns the exposed
     * banks into compact stone retaining walls without creating another giant platform.
     */
    private static void finishBossApproachCut(List<BlockEdit> out, World world,
                                               int cx, int gateY, int gateZ,
                                               int startZ, int roadY, int halfWidth) {
        for (int z = startZ - 2; z <= gateZ - 3; z++) {
            int progress = Math.max(0, z - startZ);
            int walkY = roadY + Math.min(gateY - roadY, progress / 6);
            int highestBank = walkY + 9;
            for (int x = cx - halfWidth; x <= cx + halfWidth; x++) {
                highestBank = Math.max(highestBank, Math.min(walkY + 20, terrainY(world, x, z) + 2));
            }
            for (int x = cx - halfWidth; x <= cx + halfWidth; x++) {
                for (int y = walkY; y <= highestBank; y++) {
                    Material current = world.getBlockAt(x, y, z).getType();
                    if (current.isAir() || isTerrainLike(current)
                            || current == Material.COBBLESTONE
                            || current == Material.MOSSY_COBBLESTONE) {
                        out.add(new BlockEdit(x, y, z, Material.AIR));
                    }
                }
            }
            for (int side : new int[]{-1, 1}) {
                int wallX = cx + side * (halfWidth + 1);
                int bankY = Math.min(walkY + 7, Math.max(walkY + 2,
                        terrainY(world, wallX + side, z)));
                for (int y = walkY - 1; y <= bankY; y++) {
                    Material wall = Math.floorMod(wallX * 11 + y * 7 + z * 13, 17) < 3
                            ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
                    out.add(new BlockEdit(wallX, y, z, wall));
                }
            }
            if (Math.floorMod(z - startZ, 6) == 2) {
                out.add(new BlockEdit(cx, walkY + 4, z, Material.LIGHT,
                        "minecraft:light[level=14,waterlogged=false]"));
            }
        }
    }

    private static void polishBossPortalRimJunction(List<BlockEdit> out, World world, Site boss,
                                                     OverworldCampaignAudit148.Registry registry) {
        int y = boss.baseY() + 1;
        int z = boss.z() - 62;
        // "boss-north-approach" actually lands at (boss.x(), y dipped up to 3 blocks by
        // bossApproachWalkY, boss.z() - 74) — three blocks west, twelve blocks north and up
        // to three blocks lower than where this strip starts. Nothing ever shaped that
        // stretch, leaving an uncovered patch of raw terrain exactly at the junction.
        // Bridge it explicitly with a short ramp before the flat strip below.
        int approachEndY = bossApproachWalkY(y, 24);
        int bridgeSteps = 14;
        for (int step = 0; step <= bridgeSteps; step++) {
            double t = step / (double) bridgeSteps;
            int cx = boss.x() + (int) Math.round(3.0D * t);
            int cz = boss.z() - 74 + (int) Math.round(12.0D * t);
            int cy = approachEndY + (int) Math.round((y - approachEndY) * t);
            for (int lateral = -3; lateral <= 3; lateral++) {
                int bx = cx + lateral;
                supportedPathCell(out, world, bx, cy, cz, Material.DEEPSLATE_TILES);
                for (int yy = cy; yy <= cy + 5; yy++) {
                    out.add(new BlockEdit(bx, yy, cz, Material.AIR));
                }
                registry.registerAuthoritativeRoadCell("boss-portal-north-rim", bx, cy, cz,
                        Material.DEEPSLATE_TILES);
            }
        }
        for (int x = boss.x() + 3; x <= boss.x() + 60; x++) {
            for (int lateral = -3; lateral <= 3; lateral++) {
                int wz = z + lateral;
                supportedPathCell(out, world, x, y, wz, Material.DEEPSLATE_TILES);
                for (int yy = y; yy <= y + 5; yy++) {
                    out.add(new BlockEdit(x, yy, wz, Material.AIR));
                }
                registry.registerAuthoritativeRoadCell("boss-portal-north-rim", x, y, wz,
                        Material.DEEPSLATE_TILES);
            }
            if (Math.floorMod(x, 8) == 0) {
                for (int side : new int[]{-1, 1}) {
                    int wz = z + side * 5;
                    out.add(new BlockEdit(x, y, wz, Material.POLISHED_DEEPSLATE_WALL));
                    out.add(new BlockEdit(x, y + 1, wz, Material.SOUL_LANTERN));
                }
            }
        }
        // A broad, paved turning apron keeps the route outside the solid gatehouse towers.
        for (int x = boss.x() - 5; x <= boss.x() + 10; x++) {
            for (int wz = z - 4; wz <= z + 6; wz++) {
                supportedPathCell(out, world, x, y, wz,
                        Math.floorMod(x + wz, 10) == 0
                                ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                for (int yy = y; yy <= y + 5; yy++) out.add(new BlockEdit(x, yy, wz, Material.AIR));
            }
        }
    }

    private static void furnishCitadelCourtyard(List<BlockEdit> out, World world, Site citadel,
                                                OverworldCampaignAudit148.Registry registry) {
        int cx = citadel.x();
        int y = citadel.baseY() + 1;
        int cz = citadel.z();
        int bypassX = cx + 25;
        registry.clearRoute("citadel-central-court");

        // 1.48.18 registered a straight route through the great hall. The house shell
        // correctly restored its walls afterwards, so the auditor always saw a blocked road
        // and the same phase also erased roughly twenty-five pieces of furniture. The new
        // route is a continuous, seven-wide fortified bypass around the east side.
        paveRegisteredCitadelSegment(out, world, registry, "citadel-central-court",
                cx, y, cz - 56, cx, cz - 32, 2);
        paveRegisteredCitadelSegment(out, world, registry, "citadel-central-court",
                cx, y, cz - 32, bypassX, cz - 32, 2);
        paveRegisteredCitadelSegment(out, world, registry, "citadel-central-court",
                bypassX, y, cz - 32, bypassX, cz + 32, 2);
        paveRegisteredCitadelSegment(out, world, registry, "citadel-central-court",
                bypassX, y, cz + 32, cx, cz + 32, 2);
        // Join the great-hall front path without entering its footprint.
        paveRegisteredCitadelSegment(out, world, registry, "citadel-central-court",
                cx, y, cz + 20, cx, cz + 32, 2);
        paveRegisteredCitadelSegment(out, world, registry, "citadel-central-court",
                cx, y, cz + 32, cx, cz + 56, 2);

        // Two occupied side courts reduce the oversized empty plaza without touching the
        // great hall, the bypass or any of the four audited corner towers.
        for (int sideX : new int[]{-1, 1}) {
            int ox = cx + (sideX < 0 ? -35 : 42);
            int oz = cz;
            for (int x = ox - 7; x <= ox + 7; x++) {
                for (int z = oz - 6; z <= oz + 6; z++) {
                    Material floor = Math.floorMod(x * 5 + z * 3, 9) == 0
                            ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE;
                    supportedPathCell(out, world, x, y, z, floor);
                }
            }
            for (int dx : new int[]{-6, 6}) {
                out.add(new BlockEdit(ox + dx, y, oz - 5, Material.SPRUCE_LOG));
                out.add(new BlockEdit(ox + dx, y + 1, oz - 5, Material.SPRUCE_FENCE));
                out.add(new BlockEdit(ox + dx, y + 2, oz - 5, Material.LANTERN));
            }
            for (int dz = -3; dz <= 3; dz += 3) {
                out.add(new BlockEdit(ox + sideX * 5, y, oz + dz, Material.BARREL));
                out.add(new BlockEdit(ox + sideX * 4, y, oz + dz, Material.SPRUCE_TRAPDOOR));
            }
            if (sideX < 0) {
                for (int dx = -4; dx <= 4; dx += 4) {
                    out.add(new BlockEdit(ox + dx, y, oz + 4, Material.IRON_BARS));
                    out.add(new BlockEdit(ox + dx, y + 1, oz + 4, Material.WHITE_WOOL));
                }
            } else {
                out.add(new BlockEdit(ox, y, oz, Material.SMITHING_TABLE));
                out.add(new BlockEdit(ox + 2, y, oz, Material.ANVIL));
                out.add(new BlockEdit(ox - 2, y, oz, Material.CHEST));
            }
        }
    }

    private static void paveRegisteredCitadelSegment(List<BlockEdit> out, World world,
                                                      OverworldCampaignAudit148.Registry registry,
                                                      String id, int x1, int walkY, int z1,
                                                      int x2, int z2, int halfWidth) {
        int dx = Integer.compare(x2, x1);
        int dz = Integer.compare(z2, z1);
        int length = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        int sideX = -dz;
        int sideZ = dx;
        for (int step = 0; step <= length; step++) {
            int centerX = x1 + dx * step;
            int centerZ = z1 + dz * step;
            for (int side = -halfWidth; side <= halfWidth; side++) {
                int x = centerX + sideX * side;
                int z = centerZ + sideZ * side;
                Material floor = Math.floorMod(step * 7 + side * 11, 13) == 0
                        ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_ANDESITE;
                supportedPathCell(out, world, x, walkY, z, floor);
                for (int yy = walkY; yy <= walkY + 4; yy++) {
                    out.add(new BlockEdit(x, yy, z, Material.AIR));
                }
                registry.registerRoadCell(id, x, walkY, z, floor);
            }
            if (step > 3 && step < length - 3 && step % 9 == 0) {
                for (int side : new int[]{-halfWidth - 2, halfWidth + 2}) {
                    int x = centerX + sideX * side;
                    int z = centerZ + sideZ * side;
                    out.add(new BlockEdit(x, walkY, z, Material.STONE_BRICK_WALL));
                    out.add(new BlockEdit(x, walkY + 1, z, Material.LANTERN));
                }
            }
        }
    }

    private static void sealCitadelTowerCorridors(List<BlockEdit> out, World world, Site citadel,
                                                  OverworldCampaignAudit148.Registry registry) {
        int cx = citadel.x();
        int y = citadel.baseY() + 1;
        int cz = citadel.z();
        int[][] gates = {
                {cx, cz - 55, 0, 1},
                {cx, cz + 55, 0, 1},
                {cx - 55, cz + 10, 1, 0},
                {cx + 55, cz + 10, 1, 0}
        };
        for (int[] gate : gates) {
            int gx = gate[0], gz = gate[1], alongX = gate[2], alongZ = gate[3];
            int sideX = -alongZ;
            int sideZ = alongX;
            String id = "citadel-gate-polish-" + gx + "-" + gz;
            registry.clearRoute(id);
            for (int forward = -8; forward <= 8; forward++) {
                for (int lateral = -4; lateral <= 4; lateral++) {
                    int x = gx + alongX * forward + sideX * lateral;
                    int z = gz + alongZ * forward + sideZ * lateral;
                    Material floor = Math.floorMod(forward * 5 + lateral * 7, 13) < 2
                            ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
                    supportedPathCell(out, world, x, y, z, floor);
                    for (int yy = y; yy <= y + 5; yy++) {
                        out.add(new BlockEdit(x, yy, z, Material.AIR));
                    }
                    registry.registerRoadCell(id, x, y, z, floor);
                }
                // Low rails only where the apron is above the natural terrain. The former
                // four-block-solid side walls boxed the approach and looked like duplicate ramps.
                for (int direction : new int[]{-1, 1}) {
                    int x = gx + alongX * forward + sideX * direction * 5;
                    int z = gz + alongZ * forward + sideZ * direction * 5;
                    supportedPathCell(out, world, x, y, z, Material.STONE_BRICKS);
                    if (terrainY(world, x, z) < y - 2) {
                        out.add(new BlockEdit(x, y, z, Material.STONE_BRICK_WALL));
                    }
                }
                if (forward % 6 == 0) {
                    out.add(new BlockEdit(gx + alongX * forward, y + 4,
                            gz + alongZ * forward, Material.LIGHT,
                            "minecraft:light[level=13,waterlogged=false]"));
                }
            }
        }
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
        int[] xs = new int[steps + 1];
        int[] zs = new int[steps + 1];
        int[] naturals = new int[steps + 1];
        int[] grades = new int[steps + 1];
        int startY = start.getBlockY();
        int endY = end.getBlockY();

        boolean strictGrade = id.endsWith("-citadel") || id.equals("citadel-boss") || id.startsWith("boss-");
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            double bend = Math.sin(Math.PI * t) * curve * Math.min(75.0D, length * 0.17D);
            xs[step] = (int) Math.round(start.getX() + dx * t + nx * bend);
            zs[step] = (int) Math.round(start.getZ() + dz * t + nz * bend);
            naturals[step] = terrainY(world, xs[step], zs[step]) + 1;
            if (strictGrade) {
                // Start from the real terrain, then enforce one monotonic direction below.
                // A purely linear grade hovered over shallow valleys and produced the long
                // retaining-wall roads visible beside the fortress.
                grades[step] = Math.max(Math.min(startY, endY),
                        Math.min(Math.max(startY, endY), naturals[step]));
            } else {
                int lower = Math.max(startY - step, endY - (steps - step));
                int upper = Math.min(startY + step, endY + (steps - step));
                grades[step] = Math.max(lower, Math.min(upper, naturals[step]));
            }
        }
        grades[0] = startY;
        grades[steps] = endY;
        if (strictGrade) {
            applyMonotonicTerrainGrade(grades, naturals, startY, endY);
        } else {
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 1; i <= steps; i++) {
                    grades[i] = Math.max(grades[i - 1] - 1,
                            Math.min(grades[i - 1] + 1, grades[i]));
                }
                grades[steps] = endY;
                for (int i = steps - 1; i >= 0; i--) {
                    grades[i] = Math.max(grades[i + 1] - 1,
                            Math.min(grades[i + 1] + 1, grades[i]));
                }
                grades[0] = startY;
            }
        }

        registry.clearRoute(id);
        int halfWidth = Math.max(1, width / 2);
        for (int step = 0; step <= steps; step++) {
            int cx = xs[step];
            int cz = zs[step];
            int walkY = grades[step];
            int natural = naturals[step];
            boolean reinforced = wet(world, cx, cz) || natural < walkY - 2;
            for (int ox = -halfWidth; ox <= halfWidth; ox++) {
                for (int oz = -halfWidth; oz <= halfWidth; oz++) {
                    if (ox * ox + oz * oz > halfWidth * halfWidth + halfWidth) continue;
                    int x = cx + ox;
                    int z = cz + oz;
                    Material floor = reinforced
                            ? (path == Material.DIRT_PATH ? Material.STONE_BRICKS : path)
                            : Math.floorMod(step + ox * 3 + oz * 5, 9) == 0
                            ? secondary(path) : path;
                    out.add(new BlockEdit(x, walkY - 1, z, floor));
                    for (int y = walkY; y <= walkY + 3; y++) {
                        out.add(new BlockEdit(x, y, z, Material.AIR));
                    }
                    registry.registerRoadCell(id, x, walkY, z, floor);
                    support(out, world, x, walkY - 2, z,
                            reinforced ? Material.STONE_BRICKS : Material.COBBLESTONE);
                }
            }
            if (Math.abs(natural - walkY) > 1) {
                shapeRoadEmbankment(out, world, cx, walkY, cz, nx, nz, halfWidth, path);
            }
            if (step > 0 && step % 18 == 0) {
                int lx = (int) Math.round(cx + nx * (halfWidth + 2));
                int lz = (int) Math.round(cz + nz * (halfWidth + 2));
                lamp(out, lx, walkY, lz);
            }
        }
    }

    private static void applyMonotonicTerrainGrade(int[] grades, int[] naturals,
                                                    int startY, int endY) {
        int steps = grades.length - 1;
        if (steps <= 0) return;
        if (endY >= startY) {
            grades[0] = startY;
            for (int i = 1; i < steps; i++) {
                int terrainTarget = Math.max(startY, Math.min(endY, naturals[i]));
                int mustReach = endY - (steps - i);
                int target = Math.max(grades[i - 1], Math.max(terrainTarget, mustReach));
                grades[i] = Math.min(grades[i - 1] + 1, target);
            }
            grades[steps] = endY;
            for (int i = steps - 1; i > 0; i--) {
                grades[i] = Math.max(grades[i + 1] - 1,
                        Math.min(grades[i], grades[i + 1]));
            }
        } else {
            grades[0] = startY;
            for (int i = 1; i < steps; i++) {
                int terrainTarget = Math.max(endY, Math.min(startY, naturals[i]));
                int mustReach = endY + (steps - i);
                int target = Math.min(grades[i - 1], Math.min(terrainTarget, mustReach));
                grades[i] = Math.max(grades[i - 1] - 1, target);
            }
            grades[steps] = endY;
            for (int i = steps - 1; i > 0; i--) {
                grades[i] = Math.min(grades[i + 1] + 1,
                        Math.max(grades[i], grades[i + 1]));
            }
        }
    }

    private static void shapeRoadEmbankment(List<BlockEdit> out, World world,
                                             int cx, int walkY, int cz,
                                             double nx, double nz, int halfWidth,
                                             Material path) {
        for (int direction : new int[]{-1, 1}) {
            for (int shoulder = 1; shoulder <= 3; shoulder++) {
                int x = (int) Math.round(cx + nx * direction * (halfWidth + shoulder));
                int z = (int) Math.round(cz + nz * direction * (halfWidth + shoulder));
                int natural = terrainY(world, x, z);
                int target = walkY - 1 - Math.min(2, shoulder - 1);
                if (natural < target) {
                    Material surface = shoulder == 1
                            ? (path == Material.DIRT_PATH ? Material.COBBLESTONE : secondary(path))
                            : shoulder == 2 ? Material.ANDESITE : Material.STONE;
                    fillColumn(out, world, x, target, z, surface, false);
                }
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
