package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import com.arlight.bingo.util.CampaignMissionItems;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.HeightMap;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.arlight.bingo.listeners.OverworldCampaignModel145.*;

/** Coordina la reconstrucción 1.45.0 y el altar de invocación en las copias de partida. */
public final class OverworldCampaignLandscape145 {
    static final String TEMPLATE_MARKER = "arlight-overworld-template.properties";
    static final String PENDING_TEMPLATE_MARKER = "arlight-overworld-template-1.45.pending";
    static final String LAYOUT_MARKER = "arlight-overworld-campaign-1.45.properties";
    static final String PROGRESS_MARKER = "arlight-overworld-campaign-1.45.in-progress";
    static final String DONE_MARKER = "arlight-overworld-campaign-1.45.done";
    static final String REPORT_FILE = "arlight-overworld-campaign-1.45-report.txt";
    private static final String RITUAL_OPEN_MARKER = "arlight-overworld-ritual-open.properties";

    private final BingoPlugin plugin;
    private final long startupEpochMillis = System.currentTimeMillis();
    private final Set<UUID> activeBuilds = new HashSet<>();
    private final Set<UUID> initializedSeals = new HashSet<>();
    private final Set<UUID> dormantBossNotices = new HashSet<>();
    private final Map<UUID, Layout> layouts = new HashMap<>();

    public OverworldCampaignLandscape145(BingoPlugin plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        for (World world : Bukkit.getWorlds()) {
            startIfEligible(world);
            maintainSeal(world);
            maintainRitualBoss(world);
        }
    }

    public void onWorldUnload(World world) {
        if (world == null) return;
        activeBuilds.remove(world.getUID());
        initializedSeals.remove(world.getUID());
        dormantBossNotices.remove(world.getUID());
        layouts.remove(world.getUID());
    }

