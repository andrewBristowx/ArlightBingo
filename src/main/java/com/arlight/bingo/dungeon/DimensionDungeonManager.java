package com.arlight.bingo.dungeon;

import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.game.GameState;
import com.arlight.bingo.util.BossTuning;
import com.arlight.bingo.util.CampaignCinematics;
import com.arlight.bingo.util.CampaignItemBridge;
import com.arlight.bingo.util.SomitaGuideNetwork;
import com.arlight.bingo.BingoPlugin;
import com.arlight.bingo.template.TemplateWorldCloner;
import com.arlight.bingo.template.TemplateCampaignRepair;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Castillos de progresión del Overworld y Nether. Los pisos inferiores contienen
 * spawners controlados, el penúltimo piso es una sala de jefe amplia y el último
 * es una sala segura con cuatro cofres Lootr y el portal de progresión.
 */
public final class DimensionDungeonManager implements Listener {
    private static final int CASTLE_RADIUS = 15;
    private static final int FLOOR_HEIGHT = 8;

    private final JavaPlugin plugin;
    private final AdaptiveDungeonLootManager adaptiveLoot;
    private final BingoGame game;
    private final DungeonMinionSpawnerManager spawnerManager;
    private final CampaignCinematics cinematics;
    private final OverworldCampaignProgression overworldCampaign;

    private record ProtectedRegion(String world, int minX, int maxX, int minY, int maxY,
                                   int minZ, int maxZ) {
        boolean contains(Location at) {
            return containsXZ(at) && at.getBlockY() >= minY && at.getBlockY() <= maxY;
        }

        boolean containsXZ(Location at) {
            return at != null && at.getWorld() != null && at.getWorld().getName().equals(world)
                    && at.getBlockX() >= minX && at.getBlockX() <= maxX
                    && at.getBlockZ() >= minZ && at.getBlockZ() <= maxZ;
        }
    }

    private record SafePortalRoom(ProtectedRegion region, Location fallback) { }

    private record SurfaceCandidate(int x, int z, int y, int spread, int waterSamples, double score) { }

    /** Sólo accesos y piezas críticas. Las ciudades y sus spawners son conquistables. */
    private final List<ProtectedRegion> protectedRegions = new ArrayList<>();
    private final Set<String> protectedBlocks = new HashSet<>();
    private final Map<UUID, Long> protectionMessageCooldown = new HashMap<>();
    private final Map<UUID, Long> rescueCooldown = new HashMap<>();
    private final List<SafePortalRoom> safePortalRooms = new ArrayList<>();
    private final List<Location> overworldCheckpoints = new ArrayList<>();
    private final List<Location> netherCheckpoints = new ArrayList<>();
    private final Map<UUID, Location> playerCheckpoints = new HashMap<>();
    private final Map<UUID, Location> netherPlayerCheckpoints = new HashMap<>();
    private ProtectedRegion overworldProgressionRegion;
    private ProtectedRegion netherProgressionRegion;
    private int overworldRescueY;
    private int netherRescueY;

    private UUID overworldBoss;
    private UUID netherBoss;
    private Location overworldBossHome;
    private Location netherBossHome;
    private ProtectedRegion overworldBossRoom;
    private ProtectedRegion netherBossRoom;
    private Location overworldPortal;
    private Location netherArrival;
    private Location netherPortal;
    private Location overworldReturn;
    private Location endArrival;
    private Location endGuardianHint;
    private boolean netherUnlocked;
    private boolean endUnlocked;
    private boolean netherBossGateUnlocked;
    private boolean netherBossGateOpening;
    private boolean igneousKeyConsumed;
    private Location igneousKeyChest;
    private Location netherKeyLock;
    private Location netherGateCenter;
    private int maintenanceSeconds;
    private boolean netherLockRepairAttempted;
    private BukkitTask maintenanceTask;

    public DimensionDungeonManager(JavaPlugin plugin, AdaptiveDungeonLootManager adaptiveLoot,
                                   BingoGame game, DungeonMinionSpawnerManager spawnerManager) {
        this.plugin = plugin;
        this.adaptiveLoot = adaptiveLoot;
        this.game = game;
        this.spawnerManager = spawnerManager;
        this.cinematics = new CampaignCinematics(plugin, game);
        this.overworldCampaign = new OverworldCampaignProgression(plugin, game, adaptiveLoot, spawnerManager);
        maintenanceTask = Bukkit.getScheduler().runTaskTimer(plugin, this::maintenanceTick, 20L, 20L);
    }

    private void maintenanceTick() {
        // El administrador vive durante toda la vida del plugin, pero la campaña sólo
        // debe mantenerse mientras existe una partida real. Antes, esta tarea seguía
        // intentando actualizar la cerradura cada cinco segundos después de /bingo stop,
        // cuando la arena ya había sido liberada o descargada.
        GameState state = game.getState();
        if (state != GameState.RUNNING && state != GameState.TIEBREAK && state != GameState.ENDED) {
            return;
        }

        contain(overworldBoss, overworldBossHome, overworldBossRoom);
        contain(netherBoss, netherBossHome, netherBossRoom);
        keepPortalRoomsSafe();
        overworldCampaign.tick();
        maintenanceSeconds++;
        if (maintenanceSeconds % 5 == 0) {
            ensureIgneousKeyAvailable();
            ensureNetherLockPresent();
        }
        if (!netherBossGateUnlocked && maintenanceSeconds > 0 && maintenanceSeconds % 180 == 0) {
            game.sendToParticipants(ChatColor.GOLD + "[Bingo] Pista: la Llave Ígnea está en uno de los cofres compartidos de los distritos del Nether.");
        }
    }

    private void contain(UUID id, Location home, ProtectedRegion room) {
        if (id == null || home == null || room == null) return;
        Entity entity = Bukkit.getEntity(id);
        if (entity != null && !entity.isDead() && !room.contains(entity.getLocation())) entity.teleport(home);
    }

    private void keepPortalRoomsSafe() {
        for (SafePortalRoom safe : safePortalRooms) {
            Location center = safe.fallback();
            if (center == null || center.getWorld() == null) continue;
            for (Entity entity : center.getWorld().getNearbyEntities(center, 18, 18, 18)) {
                if (!(entity instanceof Mob mob) || !safe.region().contains(entity.getLocation())) continue;
                if (entity.getUniqueId().equals(overworldBoss) || entity.getUniqueId().equals(netherBoss)) {
                    entity.teleport(safe.fallback());
                    continue;
                }
                if (entity.getScoreboardTags().contains("arlightbingo_dungeon_mob") || entity instanceof Monster) {
                    entity.teleport(safe.fallback());
                    mob.setTarget(null);
                }
            }
        }
    }

