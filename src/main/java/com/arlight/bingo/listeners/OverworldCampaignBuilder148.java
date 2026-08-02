package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;

final class OverworldCampaignBuilder148 {
    private final BingoPlugin plugin;
    private final World world;
    private final Path folder;
    private final Site village;
    private final Location safeVillageSpawn;
    private final Site residential;
    private final Site commercial;
    private final Site military;
    private final Site citadel;
    private final Site boss;
    private final Site portal;
    private final Location outerAltar;
    private final Location invocationAltar;
    private final Location ritualGate;
    private final Consumer<Layout> completion;
    private final Consumer<Throwable> failure;
    private final Report report = new Report();
    private final OverworldCampaignAudit148.Registry auditRegistry =
            new OverworldCampaignAudit148.Registry();
    private final List<Phase> phases = new ArrayList<>();
    private OverworldCampaignAudit148.Summary auditSummary;

    private BukkitTask task;
    private List<BlockEdit> edits = List.of();
    private int phaseCursor;
    private int editCursor;

    OverworldCampaignBuilder148(BingoPlugin plugin, World world, Path folder,
                                Location villageSpawn, Location citadelAnchor,
                                Location bossAnchor, Location portalAnchor,
                                Consumer<Layout> completion, Consumer<Throwable> failure) {
        this.plugin = plugin;
        this.world = world;
        this.folder = folder;
        this.completion = completion;
        this.failure = failure;

        Location villageCenter = villageSpawn.clone().add(-3.0D, -1.0D, -15.0D);
        this.village = site("village", villageCenter, 88, Style.VILLAGE);
        this.safeVillageSpawn = new Location(world, village.x(), village.baseY() + 2,
                village.z() + 63, 180.0F, 0.0F);

        int plannedCitadelX = plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.citadel-x", 140);
        int plannedCitadelZ = plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.citadel-z", 30);
        Location plannedCitadel = new Location(world, plannedCitadelX,
                citadelAnchor.getY(), plannedCitadelZ);
        Location plannedBoss = plannedCitadel.clone().add(0, 0, 210);
        Location plannedPortal = plannedCitadel.clone().add(0, 0, 320);

        this.citadel = site("citadel", plannedCitadel, 68, Style.CITADEL);
        this.residential = site("residential", plannedCitadel.clone().add(-170, 0, 100),
                55, Style.RESIDENTIAL);
        this.commercial = site("commercial", plannedCitadel.clone().add(0, 0, -190),
                57, Style.COMMERCIAL);
        this.military = site("military", plannedCitadel.clone().add(170, 0, 80),
                60, Style.MILITARY);
        this.boss = site("boss", plannedBoss, 64, Style.BOSS);
        this.portal = site("portal", plannedPortal, 32, Style.PORTAL);
        this.ritualGate = new Location(world, boss.x(), boss.baseY() + 1, boss.z() - 50);
        this.outerAltar = new Location(world, boss.x(),
                OverworldCampaignTerrain148.bossApproachWalkY(boss.baseY() + 1, 14),
                boss.z() - 64);
        this.invocationAltar = new Location(world, boss.x(), boss.baseY() + 1, boss.z());
        preparePhases();
    }

    private Site site(String id, Location rough, int radius, Style style) {
        int x = rough.getBlockX();
        int z = rough.getBlockZ();
        int y = OverworldCampaignTerrain148.medianTerrainY(world, x, z,
                Math.min(46, Math.max(18, radius - 8)));
        y = Math.max(world.getMinHeight() + 12,
                Math.min(world.getMaxHeight() - 48, y));
        return new Site(id, x, y, z, radius, style);
    }

