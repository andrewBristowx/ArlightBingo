package com.arlight.bingo.template;

import com.arlight.bingo.BingoPlugin;
import com.arlight.bingo.util.ChunkyBridge;
import com.arlight.bingo.util.CampaignItemBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Plantilla experimental del Nether: caverna dorada, súper-bastión y bóveda del jefe. */
public final class NetherTemplateManager implements Listener {

    public enum Stage { IDLE, CREATING_WORLD, PREGENERATING, FINALIZING, COMPLETE, FAILED, CANCELLED }

    private static final String MARKER = "arlight-nether-template.properties";
    private static final String IN_PROGRESS_MARKER = "arlight-nether-template-building.properties";
    private static final String VERSION = "1.35.0-nether-professional-room-pass-1";
    private static final String STRUCTURE_REVISION = "1.35.0-nether-functional-interiors-loot-1";

    private final BingoPlugin plugin;
    private final ChunkyBridge chunky;
    private final NamespacedKey builtKey;
    private Stage stage = Stage.IDLE;
    private String detail = "sin iniciar";
    private double progress;
    private TemplateChunkBuildQueue queue;
    private BukkitTask scanTask;
    private BukkitTask finalizationTask;
    private final ArrayDeque<Long> finalizationChunks = new ArrayDeque<>();
    private final Set<Long> finalizationOutstanding = new HashSet<>();
    private int finalizationTotal;
    private int finalizationDone;
    private boolean finalizationLoading;
    private CommandSender finalizationSender;
    private CompletableFuture<Void> pregenerationFuture;
    private CompletableFuture<Void> completionDrainFuture;

    public NetherTemplateManager(BingoPlugin plugin) {
        this.plugin = plugin;
        this.chunky = new ChunkyBridge(plugin);
        this.builtKey = new NamespacedKey(plugin, "template_nether_chunk_v4");
    }

    public String worldName() {
        return plugin.getConfig().getString("template-worlds.nether.name", "bingo_template_nether");
    }

    public synchronized boolean generate(CommandSender sender, boolean force) {
        if (!plugin.getConfig().getBoolean("template-worlds.nether.enabled", true)) {
            sender.sendMessage(ChatColor.RED + "La plantilla del Nether está desactivada.");
            return false;
        }
        if (isBusy()) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una generación activa: " + status());
            return false;
        }
        if ((Bukkit.getWorld(worldName()) != null || Files.exists(worldFolder())) && !force) {
            sender.sendMessage(ChatColor.YELLOW + "La plantilla ya existe. Usa generate force para reemplazarla.");
            return false;
        }
        if (force) {
            stopFinalization();
            stopLazyBuilder();
            if (!deleteExisting(sender)) return false;
        }

        stage = Stage.CREATING_WORLD;
        detail = "creando Nether base";
        progress = 0.02D;
        long seed = plugin.getConfig().getLong("template-worlds.nether.seed", 510932847112L);
        World world = new WorldCreator(worldName())
                .environment(World.Environment.NETHER)
                .type(WorldType.NORMAL)
                .seed(seed)
                .generateStructures(false)
                .createWorld();
        if (world == null) {
            fail("Bukkit no pudo crear el Nether plantilla.");
            return false;
        }

