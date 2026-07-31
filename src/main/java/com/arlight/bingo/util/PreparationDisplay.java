package com.arlight.bingo.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pantalla completa para participantes y cartel compacto para quienes no participan
 * mientras Chunky prepara una arena.
 */
public final class PreparationDisplay {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final PreparationNetwork network;
    private final Set<UUID> viewers = new LinkedHashSet<>();
    private final Set<UUID> noticeViewers = new LinkedHashSet<>();
    private BukkitTask overlayTask;
    private BukkitTask noticeTask;
    private boolean noticeActive;
    private int tip;
    private String phase = "Iniciando";
    private double progress = 0.02;
    private final List<String> tips = List.of(
            "Completa los objetivos que aparecen en tu cartón",
            "Derrota al guardián del Overworld para abrir el Nether",
            "La mazmorra del Nether desbloquea el acceso al End",
            "Los cofres pueden contener objetos útiles para tu cartón",
            "Después del dragón, conquista la torre de recompensas"
    );

    public PreparationDisplay(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.network = new PreparationNetwork(plugin);
    }

    public void start(int slot, Collection<? extends Player> audience, boolean showNotice) {
        stopOverlayTask();
        hideNotice();
        setAudience(audience);
        progress = 0.02;
        phase = "Creando arena " + slot;
        tip = 0;

        noticeActive = showNotice
                && config.isWorldPreparationNoticeEnabled()
                && config.isWorldPreparationNoticeShowToNonParticipants();
        if (noticeActive) {
            syncNoticeAudience();
            noticeTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    this::syncNoticeAudience, 40L, 40L);
        }

        if (viewers.isEmpty()) return;
        broadcastOverlay();
        overlayTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int currentTip = tip++ % tips.size();
            network.show(onlineViewers(), phase, currentTip, progress);
        }, 0L, 100L);
    }

    public void phase(String name, int completedDimensions) {
        phase = "Generando " + name;
        progress = Math.max(0.08, Math.min(0.92, 0.08 + completedDimensions * 0.28));
        broadcastOverlay();
    }

    public void dimensionComplete(int completedDimensions) {
        progress = Math.max(progress, Math.min(0.94, 0.08 + completedDimensions * 0.28));
        broadcastOverlay();
    }

    public void complete(boolean keepVisibleForCountdown) {
        stopOverlayTask();
        hideNotice();
        progress = 1.0;
        network.show(onlineViewers(), "Arena lista", tip, 1.0);
        if (!keepVisibleForCountdown) {
            Bukkit.getScheduler().runTaskLater(plugin, this::hide, 200L);
        }
    }

    public void fail(String reason) {
        stopOverlayTask();
        hideNotice();
        network.show(onlineViewers(), "Error al preparar la arena", tip, progress);
        Bukkit.getScheduler().runTaskLater(plugin, this::hide, 100L);
    }

    public void shutdown() {
        stopOverlayTask();
        hideNotice();
        network.hide(onlineViewers());
        viewers.clear();
    }

    private void setAudience(Collection<? extends Player> audience) {
        viewers.clear();
        if (audience == null) return;
        for (Player player : audience) {
            if (player != null) viewers.add(player.getUniqueId());
        }
    }

    private List<Player> onlineViewers() {
        List<Player> online = new ArrayList<>();
        for (UUID uuid : viewers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) online.add(player);
        }
        return online;
    }

    private void syncNoticeAudience() {
        if (!noticeActive) return;

        Set<UUID> desired = new LinkedHashSet<>();
        List<Player> added = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (viewers.contains(uuid)) continue;
            desired.add(uuid);
            if (!noticeViewers.contains(uuid)) added.add(player);
        }

        List<Player> removed = new ArrayList<>();
        for (UUID uuid : noticeViewers) {
            if (desired.contains(uuid)) continue;
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) removed.add(player);
        }

        if (!removed.isEmpty()) network.noticeHide(removed);
        if (!added.isEmpty()) network.noticeShow(added, config);

        noticeViewers.clear();
        noticeViewers.addAll(desired);
    }

    private void broadcastOverlay() {
        if (!viewers.isEmpty()) {
            network.show(onlineViewers(), phase, tip % tips.size(), progress);
        }
    }

    private void hide() {
        stopOverlayTask();
        hideNotice();
        network.hide(onlineViewers());
        viewers.clear();
    }

    private void hideNotice() {
        noticeActive = false;
        if (noticeTask != null) noticeTask.cancel();
        noticeTask = null;

        if (!noticeViewers.isEmpty()) {
            List<Player> online = new ArrayList<>();
            for (UUID uuid : noticeViewers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) online.add(player);
            }
            network.noticeHide(online);
            noticeViewers.clear();
        }
    }

    private void stopOverlayTask() {
        if (overlayTask != null) overlayTask.cancel();
        overlayTask = null;
    }
}
