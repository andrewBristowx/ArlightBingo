package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
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

import static com.arlight.bingo.listeners.OverworldCampaignModel145.*;

final class OverworldCampaignBuilder145 {
    private final BingoPlugin plugin;
    private final World world;
    private final Path folder;
    private final Location villageSpawn;
    private final Site village;
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
    private final Location altar;
    private final Location ritualGate;
    private final Consumer<Layout> completion;
    private final Consumer<Throwable> failure;
    private final Report report = new Report();
    private final List<Phase> phases = new ArrayList<>();

    private BukkitTask task;
    private List<BlockEdit> edits = List.of();
    private int phaseCursor;
    private int editCursor;

    OverworldCampaignBuilder145(BingoPlugin plugin, World world, Path folder,
                                Location villageSpawn, Location citadelAnchor,
                                Location bossAnchor, Location portalAnchor,
                                Consumer<Layout> completion, Consumer<Throwable> failure) {
        this.plugin = plugin;
        this.world = world;
        this.folder = folder;
        this.villageSpawn = villageSpawn.clone();
        this.completion = completion;
        this.failure = failure;

        Location villageCenter = villageSpawn.clone().add(-3.0D, -1.0D, -15.0D);
        this.village = site("village", villageCenter, 82, Style.RESIDENTIAL);

        // Las coordenadas 1.43/1.44 colocaban la capital en el extremo oriental de la isla.
        // El bastión este terminaba sobre el océano y la arena se superponía con la puerta.
        // Se guardan sus huellas sólo para retirarlas de forma controlada antes de construir.
        this.legacyCitadel = site("legacy-citadel", citadelAnchor, 102, Style.CITADEL);
        this.legacyResidential = site("legacy-residential",
                citadelAnchor.clone().add(-170, 0, 100), 64, Style.RESIDENTIAL);
        this.legacyCommercial = site("legacy-commercial",
                citadelAnchor.clone().add(0, 0, -190), 66, Style.COMMERCIAL);
        this.legacyMilitary = site("legacy-military",
                citadelAnchor.clone().add(170, 0, 80), 68, Style.MILITARY);
        this.legacyBoss = site("legacy-boss", bossAnchor, 72, Style.BOSS);
        this.legacyPortal = site("legacy-portal", portalAnchor, 40, Style.PORTAL);

        // Macrodistribución 1.45: todo queda dentro del radio terrestre de la isla y cada
        // asentamiento conserva los offsets que usa OverworldCampaignProgression.
        int plannedCitadelX = plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-45.citadel-x", 140);
        int plannedCitadelZ = plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-45.citadel-z", 30);
        Location plannedCitadel = new Location(world, plannedCitadelX, citadelAnchor.getY(), plannedCitadelZ);
        Location plannedBoss = plannedCitadel.clone().add(0, 0, 210);
        Location plannedPortal = plannedCitadel.clone().add(0, 0, 320);

        this.citadel = site("citadel", plannedCitadel, 68, Style.CITADEL);
        this.residential = site("residential", plannedCitadel.clone().add(-170, 0, 100), 55, Style.RESIDENTIAL);
        this.commercial = site("commercial", plannedCitadel.clone().add(0, 0, -190), 57, Style.COMMERCIAL);
        this.military = site("military", plannedCitadel.clone().add(170, 0, 80), 60, Style.MILITARY);
        this.boss = site("boss", plannedBoss, 62, Style.BOSS);
        this.portal = site("portal", plannedPortal, 32, Style.PORTAL);
        this.altar = new Location(world, boss.x(), boss.baseY() + 6, boss.z() - 32);
        this.ritualGate = new Location(world, boss.x(), boss.baseY() + 1, boss.z() - 20);
        preparePhases();
    }

    private Site site(String id, Location rough, int radius, Style style) {
        int x = rough.getBlockX(), z = rough.getBlockZ();
        int y = OverworldCampaignTerrain145.medianTerrainY(world, x, z, Math.min(42, radius - 8));
        y = Math.max(world.getMinHeight() + 12, Math.min(world.getMaxHeight() - 48, y));
        return new Site(id, x, y, z, radius, style);
    }

