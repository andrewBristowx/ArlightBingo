package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Mantiene el pueblo inicial como zona realmente segura y ejecuta la pasada
 * geométrica de ArlightBingo 1.44.0 sobre una revisión Overworld recién creada.
 *
 * <p>La reparación no toca revisiones antiguas al instalar el JAR. Solo se inicia
 * cuando el marcador final de la plantilla fue escrito durante la ejecución
 * actual, o cuando existe un marcador de reparación interrumpida. De esta forma
 * 1.43.0 continúa disponible como respaldo sin ser modificado.</p>
 */
public final class OverworldVillageSafety implements Listener {
    private static final String MARKER = "arlight-overworld-template.properties";
    private static final String REPAIR_PROGRESS = "arlight-overworld-layout-1.44.0.in-progress";
    private static final String REPAIR_DONE = "arlight-overworld-layout-1.44.0.done";
    private static final String REPAIR_REPORT = "arlight-overworld-layout-1.44.0-report.txt";

    private static final Set<Material> ROAD_SCAR_MATERIALS = Set.of(
            Material.DIRT_PATH,
            Material.COARSE_DIRT,
            Material.ROOTED_DIRT,
            Material.GRAVEL,
            Material.COBBLESTONE,
            Material.MOSSY_COBBLESTONE,
            Material.POLISHED_ANDESITE
    );

    private final BingoPlugin plugin;
    private final long startupEpochMillis = System.currentTimeMillis();
    private final Map<UUID, SafeZone> zones = new HashMap<>();
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private final Set<UUID> activeRepairs = new HashSet<>();

