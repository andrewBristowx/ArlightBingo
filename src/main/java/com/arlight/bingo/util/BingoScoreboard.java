package com.arlight.bingo.util;

import com.arlight.core.api.ArlightCoreAPI;
import com.arlight.core.network.VisualScoreboardNetwork;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

/** Scoreboard Bukkit de respaldo + panel visual para ArlightChatClient. */
public class BingoScoreboard {
    private static final int MAX_LINES = 12;
    private final Scoreboard scoreboard;
    private final Objective objective;
    private final Map<String, Integer> pointsByTeam = new LinkedHashMap<>();
    private final Map<String, String> lastGoalByTeam = new LinkedHashMap<>();
    private final Set<UUID> viewers = new LinkedHashSet<>();
    private final Map<UUID, Scoreboard> previous = new HashMap<>();
    private boolean lobbyMode = true;
    private int lobbyPlayers;
    private int lobbyMaxPlayers = 10;
    private Integer lobbyCountdown;
    private long remainingMillis = -1L;
    private long totalMillis = -1L;

    public BingoScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = scoreboard.registerNewObjective("bingo", "dummy",
                MinigameIcons.STAR + ChatColor.GOLD + "" + ChatColor.BOLD + "BINGO");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    public void applyTo(Player player) {
        viewers.add(player.getUniqueId());
        previous.putIfAbsent(player.getUniqueId(), player.getScoreboard());
        applyPreferredBoard(player);
        syncVisual(player);
    }

    public void removeFrom(Player player) {
        viewers.remove(player.getUniqueId());
        Scoreboard old = previous.remove(player.getUniqueId());
        if (player.getScoreboard() == scoreboard) {
            player.setScoreboard(old == null ? Bukkit.getScoreboardManager().getMainScoreboard() : old);
        }
        try { ArlightCoreAPI.clearVisualScoreboard(player); } catch (Throwable ignored) { }
    }

    public void showLobby(int players, int maxPlayers, Integer countdownSecondsOrNull) {
        lobbyMode = true;
        lobbyPlayers = players;
        lobbyMaxPlayers = maxPlayers;
        lobbyCountdown = countdownSecondsOrNull;
        clearLines();
        int score = 10;
        setLine(MinigameIcons.PLAYERS + ChatColor.YELLOW + "Jugadores: "
                + ChatColor.WHITE + players + "/" + maxPlayers, score--);
        if (countdownSecondsOrNull != null) {
            setLine(MinigameIcons.CLOCK + ChatColor.AQUA + "Empieza en: "
                    + ChatColor.WHITE + countdownSecondsOrNull + "s", score--);
        } else setLine(MinigameIcons.INFO + ChatColor.GRAY + "Esperando jugadores...", score--);
        syncAllVisual();
    }

    public void resetProgress() {
        pointsByTeam.clear();
        lastGoalByTeam.clear();
        remainingMillis = totalMillis = -1L;
    }

    public void updateTeamProgress(String teamName, int points, String lastGoalName) {
        pointsByTeam.put(teamName, points);
        lastGoalByTeam.put(teamName, lastGoalName);
        showRunning();
    }

    public void setTimeRemaining(long remaining, long total) {
        remainingMillis = Math.max(0L, remaining);
        totalMillis = Math.max(1L, total);
        if (!lobbyMode) syncAllVisual();
    }

    public void showRunning() {
        lobbyMode = false;
        clearLines();
        int score = 15;
        setLine(MinigameIcons.STAR + ChatColor.AQUA + "" + ChatColor.BOLD + "Progreso:", score--);
        List<Map.Entry<String, Integer>> sorted = sortedTeams();
        if (sorted.isEmpty()) {
            setLine(MinigameIcons.INFO + ChatColor.GRAY + "Nadie completó nada todavía", score--);
        } else {
            int maxTeams = Math.max(1, MAX_LINES / 2);
            int shown = 0;
            for (Map.Entry<String, Integer> entry : sorted) {
                if (shown >= maxTeams || score < 0) break;
                String last = lastGoalByTeam.get(entry.getKey());
                setLine(MinigameIcons.CHECK + ChatColor.GREEN + entry.getKey() + ChatColor.WHITE
                        + ": " + ChatColor.YELLOW + entry.getValue() + "p", score--);
                if (last != null && score >= 0) setLine(ChatColor.GRAY + " » "
                        + ChatColor.ITALIC + shorten(last, 28), score--);
                shown++;
            }
        }
        syncAllVisual();
    }

    private List<Map.Entry<String, Integer>> sortedTeams() {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(pointsByTeam.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return sorted;
    }

    private void syncAllVisual() {
        for (UUID uuid : List.copyOf(viewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) syncVisual(player);
        }
    }

    private void applyPreferredBoard(Player player) {
        try {
            if (ArlightCoreAPI.shouldCoverVanillaSidebar(player)) {
                Scoreboard old = previous.get(player.getUniqueId());
                if (old != null && player.getScoreboard() == scoreboard) player.setScoreboard(old);
                return;
            }
        } catch (Throwable ignored) { }
        if (player.getScoreboard() != scoreboard) player.setScoreboard(scoreboard);
    }

    private void syncVisual(Player player) {
        applyPreferredBoard(player);
        try {
            if (lobbyMode) {
                String status = lobbyCountdown == null ? "Esperando jugadores" : "Empieza en " + lobbyCountdown + "s";
                ArlightCoreAPI.showVisualScoreboard(player, "bingo", "BINGO", "Pony0n Server",
                        lobbyMaxPlayers <= 0 ? 0.0D : lobbyPlayers / (double) lobbyMaxPlayers,
                        lobbyPlayers + "/" + lobbyMaxPlayers + " jugadores",
                        List.of(
                                new VisualScoreboardNetwork.Line("\uE307", "Jugadores", lobbyPlayers + "/" + lobbyMaxPlayers),
                                new VisualScoreboardNetwork.Line("\uE305", "Estado", status),
                                new VisualScoreboardNetwork.Line("\uE303", "Objetivo", "Completa más casillas")
                        ));
                return;
            }
            List<VisualScoreboardNetwork.Line> lines = new ArrayList<>();
            String time = remainingMillis < 0 ? "En curso" : formatTime(remainingMillis);
            lines.add(new VisualScoreboardNetwork.Line("\uE305", "Tiempo", time));
            List<Map.Entry<String, Integer>> sorted = sortedTeams();
            for (int i = 0; i < Math.min(3, sorted.size()); i++) {
                Map.Entry<String, Integer> e = sorted.get(i);
                lines.add(new VisualScoreboardNetwork.Line(i == 0 ? "\uE304" : "\uE308",
                        e.getKey(), e.getValue() + " puntos"));
            }
            if (sorted.isEmpty()) lines.add(new VisualScoreboardNetwork.Line("\uE303", "Progreso", "Aún sin puntos"));
            double progress = totalMillis > 0 ? Math.max(0.0D, Math.min(1.0D, remainingMillis / (double) totalMillis)) : -1.0D;
            ArlightCoreAPI.showVisualScoreboard(player, "bingo", "BINGO", "Click en el cartón para abrirlo",
                    progress, time, lines);
        } catch (Throwable ignored) {
            // ArlightCore es integración opcional; el scoreboard Bukkit sigue funcionando.
        }
    }

    private String formatTime(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private String shorten(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
    private void clearLines() { for (String entry : new ArrayList<>(scoreboard.getEntries())) scoreboard.resetScores(entry); }
    private void setLine(String text, int score) {
        String unique = text;
        while (scoreboard.getEntries().contains(unique)) unique += ChatColor.RESET;
        objective.getScore(unique).setScore(score);
    }
}
