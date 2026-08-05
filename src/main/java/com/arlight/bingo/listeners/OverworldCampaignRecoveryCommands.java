package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;

/**
 * Recuperación estructural posterior a la pérdida accidental de la campaña Overworld.
 *
 * Restaura únicamente edificios, caminos internos y conexiones de campaña. No vuelve a
 * generar la isla, no aplana el terreno, no modifica el puerto, la mina, los bosques ni la
 * decoración manual exterior. Antes de aplicar cualquier bloque ordena un snapshot completo
 * mediante el sistema seguro incorporado en 1.48.33.
 */
final class OverworldCampaignRecoveryCommands {

    private static final DateTimeFormatter SNAPSHOT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Set<String> CATEGORIES = Set.of(
            "all", "village", "residential", "commercial", "military",
            "citadel", "boss", "portal", "settlements", "fortress");

    private final BingoPlugin plugin;
    private BukkitTask waitTask;
    private BukkitTask applyTask;
    private String detail = "sin iniciar";
    private int cursor;
    private int total;
    private int changed;

    OverworldCampaignRecoveryCommands(BingoPlugin plugin) {
        this.plugin = plugin;
    }

    boolean matches(String rawCommand) {
        String[] args = tokenize(rawCommand);
        return args.length >= 4
                && args[0].equalsIgnoreCase("bingo")
                && args[1].equalsIgnoreCase("template")
                && args[2].equalsIgnoreCase("overworld")
                && args[3].equalsIgnoreCase("recover");
    }

    void execute(CommandSender sender, String rawCommand) {
        if (!sender.hasPermission("arlightbingo.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para recuperar la plantilla.");
            return;
        }
        String[] args = tokenize(rawCommand);
        String action = args.length >= 5 ? args[4].toLowerCase(Locale.ROOT) : "help";
        String category = args.length >= 6 ? args[5].toLowerCase(Locale.ROOT) : "all";

        if (action.equals("status")) {
            sender.sendMessage(ChatColor.AQUA + "Recuperación estructural: " + ChatColor.WHITE + status());
            return;
        }
        if (action.equals("cancel")) {
            cancel(sender);
            return;
        }
        if (action.equals("preview")) {
            preview(sender, category);
            return;
        }
        if (action.equals("apply") || action.equals("rebuild")) {
            start(sender, category);
            return;
        }
        if (CATEGORIES.contains(action)) {
            start(sender, action);
            return;
        }
        sendUsage(sender);
    }

    List<String> completions(String buffer) {
        String clean = buffer == null ? "" : buffer.trim();
        if (clean.startsWith("/")) clean = clean.substring(1);
        String[] args = clean.isBlank() ? new String[0] : clean.split("\\s+");
        boolean trailing = buffer != null && buffer.endsWith(" ");
        int logical = trailing ? args.length + 1 : args.length;
        if (logical == 5) {
            String prefix = trailing ? "" : args[4];
            return filter(List.of("preview", "apply", "all", "village", "settlements",
                    "residential", "commercial", "military", "fortress", "citadel",
                    "boss", "portal", "status", "cancel"), prefix);
        }
        if (logical == 6 && args.length >= 5
                && (args[4].equalsIgnoreCase("preview") || args[4].equalsIgnoreCase("apply")
                || args[4].equalsIgnoreCase("rebuild"))) {
            String prefix = trailing ? "" : args[5];
            return filter(List.copyOf(CATEGORIES), prefix);
        }
        return List.of();
    }

    void onWorldUnload(World world) {
        // El snapshot seguro descarga y recarga deliberadamente la plantilla.
    }

