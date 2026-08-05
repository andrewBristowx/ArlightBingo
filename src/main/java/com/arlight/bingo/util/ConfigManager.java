package com.arlight.bingo.util;

import com.arlight.bingo.game.BingoGoal;
import com.arlight.bingo.game.GoalType;
import com.arlight.bingo.game.WinCondition;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public class ConfigManager {

    private final JavaPlugin plugin;

    private int cardSize;
    private boolean teamMode;
    private WinCondition winCondition;
    private int timeLimitMinutes;
    private boolean glowCompletedItems;
    private boolean broadcastGoalCompletion;

    private int minPlayersToStart;
    private int maxPlayersScoreboard;
    private int autoStartCountdownSeconds;
    private int finalCountdownSeconds;
    private int celebrationSeconds;
    private boolean tiebreakEnabled;
    private int tiebreakCountdownSeconds;
    private int tiebreakPlatformRadius;
    private int tiebreakSpawnDistance;
    private Material tiebreakPlatformMaterial;

    private List<BingoGoal> goalPool = new ArrayList<>();
    private final Set<String> blacklistItems = new LinkedHashSet<>();
    private final Set<String> blacklistMods = new LinkedHashSet<>();
    private boolean vanillaOnly;

    private boolean multiverseEnabled;
    private String lobbyWorld;
    private String gameWorld;
    private List<String> extraArenaWorlds = new ArrayList<>();
    private boolean regenOnStart;
    private boolean teleportPlayersOnJoin;
    private boolean teleportPlayersOnStart;
    private boolean teleportBackOnEnd;

    private boolean worldBorderEnabled;
    private int worldBorderMinSize;
    private int worldBorderPadding;
    private int worldBorderMaxSize;
    private int worldBorderSearchRadius;
    private boolean locateStronghold;
    private boolean locateNetherStructures;
    private boolean locateEndCity;
    private List<String> overworldStructureKeys = new ArrayList<>();
    private List<String> netherStructureKeys = new ArrayList<>();
    private List<String> endStructureKeys = new ArrayList<>();
    private boolean worldBorderPregenerate;
    private int worldBorderPregenerateRadius;
    private boolean worldPoolEnabled;
    private int worldPoolSize;
    private String worldPoolBaseName;
    private boolean worldPoolPrepareOnStartup;
    private boolean worldPoolPrepareDuringMatch;
    private int worldPoolOverworldRadius;
    private int worldPoolNetherRadius;
    private int worldPoolEndRadius;
    private boolean worldPoolUseMvnp;
    private boolean worldPoolRequireMvnp;
    private boolean worldPoolAutoConfigureInventories;
    private boolean templateMatchesEnabled;
    private boolean templateMatchesRequireComplete;
    private String overworldTemplateWorld;
    private String netherTemplateWorld;
    private String endTemplateWorld;
    private boolean safeWorldHandoffEnabled;
    private String safeWorldHandoffLobbyFallback;
    private int safeWorldHandoffSettleTicks;
    private int safeWorldHandoffInventoryClearDelayTicks;

    private boolean worldPreparationNoticeEnabled;
    private boolean worldPreparationNoticeShowToNonParticipants;
    private boolean worldPreparationNoticeShowDuringManualPrepare;
    private String worldPreparationNoticePosition;
    private int worldPreparationNoticeXOffset;
    private int worldPreparationNoticeYOffset;
    private double worldPreparationNoticeScale;
    private int worldPreparationNoticeFadeInTicks;
    private int worldPreparationNoticeFadeOutTicks;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.reloadConfig();
        ModularConfigLoader.load(plugin);
        FileConfiguration cfg = plugin.getConfig();
        // Expone las opciones nuevas como valores por defecto sin reescribir el archivo
        // existente del servidor (prepare-on-startup: false se conserva intacto).
        cfg.options().copyDefaults(true);

        this.cardSize = cfg.getInt("game.card-size", 5);
        this.teamMode = cfg.getBoolean("game.team-mode", false);
        this.winCondition = parseWinCondition(cfg.getString("game.win-condition", "POINTS"));
        this.timeLimitMinutes = cfg.getInt("game.time-limit-minutes", 60);
        this.glowCompletedItems = cfg.getBoolean("game.glow-completed-items", true);
        this.broadcastGoalCompletion = cfg.getBoolean("game.broadcast-goal-completion", true);

        this.minPlayersToStart = cfg.getInt("game.min-players-to-start", 2);
        this.maxPlayersScoreboard = cfg.getInt("game.max-players-scoreboard", 10);
        this.autoStartCountdownSeconds = cfg.getInt("game.auto-start-countdown-seconds", 50);
        this.finalCountdownSeconds = cfg.getInt("game.final-countdown-seconds", 5);
        this.celebrationSeconds = cfg.getInt("game.celebration-seconds", 8);
        this.tiebreakEnabled = cfg.getBoolean("tiebreak.enabled", true);
        this.tiebreakCountdownSeconds = Math.max(1, cfg.getInt("tiebreak.countdown-seconds", 5));
        this.tiebreakPlatformRadius = Math.max(8, cfg.getInt("tiebreak.platform-radius", 12));
        this.tiebreakSpawnDistance = Math.max(3, Math.min(tiebreakPlatformRadius - 2,
                cfg.getInt("tiebreak.spawn-distance", 6)));
        this.tiebreakPlatformMaterial = Material.matchMaterial(
                cfg.getString("tiebreak.platform-material", "SMOOTH_STONE"));
        if (this.tiebreakPlatformMaterial == null || !this.tiebreakPlatformMaterial.isBlock()) {
            this.tiebreakPlatformMaterial = Material.SMOOTH_STONE;
        }

        this.multiverseEnabled = cfg.getBoolean("arena-world.enabled", true);
        this.lobbyWorld = cfg.getString("arena-world.lobby-world", "world");
        this.gameWorld = cfg.getString("arena-world.game-world", "bingo_arena");
        this.extraArenaWorlds = new ArrayList<>(cfg.getStringList("arena-world.extra-worlds"));
        this.regenOnStart = cfg.getBoolean("arena-world.regen-on-start", true);
        this.teleportPlayersOnJoin = cfg.getBoolean("arena-world.teleport-on-join", false);
        this.teleportPlayersOnStart = cfg.getBoolean("arena-world.teleport-players-on-start", true);
        this.teleportBackOnEnd = cfg.getBoolean("arena-world.teleport-back-on-end", true);

        this.worldBorderEnabled = cfg.getBoolean("world-border.enabled", false);
        this.worldBorderMinSize = cfg.getInt("world-border.min-size", 400);
        this.worldBorderPadding = cfg.getInt("world-border.padding", 150);
        this.worldBorderMaxSize = cfg.getInt("world-border.max-size", 6000);
        this.worldBorderSearchRadius = cfg.getInt("world-border.search-radius", 10000);
        this.locateStronghold = cfg.getBoolean("world-border.locate-stronghold", false);
        this.locateNetherStructures = cfg.getBoolean("world-border.locate-nether-structures", false);
        this.locateEndCity = cfg.getBoolean("world-border.locate-end-city", false);
        this.overworldStructureKeys = structureKeys(cfg,
                "world-border.structure-keys.overworld", "betterstrongholds:stronghold");
        this.netherStructureKeys = structureKeys(cfg,
                "world-border.structure-keys.nether", "betterfortresses:fortress");
        this.endStructureKeys = structureKeys(cfg,
                "world-border.structure-keys.end", "minecraft:end_city");
        this.worldBorderPregenerate = cfg.getBoolean("world-border.pregenerate", false);
        this.worldBorderPregenerateRadius = cfg.getInt("world-border.pregenerate-radius", 400);

        this.worldPoolEnabled = cfg.getBoolean("world-pool.enabled", true);
        this.worldPoolSize = Math.max(1, cfg.getInt("world-pool.size", 1));
        this.worldPoolBaseName = cfg.getString("world-pool.base-name", "bingo_arena");
        this.worldPoolPrepareOnStartup = cfg.getBoolean("world-pool.prepare-on-startup", false);
        this.worldPoolPrepareDuringMatch = cfg.getBoolean("world-pool.prepare-next-during-match", false);
        this.worldPoolOverworldRadius = Math.max(352,
                cfg.getInt("world-pool.pregeneration.overworld-radius", 352));
        this.worldPoolNetherRadius = Math.max(320,
                cfg.getInt("world-pool.pregeneration.nether-radius", 320));
        this.worldPoolEndRadius = Math.max(384,
                cfg.getInt("world-pool.pregeneration.end-radius", 384));
        this.worldPoolUseMvnp = cfg.getBoolean("world-pool.portals.use-multiverse-netherportals", true);
        this.worldPoolRequireMvnp = cfg.getBoolean("world-pool.portals.require-plugin", false);
        this.worldPoolAutoConfigureInventories = cfg.getBoolean(
                "world-pool.inventories.auto-configure-groups", true);
        this.templateMatchesEnabled = cfg.getBoolean("template-worlds.matches.enabled", true);
        this.templateMatchesRequireComplete = cfg.getBoolean(
                "template-worlds.matches.require-complete", true);
        this.overworldTemplateWorld = cfg.getString(
                "template-worlds.overworld.name", "bingo_template_overworld");
        this.netherTemplateWorld = cfg.getString(
                "template-worlds.nether.name", "bingo_template_nether");
        this.endTemplateWorld = cfg.getString(
                "template-worlds.end.name", "bingo_template_end");
        this.safeWorldHandoffEnabled = cfg.getBoolean("safe-world-handoff.enabled", true);
        this.safeWorldHandoffLobbyFallback = cfg.getString(
                "safe-world-handoff.lobby-fallback-world", "legos");
        this.safeWorldHandoffSettleTicks = Math.max(2, Math.min(100,
                cfg.getInt("safe-world-handoff.multiverse-settle-ticks", 10)));
        this.safeWorldHandoffInventoryClearDelayTicks = Math.max(2, Math.min(100,
                cfg.getInt("safe-world-handoff.inventory-clear-delay-ticks", 8)));

        this.worldPreparationNoticeEnabled = cfg.getBoolean(
                "world-preparation-notice.enabled", true);
        this.worldPreparationNoticeShowToNonParticipants = cfg.getBoolean(
                "world-preparation-notice.show-to-non-participants", true);
        this.worldPreparationNoticeShowDuringManualPrepare = cfg.getBoolean(
                "world-preparation-notice.show-during-manual-prepare", false);
        this.worldPreparationNoticePosition = cfg.getString(
                "world-preparation-notice.position", "TOP_LEFT");
        this.worldPreparationNoticeXOffset = Math.max(0, cfg.getInt(
                "world-preparation-notice.x-offset", 8));
        this.worldPreparationNoticeYOffset = Math.max(0, cfg.getInt(
                "world-preparation-notice.y-offset", 52));
        this.worldPreparationNoticeScale = Math.max(0.15, Math.min(0.65, cfg.getDouble(
                "world-preparation-notice.scale", 0.42)));
        this.worldPreparationNoticeFadeInTicks = Math.max(0, Math.min(100, cfg.getInt(
                "world-preparation-notice.fade-in-ticks", 10)));
        this.worldPreparationNoticeFadeOutTicks = Math.max(0, Math.min(100, cfg.getInt(
                "world-preparation-notice.fade-out-ticks", 10)));

        blacklistItems.clear();
        for (String s : cfg.getStringList("goals.blacklist-items")) {
            blacklistItems.add(s.toLowerCase(Locale.ROOT));
        }
        blacklistMods.clear();
        for (String s : cfg.getStringList("goals.blacklist-mods")) {
            blacklistMods.add(s.toLowerCase(Locale.ROOT));
        }

        this.vanillaOnly = cfg.getBoolean("goals.vanilla-only", true);

        loadGoals(cfg);
    }

    private void loadGoals(FileConfiguration cfg) {
        goalPool.clear();
        boolean autoGenerate = cfg.getBoolean("goals.auto-generate", true);

        if (autoGenerate) {
            int poolSize = cfg.getInt("goals.auto-generate-pool-size", 150);
            goalPool = GoalGenerator.generate(poolSize, blacklistItems, blacklistMods, vanillaOnly);
            plugin.getLogger().info("Generados " + goalPool.size() + " objetivos aleatorios de bingo.");
            return;
        }

        List<?> rawGoals = cfg.getList("goals.manual");
        if (rawGoals == null) {
            plugin.getLogger().warning("goals.auto-generate esta en false pero no hay 'goals.manual' configurado.");
            return;
        }

        for (Object obj : rawGoals) {
            if (!(obj instanceof ConfigurationSection) && !(obj instanceof java.util.Map)) continue;
            ConfigurationSection sec = (obj instanceof ConfigurationSection)
                    ? (ConfigurationSection) obj
                    : wrapMap((java.util.Map<?, ?>) obj);

            try {
                String id = sec.getString("id");
                GoalType type = GoalType.valueOf(sec.getString("type", "ITEM_COLLECT").toUpperCase());
                String target = sec.getString("target", null);
                int amount = sec.getInt("amount", 1);
                String displayName = sec.getString("display-name", id);
                String icon = sec.getString("icon", null);

                if (id == null) {
                    plugin.getLogger().warning("Un objetivo manual no tiene 'id', se ignora.");
                    continue;
                }
                if (type != GoalType.CUSTOM_TRIGGER && target == null) {
                    plugin.getLogger().warning("El objetivo '" + id + "' no tiene 'target' y no es CUSTOM_TRIGGER, se ignora.");
                    continue;
                }

                goalPool.add(new BingoGoal(id, type, target, amount, displayName, icon));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().log(Level.WARNING, "Objetivo manual mal configurado, se ignora.", ex);
            }
        }

        plugin.getLogger().info("Cargados " + goalPool.size() + " objetivos manuales de bingo desde config.yml");
    }

    private ConfigurationSection wrapMap(java.util.Map<?, ?> map) {
        org.bukkit.configuration.MemoryConfiguration temp = new org.bukkit.configuration.MemoryConfiguration();
        for (Object key : map.keySet()) {
            temp.set(String.valueOf(key), map.get(key));
        }
        return temp;
    }

    private WinCondition parseWinCondition(String raw) {
        try {
            return WinCondition.valueOf(raw.toUpperCase());
        } catch (Exception e) {
            return WinCondition.POINTS;
        }
    }

    private List<String> structureKeys(FileConfiguration cfg, String path, String fallback) {
        List<String> values = new ArrayList<>(cfg.getStringList(path));
        if (values.isEmpty()) values.add(fallback);
        return values;
    }

    public int getCardSize() { return cardSize; }
    public boolean isTeamMode() { return teamMode; }
    public WinCondition getWinCondition() { return winCondition; }
    public int getTimeLimitMinutes() { return timeLimitMinutes; }
    public boolean isGlowCompletedItems() { return glowCompletedItems; }
    public boolean isBroadcastGoalCompletion() { return broadcastGoalCompletion; }
    public List<BingoGoal> getGoalPool() { return goalPool; }

    public int getMinPlayersToStart() { return minPlayersToStart; }
    public int getMaxPlayersScoreboard() { return maxPlayersScoreboard; }
    public int getAutoStartCountdownSeconds() { return autoStartCountdownSeconds; }
    public int getFinalCountdownSeconds() { return finalCountdownSeconds; }
    public int getCelebrationSeconds() { return celebrationSeconds; }
    public boolean isTiebreakEnabled() { return tiebreakEnabled; }
    public int getTiebreakCountdownSeconds() { return tiebreakCountdownSeconds; }
    public int getTiebreakPlatformRadius() { return tiebreakPlatformRadius; }
    public int getTiebreakSpawnDistance() { return tiebreakSpawnDistance; }
    public Material getTiebreakPlatformMaterial() { return tiebreakPlatformMaterial; }

    public boolean isArenaWorldEnabled() { return multiverseEnabled; }
    public String getLobbyWorld() { return lobbyWorld; }
    public String getGameWorld() { return gameWorld; }
    public List<String> getExtraArenaWorlds() { return extraArenaWorlds; }
    public boolean isRegenOnStart() { return regenOnStart; }
    public boolean isTeleportPlayersOnJoin() { return teleportPlayersOnJoin; }
    public boolean isTeleportPlayersOnStart() { return teleportPlayersOnStart; }
    public boolean isTeleportBackOnEnd() { return teleportBackOnEnd; }

    public boolean isWorldBorderEnabled() { return worldBorderEnabled; }
    public int getWorldBorderMinSize() { return worldBorderMinSize; }
    public int getWorldBorderPadding() { return worldBorderPadding; }
    public int getWorldBorderMaxSize() { return worldBorderMaxSize; }
    public int getWorldBorderSearchRadius() { return worldBorderSearchRadius; }
    public boolean isLocateStronghold() { return locateStronghold; }
    public boolean isLocateNetherStructures() { return locateNetherStructures; }
    public boolean isLocateEndCity() { return locateEndCity; }
    public List<String> getOverworldStructureKeys() { return new ArrayList<>(overworldStructureKeys); }
    public List<String> getNetherStructureKeys() { return new ArrayList<>(netherStructureKeys); }
    public List<String> getEndStructureKeys() { return new ArrayList<>(endStructureKeys); }
    public boolean isWorldBorderPregenerate() { return worldBorderPregenerate; }
    public int getWorldBorderPregenerateRadius() { return worldBorderPregenerateRadius; }
    public boolean isWorldPoolEnabled() { return worldPoolEnabled; }
    public int getWorldPoolSize() { return worldPoolSize; }
    public String getWorldPoolBaseName() { return worldPoolBaseName; }
    public boolean isWorldPoolPrepareOnStartup() { return worldPoolPrepareOnStartup; }
    public boolean isWorldPoolPrepareDuringMatch() { return worldPoolPrepareDuringMatch; }
    public int getWorldPoolOverworldRadius() { return worldPoolOverworldRadius; }
    public int getWorldPoolNetherRadius() { return worldPoolNetherRadius; }
    public int getWorldPoolEndRadius() { return worldPoolEndRadius; }
    public boolean isWorldPoolUseMvnp() { return worldPoolUseMvnp; }
    public boolean isWorldPoolRequireMvnp() { return worldPoolRequireMvnp; }
    public boolean isWorldPoolAutoConfigureInventories() { return worldPoolAutoConfigureInventories; }
    public boolean isTemplateMatchesEnabled() { return templateMatchesEnabled; }
    public boolean isTemplateMatchesRequireComplete() { return templateMatchesRequireComplete; }
    public String getOverworldTemplateWorld() {
        return com.arlight.bingo.template.TemplateRevisionManager
                .activeOverworldWorld(plugin, overworldTemplateWorld);
    }
    public String getNetherTemplateWorld() { return netherTemplateWorld; }
    public String getEndTemplateWorld() { return endTemplateWorld; }
    public boolean isSafeWorldHandoffEnabled() { return safeWorldHandoffEnabled; }
    public String getSafeWorldHandoffLobbyFallback() {
        return safeWorldHandoffLobbyFallback == null || safeWorldHandoffLobbyFallback.isBlank()
                ? "legos" : safeWorldHandoffLobbyFallback;
    }
    public int getSafeWorldHandoffSettleTicks() { return safeWorldHandoffSettleTicks; }
    public int getSafeWorldHandoffInventoryClearDelayTicks() {
        return safeWorldHandoffInventoryClearDelayTicks;
    }

    public boolean isWorldPreparationNoticeEnabled() { return worldPreparationNoticeEnabled; }
    public boolean isWorldPreparationNoticeShowToNonParticipants() {
        return worldPreparationNoticeShowToNonParticipants;
    }
    public boolean isWorldPreparationNoticeShowDuringManualPrepare() {
        return worldPreparationNoticeShowDuringManualPrepare;
    }
    public String getWorldPreparationNoticePosition() {
        return worldPreparationNoticePosition == null ? "TOP_LEFT" : worldPreparationNoticePosition;
    }
    public int getWorldPreparationNoticeXOffset() { return worldPreparationNoticeXOffset; }
    public int getWorldPreparationNoticeYOffset() { return worldPreparationNoticeYOffset; }
    public double getWorldPreparationNoticeScale() { return worldPreparationNoticeScale; }
    public int getWorldPreparationNoticeFadeInTicks() { return worldPreparationNoticeFadeInTicks; }
    public int getWorldPreparationNoticeFadeOutTicks() { return worldPreparationNoticeFadeOutTicks; }

    public Set<String> getBlacklistItems() { return blacklistItems; }
    public Set<String> getBlacklistMods() { return blacklistMods; }
    public boolean isVanillaOnly() { return vanillaOnly; }

    public void setVanillaOnly(boolean value) {
        this.vanillaOnly = value;
        ModularConfigLoader.savePath(plugin, "goals.vanilla-only", value);
        loadGoals(plugin.getConfig());
    }

    public void setLobbyWorld(String world) {
        this.lobbyWorld = world;
        ModularConfigLoader.savePath(plugin, "arena-world.lobby-world", world);
    }

    public void setGameWorld(String world) {
        this.gameWorld = world;
        ModularConfigLoader.savePath(plugin, "arena-world.game-world", world);
    }

    public boolean addExtraArenaWorld(String world) {
        if (extraArenaWorlds.contains(world)) return false;
        extraArenaWorlds.add(world);
        ModularConfigLoader.savePath(plugin, "arena-world.extra-worlds", extraArenaWorlds);
        return true;
    }

    public boolean removeExtraArenaWorld(String world) {
        boolean removed = extraArenaWorlds.remove(world);
        if (removed) {
            plugin.getConfig().set("arena-world.extra-worlds", extraArenaWorlds);
            plugin.saveConfig();
        }
        return removed;
    }

    /** Agrega un item (namespaced key, ej. "minecraft:diamond") a la blacklist y regenera el pool. */
    public boolean addBlacklistItem(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        boolean added = blacklistItems.add(normalized);
        if (added) persistBlacklistAndReload();
        return added;
    }

    public boolean removeBlacklistItem(String key) {
        boolean removed = blacklistItems.remove(key.toLowerCase(Locale.ROOT));
        if (removed) persistBlacklistAndReload();
        return removed;
    }

    /** Banea un mod entero (por su namespace/modid) de la generacion automatica de objetivos. */
    public boolean addBlacklistMod(String modId) {
        String normalized = modId.toLowerCase(Locale.ROOT);
        boolean added = blacklistMods.add(normalized);
        if (added) persistBlacklistAndReload();
        return added;
    }

    public boolean removeBlacklistMod(String modId) {
        boolean removed = blacklistMods.remove(modId.toLowerCase(Locale.ROOT));
        if (removed) persistBlacklistAndReload();
        return removed;
    }

    private void persistBlacklistAndReload() {
        plugin.getConfig().set("goals.blacklist-items", new ArrayList<>(blacklistItems));
        plugin.getConfig().set("goals.blacklist-mods", new ArrayList<>(blacklistMods));
        plugin.saveConfig();
        // Regeneramos el pool ya mismo para que el cambio aplique sin esperar un /bingo reload.
        loadGoals(plugin.getConfig());
    }
}
