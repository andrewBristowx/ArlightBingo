package com.arlight.bingo.template;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Resuelve y conserva la carpeta real de cada plantilla.
 *
 * Arclight/Multiverse pueden exponer una ruta distinta mientras el mundo está
 * cargado y después del reinicio Bukkit ya no conoce esa ruta. La 1.34.1 guarda
 * un registro fuera del mundo y una copia de seguridad de los marcadores para
 * que audit/status no vuelvan a IDLE y para poder restaurar un marcador perdido
 * sin regenerar chunks ni estructuras.
 */
public final class TemplateMarkerLookup {

    private static final int DISCOVERY_DEPTH = 5;

    private TemplateMarkerLookup() {
    }

    static boolean hasVersion(JavaPlugin plugin, String worldName,
                              String markerName, String expectedVersion) {
        Optional<Path> marker = findMarker(plugin, worldName, markerName, true);
        if (marker.isEmpty()) return false;
        try {
            return Files.readString(marker.get()).contains("version=" + expectedVersion);
        } catch (IOException ignored) {
            return false;
        }
    }

    /** Encuentra el marcador real y, si hace falta, lo restaura desde el backup. */
    static Optional<Path> findMarker(JavaPlugin plugin, String worldName, String markerName) {
        return findMarker(plugin, worldName, markerName, true);
    }

