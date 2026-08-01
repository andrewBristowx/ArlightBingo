package com.arlight.bingo.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;

/** Structural checks that prevent a visually broken 1.48.2 template from becoming READY. */
final class OverworldCampaignAudit148 {
    enum Facing {
        NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

        final int dx;
        final int dz;

        Facing(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    record HouseSpec(String id, int cx, int y, int cz, int hx, int hz,
                     int height, boolean roofAlongX, Facing front) { }

    record TowerSpec(String id, int cx, int y, int cz, int radius, int height) { }

    record ChimneySpec(String id, int x, int baseY, int topY, int z) { }

    record TerrainSpec(Site site, int flatRadius, int blendRadius) { }

    record RoadCell(String id, int x, int walkY, int z, Material floor) { }

    private record RoadColumn(int x, int z) { }

    record Summary(int houses, int towers, int chimneys, int roadSamples,
                   int villageLights, int interiorLights, int crops,
                   int unsupportedLanterns) { }

    static final class Registry {
        private final List<HouseSpec> houses = new ArrayList<>();
        private final List<TowerSpec> towers = new ArrayList<>();
        private final List<ChimneySpec> chimneys = new ArrayList<>();
        private final List<TerrainSpec> terrain = new ArrayList<>();
        private final Map<RoadColumn, RoadCell> roadCells = new LinkedHashMap<>();

        void registerHouse(HouseSpec candidate) {
            for (HouseSpec placed : houses) {
                boolean verticalOverlap = candidate.y <= placed.y + placed.height + 16
                        && placed.y <= candidate.y + candidate.height + 16;
                boolean horizontalOverlap = Math.abs(candidate.cx - placed.cx)
                        <= candidate.hx + placed.hx + 3
                        && Math.abs(candidate.cz - placed.cz)
                        <= candidate.hz + placed.hz + 3;
                if (verticalOverlap && horizontalOverlap) {
                    throw new IllegalStateException("Edificios superpuestos: "
                            + placed.id + " y " + candidate.id);
                }
            }
            houses.add(candidate);
        }

        void registerTower(TowerSpec candidate) {
            towers.add(candidate);
        }

        void registerChimney(ChimneySpec candidate) {
            chimneys.add(candidate);
        }

        void registerTerrain(Site site, int flatRadius, int blendRadius) {
            terrain.add(new TerrainSpec(site, flatRadius, blendRadius));
        }

        void registerRoadCell(String id, int x, int walkY, int z, Material floor) {
            roadCells.put(new RoadColumn(x, z), new RoadCell(id, x, walkY, z, floor));
        }
    }

    private record Pos(int x, int y, int z) { }

    private OverworldCampaignAudit148() { }

    static void validatePhase(String phase, List<BlockEdit> edits) {
        Map<Pos, BlockEdit> finalBlocks = new HashMap<>();
        for (BlockEdit edit : edits) {
            if (isCrop(edit.material())) {
                throw new IllegalStateException("La fase '" + phase
                        + "' intentó volver a generar cultivos en "
                        + edit.x() + "," + edit.y() + "," + edit.z());
            }
            if (edit.data() != null) {
                try {
                    Bukkit.createBlockData(edit.data());
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalStateException("Datos de bloque inválidos en la fase '"
                            + phase + "': " + edit.data(), invalid);
                }
            }
            finalBlocks.put(new Pos(edit.x(), edit.y(), edit.z()), edit);
        }
        for (Map.Entry<Pos, BlockEdit> entry : finalBlocks.entrySet()) {
            Material material = entry.getValue().material();
            Pos at = entry.getKey();
            if (isLantern(material)) {
                BlockEdit below = finalBlocks.get(new Pos(at.x, at.y - 1, at.z));
                BlockEdit above = finalBlocks.get(new Pos(at.x, at.y + 1, at.z));
                if (!supportsLantern(below == null ? null : below.material())
                        && !supportsLantern(above == null ? null : above.material())) {
                    throw new IllegalStateException("Farol sin soporte en la fase '" + phase
                            + "': " + at.x + "," + at.y + "," + at.z);
                }
            }
            if (material == Material.LADDER) {
                BlockEdit support = finalBlocks.get(new Pos(at.x + 1, at.y, at.z));
                if (entry.getValue().data() == null
                        || !entry.getValue().data().contains("facing=west")
                        || support == null || !supportsLantern(support.material())) {
                    throw new IllegalStateException("Escalera sin respaldo real en la fase '"
                            + phase + "': " + at.x + "," + at.y + "," + at.z);
                }
            }
        }
    }

    static Summary auditWorld(World world, Registry registry, Site village,
                              Site citadel, Site portal, Location outerAltar,
                              Location ritualGate) {
        List<String> failures = new ArrayList<>();
        for (HouseSpec house : registry.houses) auditHouse(world, house, failures);
        for (TowerSpec tower : registry.towers) auditTower(world, tower, failures);
        for (ChimneySpec chimney : registry.chimneys) auditChimney(world, chimney, failures);
        for (TerrainSpec terrain : registry.terrain) auditTerrainBlend(world, terrain, failures);
        Set<String> brokenRoads = new LinkedHashSet<>();
        for (RoadCell cell : registry.roadCells.values()) {
            if (!isWalkable(world, cell.x, cell.walkY, cell.z)) brokenRoads.add(cell.id);
        }
        for (String road : brokenRoads) failures.add("camino cortado: " + road);

        int crops = countCrops(world, village, village.radius() - 4);
        if (crops > 0) failures.add("quedaron " + crops + " bloques de cultivo en el pueblo");

        int[] light = auditVillageLights(world, village, failures);
        if (light[0] < 18) failures.add("iluminación insuficiente en el pueblo: " + light[0]);

        auditFoundation(world, citadel, 55, failures);
        auditFoundation(world, portal, 24, failures);
        auditRitualApproach(world, outerAltar, ritualGate, failures);
        auditFountain(world, village, failures);

        if (!failures.isEmpty()) {
            int limit = Math.min(12, failures.size());
            throw new IllegalStateException("Auditoría estructural 1.48.2 rechazada: "
                    + String.join("; ", failures.subList(0, limit)));
        }
        int interiorLights = countRegisteredInteriorLights(world, registry);
        return new Summary(registry.houses.size(), registry.towers.size(),
                registry.chimneys.size(), registry.roadCells.size(), light[0],
                interiorLights, crops, light[1]);
    }

    /**
     * Roads are planned before buildings so terrain height never sees roofs. Fortifications,
     * gates and decoration are intentionally built afterwards, so the registered road cells
     * are replayed once at the end to keep every declared corridor open at its audited height.
     */
    static List<BlockEdit> repairRoadCorridors(Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        for (RoadCell cell : registry.roadCells.values()) {
            out.add(new BlockEdit(cell.x, cell.walkY - 1, cell.z, cell.floor));
            for (int y = cell.walkY; y <= cell.walkY + 3; y++) {
                out.add(new BlockEdit(cell.x, y, cell.z, Material.AIR));
            }
        }
        return out;
    }

    private static void auditHouse(World world, HouseSpec house, List<String> failures) {
        int roofY = house.y + house.height + 1;
        int roofHx = house.hx + 2;
        int roofHz = house.hz + 2;
        if (house.roofAlongX) {
            for (int z = -roofHz; z <= roofHz; z++) {
                int expectedY = roofY + roofHz - Math.abs(z);
                for (int x = -roofHx; x <= roofHx; x++) {
                    if (!solidCover(world.getBlockAt(house.cx + x, expectedY,
                            house.cz + z).getType())) {
                        failures.add("tejado abierto en " + house.id);
                        return;
                    }
                }
            }
            for (int side : new int[]{-house.hx, house.hx}) {
                for (int z = -house.hz; z <= house.hz; z++) {
                    int top = roofY + roofHz - Math.abs(z);
                    for (int y = roofY; y < top; y++) {
                        if (!solidCover(world.getBlockAt(house.cx + side, y,
                                house.cz + z).getType())) {
                            failures.add("frontón abierto en " + house.id);
                            return;
                        }
                    }
                }
            }
        } else {
            for (int x = -roofHx; x <= roofHx; x++) {
                int expectedY = roofY + roofHx - Math.abs(x);
                for (int z = -roofHz; z <= roofHz; z++) {
                    if (!solidCover(world.getBlockAt(house.cx + x, expectedY,
                            house.cz + z).getType())) {
                        failures.add("tejado abierto en " + house.id);
                        return;
                    }
                }
            }
            for (int side : new int[]{-house.hz, house.hz}) {
                for (int x = -house.hx; x <= house.hx; x++) {
                    int top = roofY + roofHx - Math.abs(x);
                    for (int y = roofY; y < top; y++) {
                        if (!solidCover(world.getBlockAt(house.cx + x, y,
                                house.cz + side).getType())) {
                            failures.add("frontón abierto en " + house.id);
                            return;
                        }
                    }
                }
            }
        }

        int radius = frontRadius(house);
        int doorX = house.cx + house.front.dx * radius;
        int doorZ = house.cz + house.front.dz * radius;
        if (world.getBlockAt(doorX, house.y, doorZ).getType() != Material.SPRUCE_DOOR
                || world.getBlockAt(doorX, house.y + 1, doorZ).getType()
                != Material.SPRUCE_DOOR) {
            failures.add("puerta ausente o incompleta en " + house.id);
            return;
        }
        for (int x = -house.hx; x <= house.hx; x++) {
            for (int z = -house.hz; z <= house.hz; z++) {
                if (Math.abs(x) != house.hx && Math.abs(z) != house.hz) continue;
                for (int yy = 0; yy <= house.height; yy++) {
                    Material type = world.getBlockAt(house.cx + x, house.y + yy,
                            house.cz + z).getType();
                    if (!wallCover(type)) {
                        failures.add("pared abierta en " + house.id);
                        return;
                    }
                }
            }
        }

        int floors = Math.max(1, house.height / 5);
        if (floors > 1) {
            int ladderX = house.cx + house.hx - 1;
            int ladderZ = house.cz + Math.min(2, house.hz - 2);
            for (int y = house.y; y <= house.y + (floors - 1) * 5 + 1; y++) {
                if (world.getBlockAt(ladderX, y, ladderZ).getType() != Material.LADDER
                        || !world.getBlockAt(ladderX + 1, y, ladderZ).getType().isSolid()) {
                    failures.add("escalera cortada o sin respaldo en " + house.id);
                    return;
                }
            }
            for (int floor = 1; floor < floors; floor++) {
                if (!world.getBlockAt(ladderX - 1, house.y + floor * 5,
                        ladderZ).getType().isSolid()) {
                    failures.add("escalera sin desembarco en " + house.id);
                    return;
                }
            }
        }
        int houseLights = countHouseLights(world, house);
        if (houseLights < floors * 4) {
            failures.add("interior oscuro en " + house.id + ": " + houseLights + " luces");
            return;
        }

        // Steps 0..5 are the private front path generated by the house. Step 6
        // must already be supplied by a public street, which rejects fake paths
        // that stop a few blocks beyond the door.
        for (int step = 0; step <= 6; step++) {
            int x = house.cx + house.front.dx * (frontRadius(house) + step);
            int z = house.cz + house.front.dz * (frontRadius(house) + step);
            if (!world.getBlockAt(x, house.y - 1, z).getType().isSolid()
                    || blocksPassage(world.getBlockAt(x, house.y, z).getType())
                    || blocksPassage(world.getBlockAt(x, house.y + 1, z).getType())) {
                failures.add("entrada bloqueada o sin camino en " + house.id);
                return;
            }
        }
    }

    private static void auditTower(World world, TowerSpec tower, List<String> failures) {
        if (world.getBlockAt(tower.cx, tower.y, tower.cz + tower.radius).getType()
                != Material.SPRUCE_DOOR
                || world.getBlockAt(tower.cx, tower.y + 1,
                tower.cz + tower.radius).getType() != Material.SPRUCE_DOOR) {
            failures.add("torre sin puerta real: " + tower.id);
            return;
        }
        int ladderZ = tower.cz + Math.min(2, tower.radius - 2);
        for (int y = tower.y; y < tower.y + tower.height; y++) {
            if (world.getBlockAt(tower.cx + tower.radius - 1, y, ladderZ).getType()
                    != Material.LADDER
                    || !world.getBlockAt(tower.cx + tower.radius, y, ladderZ)
                    .getType().isSolid()) {
                failures.add("torre inaccesible por escalera: " + tower.id);
                return;
            }
        }
        for (int level = 6; level < tower.height; level += 6) {
            if (!world.getBlockAt(tower.cx, tower.y + level, tower.cz).getType().isSolid()) {
                failures.add("torre sin piso intermedio: " + tower.id);
                return;
            }
        }
        int lights = 0;
        for (int level = 0; level < tower.height; level += 6) {
            int lightY = tower.y + Math.min(tower.height - 1, level + 3);
            for (int side : new int[]{-1, 1}) {
                if (world.getBlockAt(tower.cx + side * Math.max(1, tower.radius / 2),
                        lightY, tower.cz).getType() == Material.LIGHT) lights++;
            }
        }
        if (lights < Math.max(2, ((tower.height + 5) / 6) * 2)) {
            failures.add("torre oscura: " + tower.id);
        }
    }

    private static void auditChimney(World world, ChimneySpec chimney,
                                     List<String> failures) {
        if (world.getBlockAt(chimney.x, chimney.baseY, chimney.z).getType()
                != Material.SMOKER) {
            failures.add("chimenea sin hogar apoyado en " + chimney.id);
            return;
        }
        for (int y = chimney.baseY + 1; y <= chimney.topY; y++) {
            if (world.getBlockAt(chimney.x, y, chimney.z).getType()
                    != Material.BRICKS) {
                failures.add("chimenea cortada o flotante en " + chimney.id);
                return;
            }
        }
        if (world.getBlockAt(chimney.x, chimney.topY + 1, chimney.z).getType()
                != Material.CAMPFIRE) {
            failures.add("chimenea sin remate en " + chimney.id);
        }
    }

    private static void auditTerrainBlend(World world, TerrainSpec spec,
                                          List<String> failures) {
        Site site = spec.site;
        for (int degree = 0; degree < 360; degree += 15) {
            double angle = Math.toRadians(degree);
            Integer previous = null;
            for (int radius = Math.max(1, spec.flatRadius - 2);
                 radius <= spec.blendRadius + 18; radius++) {
                int x = site.x() + (int) Math.round(Math.cos(angle) * radius);
                int z = site.z() + (int) Math.round(Math.sin(angle) * radius);
                int current = OverworldCampaignTerrain148.terrainY(world, x, z);
                if (previous != null && Math.abs(current - previous) > 7) {
                    failures.add("corte abrupto del terreno en " + site.id());
                    return;
                }
                previous = current;
            }
        }
    }

    private static int countHouseLights(World world, HouseSpec house) {
        int lights = 0;
        int floors = Math.max(1, house.height / 5);
        int lightX = Math.max(2, house.hx / 2);
        int lightZ = Math.max(2, house.hz / 2);
        for (int floor = 0; floor < floors; floor++) {
            int y = house.y + (floor + 1) * 5 - 2;
            for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
                if (world.getBlockAt(house.cx + sx * lightX, y,
                        house.cz + sz * lightZ).getType() == Material.LIGHT) lights++;
            }
        }
        return lights;
    }

