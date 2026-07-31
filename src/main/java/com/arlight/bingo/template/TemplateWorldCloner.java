package com.arlight.bingo.template;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Clonador en frío de plantillas.
 *
 * Arclight puede guardar un mundo Bukkit de dos formas diferentes:
 *  - como mundo autónomo: <world-container>/<nombre>/region
 *  - como dimensión personalizada: <raíz>/dimensions/<namespace>/<nombre>/region
 *
 * Las versiones anteriores asumían que siempre existía una carpeta autónoma y podían
 * copiar region/ del nivel raíz en vez del almacenamiento real de la dimensión
 * minecraft:bingo_template_nether o minecraft:bingo_template_end. Esta versión resuelve
 * primero la carpeta exacta de chunks y crea el destino en el mismo tipo de almacenamiento,
 * sin cargar el mundo destino hasta que la copia completa fue movida atómicamente.
 */
public final class TemplateWorldCloner {

    public static final String RUNTIME_MARKER = "arlight-template-runtime.properties";
    public static final String RUNTIME_VERSION = "1.36.5-arclight-runtime-dim-layout-1";
    private static final int STORAGE_DISCOVERY_DEPTH = 9;
    private static final List<String> CRITICAL_STORAGE_DIRECTORIES =
            List.of("region", "entities", "poi");

    private TemplateWorldCloner() { }

    public enum SnapshotMode {
        STANDALONE_WORLD,
        CUSTOM_DIMENSION_DEDICATED_WORLD,
        CUSTOM_DIMENSION_SHARED_LEVEL,
        LEGACY_DIMENSION_FLATTEN
    }

    private record SourceLayout(Path exposedRoot, Path metadataRoot, Path dimensionRoot,
                                SnapshotMode mode) { }

    private record TargetLayout(Path root, Path storage, Path storageRelativeToRoot) { }

    public record CloneReport(Path sourceStorage, Path targetStorage, SnapshotMode mode,
                              int files, long bytes) {
        public String summary() {
            return mode + ": datos críticos verificados: " + files
                    + " archivos / " + bytes + " bytes; "
                    + sourceStorage + " -> " + targetStorage;
        }
    }

    /** Compatibilidad con llamadas antiguas. */
    public static void copy(Path source, Path target, String sourceName,
                            World.Environment environment) throws IOException {
        Path container = target.toAbsolutePath().normalize().getParent();
        if (container == null) throw new IOException("El destino no tiene carpeta padre: " + target);
        CloneReport report = copyColdSnapshot(source, container, sourceName,
                target.getFileName().toString(), environment);
        if (!report.targetStorage().equals(target.toAbsolutePath().normalize())) {
            throw new IOException("La copia se resolvió en " + report.targetStorage()
                    + " y no en el destino heredado " + target + ".");
        }
    }

    /** Compatibilidad con la firma 1.36.1; exactWorldFolder ya no fuerza region/ incorrecta. */
    public static void copy(Path source, Path target, String sourceName,
                            World.Environment environment, boolean exactWorldFolder) throws IOException {
        copy(source, target, sourceName, environment);
    }

