package com.arlight.bingo.util;

import com.arlight.bingo.template.TemplateWorldCloner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/**
 * Gestiona el mundo de arena de forma 100% nativa con la API de Bukkit (World/WorldCreator),
 * SIN depender de Multiverse-Core ni de sus comandos de consola.
 *
 * SEGURIDAD: antes de borrar un mundo para regenerarlo, se fija si el plugin lo creo el
 * (con un archivo marca ".arlightbingo_arena" dentro de la carpeta del mundo). Si el mundo
 * ya existia en el disco de antes (por ejemplo un mapa a medida) y no tiene esa marca, el
 * plugin NO lo borra -- lo carga tal cual y avisa en consola, para evitar destruir un mundo
 * que no le pertenece. Un admin puede confirmar explicitamente que quiere que el plugin
 * tome control de ese mundo (y lo pueda regenerar) con /bingo world resetarena.
 */
public class ArenaWorldManager {

    private static final String MARKER_FILE = ".arlightbingo_arena";

    private final JavaPlugin plugin;

    public ArenaWorldManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean worldExists(String worldName) {
        return Bukkit.getWorld(worldName) != null;
    }

    /** Carga un mundo del pool conservando el entorno indicado. */
    public World loadWorld(String worldName, World.Environment environment) {
        World loaded = Bukkit.getWorld(worldName);
        return loaded != null ? loaded : Bukkit.createWorld(new WorldCreator(worldName).environment(environment));
    }

