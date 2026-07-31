package com.arlight.bingo.end;

import com.arlight.bingo.dungeon.AdaptiveDungeonLootManager;
import com.arlight.bingo.dungeon.DungeonMinionSpawnerManager;
import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.util.BossTuning;
import com.arlight.bingo.util.CampaignCinematics;
import com.arlight.bingo.util.CampaignItemBridge;
import com.arlight.bingo.template.TemplateWorldCloner;
import com.arlight.bingo.template.TemplateCampaignRepair;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Campaña del End, enlazada desde 1.32.0 a la plantilla aprobada:
 * ciudadela -> Guardián del Vacío -> Llave Dragon Esmeraltizada -> altar corrupto
 * -> isla preparada -> Dragón Corrupto -> escena final de Somita y portal.
 */
public final class EndEncounterManager implements Listener {
    private static final String GOLEM_TAG = "arlightbingo_boss_golem";
    private static final String DRAGON_TAG = "arlightbingo_boss_dragon";
    private static final String DEFAULT_CITADEL_BOSS_ENTITY = "void_guardian";
    private static final String DRAGON_ENTITY = "amethyst_corrupted_ender_dragon";

    private final JavaPlugin plugin;
    private BingoGame game;
    private AdaptiveDungeonLootManager adaptiveLootManager;
    private DungeonMinionSpawnerManager spawnerManager;

    private String activeWorldName;
    private EndCampaignStructure.Result structure;
    private UUID golemId;
    private UUID dragonId;
    private Location dragonKeyChest;
    private Location outroExitLocation;
    private boolean golemDefeated;
    private boolean dragonKeyConsumed;
    private boolean dragonSummoned;
    private boolean dragonDefeated;
    private BukkitTask maintenanceTask;
    private int maintenanceSeconds;
    private final Map<UUID, Location> lastCheckpoints = new HashMap<>();
    private final Map<UUID, Long> rescueCooldown = new HashMap<>();
    private final Map<UUID, Long> protectionMessageCooldown = new HashMap<>();
    private int dragonSummonAttempts;

    public EndEncounterManager(JavaPlugin plugin) { this.plugin = plugin; }

    public void setGame(BingoGame game) { this.game = game; }
    public void setAdaptiveLootManager(AdaptiveDungeonLootManager manager) { this.adaptiveLootManager = manager; }
    public void setSpawnerManager(DungeonMinionSpawnerManager manager) { this.spawnerManager = manager; }

    public void prepare(World world) {
        resetState();
        if (!plugin.getConfig().getBoolean("end-encounter.enabled", true)) return;
        if (world == null || world.getEnvironment() != World.Environment.THE_END) return;

        activeWorldName = world.getName();
        removeOldCampaignEntities(world);
        world.getWorldBorder().setCenter(0, -18);
        world.getWorldBorder().setSize(Math.max(2800,
                plugin.getConfig().getDouble("dimension-dungeons.world-borders.end", 2800)));

        boolean runtimeTemplate = TemplateWorldCloner.isRuntimeClone(world);
        structure = runtimeTemplate
                ? bindTemplateCampaign(world)
                : new EndCampaignStructure(plugin, adaptiveLootManager, spawnerManager).build(world);
        world.setSpawnLocation(structure.arrival());
        golemDefeated = false;
        dragonKeyConsumed = false;
        dragonSummoned = false;
        dragonDefeated = false;
        dragonSummonAttempts = 0;
        lastCheckpoints.clear();
        rescueCooldown.clear();
        protectionMessageCooldown.clear();
        for (Player player : world.getPlayers()) {
            CampaignItemBridge.removeOwnership(player, CampaignItemBridge.DRAGON_KEY);
        }

        if (!structure.keyChests().isEmpty()) {
            dragonKeyChest = structure.keyChests().get(
                    ThreadLocalRandom.current().nextInt(structure.keyChests().size())).clone();
            boolean inserted = CampaignItemBridge.putInChest(
                    dragonKeyChest, CampaignItemBridge.DRAGON_KEY, 13);
            if (!inserted && runtimeTemplate) {
                throw new IllegalStateException("No se pudo insertar la Llave del Dragón en el contenedor "
                        + format(dragonKeyChest) + ".");
            }
            plugin.getLogger().info("Llave del Dragón escondida en " + format(dragonKeyChest));
        }

        spawnGolem(structure.golemHome());
        startMaintenance();
        plugin.getLogger().info(runtimeTemplate
                ? "Campaña del End enlazada a la plantilla persistente 1.35.0 sin reconstruir su arquitectura."
                : "Campaña del End preparada: ciudadela, gólem, altar e isla del dragón.");
    }

