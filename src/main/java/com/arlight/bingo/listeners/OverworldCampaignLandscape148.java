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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;

/** Coordinates the clean 1.48.15 template build and its two-stage boss ritual. */
public final class OverworldCampaignLandscape148 {
    static final String TEMPLATE_MARKER = "arlight-overworld-template.properties";
    static final String PENDING_TEMPLATE_MARKER = "arlight-overworld-template-1.48.pending";
    static final String LAYOUT_MARKER = "arlight-overworld-campaign-1.48.properties";
    static final String PROGRESS_MARKER = "arlight-overworld-campaign-1.48.in-progress";
    static final String DONE_MARKER = "arlight-overworld-campaign-1.48.done";
    static final String FAILED_MARKER = "arlight-overworld-campaign-1.48.failed";
    static final String REPORT_FILE = "arlight-overworld-campaign-1.48-report.txt";
    private static final String GATE_OPEN_MARKER = "arlight-overworld-ritual-gate-open.properties";
    private static final String BOSS_AWAKENED_MARKER = "arlight-overworld-boss-awakened.properties";

    private final BingoPlugin plugin;
    private final long startupEpochMillis = System.currentTimeMillis();
    private final Set<UUID> activeBuilds = new HashSet<>();
    private final Set<UUID> initializedGates = new HashSet<>();
    private final Set<UUID> dormantBossNotices = new HashSet<>();
    private final Map<UUID, Layout> layouts = new HashMap<>();

    public OverworldCampaignLandscape148(BingoPlugin plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        for (World world : Bukkit.getWorlds()) {
            startIfEligible(world);
            maintainGate(world);
            maintainRitualBoss(world);
        }
    }

    public void onWorldUnload(World world) {
        if (world == null) return;
        activeBuilds.remove(world.getUID());
        initializedGates.remove(world.getUID());
        dormantBossNotices.remove(world.getUID());
        layouts.remove(world.getUID());
    }

    public void handleInteraction(PlayerInteractEvent event) {
        if (event == null || event.getClickedBlock() == null
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        World world = event.getClickedBlock().getWorld();
        Layout layout = loadLayout(world);
        if (layout == null) return;
        Location clicked = event.getClickedBlock().getLocation();
        if (near(clicked, layout.outerAltar(), 2.25D)) {
            event.setCancelled(true);
            handleOuterAltar(event.getPlayer(), world, layout);
        } else if (near(clicked, layout.invocationAltar(), 2.25D)) {
            event.setCancelled(true);
            handleInvocationAltar(event.getPlayer(), world, layout);
        }
    }

    private void handleOuterAltar(Player player, World world, Layout layout) {
        Path marker = world.getWorldFolder().toPath().resolve(GATE_OPEN_MARKER);
        if (Files.isRegularFile(marker)) {
            player.sendMessage(ChatColor.GREEN + "La puerta ritual ya está abierta. Entra y activa el altar central.");
            return;
        }
        List<String> missing = missingItems(world);
        if (!missing.isEmpty()) {
            player.sendTitle(ChatColor.DARK_GREEN + "Puerta sellada",
                    ChatColor.GRAY + "Faltan " + String.join(", ", missing), 5, 55, 10);
            player.sendMessage(ChatColor.YELLOW
                    + "Libera los tres asentamientos y trae sus piezas a los pedestales.");
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
                    0.9F, 0.72F);
            return;
        }

        consume(world, CampaignMissionItems.HOME_EMBLEM);
        consume(world, CampaignMissionItems.TRADE_EMBLEM);
        consume(world, CampaignMissionItems.EMERALD_CORE);
        openGate(world, layout);
        writeMarker(marker, player, "gate");
        for (Player online : world.getPlayers()) {
            online.sendTitle(ChatColor.GOLD + "Las tres piezas encajan",
                    ChatColor.GREEN + "La puerta de la arena se ha abierto", 10, 70, 20);
            online.sendMessage(ChatColor.GREEN + "[Bingo] La entrada quedó libre. "
                    + ChatColor.WHITE + "El Guardián aún debe ser invocado desde el altar central.");
            online.playSound(online.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.1F, 0.75F);
            online.spawnParticle(Particle.TOTEM_OF_UNDYING,
                    online.getLocation().add(0, 1.2D, 0), 42, 0.8D, 0.8D, 0.8D, 0.04D);
        }
    }