    public OverworldVillageSafety(BingoPlugin plugin) {
        this.plugin = plugin;
        long interval = Math.max(20L, plugin.getConfig().getLong(
                "template-worlds.overworld.safe-village.purge-interval-ticks", 40L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::maintenanceTick, interval, interval);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster)) return;
        SafeZone zone = safeZone(event.getLocation().getWorld());
        if (zone != null && zone.contains(event.getLocation().getX(), event.getLocation().getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID id = event.getWorld().getUID();
        zones.remove(id);
        retryAfter.remove(id);
        activeRepairs.remove(id);
    }

    private void maintenanceTick() {
        purgeLoadedVillages();
        for (World world : Bukkit.getWorlds()) startRepairIfEligible(world);
    }

    private void purgeLoadedVillages() {
        if (!plugin.getConfig().getBoolean("template-worlds.overworld.safe-village.enabled", true)) return;
        for (World world : Bukkit.getWorlds()) {
            SafeZone zone = safeZone(world);
            if (zone == null) continue;
            BoundingBox bounds = new BoundingBox(zone.x - zone.radius, world.getMinHeight(),
                    zone.z - zone.radius, zone.x + zone.radius, world.getMaxHeight(),
                    zone.z + zone.radius);
            for (Entity entity : world.getNearbyEntities(bounds)) {
                if (entity instanceof Monster && zone.contains(
                        entity.getLocation().getX(), entity.getLocation().getZ())) {
                    entity.remove();
                }
            }
        }
    }

    private void startRepairIfEligible(World world) {
        if (!plugin.getConfig().getBoolean(
                "template-worlds.overworld.layout-repair-1-44.enabled", true)) return;
        String templateName = plugin.getConfig().getString(
                "template-worlds.overworld.name", "bingo_template_overworld");
        if (!world.getName().equals(templateName)) return;
        if (activeRepairs.contains(world.getUID())) return;

        Path folder = world.getWorldFolder().toPath();
        Path marker = folder.resolve(MARKER);
        Path progress = folder.resolve(REPAIR_PROGRESS);
        Path done = folder.resolve(REPAIR_DONE);
        if (!Files.isRegularFile(marker) || Files.isRegularFile(done)) return;

        try {
            boolean interrupted = Files.isRegularFile(progress);
            long markerModified = Files.getLastModifiedTime(marker).toMillis();
            boolean completedThisRun = markerModified >= startupEpochMillis - 5_000L;
            if (!interrupted && !completedThisRun) return;

            Properties properties = readProperties(marker);
            double[] village = parseLocation(properties.getProperty("village", ""));
            if (village == null) return;

            Files.writeString(progress,
                    "version=1.44.0\nstarted=" + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            activeRepairs.add(world.getUID());
            LayoutRepairJob job = new LayoutRepairJob(world, folder,
                    (int) Math.round(village[0] - 3.0D),
                    (int) Math.round(village[1] - 1.0D),
                    (int) Math.round(village[2] - 15.0D));
            job.start();
            plugin.getLogger().info("ArlightBingo 1.44.0 inició la reparación geométrica de "
                    + world.getName() + ". La revisión 1.43.0 archivada no será modificada.");
        } catch (IOException | NumberFormatException exception) {
            plugin.getLogger().warning("No se pudo iniciar la reparación 1.44.0 de "
                    + world.getName() + ": " + exception.getMessage());
        }
    }

    private Properties readProperties(Path marker) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private double[] parseLocation(String raw) {
        String[] split = raw.split(",");
        if (split.length < 3) return null;
        return new double[]{
                Double.parseDouble(split[0].trim()),
                Double.parseDouble(split[1].trim()),
                Double.parseDouble(split[2].trim())
        };
    }

    private SafeZone safeZone(World world) {
        if (world == null || !plugin.getConfig().getBoolean(
                "template-worlds.overworld.safe-village.enabled", true)) return null;
        SafeZone known = zones.get(world.getUID());
        if (known != null) return known;
        long now = System.currentTimeMillis();
        if (retryAfter.getOrDefault(world.getUID(), 0L) > now) return null;

        Path marker = world.getWorldFolder().toPath().resolve(MARKER);
        if (!Files.isRegularFile(marker)) {
            retryAfter.put(world.getUID(), now + 10_000L);
            return null;
        }
        try {
            Properties properties = readProperties(marker);
            String[] village = properties.getProperty("village", "").split(",");
            if (village.length < 3 || !Boolean.parseBoolean(
                    properties.getProperty("villageSafe", "true"))) return null;
            double x = Double.parseDouble(village[0].trim()) + 0.5D;
            double z = Double.parseDouble(village[2].trim()) + 0.5D;
            double radius = Double.parseDouble(properties.getProperty("villageSafeRadius",
                    String.valueOf(plugin.getConfig().getInt(
                            "template-worlds.overworld.safe-village.radius", 112))));
            SafeZone loaded = new SafeZone(x, z, Math.max(48.0D, radius));
            zones.put(world.getUID(), loaded);
            return loaded;
        } catch (IOException | NumberFormatException ex) {
            retryAfter.put(world.getUID(), now + 30_000L);
            plugin.getLogger().warning("No se pudo leer la zona segura de " + world.getName()
                    + ": " + ex.getMessage());
            return null;
        }
    }

    private final class LayoutRepairJob {
        private final World world;
        private final Path folder;
        private final int originX;
        private final int baseY;
        private final int originZ;
        private final List<PlotSpec> plots;
        private final RepairReport report = new RepairReport();
        private BukkitTask task;
        private int cursor;

        LayoutRepairJob(World world, Path folder, int originX, int baseY, int originZ) {
            this.world = world;
            this.folder = folder;
            this.originX = originX;
            this.baseY = baseY;
            this.originZ = originZ;
            this.plots = List.of(
                    new PlotSpec("archivo-oeste", -42, -26, 12, 9, Direction.EAST, 0),
                    new PlotSpec("herrería-este", 42, -31, 13, 9, Direction.WEST, 1),
                    new PlotSpec("posada", -45, 37, 13, 11, Direction.NORTH, 2),
                    new PlotSpec("granero", 49, 39, 11, 9, Direction.NORTH, 3),
                    new PlotSpec("taller", 61, 8, 11, 9, Direction.NORTH, 4),
                    new PlotSpec("archivo-sur", -56, 49, 13, 11, Direction.NORTH, 5),
                    new PlotSpec("panadería", 7, 61, 11, 10, Direction.NORTH, 6),
                    new PlotSpec("torre-oeste", -18, 60, 6, 6, Direction.SOUTH, 7),
                    new PlotSpec("torre-este", 31, 63, 6, 6, Direction.SOUTH, 8)
            );
            detectOverlaps();
        }

        void start() {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
        }

        private void tick() {
            try {
                if (cursor < plots.size()) {
                    repairPlot(plots.get(cursor++));
                    return;
                }
                finish();
            } catch (Throwable error) {
                if (task != null) task.cancel();
                activeRepairs.remove(world.getUID());
                plugin.getLogger().severe("Falló la reparación geométrica 1.44.0 en "
                        + world.getName() + ": " + rootMessage(error));
            }
        }

        private void repairPlot(PlotSpec plot) {
            int cx = originX + plot.offsetX;
            int cz = originZ + plot.offsetZ;
            int floorY = locateFloorY(cx, cz, baseY + 1);

            supportAndDryPlot(cx, floorY, cz, plot.halfX, plot.halfZ);
            repairRoofScars(cx, floorY, cz, plot.halfX, plot.halfZ);
            clearEntrance(cx, floorY, cz, plot);
            connectEntrance(cx, floorY, cz, plot);
            addUniqueDetail(cx, floorY, cz, plot);
            ensureInteriorLight(cx, floorY, cz, plot);
            report.repairedPlots++;
        }

        private int locateFloorY(int cx, int cz, int expected) {
            List<Integer> candidates = new ArrayList<>();
            int[][] samples = {{0,0},{2,0},{-2,0},{0,2},{0,-2}};
            for (int[] sample : samples) {
                int candidate = closestWalkableFloor(cx + sample[0], cz + sample[1], expected);
                if (candidate != Integer.MIN_VALUE) candidates.add(candidate);
            }
            if (candidates.isEmpty()) return expected;
            candidates.sort(Comparator.naturalOrder());
            return candidates.get(candidates.size() / 2);
        }

        private int closestWalkableFloor(int x, int z, int expected) {
            int best = Integer.MIN_VALUE;
            int bestDistance = Integer.MAX_VALUE;
            int min = Math.max(world.getMinHeight() + 2, expected - 10);
            int max = Math.min(world.getMaxHeight() - 4, expected + 12);
            for (int y = min; y <= max; y++) {
                Block floor = world.getBlockAt(x, y, z);
                if (!floor.getType().isSolid()) continue;
                Block above = world.getBlockAt(x, y + 1, z);
                if (above.getType().isSolid()) continue;
                int distance = Math.abs(y - expected);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = y;
                }
            }
            return best;
        }

        private void supportAndDryPlot(int cx, int floorY, int cz, int halfX, int halfZ) {
            int maximumDepth = Math.max(6, plugin.getConfig().getInt(
                    "template-worlds.overworld.layout-repair-1-44.maximum-foundation-depth", 18));
            for (int x = cx - halfX; x <= cx + halfX; x++) {
                for (int z = cz - halfZ; z <= cz + halfZ; z++) {
                    boolean perimeter = x == cx - halfX || x == cx + halfX
                            || z == cz - halfZ || z == cz + halfZ;
                    for (int y = floorY - 1; y >= floorY - maximumDepth; y--) {
                        Block block = world.getBlockAt(x, y, z);
                        Material type = block.getType();
                        if (type.isSolid() && type != Material.ICE) break;
                        block.setType(foundationMaterial(x, y, z, perimeter), false);
                        report.foundationBlocks++;
                        if (type == Material.WATER || type == Material.LAVA) report.driedBlocks++;
                    }
                    for (int y = floorY; y <= floorY + 3; y++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() == Material.WATER || block.getType() == Material.LAVA) {
                            block.setType(y == floorY ? Material.COBBLESTONE : Material.AIR, false);
                            report.driedBlocks++;
                        }
                    }
                }
            }
        }

        private Material foundationMaterial(int x, int y, int z, boolean perimeter) {
            int pattern = Math.floorMod(x * 31 + y * 17 + z * 13, 11);
            if (perimeter && pattern == 0) return Material.MOSSY_STONE_BRICKS;
            if (perimeter) return Material.STONE_BRICKS;
            return pattern < 3 ? Material.COBBLESTONE : Material.STONE;
        }

        private void repairRoofScars(int cx, int floorY, int cz, int halfX, int halfZ) {
            for (int x = cx - halfX + 1; x <= cx + halfX - 1; x++) {
                for (int z = cz - halfZ + 1; z <= cz + halfZ - 1; z++) {
                    for (int y = floorY + 4; y <= floorY + 22; y++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (!ROAD_SCAR_MATERIALS.contains(block.getType())) continue;
                        if (world.getBlockAt(x, y + 1, z).getType().isSolid()) continue;
                        Material replacement = nearbyRoofMaterial(x, y, z);
                        if (replacement == null) continue;
                        block.setType(replacement, false);
                        report.roofScars++;
                    }
                }
            }
        }

        private Material nearbyRoofMaterial(int x, int y, int z) {
            Map<Material, Integer> counts = new HashMap<>();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Material type = world.getBlockAt(x + dx, y, z + dz).getType();
                    String name = type.name();
                    boolean roof = name.endsWith("_STAIRS") || name.endsWith("_SLAB")
                            || name.endsWith("_PLANKS") || name.endsWith("_TERRACOTTA")
                            || name.contains("BRICKS") || name.contains("TILES");
                    if (roof && !ROAD_SCAR_MATERIALS.contains(type)) {
                        counts.merge(type, 1, Integer::sum);
                    }
                }
            }
            return counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .filter(entry -> entry.getValue() >= 2)
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        private void clearEntrance(int cx, int floorY, int cz, PlotSpec plot) {
            int doorX = cx + plot.direction.dx * plot.halfX;
            int doorZ = cz + plot.direction.dz * plot.halfZ;
            for (int forward = -1; forward <= 2; forward++) {
                for (int side = -1; side <= 1; side++) {
                    int x = doorX + plot.direction.dx * forward + plot.direction.sideX() * side;
                    int z = doorZ + plot.direction.dz * forward + plot.direction.sideZ() * side;
                    for (int y = floorY + 1; y <= floorY + 3; y++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (isSafeToClear(block.getType())) block.setType(Material.AIR, false);
                    }
                }
            }
        }

