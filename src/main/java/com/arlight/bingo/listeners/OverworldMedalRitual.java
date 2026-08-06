package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import com.arlight.bingo.util.CampaignItemBridge;
import com.arlight.bingo.util.CampaignMissionItems;
import org.bukkit.ChatColor;
import org.bukkit.Color;
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


    void reset(World world) {
        if (world == null) return;
        State state = load(world);
        for (Slot slot : Slot.values()) {
            Location at = state.location(slot, world);
            if (at != null && CampaignItemBridge.isBlock(at, slot.blockId)) {
                restorePedestal(at, "empty");
            }
        }
        activeUnlocks.remove(world.getUID());
        activeInsertions.removeIf(value -> value.startsWith(world.getUID().toString() + ":"));
        try {
            Files.deleteIfExists(world.getWorldFolder().toPath().resolve(MARKER_FILE));
        } catch (IOException exception) {
            plugin.getLogger().warning("No se pudo reiniciar el ritual de medallas: " + exception.getMessage());
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
        runJointAnimation(world, layout, state);

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
        }, 72L);
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
        CampaignItemBridge.animateMedalPedestal(location, state);
    }

    /** Secuencia conjunta: pulso de los tres pedestales, triángulo de energía y descarga a la reja. */
    private void runJointAnimation(World world, Layout layout, State state) {
        List<Location> medals = new ArrayList<>();
        for (Slot slot : Slot.values()) {
            Location at = state.location(slot, world);
            if (at != null) medals.add(at.clone().add(0.5D, 1.85D, 0.5D));
        }
        if (medals.size() != 3) return;
        Location center = medals.get(0).clone().add(medals.get(1)).add(medals.get(2)).multiply(1.0D / 3.0D);
        Location gate = layout.ritualGate().clone().add(0.5D, 3.6D, 0.5D);
        Particle.DustOptions home = new Particle.DustOptions(Color.fromRGB(112, 214, 92), 1.25F);
        Particle.DustOptions trade = new Particle.DustOptions(Color.fromRGB(255, 190, 55), 1.25F);
        Particle.DustOptions bastion = new Particle.DustOptions(Color.fromRGB(42, 235, 157), 1.25F);
        Particle.DustOptions[] colors = new Particle.DustOptions[]{home, trade, bastion};

        for (int tick = 0; tick <= 64; tick += 2) {
            final int frame = tick;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!activeUnlocks.contains(world.getUID())) return;
                if (frame < 20) {
                    for (int i = 0; i < medals.size(); i++) {
                        Location at = medals.get(i);
                        double radius = 0.45D + frame * 0.025D;
                        for (int angle = 0; angle < 360; angle += 45) {
                            double radians = Math.toRadians(angle + frame * 8.0D);
                            world.spawnParticle(Particle.DUST, at.clone().add(
                                    Math.cos(radians) * radius, 0.08D * Math.sin(radians * 2.0D),
                                    Math.sin(radians) * radius), 1, 0, 0, 0, 0, colors[i]);
                        }
                    }
                    if (frame == 0 || frame == 10) {
                        world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 0.9F + frame * 0.02F);
                    }
                } else if (frame < 44) {
                    for (int i = 0; i < 3; i++) {
                        spawnBeam(world, medals.get(i), medals.get((i + 1) % 3), colors[i]);
                        spawnBeam(world, medals.get(i), center, colors[i]);
                    }
                    world.spawnParticle(Particle.ENCHANT, center, 8, 0.35D, 0.35D, 0.35D, 0.02D);
                    if (frame == 20) world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.2F, 1.15F);
                } else {
                    for (int i = 0; i < 3; i++) spawnBeam(world, medals.get(i), center, colors[i]);
                    spawnBeam(world, center, gate, bastion);
                    world.spawnParticle(Particle.END_ROD, gate, 12, 0.65D, 0.75D, 0.25D, 0.03D);
                    if (frame == 44) world.playSound(gate, Sound.BLOCK_CONDUIT_ACTIVATE, 1.3F, 0.8F);
                }
            }, tick);
        }
    }

    private void spawnBeam(World world, Location from, Location to, Particle.DustOptions dust) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(4, Math.min(42, (int) Math.ceil(length * 2.0D)));
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            world.spawnParticle(Particle.DUST,
                    from.getX() + dx * t, from.getY() + dy * t, from.getZ() + dz * t,
                    1, 0, 0, 0, 0, dust);
        }
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
        properties.setProperty("version", "1.48.44");
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