    static Optional<Path> findMarker(JavaPlugin plugin, String worldName,
                                     String markerName, boolean restoreBackup) {
        for (Path folder : candidateFolders(plugin, worldName)) {
            Path marker = folder.resolve(markerName);
            if (Files.isRegularFile(marker)) {
                rememberMarker(plugin, worldName, markerName, marker);
                return Optional.of(marker);
            }
        }

        // Nunca recorrer el árbol completo de mundos desde el hilo principal.
        // Los eventos de carga de chunks pueden entrar aquí cientos de veces y un Files.find
        // recursivo bloquea el tick de Arclight. Las rutas cargada, registrada y configurada
        // son las únicas fuentes permitidas durante ejecución normal.
        if (!Bukkit.isPrimaryThread()) {
            Optional<Path> discovered = discoverMarker(plugin, worldName, markerName);
            if (discovered.isPresent()) {
                Path marker = discovered.get();
                rememberMarker(plugin, worldName, markerName, marker);
                return discovered;
            }
        }

        if (restoreBackup) {
            Path backup = backupMarkerPath(plugin, worldName, markerName);
            Path folder = activeFolder(plugin, worldName);
            if (Files.isRegularFile(backup) && Files.isDirectory(folder)) {
                Path restored = folder.resolve(markerName);
                try {
                    Files.copy(backup, restored, StandardCopyOption.REPLACE_EXISTING);
                    plugin.getLogger().warning("Se restauró el marcador " + markerName
                            + " de la plantilla " + worldName + " desde el registro persistente.");
                    rememberWorldFolder(plugin, worldName, folder);
                    return Optional.of(restored);
                } catch (IOException error) {
                    plugin.getLogger().warning("No se pudo restaurar " + markerName + ": "
                            + error.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    public static Path activeFolder(JavaPlugin plugin, String worldName) {
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            Path folder = loaded.getWorldFolder().toPath().toAbsolutePath().normalize();
            rememberWorldFolder(plugin, worldName, folder);
            return folder;
        }

        Path registered = registeredFolder(plugin, worldName);
        if (registered != null && Files.isDirectory(registered)) return registered;

        Path configured = configuredFolder(plugin, worldName);
        if (Files.isDirectory(configured)) {
            rememberWorldFolder(plugin, worldName, configured);
            return configured;
        }

        // No hacer Files.find recursivo aquí. activeFolder se usa desde el hilo del
        // servidor y debe ser O(1). El registro persistente cubre layouts no estándar.
        return configured;
    }

    static Path markerPathForWrite(JavaPlugin plugin, String worldName, String markerName) {
        return activeFolder(plugin, worldName).resolve(markerName);
    }

    static void rememberWorld(JavaPlugin plugin, World world) {
        if (world == null) return;
        rememberWorldFolder(plugin, world.getName(),
                world.getWorldFolder().toPath().toAbsolutePath().normalize());
    }

    static void rememberMarker(JavaPlugin plugin, String worldName,
                               String markerName, Path marker) {
        if (marker == null || !Files.isRegularFile(marker)) return;
        Path normalized = marker.toAbsolutePath().normalize();
        rememberWorldFolder(plugin, worldName, normalized.getParent());
        try {
            Files.createDirectories(registryDirectory(plugin).resolve("markers"));
            Files.copy(normalized, backupMarkerPath(plugin, worldName, markerName),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo respaldar " + markerName + ": "
                    + error.getMessage());
        }
    }

    static boolean ensureProperties(JavaPlugin plugin, String worldName, String markerName,
                                    Map<String, String> required) {
        Optional<Path> marker = findMarker(plugin, worldName, markerName);
        if (marker.isEmpty()) return false;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(marker.get())) {
            properties.load(input);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo leer " + markerName + " para repararlo: "
                    + error.getMessage());
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, String> entry : required.entrySet()) {
            String current = properties.getProperty(entry.getKey());
            if (current == null || current.isBlank()) {
                properties.setProperty(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (!changed) return true;
        try (OutputStream output = Files.newOutputStream(marker.get(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(output, "ArlightBingo repaired campaign anchors");
            rememberMarker(plugin, worldName, markerName, marker.get());
            plugin.getLogger().warning("Se repararon propiedades faltantes en " + markerName
                    + " de " + worldName + ".");
            return true;
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo guardar la reparación de " + markerName + ": "
                    + error.getMessage());
            return false;
        }
    }

    static void forgetMarker(JavaPlugin plugin, String worldName, String markerName) {
        try {
            Files.deleteIfExists(backupMarkerPath(plugin, worldName, markerName));
        } catch (IOException ignored) {
            // El archivo del mundo sigue siendo la fuente principal.
        }
    }

    static void forgetWorld(JavaPlugin plugin, String worldName) {
        try {
            Files.deleteIfExists(registryFile(plugin, worldName));
        } catch (IOException ignored) {
            // El siguiente audit volverá a descubrir la carpeta si todavía existe.
        }
    }

    static String checkedFolders(JavaPlugin plugin, String worldName) {
        return candidateFolders(plugin, worldName).stream()
                .map(Path::toString)
                .reduce((left, right) -> left + " | " + right)
                .orElse("(ninguna)");
    }

    static String registeredFolderDescription(JavaPlugin plugin, String worldName) {
        Path folder = registeredFolder(plugin, worldName);
        return folder == null ? "ausente" : folder.toString();
    }

    static Set<Path> candidateFolders(JavaPlugin plugin, String worldName) {
        Set<Path> folders = new LinkedHashSet<>();
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            folders.add(loaded.getWorldFolder().toPath().toAbsolutePath().normalize());
        }
        Path registered = registeredFolder(plugin, worldName);
        if (registered != null) folders.add(registered);
        folders.add(configuredFolder(plugin, worldName));
        // Deliberadamente sin búsqueda recursiva: candidateFolders puede ejecutarse durante
        // ChunkLoadEvent y debe permanecer instantáneo.
        return folders;
    }

    private static Optional<Path> discoverMarker(JavaPlugin plugin, String worldName,
                                                 String markerName) {
        Path root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return Optional.empty();
        String normalizedWorld = worldName.toLowerCase();
        try (Stream<Path> stream = Files.find(root, DISCOVERY_DEPTH,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equals(markerName)
                        && !isRuntimeCloneFolder(path.getParent()))) {
            return stream
                    .sorted(Comparator.comparingInt(path -> markerScore(path, normalizedWorld)))
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static int markerScore(Path marker, String normalizedWorld) {
        int score = 100;
        Path parent = marker.getParent();
        if (parent != null && parent.getFileName() != null
                && parent.getFileName().toString().equalsIgnoreCase(normalizedWorld)) score -= 80;
        for (Path part : marker) {
            if (part.toString().equalsIgnoreCase(normalizedWorld)) score -= 40;
            if (part.toString().startsWith("bingo_arena")) score += 30;
        }
        return score;
    }

    private static Optional<Path> discoverNamedFolder(JavaPlugin plugin, String worldName) {
        Path root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return Optional.empty();
        try (Stream<Path> stream = Files.find(root, DISCOVERY_DEPTH,
                (path, attributes) -> attributes.isDirectory()
                        && path.getFileName() != null
                        && path.getFileName().toString().equalsIgnoreCase(worldName))) {
            return stream.findFirst().map(path -> path.toAbsolutePath().normalize());
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isRuntimeCloneFolder(Path folder) {
        if (folder == null) return false;
        return Files.isRegularFile(folder.resolve(".arlightbingo_arena"))
                || Files.isRegularFile(folder.resolve("arlight-template-runtime.properties"));
    }

    private static void rememberWorldFolder(JavaPlugin plugin, String worldName, Path folder) {
        if (folder == null) return;
        Path normalized = folder.toAbsolutePath().normalize();
        Properties properties = new Properties();
        properties.setProperty("world", worldName);
        properties.setProperty("folder", normalized.toString());
        properties.setProperty("updated", Long.toString(System.currentTimeMillis()));
        try {
            Files.createDirectories(registryDirectory(plugin));
            try (OutputStream output = Files.newOutputStream(registryFile(plugin, worldName),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "ArlightBingo persistent template folder");
            }
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo persistir la ruta de " + worldName + ": "
                    + error.getMessage());
        }
    }

    private static Path registeredFolder(JavaPlugin plugin, String worldName) {
        Path file = registryFile(plugin, worldName);
        if (!Files.isRegularFile(file)) return null;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            String raw = properties.getProperty("folder");
            if (raw == null || raw.isBlank()) return null;
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Path configuredFolder(JavaPlugin plugin, String worldName) {
        return plugin.getServer().getWorldContainer().toPath()
                .resolve(worldName).toAbsolutePath().normalize();
    }

    private static Path registryDirectory(JavaPlugin plugin) {
        return plugin.getDataFolder().toPath().resolve("template-registry");
    }

    private static Path registryFile(JavaPlugin plugin, String worldName) {
        return registryDirectory(plugin).resolve(safe(worldName) + ".properties");
    }

    private static Path backupMarkerPath(JavaPlugin plugin, String worldName, String markerName) {
        return registryDirectory(plugin).resolve("markers")
                .resolve(safe(worldName) + "__" + safe(markerName));
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