    private void preparePhases() {
        // 1.48.15 starts from a clean, anchor-only base. It never paints 1.43 structures and
        // never reconstructs circular legacy footprints over the natural island.
        addVillageShapePhases("pueblo inicial", village, 67, 94);
        addShapePhases("aldea residencial", residential, 43, 66);
        addShapePhases("mercado de la calzada", commercial, 45, 68);
        addShapePhases("bastión militar", military, 52, 74);
        addShapePhases("ciudadela", citadel, 60, 82);
        addShapePhases("arena sellada", boss, 46, 54);
        addShapePhases("santuario del portal", portal, 24, 44);
        phases.add(new Phase("conectando pueblo, asentamientos y arena",
                () -> OverworldCampaignTerrain148.buildRoadNetwork(world, layout(), village,
                        residential, commercial, military, citadel, boss, portal, report,
                        auditRegistry)));

        phases.add(new Phase("reconstruyendo pueblo inicial vivo",
                () -> OverworldCampaignStructures148.village(world, village, report, auditRegistry)));
        phases.add(new Phase("construyendo aldea residencial ocupada",
                () -> OverworldCampaignStructures148.residential(world, residential, report, auditRegistry)));
        phases.add(new Phase("construyendo mercado ocupado",
                () -> OverworldCampaignStructures148.commercial(world, commercial, report, auditRegistry)));
        phases.add(new Phase("construyendo bastión militar",
                () -> OverworldCampaignStructures148.military(world, military, report, auditRegistry)));
        phases.add(new Phase("construyendo ciudadela apoyada",
                () -> OverworldCampaignStructures148.citadel(world, citadel, report, auditRegistry)));
        phases.add(new Phase("construyendo puerta monumental y arena aprobada",
                () -> OverworldCampaignStructures148.bossArena(
                        world, boss, outerAltar, invocationAltar, ritualGate, report, auditRegistry)));
        phases.add(new Phase("construyendo santuario del portal",
                () -> OverworldCampaignStructures148.portal(world, portal, report, auditRegistry)));
        phases.add(new Phase("integrando murallas y accesos con el relieve",
                () -> OverworldCampaignTerrain148.integrateFortifications(
                        world, military, citadel, boss, report, auditRegistry)));
        phases.add(new Phase("decorando la isla reconstruida",
                () -> OverworldCampaignStructures148.decorations(
                        world, village, residential, commercial, military, citadel, report)));
        phases.add(new Phase("restaurando solo caminos externos seguros",
                () -> OverworldCampaignAudit148.repairRoadCorridors(world, auditRegistry)));
        phases.add(new Phase("restaurando envolventes completas de viviendas",
                () -> OverworldCampaignAudit148.repairRegisteredHouseShells(auditRegistry)));
        phases.add(new Phase("sellando accesos sin invadir edificios",
                () -> OverworldCampaignAudit148.repairRegisteredEntrances(world, auditRegistry)));
        phases.add(new Phase("restaurando accesos verticales auditados",
                () -> OverworldCampaignAudit148.repairRegisteredVerticalAccess(auditRegistry)));
        phases.add(new Phase("reconstruyendo descansillos finales de torres",
                () -> OverworldCampaignAudit148.repairRegisteredTowerLandings(world, auditRegistry)));
        phases.add(new Phase("rematando chimeneas después de los tejados",
                () -> OverworldCampaignAudit148.repairRegisteredChimneys(auditRegistry)));
    }

