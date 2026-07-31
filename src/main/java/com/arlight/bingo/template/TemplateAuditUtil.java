package com.arlight.bingo.template;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Diagnóstico persistente compartido por las tres plantillas. */
final class TemplateAuditUtil {

    private TemplateAuditUtil() {
    }

    static List<String> audit(JavaPlugin plugin, String worldName, String completeMarker,
                              String inProgressMarker, String expectedVersion,
                              World.Environment expectedEnvironment, long expectedSeed) {
        List<String> lines = new ArrayList<>();
        Optional<Path> complete = TemplateMarkerLookup.findMarker(plugin, worldName, completeMarker);
        Optional<Path> progress = TemplateMarkerLookup.findMarker(plugin, worldName, inProgressMarker);
        Path folder = complete.map(Path::getParent)
                .or(() -> progress.map(Path::getParent))
                .orElseGet(() -> TemplateMarkerLookup.activeFolder(plugin, worldName));
        World world = Bukkit.getWorld(worldName);

        lines.add("mundo=" + worldName);
        lines.add("carpeta-activa=" + folder);
        lines.add("carpeta-registrada="
                + TemplateMarkerLookup.registeredFolderDescription(plugin, worldName));
        lines.add("cargado=" + (world != null));
        lines.add("carpeta-existe=" + Files.isDirectory(folder));
        lines.add("marker-complete=" + complete
                .map(path -> markerState(path, expectedVersion) + " @ " + path)
                .orElse("ausente"));
        lines.add("marker-progreso=" + progress
                .map(path -> markerState(path, expectedVersion) + " @ " + path)
                .orElse("ausente"));
        lines.add("regiones=" + countRegionFiles(folder));

        if (world != null) {
            lines.add("entorno=" + world.getEnvironment().name().toLowerCase(Locale.ROOT)
                    + " (esperado " + expectedEnvironment.name().toLowerCase(Locale.ROOT) + ")");
            lines.add("seed=" + world.getSeed() + " (esperada " + expectedSeed + ")");
            lines.add("border=" + Math.round(world.getWorldBorder().getSize()));
            lines.add("chunks-cargados=" + world.getLoadedChunks().length);
            lines.add("autosave=" + world.isAutoSave());
        }
        return lines;
    }

    static String markerState(Path marker, String expectedVersion) {
        if (!Files.isRegularFile(marker)) return "ausente";
        try {
            String text = Files.readString(marker);
            String version = readProperty(text, "version");
            String state = readProperty(text, "state");
            StringBuilder out = new StringBuilder("presente");
            if (version != null) out.append(" version=").append(version);
            if (state != null) out.append(" state=").append(state);
            if (expectedVersion != null && version != null && !expectedVersion.equals(version)) {
                out.append(" (anterior; esperado ").append(expectedVersion).append(')');
            }
            return out.toString();
        } catch (IOException error) {
            return "ilegible: " + error.getMessage();
        }
    }

    static String readProperty(Path file, String key) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return readProperty(Files.readString(file), key);
        } catch (IOException ignored) {
            return null;
        }
    }

    static String missingProperties(Path marker, String... keys) {
        if (marker == null || !Files.isRegularFile(marker)) return String.join(",", keys);
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            String value = readProperty(marker, key);
            if (value == null || value.isBlank()) missing.add(key);
        }
        return missing.isEmpty() ? "ninguna" : String.join(",", missing);
    }

    static boolean hasAnyMarker(Path folder, String completeMarker, String inProgressMarker) {
        return Files.isRegularFile(folder.resolve(completeMarker))
                || Files.isRegularFile(folder.resolve(inProgressMarker));
    }

    static long countRegionFiles(Path folder) {
        if (!Files.isDirectory(folder)) return 0L;
        long count = 0L;
        for (String child : List.of("region", "DIM-1/region", "DIM1/region")) {
            Path region = folder.resolve(child);
            if (!Files.isDirectory(region)) continue;
            try (Stream<Path> stream = Files.list(region)) {
                count += stream.filter(path -> path.getFileName().toString().endsWith(".mca")).count();
            } catch (IOException ignored) {
                // Diagnóstico best-effort.
            }
        }
        return count;
    }

    private static String readProperty(String text, String key) {
        String prefix = key + "=";
        for (String line : text.split("\\R")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return null;
    }
}
