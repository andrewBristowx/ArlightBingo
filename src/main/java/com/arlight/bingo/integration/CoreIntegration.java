package com.arlight.bingo.integration;

import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.game.BingoTeam;
import com.arlight.bingo.game.GameState;
import com.arlight.bingo.gui.BingoCardItem;
import com.arlight.bingo.gui.LobbyItems;
import com.arlight.bingo.util.MinigameIcons;
import com.arlight.core.api.ArlightCoreAPI;
import com.arlight.core.api.MinigameProvider;
import com.arlight.core.api.MinigameStatus;
import com.arlight.core.api.PodiumEntry;
import com.arlight.core.api.PodiumStat;
import com.arlight.core.api.UniversalPodiumResult;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Integracion OPCIONAL con ArlightCore: registra el Bingo en el item selector de minijuegos
 * y otorga XP a los ganadores. Esta clase solo se carga (se referencia) desde BingoPlugin
 * DESPUES de comprobar que el plugin ArlightCore esta instalado, asi que si no lo esta,
 * el resto del plugin nunca toca las clases de ArlightCoreAPI y no hay ningun error.
 */
public final class CoreIntegration {

    private CoreIntegration() {
    }

    public static void register(BingoGame game) {
        game.setSessionBridge(new BingoGame.SessionBridge() {
            @Override
            public boolean begin(Player player) {
                return ArlightCoreAPI.beginMinigameSession(player, "bingo");
            }

            @Override
            public void end(Player player) {
                ArlightCoreAPI.endMinigameSession(player);
            }

            @Override
            public void cancel(Player player) {
                ArlightCoreAPI.cancelMinigameSession(player);
            }

            @Override
            public void started(Player player) {
                ArlightCoreAPI.markMinigameStarted(player);
            }

            @Override
            public boolean usesExternalInventories() {
                return ArlightCoreAPI.isExternalInventoryManagementActive();
            }
        });

        ArlightCoreAPI.registerMinigame(new MinigameProvider() {
            @Override
            public String getId() {
                return "bingo";
            }

            @Override
            public String getDisplayName() {
                return MinigameIcons.STAR + ChatColor.GOLD + "Bingo";
            }

            @Override
            public ItemStack getIcon() {
                return new ItemStack(Material.COMPASS);
            }

            @Override
            public MinigameStatus getStatus() {
                return game.getState() == GameState.WAITING ? MinigameStatus.WAITING : MinigameStatus.IN_PROGRESS;
            }

            @Override
            public void join(Player player) {
                game.addPlayer(player, null);
            }

            @Override
            public void leave(Player player) {
                if (game.getState() == GameState.RUNNING || game.getState() == GameState.TIEBREAK) {
                    game.disqualifyPlayer(player, ChatColor.RED + player.getName()
                            + " abandonó el Bingo y quedó descalificado.");
                } else {
                    game.removePlayer(player);
                }
            }

            @Override
            public void handleDisconnect(Player player) {
                game.disqualifyIfInMatch(player);
            }

            @Override
            public void cleanupAfterRecovery(Player player) {
                // En Arclight el inventario del jugador puede volver a sincronizarse varios
                // ticks después del PlayerJoinEvent. Por eso hacemos una limpieza inmediata
                // y dos comprobaciones posteriores. Las comprobaciones son seguras: solo
                // reconocen objetos marcados por Bingo o sus copias antiguas exactas.
                cleanupRecoveredItems(game, player, "inmediata");
                Bukkit.getScheduler().runTaskLater(game.getPlugin(),
                        () -> cleanupRecoveredItems(game, player, "5 ticks"), 5L);
                Bukkit.getScheduler().runTaskLater(game.getPlugin(),
                        () -> cleanupRecoveredItems(game, player, "20 ticks"), 20L);
            }

            @Override
            public boolean isPlaying(Player player) {
                return game.getTeamOf(player) != null;
            }

            @Override
            public int getCurrentPlayers() {
                return game.getTeams().stream().mapToInt(team -> team.getMembers().size()).sum();
            }

            @Override
            public int getMaxPlayers() {
                return game.getConfigManager().getMaxPlayersScoreboard();
            }
        });

        game.setWinListener(winners -> {
            for (BingoTeam team : winners) {
                for (UUID uuid : team.getMembers()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) ArlightCoreAPI.addWinXp(p);
                }
            }

            List<BingoTeam> ranking = new ArrayList<>(game.getTeams());
            ranking.sort(Comparator
                    .<BingoTeam>comparingInt(team -> winners.contains(team) ? 1 : 0).reversed()
                    .thenComparing(Comparator.comparingInt(CoreIntegration::completedGoals).reversed())
                    .thenComparing(BingoTeam::getName, String.CASE_INSENSITIVE_ORDER));

            List<UUID> individualRanking = ranking.stream()
                    .flatMap(team -> team.getMembers().stream())
                    .toList();
            Map<UUID, UniversalPodiumResult> results = new HashMap<>();
            for (UUID viewer : individualRanking) {
                results.put(viewer, createBingoPodium(game, individualRanking, viewer));
            }

            long delay = Math.max(1, game.getConfigManager().getCelebrationSeconds()) * 20L + 15L;
            Bukkit.getScheduler().runTaskLater(game.getPlugin(), () -> results.forEach((uuid, result) -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    ArlightCoreAPI.showUniversalPodium(player, result);
                }
            }), delay);
        });
    }


    private static UniversalPodiumResult createBingoPodium(BingoGame game, List<UUID> ranking, UUID viewer) {
        List<PodiumEntry> top = new ArrayList<>();
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            UUID id = ranking.get(i);
            BingoTeam team = teamFor(game, id);
            int completed = completedGoals(team);
            int total = team == null || team.getCard() == null ? 0 : team.getCard().getGoals().size();
            top.add(new PodiumEntry(i + 1, id, playerName(id), null, List.of(
                    new PodiumStat("check", "Objetivos", completed + "/" + total),
                    new PodiumStat("star", "Puntos", String.valueOf(completed)),
                    new PodiumStat("clock", "Tiempo restante", remaining(game))
            )));
        }
        BingoTeam viewerTeam = teamFor(game, viewer);
        int completed = completedGoals(viewerTeam);
        int total = viewerTeam == null || viewerTeam.getCard() == null ? 0 : viewerTeam.getCard().getGoals().size();
        int place = ranking.indexOf(viewer) + 1;
        List<PodiumStat> stats = List.of(
                new PodiumStat("trophy", "Puesto", place <= 0 ? "—" : "#" + place),
                new PodiumStat("check", "Objetivos", completed + "/" + total),
                new PodiumStat("star", "Puntos", String.valueOf(completed)),
                new PodiumStat("clock", "Tiempo restante", remaining(game))
        );
        return new UniversalPodiumResult("bingo", "Bingo", "campaign",
                "Objetivos completados en la campaña", 300, "bingo join", place, top, stats);
    }

    private static BingoTeam teamFor(BingoGame game, UUID uuid) {
        return game.getTeams().stream().filter(team -> team.getMembers().contains(uuid)).findFirst().orElse(null);
    }

    private static int completedGoals(BingoTeam team) {
        return team == null || team.getCard() == null ? 0 : team.getCard().countCompleted();
    }

    private static String remaining(BingoGame game) {
        long seconds = Math.max(0L, (game.getEndTimeMillis() - System.currentTimeMillis()) / 1000L);
        return String.format("%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private static String playerName(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) return player.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }


    public static boolean configureArenaWorlds(String groupName, String overworld,
                                               String nether, String end) {
        return ArlightCoreAPI.configureMinigameWorlds(groupName, overworld, nether, end);
    }

    public static CompletableFuture<Boolean> configureArenaWorldsStable(String groupName,
                                                                        String overworld,
                                                                        String nether,
                                                                        String end) {
        return ArlightCoreAPI.configureMinigameWorldsStable(groupName, overworld, nether, end);
    }

    public static boolean configureInventoryGroup(String groupName, String overworld,
                                                  String nether, String end) {
        return ArlightCoreAPI.configureInventoryGroup(groupName, overworld, nether, end);
    }

    public static boolean configurePortalLinks(String overworld, String nether, String end) {
        return ArlightCoreAPI.configurePortalLinks(overworld, nether, end);
    }

    public static boolean usesExternalInventories() {
        return ArlightCoreAPI.isExternalInventoryManagementActive();
    }

    public static boolean coreHandlesNetherPortals() {
        return ArlightCoreAPI.isMultiverseNetherPortalsActive();
    }

    private static void cleanupRecoveredItems(BingoGame game, Player player, String phase) {
        if (!player.isOnline() || game.getTeamOf(player) != null) return;

        ItemStack[] contents = player.getInventory().getContents();
        int removed = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (LobbyItems.isTemporaryLobbyItem(game.getPlugin(), item)
                    || BingoCardItem.isBingoCardItem(game.getPlugin(), item)) {
                player.getInventory().setItem(slot, null);
                removed++;
            }
        }

        if (removed > 0) player.updateInventory();
        game.getPlugin().getLogger().info("Recuperación de " + player.getName()
                + " (" + phase + "): " + removed
                + " objeto(s) temporal(es) de Bingo eliminado(s).");
    }
}
