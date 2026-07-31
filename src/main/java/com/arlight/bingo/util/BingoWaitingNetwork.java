package com.arlight.bingo.util;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/** Envía la pantalla persistente de sala únicamente a participantes del Bingo. */
public final class BingoWaitingNetwork {
    public static final String CHANNEL = "arlightbingo:waiting";
    private final JavaPlugin plugin;

    public BingoWaitingNetwork(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void show(Player player, int players, int maxPlayers, Integer countdown) {
        if (!enabled() || player == null || !player.isOnline()) return;
        send(player, encode("SHOW", players, maxPlayers, countdown));
    }

    public void update(Player player, int players, int maxPlayers, Integer countdown) {
        if (!enabled() || player == null || !player.isOnline()) return;
        send(player, encode("UPDATE", players, maxPlayers, countdown));
    }

    public void update(Collection<? extends Player> audience, int players, int maxPlayers, Integer countdown) {
        if (audience == null) return;
        for (Player player : audience) update(player, players, maxPlayers, countdown);
    }

    public void hide(Player player) {
        if (player != null && player.isOnline()) send(player, "HIDE");
    }

    public void hide(Collection<? extends Player> audience) {
        if (audience == null) return;
        for (Player player : audience) hide(player);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("waiting-screen.enabled", true);
    }

    private String encode(String type, int players, int maxPlayers, Integer countdown) {
        return type + "|" + players + "|" + maxPlayers + "|" + (countdown == null ? -1 : countdown);
    }

    private void send(Player player, String command) {
        byte[] text = command.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream(text.length + 5);
        writeVarInt(payload, text.length);
        payload.writeBytes(text);
        player.sendPluginMessage(plugin, CHANNEL, payload.toByteArray());
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & -128) != 0) {
            out.write(value & 127 | 128);
            value >>>= 7;
        }
        out.write(value);
    }
}