    private static int countRegisteredInteriorLights(World world, Registry registry) {
        int lights = 0;
        for (HouseSpec house : registry.houses) lights += countHouseLights(world, house);
        for (TowerSpec tower : registry.towers) {
            for (int level = 0; level < tower.height; level += 6) {
                int y = tower.y + Math.min(tower.height - 1, level + 3);
                for (int side : new int[]{-1, 1}) {
                    if (world.getBlockAt(tower.cx + side * Math.max(1, tower.radius / 2),
                            y, tower.cz).getType() == Material.LIGHT) lights++;
                }
            }
        }
        return lights;
    }

    private static boolean isWalkable(World world, int x, int walkY, int z) {
        Material floor = world.getBlockAt(x, walkY - 1, z).getType();
        if (floor.isAir() || floor == Material.WATER || floor == Material.LAVA
                || world.getBlockAt(x, walkY, z).getType().isSolid()
                || world.getBlockAt(x, walkY + 1, z).getType().isSolid()) return false;
        return true;
    }

    private static boolean blocksPassage(Material material) {
        return material.isSolid() && material != Material.SPRUCE_DOOR;
    }

    private static int countCrops(World world, Site site, int radius) {
        int found = 0;
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (x * x + z * z > radius * radius) continue;
            for (int y = site.baseY() - 4; y <= site.baseY() + 12; y++) {
                if (isCrop(world.getBlockAt(site.x() + x, y, site.z() + z).getType())) found++;
            }
        }
        return found;
    }

