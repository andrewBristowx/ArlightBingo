package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** Comandos seguros para probar el ritual en la plantilla sin guardar la reja abierta. */
final class OverworldRitualTestCommands {
    private static final String PREFIX = "bingo template overworld ritual";
    private final BingoPlugin plugin;
    private final OverworldCampaignLandscape148 campaign;

    OverworldRitualTestCommands(BingoPlugin plugin, OverworldCampaignLandscape148 campaign) {
        this.plugin = plugin;
        this.campaign = campaign;
    }

    boolean matches(String raw) {
        String normalized = normalize(raw);
        return normalized.equals(PREFIX) || normalized.startsWith(PREFIX + " ");
    }

    void execute(CommandSender sender, String raw) {
        if (!sender.hasPermission("arlightbingo.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para probar el ritual.");
            return;
        }
        String[] parts = normalize(raw).split("\\s+");
        String action = parts.length >= 5 ? parts[4] : "status";
        switch (action) {
            case "start", "iniciar" -> campaign.debugRitualTestStart(sender);
            case "give", "dar" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Este comando debe ejecutarlo un jugador.");
                    return;
                }
                campaign.debugRitualTestGive(player);
            }
            case "open", "abrir" -> campaign.debugRitualTestOpen(sender);
            case "reset", "restore", "restaurar", "cerrar" -> campaign.debugRitualTestReset(sender);
            case "status", "estado" -> campaign.debugRitualTestStatus(sender);
            default -> sender.sendMessage(ChatColor.YELLOW
                    + "Uso: /bingo template overworld ritual <start|give|open|reset|status>");
        }
    }

    List<String> completions(String raw) {
        String normalized = normalize(raw);
        if (!normalized.startsWith("bingo template overworld")) return List.of();
        String[] parts = normalized.isBlank() ? new String[0] : normalized.split("\\s+");
        if (parts.length == 3) return List.of("ritual");
        if (parts.length == 4 && "ritual".startsWith(parts[3])) return List.of("ritual");
        if (parts.length == 4 && parts[3].equals("ritual")) {
            return List.of("start", "give", "open", "reset", "status");
        }
        if (parts.length == 5 && parts[3].equals("ritual")) {
            return List.of("start", "give", "open", "reset", "status").stream()
                    .filter(value -> value.startsWith(parts[4])).toList();
        }
        return List.of();
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized.replaceAll("\\s+", " ");
    }
}
