package com.arlight.bingo.dungeon;

import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.util.CampaignMissionItems;
import com.arlight.bingo.util.SomitaGuideNetwork;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Primera versión jugable de la campaña del Overworld sobre la plantilla actual.
 * Reutiliza los tres asentamientos existentes, sustituye sus spawners decorativos
 * por spawners controlados de ArlightBosses y bloquea la ciudadela hasta liberarlos.
 */
public final class OverworldCampaignProgression {
    private record District(String id, String displayName, Location center, String group,
                            String missionItem, Material pedestalMaterial,
                            AdaptiveDungeonLootManager.ChestTier chestTier,
                            List<List<String>> spawnerPools) { }

    private static final int[][] SPAWNER_OFFSETS = {
            {0, 0}, {-20, -10}, {19, 13}, {0, 27}
    };

    private final JavaPlugin plugin;
    private final BingoGame game;
    private final AdaptiveDungeonLootManager loot;
    private final DungeonMinionSpawnerManager spawners;
    private final SomitaGuideNetwork somita;
    private final List<District> districts = new ArrayList<>();
    private final Set<String> completed = new HashSet<>();
    private final Set<String> armedGroups = new HashSet<>();
    private final Set<String> protectedBlocks = new HashSet<>();
    private final List<Location> rewardChests = new ArrayList<>();
    private final List<Location> pedestalIndicators = new ArrayList<>();

    private World world;
    private Location village;
    private Location citadel;
    private Location bossHome;
    private Location portal;
    private Location gateCenter;
    private Location finalChest;
    private Runnable unlockBoss;
    private int activeDistrict;
    private boolean installed;
    private boolean gateOpened;
    private boolean awaitingDistrictReward;
    private boolean bossSpawnRequested;
    private boolean bossDefeated;

    public OverworldCampaignProgression(JavaPlugin plugin, BingoGame game,
                                        AdaptiveDungeonLootManager loot,
                                        DungeonMinionSpawnerManager spawners) {
        this.plugin = plugin;
        this.game = game;
        this.loot = loot;
        this.spawners = spawners;
        this.somita = new SomitaGuideNetwork(plugin);
    }

    public void reset() {
        districts.clear();
        completed.clear();
        armedGroups.clear();
        protectedBlocks.clear();
        rewardChests.clear();
        pedestalIndicators.clear();
        world = null;
        village = null;
        citadel = null;
        bossHome = null;
        portal = null;
        gateCenter = null;
        finalChest = null;
        unlockBoss = null;
        activeDistrict = 0;
        installed = false;
        gateOpened = false;
        awaitingDistrictReward = false;
        bossSpawnRequested = false;
        bossDefeated = false;
    }

