package com.arlight.bingo.template;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Registro inmutable de revisiones de plantillas.
 *
 * La primera implementación segura se activa para Overworld. Nether y End siguen
 * usando sus gestores actuales hasta que se rediseñen visualmente, pero comparten
 * el mismo formato de registro para la siguiente fase del proyecto.
 *
 * Una revisión nueva nunca se pinta sobre la estable: el Overworld estable se
 * descarga y se mueve a un nombre de archivo antes de crear el mundo limpio con
 * el nombre configurado. El pool continúa copiando el archivo estable hasta que
 * el administrador promueve explícitamente la revisión candidata.
 */
public final class TemplateRevisionManager {

    public enum Dimension {
        OVERWORLD("overworld", World.Environment.NORMAL,
                "arlight-overworld-template.properties");

        private final String id;
        private final World.Environment environment;
        private final String marker;

        Dimension(String id, World.Environment environment, String marker) {
            this.id = id;
            this.environment = environment;
            this.marker = marker;
        }
    }

    public enum RevisionStatus {
        BUILDING, READY, STABLE, SUPERSEDED, FAILED
    }

    public record BeginResult(boolean success, String revision, String worldName,
                              String message) {
        static BeginResult fail(String message) {
            return new BeginResult(false, null, null, message);
        }

        static BeginResult ok(String revision, String worldName, String message) {
            return new BeginResult(true, revision, worldName, message);
        }
    }

