package com.arlight.bingo.dungeon;

import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.util.BossTuning;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Administra spawners vanilla reales configurados por NBT para generar entidades
 * registradas por ArlightBosses. Ya no invoca /summon periódicamente: cada bloque
 * spawner contiene su SpawnData/SpawnPotentials y Minecraft realiza la generación.
 */
public final class DungeonMinionSpawnerManager {
    private static final AtomicInteger IDS = new AtomicInteger();

    private static final class SpawnerData {
        final int id;
        final Location location;
        final String group;
        final List<String> entityIds;
        boolean active;

        SpawnerData(Location location, String group, List<String> entityIds, boolean active) {
            this.id = IDS.incrementAndGet();
            this.location = location.clone();
            this.group = group;
            this.entityIds = List.copyOf(entityIds);
            this.active = active;
        }

        String uniqueTag() {
            return "arlightbingo_spawner_" + id;
        }
    }

    private final JavaPlugin plugin;
    private final BingoGame game;
    private final List<SpawnerData> spawners = new ArrayList<>();
    private int generation;

    public DungeonMinionSpawnerManager(JavaPlugin plugin, BingoGame game) {
        this.plugin = plugin;
        this.game = game;
    }

    public void reset() {
        generation++;
        spawners.clear();
    }

    public void shutdown() {
        generation++;
        spawners.clear();
    }

    /**
     * Descarta spawners registrados en una parcela que será reemplazada por una
     * pasada de arquitectura. El bloque se elimina después por el generador; aquí
     * sólo se evita que mantenimiento vuelva a crear un spawner fantasma.
     */
    public void unregisterInBox(Location center, int halfWidth, int halfDepth,
                                int minYOffset, int maxYOffset) {
        if (center == null || center.getWorld() == null) return;
        World world = center.getWorld();
        int minX = center.getBlockX() - Math.max(0, halfWidth);
        int maxX = center.getBlockX() + Math.max(0, halfWidth);
        int minZ = center.getBlockZ() - Math.max(0, halfDepth);
        int maxZ = center.getBlockZ() + Math.max(0, halfDepth);
        int minY = center.getBlockY() + Math.min(minYOffset, maxYOffset);
        int maxY = center.getBlockY() + Math.max(minYOffset, maxYOffset);
        spawners.removeIf(data -> data.location.getWorld() == world
                && data.location.getBlockX() >= minX && data.location.getBlockX() <= maxX
                && data.location.getBlockY() >= minY && data.location.getBlockY() <= maxY
                && data.location.getBlockZ() >= minZ && data.location.getBlockZ() <= maxZ);
    }

    public void deactivateGroup(String group) {
        for (SpawnerData spawner : List.copyOf(spawners)) {
            if (spawner.group.equalsIgnoreCase(group)) {
                spawner.active = false;
                configureSpawner(spawner);
            }
        }
    }

    public void deactivateGroupPrefix(String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        for (SpawnerData spawner : List.copyOf(spawners)) {
            if (spawner.group.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                spawner.active = false;
                configureSpawner(spawner);
            }
        }
    }

    public void activateGroup(String group) {
        for (SpawnerData spawner : List.copyOf(spawners)) {
            if (spawner.group.equalsIgnoreCase(group)) {
                spawner.active = true;
                configureSpawner(spawner);
            }
        }
    }

    /** Cantidad de spawners aún existentes dentro de un grupo de conquista. */
    public int remainingInGroup(String group) {
        if (group == null) return 0;
        return (int) spawners.stream()
                .filter(spawner -> spawner.group.equalsIgnoreCase(group))
                .count();
    }

    public boolean hasGroup(String group) {
        return remainingInGroup(group) > 0;
    }


    /** Devuelve true únicamente para spawners colocados por esta partida. */
    public boolean isRegisteredSpawner(Location at) {
        return find(at) != null;
    }

    /** Indica si el spawner pertenece al distrito actualmente desbloqueado. */
    public boolean isActiveSpawner(Location at) {
        SpawnerData data = find(at);
        return data != null && data.active;
    }


    /**
     * El jugador conquista un sector al romper su spawner. El bloque no se
     * restaura y la definición se elimina para que mantenimiento/activación no
     * pueda revivirlo después.
     */
    public boolean handleBroken(Location at, Player player) {
        return destroySpawner(at, player, true);
    }