    public void prepare(World world, Location village, Location citadel,
                        Location bossHome, Location portal, Runnable unlockBoss) {
        reset();
        if (world == null || village == null || citadel == null || bossHome == null) return;
        if (!plugin.getConfig().getBoolean("campaign.overworld-playable.enabled", true)) return;

        this.world = world;
        this.village = village.clone();
        this.citadel = citadel.clone();
        this.bossHome = bossHome.clone();
        this.portal = portal == null ? null : portal.clone();
        this.unlockBoss = unlockBoss;

        List<Location> settlementRoute = new ArrayList<>(List.of(
                citadel.clone().add(-170, 0, 100),
                citadel.clone().add(170, 0, 80),
                citadel.clone().add(0, 0, -190)));
        settlementRoute.sort(Comparator.comparingDouble(candidate -> horizontalDistanceSquared(candidate, village)));

        District residential = district(
                "residential", "Distrito residencial", settlementRoute.get(0),
                "surface_campaign_residential", CampaignMissionItems.HOME_EMBLEM,
                Material.EMERALD_BLOCK, AdaptiveDungeonLootManager.ChestTier.RARE,
                List.of(
                        List.of("arlightbosses:emerald_zombie_minion", "arlightbosses:mossbound_spider_minion"),
                        List.of("arlightbosses:emerald_zombie_minion", "arlightbosses:emerald_creeper_minion"),
                        List.of("arlightbosses:mossbound_spider_minion", "arlightbosses:emerald_zombie_minion"),
                        List.of("arlightbosses:emerald_creeper_minion", "arlightbosses:emerald_zombie_minion")));
        District commercial = district(
                "commercial", "Distrito comercial", settlementRoute.get(1),
                "surface_campaign_commercial", CampaignMissionItems.TRADE_EMBLEM,
                Material.GOLD_BLOCK, AdaptiveDungeonLootManager.ChestTier.EPIC,
                List.of(
                        List.of("arlightbosses:emerald_zombie_minion", "arlightbosses:emerald_skeleton_archer_minion"),
                        List.of("arlightbosses:emerald_skeleton_archer_minion", "arlightbosses:emerald_creeper_minion"),
                        List.of("arlightbosses:emerald_ravager_cub_minion", "arlightbosses:emerald_zombie_minion"),
                        List.of("arlightbosses:mossbound_spider_minion", "arlightbosses:emerald_skeleton_archer_minion")));
        District military = district(
                "military", "Bastión militar de esmeralda", settlementRoute.get(2),
                "surface_campaign_military", CampaignMissionItems.EMERALD_CORE,
                Material.AMETHYST_BLOCK, AdaptiveDungeonLootManager.ChestTier.EPIC,
                List.of(
                        List.of("arlightbosses:emerald_golem_sentinel_minion", "arlightbosses:emerald_zombie_minion"),
                        List.of("arlightbosses:emerald_skeleton_archer_minion", "arlightbosses:emerald_ravager_cub_minion"),
                        List.of("arlightbosses:emerald_creeper_minion", "arlightbosses:emerald_skeleton_archer_minion"),
                        List.of("arlightbosses:emerald_ravager_cub_minion", "arlightbosses:emerald_golem_sentinel_minion")));
        districts.add(residential);
        districts.add(commercial);
        districts.add(military);

        for (int index = 0; index < districts.size(); index++) {
            installDistrict(districts.get(index), index == 0);
        }
        buildCitadelSeal();
        installFinalRewardChest();
        installed = true;

        plugin.getLogger().info("[ArlightBingo] Progresión jugable del Overworld instalada: "
                + districts.size() + " distritos, cofres ArlightBosses y cierre de ciudadela.");
    }

    private District district(String id, String name, Location roughCenter, String group,
                              String missionItem, Material indicator,
                              AdaptiveDungeonLootManager.ChestTier tier,
                              List<List<String>> pools) {
        Location centered = new Location(world, roughCenter.getBlockX(), citadel.getBlockY(),
                roughCenter.getBlockZ());
        return new District(id, name, centered, group, missionItem, indicator, tier, pools);
    }

    private void installDistrict(District district, boolean initiallyActive) {
        for (int i = 0; i < SPAWNER_OFFSETS.length; i++) {
            int[] offset = SPAWNER_OFFSETS[i];
            Location at = findExistingSpawner(district.center(), offset[0], offset[1]);
            List<String> entities = district.spawnerPools().get(Math.min(i, district.spawnerPools().size() - 1));
            spawners.register(at, district.group(), entities, initiallyActive);
        }
        if (spawners.remainingInGroup(district.group()) > 0) armedGroups.add(district.group());

        Location chest = findExistingContainer(district.center().clone().add(6, 0, 3));
        buildRewardPedestal(chest);
        loot.placeCampaignChest(chest, AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                district.chestTier(), district.missionItem(), true);
        rewardChests.add(chest.clone());
        protect(chest);
    }

    public void tick() {
        if (!installed || bossDefeated || activeDistrict >= districts.size()) return;
        District district = districts.get(activeDistrict);

        // Destruir los spawners libera el cofre, pero el avance ocurre cuando el
        // equipo recoge el objeto de misión. Así los emblemas no son decorativos.
        if (awaitingDistrictReward) {
            if (teamHasMissionItem(district.missionItem())) advanceAfterRewardClaim(district);
            return;
        }
        if (!armedGroups.contains(district.group())) return;
        if (spawners.remainingInGroup(district.group()) > 0) return;
        completeDistrict(district);
    }

