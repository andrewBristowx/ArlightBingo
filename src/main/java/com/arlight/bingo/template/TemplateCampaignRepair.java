package com.arlight.bingo.template;

import com.arlight.bingo.util.CampaignItemBridge;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.loot.LootTables;

import java.util.ArrayList;
import java.util.List;

/** Repara únicamente los anclajes funcionales; nunca regenera la arquitectura. */
public final class TemplateCampaignRepair {

    private TemplateCampaignRepair() { }

    public static List<Location> ensureNetherKeyChests(World world, List<Location> preferred) {
        List<Location> locations = validOrDefaults(world, preferred, List.of(
                point(world, -72, 64, -42), point(world, 72, 64, -42),
                point(world, -70, 64, 50), point(world, 70, 64, 50)));
        ensureContainers(locations, LootTables.BASTION_TREASURE);
        return List.copyOf(locations);
    }


    /**
     * Repara el punto de llegada del Nether copiado sin reconstruir la ciudadela.
     * Algunas copias de mundo en Arclight conservan el marcador, pero el bloque
     * bajo los pies queda sustituido por aire o la cámara aparece obstruida.
     */
    public static Location ensureNetherSafeArrival(World world, Location preferred) {
        Location arrival = valid(world, preferred)
                ? preferred.clone() : point(world, 0.5D, 65.0D, -170.5D);

        int minY = world.getMinHeight() + 3;
        int maxY = world.getMaxHeight() - 4;
        if (arrival.getY() < minY || arrival.getY() > maxY) {
            arrival.setY(Math.max(minY, Math.min(maxY, 65.0D)));
        }

        world.getChunkAt(arrival.getBlockX() >> 4, arrival.getBlockZ() >> 4).load(true);

        // Plataforma pequeña y no invasiva: sólo asegura el área inmediata del portal.
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                arrival.clone().add(x, -1, z).getBlock()
                        .setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
            }
        }
        // Espacio suficiente para que un jugador y su teletransporte no queden atrapados.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 2; y++) {
                    arrival.clone().add(x, y, z).getBlock().setType(Material.AIR, false);
                }
            }
        }
        arrival.setX(arrival.getBlockX() + 0.5D);
        arrival.setZ(arrival.getBlockZ() + 0.5D);
        arrival.setYaw(0.0F);
        arrival.setPitch(0.0F);
        return arrival;
    }

    /**
     * Repara el punto de llegada del End copiado sin reconstruir la ciudadela.
     * Mantiene la coordenada persistente cuando existe y sólo asegura el piso
     * y el espacio inmediato necesario para recibir al jugador.
     */
    public static Location ensureEndSafeArrival(World world, Location preferred) {
        Location arrival;
        if (valid(world, preferred)) {
            arrival = preferred.clone();
        } else {
            Location spawn = world.getSpawnLocation();
            arrival = new Location(world, spawn.getBlockX() + 0.5D,
                    Math.max(world.getMinHeight() + 3, spawn.getBlockY()),
                    spawn.getBlockZ() + 0.5D);
        }

        int minY = world.getMinHeight() + 3;
        int maxY = world.getMaxHeight() - 5;
        if (arrival.getY() < minY || arrival.getY() > maxY) {
            arrival.setY(Math.max(minY, Math.min(maxY, 100.0D)));
        }

        world.getChunkAt(arrival.getBlockX() >> 4, arrival.getBlockZ() >> 4).load(true);

        // Plataforma ligeramente mayor que la del Nether para proteger del vacío.
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                arrival.clone().add(x, -1, z).getBlock().setType(Material.END_STONE_BRICKS, false);
            }
        }
        // Cámara despejada para jugador, montura y teletransporte de Arclight.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 3; y++) {
                    arrival.clone().add(x, y, z).getBlock().setType(Material.AIR, false);
                }
            }
        }
        arrival.setX(arrival.getBlockX() + 0.5D);
        arrival.setZ(arrival.getBlockZ() + 0.5D);
        arrival.setYaw(0.0F);
        arrival.setPitch(0.0F);
        return arrival;
    }

    public static Location ensureNetherLock(World world, Location preferred) {
        Location lock = valid(world, preferred) ? preferred.clone() : point(world, -6, 63, 116);

        // La cerradura vive dentro de la zona que el constructor limpia por chunks.
        // Por eso su base y su volumen deben repararse siempre al final del chunk.
        if (!world.isChunkLoaded(lock.getBlockX() >> 4, lock.getBlockZ() >> 4)) {
            world.getChunkAt(lock.getBlockX() >> 4, lock.getBlockZ() >> 4);
        }
        if (!lock.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
            lock.clone().add(0, -1, 0).getBlock().setType(Material.GILDED_BLACKSTONE, false);
        }
        lock.clone().add(0, 1, 0).getBlock().setType(Material.AIR, false);
        lock.clone().add(0, 2, 0).getBlock().setType(Material.AIR, false);

        String lockedState = CampaignItemBridge.NETHER_DUNGEON_LOCK
                + "[facing=north,lock_state=locked]";
        boolean placed = CampaignItemBridge.setModBlockState(lock, lockedState);

        // Algunos builds de Arclight aceptan /setblock aunque el estado con
        // propiedades no llegue a materializarse. Reintentamos con el bloque base
        // y luego aplicamos sus propiedades directamente.
        if (!CampaignItemBridge.isBlock(lock, CampaignItemBridge.NETHER_DUNGEON_LOCK)) {
            placed = CampaignItemBridge.placeModBlock(lock, CampaignItemBridge.NETHER_DUNGEON_LOCK) || placed;
            CampaignItemBridge.updateModBlockStateSilently(lock, lockedState);
        }

        if (!CampaignItemBridge.isBlock(lock, CampaignItemBridge.NETHER_DUNGEON_LOCK)) {
            lock.getBlock().setType(Material.RESPAWN_ANCHOR, false);
        }
        return lock;
    }

    public static List<Location> ensureEndKeyChests(World world, List<Location> preferred) {
        List<Location> locations = validOrDefaults(world, preferred, List.of(
                point(world, -52, 100, -8), point(world, 52, 100, -8),
                point(world, -42, 100, 38), point(world, 42, 100, 38)));
        ensureContainers(locations, LootTables.END_CITY_TREASURE);
        return List.copyOf(locations);
    }

    public static Location ensureEndAltar(World world, Location preferred) {
        Location altar = valid(world, preferred) ? preferred.clone() : point(world, 0, 98, 430);
        boolean placed = CampaignItemBridge.placeModBlock(altar, CampaignItemBridge.CORRUPTED_ALTAR);
        if (!placed || (!CampaignItemBridge.isBlock(altar, CampaignItemBridge.CORRUPTED_ALTAR)
                && altar.getBlock().getType() != Material.RESPAWN_ANCHOR)) {
            altar.getBlock().setType(Material.RESPAWN_ANCHOR, false);
        }
        return altar;
    }

    public static boolean isContainer(Location location) {
        return valid(location == null ? null : location.getWorld(), location)
                && location.getBlock().getState() instanceof Container;
    }

    private static List<Location> validOrDefaults(World world, List<Location> preferred,
                                                   List<Location> defaults) {
        List<Location> result = new ArrayList<>();
        if (preferred != null) {
            for (Location location : preferred) {
                if (valid(world, location) && result.stream().noneMatch(existing -> sameBlock(existing, location))) {
                    result.add(location.clone());
                }
            }
        }
        for (Location fallback : defaults) {
            if (result.size() >= defaults.size()) break;
            if (result.stream().noneMatch(existing -> sameBlock(existing, fallback))) {
                result.add(fallback.clone());
            }
        }
        return result;
    }

    private static boolean sameBlock(Location first, Location second) {
        return first != null && second != null && first.getWorld() != null && second.getWorld() != null
                && first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private static void ensureContainers(List<Location> locations, LootTables table) {
        for (Location location : locations) {
            if (location.getBlock().getState() instanceof Container) continue;
            location.getBlock().setType(Material.CHEST, false);
            if (location.getBlock().getState() instanceof Chest chest) {
                chest.setLootTable(table.getLootTable());
                chest.setSeed(location.getWorld().getSeed()
                        ^ location.getBlockX() * 43L ^ location.getBlockZ() * 17L);
                chest.update(true, false);
            }
        }
    }

    private static boolean valid(World world, Location location) {
        return world != null && location != null && location.getWorld() != null
                && location.getWorld().getUID().equals(world.getUID());
    }

    private static Location point(World world, double x, double y, double z) {
        return new Location(world, x, y, z);
    }
}
