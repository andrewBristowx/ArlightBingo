package com.arlight.bingo.game;

import com.arlight.bingo.gui.BingoCardItem;
import com.arlight.bingo.end.EndEncounterManager;
import com.arlight.bingo.dungeon.DimensionDungeonManager;
import com.arlight.bingo.util.ArenaWorldManager;
import com.arlight.bingo.util.BingoScoreboard;
import com.arlight.bingo.util.ConfigManager;
import com.arlight.bingo.util.MaterialResolver;
import com.arlight.bingo.util.MatchBossBar;
import com.arlight.bingo.util.PreparationNetwork;
import com.arlight.bingo.util.CardNetwork;
import com.arlight.bingo.util.BingoWaitingNetwork;
import com.arlight.bingo.util.MinigameIcons;
import com.arlight.bingo.util.StorageManager;
import com.arlight.bingo.util.WorldBorderManager;
import com.arlight.bingo.util.WorldPoolManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class BingoGame {

    private final JavaPlugin plugin;
    private final PreparationNetwork preparationNetwork;
    private final CardNetwork cardNetwork;
    private final BingoWaitingNetwork waitingNetwork;
    private final ConfigManager configManager;

    private GameState state = GameState.WAITING;
    private final Map<String, BingoTeam> teams = new LinkedHashMap<>();
    private final Map<UUID, String> playerTeamName = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, SavedInventory> savedInventories = new HashMap<>();
    private final Set<String> claimedGlobalBosses = new HashSet<>();

    private BukkitTask timerTask;
    private BukkitTask autostartTask;
    private BukkitTask inventoryScanTask;
    private BukkitTask tiebreakTask;
    private int autostartSecondsRemaining;
    private long endTimeMillis;
    private long matchDurationMillis;
    private String currentArenaWorld;
    private final Set<UUID> tiebreakAlive = new LinkedHashSet<>();
    private final Map<UUID, String> tiebreakTeams = new HashMap<>();
    private boolean tiebreakCombatActive;
    private int tiebreakFloorY;
    private boolean arenaPreparing;
    private boolean worldHandoffInProgress;
    private long worldHandoffToken;

    private StorageManager storageManager;
    private ArenaWorldManager multiverse;
    private WorldBorderManager worldBorderManager;
    private EndEncounterManager endEncounterManager;
    private DimensionDungeonManager dimensionDungeonManager;
    private WorldPoolManager worldPoolManager;
    private List<String> currentArenaExtraWorlds = new ArrayList<>();

    public void setWorldBorderManager(WorldBorderManager worldBorderManager) {
        this.worldBorderManager = worldBorderManager;
    }

    public void setEndEncounterManager(EndEncounterManager endEncounterManager) {
        this.endEncounterManager = endEncounterManager;
    }

    public void setDimensionDungeonManager(DimensionDungeonManager manager) { this.dimensionDungeonManager = manager; }
    public boolean isNetherUnlocked() { return dimensionDungeonManager == null || dimensionDungeonManager.isNetherUnlocked(); }
    public boolean isEndUnlocked() { return dimensionDungeonManager == null || dimensionDungeonManager.isEndUnlocked(); }
    public org.bukkit.Location getFixedNetherArrival() { return dimensionDungeonManager == null ? null : dimensionDungeonManager.getNetherArrival(); }
    public org.bukkit.Location getFixedOverworldReturn() { return dimensionDungeonManager == null ? null : dimensionDungeonManager.getOverworldReturn(); }

    public void setWorldPoolManager(WorldPoolManager worldPoolManager) {
        this.worldPoolManager = worldPoolManager;
    }

    public org.bukkit.Location getEndEncounterArrival(World world) {
        return endEncounterManager == null ? null : endEncounterManager.getSafeArrival(world);
    }
    private BingoScoreboard scoreboard;
    private final MatchBossBar bossBar;
    private WinListener winListener; // opcional: lo setea BingoPlugin si detecta ArlightCore instalado
    private SessionBridge sessionBridge;

    /** Callback desacoplado para avisar quien gano, sin que este archivo dependa de ningun otro plugin. */
    public interface WinListener {
        void onWin(List<BingoTeam> winners);
    }

    /** Puente opcional para que ArlightCore administre la sesión global. */
    public interface SessionBridge {
        boolean begin(Player player);
        void end(Player player);
        void cancel(Player player);
        void started(Player player);
        boolean usesExternalInventories();
    }

    public void setWinListener(WinListener winListener) {
        this.winListener = winListener;
    }

    public void setSessionBridge(SessionBridge sessionBridge) {
        this.sessionBridge = sessionBridge;
    }

    private boolean usesExternalInventories() {
        return sessionBridge != null && sessionBridge.usesExternalInventories();
    }

    public BingoGame(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.bossBar = new MatchBossBar(() -> plugin.getConfig().getBoolean("match-bossbar.enabled", false));
        this.preparationNetwork = new PreparationNetwork(plugin);
        this.cardNetwork = new CardNetwork(plugin);
        this.waitingNetwork = new BingoWaitingNetwork(plugin);
        this.configManager = configManager;
    }

    public void setStorageManager(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public void setArenaWorldManager(ArenaWorldManager multiverse) {
        this.multiverse = multiverse;
    }

    public ArenaWorldManager getArenaWorldManager() {
        return multiverse;
    }

    public void setScoreboard(BingoScoreboard scoreboard) {
        this.scoreboard = scoreboard;
    }

    public String getCurrentArenaWorld() {
        return currentArenaWorld;
    }

    public List<String> getCurrentArenaExtraWorlds() {
        return currentArenaExtraWorlds.isEmpty()
                ? new ArrayList<>(configManager.getExtraArenaWorlds())
                : new ArrayList<>(currentArenaExtraWorlds);
    }

    private void autosave() {
        if (storageManager != null) {
            storageManager.save(this);
        }
    }

    /**
     * Teletransporta al jugador y, unos ticks despues, le vuelve a aplicar el scoreboard.
     * Es necesario porque al cambiar de mundo/dimension el cliente a veces deja de mostrar
     * el sidebar hasta que se le vuelve a enviar, aunque el objeto Scoreboard del servidor
     * siga siendo el mismo.
     */
    private boolean teleportAndKeepScoreboard(Player player, String world) {
        if (multiverse == null) return false;
        boolean teleported = multiverse.teleportToWorld(player, world);
        if (teleported && scoreboard != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    scoreboard.applyTo(player);
                }
            }, configManager.isSafeWorldHandoffEnabled()
                    ? configManager.getSafeWorldHandoffSettleTicks() : 2L);
        }
        return teleported;
    }

    public GameState getState() {
        return state;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public boolean isTeamMode() {
        return configManager.isTeamMode();
    }

    public Collection<BingoTeam> getTeams() {
        return teams.values();
    }

    /** Devuelve true únicamente para jugadores inscritos en la sesión actual. */
    public boolean isParticipant(Player player) {
        return player != null && playerTeamName.containsKey(player.getUniqueId());
    }

    /** Mensaje operativo visible solo para la cola/partida de Bingo. */
    public void sendToParticipants(String message) {
        for (Player player : activePlayers()) player.sendMessage(message);
    }

    /** Mensaje privado del equipo, usado por /bingo teamchat. */
    public void sendToTeam(BingoTeam team, String message) {
        if (team == null) return;
        for (UUID uuid : team.getMembers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) player.sendMessage(message);
        }
    }

    public void openClientCard(Player player) {
        BingoTeam team = getTeamOf(player);
        if (team == null || team.getCard() == null) return;
        cardNetwork.sync(player, team.getCard());
        cardNetwork.open(player);
    }

    public void toggleClientCard(Player player) {
        BingoTeam team = getTeamOf(player);
        if (team == null || team.getCard() == null) return;
        cardNetwork.sync(player, team.getCard());
        cardNetwork.toggle(player);
    }

    public void syncCardForPlayer(Player player) {
        BingoTeam team = getTeamOf(player);
        if (team != null && team.getCard() != null) cardNetwork.sync(player, team.getCard());
    }

    /**
     * Limpia explícitamente el cartón cliente. Se usa al reconectar para eliminar un GUI
     * que haya quedado guardado en memoria después de abandonar/desconectarse de una partida.
     */
    public void clearClientCard(Player player) {
        if (player != null && player.isOnline()) cardNetwork.clear(player);
    }

    public void syncCardForTeam(BingoTeam team) {
        if (team == null || team.getCard() == null) return;
        for (UUID uuid : team.getMembers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) cardNetwork.sync(player, team.getCard());
        }
    }

    public void onGoalProgressChanged(BingoTeam team) {
        if (team == null || team.getCard() == null) return;
        if (team.getGuiInventory() != null) {
            com.arlight.bingo.gui.CardGUI.refresh(team.getGuiInventory(), team.getCard());
        }
        syncCardForTeam(team);
    }

    private int countAllPlayers() {
        int total = 0;
        for (BingoTeam t : teams.values()) total += t.getMembers().size();
        return total;
    }

    /** En modo FFA, cada jugador es su propio "equipo", identificado por su nombre. */
    public void addPlayer(Player player, String teamNameOrNull) {
        if ((state != GameState.WAITING && state != GameState.COUNTDOWN) || arenaPreparing) {
            player.sendMessage(MinigameIcons.CROSS + ChatColor.RED
                    + "La partida ya se está preparando o está en curso. Espera a la siguiente sala.");
            return;
        }
        if (sessionBridge != null && !sessionBridge.begin(player)) {
            player.sendMessage(MinigameIcons.CROSS + ChatColor.RED
                    + "Ya estás participando en otro minijuego.");
            return;
        }
        saveInventoryIfNeeded(player);
        String teamName = isTeamMode()
                ? (teamNameOrNull != null ? teamNameOrNull : "sin-equipo")
                : player.getName();

        BingoTeam team = teams.computeIfAbsent(teamName, BingoTeam::new);
        team.addMember(player.getUniqueId());
        playerTeamName.put(player.getUniqueId(), teamName);

        player.sendMessage(MinigameIcons.PLAYERS + ChatColor.GREEN + "Te has unido al bingo"
                + (isTeamMode() ? " en el equipo " + ChatColor.GOLD + teamName : "") + ChatColor.GREEN + ".");

        // Por defecto, unirse a la cola NO mueve al jugador. Así puede permanecer en
        // el punto exacto de "legos" donde pulsó el NPC o el menú. El teletransporte a
        // la arena se conserva únicamente cuando comienza la partida.
        if (configManager.isTeleportPlayersOnJoin()
                && multiverse != null
                && configManager.isArenaWorldEnabled()) {
            String lobby = configManager.getLobbyWorld();
            if (lobby != null && !lobby.isEmpty()) {
                teleportAndKeepScoreboard(player, lobby);
            }
        }

        if (scoreboard != null) {
            scoreboard.applyTo(player);
        }

        // Al unirse la partida todavia no arranco (no hay carton asignado), asi que en vez
        // del item del carton le damos un item de informacion y uno para salir de la sala.
        // El inventario se limpia por completo para que no se cuele nada de antes.
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        player.getInventory().addItem(com.arlight.bingo.gui.LobbyItems.createInfoItem(plugin, configManager));
        player.getInventory().addItem(com.arlight.bingo.gui.LobbyItems.createLeaveItem(plugin));

        waitingNetwork.show(player, countAllPlayers(), configManager.getMaxPlayersScoreboard(),
                state == GameState.COUNTDOWN ? autostartSecondsRemaining : null);
        onPlayerCountChanged();
        autosave();
    }

    /** Salir de la SALA DE ESPERA (antes de que arranque la partida). Ver disqualifyPlayer() para salir en pleno juego. */
    public void removePlayer(Player player) {
        removePlayer(player, true);
    }

    private void removePlayer(Player player, boolean closeSession) {
        waitingNetwork.hide(player);
        String teamName = playerTeamName.remove(player.getUniqueId());
        if (teamName != null) {
            BingoTeam team = teams.get(teamName);
            if (team != null) {
                team.removeMember(player.getUniqueId());
                if (team.getMembers().isEmpty()) {
                    teams.remove(teamName);
                }
            }
        }
        if (scoreboard != null) {
            scoreboard.removeFrom(player);
        }
        if (player.isOnline()) cardNetwork.clear(player);

        if (closeSession) {
            try {
                teleportToLobbyOrStay(player);
                restoreSavedInventory(player);
            } finally {
                // Aunque otro plugin falle durante el teleport o la restauración,
                // nunca dejamos una sesión activa que luego parezca desconexión.
                cancelCoreSession(player);
            }
        }
        // Si closeSession es false (desconexion), NO borramos savedInventories aca:
        // el jugador esta offline y no podemos devolverle sus items ahora mismo. La entrada
        // queda guardada (y se persiste en gamedata.yml, ver StorageManager) hasta que
        // restoreSavedInventory() la consuma cuando el jugador vuelva a salir de una partida.

        onPlayerCountChanged();
        autosave();
    }

    /**
     * Teletransporta al mundo lobby configurado (arena-world.lobby-world) si esta cargado.
     * Si NO esta configurado o no esta cargado, NO fuerza ningun teletransporte -- deja al
     * jugador donde ya esta, en vez de mandarlo al mundo primario del servidor (que puede
     * ser distinto al lobby real que el admin este usando, ej. un mundo llamado "legos").
     */
    private void teleportToLobbyOrStay(Player player) {
        org.bukkit.Location lobby = resolveSafeLobbySpawn();
        if (lobby == null) return;
        prepareSafeRespawn(player, lobby);
        player.teleport(lobby);
    }

    /** Llamado por el listener de desconexion: solo actua si la partida esta corriendo y el jugador esta anotado. */
    public void disqualifyIfInMatch(Player player) {
        if (getTeamOf(player) == null) return;
        if (state == GameState.RUNNING) {
            disqualifyPlayer(player, ChatColor.RED + player.getName()
                    + " se desconecto y quedo descalificado del Bingo.", false);
        } else if (state == GameState.TIEBREAK) {
            eliminateTiebreakPlayer(player, false);
        } else {
            removePlayer(player, false);
        }
    }

    /**
     * Saca a un jugador de una partida EN CURSO (por desconexion o por usar /bingo leave
     * mientras se juega). A diferencia de removePlayer(), esto puede terminar la partida
     * si con esta salida solo queda un equipo/jugador en pie (gana automaticamente).
     */
    public void disqualifyPlayer(Player player, String broadcastMessage) {
        disqualifyPlayer(player, broadcastMessage, true);
    }

    private void disqualifyPlayer(Player player, String broadcastMessage, boolean closeSession) {
        BingoTeam team = getTeamOf(player);
        if (team == null) return;

        if (state == GameState.TIEBREAK) {
            eliminateTiebreakPlayer(player, true);
            return;
        }

        playerTeamName.remove(player.getUniqueId());
        team.removeMember(player.getUniqueId());
        boolean teamEliminated = team.getMembers().isEmpty();
        if (teamEliminated) {
            teams.remove(team.getName());
        }

        if (broadcastMessage != null) {
            sendToParticipants(broadcastMessage);
        }

        if (scoreboard != null) {
            scoreboard.removeFrom(player);
        }
        if (player.isOnline()) cardNetwork.clear(player);
        if (closeSession && player.isOnline()) {
            teleportToLobbyOrStay(player);
            restoreSavedInventory(player);
        }
        if (closeSession) {
            endCoreSession(player);
        }
        // Igual que en removePlayer(): si closeSession es false (desconexion en pleno juego)
        // dejamos savedInventories intacto para poder restaurarlo mas adelante en vez de
        // perder el inventario original del jugador para siempre.

        if (state != GameState.RUNNING) {
            autosave();
            return;
        }

        if (teams.isEmpty()) {
            sendToParticipants(ChatColor.GOLD + "[Bingo] " + ChatColor.RED + "No queda nadie jugando, se cancela la partida.");
            stop();
            return;
        }

        if (teams.size() == 1) {
            BingoTeam winner = teams.values().iterator().next();
            sendToParticipants(ChatColor.GOLD + "" + ChatColor.BOLD + "[Bingo] ¡" + winner.getName()
                    + " gana por ser el único que queda en la partida!");
            endMatchWithCelebration(Collections.singletonList(winner));
            return;
        }

        autosave();
    }

    public BingoTeam getTeamOf(Player player) {
        String teamName = playerTeamName.get(player.getUniqueId());
        return teamName == null ? null : teams.get(teamName);
    }

    /**
     * Se llama cada vez que se une/sale un jugador mientras se espera partida.
     * Gestiona el arranque/cancelacion de la cuenta regresiva de auto-inicio y
     * actualiza el scoreboard de sala de espera.
     */
    private void onPlayerCountChanged() {
        if (state != GameState.WAITING && state != GameState.COUNTDOWN) return;

        int total = countAllPlayers();

        if (state == GameState.WAITING && total >= configManager.getMinPlayersToStart()) {
            beginAutostartCountdown();
        } else if (state == GameState.COUNTDOWN && total < configManager.getMinPlayersToStart()) {
            cancelAutostartCountdown();
        }

        updateLobbyScoreboard();
    }

    private void updateLobbyScoreboard() {
        Integer countdown = state == GameState.COUNTDOWN ? autostartSecondsRemaining : null;
        int players = countAllPlayers();
        int maxPlayers = configManager.getMaxPlayersScoreboard();
        if (scoreboard != null) scoreboard.showLobby(players, maxPlayers, countdown);
        waitingNetwork.update(activePlayers(), players, maxPlayers, countdown);
    }

    private void beginAutostartCountdown() {
        state = GameState.COUNTDOWN;
        autostartSecondsRemaining = configManager.getAutoStartCountdownSeconds();
        sendToParticipants(MinigameIcons.CLOCK + ChatColor.GOLD + "[Bingo] " + ChatColor.GREEN
                + "¡Suficientes jugadores! La partida empieza en " + autostartSecondsRemaining + "s.");

        autostartTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            autostartSecondsRemaining--;
            updateLobbyScoreboard();

            if (autostartSecondsRemaining <= 0) {
                cancelAutostartTaskOnly();
                prepareArenaThenFinalCountdown();
            } else if (autostartSecondsRemaining <= 5 || autostartSecondsRemaining % 10 == 0) {
                sendToParticipants(MinigameIcons.CLOCK + ChatColor.GOLD + "[Bingo] " + ChatColor.YELLOW
                        + "Empieza en " + autostartSecondsRemaining + "...");
            }
        }, 20L, 20L);
    }

    private void cancelAutostartCountdown() {
        cancelAutostartTaskOnly();
        if (state == GameState.COUNTDOWN) {
            state = GameState.WAITING;
            sendToParticipants(MinigameIcons.WARNING + ChatColor.GOLD + "[Bingo] " + ChatColor.RED
                    + "Faltan jugadores, se canceló la cuenta regresiva.");
        }
    }

    private void cancelAutostartTaskOnly() {
        if (autostartTask != null) {
            autostartTask.cancel();
            autostartTask = null;
        }
    }

    /** Cuenta regresiva final "5, 4, 3, 2, 1" antes de que arranque la partida de verdad. */
    private void beginFinalCountdownThenStart() {
        state = GameState.COUNTDOWN;
        final int[] remaining = {configManager.getFinalCountdownSeconds()};

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (remaining[0] <= 0) {
                task.cancel();
                // El cliente muestra ¡COMIENZA! dentro de la interfaz cinematográfica.
                // No enviamos títulos ni mensajes de chat para evitar contadores duplicados.
                preparationNetwork.start(activePlayers());
                actuallyStart();
                return;
            }
            // Único contador visible: el integrado en ArlightChatClient.
            preparationNetwork.countdown(activePlayers(), remaining[0]);
            remaining[0]--;
        }, 0L, 20L);
    }

    /**
     * Se llama cuando termina la cuenta de 50s (o cuando un admin fuerza /bingo start):
     * primero regenera y teletransporta a todos a la arena, y RECIEN AHI arranca la cuenta
     * final 5-4-3-2-1 (ya estando todos parados en el mundo de la partida).
     */
    private void prepareArenaThenFinalCountdown() {
        if (teams.isEmpty()) {
            sendToParticipants(ChatColor.RED + "[Bingo] No hay jugadores para iniciar la partida.");
            state = GameState.WAITING;
            return;
        }

        state = GameState.COUNTDOWN;
        arenaPreparing = true;
        sendToParticipants(ChatColor.GOLD + "[Bingo] " + ChatColor.GREEN + "Preparando la arena...");
        waitingNetwork.hide(activePlayers());
        preparationNetwork.show(activePlayers(), configManager.isTemplateMatchesEnabled()
                ? "Copiando las plantillas de campaña" : "Preparando terreno con Chunky", 0, 0.02);
        setupArenaWorldAsync(this::beginFinalCountdownThenStart);
    }

    /**
     * Inicio manual (comando de admin): salta la cuenta de auto-inicio (los 50s) y
     * va directo a preparar la arena + la cuenta regresiva final 5-4-3-2-1.
     */
    public void start() {
        if (teams.isEmpty()) {
            sendToParticipants(ChatColor.RED + "[Bingo] No hay jugadores para iniciar la partida.");
            return;
        }
        cancelAutostartTaskOnly();
        prepareArenaThenFinalCountdown();
    }

    /** Aqui es donde realmente arranca la partida: se reparten cartones y se activa todo. */
    private void actuallyStart() {
        arenaPreparing = false;
        if (teams.isEmpty()) {
            sendToParticipants(ChatColor.RED + "[Bingo] No hay jugadores para iniciar la partida.");
            preparationNetwork.hide(activePlayers());
            state = GameState.WAITING;
            updateLobbyScoreboard();
            return;
        }

        int size = configManager.getCardSize();
        int needed = size * size;
        List<BingoGoal> pool = new ArrayList<>(configManager.getGoalPool());
        List<BingoGoal> bossGoals = configuredBossGoals();
        int randomNeeded = Math.max(0, needed - bossGoals.size());

        if (pool.size() < randomNeeded) {
            sendToParticipants(ChatColor.RED + "[Bingo] No hay suficientes objetivos configurados ("
                    + pool.size() + "/" + randomNeeded + "). Revisa config.yml.");
            preparationNetwork.hide(activePlayers());
            state = GameState.WAITING;
            updateLobbyScoreboard();
            return;
        }

        // Cada equipo/jugador recibe un carton barajado de forma independiente,
        // asi ningun carton es identico entre equipos.
        for (BingoTeam team : teams.values()) {
            Collections.shuffle(pool);
            List<BingoGoal> selected = new ArrayList<>();
            for (BingoGoal bossGoal : bossGoals) selected.add(bossGoal.copyFresh());
            for (int i = 0; i < randomNeeded; i++) {
                selected.add(pool.get(i).copyFresh());
            }
            Collections.shuffle(selected);
            team.setCard(new BingoCard(size, selected));
        }
        claimedGlobalBosses.clear();

        clearAllPlayerInventories();
        for (BingoTeam team : teams.values()) syncCardForTeam(team);

        state = GameState.RUNNING;
        if (sessionBridge != null) {
            for (BingoTeam team : teams.values()) {
                for (UUID uuid : team.getMembers()) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) sessionBridge.started(player);
                }
            }
        }
        if (configManager.getTimeLimitMinutes() > 0) {
            matchDurationMillis = configManager.getTimeLimitMinutes() * 60_000L;
            endTimeMillis = System.currentTimeMillis() + matchDurationMillis;
            timerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkTimeLimit, 20L, 20L);

            if (bossBar != null) {
                bossBar.start();
                for (BingoTeam team : teams.values()) {
                    for (UUID uuid : team.getMembers()) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) bossBar.addPlayer(p);
                    }
                }
                bossBar.update(matchDurationMillis, matchDurationMillis);
            }
        }

        inventoryScanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanAllInventories, 20L, 20L);

        if (scoreboard != null) {
            scoreboard.resetProgress();
            scoreboard.showRunning();
        }

        sendToParticipants(MinigameIcons.SWORDS + ChatColor.GOLD + "[Bingo] "
                + ChatColor.GREEN + "¡La partida ha comenzado! Usa el cartón para abrir la interfaz completa.");
        autosave();
    }

    /**
     * Limpia el inventario (contenido, armadura, offhand) de todos los jugadores anotados
     * al arrancar una partida nueva, para que items de partidas anteriores no den progreso
     * gratis (via el escaneo de inventario) ni queden dando vueltas. Les vuelve a dar el
     * item fisico del carton despues de limpiar.
     */
    private void clearAllPlayerInventories() {
        for (BingoTeam team : teams.values()) {
            for (UUID uuid : team.getMembers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                saveInventoryIfNeeded(p);
                p.getInventory().clear();
                p.getInventory().setArmorContents(new ItemStack[4]);
                p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                p.getInventory().addItem(BingoCardItem.createForTeam(plugin, team));
            }
        }
    }

    /**
     * Regenera (borra y crea de nuevo, de forma nativa) el mundo de arena configurado y sus
     * mundos extra, ajusta el borde adaptativo de cada uno (y pregenera una zona chica cerca
     * del spawn), y RECIEN CUANDO TODO ESO TERMINA teletransporta a los jugadores y llama a
     * onReady. No depende de ningun plugin externo.
     */
    private void setupArenaWorldAsync(Runnable onReady) {
        if (multiverse == null || !configManager.isArenaWorldEnabled()) {
            onReady.run();
            return;
        }

        if (configManager.isWorldPoolEnabled() && worldPoolManager != null) {
            worldPoolManager.prepareAndAcquireForMatch(activePlayers()).whenComplete((group, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().severe("No se pudo obtener una arena lista: " + error.getMessage());
                    sendToParticipants(MinigameIcons.CROSS + ChatColor.RED
                            + "[Bingo] La arena todavía no está lista. Revisa la consola e inténtalo nuevamente.");
                    preparationNetwork.hide(activePlayers());
                    arenaPreparing = false;
                    state = GameState.WAITING;
                    updateLobbyScoreboard();
                    autosave();
                    return;
                }
                currentArenaWorld = group.overworld();
                currentArenaExtraWorlds = new ArrayList<>(group.extras());
                finishArenaSetup(group.all(), group.overworld(), onReady);
            }));
            return;
        }

        String world = configManager.getGameWorld();
        if (world == null || world.isEmpty()) {
            plugin.getLogger().warning("arena-world.enabled esta en true pero no hay arena-world.game-world configurado.");
            onReady.run();
            return;
        }

        this.currentArenaWorld = world;
        List<String> worldsToPrepare = new ArrayList<>();
        worldsToPrepare.add(world);
        worldsToPrepare.addAll(configManager.getExtraArenaWorlds());

        if (configManager.isRegenOnStart()) {
            // Bukkit.createWorld() es sincrono/bloqueante: cuando regenerateWorld() termina,
            // el mundo y su spawn seguro ya estan listos.
            multiverse.regenerateWorld(world);
            for (String extra : configManager.getExtraArenaWorlds()) {
                multiverse.regenerateWorld(extra);
            }
        }

        // El borde adaptativo (buscar estructuras + pregenerar una zona chica) es asincrono;
        // esperamos a que TODOS los mundos terminen antes de teletransportar a nadie, para
        // que no camine hacia una zona que todavia se esta generando.
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        if (worldBorderManager != null) {
            for (String w : worldsToPrepare) {
                World bukkitWorld = Bukkit.getWorld(w);
                if (bukkitWorld != null) {
                    futures.add(worldBorderManager.applyAdaptiveBorder(bukkitWorld, configManager));
                }
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((res, err) -> {
            if (err != null) {
                plugin.getLogger().warning("Error preparando el borde/pregeneracion de la arena: " + err.getMessage());
            }
            // Volvemos al hilo principal para teletransportar (la API de Bukkit no es thread-safe).
            Bukkit.getScheduler().runTask(plugin, () -> {
                currentArenaExtraWorlds = new ArrayList<>(configManager.getExtraArenaWorlds());
                finishArenaSetup(worldsToPrepare, world, onReady);
            });
        });
    }

    private void finishArenaSetup(List<String> preparedWorlds, String destination, Runnable onReady) {
        if (worldHandoffInProgress) {
            plugin.getLogger().warning("Se ignoró una segunda preparación de arena mientras el handoff anterior sigue activo.");
            return;
        }
        World overworld = null;
        World nether = null;
        World end = null;
        for (String preparedWorld : preparedWorlds) {
            World candidate = Bukkit.getWorld(preparedWorld);
            if (candidate == null) continue;
            if (candidate.getEnvironment() == World.Environment.NORMAL) overworld = candidate;
            else if (candidate.getEnvironment() == World.Environment.NETHER) nether = candidate;
            else if (candidate.getEnvironment() == World.Environment.THE_END) end = candidate;
        }

        final World finalOverworld = overworld;
        final World finalNether = nether;
        final World finalEnd = end;
        final long token = ++worldHandoffToken;
        worldHandoffInProgress = true;

        boolean hasCore = plugin.getServer().getPluginManager().getPlugin("ArlightCore") != null;
        boolean mustConfigure = hasCore && finalOverworld != null && finalNether != null && finalEnd != null
                && (configManager.isWorldPoolAutoConfigureInventories()
                    || (configManager.isWorldPoolUseMvnp()
                        && com.arlight.bingo.integration.CoreIntegration.coreHandlesNetherPortals()));

        CompletableFuture<Boolean> handoff = mustConfigure
                ? com.arlight.bingo.integration.CoreIntegration.configureArenaWorldsStable(
                        "bingo", finalOverworld.getName(), finalNether.getName(), finalEnd.getName())
                : CompletableFuture.completedFuture(true);

        handoff.whenComplete((configured, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (token != worldHandoffToken || !arenaPreparing) return;
            if (error != null || !Boolean.TRUE.equals(configured)) {
                String detail = error == null ? "Multiverse rechazó la configuración"
                        : (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                abortArenaHandoff("No se pudo estabilizar el grupo de mundos: " + detail);
                return;
            }

            // Multiverse-Inventories procesa perfiles y respawn en tareas propias. En
            // Arclight dejamos diez ticks completos antes de construir campaña o mover jugadores.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (token != worldHandoffToken || !arenaPreparing) return;
                finishArenaSetupAfterHandoff(preparedWorlds, destination, onReady,
                        finalOverworld, finalNether, finalEnd, token);
            }, configManager.isSafeWorldHandoffEnabled()
                    ? configManager.getSafeWorldHandoffSettleTicks() : 2L);
        }));
    }

    private void finishArenaSetupAfterHandoff(List<String> preparedWorlds, String destination,
                                               Runnable onReady, World overworld, World nether,
                                               World end, long token) {
        try {
            if (dimensionDungeonManager != null && overworld != null && nether != null && end != null) {
                dimensionDungeonManager.prepare(overworld, nether, end);
            }
            if (endEncounterManager != null && end != null) endEncounterManager.prepare(end);
            if (configManager.isTemplateMatchesEnabled()) {
                org.bukkit.Location netherArrival = getFixedNetherArrival();
                org.bukkit.Location endArrival = end == null ? null : getEndEncounterArrival(end);
                if (!isSafeDimensionDestination(netherArrival, World.Environment.NETHER)) {
                    netherArrival = com.arlight.bingo.template.TemplateCampaignRepair
                            .ensureNetherSafeArrival(nether, netherArrival);
                    if (!isSafeDimensionDestination(netherArrival, World.Environment.NETHER)) {
                        throw new IllegalStateException("La copia del Nether no pudo reparar su llegada segura de plantilla.");
                    }
                    plugin.getLogger().warning("La llegada segura del Nether estaba incompleta y fue reparada automáticamente.");
                }
                if (!isSafeDimensionDestination(endArrival, World.Environment.THE_END)) {
                    endArrival = com.arlight.bingo.template.TemplateCampaignRepair
                            .ensureEndSafeArrival(end, endArrival);
                    if (!isSafeDimensionDestination(endArrival, World.Environment.THE_END)) {
                        throw new IllegalStateException("La copia del End no pudo reparar su llegada segura de plantilla.");
                    }
                    plugin.getLogger().warning("La llegada segura del End estaba incompleta y fue reparada automáticamente.");
                }
            }
        } catch (Throwable error) {
            abortArenaHandoff("Se canceló la construcción segura de la campaña: "
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            return;
        }

        if (configManager.isTeleportPlayersOnStart()) {
            boolean externalInventories = usesExternalInventories();
            org.bukkit.Location safeLobby = resolveSafeLobbySpawn();
            for (BingoTeam team : new ArrayList<>(teams.values())) {
                for (UUID uuid : new ArrayList<>(team.getMembers())) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) continue;
                    try {
                        prepareSafeRespawn(player, safeLobby);
                        if (externalInventories) restoreSavedInventoryCopy(player);

                        boolean teleported = teleportAndKeepScoreboard(player, destination);
                        if (externalInventories && teleported) {
                            // Se limpia después de que MVI haya terminado PlayerChangedWorldEvent,
                            // no dentro del mismo tick del teletransporte.
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                try {
                                    if (player.isOnline() && isArenaWorld(player.getWorld().getName())) {
                                        clearArenaInventory(player);
                                    }
                                } catch (Throwable taskError) {
                                    plugin.getLogger().warning("Fallo aislado limpiando inventario Bingo de "
                                            + player.getName() + ": " + taskError.getClass().getSimpleName());
                                }
                            }, configManager.isSafeWorldHandoffEnabled()
                                    ? configManager.getSafeWorldHandoffInventoryClearDelayTicks() : 2L);
                        } else if (externalInventories) {
                            plugin.getLogger().warning("No se pudo cambiar a " + player.getName()
                                    + " al grupo Bingo; se conservó su copia local de inventario.");
                        }
                    } catch (Throwable playerError) {
                        plugin.getLogger().warning("Se aisló un fallo al preparar a " + player.getName()
                                + ": " + (playerError.getMessage() == null
                                ? playerError.getClass().getSimpleName() : playerError.getMessage()));
                    }
                }
            }
        }

        if (token != worldHandoffToken) return;
        worldHandoffInProgress = false;
        onReady.run();
    }

    private boolean isSafeDimensionDestination(org.bukkit.Location location,
                                               World.Environment expectedEnvironment) {
        if (location == null || location.getWorld() == null) return false;
        World world = location.getWorld();
        if (world.getEnvironment() != expectedEnvironment) return false;
        if (location.getY() <= world.getMinHeight() + 1
                || location.getY() >= world.getMaxHeight() - 2) return false;
        try {
            location.getChunk().load(true);
            org.bukkit.block.Block floor = location.clone().add(0, -1, 0).getBlock();
            org.bukkit.block.Block feet = location.getBlock();
            org.bukkit.block.Block head = location.clone().add(0, 1, 0).getBlock();
            return floor.getType().isSolid() && feet.isPassable() && head.isPassable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void abortArenaHandoff(String reason) {
        plugin.getLogger().severe(reason);
        sendToParticipants(MinigameIcons.CROSS + ChatColor.RED
                + "[Bingo] No se pudo preparar la arena de forma segura. No se movió a ningún jugador.");
        preparationNetwork.hide(activePlayers());
        if (worldPoolManager != null && configManager.isWorldPoolEnabled()) worldPoolManager.releaseActive();
        worldHandoffInProgress = false;
        arenaPreparing = false;
        state = GameState.WAITING;
        updateLobbyScoreboard();
        autosave();
    }

    public org.bukkit.Location resolveSafeLobbySpawn() {
        String configured = configManager.getLobbyWorld();
        World world = configured == null ? null : Bukkit.getWorld(configured);
        if (world == null) world = Bukkit.getWorld(configManager.getSafeWorldHandoffLobbyFallback());
        if (world == null && !Bukkit.getWorlds().isEmpty()) world = Bukkit.getWorlds().get(0);
        return world == null ? null : world.getSpawnLocation();
    }

    public void ensureSafeRespawn(Player player) {
        prepareSafeRespawn(player, resolveSafeLobbySpawn());
    }

    private void prepareSafeRespawn(Player player, org.bukkit.Location safeLobby) {
        if (player == null || safeLobby == null || safeLobby.getWorld() == null) return;
        try {
            // Se usa reflexión para funcionar tanto con Paper 1.21 como con bridges de Arclight
            // que todavía exponen el nombre antiguo del método.
            try {
                player.getClass().getMethod("setRespawnLocation", org.bukkit.Location.class, boolean.class)
                        .invoke(player, safeLobby, true);
            } catch (ReflectiveOperationException modernMissing) {
                player.getClass().getMethod("setBedSpawnLocation", org.bukkit.Location.class, boolean.class)
                        .invoke(player, safeLobby, true);
            }
        } catch (Throwable ignored) {
            // El evento de respawn de Bingo mantiene el mismo fallback aunque el bridge
            // concreto no permita modificar la ubicación persistente del jugador.
        }
    }

    /** Cantidad de participantes conectados usada para escalar las mazmorras. */
    public int getActivePlayerCount() {
        return activePlayers().size();
    }

    private List<Player> activePlayers() {
        List<Player> players = new ArrayList<>();
        for (BingoTeam team : teams.values()) {
            for (UUID uuid : team.getMembers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) players.add(player);
            }
        }
        return players;
    }

    /**
     * Escanea el inventario de cada jugador conectado y actualiza el progreso de los
     * objetivos ITEM_COLLECT / CRAFT_ITEM de su equipo. Esto es mucho mas confiable que
     * depender de eventos puntuales (recoger del suelo, craftear en una mesa vanilla),
     * ya que cubre items obtenidos por cualquier via: hornos, cofres, trueques,
     * sistemas de auto-recogida de mods, etc. Se usa el maximo historico visto (no la
     * cantidad actual), asi que gastar el item despues de conseguirlo no revierte el progreso.
     */
    private void scanAllInventories() {
        for (BingoTeam team : teams.values()) {
            BingoCard card = team.getCard();
            if (card == null) continue;

            for (BingoGoal goal : card.getGoals()) {
                if (goal.isCompleted()) continue;
                if (goal.getType() != GoalType.ITEM_COLLECT && goal.getType() != GoalType.CRAFT_ITEM) continue;

                Material material = MaterialResolver.resolve(goal.getTarget());
                if (material == null) continue;

                int total = 0;
                for (UUID uuid : team.getMembers()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null) continue;
                    total += countMaterial(p, material);
                }

                int previousProgress = goal.getProgress();
                boolean justCompleted = goal.updateProgressIfHigher(total);
                if (justCompleted) {
                    onGoalCompleted(team, goal);
                } else if (goal.getProgress() != previousProgress) {
                    onGoalProgressChanged(team);
                }
            }

            if (team.getGuiInventory() != null) {
                com.arlight.bingo.gui.CardGUI.refresh(team.getGuiInventory(), card);
            }
        }
    }

    private int countMaterial(Player player, Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) count += stack.getAmount();
        }
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            if (stack != null && stack.getType() == material) count += stack.getAmount();
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand.getType() == material) count += offhand.getAmount();
        return count;
    }

    private void checkTimeLimit() {
        long remaining = endTimeMillis - System.currentTimeMillis();
        bossBar.update(remaining, matchDurationMillis);
        if (scoreboard != null) scoreboard.setTimeRemaining(remaining, matchDurationMillis);

        if (remaining <= 0) {
            if (configManager.getWinCondition() == WinCondition.POINTS) {
                List<BingoTeam> leaders = announcePointsWinner();
                if (leaders.size() > 1 && configManager.isTiebreakEnabled()) {
                    startTiebreak(leaders);
                    return;
                }
                if (!leaders.isEmpty()) {
                    endMatchWithCelebration(leaders);
                    return;
                }
            } else {
                sendToParticipants(MinigameIcons.CLOCK + ChatColor.GOLD + "[Bingo] "
                        + ChatColor.RED + "Se acabó el tiempo. Nadie completó el cartón.");
            }
            stop();
        }
    }

    /** Anuncia quien tiene mas casillas completadas (1 punto por casilla) cuando se acaba el tiempo. Devuelve el/los ganador(es). */
    private List<BingoTeam> announcePointsWinner() {
        int bestPoints = -1;
        List<BingoTeam> leaders = new ArrayList<>();

        for (BingoTeam team : teams.values()) {
            if (team.getCard() == null) continue;
            int points = team.getCard().countCompleted();
            if (points > bestPoints) {
                bestPoints = points;
                leaders.clear();
                leaders.add(team);
            } else if (points == bestPoints) {
                leaders.add(team);
            }
        }

        sendToParticipants("");
        if (leaders.isEmpty() || bestPoints <= 0) {
            sendToParticipants(MinigameIcons.CLOCK + ChatColor.GOLD + "[Bingo] "
                    + ChatColor.RED + "Se acabó el tiempo. Nadie sumó puntos.");
            return new ArrayList<>();
        } else if (leaders.size() == 1) {
            Bukkit.broadcastMessage(MinigameIcons.TROPHY + ChatColor.GOLD + "" + ChatColor.BOLD
                    + "[Bingo] ¡" + leaders.get(0).getName()
                    + " gano con " + bestPoints + " punto(s)!");
        } else {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < leaders.size(); i++) {
                if (i > 0) names.append(", ");
                names.append(leaders.get(i).getName());
            }
            sendToParticipants(MinigameIcons.STAR + ChatColor.GOLD + "" + ChatColor.BOLD
                    + "[Bingo] Empate entre " + names
                    + " con " + bestPoints + " punto(s) cada uno!");
        }
        sendToParticipants("");
        return leaders;
    }

    public void stop() {
        haltGameSystems();
        finishAndLeaveQueue();
    }

    private void haltGameSystems() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
        if (inventoryScanTask != null) {
            inventoryScanTask.cancel();
            inventoryScanTask = null;
        }
        cancelAutostartTaskOnly();
        if (tiebreakTask != null) {
            tiebreakTask.cancel();
            tiebreakTask = null;
        }
        tiebreakCombatActive = false;
        bossBar.stop();
    }

    /** Quita el carton de todos los equipos (GUI y mapa cacheados tambien se invalidan). */
    private void clearAllCards() {
        for (BingoTeam team : teams.values()) {
            for (UUID uuid : team.getMembers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) cardNetwork.clear(player);
            }
            team.setCard(null);
        }
    }

    /**
     * Termina la partida con festejo: fuegos artificiales + titulo en pantalla para los
     * ganadores, y recien despues de "celebration-seconds" se devuelve a todos al lobby
     * y se vuelve al estado WAITING (con el scoreboard de sala de espera de nuevo).
     */
    private void endMatchWithCelebration(List<BingoTeam> winners) {
        state = GameState.ENDED; // durante el festejo dejamos el scoreboard de resultados como esta
        haltGameSystems();
        autosave();
        celebrate(winners);
        if (winListener != null) {
            winListener.onWin(winners);
        }

        int delaySeconds = configManager.getCelebrationSeconds();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sendToParticipants(ChatColor.GRAY + "Volviendo al lobby...");
            finishAndLeaveQueue();
        }, delaySeconds * 20L);
    }

    /** Finaliza la sesion y vacia la sala: nunca inicia otra cola automaticamente. */
    private void finishAndLeaveQueue() {
        arenaPreparing = false;
        waitingNetwork.hide(activePlayers());
        preparationNetwork.hide(activePlayers());
        sendPlayersBackToLobby();
        if (dimensionDungeonManager != null) {
            dimensionDungeonManager.finishSession();
        }
        if (worldPoolManager != null && configManager.isWorldPoolEnabled()) {
            worldPoolManager.releaseActive();
        }
        clearAllCards();
        for (UUID uuid : new ArrayList<>(playerTeamName.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && scoreboard != null) scoreboard.removeFrom(player);
        }
        teams.clear();
        playerTeamName.clear();
        // OJO: no hacemos savedInventories.clear() aca. sendPlayersBackToLobby() ya
        // restauro (y por lo tanto removio del mapa) a todos los jugadores conectados;
        // lo que quede son inventarios de jugadores desconectados que todavia no
        // recibieron sus items de vuelta, y deben conservarse hasta que vuelvan a entrar.
        claimedGlobalBosses.clear();
        tiebreakAlive.clear();
        tiebreakTeams.clear();
        state = GameState.WAITING;
        autosave();
    }

    private void startTiebreak(List<BingoTeam> tiedTeams) {
        haltGameSystems();
        state = GameState.TIEBREAK;
        tiebreakAlive.clear();
        tiebreakTeams.clear();
        tiebreakCombatActive = false;

        Set<String> finalists = new HashSet<>();
        for (BingoTeam team : tiedTeams) finalists.add(team.getName());

        // Los jugadores que no empataron salen completamente del minijuego.
        for (BingoTeam team : new ArrayList<>(teams.values())) {
            if (finalists.contains(team.getName())) continue;
            for (UUID uuid : new ArrayList<>(team.getMembers())) {
                playerTeamName.remove(uuid);
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    teleportToLobbyOrStay(player);
                    restoreSavedInventory(player);
                    endCoreSession(player);
                    if (scoreboard != null) scoreboard.removeFrom(player);
                }
                // Si esta offline, dejamos su savedInventories tal cual: se restaura solo
                // cuando vuelva a conectarse y salga de la partida (ver restoreSavedInventory).
            }
            teams.remove(team.getName());
        }

        World world = currentArenaWorld == null ? null : Bukkit.getWorld(currentArenaWorld);
        if (world == null) {
            sendToParticipants(ChatColor.RED + "[Bingo] No se pudo crear el desempate: la arena no está cargada.");
            endMatchWithCelebration(tiedTeams);
            return;
        }

        List<Player> fighters = new ArrayList<>();
        for (BingoTeam team : tiedTeams) {
            for (UUID uuid : team.getMembers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    fighters.add(player);
                    tiebreakAlive.add(uuid);
                    tiebreakTeams.put(uuid, team.getName());
                }
            }
        }
        if (fighters.size() <= 1) {
            BingoTeam winner = fighters.isEmpty() ? tiedTeams.get(0) : getTeamOf(fighters.get(0));
            endMatchWithCelebration(Collections.singletonList(winner));
            return;
        }

        org.bukkit.Location spawn = world.getSpawnLocation();
        int centerX = spawn.getBlockX();
        int centerZ = spawn.getBlockZ();
        int highest = world.getHighestBlockYAt(centerX, centerZ);
        tiebreakFloorY = Math.min(world.getMaxHeight() - 8,
                Math.max(spawn.getBlockY() + 12, highest + 15));
        buildTiebreakArena(world, centerX, tiebreakFloorY, centerZ);

        int distance = configManager.getTiebreakSpawnDistance();
        for (int i = 0; i < fighters.size(); i++) {
            Player player = fighters.get(i);
            double angle = (Math.PI * 2.0 * i) / fighters.size();
            org.bukkit.Location location = new org.bukkit.Location(world,
                    centerX + 0.5 + Math.cos(angle) * distance,
                    tiebreakFloorY + 1.0,
                    centerZ + 0.5 + Math.sin(angle) * distance);
            location.setDirection(new org.bukkit.util.Vector(centerX + 0.5 - location.getX(), 0,
                    centerZ + 0.5 - location.getZ()));
            prepareTiebreakFighter(player, location);
        }

        sendToParticipants(MinigameIcons.SWORDS + ChatColor.GOLD + "[Bingo] " + ChatColor.LIGHT_PURPLE
                + "¡Hay empate! Los finalistas lucharan; el ultimo con vida gana.");
        final int[] seconds = {configManager.getTiebreakCountdownSeconds()};
        tiebreakTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (seconds[0] <= 0) {
                tiebreakCombatActive = true;
                for (UUID uuid : tiebreakAlive) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                        player.sendTitle(MinigameIcons.SWORDS + ChatColor.RED + "¡PELEA!",
                                ChatColor.YELLOW + "El ultimo con vida gana", 0, 30, 10);
                    }
                }
                tiebreakTask.cancel();
                tiebreakTask = null;
                return;
            }
            for (UUID uuid : tiebreakAlive) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) player.sendTitle(MinigameIcons.CLOCK
                                + ChatColor.GOLD + seconds[0],
                        ChatColor.GRAY + "Preparate para el desempate", 0, 25, 0);
            }
            seconds[0]--;
        }, 0L, 20L);
        autosave();
    }

    private void buildTiebreakArena(World world, int centerX, int floorY, int centerZ) {
        int radius = configManager.getTiebreakPlatformRadius();
        Material floor = configManager.getTiebreakPlatformMaterial();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                world.getBlockAt(centerX + x, floorY, centerZ + z).setType(floor, false);
                for (int y = 1; y <= 4; y++) {
                    world.getBlockAt(centerX + x, floorY + y, centerZ + z).setType(Material.AIR, false);
                }
                if (Math.abs(x) == radius || Math.abs(z) == radius) {
                    for (int y = 1; y <= 3; y++) {
                        world.getBlockAt(centerX + x, floorY + y, centerZ + z).setType(Material.BARRIER, false);
                    }
                }
            }
        }
    }

    private void prepareTiebreakFighter(Player player, org.bukkit.Location location) {
        player.teleport(location);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItem(0, new ItemStack(Material.IRON_SWORD));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getInventory().setHeldItemSlot(0);
        player.setGameMode(org.bukkit.GameMode.ADVENTURE);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setFireTicks(0);
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    /** Devuelve true cuando el listener debe cancelar el daño. */
    public boolean handleTiebreakDamage(Player player, double finalDamage) {
        if (state != GameState.TIEBREAK || !tiebreakAlive.contains(player.getUniqueId())) return false;
        if (!tiebreakCombatActive) return true;
        if (player.getHealth() - finalDamage <= 0.0) {
            eliminateTiebreakPlayer(player, true);
            return true;
        }
        return false;
    }

    public void checkTiebreakFall(Player player) {
        if (state == GameState.TIEBREAK && tiebreakAlive.contains(player.getUniqueId())
                && player.getLocation().getY() < tiebreakFloorY - 3) {
            eliminateTiebreakPlayer(player, true);
        }
    }

    private void eliminateTiebreakPlayer(Player player, boolean showMessage) {
        UUID uuid = player.getUniqueId();
        if (!tiebreakAlive.remove(uuid)) return;
        if (showMessage) {
            sendToParticipants(MinigameIcons.CROSS + ChatColor.RED + "[Bingo] " + player.getName()
                    + " fue eliminado del desempate.");
        }
        if (player.isOnline()) {
            player.setHealth(20.0);
            player.setFireTicks(0);
            player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        }

        if (state != GameState.TIEBREAK) return;
        if (tiebreakAlive.size() == 1) {
            UUID winnerId = tiebreakAlive.iterator().next();
            String winnerTeam = tiebreakTeams.get(winnerId);
            BingoTeam winner = teams.get(winnerTeam);
            Bukkit.broadcastMessage(MinigameIcons.TROPHY + ChatColor.GOLD
                    + "[Bingo] ¡" + winnerTeam + " ganó el desempate!");
            endMatchWithCelebration(Collections.singletonList(winner));
        } else if (tiebreakAlive.isEmpty()) {
            sendToParticipants(MinigameIcons.CROSS + ChatColor.RED
                    + "[Bingo] Todos los finalistas fueron eliminados; la partida termina sin ganador.");
            stop();
        }
    }

    private void celebrate(List<BingoTeam> winners) {
        String winnerNames = winners.size() == 1 ? winners.get(0).getName() : "Empate";

        for (BingoTeam team : teams.values()) {
            boolean isWinner = winners.contains(team);
            for (UUID uuid : team.getMembers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;

                if (isWinner) {
                    p.sendTitle(MinigameIcons.TROPHY + ChatColor.GOLD + "" + ChatColor.BOLD
                                    + "¡GANASTE EL BINGO!",
                            ChatColor.GREEN + "Buen juego!", 10, 70, 20);
                    launchFireworks(p);
                } else {
                    p.sendTitle(MinigameIcons.INFO + ChatColor.YELLOW + "Partida terminada",
                            ChatColor.GRAY + "Gano: " + winnerNames, 10, 70, 20);
                }
            }
        }
    }

    private void launchFireworks(Player player) {
        org.bukkit.Location loc = player.getLocation();
        org.bukkit.World world = loc.getWorld();
        if (world == null) return;

        for (int i = 0; i < 3; i++) {
            org.bukkit.entity.Firework firework = world.spawn(loc, org.bukkit.entity.Firework.class);
            org.bukkit.inventory.meta.FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(org.bukkit.FireworkEffect.builder()
                    .withColor(org.bukkit.Color.YELLOW, org.bukkit.Color.ORANGE, org.bukkit.Color.LIME)
                    .withFade(org.bukkit.Color.RED)
                    .with(org.bukkit.FireworkEffect.Type.BALL_LARGE)
                    .trail(true)
                    .flicker(true)
                    .build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
        }
    }

    private void sendPlayersBackToLobby() {
        String lobby = configManager.getLobbyWorld();
        boolean shouldTeleport = multiverse != null
                && configManager.isArenaWorldEnabled()
                && configManager.isTeleportBackOnEnd()
                && lobby != null && !lobby.isEmpty();

        for (BingoTeam team : teams.values()) {
            for (UUID uuid : team.getMembers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.setGameMode(org.bukkit.GameMode.SURVIVAL);
                    p.setHealth(20.0);
                    p.setFoodLevel(20);
                    p.setFireTicks(0);
                    if (shouldTeleport) teleportAndKeepScoreboard(p, lobby);
                    restoreSavedInventory(p);
                    endCoreSession(p);
                }
            }
        }
    }

    /**
     * Cancela la tarea del temporizador sin cambiar el estado de la partida ni autoguardar.
     * Se usa al deshabilitar el plugin (ej. reinicio del servidor), donde ya guardamos el
     * estado tal cual estaba (RUNNING incluido) para poder restaurarlo despues.
     */
    public void cancelTimerOnly() {
        haltGameSystems();
    }

    /** Cierra overlays del cliente cuando el plugin se deshabilita o recarga. */
    public void shutdownClientUi() {
        waitingNetwork.hide(activePlayers());
        preparationNetwork.hide(activePlayers());
        for (Player player : activePlayers()) cardNetwork.clear(player);
    }

    public void reset() {
        stop();
        state = GameState.WAITING;
        teams.clear();
        playerTeamName.clear();
        claimedGlobalBosses.clear();
    }

    private void saveInventoryIfNeeded(Player player) {
        savedInventories.computeIfAbsent(player.getUniqueId(), ignored ->
                new SavedInventory(
                        cloneItems(player.getInventory().getStorageContents()),
                        cloneItems(player.getInventory().getArmorContents()),
                        cloneItem(player.getInventory().getItemInOffHand()),
                        player.getInventory().getHeldItemSlot()
                ));
    }

    private void restoreSavedInventoryCopy(Player player) {
        SavedInventory saved = savedInventories.get(player.getUniqueId());
        if (saved == null) return;
        player.getInventory().clear();
        player.getInventory().setStorageContents(cloneItems(saved.storage()));
        player.getInventory().setArmorContents(cloneItems(saved.armor()));
        player.getInventory().setItemInOffHand(cloneItem(saved.offHand()));
        player.getInventory().setHeldItemSlot(saved.heldSlot());
        player.updateInventory();
    }

    private void clearArenaInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        player.updateInventory();
    }

    private boolean isArenaWorld(String worldName) {
        if (worldName == null) return false;
        if (currentArenaWorld != null && worldName.equalsIgnoreCase(currentArenaWorld)) return true;
        for (String extra : currentArenaExtraWorlds) {
            if (worldName.equalsIgnoreCase(extra)) return true;
        }
        return false;
    }

    /**
     * Se llama al reconectarse (ver GameListener#onJoin). Si el jugador se habia
     * desconectado en pleno partido y todavia le debemos su inventario original, se lo
     * devolvemos ahora; si no hay nada pendiente, no hace nada.
     */
    public void restoreSavedInventoryIfPending(Player player) {
        if (savedInventories.containsKey(player.getUniqueId())) {
            restoreSavedInventory(player);
        }
    }

    private void restoreSavedInventory(Player player) {
        SavedInventory saved = savedInventories.remove(player.getUniqueId());
        if (saved == null) return;

        player.getInventory().clear();
        player.getInventory().setStorageContents(cloneItems(saved.storage()));
        player.getInventory().setArmorContents(cloneItems(saved.armor()));
        player.getInventory().setItemInOffHand(cloneItem(saved.offHand()));
        player.getInventory().setHeldItemSlot(saved.heldSlot());
        player.updateInventory();
    }

    private void endCoreSession(Player player) {
        if (sessionBridge != null) sessionBridge.end(player);
    }

    private void cancelCoreSession(Player player) {
        if (sessionBridge != null) sessionBridge.cancel(player);
    }

    private ItemStack[] cloneItems(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = cloneItem(source[i]);
        }
        return copy;
    }

    private ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private record SavedInventory(
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack offHand,
            int heldSlot
    ) {
    }

    /**
     * Escribe el inventario original (pre-partida) de cada jugador todavia pendiente de
     * restaurar dentro de la seccion "saved-inventories" del YAML. Llamado por StorageManager
     * junto con el resto del estado, para que sobreviva a un reinicio del servidor o a una
     * desconexion a mitad de partida.
     */
    public void writeSavedInventories(org.bukkit.configuration.file.YamlConfiguration yaml) {
        int index = 0;
        for (Map.Entry<UUID, SavedInventory> entry : savedInventories.entrySet()) {
            String base = "saved-inventories." + index;
            SavedInventory inv = entry.getValue();
            yaml.set(base + ".uuid", entry.getKey().toString());
            yaml.set(base + ".storage", com.arlight.bingo.util.ItemSerialization.encodeItems(inv.storage()));
            yaml.set(base + ".armor", com.arlight.bingo.util.ItemSerialization.encodeItems(inv.armor()));
            yaml.set(base + ".offhand", com.arlight.bingo.util.ItemSerialization.encodeItem(inv.offHand()));
            yaml.set(base + ".held-slot", inv.heldSlot());
            index++;
        }
    }

    /** Contraparte de writeSavedInventories(), llamada por StorageManager al cargar. */
    public void readSavedInventories(org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) continue;
            try {
                UUID uuid = UUID.fromString(entry.getString("uuid"));
                ItemStack[] storage = com.arlight.bingo.util.ItemSerialization.decodeItems(entry.getString("storage"));
                ItemStack[] armor = com.arlight.bingo.util.ItemSerialization.decodeItems(entry.getString("armor"));
                ItemStack offhand = com.arlight.bingo.util.ItemSerialization.decodeItem(entry.getString("offhand"));
                int heldSlot = entry.getInt("held-slot", 0);
                savedInventories.put(uuid, new SavedInventory(storage, armor, offhand, heldSlot));
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Inventario guardado corrupto en gamedata.yml, se ignora.", e);
            }
        }
    }

    /**
     * Llamar cada vez que una casilla se completa para un equipo/jugador.
     * Revisa condicion de victoria y termina la partida si corresponde.
     */
    public void onGoalCompleted(BingoTeam team, BingoGoal goal) {
        if (state != GameState.RUNNING) return;

        if (configManager.isBroadcastGoalCompletion()) {
            sendToParticipants(MinigameIcons.CHECK + ChatColor.GOLD + "[Bingo] "
                    + ChatColor.AQUA + team.getName()
                    + ChatColor.WHITE + " completó: " + ChatColor.YELLOW + goal.getDisplayName());
        }
        onGoalProgressChanged(team);
        if (scoreboard != null) {
            scoreboard.updateTeamProgress(team.getName(), team.getCard().countCompleted(), goal.getDisplayName());
        }

        WinCondition wc = configManager.getWinCondition();
        boolean won;
        if (wc == WinCondition.POINTS) {
            // En modo puntos, completar el carton entero sigue siendo una victoria instantanea
            // (bonus); si no, se sigue jugando y se decide por puntos cuando se acabe el tiempo.
            won = team.getCard().isFullCardComplete();
        } else {
            won = (wc == WinCondition.LINE && team.getCard().hasLineComplete())
                    || ((wc == WinCondition.FULL_CARD || wc == WinCondition.BLACKOUT) && team.getCard().isFullCardComplete());
        }

        if (won) {
            sendToParticipants("");
            Bukkit.broadcastMessage(MinigameIcons.TROPHY + ChatColor.GOLD + "" + ChatColor.BOLD
                    + "[Bingo] ¡" + team.getName() + " ha ganado la partida de Bingo!");
            sendToParticipants("");
            endMatchWithCelebration(Collections.singletonList(team));
        } else {
            autosave();
        }
    }

    private List<BingoGoal> configuredBossGoals() {
        if (!plugin.getConfig().getBoolean("boss-objectives.enabled", true)) return List.of();
        return List.of(
                new BingoGoal("boss_overworld", GoalType.CUSTOM_TRIGGER, null, 1, "\uE313 Derrota al Guardián de la Superficie", "boss_surface"),
                new BingoGoal("boss_nether", GoalType.CUSTOM_TRIGGER, null, 1, "\uE314 Derrota al Guardián del Nether", "boss_nether"),
                new BingoGoal("boss_dragon", GoalType.CUSTOM_TRIGGER, null, 1, "\uE312 Derrota al Dragón Corrupto de Amatista", "boss_dragon"),
                new BingoGoal("boss_end", GoalType.CUSTOM_TRIGGER, null, 1, "\uE315 Derrota al Gólem Centinela de la Ciudadela", "boss_void")
        );
    }

    /** Objetivo de carrera: solo el primer equipo que derrota al jefe obtiene el punto. */
    public void claimGlobalBoss(Player killer, String goalId) {
        if (state != GameState.RUNNING || killer == null) return;
        BingoTeam team = getTeamOf(killer);
        if (team == null || team.getCard() == null) return;
        BingoGoal goal = team.getCard().findById(goalId);
        if (goal == null || goal.isCompleted() || !claimedGlobalBosses.add(goalId)) return;
        goal.forceComplete();
        sendToParticipants(MinigameIcons.TROPHY + ChatColor.GOLD + "[Bingo] " + ChatColor.AQUA
                + team.getName() + ChatColor.WHITE + " ganó la carrera contra " + ChatColor.YELLOW
                + goal.getDisplayName() + ChatColor.WHITE + ".");
        onGoalCompleted(team, goal);
    }

    /**
     * Reconstruye el estado de la partida a partir de datos guardados (StorageManager),
     * por ejemplo tras un reinicio del servidor a mitad de partida.
     */
    public void restoreFromStorage(GameState savedState, Map<String, BingoTeam> savedTeams, long savedEndTimeMillis, String savedArenaWorld) {
        teams.clear();
        playerTeamName.clear();
        teams.putAll(savedTeams);
        claimedGlobalBosses.clear();
        for (BingoTeam team : savedTeams.values()) {
            if (team.getCard() == null) continue;
            for (String id : List.of("boss_overworld", "boss_nether", "boss_dragon", "boss_end")) {
                BingoGoal goal = team.getCard().findById(id);
                if (goal != null && goal.isCompleted()) claimedGlobalBosses.add(id);
            }
        }
        for (BingoTeam team : teams.values()) {
            for (UUID uuid : team.getMembers()) {
                playerTeamName.put(uuid, team.getName());
            }
        }

        this.state = savedState;
        this.currentArenaWorld = savedArenaWorld;
        if (savedArenaWorld != null && configManager.isWorldPoolEnabled()) {
            this.currentArenaExtraWorlds = List.of(savedArenaWorld + "_nether", savedArenaWorld + "_the_end");
        }
        if (state == GameState.RUNNING) {
            inventoryScanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanAllInventories, 20L, 20L);
            if (configManager.getTimeLimitMinutes() > 0 && savedEndTimeMillis > 0) {
                this.matchDurationMillis = configManager.getTimeLimitMinutes() * 60_000L;
                this.endTimeMillis = savedEndTimeMillis;
                timerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkTimeLimit, 20L, 20L);

                bossBar.start();
                for (BingoTeam team : teams.values()) {
                    for (UUID uuid : team.getMembers()) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) bossBar.addPlayer(p);
                    }
                }
            }
        }
    }

    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
}