    /**
     * Conecta la campaña activa con la arquitectura aprobada de la plantilla.
     * Sólo instala los controles funcionales (puerta y altar); no vuelve a dibujar
     * islas, edificios, puentes ni la arena final.
     */
    private EndCampaignStructure.Result bindTemplateCampaign(World world) {
        final String marker = "arlight-end-template.properties";
        Location arrival = requiredTemplateLocation(world, marker, "spawn").add(0.5D, 2.0D, 0.5D);
        Location golem = requiredTemplateLocation(world, marker, "boss").add(0.5D, 0.0D, 0.5D);
        List<Location> keyChests = new ArrayList<>(TemplateCampaignRepair.ensureEndKeyChests(world,
                TemplateWorldCloner.readLocations(world, marker, "keys")));
        Location gate = requiredTemplateLocation(world, marker, "gate").add(0.5D, 0.0D, 0.5D);
        Location altar = TemplateCampaignRepair.ensureEndAltar(world,
                TemplateWorldCloner.readLocation(world, marker, "altar"));
        Location dragon = requiredTemplateLocation(world, marker, "dragon").add(0.5D, 0.0D, 0.5D);
        Location exit = requiredTemplateLocation(world, marker, "exit").add(0.5D, 0.0D, 0.5D);

        // Barrera funcional sobre el puente. Se elimina al derrotar al Guardián.
        for (int x = -4; x <= 4; x++) for (int y = 0; y <= 6; y++) {
            gate.clone().add(x, y, 0).getBlock().setType(
                    y == 3 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS, false);
        }
        EndCampaignStructure.Bounds golemRoom =
                new EndCampaignStructure.Bounds(-68, 68, 90, 145, -68, 68);
        EndCampaignStructure.Bounds campaign =
                new EndCampaignStructure.Bounds(-335, 335, 20, 190, -330, 520);
        EndCampaignStructure.Bounds dragonArena =
                new EndCampaignStructure.Bounds(-112, 112, 45, 190, 342, 518);

        List<EndCampaignStructure.Bounds> criticalRegions = List.of(
                new EndCampaignStructure.Bounds(-8, 8, 96, 108, 270, 282),
                new EndCampaignStructure.Bounds(-10, 10, 94, 108, 420, 440),
                new EndCampaignStructure.Bounds(-8, 8, 94, 108, 447, 463));
        List<Location> criticalBlocks = new ArrayList<>(keyChests);
        criticalBlocks.add(altar.clone());
        criticalBlocks.add(gate.clone());
        criticalBlocks.add(exit.clone());

        List<Location> checkpoints = List.of(
                arrival.clone(),
                new Location(world, 0.5D, 100.0D, -170.5D),
                new Location(world, 0.5D, 100.0D, -76.5D),
                new Location(world, 0.5D, 100.0D, -18.5D),
                new Location(world, 0.5D, 98.0D, 226.5D),
                gate.clone().add(0, 0, -8),
                altar.clone().add(0.5D, 0.0D, -16.5D));

        return new EndCampaignStructure.Result(
                arrival, golem, golemRoom, List.copyOf(keyChests), altar, dragon, exit, gate,
                checkpoints, campaign, criticalRegions, List.copyOf(criticalBlocks),
                dragonArena, 34);
    }

    private Location requiredTemplateLocation(World world, String marker, String key) {
        Location location = TemplateWorldCloner.readLocation(world, marker, key);
        if (location == null) {
            throw new IllegalStateException("La plantilla " + world.getName()
                    + " no contiene la posición '" + key + "'.");
        }
        return location;
    }

    public void shutdown() { resetState(); }

    public Location getSafeArrival(World world) {
        if (!isActiveWorld(world) || structure == null) return null;
        return structure.arrival().clone();
    }

