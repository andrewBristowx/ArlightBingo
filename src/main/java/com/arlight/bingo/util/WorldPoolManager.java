package com.arlight.bingo.util;

import com.arlight.bingo.integration.CoreIntegration;
import com.arlight.bingo.template.EndTemplateManager;
import com.arlight.bingo.template.NetherTemplateManager;
import com.arlight.bingo.template.OverworldTemplateManager;
import com.arlight.bingo.template.TemplateMarkerLookup;
import com.arlight.bingo.template.TemplateWorldCloner;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Mantiene arenas listas para que una partida nunca tenga que generar su mundo al comenzar. */
public final class WorldPoolManager {
    private static final String TEMPLATE_READY_MARKER = ".arlightbingo_ready_1_36_2_cold_dimension_storage";
    private static final String CHUNKY_READY_MARKER = ".arlightbingo_ready_1_32_0_chunky";
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final ArenaWorldManager worlds;
    private final ChunkyBridge chunky;
    private final MultiversePortalBridge portals;
    private final PreparationDisplay preparationDisplay;
    private final Set<Integer> ready = new LinkedHashSet<>();
    private CompletableFuture<ArenaGroup> preparing;
    private Integer preparingSlot;
    private Integer activeSlot;
    private PreparationMode preparingMode = PreparationMode.BACKGROUND;
    private OverworldTemplateManager overworldTemplates;
    private NetherTemplateManager netherTemplates;
    private EndTemplateManager endTemplates;

    private enum PreparationMode {
        BACKGROUND,
        MANUAL,
        MATCH
    }

    public record ArenaGroup(int slot, String overworld, String nether, String end) {
        public List<String> all() { return List.of(overworld, nether, end); }
        public List<String> extras() { return List.of(nether, end); }
    }

    public WorldPoolManager(JavaPlugin plugin, ConfigManager config, ArenaWorldManager worlds,
                            WorldBorderManager pregenerator) {
        this.plugin = plugin;
        this.config = config;
        this.worlds = worlds;
        this.chunky = new ChunkyBridge(plugin);
        this.portals = new MultiversePortalBridge(plugin);
        this.preparationDisplay = new PreparationDisplay(plugin, config);
    }

    public void setTemplateManagers(OverworldTemplateManager overworld,
                                    NetherTemplateManager nether,
                                    EndTemplateManager end) {
        this.overworldTemplates = overworld;
        this.netherTemplates = nether;
        this.endTemplates = end;
    }

    public void initialize(String restoredActiveWorld) {
        if (!config.isWorldPoolEnabled()) return;
        for (int slot = 1; slot <= config.getWorldPoolSize(); slot++) {
            ArenaGroup arena = group(slot);
            if (!marker(slot).isFile()) continue;
            TemplateWorldCloner.CloneValidation validation = validateArenaGroup(arena);
            if (validation.valid()) {
                ready.add(slot);
            } else {
                marker(slot).delete();
                plugin.getLogger().warning("Se invalidó el slot " + slot
                        + " porque sus tres dimensiones no están completas: " + validation.detail());
            }
        }
        if (restoredActiveWorld != null) {
            for (int slot = 1; slot <= config.getWorldPoolSize(); slot++) {
                ArenaGroup arena = group(slot);
                if (!arena.overworld().equalsIgnoreCase(restoredActiveWorld)) continue;
                TemplateWorldCloner.CloneValidation validation = validateArenaGroup(arena);
                if (validation.valid()) {
                    activeSlot = slot;
                    ready.remove(slot);
                } else {
                    plugin.getLogger().severe("No se restauró la arena activa " + slot
                            + ": " + validation.detail());
                }
            }
        }
        plugin.getLogger().info("Pool de Bingo: " + ready.size() + " arena(s) lista(s)." );
        syncExistingArenaIntegrations();
        if (config.isWorldPoolPrepareOnStartup() && activeSlot == null) prepareNextDirty();
        verifyInventoriesNotice();
    }

    /**
     * Flujo usado al iniciar una partida. Si las plantillas están activadas, crea
     * copias desechables completas; el modo heredado conserva la preparación con Chunky.
     */
    public synchronized CompletableFuture<ArenaGroup> prepareAndAcquireForMatch(Collection<? extends Player> participants) {
        if (!config.isWorldPoolEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("El pool de mundos está desactivado."));
        }

