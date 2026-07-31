package com.arlight.bingo.util;

import com.arlight.bingo.game.BingoCard;
import com.arlight.bingo.game.BingoGoal;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Sincroniza el cartón del Bingo con el mod cliente opcional ArlightChatClient.
 * El protocolo usa un único String UTF-8 para mantener compatibilidad con el
 * canal de plugin de Bukkit y los CustomPacketPayload opcionales de NeoForge.
 */
public final class CardNetwork {
    public static final String CHANNEL = "arlightbingo:card";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final JavaPlugin plugin;

    public CardNetwork(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void sync(Player player, BingoCard card) {
        if (player == null || card == null) return;

        StringBuilder goals = new StringBuilder();
        for (int i = 0; i < card.getGoals().size(); i++) {
            if (i > 0) goals.append(';');
            BingoGoal goal = card.getGoals().get(i);
            String iconKey = plugin.getConfig().getBoolean("client-card-ui.custom-objective-icons", true)
                    ? goal.getIconKey() : "";
            goals.append(encode(goal.getId())).append(',')
                    .append(goal.getType().name()).append(',')
                    .append(encode(goal.getTarget())).append(',')
                    .append(encode(goal.getDisplayName())).append(',')
                    .append(encode(iconKey)).append(',')
                    .append(goal.getProgress()).append(',')
                    .append(goal.getAmountRequired()).append(',')
                    .append(goal.isCompleted() ? '1' : '0');
        }
        send(player, "SET2|" + card.getSize() + "|" + goals);
    }

    public void open(Player player) {
        send(player, "OPEN");
    }

    public void toggle(Player player) {
        send(player, "TOGGLE");
    }

    public void hide(Player player) {
        send(player, "HIDE");
    }

    public void clear(Player player) {
        send(player, "CLEAR");
    }

    private void send(Player player, String message) {
        if (player == null || !player.isOnline()) return;
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream(text.length + 5);
        writeVarInt(payload, text.length);
        payload.writeBytes(text);
        player.sendPluginMessage(plugin, CHANNEL, payload.toByteArray());
    }

    private static String encode(String value) {
        if (value == null || value.isEmpty()) return "";
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & -128) != 0) {
            out.write(value & 127 | 128);
            value >>>= 7;
        }
        out.write(value);
    }
}