        private boolean isSafeToClear(Material type) {
            if (type.isAir() || type == Material.WATER || type == Material.LAVA) return true;
            String name = type.name();
            return name.endsWith("_LEAVES") || name.endsWith("_VINES")
                    || name.endsWith("_GRASS") || name.endsWith("_FLOWER")
                    || name.endsWith("_SAPLING") || name.equals("SNOW")
                    || type == Material.COBWEB;
        }

        private void connectEntrance(int cx, int floorY, int cz, PlotSpec plot) {
            int startX = cx + plot.direction.dx * (plot.halfX + 1);
            int startZ = cz + plot.direction.dz * (plot.halfZ + 1);
            int length = 12 + plot.style % 4;
            int targetX = startX + plot.direction.dx * length;
            int targetZ = startZ + plot.direction.dz * length;
            int walkY = floorY + 1;
            Material path = switch (plot.style % 3) {
                case 1 -> Material.MOSSY_COBBLESTONE;
                case 2 -> Material.COARSE_DIRT;
                default -> Material.COBBLESTONE;
            };

            for (int step = 0; step <= length; step++) {
                int x = startX + plot.direction.dx * step;
                int z = startZ + plot.direction.dz * step;
                int naturalWalkY = walkableSurfaceNear(x, z, walkY);
                if (naturalWalkY > walkY + 1) walkY++;
                else if (naturalWalkY < walkY - 1) walkY--;
                else walkY = naturalWalkY;

                for (int side = -1; side <= 1; side++) {
                    int px = x + plot.direction.sideX() * side;
                    int pz = z + plot.direction.sideZ() * side;
                    if (insideOtherPlot(px, pz, plot)) continue;
                    Block floor = world.getBlockAt(px, walkY - 1, pz);
                    Material previous = floor.getType();
                    floor.setType(path, false);
                    if (previous == Material.WATER || previous == Material.LAVA) report.driedBlocks++;
                    for (int y = walkY; y <= walkY + 2; y++) {
                        Block block = world.getBlockAt(px, y, pz);
                        if (isSafeToClear(block.getType())) block.setType(Material.AIR, false);
                    }
                    supportPath(px, walkY - 2, pz);
                }

                if (step > 0 && step % 6 == 0) placePathLight(x, walkY, z, plot.direction, step);
                report.pathBlocks += 3;
            }

            int targetSurface = world.getHighestBlockYAt(targetX, targetZ,
                    HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (Math.abs(targetSurface - walkY) > 3) report.steepRoutes++;
        }

        private int walkableSurfaceNear(int x, int z, int expectedWalkY) {
            int min = Math.max(world.getMinHeight() + 2, expectedWalkY - 4);
            int max = Math.min(world.getMaxHeight() - 3, expectedWalkY + 4);
            int best = expectedWalkY;
            int bestDistance = Integer.MAX_VALUE;
            for (int walkY = min; walkY <= max; walkY++) {
                Material floor = world.getBlockAt(x, walkY - 1, z).getType();
                if (!floor.isSolid() || floor == Material.ICE) continue;
                if (world.getBlockAt(x, walkY, z).getType().isSolid()) continue;
                if (world.getBlockAt(x, walkY + 1, z).getType().isSolid()) continue;
                int distance = Math.abs(walkY - expectedWalkY);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = walkY;
                }
            }
            return best;
        }

