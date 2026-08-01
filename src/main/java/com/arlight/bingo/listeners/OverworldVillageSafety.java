package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Protege la aldea inicial y coordina la construcción limpia de campaña Overworld 1.48.2.
 * La versión anterior queda en su propia rama; esta clase no modifica revisiones
 * antiguas y sólo inicia el nuevo diseño sobre una plantilla creada en la misma
 * ejecución o sobre una construcción 1.48.2 interrumpida.
 */
public final class OverworldVillageSafety implements Listener {
    private static final String MARKER = "arlight-overworld-template.properties";

    private final BingoPlugin plugin;
    private final OverworldCampaignLandscape148 campaignLayout;
    private final Map<UUID, SafeZone> zones = new HashMap<>();
    private final Map<UUID, Long> retryAfter = new HashMap<>();

    public OverworldVillageSafety(BingoPlugin plugin) {
        this.plugin = plugin;
        this.campaignLayout = new OverworldCampaignLandscape148(plugin);
        long interval = Math.max(20L, plugin.getConfig().getLong(
                "template-worlds.overworld.safe-village.purge-interval-ticks", 40L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::maintenanceTick, interval, interval);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster)) return;
        SafeZone zone = safeZone(event.getLocation().getWorld());
        if (zone != null && zone.contains(event.getLocation().getX(), event.getLocation().getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        campaignLayout.handleInteraction(event);
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID id = event.getWorld().getUID();
        zones.remove(id);
        retryAfter.remove(id);
        campaignLayout.onWorldUnload(event.getWorld());
    }

    private void maintenanceTick() {
        purgeLoadedVillages();
        campaignLayout.tick();
    }

    private void purgeLoadedVillages() {
        if (!plugin.getConfig().getBoolean("template-worlds.overworld.safe-village.enabled", true)) return;
        for (World world : Bukkit.getWorlds()) {
            SafeZone zone = safeZone(world);
            if (zone == null) continue;
            BoundingBox bounds = new BoundingBox(zone.x - zone.radius, world.getMinHeight(),
                    zone.z - zone.radius, zone.x + zone.radius, world.getMaxHeight(),
                    zone.z + zone.radius);
            for (Entity entity : world.getNearbyEntities(bounds)) {
                if (entity instanceof Monster && zone.contains(
                        entity.getLocation().getX(), entity.getLocation().getZ())) {
                    entity.remove();
                }
            }
        }
    }

    private SafeZone safeZone(World world) {
        if (world == null || !plugin.getConfig().getBoolean(
                "template-worlds.overworld.safe-village.enabled", true)) return null;
        SafeZone known = zones.get(world.getUID());
        if (known != null) return known;
        long now = System.currentTimeMillis();
        if (retryAfter.getOrDefault(world.getUID(), 0L) > now) return null;

        Path marker = world.getWorldFolder().toPath().resolve(MARKER);
        if (!Files.isRegularFile(marker)) {
            retryAfter.put(world.getUID(), now + 10_000L);
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
            properties.load(reader);
            String[] village = properties.getProperty("village", "").split(",");
            if (village.length < 3 || !Boolean.parseBoolean(
                    properties.getProperty("villageSafe", "true"))) return null;
            double x = Double.parseDouble(village[0].trim()) + 0.5D;
            double z = Double.parseDouble(village[2].trim()) + 0.5D;
            double radius = Double.parseDouble(properties.getProperty("villageSafeRadius",
                    String.valueOf(plugin.getConfig().getInt(
                            "template-worlds.overworld.safe-village.radius", 112))));
            SafeZone loaded = new SafeZone(x, z, Math.max(48.0D, radius));
            zones.put(world.getUID(), loaded);
            return loaded;
        } catch (IOException | NumberFormatException exception) {
            retryAfter.put(world.getUID(), now + 30_000L);
            plugin.getLogger().warning("No se pudo leer la zona segura de " + world.getName()
                    + ": " + exception.getMessage());
            return null;
        }
    }

    private record SafeZone(double x, double z, double radius) {
        boolean contains(double targetX, double targetZ) {
            double dx = targetX - x;
            double dz = targetZ - z;
            return dx * dx + dz * dz <= radius * radius;
        }
    }
}
