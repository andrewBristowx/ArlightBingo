package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import com.arlight.bingo.template.OverworldIslandGenerator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;

/**
 * Herramientas de recuperación manual para la plantilla Overworld.
 *
 * Intercepta únicamente tres acciones administrativas nuevas:
 *  - village: reconstrucción aislada del pueblo inicial.
 *  - snapshot: copias completas del mundo antes de operaciones destructivas.
 *  - commit: commit seguro con snapshot automático y bloqueo del constructor 1.48.
 *
 * No toca Nether, End ni el puerto editado por el administrador.
 */
final class OverworldTemplateRecoveryCommands {

    private static final String TEMPLATE_MARKER = "arlight-overworld-template.properties";
    private static final String CAMPAIGN_DONE_MARKER = "arlight-overworld-campaign-1.48.done";
    private static final String MANUAL_COMMIT_GUARD = "arlight-overworld-manual-commit.guard";
    private static final String VILLAGE_RECOVERY_MARKER = "arlight-overworld-village-recovery.properties";
    private static final DateTimeFormatter SNAPSHOT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Set<String> ROOT_ACTIONS = Set.of("village", "snapshot", "commit");

    private final BingoPlugin plugin;
    private BukkitTask ioTask;
    private BukkitTask villageTask;
    private String operationDetail = "sin operaciones";
    private int villageCursor;
    private int villageTotal;
    private int villageChanged;
    private final Map<UUID, Location> pendingReturns = new HashMap<>();

    OverworldTemplateRecoveryCommands(BingoPlugin plugin) {
        this.plugin = plugin;
    }

    boolean matches(String rawCommand) {
        String[] args = tokenize(rawCommand);
        return args.length >= 4
                && args[0].equalsIgnoreCase("bingo")
                && args[1].equalsIgnoreCase("template")
                && args[2].equalsIgnoreCase("overworld")
                && ROOT_ACTIONS.contains(args[3].toLowerCase(Locale.ROOT));
    }

    void execute(CommandSender sender, String rawCommand) {
        if (!sender.hasPermission("arlightbingo.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para administrar la plantilla.");
            return;
        }
        String[] args = tokenize(rawCommand);
        if (args.length < 4) {
            sendUsage(sender);
            return;
        }

        switch (args[3].toLowerCase(Locale.ROOT)) {
            case "village" -> executeVillage(sender, args);
            case "snapshot" -> executeSnapshot(sender, args);
            case "commit" -> executeSafeCommit(sender, args);
            default -> sendUsage(sender);
        }
    }

    List<String> completions(String buffer) {
        String clean = buffer == null ? "" : buffer.trim();
        if (clean.startsWith("/")) clean = clean.substring(1);
        String[] args = clean.isEmpty() ? new String[0] : clean.split("\\s+");
        boolean trailing = buffer != null && buffer.endsWith(" ");
        int logical = trailing ? args.length + 1 : args.length;

        if (logical == 4 && args.length >= 3
                && args[0].equalsIgnoreCase("bingo")
                && args[1].equalsIgnoreCase("template")
                && args[2].equalsIgnoreCase("overworld")) {
            String prefix = trailing ? "" : args[3];
            if (prefix.isBlank() || "village".startsWith(prefix.toLowerCase(Locale.ROOT))
                    || "snapshot".startsWith(prefix.toLowerCase(Locale.ROOT))
                    || "commit".startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return filter(List.of("village", "snapshot", "commit"), prefix);
            }
        }
        if (logical == 5 && args.length >= 4) {
            String prefix = trailing ? "" : args[4];
            if (args[3].equalsIgnoreCase("village")) {
                return filter(List.of("preview", "rebuild", "status", "cancel"), prefix);
            }
            if (args[3].equalsIgnoreCase("snapshot")) {
                return filter(List.of("create", "list", "restore", "status"), prefix);
            }
            if (args[3].equalsIgnoreCase("commit")) {
                return filter(List.of("force"), prefix);
            }
        }
        if (logical == 6 && args.length >= 5
                && args[3].equalsIgnoreCase("snapshot")
                && args[4].equalsIgnoreCase("restore")) {
            String prefix = trailing ? "" : args[5];
            return filter(snapshotNames(), prefix);
        }
        if (logical == 7 && args.length >= 6
                && args[3].equalsIgnoreCase("snapshot")
                && args[4].equalsIgnoreCase("restore")) {
            String prefix = trailing ? "" : args[6];
            return filter(List.of("confirm"), prefix);
        }
        return List.of();
    }

