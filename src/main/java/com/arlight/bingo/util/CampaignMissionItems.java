package com.arlight.bingo.util;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/** Objetos semánticos de la campaña con implementación modded y fallback vanilla. */
public final class CampaignMissionItems {
    public static final String ROUTE_COMPASS = "overworld_route_compass";
    public static final String HOME_EMBLEM = "overworld_home_emblem";
    public static final String TRADE_EMBLEM = "overworld_trade_emblem";
    public static final String EMERALD_CORE = "overworld_emerald_core";

    private CampaignMissionItems() { }

    public static ItemStack create(JavaPlugin plugin, String itemId) {
        return create(plugin, itemId, null);
    }

    public static ItemStack create(JavaPlugin plugin, String itemId, Location target) {
        String modId = modItemId(itemId);
        Material modMaterial = modId == null ? null : MaterialResolver.resolve(modId);
        ItemStack stack = modMaterial != null ? new ItemStack(modMaterial) : switch (itemId) {
            case ROUTE_COMPASS -> new ItemStack(Material.COMPASS);
            case HOME_EMBLEM -> new ItemStack(Material.ECHO_SHARD);
            case TRADE_EMBLEM -> new ItemStack(Material.GOLD_INGOT);
            case EMERALD_CORE -> new ItemStack(Material.EMERALD);
            default -> new ItemStack(Material.PAPER);
        };
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, itemId);
        meta.setDisplayName(displayName(itemId));
        meta.setLore(lore(itemId));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);

        if (meta instanceof CompassMeta compass && target != null && target.getWorld() != null) {
            compass.setLodestone(target);
            compass.setLodestoneTracked(false);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public static boolean is(JavaPlugin plugin, ItemStack stack, String itemId) {
        if (stack == null || stack.getType().isAir() || itemId == null) return false;
        String modId = modItemId(itemId);
        if (modId != null && CampaignItemBridge.matches(stack, modId)) return true;
        if (stack.getItemMeta() == null) return false;
        String stored = stack.getItemMeta().getPersistentDataContainer()
                .get(key(plugin), PersistentDataType.STRING);
        return itemId.equals(stored);
    }

    public static boolean has(Player player, JavaPlugin plugin, String itemId) {
        if (player == null) return false;
        String modId = modItemId(itemId);
        if (modId != null && CampaignItemBridge.has(player, modId)) return true;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (is(plugin, stack, itemId)) return true;
        }
        return is(plugin, player.getInventory().getItemInOffHand(), itemId);
    }

    public static boolean consume(Player player, JavaPlugin plugin, String itemId) {
        if (player == null) return false;
        String modId = modItemId(itemId);
        if (modId != null && CampaignItemBridge.has(player, modId)
                && CampaignItemBridge.consume(player, modId)) return true;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (!is(plugin, stack, itemId)) continue;
            if (stack.getAmount() <= 1) player.getInventory().setItem(slot, null);
            else stack.setAmount(stack.getAmount() - 1);
            player.updateInventory();
            return true;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (is(plugin, offhand, itemId)) {
            if (offhand.getAmount() <= 1) player.getInventory().setItemInOffHand(null);
            else offhand.setAmount(offhand.getAmount() - 1);
            player.updateInventory();
            return true;
        }
        return false;
    }

    public static boolean giveIfMissing(Player player, JavaPlugin plugin, String itemId, Location target) {
        if (player == null || has(player, plugin, itemId)) return false;
        ItemStack stack = create(plugin, itemId, target);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
        if (!leftovers.isEmpty()) {
            leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(
                    player.getLocation(), leftover));
        }
        player.updateInventory();
        return true;
    }

    public static String modItemId(String itemId) {
        return switch (itemId) {
            case HOME_EMBLEM -> CampaignItemBridge.MOSSBOUND_HOME_MEDAL;
            case TRADE_EMBLEM -> CampaignItemBridge.GILDED_TRADE_MEDAL;
            case EMERALD_CORE -> CampaignItemBridge.EMERALD_BASTION_MEDAL;
            default -> null;
        };
    }

    public static String displayName(String itemId) {
        return switch (itemId) {
            case ROUTE_COMPASS -> ChatColor.LIGHT_PURPLE + "Brújula de la Antigua Calzada";
            case HOME_EMBLEM -> ChatColor.GREEN + "Medalla del Hogar Musgoso";
            case TRADE_EMBLEM -> ChatColor.GOLD + "Medalla del Comercio Dorado";
            case EMERALD_CORE -> ChatColor.DARK_GREEN + "Medalla del Bastión Esmeralda";
            default -> ChatColor.WHITE + "Objeto de campaña";
        };
    }

    private static List<String> lore(String itemId) {
        return switch (itemId) {
            case ROUTE_COMPASS -> List.of(
                    ChatColor.GRAY + "Señala la capital corrompida.",
                    ChatColor.DARK_GRAY + "Entregada por el Cartógrafo de la Campana.",
                    ChatColor.DARK_PURPLE + "Objeto de misión · ArlightBingo");
            case HOME_EMBLEM -> List.of(
                    ChatColor.GRAY + "Prueba de que los hogares del bosque fueron protegidos.",
                    ChatColor.DARK_GREEN + "Encaja en el pedestal musgoso.",
                    ChatColor.DARK_PURPLE + "Objeto de misión · ArlightBingo");
            case TRADE_EMBLEM -> List.of(
                    ChatColor.GRAY + "Sello recuperado al liberar las rutas comerciales.",
                    ChatColor.GOLD + "Encaja en el pedestal dorado.",
                    ChatColor.DARK_PURPLE + "Objeto de misión · ArlightBingo");
            case EMERALD_CORE -> List.of(
                    ChatColor.GRAY + "Sello del bastión militar corrompido.",
                    ChatColor.DARK_GREEN + "Encaja en el pedestal esmeralda.",
                    ChatColor.DARK_PURPLE + "Objeto de misión · ArlightBingo");
            default -> List.of(ChatColor.DARK_PURPLE + "Objeto de misión · ArlightBingo");
        };
    }

    private static NamespacedKey key(JavaPlugin plugin) {
        return new NamespacedKey(plugin, "mission_item_id");
    }
}
