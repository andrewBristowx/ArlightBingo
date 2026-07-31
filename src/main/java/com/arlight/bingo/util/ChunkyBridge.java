package com.arlight.bingo.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Integración opcional con Chunky sin acoplar el JAR de Bingo a una versión concreta. */
public final class ChunkyBridge {
    public record Selection(String shape, int centerX, int centerZ, int radiusX, int radiusZ) {
        public Selection {
            shape = shape == null || shape.isBlank() ? "square" : shape;
            radiusX = Math.max(16, radiusX);
            radiusZ = Math.max(16, radiusZ);
        }
    }

    public static List<Selection> squareTiles(int radius, int requestedTileRadius) {
        int safeRadius = Math.max(16, radius);
        int tileRadius = Math.max(128, Math.min(safeRadius, requestedTileRadius));
        int tileDiameter = tileRadius * 2;
        List<Selection> selections = new ArrayList<>();

        for (int minX = -safeRadius; minX < safeRadius; minX += tileDiameter) {
            int maxX = Math.min(safeRadius, minX + tileDiameter);
            for (int minZ = -safeRadius; minZ < safeRadius; minZ += tileDiameter) {
                int maxZ = Math.min(safeRadius, minZ + tileDiameter);
                int centerX = (minX + maxX) / 2;
                int centerZ = (minZ + maxZ) / 2;
                int radiusX = Math.max(16, (maxX - minX + 1) / 2);
                int radiusZ = Math.max(16, (maxZ - minZ + 1) / 2);
                String shape = radiusX == radiusZ ? "square" : "rectangle";
                selections.add(new Selection(shape, centerX, centerZ, radiusX, radiusZ));
            }
        }

        selections.sort(Comparator.comparingLong(selection ->
                (long) selection.centerX() * selection.centerX()
                        + (long) selection.centerZ() * selection.centerZ()));
        return selections;
    }

    private final JavaPlugin plugin;
    private Object api;
    private Method startTask;
    private volatile String expectedWorld;
    private volatile CompletableFuture<Void> current;