    /**
     * Carga únicamente una carpeta ya existente. A diferencia de loadWorld(), jamás
     * genera un mundo vacío si la copia del Nether o End falta.
     */
    public World loadExistingWorld(String worldName, World.Environment environment) {
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            return loaded.getEnvironment() == environment ? loaded : null;
        }
        File folder = new File(Bukkit.getWorldContainer(), worldName);
        Path runtimeStorage = TemplateWorldCloner.findRuntimeFolder(plugin, worldName);
        if (!folder.isDirectory() && runtimeStorage == null) return null;
        World created = Bukkit.createWorld(new WorldCreator(worldName).environment(environment));
        if (created == null || created.getEnvironment() != environment) {
            if (created != null) Bukkit.unloadWorld(created, false);
            return null;
        }
        if (runtimeStorage != null) {
            Path loadedStorage = TemplateWorldCloner.resolveLoadedStorage(created);
            if (loadedStorage == null || !loadedStorage.toAbsolutePath().normalize()
                    .equals(runtimeStorage.toAbsolutePath().normalize())) {
                plugin.getLogger().severe("Arclight cargó '" + worldName + "' desde "
                        + loadedStorage + " pero la copia verificada está en " + runtimeStorage + ".");
                Bukkit.unloadWorld(created, false);
                return null;
            }
        }
        return created;
    }

    /** Teletransporta al jugador al spawn del mundo indicado. Devuelve false si el mundo no existe/no esta cargado. */
    public boolean teleportToWorld(Player player, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;
        player.teleport(world.getSpawnLocation());
        return true;
    }

    /**
     * Regenera el mundo de arena (lo borra y crea de nuevo con seed aleatoria) SOLO si el
     * plugin ya lo tiene marcado como propio, o si todavia no existe (primera vez). Si existe
     * en el disco y no tiene la marca, NO lo toca -- lo carga tal cual, para no borrar un mapa
     * que no creamos nosotros.
     */
    public void regenerateWorld(String worldName) {
        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        boolean folderExistsOnDisk = worldFolder.exists();
        boolean isOurs = !folderExistsOnDisk || hasMarker(worldFolder);

        if (!isOurs) {
            plugin.getLogger().warning("El mundo '" + worldName + "' ya existia en el disco y no fue creado por "
                    + "ArlightBingo, asi que NO se borro (por seguridad). Se cargo tal cual. Si queres que el "
                    + "plugin lo regenere solo en cada partida, usa /bingo world resetarena una vez para confirmarlo.");
            ensureLoaded(worldName);
            return;
        }

        doRegenerate(worldName);
    }

    /** Fuerza que el plugin tome control de este mundo (lo marca como propio) y lo regenera ahora mismo. */
    public void forceClaimAndRegenerate(String worldName) {
        doRegenerate(worldName);
    }

    /**
     * Descarga y elimina un mundo desechable antes de copiar encima una plantilla.
     * Mantiene la misma protección que la regeneración normal: una carpeta ajena,
     * sin la marca de ArlightBingo, jamás se reemplaza automáticamente.
     */
    public void prepareTemplateTarget(String worldName) throws IOException {
        prepareTemplateTarget(worldName, false);
    }

    /**
     * Repara un nombre de slot conocido del pool aunque una versión anterior haya creado
     * accidentalmente un mundo vanilla sin marcador. Sólo WorldPoolManager usa esta ruta
     * después de comprobar que el nombre pertenece al slot y no a una plantilla maestra.
     */
    public void prepareTemplateRuntimeTarget(String worldName) throws IOException {
        prepareTemplateTarget(worldName, true);
    }

    private void prepareTemplateTarget(String worldName, boolean reclaimKnownPoolTarget) throws IOException {
        File directFolder = new File(Bukkit.getWorldContainer(), worldName);
        Set<Path> folders = new LinkedHashSet<>();
        folders.add(directFolder.toPath().toAbsolutePath().normalize());
        Path runtime = TemplateWorldCloner.findRuntimeFolder(plugin, worldName);
        if (runtime != null) folders.add(runtime.toAbsolutePath().normalize());
        if (reclaimKnownPoolTarget) {
            folders.addAll(TemplateWorldCloner.findNamedStorageFolders(plugin, worldName));
        }

        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            Path exposed = existing.getWorldFolder().toPath().toAbsolutePath().normalize();
            Path storage = TemplateWorldCloner.resolveLoadedStorage(existing);
            // Nunca se elimina una raíz compartida (por ejemplo world/) sólo porque
            // Arclight la exponga como World#getWorldFolder(). Únicamente se aceptan
            // rutas cuyo último segmento sea exactamente el nombre del slot o que
            // tengan el marcador runtime del Bingo.
            if (safeRuntimeCandidate(exposed, worldName)) folders.add(exposed);
            if (storage != null && safeRuntimeCandidate(storage, worldName)) {
                folders.add(storage.toAbsolutePath().normalize());
            }
            World fallback = Bukkit.getWorlds().getFirst();
            for (Player player : new ArrayList<>(existing.getPlayers())) {
                player.teleport(fallback.getSpawnLocation());
            }
            if (!Bukkit.unloadWorld(existing, false)) {
                throw new IOException("No se pudo descargar el mundo '" + worldName + "'.");
            }
        }

        for (Path folder : folders) {
            if (!Files.exists(folder)) continue;
            File asFile = folder.toFile();
            boolean owned = hasMarker(asFile)
                    || Files.isRegularFile(folder.resolve(TemplateWorldCloner.RUNTIME_MARKER));
            if (!owned && !reclaimKnownPoolTarget) {
                throw new IOException("El almacenamiento '" + folder
                        + "' existe pero no pertenece a ArlightBingo.");
            }
            if (!owned) {
                plugin.getLogger().warning("Se recuperará el almacenamiento antiguo del slot conocido '"
                        + worldName + "': " + folder + ".");
            }
            TemplateWorldCloner.deleteRecursively(folder);
            if (Files.exists(folder)) {
                throw new IOException("No se pudo limpiar el almacenamiento de '" + worldName
                        + "': " + folder + ".");
            }
        }
    }


    private boolean safeRuntimeCandidate(Path folder, String worldName) {
        if (folder == null || folder.getFileName() == null) return false;
        if (folder.getFileName().toString().equalsIgnoreCase(worldName)) return true;
        return Files.isRegularFile(folder.resolve(TemplateWorldCloner.RUNTIME_MARKER))
                || Files.isRegularFile(folder.resolve(MARKER_FILE));
    }


    private void doRegenerate(String worldName) {
        World existing = Bukkit.getWorld(worldName);

        WorldCreator creator;
        if (existing != null) {
            World fallback = Bukkit.getWorlds().get(0);
            for (Player p : new ArrayList<>(existing.getPlayers())) {
                p.teleport(fallback.getSpawnLocation());
            }

            creator = new WorldCreator(worldName)
                    .environment(existing.getEnvironment())
                    .type(existing.getWorldType())
                    .generateStructures(existing.canGenerateStructures());

            boolean unloaded = Bukkit.unloadWorld(existing, false);
            if (!unloaded) {
                plugin.getLogger().warning("No se pudo descargar el mundo '" + worldName
                        + "' para regenerarlo (puede que aun queden jugadores adentro).");
                return;
            }
        } else {
            // Mundo nuevo (no existia antes): adivinamos el entorno por el sufijo del nombre,
            // siguiendo la convencion tipica de Multiverse-NetherPortals (mundo_nether /
            // mundo_the_end), para que el Nether/End se generen como corresponde (y el End
            // en particular quede con los datos vanilla del combate del dragon bien inicializados,
            // cosa que a veces NO pasa si el mundo fue creado por Multiverse en vez de por Bukkit).
            creator = new WorldCreator(worldName).environment(guessEnvironment(worldName));
        }

        deleteWorldFolder(worldName);
        creator.seed(new Random().nextLong());

        World created = Bukkit.createWorld(creator);
        if (created != null) {
            writeMarker(worldName);
            if (created.getEnvironment() == World.Environment.NORMAL) {
                // La plataforma segura de spawn solo tiene sentido en el mundo "normal" de la
                // arena -- en el Nether/End no la construimos, para no interferir con portales
                // o con la isla principal/estructura del combate del dragon.
                buildSafeSpawnPlatform(created);
            }
            plugin.getLogger().info("Mundo de arena '" + worldName + "' regenerado con una seed nueva.");
        } else {
            plugin.getLogger().warning("No se pudo crear/regenerar el mundo '" + worldName + "'.");
        }
    }

    private World.Environment guessEnvironment(String worldName) {
        String lower = worldName.toLowerCase();
        if (lower.endsWith("_nether") || lower.endsWith("nether")) return World.Environment.NETHER;
        if (lower.endsWith("_the_end") || lower.endsWith("_end") || lower.endsWith("end")) return World.Environment.THE_END;
        return World.Environment.NORMAL;
    }

    private void ensureLoaded(String worldName) {
        if (Bukkit.getWorld(worldName) != null) return;
        World world = Bukkit.createWorld(new WorldCreator(worldName));
        if (world != null) {
            plugin.getLogger().info("Mundo '" + worldName + "' cargado desde el disco.");
        }
    }

    /**
     * Construye una pequena plataforma plana y segura (una "mini isla") en coordenadas fijas
     * del mundo y fija ahi el punto de spawn, para que los jugadores NUNCA aparezcan enterrados
     * o en un lugar peligroso al ser teletransportados a la arena.
     */
    private void buildSafeSpawnPlatform(World world) {
        // Spawn provisional cercano a la futura puerta de la ciudad. DimensionDungeonManager
        // lo reemplaza por la plaza integrada cuando termina de construir la estructura.
        int centerX = plugin.getConfig().getInt("dimension-dungeons.overworld.dungeon-x", 72);
        int centerZ = plugin.getConfig().getInt("dimension-dungeons.overworld.dungeon-z", 0)
                + plugin.getConfig().getInt("overworld-city.spawn-offset-z", -72);
        int y = Math.max(world.getHighestBlockYAt(centerX, centerZ) + 1, world.getMinHeight() + 8);

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.getBlockAt(centerX + dx, y - 1, centerZ + dz).setType(Material.STONE_BRICKS, false);
                world.getBlockAt(centerX + dx, y, centerZ + dz).setType(Material.AIR, false);
                world.getBlockAt(centerX + dx, y + 1, centerZ + dz).setType(Material.AIR, false);
                world.getBlockAt(centerX + dx, y + 2, centerZ + dz).setType(Material.AIR, false);
            }
        }

        Location spawn = new Location(world, centerX + 0.5, y, centerZ + 0.5);
        world.setSpawnLocation(spawn);
    }

    private boolean hasMarker(File worldFolder) {
        return new File(worldFolder, MARKER_FILE).exists();
    }

    private void writeMarker(String worldName) {
        try {
            File folder = new File(Bukkit.getWorldContainer(), worldName);
            File marker = new File(folder, MARKER_FILE);
            if (!marker.exists()) {
                marker.createNewFile();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo escribir el archivo marca en '" + worldName + "': " + e.getMessage());
        }
    }

    private void deleteWorldFolder(String worldName) {
        File folder = new File(Bukkit.getWorldContainer(), worldName);
        deleteRecursively(folder);
    }

    private void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
