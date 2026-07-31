package com.arlight.bingo.util;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/** Sends the preparation overlay state to the optional ArlightChatClient mod. */
public final class PreparationNetwork {
    public static final String CHANNEL = "arlightbingo:preparation";
    private final JavaPlugin plugin;

    public PreparationNetwork(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void show(Collection<? extends Player> players, String phase, int tip, double progress) {
        send(players, "SHOW|" + clean(phase) + "|" + Math.floorMod(tip, 5) + "|" + clamp(progress));
    }

    public void countdown(Collection<? extends Player> players, int seconds) {
        send(players, "COUNTDOWN|" + Math.max(0, seconds));
    }

    public void start(Collection<? extends Player> players) {
        send(players, "START");
    }

    public void hide(Collection<? extends Player> players) {
        send(players, "HIDE");
    }

    public void noticeShow(Collection<? extends Player> players, ConfigManager config) {
        if (config == null) return;
        String position = clean(config.getWorldPreparationNoticePosition()).toUpperCase();
        send(players, "NOTICE_SHOW|" + position
                + "|" + config.getWorldPreparationNoticeXOffset()
                + "|" + config.getWorldPreparationNoticeYOffset()
                + "|" + config.getWorldPreparationNoticeScale()
                + "|" + config.getWorldPreparationNoticeFadeInTicks()
                + "|" + config.getWorldPreparationNoticeFadeOutTicks());
    }

    public void noticeHide(Collection<? extends Player> players) {
        send(players, "NOTICE_HIDE");
    }

    private void send(Collection<? extends Player> players, String message) {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream(text.length + 5);
        writeVarInt(payload, text.length);
        payload.writeBytes(text);
        byte[] bytes = payload.toByteArray();
        for (Player player : players) {
            if (player != null && player.isOnline()) {
                player.sendPluginMessage(plugin, CHANNEL, bytes);
            }
        }
    }

    private static String clean(String value) {
        return value == null ? "Preparando arena" : value.replace('|', ' ').replace('\n', ' ');
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & -128) != 0) {
            out.write(value & 127 | 128);
            value >>>= 7;
        }
        out.write(value);
    }
}