    private static int[] auditVillageLights(World world, Site site, List<String> failures) {
        int lights = 0;
        int unsupported = 0;
        int radius = site.radius() - 5;
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (x * x + z * z > radius * radius) continue;
            for (int y = site.baseY() - 3; y <= site.baseY() + 32; y++) {
                Material type = world.getBlockAt(site.x() + x, y, site.z() + z).getType();
                if (!isLantern(type) && type != Material.SEA_LANTERN) continue;
                lights++;
                Material below = world.getBlockAt(site.x() + x, y - 1, site.z() + z).getType();
                Material above = world.getBlockAt(site.x() + x, y + 1, site.z() + z).getType();
                if (!supportsLantern(below) && !supportsLantern(above)) unsupported++;
            }
        }
        if (unsupported > 0) failures.add("faroles sin soporte en el pueblo: " + unsupported);
        return new int[]{lights, unsupported};
    }

    private static void auditFoundation(World world, Site site, int radius,
                                        List<String> failures) {
        for (int degree = 0; degree < 360; degree += 30) {
            double angle = Math.toRadians(degree);
            int x = site.x() + (int) Math.round(Math.cos(angle) * radius);
            int z = site.z() + (int) Math.round(Math.sin(angle) * radius);
            int airRun = 0;
            for (int y = site.baseY(); y >= site.baseY() - 18; y--) {
                Material type = world.getBlockAt(x, y, z).getType();
                if (type.isAir() || type == Material.WATER) airRun++;
                else airRun = 0;
                if (airRun >= 3) {
                    failures.add("cimiento flotante en " + site.id());
                    return;
                }
            }
        }
    }

    private static void auditRitualApproach(World world, Location altar, Location gate,
                                            List<String> failures) {
        int steps = Math.max(1, Math.abs(gate.getBlockZ() - altar.getBlockZ()));
        for (int step = 3; step < steps - 2; step += 3) {
            double t = step / (double) steps;
            int x = (int) Math.round(altar.getX() + (gate.getX() - altar.getX()) * t);
            int z = (int) Math.round(altar.getZ() + (gate.getZ() - altar.getZ()) * t);
            int y = (int) Math.round(altar.getY() + (gate.getY() - altar.getY()) * t);
            if (!world.getBlockAt(x, y - 1, z).getType().isSolid()
                    || world.getBlockAt(x, y, z).getType().isSolid()
                    || world.getBlockAt(x, y + 1, z).getType().isSolid()) {
                failures.add("entrada ritual enterrada o interrumpida");
                return;
            }
        }
    }

    private static void auditFountain(World world, Site village, List<String> failures) {
        int y = village.baseY() + 3;
        int water = 0;
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
            Material type = world.getBlockAt(village.x() + x, y, village.z() + z).getType();
            if (type == Material.WATER) {
                if (Math.max(Math.abs(x), Math.abs(z)) > 3) {
                    failures.add("agua de la fuente fuera del vaso");
                    return;
                }
                water++;
            }
        }
        if (water < 20) failures.add("fuente incompleta o vacía");
    }

    private static int frontRadius(HouseSpec house) {
        return house.front == Facing.NORTH || house.front == Facing.SOUTH
                ? house.hz : house.hx;
    }

    private static boolean supportsLantern(Material material) {
        return material != null && !material.isAir()
                && material != Material.WATER && material != Material.LAVA;
    }

    private static boolean solidCover(Material material) {
        return wallCover(material);
    }

    private static boolean wallCover(Material material) {
        return material != null && (material.isSolid()
                || material == Material.GLASS_PANE || material == Material.IRON_BARS
                || material == Material.SPRUCE_DOOR);
    }

    private static boolean isLantern(Material material) {
        return material == Material.LANTERN || material == Material.SOUL_LANTERN;
    }

    private static boolean isCrop(Material material) {
        return material == Material.FARMLAND || material == Material.WHEAT
                || material == Material.CARROTS || material == Material.POTATOES
                || material == Material.BEETROOTS || material == Material.MELON_STEM
                || material == Material.ATTACHED_MELON_STEM
                || material == Material.PUMPKIN_STEM
                || material == Material.ATTACHED_PUMPKIN_STEM
                || material == Material.SWEET_BERRY_BUSH;
    }
}