        // Si ya existe una arena lista, primero verifica las tres dimensiones.
        // Un marcador antiguo del Overworld no basta para reutilizar Nether y End.
        invalidateBrokenActiveSlot();
        pruneBrokenReadySlots();
        if (activeSlot != null || !ready.isEmpty()) {
            return acquire();
        }

        // Si Chunky ya estaba preparando una arena en segundo plano, la partida se engancha
        // a esa misma tarea y activa la interfaz de espera, sin iniciar una segunda tarea.
        if (preparing != null) {
            // Una partida puede engancharse a una preparación iniciada en segundo plano
            // o manualmente. En ambos casos activa las interfaces correctas sin iniciar
            // una segunda tarea de Chunky.
            if (preparingMode != PreparationMode.MATCH && preparingSlot != null) {
                preparationDisplay.start(preparingSlot, participants, true);
            }
            preparingMode = PreparationMode.MATCH;
            return acquire();
        }

        plugin.getLogger().info(config.isTemplateMatchesEnabled()
                ? "Inicio de Bingo: copiando las tres plantillas completas a una arena desechable."
                : "Inicio de Bingo: ejecutando preparación automática de terreno con Chunky.");
        prepareNext(PreparationMode.MATCH, participants);
        return acquire();
    }

    public synchronized CompletableFuture<ArenaGroup> acquire() {
        invalidateBrokenActiveSlot();
        if (activeSlot != null) {
            try {
                return CompletableFuture.completedFuture(load(group(activeSlot)));
            } catch (Throwable error) {
                int broken = activeSlot;
                activeSlot = null;
                marker(broken).delete();
                plugin.getLogger().severe("El slot activo " + broken
                        + " dejó de ser válido: " + rootMessage(error));
            }
        }

        pruneBrokenReadySlots();
        while (!ready.isEmpty()) {
            int slot = ready.iterator().next();
            ready.remove(slot);
            ArenaGroup arena = group(slot);
            try {
                ArenaGroup selected = load(arena);
                activeSlot = slot;
                if (config.isWorldPoolPrepareDuringMatch()) prepareNextDirty();
                return CompletableFuture.completedFuture(selected);
            } catch (Throwable error) {
                marker(slot).delete();
                plugin.getLogger().warning("Se descartó el slot " + slot
                        + " durante la carga: " + rootMessage(error));
            }
        }

        if (preparing == null) {
            // No hay una arena íntegra: copia nuevamente las tres plantillas. En modo
            // plantilla esto no ejecuta Chunky ni modifica las plantillas maestras.
            prepareNextDirty();
        }
        if (preparing == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "No hay ningún slot disponible para preparar una arena de Bingo."));
        }
        CompletableFuture<ArenaGroup> currentPreparation = preparing;
        return currentPreparation.thenCompose(ignored -> acquire());
    }

    public synchronized void releaseActive() {
        if (activeSlot == null) return;
        int used = activeSlot;
        activeSlot = null;
        marker(used).delete();
        ready.remove(used);
        plugin.getLogger().info("Arena " + used + " liberada; se preparará otra seed para reutilizarla.");
        if (config.isWorldPoolPrepareDuringMatch()) prepareNextDirty();
    }

    public List<String> activeExtras() {
        return activeSlot == null ? List.of() : group(activeSlot).extras();
    }

    public synchronized boolean hasUsableActiveArena() {
        if (activeSlot == null) return false;
        return validateArenaGroup(group(activeSlot)).valid();
    }

    private synchronized void prepareNextDirty() {
        prepareNext(PreparationMode.BACKGROUND, List.of());
    }

    private synchronized boolean prepareNext(PreparationMode mode, Collection<? extends Player> audience) {
        if (!config.isWorldPoolEnabled() || preparing != null) return false;
        for (int slot = 1; slot <= config.getWorldPoolSize(); slot++) {
            if (!ready.contains(slot) && !Objects.equals(activeSlot, slot)) {
                prepare(slot, mode, audience);
                return true;
            }
        }
        return false;
    }

    /** Inicia manualmente la preparación segura de un único slot. */
    public synchronized boolean prepareNext() {
        return prepareNext(PreparationMode.MANUAL, List.of());
    }

    public synchronized String status() {
        if (preparingSlot != null) {
            return "PREPARANDO slot " + preparingSlot
                    + (config.isTemplateMatchesEnabled() ? " desde plantillas" : " con Chunky");
        }
        return ready.size() + " arena(s) lista(s)" + (activeSlot == null ? "" : ", activa: " + activeSlot);
    }

    private void prepare(int slot, PreparationMode mode, Collection<? extends Player> audience) {
        preparingSlot = slot;
        preparingMode = mode;
        ArenaGroup group = group(slot);
        plugin.getLogger().info("Preparando pool de Bingo " + slot + "/" + config.getWorldPoolSize() + "...");
        if (mode != PreparationMode.BACKGROUND) {
            boolean showNotice = mode == PreparationMode.MATCH
                    || (mode == PreparationMode.MANUAL
                    && config.isWorldPreparationNoticeShowDuringManualPrepare());
            preparationDisplay.start(slot, audience, showNotice);
        }
        CompletableFuture<ArenaGroup> future = new CompletableFuture<>();
        preparing = future;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                marker(slot).delete();
                if (config.isTemplateMatchesEnabled()) {
                    validateTemplateSources(group);
                    // Ningún destino se crea ni carga antes de copiar. Primero se descargan y
                    // eliminan las tres rutas antiguas, incluso si Arclight las guardó dentro
                    // de dimensions/<namespace>/<nombre>.
                    for (String name : group.all()) {
                        worlds.prepareTemplateRuntimeTarget(name);
                    }
                    cloneTemplateSequential(group, 0, future);
                    return;
                }
                for (String name : group.all()) worlds.regenerateWorld(name);
                if (!configureIntegrations(group)) {
                    throw new IllegalStateException("No se pudieron configurar las integraciones de Multiverse requeridas.");
                }
                pregenerateSequential(group, 0, future);
            } catch (Throwable error) {
                fail(slot, future, error);
            }
        });
    }

    private void validateTemplateSources(ArenaGroup group) {
        if (overworldTemplates == null || netherTemplates == null || endTemplates == null) {
            throw new IllegalStateException("Los gestores de plantillas todavía no están disponibles.");
        }
        boolean overworldReady = overworldTemplates.isReadyForMatches();
        boolean netherReady = netherTemplates.isReadyForMatches();
        boolean endReady = endTemplates.isReadyForMatches();
        if (config.isTemplateMatchesRequireComplete()
                && (!overworldReady || !netherReady || !endReady)) {
            throw new IllegalStateException("Falta una plantilla completa (Overworld=" + overworldReady
                    + ", Nether=" + netherReady + ", End=" + endReady
                    + "). Usa /bingo template all status y espera COMPLETE. "
                    + "Marcadores: Overworld=" + overworldTemplates.markerDiagnostic()
                    + "; Nether=" + netherTemplates.markerDiagnostic()
                    + "; End=" + endTemplates.markerDiagnostic() + ".");
        }

        // Repara y verifica los puntos funcionales antes de copiar. No vuelve a
        // generar terreno ni estructuras: sólo cofres de misión, cerradura y altar.
        if (netherReady && !netherTemplates.ensureCampaignAnchorsReady()) {
            throw new IllegalStateException("La plantilla Nether está completa, pero no se pudieron "
                    + "reparar/verificar la Llave Ígnea y su cerradura. Usa /bingo template nether audit.");
        }
        if (endReady && !endTemplates.ensureCampaignAnchorsReady()) {
            throw new IllegalStateException("La plantilla End está completa, pero no se pudieron "
                    + "reparar/verificar los cofres de llave y el altar de invocación. Usa /bingo template end audit.");
        }

        List<String> sources = List.of(config.getOverworldTemplateWorld(),
                config.getNetherTemplateWorld(), config.getEndTemplateWorld());
        Set<String> normalizedSources = new HashSet<>();
        for (String source : sources) normalizedSources.add(source.toLowerCase(Locale.ROOT));
        if (normalizedSources.size() != sources.size()) {
            throw new IllegalStateException("Los tres mundos plantilla deben tener nombres distintos.");
        }
        for (String source : sources) {
            if (group.all().stream().anyMatch(target -> target.equalsIgnoreCase(source))) {
                throw new IllegalStateException("El mundo plantilla '" + source
                        + "' no puede usar el mismo nombre que un slot del pool.");
            }
            Path folder = TemplateMarkerLookup.activeFolder(plugin, source);
            if (!Files.isDirectory(folder)) {
                throw new IllegalStateException("No existe la carpeta de plantilla '" + source + "'.");
            }
        }
    }

    private void cloneTemplateSequential(ArenaGroup group, int index,
                                         CompletableFuture<ArenaGroup> future) {
        if (index >= group.all().size()) {
            try {
                // Las tres carpetas ya están completas y cerradas. Sólo ahora Bukkit/Arclight
                // puede cargar los mundos; así ningún chunk vanilla queda en memoria antes de
                // que la instantánea de la plantilla exista en su almacenamiento definitivo.
                loadColdClonedArena(group);
                if (!configureIntegrations(group)) {
                    throw new IllegalStateException(
                            "No se pudieron configurar las integraciones de Multiverse requeridas.");
                }
                completePreparation(group, future);
            } catch (Throwable error) {
                fail(group.slot(), future, error);
            }
            return;
        }

        List<String> sources = List.of(config.getOverworldTemplateWorld(),
                config.getNetherTemplateWorld(), config.getEndTemplateWorld());
        List<World.Environment> environments = List.of(World.Environment.NORMAL,
                World.Environment.NETHER, World.Environment.THE_END);
        String sourceName = sources.get(index);
        String targetName = group.all().get(index);
        World sourceWorld = Bukkit.getWorld(sourceName);
        boolean sourceWasLoaded = sourceWorld != null;
        boolean restoreAutoSave = sourceWorld != null && sourceWorld.isAutoSave();
        Path sourceFolder;

        try {
            if (sourceWorld != null) {
                if (!sourceWorld.getPlayers().isEmpty()) {
                    throw new IllegalStateException("Hay jugadores revisando la plantilla '"
                            + sourceName + "'. Deben salir antes de copiarla.");
                }

                // La copia de una plantilla cargada puede leer archivos MCA anteriores mientras
                // Arclight todavía conserva chunks modificados en memoria. Guardar y descargar
                // con save=true obliga a cerrar RegionFileStorage antes de iniciar la copia.
                sourceFolder = sourceWorld.getWorldFolder().toPath().toAbsolutePath().normalize();
                sourceWorld.setAutoSave(true);
                sourceWorld.save();
                if (!Bukkit.unloadWorld(sourceWorld, true)) {
                    throw new IllegalStateException("Arclight no pudo descargar la plantilla '"
                            + sourceName + "' para crear una instantánea estable.");
                }
                plugin.getLogger().info("Plantilla " + sourceName
                        + " guardada y descargada; clonando desde " + sourceFolder + ".");
            } else {
                sourceFolder = TemplateMarkerLookup.activeFolder(plugin, sourceName)
                        .toAbsolutePath().normalize();
            }

            if (preparingMode != PreparationMode.BACKGROUND) {
                preparationDisplay.phase(index == 0 ? "Overworld"
                        : index == 1 ? "Nether" : "End", index);
            }
        } catch (Throwable error) {
            restoreTemplateWorld(sourceName, environments.get(index), sourceWasLoaded,
                    restoreAutoSave);
            fail(group.slot(), future, error);
            return;
        }

        Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Throwable copyFailure = null;
            TemplateWorldCloner.CloneReport copyReport = null;
            try {
                // Resuelve el almacenamiento exacto. Si Arclight usa
                // dimensions/minecraft/bingo_template_nether, la copia se escribe como
                // dimensions/minecraft/bingo_arena_1_nether; no se crea un mundo autónomo
                // vacío en la raíz del servidor.
                copyReport = TemplateWorldCloner.copyColdSnapshot(sourceFolder, worldContainer,
                        sourceName, targetName, environments.get(index));
            } catch (Throwable error) {
                copyFailure = error;
            }
            Throwable finalCopyFailure = copyFailure;
            TemplateWorldCloner.CloneReport finalCopyReport = copyReport;
            Bukkit.getScheduler().runTask(plugin, () -> {
                restoreTemplateWorld(sourceName, environments.get(index), sourceWasLoaded,
                        restoreAutoSave);
                if (finalCopyFailure != null) {
                    fail(group.slot(), future, finalCopyFailure);
                    return;
                }

                try {
                    if (finalCopyReport == null) {
                        throw new IllegalStateException("La copia de " + targetName + " no devolvió informe.");
                    }
                    plugin.getLogger().info("Clonado en frío completado para " + targetName
                            + ": " + finalCopyReport.summary());
                    TemplateWorldCloner.CloneValidation copied = validateClone(index, targetName);
                    if (!copied.valid()) {
                        throw new IllegalStateException("Copia inválida de " + targetName + ": " + copied.detail());
                    }
                    if (preparingMode != PreparationMode.BACKGROUND) {
                        preparationDisplay.dimensionComplete(index + 1);
                    }
                    cloneTemplateSequential(group, index + 1, future);
                } catch (Throwable error) {
                    fail(group.slot(), future, error);
                }
            });
        });
    }


    private void loadColdClonedArena(ArenaGroup group) {
        List<String> names = group.all();
        List<World.Environment> environments = List.of(World.Environment.NORMAL,
                World.Environment.NETHER, World.Environment.THE_END);
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            TemplateWorldCloner.CloneValidation validation = validateClone(index, name);
            if (!validation.valid()) {
                throw new IllegalStateException("No se cargará " + name + ": " + validation.detail());
            }
            World cloned = worlds.loadExistingWorld(name, environments.get(index));
            if (cloned == null) {
                throw new IllegalStateException("Bukkit/Arclight no pudo cargar el almacenamiento frío de "
                        + name + ".");
            }
            Path loadedStorage = TemplateWorldCloner.resolveLoadedStorage(cloned);
            Path runtimeStorage = TemplateWorldCloner.findRuntimeFolder(plugin, name);
            if (runtimeStorage == null || loadedStorage == null
                    || !loadedStorage.toAbsolutePath().normalize()
                    .equals(runtimeStorage.toAbsolutePath().normalize())) {
                throw new IllegalStateException("Arclight cargó " + name + " desde " + loadedStorage
                        + " pero la instantánea está en " + runtimeStorage + ".");
            }
            plugin.getLogger().info("Mundo frío cargado desde su almacenamiento verificado: "
                    + name + " -> " + loadedStorage + ".");
            if (index == 0 && overworldTemplates != null) {
                overworldTemplates.installMatchActors(cloned);
            }
        }
    }


    private void restoreTemplateWorld(String sourceName, World.Environment environment,
                                      boolean sourceWasLoaded, boolean autoSave) {
        if (!sourceWasLoaded || Bukkit.getWorld(sourceName) != null) return;
        World restored = Bukkit.createWorld(new WorldCreator(sourceName).environment(environment));
        if (restored == null) {
            plugin.getLogger().severe("No se pudo volver a cargar la plantilla '" + sourceName
                    + "' después de clonarla.");
            return;
        }
        restored.setAutoSave(autoSave);
        plugin.getLogger().info("Plantilla " + sourceName + " cargada nuevamente tras la copia.");
    }

    private void pregenerateSequential(ArenaGroup group, int index, CompletableFuture<ArenaGroup> future) {
        if (index >= group.all().size()) {
            completePreparation(group, future);
            return;
        }
        String name = group.all().get(index);
        if (preparingMode != PreparationMode.BACKGROUND) {
            preparationDisplay.phase(index == 0 ? "Overworld" : index == 1 ? "Nether" : "End", index);
        }
        World world = Bukkit.getWorld(name);
        int radius = index == 0 ? config.getWorldPoolOverworldRadius()
                : index == 1 ? config.getWorldPoolNetherRadius() : config.getWorldPoolEndRadius();
        if (world == null) {
            fail(group.slot(), future, new IllegalStateException("No se cargó " + name));
            return;
        }
        chunky.generate(name, radius).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) fail(group.slot(), future, error);
            else {
                if (preparingMode != PreparationMode.BACKGROUND) {
                    preparationDisplay.dimensionComplete(index + 1);
                }
                pregenerateSequential(group, index + 1, future);
            }
        }));
    }

    private void completePreparation(ArenaGroup group, CompletableFuture<ArenaGroup> future) {
        try {
            for (String name : group.all()) {
                World world = Bukkit.getWorld(name);
                if (world != null) world.save();
            }
            if (config.isTemplateMatchesEnabled()) {
                TemplateWorldCloner.CloneValidation validation = validateArenaGroup(group);
                if (!validation.valid()) {
                    throw new IOException("Las tres copias no superaron la validación final: "
                            + validation.detail());
                }
            }
            marker(group.slot()).createNewFile();
            PreparationMode completedMode;
            synchronized (this) {
                ready.add(group.slot());
                preparing = null;
                preparingSlot = null;
                completedMode = preparingMode;
                preparingMode = PreparationMode.BACKGROUND;
            }
            plugin.getLogger().info("Arena del pool " + group.slot()
                    + (config.isTemplateMatchesEnabled()
                    ? " lista desde las tres plantillas maestras." : " lista para jugar."));
            if (completedMode == PreparationMode.MATCH) {
                preparationDisplay.complete(true);
            } else if (completedMode == PreparationMode.MANUAL) {
                preparationDisplay.complete(false);
            }
            future.complete(group);
        } catch (IOException error) {
            fail(group.slot(), future, error);
        }
    }

    private synchronized void fail(int slot, CompletableFuture<ArenaGroup> future, Throwable error) {
        plugin.getLogger().severe("Falló la preparación de la arena " + slot + ": " + error.getMessage());
        if (preparingMode != PreparationMode.BACKGROUND) {
            preparationDisplay.fail(error.getMessage() == null ? "error desconocido" : error.getMessage());
        }
        preparing = null;
        preparingSlot = null;
        preparingMode = PreparationMode.BACKGROUND;
        future.completeExceptionally(error);
    }

    private ArenaGroup group(int slot) {
        String base = config.getWorldPoolBaseName() + "_" + slot;
        return new ArenaGroup(slot, base, base + "_nether", base + "_the_end");
    }

    private ArenaGroup load(ArenaGroup group) {
        if (config.isTemplateMatchesEnabled()) {
            TemplateWorldCloner.CloneValidation validation = validateArenaGroup(group);
            if (!validation.valid()) {
                throw new IllegalStateException("Arena incompleta: " + validation.detail());
            }
            World overworld = worlds.loadExistingWorld(group.overworld(), World.Environment.NORMAL);
            World nether = worlds.loadExistingWorld(group.nether(), World.Environment.NETHER);
            World end = worlds.loadExistingWorld(group.end(), World.Environment.THE_END);
            if (overworld == null || nether == null || end == null) {
                throw new IllegalStateException("Bukkit no pudo cargar las tres copias existentes");
            }
        } else {
            worlds.loadWorld(group.overworld(), World.Environment.NORMAL);
            worlds.loadWorld(group.nether(), World.Environment.NETHER);
            worlds.loadWorld(group.end(), World.Environment.THE_END);
        }
        return group;
    }

    private TemplateWorldCloner.CloneValidation validateClone(int index, String targetName) {
        List<String> sources = List.of(config.getOverworldTemplateWorld(),
                config.getNetherTemplateWorld(), config.getEndTemplateWorld());
        List<World.Environment> environments = List.of(World.Environment.NORMAL,
                World.Environment.NETHER, World.Environment.THE_END);
        List<String> templateMarkers = List.of("arlight-overworld-template.properties",
                "arlight-nether-template.properties", "arlight-end-template.properties");
        Path folder = TemplateWorldCloner.findRuntimeFolder(plugin, targetName);
        TemplateWorldCloner.CloneValidation validation = TemplateWorldCloner.validateFolder(
                folder, sources.get(index), targetName, environments.get(index),
                templateMarkers.get(index));
        if (!validation.valid()) return validation;
        World loaded = Bukkit.getWorld(targetName);
        if (loaded != null && loaded.getEnvironment() != environments.get(index)) {
            return TemplateWorldCloner.CloneValidation.fail("el mundo cargado tiene entorno "
                    + loaded.getEnvironment() + " en vez de " + environments.get(index));
        }
        return TemplateWorldCloner.CloneValidation.ok();
    }

    private TemplateWorldCloner.CloneValidation validateArenaGroup(ArenaGroup group) {
        if (!config.isTemplateMatchesEnabled()) return TemplateWorldCloner.CloneValidation.ok();
        for (int index = 0; index < group.all().size(); index++) {
            String name = group.all().get(index);
            TemplateWorldCloner.CloneValidation validation = validateClone(index, name);
            if (!validation.valid()) {
                String dimension = index == 0 ? "Overworld" : index == 1 ? "Nether" : "End";
                return TemplateWorldCloner.CloneValidation.fail(
                        dimension + " (" + name + "): " + validation.detail());
            }
        }
        return TemplateWorldCloner.CloneValidation.ok();
    }

    private synchronized void pruneBrokenReadySlots() {
        if (!config.isTemplateMatchesEnabled() || ready.isEmpty()) return;
        for (Integer slot : new ArrayList<>(ready)) {
            TemplateWorldCloner.CloneValidation validation = validateArenaGroup(group(slot));
            if (validation.valid()) continue;
            ready.remove(slot);
            marker(slot).delete();
            plugin.getLogger().warning("Se descartó el slot listo " + slot + ": "
                    + validation.detail());
        }
    }

    private synchronized void invalidateBrokenActiveSlot() {
        if (!config.isTemplateMatchesEnabled() || activeSlot == null) return;
        TemplateWorldCloner.CloneValidation validation = validateArenaGroup(group(activeSlot));
        if (validation.valid()) return;
        int broken = activeSlot;
        activeSlot = null;
        marker(broken).delete();
        plugin.getLogger().severe("Se invalidó el slot activo " + broken + ": "
                + validation.detail());
    }

    private String rootMessage(Throwable error) {
        if (error == null) return "error desconocido";
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }

    private File marker(int slot) {
        String name = config.isTemplateMatchesEnabled()
                ? TEMPLATE_READY_MARKER : CHUNKY_READY_MARKER;
        if (config.isTemplateMatchesEnabled()) {
            Path runtime = TemplateWorldCloner.findRuntimeFolder(plugin, group(slot).overworld());
            if (runtime != null) return runtime.resolve(name).toFile();
        }
        return new File(new File(Bukkit.getWorldContainer(), group(slot).overworld()), name);
    }

    private void syncExistingArenaIntegrations() {
        if (!config.isWorldPoolAutoConfigureInventories() && !config.isWorldPoolUseMvnp()) return;
        if (plugin.getServer().getPluginManager().getPlugin("ArlightCore") == null) return;
        for (int slot = 1; slot <= config.getWorldPoolSize(); slot++) {
            ArenaGroup arena = group(slot);
            if (config.isTemplateMatchesEnabled()) {
                if (TemplateWorldCloner.findRuntimeFolder(plugin, arena.overworld()) == null) continue;
                if (!validateArenaGroup(arena).valid()) continue;
            } else if (!new File(Bukkit.getWorldContainer(), arena.overworld()).isDirectory()) {
                continue;
            }
            configureIntegrations(arena);
        }
    }

    private boolean configureIntegrations(ArenaGroup group) {
        boolean corePresent = plugin.getServer().getPluginManager().getPlugin("ArlightCore") != null;
        boolean inventoriesOk = true;
        boolean portalsOk = true;
        boolean coreHandlesPortals = false;

        if (corePresent) {
            try {
                coreHandlesPortals = CoreIntegration.coreHandlesNetherPortals();
                if (config.isWorldPoolAutoConfigureInventories()
                        || (config.isWorldPoolUseMvnp() && coreHandlesPortals)) {
                    // Una sola llamada combinada. Core 1.20.0 aplica caché e idempotencia,
                    // evitando el antiguo patrón inventory + portal que importaba cada mundo dos veces.
                    boolean combined = CoreIntegration.configureArenaWorlds(
                            "bingo", group.overworld(), group.nether(), group.end());
                    if (config.isWorldPoolAutoConfigureInventories()) inventoriesOk = combined;
                    if (config.isWorldPoolUseMvnp() && coreHandlesPortals) portalsOk = combined;
                }
            } catch (Throwable error) {
                plugin.getLogger().warning("ArlightCore no pudo sincronizar Multiverse: "
                        + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                inventoriesOk = !config.isWorldPoolAutoConfigureInventories();
                portalsOk = false;
                coreHandlesPortals = false;
            }
        }

        if (config.isWorldPoolUseMvnp() && !coreHandlesPortals) {
            portalsOk = portals.link(group, config.isWorldPoolRequireMvnp());
        }

        if (!inventoriesOk) {
            plugin.getLogger().warning("No se pudo registrar el trío " + group.overworld()
                    + " en el grupo de inventarios 'bingo'.");
        }
        return inventoriesOk && (portalsOk || !config.isWorldPoolRequireMvnp());
    }

    public void shutdown() {
        preparationDisplay.shutdown();
    }

    private void verifyInventoriesNotice() {
        if (plugin.getServer().getPluginManager().getPlugin("Multiverse-Inventories") == null) return;
        if (config.isWorldPoolAutoConfigureInventories()) {
            plugin.getLogger().info("Multiverse-Inventories detectado: los tríos del pool se registrarán automáticamente en el grupo 'bingo'.");
        } else {
            plugin.getLogger().warning("Multiverse-Inventories detectado, pero world-pool.inventories.auto-configure-groups está en false.");
        }
    }
}