    public ChunkyBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        if (api != null) return true;
        if (Bukkit.getPluginManager().getPlugin("Chunky") == null) return false;
        try {
            Class<?> apiClass = Class.forName("org.popcraft.chunky.api.ChunkyAPI");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(apiClass);
            if (registration == null) return false;
            api = registration.getProvider();
            for (Method method : apiClass.getMethods()) {
                if (method.getName().equals("startTask") && method.getParameterCount() == 7) {
                    startTask = method;
                    break;
                }
            }
            Method onComplete = apiClass.getMethod("onGenerationComplete", Consumer.class);
            onComplete.invoke(api, (Consumer<Object>) this::onComplete);
            return startTask != null;
        } catch (Throwable error) {
            plugin.getLogger().severe("No se pudo conectar con la API de Chunky: " + error.getMessage());
            api = null;
            startTask = null;
            return false;
        }
    }

    public synchronized boolean isRunning() {
        return current != null && !current.isDone();
    }

    public synchronized CompletableFuture<Void> generate(String world, int radius) {
        return generate(world, new Selection("square", 0, 0, radius, radius));
    }

    public synchronized CompletableFuture<Void> generate(String world, Selection selection) {
        if (!connect()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Chunky no está instalado, habilitado o no expone una API compatible."));
        }
        if (current != null && !current.isDone()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Chunky ya está preparando " + expectedWorld));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        expectedWorld = world;
        current = result;
        try {
            Object started = startTask.invoke(api, world, selection.shape(),
                    selection.centerX(), selection.centerZ(),
                    selection.radiusX(), selection.radiusZ(), "concentric");
            if (started instanceof Boolean ok && !ok) {
                clearAndFail(result, new IllegalStateException("Chunky rechazó la tarea para " + world));
            } else {
                plugin.getLogger().info("Chunky comenzó " + world + " en "
                        + selection.centerX() + "," + selection.centerZ()
                        + " con radios " + selection.radiusX() + "x" + selection.radiusZ() + ".");
            }
        } catch (Throwable error) {
            clearAndFail(result, error.getCause() == null ? error : error.getCause());
        }
        return result;
    }

    /**
     * Pregenera una selección grande en tandas y no inicia la siguiente hasta
     * que el mundo haya descargado de forma estable los chunks de la anterior.
     * Evita que Arclight reciba decenas de miles de serializaciones de golpe.
     */
    public CompletableFuture<Void> generateBatched(String worldName, World world,
                                                    List<Selection> selections,
                                                    int maxLoadedChunks,
                                                    long drainCheckIntervalTicks,
                                                    int stableChecks,
                                                    Consumer<String> status) {
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("El mundo no está cargado."));
        }
        List<Selection> batches = selections == null ? List.of() : List.copyOf(selections);
        if (batches.isEmpty()) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> result = new CompletableFuture<>();
        int safeLoadedLimit = Math.max(64, maxLoadedChunks);
        long safeInterval = Math.max(20L, drainCheckIntervalTicks);
        int safeStableChecks = Math.max(2, stableChecks);

        final class BatchRunner {
            private int index;

            private void next() {
                if (result.isDone()) return;
                if (index >= batches.size()) {
                    result.complete(null);
                    return;
                }

                Selection selection = batches.get(index);
                sendStatus(status, "Chunky tanda " + (index + 1) + "/" + batches.size()
                        + " · centro " + selection.centerX() + "," + selection.centerZ());
                generate(worldName, selection).whenComplete((ignored, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (result.isDone()) return;
                            if (error != null) {
                                result.completeExceptionally(error);
                                return;
                            }
                            awaitChunkDrain(world, safeLoadedLimit, safeInterval, safeStableChecks,
                                    message -> sendStatus(status,
                                            "tanda " + (index + 1) + "/" + batches.size() + " · " + message))
                                    .whenComplete((drained, drainError) ->
                                            Bukkit.getScheduler().runTask(plugin, () -> {
                                                if (result.isDone()) return;
                                                if (drainError != null) {
                                                    result.completeExceptionally(drainError);
                                                    return;
                                                }
                                                index++;
                                                next();
                                            }));
                        }));
            }
        }

        BatchRunner runner = new BatchRunner();
        Bukkit.getScheduler().runTask(plugin, runner::next);
        return result;
    }

    /**
     * Espera varias comprobaciones consecutivas por debajo del límite. Una sola
     * lectura baja no basta porque Arclight puede volver a materializar chunks
     * que todavía estaban terminando iluminación o guardado.
     */
    public CompletableFuture<Void> awaitChunkDrain(World world,
                                                   int maxLoadedChunks,
                                                   long checkIntervalTicks,
                                                   int stableChecks,
                                                   Consumer<String> status) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        int safeLoadedLimit = Math.max(64, maxLoadedChunks);
        long safeInterval = Math.max(20L, checkIntervalTicks);
        int safeStableChecks = Math.max(2, stableChecks);
        int[] consecutive = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];

        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (result.isDone()) {
                task[0].cancel();
                return;
            }
            if (Bukkit.getWorld(world.getUID()) == null) {
                task[0].cancel();
                result.completeExceptionally(new IllegalStateException(
                        "El mundo fue descargado mientras se estabilizaban sus chunks."));
                return;
            }

            int loaded;
            try {
                loaded = world.getLoadedChunks().length;
            } catch (Throwable error) {
                task[0].cancel();
                result.completeExceptionally(error);
                return;
            }

            if (loaded <= safeLoadedLimit) consecutive[0]++;
            else consecutive[0] = 0;

            sendStatus(status, "esperando guardado/descarga: " + loaded + " chunks cargados"
                    + " · estable " + consecutive[0] + "/" + safeStableChecks);
            if (consecutive[0] < safeStableChecks) return;

            task[0].cancel();
            result.complete(null);
        }, safeInterval, safeInterval);
        return result;
    }

    private static void sendStatus(Consumer<String> status, String message) {
        if (status == null) return;
        try {
            status.accept(message);
        } catch (Throwable ignored) { }
    }

    private void onComplete(Object event) {
        try {
            String world = String.valueOf(event.getClass().getMethod("world").invoke(event));
            CompletableFuture<Void> result = current;
            if (result == null || !Objects.equals(world, expectedWorld)) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                synchronized (this) {
                    if (current != result) return;
                    plugin.getLogger().info("Chunky terminó de preparar " + world + ".");
                    current = null;
                    expectedWorld = null;
                }
                result.complete(null);
            });
        } catch (Throwable error) {
            plugin.getLogger().warning("No se pudo procesar el final de una tarea de Chunky: " + error.getMessage());
        }
    }

    private synchronized void clearAndFail(CompletableFuture<Void> result, Throwable error) {
        current = null;
        expectedWorld = null;
        result.completeExceptionally(error);
    }
}
