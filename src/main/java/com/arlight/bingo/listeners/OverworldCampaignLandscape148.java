package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import com.arlight.bingo.util.CampaignItemBridge;
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
import org.bukkit.command.CommandSender;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;

/** Coordinates the clean 1.48.28 template build and its two-stage boss ritual. */
public final class OverworldCampaignLandscape148 {
    static final String TEMPLATE_MARKER = "arlight-overworld-template.properties";
    static final String PENDING_TEMPLATE_MARKER = "arlight-overworld-template-1.48.pending";
    static final String LAYOUT_MARKER = "arlight-overworld-campaign-1.48.properties";
    static final String PROGRESS_MARKER = "arlight-overworld-campaign-1.48.in-progress";
    static final String DONE_MARKER = "arlight-overworld-campaign-1.48.done";
    static final String FAILED_MARKER = "arlight-overworld-campaign-1.48.failed";
    static final String REPORT_FILE = "arlight-overworld-campaign-1.48-report.txt";
    static final String GATE_OPEN_MARKER = "arlight-overworld-ritual-gate-open.properties";
    private static final String BOSS_AWAKENED_MARKER = "arlight-overworld-boss-awakened.properties";
    private static final String TEST_ACTIVE_MARKER = "arlight-overworld-ritual-test.active";
    private static final String TEST_OPEN_MARKER = "arlight-overworld-ritual-test.open";
    private static final String TEST_GATE_SNAPSHOT = "arlight-overworld-ritual-test-gate.snapshot";
    private static final int TEST_GATE_MIN_X = 133;
    private static final int TEST_GATE_MAX_X = 147;
    private static final int TEST_GATE_MIN_Y = 90;
    private static final int TEST_GATE_MAX_Y = 96;
    private static final int TEST_GATE_Z = 192;

    private final BingoPlugin plugin;
    private final long startupEpochMillis = System.currentTimeMillis();
    private final Set<UUID> activeBuilds = new HashSet<>();
    private final Set<UUID> dormantBossNotices = new HashSet<>();
    private final Set<UUID> activeGateAnimations = new HashSet<>();
    private final Map<UUID, Layout> layouts = new HashMap<>();
    private final OverworldMedalRitual medalRitual;

    public OverworldCampaignLandscape148(BingoPlugin plugin) {
        this.plugin = plugin;
        this.medalRitual = new OverworldMedalRitual(plugin, this::completeMedalRitual);
    }

    public void tick() {
        for (World world : Bukkit.getWorlds()) {
            startIfEligible(world);
            maintainGate(world);
            maintainMedalRitual(world);
            maintainRitualBoss(world);
        }
    }

    public void onWorldUnload(World world) {
        if (world == null) return;
        activeBuilds.remove(world.getUID());
        dormantBossNotices.remove(world.getUID());
        activeGateAnimations.remove(world.getUID());
        layouts.remove(world.getUID());
        medalRitual.onWorldUnload(world);
    }