        private void supportPath(int x, int startY, int z) {
            for (int depth = 0; depth < 8; depth++) {
                Block block = world.getBlockAt(x, startY - depth, z);
                if (block.getType().isSolid() && block.getType() != Material.ICE) return;
                block.setType(depth < 2 ? Material.COBBLESTONE : Material.STONE, false);
                report.foundationBlocks++;
            }
        }

        private void placePathLight(int x, int walkY, int z, Direction direction, int step) {
            int side = (step / 6) % 2 == 0 ? 2 : -2;
            int px = x + direction.sideX() * side;
            int pz = z + direction.sideZ() * side;
            if (insideAnyPlot(px, pz)) return;
            Block base = world.getBlockAt(px, walkY - 1, pz);
            if (!base.getType().isSolid()) base.setType(Material.COBBLESTONE, false);
            world.getBlockAt(px, walkY, pz).setType(Material.COBBLESTONE_WALL, false);
            world.getBlockAt(px, walkY + 1, pz).setType(Material.SPRUCE_FENCE, false);
            world.getBlockAt(px, walkY + 2, pz).setType(Material.LANTERN, false);
            report.lights++;
        }

        private boolean insideOtherPlot(int x, int z, PlotSpec current) {
            for (PlotSpec plot : plots) {
                if (plot == current) continue;
                int cx = originX + plot.offsetX;
                int cz = originZ + plot.offsetZ;
                if (Math.abs(x - cx) <= plot.halfX + 2
                        && Math.abs(z - cz) <= plot.halfZ + 2) return true;
            }
            return false;
        }