    /**
     * Copia la carpeta real de almacenamiento a un staging oculto y sólo después la mueve
     * al nombre definitivo. El destino nunca existe parcialmente y jamás se carga antes
     * de completar y verificar la instantánea.
     */
    public static CloneReport copyColdSnapshot(Path exposedSourceFolder, Path worldContainer,
                                               String sourceName, String targetName,
                                               World.Environment environment) throws IOException {
        if (worldContainer == null) throw new IOException("No se recibió worldContainer.");
        Path container = worldContainer.toAbsolutePath().normalize();
        SourceLayout layout = resolveSourceLayout(exposedSourceFolder, sourceName, environment);
        TargetLayout target = resolveTargetLayout(layout, container, sourceName, targetName, environment);
        if (Files.exists(target.root())) {
            throw new IOException("La carpeta destino todavía existe: " + target.root() + ".");
        }
        if (!target.root().equals(target.storage()) && Files.exists(target.storage())) {
            throw new IOException("El almacenamiento dimensional destino todavía existe: "
                    + target.storage() + ".");
        }

        Path parent = target.root().getParent();
        if (parent == null) throw new IOException("El destino no tiene carpeta padre: " + target.root());
        Files.createDirectories(parent);
        Path stagingRoot = parent.resolve("." + target.root().getFileName()
                + ".arlight-stage-" + UUID.randomUUID());
        deleteRecursively(stagingRoot);
        Path stagingStorage = target.storageRelativeToRoot() == null
                ? stagingRoot : stagingRoot.resolve(target.storageRelativeToRoot());

        SnapshotManifest expected;
        try {
            // El manifiesto se calcula sólo sobre los datos que definen los chunks.
            // Arclight/Sable puede crear marcadores, level.dat_old u otros metadatos
            // inocuos mientras se vuelve a cargar la plantilla. Esos archivos no deben
            // invalidar una copia cuyos MCA de region/, entities/ y poi/ son idénticos.
            expected = criticalSnapshotManifest(layout.dimensionRoot());
            if (expected.files().isEmpty()) {
                throw new IOException("La plantilla no contiene datos críticos en region/, entities/ o poi/.");
            }

            // El origen puede ser autónomo, una dimensión personalizada o un layout legado,
            // pero el destino debe seguir SIEMPRE el layout que Bukkit/Arclight usa al cargar
            // WorldCreator(name).environment(...): Overworld en la raíz, Nether en DIM-1 y
            // End en DIM1. La 1.36.3 copiaba correctamente los MCA, pero dejaba Nether/End
            // en la raíz del mundo; Arclight después cargaba DIM-1/DIM1 y veía terreno vanilla.
            Files.createDirectories(stagingRoot);
            if (stagingRoot.equals(stagingStorage)
                    && layout.metadataRoot().equals(layout.dimensionRoot())) {
                copyWholeTree(layout.dimensionRoot(), stagingRoot);
            } else {
                copyMetadata(layout.metadataRoot(), stagingRoot);
                copyCriticalStorageDirectories(layout.dimensionRoot(), stagingStorage);
            }
            copyTemplateMarker(layout, stagingStorage, environment);
            copyTemplateMarker(layout, stagingRoot, environment);

            SnapshotManifest actual = criticalSnapshotManifest(stagingStorage);
            if (!expected.equals(actual)) {
                throw new IOException("Los datos críticos de la instantánea no coinciden con la plantilla. "
                        + "Se compararon únicamente region/, entities/ y poi/. Esperado: "
                        + expected.summary() + "; copiado: " + actual.summary());
            }
            verifyChunkStorageExact(layout.dimensionRoot(), stagingStorage);

            Files.deleteIfExists(stagingStorage.resolve("uid.dat"));
            Files.deleteIfExists(stagingStorage.resolve("session.lock"));
            Files.writeString(stagingStorage.resolve(".arlightbingo_arena"), "template-runtime\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            if (!stagingRoot.equals(stagingStorage)) {
                Files.writeString(stagingRoot.resolve(".arlightbingo_arena"),
                        "template-runtime-root\n", StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
            Files.writeString(stagingStorage.resolve(RUNTIME_MARKER),
                    "version=" + RUNTIME_VERSION
                            + "\nsource=" + sourceName
                            + "\ntarget=" + targetName
                            + "\nenvironment=" + (environment == null ? "UNKNOWN" : environment.name())
                            + "\nmode=" + layout.mode().name()
                            + "\ncompleted=true"
                            + "\nsource-exposed=" + sanitizePath(layout.exposedRoot())
                            + "\nsource-metadata=" + sanitizePath(layout.metadataRoot())
                            + "\nsource-storage=" + sanitizePath(layout.dimensionRoot())
                            + "\ntarget-root=" + sanitizePath(target.root())
                            + "\ntarget-storage=" + sanitizePath(target.storage())
                            + "\nverification-scope=region,entities,poi"
                            + "\ncritical-files=" + expected.files().size()
                            + "\ncritical-bytes=" + expected.totalBytes() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            moveCompletedSnapshot(stagingRoot, target.root());
            return new CloneReport(layout.dimensionRoot(), target.storage(), layout.mode(),
                    expected.files().size(), expected.totalBytes());
        } catch (Throwable error) {
            try { deleteRecursively(stagingRoot); } catch (IOException ignored) { }
            if (error instanceof IOException io) throw io;
            throw new IOException("Falló el clonado en frío: " + error.getMessage(), error);
        }
    }

    private static SourceLayout resolveSourceLayout(Path exposedSourceFolder, String sourceName,
                                                    World.Environment environment) throws IOException {
        if (exposedSourceFolder == null || !Files.isDirectory(exposedSourceFolder)) {
            throw new IOException("No existe la carpeta expuesta de la plantilla: "
                    + exposedSourceFolder + ".");
        }
        Path exposed = exposedSourceFolder.toAbsolutePath().normalize();
        Path metadataRoot = findMetadataRoot(exposed);
        if (metadataRoot == null) {
            // Algunas implementaciones exponen directamente el almacenamiento de una
            // dimensión personalizada. En ese caso se busca level.dat hasta nueve niveles.
            metadataRoot = findMetadataRootDeep(exposed);
        }
        if (metadataRoot == null) {
            throw new IOException("No se encontró level.dat para la plantilla desde " + exposed + ".");
        }

        // Prioridad absoluta: dimensions/<namespace>/<sourceName>/region. Los logs de
        // Arclight muestran claves como minecraft:bingo_template_nether y minecraft:
        // bingo_template_end; sus chunks viven aquí, no necesariamente en root/region.
        Path custom = findNamedCustomDimensionStorage(exposed, metadataRoot, sourceName);
        if (custom != null) {
            boolean dedicated = metadataRoot.getFileName() != null
                    && metadataRoot.getFileName().toString().equalsIgnoreCase(sourceName);
            return new SourceLayout(exposed, metadataRoot, custom,
                    dedicated ? SnapshotMode.CUSTOM_DIMENSION_DEDICATED_WORLD
                            : SnapshotMode.CUSTOM_DIMENSION_SHARED_LEVEL);
        }

        if (Files.isRegularFile(exposed.resolve("level.dat"))
                && Files.isDirectory(exposed.resolve("region"))) {
            return new SourceLayout(exposed, exposed, exposed, SnapshotMode.STANDALONE_WORLD);
        }
        if (Files.isRegularFile(metadataRoot.resolve("level.dat"))
                && metadataRoot.getFileName() != null
                && metadataRoot.getFileName().toString().equalsIgnoreCase(sourceName)
                && Files.isDirectory(metadataRoot.resolve("region"))) {
            return new SourceLayout(exposed, metadataRoot, metadataRoot,
                    SnapshotMode.STANDALONE_WORLD);
        }

        Path dimension = findLegacyDimensionRoot(exposed, metadataRoot, environment);
        if (dimension == null || !Files.isDirectory(dimension.resolve("region"))) {
            throw new IOException("No se encontró el almacenamiento real de " + sourceName
                    + " (" + environment + ") desde " + exposed + ".");
        }
        return new SourceLayout(exposed, metadataRoot, dimension,
                SnapshotMode.LEGACY_DIMENSION_FLATTEN);
    }

    private static Path findNamedCustomDimensionStorage(Path exposed, Path metadataRoot,
                                                        String sourceName) throws IOException {
        if (isInsideDimensionsTree(exposed)
                && exposed.getFileName() != null
                && exposed.getFileName().toString().equalsIgnoreCase(sourceName)
                && Files.isDirectory(exposed.resolve("region"))) {
            return exposed;
        }

        Path dimensions = metadataRoot.resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            try (Stream<Path> stream = Files.find(dimensions, 4,
                    (path, attributes) -> attributes.isDirectory()
                            && path.getFileName() != null
                            && path.getFileName().toString().equalsIgnoreCase(sourceName)
                            && Files.isDirectory(path.resolve("region")))) {
                Optional<Path> exact = stream.sorted(Comparator.comparing(Path::toString)).findFirst();
                if (exact.isPresent()) return exact.get().toAbsolutePath().normalize();
            }
        }

        // Fallback para layouts de Arclight/Sable donde getWorldFolder() apunta a una
        // carpeta hermana del árbol dimensions.
        Path searchRoot = commonSearchRoot(exposed, metadataRoot);
        try (Stream<Path> stream = Files.find(searchRoot, STORAGE_DISCOVERY_DEPTH,
                (path, attributes) -> attributes.isDirectory()
                        && path.getFileName() != null
                        && path.getFileName().toString().equalsIgnoreCase(sourceName)
                        && isInsideDimensionsTree(path)
                        && Files.isDirectory(path.resolve("region")))) {
            return stream.sorted(Comparator.comparing(Path::toString)).findFirst()
                    .map(path -> path.toAbsolutePath().normalize()).orElse(null);
        }
    }

    private static Path commonSearchRoot(Path exposed, Path metadataRoot) {
        Path root = metadataRoot;
        if (root.getParent() != null && exposed.startsWith(root.getParent())) return root.getParent();
        return root;
    }

    private static TargetLayout resolveTargetLayout(SourceLayout layout, Path worldContainer,
                                                    String sourceName, String targetName,
                                                    World.Environment environment)
            throws IOException {
        Path root = worldContainer.resolve(targetName).toAbsolutePath().normalize();
        Path relativeStorage = switch (environment) {
            case NETHER -> Path.of("DIM-1");
            case THE_END -> Path.of("DIM1");
            default -> null;
        };
        Path storage = relativeStorage == null ? root : root.resolve(relativeStorage).normalize();
        return new TargetLayout(root, storage, relativeStorage);
    }

    private static Path replaceLastSegment(Path relative, String sourceName, String targetName) {
        if (relative == null || relative.getNameCount() == 0) return null;
        Path result = relative.getRoot();
        boolean replaced = false;
        for (int index = 0; index < relative.getNameCount(); index++) {
            String part = relative.getName(index).toString();
            if (index == relative.getNameCount() - 1 && part.equalsIgnoreCase(sourceName)) {
                part = targetName;
                replaced = true;
            }
            result = result == null ? Path.of(part) : result.resolve(part);
        }
        return replaced ? result : null;
    }

    private static Path findMetadataRoot(Path start) {
        Path current = start;
        for (int depth = 0; depth < 5 && current != null; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("level.dat"))) return current;
        }
        return null;
    }

