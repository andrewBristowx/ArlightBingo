package com.arlight.bingo.util;

import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.game.BingoTeam;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;

/** Convierte la configuración Bukkit en NBT entendido por ArlightBosses. */
public final class BossTuning {
    public record BossProfile(double health, double damage, double followRange, double attackReach,
                              float phaseTwo, float phaseThree, float particleIntensity,
                              String bossBarColor, String bossBarOverlay) { }

    public record MinionProfile(double health, double damage, double followRange, double attackReach,
                                float particleIntensity) { }

    private BossTuning() { }

    public static BossProfile boss(JavaPlugin plugin, BingoGame game, String key) {
        int extraPlayers = Math.max(0, activePlayers(game) - 1);
        String base = "bosses." + key + ".";
        boolean scaling = plugin.getConfig().getBoolean("bosses.scaling.enabled", true);
        double health = plugin.getConfig().getDouble(base + "base-health", defaultHealth(key));
        double damage = plugin.getConfig().getDouble(base + "base-damage", defaultDamage(key));
        if (scaling) {
            health += extraPlayers * plugin.getConfig().getDouble("bosses.scaling.health-per-extra-player", 55.0D);
            damage += extraPlayers * plugin.getConfig().getDouble("bosses.scaling.damage-per-extra-player", 1.5D);
        }
        double follow = plugin.getConfig().getDouble(base + "follow-range",
                plugin.getConfig().getDouble("bosses.default-follow-range", 36.0D));
        double reach = plugin.getConfig().getDouble(base + "attack-reach", defaultReach(key));
        float phaseTwo = (float) plugin.getConfig().getDouble("bosses.phases.second-phase-health", 0.60D);
        float phaseThree = (float) plugin.getConfig().getDouble("bosses.phases.final-phase-health", 0.25D);
        float particles = (float) plugin.getConfig().getDouble(base + "particle-intensity",
                plugin.getConfig().getDouble("bosses.default-particle-intensity", 1.0D));
        String color = plugin.getConfig().getString(base + "bossbar-color", defaultColor(key));
        String overlay = plugin.getConfig().getString(base + "bossbar-overlay", "NOTCHED_10");
        return new BossProfile(Math.max(20.0D, health), Math.max(1.0D, damage),
                Math.max(8.0D, follow), Math.max(2.0D, reach),
                clamp01(phaseTwo), Math.min(clamp01(phaseThree), clamp01(phaseTwo) - 0.05F),
                Math.max(0.0F, particles), color, overlay);
    }

    public static MinionProfile minion(JavaPlugin plugin, BingoGame game) {
        int extraPlayers = Math.max(0, activePlayers(game) - 1);
        double health = plugin.getConfig().getDouble("dungeon-spawners.minion-base-health", 34.0D);
        double damage = plugin.getConfig().getDouble("dungeon-spawners.minion-base-damage", 6.0D);
        if (plugin.getConfig().getBoolean("dungeon-spawners.scaling-enabled", true)) {
            health += extraPlayers * plugin.getConfig().getDouble(
                    "dungeon-spawners.health-per-extra-player", 7.0D);
            damage += extraPlayers * plugin.getConfig().getDouble(
                    "dungeon-spawners.damage-per-extra-player", 0.65D);
        }
        return new MinionProfile(health, damage,
                plugin.getConfig().getDouble("dungeon-spawners.follow-range", 28.0D),
                plugin.getConfig().getDouble("dungeon-spawners.attack-reach", 2.6D),
                (float) plugin.getConfig().getDouble("dungeon-spawners.particle-intensity", 1.0D));
    }

    public static String bossNbt(BossProfile p) {
        return String.format(Locale.ROOT,
                ",Health:%.2ff,ArlightBingo:{MaxHealth:%.2fd,AttackDamage:%.2fd,FollowRange:%.2fd," +
                        "AttackReach:%.2fd,PhaseTwo:%.4ff,PhaseThree:%.4ff,ParticleIntensity:%.3ff," +
                        "BossBarColor:\"%s\",BossBarOverlay:\"%s\"}",
                p.health(), p.health(), p.damage(), p.followRange(), p.attackReach(),
                p.phaseTwo(), p.phaseThree(), p.particleIntensity(),
                safeWord(p.bossBarColor()), safeWord(p.bossBarOverlay()));
    }

    public static String minionNbt(MinionProfile p) {
        return String.format(Locale.ROOT,
                ",Health:%.2ff,ArlightMinion:{MaxHealth:%.2fd,AttackDamage:%.2fd,FollowRange:%.2fd," +
                        "AttackReach:%.2fd,ParticleIntensity:%.3ff}",
                p.health(), p.health(), p.damage(), p.followRange(), p.attackReach(), p.particleIntensity());
    }

    public static int activePlayers(BingoGame game) {
        int total = 0;
        if (game == null) return 1;
        for (BingoTeam team : game.getTeams()) {
            for (UUID uuid : team.getMembers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) total++;
            }
        }
        return Math.max(1, total);
    }

    private static double defaultHealth(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "nether" -> 450.0D;
            case "end" -> 600.0D;
            default -> 320.0D;
        };
    }

    private static double defaultDamage(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "nether" -> 15.0D;
            case "end" -> 18.0D;
            default -> 11.0D;
        };
    }

    private static double defaultReach(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "nether" -> 5.2D;
            case "end" -> 5.7D;
            default -> 4.7D;
        };
    }

    private static String defaultColor(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "nether" -> "RED";
            case "end" -> "PURPLE";
            default -> "GREEN";
        };
    }

    private static float clamp01(float value) { return Math.max(0.05F, Math.min(0.95F, value)); }
    private static String safeWord(String value) {
        if (value == null) return "PROGRESS";
        return value.replaceAll("[^A-Za-z0-9_]", "").toUpperCase(Locale.ROOT);
    }
}