        private boolean insideAnyPlot(int x, int z) {
            for (PlotSpec plot : plots) {
                int cx = originX + plot.offsetX;
                int cz = originZ + plot.offsetZ;
                if (Math.abs(x - cx) <= plot.halfX + 2
                        && Math.abs(z - cz) <= plot.halfZ + 2) return true;
            }
            return false;
        }

        private void addUniqueDetail(int cx, int floorY, int cz, PlotSpec plot) {
            switch (plot.style % 4) {
                case 0 -> addChimney(cx, floorY, cz, plot);
                case 1 -> addEntranceAwning(cx, floorY, cz, plot);
                case 2 -> addSideGarden(cx, floorY, cz, plot);
                default -> addStorageAnnex(cx, floorY, cz, plot);
            }
        }

        private void addChimney(int cx, int floorY, int cz, PlotSpec plot) {
            int x = cx - plot.direction.sideX() * Math.max(2, plot.halfX - 3);
            int z = cz - plot.direction.sideZ() * Math.max(2, plot.halfZ - 3);
            int roofY = highestSolidNear(x, z, floorY + 5, floorY + 22);
            if (roofY < floorY + 4) return;
            for (int y = roofY + 1; y <= roofY + 4; y++) {
                world.getBlockAt(x, y, z).setType(y == roofY + 4
                        ? Material.CAMPFIRE : Material.BRICKS, false);
            }
            report.uniqueDetails++;
        }