    /** Permite que explosiones controladas conquisten el sector sin dejar un spawner fantasma. */
    public boolean handleDestroyedByExplosion(Location at) {
        return destroySpawner(at, null, false);
    }

    private boolean destroySpawner(Location at, Player player, boolean rewardPlayer) {
        SpawnerData data = find(at);
        if (data == null) return false;
        spawners.remove(data);
        data.active = false;
        if (player != null && rewardPlayer) {
            int reward = Math.max(0, plugin.getConfig().getInt("campaign.conquest.spawner-xp", 8));
            if (reward > 0) player.giveExp(reward);
            player.playSound(at, Sound.BLOCK_TRIAL_SPAWNER_BREAK, 1.0F, 1.15F);
            player.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0.5, 0.8, 0.5),
                    18, 0.45, 0.5, 0.45, 0.02);
            player.sendMessage(ChatColor.GREEN + "[Bingo] Spawner destruido: el sector está más cerca de ser liberado.");
        }
        long remaining = spawners.stream().filter(other -> other.group.equalsIgnoreCase(data.group)).count();
        if (remaining == 0 && at != null && at.getWorld() != null) {
            for (Player participant : at.getWorld().getPlayers()) {
                if (game.getTeamOf(participant) != null) {
                    participant.sendMessage(ChatColor.AQUA + "[Bingo] Sector liberado: "
                            + ChatColor.WHITE + readableGroup(data.group) + ".");
                    participant.playSound(participant.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7F, 1.25F);
                }
            }
        }
        return true;
    }

    private SpawnerData find(Location at) {
        if (at == null || at.getWorld() == null) return null;
        for (SpawnerData data : List.copyOf(spawners)) {
            Location other = data.location;
            if (other.getWorld() == at.getWorld()
                    && other.getBlockX() == at.getBlockX()
                    && other.getBlockY() == at.getBlockY()
                    && other.getBlockZ() == at.getBlockZ()) return data;
        }
        return null;
    }

    private String readableGroup(String group) {
        if (group == null || group.isBlank()) return "zona";
        String normalized = group.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("surface_city_district_"))
            return "distrito musgoso " + normalized.substring("surface_city_district_".length());
        if (normalized.startsWith("nether_city_district_"))
            return "distrito infernal " + normalized.substring("nether_city_district_".length());
        if (normalized.startsWith("end_citadel_district_"))
            return "distrito de la ciudadela " + normalized.substring("end_citadel_district_".length());
        return normalized.replace('_', ' ');
    }

    public void register(Location at, String group, List<String> entityIds) {
        register(at, group, entityIds, true);
    }

    public void register(Location at, String group, List<String> entityIds, boolean initiallyActive) {
        if (!plugin.getConfig().getBoolean("dungeon-spawners.enabled", true)) return;
        if (at == null || at.getWorld() == null || entityIds == null || entityIds.isEmpty()) return;

        List<String> cleanIds = entityIds.stream()
                .map(this::normalizeEntityId)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (cleanIds.isEmpty()) {
            plugin.getLogger().warning("[ArlightBingo] No se pudo crear un spawner sin IDs válidos en "
                    + formatLocation(at));
            return;
        }

        at.getBlock().setType(Material.SPAWNER, false);
        SpawnerData data = new SpawnerData(at, sanitizeGroup(group), cleanIds, initiallyActive);
        spawners.add(data);

        // Configuración inmediata y un segundo intento tras crear el BlockEntity.
        configureSpawner(data);
        int expectedGeneration = generation;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (generation == expectedGeneration && spawners.contains(data)) configureSpawner(data);
        }, 2L);
    }

    private void configureSpawner(SpawnerData spawner) {
        Location at = spawner.location;
        World world = at.getWorld();
        if (world == null) return;
        if (at.getBlock().getType() != Material.SPAWNER) at.getBlock().setType(Material.SPAWNER, false);

        int players = Math.max(1, game.getActivePlayerCount());
        int spawnCount = Math.max(1, Math.min(6,
                plugin.getConfig().getInt("dungeon-spawners.spawn-count", 1)));
        int maxNearby = Math.max(1,
                plugin.getConfig().getInt("dungeon-spawners.max-nearby-per-spawner", 2));

        if (plugin.getConfig().getBoolean("dungeon-spawners.count-scaling.enabled", true)) {
            int normalGroup = Math.max(2, plugin.getConfig().getInt(
                    "dungeon-spawners.count-scaling.normal-group-min-players", 2));
            int largeGroup = Math.max(normalGroup + 1, plugin.getConfig().getInt(
                    "dungeon-spawners.count-scaling.large-group-min-players", 5));
            if (players >= largeGroup) {
                maxNearby += Math.max(0, plugin.getConfig().getInt(
                        "dungeon-spawners.count-scaling.large-group-extra-nearby", 2));
                spawnCount += Math.max(0, plugin.getConfig().getInt(
                        "dungeon-spawners.count-scaling.large-group-extra-spawn", 1));
            } else if (players >= normalGroup) {
                maxNearby += Math.max(0, plugin.getConfig().getInt(
                        "dungeon-spawners.count-scaling.normal-group-extra-nearby", 1));
            }
        }
        spawnCount = Math.min(6, spawnCount);
        maxNearby = Math.min(16, maxNearby);

        int activationRange = clamp(plugin.getConfig().getInt(
                "dungeon-spawners.activation-range", 16), 1, 64);
        int spawnRange = clamp(plugin.getConfig().getInt(
                "dungeon-spawners.spawn-range", 4), 1, 12);
        int intervalSeconds = clamp(plugin.getConfig().getInt(
                "dungeon-spawners.spawn-interval-seconds", 15), 3, 120);
        int varianceSeconds = clamp(plugin.getConfig().getInt(
                "dungeon-spawners.spawn-delay-variance-seconds", 5), 0, 120);
        int minDelay = clamp(intervalSeconds * 20, 20, 32767);
        int maxDelay = clamp((intervalSeconds + varianceSeconds) * 20, minDelay, 32767);
        int initialDelay = clamp(plugin.getConfig().getInt(
                "dungeon-spawners.initial-delay-ticks", 40), 0, 32767);

        String entityNbt = entityNbt(spawner);
        String potentials = spawner.entityIds.stream()
                .map(id -> "{weight:1,data:{entity:" + entityNbt(spawner, id) + "}}")
                .collect(Collectors.joining(","));

        String nbt;
        if (spawner.active) {
            nbt = String.format(Locale.ROOT,
                    "{Delay:%ds,MinSpawnDelay:%ds,MaxSpawnDelay:%ds,SpawnCount:%ds," +
                            "MaxNearbyEntities:%ds,RequiredPlayerRange:%ds,SpawnRange:%ds," +
                            "SpawnData:{entity:%s},SpawnPotentials:[%s]}",
                    initialDelay, minDelay, maxDelay, spawnCount, maxNearby,
                    activationRange, spawnRange, entityNbt, potentials);
        } else {
            nbt = String.format(Locale.ROOT,
                    "{Delay:32767s,MinSpawnDelay:32767s,MaxSpawnDelay:32767s,SpawnCount:0s," +
                            "MaxNearbyEntities:0s,RequiredPlayerRange:0s,SpawnRange:%ds," +
                            "SpawnData:{entity:%s},SpawnPotentials:[%s]}",
                    spawnRange, entityNbt, potentials);
        }

        String command = String.format(Locale.ROOT,
                "execute in %s run data merge block %d %d %d %s",
                world.getKey(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), nbt);
        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
            plugin.getLogger().warning("[ArlightBingo] No se pudo configurar el spawner real en "
                    + formatLocation(at) + ". Comprueba que ArlightBosses esté instalado.");
        }
    }

    private String entityNbt(SpawnerData spawner) {
        return entityNbt(spawner, spawner.entityIds.get(0));
    }

    private String entityNbt(SpawnerData spawner, String entityId) {
        String extraNbt = BossTuning.minionNbt(BossTuning.minion(plugin, game));
        return String.format(Locale.ROOT,
                "{id:\"%s\",Tags:[\"arlightbingo_dungeon_mob\",\"arlightbingo_minion_%s\",\"%s\"]," +
                        "PersistenceRequired:1b%s}",
                entityId, spawner.group, spawner.uniqueTag(), extraNbt);
    }

    private String normalizeEntityId(String input) {
        if (input == null) return "";
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return "";
        if (!value.contains(":")) value = "arlightbosses:" + value;
        return value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? value : "";
    }

    private String sanitizeGroup(String group) {
        if (group == null || group.isBlank()) return "dungeon";
        String clean = group.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return clean.isBlank() ? "dungeon" : clean;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatLocation(Location at) {
        return at.getWorld().getName() + " " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
    }
}
