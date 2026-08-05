package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;

/**
 * Deterministic and transactional self-repair for the audited Overworld campaign.
 *
 * <p>This is deliberately not an unrestricted AI block editor. It works only from the
 * persisted structural registry, proposes a bounded repair plan, snapshots every touched
 * block, applies edits in batches, audits again and rolls back when the result does not
 * improve. A small memory file records which strategies solved each recurring category.</p>
 */
public final class OverworldAutoRepair148 {
    static final String CONTEXT_FILE = "arlight-overworld-autorepair-context.properties";
    static final String REGISTRY_FILE = "arlight-overworld-autorepair-registry.tsv";
    static final String MEMORY_FILE = "arlight-overworld-autorepair-memory.properties";
    static final String ROLLBACK_FILE = "arlight-overworld-autorepair-rollback.tsv";
    static final String STATUS_FILE = "arlight-overworld-autorepair-status.properties";
    static final String LAST_SCAN_FILE = "arlight-overworld-autorepair-last-scan.txt";

    private static final Map<UUID, LiveSession> ACTIVE = new HashMap<>();
    private static final Set<Material> NATURAL = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK, Material.MUD,
            Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.TUFF, Material.SAND, Material.RED_SAND,
            Material.GRAVEL, Material.CLAY, Material.TERRACOTTA, Material.SNOW_BLOCK);

    private OverworldAutoRepair148() { }

    record Context(OverworldCampaignAudit148.Registry registry,
                   Site village, Site residential, Site commercial, Site military,
                   Site citadel, Site boss, Site portal,
                   Location outerAltar, Location invocationAltar, Location ritualGate) { }

    record Inspection(boolean clean, OverworldCampaignAudit148.Summary summary,
                      List<String> issues, Set<String> categories, String diagnostic) { }

    record RepairPlan(List<BlockEdit> edits, Set<String> categories,
                      String fingerprint, String description) {
        boolean empty() { return edits.isEmpty(); }
    }

    record BuildDecision(Inspection inspection, RepairPlan plan) { }

    record RoadIssue(String routeId, int x, int y, int z) { }

    /** Persists the exact audited plan so in-game repair works after a restart. */
    static void saveContext(Path folder, World world, OverworldCampaignAudit148.Registry registry,
                            Site village, Site residential, Site commercial, Site military,
                            Site citadel, Site boss, Site portal,
                            Location outerAltar, Location invocationAltar,
                            Location ritualGate) throws IOException {
        Files.createDirectories(folder);
        Properties properties = new Properties();
        properties.setProperty("format", "1");
        properties.setProperty("world", world.getName());
        properties.setProperty("savedAt", Instant.now().toString());
        putSite(properties, "village", village);
        putSite(properties, "residential", residential);
        putSite(properties, "commercial", commercial);
        putSite(properties, "military", military);
        putSite(properties, "citadel", citadel);
        putSite(properties, "boss", boss);
        putSite(properties, "portal", portal);
        putLocation(properties, "outerAltar", outerAltar);
        putLocation(properties, "invocationAltar", invocationAltar);
        putLocation(properties, "ritualGate", ritualGate);
        atomicProperties(folder.resolve(CONTEXT_FILE), properties,
                "ArlightBingo 1.48.28 deterministic Overworld autorepair context");

        Path temporary = folder.resolve(REGISTRY_FILE + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (OverworldCampaignAudit148.HouseSpec house : registry.housesSnapshot()) {
                writer.write(String.join("\t", "H", safe(house.id()),
                        Integer.toString(house.cx()), Integer.toString(house.y()),
                        Integer.toString(house.cz()), Integer.toString(house.hx()),
                        Integer.toString(house.hz()), Integer.toString(house.height()),
                        Boolean.toString(house.roofAlongX()), house.plaster().name(),
                        house.front().name()));
                writer.newLine();
            }
            for (OverworldCampaignAudit148.TowerSpec tower : registry.towersSnapshot()) {
                writer.write(String.join("\t", "T", safe(tower.id()),
                        Integer.toString(tower.cx()), Integer.toString(tower.y()),
                        Integer.toString(tower.cz()), Integer.toString(tower.radius()),
                        Integer.toString(tower.height()), tower.front().name()));
                writer.newLine();
            }
            for (OverworldCampaignAudit148.ChimneySpec chimney : registry.chimneysSnapshot()) {
                writer.write(String.join("\t", "C", safe(chimney.id()),
                        Integer.toString(chimney.x()), Integer.toString(chimney.baseY()),
                        Integer.toString(chimney.topY()), Integer.toString(chimney.z())));
                writer.newLine();
            }
            for (OverworldCampaignAudit148.PlanterSpec planter : registry.plantersSnapshot()) {
                writer.write(String.join("\t", "P", safe(planter.id()),
                        Integer.toString(planter.x()), Integer.toString(planter.y()),
                        Integer.toString(planter.z()), planter.flower().name()));
                writer.newLine();
            }
            for (OverworldCampaignAudit148.TerrainSpec terrain : registry.terrainSnapshot()) {
                writer.write(String.join("\t", "N", safe(terrain.site().id()),
                        Integer.toString(terrain.site().x()), Integer.toString(terrain.site().baseY()),
                        Integer.toString(terrain.site().z()), Integer.toString(terrain.site().radius()),
                        terrain.site().style().name(), Integer.toString(terrain.flatRadius()),
                        Integer.toString(terrain.blendRadius())));
                writer.newLine();
            }
            for (OverworldCampaignAudit148.FortificationSpec wall : registry.fortificationsSnapshot()) {
                writer.write(String.join("\t", "F", safe(wall.id()),
                        Integer.toString(wall.cx()), Integer.toString(wall.baseY()),
                        Integer.toString(wall.cz()), Integer.toString(wall.radius()),
                        wall.shape().name()));
                writer.newLine();
            }
            for (Map.Entry<String, List<OverworldCampaignAudit148.RoadCell>> route
                    : registry.roadRoutesSnapshot().entrySet()) {
                for (OverworldCampaignAudit148.RoadCell road : route.getValue()) {
                    writer.write(String.join("\t", "R", safe(route.getKey()),
                            Integer.toString(road.x()), Integer.toString(road.walkY()),
                            Integer.toString(road.z()), road.floor().name()));
                    writer.newLine();
                }
            }
        }
        atomicMove(temporary, folder.resolve(REGISTRY_FILE));
    }

    static BuildDecision inspectAndPlan(BingoPlugin plugin, World world, Path folder,
                                        Context context, int attempt,
                                        Set<String> previousCategories) throws IOException {
        saveContext(folder, world, context.registry(), context.village(), context.residential(),
                context.commercial(), context.military(), context.citadel(), context.boss(),
                context.portal(), context.outerAltar(), context.invocationAltar(),
                context.ritualGate());
        Inspection inspection = inspect(plugin, world, folder, context);
        updateMemoryAfterAudit(folder, previousCategories, inspection.categories());
        if (inspection.clean()) return new BuildDecision(inspection,
                new RepairPlan(List.of(), Set.of(), "clean", "sin reparaciones"));
        RepairPlan plan = plan(plugin, world, folder, context, inspection, null, -1, attempt);
        writeScan(folder, inspection, plan);
        return new BuildDecision(inspection, plan);
    }

    static Context context(OverworldCampaignAudit148.Registry registry,
                           Site village, Site residential, Site commercial, Site military,
                           Site citadel, Site boss, Site portal,
                           Location outerAltar, Location invocationAltar,
                           Location ritualGate) {
        return new Context(registry, village, residential, commercial, military, citadel,
                boss, portal, outerAltar.clone(), invocationAltar.clone(), ritualGate.clone());
    }

    static void snapshotForBuild(Path folder, World world, List<BlockEdit> edits) throws IOException {
        saveRollback(folder.resolve(ROLLBACK_FILE), world, edits, true);
    }

    static void recordBuildFailure(Path folder, Set<String> categories) {
        try {
            Properties memory = loadProperties(folder.resolve(MEMORY_FILE));
            for (String category : categories) increment(memory, "failure." + category);
            memory.setProperty("lastFailedAt", Instant.now().toString());
            atomicProperties(folder.resolve(MEMORY_FILE), memory,
                    "ArlightBingo autorepair memory");
        } catch (IOException ignored) { }
    }

    static void recordBuildSuccess(Path folder, Set<String> previousCategories) {
        try {
            Properties memory = loadProperties(folder.resolve(MEMORY_FILE));
            for (String category : previousCategories) increment(memory, "success." + category);
            memory.setProperty("lastSuccessAt", Instant.now().toString());
            atomicProperties(folder.resolve(MEMORY_FILE), memory,
                    "ArlightBingo autorepair memory");
        } catch (IOException ignored) { }
    }

    public static void scan(BingoPlugin plugin, World world, Path folder, CommandSender sender) {
        try {
            Context context = loadContext(world, folder);
            Inspection inspection = inspect(plugin, world, folder, context);
            RepairPlan plan = inspection.clean()
                    ? new RepairPlan(List.of(), Set.of(), "clean", "sin cambios")
                    : plan(plugin, world, folder, context, inspection, null, -1, 0);
            writeScan(folder, inspection, plan);
            if (inspection.clean()) {
                sender.sendMessage(ChatColor.GREEN + "Autorreparación: la plantilla está limpia.");
                return;
            }
            sender.sendMessage(ChatColor.YELLOW + "Autorreparación encontró "
                    + inspection.issues().size() + " problemas en "
                    + inspection.categories().size() + " categorías.");
            int limit = Math.min(8, inspection.issues().size());
            for (String issue : inspection.issues().subList(0, limit)) {
                sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + issue);
            }
            sender.sendMessage(ChatColor.AQUA + "Plan seguro: " + plan.edits().size()
                    + " cambios · " + plan.description());
        } catch (Throwable error) {
            sender.sendMessage(ChatColor.RED + "No se pudo escanear: " + rootMessage(error));
        }
    }

    public static void preview(BingoPlugin plugin, World world, Path folder,
                               Player player, int radius) {
        try {
            Context context = loadContext(world, folder);
            Inspection inspection = inspect(plugin, world, folder, context);
            if (inspection.clean()) {
                player.sendMessage(ChatColor.GREEN + "No hay reparaciones pendientes.");
                return;
            }
            RepairPlan plan = plan(plugin, world, folder, context, inspection,
                    radius > 0 ? player.getLocation() : null, radius, 0);
            int displayed = 0;
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 80, 180), 0.8F);
            int stride = Math.max(1, plan.edits().size() / 450);
            for (int i = 0; i < plan.edits().size() && displayed < 450; i += stride) {
                BlockEdit edit = plan.edits().get(i);
                player.spawnParticle(Particle.DUST,
                        edit.x() + 0.5D, edit.y() + 0.7D, edit.z() + 0.5D,
                        1, 0, 0, 0, 0, dust);
                displayed++;
            }
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Previsualización: "
                    + plan.edits().size() + " cambios propuestos; " + displayed
                    + " marcadores mostrados.");
        } catch (Throwable error) {
            player.sendMessage(ChatColor.RED + "No se pudo previsualizar: " + rootMessage(error));
        }
    }

    public static boolean apply(BingoPlugin plugin, World world, Path folder,
                                CommandSender sender, Location center, int radius) {
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(world.getUID())) {
                sender.sendMessage(ChatColor.YELLOW + "Ya existe una reparación activa en este mundo.");
                return false;
            }
        }
        try {
            Context context = loadContext(world, folder);
            Inspection inspection = inspect(plugin, world, folder, context);
            if (inspection.clean()) {
                sender.sendMessage(ChatColor.GREEN + "La plantilla ya está limpia; no se modificó nada.");
                return true;
            }
            LiveSession session = new LiveSession(plugin, world, folder, context, sender,
                    center == null ? null : center.clone(), radius,
                    Math.max(1, plugin.getConfig().getInt(
                            "template-worlds.overworld.autorepair.max-passes", 3)));
            synchronized (ACTIVE) { ACTIVE.put(world.getUID(), session); }
            session.begin(inspection);
            return true;
        } catch (Throwable error) {
            sender.sendMessage(ChatColor.RED + "No se pudo iniciar la reparación: " + rootMessage(error));
            return false;
        }
    }

    public static boolean rollback(BingoPlugin plugin, World world, Path folder,
                                   CommandSender sender) {
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(world.getUID())) {
                sender.sendMessage(ChatColor.RED + "Espera a que termine la reparación activa.");
                return false;
            }
        }
        Path rollback = folder.resolve(ROLLBACK_FILE);
        if (!Files.isRegularFile(rollback)) {
            sender.sendMessage(ChatColor.YELLOW + "No existe una instantánea para revertir.");
            return false;
        }
        try {
            List<Snapshot> snapshots = readRollback(rollback);
            if (snapshots.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "La instantánea está vacía.");
                return false;
            }
            applySnapshots(plugin, world, snapshots, sender, () -> {
                try {
                    Files.move(rollback, folder.resolve(ROLLBACK_FILE + ".used-"
                                    + System.currentTimeMillis()),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) { }
            });
            return true;
        } catch (Throwable error) {
            sender.sendMessage(ChatColor.RED + "No se pudo revertir: " + rootMessage(error));
            return false;
        }
    }

    public static String status(World world, Path folder) {
        synchronized (ACTIVE) {
            LiveSession active = ACTIVE.get(world.getUID());
            if (active != null) return active.status();
        }
        try {
            Properties status = loadProperties(folder.resolve(STATUS_FILE));
            if (status.isEmpty()) return "inactiva · sin historial";
            return status.getProperty("state", "inactiva") + " · "
                    + status.getProperty("detail", "sin detalle") + " · "
                    + status.getProperty("updatedAt", "sin fecha");
        } catch (IOException error) {
            return "inactiva · no se pudo leer el estado";
        }
    }

    private static Inspection inspect(BingoPlugin plugin, World world, Path folder,
                                      Context context) {
        try {
            OverworldCampaignAudit148.Summary summary = OverworldCampaignAudit148.auditWorld(
                    world, context.registry(), context.village(), context.citadel(),
                    context.portal(), context.outerAltar(), context.ritualGate());
            return new Inspection(true, summary, List.of(), Set.of(), "auditoría limpia");
        } catch (IllegalStateException rejected) {
            List<String> issues = splitIssues(rejected.getMessage());
            Set<String> categories = new LinkedHashSet<>();
            for (String issue : issues) categories.add(category(issue));
            return new Inspection(false, null, List.copyOf(issues), Set.copyOf(categories),
                    rejected.getMessage());
        }
    }

    private static RepairPlan plan(BingoPlugin plugin, World world, Path folder,
                                   Context context, Inspection inspection,
                                   Location center, int radius, int attempt) throws IOException {
        Set<String> categories = inspection.categories().isEmpty()
                ? Set.of("unknown") : inspection.categories();
        Properties memory = loadProperties(folder.resolve(MEMORY_FILE));
        List<BlockEdit> edits = new ArrayList<>();
        boolean houses = categories.stream().anyMatch(c -> c.equals("house") || c.equals("interior"));
        boolean towers = categories.contains("tower");
        boolean chimneys = categories.contains("chimney");
        boolean roads = categories.contains("road") || categories.contains("ritual");
        boolean terrain = categories.contains("terrain");
        boolean fortification = categories.contains("fortification") || categories.contains("boss_gate");
        boolean lighting = categories.contains("lighting");
        boolean portalZone = categories.contains("portal");
        List<RoadIssue> roadIssues = parseRoadIssues(inspection.issues(), context.registry());
        String strategy = roadIssues.isEmpty() ? "baseline" : switch (Math.floorMod(attempt, 3)) {
            case 0 -> "targeted-clearance";
            case 1 -> "supported-segment";
            default -> "widened-corridor";
        };

        boolean greatHallFurniture = inspection.issues().stream().anyMatch(issue ->
                issue.contains("citadel-great-hall") && issue.contains("mobiliario"));
        boolean bossWallFoundation = inspection.issues().stream().anyMatch(issue ->
                issue.contains("cimiento de muralla flotante en boss-wall"));

        // Repair one structural zone per pass. A failure in another zone therefore cannot
        // cause a successful great-hall or wall repair to be mixed with thousands of unrelated
        // edits. Three passes cover the known hall -> wall -> road sequence deterministically.
        if (greatHallFurniture) {
            edits.addAll(OverworldCampaignArchitecture148.restoreCitadelGreatHallInterior(
                    context.citadel()));
            strategy = "zone-great-hall-furniture";
        } else if (bossWallFoundation) {
            edits.addAll(OverworldCampaignTerrain148.reinforceBossWallFoundation(
                    world, context.boss()));
            strategy = "zone-boss-wall-foundation";
        } else {
            if (houses) {
                edits.addAll(OverworldCampaignAudit148.repairRegisteredHouseShells(context.registry()));
                edits.addAll(OverworldCampaignAudit148.repairRegisteredEntrances(world, context.registry()));
            }
            if (chimneys || houses) {
                edits.addAll(OverworldCampaignAudit148.repairRegisteredChimneys(context.registry()));
            }
            if (towers || roads || fortification) {
                edits.addAll(OverworldCampaignAudit148.repairRegisteredTowerShells(context.registry()));
                edits.addAll(OverworldCampaignAudit148.repairRegisteredVerticalAccess(context.registry()));
                edits.addAll(OverworldCampaignAudit148.repairRegisteredTowerLandings(world, context.registry()));
            }
            if (roads || fortification) {
                if (!roadIssues.isEmpty()) {
                    edits.addAll(repairDiagnosedRoads(world, context.registry(), roadIssues, attempt));
                    if (roadIssues.stream().anyMatch(issue ->
                            "boss-north-approach".equals(issue.routeId()))) {
                        edits.addAll(OverworldCampaignTerrain148.restoreBossNorthMonumentalStairs(
                                world, context.boss(), context.ritualGate(), context.registry()));
                    }
                    edits.addAll(stabilizeDiagnosedRoadEdges(world, context.registry(), roadIssues, attempt));
                } else {
                    edits.addAll(OverworldCampaignAudit148.restoreAllRoadCorridorsStrict(
                            world, context.registry()));
                }
                edits.addAll(repairRitualThreshold(world, context));
            }
            if (fortification) {
                edits.addAll(OverworldCampaignArchitecture148.restoreBossGatehouse(
                        world, context.ritualGate()));
                edits.addAll(repairBossGate(world, context));
            }
            if (terrain) {
                edits.addAll(OverworldCampaignTerrain148.finalizeCitadelGround(
                        world, context.citadel(), context.registry()));
                int previousFailures = intProperty(memory, "failure.terrain", 0);
                edits.addAll(repairTerrainTransitions(world, context.registry(),
                        Math.max(2, 4 - Math.min(2, previousFailures + attempt))));
            }
            if (portalZone) {
                OverworldCampaignArchitecture148.portalSanctuaryFurnishings(
                        edits, context.portal().x(), context.portal().baseY() + 1,
                        context.portal().z());
                strategy = strategy + "+portal-clear";
            }
            if (lighting) {
                edits.addAll(OverworldCampaignArchitecture148.restoreCriticalPassageLighting(
                        context.citadel(), context.ritualGate(), context.portal()));
                strategy = strategy + "+passage-lighting";
            }
            if (categories.contains("unknown") || edits.isEmpty()) {
                // Safest complete recovery set. It never calls a broad structure generator.
                edits.addAll(OverworldCampaignAudit148.repairRoadCorridors(world, context.registry()));
                edits.addAll(OverworldCampaignAudit148.repairRegisteredHouseShells(context.registry()));
                edits.addAll(OverworldCampaignAudit148.repairRegisteredTowerShells(context.registry()));
                edits.addAll(OverworldCampaignArchitecture148.restoreBossGatehouse(
                        world, context.ritualGate()));
                edits.addAll(OverworldCampaignAudit148.repairRegisteredEntrances(world, context.registry()));
                edits.addAll(OverworldCampaignAudit148.repairRegisteredVerticalAccess(context.registry()));
                edits.addAll(OverworldCampaignAudit148.repairRegisteredTowerLandings(world, context.registry()));
                edits.addAll(OverworldCampaignAudit148.repairRegisteredChimneys(context.registry()));
                edits.addAll(repairTerrainTransitions(world, context.registry(), 3));
                OverworldCampaignArchitecture148.portalSanctuaryFurnishings(
                        edits, context.portal().x(), context.portal().baseY() + 1,
                        context.portal().z());
                edits.addAll(OverworldCampaignArchitecture148.restoreCriticalPassageLighting(
                        context.citadel(), context.ritualGate(), context.portal()));
                strategy = strategy + "+fallback";
            }
        }

        if (roads || fortification || terrain || lighting || portalZone
                || categories.contains("unknown")) {
            edits.addAll(OverworldCampaignTerrain148.finalizeFortressAndColiseum(
                    world, context.citadel(), context.boss(), context.ritualGate(),
                    context.registry()));
            context.registry().reconcileRouteMembershipsToPhysicalPlan();
            edits.addAll(OverworldCampaignAudit148.restoreAllRoadCorridorsStrict(
                    world, context.registry()));
            edits.addAll(OverworldCampaignArchitecture148.restoreBossColiseumClosure(
                    world, context.boss(), context.ritualGate()));
            edits.addAll(OverworldCampaignArchitecture148.restoreBossGatehouse(
                    world, context.ritualGate()));
            edits.addAll(OverworldCampaignArchitecture148.restoreBossRearTowers(
                    world, context.boss()));
            edits.addAll(OverworldCampaignAudit148.repairRegisteredTowerFoundations(
                    world, context.registry()));
            edits.addAll(OverworldCampaignAudit148.repairRegisteredTowerShells(
                    context.registry()));
            edits.addAll(OverworldCampaignAudit148.repairRegisteredVerticalAccess(
                    context.registry()));
            edits.addAll(OverworldCampaignAudit148.repairRegisteredTowerLandings(
                    world, context.registry()));
            edits.addAll(OverworldCampaignAudit148.repairRegisteredEntrances(
                    world, context.registry()));
            if (!Files.isRegularFile(world.getWorldFolder().toPath().resolve(
                    OverworldCampaignLandscape148.GATE_OPEN_MARKER))) {
                edits.addAll(OverworldCampaignArchitecture148.restoreClosedBossRitualGate(
                        context.ritualGate()));
            }
            OverworldCampaignArchitecture148.portalSanctuaryFurnishings(
                    edits, context.portal().x(), context.portal().baseY() + 1,
                    context.portal().z());
            edits.addAll(OverworldCampaignArchitecture148.restoreCriticalPassageLighting(
                    context.citadel(), context.ritualGate(), context.portal()));
            strategy = strategy + "+finished-gates-lit-passages-portal-clear";
        }

        List<BlockEdit> normalized = normalize(edits, center, radius, world);
        String fingerprint = fingerprint(categories, normalized) + "-" + strategy;
        String description = String.join(", ", categories) + " · " + strategy + " · intento " + (attempt + 1);
        return new RepairPlan(normalized, Set.copyOf(categories), fingerprint, description);
    }

    private static List<BlockEdit> repairTerrainTransitions(World world,
                                                             OverworldCampaignAudit148.Registry registry,
                                                             int maximumStep) {
        List<BlockEdit> out = new ArrayList<>();
        for (OverworldCampaignAudit148.TerrainSpec spec : registry.terrainSnapshot()) {
            Site site = spec.site();
            int start = Math.max(4, spec.flatRadius() - 2);
            int end = spec.blendRadius() + (site.style() == Style.BOSS ? 14 : 18);
            for (int degree = 0; degree < 360; degree += 6) {
                double angle = Math.toRadians(degree);
                Integer previous = null;
                for (int distance = start; distance <= end; distance++) {
                    int x = site.x() + (int) Math.round(Math.cos(angle) * distance);
                    int z = site.z() + (int) Math.round(Math.sin(angle) * distance);
                    if (registry.blocksTerrainAudit(x, z)) {
                        previous = null;
                        continue;
                    }
                    int current = OverworldCampaignTerrain148.terrainY(world, x, z);
                    if (previous != null && Math.abs(current - previous) > maximumStep) {
                        int target = current > previous
                                ? previous + maximumStep : previous - maximumStep;
                        // Around the boss we never cut a natural hill; we only fill pits and
                        // replace exposed dirt. This prevents another circular plateau.
                        if (site.style() == Style.BOSS && target < current) {
                            replaceSurfaceIfBare(out, world, x, current, z);
                        } else {
                            reshapeNaturalColumn(out, world, registry, x, current, target, z);
                            current = target;
                        }
                    } else if (site.style() == Style.BOSS) {
                        replaceSurfaceIfBare(out, world, x, current, z);
                    }
                    previous = current;
                }
            }
        }
        return out;
    }

    private static void reshapeNaturalColumn(List<BlockEdit> out, World world,
                                             OverworldCampaignAudit148.Registry registry,
                                             int x, int current, int target, int z) {
        if (registry.blocksTerrainAudit(x, z) || Math.abs(target - current) > 8) return;
        if (target > current) {
            for (int y = current + 1; y <= target; y++) {
                out.add(new BlockEdit(x, y, z, y == target ? Material.GRASS_BLOCK : Material.DIRT));
            }
            return;
        }
        for (int y = current; y > target; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (!NATURAL.contains(type)) return;
            out.add(new BlockEdit(x, y, z, Material.AIR));
        }
        out.add(new BlockEdit(x, target, z, Material.GRASS_BLOCK));
        for (int y = target - 1; y >= target - 3; y--) {
            Material existing = world.getBlockAt(x, y, z).getType();
            if (!existing.isSolid()) out.add(new BlockEdit(x, y, z, Material.DIRT));
        }
    }

    private static void replaceSurfaceIfBare(List<BlockEdit> out, World world,
                                             int x, int y, int z) {
        Material current = world.getBlockAt(x, y, z).getType();
        if (current == Material.DIRT || current == Material.COARSE_DIRT
                || current == Material.ROOTED_DIRT) {
            out.add(new BlockEdit(x, y, z, Material.GRASS_BLOCK));
        }
    }

    private static List<BlockEdit> repairRitualThreshold(World world, Context context) {
        List<BlockEdit> out = new ArrayList<>();
        int y = context.ritualGate().getBlockY();
        int startZ = Math.min(context.outerAltar().getBlockZ(), context.ritualGate().getBlockZ());
        int endZ = Math.max(context.outerAltar().getBlockZ(), context.ritualGate().getBlockZ());
        int x = context.ritualGate().getBlockX();
        for (int z = startZ; z <= endZ; z++) {
            int distance = Math.abs(context.ritualGate().getBlockZ() - z);
            int walkY = OverworldCampaignTerrain148.bossApproachWalkY(y, distance);
            for (int dx = -2; dx <= 2; dx++) {
                OverworldCampaignTerrain148.supportedPathCell(out, world, x + dx,
                        walkY, z, Material.STONE_BRICKS);
                for (int yy = 0; yy <= 2; yy++) {
                    if (!context.registry().blocksStructureBody(x + dx, walkY + yy, z)) {
                        out.add(new BlockEdit(x + dx, walkY + yy, z, Material.AIR));
                    }
                }
            }
        }
        return out;
    }

    private static List<BlockEdit> repairBossGate(World world, Context context) {
        List<BlockEdit> out = new ArrayList<>();
        int cx = context.ritualGate().getBlockX();
        int y = context.ritualGate().getBlockY();
        int cz = context.ritualGate().getBlockZ();
        for (int x = -OverworldCampaignArchitecture148.BOSS_GATE_CLEAR_HALF_WIDTH;
             x <= OverworldCampaignArchitecture148.BOSS_GATE_CLEAR_HALF_WIDTH; x++) {
            OverworldCampaignTerrain148.supportedPathCell(out, world, cx + x, y, cz + 2,
                    Material.STONE_BRICKS);
            for (int yy = 0; yy <= 5; yy++) {
                out.add(new BlockEdit(cx + x, y + yy, cz + 2, Material.AIR));
            }
        }
        return out;
    }

    private static List<RoadIssue> parseRoadIssues(List<String> issues,
                                                   OverworldCampaignAudit148.Registry registry) {
        List<RoadIssue> out = new ArrayList<>();
        Map<String, List<OverworldCampaignAudit148.RoadCell>> routes = registry.roadRoutesSnapshot();
        for (String issue : issues) {
            String lower = issue.toLowerCase(Locale.ROOT);
            if (!lower.contains("camino cortado") && !lower.contains("corredor")) continue;
            int colon = issue.indexOf(':');
            int open = issue.indexOf('(', Math.max(0, colon));
            String routeId = colon >= 0
                    ? issue.substring(colon + 1, open > colon ? open : issue.length()).trim()
                    : "";
            int x = parseTaggedInt(issue, "x=");
            int y = parseTaggedInt(issue, "y=");
            int z = parseTaggedInt(issue, "z=");
            if (!routeId.isBlank() && routes.containsKey(routeId) && x != Integer.MIN_VALUE
                    && y != Integer.MIN_VALUE && z != Integer.MIN_VALUE) {
                out.add(new RoadIssue(routeId, x, y, z));
            }
        }
        return out;
    }

    private static int parseTaggedInt(String text, String tag) {
        int start = text.indexOf(tag);
        if (start < 0) return Integer.MIN_VALUE;
        start += tag.length();
        int end = start;
        while (end < text.length()) {
            char ch = text.charAt(end);
            if ((ch >= '0' && ch <= '9') || ch == '-') {
                end++;
                continue;
            }
            break;
        }
        if (end <= start) return Integer.MIN_VALUE;
        try { return Integer.parseInt(text.substring(start, end)); }
        catch (NumberFormatException ignored) { return Integer.MIN_VALUE; }
    }

    private static List<BlockEdit> repairDiagnosedRoads(World world,
                                                        OverworldCampaignAudit148.Registry registry,
                                                        List<RoadIssue> issues,
                                                        int attempt) {
        List<BlockEdit> out = new ArrayList<>();
        Map<String, List<OverworldCampaignAudit148.RoadCell>> routes = registry.roadRoutesSnapshot();
        Set<String> repaired = new LinkedHashSet<>();
        for (RoadIssue issue : issues) {
            if (!repaired.add(issue.routeId())) continue;
            List<OverworldCampaignAudit148.RoadCell> route = routes.get(issue.routeId());
            if (route == null || route.isEmpty()) continue;
            // Rebuild the complete declared route on the first pass. The former radius-limited
            // repair could clear one sample, satisfy the BFS and leave the rest visually broken.
            for (OverworldCampaignAudit148.RoadCell cell : route) {
                if (registry.blocksStructureBody(cell.x(), cell.walkY(), cell.z())) continue;
                OverworldCampaignTerrain148.supportedPathCell(out, world, cell.x(),
                        cell.walkY(), cell.z(), cell.floor());
                for (int y = cell.walkY(); y <= cell.walkY() + 5; y++) {
                    out.add(new BlockEdit(cell.x(), y, cell.z(), Material.AIR));
                }
            }
        }
        return out;
    }

    private static Material widenedRoadFloor(Material floor, int dx, int dz) {
        if (dx == 0 && dz == 0) return floor;
        if (floor == Material.DIRT_PATH || floor == Material.PACKED_MUD
                || floor == Material.COARSE_DIRT || floor == Material.MUD_BRICKS) {
            return Material.COARSE_DIRT;
        }
        if (floor == Material.STONE_BRICKS || floor == Material.MOSSY_STONE_BRICKS
                || floor == Material.POLISHED_ANDESITE || floor == Material.ANDESITE) {
            return Material.STONE_BRICKS;
        }
        if (floor == Material.DEEPSLATE_BRICKS || floor == Material.DEEPSLATE_TILES) {
            return Material.DEEPSLATE_TILES;
        }
        if (floor == Material.SPRUCE_PLANKS) return Material.SPRUCE_PLANKS;
        return floor;
    }

    private static List<BlockEdit> stabilizeDiagnosedRoadEdges(World world,
                                                               OverworldCampaignAudit148.Registry registry,
                                                               List<RoadIssue> issues,
                                                               int attempt) {
        List<BlockEdit> out = new ArrayList<>();
        if (attempt <= 0) return out;
        Map<String, List<OverworldCampaignAudit148.RoadCell>> routes = registry.roadRoutesSnapshot();
        int reach = 6 + attempt * 4;
        int shoulderRadius = attempt >= 2 ? 2 : 1;
        for (RoadIssue issue : issues) {
            List<OverworldCampaignAudit148.RoadCell> route = routes.get(issue.routeId());
            if (route == null || route.isEmpty()) continue;
            for (OverworldCampaignAudit148.RoadCell cell : route) {
                if (Math.max(Math.abs(cell.x() - issue.x()), Math.abs(cell.z() - issue.z())) > reach) {
                    continue;
                }
                for (int dx = -shoulderRadius; dx <= shoulderRadius; dx++) {
                    for (int dz = -shoulderRadius; dz <= shoulderRadius; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) != shoulderRadius) continue;
                        int x = cell.x() + dx;
                        int z = cell.z() + dz;
                        if (registry.blocksTerrainAudit(x, z) || registry.blocksStructureBody(x, cell.walkY(), z)) {
                            continue;
                        }
                        stabilizeShoulderColumn(out, world, x, z, cell.walkY() - 1, cell.floor());
                    }
                }
            }
        }
        return out;
    }

    private static void stabilizeShoulderColumn(List<BlockEdit> out, World world,
                                                int x, int z, int targetTop,
                                                Material roadFloor) {
        int current = OverworldCampaignTerrain148.terrainY(world, x, z);
        if (current >= targetTop - 1) {
            replaceSurfaceIfBare(out, world, x, current, z);
            return;
        }
        Material fill = roadFloor == Material.STONE_BRICKS || roadFloor == Material.MOSSY_STONE_BRICKS
                || roadFloor == Material.POLISHED_ANDESITE || roadFloor == Material.ANDESITE
                || roadFloor == Material.DEEPSLATE_BRICKS || roadFloor == Material.DEEPSLATE_TILES
                ? Material.COBBLESTONE : Material.DIRT;
        for (int y = current + 1; y < targetTop; y++) {
            out.add(new BlockEdit(x, y, z, fill));
        }
        out.add(new BlockEdit(x, targetTop, z, fill == Material.DIRT ? Material.GRASS_BLOCK : fill));
    }

    private static List<BlockEdit> normalize(List<BlockEdit> edits, Location center,
                                             int radius, World world) {
        Map<String, BlockEdit> unique = new LinkedHashMap<>();
        long radiusSquared = radius > 0 ? (long) radius * radius : -1L;
        for (BlockEdit edit : edits) {
            if (edit.y() < world.getMinHeight() || edit.y() >= world.getMaxHeight()) continue;
            if (center != null && radiusSquared > 0) {
                long dx = edit.x() - center.getBlockX();
                long dz = edit.z() - center.getBlockZ();
                if (dx * dx + dz * dz > radiusSquared) continue;
            }
            unique.put(edit.x() + ":" + edit.y() + ":" + edit.z(), edit);
        }
        return List.copyOf(unique.values());
    }

    private static Context loadContext(World world, Path folder) throws IOException {
        Properties properties = loadProperties(folder.resolve(CONTEXT_FILE));
        if (properties.isEmpty() || !Files.isRegularFile(folder.resolve(REGISTRY_FILE))) {
            throw new IOException("La plantilla no tiene contexto de autorreparación 1.48.28. "
                    + "Regenera una revisión con esta versión.");
        }
        OverworldCampaignAudit148.Registry registry = new OverworldCampaignAudit148.Registry();
        try (BufferedReader reader = Files.newBufferedReader(folder.resolve(REGISTRY_FILE),
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t", -1);
                switch (p[0]) {
                    case "H" -> registry.registerHouse(new OverworldCampaignAudit148.HouseSpec(
                            unsafe(p[1]), integer(p[2]), integer(p[3]), integer(p[4]),
                            integer(p[5]), integer(p[6]), integer(p[7]),
                            Boolean.parseBoolean(p[8]), Material.valueOf(p[9]),
                            OverworldCampaignAudit148.Facing.valueOf(p[10])));
                    case "T" -> registry.registerTower(new OverworldCampaignAudit148.TowerSpec(
                            unsafe(p[1]), integer(p[2]), integer(p[3]), integer(p[4]),
                            integer(p[5]), integer(p[6]),
                            OverworldCampaignAudit148.Facing.valueOf(p[7])));
                    case "C" -> registry.registerChimney(new OverworldCampaignAudit148.ChimneySpec(
                            unsafe(p[1]), integer(p[2]), integer(p[3]), integer(p[4]), integer(p[5])));
                    case "P" -> registry.registerPlanter(new OverworldCampaignAudit148.PlanterSpec(
                            unsafe(p[1]), integer(p[2]), integer(p[3]), integer(p[4]),
                            Material.valueOf(p[5])));
                    case "N" -> registry.registerTerrain(new Site(unsafe(p[1]), integer(p[2]),
                                    integer(p[3]), integer(p[4]), integer(p[5]), Style.valueOf(p[6])),
                            integer(p[7]), integer(p[8]));
                    case "F" -> registry.registerFortification(
                            new OverworldCampaignAudit148.FortificationSpec(unsafe(p[1]),
                                    integer(p[2]), integer(p[3]), integer(p[4]), integer(p[5]),
                                    OverworldCampaignAudit148.FortificationShape.valueOf(p[6])));
                    case "R" -> registry.registerRoadCell(unsafe(p[1]), integer(p[2]),
                            integer(p[3]), integer(p[4]), Material.valueOf(p[5]));
                    default -> { }
                }
            }
        }
        return new Context(registry,
                readSite(properties, "village"), readSite(properties, "residential"),
                readSite(properties, "commercial"), readSite(properties, "military"),
                readSite(properties, "citadel"), readSite(properties, "boss"),
                readSite(properties, "portal"), readLocation(world, properties, "outerAltar"),
                readLocation(world, properties, "invocationAltar"),
                readLocation(world, properties, "ritualGate"));
    }

    private static final class LiveSession {
        private final BingoPlugin plugin;
        private final World world;
        private final Path folder;
        private final Context context;
        private final CommandSender sender;
        private final Location center;
        private final int radius;
        private final int maxPasses;
        private BukkitTask task;
        private List<BlockEdit> edits = List.of();
        private int cursor;
        private int pass;
        private Set<String> previousCategories = Set.of();
        private String detail = "iniciando";

        LiveSession(BingoPlugin plugin, World world, Path folder, Context context,
                    CommandSender sender, Location center, int radius, int maxPasses) {
            this.plugin = plugin;
            this.world = world;
            this.folder = folder;
            this.context = context;
            this.sender = sender;
            this.center = center;
            this.radius = radius;
            this.maxPasses = maxPasses;
        }

        void begin(Inspection inspection) throws IOException {
            Files.deleteIfExists(folder.resolve(ROLLBACK_FILE));
            preparePass(inspection);
        }

        private void preparePass(Inspection inspection) throws IOException {
            if (inspection.clean()) {
                finish(true, "auditoría limpia tras " + pass + " intentos");
                return;
            }
            if (pass >= maxPasses) {
                recordBuildFailure(folder, inspection.categories());
                finish(false, "persisten " + inspection.issues().size()
                        + " problemas después de " + pass + " intentos");
                return;
            }
            RepairPlan plan = plan(plugin, world, folder, context, inspection,
                    center, radius, pass);
            if (plan.empty()) {
                finish(false, "no existe una reparación segura para " + inspection.categories());
                return;
            }
            if (pass > 0 && plan.fingerprint().equals(readStatusFingerprint(folder))) {
                finish(false, "la misma reparación no produjo mejora; se detuvo para evitar un bucle");
                return;
            }
            previousCategories = inspection.categories();
            edits = plan.edits();
            cursor = 0;
            pass++;
            saveRollback(folder.resolve(ROLLBACK_FILE), world, edits, true);
            writeStatus(folder, "applying", "intento " + pass + ": " + plan.description(),
                    plan.fingerprint());
            detail = "intento " + pass + " · 0/" + edits.size();
            sender.sendMessage(ChatColor.YELLOW + "Aplicando autorreparación " + pass + "/"
                    + maxPasses + ": " + edits.size() + " cambios.");
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }

        private void tick() {
            try {
                int maximum = Math.max(200, plugin.getConfig().getInt(
                        "template-worlds.overworld.autorepair.blocks-per-tick", 900));
                long deadline = System.nanoTime() + Math.max(2L, plugin.getConfig().getLong(
                        "template-worlds.overworld.autorepair.max-millis-per-tick", 5L))
                        * 1_000_000L;
                int applied = 0;
                while (cursor < edits.size() && applied < maximum
                        && System.nanoTime() < deadline) {
                    apply(world, edits.get(cursor++));
                    applied++;
                }
                detail = "intento " + pass + " · " + cursor + "/" + edits.size();
                if (cursor < edits.size()) return;
                task.cancel();
                task = null;
                world.save();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Inspection next = inspect(plugin, world, folder, context);
                    updateMemoryAfterAuditQuietly(folder, previousCategories, next.categories());
                    try {
                        preparePass(next);
                    } catch (IOException error) {
                        finish(false, rootMessage(error));
                    }
                }, 10L);
            } catch (Throwable error) {
                if (task != null) task.cancel();
                finish(false, rootMessage(error));
            }
        }

        String status() { return "activa · " + detail; }

        private void finish(boolean success, String message) {
            if (task != null) task.cancel();
            task = null;
            synchronized (ACTIVE) { ACTIVE.remove(world.getUID()); }
            writeStatusQuietly(folder, success ? "complete" : "failed", message, "");
            sender.sendMessage((success ? ChatColor.GREEN : ChatColor.RED)
                    + "Autorreparación: " + message);
        }
    }

    private record Snapshot(int x, int y, int z, Material material, String data) { }

    private static void saveRollback(Path file, World world, List<BlockEdit> edits,
                                     boolean merge) throws IOException {
        Map<String, Snapshot> snapshots = new LinkedHashMap<>();
        if (merge && Files.isRegularFile(file)) {
            for (Snapshot snapshot : readRollback(file)) {
                snapshots.put(key(snapshot.x(), snapshot.y(), snapshot.z()), snapshot);
            }
        }
        for (BlockEdit edit : edits) {
            String key = key(edit.x(), edit.y(), edit.z());
            if (snapshots.containsKey(key)) continue;
            Block block = world.getBlockAt(edit.x(), edit.y(), edit.z());
            snapshots.put(key, new Snapshot(edit.x(), edit.y(), edit.z(), block.getType(),
                    block.getBlockData().getAsString()));
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Snapshot snapshot : snapshots.values()) {
                writer.write(snapshot.x() + "\t" + snapshot.y() + "\t" + snapshot.z()
                        + "\t" + snapshot.material().name() + "\t"
                        + Base64.getEncoder().encodeToString(
                        snapshot.data().getBytes(StandardCharsets.UTF_8)));
                writer.newLine();
            }
        }
        atomicMove(temporary, file);
    }

    private static List<Snapshot> readRollback(Path file) throws IOException {
        List<Snapshot> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split("\t", 5);
                if (p.length < 5) continue;
                result.add(new Snapshot(integer(p[0]), integer(p[1]), integer(p[2]),
                        Material.valueOf(p[3]), new String(Base64.getDecoder().decode(p[4]),
                        StandardCharsets.UTF_8)));
            }
        }
        return result;
    }

    private static void applySnapshots(BingoPlugin plugin, World world,
                                       List<Snapshot> snapshots, CommandSender sender,
                                       Runnable completion) {
        final int[] cursor = {0};
        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int maximum = Math.max(200, plugin.getConfig().getInt(
                    "template-worlds.overworld.autorepair.blocks-per-tick", 900));
            int end = Math.min(snapshots.size(), cursor[0] + maximum);
            while (cursor[0] < end) {
                Snapshot snapshot = snapshots.get(cursor[0]++);
                Block block = world.getBlockAt(snapshot.x(), snapshot.y(), snapshot.z());
                block.setType(snapshot.material(), false);
                try { block.setBlockData(Bukkit.createBlockData(snapshot.data()), false); }
                catch (IllegalArgumentException ignored) { }
            }
            if (cursor[0] < snapshots.size()) return;
            holder[0].cancel();
            world.save();
            completion.run();
            sender.sendMessage(ChatColor.GREEN + "Rollback completado: "
                    + snapshots.size() + " bloques restaurados.");
        }, 1L, 1L);
    }

    private static void apply(World world, BlockEdit edit) {
        Block block = world.getBlockAt(edit.x(), edit.y(), edit.z());
        if (block.getType() != edit.material()) block.setType(edit.material(), false);
        if (edit.data() != null) {
            try { block.setBlockData(Bukkit.createBlockData(edit.data()), false); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private static List<String> splitIssues(String message) {
        if (message == null || message.isBlank()) return List.of("error de auditoría sin detalle");
        int colon = message.indexOf(':');
        String body = colon >= 0 ? message.substring(colon + 1).trim() : message.trim();
        List<String> result = new ArrayList<>();
        for (String issue : body.split(";\\s*")) {
            if (!issue.isBlank()) result.add(issue.trim());
        }
        return result.isEmpty() ? List.of(body) : result;
    }

    private static String category(String issue) {
        String lower = issue.toLowerCase(Locale.ROOT);
        if (lower.contains("camino") || lower.contains("ruta") || lower.contains("corredor")) return "road";
        if (lower.contains("terreno") || lower.contains("ladera") || lower.contains("corte abrupto")) return "terrain";
        if (lower.contains("chimenea")) return "chimney";
        if (lower.contains("torre") || lower.contains("escalera")) return "tower";
        if (lower.contains("puerta del boss") || lower.contains("portón")
                || lower.contains("muralla") || lower.contains("coliseo")) return "boss_gate";
        if (lower.contains("ritual") || lower.contains("altar exterior")) return "ritual";
        if (lower.contains("pared") || lower.contains("tejado") || lower.contains("frontón")
                || lower.contains("alero") || lower.contains("puerta ausente")) return "house";
        if (lower.contains("santuario del portal") || lower.contains("zona reservada del portal")
                || lower.contains("atril")) return "portal";
        if (lower.contains("luz") || lower.contains("farol") || lower.contains("oscuro")) return "lighting";
        if (lower.contains("interior") || lower.contains("mobiliario")) return "interior";
        if (lower.contains("cimiento")) return "fortification";
        return "unknown";
    }

    private static String fingerprint(Set<String> categories, List<BlockEdit> edits) {
        long hash = 1125899906842597L;
        for (String category : categories.stream().sorted().toList()) hash = hash * 31 + category.hashCode();
        for (BlockEdit edit : edits) {
            hash = hash * 31 + edit.x();
            hash = hash * 31 + edit.y();
            hash = hash * 31 + edit.z();
            hash = hash * 31 + edit.material().ordinal();
        }
        return Long.toUnsignedString(hash, 16);
    }

    private static void writeScan(Path folder, Inspection inspection,
                                  RepairPlan plan) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("ARLIGHTBINGO 1.48.28 AUTOREPAIR SCAN\n")
                .append("time=").append(Instant.now()).append('\n')
                .append("clean=").append(inspection.clean()).append('\n')
                .append("categories=").append(String.join(",", inspection.categories())).append('\n')
                .append("plannedEdits=").append(plan.edits().size()).append('\n')
                .append("fingerprint=").append(plan.fingerprint()).append('\n');
        for (String issue : inspection.issues()) text.append("issue=").append(issue).append('\n');
        Files.writeString(folder.resolve(LAST_SCAN_FILE), text.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void updateMemoryAfterAudit(Path folder, Set<String> previous,
                                               Set<String> current) throws IOException {
        if (previous == null || previous.isEmpty()) return;
        Properties memory = loadProperties(folder.resolve(MEMORY_FILE));
        for (String category : previous) {
            if (current.contains(category)) increment(memory, "failure." + category);
            else increment(memory, "success." + category);
        }
        memory.setProperty("lastAuditAt", Instant.now().toString());
        atomicProperties(folder.resolve(MEMORY_FILE), memory, "ArlightBingo autorepair memory");
    }

    private static void updateMemoryAfterAuditQuietly(Path folder, Set<String> previous,
                                                      Set<String> current) {
        try { updateMemoryAfterAudit(folder, previous, current); }
        catch (IOException ignored) { }
    }

    private static void writeStatus(Path folder, String state, String detail,
                                    String fingerprint) throws IOException {
        Properties status = new Properties();
        status.setProperty("state", state);
        status.setProperty("detail", detail);
        status.setProperty("fingerprint", fingerprint == null ? "" : fingerprint);
        status.setProperty("updatedAt", Instant.now().toString());
        atomicProperties(folder.resolve(STATUS_FILE), status, "ArlightBingo autorepair status");
    }

    private static void writeStatusQuietly(Path folder, String state, String detail,
                                           String fingerprint) {
        try { writeStatus(folder, state, detail, fingerprint); }
        catch (IOException ignored) { }
    }

    private static String readStatusFingerprint(Path folder) {
        try { return loadProperties(folder.resolve(STATUS_FILE)).getProperty("fingerprint", ""); }
        catch (IOException ignored) { return ""; }
    }

    private static void putSite(Properties p, String key, Site site) {
        p.setProperty(key, site.id() + "," + site.x() + "," + site.baseY() + ","
                + site.z() + "," + site.radius() + "," + site.style().name());
    }

    private static Site readSite(Properties p, String key) throws IOException {
        String[] value = required(p, key).split(",", -1);
        if (value.length != 6) throw new IOException("Sitio inválido: " + key);
        return new Site(value[0], integer(value[1]), integer(value[2]), integer(value[3]),
                integer(value[4]), Style.valueOf(value[5]));
    }

    private static void putLocation(Properties p, String key, Location location) {
        p.setProperty(key, location.getX() + "," + location.getY() + "," + location.getZ());
    }

    private static Location readLocation(World world, Properties p, String key) throws IOException {
        String[] value = required(p, key).split(",", -1);
        if (value.length != 3) throw new IOException("Ubicación inválida: " + key);
        return new Location(world, Double.parseDouble(value[0]), Double.parseDouble(value[1]),
                Double.parseDouble(value[2]));
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Falta " + key);
        return value;
    }

    private static String safe(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unsafe(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static int integer(String value) { return Integer.parseInt(value); }
    private static String key(int x, int y, int z) { return x + ":" + y + ":" + z; }

    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    private static void atomicProperties(Path file, Properties properties,
                                         String comment) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(writer, comment);
        }
        atomicMove(temporary, file);
    }

    private static void atomicMove(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        try { return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static void increment(Properties properties, String key) {
        properties.setProperty(key, Integer.toString(intProperty(properties, key, 0) + 1));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