        private void addEntranceAwning(int cx, int floorY, int cz, PlotSpec plot) {
            int doorX = cx + plot.direction.dx * (plot.halfX + 1);
            int doorZ = cz + plot.direction.dz * (plot.halfZ + 1);
            for (int side = -2; side <= 2; side++) {
                int x = doorX + plot.direction.sideX() * side;
                int z = doorZ + plot.direction.sideZ() * side;
                world.getBlockAt(x, floorY + 3, z).setType(Material.SPRUCE_SLAB, false);
            }
            world.getBlockAt(doorX + plot.direction.sideX() * 2, floorY + 1,
                    doorZ + plot.direction.sideZ() * 2).setType(Material.SPRUCE_FENCE, false);
            world.getBlockAt(doorX - plot.direction.sideX() * 2, floorY + 1,
                    doorZ - plot.direction.sideZ() * 2).setType(Material.SPRUCE_FENCE, false);
            report.uniqueDetails++;
        }

        private void addSideGarden(int cx, int floorY, int cz, PlotSpec plot) {
            int sideX = plot.direction.sideX();
            int sideZ = plot.direction.sideZ();
            int gx = cx + sideX * (plot.halfX + 3);
            int gz = cz + sideZ * (plot.halfZ + 3);
            for (int forward = -3; forward <= 3; forward++) {
                for (int side = -2; side <= 2; side++) {
                    int x = gx + plot.direction.dx * forward + sideX * side;
                    int z = gz + plot.direction.dz * forward + sideZ * side;
                    int y = walkableSurfaceNear(x, z, floorY + 1) - 1;
                    world.getBlockAt(x, y, z).setType(((forward + side) & 1) == 0
                            ? Material.MOSS_BLOCK : Material.COARSE_DIRT, false);
                    if ((forward + side) % 3 == 0) {
                        world.getBlockAt(x, y + 1, z).setType(Material.FERN, false);
                    }
                }
            }
            report.uniqueDetails++;
        }

