package com.arlight.bingo.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Locale;

/**
 * Protocolo opcional para la Somita con skin slim renderizada por ArlightChatClient.
 * Cada jugador recibe su propia guía y sus propios efectos, sin entidades persistentes.
 */
public final class SomitaGuideNetwork {
    public static final String CHANNEL = "arlightbingo:somita";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public enum Variant {
        OVERWORLD, NETHER, END, CELEBRATION;

        public static Variant parse(String value) {
            if (value == null) return OVERWORLD;
            try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return OVERWORLD; }
        }
    }

    public enum Animation {
        IDLE, WAVE, POINT, WALK, LOOK, BOW,
        CELEBRATE_CUTE, CELEBRATE_ELEGANT,
        VANISH, HOLD, CROSS_ARMS;

        public static Animation parse(String value) {
            if (value == null) return IDLE;
            String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (normalized.equals("CROSSARMS")) normalized = "CROSS_ARMS";
            if (normalized.equals("CELEBRATE")) normalized = "CELEBRATE_CUTE";
            try { return valueOf(normalized); }
            catch (IllegalArgumentException ignored) { return IDLE; }
        }
    }

    public enum Effect {
        OVERWORLD_APPEAR, OVERWORLD_POINT,
        NETHER_APPEAR, NETHER_LOCK,
        END_APPEAR, END_ALTAR,
        CELEBRATION_BURST,
        VANISH, WAVE_SPARKLE, HOLD_ORBIT;

        public static Effect parse(String value) {
            if (value == null) return OVERWORLD_APPEAR;
            String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            try { return valueOf(normalized); }
            catch (IllegalArgumentException ignored) { return OVERWORLD_APPEAR; }
        }
    }

    private final JavaPlugin plugin;

    public SomitaGuideNetwork(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void show(Player player, String sceneId, Variant variant, Animation animation,
                     Location location, int durationTicks, String dialogue) {
        if (player == null || !player.isOnline() || location == null || location.getWorld() == null) return;
        sendSettings(player);
        String command = String.join("|",
                "SHOW",
                clean(sceneId),
                variant.name(),
                animation.name(),
                decimal(location.getX()),
                decimal(location.getY()),
                decimal(location.getZ()),
                decimal(location.getYaw()),
                Integer.toString(Math.max(20, durationTicks)),
                encode("✦ Somita ✦"),
                encode(dialogue == null ? "" : dialogue));
        send(player, command);
    }

    public void animate(Player player, Animation animation, int durationTicks, String dialogue) {
        if (player == null || !player.isOnline()) return;
        send(player, "ANIMATE|" + animation.name() + "|" + Math.max(1, durationTicks)
                + "|" + encode(dialogue == null ? "" : dialogue));
    }

    public void move(Player player, Location destination, int durationTicks, Animation animation) {
        if (player == null || !player.isOnline() || destination == null || destination.getWorld() == null) return;
        send(player, String.join("|", "MOVE",
                decimal(destination.getX()), decimal(destination.getY()), decimal(destination.getZ()),
                decimal(destination.getYaw()), Integer.toString(Math.max(1, durationTicks)), animation.name()));
    }

    public void effect(Player player, Effect effect, int durationTicks) {
        effect(player, effect, durationTicks, null);
    }

    public void effect(Player player, Effect effect, int durationTicks, Location target) {
        if (player == null || !player.isOnline() || effect == null) return;
        String x = "", y = "", z = "";
        if (target != null && target.getWorld() != null && target.getWorld() == player.getWorld()) {
            x = decimal(target.getX());
            y = decimal(target.getY());
            z = decimal(target.getZ());
        }
        send(player, String.join("|", "EFFECT", effect.name(),
                Integer.toString(Math.max(1, durationTicks)), x, y, z));
    }

    public void hide(Player player) {
        if (player != null && player.isOnline()) send(player, "HIDE");
    }

    public void clear(Player player) {
        if (player != null && player.isOnline()) send(player, "CLEAR");
    }

    public void hide(Collection<? extends Player> players) {
        if (players == null) return;
        for (Player player : players) hide(player);
    }

    public void clearAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) clear(player);
    }

    private void sendSettings(Player player) {
        boolean enabled = plugin.getConfig().getBoolean("somita.effects.enabled", true);
        String intensity = plugin.getConfig().getString("somita.effects.intensity", "normal");
        if (intensity == null || intensity.isBlank()) intensity = "normal";
        int maxParticles = Math.max(0, Math.min(96,
                plugin.getConfig().getInt("somita.effects.maximum-particles", 36)));
        int appearance = Math.max(1, Math.min(60,
                plugin.getConfig().getInt("somita.animations.appearance-ticks", 14)));
        int vanish = Math.max(1, Math.min(60,
                plugin.getConfig().getInt("somita.animations.vanish-ticks", 18)));
        send(player, String.join("|", "SETTINGS", Boolean.toString(enabled),
                clean(intensity), Integer.toString(maxParticles),
                Integer.toString(appearance), Integer.toString(vanish)));
    }

    private void send(Player player, String command) {
        byte[] text = command.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream(text.length + 5);
        writeVarInt(payload, text.length);
        payload.writeBytes(text);
        player.sendPluginMessage(plugin, CHANNEL, payload.toByteArray());
    }

    private static String encode(String value) {
        if (value == null || value.isEmpty()) return "";
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String clean(String value) {
        return value == null ? "scene" : value.replace('|', '_').replace('\n', ' ').trim();
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & -128) != 0) {
            out.write(value & 127 | 128);
            value >>>= 7;
        }
        out.write(value);
    }
}