    private void preview(CommandSender sender, String requestedCategory) {
        String category = normalizeCategory(requestedCategory);
        if (category == null) {
            sendUsage(sender);
            return;
        }
        World world = templateWorld();
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "No se pudo cargar bingo_template_overworld.");
            return;
        }
        RecoveryPlan plan = plan(world, category);
        sender.sendMessage(ChatColor.AQUA + "=== Recuperación estructural Overworld 1.48.38 ===");
        sender.sendMessage(ChatColor.GRAY + "- categoría=" + ChatColor.WHITE + category);
        sender.sendMessage(ChatColor.GRAY + "- operaciones=" + ChatColor.WHITE + plan.edits().size());
        sender.sendMessage(ChatColor.GRAY + "- reconstruye=" + ChatColor.WHITE
                + String.join(", ", plan.categories()));
        sender.sendMessage(ChatColor.GREEN + "- protegido=" + ChatColor.WHITE
                + "puerto, mina, geoda, bosques, campamentos, corrupción exterior y relieve general");
        sender.sendMessage(ChatColor.YELLOW + "Se creará un snapshot completo antes de aplicar bloques.");
        sender.sendMessage(ChatColor.YELLOW + "Aplicar con: " + ChatColor.WHITE
                + "/bingo template overworld recover apply " + category);
    }

    private void start(CommandSender sender, String requestedCategory) {
        String category = normalizeCategory(requestedCategory);
        if (category == null) {
            sendUsage(sender);
            return;
        }
        if (busy()) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una recuperación activa: " + status());
            return;
        }

        String snapshot = "pre-structural-recovery-" + SNAPSHOT_TIME.format(Instant.now());
        Path expected = plugin.getDataFolder().toPath().resolve("template-snapshots")
                .resolve("overworld").resolve(snapshot);
        detail = "esperando snapshot " + snapshot;
        sender.sendMessage(ChatColor.YELLOW + "Creando snapshot completo antes de recuperar "
                + category + "...");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "bingo template overworld snapshot create " + snapshot);

        final int[] waited = {0};
        waitTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            waited[0]++;
            World world = Bukkit.getWorld(templateWorldName());
            if (Files.isDirectory(expected) && world != null) {
                BukkitTask finished = waitTask;
                waitTask = null;
                if (finished != null) finished.cancel();
                startApply(sender, world, category, snapshot);
                return;
            }
            if (waited[0] < 300) return;
            BukkitTask finished = waitTask;
            waitTask = null;
            if (finished != null) finished.cancel();
            detail = "cancelada: el snapshot previo no terminó";
            sender.sendMessage(ChatColor.RED + "No se aplicó ningún bloque porque el snapshot no terminó.");
        }, 20L, 20L);
    }

    private void startApply(CommandSender sender, World world, String category, String snapshot) {
        RecoveryPlan plan = plan(world, category);
        if (plan.edits().isEmpty()) {
            detail = "sin operaciones para " + category;
            sender.sendMessage(ChatColor.RED + "No se generaron operaciones de recuperación.");
            return;
        }
        purgeEntities(world, plan.sites());
        List<BlockEdit> edits = plan.edits();
        cursor = 0;
        total = edits.size();
        changed = 0;
        detail = "aplicando " + category + " 0/" + total;
        int perTick = Math.max(300, plugin.getConfig().getInt(
                "template-worlds.overworld.structural-recovery.blocks-per-tick", 1400));
        long maxNanos = Math.max(2L, plugin.getConfig().getLong(
                "template-worlds.overworld.structural-recovery.max-millis-per-tick", 6L))
                * 1_000_000L;

        applyTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long deadline = System.nanoTime() + maxNanos;
            int applied = 0;
            while (cursor < total && applied < perTick && System.nanoTime() < deadline) {
                if (apply(world, edits.get(cursor++))) changed++;
                applied++;
            }
            detail = "aplicando " + category + " " + cursor + "/" + total
                    + " · " + changed + " cambios reales";
            if (cursor < total) return;

            BukkitTask finished = applyTask;
            applyTask = null;
            if (finished != null) finished.cancel();
            writeRecoveryMarkers(world, category, snapshot, plan.categories());
            world.setAutoSave(true);
            world.save();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
            plugin.getOverworldTemplateManager().installMatchActors(world);
            detail = "recuperación completada · " + changed + " bloques cambiados";
            sender.sendMessage(ChatColor.GREEN + "Recuperación estructural terminada sin regenerar la isla.");
            sender.sendMessage(ChatColor.YELLOW + "Revisa todas las zonas antes de usar el commit seguro.");
        }, 1L, 1L);
        sender.sendMessage(ChatColor.GREEN + "Snapshot confirmado: " + snapshot + ".");
        sender.sendMessage(ChatColor.GREEN + "Recuperación iniciada con " + total + " operaciones.");
    }

    private RecoveryPlan plan(World world, String requestedCategory) {
        LinkedHashSet<String> selected = selectedCategories(requestedCategory);
        Report report = new Report();
        OverworldCampaignAudit148.Registry registry = new OverworldCampaignAudit148.Registry();

        int citadelX = plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.citadel-x", 140);
        int citadelZ = plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.citadel-z", 30);
        int villageX = plugin.getConfig().getInt("template-worlds.overworld.layout.village-x", -360);
        int villageZ = plugin.getConfig().getInt("template-worlds.overworld.layout.village-z", 0);

        Site village = site(world, "village", villageX, villageZ, 88, Style.VILLAGE);
        Site citadel = site(world, "citadel", citadelX, citadelZ, 68, Style.CITADEL);
        Site residential = site(world, "residential", citadelX - 170, citadelZ + 100,
                55, Style.RESIDENTIAL);
        Site commercial = site(world, "commercial", citadelX, citadelZ - 190,
                57, Style.COMMERCIAL);
        Site military = site(world, "military", citadelX + 170, citadelZ + 80,
                60, Style.MILITARY);
        Site boss = site(world, "boss", citadelX, citadelZ + 210, 64, Style.BOSS);
        Site portal = site(world, "portal", citadelX, citadelZ + 320, 32, Style.PORTAL);

        Location ritualGate = new Location(world, boss.x(), boss.baseY() + 1, boss.z() - 50);
        Location outerAltar = new Location(world, boss.x(),
                OverworldCampaignTerrain148.bossApproachWalkY(boss.baseY() + 1, 14),
                boss.z() - 64);
        Location invocationAltar = new Location(world, boss.x(), boss.baseY() + 1, boss.z());
        Location safeVillageSpawn = new Location(world, village.x(), village.baseY() + 2,
                village.z() + 63, 180.0F, 0.0F);
        Layout layout = new Layout(safeVillageSpawn, residential.floor(world), commercial.floor(world),
                military.floor(world), citadel.floor(world), boss.floor(world), portal.floor(world),
                outerAltar, invocationAltar, ritualGate);

        List<BlockEdit> edits = new ArrayList<>();
        if (requestedCategory.equals("all")) {
            edits.addAll(OverworldCampaignTerrain148.buildRoadNetwork(world, layout, village,
                    residential, commercial, military, citadel, boss, portal, report, registry));
        }
        if (selected.contains("village")) {
            edits.addAll(OverworldCampaignStructures148.village(world, village, report, registry));
        }
        if (selected.contains("residential")) {
            edits.addAll(OverworldCampaignStructures148.residential(world, residential, report, registry));
        }
        if (selected.contains("commercial")) {
            edits.addAll(OverworldCampaignStructures148.commercial(world, commercial, report, registry));
        }
        if (selected.contains("military")) {
            edits.addAll(OverworldCampaignStructures148.military(world, military, report, registry));
        }
        if (selected.contains("citadel")) {
            edits.addAll(OverworldCampaignStructures148.citadel(world, citadel, report, registry));
            edits.addAll(OverworldCampaignArchitecture148.restoreCitadelGreatHallInterior(citadel));
        }
        if (selected.contains("boss")) {
            edits.addAll(OverworldCampaignStructures148.bossArena(world, boss, outerAltar,
                    invocationAltar, ritualGate, report, registry));
            edits.addAll(OverworldCampaignArchitecture148.restoreBossColiseumClosure(
                    world, boss, ritualGate));
            edits.addAll(OverworldCampaignArchitecture148.restoreBossGatehouse(world, ritualGate));
            edits.addAll(OverworldCampaignArchitecture148.restoreBossRearTowers(world, boss));
            edits.addAll(OverworldCampaignArchitecture148.restoreClosedBossRitualGate(ritualGate));
            edits.addAll(OverworldCampaignTerrain148.reinforceBossWallFoundation(world, boss));
        }
        if (selected.contains("portal")) {
            edits.addAll(OverworldCampaignStructures148.portal(world, portal, report, registry));
            OverworldCampaignArchitecture148.portalSanctuaryFurnishings(
                    edits, portal.x(), portal.baseY() + 1, portal.z());
        }

        edits.addAll(OverworldCampaignAudit148.repairRegisteredHouseShells(registry));
        edits.addAll(OverworldCampaignAudit148.repairRegisteredTowerFoundations(world, registry));
        edits.addAll(OverworldCampaignAudit148.repairRegisteredTowerShells(registry));
        edits.addAll(OverworldCampaignAudit148.repairRegisteredVerticalAccess(registry));
        edits.addAll(OverworldCampaignAudit148.repairRegisteredTowerLandings(world, registry));
        edits.addAll(OverworldCampaignAudit148.repairRegisteredEntrances(world, registry));
        edits.addAll(OverworldCampaignAudit148.repairRegisteredChimneys(registry));
        registry.reconcileRouteMembershipsToPhysicalPlan();
        edits.addAll(OverworldCampaignAudit148.restoreAllRoadCorridorsStrict(world, registry));

        if (selected.contains("citadel") || selected.contains("boss") || selected.contains("portal")) {
            edits.addAll(OverworldCampaignArchitecture148.restoreCriticalPassageLighting(
                    citadel, ritualGate, portal));
        }
        return new RecoveryPlan(List.copyOf(selected), edits,
                selectedSites(selected, village, residential, commercial, military,
                        citadel, boss, portal));
    }

    private Site site(World world, String id, int x, int z, int radius, Style style) {
        int y = OverworldCampaignTerrain148.medianTerrainY(world, x, z,
                Math.min(46, Math.max(18, radius - 8)));
        y = Math.max(world.getMinHeight() + 12, Math.min(world.getMaxHeight() - 48, y));
        return new Site(id, x, y, z, radius, style);
    }

    private LinkedHashSet<String> selectedCategories(String category) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        switch (category) {
            case "all" -> selected.addAll(List.of("village", "residential", "commercial",
                    "military", "citadel", "boss", "portal"));
            case "settlements" -> selected.addAll(List.of("residential", "commercial", "military"));
            case "fortress" -> selected.addAll(List.of("citadel", "boss", "portal"));
            default -> selected.add(category);
        }
        return selected;
    }

    private List<Site> selectedSites(Set<String> selected, Site village, Site residential,
                                     Site commercial, Site military, Site citadel,
                                     Site boss, Site portal) {
        List<Site> sites = new ArrayList<>();
        if (selected.contains("village")) sites.add(village);
        if (selected.contains("residential")) sites.add(residential);
        if (selected.contains("commercial")) sites.add(commercial);
        if (selected.contains("military")) sites.add(military);
        if (selected.contains("citadel")) sites.add(citadel);
        if (selected.contains("boss")) sites.add(boss);
        if (selected.contains("portal")) sites.add(portal);
        return sites;
    }

    private void purgeEntities(World world, List<Site> sites) {
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (!(entity instanceof LivingEntity) || entity instanceof Player
                    || entity.getScoreboardTags().contains("arlightbingo_keep")) continue;
            for (Site site : sites) {
                double dx = entity.getLocation().getX() - site.x();
                double dz = entity.getLocation().getZ() - site.z();
                double radius = site.radius() + 10.0D;
                if (dx * dx + dz * dz <= radius * radius) {
                    entity.remove();
                    break;
                }
            }
        }
    }

    private boolean apply(World world, BlockEdit edit) {
        if (edit.y() < world.getMinHeight() || edit.y() >= world.getMaxHeight()) return false;
        Block block = world.getBlockAt(edit.x(), edit.y(), edit.z());
        boolean changedNow = block.getType() != edit.material();
        if (changedNow) block.setType(edit.material(), false);
        if (edit.data() != null) {
            var expected = Bukkit.createBlockData(edit.data());
            if (!block.getBlockData().matches(expected)) changedNow = true;
            block.setBlockData(expected, false);
        }
        return changedNow;
    }

    private void writeRecoveryMarkers(World world, String category, String snapshot,
                                      List<String> categories) {
        Path folder = world.getWorldFolder().toPath();
        String text = "version=1.48.38\n"
                + "recoveredAt=" + System.currentTimeMillis() + "\n"
                + "category=" + category + "\n"
                + "components=" + String.join(",", categories) + "\n"
                + "snapshot=" + snapshot + "\n"
                + "changedBlocks=" + changed + "\n"
                + "terrainRegenerated=false\n"
                + "portProtected=true\n"
                + "mineProtected=true\n";
        try {
            Files.writeString(folder.resolve("arlight-overworld-structural-recovery.properties"),
                    text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(folder.resolve("arlight-overworld-manual-commit.guard"),
                    text + "preventsAutomaticCampaignRebuild=true\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Path done = folder.resolve("arlight-overworld-campaign-1.48.done");
            if (!Files.isRegularFile(done)) {
                Files.writeString(done, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo escribir el marcador de recuperación: "
                    + error.getMessage());
        }
    }

    private World templateWorld() {
        return Bukkit.getWorld(templateWorldName());
    }

    private String templateWorldName() {
        return plugin.getConfig().getString("template-worlds.overworld.name",
                "bingo_template_overworld");
    }

    private String normalizeCategory(String raw) {
        String category = raw == null || raw.isBlank() ? "all" : raw.toLowerCase(Locale.ROOT);
        return CATEGORIES.contains(category) ? category : null;
    }

    private boolean busy() {
        return waitTask != null || applyTask != null;
    }

    private String status() {
        if (waitTask != null) return detail;
        if (applyTask != null) {
            int percent = total <= 0 ? 0 : (int) Math.round(cursor * 100.0D / total);
            return percent + "% · " + detail;
        }
        return detail;
    }

    private void cancel(CommandSender sender) {
        if (waitTask == null && applyTask == null) {
            sender.sendMessage(ChatColor.YELLOW + "No hay una recuperación estructural activa.");
            return;
        }
        if (waitTask != null) waitTask.cancel();
        if (applyTask != null) applyTask.cancel();
        waitTask = null;
        applyTask = null;
        detail = "cancelada manualmente en " + cursor + "/" + total;
        sender.sendMessage(ChatColor.YELLOW + "Recuperación cancelada. El snapshot previo se conserva.");
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(lower)).sorted().toList();
    }

    private String[] tokenize(String raw) {
        String clean = raw == null ? "" : raw.trim();
        if (clean.startsWith("/")) clean = clean.substring(1);
        return clean.isBlank() ? new String[0] : clean.split("\\s+");
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo template overworld recover "
                + "<preview|apply> <all|village|settlements|residential|commercial|military|"
                + "fortress|citadel|boss|portal>");
        sender.sendMessage(ChatColor.YELLOW + "Estado/cancelar: /bingo template overworld recover "
                + "<status|cancel>");
    }

    private record RecoveryPlan(List<String> categories, List<BlockEdit> edits,
                                List<Site> sites) { }
}
