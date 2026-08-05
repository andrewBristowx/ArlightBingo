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

final class OverworldCampaignBuilder146 {
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
    private final List<Phase> phases = new ArrayList<>();

    private BukkitTask task;
    private List<BlockEdit> edits = List.of();
    private int phaseCursor;
    private int editCursor;

    OverworldCampaignBuilder146(BingoPlugin plugin, World world, Path folder,
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

        int plannedCitadelX = plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-46.citadel-x", 140);
        int plannedCitadelZ = plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-46.citadel-z", 30);
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
        int y = OverworldCampaignTerrain146.medianTerrainY(world, x, z,
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

        addVillageShapePhases("pueblo inicial", village, 67, 86);
        phases.add(new Phase("reconstruyendo pueblo inicial vivo",
                () -> OverworldCampaignStructures146.village(village, report)));

        addShapePhases("aldea residencial", residential, 43, 57);
        phases.add(new Phase("construyendo aldea residencial",
                () -> OverworldCampaignStructures146.residential(residential, report)));
        addShapePhases("mercado de la calzada", commercial, 45, 59);
        phases.add(new Phase("construyendo mercado de la calzada",
                () -> OverworldCampaignStructures146.commercial(commercial, report)));
        addShapePhases("bastión militar", military, 48, 62);
        phases.add(new Phase("construyendo bastión militar",
                () -> OverworldCampaignStructures146.military(military, report)));
        addShapePhases("ciudadela", citadel, 54, 68);
        phases.add(new Phase("construyendo ciudadela",
                () -> OverworldCampaignStructures146.citadel(citadel, report)));
        addShapePhases("arena sellada", boss, 52, 67);
        phases.add(new Phase("construyendo puerta monumental y arena",
                () -> OverworldCampaignStructures146.bossArena(
                        boss, outerAltar, invocationAltar, ritualGate, report)));
        addShapePhases("santuario del portal", portal, 24, 34);
        phases.add(new Phase("construyendo santuario del portal",
                () -> OverworldCampaignStructures146.portal(portal, report)));
        phases.add(new Phase("conectando pueblo, asentamientos y arena",
                () -> OverworldCampaignTerrain146.buildRoadNetwork(world, layout(), village,
                        residential, commercial, military, citadel, boss, portal, report)));
        phases.add(new Phase("decorando la isla reconstruida",
                () -> OverworldCampaignStructures146.decorations(
                        village, residential, commercial, military, citadel, report)));
    }

    private void addRestorePhases(String name, Site site) {
        int strip = Math.max(6, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-46.planning-strip-width", 10));
        for (int minimum = -site.radius(); minimum <= site.radius(); minimum += strip) {
            int from = minimum;
            int to = Math.min(site.radius(), minimum + strip - 1);
            phases.add(new Phase("reconstruyendo isla: " + name + " [" + from + ".." + to + "]",
                    () -> OverworldCampaignTerrain146.restoreLegacySite(
                            world, site, report, from, to)));
        }
    }

    private void addVillageShapePhases(String name, Site site, int flatRadius, int blendRadius) {
        int strip = Math.max(6, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-46.planning-strip-width", 10));
        for (int minimum = -blendRadius; minimum <= blendRadius; minimum += strip) {
            int from = minimum;
            int to = Math.min(blendRadius, minimum + strip - 1);
            phases.add(new Phase("aterrazando " + name + " [" + from + ".." + to + "]",
                    () -> OverworldCampaignTerrain146.shapeVillage(
                            world, site, flatRadius, blendRadius, report, from, to)));
        }
    }

    private void addShapePhases(String name, Site site, int flatRadius, int blendRadius) {
        int strip = Math.max(6, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-46.planning-strip-width", 10));
        for (int minimum = -blendRadius; minimum <= blendRadius; minimum += strip) {
            int from = minimum;
            int to = Math.min(blendRadius, minimum + strip - 1);
            phases.add(new Phase("adaptando " + name + " [" + from + ".." + to + "]",
                    () -> OverworldCampaignTerrain146.shapeSite(
                            world, site, flatRadius, blendRadius, report, from, to)));
        }
    }

    void start() {
        purgeLegacyEntities();
        plugin.getLogger().info("[ArlightBingo] Diseño Overworld 1.46.0: "
                + phases.size() + " fases reanudables.");
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void purgeLegacyEntities() {
        List<Site> legacy = List.of(legacyCitadel, legacyResidential, legacyCommercial,
                legacyMilitary, legacyBoss, legacyPortal);
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
                editCursor = 0;
                plugin.getLogger().info("[ArlightBingo 1.46] " + phase.name()
                        + " · " + edits.size() + " cambios");
                return;
            }

            int maximum = Math.max(250, plugin.getConfig().getInt(
                    "template-worlds.overworld.campaign-layout-1-46.blocks-per-tick", 1400));
            long deadline = System.nanoTime() + Math.max(2L, plugin.getConfig().getLong(
                    "template-worlds.overworld.campaign-layout-1-46.max-millis-per-tick", 6L))
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
        writeLayout(layout);
        updateTemplateMarker();
        writeReport();
        Files.writeString(folder.resolve(OverworldCampaignLandscape146.DONE_MARKER),
                "version=1.46.0\ncompleted=" + System.currentTimeMillis() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.deleteIfExists(folder.resolve(OverworldCampaignLandscape146.PROGRESS_MARKER));
        completion.accept(layout);
        plugin.getLogger().info("[ArlightBingo] Overworld 1.46.0 completado: isla restaurada, "
                + "pueblo nuevo, entrada ritual y arena de dos fases.");
        Bukkit.broadcast(ChatColor.GREEN
                        + "[Bingo] La plantilla Overworld 1.46.0 terminó su reconstrucción.",
                "arlightbingo.admin");
    }

    private Layout layout() {
        return new Layout(safeVillageSpawn.clone(), residential.floor(world), commercial.floor(world),
                military.floor(world), citadel.floor(world), boss.floor(world), portal.floor(world),
                outerAltar.clone(), invocationAltar.clone(), ritualGate.clone());
    }

    private void writeLayout(Layout layout) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1.46.0");
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
                folder.resolve(OverworldCampaignLandscape146.LAYOUT_MARKER),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(writer, "ArlightBingo 1.46.0 Overworld campaign layout");
        }
    }

    private void updateTemplateMarker() throws IOException {
        Path pending = folder.resolve(OverworldCampaignLandscape146.PENDING_TEMPLATE_MARKER);
        Path marker = folder.resolve(OverworldCampaignLandscape146.TEMPLATE_MARKER);
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
                "1.46.0-island-reconstruction-village-boss-gate-1");
        properties.setProperty("villageArchitecture", "terraced_living_safe_village_v1");
        properties.setProperty("islandRestoration", "boundary_interpolated_legacy_cleanup_v1");
        properties.setProperty("roadNetwork", "supported_campaign_routes_v6");
        properties.setProperty("bossArena", "monumental_gate_two_stage_ritual_v2");
        properties.setProperty("campaignLayout", "1.46.0");
        try (Writer writer = Files.newBufferedWriter(marker, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(writer,
                    "ArlightBingo Overworld template updated by campaign layout 1.46.0");
        }
        Files.deleteIfExists(pending);
    }

    private void writeReport() throws IOException {
        String text = "ARLIGHTBINGO 1.46.0 - ISLA Y CAMPAÑA OVERWORLD\n"
                + "columnas-isla-restauradas=" + report.restoredColumns + "\n"
                + "columnas-zonas-adaptadas=" + report.shapedColumns + "\n"
                + "bloques-heredados-retirados=" + report.clearedBlocks + "\n"
                + "bloques-aplicados=" + report.appliedBlocks + "\n"
                + "edificios-pueblo=" + report.villageBuildings + "\n"
                + "estructuras-principales=" + report.structures + "\n"
                + "caminos=" + report.roads + "\n"
                + "decoraciones=" + report.decorations + "\n"
                + "pueblo=" + format(safeVillageSpawn) + "\n"
                + "residencial=" + format(residential.floor(world)) + "\n"
                + "comercial=" + format(commercial.floor(world)) + "\n"
                + "militar=" + format(military.floor(world)) + "\n"
                + "ciudadela=" + format(citadel.floor(world)) + "\n"
                + "puerta-ritual=" + format(ritualGate) + "\n"
                + "altar-exterior=" + format(outerAltar) + "\n"
                + "altar-invocacion=" + format(invocationAltar) + "\n"
                + "jefe=" + format(boss.floor(world)) + "\n";
        Files.writeString(folder.resolve(OverworldCampaignLandscape146.REPORT_FILE), text,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private record Phase(String name, Supplier<List<BlockEdit>> supplier) { }
}
