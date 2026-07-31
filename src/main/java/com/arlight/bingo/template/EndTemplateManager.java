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
import java.util.Set;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/** Plantilla experimental del End: archipiélago de geodas y arena estelar. */
public final class EndTemplateManager implements Listener {

    public enum Stage { IDLE, CREATING_WORLD, PREGENERATING, FINALIZING, COMPLETE, FAILED, CANCELLED }

    private static final String MARKER = "arlight-end-template.properties";
    private static final String IN_PROGRESS_MARKER = "arlight-end-template-building.properties";
    private static final String VERSION = "1.35.0-end-exploration-reward-pass-1";
    private static final String STRUCTURE_REVISION = "1.35.0-end-functional-zones-loot-1";

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

    public EndTemplateManager(BingoPlugin plugin) {
        this.plugin = plugin;
        this.chunky = new ChunkyBridge(plugin);
        this.builtKey = new NamespacedKey(plugin, "template_end_chunk_v3");
    }

    public String worldName() {
        return plugin.getConfig().getString("template-worlds.end.name", "bingo_template_end");
    }

    public void resumeIfNeeded() {
        Bukkit.getScheduler().runTask(plugin, this::resumeInterruptedFinalization);
    }

    public synchronized boolean generate(CommandSender sender, boolean force) {
        if (!plugin.getConfig().getBoolean("template-worlds.end.enabled", true)) {
            sender.sendMessage(ChatColor.RED + "La plantilla del End está desactivada.");
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
        detail = "creando End base";
        progress = 0.02D;
        long seed = plugin.getConfig().getLong("template-worlds.end.seed", 880174920611L);
        World world = new WorldCreator(worldName())
                .environment(World.Environment.THE_END)
                .type(WorldType.NORMAL)
                .seed(seed)
                .generateStructures(false)
                .createWorld();
        if (world == null) {
            fail("Bukkit no pudo crear el End plantilla.");
            return false;
        }

        int radius = Math.max(640, plugin.getConfig().getInt("template-worlds.end.radius", 1024));
        world.getWorldBorder().setCenter(0.0D, 120.0D);
        world.getWorldBorder().setSize(radius * 2.0D);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, true);
        world.setAutoSave(true);

        stage = Stage.PREGENERATING;
        detail = "Chunky pregenerando el End";
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
            if (!writeInProgressMarker()) {
                fail("No se pudo guardar el marcador reanudable del End.");
                sender.sendMessage(ChatColor.RED + detail);
                return;
            }
            startEagerFinalization(world, sender);
        }));
        sender.sendMessage(ChatColor.DARK_PURPLE + "Chunky empezó a pregenerar el End plantilla.");
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
        sender.sendMessage(ChatColor.YELLOW + "Generación cancelada. El mundo se conserva; usa /bingo template end resume para continuar sin Chunky.");
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
        if (world == null && markerCurrentRevision()) {
            world = loadExistingWorld(plugin.getConfig().getLong(
                    "template-worlds.end.seed", 880174920611L));
        }
        if (world == null) return false;
        if (!markerExists(world)) {
            player.sendMessage(ChatColor.YELLOW + "El End todavía no está terminado: " + status());
            return false;
        }
        player.setAllowFlight(true);
        player.setFlying(true);
        player.sendMessage(ChatColor.LIGHT_PURPLE + "Plantilla End completa: el archipiélago ya está construido.");
        return player.teleport(new Location(world, 0.5D, 150.0D, -230.5D, 0.0F, 0.0F));
    }

    public boolean testBoss(Player player) {
        World world = Bukkit.getWorld(worldName());
        if (world == null && markerCurrentRevision()) {
            world = loadExistingWorld(plugin.getConfig().getLong(
                    "template-worlds.end.seed", 880174920611L));
        }
        if (world == null || !markerExists(world)) return false;
        Location arena = new Location(world, 0.5D, 99.0D, 430.5D);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.teleport(arena.clone().add(0, 4.0D, -34));
        player.sendMessage(ChatColor.LIGHT_PURPLE + "Arena completa. Generando el jefe de prueba...");
        summonBossWhenReady(player, world, arena, "arlightbosses:void_guardian", "arlightbingo_template_end_boss", 40);
        return true;
    }

    public String status() {
        int pending = queue == null ? 0 : queue.pending();
        return stage.name().toLowerCase(Locale.ROOT) + " · " + Math.round(progress * 100.0D)
                + "% · " + detail + (pending > 0 ? " · cola " + pending : "");
    }

    public void audit(CommandSender sender) {
        long seed = plugin.getConfig().getLong("template-worlds.end.seed", 880174920611L);
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "=== Auditoría plantilla End ===");
        for (String line : TemplateAuditUtil.audit(plugin, worldName(), MARKER, IN_PROGRESS_MARKER,
                VERSION, World.Environment.THE_END, seed)) {
            sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + line);
        }
        Path marker = TemplateMarkerLookup.findMarker(plugin, worldName(), MARKER).orElse(null);
        String revision = TemplateAuditUtil.readProperty(marker, "structureRevision");
        sender.sendMessage(ChatColor.GRAY + "- revisión-estructural=" + ChatColor.WHITE
                + (revision == null ? "ausente" : revision) + " (esperada " + STRUCTURE_REVISION + ")");
        sender.sendMessage(ChatColor.GRAY + "- anclajes-faltantes=" + ChatColor.WHITE
                + TemplateAuditUtil.missingProperties(marker, "spawn", "boss", "keys", "gate", "altar", "dragon", "exit"));
        World loaded = Bukkit.getWorld(worldName());
        if (loaded != null) sender.sendMessage(ChatColor.GRAY + "- anclajes-físicos=" + ChatColor.WHITE
                + campaignAnchorStatus(loaded));
        else sender.sendMessage(ChatColor.GRAY + "- anclajes-físicos=" + ChatColor.WHITE
                + "no comprobados; usa resume para cargar y reparar sin Chunky");
        sender.sendMessage(ChatColor.GRAY + "- estado-runtime=" + ChatColor.WHITE + status());
        sender.sendMessage(ChatColor.GRAY + "- soportes=" + ChatColor.WHITE
                + "adaptativos hasta " + supportMaximumDepth() + " bloques");
    }

    public Stage stage() {
        return stage;
    }

    public boolean isCompleteOnDisk() {
        return markerCurrentRevision();
    }

    public boolean isReadyForMatches() {
        return stage == Stage.COMPLETE || isCompleteOnDisk();
    }

    public boolean ensureCampaignAnchorsReady() {
        if (!markerCurrentRevision()) return false;
        long seed = plugin.getConfig().getLong("template-worlds.end.seed", 880174920611L);
        World world = loadExistingWorld(seed);
        if (world == null) return false;
        repairCampaignAnchors(world);
        return campaignAnchorStatus(world).contains("cofres=4/4")
                && campaignAnchorStatus(world).contains("altar=presente");
    }

    public String markerDiagnostic() {
        return (isCompleteOnDisk() ? "READY" : "MISSING")
                + " [" + TemplateMarkerLookup.checkedFolders(plugin, worldName()) + "]";
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
        if (!event.getChunk().getPersistentDataContainer().has(builtKey, PersistentDataType.BYTE)) queue.offer(event.getChunk());
    }

    private void resumeInterruptedFinalization() {
        if (isBusy()) return;
        long seed = plugin.getConfig().getLong("template-worlds.end.seed", 880174920611L);
        if (markerCurrentRevision()) {
            World readyWorld = loadExistingWorld(seed);
            if (readyWorld != null) {
                repairCampaignAnchors(readyWorld);
                startLazyBuilder(readyWorld);
            }
            stage = Stage.COMPLETE;
            progress = 1.0D;
            detail = "plantilla lista; archipiélago completamente construido";
            return;
        }
        Path folder = worldFolder();
        boolean interrupted = TemplateMarkerLookup.findMarker(plugin, worldName(), IN_PROGRESS_MARKER).isPresent();
        boolean olderComplete = TemplateMarkerLookup.findMarker(plugin, worldName(), MARKER).isPresent();
        if (!interrupted && !olderComplete) return;

        World world = loadExistingWorld(seed);
        if (world == null) {
            fail("No se pudo reanudar la finalización del End después del reinicio.");
            return;
        }
        if (!writeInProgressMarker(world, olderComplete ? "upgrading" : "finalizing")) {
            fail("No se pudo guardar el marcador reanudable del End.");
            return;
        }
        plugin.getLogger().warning(olderComplete
                ? "Se detectó una plantilla End de una revisión anterior. Actualizando soportes y arquitectura sin Chunky."
                : "Se detectó una plantilla End incompleta. Reanudando la construcción segura sin Chunky.");
        startEagerFinalization(world, Bukkit.getConsoleSender());
    }

    public synchronized boolean resume(CommandSender sender) {
        if (isBusy()) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una operación activa: " + status());
            return false;
        }
        if (markerCurrentRevision()) {
            long seed = plugin.getConfig().getLong("template-worlds.end.seed", 880174920611L);
            World readyWorld = loadExistingWorld(seed);
            if (readyWorld != null) {
                repairCampaignAnchors(readyWorld);
                startLazyBuilder(readyWorld);
            }
            stage = Stage.COMPLETE;
            progress = 1.0D;
            detail = "plantilla lista; archipiélago completamente construido";
            sender.sendMessage(ChatColor.GREEN + "La plantilla End ya usa la revisión estructural actual.");
            return true;
        }
        if (!Files.isDirectory(worldFolder())) {
            sender.sendMessage(ChatColor.RED + "No existe la carpeta del End para reanudar.");
            return false;
        }
        long seed = plugin.getConfig().getLong("template-worlds.end.seed", 880174920611L);
        World world = loadExistingWorld(seed);
        if (world == null) {
            fail("No se pudo cargar el End existente.");
            sender.sendMessage(ChatColor.RED + detail);
            return false;
        }
        String resumeState = TemplateMarkerLookup.findMarker(plugin, worldName(), MARKER).isPresent() ? "upgrading" : "finalizing";
        if (!writeInProgressMarker(world, resumeState)) {
            fail("No se pudo guardar el marcador reanudable del End.");
            sender.sendMessage(ChatColor.RED + detail);
            return false;
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Reanudando/actualizando el End existente. Chunky NO se repetirá y el mundo NO se borrará.");
        startEagerFinalization(world, sender);
        return true;
    }

    private World loadExistingWorld(long seed) {
        World world = Bukkit.getWorld(worldName());
        if (world != null) return world;
        if (!Files.exists(worldFolder())) return null;
        return new WorldCreator(worldName())
                .environment(World.Environment.THE_END)
                .type(WorldType.NORMAL)
                .seed(seed)
                .generateStructures(false)
                .createWorld();
    }

    private boolean writeInProgressMarker() {
        World world = Bukkit.getWorld(worldName());
        return world != null && writeInProgressMarker(world, "finalizing");
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
            plugin.getLogger().warning("No se pudo guardar el progreso del End: " + error.getMessage());
            return false;
        }
    }

    private void deleteInProgressMarker() {
        try {
            Files.deleteIfExists(inProgressMarkerPath());
            TemplateMarkerLookup.forgetMarker(plugin, worldName(), IN_PROGRESS_MARKER);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo borrar el marcador temporal End: " + error.getMessage());
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
        detail = "construyendo archipiélago completo 0/" + finalizationTotal;
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Chunky terminó. Ahora Bingo construirá todo el End antes de marcarlo como completo.");

        long interval = Math.max(5L, plugin.getConfig().getLong(
                "template-worlds.end.finalization-load-interval-ticks", 10L));
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
                try { loaded.addPluginChunkTicket(plugin); } catch (Throwable ignored) { }
                queue.offerPriority(loaded);
                return;
            }

            finalizationLoading = true;
            requestFinalizationChunk(world, cx, cz, key);
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
                        fail("No se pudo estabilizar el End: " + rootMessage(error));
                        return;
                    }
                    completeEagerFinalization(world);
                }));
    }

    private void requestFinalizationChunk(World world, int cx, int cz, long key) {
        try {
            Method method = world.getClass().getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
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
            // Arclight puede no exponer la extensión async de Paper. Se usa un fallback
            // sincronizado de un solo chunk por vez, siempre después de la pregeneración.
        }
        loadFinalizationChunkSync(world, cx, cz, key);
    }

    private void loadFinalizationChunkSync(World world, int cx, int cz, long key) {
        try {
            Chunk chunk = world.getChunkAt(cx, cz);
            acceptFinalizationChunk(chunk, key);
        } catch (Throwable error) {
            finalizationLoading = false;
            stopFinalization();
            stopLazyBuilder();
            deleteInProgressMarker();
            fail("No se pudo cargar el chunk End " + cx + "," + cz + ": " + rootMessage(error));
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
        try { chunk.addPluginChunkTicket(plugin); } catch (Throwable ignored) { }
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
        detail = "plantilla lista; archipiélago completamente construido";

        CommandSender sender = finalizationSender;
        finalizationSender = null;
        if (sender != null) sender.sendMessage(ChatColor.LIGHT_PURPLE
                + "Plantilla End terminada por completo. Ya puedes usar /bingo template end tp.");

        // Conserva una cola ligera únicamente como reparación, por si un administrador
        // borra manualmente un chunk después de finalizar la plantilla.
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
        detail = "construyendo archipiélago completo " + finalizationDone + "/" + finalizationTotal;
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
        for (int cx = -40; cx <= 40; cx++) {
            for (int cz = -22; cz <= 35; cz++) {
                if (chunkMayContainTemplate(cx, cz)) targets.add(chunkKey(cx, cz));
            }
        }
        targets.sort(Comparator.comparingDouble(key -> {
            int cx = (int) (key >> 32);
            int cz = key.intValue();
            double dx = cx * 16.0D;
            double dz = cz * 16.0D + 230.0D;
            return dx * dx + dz * dz;
        }));
        return targets;
    }

    private boolean chunkMayContainTemplate(int cx, int cz) {
        int minX = cx << 4, maxX = minX + 15;
        int minZ = cz << 4, maxZ = minZ + 15;

        if (intersects(minX, maxX, minZ, maxZ, -210, 210, -210, 210)) return true;
        if (intersects(minX, maxX, minZ, maxZ, -320, -140, -80, 80)) return true;
        if (intersects(minX, maxX, minZ, maxZ, 140, 320, -80, 80)) return true;
        if (intersects(minX, maxX, minZ, maxZ, -95, 95, -305, -150)) return true;
        if (intersects(minX, maxX, minZ, maxZ, -95, 95, 155, 315)) return true;
        if (intersects(minX, maxX, minZ, maxZ, -215, -85, 110, 220)) return true;
        if (intersects(minX, maxX, minZ, maxZ, 85, 220, 110, 220)) return true;
        if (intersects(minX, maxX, minZ, maxZ, -120, 120, 305, 550)) return true;

        // Corredores de los puentes. Las cajas son deliberadamente un poco más
        // anchas para incluir barandillas y columnas de soporte.
        if (intersects(minX, maxX, minZ, maxZ, -245, 245, -10, 10)) return true;
        if (intersects(minX, maxX, minZ, maxZ, -10, 10, -245, 445)) return true;
        if (intersects(minX, maxX, minZ, maxZ, -165, -65, 55, 180)) return true;
        return intersects(minX, maxX, minZ, maxZ, 60, 170, 55, 180);
    }

    private boolean intersects(int minX, int maxX, int minZ, int maxZ,
                               int boxMinX, int boxMaxX, int boxMinZ, int boxMaxZ) {
        return maxX >= boxMinX && minX <= boxMaxX && maxZ >= boxMinZ && minZ <= boxMaxZ;
    }

    private long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private void startLazyBuilder(World world) {
        ensureQueue(world);
        if (scanTask != null) scanTask.cancel();
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (queue == null || queue.pending() > 14) return;
            for (Chunk chunk : world.getLoadedChunks()) {
                if (!chunk.getPersistentDataContainer().has(builtKey, PersistentDataType.BYTE)) queue.offer(chunk);
                if (queue.pending() > 14) break;
            }
        }, 1L, 20L);
    }

    private void ensureQueue(World world) {
        if (queue != null) return;
        int blocks = Math.min(400, Math.max(100,
                plugin.getConfig().getInt("template-worlds.end.blocks-per-tick", 350)));
        long millis = Math.min(5L, Math.max(2L,
                plugin.getConfig().getLong("template-worlds.end.max-build-millis-per-tick", 4L)));
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
        int minX = chunk.getX() << 4;
        int minZ = chunk.getZ() << 4;
        if (!insideReplacementZone(minX + 8, minZ + 8)) return ops;

        // Solo borra la isla vanilla central. Fuera de ese radio el End ya es vacío,
        // así que limpiar cada columna del archipiélago desperdiciaba millones de operaciones.
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                if ((long) x * x + (long) z * z <= 205L * 205L) {
                    for (int y = 0; y <= 154; y++) ops.add(op(x, y, z, Material.AIR));
                }
                IslandColumn column = islandColumn(x, z);
                if (column != null) {
                    for (int y = column.bottom(); y <= column.top(); y++) {
                        Material material;
                        if (y >= column.top() - 2) material = surfaceMaterial(x, y, z, column.kind());
                        else if (y <= column.bottom() + 2 && ((x + z + y) & 5) == 0) material = Material.AMETHYST_BLOCK;
                        else material = Material.END_STONE;
                        ops.add(op(x, y, z, material));
                    }
                    planAdaptiveArchitectureSupport(ops, x, z, column);
                    planIslandArchitecture(ops, x, z, column.top(), column.kind());
                }
                planBridgeColumn(ops, x, z);
            }
        }
        return ops;
    }

    private IslandColumn islandColumn(int x, int z) {
        IslandColumn best = null;
        IslandSpec[] islands = {
                new IslandSpec(0,0,142,112,94,0),
                new IslandSpec(-230,0,76,62,93,1),
                new IslandSpec(230,0,76,62,93,2),
                new IslandSpec(0,-230,80,64,94,3),
                new IslandSpec(0,235,82,66,93,4),
                new IslandSpec(-150,165,50,41,91,5),
                new IslandSpec(155,165,52,43,92,6)
        };
        for (IslandSpec island : islands) {
            double dx = (x - island.x()) / (double) island.rx();
            double dz = (z - island.z()) / (double) island.rz();
            double n = Math.sqrt(dx * dx + dz * dz);
            double wave = Math.sin((x + island.kind() * 23) * 0.045D) * 0.025D
                    + Math.cos((z - island.kind() * 17) * 0.052D) * 0.022D;
            if (n + wave > 1.0D) continue;
            int top;
            if (island.kind() == 0 && n < 0.72D) {
                top = n < 0.28D ? 98 : n < 0.52D ? 96 : 94;
            } else if (island.kind() > 0 && n < 0.52D) {
                top = island.baseY() + 2;
            } else {
                top = island.baseY() + (int) Math.round((1.0D - n) * 5.0D
                        + Math.sin(x * 0.06D) * 1.2D + Math.cos(z * 0.055D) * 1.2D);
            }
            int thickness = 18 + (int) Math.round(Math.pow(Math.max(0.0D, 1.0D - n), 1.35D) * 34.0D);
            IslandColumn candidate = new IslandColumn(top - thickness, top, island.kind());
            if (best == null || candidate.top() > best.top()) best = candidate;
        }

        // Isla final estelar con plataforma central lisa y puntas naturales.
        double dx = x;
        double dz = z - 430.0D;
        double distance = Math.hypot(dx, dz);
        double angle = Math.atan2(dz, dx);
        double limit = 84.0D + 24.0D * Math.cos(angle * 5.0D);
        if (distance <= limit) {
            double n = Math.min(1.0D, distance / Math.max(56.0D, limit));
            int top = distance < 58.0D ? 96 : 92 + (int) Math.round((1.0D - n) * 4.0D);
            IslandColumn star = new IslandColumn(top - 20 - (int) Math.round((1.0D - n) * 30.0D), top, 10);
            if (best == null || star.top() > best.top()) best = star;
        }
        return best;
    }

    private void planIslandArchitecture(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z, int top, int kind) {
        if (kind == 0) planMainCitadel(ops, x, z, top);
        else if (kind >= 1 && kind <= 6) planSatelliteShrine(ops, x, z, top, kind);
        else if (kind == 10) planStarArena(ops, x, z, top);
        planEndExplorationInteriorColumn(ops, x, z, kind);

        // Cristales y geodas visibles desde lejos.
        int hash = x * 7349 ^ z * 9151 ^ kind * 11939;
        if ((hash & 511) == 21) {
            int height = 7 + Math.floorMod(hash >> 9, 14);
            for (int dy = 1; dy <= height; dy++) {
                int bx = x + dy / 5;
                int bz = z - dy / 6;
                ops.add(op(bx, top + dy, bz, dy % 4 == 0 ? Material.END_ROD : Material.AMETHYST_BLOCK));
            }
        }
    }

    private void planMainCitadel(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z, int top) {
        final int ground = 99;
        double d = Math.hypot(x, z);

        // Calles principales y diagonales sobre una meseta estable.
        if (d <= 96 && (Math.abs(x) <= 4 || Math.abs(z) <= 4 || Math.abs(Math.abs(x) - Math.abs(z)) <= 3)) {
            ops.add(op(x, ground, z, ((x + z) & 7) == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_BLOCK));
        }

        // Muralla baja e irregular con cuatro puertas; no vuelve a formar un cilindro gigantesco.
        double metric = octagonalMetric(x, z, 96.0D, 80.0D);
        if (metric >= 0.94D && metric <= 1.02D) {
            double angle = Math.toDegrees(Math.atan2(z / 80.0D, x / 96.0D));
            if (angle < 0) angle += 360.0D;
            boolean gate = nearEndAngle(angle, 0, 11) || nearEndAngle(angle, 90, 11)
                    || nearEndAngle(angle, 180, 11) || nearEndAngle(angle, 270, 11);
            boolean ruin = Math.floorMod(x * 7 + z * 11, 31) < 5;
            if (!gate && !ruin) {
                int height = 8 + Math.floorMod(x + z, 6);
                for (int y = ground + 1; y <= ground + height; y++) ops.add(op(x, y, z,
                        (x + z + y) % 11 == 0 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS));
            }
        }

        int[][] towers = {{-72,-45,8,20},{72,-45,9,24},{-72,45,7,18},{72,45,8,22},{0,-70,10,27},{0,68,8,21}};
        for (int[] t : towers) planEndTowerColumn(ops, x, z, t[0], t[1], t[2], ground, t[3]);

        // Cuatro barrios con edificios bajos en L y patios abiertos.
        planEndDistrictColumn(ops, x, z, -44, -28, 0, ground);
        planEndDistrictColumn(ops, x, z, 44, -28, 1, ground);
        planEndDistrictColumn(ops, x, z, -42, 34, 2, ground);
        planEndDistrictColumn(ops, x, z, 43, 34, 3, ground);

        // Santuario/geoda central: anillo abierto, no torre maciza.
        if (d >= 21 && d <= 29) {
            for (int y = ground + 1; y <= ground + 12 + Math.floorMod(x - z, 4); y++) ops.add(op(x, y, z,
                    y % 5 == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_PILLAR));
        }
        if (d <= 18) {
            ops.add(op(x, ground, z, d <= 7 ? Material.OBSIDIAN : Material.PURPUR_BLOCK));
            if (d >= 10 && d <= 14 && ((x + z) & 3) == 0) ops.add(op(x, ground + 1, z, Material.SMALL_AMETHYST_BUD));
        }
    }

    private void planSatelliteShrine(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z, int top, int kind) {
        int[][] centers = {{0,0},{-230,0},{230,0},{0,-230},{0,235},{-150,165},{155,165}};
        int cx = centers[kind][0], cz = centers[kind][1];
        int ground = switch (kind) {
            case 1, 2 -> 96;
            case 3 -> 97;
            case 4 -> 96;
            default -> 94;
        };
        double d = Math.hypot(x - cx, z - cz);
        if (Math.abs(x - cx) <= 3 || Math.abs(z - cz) <= 3) ops.add(op(x, ground, z, Material.PURPUR_BLOCK));
        if (d >= 22 && d <= 28) {
            int height = 8 + kind;
            for (int y = ground + 1; y <= ground + height; y++) ops.add(op(x, y, z,
                    y % 5 == 0 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS));
        }
        int[][] offsets = {{-18,-12},{17,-10},{-15,15},{16,14}};
        for (int i = 0; i < offsets.length; i++) {
            int ox = cx + offsets[i][0], oz = cz + offsets[i][1];
            int dx = Math.abs(x - ox), dz = Math.abs(z - oz);
            boolean shape = (dx <= 7 && dz <= 5) || (dx <= 3 && dz <= 9);
            boolean inner = (dx <= 4 && dz <= 2) || (dx <= 1 && dz <= 6);
            if (shape && !inner) {
                for (int y = ground + 1; y <= ground + 6 + (i + kind) % 4; y++) ops.add(op(x, y, z,
                        (x + z + y) % 8 == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_BLOCK));
            }
            if (shape) {
                int roof = ground + 8 + (i + kind) % 4;
                if (!inner || dx + dz >= 4) ops.add(op(x, roof, z,
                        ((x + z) & 3) == 0 ? Material.PURPUR_SLAB : Material.END_STONE_BRICK_SLAB));
            }
            if (dx <= 1 && z == oz - 9) for (int y = ground + 1; y <= ground + 4; y++) {
                ops.add(op(x, y, z, Material.AIR));
            }
        }
        if (d <= 7) {
            ops.add(op(x, ground, z, Material.AMETHYST_BLOCK));
            if (((x + z) & 3) == 0) ops.add(op(x, ground + 1, z, Material.END_ROD));
        }
    }


    /**
     * Pase de exploración: cada barrio e isla recibe una función reconocible,
     * decoración propia y puntos de interés que justifican abandonar la ruta principal.
     */
    private void planEndExplorationInteriorColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z, int kind) {
        if (kind == 0) {
            planEndZone(ops, x, z, -44, -28, 13, 8, 99, 0); // archivo
            planEndZone(ops, x, z, 44, -28, 13, 8, 99, 1);  // prisión shulker
            planEndZone(ops, x, z, -42, 34, 13, 8, 99, 2);  // jardín cristalino
            planEndZone(ops, x, z, 43, 34, 13, 8, 99, 3);   // tesorería
            planEndZone(ops, x, z, 0, 0, 18, 18, 99, 4);    // santuario central
            return;
        }
        if (kind < 1 || kind > 6) return;
        int[][] centers = {{0,0},{-230,0},{230,0},{0,-230},{0,235},{-150,165},{155,165}};
        int cx = centers[kind][0], cz = centers[kind][1];
        int ground = switch (kind) {
            case 1, 2, 4 -> 96;
            case 3 -> 97;
            default -> 94;
        };
        // Cada isla satélite tiene una identidad distinta.
        planEndZone(ops, x, z, cx, cz, 12, 10, ground, 4 + kind);
    }

    private void planEndZone(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z,
                             int cx, int cz, int halfX, int halfZ, int ground, int style) {
        int dx = x - cx, dz = z - cz;
        if (Math.abs(dx) > halfX || Math.abs(dz) > halfZ) return;
        int hash = Math.floorMod(dx * 19 + dz * 29 + style * 41, 31);
        switch (style) {
            case 0, 5 -> { // archivo / memoria
                if (Math.abs(dx) == halfX - 2 && Math.floorMod(dz, 3) == 0) {
                    ops.add(op(x, ground + 1, z, Material.CHISELED_BOOKSHELF));
                    ops.add(op(x, ground + 2, z, Material.BOOKSHELF));
                }
                if (dx == 0 && dz == 0) ops.add(op(x, ground + 1, z, Material.LECTERN));
                if (hash % 11 == 0) ops.add(op(x, ground + 3, z, Material.END_ROD));
            }
            case 1, 8 -> { // prisión / bóveda shulker
                if (Math.floorMod(dx + halfX, 5) == 0 && Math.abs(dz) >= 2) {
                    for (int y = ground + 1; y <= ground + 5; y++) ops.add(op(x, y, z, Material.IRON_BARS));
                }
                if (Math.abs(dx) == halfX - 2 && Math.floorMod(dz, 4) == 0) ops.add(op(x, ground + 1, z, Material.PURPLE_SHULKER_BOX));
            }
            case 2, 9 -> { // jardín de coro
                ops.add(op(x, ground, z, hash % 5 == 0 ? Material.AMETHYST_BLOCK : Material.END_STONE));
                if (hash % 13 == 0) {
                    ops.add(op(x, ground + 1, z, Material.CHORUS_PLANT));
                    ops.add(op(x, ground + 2, z, Material.CHORUS_FLOWER));
                } else if (hash % 17 == 0) ops.add(op(x, ground + 1, z, Material.END_ROD));
            }
            case 3, 10 -> { // tesorería
                if (Math.abs(dx) <= 7 && Math.abs(dz) <= 5 && ((dx + dz) & 1) == 0) {
                    ops.add(op(x, ground + 1, z, hash % 4 == 0 ? Material.GOLD_BLOCK : Material.AMETHYST_BLOCK));
                }
                if ((Math.abs(dx) == 9 && dz == 0) || (Math.abs(dz) == 7 && dx == 0)) ops.add(op(x, ground + 1, z, Material.CRYING_OBSIDIAN));
            }
            case 4 -> { // santuario central
                double d = Math.hypot(dx, dz);
                if (d <= 7) ops.add(op(x, ground + 1, z, d <= 2 ? Material.RESPAWN_ANCHOR : Material.CRYING_OBSIDIAN));
                if (d >= 11 && d <= 14 && hash % 4 == 0) ops.add(op(x, ground + 1, z, Material.END_ROD));
            }
            case 6 -> { // observatorio occidental
                if (Math.abs(dx) <= 6 && Math.abs(dz) <= 6 && (Math.abs(dx) == 6 || Math.abs(dz) == 6)) {
                    ops.add(op(x, ground + 4, z, Material.TINTED_GLASS));
                }
                if (dx == 0 && dz == 0) ops.add(op(x, ground + 1, z, Material.BEACON));
            }
            case 7 -> { // arsenal oriental
                if (Math.abs(dx) == halfX - 2 && Math.floorMod(dz, 4) == 0) ops.add(op(x, ground + 1, z, Material.SMITHING_TABLE));
                if (Math.abs(dz) == halfZ - 2 && Math.floorMod(dx, 5) == 0) ops.add(op(x, ground + 1, z, Material.PURPUR_PILLAR));
                if (dx == 0 && dz == 0) ops.add(op(x, ground + 1, z, Material.ANVIL));
            }
            default -> { }
        }
    }

    private void planStarArena(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z, int top) {
        final int ground = 97;
        double dx = x, dz = z - 430.0D;
        double d = Math.hypot(dx, dz);
        if (d <= 60) {
            ops.add(op(x, ground, z, d < 17 ? (((x + z) & 3) == 0 ? Material.OBSIDIAN : Material.POLISHED_BLACKSTONE) : Material.PURPUR_BLOCK));
            if (d <= 24) for (int y = ground + 1; y <= ground + 18; y++) ops.add(op(x, y, z, Material.AIR));
        }
        double angleDeg = Math.toDegrees(Math.atan2(dz, dx));
        if (angleDeg < 0) angleDeg += 360.0D;
        boolean gate = nearEndAngle(angleDeg, 270.0D, 14.0D);
        if (d >= 55 && d <= 62 && !gate) {
            for (int y = ground + 1; y <= ground + 10; y++) ops.add(op(x, y, z,
                    (x + z + y) % 7 == 0 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS));
        }
        for (int i = 0; i < 5; i++) {
            double angle = -Math.PI / 2.0D + i * Math.PI * 2.0D / 5.0D;
            int px = (int) Math.round(Math.cos(angle) * 42.0D);
            int pz = 430 + (int) Math.round(Math.sin(angle) * 42.0D);
            double pd = Math.hypot(x - px, z - pz);
            if (pd <= 4) {
                int height = 20 + i * 2;
                for (int y = ground + 1; y <= ground + height; y++) ops.add(op(x, y, z,
                        y % 5 == 0 ? Material.END_ROD : Material.AMETHYST_BLOCK));
            }
        }
    }

    private void planBridgeColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z) {
        int[][] segments = {
                {0,0,-230,0}, {0,0,230,0}, {0,0,0,-230}, {0,0,0,235},
                {-74,70,-150,165}, {74,70,155,165}, {0,235,0,430}
        };
        for (int[] s : segments) {
            SegmentPoint point = distanceToSegment(x, z, s[0], s[1], s[2], s[3]);
            if (point.distance() > 4.8D) continue;
            int gapBand = (int) Math.floor(point.t() * 24.0D);
            if ((gapBand == 8 || gapBand == 17) && point.distance() < 3.5D) continue;
            int y = 97;
            ops.add(op(x, y, z, ((x + z) & 9) == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_BLOCK));
            if (point.distance() >= 3.8D && gapBand % 4 == 0) ops.add(op(x, y + 1, z, Material.END_STONE_BRICK_WALL));
            if (point.distance() <= 1.0D && gapBand % 6 == 0) {
                planAdaptiveBridgeSupport(ops, x, z, y, gapBand);
            }
        }
    }

    private int supportMaximumDepth() {
        return Math.max(8, Math.min(48, plugin.getConfig().getInt(
                "template-worlds.end.adaptive-supports.maximum-depth", 30)));
    }

    private void planAdaptiveArchitectureSupport(List<TemplateChunkBuildQueue.BlockOp> ops,
                                                 int x, int z, IslandColumn column) {
        int ground = architectureGround(column.kind());
        if (ground <= column.top() || !isArchitectureFootprint(x, z, column.kind())) return;
        int from = Math.max(column.top() + 1, ground - supportMaximumDepth());
        for (int y = from; y <= ground; y++) {
            Material material = ((x + z + y) & 7) == 0
                    ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS;
            ops.add(op(x, y, z, material));
        }
        int gap = ground - column.top();
        if (gap >= 3 && Math.floorMod(x * 13 + z * 7, 11) <= 1) {
            for (int d = 1; d <= Math.min(gap, 8); d++) {
                ops.add(op(x + Integer.signum(x), ground - d, z + Integer.signum(z),
                        d % 3 == 0 ? Material.PURPUR_PILLAR : Material.END_STONE_BRICKS));
            }
        }
    }

    private int architectureGround(int kind) {
        return switch (kind) {
            case 0 -> 99;
            case 1, 2, 4 -> 96;
            case 3 -> 97;
            case 5, 6 -> 94;
            case 10 -> 97;
            default -> Integer.MIN_VALUE;
        };
    }

    private boolean isArchitectureFootprint(int x, int z, int kind) {
        if (kind == 0) {
            double d = Math.hypot(x, z);
            if (d <= 31 || Math.abs(x) <= 5 || Math.abs(z) <= 5
                    || Math.abs(Math.abs(x) - Math.abs(z)) <= 4) return true;
            double metric = octagonalMetric(x, z, 96.0D, 80.0D);
            if (metric >= 0.90D && metric <= 1.06D) return true;
            int[][] towers = {{-72,-45,10},{72,-45,11},{-72,45,9},{72,45,10},{0,-70,12},{0,68,10}};
            for (int[] t : towers) if (Math.hypot(x - t[0], z - t[1]) <= t[2]) return true;
            int[][] districts = {{-44,-28},{44,-28},{-42,34},{43,34}};
            for (int[] c : districts) {
                int dx = Math.abs(x - c[0]), dz = Math.abs(z - c[1]);
                if ((dx <= 17 && dz <= 12) || (dx <= 9 && dz <= 22)) return true;
            }
            return false;
        }
        if (kind >= 1 && kind <= 6) {
            int[][] centers = {{0,0},{-230,0},{230,0},{0,-230},{0,235},{-150,165},{155,165}};
            int cx = centers[kind][0], cz = centers[kind][1];
            double d = Math.hypot(x - cx, z - cz);
            if (d <= 30 || Math.abs(x - cx) <= 4 || Math.abs(z - cz) <= 4) return true;
            int[][] offsets = {{-18,-12},{17,-10},{-15,15},{16,14}};
            for (int[] o : offsets) {
                int dx = Math.abs(x - (cx + o[0])), dz = Math.abs(z - (cz + o[1]));
                if ((dx <= 8 && dz <= 6) || (dx <= 4 && dz <= 10)) return true;
            }
            return false;
        }
        return kind == 10 && Math.hypot(x, z - 430.0D) <= 65.0D;
    }

    private void planAdaptiveBridgeSupport(List<TemplateChunkBuildQueue.BlockOp> ops,
                                           int x, int z, int bridgeY, int band) {
        IslandColumn below = islandColumn(x, z);
        if (below != null && below.top() < bridgeY) {
            int from = Math.max(below.top() + 1, bridgeY - supportMaximumDepth());
            for (int y = from; y < bridgeY; y++) {
                ops.add(op(x, y, z, y % 4 == 0 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS));
            }
            return;
        }
        int depth = Math.min(supportMaximumDepth(), 16 + Math.floorMod(band, 9));
        for (int d = 1; d <= depth; d++) {
            int radius = d < depth / 3 ? 2 : (d < depth * 2 / 3 ? 1 : 0);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > radius + 1) continue;
                    Material material = d % 5 == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_PILLAR;
                    ops.add(op(x + dx, bridgeY - d, z + dz, material));
                }
            }
        }
        ops.add(op(x, bridgeY - depth - 1, z, Material.END_ROD));
    }

    private boolean nearEndAngle(double angle, double target, double tolerance) {
        double diff = Math.abs(angle - target) % 360.0D;
        return Math.min(diff, 360.0D - diff) <= tolerance;
    }

    private void planEndTowerColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z,
                                    int cx, int cz, int radius, int ground, int height) {
        double d = Math.hypot(x - cx, z - cz);
        if (d >= radius - 2.0D && d <= radius) {
            for (int y = ground + 1; y <= ground + height; y++) ops.add(op(x, y, z,
                    y % 6 == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_PILLAR));
        }
        if (d < radius - 2.2D) ops.add(op(x, ground, z, Material.PURPUR_BLOCK));

        // Miradores intermedios y corona de geoda: la verticalidad se lee desde los puentes
        // y el interior queda dividido en espacios explorables.
        if (d < radius - 2.4D && height > 16) {
            ops.add(op(x, ground + height / 2, z, Material.PURPUR_SLAB));
        }
        if (d >= radius - 3.8D && d <= radius - 1.2D) {
            ops.add(op(x, ground + height + 1, z, Material.END_STONE_BRICK_WALL));
        }
        if (d <= Math.max(1.0D, radius - 4.0D)
                && Math.floorMod(x - cx, 3) == 0 && Math.floorMod(z - cz, 3) == 0) {
            ops.add(op(x, ground + height + 2, z, Material.AMETHYST_BLOCK));
        }
        if (Math.abs(x - cx) <= 1 && z == cz - radius) {
            for (int y = ground + 1; y <= ground + 4; y++) ops.add(op(x, y, z, Material.AIR));
        }
    }

    private void planEndDistrictColumn(List<TemplateChunkBuildQueue.BlockOp> ops, int x, int z,
                                       int cx, int cz, int style, int ground) {
        int dx = Math.abs(x - cx), dz = Math.abs(z - cz);
        boolean shape = (dx <= 15 && dz <= 10) || (dx <= 7 && dz <= 20);
        boolean inner = (dx <= 11 && dz <= 6) || (dx <= 3 && dz <= 16);
        if (shape) {
            ops.add(op(x, ground, z, Material.PURPUR_BLOCK));
            if (!inner) {
                int height = 7 + style * 2 + Math.floorMod(x + z, 3);
                for (int y = ground + 1; y <= ground + height; y++) ops.add(op(x, y, z,
                        (x + z + y) % 10 == 0 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS));
            }

            // Cubiertas escalonadas con lucernarios y remates asimétricos.
            int roof = ground + 9 + style * 2;
            if (!inner || dx >= 4 || dz >= 4) ops.add(op(x, roof, z,
                    ((x + z) & 3) == 0 ? Material.PURPUR_SLAB : Material.END_STONE_BRICK_SLAB));
            if (dx <= 2 && dz <= 2) ops.add(op(x, roof, z, Material.AIR));
        }

        if (dx <= 2 && z == cz - 20) for (int y = ground + 1; y <= ground + 5; y++) {
            ops.add(op(x, y, z, Material.AIR));
        }
        if ((dx == 15 && Math.floorMod(z - cz, 7) == 0)
                || (dz == 20 && Math.floorMod(x - cx, 7) == 0)) {
            ops.add(op(x, ground + 4, z, Material.PURPLE_STAINED_GLASS_PANE));
            ops.add(op(x, ground + 5, z, Material.PURPLE_STAINED_GLASS_PANE));
        }
        // Pequeño patio lateral que rompe la silueta rectangular.
        if (x >= cx + 16 && x <= cx + 27 && Math.abs(z - cz) <= 8) {
            ops.add(op(x, ground, z, Material.END_STONE_BRICKS));
            if ((x == cx + 16 || x == cx + 27 || Math.abs(z - cz) == 8) && (x + z) % 4 != 0) {
                for (int y = ground + 1; y <= ground + 5; y++) ops.add(op(x, y, z, Material.PURPUR_BLOCK));
            }
        }
    }

    private void finishChunk(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        placeFixedContent(chunk);
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
                new Spawn(-70,100,-48,"arlightbosses:amethyst_shulker_minion"),
                new Spawn(70,100,-48,"arlightbosses:amethyst_eye_minion"),
                new Spawn(-72,100,48,"arlightbosses:void_enderman_sentinel_minion"),
                new Spawn(72,100,48,"arlightbosses:amethyst_guardian_shard_minion"),
                new Spawn(-42,100,-28,"arlightbosses:corrupted_ender_mite_minion"),
                new Spawn(42,100,34,"arlightbosses:amethyst_eye_minion"),
                new Spawn(0,98,-230,"arlightbosses:amethyst_phantom_minion"),
                new Spawn(-230,97,0,"arlightbosses:void_enderman_minion"),
                new Spawn(230,97,0,"arlightbosses:amethyst_shulker_minion"),
                new Spawn(0,97,235,"arlightbosses:amethyst_guardian_shard_minion"),
                new Spawn(-150,95,165,"arlightbosses:amethyst_eye_minion"),
                new Spawn(155,95,165,"arlightbosses:corrupted_ender_mite_minion"),
                new Spawn(-36,98,430,"arlightbosses:void_enderman_sentinel_minion"),
                new Spawn(36,98,430,"arlightbosses:amethyst_phantom_minion"),
                new Spawn(-44,100,-28,"arlightbosses:amethyst_eye_minion"),
                new Spawn(44,100,-28,"arlightbosses:amethyst_shulker_minion"),
                new Spawn(-42,100,34,"arlightbosses:corrupted_ender_mite_minion"),
                new Spawn(43,100,34,"arlightbosses:void_enderman_sentinel_minion"),
                new Spawn(-230,97,14,"arlightbosses:void_enderman_minion"),
                new Spawn(230,97,14,"arlightbosses:amethyst_guardian_shard_minion"),
                new Spawn(0,98,-216,"arlightbosses:amethyst_phantom_minion"),
                new Spawn(0,97,249,"arlightbosses:amethyst_shulker_minion"),
                new Spawn(-150,95,177,"arlightbosses:amethyst_eye_minion"),
                new Spawn(155,95,177,"arlightbosses:corrupted_ender_mite_minion")
        );
        for (Spawn spawn : spawns) if (contains(chunk, spawn.x(), spawn.z())) placeSpawner(chunk.getWorld(), spawn.x(), spawn.y(), spawn.z(), spawn.id());

        record Loot(int x, int y, int z, LootTables table, boolean barrel) { }
        List<Loot> loot = List.of(
                new Loot(-52,100,-8,LootTables.END_CITY_TREASURE,false),
                new Loot(52,100,-8,LootTables.END_CITY_TREASURE,true),
                new Loot(-42,100,38,LootTables.END_CITY_TREASURE,false),
                new Loot(42,100,38,LootTables.END_CITY_TREASURE,true),
                new Loot(0,98,-230,LootTables.STRONGHOLD_LIBRARY,false),
                new Loot(-230,97,0,LootTables.END_CITY_TREASURE,false),
                new Loot(230,97,0,LootTables.END_CITY_TREASURE,false),
                new Loot(0,97,235,LootTables.END_CITY_TREASURE,true),
                new Loot(-20,98,430,LootTables.END_CITY_TREASURE,false),
                new Loot(20,98,430,LootTables.END_CITY_TREASURE,false),
                new Loot(-50,100,-28,LootTables.STRONGHOLD_LIBRARY,false),
                new Loot(-38,100,-28,LootTables.STRONGHOLD_LIBRARY,true),
                new Loot(38,100,-28,LootTables.END_CITY_TREASURE,true),
                new Loot(50,100,-28,LootTables.END_CITY_TREASURE,false),
                new Loot(-48,100,34,LootTables.STRONGHOLD_CORRIDOR,true),
                new Loot(-36,100,34,LootTables.END_CITY_TREASURE,false),
                new Loot(37,100,34,LootTables.END_CITY_TREASURE,true),
                new Loot(49,100,34,LootTables.END_CITY_TREASURE,false),
                new Loot(-230,97,-12,LootTables.STRONGHOLD_LIBRARY,true),
                new Loot(230,97,-12,LootTables.END_CITY_TREASURE,false),
                new Loot(0,98,-244,LootTables.STRONGHOLD_LIBRARY,false),
                new Loot(0,97,249,LootTables.END_CITY_TREASURE,true),
                new Loot(-150,95,153,LootTables.END_CITY_TREASURE,false),
                new Loot(155,95,153,LootTables.END_CITY_TREASURE,true)
        );
        for (Loot lootEntry : loot) if (contains(chunk, lootEntry.x(), lootEntry.z())) {
            placeLoot(chunk.getWorld(), lootEntry.x(), lootEntry.y(), lootEntry.z(), lootEntry.table().getLootTable(), lootEntry.barrel());
        }
    }

    private void placeSpawner(World world, int x, int y, int z, String id) {
        world.getBlockAt(x, y, z).setType(Material.SPAWNER, false);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), String.format(Locale.ROOT,
                "execute in %s run data merge block %d %d %d {Delay:80s,MinSpawnDelay:240s,MaxSpawnDelay:400s,SpawnCount:2s,MaxNearbyEntities:4s,RequiredPlayerRange:18s,SpawnRange:5s,SpawnData:{entity:{id:\"%s\",Tags:[\"arlightbingo_template_mob\"],PersistenceRequired:1b}},SpawnPotentials:[{weight:1,data:{entity:{id:\"%s\",Tags:[\"arlightbingo_template_mob\"],PersistenceRequired:1b}}}]} ",
                world.getKey(), x, y, z, id, id));
    }

    private void placeLoot(World world, int x, int y, int z, LootTable table, boolean barrel) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(barrel ? Material.BARREL : Material.CHEST, false);
        if (barrel && block.getState() instanceof Barrel state) {
            state.setLootTable(table); state.setSeed(world.getSeed() ^ x * 43L ^ z); state.update(true, false);
        } else if (!barrel && block.getState() instanceof Chest state) {
            state.setLootTable(table); state.setSeed(world.getSeed() ^ x * 43L ^ z); state.update(true, false);
        }
    }

    private boolean insideReplacementZone(int x, int z) {
        return Math.hypot(x, z - 80.0D) <= 610.0D;
    }

    private Material surfaceMaterial(int x, int y, int z, int kind) {
        int hash = x * 31 + z * 17 + y * 13 + kind * 97;
        if (Math.floorMod(hash, 41) == 0) return Material.AMETHYST_BLOCK;
        if (Math.floorMod(hash, 29) == 0) return Material.PURPUR_BLOCK;
        return Material.END_STONE;
    }

    private double octagonalMetric(int x, int z, double rx, double rz) {
        double nx = Math.abs(x) / rx;
        double nz = Math.abs(z) / rz;
        return Math.max(nx, nz) + 0.42D * Math.min(nx, nz);
    }

    private SegmentPoint distanceToSegment(double px, double pz, double x1, double z1, double x2, double z2) {
        double vx = x2 - x1, vz = z2 - z1;
        double length2 = vx * vx + vz * vz;
        double t = length2 == 0 ? 0 : ((px - x1) * vx + (pz - z1) * vz) / length2;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double sx = x1 + t * vx, sz = z1 + t * vz;
        return new SegmentPoint(Math.hypot(px - sx, pz - sz), t);
    }

    private TemplateChunkBuildQueue.BlockOp op(int x, int y, int z, Material material) {
        return TemplateChunkBuildQueue.BlockOp.of(x, y, z, material);
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
                "spawn", "0,98,-230", "boss", "0,100,0",
                "keys", "-52,100,-8;52,100,-8;-42,100,38;42,100,38",
                "gate", "0,98,276", "altar", "0,98,430",
                "dragon", "0,120,430", "exit", "0,98,455"));
        TemplateCampaignRepair.ensureEndKeyChests(world, List.of());
        TemplateCampaignRepair.ensureEndAltar(world, null);
        world.save();
    }

    private String campaignAnchorStatus(World world) {
        List<Location> keys = List.of(
                new Location(world, -52, 100, -8), new Location(world, 52, 100, -8),
                new Location(world, -42, 100, 38), new Location(world, 42, 100, 38));
        long containers = keys.stream().filter(TemplateCampaignRepair::isContainer).count();
        Location altar = new Location(world, 0, 98, 430);
        boolean altarPresent = CampaignItemBridge.isBlock(altar, CampaignItemBridge.CORRUPTED_ALTAR)
                || altar.getBlock().getType() == Material.RESPAWN_ANCHOR;
        return "cofres=" + containers + "/4, altar=" + (altarPresent ? "presente" : "ausente");
    }

    private boolean writeMarker(World world) {
        Path file = world.getWorldFolder().toPath().resolve(MARKER);
        String text = "version=" + VERSION + "\n"
                + "structureRevision=" + STRUCTURE_REVISION + "\n"
                + "spawn=0,98,-230\n"
                + "boss=0,100,0\n"
                + "keys=-52,100,-8;52,100,-8;-42,100,38;42,100,38\n"
                + "gate=0,98,276\n"
                + "altar=0,98,430\n"
                + "dragon=0,120,430\n"
                + "exit=0,98,455\n";
        try {
            world.save();
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            TemplateMarkerLookup.rememberMarker(plugin, worldName(), MARKER, file);
            return true;
        } catch (Throwable error) {
            plugin.getLogger().warning("No se pudo escribir el marcador End: " + error.getMessage());
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
            sender.sendMessage(ChatColor.YELLOW + "Plantilla End eliminada.");
            return true;
        } catch (Throwable error) {
            sender.sendMessage(ChatColor.RED + "No se pudo borrar la plantilla End: " + rootMessage(error));
            return false;
        }
    }

    private void cleanupBoss(World world, String tag) {
        for (Entity entity : new ArrayList<>(world.getEntities())) if (entity.getScoreboardTags().contains(tag)) entity.remove();
    }

    private boolean isBusy() { return stage == Stage.CREATING_WORLD || stage == Stage.PREGENERATING || stage == Stage.FINALIZING; }
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

    private record IslandSpec(int x, int z, int rx, int rz, int baseY, int kind) { }
    private record IslandColumn(int bottom, int top, int kind) { }
    private record SegmentPoint(double distance, double t) { }
}