    private void startMaintenance() {
        maintenanceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            maintenanceSeconds++;
            if (structure == null) return;
            Entity golem = golemId == null ? null : Bukkit.getEntity(golemId);
            if (!golemDefeated && golem instanceof Mob mob && !structure.golemRoom().contains(golem.getLocation())) {
                mob.teleport(structure.golemHome());
                mob.setTarget(null);
            }
            if (maintenanceSeconds % 5 == 0) ensureDragonKeyAvailable();
            if (golemDefeated && !dragonKeyConsumed && !dragonSummoned
                    && maintenanceSeconds > 0 && maintenanceSeconds % 180 == 0 && game != null) {
                game.sendToParticipants(ChatColor.LIGHT_PURPLE + "[Bingo] Pista: la Llave del Dragón está en un cofre compartido de la ciudadela.");
            }
            rescuePlayers();
        }, 20L, 20L);
    }

    private void rescuePlayers() {
        if (structure == null) return;
        World world = Bukkit.getWorld(activeWorldName);
        if (world == null) return;
        long now = System.currentTimeMillis();
        for (Player player : world.getPlayers()) {
            if (game == null || game.getTeamOf(player) == null) continue;
            if (player.getLocation().getY() >= structure.rescueY()) continue;
            if (rescueCooldown.getOrDefault(player.getUniqueId(), 0L) > now) continue;
            rescueCooldown.put(player.getUniqueId(), now + 6000L);
            Location nearest = lastCheckpoints.get(player.getUniqueId());
            if (nearest == null) nearest = nearestCheckpoint(player.getLocation());
            Location safe = safeLocation(nearest == null ? structure.arrival() : nearest);
            player.setVelocity(new Vector(0,0,0));
            player.teleport(safe);
            player.setVelocity(new Vector(0,0,0));
            player.setFallDistance(0);
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 4, false, false, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, false, false, true));
            player.sendMessage(ChatColor.YELLOW + "[Bingo] El vacío te devolvió al último punto seguro.");
        }
    }

    private Location nearestCheckpoint(Location from) {
        Location nearest=structure.arrival(); double best=Double.MAX_VALUE;
        for(Location checkpoint:structure.checkpoints()) {
            double distance=horizontalDistanceSquared(from,checkpoint);
            if(distance<best){best=distance;nearest=checkpoint;}
        }
        return nearest==null?null:nearest.clone();
    }

    private Location safeLocation(Location original) {
        Location safe=original.clone();safe.setX(safe.getBlockX()+0.5);safe.setZ(safe.getBlockZ()+0.5);
        for(int rise=0;rise<=10;rise++){
            Location c=safe.clone().add(0,rise,0);
            if(c.clone().add(0,-1,0).getBlock().getType().isSolid()&&c.getBlock().isPassable()
                    &&c.clone().add(0,1,0).getBlock().isPassable())return c;
        }
        safe.clone().add(0,-1,0).getBlock().setType(Material.END_STONE_BRICKS,false);
        safe.getBlock().setType(Material.AIR,false);safe.clone().add(0,1,0).getBlock().setType(Material.AIR,false);
        return safe;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) return;
        if (!isActiveWorld(event.getPlayer().getWorld()) || structure == null) return;
        Player player = event.getPlayer();
        if (game == null || game.getTeamOf(player) == null) return;
        updateCheckpoint(player);

        if (!golemDefeated && structure.golemRoom().contains(player.getLocation())) {
            Entity entity = golemId == null ? null : Bukkit.getEntity(golemId);
            if (entity instanceof Mob golem && !golem.isDead()) {
                if (!golem.hasAI()) golem.setAI(true);
                golem.setTarget(player);
            }
        }
    }

    private void updateCheckpoint(Player player) {
        if (structure == null) return;
        Location current=player.getLocation();
        for(Location checkpoint:structure.checkpoints()) {
            if(checkpoint.getWorld()==current.getWorld()&&Math.abs(checkpoint.getY()-current.getY())<=6
                    &&horizontalDistanceSquared(current,checkpoint)<=36) {
                lastCheckpoints.put(player.getUniqueId(),checkpoint.clone());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAltarUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (!isActiveWorld(event.getPlayer().getWorld()) || structure == null) return;
        Location clicked = event.getClickedBlock().getLocation();
        Player player = event.getPlayer();
        if (game == null || game.getTeamOf(player) == null) return;

        // La ciudadela elige un cofre por partida. Al abrirlo, la llave pasa al
        // inventario del primer participante, incluso si Lootr reemplaza la GUI.
        if (!dragonKeyConsumed && !dragonSummoned && sameBlock(clicked, dragonKeyChest)) {
            if (!anyParticipantHasDragonKey(clicked.getWorld())) {
                CampaignItemBridge.clearChestSlot(dragonKeyChest, 13);
                CampaignItemBridge.give(player, CampaignItemBridge.DRAGON_KEY);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "Encontraste la Llave del Dragón Esmeraltizada.");
                game.sendToParticipants(ChatColor.LIGHT_PURPLE + "[Bingo] " + ChatColor.WHITE
                        + player.getName() + " encontró la llave del altar corrupto.");
                player.getWorld().playSound(clicked, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.4F, 1.25F);
            }
            return;
        }

        if (clicked.distanceSquared(structure.altar()) > 2.25D
                && !CampaignItemBridge.isBlock(clicked, CampaignItemBridge.CORRUPTED_ALTAR)) return;
        event.setCancelled(true);
        if (!golemDefeated) {
            player.sendMessage(ChatColor.RED + "El altar no responde. Primero derrota al Guardián del Vacío de la ciudadela.");
            return;
        }
        if (dragonSummoned && !dragonDefeated) {
            player.sendMessage(ChatColor.LIGHT_PURPLE + "El Dragón Corrupto ya fue invocado.");
            return;
        }
        if (!CampaignItemBridge.has(player, CampaignItemBridge.DRAGON_KEY)) {
            player.sendMessage(ChatColor.RED + "Necesitas la Llave del Dragón Esmeraltizada escondida en la ciudadela.");
            return;
        }
        if (!CampaignItemBridge.consume(player, CampaignItemBridge.DRAGON_KEY)) return;
        dragonKeyConsumed = true;
        invokeDragon(player);
    }


    @EventHandler
    public void onDragonKeyCarrierQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (CampaignItemBridge.has(player, CampaignItemBridge.DRAGON_KEY)
                && !dragonKeyConsumed && !dragonSummoned) {
            CampaignItemBridge.removeOwnership(player, CampaignItemBridge.DRAGON_KEY);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (dragonKeyChest != null && dragonKeyChest.getWorld() != null) {
                    CampaignItemBridge.putInChest(dragonKeyChest, CampaignItemBridge.DRAGON_KEY, 13);
                } else {
                    plugin.getLogger().warning("No se pudo devolver la Llave del Dragón: el cofre de misión no está disponible.");
                }
            });
        }
    }

    private boolean anyParticipantHasDragonKey(World world) {
        if (world == null) return false;
        for (Player participant : world.getPlayers()) {
            if (game != null && game.getTeamOf(participant) != null
                    && CampaignItemBridge.has(participant, CampaignItemBridge.DRAGON_KEY)) return true;
        }
        return false;
    }

    private void invokeDragon(Player activator) {
        if (structure == null || dragonSummoned) return;
        dragonSummoned = true;
        dragonSummonAttempts = 0;
        World world = structure.dragonSpawn().getWorld();
        Location altar = structure.altar();
        if (world == null) { abortDragonInvocation(activator, "El mundo del End no está disponible."); return; }
        CampaignItemBridge.animateCorruptedAltar(altar);
        world.playSound(altar, Sound.BLOCK_END_PORTAL_SPAWN, 2.0F, 0.65F);
        world.playSound(altar, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0F, 0.7F);
        world.spawnParticle(Particle.REVERSE_PORTAL, altar.clone().add(0.5, 1.2, 0.5),
                260, 3.5, 3.0, 3.5, 0.18);
        world.spawnParticle(Particle.WITCH, altar.clone().add(0.5, 1.2, 0.5),
                150, 3.0, 2.5, 3.0, 0.05);
        Bukkit.getScheduler().runTaskLater(plugin, () -> attemptDragonSummon(activator), 45L);
    }

    private void attemptDragonSummon(Player activator) {
        if (!plugin.isEnabled() || structure == null || dragonDefeated) return;
        dragonSummonAttempts++;
        try {
            Entity dragon = summonArlightEntity(structure.dragonSpawn(), DRAGON_ENTITY, List.of(DRAGON_TAG), "");
            if (dragon == null) {
                if (dragonSummonAttempts < 3) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> attemptDragonSummon(activator), 30L);
                    return;
                }
                abortDragonInvocation(activator, "No se pudo crear la entidad después de tres intentos.");
                return;
            }
            dragonId = dragon.getUniqueId();
            dragon.addScoreboardTag("arlightbingo_cleanup_on_end");
            if (dragon instanceof Mob mob) {mob.setPersistent(true);mob.setRemoveWhenFarAway(false);}
            if (spawnerManager != null) spawnerManager.activateGroup("end_dragon_outer");
            if (game != null) game.sendToParticipants(ChatColor.LIGHT_PURPLE + "[Bingo] "
                    + activator.getName() + " activó el Altar de Perla Corrompida. ¡El dragón ha despertado!");
        } catch (Throwable failure) {
            plugin.getLogger().severe("Fallo protegido al invocar al Dragón Corrupto: " + failure);
            abortDragonInvocation(activator, "La invocación fue cancelada de forma segura.");
        }
    }

    private void abortDragonInvocation(Player activator,String reason) {
        dragonSummoned=false;dragonKeyConsumed=false;dragonId=null;
        if(activator!=null&&activator.isOnline()){
            CampaignItemBridge.give(activator,CampaignItemBridge.DRAGON_KEY);
            activator.sendMessage(ChatColor.RED+reason+" La llave fue devuelta.");
        } else if(dragonKeyChest!=null) CampaignItemBridge.putInChest(dragonKeyChest,CampaignItemBridge.DRAGON_KEY,13);
        if(game!=null)game.sendToParticipants(ChatColor.RED+"[Bingo] El ritual falló sin cerrar la partida; la llave fue restaurada.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!isActiveWorld(dead.getWorld())) return;
        if (!golemDefeated && (dead.getScoreboardTags().contains(GOLEM_TAG)
                || (golemId != null && golemId.equals(dead.getUniqueId())))) {
            golemDefeated = true;
            if (game != null) {
                game.claimGlobalBoss(dead.getKiller(), "boss_end");
                game.sendToParticipants(ChatColor.LIGHT_PURPLE + "[Bingo] El Guardián del Vacío cayó. "
                        + "El camino hacia el altar del dragón está abierto.");
            }
            openBridgeGate();
            if (spawnerManager != null) spawnerManager.deactivateGroupPrefix("end_citadel");
            return;
        }
        if (!dragonDefeated && (dead.getScoreboardTags().contains(DRAGON_TAG)
                || (dragonId != null && dragonId.equals(dead.getUniqueId())))) {
            dragonDefeated = true;
            if (game != null) game.claimGlobalBoss(dead.getKiller(), "boss_dragon");
            Location death = dead.getLocation().clone();
            // El portal final se crea en la misma arena, cerca de la cinemática, para
            // que los jugadores lo vean y puedan cruzarlo inmediatamente.
            outroExitLocation = structure.exitPortal().clone();
            CampaignCinematics scene = new CampaignCinematics(plugin, game);
            scene.playDragonOutro(death, () -> Bukkit.getScheduler().runTaskLater(plugin, this::createExitPortal,
                    Math.max(20L, plugin.getConfig().getLong("end-campaign.exit-portal-delay-ticks", 80L))));
            if (game != null) game.sendToParticipants(ChatColor.GOLD + "[Bingo] " + ChatColor.GREEN
                    + "¡El Dragón Corrupto fue derrotado! Somita viene por el huevo...");
        }
    }

    private void openBridgeGate() {
        if (structure == null) return;
        Location gate=structure.bridgeGate();
        for(int x=-4;x<=4;x++)for(int y=0;y<=6;y++)gate.clone().add(x,y,0).getBlock().setType(Material.AIR,false);
        gate.getWorld().playSound(gate,Sound.BLOCK_IRON_DOOR_OPEN,1.5F,0.75F);
        gate.getWorld().spawnParticle(Particle.END_ROD,gate.clone().add(0,3,0),70,3.0,2.5,0.8,0.04);
    }

    private Location findPortalFloor(Location origin) {
        if (origin == null || origin.getWorld() == null) return origin;
        World world = origin.getWorld();
        int x = origin.getBlockX();
        int z = origin.getBlockZ();
        int start = Math.min(world.getMaxHeight() - 3, Math.max(origin.getBlockY(), structure.dragonSpawn().getBlockY()));
        for (int y = start; y > world.getMinHeight() + 3; y--) {
            Material below = world.getBlockAt(x, y - 1, z).getType();
            if (below.isSolid()) return new Location(world, x + 0.5, y, z + 0.5);
        }
        Location fallback = structure.exitPortal().clone();
        return new Location(world, fallback.getBlockX() + 0.5, fallback.getBlockY(), fallback.getBlockZ() + 0.5);
    }

    private void createExitPortal() {
        if (structure == null || structure.exitPortal() == null) return;
        Location at = outroExitLocation != null ? outroExitLocation.clone() : structure.exitPortal().clone();
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++){
            int edge = Math.max(Math.abs(x), Math.abs(z));
            Material floor = edge >= 4 ? Material.PURPUR_BLOCK
                    : (Math.floorMod(x + z, 4) == 0 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS);
            at.clone().add(x,-1,z).getBlock().setType(floor,false);
            at.clone().add(x,0,z).getBlock().setType(Material.AIR,false);
            at.clone().add(x,1,z).getBlock().setType(Material.AIR,false);
            at.clone().add(x,2,z).getBlock().setType(Material.AIR,false);
        }
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            if(Math.abs(x)==2||Math.abs(z)==2){
                at.clone().add(x,0,z).getBlock().setType(Material.END_PORTAL_FRAME,false);
                if(at.clone().add(x,0,z).getBlock().getBlockData() instanceof EndPortalFrame frame){
                    frame.setEye(true);at.clone().add(x,0,z).getBlock().setBlockData(frame,false);
                }
            } else at.clone().add(x,0,z).getBlock().setType(Material.END_PORTAL,false);
        }
        // Cuatro obeliscos hacen visible la salida incluso desde el otro extremo de la arena.
        for (int[] corner : new int[][]{{-4,-4},{-4,4},{4,-4},{4,4}}) {
            for (int y=0; y<=5; y++) at.clone().add(corner[0], y, corner[1]).getBlock()
                    .setType(y == 5 ? Material.END_ROD : Material.PURPUR_PILLAR, false);
        }
        at.getWorld().spawnParticle(Particle.END_ROD, at.clone().add(0, 1.0, 0), 150, 2.8, 1.2, 2.8, 0.04);
        at.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0, 1.0, 0), 180, 2.5, 1.0, 2.5, 0.14);
        at.getWorld().playSound(at,Sound.BLOCK_END_PORTAL_SPAWN,2.0F,1.0F);
        if(game!=null)game.sendToParticipants(ChatColor.GREEN+"[Bingo] Somita abrió el portal de regreso junto a la arena.");
    }

    private void spawnGolem(Location at) {
        BossTuning.BossProfile profile=BossTuning.boss(plugin,game,"end");
        String extra=BossTuning.bossNbt(profile);
        String configured = plugin.getConfig().getString("end-encounter.citadel-boss-entity",
                "arlightbosses:" + DEFAULT_CITADEL_BOSS_ENTITY).trim();
        String entityId = configured.contains(":") ? configured.substring(configured.indexOf(':') + 1) : configured;
        // La ciudadela siempre usa el jefe real. Una configuración vieja que apunte
        // al minion gólem no puede sustituirlo accidentalmente.
        if (!DEFAULT_CITADEL_BOSS_ENTITY.equalsIgnoreCase(entityId)) {
            plugin.getLogger().warning("end-encounter.citadel-boss-entity='" + configured
                    + "' no es el jefe oficial; se usará arlightbosses:void_guardian.");
            entityId = DEFAULT_CITADEL_BOSS_ENTITY;
        }
        Entity entity=summonArlightEntity(at,entityId,List.of(GOLEM_TAG,"arlightbingo_end_citadel_boss"),extra);
        if(entity instanceof Mob golem){configureGolem(golem);golemId=golem.getUniqueId();}
        else registerGolemLater(at);
    }

    private void configureGolem(Mob golem){
        golem.setCustomName(ChatColor.DARK_PURPLE+"Guardián del Vacío de la Ciudadela");
        golem.setCustomNameVisible(true);golem.setPersistent(true);golem.setRemoveWhenFarAway(false);
        golem.setAI(false);golem.setGlowing(true);
    }

    private void registerGolemLater(Location at){
        for(long delay:new long[]{2L,10L,30L})Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(golemId!=null||at.getWorld()==null)return;
            for(Entity e:at.getWorld().getNearbyEntities(at,10,10,10))if(e instanceof Mob m&&e.getScoreboardTags().contains(GOLEM_TAG)){
                configureGolem(m);golemId=m.getUniqueId();return;
            }
        },delay);
    }

    private Entity summonArlightEntity(Location at,String entityId,List<String> tags,String extraNbt){
        if(at==null||at.getWorld()==null||tags==null||tags.isEmpty())return null;
        // Antes de ejecutar otro /summon, recupera una entidad ya creada por un intento
        // anterior. En Arclight el registro Bukkit puede retrasarse uno o dos ticks.
        for(Entity existing:at.getWorld().getNearbyEntities(at,20,20,20)){
            if(existing.getScoreboardTags().contains(tags.get(0)))return existing;
        }
        Set<UUID> before=new HashSet<>();
        for(Entity e:at.getWorld().getNearbyEntities(at,20,20,20))before.add(e.getUniqueId());
        String nbtTags=tags.stream().map(t->"\""+t+"\"").collect(Collectors.joining(","));
        String command=String.format(Locale.ROOT,
                "execute in %s run summon arlightbosses:%s %.2f %.2f %.2f {Tags:[%s],PersistenceRequired:1b%s}",
                at.getWorld().getKey(),entityId,at.getX(),at.getY(),at.getZ(),nbtTags,extraNbt==null?"":extraNbt);
        if(!Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command))return null;
        for(Entity e:at.getWorld().getNearbyEntities(at,20,20,20)){
            if(!before.contains(e.getUniqueId())&&e.getScoreboardTags().contains(tags.get(0)))return e;
        }
        return null;
    }

    private void ensureDragonKeyAvailable() {
        if (dragonKeyConsumed || dragonSummoned || dragonKeyChest == null || dragonKeyChest.getWorld() == null) return;
        for (Player player : dragonKeyChest.getWorld().getPlayers()) {
            if (game != null && game.getTeamOf(player) != null && CampaignItemBridge.has(player, CampaignItemBridge.DRAGON_KEY)) return;
        }
        if (dragonKeyChest.getBlock().getState() instanceof Container container) {
            boolean exists=Arrays.stream(container.getInventory().getContents())
                    .anyMatch(stack->CampaignItemBridge.matches(stack,CampaignItemBridge.DRAGON_KEY));
            if(!exists)CampaignItemBridge.putInChest(dragonKeyChest,CampaignItemBridge.DRAGON_KEY,13);
        }
    }

    public String debugStatus(){
        return "GolemDefeated="+golemDefeated+", DragonKeyConsumed="+dragonKeyConsumed
                +", DragonSummoned="+dragonSummoned+", DragonDefeated="+dragonDefeated
                +", SummonAttempts="+dragonSummonAttempts;
    }

    public boolean debugGiveDragonKey(Player player){
        if(player==null)return false;CampaignItemBridge.give(player,CampaignItemBridge.DRAGON_KEY);return true;
    }

    public boolean debugOpenGolemGate(){
        if(structure==null)return false;golemDefeated=true;openBridgeGate();return true;
    }

    public boolean debugInvokeDragon(Player player){
        if(player==null||structure==null||dragonSummoned)return false;
        golemDefeated=true;dragonKeyConsumed=true;invokeDragon(player);return true;
    }

    public boolean debugTeleport(Player player,String stage){
        if(player==null||structure==null)return false;
        Location target=switch(stage.toLowerCase(Locale.ROOT)){
            case "end","arrival"->structure.arrival();
            case "golem"->structure.golemHome();
            case "altar"->structure.altar().clone().add(0,0,-5);
            case "dragon"->structure.dragonSpawn().clone().add(0,-23,-15);
            default->null;
        };
        return target!=null&&player.teleport(safeLocation(target));
    }

    @EventHandler(ignoreCancelled=true)
    public void onBreak(BlockBreakEvent event){
        if(structure==null||!isActiveWorld(event.getBlock().getWorld()))return;
        Location at=event.getBlock().getLocation();
        if(spawnerManager!=null&&spawnerManager.isRegisteredSpawner(at)){
            spawnerManager.handleBroken(at,event.getPlayer());
            return;
        }
        if(adaptiveLootManager!=null&&adaptiveLootManager.isRegisteredChest(at)){
            event.setCancelled(true);
            protectedMessage(event.getPlayer(),"Los cofres de campaña no se pueden romper.");
            return;
        }
        if(isCritical(at)){
            event.setCancelled(true);
            protectedMessage(event.getPlayer(),"Este altar, cofre o acceso de jefe es parte de la campaña.");
        }
    }
    @EventHandler(ignoreCancelled=true)
    public void onExplosion(EntityExplodeEvent event){
        if(structure==null||!isActiveWorld(event.getLocation().getWorld()))return;
        for (org.bukkit.block.Block block : List.copyOf(event.blockList())) {
            Location at = block.getLocation();
            if (spawnerManager != null && spawnerManager.isRegisteredSpawner(at)) {
                spawnerManager.handleDestroyedByExplosion(at);
                continue;
            }
            if (isCritical(at) || (adaptiveLootManager != null && adaptiveLootManager.isRegisteredChest(at))) {
                event.blockList().remove(block);
            }
        }
    }

    private boolean isCritical(Location at){
        if(at==null||structure==null)return false;
        for(EndCampaignStructure.Bounds region:structure.criticalRegions())if(region.contains(at))return true;
        for(Location block:structure.criticalBlocks())if(sameBlock(at,block))return true;
        return false;
    }

    private void protectedMessage(Player player,String message){
        long now=System.currentTimeMillis();
        if(protectionMessageCooldown.getOrDefault(player.getUniqueId(),0L)>now)return;
        protectionMessageCooldown.put(player.getUniqueId(),now+2500L);
        player.sendMessage(ChatColor.RED+message);
    }

    private void removeOldCampaignEntities(World world){
        for(Entity e:world.getEntities())if(e.getScoreboardTags().contains(GOLEM_TAG)||e.getScoreboardTags().contains(DRAGON_TAG)
                ||e.getScoreboardTags().contains("arlightbingo_somita_guide")||e.getScoreboardTags().contains("arlightbingo_somita_bubble"))e.remove();
    }

    private void resetState(){
        if(maintenanceTask!=null){maintenanceTask.cancel();maintenanceTask=null;}
        World old=activeWorldName==null?null:Bukkit.getWorld(activeWorldName);
        if(old!=null)removeOldCampaignEntities(old);
        activeWorldName=null;structure=null;golemId=null;dragonId=null;dragonKeyChest=null;outroExitLocation=null;
        golemDefeated=false;dragonKeyConsumed=false;dragonSummoned=false;dragonDefeated=false;maintenanceSeconds=0;
        dragonSummonAttempts=0;lastCheckpoints.clear();rescueCooldown.clear();protectionMessageCooldown.clear();
    }

    private boolean isActiveWorld(World world){return world!=null&&activeWorldName!=null&&activeWorldName.equalsIgnoreCase(world.getName());}
    private boolean sameBlock(Location a,Location b){return a!=null&&b!=null&&a.getWorld()==b.getWorld()
            &&a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();}
    private double horizontalDistanceSquared(Location a,Location b){double x=a.getX()-b.getX(),z=a.getZ()-b.getZ();return x*x+z*z;}
    private String format(Location l){return l.getWorld().getName()+" ("+l.getBlockX()+", "+l.getBlockY()+", "+l.getBlockZ()+")";}
}
