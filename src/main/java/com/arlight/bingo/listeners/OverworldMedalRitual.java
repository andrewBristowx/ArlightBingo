package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import com.arlight.bingo.util.CampaignItemBridge;
import com.arlight.bingo.util.CampaignMissionItems;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.Layout;

/** Ritual persistente de tres medallas, separado de la geometría manual del mapa. */
final class OverworldMedalRitual {
    static final String MARKER_FILE = "arlight-overworld-medal-ritual.properties";

    interface UnlockHandler {
        void unlock(World world, Layout layout, Player activator);
    }

    private enum Slot {
        HOME("home", CampaignItemBridge.HOME_MEDAL_PEDESTAL,
                CampaignMissionItems.HOME_EMBLEM, "Medalla del Hogar Musgoso"),
        TRADE("trade", CampaignItemBridge.TRADE_MEDAL_PEDESTAL,
                CampaignMissionItems.TRADE_EMBLEM, "Medalla del Comercio Dorado"),
        BASTION("bastion", CampaignItemBridge.BASTION_MEDAL_PEDESTAL,
                CampaignMissionItems.EMERALD_CORE, "Medalla del Bastión Esmeralda");

        final String key;
        final String blockId;
        final String missionItem;
        final String display;

        Slot(String key, String blockId, String missionItem, String display) {
            this.key = key;
            this.blockId = blockId;
            this.missionItem = missionItem;
            this.display = display;
        }
    }

    private final BingoPlugin plugin;
    private final UnlockHandler unlockHandler;
    private final Set<String> activeInsertions = new HashSet<>();
    private final Set<UUID> activeUnlocks = new HashSet<>();

    OverworldMedalRitual(BingoPlugin plugin, UnlockHandler unlockHandler) {
        this.plugin = plugin;
        this.unlockHandler = unlockHandler;
    }