    public void handleInteraction(PlayerInteractEvent event) {
        if (event == null || event.getClickedBlock() == null
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        World world = event.getClickedBlock().getWorld();
        Layout layout = loadLayout(world);
        if (layout == null || !near(event.getClickedBlock().getLocation(), layout.altar(), 1.75D)) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        Path openMarker = world.getWorldFolder().toPath().resolve(RITUAL_OPEN_MARKER);
        if (Files.isRegularFile(openMarker)) {
            player.sendMessage(ChatColor.GREEN + "El ritual ya fue completado y la arena está abierta.");
            return;
        }

        List<String> missing = missingItems(world);
        if (!missing.isEmpty()) {
            player.sendTitle(ChatColor.DARK_GREEN + "Ritual incompleto",
                    ChatColor.GRAY + "Faltan " + String.join(", ", missing), 5, 50, 10);
            player.sendMessage(ChatColor.YELLOW + "Libera los tres asentamientos y recupera sus piezas.");
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8F, 0.75F);
            return;
        }

        consume(world, CampaignMissionItems.HOME_EMBLEM);
        consume(world, CampaignMissionItems.TRADE_EMBLEM);
        consume(world, CampaignMissionItems.EMERALD_CORE);
        openSeal(world, layout);
        awakenRitualBoss(world, layout);
        try {
            Files.writeString(openMarker,
                    "version=1.45.0\nopenedBy=" + player.getUniqueId() + "\nopened="
                            + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            plugin.getLogger().warning("El sello se abrió, pero no se guardó el ritual: "
                    + exception.getMessage());
        }

        for (Player online : world.getPlayers()) {
            online.sendTitle(ChatColor.DARK_GREEN + "Ritual completado",
                    ChatColor.GOLD + "La arena del Guardián está abierta", 10, 70, 20);
            online.sendMessage(ChatColor.GREEN + "[Bingo] Las tres piezas reaccionaron con el altar. "
                    + ChatColor.WHITE + "El sello interior se ha roto.");
            online.playSound(online.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.75F, 1.35F);
            online.spawnParticle(Particle.TOTEM_OF_UNDYING,
                    online.getLocation().add(0, 1.2D, 0), 45, 0.9D, 0.8D, 0.9D, 0.04D);
        }
    }

    private void startIfEligible(World world) {
        if (!plugin.getConfig().getBoolean(
                "template-worlds.overworld.campaign-layout-1-45.enabled", true)) return;
        String template = plugin.getConfig().getString(
                "template-worlds.overworld.name", "bingo_template_overworld");
        if (!world.getName().equals(template) || activeBuilds.contains(world.getUID())) return;

        Path folder = world.getWorldFolder().toPath();
        Path baseMarker = folder.resolve(TEMPLATE_MARKER);
        Path pendingMarker = folder.resolve(PENDING_TEMPLATE_MARKER);
        Path progress = folder.resolve(PROGRESS_MARKER);
        Path done = folder.resolve(DONE_MARKER);
        if ((!Files.isRegularFile(baseMarker) && !Files.isRegularFile(pendingMarker))
                || Files.isRegularFile(done)) return;

        try {
            boolean interrupted = Files.isRegularFile(progress)
                    && (Files.isRegularFile(pendingMarker) || Files.isRegularFile(baseMarker));
            Path sourceMarker = Files.isRegularFile(pendingMarker) ? pendingMarker : baseMarker;
            boolean completedThisRun = Files.isRegularFile(baseMarker)
                    && Files.getLastModifiedTime(baseMarker).toMillis() >= startupEpochMillis - 5_000L;
            if (!interrupted && !completedThisRun) return;

            Properties base = read(sourceMarker);
            Location village = parse(world, base.getProperty("village"));
            Location citadel = parse(world, base.getProperty("dungeon"));
            Location boss = parse(world, base.getProperty("boss"));
            Location portal = parse(world, base.getProperty("portal"));
            if (village == null || citadel == null || boss == null || portal == null) {
                plugin.getLogger().warning("No se inició 1.45.0: faltan anclas en " + sourceMarker);
                return;
            }

            Files.writeString(progress,
                    "version=1.45.0\nstarted=" + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            if (!Files.isRegularFile(pendingMarker)) {
                // Oculta temporalmente el marker READY: ninguna partida puede copiar una
                // plantilla a medio rediseñar. Si el servidor cae en esta transición, el
                // marker de progreso permite completar el movimiento al volver a iniciar.
                Files.move(baseMarker, pendingMarker,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            activeBuilds.add(world.getUID());
            new OverworldCampaignBuilder145(plugin, world, folder, village, citadel, boss, portal,
                    layout -> {
                        activeBuilds.remove(world.getUID());
                        layouts.put(world.getUID(), layout);
                        initializedSeals.add(world.getUID());
                        closeSeal(world, layout);
                    }, error -> {
                        activeBuilds.remove(world.getUID());
                        plugin.getLogger().severe("Falló el diseño Overworld 1.45.0: " + root(error));
                    }).start();
        } catch (IOException exception) {
            plugin.getLogger().warning("No se pudo iniciar el diseño 1.45.0: " + exception.getMessage());
        }
    }

    private void maintainSeal(World world) {
        Layout layout = loadLayout(world);
        if (layout == null || activeBuilds.contains(world.getUID())
                || !initializedSeals.add(world.getUID())) return;
        if (Files.isRegularFile(world.getWorldFolder().toPath().resolve(RITUAL_OPEN_MARKER))) openSeal(world, layout);
        else closeSeal(world, layout);
    }

    private Layout loadLayout(World world) {
        Layout known = layouts.get(world.getUID());
        if (known != null) return known;
        Path file = world.getWorldFolder().toPath().resolve(LAYOUT_MARKER);
        if (!Files.isRegularFile(file)) return null;
        try {
            Properties p = read(file);
            Layout layout = new Layout(required(world, p, "village"),
                    required(world, p, "residential"), required(world, p, "commercial"),
                    required(world, p, "military"), required(world, p, "citadel"),
                    required(world, p, "boss"), required(world, p, "portal"),
                    required(world, p, "altar"), required(world, p, "ritualGate"));
            layouts.put(world.getUID(), layout);
            return layout;
        } catch (IOException | IllegalArgumentException exception) {
            plugin.getLogger().warning("No se pudo leer el diseño 1.45.0 en "
                    + world.getName() + ": " + exception.getMessage());
            return null;
        }
    }

    private List<String> missingItems(World world) {
        List<String> missing = new ArrayList<>();
        if (!has(world, CampaignMissionItems.HOME_EMBLEM)) missing.add("Emblema del Hogar");
        if (!has(world, CampaignMissionItems.TRADE_EMBLEM)) missing.add("Emblema del Comercio");
        if (!has(world, CampaignMissionItems.EMERALD_CORE)) missing.add("Núcleo Esmeralda");
        return missing;
    }

    private boolean has(World world, String id) {
        for (Player player : world.getPlayers()) if (CampaignMissionItems.has(player, plugin, id)) return true;
        return false;
    }

    private void consume(World world, String id) {
        for (Player player : world.getPlayers()) {
            ItemStack[] contents = player.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (!CampaignMissionItems.is(plugin, stack, id)) continue;
                if (stack.getAmount() <= 1) player.getInventory().setItem(slot, null);
                else stack.setAmount(stack.getAmount() - 1);
                player.updateInventory();
                return;
            }
            ItemStack offhand = player.getInventory().getItemInOffHand();
            if (CampaignMissionItems.is(plugin, offhand, id)) {
                if (offhand.getAmount() <= 1) player.getInventory().setItemInOffHand(null);
                else offhand.setAmount(offhand.getAmount() - 1);
                player.updateInventory();
                return;
            }
        }
    }

    private void maintainRitualBoss(World world) {
        Layout layout = loadLayout(world);
        if (layout == null) return;
        boolean ritualOpen = Files.isRegularFile(
                world.getWorldFolder().toPath().resolve(RITUAL_OPEN_MARKER));
        boolean found = false;
        for (Entity entity : world.getNearbyEntities(layout.boss(), 72.0D, 40.0D, 72.0D)) {
            if (!entity.getScoreboardTags().contains("arlightbingo_boss_surface")) continue;
            found = true;
            if (ritualOpen) {
                entity.setInvisible(false);
                entity.setInvulnerable(false);
                entity.setGlowing(true);
            } else {
                entity.setInvisible(true);
                entity.setInvulnerable(true);
                entity.setGlowing(false);
                if (entity instanceof Mob mob) {
                    mob.setAI(false);
                    mob.setTarget(null);
                }
                if (entity.getLocation().distanceSquared(layout.boss()) > 16.0D * 16.0D)
                    entity.teleport(layout.boss());
            }
        }
        if (found && !ritualOpen && dormantBossNotices.add(world.getUID())) {
            for (Player player : world.getPlayers()) {
                player.sendTitle(ChatColor.DARK_GREEN + "Guardián sellado",
                        ChatColor.GOLD + "Usa las tres piezas en el altar", 10, 65, 15);
                player.sendMessage(ChatColor.YELLOW + "[Bingo] La ciudadela fue abierta, pero el Guardián "
                        + "todavía no puede manifestarse. Completa el ritual de la arena.");
            }
        }
    }

    private void awakenRitualBoss(World world, Layout layout) {
        for (Entity entity : world.getNearbyEntities(layout.boss(), 72.0D, 40.0D, 72.0D)) {
            if (!entity.getScoreboardTags().contains("arlightbingo_boss_surface")) continue;
            entity.teleport(layout.boss());
            entity.setInvisible(false);
            entity.setInvulnerable(false);
            entity.setGlowing(true);
        }
    }

    private void closeSeal(World world, Layout layout) {
        int gx = layout.ritualGate().getBlockX(), gy = layout.ritualGate().getBlockY();
        int gz = layout.ritualGate().getBlockZ();
        for (int x = -5; x <= 5; x++) for (int y = 0; y <= 7; y++) {
            boolean edge = Math.abs(x) == 5 || y == 0 || y == 7;
            Material material = edge ? ((x + y) & 1) == 0
                    ? Material.MOSSY_STONE_BRICKS : Material.DEEPSLATE_BRICKS
                    : Math.abs(x) <= 1 && y >= 2 && y <= 5
                    ? Material.EMERALD_BLOCK : Material.IRON_BARS;
            world.getBlockAt(gx + x, gy + y, gz).setType(material, false);
        }
    }

    private void openSeal(World world, Layout layout) {
        int gx = layout.ritualGate().getBlockX(), gy = layout.ritualGate().getBlockY();
        int gz = layout.ritualGate().getBlockZ();
        for (int x = -4; x <= 4; x++) for (int y = 1; y <= 6; y++)
            world.getBlockAt(gx + x, gy + y, gz).setType(Material.AIR, false);
        world.getBlockAt(layout.altar().getBlockX(), layout.altar().getBlockY(),
                layout.altar().getBlockZ()).setType(Material.BEACON, false);
        world.playSound(layout.altar(), Sound.BLOCK_BEACON_ACTIVATE, 1.2F, 0.8F);
        world.spawnParticle(Particle.END_ROD, layout.altar().clone().add(0.5D, 1.0D, 0.5D),
                120, 4.0D, 3.5D, 4.0D, 0.05D);
    }

    private Properties read(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private Location required(World world, Properties properties, String key) {
        Location location = parse(world, properties.getProperty(key));
        if (location == null) throw new IllegalArgumentException("falta " + key);
        return location;
    }

    private Location parse(World world, String raw) {
        if (raw == null) return null;
        String[] split = raw.split(",");
        if (split.length < 3) return null;
        try {
            return new Location(world, Double.parseDouble(split[0].trim()),
                    Double.parseDouble(split[1].trim()), Double.parseDouble(split[2].trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean near(Location a, Location b, double radius) {
        if (a == null || b == null || a.getWorld() != b.getWorld()) return false;
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private String root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