    public void handleInteraction(PlayerInteractEvent event) {
        if (event == null || event.getClickedBlock() == null
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        World world = event.getClickedBlock().getWorld();
        Layout layout = loadLayout(world);
        if (layout == null) return;
        if (medalRitual.handle(event, layout)) return;
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
        List<String> missing = medalRitual.missing(world);
        if (!missing.isEmpty()) {
            player.sendTitle(ChatColor.DARK_GREEN + "Puerta sellada",
                    ChatColor.GRAY + "Faltan " + String.join(", ", missing), 5, 55, 10);
            player.sendMessage(ChatColor.YELLOW
                    + "Coloca cada medalla en su pedestal GeckoLib correspondiente.");
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
                    0.9F, 0.72F);
        } else {
            player.sendMessage(ChatColor.GOLD + "Las tres medallas están colocadas. El mecanismo está terminando de abrirse.");
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
        CampaignItemBridge.animateCorruptedAltar(layout.invocationAltar());
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
                        "1.48.28 exige una base limpia sin estructuras heredadas; usa reset y generate"));
                return;
            }
            Location village = parse(world, base.getProperty("village"));
            Location citadel = parse(world, base.getProperty("dungeon"));
            Location boss = parse(world, base.getProperty("boss"));
            Location portal = parse(world, base.getProperty("portal"));
            if (village == null || citadel == null || boss == null || portal == null) {
                failBuild(folder, new IllegalStateException(
                        "No se inició 1.48.28: faltan anclas en " + sourceMarker));
                return;
            }

            Files.writeString(progress,
                    "version=1.48.28\nstarted=" + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(folder.resolve(GATE_OPEN_MARKER));
            Files.deleteIfExists(folder.resolve(BOSS_AWAKENED_MARKER));
            Files.deleteIfExists(folder.resolve(OverworldMedalRitual.MARKER_FILE));
            if (!Files.isRegularFile(pendingMarker)) {
                Files.move(baseMarker, pendingMarker, StandardCopyOption.REPLACE_EXISTING);
            }
            activeBuilds.add(world.getUID());
            new OverworldCampaignBuilder148(plugin, world, folder, village, citadel, boss, portal,
                    layout -> {
                        activeBuilds.remove(world.getUID());
                        layouts.put(world.getUID(), layout);
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
        plugin.getLogger().severe("Falló el diseño Overworld 1.48.28: " + reason);
        try {
            Files.deleteIfExists(folder.resolve(PROGRESS_MARKER));
            Files.writeString(folder.resolve(FAILED_MARKER),
                    "version=1.48.28\nfailed=" + System.currentTimeMillis()
                            + "\nreason=" + reason + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException markerError) {
            plugin.getLogger().severe("No se pudo guardar el estado FAILED de Overworld 1.48.28: "
                    + markerError.getMessage());
        }
    }

    private void maintainGate(World world) {
        Layout layout = loadLayout(world);
        if (layout == null || activeBuilds.contains(world.getUID())
                || activeGateAnimations.contains(world.getUID())) return;
        Path folder = world.getWorldFolder().toPath();
        if (Files.isRegularFile(folder.resolve(TEST_ACTIVE_MARKER))) {
            if (Files.isRegularFile(folder.resolve(TEST_OPEN_MARKER))) {
                setRegionAir(world, TEST_GATE_MIN_X, TEST_GATE_MAX_X,
                        TEST_GATE_MIN_Y, TEST_GATE_MAX_Y, TEST_GATE_Z);
            }
            return;
        }
        Path marker = folder.resolve(GATE_OPEN_MARKER);
        boolean open = Files.isRegularFile(marker);
        if (open) {
            if (!isGateOpen(world, layout)) openGate(world, layout);
        } else if (!isGateClosed(world, layout)) {
            closeGate(world, layout);
        }
    }

    private boolean isGateClosed(World world, Layout layout) {
        int gx = layout.ritualGate().getBlockX();
        int gy = layout.ritualGate().getBlockY();
        int gz = OverworldCampaignArchitecture148.bossRitualGatePlaneZ(layout.ritualGate());
        for (int x = -OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HALF_WIDTH;
             x <= OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HALF_WIDTH; x++) {
            for (int y = 0; y < OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HEIGHT; y++) {
                if (world.getBlockAt(gx + x, gy + y, gz).getType() != Material.IRON_BARS) return false;
            }
        }
        return true;
    }

    private boolean isGateOpen(World world, Layout layout) {
        int gx = layout.ritualGate().getBlockX();
        int gy = layout.ritualGate().getBlockY();
        int gz = OverworldCampaignArchitecture148.bossRitualGatePlaneZ(layout.ritualGate());
        for (int x = -OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HALF_WIDTH;
             x <= OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HALF_WIDTH; x++) {
            for (int y = 0; y < OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HEIGHT; y++) {
                if (!world.getBlockAt(gx + x, gy + y, gz).getType().isAir()) return false;
            }
        }
        return true;
    }

    private Layout loadLayout(World world) {
        Layout known = layouts.get(world.getUID());
        if (known != null) return known;
        Path file = world.getWorldFolder().toPath().resolve(LAYOUT_MARKER);
        try {
            Layout layout;
            if (Files.isRegularFile(file)) {
                Properties p = read(file);
                layout = new Layout(required(world, p, "village"),
                        required(world, p, "residential"), required(world, p, "commercial"),
                        required(world, p, "military"), required(world, p, "citadel"),
                        required(world, p, "boss"), required(world, p, "portal"),
                        required(world, p, "outerAltar"), required(world, p, "invocationAltar"),
                        required(world, p, "ritualGate"));
            } else {
                layout = fallbackLayout(world);
                if (layout == null) return null;
            }
            layouts.put(world.getUID(), layout);
            return layout;
        } catch (IOException | IllegalArgumentException exception) {
            plugin.getLogger().warning("No se pudo leer el diseño ritual en "
                    + world.getName() + ": " + exception.getMessage());
            return null;
        }
    }

    private Layout fallbackLayout(World world) throws IOException {
        Path marker = world.getWorldFolder().toPath().resolve(TEMPLATE_MARKER);
        if (!Files.isRegularFile(marker)) return null;
        Properties p = read(marker);
        Location village = required(world, p, "village");
        Location boss = required(world, p, "boss");
        Location portal = required(world, p, "portal");
        int citadelX = plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.citadel-x", 140);
        int citadelZ = plugin.getConfig().getInt(
                "template-worlds.overworld.campaign-layout-1-48.citadel-z", 30);
        Location citadel = surface(world, citadelX, citadelZ);
        Location residential = surface(world, citadelX - 170, citadelZ + 100);
        Location commercial = surface(world, citadelX, citadelZ - 190);
        Location military = surface(world, citadelX + 170, citadelZ + 80);
        Location gate = new Location(world, boss.getBlockX(), boss.getBlockY(), boss.getBlockZ() - 50);
        Location outer = surface(world, boss.getBlockX(), boss.getBlockZ() - 64);
        return new Layout(village, residential, commercial, military, citadel, boss, portal,
                outer, boss.clone(), gate);
    }

    private Location surface(World world, int x, int z) {
        return new Location(world, x, world.getHighestBlockYAt(x, z) + 1, z);
    }

    private void maintainMedalRitual(World world) {
        Layout layout = loadLayout(world);
        if (layout == null) return;
        Path folder = world.getWorldFolder().toPath();
        boolean test = Files.isRegularFile(folder.resolve(TEST_ACTIVE_MARKER));
        boolean gateOpen = Files.isRegularFile(folder.resolve(
                test ? TEST_OPEN_MARKER : GATE_OPEN_MARKER));
        medalRitual.maintain(world, layout, gateOpen);
    }

    private void completeMedalRitual(World world, Layout layout, Player activator) {
        Path folder = world.getWorldFolder().toPath();
        boolean test = Files.isRegularFile(folder.resolve(TEST_ACTIVE_MARKER));
        Path marker = folder.resolve(test ? TEST_OPEN_MARKER : GATE_OPEN_MARKER);
        if (Files.isRegularFile(marker) || activeGateAnimations.contains(world.getUID())) return;

        Runnable completed = () -> {
            writeMarker(marker, activator, test ? "template-test-three-medals" : "three-medals");
            for (Player online : world.getPlayers()) {
                online.sendTitle(ChatColor.GOLD + "Las tres medallas resonaron",
                        ChatColor.GREEN + "La reja del Guardián se ha abierto", 10, 75, 20);
                online.sendMessage(ChatColor.GREEN + "[Bingo] El sello exterior fue destruido. "
                        + ChatColor.WHITE + (test
                        ? "Usa /bingo template overworld ritual reset para restaurar la prueba."
                        : "El Guardián aún debe ser invocado desde el altar central."));
                online.playSound(online.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.1F, 0.75F);
                online.spawnParticle(Particle.TOTEM_OF_UNDYING,
                        online.getLocation().add(0, 1.2D, 0), 42, 0.8D, 0.8D, 0.8D, 0.04D);
            }
        };

        if (test) {
            animateGateOpen(world, TEST_GATE_MIN_X, TEST_GATE_MAX_X,
                    TEST_GATE_MIN_Y, TEST_GATE_MAX_Y, TEST_GATE_Z,
                    new Location(world, 140.0D, 93.0D, 192.0D), completed);
        } else {
            int gx = layout.ritualGate().getBlockX();
            int gy = layout.ritualGate().getBlockY();
            int gz = OverworldCampaignArchitecture148.bossRitualGatePlaneZ(layout.ritualGate());
            animateGateOpen(world,
                    gx - OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HALF_WIDTH,
                    gx + OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HALF_WIDTH,
                    gy, gy + OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HEIGHT - 1,
                    gz, layout.ritualGate(), completed);
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
        for (BlockEdit edit : OverworldCampaignArchitecture148.restoreClosedBossRitualGate(
                layout.ritualGate())) {
            var block = world.getBlockAt(edit.x(), edit.y(), edit.z());
            if (block.getType() != edit.material()) block.setType(edit.material(), false);
            if (edit.data() != null) {
                block.setBlockData(Bukkit.createBlockData(edit.data()), false);
            }
        }
    }

    private void openGate(World world, Layout layout) {
        int gx = layout.ritualGate().getBlockX();
        int gy = layout.ritualGate().getBlockY();
        int gz = OverworldCampaignArchitecture148.bossRitualGatePlaneZ(layout.ritualGate());
        for (int x = -OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HALF_WIDTH;
             x <= OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HALF_WIDTH; x++) {
            for (int y = 0; y < OverworldCampaignArchitecture148.BOSS_RITUAL_GATE_HEIGHT; y++) {
                world.getBlockAt(gx + x, gy + y, gz).setType(Material.AIR, false);
            }
        }
        world.playSound(layout.outerAltar(), Sound.BLOCK_BEACON_ACTIVATE, 1.2F, 0.78F);
        world.spawnParticle(Particle.END_ROD,
                layout.ritualGate().clone().add(0.5D, 5.0D, 0.5D),
                130, 5.0D, 5.0D, 2.0D, 0.05D);
    }

    public boolean debugRitualTestStart(CommandSender sender) {
        World world = resolveTemplateWorld(sender);
        if (world == null) return false;
        Path folder = world.getWorldFolder().toPath();
        if (Files.isRegularFile(folder.resolve(TEST_ACTIVE_MARKER))) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una prueba ritual activa. Usa ritual reset antes de iniciar otra.");
            return false;
        }
        try {
            saveGateSnapshot(world, folder.resolve(TEST_GATE_SNAPSHOT));
            Files.deleteIfExists(folder.resolve(TEST_OPEN_MARKER));
            Files.writeString(folder.resolve(TEST_ACTIVE_MARKER),
                    "version=1.48.43\\nstarted=" + System.currentTimeMillis() + "\\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            medalRitual.reset(world);
            sender.sendMessage(ChatColor.GREEN + "Prueba ritual iniciada. La reja 133..147 / 90..96 / z=192 fue respaldada.");
            sender.sendMessage(ChatColor.AQUA + "Coloca los pedestales y usa ritual give para recibir los objetos.");
            return true;
        } catch (IOException exception) {
            sender.sendMessage(ChatColor.RED + "No se pudo iniciar la prueba: " + exception.getMessage());
            return false;
        }
    }

    public boolean debugRitualTestGive(Player player) {
        World world = resolveTemplateWorld(player);
        if (world == null) return false;
        Path active = world.getWorldFolder().toPath().resolve(TEST_ACTIVE_MARKER);
        if (!Files.isRegularFile(active)) {
            player.sendMessage(ChatColor.RED + "Primero usa /bingo template overworld ritual start.");
            return false;
        }
        CampaignItemBridge.give(player, CampaignItemBridge.HOME_MEDAL_PEDESTAL);
        CampaignItemBridge.give(player, CampaignItemBridge.TRADE_MEDAL_PEDESTAL);
        CampaignItemBridge.give(player, CampaignItemBridge.BASTION_MEDAL_PEDESTAL);
        CampaignItemBridge.give(player, CampaignItemBridge.MOSSBOUND_HOME_MEDAL);
        CampaignItemBridge.give(player, CampaignItemBridge.GILDED_TRADE_MEDAL);
        CampaignItemBridge.give(player, CampaignItemBridge.EMERALD_BASTION_MEDAL);
        player.sendMessage(ChatColor.GREEN + "Recibiste los tres pedestales y las tres medallas de prueba.");
        return true;
    }

    public boolean debugRitualTestOpen(CommandSender sender) {
        World world = resolveTemplateWorld(sender);
        if (world == null) return false;
        Path folder = world.getWorldFolder().toPath();
        if (!Files.isRegularFile(folder.resolve(TEST_ACTIVE_MARKER))) {
            sender.sendMessage(ChatColor.RED + "Primero usa /bingo template overworld ritual start.");
            return false;
        }
        if (Files.isRegularFile(folder.resolve(TEST_OPEN_MARKER))) {
            sender.sendMessage(ChatColor.YELLOW + "La reja de prueba ya está abierta.");
            return false;
        }
        animateGateOpen(world, TEST_GATE_MIN_X, TEST_GATE_MAX_X,
                TEST_GATE_MIN_Y, TEST_GATE_MAX_Y, TEST_GATE_Z,
                new Location(world, 140.0D, 93.0D, 192.0D), () -> {
                    writeMarker(folder.resolve(TEST_OPEN_MARKER),
                            sender instanceof Player player ? player : null, "template-test-direct-open");
                    sender.sendMessage(ChatColor.GREEN + "Animación de apertura terminada. Usa ritual reset para restaurar la reja.");
                });
        return true;
    }

    public boolean debugRitualTestReset(CommandSender sender) {
        World world = resolveTemplateWorld(sender);
        if (world == null) return false;
        Path folder = world.getWorldFolder().toPath();
        Path snapshot = folder.resolve(TEST_GATE_SNAPSHOT);
        if (!Files.isRegularFile(snapshot)) {
            sender.sendMessage(ChatColor.RED + "No existe una copia de la reja de prueba para restaurar.");
            return false;
        }
        try {
            activeGateAnimations.remove(world.getUID());
            restoreGateSnapshot(world, snapshot);
            medalRitual.reset(world);
            Files.deleteIfExists(folder.resolve(TEST_OPEN_MARKER));
            Files.deleteIfExists(folder.resolve(TEST_ACTIVE_MARKER));
            Files.deleteIfExists(snapshot);
            sender.sendMessage(ChatColor.GREEN + "Prueba terminada: la reja y los pedestales regresaron a su estado anterior.");
            return true;
        } catch (IOException exception) {
            sender.sendMessage(ChatColor.RED + "No se pudo restaurar la reja: " + exception.getMessage());
            return false;
        }
    }

    public boolean debugRitualTestStatus(CommandSender sender) {
        World world = resolveTemplateWorld(sender);
        if (world == null) return false;
        Path folder = world.getWorldFolder().toPath();
        boolean active = Files.isRegularFile(folder.resolve(TEST_ACTIVE_MARKER));
        boolean open = Files.isRegularFile(folder.resolve(TEST_OPEN_MARKER));
        boolean snapshot = Files.isRegularFile(folder.resolve(TEST_GATE_SNAPSHOT));
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Ritual de plantilla: "
                + ChatColor.WHITE + "activo=" + active + ", rejaAbierta=" + open
                + ", respaldo=" + snapshot + ", animando="
                + activeGateAnimations.contains(world.getUID()));
        return true;
    }

    private World resolveTemplateWorld(CommandSender sender) {
        String template = plugin.getConfig().getString(
                "template-worlds.overworld.name", "bingo_template_overworld");
        World world = sender instanceof Player player && player.getWorld().getName().equals(template)
                ? player.getWorld() : Bukkit.getWorld(template);
        if (world == null) sender.sendMessage(ChatColor.RED + "La plantilla Overworld no está cargada: " + template);
        return world;
    }

    private void saveGateSnapshot(World world, Path file) throws IOException {
        Properties snapshot = new Properties();
        snapshot.setProperty("version", "1.48.43");
        snapshot.setProperty("bounds", TEST_GATE_MIN_X + "," + TEST_GATE_MAX_X + ","
                + TEST_GATE_MIN_Y + "," + TEST_GATE_MAX_Y + "," + TEST_GATE_Z);
        for (int x = TEST_GATE_MIN_X; x <= TEST_GATE_MAX_X; x++) {
            for (int y = TEST_GATE_MIN_Y; y <= TEST_GATE_MAX_Y; y++) {
                snapshot.setProperty(x + "," + y + "," + TEST_GATE_Z,
                        world.getBlockAt(x, y, TEST_GATE_Z).getBlockData().getAsString());
            }
        }
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            snapshot.store(writer, "ArlightBingo exact ritual test gate snapshot");
        }
    }

    private void restoreGateSnapshot(World world, Path file) throws IOException {
        Properties snapshot = read(file);
        for (int x = TEST_GATE_MIN_X; x <= TEST_GATE_MAX_X; x++) {
            for (int y = TEST_GATE_MIN_Y; y <= TEST_GATE_MAX_Y; y++) {
                String data = snapshot.getProperty(x + "," + y + "," + TEST_GATE_Z);
                if (data == null || data.isBlank()) continue;
                world.getBlockAt(x, y, TEST_GATE_Z).setBlockData(Bukkit.createBlockData(data), false);
            }
        }
    }

    private void animateGateOpen(World world, int minX, int maxX, int minY, int maxY,
                                 int z, Location effects, Runnable completed) {
        if (!activeGateAnimations.add(world.getUID())) return;
        int layers = Math.max(1, maxY - minY + 1);
        for (int layer = 0; layer < layers; layer++) {
            final int layerIndex = layer;
            final int y = minY + layerIndex;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (int x = minX; x <= maxX; x++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
                Location line = new Location(world, (minX + maxX) / 2.0D + 0.5D,
                        y + 0.5D, z + 0.5D);
                world.spawnParticle(Particle.ELECTRIC_SPARK, line,
                        Math.max(12, (maxX - minX + 1) * 2),
                        Math.max(1.0D, (maxX - minX) / 2.0D), 0.15D, 0.2D, 0.02D);
                world.playSound(line, Sound.BLOCK_CHAIN_BREAK, 0.75F,
                        0.75F + layerIndex * 0.06F);
            }, layerIndex * 5L);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            activeGateAnimations.remove(world.getUID());
            world.playSound(effects, Sound.BLOCK_PISTON_EXTEND, 1.0F, 0.65F);
            world.spawnParticle(Particle.END_ROD, effects.clone().add(0.5D, 1.0D, 0.5D),
                    90, 4.0D, 2.8D, 0.7D, 0.04D);
            if (completed != null) completed.run();
        }, layers * 5L + 4L);
    }

    private void setRegionAir(World world, int minX, int maxX, int minY, int maxY, int z) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (!world.getBlockAt(x, y, z).getType().isAir()) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private void writeMarker(Path marker, Player player, String stage) {
        try {
            Files.writeString(marker,
                    "version=1.48.43\nstage=" + stage + "\nplayer="
                            + (player == null ? "system" : player.getUniqueId())
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