    public void prepare(World overworld, World nether, World end) {
        adaptiveLoot.reset();
        spawnerManager.reset();
        protectedRegions.clear();
        protectedBlocks.clear();
        protectionMessageCooldown.clear();
        rescueCooldown.clear();
        safePortalRooms.clear();
        overworldCheckpoints.clear();
        netherCheckpoints.clear();
        playerCheckpoints.clear();
        netherPlayerCheckpoints.clear();
        overworldProgressionRegion = null;
        netherProgressionRegion = null;
        netherUnlocked = false;
        endUnlocked = false;
        netherBossGateUnlocked = false;
        netherBossGateOpening = false;
        igneousKeyConsumed = false;
        igneousKeyChest = null;
        netherKeyLock = null;
        netherGateCenter = null;
        endArrival = null;
        endGuardianHint = null;
        maintenanceSeconds = 0;
        netherLockRepairAttempted = false;
        cinematics.reset();
        overworldCampaign.reset();
        for (Player player : Bukkit.getOnlinePlayers()) {
            CampaignItemBridge.removeOwnership(player, CampaignItemBridge.IGNEOUS_KEY);
            CampaignItemBridge.removeOwnership(player, CampaignItemBridge.DRAGON_KEY);
        }
        overworldBoss = null;
        netherBoss = null;
        if (!plugin.getConfig().getBoolean("dimension-dungeons.enabled", true)) return;

        if (TemplateWorldCloner.isRuntimeClone(overworld)
                && TemplateWorldCloner.isRuntimeClone(nether)
                && TemplateWorldCloner.isRuntimeClone(end)) {
            prepareTemplateCampaign(overworld, nether, end);
            return;
        }

        setBorder(overworld, "overworld", 2800);
        setBorder(nether, "nether", 4096);
        setBorder(end, "end", 2800);

        int owX = plugin.getConfig().getInt("dimension-dungeons.overworld.dungeon-x", 72);
        int owZ = plugin.getConfig().getInt("dimension-dungeons.overworld.dungeon-z", 0);
        int netherX = plugin.getConfig().getInt("dimension-dungeons.nether.dungeon-x", 64);
        int netherY = plugin.getConfig().getInt("dimension-dungeons.nether.dungeon-y", 70);
        int netherZ = plugin.getConfig().getInt("dimension-dungeons.nether.dungeon-z", 0);
        Location owBase = surfaceBase(overworld, owX, owZ);
        Location netherBase = new Location(nether, netherX, netherY, netherZ);

        // Nunca permitimos que la construcción fuerce generación de terreno dentro del
        // tick del servidor. Chunky debe haber preparado por completo las huellas urbanas.
        requirePregeneratedArea(overworld, owBase, 192, 200, "Overworld");
        requirePregeneratedArea(nether, netherBase, 192, 184, "Nether");

        int combatLevels = configuredCombatLevels();
        int bossLevel = combatLevels - 1;
        int portalLevel = combatLevels;

        buildFoundation(owBase, Material.STONE_BRICKS, Material.COBBLESTONE);

        // Punto provisional únicamente para la construcción. El spawn definitivo lo devuelve
        // la ciudad y queda dentro de su plaza de entrada.
        overworldReturn = owBase.clone().add(0, 1, -(CASTLE_RADIUS + 28));

        // 1.17.0: ciudad infestada del Overworld.
        OverworldCastleProgression.Result overworldProgression =
                new OverworldCastleProgression(plugin, adaptiveLoot, spawnerManager)
                        .build(owBase, combatLevels, overworldReturn);
        overworldCheckpoints.addAll(overworldProgression.checkpoints());
        overworldRescueY = overworldProgression.rescueY();
        overworldReturn = overworldProgression.spawnLocation().clone();
        overworldReturn.setYaw(0.0F);
        overworldReturn.setPitch(0.0F);
        overworld.setSpawnLocation(overworldReturn);
        removeLegacyArenaSpawnPlatform(overworld, overworldReturn);

        // 1.18.0: la vieja torre cuadrada del Nether se reemplaza por una ciudadela
        // completa con puente, barrios, túneles de lava, torres y fortaleza vertical.
        NetherCitadelProgression.Result netherProgression =
                new NetherCitadelProgression(plugin, adaptiveLoot, spawnerManager)
                        .build(netherBase, combatLevels);

        overworldPortal = owBase.clone().add(0.5, portalLevel * FLOOR_HEIGHT + 1, 3.5);
        overworldBossHome = owBase.clone().add(0.5, bossLevel * FLOOR_HEIGHT + 1, 0.5);
        netherArrival = netherProgression.arrival().clone();
        netherPortal = netherProgression.portal().clone();
        netherBossHome = netherProgression.bossHome().clone();
        netherCheckpoints.addAll(netherProgression.checkpoints());
        netherRescueY = netherProgression.rescueY();
        netherKeyLock = cloneOf(netherProgression.keyLock());
        netherGateCenter = cloneOf(netherProgression.gateCenter());
        if (!netherProgression.keyChests().isEmpty()) {
            igneousKeyChest = netherProgression.keyChests().get(
                    ThreadLocalRandom.current().nextInt(netherProgression.keyChests().size())).clone();
            CampaignItemBridge.putInChest(igneousKeyChest, CampaignItemBridge.IGNEOUS_KEY, 13);
            plugin.getLogger().info("Llave Ígnea escondida en " + igneousKeyChest.getBlockX() + ", "
                    + igneousKeyChest.getBlockY() + ", " + igneousKeyChest.getBlockZ());
        }

        OverworldCastleProgression.Bounds owBounds = overworldProgression.bounds();
        ProtectedRegion owCastle = new ProtectedRegion(overworld.getName(), owBounds.minX(), owBounds.maxX(),
                owBounds.minY(), owBounds.maxY(), owBounds.minZ(), owBounds.maxZ());
        overworldProgressionRegion = owCastle;
        ProtectedRegion netherCastle = protectedRegion(nether.getName(), netherProgression.bounds());
        netherProgressionRegion = netherCastle;
        // Protección selectiva: no se protegen las ciudades completas. Los jugadores
        // pueden romper spawners, barricadas y edificios; sólo quedan bloqueados los
        // accesos de jefes, portales, cerradura y cofres de misión.
        overworldBossRoom = bossRoomRegion(owBase, bossLevel);
        netherBossRoom = protectedRegion(nether.getName(), netherProgression.bossRoom());
        ProtectedRegion owPortalRoom = portalRoomRegion(owBase, portalLevel);
        ProtectedRegion netherPortalRoom = protectedRegion(nether.getName(), netherProgression.portalRoom());
        protectedRegions.add(overworldBossRoom);
        protectedRegions.add(netherBossRoom);
        protectedRegions.add(owPortalRoom);
        protectedRegions.add(netherPortalRoom);
        for (Location missionChest : netherProgression.keyChests()) protectBlock(missionChest);
        protectBlock(netherKeyLock);
        protectBlock(overworldPortal);
        protectBlock(netherPortal);
        if (netherGateCenter != null) {
            for (int x = -4; x <= 4; x++) for (int y = -1; y <= 7; y++)
                protectBlock(netherGateCenter.clone().add(x, y, 0));
        }
        safePortalRooms.add(new SafePortalRoom(owPortalRoom,
                owBase.clone().add(0.5, bossLevel * FLOOR_HEIGHT + 1, 9.5)));
        safePortalRooms.add(new SafePortalRoom(netherPortalRoom,
                netherProgression.portalFallback().clone()));

        endArrival = end.getSpawnLocation().clone();
        endGuardianHint = endArrival.clone().add(0, 0, 80);

        spawnOverworldBoss(overworldBossHome);
        // El Guardián del Nether NO existe hasta terminar la animación de la cerradura.
        netherBoss = null;
    }

    /**
     * Enlaza la progresión a las estructuras ya presentes en las copias maestras.
     * Aquí no se reconstruye ninguna ciudad, muralla ni arena aprobada visualmente.
     */
    private void prepareTemplateCampaign(World overworld, World nether, World end) {
        setBorder(overworld, "overworld", 2800);
        setBorder(nether, "nether", 4096);
        setBorder(end, "end", 2800);

        Location village = requiredTemplateLocation(overworld,
                "arlight-overworld-template.properties", "village");
        Location surfaceCitadel = requiredTemplateLocation(overworld,
                "arlight-overworld-template.properties", "dungeon");
        Location surfaceBoss = requiredTemplateLocation(overworld,
                "arlight-overworld-template.properties", "boss");
        Location surfacePortal = requiredTemplateLocation(overworld,
                "arlight-overworld-template.properties", "portal");
        Location netherSpawn = requiredTemplateLocation(nether,
                "arlight-nether-template.properties", "spawn");
        Location netherBossLocation = requiredTemplateLocation(nether,
                "arlight-nether-template.properties", "boss");
        Location endPortalLocation = requiredTemplateLocation(nether,
                "arlight-nether-template.properties", "portal");
        Location netherGate = requiredTemplateLocation(nether,
                "arlight-nether-template.properties", "gate");
        Location netherLock = TemplateWorldCloner.readLocation(nether,
                "arlight-nether-template.properties", "lock");
        List<Location> keyChests = TemplateCampaignRepair.ensureNetherKeyChests(nether,
                TemplateWorldCloner.readLocations(nether,
                        "arlight-nether-template.properties", "keys"));
        netherLock = TemplateCampaignRepair.ensureNetherLock(nether, netherLock);

        overworldReturn = centered(village.clone().add(0, 1, 0));
        overworldReturn.setYaw(0.0F);
        overworldReturn.setPitch(0.0F);
        overworld.setSpawnLocation(overworldReturn);
        overworldBossHome = centered(surfaceBoss);
        overworldPortal = centered(surfacePortal);

        netherArrival = TemplateCampaignRepair.ensureNetherSafeArrival(nether, netherSpawn);
        netherPortal = centered(endPortalLocation);
        netherBossHome = centered(netherBossLocation);
        netherGateCenter = centered(netherGate);
        netherKeyLock = netherLock.clone();
        Location endSpawn = TemplateWorldCloner.readLocation(end, "arlight-end-template.properties", "spawn");
        Location endBoss = TemplateWorldCloner.readLocation(end, "arlight-end-template.properties", "boss");
        endArrival = TemplateCampaignRepair.ensureEndSafeArrival(end, endSpawn == null ? end.getSpawnLocation() : endSpawn);
        endGuardianHint = endBoss == null ? endArrival.clone().add(0, 0, 120) : centered(endBoss);

        removeRuntimeBosses(overworld, "arlightbingo_boss_surface");
        removeRuntimeBosses(nether, "arlightbingo_boss_nether");

        overworldBossRoom = regionAround(overworldBossHome, 64, 10, 44);
        netherBossRoom = regionAround(netherBossHome, 62, 10, 42);
        overworldProgressionRegion = new ProtectedRegion(overworld.getName(),
                surfaceBoss.getBlockX() - 235, surfaceBoss.getBlockX() + 235,
                overworld.getMinHeight(), overworld.getMaxHeight() - 1,
                surfaceBoss.getBlockZ() - 360, surfaceBoss.getBlockZ() + 110);
        netherProgressionRegion = new ProtectedRegion(nether.getName(),
                -180, 180, 0, 126, -218, 275);
        overworldRescueY = Math.max(overworld.getMinHeight() + 8,
                overworldBossHome.getBlockY() - 74);
        netherRescueY = 48;

        overworldCheckpoints.add(overworldReturn.clone());
        overworldCheckpoints.add(overworldBossHome.clone().add(0, 0, -88));
        overworldCheckpoints.add(overworldBossHome.clone().add(0, 0, -30));
        overworldCheckpoints.add(overworldPortal.clone().add(0, 0, -7));
        netherCheckpoints.add(netherArrival.clone());
        netherCheckpoints.add(new Location(nether, 0.5D, 64.0D, -104.5D));
        netherCheckpoints.add(new Location(nether, 0.5D, 64.0D, 104.5D));
        netherCheckpoints.add(netherBossHome.clone().add(0, 0, -40));
        netherCheckpoints.add(netherPortal.clone().add(0, 0, -8));

        ProtectedRegion overworldPortalRoom = regionAround(overworldPortal, 10, 4, 10);
        ProtectedRegion netherPortalRoom = regionAround(netherPortal, 10, 4, 10);
        protectedRegions.add(overworldBossRoom);
        protectedRegions.add(netherBossRoom);
        protectedRegions.add(overworldPortalRoom);
        protectedRegions.add(netherPortalRoom);
        safePortalRooms.add(new SafePortalRoom(overworldPortalRoom,
                overworldPortal.clone().add(0, 0, -7)));
        safePortalRooms.add(new SafePortalRoom(netherPortalRoom,
                netherPortal.clone().add(0, 0, -8)));

        closeTemplateNetherGate();
        // La reparación ya aseguró una cerradura funcional (o su respaldo vanilla).
        netherKeyLock = TemplateCampaignRepair.ensureNetherLock(nether, netherKeyLock);
        igneousKeyChest = keyChests.get(
                ThreadLocalRandom.current().nextInt(keyChests.size())).clone();
        if (!CampaignItemBridge.putInChest(
                igneousKeyChest, CampaignItemBridge.IGNEOUS_KEY, 13)) {
            throw new IllegalStateException("No se pudo insertar la Llave Ígnea en el contenedor "
                    + igneousKeyChest.getBlockX() + "," + igneousKeyChest.getBlockY()
                    + "," + igneousKeyChest.getBlockZ() + ".");
        }

        for (Location missionChest : keyChests) protectBlock(missionChest);
        protectBlock(netherKeyLock);
        protectBlock(overworldPortal);
        protectBlock(netherPortal);
        for (int x = -4; x <= 4; x++) for (int y = -1; y <= 7; y++) {
            protectBlock(netherGateCenter.clone().add(x, y, 0));
        }

        overworldBoss = null;
        overworldCampaign.prepare(overworld, overworldReturn, surfaceCitadel,
                overworldBossHome, overworldPortal, () -> spawnOverworldBoss(overworldBossHome));
        netherBoss = null;
        plugin.getLogger().info("Campaña enlazada a las plantillas persistentes con progresión jugable Overworld 1.38.0.");
    }

