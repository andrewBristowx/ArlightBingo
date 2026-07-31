package com.arlight.bingo.util;

import com.arlight.bingo.BingoPlugin;
import com.arlight.bingo.game.BingoGame;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Escenas personales de Somita. Desde 1.37.0 la guía ya no es una entidad del
 * servidor: ArlightChatClient dibuja una skin slim por jugador y el servidor sólo
 * sincroniza variante, animación, posición y diálogo.
 */
public final class CampaignCinematics {
    private static final String LEGACY_GUIDE_TAG = "arlightbingo_somita_guide";
    private static final String LEGACY_TEMPLATE_TAG = "arlightbingo_template_somita";

    private final JavaPlugin plugin;
    private final BingoGame game;
    private final SomitaGuideNetwork network;
    private final Set<UUID> overworldPlayed = new HashSet<>();
    private final Set<UUID> netherPlayed = new HashSet<>();
    private final Set<UUID> endPlayed = new HashSet<>();
    private boolean outroRunning;

    public CampaignCinematics(JavaPlugin plugin, BingoGame game) {
        this.plugin = plugin;
        this.game = game;
        this.network = plugin instanceof BingoPlugin bingo
                ? bingo.getSomitaGuideNetwork() : new SomitaGuideNetwork(plugin);
    }

    public void reset() {
        overworldPlayed.clear();
        netherPlayed.clear();
        endPlayed.clear();
        outroRunning = false;
        network.clearAll();
        for (World world : Bukkit.getWorlds()) cleanupLegacySomita(world);
    }

    public boolean isIntroPlayed(Player player) {
        return player != null && overworldPlayed.contains(player.getUniqueId());
    }