    private void preparePhases() {
        addRetirePhases("capital heredada del borde", legacyCitadel);
        addRetirePhases("asentamiento residencial heredado", legacyResidential);
        addRetirePhases("mercado heredado", legacyCommercial);
        addRetirePhases("bastión flotante heredado", legacyMilitary);
        addRetirePhases("arena y casas heredadas", legacyBoss);
        addRetirePhases("santuario heredado", legacyPortal);

        addShapePhases("aldea residencial", residential, 43, 57);
        phases.add(new Phase("construyendo aldea residencial",
                () -> OverworldCampaignStructures145.residential(residential, report)));
        addShapePhases("mercado de la calzada", commercial, 45, 59);
        phases.add(new Phase("construyendo mercado de la calzada",
                () -> OverworldCampaignStructures145.commercial(commercial, report)));
        addShapePhases("bastión militar", military, 48, 62);
        phases.add(new Phase("construyendo bastión militar",
                () -> OverworldCampaignStructures145.military(military, report)));
        addShapePhases("ciudadela", citadel, 54, 68);
        phases.add(new Phase("construyendo ciudadela",
                () -> OverworldCampaignStructures145.citadel(citadel, report)));
        addShapePhases("arena reservada", boss, 50, 66);
        phases.add(new Phase("construyendo arena y altar",
                () -> OverworldCampaignStructures145.bossArena(
                        boss, altar.getBlockY(), altar.getBlockZ(),
                        ritualGate.getBlockY(), ritualGate.getBlockZ(), report)));
        addShapePhases("santuario del portal", portal, 24, 34);
        phases.add(new Phase("construyendo santuario del portal",
                () -> OverworldCampaignStructures145.portal(portal, report)));
        phases.add(new Phase("conectando asentamientos",
                () -> OverworldCampaignTerrain145.buildRoadNetwork(world, layout(), village,
                        residential, commercial, military, citadel, boss, portal, report)));
        phases.add(new Phase("decorando campaña",
                () -> OverworldCampaignStructures145.decorations(
                        residential, commercial, military, citadel, report)));
    }

