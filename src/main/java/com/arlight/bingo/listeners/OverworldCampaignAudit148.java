package com.arlight.bingo.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;

/** Structural checks that prevent a visually broken 1.48.7 template from becoming READY. */
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

    record TowerSpec(String id, int cx, int y, int cz, int radius, int height,
                     Facing front) { }

    record ChimneySpec(String id, int x, int baseY, int topY, int z) { }

    record PlanterSpec(String id, int x, int y, int z, Material flower) { }

    record TerrainSpec(Site site, int flatRadius, int blendRadius) { }

    enum FortificationShape { SQUARE, CIRCLE }

    record FortificationSpec(String id, int cx, int baseY, int cz, int radius,
                             FortificationShape shape) { }

    record RoadCell(String id, int x, int walkY, int z, Material floor) { }

    private record RoadColumn(int x, int z) { }

    record Summary(int houses, int towers, int chimneys, int planters, int roadSamples,
                   int villageLights, int interiorLights, int crops,
                   int unsupportedLanterns) { }

    static final class Registry {
        private final List<HouseSpec> houses = new ArrayList<>();
        private final List<TowerSpec> towers = new ArrayList<>();
        private final List<ChimneySpec> chimneys = new ArrayList<>();
        private final List<PlanterSpec> planters = new ArrayList<>();
        private final List<TerrainSpec> terrain = new ArrayList<>();
        private final List<FortificationSpec> fortifications = new ArrayList<>();
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

        void registerPlanter(PlanterSpec candidate) {
            planters.add(candidate);
        }

        void registerTerrain(Site site, int flatRadius, int blendRadius) {
            terrain.add(new TerrainSpec(site, flatRadius, blendRadius));
        }

        void registerFortification(FortificationSpec fortification) {
            fortifications.add(fortification);
        }

        boolean blocksHouseOrEntrance(int x, int y, int z) {
            for (HouseSpec house : houses) {
                if (y < house.y - 2 || y > house.y + house.height + 8) continue;
                int dx = x - house.cx;
                int dz = z - house.cz;
                if (Math.abs(dx) <= house.hx + 2 && Math.abs(dz) <= house.hz + 2) {
                    return true;
                }
                int radius = frontRadius(house);
                int forward = dx * house.front.dx + dz * house.front.dz;
                int lateral = Math.abs(dx * house.front.dz - dz * house.front.dx);
                if (forward >= radius && forward <= radius + 7 && lateral <= 3) {
                    return true;
                }
            }
            for (TowerSpec tower : towers) {
                if (y < tower.y - 2 || y > tower.y + tower.height + 5) continue;
                int dx = x - tower.cx;
                int dz = z - tower.cz;
                if (Math.abs(dx) <= tower.radius + 2
                        && Math.abs(dz) <= tower.radius + 2) return true;
                int forward = dx * tower.front.dx + dz * tower.front.dz;
                int lateral = Math.abs(dx * tower.front.dz - dz * tower.front.dx);
                if (forward >= tower.radius && forward <= tower.radius + 7
                        && lateral <= 3) return true;
            }
            return false;
        }

        boolean blocksTerrainAudit(int x, int z) {
            for (HouseSpec house : houses) {
                if (Math.abs(x - house.cx) <= house.hx + 4
                        && Math.abs(z - house.cz) <= house.hz + 4) return true;
            }
            for (TowerSpec tower : towers) {
                if (Math.abs(x - tower.cx) <= tower.radius + 9
                        && Math.abs(z - tower.cz) <= tower.radius + 9) return true;
            }
            for (FortificationSpec wall : fortifications) {
                int dx = Math.abs(x - wall.cx);
                int dz = Math.abs(z - wall.cz);
                if (wall.shape == FortificationShape.SQUARE) {
                    if (dx <= wall.radius + 5 && dz <= wall.radius + 5
                            && (Math.abs(dx - wall.radius) <= 5
                            || Math.abs(dz - wall.radius) <= 5)) return true;
                } else {
                    double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (Math.abs(distance - wall.radius) <= 6.0D) return true;
                }
            }
            if (roadCells.containsKey(new RoadColumn(x, z))) return true;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (roadCells.containsKey(new RoadColumn(x + dx, z + dz))) return true;
            }
            return false;
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
        for (PlanterSpec planter : registry.planters) auditPlanter(world, planter, failures);
        for (TerrainSpec terrain : registry.terrain) {
            auditTerrainBlend(world, registry, terrain, failures);
        }
        for (FortificationSpec fortification : registry.fortifications) {
            auditFortificationFoundation(world, fortification, failures);
        }
        Map<String, RoadCell> brokenRoads = new LinkedHashMap<>();
        for (RoadCell cell : registry.roadCells.values()) {
            if (!isWalkable(world, cell.x, cell.walkY, cell.z)) {
                brokenRoads.putIfAbsent(cell.id, cell);
            }
        }
        for (Map.Entry<String, RoadCell> broken : brokenRoads.entrySet()) {
            RoadCell cell = broken.getValue();
            Material floor = world.getBlockAt(cell.x, cell.walkY - 1, cell.z).getType();
            Material passage = world.getBlockAt(cell.x, cell.walkY, cell.z).getType();
            Material head = world.getBlockAt(cell.x, cell.walkY + 1, cell.z).getType();
            failures.add("camino cortado: " + broken.getKey()
                    + " (x=" + cell.x + ", y=" + cell.walkY + ", z=" + cell.z
                    + ", suelo=" + floor + ", paso=" + passage
                    + ", cabeza=" + head + ")");
        }

        int crops = countCrops(world, village, village.radius() - 4);
        if (crops > 0) failures.add("quedaron " + crops + " bloques de cultivo en el pueblo");

        int[] light = auditVillageLights(world, village, failures);
        if (light[0] < 18) failures.add("iluminación insuficiente en el pueblo: " + light[0]);

        auditRitualApproach(world, outerAltar, ritualGate, failures);
        auditFountain(world, village, failures);
        auditBellPavilion(world, village, failures);
        auditArenaTerraces(world, ritualGate, failures);

        if (!failures.isEmpty()) {
            int limit = Math.min(12, failures.size());
            throw new IllegalStateException("Auditoría estructural 1.48.7 rechazada: "
                    + String.join("; ", failures.subList(0, limit)));
        }
        int interiorLights = countRegisteredInteriorLights(world, registry);
        return new Summary(registry.houses.size(), registry.towers.size(),
                registry.chimneys.size(), registry.planters.size(),
                registry.roadCells.size(), light[0],
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

    /**
     * Replays every registered private entrance after roads, fortifications and decoration.
     * Step zero is the real doorway and therefore receives a door instead of AIR; the
     * remaining cells are the supported outdoor landing. Keeping this separate from the
     * public-road replay prevents a late road brush from deleting a valid door.
     */
    static List<BlockEdit> repairRegisteredEntrances(Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        for (HouseSpec house : registry.houses) {
            replayEntrance(out, house.cx, house.y, house.cz, frontRadius(house),
                    house.front, 1, 2, Material.COBBLESTONE);
        }
        for (TowerSpec tower : registry.towers) {
            replayEntrance(out, tower.cx, tower.y, tower.cz, tower.radius,
                    tower.front, 2, 3, Material.STONE_BRICKS);
        }
        return out;
    }

    /**
     * Replays the complete vertical route of every registered tower after all other edits.
     * The arena's west gate shares a dense boundary with roads, the gatehouse and the arena
     * wall; restoring both the ladder and its backing here makes the final world independent
     * of which of those structures wrote the same column earlier.
     */
    static List<BlockEdit> repairRegisteredVerticalAccess(Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        for (TowerSpec tower : registry.towers) {
            int localLadderZ = Math.min(2, tower.radius - 2);
            int ladderX = tower.cx + tower.radius - 1;
            int ladderZ = tower.cz + localLadderZ;
            for (int offset = 0; offset < tower.height; offset++) {
                Material backing = ((tower.radius + localLadderZ + offset) & 11) == 0
                        ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
                out.add(new BlockEdit(ladderX + 1, tower.y + offset, ladderZ, backing));
                out.add(new BlockEdit(ladderX, tower.y + offset, ladderZ, Material.LADDER,
                        "minecraft:ladder[facing=west,waterlogged=false]"));
            }
            for (int level = 6; level < tower.height; level += 6) {
                // The solid cell immediately west of the shaft is the actual dismount.
                out.add(new BlockEdit(ladderX - 1, tower.y + level, ladderZ,
                        Material.DEEPSLATE_TILES));
                out.add(new BlockEdit(ladderX - 1, tower.y + level + 1, ladderZ,
                        Material.AIR));
            }
        }
        return out;
    }

    private static void replayEntrance(List<BlockEdit> out, int cx, int y, int cz,
                                       int radius, Facing front, int landingHalfWidth,
                                       int clearance, Material floor) {
        int sideX = -front.dz;
        int sideZ = front.dx;
        for (int step = 0; step <= 5; step++) {
            int sideRadius = step == 0 ? 0 : landingHalfWidth;
            for (int side = -sideRadius; side <= sideRadius; side++) {
                int x = cx + front.dx * (radius + step) + sideX * side;
                int z = cz + front.dz * (radius + step) + sideZ * side;
                out.add(new BlockEdit(x, y - 1, z,
                        Math.floorMod(step + side, 7) == 0
                                ? Material.MOSSY_STONE_BRICKS : floor));
                if (step > 0) {
                    for (int yy = 0; yy <= clearance; yy++) {
                        out.add(new BlockEdit(x, y + yy, z, Material.AIR));
                    }
                }
            }
        }
        int doorX = cx + front.dx * radius;
        int doorZ = cz + front.dz * radius;
        String direction = front.name().toLowerCase(java.util.Locale.ROOT);
        out.add(new BlockEdit(doorX, y, doorZ, Material.SPRUCE_DOOR,
                "minecraft:spruce_door[facing=" + direction
                        + ",half=lower,hinge=left,open=false,powered=false]"));
        out.add(new BlockEdit(doorX, y + 1, doorZ, Material.SPRUCE_DOOR,
                "minecraft:spruce_door[facing=" + direction
                        + ",half=upper,hinge=left,open=false,powered=false]"));
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
                    if (type == Material.GLASS_PANE) {
                        String state = world.getBlockAt(house.cx + x, house.y + yy,
                                house.cz + z).getBlockData().getAsString();
                        boolean xWall = Math.abs(x) == house.hx;
                        boolean connected = xWall
                                ? state.contains("north=true") && state.contains("south=true")
                                : state.contains("east=true") && state.contains("west=true");
                        if (!connected) {
                            failures.add("panel de cristal desconectado en " + house.id);
                            return;
                        }
                    }
                }
            }
        }

        int failuresBeforeFoundation = failures.size();
        auditRectangularFoundation(world, house.id, house.cx, house.y - 1, house.cz,
                house.hx + 1, house.hz + 1, failures);
        if (failures.size() > failuresBeforeFoundation) return;

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
                int floorY = house.y + floor * 5;
                for (int x = -house.hx + 1; x <= house.hx - 1; x++) {
                    for (int z = -house.hz + 1; z <= house.hz - 1; z++) {
                        boolean shaft = house.cx + x == ladderX
                                && house.cz + z == ladderZ;
                        if (!shaft && !world.getBlockAt(house.cx + x, floorY,
                                house.cz + z).getType().isSolid()) {
                            failures.add("piso intermedio abierto en " + house.id);
                            return;
                        }
                    }
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
        int failuresBeforeFoundation = failures.size();
        auditRectangularFoundation(world, tower.id, tower.cx, tower.y - 1, tower.cz,
                tower.radius + 1, tower.radius + 1, failures);
        if (failures.size() > failuresBeforeFoundation) return;
        int doorX = tower.cx + tower.front.dx * tower.radius;
        int doorZ = tower.cz + tower.front.dz * tower.radius;
        if (world.getBlockAt(doorX, tower.y, doorZ).getType() != Material.SPRUCE_DOOR
                || world.getBlockAt(doorX, tower.y + 1, doorZ).getType()
                != Material.SPRUCE_DOOR) {
            failures.add("torre sin puerta real: " + tower.id);
            return;
        }
        int sideX = -tower.front.dz;
        int sideZ = tower.front.dx;
        // Step zero is the already-validated closed door. Only the outdoor
        // landing must be empty; treating the door as AIR rejected every valid tower.
        for (int step = 1; step <= 5; step++) {
            int sideRadius = 2;
            for (int side = -sideRadius; side <= sideRadius; side++) {
                int x = tower.cx + tower.front.dx * (tower.radius + step) + sideX * side;
                int z = tower.cz + tower.front.dz * (tower.radius + step) + sideZ * side;
                if (!world.getBlockAt(x, tower.y - 1, z).getType().isSolid()
                        || world.getBlockAt(x, tower.y, z).getType().isSolid()
                        || world.getBlockAt(x, tower.y + 1, z).getType().isSolid()) {
                    failures.add("entrada de torre enterrada o sin descansillo: " + tower.id);
                    return;
                }
            }
        }
        int ladderZ = tower.cz + Math.min(2, tower.radius - 2);
        for (int y = tower.y; y < tower.y + tower.height; y++) {
            Material ladder = world.getBlockAt(tower.cx + tower.radius - 1,
                    y, ladderZ).getType();
            Material backing = world.getBlockAt(tower.cx + tower.radius,
                    y, ladderZ).getType();
            if (ladder != Material.LADDER || !backing.isSolid()) {
                failures.add("torre inaccesible por escalera: " + tower.id
                        + " (y=" + y + ", escalera=" + ladder
                        + ", respaldo=" + backing + ")");
                return;
            }
        }
        for (int level = 6; level < tower.height; level += 6) {
            int ladderX = tower.cx + tower.radius - 1;
            boolean centralFloor = world.getBlockAt(tower.cx,
                    tower.y + level, tower.cz).getType().isSolid();
            boolean dismount = world.getBlockAt(ladderX - 1,
                    tower.y + level, ladderZ).getType().isSolid()
                    && !world.getBlockAt(ladderX - 1,
                    tower.y + level + 1, ladderZ).getType().isSolid();
            if (!centralFloor || !dismount) {
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

    private static void auditPlanter(World world, PlanterSpec planter,
                                     List<String> failures) {
        Material flower = world.getBlockAt(planter.x, planter.y, planter.z).getType();
        Material support = world.getBlockAt(planter.x, planter.y - 1, planter.z).getType();
        if (flower != planter.flower) {
            failures.add("maceta ausente o reemplazada en " + planter.id);
            return;
        }
        if (support != Material.SPRUCE_PLANKS || !support.isSolid()) {
            failures.add("maceta flotante en " + planter.id
                    + " (soporte=" + support + ")");
        }
    }

    private static void auditTerrainBlend(World world, Registry registry, TerrainSpec spec,
                                          List<String> failures) {
        Site site = spec.site;
        for (int degree = 0; degree < 360; degree += 15) {
            double angle = Math.toRadians(degree);
            Integer previous = null;
            for (int radius = Math.max(1, spec.flatRadius - 2);
                 radius <= spec.blendRadius + 18; radius++) {
                int x = site.x() + (int) Math.round(Math.cos(angle) * radius);
                int z = site.z() + (int) Math.round(Math.sin(angle) * radius);
                if (registry.blocksTerrainAudit(x, z)) {
                    previous = null;
                    continue;
                }
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

    private static void auditRectangularFoundation(World world, String id, int cx, int topY,
                                                   int cz, int hx, int hz,
                                                   List<String> failures) {
        for (int x = -hx; x <= hx; x += 2) {
            if (!supportedDepth(world, cx + x, topY, cz - hz, 12)
                    || !supportedDepth(world, cx + x, topY, cz + hz, 12)) {
                failures.add("cimiento de edificio flotante en " + id);
                return;
            }
        }
        for (int z = -hz; z <= hz; z += 2) {
            if (!supportedDepth(world, cx - hx, topY, cz + z, 12)
                    || !supportedDepth(world, cx + hx, topY, cz + z, 12)) {
                failures.add("cimiento de edificio flotante en " + id);
                return;
            }
        }
        if (!supportedDepth(world, cx, topY, cz, 12)) {
            failures.add("cimiento de edificio flotante en " + id);
        }
    }

    private static void auditFortificationFoundation(World world, FortificationSpec wall,
                                                      List<String> failures) {
        if (wall.shape == FortificationShape.SQUARE) {
            for (int offset = -wall.radius; offset <= wall.radius; offset++) {
                if (!supportedDepth(world, wall.cx + offset, wall.baseY - 1,
                        wall.cz - wall.radius, OverworldCampaignTerrain148.WALL_FOUNDATION_DEPTH)
                        || !supportedDepth(world, wall.cx + offset, wall.baseY - 1,
                        wall.cz + wall.radius, OverworldCampaignTerrain148.WALL_FOUNDATION_DEPTH)
                        || !supportedDepth(world, wall.cx - wall.radius, wall.baseY - 1,
                        wall.cz + offset, OverworldCampaignTerrain148.WALL_FOUNDATION_DEPTH)
                        || !supportedDepth(world, wall.cx + wall.radius, wall.baseY - 1,
                        wall.cz + offset, OverworldCampaignTerrain148.WALL_FOUNDATION_DEPTH)) {
                    failures.add("cimiento de muralla flotante en " + wall.id
                            + " (offset=" + offset + ")");
                    return;
                }
            }
            return;
        }
        for (int degree = 0; degree < 360; degree += 2) {
            double angle = Math.toRadians(degree);
            int x = wall.cx + (int) Math.round(Math.cos(angle) * wall.radius);
            int z = wall.cz + (int) Math.round(Math.sin(angle) * wall.radius);
            if (!supportedDepth(world, x, wall.baseY - 1, z,
                    OverworldCampaignTerrain148.WALL_FOUNDATION_DEPTH)) {
                failures.add("cimiento de muralla flotante en " + wall.id
                        + " (x=" + x + ", z=" + z + ")");
                return;
            }
        }
    }

    private static boolean supportedDepth(World world, int x, int topY, int z, int depth) {
        for (int y = topY; y > topY - depth; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (!type.isSolid() || type == Material.WATER || type == Material.LAVA) return false;
        }
        return true;
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

    private static void auditBellPavilion(World world, Site village,
                                          List<String> failures) {
        int y = village.baseY() + 3;
        int bellX = village.x() + 11;
        if (world.getBlockAt(bellX, y + 2, village.z()).getType() != Material.BELL) {
            failures.add("campana ausente en el pabellón del pueblo");
            return;
        }
        for (int chainY = y + 3; chainY <= y + 8; chainY++) {
            if (world.getBlockAt(bellX, chainY, village.z()).getType() != Material.CHAIN) {
                failures.add("campana sin cadena continua en el pabellón del pueblo");
                return;
            }
        }
        for (int[] point : new int[][]{{-3,-3},{3,-3},{-3,3},{3,3}}) {
            for (int supportY = y + 1; supportY <= y + 5; supportY++) {
                if (world.getBlockAt(bellX + point[0], supportY,
                        village.z() + point[1]).getType() != Material.SPRUCE_FENCE) {
                    failures.add("pabellón de campana sin soporte continuo");
                    return;
                }
            }
        }
        if (world.getBlockAt(bellX, y + 9, village.z()).getType()
                != Material.DARK_OAK_SLAB) {
            failures.add("pabellón de campana sin remate de tejado");
        }
    }

    private static void auditArenaTerraces(World world, Location ritualGate,
                                           List<String> failures) {
        int cx = ritualGate.getBlockX();
        int y = ritualGate.getBlockY();
        int cz = ritualGate.getBlockZ() + 50;
        for (int[] sample : new int[][]{
                {35, 0, 0}, {-35, 0, 0}, {0, 35, 0},
                {36, 15, 1}, {-36, 15, 1}, {15, 36, 1},
                {43, 0, 2}, {-43, 0, 2}, {0, 43, 2}}) {
            int x = cx + sample[0];
            int z = cz + sample[1];
            int topY = y + sample[2];
            if (!world.getBlockAt(x, topY, z).getType().isSolid()
                    || world.getBlockAt(x, topY + 1, z).getType().isSolid()) {
                failures.add("gradas cortadas o sin espacio en la arena");
                return;
            }
        }
        int aisleZ = cz - 35;
        if (!world.getBlockAt(cx, y - 1, aisleZ).getType().isSolid()
                || world.getBlockAt(cx, y, aisleZ).getType().isSolid()
                || world.getBlockAt(cx, y + 1, aisleZ).getType().isSolid()) {
            failures.add("gradas bloquean el pasillo ceremonial norte");
        }
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