    public void playOverworldIntro(Player player, Location focus) {
        if (!enabled() || !plugin.getConfig().getBoolean("campaign.somita-guide.auto-overworld", true) || player == null || focus == null || focus.getWorld() == null
                || !overworldPlayed.add(player.getUniqueId())) return;
        Location at = inFrontOf(player, focus, 3.0D, 1.5D);
        network.show(player, "overworld_intro", SomitaGuideNetwork.Variant.OVERWORLD,
                SomitaGuideNetwork.Animation.WAVE, at, 170,
                "Este mundo necesita tu ayuda. Explora la ciudad, completa objetivos y encuentra la ruta hacia el guardián.");
        network.effect(player, SomitaGuideNetwork.Effect.OVERWORLD_APPEAR, 34);
        cue(player, at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, Particle.CHERRY_LEAVES);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            network.animate(player, SomitaGuideNetwork.Animation.POINT, 70,
                    "Empieza por la plaza y sigue las luces de esmeralda.");
            network.effect(player, SomitaGuideNetwork.Effect.OVERWORLD_POINT, 54, focus);
        }, 72L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> network.animate(player,
                SomitaGuideNetwork.Animation.VANISH, 24, ""), 145L);
    }

    public void playNetherIntro(Player player, Location arrival, Location lock) {
        if (!enabled() || !plugin.getConfig().getBoolean("campaign.somita-guide.auto-nether", true) || player == null || arrival == null || arrival.getWorld() == null
                || !netherPlayed.add(player.getUniqueId())) return;
        Location at = inFrontOf(player, arrival, 3.2D, 1.4D);
        face(at, lock == null ? player.getLocation() : lock);
        network.show(player, "nether_intro", SomitaGuideNetwork.Variant.NETHER,
                SomitaGuideNetwork.Animation.CROSS_ARMS, at, 220,
                "La puerta central está sellada. Explora las alas de la fortaleza y recupera la Llave Ígnea.");
        network.effect(player, SomitaGuideNetwork.Effect.NETHER_APPEAR, 38);
        cue(player, at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, Particle.REVERSE_PORTAL);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            network.animate(player, SomitaGuideNetwork.Animation.POINT, 86,
                    "Abre la cerradura, derrota al guardián y el santuario revelará el portal al End.");
            network.effect(player, SomitaGuideNetwork.Effect.NETHER_LOCK, 70, lock);
        }, 92L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> network.animate(player,
                SomitaGuideNetwork.Animation.VANISH, 26, ""), 190L);
    }

    public void playEndIntro(Player player, Location arrival, Location guardianDirection) {
        if (!enabled() || !plugin.getConfig().getBoolean("campaign.somita-guide.auto-end", true) || player == null || arrival == null || arrival.getWorld() == null
                || !endPlayed.add(player.getUniqueId())) return;
        Location at = inFrontOf(player, arrival, 3.0D, 1.6D);
        face(at, guardianDirection == null ? player.getLocation() : guardianDirection);
        network.show(player, "end_intro", SomitaGuideNetwork.Variant.END,
                SomitaGuideNetwork.Animation.LOOK, at, 230,
                "Antes del dragón debes vencer al Guardián del Vacío y abrir el camino hacia el altar.");
        network.effect(player, SomitaGuideNetwork.Effect.END_APPEAR, 42);
        cue(player, at, Sound.BLOCK_END_PORTAL_FRAME_FILL, Particle.END_ROD);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            network.animate(player, SomitaGuideNetwork.Animation.POINT, 90,
                    "Consigue la llave, activa el altar y continúa hacia la arena final.");
            network.effect(player, SomitaGuideNetwork.Effect.END_ALTAR, 74, guardianDirection);
        }, 96L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> network.animate(player,
                SomitaGuideNetwork.Animation.VANISH, 28, ""), 200L);
    }

    /** Ejecuta el final y llama openExit incluso si la parte visual no está instalada. */
    public void playDragonOutro(Location death, Runnable openExit) {
        if (outroRunning || death == null || death.getWorld() == null) {
            if (openExit != null) openExit.run();
            return;
        }
        outroRunning = true;
        World world = death.getWorld();
        Location eggAt = findSafeFloor(death.clone());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            eggAt.getBlock().setType(Material.DRAGON_EGG, false);
            Location somitaAt = eggAt.clone().add(2.6D, 0.0D, 1.5D);
            somitaAt.setYaw(120.0F);
            Vector facing = somitaAt.getDirection().setY(0);
            if (facing.lengthSquared() < 0.0001D) facing = new Vector(0, 0, 1);
            facing.normalize();
            Location portalBase = somitaAt.clone().subtract(facing.clone().multiply(3.6D));
            buildSomitaPortal(portalBase, facing);

            for (Player player : world.getPlayers()) {
                if (game.getTeamOf(player) == null) continue;
                network.show(player, "dragon_outro", SomitaGuideNetwork.Variant.CELEBRATION,
                        celebrationAnimation(), somitaAt, 185,
                        "¡Lo lograron! Gracias por recuperar el huevo del dragón.");
                network.effect(player, SomitaGuideNetwork.Effect.CELEBRATION_BURST, 72);
            }
            world.spawnParticle(Particle.PORTAL, somitaAt.clone().add(0, 1.2, 0), 70,
                    0.8, 1.3, 0.8, 0.15);
            world.playSound(somitaAt, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.2F, 1.35F);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                eggAt.getBlock().setType(Material.AIR, false);
                for (Player player : world.getPlayers()) if (game.getTeamOf(player) != null) {
                    network.animate(player, SomitaGuideNetwork.Animation.HOLD, 70,
                            "Me servirá para mi desayuno ♡");
                }
                world.playSound(somitaAt, Sound.ENTITY_ITEM_PICKUP, 1.0F, 1.4F);
            }, 55L);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    if (openExit != null) openExit.run();
                } finally {
                    for (Player player : world.getPlayers()) if (game.getTeamOf(player) != null) {
                        network.animate(player, SomitaGuideNetwork.Animation.VANISH, 28, "");
                    }
                    world.spawnParticle(Particle.REVERSE_PORTAL, somitaAt.clone().add(0, 1.2, 0),
                            90, 0.9, 1.2, 0.9, 0.16);
                    world.playSound(somitaAt, Sound.BLOCK_PORTAL_TRAVEL, 0.7F, 1.45F);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> removeSomitaPortal(portalBase), 24L);
                    outroRunning = false;
                }
            }, 165L);
        }, 105L);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!outroRunning) return;
            if (openExit != null) openExit.run();
            outroRunning = false;
        }, 340L);
    }

    private SomitaGuideNetwork.Animation celebrationAnimation() {
        return SomitaGuideNetwork.Animation.parse(plugin.getConfig().getString(
                "somita.animations.celebration", "celebrate_cute"));
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("somita.enabled", true)
                && plugin.getConfig().getBoolean("campaign.somita-guide.enabled",
                plugin.getConfig().getBoolean("campaign.somita-cinematics", true));
    }

    private Location inFrontOf(Player player, Location fallback, double forward, double side) {
        Location base = player.getLocation().clone();
        if (base.getWorld() != fallback.getWorld()) base = fallback.clone();
        Vector direction = base.getDirection().setY(0);
        if (direction.lengthSquared() < 0.001D) direction = new Vector(0, 0, 1);
        direction.normalize();
        Vector right = new Vector(-direction.getZ(), 0, direction.getX());
        Location result = base.add(direction.multiply(forward)).add(right.multiply(side));
        result.setY(Math.max(fallback.getY(), result.getY()));
        face(result, player.getLocation());
        return result;
    }

    private void face(Location source, Location target) {
        if (source == null || target == null || source.getWorld() != target.getWorld()) return;
        Vector vector = target.toVector().subtract(source.toVector());
        source.setYaw((float) Math.toDegrees(Math.atan2(-vector.getX(), vector.getZ())));
        source.setPitch(0.0F);
    }

    private void cue(Player player, Location at, Sound sound, Particle particle) {
        if (at.getWorld() == null) return;
        player.playSound(at, sound, 1.0F, 1.25F);
        if (!player.getListeningPluginChannels().contains(SomitaGuideNetwork.CHANNEL)) {
            player.spawnParticle(particle, at.clone().add(0, 1.2, 0), 24,
                    0.8, 1.0, 0.8, 0.04);
            player.sendMessage(ChatColor.LIGHT_PURPLE + "[Somita] " + ChatColor.WHITE
                    + "Instala ArlightChatClient 4.8.0 para ver a la guía animada y sus efectos.");
        }
    }

    private void cleanupLegacySomita(World world) {
        if (world == null) return;
        for (Entity entity : world.getEntities()) {
            if (entity.getScoreboardTags().contains(LEGACY_GUIDE_TAG)
                    || entity.getScoreboardTags().contains(LEGACY_TEMPLATE_TAG)
                    || entity.getScoreboardTags().contains("arlight_somita_intro")
                    || entity.getScoreboardTags().contains("arlight_somita_outro")) {
                entity.remove();
            }
        }
    }

    private void buildSomitaPortal(Location base, Vector facing) {
        if (base == null || base.getWorld() == null) return;
        World world = base.getWorld();
        boolean alongX = Math.abs(facing.getX()) < Math.abs(facing.getZ());
        for (int horizontal = -2; horizontal <= 2; horizontal++) for (int y = 0; y <= 5; y++) {
            boolean frame = Math.abs(horizontal) == 2 || y == 0 || y == 5;
            Location block = alongX ? base.clone().add(horizontal, y, 0) : base.clone().add(0, y, horizontal);
            block.getBlock().setType(frame ? Material.CRYING_OBSIDIAN : Material.PURPLE_STAINED_GLASS, false);
        }
        Location center = base.clone().add(0, 2.5, 0);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 170, 1.5, 2.1, 0.55, 0.16);
        world.spawnParticle(Particle.END_ROD, center, 45, 1.2, 1.8, 0.45, 0.025);
        world.playSound(base, Sound.BLOCK_END_PORTAL_SPAWN, 1.5F, 1.25F);
    }

    private void removeSomitaPortal(Location base) {
        if (base == null || base.getWorld() == null) return;
        for (int horizontal = -2; horizontal <= 2; horizontal++) for (int y = 0; y <= 5; y++) {
            for (Location block : new Location[]{base.clone().add(horizontal, y, 0),
                    base.clone().add(0, y, horizontal)}) {
                Material material = block.getBlock().getType();
                if (material == Material.CRYING_OBSIDIAN || material == Material.PURPLE_STAINED_GLASS) {
                    block.getBlock().setType(Material.AIR, false);
                }
            }
        }
    }

    private Location findSafeFloor(Location origin) {
        World world = origin.getWorld();
        if (world == null) return origin;
        int x = origin.getBlockX(), z = origin.getBlockZ();
        int start = Math.min(world.getMaxHeight() - 2, Math.max(world.getMinHeight() + 2, origin.getBlockY() + 5));
        for (int y = start; y >= world.getMinHeight() + 1; y--) {
            if (world.getBlockAt(x, y - 1, z).getType().isSolid()
                    && world.getBlockAt(x, y, z).isEmpty()
                    && world.getBlockAt(x, y + 1, z).isEmpty()) {
                return new Location(world, x + 0.5D, y, z + 0.5D, origin.getYaw(), 0.0F);
            }
        }
        return origin;
    }
}
