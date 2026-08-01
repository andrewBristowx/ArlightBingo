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
    private final Site legacyCitadel;
    private final Site legacyResidential;
    private final Site legacyCommercial;
    private final Site legacyMilitary;
    private final Site legacyBoss;
    private final Site legacyPortal;
    private final Site legacyVillageFragment;
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

        // Enlarged restoration footprints cover the cut walls and floating fragments seen
        // around the eastern coast instead of cleaning only the original building centers.
        this.legacyCitadel = site("legacy-citadel", citadelAnchor, 132, Style.CITADEL);
        this.legacyResidential = site("legacy-residential",
                citadelAnchor.clone().add(-170, 0, 100), 84, Style.RESIDENTIAL);
        this.legacyCommercial = site("legacy-commercial",
                citadelAnchor.clone().add(0, 0, -190), 90, Style.COMMERCIAL);
        this.legacyMilitary = site("legacy-military",
                citadelAnchor.clone().add(170, 0, 80), 98, Style.MILITARY);
        this.legacyBoss = site("legacy-boss", bossAnchor, 94, Style.BOSS);
        this.legacyPortal = site("legacy-portal", portalAnchor, 62, Style.PORTAL);
        // Fragmento heredado visto al sureste del pueblo: una torre/techo incompleto
        // que queda fuera del radio principal. Se limpia antes de modelar el pueblo
        // y se reconstruye desde el terreno sano circundante para no dejar cráteres.
        this.legacyVillageFragment = site("legacy-village-fragment",
                villageCenter.clone().add(-42, 0, 96), 32, Style.VILLAGE);

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
        this.outerAltar = new Location(world, boss.x(), boss.baseY() + 1, boss.z() - 64);
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
        addRestorePhases("capital y murallas heredadas", legacyCitadel);
        addRestorePhases("asentamiento residencial heredado", legacyResidential);
        addRestorePhases("mercado heredado", legacyCommercial);
        addRestorePhases("bastión costero heredado", legacyMilitary);
        addRestorePhases("arena y viviendas heredadas", legacyBoss);
        addRestorePhases("santuario heredado", legacyPortal);
        addRestorePhases("torre y tejados heredados del pueblo", legacyVillageFragment);

        // First finish every terrain surface. Routes are then laid on terrain only, never on roofs.
        addVillageShapePhases("pueblo inicial", village, 67, 94);
        addShapePhases("aldea residencial", residential, 43, 66);
        addShapePhases("mercado de la calzada", commercial, 45, 68);
        addShapePhases("bastión militar", military, 48, 74);
        addShapePhases("ciudadela", citadel, 54, 82);
        addShapePhases("arena sellada", boss, 52, 76);
        addShapePhases("santuario del portal", portal, 24, 44);
        phases.add(new Phase("reparando costa sin crear masas circulares",
                () -> OverworldCampaignTerrain148.polishCoastlines(
                        world, village, portal, report)));
        phases.add(new Phase("conectando pueblo, asentamientos y arena",
                () -> OverworldCampaignTerrain148.buildRoadNetwork(world, layout(), village,
                        residential, commercial, military, citadel, boss, portal, report,
                        auditRegistry)));

        phases.add(new Phase("reconstruyendo pueblo inicial vivo",
                () -> OverworldCampaignStructures148.village(village, report, auditRegistry)));
        phases.add(new Phase("construyendo aldea residencial ocupada",
                () -> OverworldCampaignStructures148.residential(residential, report, auditRegistry)));
        phases.add(new Phase("construyendo mercado ocupado",
                () -> OverworldCampaignStructures148.commercial(commercial, report, auditRegistry)));
        phases.add(new Phase("construyendo bastión militar",
                () -> OverworldCampaignStructures148.military(military, report, auditRegistry)));
        phases.add(new Phase("construyendo ciudadela apoyada",
                () -> OverworldCampaignStructures148.citadel(citadel, report, auditRegistry)));
        phases.add(new Phase("construyendo puerta monumental y arena aprobada",
                () -> OverworldCampaignStructures148.bossArena(
                        boss, outerAltar, invocationAltar, ritualGate, report)));
        phases.add(new Phase("construyendo santuario del portal",
                () -> OverworldCampaignStructures148.portal(portal, report)));
        phases.add(new Phase("integrando murallas y accesos con el relieve",
                () -> OverworldCampaignTerrain148.integrateFortifications(
                        world, village, residential, military, citadel, boss, portal, report)));
        phases.add(new Phase("decorando la isla reconstruida",
                () -> OverworldCampaignStructures148.decorations(
                        world, village, residential, commercial, military, citadel, report)));
        phases.add(new Phase("restableciendo corredores auditados",
                () -> OverworldCampaignAudit148.repairRoadCorridors(auditRegistry)));
    }

    private void addRestorePhases(String name, Site site) {
        int strip = Math.max(6, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.planning-strip-width", 10));
        for (int minimum = -site.radius(); minimum <= site.radius(); minimum += strip) {
            int from = minimum;
            int to = Math.min(site.radius(), minimum + strip - 1);
            phases.add(new Phase("reconstruyendo isla: " + name + " [" + from + ".." + to + "]",
                    () -> OverworldCampaignTerrain148.restoreLegacySite(
                            world, site, report, from, to)));
        }
    }

    private void addVillageShapePhases(String name, Site site, int flatRadius, int blendRadius) {
        int strip = Math.max(6, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.planning-strip-width", 10));
        for (int minimum = -blendRadius; minimum <= blendRadius; minimum += strip) {
            int from = minimum;
            int to = Math.min(blendRadius, minimum + strip - 1);
            phases.add(new Phase("aterrazando " + name + " [" + from + ".." + to + "]",
                    () -> OverworldCampaignTerrain148.shapeVillage(
                            world, site, flatRadius, blendRadius, report, from, to)));
        }
    }

    private void addShapePhases(String name, Site site, int flatRadius, int blendRadius) {
        int strip = Math.max(6, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.planning-strip-width", 10));
        for (int minimum = -blendRadius; minimum <= blendRadius; minimum += strip) {
            int from = minimum;
            int to = Math.min(blendRadius, minimum + strip - 1);
            phases.add(new Phase("adaptando " + name + " [" + from + ".." + to + "]",
                    () -> OverworldCampaignTerrain148.shapeSite(
                            world, site, flatRadius, blendRadius, report, from, to)));
        }
    }

    void start() {
        purgeLegacyEntities();
        plugin.getLogger().info("[ArlightBingo] Diseño Overworld 1.48.1: "
                + phases.size() + " fases reanudables.");
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void purgeLegacyEntities() {
        List<Site> legacy = List.of(legacyCitadel, legacyResidential, legacyCommercial,
                legacyMilitary, legacyBoss, legacyPortal, legacyVillageFragment);
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (!(entity instanceof LivingEntity) || entity instanceof Player
                    || entity.getScoreboardTags().contains("arlightbingo_keep")) continue;
            for (Site site : legacy) {
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
                plugin.getLogger().info("[ArlightBingo 1.48] " + phase.name()
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
                    world.getBlockAt(edit.x(), edit.y(), edit.z()).setType(edit.material(), false);
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
        writeLayout(layout);
        updateTemplateMarker();
        writeReport();
        Files.writeString(folder.resolve(OverworldCampaignLandscape148.DONE_MARKER),
                "version=1.48.1\ncompleted=" + System.currentTimeMillis() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.deleteIfExists(folder.resolve(OverworldCampaignLandscape148.PROGRESS_MARKER));
        completion.accept(layout);
        plugin.getLogger().info("[ArlightBingo] Overworld 1.48.1 completado: isla restaurada, "
                + "pueblo nuevo, entrada ritual y arena de dos fases.");
        Bukkit.broadcast(ChatColor.GREEN
                        + "[Bingo] La plantilla Overworld 1.48.1 terminó su reconstrucción.",
                "arlightbingo.admin");
    }

    private Layout layout() {
        return new Layout(safeVillageSpawn.clone(), residential.floor(world), commercial.floor(world),
                military.floor(world), citadel.floor(world), boss.floor(world), portal.floor(world),
                outerAltar.clone(), invocationAltar.clone(), ritualGate.clone());
    }

    private void writeLayout(Layout layout) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1.48.1");
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
            properties.store(writer, "ArlightBingo 1.48.1 Overworld campaign layout");
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
                "1.48.1-route-audit-loop-fix-1");
        properties.setProperty("villageArchitecture", "coherent_closed_varied_village_v3");
        properties.setProperty("islandRestoration", "adaptive_shoreline_legacy_cleanup_v3");
        properties.setProperty("roadNetwork", "terrain_first_audited_routes_v8");
        properties.setProperty("bossArena", "approved_arena_without_runtime_intrusion_v4");
        properties.setProperty("villageSafe", "true");
        properties.setProperty("villageSafeRadius", "116");
        properties.setProperty("structuralAudit", "passed_1_48");
        properties.setProperty("campaignLayout", "1.48.1");
        try (Writer writer = Files.newBufferedWriter(marker, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(writer,
                    "ArlightBingo Overworld template updated by campaign layout 1.48.1");
        }
        Files.deleteIfExists(pending);
    }

    private void writeReport() throws IOException {
        String text = "ARLIGHTBINGO 1.48.1 - ISLA Y CAMPAÑA OVERWORLD\n"
                + "columnas-isla-restauradas=" + report.restoredColumns + "\n"
                + "columnas-zonas-adaptadas=" + report.shapedColumns + "\n"
                + "bloques-heredados-retirados=" + report.clearedBlocks + "\n"
                + "bloques-aplicados=" + report.appliedBlocks + "\n"
                + "edificios-pueblo=" + report.villageBuildings + "\n"
                + "estructuras-principales=" + report.structures + "\n"
                + "caminos=" + report.roads + "\n"
                + "decoraciones=" + report.decorations + "\n"
                + "auditoria-casas-cerradas=" + auditSummary.houses() + "\n"
                + "auditoria-muestras-camino=" + auditSummary.roadSamples() + "\n"
                + "auditoria-luces-pueblo=" + auditSummary.villageLights() + "\n"
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
