package com.arlight.bingo.dungeon;

import com.arlight.bingo.game.BingoCard;
import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.game.BingoGoal;
import com.arlight.bingo.game.BingoTeam;
import com.arlight.bingo.game.GameState;
import com.arlight.bingo.game.GoalType;
import com.arlight.bingo.util.CampaignItemBridge;
import com.arlight.bingo.util.CampaignMissionItems;
import com.arlight.bingo.util.MaterialResolver;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Botín personal para los cofres de dungeon. Los bloques de ArlightBosses son
 * únicamente la apariencia animada; cada jugador recibe un inventario propio,
 * similar a Lootr, y no puede usarlo como almacenamiento compartido.
 */
public final class AdaptiveDungeonLootManager implements Listener {

    public enum DimensionGroup { OVERWORLD, NETHER, END }

    public enum ChestTier {
        COMMON(0, 1.0D), RARE(1, 1.20D), EPIC(2, 1.45D), LEGENDARY(3, 1.75D);
        private final int objectiveBonus;
        private final double portionMultiplier;
        ChestTier(int objectiveBonus, double portionMultiplier) {
            this.objectiveBonus = objectiveBonus;
            this.portionMultiplier = portionMultiplier;
        }
        public boolean atLeast(ChestTier other) { return ordinal() >= other.ordinal(); }
    }