    void onWorldUnload(World world) {
        // Los snapshots descargan deliberadamente la plantilla. Las posiciones de regreso
        // deben sobrevivir a ese evento hasta que el mundo se vuelva a cargar.
    }

    private void executeVillage(CommandSender sender, String[] args) {
        String action = args.length >= 5 ? args[4].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "status" -> sender.sendMessage(ChatColor.AQUA + "Recuperación del pueblo: "
                    + ChatColor.WHITE + operationStatus());
            case "cancel" -> cancelVillage(sender);
            case "preview" -> previewVillage(sender);
            case "rebuild", "apply" -> {
                if (busy()) {
                    sender.sendMessage(ChatColor.YELLOW + "Ya hay una operación activa: " + operationStatus());
                    return;
                }
                String snapshot = "pre-village-rebuild-" + SNAPSHOT_TIME.format(Instant.now());
                beginSnapshot(sender, snapshot, world -> startVillageRebuild(sender, world));
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo template overworld village "
                    + "<preview|rebuild|status|cancel>");
        }
    }

    private void previewVillage(CommandSender sender) {
        if (busy()) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una operación activa: " + operationStatus());
            return;
        }
        World world = resolveTemplateWorld(sender);
        if (world == null) return;
        VillagePlan plan = planVillage(world);
        if (plan == null) {
            sender.sendMessage(ChatColor.RED + "No se pudo localizar el ancla del pueblo inicial.");
            return;
        }
        sender.sendMessage(ChatColor.AQUA + "=== Reconstrucción aislada del pueblo inicial ===");
        sender.sendMessage(ChatColor.GRAY + "- centro=" + ChatColor.WHITE
                + plan.site().x() + "," + plan.site().baseY() + "," + plan.site().z());
        sender.sendMessage(ChatColor.GRAY + "- radio máximo=" + ChatColor.WHITE + "108 bloques");
        sender.sendMessage(ChatColor.GRAY + "- operaciones=" + ChatColor.WHITE + plan.edits().size());
        sender.sendMessage(ChatColor.GRAY + "- incluye=" + ChatColor.WHITE
                + "terrazas del pueblo, calles, plaza, diez edificios, corrales, árboles y faroles");
        sender.sendMessage(ChatColor.GREEN + "- no toca=" + ChatColor.WHITE
                + "puerto, fortaleza, arena del boss, mina, bosques exteriores ni otros poblados");
        sender.sendMessage(ChatColor.YELLOW + "Antes de reconstruir se creará un snapshot automático completo.");
        sender.sendMessage(ChatColor.YELLOW + "Aplicar con: " + ChatColor.WHITE
                + "/bingo template overworld village rebuild");
    }

    private void startVillageRebuild(CommandSender sender, World world) {
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "El snapshot terminó, pero no se pudo recargar la plantilla.");
            return;
        }
        VillagePlan plan = planVillage(world);
        if (plan == null || plan.edits().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No se pudo planificar la reconstrucción del pueblo.");
            return;
        }

        purgeVillageEntities(world, plan.site());
        List<BlockEdit> edits = plan.edits();
        villageCursor = 0;
        villageTotal = edits.size();
        villageChanged = 0;
        operationDetail = "reconstruyendo pueblo 0/" + villageTotal;
        int perTick = Math.max(300, plugin.getConfig().getInt(
                "template-worlds.overworld.village-recovery.blocks-per-tick", 1400));
        long maxNanos = Math.max(2L, plugin.getConfig().getLong(
                "template-worlds.overworld.village-recovery.max-millis-per-tick", 6L)) * 1_000_000L;

        villageTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long deadline = System.nanoTime() + maxNanos;
            int applied = 0;
            while (villageCursor < villageTotal && applied < perTick
                    && System.nanoTime() < deadline) {
                if (apply(world, edits.get(villageCursor++))) villageChanged++;
                applied++;
            }
            operationDetail = "reconstruyendo pueblo " + villageCursor + "/" + villageTotal
                    + " · " + villageChanged + " cambios reales";
            if (villageCursor < villageTotal) return;

            BukkitTask finished = villageTask;
            villageTask = null;
            if (finished != null) finished.cancel();
            ensureManualCommitGuard(world, "village-rebuild");
            writeVillageRecoveryMarker(world, plan.site());
            world.save();
            plugin.getOverworldTemplateManager().installMatchActors(world);
            operationDetail = "pueblo reconstruido · " + villageChanged + " bloques cambiados";
            sender.sendMessage(ChatColor.GREEN + "Pueblo inicial reconstruido sin regenerar el resto de la isla.");
            sender.sendMessage(ChatColor.YELLOW + "Revísalo. Para guardarlo usa el commit seguro: "
                    + ChatColor.WHITE + "/bingo template overworld commit");
        }, 1L, 1L);
        sender.sendMessage(ChatColor.GREEN + "Reconstrucción iniciada con " + villageTotal
                + " operaciones. Consulta /bingo template overworld village status.");
    }

    private VillagePlan planVillage(World world) {
        Location markerVillage = readMarkerLocation(world, "village");
        int fallbackX = plugin.getConfig().getInt("template-worlds.overworld.layout.village-x", -360) + 3;
        int fallbackZ = plugin.getConfig().getInt("template-worlds.overworld.layout.village-z", 0) + 15;
        if (markerVillage == null) {
            int y = world.getHighestBlockYAt(fallbackX, fallbackZ,
                    HeightMap.MOTION_BLOCKING_NO_LEAVES);
            markerVillage = new Location(world, fallbackX, y, fallbackZ);
        }

        Location center = markerVillage.clone().add(-3.0D, -1.0D, -15.0D);
        int x = center.getBlockX();
        int z = center.getBlockZ();
        int y = OverworldCampaignTerrain148.medianTerrainY(world, x, z, 46);
        y = Math.max(world.getMinHeight() + 12, Math.min(world.getMaxHeight() - 48, y));
        Site site = new Site("village", x, y, z, 88, Style.VILLAGE);
        Report report = new Report();
        OverworldCampaignAudit148.Registry registry = new OverworldCampaignAudit148.Registry();
        registry.registerTerrain(site, 67, 94);

        List<BlockEdit> edits = new ArrayList<>();
        int strip = Math.max(6, plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.planning-strip-width", 10));
        int extent = 108;
        for (int minimum = -extent; minimum <= extent; minimum += strip) {
            int maximum = Math.min(extent, minimum + strip - 1);
            edits.addAll(OverworldCampaignTerrain148.shapeVillage(
                    world, site, 67, 94, report, minimum, maximum));
        }
        edits.addAll(OverworldCampaignStructures148.village(world, site, report, registry));
        // Repite las fases finales que usa el constructor completo, pero únicamente para
        // las casas y caminos registrados dentro del pueblo inicial.
        edits.addAll(OverworldCampaignAudit148.repairRegisteredHouseShells(registry));
        registry.reconcileRouteMembershipsToPhysicalPlan();
        edits.addAll(OverworldCampaignAudit148.restoreAllRoadCorridorsStrict(world, registry));
        edits.addAll(OverworldCampaignAudit148.repairRegisteredEntrances(world, registry));
        edits.addAll(OverworldCampaignAudit148.repairRegisteredChimneys(registry));
        return new VillagePlan(site, edits);
    }

    private void cancelVillage(CommandSender sender) {
        if (villageTask == null) {
            sender.sendMessage(ChatColor.YELLOW + "No hay una reconstrucción del pueblo activa.");
            return;
        }
        villageTask.cancel();
        villageTask = null;
        operationDetail = "reconstrucción cancelada en " + villageCursor + "/" + villageTotal;
        sender.sendMessage(ChatColor.YELLOW + "Reconstrucción cancelada. Puedes restaurar el snapshot pre-village-rebuild.");
    }

    private void executeSnapshot(CommandSender sender, String[] args) {
        String action = args.length >= 5 ? args[4].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "status" -> sender.sendMessage(ChatColor.AQUA + "Snapshots Overworld: "
                    + ChatColor.WHITE + operationStatus());
            case "list" -> listSnapshots(sender);
            case "create" -> {
                String requested = args.length >= 6 ? args[5]
                        : "manual-" + SNAPSHOT_TIME.format(Instant.now());
                String name = normalizeName(requested);
                if (name == null) {
                    sender.sendMessage(ChatColor.RED + "Nombre inválido. Usa letras, números, '.', '_' o '-'.");
                    return;
                }
                beginSnapshot(sender, name, world -> sender.sendMessage(ChatColor.GREEN
                        + "Snapshot listo. La plantilla se recargó sin modificar sus bloques."));
            }
            case "restore" -> {
                if (args.length < 7 || !args[6].equalsIgnoreCase("confirm")) {
                    sender.sendMessage(ChatColor.RED + "Uso: /bingo template overworld snapshot "
                            + "restore <nombre> confirm");
                    return;
                }
                beginRestore(sender, args[5]);
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo template overworld snapshot "
                    + "<create [nombre]|list|restore <nombre> confirm|status>");
        }
    }

    private void executeSafeCommit(CommandSender sender, String[] args) {
        if (busy()) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una operación activa: " + operationStatus());
            return;
        }
        boolean force = args.length >= 5 && args[4].equalsIgnoreCase("force");
        if (plugin.getOverworldTemplateManager().workingRevision().isBlank()) {
            sender.sendMessage(ChatColor.RED + "No hay una revisión Overworld en trabajo para guardar.");
            return;
        }
        String snapshot = "pre-commit-" + SNAPSHOT_TIME.format(Instant.now());
        sender.sendMessage(ChatColor.YELLOW + "Commit seguro: primero se creará el snapshot " + snapshot + ".");
        beginSnapshot(sender, snapshot, world -> {
            ensureManualCommitGuard(world, force ? "safe-commit-force" : "safe-commit");
            boolean success = plugin.getOverworldTemplateManager().commit(sender, force);
            if (success) {
                ensureManualCommitGuard(world, force ? "safe-commit-force-complete" : "safe-commit-complete");
                sender.sendMessage(ChatColor.GREEN + "Commit seguro completado. El constructor 1.48 no se reiniciará.");
            } else {
                sender.sendMessage(ChatColor.RED + "El commit falló, pero el snapshot previo quedó disponible.");
            }
        });
    }

    private void beginSnapshot(CommandSender sender, String requestedName, Consumer<World> after) {
        if (busy()) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una operación activa: " + operationStatus());
            return;
        }
        String name = normalizeName(requestedName);
        if (name == null) {
            sender.sendMessage(ChatColor.RED + "Nombre de snapshot inválido.");
            return;
        }
        World world = resolveTemplateWorld(sender);
        if (world == null) return;
        Path source = world.getWorldFolder().toPath();
        Path destination = snapshotRoot().resolve(name);
        if (Files.exists(destination)) {
            sender.sendMessage(ChatColor.RED + "Ya existe el snapshot '" + name + "'.");
            return;
        }

        if (!prepareWorldForOfflineCopy(world, sender)) return;
        operationDetail = "copiando snapshot " + name;
        sender.sendMessage(ChatColor.YELLOW + "Creando snapshot completo '" + name + "'...");
        ioTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Throwable failure = null;
            try {
                copyDirectoryAtomic(source, destination);
                pruneSnapshots(3);
            } catch (Throwable error) {
                failure = error;
            }
            Throwable finalFailure = failure;
            Bukkit.getScheduler().runTask(plugin, () -> {
                ioTask = null;
                World reloaded = loadTemplateWorld();
                restorePlayers(reloaded);
                if (finalFailure != null) {
                    operationDetail = "snapshot fallido: " + rootMessage(finalFailure);
                    sender.sendMessage(ChatColor.RED + "No se pudo crear el snapshot: "
                            + rootMessage(finalFailure));
                    return;
                }
                operationDetail = "snapshot creado: " + name;
                sender.sendMessage(ChatColor.GREEN + "Snapshot creado: " + name + ".");
                if (after != null) after.accept(reloaded);
            });
        });
    }

    private void beginRestore(CommandSender sender, String requestedName) {
        if (busy()) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una operación activa: " + operationStatus());
            return;
        }
        String name = normalizeName(requestedName);
        if (name == null) {
            sender.sendMessage(ChatColor.RED + "Nombre de snapshot inválido.");
            return;
        }
        Path snapshot = snapshotRoot().resolve(name);
        if (!Files.isDirectory(snapshot)) {
            sender.sendMessage(ChatColor.RED + "No existe el snapshot '" + name + "'.");
            return;
        }
        World world = resolveTemplateWorld(sender);
        if (world == null) return;
        Path target = world.getWorldFolder().toPath();
        String emergencyName = "pre-restore-" + SNAPSHOT_TIME.format(Instant.now());
        Path emergency = snapshotRoot().resolve(emergencyName);

        if (!prepareWorldForOfflineCopy(world, sender)) return;
        operationDetail = "restaurando snapshot " + name;
        sender.sendMessage(ChatColor.YELLOW + "Restaurando '" + name
                + "'. También se guardará " + emergencyName + ".");
        ioTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Throwable failure = null;
            try {
                copyDirectoryAtomic(target, emergency);
                deleteTree(target);
                copyDirectory(snapshot, target);
                pruneSnapshots(3);
            } catch (Throwable error) {
                failure = error;
                try {
                    if (Files.isDirectory(emergency)) {
                        deleteTree(target);
                        copyDirectory(emergency, target);
                    }
                } catch (Throwable ignored) { }
            }
            Throwable finalFailure = failure;
            Bukkit.getScheduler().runTask(plugin, () -> {
                ioTask = null;
                World reloaded = loadTemplateWorld();
                restorePlayers(reloaded);
                if (finalFailure != null) {
                    operationDetail = "restauración fallida: " + rootMessage(finalFailure);
                    sender.sendMessage(ChatColor.RED + "La restauración falló: "
                            + rootMessage(finalFailure));
                    return;
                }
                operationDetail = "snapshot restaurado: " + name;
                sender.sendMessage(ChatColor.GREEN + "Snapshot restaurado: " + name + ".");
            });
        });
    }

    private boolean prepareWorldForOfflineCopy(World world, CommandSender sender) {
        if (world == null) return false;
        World fallback = fallbackWorld(world);
        if (fallback == null) {
            sender.sendMessage(ChatColor.RED + "No existe otro mundo cargado para mover temporalmente a los jugadores.");
            return false;
        }
        pendingReturns.clear();
        for (Player player : new ArrayList<>(world.getPlayers())) {
            pendingReturns.put(player.getUniqueId(), player.getLocation().clone());
            player.teleport(fallback.getSpawnLocation());
            player.sendMessage(ChatColor.YELLOW + "Plantilla descargada temporalmente para crear una copia segura.");
        }
        world.setAutoSave(true);
        world.save();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        if (!Bukkit.unloadWorld(world, true)) {
            restorePlayers(world);
            sender.sendMessage(ChatColor.RED + "No se pudo descargar la plantilla para copiarla de forma segura.");
            return false;
        }
        return true;
    }

    private World resolveTemplateWorld(CommandSender sender) {
        String name = templateWorldName();
        World world = Bukkit.getWorld(name);
        if (world == null) world = loadTemplateWorld();
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "No se pudo cargar la plantilla Overworld '" + name + "'.");
            return null;
        }
        return world;
    }

    private World loadTemplateWorld() {
        String name = templateWorldName();
        World loaded = Bukkit.getWorld(name);
        if (loaded != null) return loaded;
        Path folder = plugin.getServer().getWorldContainer().toPath().resolve(name);
        if (!Files.isDirectory(folder)) return null;

        long seed = plugin.getConfig().getLong("template-worlds.overworld.seed", 741905270311L);
        boolean island = plugin.getConfig().getBoolean("template-worlds.overworld.island.enabled", true);
        WorldCreator creator = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .seed(seed)
                .generateStructures(!island);
        if (island) {
            creator.generator(new OverworldIslandGenerator(seed,
                    plugin.getConfig().getInt("template-worlds.overworld.island.land-radius", 500),
                    plugin.getConfig().getInt("template-worlds.overworld.island.sea-level", 62)));
        }
        return creator.createWorld();
    }

    private void restorePlayers(World world) {
        if (world == null) {
            pendingReturns.clear();
            return;
        }
        for (Map.Entry<UUID, Location> entry : new HashMap<>(pendingReturns).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            Location saved = entry.getValue();
            Location target = new Location(world, saved.getX(), saved.getY(), saved.getZ(),
                    saved.getYaw(), saved.getPitch());
            player.teleport(target);
        }
        pendingReturns.clear();
    }

    private World fallbackWorld(World excluded) {
        for (String preferred : List.of("legos", "world", "lobby")) {
            World world = Bukkit.getWorld(preferred);
            if (world != null && !world.getUID().equals(excluded.getUID())) return world;
        }
        return Bukkit.getWorlds().stream()
                .filter(world -> !world.getUID().equals(excluded.getUID()))
                .findFirst().orElse(null);
    }

    private void ensureManualCommitGuard(World world, String reason) {
        if (world == null) return;
        Path folder = world.getWorldFolder().toPath();
        String text = "version=1.48.33\n"
                + "reason=" + reason + "\n"
                + "createdAt=" + System.currentTimeMillis() + "\n"
                + "preventsAutomaticCampaignRebuild=true\n";
        try {
            Path done = folder.resolve(CAMPAIGN_DONE_MARKER);
            if (!Files.isRegularFile(done)) {
                Files.writeString(done, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            }
            Files.writeString(folder.resolve(MANUAL_COMMIT_GUARD), text,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo escribir el bloqueo de commit seguro: "
                    + error.getMessage());
        }
    }

    private void writeVillageRecoveryMarker(World world, Site site) {
        String text = "version=1.48.33\n"
                + "recoveredAt=" + System.currentTimeMillis() + "\n"
                + "center=" + site.x() + "," + site.baseY() + "," + site.z() + "\n"
                + "changedBlocks=" + villageChanged + "\n"
                + "scope=village-only\n";
        try {
            Files.writeString(world.getWorldFolder().toPath().resolve(VILLAGE_RECOVERY_MARKER), text,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo escribir el marcador de recuperación del pueblo: "
                    + error.getMessage());
        }
    }

    private void purgeVillageEntities(World world, Site site) {
        double radius = 112.0D;
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (!(entity instanceof LivingEntity) || entity instanceof Player
                    || entity.getScoreboardTags().contains("arlightbingo_keep")) continue;
            double dx = entity.getLocation().getX() - site.x();
            double dz = entity.getLocation().getZ() - site.z();
            if (dx * dx + dz * dz <= radius * radius) entity.remove();
        }
    }

    private boolean apply(World world, BlockEdit edit) {
        if (edit.y() < world.getMinHeight() || edit.y() >= world.getMaxHeight()) return false;
        Block block = world.getBlockAt(edit.x(), edit.y(), edit.z());
        boolean changed = block.getType() != edit.material();
        if (changed) block.setType(edit.material(), false);
        if (edit.data() != null) {
            var expected = Bukkit.createBlockData(edit.data());
            if (!block.getBlockData().matches(expected)) changed = true;
            block.setBlockData(expected, false);
        }
        return changed;
    }

    private Location readMarkerLocation(World world, String key) {
        Path marker = world.getWorldFolder().toPath().resolve(TEMPLATE_MARKER);
        if (!Files.isRegularFile(marker)) return null;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
            properties.load(reader);
            String[] split = properties.getProperty(key, "").split(",");
            if (split.length < 3) return null;
            return new Location(world, Double.parseDouble(split[0].trim()),
                    Double.parseDouble(split[1].trim()), Double.parseDouble(split[2].trim()));
        } catch (IOException | NumberFormatException ignored) {
            return null;
        }
    }

    private void listSnapshots(CommandSender sender) {
        List<String> names = snapshotNames();
        sender.sendMessage(ChatColor.AQUA + "=== Snapshots Overworld ===");
        if (names.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No hay snapshots todavía.");
            return;
        }
        for (String name : names) sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + name);
    }

    private List<String> snapshotNames() {
        Path root = snapshotRoot();
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(this::modifiedTime).reversed())
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private void pruneSnapshots(int keep) throws IOException {
        List<Path> snapshots;
        try (Stream<Path> stream = Files.list(snapshotRoot())) {
            snapshots = stream.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().endsWith(".tmp"))
                    .sorted(Comparator.comparingLong(this::modifiedTime).reversed())
                    .toList();
        }
        for (int index = Math.max(1, keep); index < snapshots.size(); index++) {
            deleteTree(snapshots.get(index));
        }
    }

    private long modifiedTime(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return 0L; }
    }

    private void copyDirectoryAtomic(Path source, Path destination) throws IOException {
        Files.createDirectories(snapshotRoot());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        deleteTree(temporary);
        copyDirectory(source, temporary);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyDirectory(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("La carpeta origen no existe: " + source);
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().equals("session.lock")) continue;
                Path target = destination.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private Path snapshotRoot() {
        return plugin.getDataFolder().toPath().resolve("template-snapshots").resolve("overworld");
    }

    private String templateWorldName() {
        return plugin.getConfig().getString("template-worlds.overworld.name", "bingo_template_overworld");
    }

    private boolean busy() {
        return ioTask != null || villageTask != null;
    }

    private String operationStatus() {
        if (ioTask != null) return "I/O activo · " + operationDetail;
        if (villageTask != null) {
            int percent = villageTotal <= 0 ? 0
                    : (int) Math.min(100L, Math.round(villageCursor * 100.0D / villageTotal));
            return percent + "% · " + operationDetail;
        }
        return operationDetail;
    }

    private String normalizeName(String raw) {
        if (raw == null) return null;
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if (clean.isBlank() || clean.length() > 64 || !clean.matches("[a-z0-9._-]+")) return null;
        return clean;
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private String[] tokenize(String raw) {
        String clean = raw == null ? "" : raw.trim();
        if (clean.startsWith("/")) clean = clean.substring(1);
        return clean.isBlank() ? new String[0] : clean.split("\\s+");
    }

    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Recuperación del pueblo: /bingo template overworld village "
                + "<preview|rebuild|status|cancel>");
        sender.sendMessage(ChatColor.YELLOW + "Snapshots: /bingo template overworld snapshot "
                + "<create [nombre]|list|restore <nombre> confirm|status>");
        sender.sendMessage(ChatColor.YELLOW + "Commit seguro: /bingo template overworld commit [force]");
    }

    private record VillagePlan(Site site, List<BlockEdit> edits) { }
}