        int radius = Math.max(512, plugin.getConfig().getInt("template-worlds.nether.radius", 1024));
        world.getWorldBorder().setCenter(0.0D, 0.0D);
        world.getWorldBorder().setSize(radius * 2.0D);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, true);
        world.setAutoSave(true);

        stage = Stage.PREGENERATING;
        detail = "Chunky pregenerando el Nether";
        progress = 0.05D;
        int tileRadius = plugin.getConfig().getInt(
                "template-worlds.safety.chunky-tile-radius", 384);
        pregenerationFuture = chunky.generateBatched(
                worldName(),
                world,
                ChunkyBridge.squareTiles(radius, tileRadius),
                safetyLoadedChunkLimit(),
                safetyDrainInterval(),
                safetyStableChecks(),
                message -> {
                    if (stage == Stage.PREGENERATING) detail = message;
                });
        pregenerationFuture.whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            pregenerationFuture = null;
            if (stage == Stage.CANCELLED) return;
            if (error != null) {
                fail("Chunky falló: " + rootMessage(error));
                return;
            }
            if (!writeInProgressMarker(world)) {
                fail("No se pudo guardar el marcador reanudable del Nether.");
                sender.sendMessage(ChatColor.RED + detail);
                return;
            }
            startEagerFinalization(world, sender);
        }));
        sender.sendMessage(ChatColor.GOLD + "Chunky empezó a pregenerar el Nether plantilla.");
        return true;
    }

    public synchronized void cancel(CommandSender sender) {
        if (!isBusy()) {
            sender.sendMessage(ChatColor.YELLOW + "No hay una generación activa.");
            return;
        }
        stage = Stage.CANCELLED;
        detail = "cancelada por administrador";
        if (pregenerationFuture != null) pregenerationFuture.cancel(false);
        pregenerationFuture = null;
        stopFinalization();
        stopLazyBuilder();
        World world = Bukkit.getWorld(worldName());
        if (world != null) writeInProgressMarker(world, "cancelled");
        sender.sendMessage(ChatColor.YELLOW + "Generación cancelada. El mundo se conserva; usa /bingo template nether resume para continuar sin Chunky.");
    }

    public synchronized boolean reset(CommandSender sender) {
        if (isBusy() || chunky.isRunning()) {
            sender.sendMessage(ChatColor.RED + "Chunky todavía está trabajando.");
            return false;
        }
        stopFinalization();
        stopLazyBuilder();
        boolean ok = deleteExisting(sender);
        if (ok) {
            stage = Stage.IDLE;
            progress = 0.0D;
            detail = "sin iniciar";
        }
        return ok;
    }

    public boolean teleport(Player player) {
        World world = Bukkit.getWorld(worldName());
        if (world == null && isCompleteOnDisk()) {
            world = loadExistingWorld(plugin.getConfig().getLong(
                    "template-worlds.nether.seed", 510932847112L));
        }
        if (world == null) return false;
        if (!markerExists(world)) {
            player.sendMessage(ChatColor.YELLOW + "El Nether todavía no está terminado: " + status());
            return false;
        }
        player.setAllowFlight(true);
        player.setFlying(true);
        player.sendMessage(ChatColor.GREEN + "Plantilla Nether completa: el bastión entero ya está construido.");
        return player.teleport(new Location(world, 0.5D, 64.0D, -188.5D, 0.0F, 0.0F));
    }

    public boolean testBoss(Player player) {
        World world = Bukkit.getWorld(worldName());
        if (world == null || !markerExists(world)) return false;
        Location arena = new Location(world, 0.5D, 66.0D, 176.5D);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.teleport(arena.clone().add(0, 3.0D, -34));
        prioritizeLoadedArea(world, 0, 170, 4);
        player.sendMessage(ChatColor.GOLD + "Preparando toda la arena del jefe por chunks...");
        summonBossWhenReady(player, world, arena, "arlightbosses:nether_guardian", "arlightbingo_template_nether_boss", 40);
        return true;
    }

    public String status() {
        int pending = queue == null ? 0 : queue.pending();
        return stage.name().toLowerCase(Locale.ROOT) + " · " + Math.round(progress * 100.0D)
                + "% · " + detail + (pending > 0 ? " · cola " + pending : "");
    }

    public void audit(CommandSender sender) {
        long seed = plugin.getConfig().getLong("template-worlds.nether.seed", 510932847112L);
        sender.sendMessage(ChatColor.GOLD + "=== Auditoría plantilla Nether ===");
        for (String line : TemplateAuditUtil.audit(plugin, worldName(), MARKER, IN_PROGRESS_MARKER,
                VERSION, World.Environment.NETHER, seed)) {
            sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + line);
        }
        Path marker = TemplateMarkerLookup.findMarker(plugin, worldName(), MARKER).orElse(null);
        String revision = TemplateAuditUtil.readProperty(marker, "structureRevision");
        sender.sendMessage(ChatColor.GRAY + "- revisión-estructural=" + ChatColor.WHITE
                + (revision == null ? "ausente" : revision) + " (esperada " + STRUCTURE_REVISION + ")");
        sender.sendMessage(ChatColor.GRAY + "- anclajes-faltantes=" + ChatColor.WHITE
                + TemplateAuditUtil.missingProperties(marker, "spawn", "boss", "portal", "gate", "lock", "keys"));
        World loaded = Bukkit.getWorld(worldName());
        if (loaded != null) sender.sendMessage(ChatColor.GRAY + "- anclajes-físicos=" + ChatColor.WHITE
                + campaignAnchorStatus(loaded));
        else sender.sendMessage(ChatColor.GRAY + "- anclajes-físicos=" + ChatColor.WHITE
                + "no comprobados; usa resume para cargar y reparar sin Chunky");
        sender.sendMessage(ChatColor.GRAY + "- estado-runtime=" + ChatColor.WHITE + status());
        sender.sendMessage(ChatColor.GRAY + "- zona-protegida=" + ChatColor.WHITE
                + "x[-176,176] z[-220,270] y[" + protectedClearMinY() + "," + protectedClearMaxY() + "]");
    }

    public Stage stage() {
        return stage;
    }

    public void shutdown() {
        if (pregenerationFuture != null) pregenerationFuture.cancel(false);
        pregenerationFuture = null;
        stopFinalization();
        stopLazyBuilder();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.getWorld().getName().equals(worldName())) return;
        if (stage == Stage.FINALIZING) return;
        if (!markerExists(event.getWorld())) return;
        ensureQueue(event.getWorld());
        if (!event.getChunk().getPersistentDataContainer().has(builtKey, PersistentDataType.BYTE)) {
            queue.offer(event.getChunk());
        }
    }

    public void resumeIfNeeded() {
        Bukkit.getScheduler().runTask(plugin, this::resumeInterruptedFinalization);
    }

    public boolean isCompleteOnDisk() {
        return markerCurrentRevision();
    }

    public boolean isReadyForMatches() {
        return stage == Stage.COMPLETE || isCompleteOnDisk();
    }

    public boolean ensureCampaignAnchorsReady() {
        if (!markerCurrentRevision()) return false;
        long seed = plugin.getConfig().getLong("template-worlds.nether.seed", 510932847112L);
        World world = loadExistingWorld(seed);
        if (world == null) return false;
        repairCampaignAnchors(world);
        return campaignAnchorStatus(world).contains("cofres=4/4")
                && campaignAnchorStatus(world).contains("cerradura=presente");
    }

    public String markerDiagnostic() {
        return (isCompleteOnDisk() ? "READY" : "MISSING")
                + " [" + TemplateMarkerLookup.checkedFolders(plugin, worldName()) + "]";
    }

    private void resumeInterruptedFinalization() {
        if (isBusy()) return;
        long seed = plugin.getConfig().getLong("template-worlds.nether.seed", 510932847112L);
        if (markerCurrentRevision()) {
            World readyWorld = loadExistingWorld(seed);
            if (readyWorld != null) {
                repairCampaignAnchors(readyWorld);
                startLazyBuilder(readyWorld);
            }
            stage = Stage.COMPLETE;
            progress = 1.0D;
            detail = "plantilla lista; bastión completamente construido";
            return;
        }
        Path folder = worldFolder();
        boolean interrupted = TemplateMarkerLookup.findMarker(plugin, worldName(), IN_PROGRESS_MARKER).isPresent();
        boolean olderComplete = TemplateMarkerLookup.findMarker(plugin, worldName(), MARKER).isPresent();
        if (!interrupted && !olderComplete) return;

        World world = loadExistingWorld(seed);
        if (world == null) {
            fail("No se pudo reanudar la finalización del Nether después del reinicio.");
            return;
        }
        if (!writeInProgressMarker(world, olderComplete ? "upgrading" : "finalizing")) {
            fail("No se pudo guardar el marcador reanudable del Nether.");
            return;
        }
        plugin.getLogger().warning(olderComplete
                ? "Se detectó una plantilla Nether de una revisión anterior. Actualizando solo la zona custom, sin Chunky."
                : "Se detectó una plantilla Nether incompleta. Reanudando la construcción segura sin Chunky.");
        startEagerFinalization(world, Bukkit.getConsoleSender());
    }

    public synchronized boolean resume(CommandSender sender) {
        if (isBusy()) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una operación activa: " + status());
            return false;
        }
        if (markerCurrentRevision()) {
            long seed = plugin.getConfig().getLong("template-worlds.nether.seed", 510932847112L);
            World readyWorld = loadExistingWorld(seed);
            if (readyWorld != null) {
                repairCampaignAnchors(readyWorld);
                startLazyBuilder(readyWorld);
            }
            stage = Stage.COMPLETE;
            progress = 1.0D;
            detail = "plantilla lista; bastión completamente construido";
            sender.sendMessage(ChatColor.GREEN + "La plantilla Nether ya usa la revisión estructural actual.");
            return true;
        }
        if (!Files.isDirectory(worldFolder())) {
            sender.sendMessage(ChatColor.RED + "No existe la carpeta del Nether para reanudar.");
            return false;
        }
        long seed = plugin.getConfig().getLong("template-worlds.nether.seed", 510932847112L);
        World world = loadExistingWorld(seed);
        if (world == null) {
            fail("No se pudo cargar el Nether existente.");
            sender.sendMessage(ChatColor.RED + detail);
            return false;
        }
        if (!writeInProgressMarker(world, TemplateMarkerLookup.findMarker(plugin, worldName(), MARKER).isPresent() ? "upgrading" : "finalizing")) {
            fail("No se pudo guardar el marcador reanudable del Nether.");
            sender.sendMessage(ChatColor.RED + detail);
            return false;
        }
        sender.sendMessage(ChatColor.GOLD + "Reanudando/actualizando la zona custom del Nether. Chunky NO se repetirá y el mundo NO se borrará.");
        startEagerFinalization(world, sender);
        return true;
    }

    private World loadExistingWorld(long seed) {
        World world = Bukkit.getWorld(worldName());
        if (world != null) return world;
        if (!Files.isDirectory(worldFolder())) return null;
        return new WorldCreator(worldName())
                .environment(World.Environment.NETHER)
                .type(WorldType.NORMAL)
                .seed(seed)
                .generateStructures(false)
                .createWorld();
    }

    private boolean writeInProgressMarker(World world) {
        return writeInProgressMarker(world, "finalizing");
    }

    private boolean writeInProgressMarker(World world, String stateName) {
        String text = "version=" + VERSION + "\n"
                + "structureRevision=" + STRUCTURE_REVISION + "\n"
                + "state=" + stateName + "\n"
                + "chunkyComplete=true\n"
                + "seed=" + world.getSeed() + "\n";
        try {
            world.save();
            Path marker = inProgressMarkerPath();
            Files.writeString(marker, text,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            TemplateMarkerLookup.rememberMarker(plugin, worldName(), IN_PROGRESS_MARKER, marker);
            return true;
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo guardar el progreso del Nether: "
                    + error.getMessage());
            return false;
        }
    }

    private void deleteInProgressMarker() {
        try {
            Files.deleteIfExists(inProgressMarkerPath());
            TemplateMarkerLookup.forgetMarker(plugin, worldName(), IN_PROGRESS_MARKER);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo borrar el marcador temporal Nether: "
                    + error.getMessage());
        }
    }

    private void startEagerFinalization(World world, CommandSender sender) {
        stopFinalization();
        ensureQueue(world);

        List<Long> targets = collectFinalizationChunks();
        finalizationChunks.addAll(targets);
        finalizationOutstanding.addAll(targets);
        finalizationTotal = targets.size();
        finalizationDone = 0;
        finalizationLoading = false;
        finalizationSender = sender;

        stage = Stage.FINALIZING;
        progress = 0.10D;
        detail = "construyendo bastión completo 0/" + finalizationTotal;
        sender.sendMessage(ChatColor.GOLD
                + "Chunky terminó. Ahora Bingo construirá todo el Nether antes de marcarlo como completo.");

        long interval = Math.max(5L, plugin.getConfig().getLong(
                "template-worlds.nether.finalization-load-interval-ticks", 10L));
        finalizationTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> tickEagerFinalization(world), 1L, interval);
    }

    private void tickEagerFinalization(World world) {
        if (stage != Stage.FINALIZING) return;
        if (finalizationLoading || (queue != null && queue.pending() > 0)) return;

        while (!finalizationChunks.isEmpty()) {
            long key = finalizationChunks.pollFirst();
            int cx = (int) (key >> 32);
            int cz = (int) key;

            if (world.isChunkLoaded(cx, cz)) {
                Chunk loaded = world.getChunkAt(cx, cz);
                if (loaded.getPersistentDataContainer().has(builtKey, PersistentDataType.BYTE)) {
                    if (finalizationOutstanding.remove(key)) {
                        finalizationDone++;
                        updateFinalizationProgress();
                    }
                    continue;
                }
                try {
                    loaded.addPluginChunkTicket(plugin);
                } catch (Throwable ignored) { }
                queue.offerPriority(loaded);
                return;
            }

            finalizationLoading = true;
            requestFinalizationChunk(world, cx, cz, key);
            return;
        }

        if (!finalizationOutstanding.isEmpty() && (queue == null || queue.pending() == 0)) {
            // Si Arclight descargó un chunk justo después de solicitarlo, la cola
            // puede descartarlo sin completar. Se vuelve a intentar en vez de dejar
            // FINALIZING detenido para siempre.
            finalizationChunks.addAll(finalizationOutstanding);
            return;
        }
        if (finalizationOutstanding.isEmpty() && (queue == null || queue.pending() == 0)) {
            if (queue != null && !queue.isSettled()) {
                detail = "dejando guardar " + queue.retained() + " chunks finales";
                return;
            }
            beginCompletionDrain(world);
        }
    }

    private void beginCompletionDrain(World world) {
        if (completionDrainFuture != null) return;
        if (finalizationTask != null) finalizationTask.cancel();
        finalizationTask = null;
        detail = "estabilizando guardado antes de COMPLETE";
        progress = Math.max(progress, 0.99D);
        CompletableFuture<Void> drain = chunky.awaitChunkDrain(
                world,
                safetyLoadedChunkLimit(),
                safetyDrainInterval(),
                safetyStableChecks(),
                message -> {
                    if (stage == Stage.FINALIZING) detail = message;
                });
        completionDrainFuture = drain;
        drain.whenComplete((ignored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (completionDrainFuture != drain) return;
                    completionDrainFuture = null;
                    if (stage != Stage.FINALIZING) return;
                    if (error != null) {
                        fail("No se pudo estabilizar el Nether: " + rootMessage(error));
                        return;
                    }
                    completeEagerFinalization(world);
                }));
    }

    private void requestFinalizationChunk(World world, int cx, int cz, long key) {
        try {
            Method method = world.getClass().getMethod(
                    "getChunkAtAsync", int.class, int.class, boolean.class);
            Object result = method.invoke(world, cx, cz, true);
            if (result instanceof CompletableFuture<?> future) {
                future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (stage != Stage.FINALIZING) {
                        finalizationLoading = false;
                        return;
                    }
                    if (error == null && value instanceof Chunk chunk) {
                        acceptFinalizationChunk(chunk, key);
                    } else {
                        loadFinalizationChunkSync(world, cx, cz, key);
                    }
                }));
                return;
            }
        } catch (Throwable ignored) {
            // Arclight puede no exponer getChunkAtAsync. El fallback carga un solo
            // chunk por intervalo y únicamente después de terminar Chunky.
        }
        loadFinalizationChunkSync(world, cx, cz, key);
    }

    private void loadFinalizationChunkSync(World world, int cx, int cz, long key) {
        try {
            acceptFinalizationChunk(world.getChunkAt(cx, cz), key);
        } catch (Throwable error) {
            finalizationLoading = false;
            stopFinalization();
            stopLazyBuilder();
            deleteInProgressMarker();
            fail("No se pudo cargar el chunk Nether " + cx + "," + cz + ": "
                    + rootMessage(error));
        }
    }

    private void acceptFinalizationChunk(Chunk chunk, long key) {
        finalizationLoading = false;
        if (stage != Stage.FINALIZING) return;
        if (chunk.getPersistentDataContainer().has(builtKey, PersistentDataType.BYTE)) {
            if (finalizationOutstanding.remove(key)) {
                finalizationDone++;
                updateFinalizationProgress();
            }
            return;
        }
        try {
            chunk.addPluginChunkTicket(plugin);
        } catch (Throwable ignored) { }
        queue.offerPriority(chunk);
    }

    private void completeEagerFinalization(World world) {
        if (finalizationTask != null) finalizationTask.cancel();
        finalizationTask = null;
        finalizationLoading = false;

        repairCampaignAnchors(world);
        if (!writeMarker(world)) {
            CommandSender sender = finalizationSender;
            finalizationSender = null;
            fail("La estructura terminó, pero no se pudo guardar su marcador COMPLETE.");
            if (sender != null) sender.sendMessage(ChatColor.RED + detail);
            return;
        }
        deleteInProgressMarker();
        stage = Stage.COMPLETE;
        progress = 1.0D;
        detail = "plantilla lista; bastión completamente construido";

        CommandSender sender = finalizationSender;
        finalizationSender = null;
        if (sender != null) {
            sender.sendMessage(ChatColor.GREEN
                    + "Plantilla Nether terminada por completo. Ya puedes usar /bingo template nether tp.");
        }

        startLazyBuilder(world);
        finalizationChunks.clear();
        finalizationOutstanding.clear();
    }

    private void updateFinalizationProgress() {
        if (finalizationTotal <= 0) {
            progress = 0.99D;
            return;
        }
        double ratio = Math.min(1.0D, finalizationDone / (double) finalizationTotal);
        progress = 0.10D + ratio * 0.89D;
        detail = "construyendo bastión completo " + finalizationDone + "/" + finalizationTotal;
    }

    private void stopFinalization() {
        if (finalizationTask != null) finalizationTask.cancel();
        finalizationTask = null;
        if (completionDrainFuture != null) completionDrainFuture.cancel(false);
        completionDrainFuture = null;
        finalizationLoading = false;
        finalizationSender = null;
        finalizationChunks.clear();
        finalizationOutstanding.clear();
        finalizationTotal = 0;
        finalizationDone = 0;
    }

    private List<Long> collectFinalizationChunks() {
        List<Long> targets = new ArrayList<>();
        for (int cx = -12; cx <= 12; cx++) {
            for (int cz = -14; cz <= 17; cz++) {
                if (chunkMayContainTemplate(cx, cz)) targets.add(chunkKey(cx, cz));
            }
        }
        targets.sort(Comparator.comparingDouble(key -> {
            int cx = (int) (key >> 32);
            int cz = key.intValue();
            double dx = cx * 16.0D;
            double dz = cz * 16.0D + 188.0D;
            return dx * dx + dz * dz;
        }));
        return targets;
    }

    private boolean chunkMayContainTemplate(int cx, int cz) {
        int minX = cx << 4;
        int maxX = minX + 15;
        int minZ = cz << 4;
        int maxZ = minZ + 15;
        if (intersects(minX, maxX, minZ, maxZ, -164, 164, -132, 270)) return true;
        return intersects(minX, maxX, minZ, maxZ, -12, 12, -210, -105);
    }

    private boolean intersects(int minX, int maxX, int minZ, int maxZ,
                               int boxMinX, int boxMaxX, int boxMinZ, int boxMaxZ) {
        return maxX >= boxMinX && minX <= boxMaxX
                && maxZ >= boxMinZ && minZ <= boxMaxZ;
    }

    private long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private void startLazyBuilder(World world) {
        ensureQueue(world);
        if (scanTask != null) scanTask.cancel();
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (queue == null || queue.pending() > 18) return;
            for (Chunk chunk : world.getLoadedChunks()) {
                if (!chunk.getPersistentDataContainer().has(builtKey, PersistentDataType.BYTE)) queue.offer(chunk);
                if (queue.pending() > 18) break;
            }
        }, 1L, 20L);
    }

    private void ensureQueue(World world) {
        if (queue != null) return;
        int blocks = Math.min(400, Math.max(100,
                plugin.getConfig().getInt("template-worlds.nether.blocks-per-tick", 350)));
        long millis = Math.min(5L, Math.max(2L,
                plugin.getConfig().getLong("template-worlds.nether.max-build-millis-per-tick", 4L)));
        long releaseDelay = plugin.getConfig().getLong(
                "template-worlds.safety.build-chunk-ticket-release-delay-ticks", 100L);
        int retainedChunks = plugin.getConfig().getInt(
                "template-worlds.safety.max-retained-build-chunks", 8);
        queue = new TemplateChunkBuildQueue(plugin, world, new TemplateChunkBuildQueue.Planner() {
            @Override public List<TemplateChunkBuildQueue.BlockOp> plan(Chunk chunk) { return planChunk(chunk); }
            @Override public void complete(Chunk chunk) { finishChunk(chunk); }
        }, blocks, millis, releaseDelay, retainedChunks);
        queue.start();
    }

    private void stopLazyBuilder() {
        if (scanTask != null) scanTask.cancel();
        scanTask = null;
        if (queue != null) queue.shutdown();
        queue = null;
    }

    private List<TemplateChunkBuildQueue.BlockOp> planChunk(Chunk chunk) {
        List<TemplateChunkBuildQueue.BlockOp> ops = new ArrayList<>();
        World world = chunk.getWorld();
        int minX = chunk.getX() << 4;
        int minZ = chunk.getZ() << 4;
        Random random = new Random(world.getSeed() ^ chunk.getX() * 73428767L ^ chunk.getZ() * 912931L);

        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                boolean bastion = inBastionEnvelope(x, z);
                if (bastion) {
                    // La zona completa se sanea después de la generación del chunk.
                    // Esto elimina fortalezas/bastiones de Minecraft y estructuras de
                    // mods que hayan invadido el volumen reservado.
                    for (int y = protectedClearMinY(); y <= protectedClearMaxY(); y++) {
                        ops.add(op(x, y, z, Material.AIR));
                    }
                    planAdaptiveNetherFoundation(ops, x, z);
                    ops.add(op(x, 62, z, floorMaterial(x, z)));
                    planBastionColumn(ops, x, z);
                }
            }
        }

        if (!intersectsBastion(chunk)) planGoldCorruption(chunk, ops, random);
        return ops;
    }

    private void planBastionColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z) {
        final int floor = 62;
        int ax = Math.abs(x), az = Math.abs(z);
        double outer = octagonalMetric(x, z, 146.0D, 120.0D);
        boolean arrivalCorridor = Math.abs(x) <= 11 && z >= -206 && z <= -105;

        // Piso continuo pero con bordes naturales; los canales quedan hundidos y no forman láminas flotantes.
        if (outer <= 1.0D && !isNetherLavaChannel(x, z)) {
            ops.add(op(x, floor, z, ((x * 13 + z * 7) & 31) == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE));
        }
        if (arrivalCorridor) {
            ops.add(op(x, floor, z, ((x + z) & 7) == 0
                    ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
            if (Math.abs(x) >= 9) {
                for (int y = floor + 1; y <= floor + 10; y++) {
                    ops.add(op(x, y, z, y == floor + 5
                            ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
                }
            }
            if (Math.floorMod(z + 206, 14) == 0 && Math.abs(x) <= 10) {
                ops.add(op(x, floor + 11, z, Math.abs(x) >= 8
                        ? Material.POLISHED_BASALT : Material.POLISHED_BLACKSTONE_SLAB));
            }
            if (Math.abs(x) == 7 && Math.floorMod(z + 203, 18) == 0) {
                ops.add(op(x, floor + 2, z, Material.SOUL_LANTERN));
            }
        }
        if (isNetherLavaChannel(x, z) && outer < 0.90D) {
            ops.add(op(x, floor - 2, z, Material.MAGMA_BLOCK));
            ops.add(op(x, floor - 1, z, Material.LAVA));
            ops.add(op(x, floor, z, Material.AIR));
            ops.add(op(x, floor + 1, z, Material.AIR));
        }

        // Muralla quebrada con puertas monumentales y alturas variadas.
        if (outer >= 0.94D && outer <= 1.02D) {
            double angle = Math.toDegrees(Math.atan2(z / 120.0D, x / 146.0D));
            if (angle < 0) angle += 360.0D;
            boolean gate = nearAngle(angle, 0, 10) || nearAngle(angle, 90, 10) || nearAngle(angle, 180, 10) || nearAngle(angle, 270, 10);
            boolean collapsed = Math.floorMod(x * 5 + z * 11, 37) < 4;
            if (!gate && !collapsed) {
                int height = 13 + Math.floorMod(x + z, 10);
                for (int y = floor + 1; y <= floor + height; y++) ops.add(op(x, y, z,
                        (x + z + y) % 15 == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
            }
        }

        // Torres de siluetas distintas y abiertas en su interior.
        int[][] towers = {{-116,-90,10,27},{116,-90,12,35},{-116,88,9,31},{116,88,11,29},
                {-64,0,8,23},{66,4,9,27},{0,-78,12,33}};
        for (int[] t : towers) planNetherTowerColumn(ops, x, z, t[0], t[1], t[2], floor, t[3]);

        // Cuatro barrios conectados por calles, patios y edificios en L.
        planNetherDistrictColumn(ops, x, z, -72, -42, 0);
        planNetherDistrictColumn(ops, x, z, 72, -42, 1);
        planNetherDistrictColumn(ops, x, z, -70, 50, 2);
        planNetherDistrictColumn(ops, x, z, 70, 50, 3);

        // Forja central circular y patios abiertos: reemplaza el antiguo bloque gigante en cruz.
        double forgeD = Math.hypot(x, z - 4);
        if (forgeD >= 26 && forgeD <= 35) {
            int height = 15 + Math.floorMod(x - z, 7);
            for (int y = floor + 1; y <= floor + height; y++) ops.add(op(x, y, z,
                    (y % 6 == 0) ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
        }
        if (forgeD <= 24) {
            ops.add(op(x, floor, z, ((x + z) & 5) == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BASALT));
            if (forgeD >= 8 && forgeD <= 12 && ((x + z) & 3) == 0) ops.add(op(x, floor + 1, z, Material.MAGMA_BLOCK));
        }
        if ((ax <= 5 || az <= 5) && outer < 0.91D) ops.add(op(x, floor, z, Material.POLISHED_BLACKSTONE_BRICKS));

        planNetherMarketColumn(ops, x, z, floor);
        planNetherUpperWalkways(ops, x, z, floor);
        planNetherDungeonColumn(ops, x, z, floor);
        planNetherFunctionalInteriorColumn(ops, x, z, floor);
        planNetherBossArenaColumn(ops, x, z, floor);

        // Puente final y cámara del portal, con laterales y techo abovedado.
        if (Math.abs(x) <= 7 && z >= 118 && z <= 226) {
            ops.add(op(x, floor + 2, z, ((x + z) & 7) == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
            if (Math.abs(x) == 7) ops.add(op(x, floor + 3, z, Material.POLISHED_BLACKSTONE_BRICK_WALL));
        }
        if (Math.abs(x) <= 27 && z >= 220 && z <= 258) {
            boolean shell = Math.abs(x) >= 23 || z <= 223 || z >= 255;
            if (shell) {
                for (int y = floor + 1; y <= floor + 18; y++) ops.add(op(x, y, z,
                        (x + z + y) % 11 == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
            }
            if (z == 247 && Math.abs(x) <= 6) {
                for (int y = floor + 2; y <= floor + 13; y++) ops.add(op(x, y, z,
                        (Math.abs(x) == 6 || y == floor + 13) ? Material.OBSIDIAN : Material.AIR));
            }
        }

        // Cristales de oro colocados solamente en plazas y bordes, nunca atravesando pasillos.
        if (outer < 0.90D && !isNetherLavaChannel(x, z) && ((x * 31 + z * 17) & 511) == 11) {
            for (int y = floor + 1; y <= floor + 5 + Math.abs((x + z) % 6); y++) ops.add(op(x, y, z,
                    y % 4 == 0 ? Material.SHROOMLIGHT : Material.GOLD_BLOCK));
        }
    }

    private boolean nearAngle(double angle, double target, double tolerance) {
        double diff = Math.abs(angle - target) % 360.0D;
        return Math.min(diff, 360.0D - diff) <= tolerance;
    }

    private boolean isNetherLavaChannel(int x, int z) {
        return ((Math.abs(x) >= 18 && Math.abs(x) <= 22 && Math.abs(z) <= 108)
                || (Math.abs(z) >= 18 && Math.abs(z) <= 22 && Math.abs(x) <= 128));
    }

    private int protectedClearMinY() {
        return Math.max(8, plugin.getConfig().getInt(
                "template-worlds.nether.protected-zone.clear-min-y", 36));
    }

    private int protectedClearMaxY() {
        return Math.min(126, plugin.getConfig().getInt(
                "template-worlds.nether.protected-zone.clear-max-y", 125));
    }

    private void planAdaptiveNetherFoundation(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z) {
        int hash = yHash(x, z);
        int bottom = 42 + Math.floorMod(hash, 7);
        double outer = octagonalMetric(x, z, 146.0D, 120.0D);
        boolean structural = outer <= 0.93D
                || Math.hypot(x, z - 176.0D) <= 58.0D
                || (Math.abs(x) <= 30 && z >= 110 && z <= 258)
                || (Math.abs(x) <= 14 && z >= -212 && z <= -103);
        if (!structural) bottom = 50 + Math.floorMod(hash, 5);
        for (int y = bottom; y <= 61; y++) {
            Material material;
            if (y <= bottom + 2) material = ((hash + y) & 3) == 0 ? Material.BASALT : Material.BLACKSTONE;
            else if ((hash + y) % 11 == 0) material = Material.GILDED_BLACKSTONE;
            else material = Material.POLISHED_BLACKSTONE_BRICKS;
            ops.add(op(x, y, z, material));
        }
        // Contrafuertes profundos en una cuadrícula irregular para evitar una losa flotante.
        if (structural && Math.floorMod(x, 12) <= 2 && Math.floorMod(z, 12) <= 2) {
            int pillarBottom = Math.max(protectedClearMinY(), bottom - 14 - Math.floorMod(hash, 8));
            for (int y = pillarBottom; y < bottom; y++) {
                ops.add(op(x, y, z, ((y + hash) & 5) == 0 ? Material.GILDED_BLACKSTONE : Material.BASALT));
            }
        }
    }

    private void planNetherMarketColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z, int floor) {
        // Mercado/forja al norte: puestos, pasarelas y chimeneas para densificar la zona vacía.
        if (x < -52 || x > 52 || z < -112 || z > -70) return;
        int ax = Math.abs(x);
        if (Math.floorMod(x + 52, 13) <= 7 && Math.floorMod(z + 112, 14) <= 8) {
            ops.add(op(x, floor, z, ((x + z) & 3) == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE));
            boolean edge = Math.floorMod(x + 52, 13) == 0 || Math.floorMod(x + 52, 13) == 7
                    || Math.floorMod(z + 112, 14) == 0 || Math.floorMod(z + 112, 14) == 8;
            if (edge) {
                for (int y = floor + 1; y <= floor + 6; y++) {
                    ops.add(op(x, y, z, y == floor + 5 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
                }
            }
            if (!edge && ((x * 17 + z * 31) & 15) == 0) ops.add(op(x, floor + 1, z, Material.SOUL_LANTERN));
        }
        if (ax <= 4) {
            ops.add(op(x, floor + 1, z, Material.POLISHED_BASALT));
            if (Math.floorMod(z, 9) == 0) ops.add(op(x, floor + 2, z, Material.MAGMA_BLOCK));
        }
        if ((Math.abs(x) == 48 || Math.abs(x) == 36) && Math.floorMod(z + 112, 18) <= 2) {
            for (int y = floor + 1; y <= floor + 18; y++) {
                ops.add(op(x, y, z, y % 6 == 0 ? Material.SHROOMLIGHT : Material.BASALT));
            }
        }
    }

    private void planNetherUpperWalkways(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z, int floor) {
        boolean eastWest = Math.abs(z - 4) <= 2 && Math.abs(x) >= 34 && Math.abs(x) <= 116;
        boolean northSouth = Math.abs(x) <= 2 && z >= -72 && z <= 108;
        if (!eastWest && !northSouth) return;
        int y = floor + 14;
        ops.add(op(x, y, z, ((x + z) & 7) == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
        if ((eastWest && Math.abs(z - 4) == 2) || (northSouth && Math.abs(x) == 2)) {
            ops.add(op(x, y + 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL));
        }
        if (Math.floorMod(x + z, 16) == 0) {
            for (int py = floor + 1; py < y; py++) ops.add(op(x, py, z, Material.POLISHED_BASALT));
        }
    }

    private void planNetherTowerColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z,
                                       int cx, int cz, int radius, int floor, int height) {
        double d = Math.hypot(x - cx, z - cz);
        if (d >= radius - 2.2D && d <= radius) {
            for (int y = floor + 1; y <= floor + height; y++) ops.add(op(x, y, z,
                    y % 7 == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
        }
        if (d <= radius - 2.5D) {
            ops.add(op(x, floor, z, Material.POLISHED_BLACKSTONE));
            if (yHash(x, z) % 19 == 0) ops.add(op(x, floor + 1, z, Material.SOUL_LANTERN));
        }

        // Plataformas intermedias, corona escalonada y acceso: las torres dejan de ser
        // cilindros huecos sin recorrido ni remate visible.
        if (d <= radius - 2.5D && (height > 18)) {
            if (floor + height / 2 > floor + 6) ops.add(op(x, floor + height / 2, z, Material.POLISHED_BLACKSTONE_SLAB));
            if (d >= radius - 4.2D && d <= radius - 1.5D) ops.add(op(x, floor + height + 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL));
        }
        if (Math.abs(x - cx) <= 1 && z == cz - radius) {
            for (int y = floor + 1; y <= floor + 4; y++) ops.add(op(x, y, z, Material.AIR));
        }
        if (d <= Math.max(1.0D, radius - 5.0D) && floor + height + 2 < 124
                && Math.floorMod(x - cx, 3) == 0 && Math.floorMod(z - cz, 3) == 0) {
            ops.add(op(x, floor + height + 2, z, Material.GILDED_BLACKSTONE));
        }
    }

    private void planNetherDistrictColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z,
                                          int cx, int cz, int style) {
        int dx = x - cx, dz = z - cz;
        int ax = Math.abs(dx), az = Math.abs(dz);
        boolean main = (ax <= 18 && az <= 12) || (ax <= 9 && az <= 24);
        boolean inner = (ax <= 14 && az <= 8) || (ax <= 5 && az <= 20);
        if (main) {
            ops.add(op(x, 62, z, Material.POLISHED_BLACKSTONE));
            if (!inner) {
                int height = 8 + style * 2 + Math.floorMod(dx + dz, 4);
                for (int y = 63; y <= 62 + height; y++) ops.add(op(x, y, z,
                        (x + z + y) % 12 == 0 ? Material.GILDED_BLACKSTONE : Material.BLACKSTONE));
            }

            // Tejado aterrazado con lucernario central y vigas que dan una silueta propia
            // a cada barrio sin cerrar los patios.
            int roof = 72 + style * 2;
            if (!inner || ax >= 5 || az >= 5) ops.add(op(x, roof, z,
                    ((x + z) & 5) == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_SLAB));
            if (ax <= 2 && az <= 3) ops.add(op(x, roof, z, Material.AIR));
        }

        // Puerta principal de cinco bloques y ventanas de lava protegidas.
        if (ax <= 2 && dz == -12) for (int y = 63; y <= 67; y++) ops.add(op(x, y, z, Material.AIR));
        if ((ax == 18 && Math.floorMod(dz, 8) == 0) || (az == 24 && Math.floorMod(dx, 7) == 0)) {
            ops.add(op(x, 66, z, Material.ORANGE_STAINED_GLASS_PANE));
            ops.add(op(x, 67, z, Material.ORANGE_STAINED_GLASS_PANE));
        }
        // Patio lateral y galería porticada.
        if (dx >= 20 && dx <= 35 && az <= 13) {
            ops.add(op(x, 62, z, Material.POLISHED_BASALT));
            if ((dx == 20 || dx == 35 || az == 13) && Math.floorMod(x + z, 5) != 0) {
                for (int y = 63; y <= 70 + style; y++) ops.add(op(x, y, z, Material.POLISHED_BLACKSTONE_BRICKS));
            }
        }
    }

    private void planNetherDungeonColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z, int floor) {
        // Laberinto occidental de dos niveles con habitaciones abiertas, no una caja cerrada.
        if (x < -132 || x > -70 || z < -90 || z > -22) return;
        ops.add(op(x, floor, z, ((x + z) & 7) == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE));
        int lx = x + 132, lz = z + 90;
        boolean wall = (lx % 14 == 0 && lz % 28 > 5) || (lz % 13 == 0 && lx % 28 > 6);
        boolean roomShell = ((lx >= 6 && lx <= 22 && lz >= 7 && lz <= 23)
                || (lx >= 34 && lx <= 56 && lz >= 36 && lz <= 60));
        boolean roomInner = ((lx >= 9 && lx <= 19 && lz >= 10 && lz <= 20)
                || (lx >= 37 && lx <= 53 && lz >= 39 && lz <= 57));
        if (wall || (roomShell && !roomInner)) {
            for (int y = floor + 1; y <= floor + 8; y++) ops.add(op(x, y, z,
                    (x + z + y) % 9 == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
        }
        if (lx >= 30 && lx <= 60 && lz >= 3 && lz <= 28) {
            ops.add(op(x, floor + 12, z, Material.POLISHED_BLACKSTONE_BRICKS));
            if ((lx == 30 || lx == 60 || lz == 3 || lz == 28) && (lx + lz) % 5 != 0) {
                for (int y = floor + 13; y <= floor + 20; y++) ops.add(op(x, y, z, Material.BLACKSTONE));
            }
        }
    }


    /**
     * Convierte edificios y habitaciones del súper-bastión en espacios funcionales.
     * Cada sala tiene una lectura visual propia: armería, fundición, barracones,
     * reliquias, prisión, alquimia, suministros o tesoro.
     */
    private void planNetherFunctionalInteriorColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z, int floor) {
        planNetherRoom(ops, x, z, -72, -42, 13, 8, floor, 0);   // armería
        planNetherRoom(ops, x, z, 72, -42, 13, 8, floor, 1);    // fundición
        planNetherRoom(ops, x, z, -70, 50, 13, 8, floor, 2);    // barracones
        planNetherRoom(ops, x, z, 70, 50, 13, 8, floor, 3);     // reliquias
        planNetherRoom(ops, x, z, -116, -67, 9, 7, floor, 4);   // prisión
        planNetherRoom(ops, x, z, -93, -42, 10, 7, floor, 5);   // alquimia
        planNetherRoom(ops, x, z, -34, -92, 8, 6, floor, 6);    // suministros
        planNetherRoom(ops, x, z, 34, -92, 8, 6, floor, 7);     // cámara del tesoro
    }

    private void planNetherRoom(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z,
                                int cx, int cz, int halfX, int halfZ, int floor, int style) {
        int dx = x - cx, dz = z - cz;
        if (Math.abs(dx) > halfX || Math.abs(dz) > halfZ) return;
        int hash = Math.floorMod(dx * 31 + dz * 17 + style * 53, 23);
        ops.add(op(x, floor, z, switch (style) {
            case 0 -> hash % 5 == 0 ? Material.IRON_BLOCK : Material.POLISHED_BLACKSTONE;
            case 1 -> hash % 4 == 0 ? Material.MAGMA_BLOCK : Material.POLISHED_BASALT;
            case 2 -> hash % 5 == 0 ? Material.CRIMSON_PLANKS : Material.BLACKSTONE;
            case 3, 7 -> hash % 4 == 0 ? Material.GOLD_BLOCK : Material.GILDED_BLACKSTONE;
            case 4 -> Material.POLISHED_BLACKSTONE_BRICKS;
            case 5 -> hash % 6 == 0 ? Material.CRYING_OBSIDIAN : Material.BLACKSTONE;
            default -> Material.CRIMSON_PLANKS;
        }));

        boolean edge = Math.abs(dx) == halfX || Math.abs(dz) == halfZ;
        if (edge && Math.floorMod(dx + dz, 5) == 0) {
            ops.add(op(x, floor + 2, z, style == 4 ? Material.IRON_BARS : Material.CHAIN));
            ops.add(op(x, floor + 3, z, style == 4 ? Material.IRON_BARS : Material.CHAIN));
        }

        switch (style) {
            case 0 -> { // armería
                if (dz == halfZ - 1 && Math.floorMod(dx, 4) == 0) ops.add(op(x, floor + 1, z, Material.SMITHING_TABLE));
                if (dz == -halfZ + 2 && Math.floorMod(dx, 5) == 0) ops.add(op(x, floor + 1, z, Material.ANVIL));
                if (Math.abs(dx) == halfX - 2 && Math.floorMod(dz, 4) == 0) ops.add(op(x, floor + 1, z, Material.BARREL));
            }
            case 1 -> { // fundición
                if (Math.abs(dx) <= 2 && Math.abs(dz) <= halfZ - 2) {
                    ops.add(op(x, floor, z, Material.MAGMA_BLOCK));
                    if (Math.floorMod(dz, 5) == 0) ops.add(op(x, floor + 1, z, Material.LAVA));
                }
                if (Math.abs(dx) == halfX - 2 && Math.floorMod(dz, 4) == 0) ops.add(op(x, floor + 1, z, Material.BLAST_FURNACE));
            }
            case 2 -> { // barracones
                if (Math.abs(dz) == halfZ - 2 && Math.floorMod(dx, 5) <= 2) ops.add(op(x, floor + 1, z, Material.CRIMSON_SLAB));
                if (Math.abs(dx) == halfX - 2 && Math.floorMod(dz, 5) == 0) ops.add(op(x, floor + 1, z, Material.BARREL));
                if (dx == 0 && dz == 0) ops.add(op(x, floor + 1, z, Material.TARGET));
            }
            case 3 -> { // santuario de reliquias
                double d = Math.hypot(dx, dz);
                if (d <= 3.2D) ops.add(op(x, floor + 1, z, d <= 1.2D ? Material.RESPAWN_ANCHOR : Material.CRYING_OBSIDIAN));
                if (d >= 5 && d <= 6 && hash % 3 == 0) ops.add(op(x, floor + 1, z, Material.SOUL_LANTERN));
            }
            case 4 -> { // prisión
                if (Math.floorMod(dx + halfX, 6) == 0 && Math.abs(dz) >= 2) {
                    for (int y = floor + 1; y <= floor + 5; y++) ops.add(op(x, y, z, Material.IRON_BARS));
                }
                if (Math.abs(dx) == halfX - 2 && Math.floorMod(dz, 5) == 0) ops.add(op(x, floor + 1, z, Material.CHAIN));
            }
            case 5 -> { // laboratorio alquímico
                if (Math.abs(dx) == halfX - 2 && Math.floorMod(dz, 4) == 0) ops.add(op(x, floor + 1, z, Material.BREWING_STAND));
                if (Math.abs(dz) == halfZ - 2 && Math.floorMod(dx, 5) == 0) ops.add(op(x, floor + 1, z, Material.CAULDRON));
                if (dx == 0 && dz == 0) ops.add(op(x, floor + 1, z, Material.SOUL_CAMPFIRE));
            }
            case 6 -> { // almacén
                if (Math.abs(dx) >= halfX - 2 || Math.abs(dz) >= halfZ - 2) {
                    if (hash % 3 == 0) ops.add(op(x, floor + 1, z, Material.BARREL));
                    else if (hash % 5 == 0) ops.add(op(x, floor + 1, z, Material.CHEST));
                }
            }
            case 7 -> { // tesoro
                if (Math.abs(dx) <= 4 && Math.abs(dz) <= 3 && ((dx + dz) & 1) == 0) ops.add(op(x, floor + 1, z, Material.GOLD_BLOCK));
                if ((Math.abs(dx) == 6 && dz == 0) || (Math.abs(dz) == 4 && dx == 0)) ops.add(op(x, floor + 1, z, Material.GILDED_BLACKSTONE));
            }
            default -> { }
        }
    }

    private void planNetherBossArenaColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z, int floor) {
        double d = Math.hypot(x, z - 176.0D);
        if (d > 58) return;
        if (d <= 52) {
            ops.add(op(x, floor + 2, z, d <= 18 ? Material.POLISHED_BASALT : Material.POLISHED_BLACKSTONE));
            // El centro queda libre hasta gran altura para que el jefe nunca aparezca encerrado.
            if (d <= 23) for (int y = floor + 3; y <= floor + 24; y++) ops.add(op(x, y, z, Material.AIR));
        }
        double angle = Math.toDegrees(Math.atan2(z - 176.0D, x));
        if (angle < 0) angle += 360.0D;
        boolean entrance = nearAngle(angle, 270.0D, 13.0D);
        if (d >= 49 && d <= 55 && !entrance) {
            int height = 20 + Math.floorMod(x + z, 7);
            for (int y = floor + 3; y <= floor + height; y++) ops.add(op(x, y, z,
                    y % 7 == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
        }
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2.0D * i / 6.0D;
            int px = (int) Math.round(Math.cos(a) * 36.0D);
            int pz = 176 + (int) Math.round(Math.sin(a) * 36.0D);
            double pd = Math.hypot(x - px, z - pz);
            if (pd <= 3.2D) {
                for (int y = floor + 3; y <= floor + 15 + i; y++) ops.add(op(x, y, z,
                        y % 5 == 0 ? Material.SHROOMLIGHT : Material.GOLD_BLOCK));
            }
        }
    }

    private void planGoldCorruption(Chunk chunk, List<TemplateChunkBuildQueue.BlockOp> ops, Random random) {
        World world = chunk.getWorld();
        int patches = 1 + random.nextInt(2);
        for (int i = 0; i < patches; i++) {
            int x = (chunk.getX() << 4) + 3 + random.nextInt(10);
            int z = (chunk.getZ() << 4) + 3 + random.nextInt(10);
            int y = findNetherSurface(world, x, z);
            if (y < 0) continue;
            int h = 3 + random.nextInt(7);
            for (int dy = 0; dy < h; dy++) {
                Material m = dy % 4 == 0 ? Material.SHROOMLIGHT : (dy % 3 == 0 ? Material.GILDED_BLACKSTONE : Material.GOLD_BLOCK);
                ops.add(op(x + dy / 4, y + dy, z + dy / 5, m));
            }
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz <= 5) ops.add(op(x + dx, y - 1, z + dz,
                        random.nextBoolean() ? Material.GILDED_BLACKSTONE : Material.MAGMA_BLOCK));
            }
        }
    }

    private int findNetherSurface(World world, int x, int z) {
        for (int y = 116; y >= 34; y--) {
            Block below = world.getBlockAt(x, y - 1, z);
            Block at = world.getBlockAt(x, y, z);
            if (below.getType().isSolid() && at.isEmpty()) return y;
        }
        return -1;
    }

    private void finishChunk(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        placeFixedContent(chunk);

        // La limpieza del constructor se ejecuta antes de este callback. La
        // cerradura debe colocarse aquí, no solamente al final de toda la plantilla,
        // para que una reconstrucción diferida del chunk no vuelva a borrarla.
        if (contains(chunk, -6, 116)) {
            Location lock = TemplateCampaignRepair.ensureNetherLock(
                    chunk.getWorld(), new Location(chunk.getWorld(), -6, 63, 116));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (lock.getWorld() != null
                        && lock.getWorld().isChunkLoaded(lock.getBlockX() >> 4, lock.getBlockZ() >> 4)) {
                    TemplateCampaignRepair.ensureNetherLock(lock.getWorld(), lock);
                }
            }, 2L);
        }

        chunk.getPersistentDataContainer().set(builtKey, PersistentDataType.BYTE, (byte) 1);
        if (stage == Stage.FINALIZING) {
            long key = chunkKey(chunk.getX(), chunk.getZ());
            if (finalizationOutstanding.remove(key)) {
                finalizationDone++;
                updateFinalizationProgress();
            }
        }
    }

    private void placeFixedContent(Chunk chunk) {
        record Spawn(int x, int y, int z, String id) { }
        List<Spawn> spawns = List.of(
                new Spawn(-116,64,-90,"arlightbosses:gilded_piglin_minion"),
                new Spawn(116,64,-90,"arlightbosses:gilded_blaze_wraith_minion"),
                new Spawn(-116,64,88,"arlightbosses:gilded_hoglin_minion"),
                new Spawn(116,64,88,"arlightbosses:gilded_wither_skeleton_vanguard_minion"),
                new Spawn(-72,64,-42,"arlightbosses:gilded_piglin_minion"),
                new Spawn(72,64,-42,"arlightbosses:gilded_blaze_wraith_minion"),
                new Spawn(-70,64,50,"arlightbosses:gilded_hoglin_rider_minion"),
                new Spawn(70,64,50,"arlightbosses:gilded_wither_skeleton_vanguard_minion"),
                new Spawn(-16,64,4,"arlightbosses:molten_strider_minion"),
                new Spawn(16,64,4,"arlightbosses:gilded_piglin_minion"),
                new Spawn(-118,64,-72,"arlightbosses:gilded_wither_skeleton_vanguard_minion"),
                new Spawn(-94,64,-58,"arlightbosses:gilded_piglin_minion"),
                new Spawn(-82,76,-42,"arlightbosses:gilded_blaze_wraith_minion"),
                new Spawn(-106,76,-30,"arlightbosses:gilded_hoglin_minion"),
                new Spawn(-34,66,176,"arlightbosses:gilded_wither_skeleton_vanguard_minion"),
                new Spawn(34,66,176,"arlightbosses:gilded_blaze_wraith_minion"),
                new Spawn(0,66,140,"arlightbosses:gilded_hoglin_rider_minion"),
                new Spawn(0,65,236,"arlightbosses:gilded_piglin_minion"),
                new Spawn(-78,64,-42,"arlightbosses:gilded_wither_skeleton_vanguard_minion"),
                new Spawn(78,64,-42,"arlightbosses:gilded_blaze_wraith_minion"),
                new Spawn(-76,64,50,"arlightbosses:gilded_hoglin_minion"),
                new Spawn(76,64,50,"arlightbosses:gilded_piglin_minion"),
                new Spawn(-116,64,-67,"arlightbosses:gilded_wither_skeleton_vanguard_minion"),
                new Spawn(-93,64,-42,"arlightbosses:gilded_blaze_wraith_minion"),
                new Spawn(34,64,-92,"arlightbosses:gilded_hoglin_rider_minion")
        );
        for (Spawn spawn : spawns) if (contains(chunk, spawn.x(), spawn.z())) placeSpawner(chunk.getWorld(), spawn.x(), spawn.y(), spawn.z(), spawn.id());

        record Loot(int x, int y, int z, LootTables table, boolean barrel) { }
        List<Loot> loot = List.of(
                new Loot(-72,64,-42,LootTables.BASTION_OTHER,false),
                new Loot(72,64,-42,LootTables.BASTION_BRIDGE,true),
                new Loot(-70,64,50,LootTables.BASTION_HOGLIN_STABLE,true),
                new Loot(70,64,50,LootTables.BASTION_OTHER,false),
                new Loot(0,64,4,LootTables.BASTION_TREASURE,false),
                new Loot(-118,64,-48,LootTables.NETHER_BRIDGE,false),
                new Loot(-82,76,-54,LootTables.BASTION_TREASURE,true),
                new Loot(-22,66,176,LootTables.BASTION_TREASURE,false),
                new Loot(22,66,176,LootTables.BASTION_TREASURE,false),
                new Loot(0,65,238,LootTables.BASTION_TREASURE,false),
                new Loot(-78,63,-42,LootTables.BASTION_BRIDGE,true),
                new Loot(-66,63,-42,LootTables.NETHER_BRIDGE,false),
                new Loot(66,63,-42,LootTables.BASTION_OTHER,true),
                new Loot(78,63,-42,LootTables.BASTION_TREASURE,false),
                new Loot(-76,63,50,LootTables.BASTION_HOGLIN_STABLE,true),
                new Loot(-64,63,50,LootTables.BASTION_OTHER,false),
                new Loot(64,63,50,LootTables.BASTION_TREASURE,false),
                new Loot(76,63,50,LootTables.BASTION_OTHER,true),
                new Loot(-121,63,-67,LootTables.NETHER_BRIDGE,true),
                new Loot(-111,63,-67,LootTables.BASTION_OTHER,false),
                new Loot(-98,63,-42,LootTables.BASTION_OTHER,true),
                new Loot(-88,63,-42,LootTables.NETHER_BRIDGE,false),
                new Loot(-38,63,-92,LootTables.BASTION_OTHER,true),
                new Loot(38,63,-92,LootTables.BASTION_TREASURE,false)
        );
        for (Loot l : loot) if (contains(chunk, l.x(), l.z())) placeLoot(chunk.getWorld(), l.x(), l.y(), l.z(), l.table().getLootTable(), l.barrel());
    }

    private void placeSpawner(World world, int x, int y, int z, String id) {
        world.getBlockAt(x, y, z).setType(Material.SPAWNER, false);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), String.format(Locale.ROOT,
                "execute in %s run data merge block %d %d %d {Delay:80s,MinSpawnDelay:220s,MaxSpawnDelay:360s,SpawnCount:2s,MaxNearbyEntities:4s,RequiredPlayerRange:18s,SpawnRange:5s,SpawnData:{entity:{id:\"%s\",Tags:[\"arlightbingo_template_mob\"],PersistenceRequired:1b}},SpawnPotentials:[{weight:1,data:{entity:{id:\"%s\",Tags:[\"arlightbingo_template_mob\"],PersistenceRequired:1b}}}]} ",
                world.getKey(), x, y, z, id, id));
    }

    private void placeLoot(World world, int x, int y, int z, LootTable table, boolean barrel) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(barrel ? Material.BARREL : Material.CHEST, false);
        if (barrel && block.getState() instanceof Barrel state) {
            state.setLootTable(table); state.setSeed(world.getSeed() ^ x * 31L ^ z); state.update(true, false);
        } else if (!barrel && block.getState() instanceof Chest state) {
            state.setLootTable(table); state.setSeed(world.getSeed() ^ x * 31L ^ z); state.update(true, false);
        }
    }

    private boolean inBastionEnvelope(int x, int z) {
        return octagonalMetric(x, z, 142.0D, 118.0D) <= 1.05D
                || Math.hypot(x, z - 170) <= 52.0D
                || (Math.abs(x) <= 28 && z >= 110 && z <= 254)
                || (Math.abs(x) <= 12 && z >= -210 && z <= -105);
    }

    private boolean intersectsBastion(Chunk chunk) {
        int minX = chunk.getX() << 4, maxX = minX + 15;
        int minZ = chunk.getZ() << 4, maxZ = minZ + 15;
        for (int x : new int[]{minX, maxX}) for (int z : new int[]{minZ, maxZ}) if (inBastionEnvelope(x, z)) return true;
        return minX <= 0 && maxX >= 0
                && ((minZ <= 170 && maxZ >= 170)
                || (maxZ >= -210 && minZ <= -105));
    }

    private double octagonalMetric(int x, int z, double rx, double rz) {
        double nx = Math.abs(x) / rx;
        double nz = Math.abs(z) / rz;
        return Math.max(nx, nz) + 0.42D * Math.min(nx, nz);
    }

    private Material floorMaterial(int x, int z) {
        int hash = yHash(x, z);
        if ((hash & 31) == 0) return Material.GILDED_BLACKSTONE;
        if ((hash & 15) == 0) return Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
        return Material.POLISHED_BLACKSTONE_BRICKS;
    }

    private int yHash(int x, int z) { return x * 7349 ^ z * 9151; }

    private TemplateChunkBuildQueue.BlockOp op(int x, int y, int z, Material m) {
        return TemplateChunkBuildQueue.BlockOp.of(x, y, z, m);
    }

    private boolean contains(Chunk chunk, int x, int z) { return (x >> 4) == chunk.getX() && (z >> 4) == chunk.getZ(); }



    private void summonBossWhenReady(Player player, World world, Location arena, String entityId, String tag, int attempts) {
        int cx = arena.getBlockX() >> 4;
        int cz = arena.getBlockZ() >> 4;
        if (world.isChunkLoaded(cx, cz)) {
            Chunk chunk = world.getChunkAt(cx, cz);
            if (chunk.getPersistentDataContainer().has(builtKey, PersistentDataType.BYTE)) {
                cleanupBoss(world, tag);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), String.format(Locale.ROOT,
                        "execute in %s run summon %s %.2f %.2f %.2f {Tags:[\"%s\"],PersistenceRequired:1b}",
                        world.getKey(), entityId, arena.getX(), arena.getY(), arena.getZ(), tag));
                player.sendMessage(ChatColor.GREEN + "Jefe de prueba generado en la arena.");
                return;
            }
        }
        if (attempts <= 0) {
            player.sendMessage(ChatColor.RED + "La arena aún no terminó de construirse. Espera un poco y vuelve a usar testboss.");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> summonBossWhenReady(player, world, arena, entityId, tag, attempts - 1), 10L);
    }

    private void prioritizeLoadedArea(World world, int blockX, int blockZ, int radiusChunks) {
        ensureQueue(world);
        int centerX = blockX >> 4;
        int centerZ = blockZ >> 4;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = centerX + dx, cz = centerZ + dz;
                if (world.isChunkLoaded(cx, cz)) queue.offerPriority(world.getChunkAt(cx, cz));
            }
        }
    }

    private void repairCampaignAnchors(World world) {
        if (world == null) return;
        TemplateMarkerLookup.rememberWorld(plugin, world);
        TemplateMarkerLookup.ensureProperties(plugin, worldName(), MARKER, java.util.Map.of(
                "spawn", "0,63,-188", "boss", "0,66,176", "portal", "0,65,238",
                "gate", "0,65,118", "lock", "-6,63,116",
                "keys", "-72,64,-42;72,64,-42;-70,64,50;70,64,50"));
        TemplateCampaignRepair.ensureNetherKeyChests(world, List.of());
        Location lock = TemplateCampaignRepair.ensureNetherLock(world, null);
        // Segundo pase breve para crear correctamente la BlockEntity de
        // ArlightBosses después de cargar el chunk en Arclight.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (lock.getWorld() != null
                    && lock.getWorld().isChunkLoaded(lock.getBlockX() >> 4, lock.getBlockZ() >> 4)) {
                TemplateCampaignRepair.ensureNetherLock(lock.getWorld(), lock);
                lock.getWorld().save();
            }
        }, 2L);
        world.save();
    }

    private String campaignAnchorStatus(World world) {
        List<Location> keys = List.of(
                new Location(world, -72, 64, -42), new Location(world, 72, 64, -42),
                new Location(world, -70, 64, 50), new Location(world, 70, 64, 50));
        long containers = keys.stream().filter(TemplateCampaignRepair::isContainer).count();
        Location lock = new Location(world, -6, 63, 116);
        boolean lockPresent = CampaignItemBridge.isBlock(lock, CampaignItemBridge.NETHER_DUNGEON_LOCK)
                || lock.getBlock().getType() == Material.RESPAWN_ANCHOR;
        return "cofres=" + containers + "/4, cerradura=" + (lockPresent ? "presente" : "ausente");
    }

    private boolean writeMarker(World world) {
        Path file = world.getWorldFolder().toPath().resolve(MARKER);
        String text = "version=" + VERSION + "\n"
                + "structureRevision=" + STRUCTURE_REVISION + "\n"
                + "spawn=0,63,-188\n"
                + "boss=0,66,176\n"
                + "portal=0,65,238\n"
                + "gate=0,65,118\n"
                + "lock=-6,63,116\n"
                + "keys=-72,64,-42;72,64,-42;-70,64,50;70,64,50\n";
        try {
            world.save();
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            TemplateMarkerLookup.rememberMarker(plugin, worldName(), MARKER, file);
            return true;
        } catch (Throwable error) {
            plugin.getLogger().warning("No se pudo escribir el marcador Nether: " + error.getMessage());
            return false;
        }
    }

    private boolean markerExists(World world) {
        return markerCurrentRevision();
    }
    private boolean markerExistsOnDisk() {
        return markerCurrentRevision();
    }
    private boolean markerCurrentRevision() {
        Path marker = TemplateMarkerLookup.findMarker(plugin, worldName(), MARKER).orElse(null);
        return marker != null
                && VERSION.equals(TemplateAuditUtil.readProperty(marker, "version"))
                && STRUCTURE_REVISION.equals(TemplateAuditUtil.readProperty(marker, "structureRevision"));
    }
    private Path inProgressMarkerPath() { return worldFolder().resolve(IN_PROGRESS_MARKER); }
    private Path worldFolder() { return TemplateMarkerLookup.activeFolder(plugin, worldName()); }

    private boolean deleteExisting(CommandSender sender) {
        World loaded = Bukkit.getWorld(worldName());
        Path folder = loaded != null
                ? loaded.getWorldFolder().toPath().toAbsolutePath().normalize()
                : TemplateMarkerLookup.activeFolder(plugin, worldName());
        if (loaded != null) {
            for (Player player : new ArrayList<>(loaded.getPlayers())) player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
            if (!Bukkit.unloadWorld(loaded, false)) return false;
        }
        if (!Files.exists(folder)) {
            TemplateMarkerLookup.forgetMarker(plugin, worldName(), MARKER);
            TemplateMarkerLookup.forgetMarker(plugin, worldName(), IN_PROGRESS_MARKER);
            TemplateMarkerLookup.forgetWorld(plugin, worldName());
            return true;
        }
        try (var walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException exception) { throw new RuntimeException(exception); }
            });
            TemplateMarkerLookup.forgetMarker(plugin, worldName(), MARKER);
            TemplateMarkerLookup.forgetMarker(plugin, worldName(), IN_PROGRESS_MARKER);
            TemplateMarkerLookup.forgetWorld(plugin, worldName());
            sender.sendMessage(ChatColor.YELLOW + "Plantilla Nether eliminada.");
            return true;
        } catch (Throwable error) {
            sender.sendMessage(ChatColor.RED + "No se pudo borrar la plantilla Nether: " + rootMessage(error));
            return false;
        }
    }

    private void cleanupBoss(World world, String tag) {
        for (Entity entity : new ArrayList<>(world.getEntities())) if (entity.getScoreboardTags().contains(tag)) entity.remove();
    }

    private boolean isBusy() {
        return stage == Stage.CREATING_WORLD || stage == Stage.PREGENERATING
                || stage == Stage.FINALIZING;
    }
    private int safetyLoadedChunkLimit() {
        return Math.max(64, plugin.getConfig().getInt(
                "template-worlds.safety.max-loaded-chunks-between-batches", 1024));
    }
    private long safetyDrainInterval() {
        return Math.max(20L, plugin.getConfig().getLong(
                "template-worlds.safety.drain-check-interval-ticks", 100L));
    }
    private int safetyStableChecks() {
        return Math.max(2, plugin.getConfig().getInt(
                "template-worlds.safety.stable-drain-checks", 3));
    }
    private void fail(String message) { stage = Stage.FAILED; detail = message; }
    private static String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