    private static Path findMetadataRootDeep(Path start) {
        Path current = start;
        for (int depth = 0; depth < STORAGE_DISCOVERY_DEPTH && current != null;
             depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("level.dat"))) return current;
        }
        return null;
    }

    private static Path findLegacyDimensionRoot(Path preferred, Path metadataRoot,
                                                World.Environment environment) {
        if (Files.isDirectory(preferred.resolve("region"))) return preferred;
        if (environment == World.Environment.NETHER) {
            Path dim = metadataRoot.resolve("DIM-1");
            if (Files.isDirectory(dim.resolve("region"))) return dim;
        } else if (environment == World.Environment.THE_END) {
            Path dim = metadataRoot.resolve("DIM1");
            if (Files.isDirectory(dim.resolve("region"))) return dim;
        } else if (Files.isDirectory(metadataRoot.resolve("region"))) {
            return metadataRoot;
        }
        return null;
    }

    private static boolean isInsideDimensionsTree(Path path) {
        if (path == null) return false;
        for (Path part : path) {
            if (part.toString().equalsIgnoreCase("dimensions")) return true;
        }
        return false;
    }

    private static void copyCriticalStorageDirectories(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        for (String directory : CRITICAL_STORAGE_DIRECTORIES) {
            Path sourceDirectory = source.resolve(directory);
            if (!Files.isDirectory(sourceDirectory)) continue;
            copyWholeTree(sourceDirectory, target.resolve(directory));
        }
    }

    private static void copyWholeTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = source.relativize(directory);
                if (!relative.toString().isEmpty()
                        && skipDirectory(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (skipFile(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                copyFile(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyMetadata(Path metadataRoot, Path target) throws IOException {
        Files.walkFileTree(metadataRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = metadataRoot.relativize(directory);
                if (!relative.toString().isEmpty()) {
                    String name = directory.getFileName().toString();
                    if (skipDirectory(name)) return FileVisitResult.SKIP_SUBTREE;
                    if (name.equals("region") || name.equals("poi")
                            || name.equals("entities") || name.equals("DIM-1")
                            || name.equals("DIM1") || name.equals("dimensions")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (skipFile(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                copyFile(file, target.resolve(metadataRoot.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyDimensionData(Path dimensionRoot, Path target) throws IOException {
        Files.walkFileTree(dimensionRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = dimensionRoot.relativize(directory);
                if (!relative.toString().isEmpty()
                        && skipDirectory(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (skipFile(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                copyFile(file, target.resolve(dimensionRoot.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyTemplateMarker(SourceLayout layout, Path staging,
                                           World.Environment environment) throws IOException {
        String markerName = templateMarkerName(environment);
        if (markerName == null) return;
        for (Path root : List.of(layout.exposedRoot(), layout.metadataRoot(), layout.dimensionRoot())) {
            Path marker = root.resolve(markerName);
            if (!Files.isRegularFile(marker)) continue;
            copyFile(marker, staging.resolve(markerName));
            return;
        }
        throw new IOException("La dimensión personalizada no contiene el marcador " + markerName + ".");
    }

    private static String templateMarkerName(World.Environment environment) {
        if (environment == World.Environment.NETHER) return "arlight-nether-template.properties";
        if (environment == World.Environment.THE_END) return "arlight-end-template.properties";
        if (environment == World.Environment.NORMAL) return "arlight-overworld-template.properties";
        return null;
    }

    private record SnapshotManifest(Map<String, Long> files) {
        long totalBytes() { return files.values().stream().mapToLong(Long::longValue).sum(); }
        String summary() { return files.size() + " archivos / " + totalBytes() + " bytes"; }
    }

    private static SnapshotManifest criticalSnapshotManifest(Path storageRoot) throws IOException {
        Map<String, Long> files = new TreeMap<>();
        for (String directory : CRITICAL_STORAGE_DIRECTORIES) {
            Path base = storageRoot.resolve(directory);
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> stream = Files.walk(base)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    Path relative = base.relativize(file);
                    String key = directory + "/"
                            + relative.toString().replace('\\', '/');
                    files.put(key, Files.size(file));
                }
            }
        }
        return new SnapshotManifest(Map.copyOf(files));
    }

    private static void verifyChunkStorageExact(Path sourceStorage, Path copiedStorage)
            throws IOException {
        for (String directory : CRITICAL_STORAGE_DIRECTORIES) {
            Path sourceBase = sourceStorage.resolve(directory);
            Path copiedBase = copiedStorage.resolve(directory);
            if (!Files.isDirectory(sourceBase)) continue;
            if (!Files.isDirectory(copiedBase)) {
                throw new IOException("Falta " + directory + " en la copia.");
            }
            try (Stream<Path> stream = Files.walk(sourceBase)) {
                for (Path sourceFile : stream.filter(Files::isRegularFile).toList()) {
                    Path relative = sourceBase.relativize(sourceFile);
                    Path copiedFile = copiedBase.resolve(relative);
                    if (!Files.isRegularFile(copiedFile)) {
                        throw new IOException("Falta " + directory + "/" + relative + " en la copia.");
                    }
                    if (Files.size(sourceFile) != Files.size(copiedFile)) {
                        throw new IOException("Tamaño distinto en " + directory + "/" + relative + ".");
                    }
                    long mismatch = Files.mismatch(sourceFile, copiedFile);
                    if (mismatch != -1L) {
                        throw new IOException("Contenido distinto en " + directory + "/" + relative
                                + " desde el byte " + mismatch + ".");
                    }
                }
            }
        }
    }

    private static void moveCompletedSnapshot(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(staging, target);
        }
    }

    private static String sanitizePath(Path path) {
        return path == null ? "" : path.toString().replace('\n', '_').replace('\r', '_');
    }

    private static void copyFile(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        } catch (UnsupportedOperationException ignored) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record CloneValidation(boolean valid, String detail) {
        public static CloneValidation ok() { return new CloneValidation(true, "OK"); }
        public static CloneValidation fail(String detail) { return new CloneValidation(false, detail); }
    }

    /** Busca la ubicación exacta de una copia runtime, incluso dentro de dimensions/. */
    public static Path findRuntimeFolder(JavaPlugin plugin, String targetName) {
        if (plugin == null || targetName == null) return null;
        World loaded = Bukkit.getWorld(targetName);
        if (loaded != null) {
            Path exact = resolveLoadedStorage(loaded);
            if (isRuntimeFolderFor(exact, targetName)) return exact;
        }
        Path root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> stream = Files.find(root, STORAGE_DISCOVERY_DEPTH,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equals(RUNTIME_MARKER))) {
            return stream.map(Path::getParent)
                    .filter(folder -> isRuntimeFolderFor(folder, targetName))
                    .sorted(Comparator.comparing(Path::toString))
                    .findFirst().orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Carpetas con el nombre del mundo que pueden haber sido creadas por versiones anteriores. */
    public static Set<Path> findNamedStorageFolders(JavaPlugin plugin, String worldName) {
        Set<Path> found = new LinkedHashSet<>();
        if (plugin == null || worldName == null) return found;
        Path root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path direct = root.resolve(worldName);
        if (Files.isDirectory(direct)) found.add(direct);
        if (!Files.isDirectory(root)) return found;
        try (Stream<Path> stream = Files.find(root, STORAGE_DISCOVERY_DEPTH,
                (path, attributes) -> attributes.isDirectory()
                        && path.getFileName() != null
                        && path.getFileName().toString().equalsIgnoreCase(worldName)
                        && (Files.isDirectory(path.resolve("region"))
                        || Files.isRegularFile(path.resolve(RUNTIME_MARKER))))) {
            stream.map(path -> path.toAbsolutePath().normalize()).forEach(found::add);
        } catch (IOException ignored) { }
        return found;
    }

    public static Path resolveLoadedStorage(World world) {
        if (world == null) return null;
        Path exposed = world.getWorldFolder().toPath().toAbsolutePath().normalize();
        try {
            Path metadata = findMetadataRootDeep(exposed);
            if (metadata != null) {
                Path custom = findNamedCustomDimensionStorage(exposed, metadata, world.getName());
                if (custom != null) return custom;
            }
        } catch (IOException ignored) { }
        return exposed;
    }

    private static boolean isRuntimeFolderFor(Path folder, String targetName) {
        if (folder == null || !Files.isDirectory(folder)) return false;
        Properties properties = loadProperties(folder.resolve(RUNTIME_MARKER));
        return properties != null
                && RUNTIME_VERSION.equals(properties.getProperty("version"))
                && targetName.equalsIgnoreCase(properties.getProperty("target", ""))
                && "true".equalsIgnoreCase(properties.getProperty("completed", ""));
    }

    public static CloneValidation validateFolder(Path folder, String expectedSource,
                                                 World.Environment expectedEnvironment,
                                                 String requiredTemplateMarker) {
        return validateFolder(folder, expectedSource, null, expectedEnvironment, requiredTemplateMarker);
    }

    public static CloneValidation validateFolder(Path folder, String expectedSource,
                                                 String expectedTarget,
                                                 World.Environment expectedEnvironment,
                                                 String requiredTemplateMarker) {
        if (folder == null || !Files.isDirectory(folder)) {
            return CloneValidation.fail("no existe la carpeta de almacenamiento "
                    + (folder == null ? "(null)" : folder));
        }
        Path normalized = folder.toAbsolutePath().normalize();
        Path ownership = normalized.resolve(".arlightbingo_arena");
        if (!Files.isRegularFile(ownership)) return CloneValidation.fail("falta .arlightbingo_arena");

        Properties properties = loadProperties(normalized.resolve(RUNTIME_MARKER));
        if (properties == null) return CloneValidation.fail("no se pudo leer " + RUNTIME_MARKER);
        if (!RUNTIME_VERSION.equals(properties.getProperty("version"))) {
            return CloneValidation.fail("versión de copia antigua o incompleta");
        }
        if (expectedSource != null
                && !expectedSource.equalsIgnoreCase(properties.getProperty("source", ""))) {
            return CloneValidation.fail("la copia procede de otra plantilla");
        }
        if (expectedTarget != null
                && !expectedTarget.equalsIgnoreCase(properties.getProperty("target", ""))) {
            return CloneValidation.fail("el almacenamiento pertenece a otro mundo destino");
        }
        if (!"true".equalsIgnoreCase(properties.getProperty("completed", ""))) {
            return CloneValidation.fail("la copia no terminó");
        }
        if (expectedEnvironment != null
                && !expectedEnvironment.name().equalsIgnoreCase(properties.getProperty("environment", ""))) {
            return CloneValidation.fail("entorno incorrecto: "
                    + properties.getProperty("environment", "ausente"));
        }
        if (!Files.isDirectory(normalized.resolve("region"))) {
            return CloneValidation.fail("falta region/ en el almacenamiento exacto");
        }
        if (requiredTemplateMarker != null
                && !Files.isRegularFile(normalized.resolve(requiredTemplateMarker))) {
            return CloneValidation.fail("falta el marcador " + requiredTemplateMarker);
        }
        try (Stream<Path> stream = Files.list(normalized.resolve("region"))) {
            boolean hasRegion = stream.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".mca") && fileHasData(path));
            if (!hasRegion) return CloneValidation.fail("no hay archivos de región copiados");
        } catch (IOException error) {
            return CloneValidation.fail("no se pudo revisar region/");
        }
        return CloneValidation.ok();
    }

    private static Properties loadProperties(Path file) {
        if (!Files.isRegularFile(file)) return null;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return properties;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean fileHasData(Path path) {
        try { return Files.size(path) > 8192L; }
        catch (IOException ignored) { return false; }
    }

    public static boolean isRuntimeClone(World world) {
        if (world == null) return false;
        Path storage = resolveLoadedStorage(world);
        if (isRuntimeFolderFor(storage, world.getName())) return true;
        for (Path marker : markerCandidates(world, RUNTIME_MARKER)) {
            Properties properties = loadProperties(marker);
            if (properties != null && RUNTIME_VERSION.equals(properties.getProperty("version"))) {
                return true;
            }
        }
        return false;
    }

    public static Location readLocation(World world, String markerName, String key) {
        String raw = readProperty(world, markerName, key);
        if (raw == null || raw.isBlank()) return null;
        return parseLocation(world, raw);
    }

    public static List<Location> readLocations(World world, String markerName, String key) {
        String raw = readProperty(world, markerName, key);
        if (raw == null || raw.isBlank()) return List.of();
        List<Location> locations = new ArrayList<>();
        for (String encoded : raw.split(";")) {
            Location location = parseLocation(world, encoded);
            if (location != null) locations.add(location);
        }
        return List.copyOf(locations);
    }

    public static String readProperty(World world, String markerName, String key) {
        if (world == null || markerName == null || key == null) return null;
        for (Path marker : markerCandidates(world, markerName)) {
            Properties properties = loadProperties(marker);
            if (properties == null) continue;
            String value = properties.getProperty(key);
            if (value != null) return value;
        }
        return null;
    }

    private static Location parseLocation(World world, String encoded) {
        try {
            String[] split = encoded.trim().split(",");
            if (split.length < 3) return null;
            return new Location(world, Double.parseDouble(split[0].trim()),
                    Double.parseDouble(split[1].trim()), Double.parseDouble(split[2].trim()));
        } catch (RuntimeException ignored) { return null; }
    }

    private static Set<Path> markerCandidates(World world, String markerName) {
        Set<Path> markers = new LinkedHashSet<>();
        Path exposed = world.getWorldFolder().toPath().toAbsolutePath().normalize();
        Path storage = resolveLoadedStorage(world);
        addMarkerCandidate(markers, storage, markerName);
        addMarkerCandidate(markers, exposed, markerName);
        Path configured = Bukkit.getWorldContainer().toPath().resolve(world.getName())
                .toAbsolutePath().normalize();
        addMarkerCandidate(markers, configured, markerName);
        Path metadata = findMetadataRootDeep(exposed);
        addMarkerCandidate(markers, metadata, markerName);
        return markers;
    }

    private static void addMarkerCandidate(Set<Path> markers, Path folder, String markerName) {
        if (folder != null) markers.add(folder.resolve(markerName));
    }

    private static boolean skipDirectory(String name) {
        return name.equals("playerdata") || name.equals("stats") || name.equals("advancements");
    }

    private static boolean skipFile(String name) {
        return name.equals("uid.dat") || name.equals("session.lock")
                || name.equals(".arlightbingo_arena") || name.equals(RUNTIME_MARKER)
                || name.startsWith(".arlightbingo_ready_");
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path current : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }
}