    private void handleInvocationAltar(Player player, World world, Layout layout) {
        Path gateMarker = world.getWorldFolder().toPath().resolve(GATE_OPEN_MARKER);
        if (!Files.isRegularFile(gateMarker)) {
            player.sendMessage(ChatColor.RED + "No puedes iniciar la invocación: la puerta ritual sigue sellada.");
            player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 0.8F, 0.7F);
            return;
        }
        Path awakened = world.getWorldFolder().toPath().resolve(BOSS_AWAKENED_MARKER);
        if (Files.isRegularFile(awakened)) {
            player.sendMessage(ChatColor.GREEN + "El Guardián ya fue invocado.");
            return;
        }
        writeMarker(awakened, player, "boss");
        awakenRitualBoss(world, layout);
        world.getBlockAt(layout.invocationAltar().getBlockX(),
                layout.invocationAltar().getBlockY(),
                layout.invocationAltar().getBlockZ()).setType(Material.BEACON, false);
        for (Player online : world.getPlayers()) {
            online.sendTitle(ChatColor.DARK_GREEN + "El Guardián despierta",
                    ChatColor.GOLD + "La corrupción responde al altar", 10, 75, 20);
            online.sendMessage(ChatColor.GOLD + "[Bingo] El ritual interior se completó. "
                    + ChatColor.WHITE + "Derrota al Guardián de la Superficie.");
            online.playSound(online.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.85F, 1.25F);
            online.spawnParticle(Particle.END_ROD,
                    online.getLocation().add(0, 1.2D, 0), 70, 1.1D, 0.9D, 1.1D, 0.05D);
        }
    }

    private void startIfEligible(World world) {
        if (!plugin.getConfig().getBoolean(
                "template-worlds.overworld.campaign-layout-1-48.enabled", true)) return;
        String template = plugin.getConfig().getString(
                "template-worlds.overworld.name", "bingo_template_overworld");
        if (!world.getName().equals(template) || activeBuilds.contains(world.getUID())) return;

        Path folder = world.getWorldFolder().toPath();
        Path baseMarker = folder.resolve(TEMPLATE_MARKER);
        Path pendingMarker = folder.resolve(PENDING_TEMPLATE_MARKER);
        Path progress = folder.resolve(PROGRESS_MARKER);
        Path done = folder.resolve(DONE_MARKER);
        Path failed = folder.resolve(FAILED_MARKER);
        if ((!Files.isRegularFile(baseMarker) && !Files.isRegularFile(pendingMarker))
                || Files.isRegularFile(done)) return;

        try {
            boolean completedThisRun = Files.isRegularFile(baseMarker)
                    && Files.getLastModifiedTime(baseMarker).toMillis() >= startupEpochMillis - 5_000L;
            // A failed audit is terminal. It is cleared only when reset/generate writes
            // a strictly newer clean base marker, never merely because the server restarted.
            if (Files.isRegularFile(failed)) {
                boolean freshBase = Files.isRegularFile(baseMarker)
                        && Files.getLastModifiedTime(baseMarker).toMillis()
                        > Files.getLastModifiedTime(failed).toMillis();
                if (!freshBase) return;
                Files.deleteIfExists(failed);
                Files.deleteIfExists(progress);
            }
            boolean interrupted = Files.isRegularFile(progress)
                    && (Files.isRegularFile(pendingMarker) || Files.isRegularFile(baseMarker));
            Path sourceMarker = Files.isRegularFile(pendingMarker) ? pendingMarker : baseMarker;
            if (!interrupted && !completedThisRun) return;

            Properties base = read(sourceMarker);
            if (!"campaign_1_48_anchor_only".equals(
                    base.getProperty("baseConstruction", ""))) {
                failBuild(folder, new IllegalStateException(
                        "1.48.15 exige una base limpia sin estructuras heredadas; usa reset y generate"));
                return;
            }
            Location village = parse(world, base.getProperty("village"));
            Location citadel = parse(world, base.getProperty("dungeon"));
            Location boss = parse(world, base.getProperty("boss"));
            Location portal = parse(world, base.getProperty("portal"));
            if (village == null || citadel == null || boss == null || portal == null) {
                failBuild(folder, new IllegalStateException(
                        "No se inició 1.48.15: faltan anclas en " + sourceMarker));
                return;
            }

            Files.writeString(progress,
                    "version=1.48.15\nstarted=" + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(folder.resolve(GATE_OPEN_MARKER));
            Files.deleteIfExists(folder.resolve(BOSS_AWAKENED_MARKER));
            if (!Files.isRegularFile(pendingMarker)) {
                Files.move(baseMarker, pendingMarker, StandardCopyOption.REPLACE_EXISTING);
            }
            activeBuilds.add(world.getUID());
            new OverworldCampaignBuilder148(plugin, world, folder, village, citadel, boss, portal,
                    layout -> {
                        activeBuilds.remove(world.getUID());
                        layouts.put(world.getUID(), layout);
                        initializedGates.add(world.getUID());
                        closeGate(world, layout);
                    }, error -> {
                        activeBuilds.remove(world.getUID());
                        failBuild(folder, error);
                    }).start();
        } catch (IOException exception) {
            failBuild(folder, exception);
        }
    }

    private void failBuild(Path folder, Throwable error) {
        String reason = root(error).replace('\n', ' ').replace('\r', ' ');
        plugin.getLogger().severe("Falló el diseño Overworld 1.48.15: " + reason);
        try {
            Files.deleteIfExists(folder.resolve(PROGRESS_MARKER));
            Files.writeString(folder.resolve(FAILED_MARKER),
                    "version=1.48.15\nfailed=" + System.currentTimeMillis()
                            + "\nreason=" + reason + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException markerError) {
            plugin.getLogger().severe("No se pudo guardar el estado FAILED de Overworld 1.48.15: "
                    + markerError.getMessage());
        }
    }

    private void maintainGate(World world) {
        Layout layout = loadLayout(world);
        if (layout == null || activeBuilds.contains(world.getUID())
                || !initializedGates.add(world.getUID())) return;
        Path marker = world.getWorldFolder().toPath().resolve(GATE_OPEN_MARKER);
        if (Files.isRegularFile(marker)) openGate(world, layout);
        else closeGate(world, layout);
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
                    required(world, p, "outerAltar"), required(world, p, "invocationAltar"),
                    required(world, p, "ritualGate"));
            layouts.put(world.getUID(), layout);
            return layout;
        } catch (IOException | IllegalArgumentException exception) {
            plugin.getLogger().warning("No se pudo leer el diseño 1.48.15 en "
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
        for (Player player : world.getPlayers()) {
            if (CampaignMissionItems.has(player, plugin, id)) return true;
        }
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
        boolean awakened = Files.isRegularFile(
                world.getWorldFolder().toPath().resolve(BOSS_AWAKENED_MARKER));
        boolean found = false;
        for (Entity entity : world.getNearbyEntities(layout.boss(), 74.0D, 42.0D, 74.0D)) {
            if (!entity.getScoreboardTags().contains("arlightbingo_boss_surface")) continue;
            found = true;
            if (awakened) {
                entity.setInvisible(false);
                entity.setInvulnerable(false);
                entity.setGlowing(true);
                if (entity instanceof Mob mob) mob.setAI(true);
            } else {
                entity.setInvisible(true);
                entity.setInvulnerable(true);
                entity.setGlowing(false);
                if (entity instanceof Mob mob) {
                    mob.setAI(false);
                    mob.setTarget(null);
                }
                if (entity.getLocation().distanceSquared(layout.boss()) > 14.0D * 14.0D) {
                    entity.teleport(layout.boss());
                }
            }
        }
        boolean gateOpen = Files.isRegularFile(
                world.getWorldFolder().toPath().resolve(GATE_OPEN_MARKER));
        if (found && gateOpen && !awakened && dormantBossNotices.add(world.getUID())) {
            for (Player player : world.getPlayers()) {
                player.sendTitle(ChatColor.DARK_GREEN + "Arena abierta",
                        ChatColor.GOLD + "Activa el altar central para invocar al Guardián",
                        10, 70, 15);
            }
        }
    }

    private void awakenRitualBoss(World world, Layout layout) {
        for (Entity entity : world.getNearbyEntities(layout.boss(), 74.0D, 42.0D, 74.0D)) {
            if (!entity.getScoreboardTags().contains("arlightbingo_boss_surface")) continue;
            entity.teleport(layout.boss());
            entity.setInvisible(false);
            entity.setInvulnerable(false);
            entity.setGlowing(true);
            if (entity instanceof Mob mob) mob.setAI(true);
        }
    }

    private void closeGate(World world, Layout layout) {
        int gx = layout.ritualGate().getBlockX();
        int gy = layout.ritualGate().getBlockY();
        int gz = layout.ritualGate().getBlockZ();
        for (int x = -5; x <= 5; x++) for (int y = 0; y <= 10; y++) {
            boolean edge = Math.abs(x) == 5 || y == 0 || y == 10;
            Material material = edge ? Material.DEEPSLATE_BRICKS : Material.DARK_OAK_FENCE;
            world.getBlockAt(gx + x, gy + y, gz).setType(material, false);
        }
    }

    private void openGate(World world, Layout layout) {
        int gx = layout.ritualGate().getBlockX();
        int gy = layout.ritualGate().getBlockY();
        int gz = layout.ritualGate().getBlockZ();
        for (int x = -4; x <= 4; x++) for (int y = 1; y <= 9; y++) {
            world.getBlockAt(gx + x, gy + y, gz).setType(Material.AIR, false);
        }
        world.getBlockAt(layout.outerAltar().getBlockX(),
                layout.outerAltar().getBlockY() + 4,
                layout.outerAltar().getBlockZ()).setType(Material.BEACON, false);
        world.playSound(layout.outerAltar(), Sound.BLOCK_BEACON_ACTIVATE, 1.2F, 0.78F);
        world.spawnParticle(Particle.END_ROD,
                layout.ritualGate().clone().add(0.5D, 5.0D, 0.5D),
                130, 5.0D, 5.0D, 2.0D, 0.05D);
    }

    private void writeMarker(Path marker, Player player, String stage) {
        try {
            Files.writeString(marker,
                    "version=1.48.15\nstage=" + stage + "\nplayer=" + player.getUniqueId()
                            + "\ntime=" + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            plugin.getLogger().warning("La fase ritual cambió, pero no pudo guardarse: "
                    + exception.getMessage());
        }
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
                    Double.parseDouble(split[1].trim()),
                    Double.parseDouble(split[2].trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean near(Location a, Location b, double radius) {
        if (a == null || b == null || a.getWorld() != b.getWorld()) return false;
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private String root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }
}