    boolean handle(PlayerInteractEvent event, Layout layout) {
        if (event == null || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) return false;
        Location clicked = event.getClickedBlock().getLocation();
        Slot slot = slotAt(clicked);
        if (slot == null) return false;
        event.setCancelled(true);
        Player player = event.getPlayer();
        World world = clicked.getWorld();
        State state = load(world);

        if (state.filled(slot)) {
            player.sendMessage(ChatColor.GREEN + slot.display + " ya está colocada.");
            player.playSound(clicked, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7F, 1.25F);
            restorePedestal(clicked, state.unlocked() ? "unlocked" : "filled");
            return true;
        }

        if (!CampaignMissionItems.has(player, plugin, slot.missionItem)) {
            player.sendTitle(ChatColor.DARK_GREEN + "Pedestal vacío",
                    ChatColor.GRAY + "Necesitas: " + slot.display, 5, 45, 10);
            player.sendMessage(ChatColor.YELLOW + "Consigue la medalla correspondiente y úsala sobre este pedestal.");
            player.playSound(clicked, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8F, 0.8F);
            return true;
        }

        String insertionKey = insertionKey(world, clicked);
        if (!activeInsertions.add(insertionKey)) return true;
        if (!CampaignMissionItems.consume(player, plugin, slot.missionItem)) {
            activeInsertions.remove(insertionKey);
            player.sendMessage(ChatColor.RED + "No se pudo consumir la medalla. Inténtalo nuevamente.");
            return true;
        }

        state.set(slot, clicked, true);
        save(world, state);
        restorePedestal(clicked, "inserting");
        world.playSound(clicked, Sound.BLOCK_BEACON_POWER_SELECT, 1.0F, 1.2F);
        world.spawnParticle(Particle.ENCHANT, clicked.clone().add(0.5D, 1.3D, 0.5D),
                45, 0.45D, 0.7D, 0.45D, 0.05D);
        player.sendMessage(ChatColor.GREEN + slot.display + " colocada correctamente.");

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            activeInsertions.remove(insertionKey);
            restorePedestal(clicked, "filled");
            State current = load(world);
            if (current.complete()) beginUnlock(world, layout, player, current);
        }, 26L);
        return true;
    }

    void maintain(World world, Layout layout, boolean gateOpen) {
        if (world == null || layout == null) return;
        State state = load(world);
        if (activeUnlocks.contains(world.getUID())) return;
        for (Slot slot : Slot.values()) {
            Location location = state.location(slot, world);
            if (location == null || !CampaignItemBridge.isBlock(location, slot.blockId)
                    || activeInsertions.contains(insertionKey(world, location))) continue;
            String target = gateOpen || state.unlocked() ? "unlocked" : state.filled(slot) ? "filled" : "empty";
            restorePedestal(location, target);
        }
        if (gateOpen && !state.unlocked()) {
            state.unlocked = true;
            save(world, state);
        } else if (!gateOpen && state.complete() && !state.unlocked()) {
            beginUnlock(world, layout, null, state);
        }
    }

    void onWorldUnload(World world) {
        if (world == null) return;
        activeUnlocks.remove(world.getUID());
        activeInsertions.removeIf(value -> value.startsWith(world.getUID().toString() + ":"));
    }

    List<String> missing(World world) {
        State state = load(world);
        List<String> missing = new ArrayList<>();
        for (Slot slot : Slot.values()) if (!state.filled(slot)) missing.add(slot.display);
        return missing;
    }

    private void beginUnlock(World world, Layout layout, Player player, State state) {
        if (world == null || layout == null || !state.complete() || !activeUnlocks.add(world.getUID())) return;
        for (Slot slot : Slot.values()) {
            Location at = state.location(slot, world);
            if (at != null) restorePedestal(at, "unlocking");
        }
        world.playSound(layout.ritualGate(), Sound.BLOCK_END_PORTAL_SPAWN, 1.25F, 0.85F);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING,
                layout.ritualGate().clone().add(0.5D, 4.0D, 0.5D), 100,
                4.5D, 3.0D, 1.5D, 0.05D);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                State latest = load(world);
                latest.unlocked = true;
                save(world, latest);
                for (Slot slot : Slot.values()) {
                    Location at = latest.location(slot, world);
                    if (at != null) restorePedestal(at, "unlocked");
                }
                unlockHandler.unlock(world, layout, player);
            } finally {
                activeUnlocks.remove(world.getUID());
            }
        }, 48L);
    }

    private Slot slotAt(Location clicked) {
        for (Slot slot : Slot.values()) {
            if (CampaignItemBridge.isBlock(clicked, slot.blockId)) return slot;
        }
        return null;
    }

    private void restorePedestal(Location location, String state) {
        if (location == null || location.getWorld() == null) return;
        try {
            String current = location.getBlock().getBlockData().getAsString();
            if (current.contains("pedestal_state=" + state)) return;
        } catch (Throwable ignored) { }
        CampaignItemBridge.updateExistingModBlockProperties(location,
                Map.of("pedestal_state", state));
    }

    private String insertionKey(World world, Location location) {
        return world.getUID() + ":" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ();
    }

    private State load(World world) {
        State state = new State();
        if (world == null) return state;
        Path file = world.getWorldFolder().toPath().resolve(MARKER_FILE);
        if (!Files.isRegularFile(file)) return state;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            state.unlocked = Boolean.parseBoolean(properties.getProperty("unlocked", "false"));
            for (Slot slot : Slot.values()) {
                state.filled.put(slot, Boolean.parseBoolean(properties.getProperty(slot.key + ".filled", "false")));
                String raw = properties.getProperty(slot.key + ".location");
                if (raw != null && !raw.isBlank()) state.locations.put(slot, raw);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("No se pudo leer el ritual de medallas: " + exception.getMessage());
        }
        return state;
    }

    private void save(World world, State state) {
        if (world == null || state == null) return;
        Properties properties = new Properties();
        properties.setProperty("version", "1.48.42");
        properties.setProperty("unlocked", Boolean.toString(state.unlocked));
        for (Slot slot : Slot.values()) {
            properties.setProperty(slot.key + ".filled", Boolean.toString(state.filled(slot)));
            String location = state.locations.get(slot);
            if (location != null) properties.setProperty(slot.key + ".location", location);
        }
        Path file = world.getWorldFolder().toPath().resolve(MARKER_FILE);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(writer, "ArlightBingo Overworld medal ritual");
        } catch (IOException exception) {
            plugin.getLogger().warning("No se pudo guardar el ritual de medallas: " + exception.getMessage());
        }
    }

    private static final class State {
        private final EnumMap<Slot, Boolean> filled = new EnumMap<>(Slot.class);
        private final EnumMap<Slot, String> locations = new EnumMap<>(Slot.class);
        private boolean unlocked;

        boolean filled(Slot slot) { return Boolean.TRUE.equals(filled.get(slot)); }
        boolean complete() {
            for (Slot slot : Slot.values()) if (!filled(slot) || !locations.containsKey(slot)) return false;
            return true;
        }
        boolean unlocked() { return unlocked; }
        void set(Slot slot, Location location, boolean value) {
            filled.put(slot, value);
            locations.put(slot, location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
        }
        Location location(Slot slot, World world) {
            String raw = locations.get(slot);
            if (raw == null || world == null) return null;
            String[] parts = raw.split(",");
            if (parts.length != 3) return null;
            try {
                return new Location(world, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