    private void addRetirePhases(String name, Site site) {
        int strip = Math.max(8, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-45.planning-strip-width", 14));
        for (int minimum = -site.radius(); minimum <= site.radius(); minimum += strip) {
            int from = minimum;
            int to = Math.min(site.radius(), minimum + strip - 1);
            phases.add(new Phase("retirando " + name + " [" + from + ".." + to + "]",
                    () -> OverworldCampaignTerrain145.retireLegacySite(
                            world, site, report, from, to)));
        }
    }

    private void addShapePhases(String name, Site site, int flatRadius, int blendRadius) {
        int strip = Math.max(8, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-45.planning-strip-width", 14));
        for (int minimum = -blendRadius; minimum <= blendRadius; minimum += strip) {
            int from = minimum;
            int to = Math.min(blendRadius, minimum + strip - 1);
            phases.add(new Phase("adaptando " + name + " [" + from + ".." + to + "]",
                    () -> OverworldCampaignTerrain145.shapeSite(
                            world, site, flatRadius, blendRadius, report, from, to)));
        }
    }

    void start() {
        plugin.getLogger().info("[ArlightBingo] Diseño Overworld 1.45.0: " + phases.size() + " fases.");
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
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
                plugin.getLogger().info("[ArlightBingo 1.45] " + phase.name()
                        + " · " + edits.size() + " cambios");
                return;
            }

            int maximum = Math.max(300, plugin.getConfig().getInt(
                    "template-worlds.overworld.campaign-layout-1-45.blocks-per-tick", 1800));
            long deadline = System.nanoTime() + Math.max(2L, plugin.getConfig().getLong(
                    "template-worlds.overworld.campaign-layout-1-45.max-millis-per-tick", 7L)) * 1_000_000L;
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
        Files.writeString(folder.resolve(OverworldCampaignLandscape145.DONE_MARKER),
                "version=1.45.0\ncompleted=" + System.currentTimeMillis() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.deleteIfExists(folder.resolve(OverworldCampaignLandscape145.PROGRESS_MARKER));
        completion.accept(layout);
        plugin.getLogger().info("[ArlightBingo] Overworld 1.45.0 completado: asentamientos separados, "
                + "caminos, ciudadela, altar y arena reservada.");
        Bukkit.broadcast(ChatColor.GREEN + "[Bingo] La plantilla Overworld 1.45.0 terminó su diseño de campaña.",
                "arlightbingo.admin");
    }

    private Layout layout() {
        return new Layout(villageSpawn.clone(), residential.floor(world), commercial.floor(world),
                military.floor(world), citadel.floor(world), boss.floor(world), portal.floor(world),
                altar.clone(), ritualGate.clone());
    }

    private void writeLayout(Layout layout) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1.45.0");
        properties.setProperty("village", format(layout.village()));
        properties.setProperty("residential", format(layout.residential()));
        properties.setProperty("commercial", format(layout.commercial()));
        properties.setProperty("military", format(layout.military()));
        properties.setProperty("citadel", format(layout.citadel()));
        properties.setProperty("boss", format(layout.boss()));
        properties.setProperty("portal", format(layout.portal()));
        properties.setProperty("altar", format(layout.altar()));
        properties.setProperty("ritualGate", format(layout.ritualGate()));
        properties.setProperty("progression", "home_emblem,trade_emblem,emerald_core,ritual_altar,boss");
        try (Writer writer = Files.newBufferedWriter(folder.resolve(OverworldCampaignLandscape145.LAYOUT_MARKER),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(writer, "ArlightBingo 1.45.0 Overworld campaign layout");
        }
    }

    private void updateTemplateMarker() throws IOException {
        Path pending = folder.resolve(OverworldCampaignLandscape145.PENDING_TEMPLATE_MARKER);
        Path marker = folder.resolve(OverworldCampaignLandscape145.TEMPLATE_MARKER);
        Path source = Files.isRegularFile(pending) ? pending : marker;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        properties.setProperty("dungeon", format(citadel.floor(world)));
        properties.setProperty("boss", format(new Location(world, boss.x(), boss.baseY() + 5, boss.z())));
        properties.setProperty("portal", format(portal.floor(world)));
        properties.setProperty("structureRevision", "1.45.0-separated-settlements-ritual-arena-1");
        properties.setProperty("capitalArchitecture", "separated_campaign_settlements_v5");
        properties.setProperty("roadNetwork", "connected_settlement_routes_v5");
        properties.setProperty("bossArena", "reserved_ritual_amphitheatre_v1");
        properties.setProperty("campaignLayout", "1.45.0");
        try (Writer writer = Files.newBufferedWriter(marker, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(writer, "ArlightBingo Overworld template updated by campaign layout 1.45.0");
        }
        Files.deleteIfExists(pending);
    }

    private void writeReport() throws IOException {
        String text = "ARLIGHTBINGO 1.45.0 - CAMPAÑA OVERWORLD RECONSTRUIDA\n"
                + "columnas-adaptadas=" + report.shapedColumns + "\n"
                + "bloques-aplicados=" + report.appliedBlocks + "\n"
                + "estructuras-principales=" + report.structures + "\n"
                + "caminos=" + report.roads + "\n"
                + "decoraciones=" + report.decorations + "\n"
                + "residencial=" + format(residential.floor(world)) + "\n"
                + "comercial=" + format(commercial.floor(world)) + "\n"
                + "militar=" + format(military.floor(world)) + "\n"
                + "ciudadela=" + format(citadel.floor(world)) + "\n"
                + "jefe=" + format(boss.floor(world)) + "\n"
                + "altar=" + format(altar) + "\n";
        Files.writeString(folder.resolve(OverworldCampaignLandscape145.REPORT_FILE), text,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private record Phase(String name, Supplier<List<BlockEdit>> supplier) { }
}
