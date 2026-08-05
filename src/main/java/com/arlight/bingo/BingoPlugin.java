package com.arlight.bingo;

import com.arlight.bingo.commands.BingoCommand;
import com.arlight.bingo.end.EndEncounterManager;
import com.arlight.bingo.dungeon.AdaptiveDungeonLootManager;
import com.arlight.bingo.dungeon.DimensionDungeonManager;
import com.arlight.bingo.dungeon.DungeonMinionSpawnerManager;
import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.game.GameState;
import com.arlight.bingo.gui.CardGUIListener;
import com.arlight.bingo.listeners.GoalListener;
import com.arlight.bingo.listeners.SignListener;
import com.arlight.bingo.util.BingoScoreboard;
import com.arlight.bingo.util.ConfigManager;
import com.arlight.bingo.util.ArenaWorldManager;
import com.arlight.bingo.util.StorageManager;
import com.arlight.bingo.util.WorldPoolManager;
import com.arlight.bingo.util.PreparationNetwork;
import com.arlight.bingo.util.CardNetwork;
import com.arlight.bingo.util.BingoWaitingNetwork;
import com.arlight.bingo.util.SomitaGuideNetwork;
import com.arlight.bingo.listeners.BingoChatListener;
import com.arlight.bingo.listeners.OverworldVillageSafety;
import com.arlight.bingo.template.OverworldTemplateManager;
import com.arlight.bingo.template.NetherTemplateManager;
import com.arlight.bingo.template.EndTemplateManager;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class BingoPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private BingoGame game;
    private StorageManager storageManager;
    private ArenaWorldManager arenaWorldManager;
    private BingoScoreboard scoreboard;
    private EndEncounterManager endEncounterManager;
    private AdaptiveDungeonLootManager adaptiveDungeonLootManager;
    private DimensionDungeonManager dimensionDungeonManager;
    private DungeonMinionSpawnerManager dungeonMinionSpawnerManager;
    private WorldPoolManager worldPoolManager;
    private OverworldTemplateManager overworldTemplateManager;
    private NetherTemplateManager netherTemplateManager;
    private EndTemplateManager endTemplateManager;
    private SomitaGuideNetwork somitaGuideNetwork;
    private OverworldVillageSafety overworldVillageSafety;

    public WorldPoolManager getWorldPoolManager() { return worldPoolManager; }

    public boolean dispatchOverworldTemplateUtility(CommandSender sender, String rawCommand) {
        return overworldVillageSafety != null
                && overworldVillageSafety.dispatchTemplateUtility(sender, rawCommand);
    }

    public List<String> completeOverworldTemplateUtility(String rawCommand) {
        return overworldVillageSafety == null
                ? List.of()
                : overworldVillageSafety.templateUtilityCompletions(rawCommand);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, PreparationNetwork.CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CardNetwork.CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BingoWaitingNetwork.CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, SomitaGuideNetwork.CHANNEL);
        this.somitaGuideNetwork = new SomitaGuideNetwork(this);

        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.game = new BingoGame(this, configManager);
        this.storageManager = new StorageManager(this);
        this.game.setStorageManager(storageManager);

        this.arenaWorldManager = new ArenaWorldManager(this);
        this.game.setArenaWorldManager(arenaWorldManager);
        com.arlight.bingo.util.WorldBorderManager worldBorderManager = new com.arlight.bingo.util.WorldBorderManager(this);
        this.game.setWorldBorderManager(worldBorderManager);
        this.worldPoolManager = new WorldPoolManager(this, configManager, arenaWorldManager, worldBorderManager);
        this.game.setWorldPoolManager(worldPoolManager);

        this.overworldTemplateManager = new OverworldTemplateManager(this);
        this.netherTemplateManager = new NetherTemplateManager(this);
        this.endTemplateManager = new EndTemplateManager(this);
        worldPoolManager.setTemplateManagers(
                overworldTemplateManager, netherTemplateManager, endTemplateManager);
        getServer().getPluginManager().registerEvents(overworldTemplateManager, this);
        getServer().getPluginManager().registerEvents(netherTemplateManager, this);
        getServer().getPluginManager().registerEvents(endTemplateManager, this);
        this.overworldVillageSafety = new OverworldVillageSafety(this);
        getServer().getPluginManager().registerEvents(overworldVillageSafety, this);
        overworldTemplateManager.resumeIfNeeded();
        netherTemplateManager.resumeIfNeeded();
        endTemplateManager.resumeIfNeeded();

        this.adaptiveDungeonLootManager = new AdaptiveDungeonLootManager(this, game);
        getServer().getPluginManager().registerEvents(adaptiveDungeonLootManager, this);
        this.dungeonMinionSpawnerManager = new DungeonMinionSpawnerManager(this, game);
        this.dimensionDungeonManager = new DimensionDungeonManager(
                this, adaptiveDungeonLootManager, game, dungeonMinionSpawnerManager);
        this.game.setDimensionDungeonManager(dimensionDungeonManager);
        getServer().getPluginManager().registerEvents(dimensionDungeonManager, this);

        this.endEncounterManager = new EndEncounterManager(this);
        this.endEncounterManager.setGame(game);
        this.endEncounterManager.setAdaptiveLootManager(adaptiveDungeonLootManager);
        this.endEncounterManager.setSpawnerManager(dungeonMinionSpawnerManager);
        this.game.setEndEncounterManager(endEncounterManager);
        getServer().getPluginManager().registerEvents(endEncounterManager, this);

        this.scoreboard = new BingoScoreboard();
        this.game.setScoreboard(scoreboard);

        getServer().getPluginManager().registerEvents(new GoalListener(game), this);
        getServer().getPluginManager().registerEvents(new CardGUIListener(game, this), this);
        getServer().getPluginManager().registerEvents(new SignListener(game, arenaWorldManager), this);
        getServer().getPluginManager().registerEvents(new com.arlight.bingo.listeners.GameListener(game), this);
        getServer().getPluginManager().registerEvents(new com.arlight.bingo.listeners.LobbyItemListener(game, this), this);
        getServer().getPluginManager().registerEvents(new BingoChatListener(game), this);
        BingoCommand bingoCommand = new BingoCommand(game, this);
        getCommand("bingo").setExecutor(bingoCommand);
        getCommand("bingo").setTabCompleter(bingoCommand);

        // Si el servidor se reinicio a mitad de una partida, la restauramos.
        boolean restored = storageManager.load(game);
        boolean matchWasActive = restored && (game.getState() == GameState.RUNNING
                || game.getState() == GameState.TIEBREAK || game.getState() == GameState.ENDED);
        worldPoolManager.initialize(matchWasActive ? game.getCurrentArenaWorld() : null);
        if (matchWasActive && !worldPoolManager.hasUsableActiveArena()) {
            getLogger().severe("La partida guardada usaba un slot incompleto de Nether/End. "
                    + "Se canceló de forma segura para reconstruir las tres copias en la próxima partida.");
            game.stop();
            restored = false;
        }
        if (restored && game.getState() == GameState.RUNNING) {
            getLogger().info("Se restauro una partida de bingo que estaba en curso.");
        }

        // Integracion OPCIONAL con ArlightCore (selector de minijuegos + XP al ganar).
        // Si ArlightCore no esta instalado, esto se salta por completo y el resto del
        // plugin funciona exactamente igual.
        if (getServer().getPluginManager().getPlugin("ArlightCore") != null) {
            try {
                com.arlight.bingo.integration.CoreIntegration.register(game);
                getLogger().info("Integracion con ArlightCore activada.");
            } catch (Throwable t) {
                getLogger().warning("No se pudo activar la integracion con ArlightCore: " + t.getMessage());
            }
        }

        // === CONFIGURACIÓN POR DEFECTO PARA SPAWNERS REALES ===
        // Minecraft genera directamente las entidades registradas por ArlightBosses
        // mediante SpawnData/SpawnPotentials del bloque spawner.
        getConfig().addDefault("dungeon-spawners.enabled", true);
        getConfig().addDefault("dungeon-spawners.activation-range", 16);
        getConfig().addDefault("dungeon-spawners.spawn-range", 4);
        getConfig().addDefault("dungeon-spawners.max-nearby-per-spawner", 2);
        getConfig().addDefault("dungeon-spawners.spawn-count", 1);
        getConfig().addDefault("dungeon-spawners.spawn-interval-seconds", 15);
        getConfig().addDefault("dungeon-spawners.spawn-delay-variance-seconds", 5);
        getConfig().addDefault("dungeon-spawners.initial-delay-ticks", 40);
        getConfig().addDefault("dungeon-spawners.count-scaling.enabled", true);
        getConfig().addDefault("dungeon-spawners.count-scaling.normal-group-min-players", 2);
        getConfig().addDefault("dungeon-spawners.count-scaling.normal-group-extra-nearby", 1);
        getConfig().addDefault("dungeon-spawners.count-scaling.large-group-min-players", 5);
        getConfig().addDefault("dungeon-spawners.count-scaling.large-group-extra-nearby", 2);
        getConfig().addDefault("dungeon-spawners.count-scaling.large-group-extra-spawn", 1);

        getConfig().addDefault("overworld-castle.enabled", true);
        getConfig().addDefault("overworld-castle.safe-path-markers", true);
        getConfig().addDefault("overworld-castle.lootr.enabled", true);
        getConfig().addDefault("campaign.enabled", true);
        getConfig().addDefault("campaign.somita-cinematics", true);
        getConfig().addDefault("campaign.somita-guide.enabled", true);
        getConfig().addDefault("campaign.somita-guide.auto-overworld", true);
        getConfig().addDefault("campaign.somita-guide.auto-nether", true);
        getConfig().addDefault("campaign.somita-guide.auto-end", true);
        getConfig().addDefault("somita.enabled", true);
        getConfig().addDefault("somita.effects.enabled", true);
        getConfig().addDefault("somita.effects.intensity", "normal");
        getConfig().addDefault("somita.effects.maximum-particles", 36);
        getConfig().addDefault("somita.effects.reduced-effects-fallback", true);
        getConfig().addDefault("somita.animations.appearance-ticks", 14);
        getConfig().addDefault("somita.animations.vanish-ticks", 18);
        getConfig().addDefault("somita.animations.celebration", "celebrate_cute");
        getConfig().addDefault("campaign.mission-keys.recover-if-missing", true);
        getConfig().addDefault("campaign.conquest.spawner-xp", 8);
        getConfig().addDefault("campaign.conquest.protection-message-cooldown-seconds", 3);
        getConfig().addDefault("campaign.rescue.cooldown-seconds", 6);
        getConfig().addDefault("campaign.rescue.invulnerability-seconds", 5);
        getConfig().addDefault("end-campaign.base-y", 88);
        getConfig().addDefault("end-campaign.remove-vanilla-island", true);
        getConfig().addDefault("reference-city-pass.overworld.enabled", true);
        getConfig().addDefault("reference-city-pass.nether.enabled", true);
        getConfig().addDefault("reference-city-pass.end.enabled", true);
        getConfig().addDefault("end-campaign.clear-radius-x", 190);
        getConfig().addDefault("end-campaign.clear-radius-z", 260);
        getConfig().addDefault("end-campaign.clear-min-y", -32);
        getConfig().addDefault("end-campaign.clear-max-y", 210);
        getConfig().addDefault("end-campaign.void-rescue-cooldown-seconds", 6);
        getConfig().addDefault("dungeon-loot.tier-supplies.enabled", true);
        getConfig().addDefault("dungeon-loot.custom-chests.enabled", true);
        getConfig().addDefault("dungeon-loot.progression-equipment.enabled", true);
        getConfig().addDefault("dungeon-loot.goal-rewards.enabled", true);
        getConfig().addDefault("dungeon-loot.goal-rewards.maximum-per-player-per-dimension", 1);
        getConfig().addDefault("dungeon-loot.goal-rewards.chance.epic", 15.0);
        getConfig().addDefault("dungeon-loot.goal-rewards.chance.legendary", 35.0);
        getConfig().addDefault("dungeon-loot.rare-modded-rewards.enabled", true);
        getConfig().addDefault("dungeon-loot.rare-modded-rewards.maximum-per-player", 1);

        // === MUNDOS PLANTILLA Y COPIA AUTOMÁTICA POR PARTIDA ===
        getConfig().addDefault("template-worlds.overworld.enabled", true);
        getConfig().addDefault("template-worlds.overworld.name", "bingo_template_overworld");
        getConfig().addDefault("template-worlds.overworld.seed", 741905270311L);
        getConfig().addDefault("template-worlds.overworld.radius", 1536);
        getConfig().addDefault("template-worlds.overworld.blocks-per-tick", 1800);
        getConfig().addDefault("template-worlds.overworld.corruption-spacing", 48);
        getConfig().addDefault("template-worlds.overworld.lootr-mode", "LOOT_TABLES");
        getConfig().addDefault("template-worlds.overworld.max-build-millis-per-tick", 8L);
        getConfig().addDefault("template-worlds.overworld.dungeon-extra-spawners", 18);
        getConfig().addDefault("template-worlds.overworld.layout.village-x", -360);
        getConfig().addDefault("template-worlds.overworld.layout.village-z", 0);
        getConfig().addDefault("template-worlds.overworld.layout.city-x", 420);
        getConfig().addDefault("template-worlds.overworld.layout.city-z", 0);
        getConfig().addDefault("template-worlds.overworld.zone-a.enabled", true);
        getConfig().addDefault("template-worlds.overworld.zone-a.irregular-boundary", true);
        getConfig().addDefault("template-worlds.overworld.zone-a.functional-interiors", true);
        getConfig().addDefault("template-worlds.overworld.zone-a.visible-route-to-zone-b", true);
        getConfig().addDefault("template-worlds.overworld.safe-village.enabled", true);
        getConfig().addDefault("template-worlds.overworld.safe-village.radius", 112);
        getConfig().addDefault("template-worlds.overworld.safe-village.purge-interval-ticks", 40L);
        getConfig().addDefault("template-worlds.overworld.autorepair.enabled", true);
        getConfig().addDefault("template-worlds.overworld.autorepair.max-passes", 3);
        getConfig().addDefault("template-worlds.overworld.autorepair.blocks-per-tick", 900);
        getConfig().addDefault("template-worlds.overworld.autorepair.max-millis-per-tick", 5L);
        getConfig().addDefault("template-worlds.overworld.autorepair.preview-radius", 96);
        getConfig().addDefault("template-worlds.overworld.autorepair.rollback.enabled", true);


        getConfig().addDefault("template-worlds.revisions.keep-latest", 3);
        getConfig().addDefault("template-worlds.revisions.require-explicit-promotion", true);
        getConfig().addDefault("template-worlds.safety.chunky-tile-radius", 384);
        getConfig().addDefault("template-worlds.safety.max-loaded-chunks-between-batches", 1024);
        getConfig().addDefault("template-worlds.safety.drain-check-interval-ticks", 100L);
        getConfig().addDefault("template-worlds.safety.stable-drain-checks", 3);
        getConfig().addDefault("template-worlds.safety.build-chunk-ticket-release-delay-ticks", 100L);
        getConfig().addDefault("template-worlds.safety.max-retained-build-chunks", 8);

        getConfig().addDefault("template-worlds.nether.enabled", true);
        getConfig().addDefault("template-worlds.nether.name", "bingo_template_nether");
        getConfig().addDefault("template-worlds.nether.seed", 510932847112L);
        getConfig().addDefault("template-worlds.nether.radius", 1024);
        getConfig().addDefault("template-worlds.nether.blocks-per-tick", 350);
        getConfig().addDefault("template-worlds.nether.max-build-millis-per-tick", 4L);
        getConfig().addDefault("template-worlds.nether.protected-zone.clear-min-y", 36);
        getConfig().addDefault("template-worlds.nether.protected-zone.clear-max-y", 125);
        getConfig().addDefault("template-worlds.nether.finalization-load-interval-ticks", 10L);

        getConfig().addDefault("template-worlds.end.enabled", true);
        getConfig().addDefault("template-worlds.end.name", "bingo_template_end");
        getConfig().addDefault("template-worlds.end.seed", 880174920611L);
        getConfig().addDefault("template-worlds.end.radius", 1024);
        getConfig().addDefault("template-worlds.end.blocks-per-tick", 350);
        getConfig().addDefault("template-worlds.end.max-build-millis-per-tick", 4L);
        getConfig().addDefault("template-worlds.end.finalization-load-interval-ticks", 10L);
        getConfig().addDefault("template-worlds.end.adaptive-supports.maximum-depth", 30);
        getConfig().addDefault("template-worlds.matches.enabled", true);
        getConfig().addDefault("template-worlds.matches.require-complete", true);

        // === AVISO DE PREPARACIÓN PARA NO PARTICIPANTES ===
        getConfig().addDefault("waiting-screen.enabled", true);
        getConfig().addDefault("world-preparation-notice.enabled", true);
        getConfig().addDefault("world-preparation-notice.show-to-non-participants", true);
        getConfig().addDefault("world-preparation-notice.show-during-manual-prepare", false);
        getConfig().addDefault("world-preparation-notice.position", "TOP_LEFT");
        getConfig().addDefault("world-preparation-notice.x-offset", 8);
        getConfig().addDefault("world-preparation-notice.y-offset", 52);
        getConfig().addDefault("world-preparation-notice.scale", 0.42);
        getConfig().addDefault("world-preparation-notice.fade-in-ticks", 10);
        getConfig().addDefault("world-preparation-notice.fade-out-ticks", 10);

        getConfig().options().copyDefaults(true);
        saveConfig();

        getLogger().info("ArlightBingo habilitado correctamente.");
    }

    public DimensionDungeonManager getDimensionDungeonManager() { return dimensionDungeonManager; }
    public EndEncounterManager getEndEncounterManager() { return endEncounterManager; }
    public DungeonMinionSpawnerManager getDungeonMinionSpawnerManager() { return dungeonMinionSpawnerManager; }
    public OverworldTemplateManager getOverworldTemplateManager() { return overworldTemplateManager; }
    public NetherTemplateManager getNetherTemplateManager() { return netherTemplateManager; }
    public EndTemplateManager getEndTemplateManager() { return endTemplateManager; }
    public SomitaGuideNetwork getSomitaGuideNetwork() { return somitaGuideNetwork; }

    @Override
    public void onDisable() {
        if (game != null) {
            if (storageManager != null) {
                // Guardamos el estado tal cual esta (incluyendo RUNNING) para poder
                // restaurar la partida si esto fue un reinicio del servidor.
                storageManager.save(game);
            }
            game.shutdownClientUi();
            game.cancelTimerOnly();
        }
        if (worldPoolManager != null) {
            worldPoolManager.shutdown();
        }
        if (overworldTemplateManager != null) overworldTemplateManager.shutdown();
        if (netherTemplateManager != null) netherTemplateManager.shutdown();
        if (endTemplateManager != null) endTemplateManager.shutdown();
        if (endEncounterManager != null) {
            endEncounterManager.shutdown();
        }
        if (dimensionDungeonManager != null) {
            dimensionDungeonManager.shutdown();
        }
        if (dungeonMinionSpawnerManager != null) {
            dungeonMinionSpawnerManager.shutdown();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, PreparationNetwork.CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CardNetwork.CHANNEL);
        if (somitaGuideNetwork != null) somitaGuideNetwork.clearAll();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, BingoWaitingNetwork.CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, SomitaGuideNetwork.CHANNEL);
        getLogger().info("ArlightBingo deshabilitado.");
    }

    public BingoGame getGame() {
        return game;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public ArenaWorldManager getArenaWorldManager() {
        return arenaWorldManager;
    }
}
