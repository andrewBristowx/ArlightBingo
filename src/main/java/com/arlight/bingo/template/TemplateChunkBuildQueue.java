package com.arlight.bingo.template;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cola segura para construir una plantilla únicamente en chunks que Minecraft ya cargó.
 * Nunca solicita ni genera chunks. Mantiene un solo trabajo materializado a la vez para
 * no llenar la memoria con millones de operaciones.
 */
final class TemplateChunkBuildQueue {

    interface Planner {
        List<BlockOp> plan(Chunk chunk);
        void complete(Chunk chunk);
    }

    record BlockOp(int x, int y, int z, Material material, String data) {
        static BlockOp of(int x, int y, int z, Material material) {
            return new BlockOp(x, y, z, material, null);
        }
    }

    private final JavaPlugin plugin;
    private final World world;
    private final Planner planner;
    private final int blocksPerTick;
    private final long nanosPerTick;
    private final long ticketReleaseDelayTicks;
    private final int maxRetainedChunks;
    private final ArrayDeque<Long> waiting = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final ArrayDeque<RetainedChunk> retained = new ArrayDeque<>();

    private BukkitTask task;
    private Chunk currentChunk;
    private List<BlockOp> currentOps = List.of();
    private int currentIndex;
    private long elapsedTicks;

    TemplateChunkBuildQueue(JavaPlugin plugin, World world, Planner planner,
                            int blocksPerTick, long maxMillisPerTick,
                            long ticketReleaseDelayTicks, int maxRetainedChunks) {
        this.plugin = plugin;
        this.world = world;
        this.planner = planner;
        this.blocksPerTick = Math.max(100, blocksPerTick);
        this.nanosPerTick = Math.max(1L, maxMillisPerTick) * 1_000_000L;
        this.ticketReleaseDelayTicks = Math.max(20L, ticketReleaseDelayTicks);
        this.maxRetainedChunks = Math.max(2, maxRetainedChunks);
    }

    void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void offer(Chunk chunk) {
        if (chunk == null || chunk.getWorld() != world) return;
        long key = key(chunk.getX(), chunk.getZ());
        if (currentChunk != null && currentChunk.getX() == chunk.getX() && currentChunk.getZ() == chunk.getZ()) return;
        if (queued.add(key)) waiting.offer(key);
    }

    void offerPriority(Chunk chunk) {
        if (chunk == null || chunk.getWorld() != world) return;
        long key = key(chunk.getX(), chunk.getZ());
        if (currentChunk != null && currentChunk.getX() == chunk.getX() && currentChunk.getZ() == chunk.getZ()) return;
        if (!queued.add(key)) waiting.remove(key);
        waiting.addFirst(key);
    }

    int pending() {
        return waiting.size() + (currentChunk == null ? 0 : 1);
    }

    int retained() {
        return retained.size();
    }

    boolean isSettled() {
        return pending() == 0 && retained.isEmpty();
    }

    void shutdown() {
        if (task != null) task.cancel();
        task = null;
        releaseCurrent();
        while (!retained.isEmpty()) releaseTicket(retained.pollFirst().chunk());
        waiting.clear();
        queued.clear();
    }

    private void tick() {
        elapsedTicks++;
        releaseDueTicket();
        long started = System.nanoTime();
        int applied = 0;

        while (applied < blocksPerTick && System.nanoTime() - started < nanosPerTick) {
            if (currentChunk == null) {
                if (!beginNext()) return;
                if (currentOps.isEmpty()) {
                    finishCurrent();
                    continue;
                }
            }

            BlockOp op = currentOps.get(currentIndex++);
            boolean wrote = false;
            if (op.y() > world.getMinHeight() && op.y() < world.getMaxHeight()) {
                var block = world.getBlockAt(op.x(), op.y(), op.z());
                if (block.getType() != op.material()) {
                    block.setType(op.material(), false);
                    wrote = true;
                }
                if (op.data() != null) {
                    try {
                        block.setBlockData(Bukkit.createBlockData(op.data()), false);
                        wrote = true;
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            // El límite de bloques cuenta escrituras reales. Las operaciones AIR sobre
            // vacío se descartan rápidamente y siguen sujetas al presupuesto de tiempo.
            if (wrote) applied++;

            if (currentIndex >= currentOps.size()) finishCurrent();
        }
    }

    private boolean beginNext() {
        if (retained.size() >= maxRetainedChunks) return false;
        while (!waiting.isEmpty()) {
            long key = waiting.poll();
            queued.remove(key);
            int cx = (int) (key >> 32);
            int cz = (int) key;
            if (!world.isChunkLoaded(cx, cz)) continue;
            Chunk chunk = world.getChunkAt(cx, cz); // ya está cargado; no solicita generación.
            try { chunk.addPluginChunkTicket(plugin); }
            catch (Throwable ignored) { }
            currentChunk = chunk;
            currentIndex = 0;
            List<BlockOp> planned = planner.plan(chunk);
            currentOps = planned == null ? List.of() : new ArrayList<>(planned);
            return true;
        }
        return false;
    }

    private void finishCurrent() {
        Chunk finished = currentChunk;
        try {
            if (finished != null) planner.complete(finished);
        } finally {
            retainCurrent();
        }
    }

    private void retainCurrent() {
        Chunk finished = currentChunk;
        currentChunk = null;
        currentOps = List.of();
        currentIndex = 0;
        if (finished != null) {
            retained.addLast(new RetainedChunk(finished, elapsedTicks + ticketReleaseDelayTicks));
        }
    }

    private void releaseDueTicket() {
        RetainedChunk first = retained.peekFirst();
        if (first == null || first.releaseAtTick() > elapsedTicks) return;
        retained.pollFirst();
        releaseTicket(first.chunk());
    }

    private void releaseCurrent() {
        if (currentChunk != null) {
            releaseTicket(currentChunk);
        }
        currentChunk = null;
        currentOps = List.of();
        currentIndex = 0;
    }

    private void releaseTicket(Chunk chunk) {
        try { chunk.removePluginChunkTicket(plugin); }
        catch (Throwable ignored) { }
    }

    private record RetainedChunk(Chunk chunk, long releaseAtTick) { }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