    private static final DateTimeFormatter AUTOMATIC_ID =
            DateTimeFormatter.ofPattern("'r'yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String STATE_FILE = "state.properties";

    private final JavaPlugin plugin;

    public TemplateRevisionManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void bootstrapOverworld(String baseWorldName, String legacyRevisionHint) {
        bootstrap(Dimension.OVERWORLD, baseWorldName, legacyRevisionHint);
    }

    /**
     * Devuelve la fuente estable que deben copiar las arenas. Es una lectura O(1)
     * y no recorre las carpetas del servidor.
     */
    public static String activeOverworldWorld(JavaPlugin plugin, String fallback) {
        Path stateFile = stateFile(plugin, Dimension.OVERWORLD);
        Properties state = load(stateFile);
        String active = state.getProperty("activeWorld", "").trim();
        return active.isEmpty() ? fallback : active;
    }

    public String activeOverworldWorld(String fallback) {
        return activeOverworldWorld(plugin, fallback);
    }

    public String activeRevision() {
        return state(Dimension.OVERWORLD).getProperty("activeRevision", "").trim();
    }

    public String workingRevision() {
        return state(Dimension.OVERWORLD).getProperty("workingRevision", "").trim();
    }

    public boolean hasWorkingRevision() {
        return !workingRevision().isBlank();
    }

    public String automaticRevisionId() {
        return AUTOMATIC_ID.format(Instant.now()) + "-zone-a";
    }

    /**
     * Prepara una revisión limpia del Overworld. Si el mundo configurado contiene
     * la versión estable, primero lo archiva y actualiza el puntero activo.
     */
    public synchronized BeginResult beginOverworldRevision(
            CommandSender sender, String requestedRevision, String baseWorldName) {
        Dimension dimension = Dimension.OVERWORLD;
        bootstrap(dimension, baseWorldName, "legacy");

        String revision = normalizeRevision(requestedRevision);
        if (revision == null) {
            return BeginResult.fail("El identificador de revisión debe usar letras, números, '.', '_' o '-'.");
        }

        Properties state = state(dimension);
        String existingWorking = state.getProperty("workingRevision", "").trim();
        if (!existingWorking.isEmpty()) {
            return BeginResult.fail("Ya existe una revisión en trabajo: " + existingWorking
                    + ". Reanúdala, audítala o descártala antes de crear otra.");
        }
        if (Files.isRegularFile(revisionFile(dimension, revision))) {
            return BeginResult.fail("La revisión '" + revision + "' ya existe y no se puede sobrescribir.");
        }

        Path baseFolder = configuredFolder(baseWorldName);
        String activeWorld = state.getProperty("activeWorld", "").trim();
        String activeRevision = state.getProperty("activeRevision", "").trim();

        if (Files.isDirectory(baseFolder)) {
            if (!activeWorld.isEmpty() && !activeWorld.equalsIgnoreCase(baseWorldName)) {
                return BeginResult.fail("Existe una carpeta suelta '" + baseWorldName
                        + "' que no es la estable registrada. Audítala antes de continuar.");
            }

            World loaded = Bukkit.getWorld(baseWorldName);
            if (loaded != null) {
                if (!loaded.getPlayers().isEmpty()) {
                    return BeginResult.fail("Hay jugadores dentro de la plantilla estable. "
                            + "Deben salir antes de crear la revisión.");
                }
                loaded.setAutoSave(true);
                loaded.save();
                if (!Bukkit.unloadWorld(loaded, true)) {
                    return BeginResult.fail("No se pudo descargar la plantilla estable para respaldarla.");
                }
            }

            String archiveName = availableArchiveName(baseWorldName,
                    activeRevision.isBlank() ? "stable" : activeRevision);
            Path archiveFolder = configuredFolder(archiveName);
            try {
                moveDirectory(baseFolder, archiveFolder);
            } catch (IOException error) {
                return BeginResult.fail("No se pudo respaldar la plantilla estable: " + error.getMessage());
            }

            if (!activeRevision.isBlank()) {
                Properties previous = revision(dimension, activeRevision);
                previous.setProperty("world", archiveName);
                previous.setProperty("status", RevisionStatus.STABLE.name());
                previous.setProperty("archivedAt", Long.toString(System.currentTimeMillis()));
                save(revisionFile(dimension, activeRevision), previous);
            }
            state.setProperty("activeWorld", archiveName);
            TemplateMarkerLookup.forgetWorld(plugin, baseWorldName);
            TemplateMarkerLookup.forgetMarker(plugin, baseWorldName, dimension.marker);
            TemplateMarkerLookup.forgetMarker(plugin, baseWorldName,
                    "arlight-overworld-template-building.properties");
            Path archivedMarker = archiveFolder.resolve(dimension.marker);
            if (Files.isRegularFile(archivedMarker)) {
                TemplateMarkerLookup.rememberMarker(plugin, archiveName,
                        dimension.marker, archivedMarker);
            }
            sender.sendMessage(ChatColor.GREEN + "Respaldo estable creado: "
                    + archiveName + ". Las partidas seguirán usando esa copia.");
        }

        Properties candidate = new Properties();
        candidate.setProperty("revision", revision);
        candidate.setProperty("dimension", dimension.id);
        candidate.setProperty("world", baseWorldName);
        candidate.setProperty("status", RevisionStatus.BUILDING.name());
        candidate.setProperty("createdAt", Long.toString(System.currentTimeMillis()));
        candidate.setProperty("baseRevision", activeRevision);
        save(revisionFile(dimension, revision), candidate);

        state.setProperty("workingRevision", revision);
        state.setProperty("workingWorld", baseWorldName);
        save(stateFile(plugin, dimension), state);
        prune(dimension);
        return BeginResult.ok(revision, baseWorldName,
                "Revisión limpia preparada. La estable no será reemplazada hasta promote.");
    }

    public synchronized void markOverworldReady(String pluginVersion, Path marker) {
        Dimension dimension = Dimension.OVERWORLD;
        String working = workingRevision();
        if (working.isBlank()) return;
        Properties candidate = revision(dimension, working);
        candidate.setProperty("status", RevisionStatus.READY.name());
        candidate.setProperty("pluginVersion", pluginVersion);
        candidate.setProperty("completedAt", Long.toString(System.currentTimeMillis()));
        if (marker != null) candidate.setProperty("marker", marker.toAbsolutePath().normalize().toString());
        save(revisionFile(dimension, working), candidate);
    }

    public synchronized void markOverworldFailed(String message) {
        String working = workingRevision();
        if (working.isBlank()) return;
        Properties candidate = revision(Dimension.OVERWORLD, working);
        candidate.setProperty("status", RevisionStatus.FAILED.name());
        candidate.setProperty("lastError", message == null ? "desconocido" : message);
        candidate.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
        save(revisionFile(Dimension.OVERWORLD, working), candidate);
    }

    public synchronized boolean forceOverworldCustomStage(
            CommandSender sender, String revisionId, String baseWorldName) {
        String requested = normalizeRevision(revisionId);
        String working = workingRevision();
        if (requested == null || !requested.equals(working)) {
            sender.sendMessage(ChatColor.RED + "Solo puede forzarse la revisión de trabajo actual: "
                    + (working.isBlank() ? "(ninguna)" : working) + ".");
            return false;
        }
        if (!Files.isDirectory(configuredFolder(baseWorldName))) {
            sender.sendMessage(ChatColor.RED + "La carpeta de trabajo no existe.");
            return false;
        }
        Properties candidate = revision(Dimension.OVERWORLD, working);
        candidate.setProperty("status", RevisionStatus.BUILDING.name());
        candidate.setProperty("forcedStage", "custom");
        candidate.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
        save(revisionFile(Dimension.OVERWORLD, working), candidate);
        return true;
    }

    public synchronized boolean promoteOverworld(
            CommandSender sender, String revisionId, String expectedVersion) {
        Dimension dimension = Dimension.OVERWORLD;
        String requested = normalizeRevision(revisionId);
        if (requested == null) {
            sender.sendMessage(ChatColor.RED + "Identificador de revisión inválido.");
            return false;
        }
        Properties candidate = revision(dimension, requested);
        if (candidate.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No existe la revisión '" + requested + "'.");
            return false;
        }
        if (!RevisionStatus.READY.name().equals(candidate.getProperty("status"))) {
            sender.sendMessage(ChatColor.RED + "La revisión debe estar READY antes de promoverse.");
            return false;
        }
        String candidateWorld = candidate.getProperty("world", "").trim();
        Path marker = configuredFolder(candidateWorld).resolve(dimension.marker);
        if (!Files.isRegularFile(marker)) {
            sender.sendMessage(ChatColor.RED + "Falta el marcador COMPLETE de la revisión.");
            return false;
        }
        String markerVersion = TemplateAuditUtil.readProperty(marker, "version");
        if (expectedVersion != null && !expectedVersion.equals(markerVersion)) {
            sender.sendMessage(ChatColor.RED + "El marcador pertenece a " + markerVersion
                    + " y se esperaba " + expectedVersion + ".");
            return false;
        }

        Properties state = state(dimension);
        String previousId = state.getProperty("activeRevision", "").trim();
        if (!previousId.isEmpty() && !previousId.equals(requested)) {
            Properties previous = revision(dimension, previousId);
            previous.setProperty("status", RevisionStatus.SUPERSEDED.name());
            previous.setProperty("supersededAt", Long.toString(System.currentTimeMillis()));
            save(revisionFile(dimension, previousId), previous);
        }

        candidate.setProperty("status", RevisionStatus.STABLE.name());
        candidate.setProperty("promotedAt", Long.toString(System.currentTimeMillis()));
        save(revisionFile(dimension, requested), candidate);
        state.setProperty("activeRevision", requested);
        state.setProperty("activeWorld", candidateWorld);
        state.remove("workingRevision");
        state.remove("workingWorld");
        save(stateFile(plugin, dimension), state);
        prune(dimension);
        sender.sendMessage(ChatColor.GREEN + "Revisión promovida: " + requested
                + ". Las arenas nuevas copiarán " + candidateWorld + ".");
        return true;
    }

    public synchronized boolean rollbackOverworld(CommandSender sender, String revisionId) {
        Dimension dimension = Dimension.OVERWORLD;
        String requested = normalizeRevision(revisionId);
        if (requested == null) {
            sender.sendMessage(ChatColor.RED + "Debes indicar una revisión válida.");
            return false;
        }
        Properties target = revision(dimension, requested);
        if (target.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No existe la revisión '" + requested + "'.");
            return false;
        }
        String worldName = target.getProperty("world", "").trim();
        if (!Files.isRegularFile(configuredFolder(worldName).resolve(dimension.marker))) {
            sender.sendMessage(ChatColor.RED + "La revisión no conserva una plantilla COMPLETE.");
            return false;
        }

        Properties state = state(dimension);
        String currentId = state.getProperty("activeRevision", "").trim();
        if (!currentId.isBlank() && !currentId.equals(requested)) {
            Properties current = revision(dimension, currentId);
            current.setProperty("status", RevisionStatus.SUPERSEDED.name());
            save(revisionFile(dimension, currentId), current);
        }
        target.setProperty("status", RevisionStatus.STABLE.name());
        target.setProperty("rollbackAt", Long.toString(System.currentTimeMillis()));
        save(revisionFile(dimension, requested), target);
        state.setProperty("activeRevision", requested);
        state.setProperty("activeWorld", worldName);
        save(stateFile(plugin, dimension), state);
        sender.sendMessage(ChatColor.GREEN + "Rollback completado. La revisión estable vuelve a ser "
                + requested + ".");
        return true;
    }

    public synchronized void discardWorkingAfterReset() {
        Dimension dimension = Dimension.OVERWORLD;
        Properties state = state(dimension);
        String working = state.getProperty("workingRevision", "").trim();
        if (!working.isBlank()) {
            Properties candidate = revision(dimension, working);
            candidate.setProperty("status", RevisionStatus.FAILED.name());
            candidate.setProperty("lastError", "descartada mediante reset");
            save(revisionFile(dimension, working), candidate);
        }
        state.remove("workingRevision");
        state.remove("workingWorld");
        save(stateFile(plugin, dimension), state);
        prune(dimension);
    }

    public List<String> overworldAuditLines(String baseWorldName) {
        bootstrap(Dimension.OVERWORLD, baseWorldName, "legacy");
        Properties state = state(Dimension.OVERWORLD);
        List<String> lines = new ArrayList<>();
        lines.add("revisión-estable=" + value(state, "activeRevision", "ausente"));
        lines.add("mundo-estable=" + value(state, "activeWorld", baseWorldName));
        lines.add("revisión-en-trabajo=" + value(state, "workingRevision", "ninguna"));
        lines.add("mundo-en-trabajo=" + value(state, "workingWorld", "ninguno"));
        lines.add("retención=" + Math.max(1, plugin.getConfig().getInt(
                "template-worlds.revisions.keep-latest", 3)) + " revisiones");
        lines.add("revisiones-registradas=" + listRevisionFiles(Dimension.OVERWORLD).size());
        return lines;
    }

    public List<String> overworldRevisionLines() {
        List<Properties> revisions = loadAll(Dimension.OVERWORLD);
        revisions.sort(Comparator.comparingLong(TemplateRevisionManager::createdAt).reversed());
        List<String> lines = new ArrayList<>();
        for (Properties revision : revisions) {
            String id = revision.getProperty("revision", "?");
            String status = revision.getProperty("status", "?");
            String world = revision.getProperty("world", "?");
            lines.add(id + " · " + status.toLowerCase(Locale.ROOT) + " · " + world);
        }
        if (lines.isEmpty()) lines.add("(sin revisiones registradas)");
        return lines;
    }

    private void bootstrap(Dimension dimension, String baseWorldName, String legacyRevisionHint) {
        Properties state = state(dimension);
        if (!state.getProperty("activeRevision", "").isBlank()) return;

        Path base = configuredFolder(baseWorldName);
        Path marker = base.resolve(dimension.marker);
        if (!Files.isRegularFile(marker)) return;

        String markerVersion = TemplateAuditUtil.readProperty(marker, "version");
        String revision = normalizeRevision(markerVersion == null
                ? legacyRevisionHint : markerVersion);
        if (revision == null) revision = "legacy";

        Properties legacy = new Properties();
        legacy.setProperty("revision", revision);
        legacy.setProperty("dimension", dimension.id);
        legacy.setProperty("world", baseWorldName);
        legacy.setProperty("status", RevisionStatus.STABLE.name());
        legacy.setProperty("createdAt", Long.toString(Files.exists(marker)
                ? marker.toFile().lastModified() : System.currentTimeMillis()));
        legacy.setProperty("pluginVersion", markerVersion == null ? "desconocida" : markerVersion);
        save(revisionFile(dimension, revision), legacy);

        state.setProperty("activeRevision", revision);
        state.setProperty("activeWorld", baseWorldName);
        save(stateFile(plugin, dimension), state);
    }

    private void prune(Dimension dimension) {
        int keep = Math.max(1, plugin.getConfig().getInt(
                "template-worlds.revisions.keep-latest", 3));
        Properties state = state(dimension);
        String active = state.getProperty("activeRevision", "");
        String working = state.getProperty("workingRevision", "");

        List<Properties> revisions = loadAll(dimension);
        revisions.sort(Comparator.comparingLong(TemplateRevisionManager::createdAt).reversed());
        List<String> retained = new ArrayList<>();
        if (!active.isBlank()) retained.add(active);
        if (!working.isBlank() && !retained.contains(working)) retained.add(working);
        for (Properties revision : revisions) {
            String id = revision.getProperty("revision", "");
            if (retained.contains(id)) continue;
            if (retained.size() < keep) retained.add(id);
        }

        for (Properties revision : revisions) {
            String id = revision.getProperty("revision", "");
            if (id.isBlank() || retained.contains(id)) continue;
            String worldName = revision.getProperty("world", "");
            if (worldName.isBlank() || Bukkit.getWorld(worldName) != null) continue;
            Path folder = configuredFolder(worldName);
            Path container = worldContainer();
            if (!folder.startsWith(container) || folder.equals(container)) continue;
            try {
                deleteRecursively(folder);
                Files.deleteIfExists(revisionFile(dimension, id));
                TemplateMarkerLookup.forgetWorld(plugin, worldName);
                TemplateMarkerLookup.forgetMarker(plugin, worldName, dimension.marker);
            } catch (IOException error) {
                plugin.getLogger().warning("No se pudo depurar la revisión antigua "
                        + id + ": " + error.getMessage());
            }
        }
    }

    private List<Properties> loadAll(Dimension dimension) {
        List<Properties> result = new ArrayList<>();
        for (Path file : listRevisionFiles(dimension)) {
            Properties properties = load(file);
            if (!properties.isEmpty()) result.add(properties);
        }
        return result;
    }

    private List<Path> listRevisionFiles(Dimension dimension) {
        Path folder = dimensionFolder(dimension).resolve("revisions");
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> stream = Files.list(folder)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private Properties state(Dimension dimension) {
        return load(stateFile(plugin, dimension));
    }

    private Properties revision(Dimension dimension, String revision) {
        return load(revisionFile(dimension, revision));
    }

    private Path revisionFile(Dimension dimension, String revision) {
        return dimensionFolder(dimension).resolve("revisions")
                .resolve(revision + ".properties");
    }

    private Path dimensionFolder(Dimension dimension) {
        return plugin.getDataFolder().toPath().resolve("template-revisions")
                .resolve(dimension.id);
    }

    private static Path stateFile(JavaPlugin plugin, Dimension dimension) {
        return plugin.getDataFolder().toPath().resolve("template-revisions")
                .resolve(dimension.id).resolve(STATE_FILE);
    }

    private Path configuredFolder(String worldName) {
        return worldContainer().resolve(worldName).toAbsolutePath().normalize();
    }

    private Path worldContainer() {
        return plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    private String availableArchiveName(String baseWorldName, String revision) {
        String stem = baseWorldName + "__" + safeWorldSuffix(revision);
        String candidate = stem;
        int index = 2;
        while (Files.exists(configuredFolder(candidate)) || Bukkit.getWorld(candidate) != null) {
            candidate = stem + "-" + index++;
        }
        return candidate;
    }

    private static String normalizeRevision(String input) {
        if (input == null) return null;
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 80) return null;
        if (!normalized.matches("[a-z0-9][a-z0-9._-]*")) return null;
        return normalized;
    }

    private static String safeWorldSuffix(String revision) {
        String normalized = Optional.ofNullable(normalizeRevision(revision)).orElse("stable");
        return normalized.replace('.', '_');
    }

    private static long createdAt(Properties properties) {
        try {
            return Long.parseLong(properties.getProperty("createdAt", "0"));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static Properties load(Path file) {
        Properties properties = new Properties();
        if (file == null || !Files.isRegularFile(file)) return properties;
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException ignored) {
            // El comando audit mostrará el registro como ausente.
        }
        return properties;
    }

    private static void save(Path file, Properties properties) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "ArlightBingo template revision");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("No se pudo guardar " + file + ": "
                    + error.getMessage(), error);
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void deleteRecursively(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }
}