    private record ChestKey(String world, int x, int y, int z) {
        private static ChestKey of(Location location) {
            return new ChestKey(location.getWorld().getName(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
        private String compact() { return world + ":" + x + ":" + y + ":" + z; }
    }

    private record DungeonChest(DimensionGroup dimension, int index, ChestTier tier,
                                String customBlockId, String missionItemId) {
        boolean finalReward() { return tier == ChestTier.LEGENDARY; }
        boolean custom() { return customBlockId != null && !customBlockId.isBlank(); }
    }

    private static final class TreasureHolder implements InventoryHolder {
        private final String inventoryKey;
        private Inventory inventory;
        private TreasureHolder(String inventoryKey) { this.inventoryKey = inventoryKey; }
        private void bind(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final JavaPlugin plugin;
    private final BingoGame game;
    private final Map<ChestKey, DungeonChest> chests = new HashMap<>();
    private final Map<DimensionGroup, Integer> chestCounts = new HashMap<>();
    private final Map<String, ItemStack[]> personalInventories = new HashMap<>();
    private final Map<String, Integer> progressionClaims = new HashMap<>();
    private final Map<String, Integer> rareRewardClaims = new HashMap<>();
    private final Map<String, Integer> directGoalClaims = new HashMap<>();
    private final Set<String> claimed = new HashSet<>();
    private final Set<String> finalClaimed = new HashSet<>();
    private final Map<ChestKey, Integer> customChestViewers = new HashMap<>();
    private final Set<ChestKey> lockedChests = new HashSet<>();
    private final Map<String, ChestKey> openInventoryChests = new HashMap<>();
    private static final String CLAIM_TAG_PREFIX = "arlightbingo_chest_";

    public AdaptiveDungeonLootManager(JavaPlugin plugin, BingoGame game) {
        this.plugin = plugin;
        this.game = game;
    }

    public void reset() {
        chests.clear();
        chestCounts.clear();
        personalInventories.clear();
        progressionClaims.clear();
        rareRewardClaims.clear();
        directGoalClaims.clear();
        claimed.clear();
        finalClaimed.clear();
        customChestViewers.clear();
        lockedChests.clear();
        openInventoryChests.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            new HashSet<>(player.getScoreboardTags()).stream()
                    .filter(tag -> tag.startsWith(CLAIM_TAG_PREFIX))
                    .forEach(player::removeScoreboardTag);
        }
    }

    public void registerChest(Location location, DimensionGroup dimension, boolean finalReward) {
        registerChest(location, dimension, finalReward ? ChestTier.LEGENDARY : ChestTier.COMMON);
    }

    public void registerChest(Location location, DimensionGroup dimension, ChestTier tier) {
        register(location, dimension, tier, null, null);
    }

    /** Devuelve true para cualquier cofre personal/custom administrado por Bingo. */
    public boolean isRegisteredChest(Location location) {
        return location != null && location.getWorld() != null && chests.containsKey(ChestKey.of(location));
    }

    /**
     * Elimina registros de cofres que una segunda pasada arquitectónica va a
     * reconstruir. Evita que una coordenada que ya no contiene un cofre siga
     * abriendo una GUI personal invisible. Se usa sólo durante la generación.
     */
    public void unregisterInBox(Location center, int halfWidth, int halfDepth,
                                int minYOffset, int maxYOffset) {
        if (center == null || center.getWorld() == null) return;
        String worldName = center.getWorld().getName();
        int minX = center.getBlockX() - Math.max(0, halfWidth);
        int maxX = center.getBlockX() + Math.max(0, halfWidth);
        int minZ = center.getBlockZ() - Math.max(0, halfDepth);
        int maxZ = center.getBlockZ() + Math.max(0, halfDepth);
        int minY = center.getBlockY() + Math.min(minYOffset, maxYOffset);
        int maxY = center.getBlockY() + Math.max(minYOffset, maxYOffset);
        Set<ChestKey> removed = new HashSet<>();
        chests.keySet().removeIf(key -> {
            boolean inside = key.world().equalsIgnoreCase(worldName)
                    && key.x() >= minX && key.x() <= maxX
                    && key.y() >= minY && key.y() <= maxY
                    && key.z() >= minZ && key.z() <= maxZ;
            if (inside) removed.add(key);
            return inside;
        });
        removed.forEach(customChestViewers::remove);
        lockedChests.removeAll(removed);
        if (!removed.isEmpty()) openInventoryChests.values().removeIf(removed::contains);
    }

    /** Coloca la variante visual adecuada. Si el mod no está disponible usa cofre/Lootr. */
    public void placeCustomChest(Location location, DimensionGroup dimension, ChestTier tier) {
        if (location == null || location.getWorld() == null) return;
        String id = customBlockFor(dimension, tier);
        boolean placed = plugin.getConfig().getBoolean("dungeon-loot.custom-chests.enabled", true)
                && CampaignItemBridge.setModBlockState(location,
                id + "[facing=north,chest_state=closed]");
        if (placed) {
            register(location, dimension, tier, id, null);
            return;
        }

        location.getBlock().setType(Material.CHEST, false);
        if (location.getBlock().getState() instanceof Chest chest) {
            chest.setLootTable(switch (dimension) {
                case OVERWORLD -> tier.atLeast(ChestTier.EPIC)
                        ? LootTables.STRONGHOLD_CROSSING.getLootTable() : LootTables.SIMPLE_DUNGEON.getLootTable();
                case NETHER -> tier == ChestTier.LEGENDARY
                        ? LootTables.BASTION_TREASURE.getLootTable() : LootTables.NETHER_BRIDGE.getLootTable();
                case END -> tier.atLeast(ChestTier.EPIC)
                        ? LootTables.END_CITY_TREASURE.getLootTable() : LootTables.STRONGHOLD_CORRIDOR.getLootTable();
            });
            chest.update(true, false);
        }
        register(location, dimension, tier, null, null);
    }

    /** Coloca un cofre de distrito con objeto de misión y bloqueo inicial. */
    public void placeCampaignChest(Location location, DimensionGroup dimension, ChestTier tier,
                                   String missionItemId, boolean initiallyLocked) {
        if (location == null || location.getWorld() == null) return;
        String id = customBlockFor(dimension, tier);
        boolean placed = plugin.getConfig().getBoolean("dungeon-loot.custom-chests.enabled", true)
                && CampaignItemBridge.setModBlockState(location,
                id + "[facing=north,chest_state=closed]");
        if (!placed) {
            location.getBlock().setType(Material.CHEST, false);
        }
        register(location, dimension, tier, placed ? id : null, missionItemId);
        setChestLocked(location, initiallyLocked);
    }

    public void setChestLocked(Location location, boolean locked) {
        if (location == null || location.getWorld() == null) return;
        ChestKey key = ChestKey.of(location);
        if (locked) lockedChests.add(key);
        else lockedChests.remove(key);
    }

    public boolean isChestLocked(Location location) {
        return location != null && location.getWorld() != null
                && lockedChests.contains(ChestKey.of(location));
    }

    private void register(Location location, DimensionGroup dimension, ChestTier tier, String customId, String missionItemId) {
        if (location == null || location.getWorld() == null) return;
        int index = chestCounts.getOrDefault(dimension, 0);
        chests.put(ChestKey.of(location), new DungeonChest(dimension, index,
                tier == null ? ChestTier.COMMON : tier, customId, missionItemId));
        chestCounts.put(dimension, index + 1);
    }

    private String customBlockFor(DimensionGroup dimension, ChestTier tier) {
        String path = "dungeon-loot.custom-chests.blocks." + dimension.name().toLowerCase(Locale.ROOT)
                + "." + tier.name().toLowerCase(Locale.ROOT);
        String configured = plugin.getConfig().getString(path, "").trim();
        if (!configured.isBlank()) return configured;
        return switch (dimension) {
            case OVERWORLD -> switch (tier) {
                case COMMON -> CampaignItemBridge.COPPER_TREASURE_CHEST;
                case RARE -> CampaignItemBridge.IRON_TREASURE_CHEST;
                case EPIC -> CampaignItemBridge.PONY_TREASURE_CHEST;
                case LEGENDARY -> CampaignItemBridge.DIAMOND_TREASURE_CHEST;
            };
            case NETHER -> switch (tier) {
                case COMMON -> CampaignItemBridge.IRON_TREASURE_CHEST;
                case RARE -> CampaignItemBridge.PONY_TREASURE_CHEST;
                case EPIC, LEGENDARY -> CampaignItemBridge.DIAMOND_TREASURE_CHEST;
            };
            case END -> switch (tier) {
                case COMMON -> CampaignItemBridge.PONY_TREASURE_CHEST;
                case RARE -> CampaignItemBridge.DIAMOND_TREASURE_CHEST;
                case EPIC, LEGENDARY -> CampaignItemBridge.NETHERITE_TREASURE_CHEST;
            };
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChestInteraction(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        ChestKey key = ChestKey.of(event.getClickedBlock().getLocation());
        DungeonChest chest = chests.get(key);
        if (chest == null || game.getState() != GameState.RUNNING) return;
        if (lockedChests.contains(key)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED
                    + "Este cofre se abrirá cuando el distrito haya sido liberado.");
            event.getPlayer().playSound(event.getPlayer().getLocation(),
                    org.bukkit.Sound.BLOCK_IRON_DOOR_CLOSE, 0.55F, 0.75F);
            return;
        }
        if (chest.custom()) {
            event.setCancelled(true);
            openPersonalChest(event.getPlayer(), event.getClickedBlock().getLocation(), key, chest);
            return;
        }

        Player player = event.getPlayer();
        String claimKey = player.getUniqueId() + ":" + key.compact();
        if (claimed.contains(claimKey)) return;
        injectWhenOpen(player, chest, claimKey, 0);
    }

    private void openPersonalChest(Player player, Location location, ChestKey key, DungeonChest chest) {
        String inventoryKey = player.getUniqueId() + ":" + key.compact();
        String tag = claimTag(inventoryKey);
        boolean alreadyClaimed = claimed.contains(inventoryKey) || player.getScoreboardTags().contains(tag);
        ItemStack[] saved = personalInventories.get(inventoryKey);

        TreasureHolder holder = new TreasureHolder(inventoryKey);
        int size = switch (chest.tier) {
            case COMMON, RARE -> 27;
            case EPIC -> 36;
            case LEGENDARY -> 54;
        };
        Inventory inventory = Bukkit.createInventory(holder, size, chestTitle(chest));
        holder.bind(inventory);

        if (saved != null) {
            inventory.setContents(saved);
        } else if (!alreadyClaimed) {
            List<ItemStack> rewards = generateRewards(player, chest, key);
            placeRewards(inventory, rewards, new Random(inventoryKey.hashCode()));
            claimed.add(inventoryKey);
            player.addScoreboardTag(tag);
            personalInventories.put(inventoryKey, inventory.getContents());
            maybeGrantDirectGoal(player, chest, new Random(inventoryKey.hashCode() * 31L + 17L));
        }
        ensureMissionItem(player, inventory, chest);
        personalInventories.put(inventoryKey, inventory.getContents());

        openCustomChestAnimation(location, key, chest.customBlockId);
        openInventoryChests.put(inventoryKey, key);
        player.openInventory(inventory);
        player.playSound(location, Sound.BLOCK_CHEST_OPEN, 0.9F, chest.tier == ChestTier.LEGENDARY ? 0.65F : 1.0F);
    }

    private String chestTitle(DungeonChest chest) {
        String tier = switch (chest.tier) {
            case COMMON -> "Cobre";
            case RARE -> "Hierro";
            case EPIC -> chest.dimension == DimensionGroup.END ? "Netherita" : "Pony";
            case LEGENDARY -> chest.dimension == DimensionGroup.END ? "Netherita legendaria" : "Tesoro legendario";
        };
        return ChatColor.DARK_PURPLE + "Cofre personal: " + ChatColor.LIGHT_PURPLE + tier;
    }

    private void openCustomChestAnimation(Location location, ChestKey key, String id) {
        int viewers = customChestViewers.getOrDefault(key, 0);
        customChestViewers.put(key, viewers + 1);
        if (viewers > 0) return;
        // No usa /setblock: llama la BlockEntity real del mod, conserva NBT e inventario
        // y deja que ArlightBosses sincronice OPENING -> OPENED.
        CampaignItemBridge.animateTreasureChest(location, true);
    }

    private void closeCustomChestAnimation(ChestKey key) {
        DungeonChest chest = chests.get(key);
        if (chest == null || !chest.custom()) return;
        int remaining = Math.max(0, customChestViewers.getOrDefault(key, 1) - 1);
        if (remaining > 0) {
            customChestViewers.put(key, remaining);
            return;
        }
        customChestViewers.remove(key);
        org.bukkit.World world = Bukkit.getWorld(key.world());
        if (world == null) return;
        Location at = new Location(world, key.x(), key.y(), key.z());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (customChestViewers.getOrDefault(key, 0) == 0 && at.getWorld() != null) {
                CampaignItemBridge.animateTreasureChest(at, false);
            }
        }, 2L);
    }

    private String claimTag(String inventoryKey) {
        return CLAIM_TAG_PREFIX + Integer.toUnsignedString(inventoryKey.hashCode(), 36);
    }

    @EventHandler
    public void onPersonalChestClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TreasureHolder holder)) return;
        personalInventories.put(holder.inventoryKey, event.getInventory().getContents());
        ChestKey key=openInventoryChests.remove(holder.inventoryKey);
        if(key!=null)closeCustomChestAnimation(key);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPersonalChestClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TreasureHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlot() >= topSize) {
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) event.setCancelled(true);
            return;
        }
        InventoryAction action = event.getAction();
        boolean taking = action == InventoryAction.PICKUP_ALL || action == InventoryAction.PICKUP_HALF
                || action == InventoryAction.PICKUP_ONE || action == InventoryAction.PICKUP_SOME
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.DROP_ALL_SLOT || action == InventoryAction.DROP_ONE_SLOT;
        if (!taking) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPersonalChestDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TreasureHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
    }

    private void injectWhenOpen(Player player, DungeonChest chest, String claimKey, int attempt) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || claimed.contains(claimKey)) return;
            Inventory top = player.getOpenInventory().getTopInventory();
            org.bukkit.event.inventory.InventoryType type = top.getType();
            boolean containerOpen = type == org.bukkit.event.inventory.InventoryType.CHEST
                    || type == org.bukkit.event.inventory.InventoryType.BARREL
                    || type == org.bukkit.event.inventory.InventoryType.SHULKER_BOX;
            if (!containerOpen) {
                if (attempt < 4) injectWhenOpen(player, chest, claimKey, attempt + 1);
                return;
            }
            List<ItemStack> additions = generateRewards(player, chest, null);
            for (ItemStack stack : additions) {
                Map<Integer, ItemStack> leftovers = top.addItem(stack);
                for (ItemStack leftover : leftovers.values()) player.getInventory().addItem(leftover);
            }
            claimed.add(claimKey);
            maybeGrantDirectGoal(player, chest, new Random(claimKey.hashCode()));
        }, attempt == 0 ? 2L : 1L);
    }

    private List<ItemStack> generateRewards(Player player, DungeonChest chest, ChestKey key) {
        BingoTeam team = game.getTeamOf(player);
        BingoCard card = team == null ? null : team.getCard();
        List<ItemStack> rewards = card == null ? new ArrayList<>() : objectiveLoot(card, chest);
        addTierSupplies(chest, rewards);
        addProgressionEquipment(player, chest, rewards);
        if (chest.finalReward()) addFinalRewards(player, chest.dimension, rewards);
        addRareModdedRewards(player, chest, rewards, key == null
                ? new Random(player.getUniqueId().hashCode() ^ chest.index)
                : new Random((player.getUniqueId().toString() + key.compact()).hashCode()));
        if (chest.missionItemId != null && !chest.missionItemId.isBlank()
                && !CampaignMissionItems.has(player, plugin, chest.missionItemId)) {
            rewards.add(CampaignMissionItems.create(plugin, chest.missionItemId));
        }
        return rewards;
    }

    private void ensureMissionItem(Player player, Inventory inventory, DungeonChest chest) {
        if (chest.missionItemId == null || chest.missionItemId.isBlank()
                || CampaignMissionItems.has(player, plugin, chest.missionItemId)) return;
        for (ItemStack stack : inventory.getContents()) {
            if (CampaignMissionItems.is(plugin, stack, chest.missionItemId)) return;
        }
        inventory.addItem(CampaignMissionItems.create(plugin, chest.missionItemId));
    }

    private void placeRewards(Inventory inventory, List<ItemStack> rewards, Random random) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inventory.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, random);
        int cursor = 0;
        for (ItemStack stack : rewards) {
            if (stack == null || stack.getType().isAir()) continue;
            if (cursor < slots.size()) inventory.setItem(slots.get(cursor++), stack);
            else inventory.addItem(stack);
        }
    }

    private List<ItemStack> objectiveLoot(BingoCard card, DungeonChest chest) {
        if (!plugin.getConfig().getBoolean("dungeon-loot.enabled", true)) return new ArrayList<>();
        List<BingoGoal> candidates = card.getGoals().stream()
                .filter(goal -> !goal.isCompleted()).filter(goal -> goal.getType() == GoalType.ITEM_COLLECT)
                .filter(goal -> classify(goal.getTarget()) == chest.dimension)
                .filter(goal -> MaterialResolver.resolve(goal.getTarget()) != null)
                .sorted(Comparator.comparing(BingoGoal::getId)).toList();
        int percentage = clamp(plugin.getConfig().getInt("dungeon-loot.objective-items-percentage", 40), 0, 100);
        int allowedTotal = (int) Math.ceil(candidates.size() * percentage / 100.0);
        int count = Math.max(1, chestCounts.getOrDefault(chest.dimension, 1));
        int maximum = Math.max(1, plugin.getConfig().getInt("dungeon-loot.maximum-objectives-per-chest", 3)
                + chest.tier.objectiveBonus);
        double portion = Math.max(0.05, Math.min(1.0,
                plugin.getConfig().getDouble("dungeon-loot.maximum-required-portion", 0.5)
                        * chest.tier.portionMultiplier));
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < Math.min(allowedTotal, candidates.size()); i++) {
            BingoGoal goal = candidates.get(i);
            if (Math.floorMod(goal.getId().hashCode(), count) != chest.index || result.size() >= maximum) continue;
            Material material = MaterialResolver.resolve(goal.getTarget());
            int amount = Math.max(1, (int) Math.ceil(goal.getAmountRequired() * portion));
            addAmount(result, material, amount);
        }
        return result;
    }

    private void addTierSupplies(DungeonChest chest, List<ItemStack> rewards) {
        if (!plugin.getConfig().getBoolean("dungeon-loot.tier-supplies.enabled", true)) return;
        switch (chest.dimension) {
            case OVERWORLD -> {
                rewards.add(new ItemStack(Material.COOKED_BEEF, chest.tier.atLeast(ChestTier.EPIC) ? 16 : 8));
                rewards.add(new ItemStack(Material.TORCH, chest.tier.atLeast(ChestTier.RARE) ? 32 : 16));
                rewards.add(new ItemStack(Material.ARROW, chest.tier.atLeast(ChestTier.RARE) ? 40 : 16));
                if (chest.tier.atLeast(ChestTier.RARE)) rewards.add(new ItemStack(Material.SHIELD, 1));
                if (chest.tier.atLeast(ChestTier.EPIC)) { rewards.add(new ItemStack(Material.GOLDEN_APPLE, 2)); rewards.add(potion(PotionType.HEALING,1)); }
            }
            case NETHER -> {
                rewards.add(new ItemStack(Material.COOKED_PORKCHOP, 16));
                rewards.add(potion(PotionType.FIRE_RESISTANCE, chest.tier.atLeast(ChestTier.EPIC) ? 3 : 2));
                rewards.add(new ItemStack(Material.ARROW, 48));
                rewards.add(new ItemStack(Material.GOLD_INGOT, chest.tier.atLeast(ChestTier.EPIC)?12:6));
                if (chest.tier.atLeast(ChestTier.EPIC)) { rewards.add(new ItemStack(Material.GOLDEN_APPLE, 3)); rewards.add(potion(PotionType.HEALING,2)); }
            }
            case END -> {
                rewards.add(new ItemStack(Material.GOLDEN_CARROT, 24));
                rewards.add(new ItemStack(Material.ENDER_PEARL, 12));
                rewards.add(potion(PotionType.HEALING, chest.tier.atLeast(ChestTier.EPIC) ? 4 : 2));
                rewards.add(new ItemStack(Material.ARROW,48));
                if (chest.tier.atLeast(ChestTier.EPIC)) { rewards.add(potion(PotionType.STRENGTH,2)); rewards.add(new ItemStack(Material.TOTEM_OF_UNDYING,1)); }
            }
        }
    }

    private void addProgressionEquipment(Player player, DungeonChest chest, List<ItemStack> rewards) {
        if (!plugin.getConfig().getBoolean("dungeon-loot.progression-equipment.enabled", true)) return;
        boolean eligible = switch (chest.dimension) {
            case OVERWORLD, NETHER -> chest.tier.atLeast(ChestTier.RARE);
            case END -> chest.tier.atLeast(ChestTier.EPIC);
        };
        if (!eligible) return;
        String key = player.getUniqueId() + ":" + chest.dimension;
        int stage = progressionClaims.getOrDefault(key, 0);
        progressionClaims.put(key, stage + 1);
        switch (chest.dimension) {
            case OVERWORLD -> addGearStage(rewards, stage,
                    Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_HELMET,
                    Material.IRON_BOOTS, Material.IRON_SWORD, Material.BOW, false);
            case NETHER -> addGearStage(rewards, stage,
                    Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_HELMET,
                    Material.DIAMOND_BOOTS, Material.DIAMOND_SWORD, Material.CROSSBOW, true);
            case END -> addGearStage(rewards, stage,
                    Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_HELMET,
                    Material.NETHERITE_BOOTS, Material.NETHERITE_SWORD, Material.BOW, true);
        }
        if (chest.finalReward()) ensureFinalSet(player, chest.dimension, rewards);
    }

    private void addGearStage(List<ItemStack> rewards, int stage, Material chest, Material legs,
                              Material helmet, Material boots, Material weapon, Material ranged,
                              boolean stronger) {
        switch (Math.floorMod(stage, 6)) {
            case 0 -> rewards.add(enchanted(chest, Enchantment.PROTECTION, stronger ? 3 : 2));
            case 1 -> rewards.add(enchanted(legs, Enchantment.PROTECTION, stronger ? 3 : 2));
            case 2 -> {
                rewards.add(enchanted(helmet, Enchantment.PROTECTION, stronger ? 3 : 2));
                rewards.add(enchanted(boots, Enchantment.FEATHER_FALLING, stronger ? 3 : 2));
            }
            case 3 -> {
                rewards.add(enchanted(weapon, Enchantment.SHARPNESS, stronger ? 4 : 2));
                rewards.add(new ItemStack(Material.SHIELD, 1));
            }
            case 4 -> {
                rewards.add(enchanted(ranged, ranged == Material.CROSSBOW ? Enchantment.QUICK_CHARGE : Enchantment.POWER,
                        stronger ? 3 : 2));
                rewards.add(new ItemStack(Material.ARROW, 32));
            }
            default -> rewards.add(new ItemStack(stronger ? Material.GOLDEN_APPLE : Material.COOKED_BEEF,
                    stronger ? 2 : 8));
        }
    }

    private void ensureFinalSet(Player player, DimensionGroup dimension, List<ItemStack> rewards) {
        Material[] desired = switch (dimension) {
            case OVERWORLD -> new Material[]{Material.IRON_HELMET, Material.IRON_CHESTPLATE,
                    Material.IRON_LEGGINGS, Material.IRON_BOOTS, Material.IRON_SWORD};
            case NETHER -> new Material[]{Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
                    Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, Material.DIAMOND_SWORD};
            case END -> new Material[]{Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                    Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, Material.NETHERITE_SWORD};
        };
        for (Material material : desired) if (!playerHas(player, material) && rewards.stream().noneMatch(i -> i.getType() == material)) {
            rewards.add(enchanted(material, material.name().endsWith("SWORD") ? Enchantment.SHARPNESS : Enchantment.PROTECTION,
                    dimension == DimensionGroup.OVERWORLD ? 2 : 3));
        }
    }

    private boolean playerHas(Player player, Material material) {
        for (ItemStack stack : player.getInventory().getContents())
            if (stack != null && stack.getType() == material) return true;
        return false;
    }

    private ItemStack enchanted(Material material, Enchantment enchantment, int level) {
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.addEnchant(enchantment, level, true);
            meta.addEnchant(Enchantment.UNBREAKING, Math.max(1, level - 1), true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack potion(PotionType type, int amount) {
        ItemStack stack = new ItemStack(Material.POTION, Math.max(1, amount));
        if (stack.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(type);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void addFinalRewards(Player player, DimensionGroup dimension, List<ItemStack> rewards) {
        String key = player.getUniqueId() + ":" + dimension;
        if (!finalClaimed.add(key)) return;
        if (dimension == DimensionGroup.END) {
            int elytra = Math.max(0, plugin.getConfig().getInt("dungeon-loot.final-rewards.elytra", 1));
            int heads = Math.max(0, plugin.getConfig().getInt("dungeon-loot.final-rewards.dragon-head", 1));
            if (elytra > 0) rewards.add(new ItemStack(Material.ELYTRA, 1));
            if (heads > 0) rewards.add(new ItemStack(Material.DRAGON_HEAD, Math.min(heads, 64)));
            rewards.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE,1));
            rewards.add(new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE,1));
        }
    }

    private void addRareModdedRewards(Player player, DungeonChest chest, List<ItemStack> rewards, Random random) {
        if (!chest.finalReward() || !plugin.getConfig().getBoolean("dungeon-loot.rare-modded-rewards.enabled", true)) return;
        String claimKey = player.getUniqueId() + ":" + chest.dimension;
        int max = Math.max(0, plugin.getConfig().getInt("dungeon-loot.rare-modded-rewards.maximum-per-player", 1));
        if (rareRewardClaims.getOrDefault(claimKey, 0) >= max) return;
        List<Map<?, ?>> entries = plugin.getConfig().getMapList("dungeon-loot.rare-modded-rewards.pools."
                + chest.dimension.name().toLowerCase(Locale.ROOT));
        for (Map<?, ?> entry : entries) {
            Object idValue = entry.get("id");
            String id = idValue == null ? "" : String.valueOf(idValue).trim();
            if (id.isBlank()) continue;
            double chance = number(entry.get("chance"), 0.0D);
            if (random.nextDouble() * 100.0D >= chance) continue;
            int min = Math.max(1, (int) number(entry.get("min"), 1));
            int maxAmount = Math.max(min, (int) number(entry.get("max"), min));
            int amount = min + random.nextInt(maxAmount - min + 1);
            Material material = MaterialResolver.resolve(id);
            if (material != null) addAmount(rewards, material, amount);
            else plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "give " + player.getName() + " " + id + " " + amount);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "[Bingo] Recompensa rara encontrada: " + id);
            }, 2L);
            rareRewardClaims.put(claimKey, rareRewardClaims.getOrDefault(claimKey, 0) + 1);
            break;
        }
    }

    private double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    /** Cofres épicos/legendarios pueden completar un objetivo permitido del cartón. */
    private void maybeGrantDirectGoal(Player player, DungeonChest chest, Random random) {
        if (!plugin.getConfig().getBoolean("dungeon-loot.goal-rewards.enabled", true)
                || !chest.tier.atLeast(ChestTier.EPIC)) return;
        String counterKey = player.getUniqueId() + ":" + chest.dimension;
        int maximum = Math.max(0, plugin.getConfig().getInt("dungeon-loot.goal-rewards.maximum-per-player-per-dimension", 1));
        if (directGoalClaims.getOrDefault(counterKey, 0) >= maximum) return;
        double chance = plugin.getConfig().getDouble("dungeon-loot.goal-rewards.chance."
                + chest.tier.name().toLowerCase(Locale.ROOT), chest.tier == ChestTier.LEGENDARY ? 35.0D : 15.0D);
        if (random.nextDouble() * 100.0D >= chance) return;

        BingoTeam team = game.getTeamOf(player);
        BingoCard card = team == null ? null : team.getCard();
        if (card == null) return;
        List<BingoGoal> advancementGoals = card.getGoals().stream()
                .filter(g -> !g.isCompleted() && g.getType() == GoalType.ADVANCEMENT)
                .filter(g -> classify(g.getTarget()) == chest.dimension)
                .filter(g -> advancementAllowed(g.getTarget())).toList();
        if (!advancementGoals.isEmpty()) {
            BingoGoal goal = advancementGoals.get(random.nextInt(advancementGoals.size()));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "advancement grant " + player.getName()
                    + " only " + goal.getTarget());
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!goal.isCompleted()) {
                    goal.forceComplete();
                    game.onGoalCompleted(team, goal);
                }
            }, 2L);
            directGoalClaims.put(counterKey, directGoalClaims.getOrDefault(counterKey, 0) + 1);
            player.sendMessage(ChatColor.GOLD + "[Bingo] El cofre legendario te concedió un logro del cartón: "
                    + ChatColor.YELLOW + goal.getDisplayName());
            return;
        }

        List<BingoGoal> itemGoals = card.getGoals().stream()
                .filter(g -> !g.isCompleted() && g.getType() == GoalType.ITEM_COLLECT)
                .filter(g -> classify(g.getTarget()) == chest.dimension)
                .filter(g -> MaterialResolver.resolve(g.getTarget()) != null).toList();
        if (!itemGoals.isEmpty()) {
            BingoGoal goal = itemGoals.get(random.nextInt(itemGoals.size()));
            Material material = MaterialResolver.resolve(goal.getTarget());
            int missing = Math.max(1, goal.getAmountRequired() - goal.getProgress());
            List<ItemStack> completion = new ArrayList<>();
            addAmount(completion, material, missing);
            for (ItemStack stack : completion) player.getInventory().addItem(stack);
            directGoalClaims.put(counterKey, directGoalClaims.getOrDefault(counterKey, 0) + 1);
            player.sendMessage(ChatColor.GOLD + "[Bingo] Encontraste los objetos necesarios para completar: "
                    + ChatColor.YELLOW + goal.getDisplayName());
        }
    }

    private boolean advancementAllowed(String target) {
        String value = target.toLowerCase(Locale.ROOT);
        List<String> denied = plugin.getConfig().getStringList("dungeon-loot.goal-rewards.denied-advancements");
        for (String raw : denied) if (matchesPattern(value, raw)) return false;
        List<String> allow = plugin.getConfig().getStringList("dungeon-loot.goal-rewards.allowed-advancements");
        if (allow.isEmpty()) return false;
        for (String raw : allow) if (matchesPattern(value, raw)) return true;
        return false;
    }

    private boolean matchesPattern(String value, String raw) {
        String pattern = raw.toLowerCase(Locale.ROOT).trim();
        if (pattern.equals("*")) return true;
        if (pattern.endsWith("*")) return value.startsWith(pattern.substring(0, pattern.length() - 1));
        return value.equals(pattern);
    }

    private void addAmount(List<ItemStack> target, Material material, int amount) {
        int remaining = Math.max(1, amount);
        while (remaining > 0) {
            int part = Math.min(remaining, material.getMaxStackSize());
            target.add(new ItemStack(material, part));
            remaining -= part;
        }
    }

    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    /** Clasificación conservadora. Los objetos desconocidos se consideran Overworld. */
    public DimensionGroup classify(String target) {
        if (target == null) return DimensionGroup.OVERWORLD;
        String key = target.toLowerCase(Locale.ROOT);
        List<String> end = plugin.getConfig().getStringList("dungeon-loot.dimension-keywords.end");
        if (end.isEmpty()) end = List.of("end_", "chorus", "shulker", "elytra", "dragon", "purpur", "the_end/");
        for (String token : end) if (key.contains(token.toLowerCase(Locale.ROOT))) return DimensionGroup.END;
        List<String> nether = plugin.getConfig().getStringList("dungeon-loot.dimension-keywords.nether");
        if (nether.isEmpty()) nether = List.of("nether", "blaze", "ghast", "magma", "piglin",
                "wither", "quartz", "soul_", "crimson", "warped", "basalt", "blackstone");
        for (String token : nether) if (key.contains(token.toLowerCase(Locale.ROOT))) return DimensionGroup.NETHER;
        return DimensionGroup.OVERWORLD;
    }
}