    private void addVillageShapePhases(String name, Site site, int flatRadius, int blendRadius) {
        auditRegistry.registerTerrain(site, flatRadius, blendRadius);
        int strip = Math.max(6, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.planning-strip-width", 10));
        int extent = blendRadius + 14;
        for (int minimum = -extent; minimum <= extent; minimum += strip) {
            int from = minimum;
            int to = Math.min(extent, minimum + strip - 1);
            phases.add(new Phase("aterrazando " + name + " [" + from + ".." + to + "]",
                    () -> OverworldCampaignTerrain148.shapeVillage(
                            world, site, flatRadius, blendRadius, report, from, to)));
        }
    }

    private void addShapePhases(String name, Site site, int flatRadius, int blendRadius) {
        auditRegistry.registerTerrain(site, flatRadius, blendRadius);
        int strip = Math.max(6, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.planning-strip-width", 10));
        int extent = blendRadius + 14;
        for (int minimum = -extent; minimum <= extent; minimum += strip) {
            int from = minimum;
            int to = Math.min(extent, minimum + strip - 1);
            phases.add(new Phase("adaptando " + name + " [" + from + ".." + to + "]",
                    () -> OverworldCampaignTerrain148.shapeSite(
                            world, site, flatRadius, blendRadius, report, from, to)));
        }
    }

    void start() {
        purgeBuildFootprintEntities();
        plugin.getLogger().info("[ArlightBingo] Diseño Overworld 1.48.15: "
                + phases.size() + " fases reanudables.");
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void purgeBuildFootprintEntities() {
        List<Site> footprints = List.of(village, residential, commercial, military,
                citadel, boss, portal);
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (!(entity instanceof LivingEntity) || entity instanceof Player
                    || entity.getScoreboardTags().contains("arlightbingo_keep")) continue;
            for (Site site : footprints) {
                double dx = entity.getLocation().getX() - site.x();
                double dz = entity.getLocation().getZ() - site.z();
                if (dx * dx + dz * dz <= (site.radius() + 8.0D) * (site.radius() + 8.0D)) {
                    entity.remove();
                    break;
                }
            }
        }
    }

    private void tick() {
        try {
            if (editCursor >= edits.size()) {
                if (phaseCursor >= phases.size()) {
                    finish();
                    return;
                }
                Phase phase = phases.get(phaseCursor++);
                edits = phase.supplier().get();
                OverworldCampaignAudit148.validatePhase(phase.name(), edits);
                editCursor = 0;
                plugin.getLogger().info("[ArlightBingo 1.48.15] " + phase.name()
                        + " · " + edits.size() + " cambios");
                return;
            }

            int maximum = Math.max(250, plugin.getConfig().getInt(
                    "template-worlds.overworld.campaign-layout-1-48.blocks-per-tick", 1400));
            long deadline = System.nanoTime() + Math.max(2L, plugin.getConfig().getLong(
                    "template-worlds.overworld.campaign-layout-1-48.max-millis-per-tick", 6L))
                    * 1_000_000L;
            int applied = 0;
            while (editCursor < edits.size() && applied < maximum && System.nanoTime() < deadline) {
                BlockEdit edit = edits.get(editCursor++);
                if (edit.y() >= world.getMinHeight() && edit.y() < world.getMaxHeight()) {
                    var block = world.getBlockAt(edit.x(), edit.y(), edit.z());
                    if (block.getType() != edit.material()) block.setType(edit.material(), false);
                    if (edit.data() != null) {
                        block.setBlockData(Bukkit.createBlockData(edit.data()), false);
                    }
                    report.appliedBlocks++;
                }
                applied++;
            }
        } catch (Throwable error) {
            if (task != null) task.cancel();
            failure.accept(error);
        }
    }

    private void finish() throws IOException {
        if (task != null) task.cancel();
        Layout layout = layout();
        auditSummary = OverworldCampaignAudit148.auditWorld(world, auditRegistry,
                village, citadel, portal, outerAltar, ritualGate);
        world.setSpawnLocation(safeVillageSpawn);
        writeLayout(layout);
        updateTemplateMarker();
        writeReport();
        Files.writeString(folder.resolve(OverworldCampaignLandscape148.DONE_MARKER),
                "version=1.48.15\ncompleted=" + System.currentTimeMillis() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.deleteIfExists(folder.resolve(OverworldCampaignLandscape148.PROGRESS_MARKER));
        completion.accept(layout);
        plugin.getLogger().info("[ArlightBingo] Overworld 1.48.15 completado: base limpia, "
                + "interiores funcionales, lugares decorados y auditoría estructural completa.");
        Bukkit.broadcast(ChatColor.GREEN
                        + "[Bingo] La plantilla Overworld 1.48.15 terminó su construcción limpia.",
                "arlightbingo.admin");
    }

    private Layout layout() {
        return new Layout(safeVillageSpawn.clone(), residential.floor(world), commercial.floor(world),
                military.floor(world), citadel.floor(world), boss.floor(world), portal.floor(world),
                outerAltar.clone(), invocationAltar.clone(), ritualGate.clone());
    }

    private void writeLayout(Layout layout) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1.48.15");
        properties.setProperty("village", format(layout.village()));
        properties.setProperty("residential", format(layout.residential()));
        properties.setProperty("commercial", format(layout.commercial()));
        properties.setProperty("military", format(layout.military()));
        properties.setProperty("citadel", format(layout.citadel()));
        properties.setProperty("boss", format(layout.boss()));
        properties.setProperty("portal", format(layout.portal()));
        properties.setProperty("outerAltar", format(layout.outerAltar()));
        properties.setProperty("invocationAltar", format(layout.invocationAltar()));
        properties.setProperty("ritualGate", format(layout.ritualGate()));
        properties.setProperty("progression",
                "home_emblem,trade_emblem,emerald_core,outer_gate,inner_invocation,boss");
        try (Writer writer = Files.newBufferedWriter(
                folder.resolve(OverworldCampaignLandscape148.LAYOUT_MARKER),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(writer, "ArlightBingo 1.48.15 Overworld campaign layout");
        }
    }

    private void updateTemplateMarker() throws IOException {
        Path pending = folder.resolve(OverworldCampaignLandscape148.PENDING_TEMPLATE_MARKER);
        Path marker = folder.resolve(OverworldCampaignLandscape148.TEMPLATE_MARKER);
        Path source = Files.isRegularFile(pending) ? pending : marker;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        properties.setProperty("village", format(safeVillageSpawn));
        properties.setProperty("dungeon", format(citadel.floor(world)));
        properties.setProperty("boss", format(new Location(world,
                invocationAltar.getBlockX(), invocationAltar.getBlockY() + 5,
                invocationAltar.getBlockZ())));
        properties.setProperty("portal", format(portal.floor(world)));
        properties.setProperty("structureRevision",
                "1.48.15-critical-routes-soft-secondary-audit-1");
        properties.setProperty("villageArchitecture", "furnished_roles_pavilion_planters_v8");
        properties.setProperty("islandRestoration", "clustered_ground_furnished_landmarks_v8");
        properties.setProperty("roadNetwork", "gate_clearance_furnished_routes_v14");
        properties.setProperty("towerArchitecture", "finalized_doors_ladders_landings_v6");
        properties.setProperty("fortificationFoundation", "continuous_twelve_block_core_v1");
        properties.setProperty("bossArena", "complete_gatehouse_wall_wings_terraced_approach_v8");
        properties.setProperty("villageSafe", "true");
        properties.setProperty("villageSafeRadius", "116");
        properties.setProperty("structuralAudit", "passed_1_48_15_critical_routes_soft_secondary_audit");
        properties.setProperty("campaignLayout", "1.48.15");
        try (Writer writer = Files.newBufferedWriter(marker, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(writer,
                    "ArlightBingo Overworld template updated by campaign layout 1.48.15");
        }
        Files.deleteIfExists(pending);
    }

    private void writeReport() throws IOException {
        String text = "ARLIGHTBINGO 1.48.15 - RUTAS CRÍTICAS Y SECUNDARIAS\n"
                + "columnas-isla-restauradas=" + report.restoredColumns + "\n"
                + "columnas-zonas-adaptadas=" + report.shapedColumns + "\n"
                + "bloques-heredados-retirados=" + report.clearedBlocks + "\n"
                + "bloques-aplicados=" + report.appliedBlocks + "\n"
                + "edificios-pueblo=" + report.villageBuildings + "\n"
                + "estructuras-principales=" + report.structures + "\n"
                + "caminos=" + report.roads + "\n"
                + "decoraciones=" + report.decorations + "\n"
                + "auditoria-casas-cerradas=" + auditSummary.houses() + "\n"
                + "auditoria-torres-accesibles=" + auditSummary.towers() + "\n"
                + "auditoria-chimeneas-apoyadas=" + auditSummary.chimneys() + "\n"
                + "auditoria-macetas-apoyadas=" + auditSummary.planters() + "\n"
                + "auditoria-muestras-camino=" + auditSummary.roadSamples() + "\n"
                + "auditoria-luces-pueblo=" + auditSummary.villageLights() + "\n"
                + "auditoria-luces-interiores=" + auditSummary.interiorLights() + "\n"
                + "auditoria-mobiliario-interior=" + auditSummary.furnishings() + "\n"
                + "auditoria-cultivos=" + auditSummary.crops() + "\n"
                + "auditoria-faroles-sin-soporte=" + auditSummary.unsupportedLanterns() + "\n"
                + "pueblo=" + format(safeVillageSpawn) + "\n"
                + "residencial=" + format(residential.floor(world)) + "\n"
                + "comercial=" + format(commercial.floor(world)) + "\n"
                + "militar=" + format(military.floor(world)) + "\n"
                + "ciudadela=" + format(citadel.floor(world)) + "\n"
                + "puerta-ritual=" + format(ritualGate) + "\n"
                + "altar-exterior=" + format(outerAltar) + "\n"
                + "altar-invocacion=" + format(invocationAltar) + "\n"
                + "jefe=" + format(boss.floor(world)) + "\n";
        Files.writeString(folder.resolve(OverworldCampaignLandscape148.REPORT_FILE), text,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private record Phase(String name, Supplier<List<BlockEdit>> supplier) { }
}
