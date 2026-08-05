package com.arlight.bingo.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;

/** Puente Bukkit/Arclight para los objetos y bloques de misión de ArlightBosses. */
public final class CampaignItemBridge {
    public static final String IGNEOUS_KEY = "arlightbosses:igneous_legendary_key";
    public static final String DRAGON_KEY = "arlightbosses:emeraldized_dragon_key";
    public static final String CORRUPTED_ALTAR = "arlightbosses:corrupted_pearl_altar";
    public static final String NETHER_DUNGEON_LOCK = "arlightbosses:nether_dungeon_lock";
    public static final String COPPER_TREASURE_CHEST = "arlightbosses:copper_treasure_chest";
    public static final String IRON_TREASURE_CHEST = "arlightbosses:iron_treasure_chest";
    public static final String PONY_TREASURE_CHEST = "arlightbosses:pony_treasure_chest";
    public static final String DIAMOND_TREASURE_CHEST = "arlightbosses:diamond_treasure_chest";
    public static final String NETHERITE_TREASURE_CHEST = "arlightbosses:netherite_treasure_chest";
    public static final String MOSSBOUND_HOME_MEDAL = "arlightbosses:mossbound_home_medal";
    public static final String GILDED_TRADE_MEDAL = "arlightbosses:gilded_trade_medal";
    public static final String EMERALD_BASTION_MEDAL = "arlightbosses:emerald_bastion_medal";
    public static final String HOME_MEDAL_PEDESTAL = "arlightbosses:home_medal_pedestal";
    public static final String TRADE_MEDAL_PEDESTAL = "arlightbosses:trade_medal_pedestal";
    public static final String BASTION_MEDAL_PEDESTAL = "arlightbosses:bastion_medal_pedestal";

    private static final String IGNEOUS_TAG = "arlightbingo_has_igneous_key";
    private static final String DRAGON_TAG = "arlightbingo_has_dragon_key";
    private static final String HOME_MEDAL_TAG = "arlightbingo_has_home_medal";
    private static final String TRADE_MEDAL_TAG = "arlightbingo_has_trade_medal";
    private static final String BASTION_MEDAL_TAG = "arlightbingo_has_bastion_medal";

    private CampaignItemBridge() { }

    public static boolean matches(ItemStack stack, String id) {
        if (stack == null || stack.getType().isAir() || id == null) return false;
        try {
            return id.equalsIgnoreCase(stack.getType().getKey().toString());
        } catch (Throwable ignored) {
            Material resolved = MaterialResolver.resolve(id);
            return resolved != null && stack.getType() == resolved;
        }
    }

