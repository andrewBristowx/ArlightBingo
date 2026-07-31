package com.arlight.bingo.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.function.BooleanSupplier;

/**
 * Boss bar compartida del tiempo restante. Mantiene una única instancia y solo envía
 * cambios reales al cliente para evitar parpadeos y paquetes redundantes.
 */
public class MatchBossBar {

    private final BooleanSupplier enabled;
    private BossBar bossBar;
    private long lastDisplayedSecond = Long.MIN_VALUE;
    private double lastProgress = Double.NaN;
    private BarColor lastColor;

    public MatchBossBar(BooleanSupplier enabled) {
        this.enabled = enabled;
    }

    private boolean isEnabled() {
        return enabled != null && enabled.getAsBoolean();
    }

    public void start() {
        // El panel lateral ya muestra el tiempo; por defecto esta barra vanilla queda apagada.
        stop();
        if (!isEnabled()) return;
        bossBar = Bukkit.createBossBar("Bingo", BarColor.GREEN, BarStyle.SOLID);
        bossBar.setVisible(true);
        lastDisplayedSecond = Long.MIN_VALUE;
        lastProgress = Double.NaN;
        lastColor = null;
    }

    public void addPlayer(Player player) {
        if (bossBar != null && player != null && !bossBar.getPlayers().contains(player)) {
            bossBar.addPlayer(player);
        }
    }

    public void removePlayer(Player player) {
        if (bossBar != null && player != null && bossBar.getPlayers().contains(player)) {
            bossBar.removePlayer(player);
        }
    }

    /** Actualiza título/progreso solo cuando el dato visible realmente cambió. */
    public void update(long remainingMillis, long totalMillis) {
        if (bossBar == null) return;

        long remainingSeconds = Math.max(0L, (remainingMillis + 999L) / 1000L);
        if (remainingSeconds != lastDisplayedSecond) {
            long minutes = remainingSeconds / 60L;
            long seconds = remainingSeconds % 60L;
            bossBar.setTitle(ChatColor.GOLD + "Bingo "
                    + ChatColor.WHITE + "- Tiempo restante: "
                    + ChatColor.YELLOW + String.format("%02d:%02d", minutes, seconds));
            lastDisplayedSecond = remainingSeconds;
        }

        double progress = totalMillis > 0 ? clamp((double) remainingMillis / totalMillis) : 0.0D;
        if (Double.isNaN(lastProgress) || Math.abs(progress - lastProgress) >= 0.0005D) {
            bossBar.setProgress(progress);
            lastProgress = progress;
        }

        BarColor color = progress < 0.2D ? BarColor.RED
                : progress < 0.5D ? BarColor.YELLOW : BarColor.GREEN;
        if (color != lastColor) {
            bossBar.setColor(color);
            lastColor = color;
        }
    }

    private double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public void stop() {
        if (bossBar != null) {
            bossBar.setVisible(false);
            bossBar.removeAll();
            bossBar = null;
        }
        lastDisplayedSecond = Long.MIN_VALUE;
        lastProgress = Double.NaN;
        lastColor = null;
    }
}