    private void completeDistrict(District district) {
        if (!completed.add(district.id())) return;
        Location chest = rewardChests.get(activeDistrict);
        loot.setChestLocked(chest, false);
        awaitingDistrictReward = true;

        for (Player player : participantsInWorld()) {
            player.sendTitle(ChatColor.GREEN + "Distrito liberado",
                    ChatColor.WHITE + district.displayName(), 10, 55, 15);
            player.sendMessage(ChatColor.AQUA + "[Bingo] " + ChatColor.WHITE
                    + district.displayName() + " fue limpiado. El cofre de recompensa ya puede abrirse.");
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.20F);
            player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0),
                    18, 0.8, 0.6, 0.8, 0.02);
            showDistrictSomita(player, district, chest);
        }

    }

    private void advanceAfterRewardClaim(District district) {
        if (!awaitingDistrictReward || activeDistrict >= districts.size()) return;
        awaitingDistrictReward = false;
        setIndicator(activeDistrict, district.pedestalMaterial());
        game.sendToParticipants(ChatColor.GREEN + "[Bingo] Objeto recuperado: "
                + CampaignMissionItems.displayName(district.missionItem()) + ChatColor.GREEN + ".");

        activeDistrict++;
        if (activeDistrict < districts.size()) {
            District next = districts.get(activeDistrict);
            spawners.activateGroup(next.group());
            game.sendToParticipants(ChatColor.YELLOW + "[Bingo] Nuevo objetivo: "
                    + ChatColor.WHITE + "limpia " + next.displayName() + ".");
            for (Player player : participantsInWorld()) {
                Location guide = rewardChests.get(activeDistrict - 1).clone().add(2.5D, 0.0D, 1.5D);
                Location nextTarget = surfaceTarget(next.center());
                guide.setYaw(yawToward(guide, nextTarget));
                somita.show(player, "overworld_route_" + next.id(),
                        SomitaGuideNetwork.Variant.OVERWORLD,
                        SomitaGuideNetwork.Animation.POINT,
                        guide, 130,
                        "Ya tenemos el emblema. Sigue el camino hacia " + next.displayName() + ".");
                somita.effect(player, SomitaGuideNetwork.Effect.OVERWORLD_POINT, 42, nextTarget);
            }
            return;
        }
        openCitadelGate();
        requestBossSpawn();
    }

    private boolean teamHasMissionItem(String missionItemId) {
        for (Player player : participantsInWorld()) {
            if (CampaignMissionItems.has(player, plugin, missionItemId)) return true;
        }
        return false;
    }

    private void showDistrictSomita(Player player, District district, Location chest) {
        Location guide = chest.clone().add(2.5D, 0.0D, 1.5D);
        guide.setYaw(yawToward(guide, player.getLocation()));
        somita.show(player, "overworld_" + district.id() + "_clear",
                SomitaGuideNetwork.Variant.OVERWORLD,
                SomitaGuideNetwork.Animation.CELEBRATE_CUTE,
                guide, 150,
                "¡Zona limpia! Abre el cofre: allí está "
                        + ChatColor.stripColor(CampaignMissionItems.displayName(district.missionItem())) + ".");
        somita.effect(player, SomitaGuideNetwork.Effect.CELEBRATION_BURST, 36, chest);
    }

    private void requestBossSpawn() {
        if (bossSpawnRequested) return;
        bossSpawnRequested = true;
        if (unlockBoss != null) unlockBoss.run();
        for (Player player : participantsInWorld()) {
            Location guide = gateCenter.clone().add(0.5D, 0.0D, -4.5D);
            guide.setYaw(0.0F);
            somita.show(player, "overworld_citadel_open",
                    SomitaGuideNetwork.Variant.OVERWORLD,
                    SomitaGuideNetwork.Animation.POINT,
                    guide, 180,
                    "Los tres distritos están libres. Lleva las tres piezas al altar exterior de la arena.");
            somita.effect(player, SomitaGuideNetwork.Effect.OVERWORLD_POINT, 60, bossHome);
        }
        game.sendToParticipants(ChatColor.GOLD + "[Bingo] La ciudadela está abierta. "
                + ChatColor.WHITE + "Sigue la calzada hasta la puerta ritual y coloca las tres piezas.");
    }

    public void onBossDefeated() {
        if (!installed || bossDefeated) return;
        bossDefeated = true;
        if (finalChest != null) loot.setChestLocked(finalChest, false);
        for (Player player : participantsInWorld()) {
            Location guide = finalChest == null ? bossHome.clone() : finalChest.clone().add(2.5, 0, 1.5);
            guide.setYaw(yawToward(guide, player.getLocation()));
            somita.show(player, "overworld_victory",
                    SomitaGuideNetwork.Variant.CELEBRATION,
                    SomitaGuideNetwork.Animation.CELEBRATE_CUTE,
                    guide, 190,
                    "¡La capital fue liberada! Recoge tu recompensa y continúa hacia el Nether.");
            somita.effect(player, SomitaGuideNetwork.Effect.CELEBRATION_BURST, 54,
                    finalChest == null ? bossHome : finalChest);
        }
    }

    private void buildCitadelSeal() {
        // 1.48.0 seals the real citadel doorway. Older revisions created a second
        // emerald/iron cage eighty-eight blocks away, which invaded the approved route.
        gateCenter = new Location(world, citadel.getBlockX(), citadel.getBlockY(),
                citadel.getBlockZ() + 55);
        int baseY = gateCenter.getBlockY();
        for (int x = -4; x <= 4; x++) {
            for (int y = 1; y <= 6; y++) {
                Location at = new Location(world, gateCenter.getBlockX() + x, baseY + y,
                        gateCenter.getBlockZ());
                at.getBlock().setType(Material.DARK_OAK_FENCE, false);
                protect(at);
            }
        }
        pedestalIndicators.clear();
    }

    private void openCitadelGate() {
        if (gateOpened || gateCenter == null) return;
        gateOpened = true;
        int baseY = gateCenter.getBlockY();
        for (int x = -4; x <= 4; x++) {
            for (int y = 1; y <= 6; y++) {
                Location at = new Location(world, gateCenter.getBlockX() + x, baseY + y,
                        gateCenter.getBlockZ());
                at.getBlock().setType(Material.AIR, false);
                protectedBlocks.remove(key(at));
            }
        }
        world.playSound(gateCenter, Sound.BLOCK_IRON_DOOR_OPEN, 1.2F, 0.65F);
        world.spawnParticle(Particle.HAPPY_VILLAGER, gateCenter.clone().add(0, 3, 0),
                80, 4.0, 2.5, 1.0, 0.04);
    }

    private void installFinalRewardChest() {
        finalChest = bossHome.clone().add(0, 0, 24);
        finalChest.setX(finalChest.getBlockX());
        finalChest.setY(finalChest.getBlockY());
        finalChest.setZ(finalChest.getBlockZ());
        buildRewardPedestal(finalChest);
        loot.placeCampaignChest(finalChest, AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.LEGENDARY, null, true);
        protect(finalChest);
    }

    private void buildRewardPedestal(Location chest) {
        int y = chest.getBlockY();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            Location floor = chest.clone().add(x, -1, z);
            floor.getBlock().setType((Math.abs(x) == 2 || Math.abs(z) == 2)
                    ? Material.MOSSY_STONE_BRICKS : Material.CHISELED_STONE_BRICKS, false);
        }
        for (int yy = 0; yy <= 3; yy++) chest.clone().add(0, yy, 0).getBlock().setType(Material.AIR, false);
        chest.setY(y);
    }

    private void setIndicator(int index, Material material) {
        if (index < 0 || index >= pedestalIndicators.size()) return;
        pedestalIndicators.get(index).getBlock().setType(material, false);
    }

    private Location findExistingSpawner(Location center, int dx, int dz) {
        int x = center.getBlockX() + dx;
        int z = center.getBlockZ() + dz;
        int top = world.getHighestBlockYAt(x, z);
        for (int y = Math.min(world.getMaxHeight() - 2, top + 6);
             y >= Math.max(world.getMinHeight() + 1, top - 48); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() == Material.SPAWNER) return block.getLocation();
        }
        Location fallback = surfaceLocation(x, z);
        fallback.getBlock().setType(Material.AIR, false);
        fallback.clone().add(0, 1, 0).getBlock().setType(Material.AIR, false);
        return fallback;
    }

    private Location findExistingContainer(Location rough) {
        int x = rough.getBlockX();
        int z = rough.getBlockZ();
        int top = world.getHighestBlockYAt(x, z);
        for (int y = Math.min(world.getMaxHeight() - 2, top + 4);
             y >= Math.max(world.getMinHeight() + 1, top - 42); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getState() instanceof Container) return block.getLocation();
        }
        return surfaceLocation(x, z);
    }

    private Location surfaceLocation(int x, int z) {
        int y = world.getHighestBlockYAt(x, z) + 1;
        y = Math.max(world.getMinHeight() + 2, Math.min(world.getMaxHeight() - 4, y));
        Location result = new Location(world, x, y, z);
        for (int yy = 0; yy <= 2; yy++) result.clone().add(0, yy, 0).getBlock().setType(Material.AIR, false);
        if (!result.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
            result.clone().add(0, -1, 0).getBlock().setType(Material.STONE_BRICKS, false);
        }
        return result;
    }

    public boolean protectFromBreak(Location at, Player player) {
        if (!installed || at == null || at.getWorld() != world || !protectedBlocks.contains(key(at))) return false;
        if (player != null) player.sendMessage(ChatColor.RED
                + "Este cofre, sello o acceso forma parte de la progresión de la campaña.");
        return true;
    }

    public boolean isProtected(Location at) {
        return installed && at != null && at.getWorld() == world && protectedBlocks.contains(key(at));
    }

    public boolean debugTeleport(Player player, String stage) {
        if (!installed || player == null) return false;
        Location target = switch (stage.toLowerCase(Locale.ROOT)) {
            case "village", "start", "inicio" -> village;
            case "residential", "residencial", "district1" -> surfaceTarget(districts.get(0).center());
            case "commercial", "comercial", "district2" -> surfaceTarget(districts.get(1).center());
            case "military", "militar", "district3" -> surfaceTarget(districts.get(2).center());
            case "citadel", "ciudadela", "gate" -> gateCenter.clone().add(0, 0, -8);
            case "surfaceboss", "overworldboss" -> bossHome.clone().add(0, 0, -22);
            case "surfaceportal", "overworldportal" -> portal == null ? null : portal.clone().add(0, 0, -6);
            default -> null;
        };
        return target != null && player.teleport(target.clone().add(0.5D, 0.1D, 0.5D));
    }

    public String status() {
        if (!installed) return "progresión Overworld no instalada";
        String current = activeDistrict < districts.size()
                ? districts.get(activeDistrict).displayName() : (bossDefeated ? "completada" : "jefe desbloqueado");
        return "Overworld=" + completed.size() + "/3 distritos · actual=" + current
                + (awaitingDistrictReward ? " · esperando recompensa" : "")
                + " · puerta=" + (gateOpened ? "abierta" : "cerrada")
                + " · jefe=" + (bossDefeated ? "derrotado" : bossSpawnRequested ? "activo" : "bloqueado");
    }

    private List<Player> participantsInWorld() {
        if (world == null) return List.of();
        return world.getPlayers().stream().filter(player -> game.getTeamOf(player) != null).toList();
    }

    private void protect(Location at) {
        if (at != null && at.getWorld() != null) protectedBlocks.add(key(at));
    }

    private String key(Location at) {
        return at.getWorld().getUID() + ":" + at.getBlockX() + ":" + at.getBlockY() + ":" + at.getBlockZ();
    }

    private Location surfaceTarget(Location rough) {
        int x = rough.getBlockX();
        int z = rough.getBlockZ();
        int y = world.getHighestBlockYAt(x, z) + 1;
        y = Math.max(world.getMinHeight() + 2, Math.min(world.getMaxHeight() - 3, y));
        return new Location(world, x + 0.5D, y, z + 0.5D);
    }

    private double horizontalDistanceSquared(Location first, Location second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private float yawToward(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