    public static boolean has(Player player, String id) {
        if (player == null) return false;
        String tag = ownershipTag(id);
        if (tag != null && player.getScoreboardTags().contains(tag)) return true;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (matches(stack, id)) return true;
        }
        return matches(player.getInventory().getItemInOffHand(), id);
    }

    public static boolean consume(Player player, String id) {
        if (player == null) return false;
        boolean ownedByTag = false;
        String tag = ownershipTag(id);
        if (tag != null && player.getScoreboardTags().contains(tag)) {
            player.removeScoreboardTag(tag);
            ownedByTag = true;
        }

        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (!matches(stack, id)) continue;
            if (stack.getAmount() <= 1) player.getInventory().setItem(slot, null);
            else stack.setAmount(stack.getAmount() - 1);
            player.updateInventory();
            return true;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (matches(offhand, id)) {
            if (offhand.getAmount() <= 1) player.getInventory().setItemInOffHand(null);
            else offhand.setAmount(offhand.getAmount() - 1);
            player.updateInventory();
            return true;
        }

        // Cuando Bukkit no expone el Material modded, la etiqueta conserva el estado
        // y /clear elimina el objeto NeoForge real del inventario.
        if (ownedByTag) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "clear " + player.getName() + " " + id + " 1");
            return true;
        }
        return false;
    }

    public static void give(Player player, String id) {
        if (player == null || id == null) return;
        String tag = ownershipTag(id);
        if (tag != null) player.addScoreboardTag(tag);
        Material resolved = MaterialResolver.resolve(id);
        if (resolved != null) {
            player.getInventory().addItem(new ItemStack(resolved, 1));
            player.updateInventory();
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "give " + player.getName() + " " + id + " 1");
    }

    public static void removeOwnership(Player player, String id) {
        if (player == null) return;
        String tag = ownershipTag(id);
        if (tag != null) player.removeScoreboardTag(tag);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "clear " + player.getName() + " " + id + " 1");
    }

    /**
     * Inserta una llave en cualquier contenedor de misión (cofre o barril). Si
     * Bukkit no expone el Material modded, usa /item, que sigue funcionando con
     * los registros NeoForge en Arclight.
     */
    public static boolean putInChest(Location chestLocation, String id, int preferredSlot) {
        if (chestLocation == null || chestLocation.getWorld() == null) return false;
        if (!(chestLocation.getBlock().getState() instanceof Container container)) return false;
        Material resolved = MaterialResolver.resolve(id);
        if (resolved != null) {
            int slot = Math.max(0, Math.min(container.getInventory().getSize() - 1, preferredSlot));
            container.getInventory().setItem(slot, new ItemStack(resolved, 1));
            container.update(true, false);
            return true;
        }
        World world = chestLocation.getWorld();
        int slot = Math.max(0, Math.min(26, preferredSlot));
        String command = String.format(Locale.ROOT,
                "execute in %s run item replace block %d %d %d container.%d with %s 1",
                world.getKey(), chestLocation.getBlockX(), chestLocation.getBlockY(),
                chestLocation.getBlockZ(), slot, id);
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    public static void clearChestSlot(Location chestLocation, int preferredSlot) {
        if (chestLocation == null || chestLocation.getWorld() == null) return;
        int slot = Math.max(0, Math.min(26, preferredSlot));
        if (chestLocation.getBlock().getState() instanceof Container container) {
            if (slot < container.getInventory().getSize()) container.getInventory().setItem(slot, null);
            container.update(true, false);
        }
        String command = String.format(Locale.ROOT,
                "execute in %s run item replace block %d %d %d container.%d with air",
                chestLocation.getWorld().getKey(), chestLocation.getBlockX(), chestLocation.getBlockY(),
                chestLocation.getBlockZ(), slot);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    public static boolean placeModBlock(Location location, String id) {
        if (location == null || location.getWorld() == null) return false;
        if (ArclightDirectBridge.setBlockState(location, id)) return true;
        Material resolved = MaterialResolver.resolve(id);
        if (resolved != null) {
            location.getBlock().setType(resolved, false);
            return true;
        }
        String command = String.format(Locale.ROOT,
                "execute in %s run setblock %d %d %d %s",
                location.getWorld().getKey(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), id);
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    /**
     * Actualiza propiedades de un bloque modded sin ejecutar comandos. Se usa para
     * animaciones repetidas (cofres, cerraduras y altares) para no llenar el chat
     * o la consola con mensajes de /setblock. Si el puente directo no está
     * disponible, conserva el estado visual actual en lugar de usar comandos.
     */
    public static boolean updateModBlockStateSilently(Location location, String blockState) {
        if (location == null || location.getWorld() == null || blockState == null || blockState.isBlank()) return false;
        return ArclightDirectBridge.setBlockState(location, blockState);
    }

    /** Coloca un bloque modded incluyendo propiedades, por ejemplo [facing=north,lock_state=opening]. */
    public static boolean setModBlockState(Location location, String blockState) {
        if (location == null || location.getWorld() == null || blockState == null || blockState.isBlank()) return false;
        if (ArclightDirectBridge.setBlockState(location, blockState)) return true;
        String command = String.format(Locale.ROOT,
                "execute in %s run setblock %d %d %d %s",
                location.getWorld().getKey(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), blockState);
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }


    /** Actualiza propiedades del bloque modded existente conservando su BlockEntity. */
    public static boolean updateExistingModBlockProperties(Location location, Map<String, String> properties) {
        return ArclightDirectBridge.updateExistingBlockProperties(location, properties);
    }

    /**
     * Reproduce la animación nativa de un cofre de ArlightBosses sin ejecutar
     * comandos ni sustituir el bloque. El primer camino llama a la BlockEntity de
     * ArlightBosses 1.19.0; el respaldo cambia sólo la propiedad visual existente.
     */
    public static boolean animateTreasureChest(Location location, boolean opening) {
        String method = opening ? "playExternalOpen" : "playExternalClose";
        if (ArclightDirectBridge.invokeBlockEntityMethod(location, method)) return true;
        return ArclightDirectBridge.updateExistingBlockProperties(location,
                Map.of("chest_state", opening ? "opening" : "closing"));
    }

    /** Dispara la animación cinemática del altar sin usar comandos ni reemplazar el bloque. */
    public static boolean animateCorruptedAltar(Location location) {
        return ArclightDirectBridge.invokeBlockEntityMethod(location, "playExternalActivate");
    }

    public static boolean isBlock(Location location, String id) {
        if (location == null || location.getWorld() == null) return false;
        try {
            return id.equalsIgnoreCase(location.getBlock().getType().getKey().toString());
        } catch (Throwable ignored) {
            Material resolved = MaterialResolver.resolve(id);
            return resolved != null && location.getBlock().getType() == resolved;
        }
    }

    private static String ownershipTag(String id) {
        if (IGNEOUS_KEY.equalsIgnoreCase(id)) return IGNEOUS_TAG;
        if (DRAGON_KEY.equalsIgnoreCase(id)) return DRAGON_TAG;
        if (MOSSBOUND_HOME_MEDAL.equalsIgnoreCase(id)) return HOME_MEDAL_TAG;
        if (GILDED_TRADE_MEDAL.equalsIgnoreCase(id)) return TRADE_MEDAL_TAG;
        if (EMERALD_BASTION_MEDAL.equalsIgnoreCase(id)) return BASTION_MEDAL_TAG;
        return null;
    }
}