        private void addStorageAnnex(int cx, int floorY, int cz, PlotSpec plot) {
            int sideX = -plot.direction.sideX();
            int sideZ = -plot.direction.sideZ();
            int ax = cx + sideX * (plot.halfX + 3);
            int az = cz + sideZ * (plot.halfZ + 3);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    world.getBlockAt(ax + dx, floorY, az + dz).setType(Material.SPRUCE_PLANKS, false);
                }
            }
            world.getBlockAt(ax - 1, floorY + 1, az).setType(Material.BARREL, false);
            world.getBlockAt(ax + 1, floorY + 1, az).setType(Material.HAY_BLOCK, false);
            world.getBlockAt(ax, floorY + 1, az + 1).setType(Material.CRAFTING_TABLE, false);
            report.uniqueDetails++;
        }

        private int highestSolidNear(int x, int z, int minY, int maxY) {
            int top = Integer.MIN_VALUE;
            for (int y = minY; y <= maxY; y++) {
                if (world.getBlockAt(x, y, z).getType().isSolid()) top = y;
            }
            return top;
        }

        private void ensureInteriorLight(int cx, int floorY, int cz, PlotSpec plot) {
            int[][] positions = {{0,0},{3,3},{-3,-3}};
            for (int[] position : positions) {
                int x = cx + Math.max(-plot.halfX + 2, Math.min(plot.halfX - 2, position[0]));
                int z = cz + Math.max(-plot.halfZ + 2, Math.min(plot.halfZ - 2, position[1]));
                Block block = world.getBlockAt(x, floorY + 3, z);
                if (block.getType().isAir()) {
                    block.setType(Material.LIGHT, false);
                    report.lights++;
                }
            }
        }

        private void detectOverlaps() {
            for (int first = 0; first < plots.size(); first++) {
                for (int second = first + 1; second < plots.size(); second++) {
                    PlotSpec a = plots.get(first);
                    PlotSpec b = plots.get(second);
                    int ax = originX + a.offsetX;
                    int az = originZ + a.offsetZ;
                    int bx = originX + b.offsetX;
                    int bz = originZ + b.offsetZ;
                    boolean overlapX = Math.abs(ax - bx) <= a.halfX + b.halfX + 4;
                    boolean overlapZ = Math.abs(az - bz) <= a.halfZ + b.halfZ + 4;
                    if (overlapX && overlapZ) {
                        report.overlaps.add(a.name + " <-> " + b.name);
                    }
                }
            }
        }

        private void finish() throws IOException {
            if (task != null) task.cancel();
            StringBuilder output = new StringBuilder();
            output.append("ARLIGHTBINGO 1.44.0 - INFORME DE REPARACIÓN GEOMÉTRICA\n")
                    .append("mundo=").append(world.getName()).append('\n')
                    .append("parcelas-reparadas=").append(report.repairedPlots).append('\n')
                    .append("bloques-cimiento=").append(report.foundationBlocks).append('\n')
                    .append("bloques-secados=").append(report.driedBlocks).append('\n')
                    .append("cicatrices-tejado=").append(report.roofScars).append('\n')
                    .append("bloques-camino=").append(report.pathBlocks).append('\n')
                    .append("luces=").append(report.lights).append('\n')
                    .append("detalles-unicos=").append(report.uniqueDetails).append('\n')
                    .append("rutas-pendiente-alta=").append(report.steepRoutes).append('\n')
                    .append("solapamientos-detectados=").append(report.overlaps.size()).append('\n');
            for (String overlap : report.overlaps) output.append("solapamiento=").append(overlap).append('\n');

            Files.writeString(folder.resolve(REPAIR_REPORT), output.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(folder.resolve(REPAIR_DONE),
                    "version=1.44.0\ncompleted=" + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(folder.resolve(REPAIR_PROGRESS));
            activeRepairs.remove(world.getUID());
            plugin.getLogger().info(String.format(Locale.ROOT,
                    "Reparación 1.44.0 completada en %s: %d parcelas, %d apoyos, %d bloques secados y %d cicatrices de tejado.",
                    world.getName(), report.repairedPlots, report.foundationBlocks,
                    report.driedBlocks, report.roofScars));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private enum Direction {
        NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

        final int dx;
        final int dz;

        Direction(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        int sideX() { return -dz; }
        int sideZ() { return dx; }
    }

    private record PlotSpec(String name, int offsetX, int offsetZ,
                            int halfX, int halfZ, Direction direction, int style) { }

    private static final class RepairReport {
        int repairedPlots;
        int foundationBlocks;
        int driedBlocks;
        int roofScars;
        int pathBlocks;
        int lights;
        int uniqueDetails;
        int steepRoutes;
        final List<String> overlaps = new ArrayList<>();
    }

    private record SafeZone(double x, double z, double radius) {
        boolean contains(double targetX, double targetZ) {
            double dx = targetX - x;
            double dz = targetZ - z;
            return dx * dx + dz * dz <= radius * radius;
        }
    }
}