    private Location requiredTemplateLocation(World world, String marker, String key) {
        Location location = TemplateWorldCloner.readLocation(world, marker, key);
        if (location == null) {
            throw new IllegalStateException("La plantilla " + world.getName()
                    + " no contiene la posición '" + key + "'.");
        }
        return location;
    }

    private Location centered(Location location) {
        return new Location(location.getWorld(), location.getBlockX() + 0.5D,
                location.getY(), location.getBlockZ() + 0.5D,
                location.getYaw(), location.getPitch());
    }

    private ProtectedRegion regionAround(Location center, int radius, int below, int above) {
        return new ProtectedRegion(center.getWorld().getName(),
                center.getBlockX() - radius, center.getBlockX() + radius,
                center.getBlockY() - below, center.getBlockY() + above,
                center.getBlockZ() - radius, center.getBlockZ() + radius);
    }

    private void removeRuntimeBosses(World world, String tag) {
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (entity.getScoreboardTags().contains(tag)
                    || entity.getScoreboardTags().contains("arlightbingo_template_surface_boss")
                    || entity.getScoreboardTags().contains("arlightbingo_template_nether_boss")) {
                entity.remove();
            }
        }
    }

    private void closeTemplateNetherGate() {
        if (netherGateCenter == null) return;
        for (int x = -3; x <= 3; x++) for (int y = 0; y <= 5; y++) {
            netherGateCenter.clone().add(x, y, 0).getBlock().setType(
                    y == 2 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS,
                    false);
        }
    }

    public boolean isNetherUnlocked() { return netherUnlocked; }
    public boolean isEndUnlocked() { return endUnlocked; }
    public Location getNetherArrival() { return cloneOf(netherArrival); }
    public Location getOverworldReturn() { return cloneOf(overworldReturn); }

    private int configuredCombatLevels() {
        return Math.max(2, Math.min(8, plugin.getConfig().getInt("dimension-dungeons.floors", 5)));
    }

    private Location cloneOf(Location location) { return location == null ? null : location.clone(); }

    private ProtectedRegion protectedRegion(String world, NetherCitadelProgression.Bounds bounds) {
        return new ProtectedRegion(world, bounds.minX(), bounds.maxX(), bounds.minY(), bounds.maxY(),
                bounds.minZ(), bounds.maxZ());
    }

    private ProtectedRegion castleRegion(Location base, int combatLevels) {
        int totalHeight = (combatLevels + 1) * FLOOR_HEIGHT + 10;
        return new ProtectedRegion(base.getWorld().getName(), base.getBlockX() - CASTLE_RADIUS - 3,
                base.getBlockX() + CASTLE_RADIUS + 3, base.getBlockY() - 6,
                base.getBlockY() + totalHeight, base.getBlockZ() - CASTLE_RADIUS - 8,
                base.getBlockZ() + CASTLE_RADIUS + 3);
    }

    private ProtectedRegion bossRoomRegion(Location base, int level) {
        int y = base.getBlockY() + level * FLOOR_HEIGHT;
        return new ProtectedRegion(base.getWorld().getName(), base.getBlockX() - 13,
                base.getBlockX() + 13, y, y + FLOOR_HEIGHT - 1,
                base.getBlockZ() - 13, base.getBlockZ() + 13);
    }

    private ProtectedRegion portalRoomRegion(Location base, int level) {
        int y = base.getBlockY() + level * FLOOR_HEIGHT;
        return new ProtectedRegion(base.getWorld().getName(), base.getBlockX() - 13,
                base.getBlockX() + 13, y, y + FLOOR_HEIGHT - 1,
                base.getBlockZ() - 13, base.getBlockZ() + 13);
    }

    private void setBorder(World world, String key, int fallback) {
        if (world == null) return;
        double configured = plugin.getConfig().getDouble("dimension-dungeons.world-borders." + key, fallback);
        double hardMinimum = switch (key.toLowerCase(Locale.ROOT)) {
            case "nether" -> 4096.0D;
            case "overworld", "end" -> 2800.0D;
            default -> 2800.0D;
        };
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(Math.max(hardMinimum, configured));
    }

    private void requirePregeneratedArea(World world, Location center, int radiusX, int radiusZ,
                                         String label) {
        int minChunkX = (center.getBlockX() - radiusX) >> 4;
        int maxChunkX = (center.getBlockX() + radiusX) >> 4;
        int minChunkZ = (center.getBlockZ() - radiusZ) >> 4;
        int maxChunkZ = (center.getBlockZ() + radiusZ) >> 4;
        int missing = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkGenerated(chunkX, chunkZ)) missing++;
            }
        }
        if (missing > 0) {
            throw new IllegalStateException(label + " no está completamente pregenerado: faltan "
                    + missing + " chunks. Se cancela para evitar bloquear Arclight.");
        }
    }

    private Location surfaceBase(World world, int requestedX, int requestedZ) {
        // 1.28.1: la búsqueda de terreno sólo inspecciona chunks que Chunky ya generó.
        // La 1.28.0 recorría hasta 225 candidatos y forzaba load(true) para miles de
        // muestras. En Arclight eso bloqueaba el hilo principal esperando generación
        // de chunks y terminaba activando ServerHangWatchdog.
        int configuredRadius = Math.max(0,
                plugin.getConfig().getInt("world-pool.pregeneration.overworld-radius", 352));
        int citySafetyMargin = Math.max(176, Math.min(256,
                plugin.getConfig().getInt("overworld-city.terrain.city-safety-margin", 192)));
        int requestedDistance = Math.max(Math.abs(requestedX), Math.abs(requestedZ));
        int generatedSearchLimit = Math.max(0, configuredRadius - citySafetyMargin - requestedDistance);
        int requestedSearchRadius = Math.max(0, Math.min(384,
                plugin.getConfig().getInt("overworld-city.terrain.search-radius", 192)));
        int searchRadius = Math.min(requestedSearchRadius, generatedSearchLimit);
        int candidateStep = Math.max(48, Math.min(128,
                plugin.getConfig().getInt("overworld-city.terrain.search-step", 64)));
        int maxCandidates = Math.max(1, Math.min(25,
                plugin.getConfig().getInt("overworld-city.terrain.max-candidates", 9)));

        List<int[]> offsets = new ArrayList<>();
        offsets.add(new int[]{0, 0});
        for (int radius = candidateStep; radius <= searchRadius && offsets.size() < maxCandidates;
             radius += candidateStep) {
            int[][] ring = {
                    { radius, 0}, {-radius, 0}, {0, radius}, {0, -radius},
                    { radius, radius}, { radius, -radius}, {-radius, radius}, {-radius, -radius}
            };
            for (int[] offset : ring) {
                if (offsets.size() >= maxCandidates) break;
                offsets.add(offset);
            }
        }

        SurfaceCandidate best = null;
        int skippedUngenerated = 0;
        for (int[] offset : offsets) {
            SurfaceCandidate candidate = evaluateSurfaceCandidate(world,
                    requestedX + offset[0], requestedZ + offset[1], requestedX, requestedZ);
            if (candidate == null) {
                skippedUngenerated++;
                continue;
            }
            if (best == null || candidate.score() < best.score()) best = candidate;
        }

        if (best == null) {
            Location spawn = world.getSpawnLocation();
            int fallbackX = requestedX;
            int fallbackZ = requestedZ;
            if (!world.isChunkGenerated(fallbackX >> 4, fallbackZ >> 4)) {
                fallbackX = spawn.getBlockX();
                fallbackZ = spawn.getBlockZ();
            }
            int y = naturalSurfaceY(world, fallbackX, fallbackZ) + 1;
            plugin.getLogger().warning("No había candidatos de terreno ya pregenerados para la ciudad. "
                    + "Se usa el punto seguro " + fallbackX + ", " + y + ", " + fallbackZ
                    + " sin generar chunks nuevos en el hilo principal.");
            return new Location(world, fallbackX, y, fallbackZ);
        }

        plugin.getLogger().info("Ciudad Overworld adaptada al terreno en " + best.x() + ", "
                + best.y() + ", " + best.z() + " (variación " + best.spread()
                + ", agua " + best.waterSamples() + ", candidatos omitidos "
                + skippedUngenerated + ")");
        return new Location(world, best.x(), Math.min(world.getMaxHeight() - 96, best.y() + 1), best.z());
    }

    private SurfaceCandidate evaluateSurfaceCandidate(World world, int centerX, int centerZ,
                                                       int requestedX, int requestedZ) {
        // Nueve muestras bastan para comparar llanura/agua/desnivel y evitan las más de
        // 18 000 consultas de altura de la 1.28.0. Nunca se llama load(true).
        int[][] samples = {
                {-72, -80}, {0, -80}, {72, -80},
                {-72,   0}, {0,   0}, {72,   0},
                {-72,  80}, {0,  80}, {72,  80}
        };
        for (int[] sample : samples) {
            int chunkX = (centerX + sample[0]) >> 4;
            int chunkZ = (centerZ + sample[1]) >> 4;
            if (!world.isChunkGenerated(chunkX, chunkZ)) return null;
        }

        List<Integer> heights = new ArrayList<>(samples.length);
        int water = 0;
        for (int[] sample : samples) {
            int x = centerX + sample[0];
            int z = centerZ + sample[1];
            int y = naturalSurfaceY(world, x, z);
            heights.add(y);
            Material top = world.getBlockAt(x, Math.max(world.getMinHeight(), y), z).getType();
            if (top == Material.WATER
                    || world.getBlockAt(x, Math.min(world.getMaxHeight() - 1, y + 1), z).getType() == Material.WATER) {
                water++;
            }
        }
        heights.sort(Integer::compareTo);
        int low = heights.get(1);
        int median = heights.get(heights.size() / 2);
        int high = heights.get(heights.size() - 2);
        int spread = high - low;
        double distance = Math.hypot(centerX - requestedX, centerZ - requestedZ);
        double highPenalty = Math.max(0, median - 92) * 5.0D;
        double cliffPenalty = Math.max(0, spread - 16) * 45.0D;
        double waterPenalty = water * 180.0D + (water > 3 ? 5000.0D : 0.0D);
        double score = spread * 22.0D + cliffPenalty + waterPenalty + highPenalty + distance * .20D;
        return new SurfaceCandidate(centerX, centerZ, median, spread, water, score);
    }

    private int naturalSurfaceY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        while (y > world.getMinHeight() + 1) {
            Material material = world.getBlockAt(x, y, z).getType();
            String name = material.name();
            // El agua cuenta como altura útil para que una ciudad cercana a un lago se
            // construya por encima de su superficie y después rellene el fondo.
            if (material == Material.WATER) return y;
            boolean decoration = material == Material.AIR || material == Material.CAVE_AIR
                    || material == Material.VOID_AIR || material == Material.LAVA
                    || name.endsWith("_LEAVES")
                    || name.endsWith("_LOG") || name.endsWith("_WOOD")
                    || name.endsWith("_SAPLING") || name.endsWith("_FLOWER")
                    || name.endsWith("_GRASS") || name.endsWith("_VINE")
                    || name.endsWith("_VINES") || name.endsWith("_BUSH")
                    || name.endsWith("_MUSHROOM") || name.endsWith("_ROOTS")
                    || name.equals("SNOW");
            if (!decoration && material.isSolid()) return y;
            y--;
        }
        return y;
    }

    private void removeLegacyArenaSpawnPlatform(World world, Location newSpawn) {
        if (!plugin.getConfig().getBoolean("overworld-city.remove-legacy-floating-spawn", true)) return;
        if (newSpawn.distanceSquared(new Location(world, 0.5, 100, 0.5)) < 32 * 32) return;
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
            Block floor = world.getBlockAt(x, 99, z);
            if (floor.getType() == Material.STONE_BRICKS || floor.getType() == Material.COBBLESTONE) {
                floor.setType(Material.AIR, false);
            }
            for (int y = 100; y <= 103; y++) {
                Block block = world.getBlockAt(x, y, z);
                if (block.getType() == Material.STONE_BRICKS || block.getType() == Material.COBBLESTONE
                        || block.getType() == Material.STONE_BRICK_WALL || block.getType() == Material.LANTERN) {
                    block.setType(Material.AIR, false);
                }
            }
        }
    }

    private void buildSafeArrival(Location center, Material floor) {
        if (center == null || center.getWorld() == null) return;
        for (int z = -7; z <= 7; z++) for (int x = -4; x <= 4; x++) {
            center.clone().add(x, -1, z).getBlock().setType(floor, false);
            for (int y = 0; y <= 5; y++) center.clone().add(x, y, z).getBlock().setType(Material.AIR, false);
        }
        for (int z = -7; z <= 7; z++) {
            center.clone().add(-5, 0, z).getBlock().setType(floor, false);
            center.clone().add(5, 0, z).getBlock().setType(floor, false);
        }
    }

    private void buildFoundation(Location base, Material supportMaterial, Material foundationMaterial) {
        for (int x = -CASTLE_RADIUS - 2; x <= CASTLE_RADIUS + 2; x++) {
            for (int z = -CASTLE_RADIUS - 2; z <= CASTLE_RADIUS + 2; z++) {
                base.clone().add(x, -1, z).getBlock().setType(foundationMaterial, false);
            }
        }
        for (int x : new int[]{-14, -9, 0, 9, 14}) for (int z : new int[]{-14, -9, 0, 9, 14}) {
            Location support = base.clone().add(x, -2, z);
            for (int depth = 0; depth < 96 && support.getBlockY() > base.getWorld().getMinHeight(); depth++) {
                if (support.getBlock().getType().isSolid()) break;
                support.getBlock().setType(supportMaterial, false);
                support.subtract(0, 1, 0);
            }
        }
    }

    private void buildCastle(Location base, boolean nether, int combatLevels) {
        Material wall = nether ? Material.POLISHED_BLACKSTONE_BRICKS : Material.STONE_BRICKS;
        Material accent = nether ? Material.GILDED_BLACKSTONE : Material.MOSSY_STONE_BRICKS;
        Material floor = nether ? Material.NETHER_BRICKS : Material.COBBLESTONE;
        Material pillar = nether ? Material.POLISHED_BASALT : Material.CHISELED_STONE_BRICKS;
        Material light = nether ? Material.SOUL_LANTERN : Material.LANTERN;
        Material window = nether ? Material.RED_STAINED_GLASS_PANE : Material.CYAN_STAINED_GLASS_PANE;
        int bossLevel = combatLevels - 1;
        int portalLevel = combatLevels;
        int totalLevels = combatLevels + 1;
        int roofY = totalLevels * FLOOR_HEIGHT;

        // Casco principal 31x31 con muros de dos bloques, ventanas y pisos reforzados.
        for (int level = 0; level < totalLevels; level++) {
            int y0 = level * FLOOR_HEIGHT;
            for (int x = -CASTLE_RADIUS; x <= CASTLE_RADIUS; x++) {
                for (int z = -CASTLE_RADIUS; z <= CASTLE_RADIUS; z++) {
                    Material floorMaterial = ((x + z + level) & 3) == 0 ? accent : floor;
                    base.clone().add(x, y0, z).getBlock().setType(floorMaterial, false);
                    for (int y = 1; y < FLOOR_HEIGHT; y++) {
                        boolean wallBand = Math.abs(x) >= CASTLE_RADIUS - 1 || Math.abs(z) >= CASTLE_RADIUS - 1;
                        Material material = Material.AIR;
                        if (wallBand) {
                            boolean windowSlot = y >= 3 && y <= 4
                                    && ((Math.abs(x) == CASTLE_RADIUS || Math.abs(z) == CASTLE_RADIUS)
                                    && ((x + z) % 5 == 0));
                            material = windowSlot ? window : ((y == 2 || y == 6) ? accent : wall);
                        }
                        base.clone().add(x, y0 + y, z).getBlock().setType(material, false);
                    }
                }
            }

            decorateFloor(base, y0, level, bossLevel, portalLevel, nether, wall, accent, pillar, light);
        }

        // Entrada monumental y puente cubierto.
        for (int z = -(CASTLE_RADIUS + 8); z <= -CASTLE_RADIUS; z++) {
            for (int x = -4; x <= 4; x++) {
                base.clone().add(x, 0, z).getBlock().setType(floor, false);
                for (int y = 1; y <= 6; y++) base.clone().add(x, y, z).getBlock().setType(Material.AIR, false);
                if (Math.abs(x) == 4) for (int y = 1; y <= 6; y++)
                    base.clone().add(x, y, z).getBlock().setType(wall, false);
            }
        }
        clearGate(base, 0);
        buildGatehouse(base, wall, accent, pillar);

        // Escaleras de piedra de dos bloques de ancho entre todos los pisos.
        for (int level = 0; level < totalLevels - 1; level++) {
            buildStairFlight(base, level * FLOOR_HEIGHT, nether);
        }

        // Techo doble, almenas y cuatro torres elevadas.
        for (int x = -CASTLE_RADIUS; x <= CASTLE_RADIUS; x++) for (int z = -CASTLE_RADIUS; z <= CASTLE_RADIUS; z++) {
            base.clone().add(x, roofY, z).getBlock().setType(wall, false);
            base.clone().add(x, roofY + 1, z).getBlock().setType(
                    Math.abs(x) >= CASTLE_RADIUS - 1 || Math.abs(z) >= CASTLE_RADIUS - 1 ? accent : floor, false);
            if ((Math.abs(x) == CASTLE_RADIUS || Math.abs(z) == CASTLE_RADIUS) && ((x + z) & 1) == 0) {
                base.clone().add(x, roofY + 2, z).getBlock().setType(wall, false);
            }
        }
        for (int sx : new int[]{-12, 12}) for (int sz : new int[]{-12, 12}) {
            buildTurretTop(base.clone().add(sx, roofY + 1, sz), wall, accent);
        }

        // Spawners únicamente en los pisos anteriores a la sala del jefe.
        int spawnersPerFloor = Math.max(1, Math.min(4,
                plugin.getConfig().getInt("dimension-dungeons.spawners-per-floor", 2)));
        List<String> types = nether
                ? List.of("gilded_piglin_minion", "gilded_hoglin_rider_minion")
                : List.of("emerald_zombie_minion", "emerald_creeper_minion", "emerald_skeleton_archer_minion", "mossbound_spider_minion");
        String group = nether ? "nether" : "surface";
        int[][] points = {{-8, -7}, {8, -7}, {-8, 7}, {8, 7}};
        for (int level = 0; level < bossLevel; level++) {
            int y = level * FLOOR_HEIGHT + 1;
            for (int i = 0; i < spawnersPerFloor; i++) {
                spawnerManager.register(base.clone().add(points[i][0], y, points[i][1]), group, types);
            }
            AdaptiveDungeonLootManager.ChestTier tier = level >= bossLevel - 1
                    ? AdaptiveDungeonLootManager.ChestTier.EPIC
                    : (level == 0 ? AdaptiveDungeonLootManager.ChestTier.COMMON
                    : AdaptiveDungeonLootManager.ChestTier.RARE);
            placeChest(base.clone().add(-10, y, 10), nether, tier);
            placeChest(base.clone().add(10, y, 10), nether, tier);
        }

        // Cuatro cofres Lootr en la sala segura del portal.
        int portalY = portalLevel * FLOOR_HEIGHT + 1;
        for (int[] point : new int[][]{{-8, -8}, {8, -8}, {-8, 8}, {8, 8}}) {
            placeChest(base.clone().add(point[0], portalY, point[1]), nether, AdaptiveDungeonLootManager.ChestTier.LEGENDARY);
        }
    }


    private void decorateFloor(Location base, int y0, int level, int bossLevel, int portalLevel,
                               boolean nether, Material wall, Material accent, Material pillar, Material light) {
        // Pilares, arcos y lámparas hacen que cada nivel parezca un salón de castillo.
        for (int x : new int[]{-11, 11}) for (int z : new int[]{-11, 11}) {
            for (int y = 1; y <= 6; y++) base.clone().add(x, y0 + y, z).getBlock().setType(pillar, false);
            base.clone().add(x, y0 + 5, z + (z < 0 ? 1 : -1)).getBlock().setType(light, false);
        }

        if (level < bossLevel) {
            // Cuatro alcobas conectadas por un gran salón central.
            for (int x = -12; x <= 12; x++) {
                if (Math.abs(x) <= 3) continue;
                for (int y = 1; y <= 4; y++) base.clone().add(x, y0 + y, 0).getBlock().setType(
                        y == 4 ? accent : wall, false);
            }
            for (int z = -12; z <= 12; z++) {
                if (Math.abs(z) <= 3) continue;
                for (int y = 1; y <= 4; y++) base.clone().add(0, y0 + y, z).getBlock().setType(
                        y == 4 ? accent : wall, false);
            }
        } else if (level == bossLevel) {
            // Sala del jefe completamente abierta, con trono y arena de 25x25.
            for (int z = 8; z <= 12; z++) for (int x = -5; x <= 5; x++) {
                base.clone().add(x, y0 + 1, z).getBlock().setType(accent, false);
            }
            for (int y = 1; y <= 6; y++) {
                base.clone().add(-6, y0 + y, 11).getBlock().setType(pillar, false);
                base.clone().add(6, y0 + y, 11).getBlock().setType(pillar, false);
            }
            base.clone().add(0, y0 + 2, 12).getBlock().setType(
                    nether ? Material.NETHER_GOLD_ORE : Material.EMERALD_BLOCK, false);
            for (int x = -12; x <= 12; x += 4) {
                base.clone().add(x, y0 + 1, -11).getBlock().setType(
                        nether ? Material.SOUL_FIRE : Material.CAMPFIRE, false);
            }
        } else if (level == portalLevel) {
            // Sala del portal: abierta, iluminada y sin puntos de aparición.
            for (int x = -11; x <= 11; x++) for (int z = -11; z <= 11; z++) {
                if (Math.abs(x) == 11 || Math.abs(z) == 11) {
                    base.clone().add(x, y0 + 1, z).getBlock().setType(accent, false);
                }
            }
            for (int[] point : new int[][]{{-5, -5}, {5, -5}, {-5, 5}, {5, 5}}) {
                base.clone().add(point[0], y0 + 1, point[1]).getBlock().setType(light, false);
            }
        }
    }

    private void clearGate(Location base, int y0) {
        for (int x = -3; x <= 3; x++) for (int y = 1; y <= 6; y++) {
            base.clone().add(x, y0 + y, -CASTLE_RADIUS).getBlock().setType(Material.AIR, false);
            base.clone().add(x, y0 + y, -(CASTLE_RADIUS - 1)).getBlock().setType(Material.AIR, false);
        }
    }

    private void buildGatehouse(Location base, Material wall, Material accent, Material pillar) {
        for (int x = -7; x <= 7; x++) for (int y = 1; y <= 10; y++) {
            boolean archOpening = Math.abs(x) <= 3 && y <= 6;
            base.clone().add(x, y, -CASTLE_RADIUS - 1).getBlock().setType(
                    archOpening ? Material.AIR : (y == 4 || y == 8 ? accent : wall), false);
        }
        for (int x : new int[]{-7, 7}) for (int z = -CASTLE_RADIUS - 3; z <= -CASTLE_RADIUS + 1; z++) {
            for (int y = 1; y <= 11; y++) base.clone().add(x, y, z).getBlock().setType(pillar, false);
        }
    }

    private void buildStairFlight(Location base, int y0, boolean nether) {
        Material stairsMaterial = nether ? Material.POLISHED_BLACKSTONE_BRICK_STAIRS : Material.STONE_BRICK_STAIRS;
        Material support = nether ? Material.POLISHED_BLACKSTONE_BRICKS : Material.STONE_BRICKS;
        for (int step = 0; step < FLOOR_HEIGHT; step++) {
            int z = -5 + step;
            int y = y0 + 1 + step;
            for (int x : new int[]{10, 11}) {
                Location stair = base.clone().add(x, y, z);
                stair.getBlock().setType(stairsMaterial, false);
                if (stair.getBlock().getBlockData() instanceof Stairs data) {
                    data.setFacing(org.bukkit.block.BlockFace.SOUTH);
                    stair.getBlock().setBlockData(data, false);
                }
                stair.clone().add(0, -1, 0).getBlock().setType(support, false);
                for (int clear = 1; clear <= 3; clear++) stair.clone().add(0, clear, 0).getBlock().setType(Material.AIR, false);
            }
            base.clone().add(9, y, z).getBlock().setType(support, false);
            base.clone().add(12, y, z).getBlock().setType(support, false);
        }
    }

    private void buildTurretTop(Location center, Material wall, Material accent) {
        for (int y = 0; y <= 5; y++) for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
            if (y == 0 || (edge && y <= 4)) center.clone().add(x, y, z).getBlock().setType(
                    y == 2 ? accent : wall, false);
        }
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            if ((Math.abs(x) == 3 || Math.abs(z) == 3) && ((x + z) & 1) == 0) {
                center.clone().add(x, 5, z).getBlock().setType(wall, false);
            }
        }
    }

    private void placeChest(Location at, boolean nether, boolean finalReward) {
        placeChest(at, nether, finalReward ? AdaptiveDungeonLootManager.ChestTier.LEGENDARY
                : AdaptiveDungeonLootManager.ChestTier.COMMON);
    }

    private void placeChest(Location at, boolean nether, AdaptiveDungeonLootManager.ChestTier tier) {
        adaptiveLoot.placeCustomChest(at, nether ? AdaptiveDungeonLootManager.DimensionGroup.NETHER
                : AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD, tier);
    }

    private void spawnOverworldBoss(Location at) {
        Entity spawned = summonArlightBoss(at, "surface_guardian", "arlightbingo_boss_surface", "overworld");
        if (!(spawned instanceof Mob boss)) {
            registerBossLater(at, "arlightbingo_boss_surface", true);
            return;
        }
        configureBossBukkit(boss, ChatColor.DARK_GREEN + "Guardián de la superficie");
        overworldBoss = boss.getUniqueId();
    }

    private void spawnNetherBoss(Location at) {
        Entity spawned = summonArlightBoss(at, "nether_guardian", "arlightbingo_boss_nether", "nether");
        if (!(spawned instanceof Mob boss)) {
            registerBossLater(at, "arlightbingo_boss_nether", false);
            return;
        }
        configureBossBukkit(boss, ChatColor.DARK_RED + "Guardián del Nether");
        netherBoss = boss.getUniqueId();
    }

    private void configureBossBukkit(Mob boss, String name) {
        boss.setCustomName(name);
        boss.setCustomNameVisible(true);
        boss.setPersistent(true);
        boss.setRemoveWhenFarAway(false);
        boss.setAI(false);
        boss.setGlowing(true);
    }

    private Entity summonArlightBoss(Location at, String entityId, String tag, String profileKey) {
        BossTuning.BossProfile profile = BossTuning.boss(plugin, game, profileKey);
        return summonArlightEntity(at, entityId, List.of(tag), BossTuning.bossNbt(profile));
    }

    private Entity summonArlightEntity(Location at, String entityId, List<String> tags, String extraNbt) {
        if (at.getWorld() == null || tags.isEmpty()) return null;
        Set<UUID> before = new HashSet<>();
        for (Entity entity : at.getWorld().getNearbyEntities(at, 6, 6, 6)) before.add(entity.getUniqueId());
        String nbtTags = tags.stream().map(tag -> "\"" + tag + "\"").collect(Collectors.joining(","));
        String command;
        if (!at.getWorld().getPlayers().isEmpty()) {
            Player anchor = at.getWorld().getPlayers().get(0);
            command = String.format(Locale.ROOT,
                    "execute at %s positioned %.2f %.2f %.2f run summon arlightbosses:%s ~ ~ ~ " +
                            "{Tags:[%s],PersistenceRequired:1b%s}",
                    anchor.getName(), at.getX(), at.getY(), at.getZ(), entityId, nbtTags, extraNbt);
        } else {
            command = String.format(Locale.ROOT,
                    "execute in %s run summon arlightbosses:%s %.2f %.2f %.2f " +
                            "{Tags:[%s],PersistenceRequired:1b%s}",
                    at.getWorld().getKey(), entityId, at.getX(), at.getY(), at.getZ(), nbtTags, extraNbt);
        }
        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) return null;
        for (Entity entity : at.getWorld().getNearbyEntities(at, 6, 6, 6)) {
            if (!before.contains(entity.getUniqueId()) && entity.getScoreboardTags().contains(tags.get(0))) return entity;
        }
        return null;
    }

    private void registerBossLater(Location at, String tag, boolean overworld) {
        for (long delay : new long[]{2L, 10L, 30L}) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (at.getWorld() == null) return;
            for (Entity entity : at.getWorld().getNearbyEntities(at, 8, 8, 8)) {
                if (!entity.getScoreboardTags().contains(tag) || !(entity instanceof Mob boss)) continue;
                configureBossBukkit(boss, overworld
                        ? ChatColor.DARK_GREEN + "Guardián de la superficie"
                        : ChatColor.DARK_RED + "Guardián del Nether");
                if (overworld) overworldBoss = boss.getUniqueId(); else netherBoss = boss.getUniqueId();
                return;
            }
        }, delay);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDungeonEnter(PlayerMoveEvent event) {
        // Igual que en GameListener#onTiebreakMove: nos salteamos los movimientos que
        // solo giran la camara sin cambiar de bloque, que son la mayoria de los eventos.
        if (event.getTo() == null
                || (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ())) {
            return;
        }
        updateCheckpointAndRescue(event.getPlayer());
        Player movingPlayer = event.getPlayer();
        if (!cinematics.isIntroPlayed(movingPlayer) && overworldReturn != null
                && movingPlayer.getWorld() == overworldReturn.getWorld()
                && movingPlayer.getLocation().distanceSquared(overworldReturn) <= 24 * 24) {
            cinematics.playOverworldIntro(movingPlayer, overworldReturn);
        }
        if (netherArrival != null && movingPlayer.getWorld() == netherArrival.getWorld()
                && movingPlayer.getLocation().distanceSquared(netherArrival) <= 42 * 42) {
            cinematics.playNetherIntro(movingPlayer, netherArrival, netherKeyLock);
        }
        if (endArrival != null && movingPlayer.getWorld() == endArrival.getWorld()
                && movingPlayer.getLocation().distanceSquared(endArrival) <= 52 * 52) {
            cinematics.playEndIntro(movingPlayer, endArrival, endGuardianHint);
        }
        activateBoss(event.getPlayer(), overworldBoss, overworldBossHome, overworldBossRoom, "surface_boss_support");
        if (netherBossGateUnlocked) {
            activateBoss(event.getPlayer(), netherBoss, netherBossHome, netherBossRoom, "nether_boss_support");
        }
    }

    private void updateCheckpointAndRescue(Player player) {
        if (player == null || game.getTeamOf(player) == null) return;
        Location current = player.getLocation();

        if (overworldProgressionRegion != null && overworldProgressionRegion.containsXZ(current)) {
            updateCheckpoint(player, current, overworldCheckpoints, playerCheckpoints);
            if (current.getY() < overworldRescueY) {
                rescue(player, playerCheckpoints.getOrDefault(player.getUniqueId(), overworldReturn),
                        "la ciudad infestada");
            }
            return;
        }

        if (netherProgressionRegion != null && netherProgressionRegion.containsXZ(current)) {
            updateCheckpoint(player, current, netherCheckpoints, netherPlayerCheckpoints);
            if (current.getY() < netherRescueY) {
                rescue(player, netherPlayerCheckpoints.getOrDefault(player.getUniqueId(), netherArrival),
                        "la ciudadela del Nether");
            }
        }
    }

    private void updateCheckpoint(Player player, Location current, List<Location> checkpoints,
                                  Map<UUID, Location> personalCheckpoints) {
        for (Location checkpoint : checkpoints) {
            if (checkpoint.getWorld() == current.getWorld()
                    && Math.abs(checkpoint.getY() - current.getY()) <= 4.0D
                    && checkpoint.distanceSquared(current) <= 25.0D) {
                personalCheckpoints.put(player.getUniqueId(), checkpoint.clone());
            }
        }
    }

    private void rescue(Player player, Location fallback, String place) {
        if (fallback == null || fallback.getWorld() == null) return;
        long now = System.currentTimeMillis();
        if (rescueCooldown.getOrDefault(player.getUniqueId(), 0L) > now) return;
        rescueCooldown.put(player.getUniqueId(), now + 5000L);
        Location safe = findSafeRescue(fallback);
        player.setVelocity(new Vector(0, 0, 0));
        player.teleport(safe);
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0.0F);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 80, 4, false, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, true));
        player.sendMessage(ChatColor.YELLOW + "[Bingo] Regresaste al último punto seguro de " + place + ".");
    }

    private Location findSafeRescue(Location original) {
        Location safe = original.clone();
        safe.setX(safe.getBlockX() + 0.5D);
        safe.setZ(safe.getBlockZ() + 0.5D);
        for (int rise = 0; rise <= 8; rise++) {
            Location candidate = safe.clone().add(0, rise, 0);
            if (candidate.clone().add(0, -1, 0).getBlock().getType().isSolid()
                    && candidate.getBlock().isPassable()
                    && candidate.clone().add(0, 1, 0).getBlock().isPassable()) return candidate;
        }
        safe.clone().add(0, -1, 0).getBlock().setType(Material.POLISHED_ANDESITE, false);
        safe.getBlock().setType(Material.AIR, false);
        safe.clone().add(0, 1, 0).getBlock().setType(Material.AIR, false);
        return safe;
    }

    private void activateBoss(Player player, UUID bossId, Location home, ProtectedRegion room, String supportGroup) {
        if (bossId == null || home == null || room == null || !room.contains(player.getLocation())) return;
        Entity entity = Bukkit.getEntity(bossId);
        if (!(entity instanceof Mob boss) || boss.isDead()) return;
        if (!room.contains(boss.getLocation())) boss.teleport(home);
        if (!boss.hasAI()) boss.setAI(true);
        if (supportGroup != null) spawnerManager.activateGroup(supportGroup);
        boss.setTarget(player);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCampaignKeyUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Location clicked = event.getClickedBlock().getLocation();
        Player player = event.getPlayer();
        if (game.getTeamOf(player) == null) return;

        // La llave se entrega al primer participante que abre el cofre elegido. Esto
        // conserva el hallazgo aleatorio incluso cuando Lootr usa inventarios personales.
        if (!igneousKeyConsumed && !netherBossGateUnlocked && sameBlock(clicked, igneousKeyChest)) {
            if (!anyParticipantHasKey(CampaignItemBridge.IGNEOUS_KEY, clicked.getWorld())) {
                CampaignItemBridge.clearChestSlot(igneousKeyChest, 13);
                CampaignItemBridge.give(player, CampaignItemBridge.IGNEOUS_KEY);
                player.sendMessage(ChatColor.GOLD + "Encontraste la Llave Ígnea Legendaria.");
                game.sendToParticipants(ChatColor.GOLD + "[Bingo] " + ChatColor.YELLOW
                        + player.getName() + " encontró la llave de la sala del Guardián del Nether.");
                player.getWorld().playSound(clicked, org.bukkit.Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.2F, 0.65F);
            }
            return;
        }

        if (netherKeyLock == null || netherBossGateUnlocked || netherBossGateOpening) return;
        if (clicked.getWorld() != netherKeyLock.getWorld() || clicked.distanceSquared(netherKeyLock) > 2.25D) return;
        event.setCancelled(true);
        if (!CampaignItemBridge.has(player, CampaignItemBridge.IGNEOUS_KEY)) {
            player.sendMessage(ChatColor.RED + "Necesitas la Llave Ígnea Legendaria escondida en la ciudad.");
            return;
        }
        if (!CampaignItemBridge.consume(player, CampaignItemBridge.IGNEOUS_KEY)) return;
        igneousKeyConsumed = true;
        netherBossGateOpening = true;
        beginAnimatedNetherUnlock(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCampaignKeyDrop(PlayerDropItemEvent event) {
        if (CampaignItemBridge.matches(event.getItemDrop().getItemStack(), CampaignItemBridge.IGNEOUS_KEY)
                || CampaignItemBridge.matches(event.getItemDrop().getItemStack(), CampaignItemBridge.DRAGON_KEY)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Las llaves de misión no pueden tirarse.");
        }
    }


    @EventHandler
    public void onCampaignCarrierQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (CampaignItemBridge.has(player, CampaignItemBridge.IGNEOUS_KEY)
                && !igneousKeyConsumed && !netherBossGateUnlocked) {
            CampaignItemBridge.removeOwnership(player, CampaignItemBridge.IGNEOUS_KEY);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (igneousKeyChest != null && igneousKeyChest.getWorld() != null) {
                    CampaignItemBridge.putInChest(igneousKeyChest, CampaignItemBridge.IGNEOUS_KEY, 13);
                } else {
                    plugin.getLogger().warning("No se pudo devolver la Llave Ígnea: el cofre de misión no está disponible.");
                }
            });
        }
    }

    private boolean anyParticipantHasKey(String id, World world) {
        if (world == null) return false;
        for (Player participant : world.getPlayers()) {
            if (game.getTeamOf(participant) != null && CampaignItemBridge.has(participant, id)) return true;
        }
        return false;
    }

    private boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() == b.getWorld()
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private void beginAnimatedNetherUnlock(Player player) {
        if (netherKeyLock == null || netherKeyLock.getWorld() == null) {
            netherBossGateOpening = false;
            CampaignItemBridge.give(player, CampaignItemBridge.IGNEOUS_KEY);
            igneousKeyConsumed = false;
            return;
        }
        CampaignItemBridge.setModBlockState(netherKeyLock, CampaignItemBridge.NETHER_DUNGEON_LOCK
                + "[facing=north,lock_state=opening]");
        World world = netherKeyLock.getWorld();
        Location effect = netherKeyLock.clone().add(0.5, 1.0, 0.5);
        world.playSound(effect, org.bukkit.Sound.BLOCK_CHAIN_PLACE, 1.7F, 0.55F);
        world.playSound(effect, org.bukkit.Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5F, 0.65F);
        world.spawnParticle(org.bukkit.Particle.LAVA, effect, 18, 0.8, 0.9, 0.8, 0.02);
        world.spawnParticle(org.bukkit.Particle.SMOKE, effect, 35, 0.8, 1.0, 0.8, 0.03);

        // La animación del modelo dura 3.2 s. El portón empieza a elevarse al final del giro.
        Bukkit.getScheduler().runTaskLater(plugin, () -> animateNetherBossGateOpening(player, 0), 44L);
    }

    private void animateNetherBossGateOpening(Player player, int layer) {
        if (netherGateCenter == null || netherGateCenter.getWorld() == null) {
            netherBossGateOpening = false;
            return;
        }
        World world = netherGateCenter.getWorld();
        if (layer <= 5) {
            // Elimina una capa por vez, desde abajo, como un portón pesado que se eleva.
            for (int x = -3; x <= 3; x++) {
                netherGateCenter.clone().add(x, layer, 0).getBlock().setType(Material.AIR, false);
            }
            Location soundAt = netherGateCenter.clone().add(0.5, layer + 0.5, 0.5);
            world.playSound(soundAt, org.bukkit.Sound.BLOCK_PISTON_EXTEND, 1.2F, 0.55F + layer * 0.035F);
            world.playSound(soundAt, org.bukkit.Sound.BLOCK_CHAIN_BREAK, 0.9F, 0.65F);
            world.spawnParticle(org.bukkit.Particle.LARGE_SMOKE, soundAt, 14, 2.8, 0.25, 0.35, 0.02);
            Bukkit.getScheduler().runTaskLater(plugin, () -> animateNetherBossGateOpening(player, layer + 1), 4L);
            return;
        }

        netherBossGateOpening = false;
        netherBossGateUnlocked = true;
        CampaignItemBridge.setModBlockState(netherKeyLock, CampaignItemBridge.NETHER_DUNGEON_LOCK
                + "[facing=north,lock_state=opened]");
        // El jefe aparece únicamente ahora; antes de usar la llave no existe entidad que matar.
        if (netherBoss == null || Bukkit.getEntity(netherBoss) == null) spawnNetherBoss(netherBossHome);
        game.sendToParticipants(ChatColor.GOLD + "[Bingo] " + ChatColor.YELLOW
                + player.getName() + " abrió la sala del Guardián con la Llave Ígnea.");
        Location effect = netherKeyLock.clone().add(0.5, 1.0, 0.5);
        world.playSound(effect, org.bukkit.Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.6F, 0.62F);
        world.playSound(effect, org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 0.9F, 0.45F);
        world.spawnParticle(org.bukkit.Particle.FLAME, effect, 90, 1.2, 1.4, 1.2, 0.05);
    }

    private void openNetherBossGate() {
        if (netherGateCenter == null) return;
        for (int x = -3; x <= 3; x++) for (int y = 0; y <= 5; y++) {
            netherGateCenter.clone().add(x, y, 0).getBlock().setType(Material.AIR, false);
        }
        if (netherKeyLock != null) CampaignItemBridge.setModBlockState(netherKeyLock,
                CampaignItemBridge.NETHER_DUNGEON_LOCK + "[facing=north,lock_state=opened]");
    }

    private void ensureIgneousKeyAvailable() {
        if (igneousKeyConsumed || netherBossGateUnlocked || igneousKeyChest == null) return;
        boolean held = false;
        if (igneousKeyChest.getWorld() != null) {
            for (Player player : igneousKeyChest.getWorld().getPlayers()) {
                if (game.getTeamOf(player) != null && CampaignItemBridge.has(player, CampaignItemBridge.IGNEOUS_KEY)) {
                    held = true; break;
                }
            }
        }
        if (!held && igneousKeyChest.getBlock().getState() instanceof Container container) {
            boolean inChest = java.util.Arrays.stream(container.getInventory().getContents())
                    .anyMatch(stack -> CampaignItemBridge.matches(stack, CampaignItemBridge.IGNEOUS_KEY));
            if (!inChest) CampaignItemBridge.putInChest(igneousKeyChest, CampaignItemBridge.IGNEOUS_KEY, 13);
        }
    }

    private void ensureNetherLockPresent() {
        if (netherKeyLock == null || netherKeyLock.getWorld() == null) return;
        String state = netherBossGateUnlocked ? "opened" : netherBossGateOpening ? "opening" : "locked";
        String expected = CampaignItemBridge.NETHER_DUNGEON_LOCK + "[facing=north,lock_state=" + state + "]";

        if (!CampaignItemBridge.isBlock(netherKeyLock, CampaignItemBridge.NETHER_DUNGEON_LOCK)) {
            // Una cerradura ausente se repara una sola vez por partida. Reintentar cada
            // cinco segundos llenaba la consola con "Could not set the block" cuando
            // Arclight no exponía el bloque modded o el mundo ya estaba descargándose.
            if (netherLockRepairAttempted) return;
            netherLockRepairAttempted = true;
            netherKeyLock = TemplateCampaignRepair.ensureNetherLock(netherKeyLock.getWorld(), netherKeyLock);
            if (!CampaignItemBridge.isBlock(netherKeyLock, CampaignItemBridge.NETHER_DUNGEON_LOCK)) {
                plugin.getLogger().warning("No se pudo restaurar la cerradura modded del Nether; se conserva el respaldo visual sin reintentos repetidos.");
                return;
            }
        }

        // El mantenimiento periódico nunca usa /setblock. Si el puente directo no
        // puede cambiar las propiedades visuales, se conserva el estado existente.
        CampaignItemBridge.updateModBlockStateSilently(netherKeyLock, expected);
    }

    /**
     * Libera todas las referencias de la campaña al terminar una partida. Esto evita
     * que la tarea de mantenimiento conserve mundos de arena descargados y garantiza
     * que no vuelva a tocar cerraduras, cofres o jefes después de /bingo stop.
     */
    public void finishSession() {
        protectedRegions.clear();
        protectedBlocks.clear();
        protectionMessageCooldown.clear();
        rescueCooldown.clear();
        safePortalRooms.clear();
        overworldCheckpoints.clear();
        netherCheckpoints.clear();
        playerCheckpoints.clear();
        netherPlayerCheckpoints.clear();
        overworldProgressionRegion = null;
        netherProgressionRegion = null;
        overworldBoss = null;
        netherBoss = null;
        overworldBossHome = null;
        netherBossHome = null;
        overworldBossRoom = null;
        netherBossRoom = null;
        overworldPortal = null;
        netherArrival = null;
        netherPortal = null;
        overworldReturn = null;
        endArrival = null;
        endGuardianHint = null;
        igneousKeyChest = null;
        netherKeyLock = null;
        netherGateCenter = null;
        netherUnlocked = false;
        endUnlocked = false;
        netherBossGateUnlocked = false;
        netherBossGateOpening = false;
        igneousKeyConsumed = false;
        netherLockRepairAttempted = false;
        maintenanceSeconds = 0;
        cinematics.reset();
        overworldCampaign.reset();
    }

    /** Cancela la tarea global al deshabilitar el plugin. */
    public void shutdown() {
        finishSession();
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }
    }

    private void protectBlock(Location at) {
        if (at != null && at.getWorld() != null) protectedBlocks.add(blockKey(at));
    }

    private String blockKey(Location at) {
        return at.getWorld().getUID() + ":" + at.getBlockX() + ":" + at.getBlockY() + ":" + at.getBlockZ();
    }

    private boolean isProtected(Location at) {
        return at != null && at.getWorld() != null && (protectedBlocks.contains(blockKey(at))
                || protectedRegions.stream().anyMatch(region -> region.contains(at)));
    }

    private void protectedMessage(Player player, String message) {
        long now = System.currentTimeMillis();
        if (protectionMessageCooldown.getOrDefault(player.getUniqueId(), 0L) > now) return;
        protectionMessageCooldown.put(player.getUniqueId(), now + 2500L);
        player.sendMessage(ChatColor.RED + message);
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if ((overworldBoss != null && id.equals(overworldBoss))
                || event.getEntity().getScoreboardTags().contains("arlightbingo_boss_surface")) {
            game.claimGlobalBoss(event.getEntity().getKiller(), "boss_overworld");
            overworldCampaign.onBossDefeated();
            netherUnlocked = true;
            spawnerManager.deactivateGroupPrefix("surface");
            buildNetherPortal(overworldPortal);
            game.sendToParticipants(ChatColor.GOLD + "[Bingo] El portal al Nether ha sido desbloqueado.");
        } else if ((netherBoss != null && id.equals(netherBoss))
                || event.getEntity().getScoreboardTags().contains("arlightbingo_boss_nether")) {
            game.claimGlobalBoss(event.getEntity().getKiller(), "boss_nether");
            endUnlocked = true;
            spawnerManager.deactivateGroupPrefix("nether");
            buildEndPortal(netherPortal);
            game.sendToParticipants(ChatColor.LIGHT_PURPLE + "[Bingo] El portal al End ha sido desbloqueado.");
        }
    }

    private void buildNetherPortal(Location at) {
        if (at == null) return;
        clearPortalDais(at, false);
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 4; y++) {
            boolean frame = Math.abs(x) == 2 || y == 0 || y == 4;
            at.clone().add(x, y, 0).getBlock().setType(frame ? Material.OBSIDIAN : Material.NETHER_PORTAL, false);
        }
    }

    private void buildEndPortal(Location at) {
        if (at == null) return;
        clearPortalDais(at, true);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            org.bukkit.block.Block block = at.clone().add(x, 0, z).getBlock();
            if (Math.abs(x) == 2 || Math.abs(z) == 2) {
                block.setType(Material.END_PORTAL_FRAME, false);
                if (block.getBlockData() instanceof EndPortalFrame frame) {
                    frame.setEye(true);
                    block.setBlockData(frame, false);
                }
            } else block.setType(Material.END_PORTAL, false);
        }
    }

    private void clearPortalDais(Location at, boolean horizontal) {
        Material floor = at.getWorld().getEnvironment() == World.Environment.NETHER
                ? Material.POLISHED_BLACKSTONE_BRICKS : Material.STONE_BRICKS;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            at.clone().add(x, -1, z).getBlock().setType(floor, false);
            for (int y = 0; y <= 5; y++) {
                if (!horizontal || y > 0) at.clone().add(x, y, z).getBlock().setType(Material.AIR, false);
            }
        }
    }

    public String debugStatus() {
        return overworldCampaign.status() + " · NetherUnlocked="+netherUnlocked+", GateOpening="+netherBossGateOpening
                +", GateUnlocked="+netherBossGateUnlocked+", KeyConsumed="+igneousKeyConsumed
                +", NetherBoss="+(netherBoss==null?"none":netherBoss);
    }

    public boolean debugGiveIgneousKey(Player player) {
        if(player==null)return false;
        CampaignItemBridge.give(player,CampaignItemBridge.IGNEOUS_KEY);
        return true;
    }

    public boolean debugUnlockNetherGate(Player player) {
        if(player==null||netherBossGateUnlocked||netherBossGateOpening)return false;
        igneousKeyConsumed=true;netherBossGateOpening=true;beginAnimatedNetherUnlock(player);return true;
    }

    public boolean debugTeleport(Player player,String stage) {
        if(player==null)return false;
        if (overworldCampaign.debugTeleport(player, stage)) return true;
        Location target=switch(stage.toLowerCase(Locale.ROOT)){
            case "overworld","spawn"->overworldReturn;
            case "nether"->netherArrival;
            case "lock"->netherKeyLock==null?null:netherKeyLock.clone().add(0,0,-3);
            case "netherboss"->netherBossHome;
            default->null;
        };
        return target!=null&&target.getWorld()!=null&&player.teleport(target);
    }


    public boolean debugSomitaShow(Player player, String variantName) {
        if (player == null || !(plugin instanceof BingoPlugin bingo)) return false;
        SomitaGuideNetwork.Variant variant = SomitaGuideNetwork.Variant.parse(variantName);
        Location at = guideTestLocation(player, 3.0D, 1.3D);
        bingo.getSomitaGuideNetwork().show(player, "manual_preview", variant,
                SomitaGuideNetwork.Animation.IDLE, at, 1200,
                "Vista previa de Somita " + variant.name().toLowerCase(Locale.ROOT) + ".");
        bingo.getSomitaGuideNetwork().effect(player, appearanceEffect(variant), 36);
        return true;
    }

    public boolean debugSomitaAnimation(Player player, String animationName) {
        if (player == null || !(plugin instanceof BingoPlugin bingo)) return false;
        SomitaGuideNetwork.Animation animation = SomitaGuideNetwork.Animation.parse(animationName);
        bingo.getSomitaGuideNetwork().animate(player, animation, 100,
                "Animación de prueba: " + animation.name().toLowerCase(Locale.ROOT) + ".");
        return true;
    }

    public boolean debugSomitaEffect(Player player, String effectName) {
        if (player == null || !(plugin instanceof BingoPlugin bingo)) return false;
        SomitaGuideNetwork.Effect effect = SomitaGuideNetwork.Effect.parse(effectName);
        Location target = switch (effect) {
            case NETHER_LOCK -> sameWorld(player, netherKeyLock) ? netherKeyLock : guideTargetLocation(player, 7.0D);
            case END_ALTAR, OVERWORLD_POINT -> guideTargetLocation(player, 7.0D);
            default -> null;
        };
        bingo.getSomitaGuideNetwork().effect(player, effect, 70, target);
        return true;
    }

    public boolean debugSomitaScene(Player player, String sceneName) {
        if (player == null || !(plugin instanceof BingoPlugin bingo)) return false;
        String scene = sceneName == null ? "overworld" : sceneName.toLowerCase(Locale.ROOT);
        SomitaGuideNetwork.Variant variant;
        SomitaGuideNetwork.Animation animation;
        SomitaGuideNetwork.Effect effect;
        Location target = null;
        String dialogue;
        switch (scene) {
            case "nether" -> {
                variant = SomitaGuideNetwork.Variant.NETHER;
                animation = SomitaGuideNetwork.Animation.CROSS_ARMS;
                effect = SomitaGuideNetwork.Effect.NETHER_APPEAR;
                dialogue = "La puerta central está sellada. Explora las alas y recupera la Llave Ígnea.";
            }
            case "end" -> {
                variant = SomitaGuideNetwork.Variant.END;
                animation = SomitaGuideNetwork.Animation.LOOK;
                effect = SomitaGuideNetwork.Effect.END_APPEAR;
                dialogue = "Derrota al Guardián del Vacío antes de abrir el altar del dragón.";
            }
            case "altar" -> {
                variant = SomitaGuideNetwork.Variant.END;
                animation = SomitaGuideNetwork.Animation.POINT;
                effect = SomitaGuideNetwork.Effect.END_ALTAR;
                target = guideTargetLocation(player, 7.0D);
                dialogue = "Coloca la llave en el altar para desbloquear el camino final.";
            }
            case "celebration", "final" -> {
                variant = SomitaGuideNetwork.Variant.CELEBRATION;
                animation = SomitaGuideNetwork.Animation.CELEBRATE_CUTE;
                effect = SomitaGuideNetwork.Effect.CELEBRATION_BURST;
                dialogue = "¡Lo lograron! La campaña ha sido completada.";
            }
            default -> {
                variant = SomitaGuideNetwork.Variant.OVERWORLD;
                animation = SomitaGuideNetwork.Animation.WAVE;
                effect = SomitaGuideNetwork.Effect.OVERWORLD_APPEAR;
                dialogue = "Explora la ciudad, completa objetivos y encuentra la ruta hacia el guardián.";
            }
        }
        bingo.getSomitaGuideNetwork().show(player, "test_" + scene, variant, animation,
                guideTestLocation(player, 3.0D, 1.3D), 240, dialogue);
        bingo.getSomitaGuideNetwork().effect(player, effect, 60, target);
        return true;
    }

    public boolean debugSomitaDemo(Player player) {
        return debugSomitaDemo(player, "overworld");
    }

    public boolean debugSomitaDemo(Player player, String variantName) {
        if (player == null || !(plugin instanceof BingoPlugin bingo)) return false;
        SomitaGuideNetwork.Variant variant = SomitaGuideNetwork.Variant.parse(variantName);
        SomitaGuideNetwork.Animation[] animations = {
                SomitaGuideNetwork.Animation.IDLE,
                SomitaGuideNetwork.Animation.WAVE,
                SomitaGuideNetwork.Animation.POINT,
                SomitaGuideNetwork.Animation.WALK,
                SomitaGuideNetwork.Animation.LOOK,
                SomitaGuideNetwork.Animation.BOW,
                SomitaGuideNetwork.Animation.CROSS_ARMS,
                SomitaGuideNetwork.Animation.HOLD,
                variant == SomitaGuideNetwork.Variant.END
                        ? SomitaGuideNetwork.Animation.CELEBRATE_ELEGANT
                        : SomitaGuideNetwork.Animation.CELEBRATE_CUTE,
                SomitaGuideNetwork.Animation.VANISH
        };
        bingo.getSomitaGuideNetwork().show(player, "animation_demo_" + variant.name().toLowerCase(Locale.ROOT),
                variant, animations[0], guideTestLocation(player, 3.0D, 1.3D),
                animations.length * 66 + 40, "Demostración de Somita "
                        + variant.name().toLowerCase(Locale.ROOT) + ".");
        bingo.getSomitaGuideNetwork().effect(player, appearanceEffect(variant), 40);
        for (int index = 0; index < animations.length; index++) {
            int delay = index * 66;
            SomitaGuideNetwork.Animation current = animations[index];
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                bingo.getSomitaGuideNetwork().animate(player, current, 62,
                        "Animación: " + current.name().toLowerCase(Locale.ROOT) + ".");
                if (current == SomitaGuideNetwork.Animation.POINT) {
                    bingo.getSomitaGuideNetwork().effect(player, pointEffect(variant), 52,
                            guideTargetLocation(player, 7.0D));
                }
            }, delay);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> bingo.getSomitaGuideNetwork().clear(player),
                animations.length * 66L + 34L);
        return true;
    }

    public boolean debugSomitaHide(Player player) {
        if (player == null || !(plugin instanceof BingoPlugin bingo)) return false;
        bingo.getSomitaGuideNetwork().hide(player);
        return true;
    }

    public boolean debugSomitaClear(Player player) {
        if (player == null || !(plugin instanceof BingoPlugin bingo)) return false;
        bingo.getSomitaGuideNetwork().clear(player);
        return true;
    }

    private SomitaGuideNetwork.Effect appearanceEffect(SomitaGuideNetwork.Variant variant) {
        return switch (variant) {
            case NETHER -> SomitaGuideNetwork.Effect.NETHER_APPEAR;
            case END -> SomitaGuideNetwork.Effect.END_APPEAR;
            case CELEBRATION -> SomitaGuideNetwork.Effect.CELEBRATION_BURST;
            default -> SomitaGuideNetwork.Effect.OVERWORLD_APPEAR;
        };
    }

    private SomitaGuideNetwork.Effect pointEffect(SomitaGuideNetwork.Variant variant) {
        return switch (variant) {
            case NETHER -> SomitaGuideNetwork.Effect.NETHER_LOCK;
            case END -> SomitaGuideNetwork.Effect.END_ALTAR;
            default -> SomitaGuideNetwork.Effect.OVERWORLD_POINT;
        };
    }

    private boolean sameWorld(Player player, Location location) {
        return player != null && location != null && location.getWorld() == player.getWorld();
    }

    private Location guideTargetLocation(Player player, double distance) {
        Location target = player.getEyeLocation().clone();
        Vector direction = target.getDirection();
        if (direction.lengthSquared() < 0.001D) direction = new Vector(0, 0, 1);
        return target.add(direction.normalize().multiply(distance));
    }

    private Location guideTestLocation(Player player, double forwardDistance, double sideDistance) {
        Location base = player.getLocation().clone();
        Vector forward = base.getDirection().setY(0);
        if (forward.lengthSquared() < 0.001D) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Location at = base.add(forward.multiply(forwardDistance)).add(right.multiply(sideDistance));
        Vector toPlayer = player.getLocation().toVector().subtract(at.toVector());
        at.setYaw((float) Math.toDegrees(Math.atan2(-toPlayer.getX(), toPlayer.getZ())));
        at.setPitch(0.0F);
        return at;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Location at = event.getBlock().getLocation();
        if (overworldCampaign.protectFromBreak(at, event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        if (spawnerManager.isRegisteredSpawner(at)) {
            if (!spawnerManager.isActiveSpawner(at)) {
                event.setCancelled(true);
                protectedMessage(event.getPlayer(),
                        "Este distrito aún no está desbloqueado. Sigue la ruta de la campaña.");
                return;
            }
            spawnerManager.handleBroken(at, event.getPlayer());
            return; // conquista permitida
        }
        if (adaptiveLoot.isRegisteredChest(at)) {
            event.setCancelled(true);
            protectedMessage(event.getPlayer(), "Los cofres de campaña no se pueden romper.");
            return;
        }
        if (isProtected(at)) {
            event.setCancelled(true);
            protectedMessage(event.getPlayer(), "Este acceso, cofre o mecanismo de campaña no se puede romper.");
        }
    }

    /**
     * Comprueba si un mundo pertenece a la arena activa de Bingo.
     * Incluye nombres exactos configurados, mundos dinámicos del pool y mundos
     * que ya contienen regiones protegidas registradas por esta campaña.
     */
    private boolean isCampaignWorld(World world) {
        if (world == null) return false;
        String worldName = world.getName();

        String configuredGameWorld = plugin.getConfig().getString(
                "arena-world.game-world", "bingo_arena");
        if (worldName.equals(configuredGameWorld)) return true;

        for (String extraWorld : plugin.getConfig().getStringList("arena-world.extra-worlds")) {
            if (worldName.equals(extraWorld)) return true;
        }

        String poolBaseName = plugin.getConfig().getString(
                "world-pool.base-name", configuredGameWorld);
        if (poolBaseName != null && !poolBaseName.isBlank()
                && (worldName.equals(poolBaseName) || worldName.startsWith(poolBaseName + "_"))) {
            return true;
        }

        for (ProtectedRegion region : protectedRegions) {
            if (region.world().equals(worldName)) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        if (!isCampaignWorld(event.getLocation().getWorld())) return;
        for (org.bukkit.block.Block block : List.copyOf(event.blockList())) {
            Location at = block.getLocation();
            if (spawnerManager != null && spawnerManager.isRegisteredSpawner(at)) {
                if (spawnerManager.isActiveSpawner(at)) {
                    spawnerManager.handleDestroyedByExplosion(at);
                } else {
                    // Una explosión no puede saltarse el orden de los distritos.
                    event.blockList().remove(block);
                }
                continue;
            }
            if (overworldCampaign.isProtected(at) || isProtected(at)
                    || (adaptiveLoot != null && adaptiveLoot.isRegisteredChest(at))) {
                event.blockList().remove(block);
            }
        }
    }
}
