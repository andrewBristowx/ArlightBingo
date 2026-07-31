package com.arlight.bingo.template;

import com.arlight.bingo.BingoPlugin;
import com.arlight.bingo.util.ChunkyBridge;
import com.arlight.bingo.util.CampaignMissionItems;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.loot.Lootable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Genera una plantilla maestra del Overworld una sola vez. Durante una partida
 * futura la carpeta se copiará; no se volverán a construir millones de bloques.
 *
 * La generación se divide en dos fases:
 *  1) Chunky pregenera el mundo vanilla completo dentro del borde.
 *  2) Este constructor añade pueblo, corrupción, minas, fortines y ciudadela
 *     por lotes pequeños en el hilo del servidor.
 */
public final class OverworldTemplateManager implements Listener {

    public enum Stage {
        IDLE, CREATING_WORLD, PREGENERATING, PLANNING, BUILDING, STABILIZING,
        COMPLETE, FAILED, CANCELLED
    }

    private static final String MARKER = "arlight-overworld-template.properties";
    private static final String IN_PROGRESS_MARKER = "arlight-overworld-template-building.properties";
    private static final String VERSION = "1.39.1-overworld-playable-city-v2-hang-fix";
    private static final String STRUCTURE_REVISION = "1.39.1-linear-district-campaign-v2-safe-planning";
    private static final String SOMITA_TAG = "arlightbingo_template_somita";
    private static final String GUIDE_TAG = "arlightbingo_template_guide";
    private static final String VILLAGE_LIFE_TAG = "arlightbingo_village_life";

    private final BingoPlugin plugin;
    private final ChunkyBridge chunky;
    private final NamespacedKey guideKey;
    private final NamespacedKey dungeonXKey;
    private final NamespacedKey dungeonZKey;
    private final NamespacedKey corruptionChunkKey;

    private volatile Stage stage = Stage.IDLE;
    private volatile String detail = "sin iniciar";
    private volatile double progress;
    private volatile Location villageCenter;
    private volatile Location dungeonCenter;
    private OverworldTemplateBuilder builder;
    private CompletableFuture<Void> pregenerationFuture;
    private CompletableFuture<Void> stabilizationFuture;

    public OverworldTemplateManager(BingoPlugin plugin) {
        this.plugin = plugin;
        this.chunky = new ChunkyBridge(plugin);
        this.guideKey = new NamespacedKey(plugin, "template_guide_villager");
        this.dungeonXKey = new NamespacedKey(plugin, "template_dungeon_x");
        this.dungeonZKey = new NamespacedKey(plugin, "template_dungeon_z");
        this.corruptionChunkKey = new NamespacedKey(plugin, "template_corruption_chunk_v2");
    }

    public String worldName() {
        return plugin.getConfig().getString("template-worlds.overworld.name", "bingo_template_overworld");
    }

    public void resumeIfNeeded() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isBusy()) return;
            if (isCompleteOnDisk()) {
                stage = Stage.COMPLETE;
                progress = 1.0D;
                detail = "plantilla completa y disponible para copiar a partidas";
                return;
            }
            if (TemplateMarkerLookup.findMarker(plugin, worldName(), IN_PROGRESS_MARKER).isPresent()) {
                plugin.getLogger().warning("Se detectó una plantilla Overworld interrumpida después de Chunky. Reanudando solo la construcción custom.");
                resume(Bukkit.getConsoleSender());
                return;
            }
            // Existe una plantilla completa de una revisión anterior, pero su versión
            // ya no coincide con la capital jugable V2. Se reutilizan sus chunks y seed:
            // únicamente se ejecuta la fase custom, sin repetir Chunky ni borrar el mundo.
            if (TemplateMarkerLookup.findMarker(plugin, worldName(), MARKER).isPresent()) {
                plugin.getLogger().warning("Plantilla Overworld anterior detectada. Aplicando automáticamente la revisión jugable 1.39.0 sin repetir Chunky.");
                resume(Bukkit.getConsoleSender());
            }
        });
    }

    /**
     * Reanuda la fase custom sobre el mundo existente. Nunca inicia Chunky ni borra
     * la carpeta. El constructor es determinista e idempotente: puede volver a
     * aplicar los mismos bloques después de un reinicio sin duplicar los NPC.
     */
    public synchronized boolean resume(CommandSender sender) {
        if (isBusy()) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una operación activa: " + status());
            return false;
        }
        if (isCompleteOnDisk()) {
            stage = Stage.COMPLETE;
            progress = 1.0D;
            detail = "plantilla completa y disponible para copiar a partidas";
            sender.sendMessage(ChatColor.GREEN + "La plantilla Overworld ya está completa; no se ejecutó Chunky ni se modificó el mundo.");
            return true;
        }
        Path folder = TemplateMarkerLookup.activeFolder(plugin, worldName());
        if (!Files.isDirectory(folder)) {
            sender.sendMessage(ChatColor.RED + "No existe la carpeta del Overworld para reanudar. Usa generate para crearla.");
            return false;
        }

        boolean hadProgressMarker = TemplateMarkerLookup.findMarker(plugin, worldName(), IN_PROGRESS_MARKER).isPresent();
        if (!hadProgressMarker) {
            sender.sendMessage(ChatColor.YELLOW
                    + "No existe un marcador post-Chunky de una versión anterior. Se conservará el mundo y se reanudará únicamente la construcción custom, como solicitaste.");
        }

        long seed = plugin.getConfig().getLong("template-worlds.overworld.seed", 741905270311L);
        World world = loadExistingWorld(seed);
        if (world == null) {
            fail("No se pudo cargar el Overworld existente para reanudarlo.");
            sender.sendMessage(ChatColor.RED + detail);
            return false;
        }
        if (world.getEnvironment() != World.Environment.NORMAL) {
            fail("La carpeta existente no es un Overworld NORMAL.");
            sender.sendMessage(ChatColor.RED + detail);
            return false;
        }
        if (world.getSeed() != seed) {
            sender.sendMessage(ChatColor.YELLOW + "Aviso: la seed del mundo (" + world.getSeed()
                    + ") no coincide con config (" + seed + "). Se conserva la seed real del mundo.");
        }
        if (!writeInProgressMarker(world, "building")) {
            fail("No se pudo crear el marcador reanudable del Overworld.");
            sender.sendMessage(ChatColor.RED + detail);
            return false;
        }
        sender.sendMessage(ChatColor.GREEN + "Reanudando el Overworld existente desde la fase custom. Chunky NO se repetirá y el mundo NO se borrará.");
        startBuild(world);
        return true;
    }

    public boolean isCompleteOnDisk() {
        return TemplateMarkerLookup.hasVersion(
                plugin, worldName(), MARKER, VERSION);
    }

    public boolean isReadyForMatches() {
        return stage == Stage.COMPLETE || isCompleteOnDisk();
    }

    public String markerDiagnostic() {
        return (isCompleteOnDisk() ? "READY" : "MISSING")
                + " [" + TemplateMarkerLookup.checkedFolders(plugin, worldName()) + "]";
    }

    public synchronized boolean generate(CommandSender sender, boolean force) {
        if (!plugin.getConfig().getBoolean("template-worlds.overworld.enabled", true)) {
            sender.sendMessage(ChatColor.RED + "La plantilla Overworld está desactivada en config.yml.");
            return false;
        }
        if (isBusy()) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una plantilla en proceso: " + status());
            return false;
        }

        String name = worldName();
        World loaded = Bukkit.getWorld(name);
        Path folder = plugin.getServer().getWorldContainer().toPath().resolve(name);
        if ((loaded != null || Files.exists(folder)) && !force) {
            sender.sendMessage(ChatColor.YELLOW + "La plantilla '" + name + "' ya existe. Usa /bingo template overworld generate force para reemplazarla.");
            return false;
        }

        if (force && !deleteExisting(name, sender)) return false;

        stage = Stage.CREATING_WORLD;
        detail = "creando mundo vanilla base";
        progress = 0.01D;

        long seed = plugin.getConfig().getLong("template-worlds.overworld.seed", 741905270311L);
        WorldCreator creator = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .seed(seed)
                .generateStructures(true);
        World world = creator.createWorld();
        if (world == null) {
            fail("Bukkit no pudo crear el mundo plantilla.");
            sender.sendMessage(ChatColor.RED + detail);
            return false;
        }

        int radius = Math.max(512, plugin.getConfig().getInt("template-worlds.overworld.radius", 1536));
        world.getWorldBorder().setCenter(0.0D, 0.0D);
        world.getWorldBorder().setSize(radius * 2.0D);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setTime(6000L);
        world.setStorm(false);
        world.setThundering(false);
        world.setAutoSave(true);

        stage = Stage.PREGENERATING;
        detail = "Chunky pregenerando " + (radius * 2) + "×" + (radius * 2) + " bloques";
        progress = 0.03D;
        sender.sendMessage(ChatColor.GREEN + "Plantilla creada. Chunky empezó a pregenerar el Overworld; después se construirá por lotes.");

        int tileRadius = plugin.getConfig().getInt(
                "template-worlds.safety.chunky-tile-radius", 384);
        pregenerationFuture = chunky.generateBatched(
                name,
                world,
                ChunkyBridge.squareTiles(radius, tileRadius),
                safetyLoadedChunkLimit(),
                safetyDrainInterval(),
                safetyStableChecks(),
                message -> {
                    if (stage == Stage.PREGENERATING) detail = message;
                });
        pregenerationFuture.whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            pregenerationFuture = null;
            if (stage == Stage.CANCELLED) return;
            if (error != null) {
                fail("Chunky falló: " + rootMessage(error));
                Bukkit.broadcast(ChatColor.RED + "[Bingo] Falló la plantilla Overworld: " + detail,
                        "arlightbingo.admin");
                return;
            }
            if (!writeInProgressMarker(world, "post_chunky")) {
                fail("Chunky terminó, pero no se pudo guardar el marcador de reanudación.");
                return;
            }
            startBuild(world);
        }));
        return true;
    }

    private void startBuild(World world) {
        if (!writeInProgressMarker(world, "building")) {
            fail("No se pudo guardar el marcador reanudable antes de construir el Overworld.");
            return;
        }
        stage = Stage.PLANNING;
        detail = "calculando pueblo, ciudadela, minas y corrupción";
        progress = 0.20D;
        Location savedVillageSpawn = readMarkerLocation(world, "village");
        Location savedDungeon = readMarkerLocation(world, "dungeon");
        Location preferredVillageCenter = savedVillageSpawn == null ? null
                : new Location(world, savedVillageSpawn.getX() - 3.0D,
                savedVillageSpawn.getY() - 1.0D, savedVillageSpawn.getZ() - 15.0D);
        builder = new OverworldTemplateBuilder(plugin, world, this::onBuildProgress,
                result -> {
                    if (!result.success()) {
                        fail(result.message());
                        Bukkit.broadcast(ChatColor.RED + "[Bingo] Falló la plantilla Overworld: " + result.message(),
                                "arlightbingo.admin");
                        return;
                    }
                    stabilizeCompletedBuild(world, result);
                }, guideKey, dungeonXKey, dungeonZKey, preferredVillageCenter, savedDungeon);
        builder.start();
    }

    private void stabilizeCompletedBuild(World world, BuildResult result) {
        writeInProgressMarker(world, "stabilizing");
        stage = Stage.STABILIZING;
        progress = 0.99D;
        detail = "guardando y descargando los chunks modificados";
        CompletableFuture<Void> drain = chunky.awaitChunkDrain(
                world,
                safetyLoadedChunkLimit(),
                safetyDrainInterval(),
                safetyStableChecks(),
                message -> {
                    if (stage == Stage.STABILIZING) detail = message;
                });
        stabilizationFuture = drain;
        drain.whenComplete((ignored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (stabilizationFuture != drain) return;
                    stabilizationFuture = null;
                    if (stage == Stage.CANCELLED) return;
                    if (error != null) {
                        fail("No se pudo estabilizar el Overworld: " + rootMessage(error));
                        return;
                    }
                    this.villageCenter = result.villageCenter();
                    this.dungeonCenter = result.dungeonCenter();
                    if (!writeMarker(world, result)) {
                        fail("La estructura terminó, pero no se pudo guardar su marcador COMPLETE.");
                        return;
                    }
                    deleteInProgressMarker();
                    stage = Stage.COMPLETE;
                    detail = "plantilla completa y lista para revisar";
                    progress = 1.0D;
                    Bukkit.broadcast(ChatColor.GREEN
                                    + "[Bingo] Plantilla Overworld completada. Usa /bingo template overworld tp para revisarla.",
                            "arlightbingo.admin");
                }));
    }

    private void onBuildProgress(double value, String message) {
        stage = Stage.BUILDING;
        progress = Math.max(0.20D, Math.min(0.99D, value));
        detail = message;
    }

    public synchronized void cancel(CommandSender sender) {
        if (!isBusy()) {
            sender.sendMessage(ChatColor.YELLOW + "No hay una generación activa.");
            return;
        }
        stage = Stage.CANCELLED;
        detail = "cancelada por administrador";
        if (pregenerationFuture != null) pregenerationFuture.cancel(false);
        pregenerationFuture = null;
        if (builder != null) builder.cancel();
        if (stabilizationFuture != null) stabilizationFuture.cancel(false);
        stabilizationFuture = null;
        World world = Bukkit.getWorld(worldName());
        if (world != null) writeInProgressMarker(world, "cancelled");
        sender.sendMessage(ChatColor.YELLOW + "Generación cancelada. El mundo y el marcador se conservan; usa /bingo template overworld resume para continuar sin Chunky.");
    }

    public synchronized boolean reset(CommandSender sender) {
        if (isBusy() || chunky.isRunning()) {
            sender.sendMessage(ChatColor.RED + "La plantilla o Chunky siguen trabajando. Espera a que termine antes de borrar el mundo.");
            return false;
        }
        boolean ok = deleteExisting(worldName(), sender);
        if (ok) {
            stage = Stage.IDLE;
            detail = "sin iniciar";
            progress = 0.0D;
            villageCenter = null;
            dungeonCenter = null;
        }
        return ok;
    }

    private boolean deleteExisting(String name, CommandSender sender) {
        World loaded = Bukkit.getWorld(name);
        Path folder = loaded != null
                ? loaded.getWorldFolder().toPath().toAbsolutePath().normalize()
                : TemplateMarkerLookup.activeFolder(plugin, name);
        if (loaded != null) {
            for (Player player : new ArrayList<>(loaded.getPlayers())) {
                player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
            }
            if (!Bukkit.unloadWorld(loaded, false)) {
                sender.sendMessage(ChatColor.RED + "No se pudo descargar el mundo '" + name + "'.");
                return false;
            }
        }
        if (!Files.exists(folder)) {
            TemplateMarkerLookup.forgetMarker(plugin, name, MARKER);
            TemplateMarkerLookup.forgetMarker(plugin, name, IN_PROGRESS_MARKER);
            TemplateMarkerLookup.forgetWorld(plugin, name);
            return true;
        }
        try (var walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException exception) { throw new RuntimeException(exception); }
            });
            TemplateMarkerLookup.forgetMarker(plugin, name, MARKER);
            TemplateMarkerLookup.forgetMarker(plugin, name, IN_PROGRESS_MARKER);
            TemplateMarkerLookup.forgetWorld(plugin, name);
            sender.sendMessage(ChatColor.YELLOW + "Plantilla anterior eliminada: " + name + ".");
            return true;
        } catch (Throwable error) {
            sender.sendMessage(ChatColor.RED + "No se pudo borrar la plantilla: " + rootMessage(error));
            return false;
        }
    }

    public boolean teleport(Player player) {
        World world = Bukkit.getWorld(worldName());
        if (world == null && Files.isDirectory(TemplateMarkerLookup.activeFolder(plugin, worldName()))) {
            long seed = plugin.getConfig().getLong("template-worlds.overworld.seed", 741905270311L);
            world = new WorldCreator(worldName()).environment(World.Environment.NORMAL)
                    .type(WorldType.NORMAL).seed(seed).generateStructures(true).createWorld();
        }
        if (world == null) return false;
        if (villageCenter == null) villageCenter = readMarkerLocation(world, "village");
        if (dungeonCenter == null) dungeonCenter = readMarkerLocation(world, "dungeon");
        installMatchActors(world);
        Location target = villageCenter != null ? villageCenter.clone().add(0.5, 1.0, 0.5) : world.getSpawnLocation();
        return player.teleport(target);
    }

    /**
     * Reinstala los dos NPC de la historia en una copia del mundo. Las regiones
     * de entidades se excluyen al clonar para no arrastrar jefes de prueba.
     */
    public void installMatchActors(World world) {
        if (world == null) return;
        ensureMatchActors(world);
        Bukkit.getScheduler().runTaskLater(plugin, () -> ensureMatchActors(world), 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> ensureMatchActors(world), 100L);
    }

    private void ensureMatchActors(World world) {
        Location village = readMarkerLocation(world, "village");
        Location dungeon = readMarkerLocation(world, "dungeon");
        if (village == null || dungeon == null) return;
        Location guideHome = readMarkerLocation(world, "guide");
        Location somitaHome = readMarkerLocation(world, "somita");
        if (guideHome == null) guideHome = village.clone().add(-20.5D, 1.0D, -5.5D);
        if (somitaHome == null) somitaHome = village.clone().add(13.5D, 1.0D, -3.5D);
        guideHome.setYaw(90.0F);
        somitaHome.setYaw(210.0F);

        Villager existingGuide = null;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Villager villager
                    && (entity.getScoreboardTags().contains(GUIDE_TAG)
                    || villager.getPersistentDataContainer().has(guideKey, PersistentDataType.BYTE))) {
                existingGuide = villager;
            }
            if (entity.getScoreboardTags().contains(SOMITA_TAG)) entity.remove();
        }

        if (existingGuide == null) {
            Location finalGuideHome = guideHome;
            Villager guide = world.spawn(finalGuideHome, Villager.class, villager -> {
                villager.setAI(false);
                villager.setInvulnerable(true);
                villager.setPersistent(true);
                villager.setRemoveWhenFarAway(false);
                villager.setSilent(false);
                villager.setProfession(Villager.Profession.CARTOGRAPHER);
                villager.setVillagerType(Villager.Type.PLAINS);
                villager.setCustomName(ChatColor.GOLD + "Cartógrafo de la Campana");
                villager.setCustomNameVisible(true);
                villager.getPersistentDataContainer().set(guideKey, PersistentDataType.BYTE, (byte) 1);
                villager.getPersistentDataContainer().set(
                        dungeonXKey, PersistentDataType.INTEGER, dungeon.getBlockX());
                villager.getPersistentDataContainer().set(
                        dungeonZKey, PersistentDataType.INTEGER, dungeon.getBlockZ());
            });
            guide.addScoreboardTag(GUIDE_TAG);
        } else {
            existingGuide.teleport(guideHome);
            existingGuide.setAI(false);
            existingGuide.setInvulnerable(true);
            existingGuide.setPersistent(true);
            existingGuide.setRemoveWhenFarAway(false);
            existingGuide.setProfession(Villager.Profession.CARTOGRAPHER);
            existingGuide.setVillagerType(Villager.Type.PLAINS);
            existingGuide.setCustomName(ChatColor.GOLD + "Cartógrafo de la Campana");
            existingGuide.setCustomNameVisible(true);
            existingGuide.addScoreboardTag(GUIDE_TAG);
            existingGuide.getPersistentDataContainer().set(
                    guideKey, PersistentDataType.BYTE, (byte) 1);
            existingGuide.getPersistentDataContainer().set(
                    dungeonXKey, PersistentDataType.INTEGER, dungeon.getBlockX());
            existingGuide.getPersistentDataContainer().set(
                    dungeonZKey, PersistentDataType.INTEGER, dungeon.getBlockZ());
        }

        // Somita se renderiza de forma personal en ArlightChatClient; no se crea una entidad persistente.
        if (plugin.getConfig().getBoolean("campaign.overworld-playable.living-start-village", true)) {
            ensureLivingVillage(world, village);
        }
    }

    /** Añade habitantes, guardias y animales para que la ciudad inicial se sienta viva. */
    private void ensureLivingVillage(World world, Location villageSpawn) {
        Location center = villageSpawn.clone().add(-3.0D, -1.0D, -15.0D);
        String[] professions = {"FARMER", "WEAPONSMITH", "LIBRARIAN", "BUTCHER",
                "MASON", "CLERIC", "LEATHERWORKER", "FISHERMAN"};
        // Todos los puntos caen sobre las calles construidas; evitamos tejados.
        int[][] villagerOffsets = {
                {-25, 12}, {25, 12}, {-48, 12}, {48, 12},
                {0, -25}, {0, 35}, {-15, 1}, {18, 24}
        };
        for (int i = 0; i < villagerOffsets.length; i++) {
            String tag = VILLAGE_LIFE_TAG + "_villager_" + i;
            if (world.getEntities().stream().anyMatch(entity -> entity.getScoreboardTags().contains(tag))) continue;
            int[] offset = villagerOffsets[i];
            Location at = livingSurface(center.clone().add(offset[0], 0, offset[1]));
            Villager.Profession profession;
            try { profession = Villager.Profession.valueOf(professions[i]); }
            catch (IllegalArgumentException ignored) { profession = Villager.Profession.NONE; }
            Villager.Profession finalProfession = profession;
            Villager villager = world.spawn(at, Villager.class, actor -> {
                actor.setAI(true);
                actor.setInvulnerable(true);
                actor.setPersistent(true);
                actor.setRemoveWhenFarAway(false);
                actor.setProfession(finalProfession);
                actor.setVillagerType(Villager.Type.PLAINS);
                actor.setVillagerLevel(2);
            });
            villager.addScoreboardTag(VILLAGE_LIFE_TAG);
            villager.addScoreboardTag(tag);
        }

        for (int i = 0; i < 2; i++) {
            String tag = VILLAGE_LIFE_TAG + "_golem_" + i;
            if (world.getEntities().stream().anyMatch(entity -> entity.getScoreboardTags().contains(tag))) continue;
            Location at = livingSurface(center.clone().add(i == 0 ? -34 : 34, 0, 12));
            IronGolem golem = world.spawn(at, IronGolem.class, actor -> {
                actor.setPlayerCreated(true);
                actor.setInvulnerable(true);
                actor.setPersistent(true);
                actor.setRemoveWhenFarAway(false);
            });
            golem.addScoreboardTag(VILLAGE_LIFE_TAG);
            golem.addScoreboardTag(tag);
        }

        ensureFarmAnimal(world, center.clone().add(-57, 0, 38), Cow.class, "cow_0");
        ensureFarmAnimal(world, center.clone().add(-53, 0, 42), Cow.class, "cow_1");
        ensureFarmAnimal(world, center.clone().add(57, 0, -41), Sheep.class, "sheep_0");
        ensureFarmAnimal(world, center.clone().add(61, 0, -37), Sheep.class, "sheep_1");
        ensureFarmAnimal(world, center.clone().add(52, 0, -45), Chicken.class, "chicken_0");
    }

    private <T extends Entity> void ensureFarmAnimal(World world, Location rough, Class<T> type, String suffix) {
        String tag = VILLAGE_LIFE_TAG + "_" + suffix;
        if (world.getEntities().stream().anyMatch(entity -> entity.getScoreboardTags().contains(tag))) return;
        T animal = world.spawn(livingSurface(rough), type, actor -> {
            actor.setInvulnerable(true);
            actor.setPersistent(true);
        });
        animal.addScoreboardTag(VILLAGE_LIFE_TAG);
        animal.addScoreboardTag(tag);
    }

    private double horizontalDistanceSquared(Location first, Location second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private Location livingSurface(Location rough) {
        World world = rough.getWorld();
        int x = rough.getBlockX(), z = rough.getBlockZ();
        int y = world.getHighestBlockYAt(x, z) + 1;
        Location result = new Location(world, x + 0.5D, y, z + 0.5D);
        for (int yy = 0; yy <= 2; yy++) result.clone().add(0, yy, 0).getBlock().setType(Material.AIR, false);
        return result;
    }

    public boolean testBoss(Player player) {
        World world = Bukkit.getWorld(worldName());
        if (world == null || !templateMarkerExists(world)) return false;
        Location dungeonLocation = dungeonCenter != null ? dungeonCenter.clone() : readMarkerLocation(world, "dungeon");
        if (dungeonLocation == null) return false;
        Location markedBoss = readMarkerLocation(world, "boss");
        Location arena = markedBoss != null
                ? markedBoss.clone().add(0.5D, 0.0D, 0.5D)
                : dungeonLocation.clone().add(0.5D, 2.0D, 130.5D);
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (entity.getScoreboardTags().contains("arlightbingo_template_surface_boss")) entity.remove();
        }
        player.teleport(arena.clone().add(0.0D, 2.0D, -28.0D));
        String command = String.format(Locale.ROOT,
                "execute in %s run summon arlightbosses:surface_guardian %.2f %.2f %.2f {Tags:[\"arlightbingo_template_surface_boss\"],PersistenceRequired:1b}",
                world.getKey(), arena.getX(), arena.getY(), arena.getZ());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        return true;
    }

    private Location readMarkerLocation(World world, String key) {
        Path file = world.getWorldFolder().toPath().resolve(MARKER);
        if (!Files.exists(file)) return null;
        try {
            for (String line : Files.readAllLines(file)) {
                if (!line.startsWith(key + "=")) continue;
                String[] split = line.substring(key.length() + 1).split(",");
                if (split.length < 3) return null;
                return new Location(world, Double.parseDouble(split[0]), Double.parseDouble(split[1]), Double.parseDouble(split[2]));
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public String status() {
        return stage.name().toLowerCase(Locale.ROOT) + " · " + Math.round(progress * 100.0D) + "% · " + detail;
    }

    public void audit(CommandSender sender) {
        long seed = plugin.getConfig().getLong("template-worlds.overworld.seed", 741905270311L);
        sender.sendMessage(ChatColor.GREEN + "=== Auditoría plantilla Overworld ===");
        for (String line : TemplateAuditUtil.audit(plugin, worldName(), MARKER, IN_PROGRESS_MARKER,
                VERSION, World.Environment.NORMAL, seed)) {
            sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + line);
        }
        Path marker = TemplateMarkerLookup.findMarker(plugin, worldName(), MARKER).orElse(null);
        String revision = TemplateAuditUtil.readProperty(marker, "structureRevision");
        sender.sendMessage(ChatColor.GRAY + "- revisión-estructural=" + ChatColor.WHITE
                + (revision == null ? "ausente" : revision) + " (esperada " + STRUCTURE_REVISION + ")");
        sender.sendMessage(ChatColor.GRAY + "- integración-terreno=" + ChatColor.WHITE
                + "cimientos completos, muros de contención y caminos apoyados");
        sender.sendMessage(ChatColor.GRAY + "- estado-runtime=" + ChatColor.WHITE + status());
        sender.sendMessage(ChatColor.GRAY + "- diagnóstico-marker=" + ChatColor.WHITE + markerDiagnostic());
    }

    public Stage stage() {
        return stage;
    }

    public String lootrStatus() {
        Plugin pluginView = Bukkit.getPluginManager().getPlugin("Lootr");
        boolean modClass = classExists("noobanidus.mods.lootr.common.impl.LootrServiceRegistry")
                || classExists("noobanidus.mods.lootr.Lootr")
                || classExists("noobanidus.mods.lootr.common.Lootr");
        if (pluginView != null) return "Lootr visible desde Bukkit: " + pluginView.getDescription().getVersion();
        if (modClass) return "Lootr detectado como mod. Los cofres y barriles con loot table se dejan listos para su conversión personal.";
        return "Lootr no pudo detectarse desde Bukkit. Los contenedores conservan loot tables vanilla y funcionan como respaldo normal.";
    }

    private boolean classExists(String name) {
        try {
            Class.forName(name, false, plugin.getClass().getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void shutdown() {
        if (pregenerationFuture != null) pregenerationFuture.cancel(false);
        pregenerationFuture = null;
        if (builder != null) builder.cancel();
        if (stabilizationFuture != null) stabilizationFuture.cancel(false);
        stabilizationFuture = null;
    }

    private boolean isBusy() {
        return stage == Stage.CREATING_WORLD || stage == Stage.PREGENERATING
                || stage == Stage.PLANNING || stage == Stage.BUILDING
                || stage == Stage.STABILIZING;
    }

    private void fail(String message) {
        stage = Stage.FAILED;
        detail = message == null ? "error desconocido" : message;
    }

    private boolean writeMarker(World world, BuildResult result) {
        Path file = world.getWorldFolder().toPath().resolve(MARKER);
        Location guide = result.villageCenter().clone().add(-20.5D, 1.0D, -5.5D);
        Location somita = result.villageCenter().clone().add(13.5D, 1.0D, -3.5D);
        Location portal = result.dungeonCenter().clone().add(0.0D, 2.0D, 202.0D);
        String text = "version=" + VERSION + "\n"
                + "village=" + result.villageCenter().getBlockX() + "," + result.villageCenter().getBlockY() + "," + result.villageCenter().getBlockZ() + "\n"
                + "guide=" + guide.getX() + "," + guide.getY() + "," + guide.getZ() + "\n"
                + "somita=" + somita.getX() + "," + somita.getY() + "," + somita.getZ() + "\n"
                + "dungeon=" + result.dungeonCenter().getBlockX() + "," + result.dungeonCenter().getBlockY() + "," + result.dungeonCenter().getBlockZ() + "\n"
                + "boss=" + result.dungeonCenter().getBlockX() + "," + (result.dungeonCenter().getBlockY() + 2) + "," + (result.dungeonCenter().getBlockZ() + 130) + "\n"
                + "portal=" + portal.getX() + "," + portal.getY() + "," + portal.getZ() + "\n"
                + "structureRevision=" + STRUCTURE_REVISION + "\n"
                + "lootrMode=loot_tables\n";
        try {
            world.save();
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            TemplateMarkerLookup.rememberMarker(plugin, worldName(), MARKER, file);
            return true;
        } catch (Throwable exception) {
            plugin.getLogger().warning("No se pudo escribir el marcador de plantilla: " + exception.getMessage());
            return false;
        }
    }

    private World loadExistingWorld(long seed) {
        World world = Bukkit.getWorld(worldName());
        if (world != null) return world;
        Path folder = TemplateMarkerLookup.activeFolder(plugin, worldName());
        if (!Files.isDirectory(folder)) return null;
        return new WorldCreator(worldName())
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .seed(seed)
                .generateStructures(true)
                .createWorld();
    }

    private Path inProgressMarkerPath() {
        return TemplateMarkerLookup.activeFolder(plugin, worldName()).resolve(IN_PROGRESS_MARKER);
    }

    private boolean writeInProgressMarker(World world, String stateName) {
        Path file = world.getWorldFolder().toPath().resolve(IN_PROGRESS_MARKER);
        String text = "version=" + VERSION + "\n"
                + "state=" + stateName + "\n"
                + "seed=" + world.getSeed() + "\n"
                + "chunkyComplete=true\n";
        try {
            world.save();
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            TemplateMarkerLookup.rememberMarker(plugin, worldName(), IN_PROGRESS_MARKER, file);
            return true;
        } catch (Throwable error) {
            plugin.getLogger().warning("No se pudo escribir el marcador reanudable Overworld: " + error.getMessage());
            return false;
        }
    }

    private void deleteInProgressMarker() {
        try {
            Files.deleteIfExists(inProgressMarkerPath());
            TemplateMarkerLookup.forgetMarker(plugin, worldName(), IN_PROGRESS_MARKER);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo borrar el marcador temporal Overworld: " + error.getMessage());
        }
    }

    private int safetyLoadedChunkLimit() {
        return Math.max(64, plugin.getConfig().getInt(
                "template-worlds.safety.max-loaded-chunks-between-batches", 1024));
    }

    private long safetyDrainInterval() {
        return Math.max(20L, plugin.getConfig().getLong(
                "template-worlds.safety.drain-check-interval-ticks", 100L));
    }

    private int safetyStableChecks() {
        return Math.max(2, plugin.getConfig().getInt(
                "template-worlds.safety.stable-drain-checks", 3));
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuideClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        if (!villager.getPersistentDataContainer().has(guideKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        Integer x = villager.getPersistentDataContainer().get(dungeonXKey, PersistentDataType.INTEGER);
        Integer z = villager.getPersistentDataContainer().get(dungeonZKey, PersistentDataType.INTEGER);
        if (x == null || z == null) return;

        Player player = event.getPlayer();
        List<Location> districtEntrances = List.of(
                new Location(villager.getWorld(), x - 170, villager.getY(), z + 100),
                new Location(villager.getWorld(), x + 170, villager.getY(), z + 80),
                new Location(villager.getWorld(), x, villager.getY(), z - 190));
        Location target = districtEntrances.stream()
                .min(Comparator.comparingDouble(candidate -> horizontalDistanceSquared(candidate, villager.getLocation())))
                .orElse(new Location(villager.getWorld(), x, villager.getY(), z));

        player.sendMessage(ChatColor.GOLD + "Cartógrafo del pueblo: " + ChatColor.WHITE
                + "La corrupción más fuerte viene de la Ciudadela de la Esmeralda Musgosa.");
        player.sendMessage(ChatColor.GREEN + "La brújula señala el primer distrito: " + ChatColor.WHITE
                + "X " + target.getBlockX() + " · Z " + target.getBlockZ());
        player.sendMessage(ChatColor.GRAY + "Limpia sus spawners, abre el cofre Arlight y recupera el emblema para continuar.");

        boolean delivered = CampaignMissionItems.giveIfMissing(player, plugin,
                CampaignMissionItems.ROUTE_COMPASS, target);
        player.sendMessage(delivered
                ? ChatColor.LIGHT_PURPLE + "Recibiste la Brújula de la Antigua Calzada."
                : ChatColor.GRAY + "Ya tienes la brújula de la misión.");
        player.playSound(villager.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0F, 1.05F);
    }


    /**
     * Aplica la corrupción únicamente cuando Minecraft ya cargó el chunk.
     * Nunca llama getChunkAt/loadChunk ni recorre el mapa completo.
     */
    @EventHandler
    public void onTemplateChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (!world.getName().equals(worldName())) return;
        if (!plugin.getConfig().getBoolean(
                "template-worlds.overworld.safe-corruption.decorate-on-chunk-load", true)) return;
        // El marcador jamás se consulta desde ChunkLoadEvent. En 1.39.0 esto disparaba
        // una búsqueda recursiva de carpetas por cada chunk y bloqueaba el servidor.
        if (stage != Stage.COMPLETE) return;

        Chunk chunk = event.getChunk();
        if (chunk.getPersistentDataContainer().has(corruptionChunkKey, PersistentDataType.BYTE)) return;

        Bukkit.getScheduler().runTask(plugin, () -> decorateLoadedTemplateChunk(chunk));
    }

    private boolean templateMarkerExists(World world) {
        return TemplateMarkerLookup.hasVersion(
                plugin, world.getName(), MARKER, VERSION);
    }

    private void decorateLoadedTemplateChunk(Chunk chunk) {
        World world = chunk.getWorld();
        if (!world.isChunkLoaded(chunk.getX(), chunk.getZ())) return;
        if (chunk.getPersistentDataContainer().has(corruptionChunkKey, PersistentDataType.BYTE)) return;

        int maximumPatches = Math.max(1, plugin.getConfig().getInt(
                "template-worlds.overworld.safe-corruption.max-patches-per-chunk", 2));
        long seed = world.getSeed()
                ^ (((long) chunk.getX()) * 341873128712L)
                ^ (((long) chunk.getZ()) * 132897987541L)
                ^ 0x5AFE29A1L;
        Random random = new Random(seed);

        int patches = 1 + random.nextInt(maximumPatches);
        for (int i = 0; i < patches; i++) {
            int localX = 3 + random.nextInt(10);
            int localZ = 3 + random.nextInt(10);
            int worldX = (chunk.getX() << 4) + localX;
            int worldZ = (chunk.getZ() << 4) + localZ;
            int y = world.getHighestBlockYAt(worldX, worldZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block surface = world.getBlockAt(worldX, y - 1, worldZ);
            if (!surface.getType().isSolid()
                    || surface.getType() == Material.ICE
                    || surface.getType() == Material.BEDROCK) continue;

            int radius = 1 + random.nextInt(2);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int bx = worldX + dx;
                    int bz = worldZ + dz;
                    if ((bx >> 4) != chunk.getX() || (bz >> 4) != chunk.getZ()) continue;
                    if (dx * dx + dz * dz > radius * radius + 1) continue;
                    int by = world.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) - 1;
                    Block top = world.getBlockAt(bx, by, bz);
                    if (!top.getType().isSolid()) continue;
                    top.setType(random.nextDouble() < 0.12D ? Material.EMERALD_BLOCK : Material.MOSS_BLOCK, false);
                    if (random.nextDouble() < 0.18D && world.getBlockAt(bx, by + 1, bz).isEmpty()) {
                        world.getBlockAt(bx, by + 1, bz).setType(Material.MOSS_CARPET, false);
                    }
                }
            }

            if (random.nextDouble() < 0.55D) {
                int height = 3 + random.nextInt(6);
                for (int dy = 0; dy < height; dy++) {
                    int bx = worldX + dy / 4;
                    int bz = worldZ + dy / 5;
                    if ((bx >> 4) != chunk.getX() || (bz >> 4) != chunk.getZ()) break;
                    Material material = dy % 5 == 0
                            ? Material.VERDANT_FROGLIGHT
                            : (dy % 3 == 0 ? Material.GREEN_GLAZED_TERRACOTTA : Material.EMERALD_BLOCK);
                    world.getBlockAt(bx, y + dy, bz).setType(material, false);
                }
            }
        }

        // Pequeña ruina opcional, siempre confinada al mismo chunk.
        if (random.nextDouble() < plugin.getConfig().getDouble(
                "template-worlds.overworld.safe-corruption.ruin-chance-per-chunk", 0.035D)) {
            buildLazyChunkRuin(chunk, random);
        }

        chunk.getPersistentDataContainer().set(corruptionChunkKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void buildLazyChunkRuin(Chunk chunk, Random random) {
        World world = chunk.getWorld();
        int cx = (chunk.getX() << 4) + 8;
        int cz = (chunk.getZ() << 4) + 8;
        int y = world.getHighestBlockYAt(cx, cz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Material below = world.getBlockAt(cx, y - 1, cz).getType();
        if (!below.isSolid() || below == Material.ICE) return;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.getBlockAt(cx + dx, y - 1, cz + dz).setType(
                        ((dx + dz) & 3) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE, false);
            }
        }
        for (int cornerX : new int[]{-3, 3}) {
            for (int cornerZ : new int[]{-3, 3}) {
                int wallHeight = 2 + random.nextInt(4);
                for (int dy = 0; dy < wallHeight; dy++) {
                    world.getBlockAt(cx + cornerX, y + dy, cz + cornerZ).setType(
                            dy == wallHeight - 1 ? Material.CRACKED_STONE_BRICKS : Material.MOSSY_STONE_BRICKS, false);
                }
            }
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record BuildResult(boolean success, String message, Location villageCenter, Location dungeonCenter) {
        static BuildResult failed(String message) { return new BuildResult(false, message, null, null); }
        static BuildResult success(Location village, Location dungeon) {
            return new BuildResult(true, "ok", village.clone(), dungeon.clone());
        }
    }

    /** Constructor por lotes de la plantilla maestra del Overworld. */
    private static final class OverworldTemplateBuilder {
        private final BingoPlugin plugin;
        private final World world;
        private final Consumer<Progress> progressConsumer;
        private final Consumer<BuildResult> completion;
        private final NamespacedKey guideKey;
        private final NamespacedKey dungeonXKey;
        private final NamespacedKey dungeonZKey;
        private final List<BlockOp> blocks = new ArrayList<>();
        private final List<Runnable> afterBlocks = new ArrayList<>();
        private final Random random;
        private BukkitTask task;
        private BukkitTask planningTask;
        private boolean cancelled;
        private int cursor;
        private int planningPhase;
        private List<Location> explorationPoints = List.of();
        private int explorationIndex;
        private Location village;
        private Location dungeon;
        private final Location preferredVillageCenter;
        private final Location preferredDungeonCenter;

        OverworldTemplateBuilder(BingoPlugin plugin, World world,
                                 BiProgress progress, Consumer<BuildResult> completion,
                                 NamespacedKey guideKey, NamespacedKey dungeonXKey, NamespacedKey dungeonZKey,
                                 Location preferredVillageCenter, Location preferredDungeonCenter) {
            this.plugin = plugin;
            this.world = world;
            this.completion = completion;
            this.progressConsumer = p -> progress.accept(p.value(), p.message());
            this.guideKey = guideKey;
            this.dungeonXKey = dungeonXKey;
            this.dungeonZKey = dungeonZKey;
            this.preferredVillageCenter = preferredVillageCenter == null ? null : preferredVillageCenter.clone();
            this.preferredDungeonCenter = preferredDungeonCenter == null ? null : preferredDungeonCenter.clone();
            this.random = new Random(plugin.getConfig().getLong("template-worlds.overworld.seed", 741905270311L) ^ 0x5EEDB1A6L);
        }

        void start() {
            planningPhase = 0;
            planningTask = Bukkit.getScheduler().runTaskTimer(plugin, this::planningTick, 1L, 1L);
        }

        private void planningTick() {
            if (cancelled) {
                if (planningTask != null) planningTask.cancel();
                return;
            }
            try {
                switch (planningPhase) {
                    case 0 -> {
                        if (preferredVillageCenter != null && preferredDungeonCenter != null) {
                            progressConsumer.accept(new Progress(0.21D,
                                    "actualizando la plantilla existente en sus coordenadas guardadas"));
                            village = preferredVillageCenter.clone();
                            // La 1.39.0 no vuelve a pintar la capital lineal encima de la
                            // fortaleza circular antigua. La desplaza dentro del área ya
                            // pregenerada y actualiza el marcador al terminar. Chunky no se repite.
                            dungeon = preferredDungeonCenter.clone().add(0, 0, 420);
                            buildSpawnVillage(village);
                            progressConsumer.accept(new Progress(0.34D,
                                    "reforzando terreno y aplicando el pueblo profesional sin mover la seed"));
                            buildDungeonRegion(dungeon);
                            planningPhase = 4;
                            break;
                        }
                        progressConsumer.accept(new Progress(0.21D,
                                "seleccionando anclas seguras sin escanear chunks en el hilo principal"));
                        // 1.39.0 analizaba cientos de columnas mediante getHighestBlockYAt en un
                        // único tick. Eso forzaba carga síncrona de chunks y, combinado con el
                        // listener del marcador, activaba el watchdog. En una plantilla nueva
                        // usamos dos anclas deterministas dentro del área pregenerada y el propio
                        // constructor nivela el terreno de cada estructura.
                        village = deterministicAnchor(
                                plugin.getConfig().getInt("template-worlds.overworld.layout.village-x", -360),
                                plugin.getConfig().getInt("template-worlds.overworld.layout.village-z", 0));
                        dungeon = deterministicAnchor(
                                plugin.getConfig().getInt("template-worlds.overworld.layout.city-x", 420),
                                plugin.getConfig().getInt("template-worlds.overworld.layout.city-z", 0));
                        progressConsumer.accept(new Progress(0.23D, "planificando pueblo inicial, aldeano guía y Somita"));
                        buildSpawnVillage(village);
                        // La corrupción global ya no recorre miles de chunks después de Chunky.
                        // Se coloca de forma determinista cuando cada chunk ya está cargado.
                        progressConsumer.accept(new Progress(0.33D,
                                "corrupción global preparada en modo seguro por carga de chunk"));
                        explorationPoints = List.of();
                        explorationIndex = 0;
                        planningPhase = 3;
                    }
                    case 1, 2 -> planningPhase = 3;
                    case 3 -> {
                        progressConsumer.accept(new Progress(0.39D, "planificando tres asentamientos y la Ciudadela de la Esmeralda Musgosa"));
                        buildDungeonRegion(dungeon);
                        planningPhase = 4;
                    }
                    case 4 -> {
                        if (planningTask != null) planningTask.cancel();
                        planningTask = null;
                        startBlockPlacement();
                    }
                    default -> { }
                }
            } catch (Throwable error) {
                stopPlanning(BuildResult.failed("Error planificando la plantilla: " + rootMessage(error)));
            }
        }

        private void startBlockPlacement() {
            int perTick = Math.max(100, plugin.getConfig().getInt("template-worlds.overworld.blocks-per-tick", 500));
            long maxNanos = Math.max(2L,
                    plugin.getConfig().getLong("template-worlds.overworld.max-build-millis-per-tick", 8L)) * 1_000_000L;
            final int total = Math.max(1, blocks.size());
            task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (cancelled) {
                    if (task != null) task.cancel();
                    return;
                }
                int end = Math.min(total, cursor + perTick);
                long deadline = System.nanoTime() + maxNanos;
                while (cursor < end && System.nanoTime() < deadline) apply(blocks.get(cursor++));
                double buildPart = cursor / (double) total;
                progressConsumer.accept(new Progress(0.40D + buildPart * 0.56D,
                        "construyendo plantilla por lotes: " + cursor + "/" + total + " bloques"));
                if (cursor < total) return;
                task.cancel();
                try {
                    for (Runnable runnable : afterBlocks) runnable.run();
                    world.setSpawnLocation(village.clone().add(0.5, 1.0, 0.5));
                    completion.accept(BuildResult.success(village, dungeon));
                } catch (Throwable error) {
                    completion.accept(BuildResult.failed("Falló la fase de entidades/contenedores: " + rootMessage(error)));
                }
            }, 1L, 1L);
        }

        private void stopPlanning(BuildResult result) {
            if (planningTask != null) planningTask.cancel();
            planningTask = null;
            completion.accept(result);
        }

        void cancel() {
            cancelled = true;
            if (planningTask != null) planningTask.cancel();
            if (task != null) task.cancel();
        }

        private Location deterministicAnchor(int x, int z) {
            int border = Math.max(256, (int) (world.getWorldBorder().getSize() / 2.0D) - 180);
            int safeX = Math.max(-border, Math.min(border, x));
            int safeZ = Math.max(-border, Math.min(border, z));
            int y = surfaceY(safeX, safeZ);
            if (y < world.getMinHeight() + 20 || y > 190) y = 80;
            return new Location(world, safeX, y, safeZ);
        }

        private Location findDungeonArea(Location village) {
            int[][] directions = {{1,1},{1,-1},{-1,1},{-1,-1},{1,0},{-1,0},{0,1},{0,-1}};
            Location best = null;
            double bestScore = Double.MAX_VALUE;
            for (int distance : new int[]{720, 860, 1000, 1120}) {
                for (int[] direction : directions) {
                    int x = village.getBlockX() + direction[0] * distance;
                    int z = village.getBlockZ() + direction[1] * distance;
                    Location candidate = findBestArea(x, z, 192, 48, 150, 80, 26);
                    if (candidate == null) continue;
                    double score = areaScore(candidate.getBlockX(), candidate.getBlockZ(), 80, 26);
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
            return best;
        }

        private Location findBestArea(int originX, int originZ, int searchRadius, int step,
                                      int footprintRadius, int sampleRadius, int maxVariation) {
            Location best = null;
            double bestScore = Double.MAX_VALUE;
            for (int ring = 0; ring <= searchRadius; ring += step) {
                for (int x = originX - ring; x <= originX + ring; x += step) {
                    for (int z : new int[]{originZ - ring, originZ + ring}) {
                        Location candidate = candidate(x, z, footprintRadius, sampleRadius, maxVariation);
                        if (candidate == null) continue;
                        double score = areaScore(x, z, sampleRadius, maxVariation);
                        if (score < bestScore) { bestScore = score; best = candidate; }
                    }
                }
                for (int z = originZ - ring + step; z < originZ + ring; z += step) {
                    for (int x : new int[]{originX - ring, originX + ring}) {
                        Location candidate = candidate(x, z, footprintRadius, sampleRadius, maxVariation);
                        if (candidate == null) continue;
                        double score = areaScore(x, z, sampleRadius, maxVariation);
                        if (score < bestScore) { bestScore = score; best = candidate; }
                    }
                }
                if (best != null && ring >= step * 2) break;
            }
            return best;
        }

        private Location candidate(int x, int z, int footprintRadius, int sampleRadius, int maxVariation) {
            int border = (int) (world.getWorldBorder().getSize() / 2.0D) - footprintRadius - 32;
            if (Math.abs(x) > border || Math.abs(z) > border) return null;
            int centerY = surfaceY(x, z);
            if (centerY < world.getMinHeight() + 20 || centerY > 190) return null;
            Material surface = world.getBlockAt(x, centerY - 1, z).getType();
            if (surface == Material.WATER || surface == Material.LAVA || surface == Material.ICE) return null;
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            int wet = 0;
            int wooded = 0;
            for (int dx : new int[]{-sampleRadius, -sampleRadius / 2, 0, sampleRadius / 2, sampleRadius}) {
                for (int dz : new int[]{-sampleRadius, -sampleRadius / 2, 0, sampleRadius / 2, sampleRadius}) {
                    int px = x + dx, pz = z + dz;
                    int y = surfaceY(px, pz);
                    min = Math.min(min, y);
                    max = Math.max(max, y);
                    Material below = world.getBlockAt(px, y - 1, pz).getType();
                    if (below == Material.WATER || below == Material.LAVA || below == Material.ICE) wet++;
                    int surfaceWithLeaves = world.getHighestBlockYAt(px, pz, HeightMap.WORLD_SURFACE);
                    if (surfaceWithLeaves - y >= 3 || isTreeMaterial(below)) wooded++;
                }
            }
            if (max - min > maxVariation || wet > 4) return null;
            // Las estructuras grandes se colocan solamente en una región abierta. Así la arena
            // no nace debajo de una selva ni con copas atravesando murallas y techos.
            if (footprintRadius >= 140 && wooded > 3) return null;
            return new Location(world, x, centerY, z);
        }

        private double areaScore(int x, int z, int sampleRadius, int maxVariation) {
            List<Integer> heights = new ArrayList<>();
            int wet = 0;
            int wooded = 0;
            for (int dx : new int[]{-sampleRadius, -sampleRadius / 2, 0, sampleRadius / 2, sampleRadius}) {
                for (int dz : new int[]{-sampleRadius, -sampleRadius / 2, 0, sampleRadius / 2, sampleRadius}) {
                    int px = x + dx, pz = z + dz;
                    int y = surfaceY(px, pz);
                    heights.add(y);
                    Material below = world.getBlockAt(px, y - 1, pz).getType();
                    if (below == Material.WATER || below == Material.LAVA || below == Material.ICE) wet++;
                    int surfaceWithLeaves = world.getHighestBlockYAt(px, pz, HeightMap.WORLD_SURFACE);
                    if (surfaceWithLeaves - y >= 3 || isTreeMaterial(below)) wooded++;
                }
            }
            double mean = heights.stream().mapToInt(Integer::intValue).average().orElse(80.0D);
            double variance = heights.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0.0D);
            return variance + wet * 250.0D + wooded * 95.0D + Math.max(0.0D, mean - 135.0D) * 10.0D;
        }

        private boolean isTreeMaterial(Material material) {
            String name = material.name();
            return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_LEAVES")
                    || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
        }

        private int surfaceY(int x, int z) {
            return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        }

        private void buildSpawnVillage(Location center) {
            int cx = center.getBlockX();
            int cz = center.getBlockZ();
            int base = medianHeight(cx, cz, 24);

            // El centro visual es una plaza de pueblo; el punto real de aparición está en la
            // calle sur, lejos del agua, de la campana y de cualquier techo.
            terraceCircle(cx, base, cz, 23, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE);
            road(cx, cz + 12, cx + 76, cz + 12, 5, Material.DIRT_PATH);
            road(cx, cz + 12, cx - 76, cz + 12, 5, Material.DIRT_PATH);
            road(cx, cz + 12, cx, cz + 72, 5, Material.DIRT_PATH);
            road(cx, cz + 12, cx, cz - 72, 5, Material.DIRT_PATH);
            road(cx - 54, cz - 40, cx + 62, cz + 46, 3, Material.COARSE_DIRT);

            buildWellAndBell(cx - 9, base + 1, cz);
            buildVillageMarket(cx + 11, base + 1, cz + 1);

            // Edificios de la misma familia visual que el poblado natural cercano: piedra baja,
            // entramado oscuro, tejados inclinados y ampliaciones irregulares.
            buildVillageCottage(cx + 34, cz + 29, 0, false);
            buildVillageCottage(cx - 36, cz + 28, 1, false);
            buildVillageCottage(cx + 39, cz - 27, 2, true);
            buildVillageCottage(cx - 40, cz - 30, 3, false);
            buildVillageCottage(cx + 7, cz + 54, 4, false);
            buildVillageCottage(cx - 8, cz - 57, 5, false);
            buildVillageBarn(cx - 58, cz + 3);
            buildVillageWorkshop(cx + 59, cz + 5);
            buildStorehouse(cx - 68, cz - 13);
            buildVillageInn(cx + 54, cz + 58);
            buildVillageArchive(cx - 62, cz - 48);
            buildFarm(cx - 58, cz + 38, 21, 15);
            buildFarm(cx + 57, cz - 41, 17, 13);
            buildMineEntrance(cx + 72, cz - 16, false);
            decorateVillagePublicSpace(cx, base, cz);

            // Plataforma segura del jugador. El spawn nunca vuelve a caer dentro de la fuente.
            int spawnX = cx + 3, spawnZ = cz + 15;
            for (int x = spawnX - 3; x <= spawnX + 3; x++) for (int z = spawnZ - 3; z <= spawnZ + 3; z++) {
                add(x, base + 1, z, ((x + z) & 3) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
                for (int y = base + 2; y <= base + 6; y++) add(x, y, z, Material.AIR);
            }
            village = new Location(world, spawnX, base + 1, spawnZ);

            // El cartógrafo queda junto a la campana y fuera del mercado. En la versión
            // anterior estaba dentro de la huella del puesto y podía quedar oculto.
            int guideX = cx - 18, guideZ = cz + 9;
            for (int x = guideX - 2; x <= guideX + 2; x++) for (int z = guideZ - 2; z <= guideZ + 2; z++) {
                add(x, base + 1, z, ((x + z) & 1) == 0 ? Material.STONE_BRICKS : Material.MOSSY_STONE_BRICKS);
                for (int y = base + 2; y <= base + 7; y++) add(x, y, z, Material.AIR);
            }
            add(guideX - 2, base + 2, guideZ - 2, Material.OAK_FENCE);
            add(guideX - 2, base + 3, guideZ - 2, Material.LANTERN);

            int somitaX = cx + 16, somitaZ = cz + 11;
            for (int x = somitaX - 2; x <= somitaX + 2; x++) for (int z = somitaZ - 2; z <= somitaZ + 2; z++) {
                add(x, base + 1, z, ((x + z) & 2) == 0 ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE);
                for (int y = base + 2; y <= base + 7; y++) add(x, y, z, Material.AIR);
            }
            add(somitaX + 2, base + 2, somitaZ + 2, Material.SPRUCE_FENCE);
            add(somitaX + 2, base + 3, somitaZ + 2, Material.LANTERN);

            afterBlocks.add(() -> {
                cleanupTemplateEntities(world);
                Location guideHome = new Location(world, guideX + 0.5D, base + 2.0D, guideZ + 0.5D, 180.0F, 0.0F);
                Villager guide = world.spawn(guideHome, Villager.class, villager -> {
                    villager.setAI(false);
                    villager.setInvulnerable(true);
                    villager.setPersistent(true);
                    villager.setSilent(false);
                    villager.setProfession(Villager.Profession.CARTOGRAPHER);
                    villager.setVillagerType(Villager.Type.PLAINS);
                    villager.setCustomName(ChatColor.GOLD + "Cartógrafo de la Campana");
                    villager.setCustomNameVisible(true);
                    villager.getPersistentDataContainer().set(guideKey, PersistentDataType.BYTE, (byte) 1);
                    villager.getPersistentDataContainer().set(dungeonXKey, PersistentDataType.INTEGER, dungeon.getBlockX());
                    villager.getPersistentDataContainer().set(dungeonZKey, PersistentDataType.INTEGER, dungeon.getBlockZ());
                });
                guide.addScoreboardTag(GUIDE_TAG);
            });
        }

        private void buildVillageMarket(int cx, int y, int cz) {
            for (int x = cx - 7; x <= cx + 7; x++) for (int z = cz - 5; z <= cz + 5; z++) {
                add(x, y, z, ((x + z) & 5) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
                for (int yy = y + 1; yy <= y + 5; yy++) add(x, yy, z, Material.AIR);
            }
            for (int[] p : new int[][]{{-6,-4},{-6,4},{6,-4},{6,4}}) {
                for (int yy = 1; yy <= 4; yy++) add(cx + p[0], y + yy, cz + p[1], Material.STRIPPED_SPRUCE_LOG);
            }
            for (int x = cx - 7; x <= cx + 7; x++) for (int z = cz - 5; z <= cz + 5; z++) {
                if (Math.abs(x - cx) + Math.abs(z - cz) <= 10) add(x, y + 5, z, ((x + z) & 1) == 0 ? Material.SPRUCE_SLAB : Material.DARK_OAK_SLAB);
            }
            placeLootContainer(new Location(world, cx - 3, y + 1, cz), LootTables.VILLAGE_PLAINS_HOUSE, true);
            placeLootContainer(new Location(world, cx + 3, y + 1, cz), LootTables.VILLAGE_PLAINS_HOUSE, true);
        }

        private void buildVillageCottage(int cx, int cz, int style, boolean smithy) {
            int width = 11 + (style % 3) * 2;
            int depth = 9 + ((style + 1) % 3) * 2;
            int y = localBuildY(cx, cz, width / 2 + 5, depth / 2 + 5);
            preparePlot(cx, y, cz, width / 2 + 4, depth / 2 + 4);
            int minX = cx - width / 2, maxX = cx + width / 2;
            int minZ = cz - depth / 2, maxZ = cz + depth / 2;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) add(x, y, z, Material.SPRUCE_PLANKS);
            // Ala lateral para evitar casas-caja repetidas.
            int side = (style & 1) == 0 ? 1 : -1;
            for (int x = cx + side * (width / 2 - 1); x != cx + side * (width / 2 + 6); x += side) {
                for (int z = cz - 3; z <= cz + 3; z++) add(x, y, z, Material.SPRUCE_PLANKS);
            }
            for (int yy = 1; yy <= 5 + style % 2; yy++) {
                for (int x = minX; x <= maxX; x++) for (int z : new int[]{minZ, maxZ}) villageWallBlock(x, y + yy, z, yy);
                for (int z = minZ; z <= maxZ; z++) for (int x : new int[]{minX, maxX}) villageWallBlock(x, y + yy, z, yy);
            }
            for (int[] p : new int[][]{{minX,minZ},{minX,maxZ},{maxX,minZ},{maxX,maxZ}}) {
                for (int yy = 1; yy <= 7; yy++) add(p[0], y + yy, p[1], Material.STRIPPED_DARK_OAK_LOG);
            }
            // Ventanas regulares y vigas horizontales acercan las casas al lenguaje del
            // pueblo cercano; dejan de parecer cajas grises con un bloque de techo.
            for (int windowX : new int[]{cx - 3, cx + 3}) {
                add(windowX, y + 3, minZ, Material.GLASS_PANE);
                add(windowX, y + 3, maxZ, Material.GLASS_PANE);
            }
            add(minX, y + 3, cz, Material.GLASS_PANE);
            add(maxX, y + 3, cz, Material.GLASS_PANE);
            for (int x = minX + 1; x < maxX; x++) {
                add(x, y + 5, minZ, Material.STRIPPED_SPRUCE_LOG);
                add(x, y + 5, maxZ, Material.STRIPPED_SPRUCE_LOG);
            }
            add(cx, y + 1, minZ, Material.AIR); add(cx, y + 2, minZ, Material.AIR);
            addData(cx, y + 1, minZ, Material.SPRUCE_DOOR, "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
            addData(cx, y + 2, minZ, Material.SPRUCE_DOOR, "minecraft:spruce_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
            villageGabledRoof(minX - 2, maxX + 2, minZ - 2, maxZ + 2,
                    y + 6, depth >= width, style);
            int annexInnerX = cx + side * (width / 2 - 1);
            int annexOuterX = cx + side * (width / 2 + 6);
            villageGabledRoof(Math.min(annexInnerX, annexOuterX) - 1,
                    Math.max(annexInnerX, annexOuterX) + 1,
                    cz - 4, cz + 4, y + 4, true, style + 1);
            for (int x = cx - 3; x <= cx + 3; x++) {
                add(x, y, minZ - 2, Material.SPRUCE_SLAB);
            }
            for (int yy = 1; yy <= 5; yy++) {
                add(maxX - 2, y + yy, maxZ - 2, yy == 5 ? Material.CAMPFIRE : Material.BRICKS);
            }
            add(cx - 2, y + 1, cz, smithy ? Material.BLAST_FURNACE : Material.CRAFTING_TABLE);
            add(cx + 2, y + 1, cz, smithy ? Material.ANVIL : Material.BARREL);
            placeLootContainer(new Location(world, cx, y + 1, cz + 2), smithy ? LootTables.VILLAGE_WEAPONSMITH : LootTables.VILLAGE_PLAINS_HOUSE, false);
        }

        private void buildVillageBarn(int cx, int cz) {
            int y = localBuildY(cx, cz, 12, 9);
            preparePlot(cx, y, cz, 13, 10);
            for (int x = cx - 10; x <= cx + 10; x++) for (int z = cz - 7; z <= cz + 7; z++) add(x, y, z, Material.COBBLESTONE);
            for (int yy = 1; yy <= 7; yy++) {
                for (int x = cx - 10; x <= cx + 10; x++) for (int z : new int[]{cz - 7, cz + 7}) villageWallBlock(x, y + yy, z, yy);
                for (int z = cz - 7; z <= cz + 7; z++) for (int x : new int[]{cx - 10, cx + 10}) villageWallBlock(x, y + yy, z, yy);
            }
            for (int x : new int[]{cx - 10, cx, cx + 10}) for (int yy = 1; yy <= 9; yy++) {
                add(x, y + yy, cz - 7, Material.STRIPPED_SPRUCE_LOG);
                add(x, y + yy, cz + 7, Material.STRIPPED_SPRUCE_LOG);
            }
            for (int dx = -2; dx <= 2; dx++) { add(cx + dx, y + 1, cz - 7, Material.AIR); add(cx + dx, y + 2, cz - 7, Material.AIR); add(cx + dx, y + 3, cz - 7, Material.AIR); }
            villageGabledRoof(cx - 12, cx + 12, cz - 9, cz + 9, y + 8, false, 6);
            placeLootContainer(new Location(world, cx - 4, y + 1, cz), LootTables.VILLAGE_PLAINS_HOUSE, true);
            placeLootContainer(new Location(world, cx + 4, y + 1, cz), LootTables.VILLAGE_PLAINS_HOUSE, true);
        }

        private void buildVillageWorkshop(int cx, int cz) {
            buildVillageCottage(cx, cz, 7, true);
            int y = localBuildY(cx + 11, cz + 3, 6, 6);
            preparePlot(cx + 11, y, cz + 3, 6, 6);
            for (int x = cx + 6; x <= cx + 16; x++) for (int z = cz - 1; z <= cz + 7; z++) add(x, y, z, Material.STONE_BRICKS);
            for (int yy = 1; yy <= 3; yy++) for (int x : new int[]{cx + 6, cx + 16}) for (int z = cz - 1; z <= cz + 7; z++) add(x, y + yy, z, Material.COBBLESTONE_WALL);
            add(cx + 11, y + 1, cz + 3, Material.ANVIL);
            add(cx + 13, y + 1, cz + 3, Material.SMITHING_TABLE);
        }


        /** Posada principal con comedor, cocina, habitaciones y botín útil. */
        private void buildVillageInn(int cx, int cz) {
            int y = localBuildY(cx, cz, 13, 10);
            preparePlot(cx, y, cz, 14, 11);
            int minX = cx - 11, maxX = cx + 11, minZ = cz - 8, maxZ = cz + 8;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                add(x, y, z, ((x + z) & 7) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
            }
            for (int yy = 1; yy <= 8; yy++) {
                for (int x = minX; x <= maxX; x++) for (int z : new int[]{minZ, maxZ}) {
                    add(x, y + yy, z, yy <= 2 ? Material.STONE_BRICKS : Material.SPRUCE_PLANKS);
                }
                for (int z = minZ; z <= maxZ; z++) for (int x : new int[]{minX, maxX}) {
                    add(x, y + yy, z, yy <= 2 ? Material.STONE_BRICKS : Material.SPRUCE_PLANKS);
                }
            }
            for (int[] corner : new int[][]{{minX,minZ},{minX,maxZ},{maxX,minZ},{maxX,maxZ},{cx,minZ},{cx,maxZ}}) {
                for (int yy = 1; yy <= 10; yy++) add(corner[0], y + yy, corner[1], Material.STRIPPED_DARK_OAK_LOG);
            }
            for (int x = minX + 1; x < maxX; x++) add(x, y + 5, cz, Material.SPRUCE_PLANKS);
            for (int z = minZ + 1; z < maxZ; z++) add(cx, y + 5, z, Material.SPRUCE_PLANKS);
            for (int yy = 1; yy <= 3; yy++) { add(cx, y + yy, minZ, Material.AIR); add(cx, y + yy, cz, Material.AIR); }
            villageGabledRoof(minX - 2, maxX + 2, minZ - 2, maxZ + 2, y + 9, false, 9);

            // Comedor y cocina: el edificio cuenta una historia y recompensa entrar.
            for (int x : new int[]{cx - 6, cx, cx + 6}) {
                add(x, y + 1, cz - 2, Material.SPRUCE_SLAB);
                add(x, y + 1, cz + 2, Material.SPRUCE_SLAB);
                add(x - 1, y + 1, cz - 2, Material.SPRUCE_STAIRS);
                add(x + 1, y + 1, cz - 2, Material.SPRUCE_STAIRS);
            }
            add(cx - 8, y + 1, cz + 5, Material.SMOKER);
            add(cx - 6, y + 1, cz + 5, Material.FURNACE);
            add(cx - 4, y + 1, cz + 5, Material.CAULDRON);
            add(cx + 7, y + 1, cz + 5, Material.BREWING_STAND);
            add(cx + 9, y + 1, cz + 5, Material.BARREL);
            add(cx - 8, y + 6, cz - 4, Material.WHITE_BED);
            add(cx + 8, y + 6, cz - 4, Material.PINK_BED);
            add(cx - 8, y + 6, cz + 4, Material.CHEST);
            add(cx + 8, y + 6, cz + 4, Material.CHEST);
            placeLootContainer(new Location(world, cx - 8, y + 1, cz + 3), LootTables.VILLAGE_BUTCHER, true);
            placeLootContainer(new Location(world, cx + 8, y + 6, cz + 4), LootTables.VILLAGE_PLAINS_HOUSE, false);
        }

        /** Archivo/capilla que funciona como punto de orientación y sala de exploración. */
        private void buildVillageArchive(int cx, int cz) {
            int y = localBuildY(cx, cz, 10, 12);
            preparePlot(cx, y, cz, 11, 13);
            for (int x = cx - 8; x <= cx + 8; x++) for (int z = cz - 10; z <= cz + 10; z++) {
                add(x, y, z, ((x * 3 + z) & 7) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
            }
            for (int yy = 1; yy <= 10; yy++) {
                for (int x = cx - 8; x <= cx + 8; x++) for (int z : new int[]{cz - 10, cz + 10}) {
                    add(x, y + yy, z, yy % 5 == 0 ? Material.STRIPPED_SPRUCE_LOG : Material.COBBLESTONE);
                }
                for (int z = cz - 10; z <= cz + 10; z++) for (int x : new int[]{cx - 8, cx + 8}) {
                    add(x, y + yy, z, yy % 5 == 0 ? Material.STRIPPED_SPRUCE_LOG : Material.COBBLESTONE);
                }
            }
            for (int yy = 1; yy <= 4; yy++) add(cx, y + yy, cz - 10, Material.AIR);
            villageGabledRoof(cx - 10, cx + 10, cz - 12, cz + 12, y + 11, true, 10);
            for (int z = cz - 6; z <= cz + 6; z += 3) {
                for (int x : new int[]{cx - 6, cx + 6}) {
                    add(x, y + 1, z, Material.CHISELED_BOOKSHELF);
                    add(x, y + 2, z, Material.BOOKSHELF);
                }
            }
            add(cx, y + 1, cz + 1, Material.LECTERN);
            add(cx, y + 1, cz + 5, Material.CARTOGRAPHY_TABLE);
            add(cx, y + 6, cz, Material.LANTERN);
            placeLootContainer(new Location(world, cx - 4, y + 1, cz + 7), LootTables.STRONGHOLD_LIBRARY, false);
            placeLootContainer(new Location(world, cx + 4, y + 1, cz + 7), LootTables.VILLAGE_CARTOGRAPHER, true);
        }

        /** Detalles de plaza, descanso, iluminación y pequeñas recompensas visuales. */
        private void decorateVillagePublicSpace(int cx, int base, int cz) {
            int[][] lamps = {{-25,-17},{25,-17},{-25,22},{25,22},{0,34},{0,-34},{45,10},{-45,10}};
            for (int[] p : lamps) {
                int y = surfaceY(cx + p[0], cz + p[1]) - 1;
                add(cx + p[0], y + 1, cz + p[1], Material.SPRUCE_FENCE);
                add(cx + p[0], y + 2, cz + p[1], Material.SPRUCE_FENCE);
                add(cx + p[0], y + 3, cz + p[1], Material.LANTERN);
            }
            int[][] benches = {{-20,17,0},{20,17,1},{-22,-13,0},{22,-13,1}};
            for (int[] b : benches) {
                int y = surfaceY(cx + b[0], cz + b[1]) - 1;
                for (int dx = -2; dx <= 2; dx++) add(cx + b[0] + dx, y + 1, cz + b[1], Material.SPRUCE_SLAB);
                add(cx + b[0] - 2, y + 1, cz + b[1] + (b[2] == 0 ? 1 : -1), Material.SPRUCE_FENCE);
                add(cx + b[0] + 2, y + 1, cz + b[1] + (b[2] == 0 ? 1 : -1), Material.SPRUCE_FENCE);
            }
            // Carro de suministros y pequeño memorial, dos puntos visuales que ayudan a leer la plaza.
            int cartY = surfaceY(cx + 29, cz + 12) - 1;
            for (int x = cx + 26; x <= cx + 32; x++) for (int z = cz + 10; z <= cz + 14; z++) {
                add(x, cartY + 1, z, Material.SPRUCE_PLANKS);
            }
            add(cx + 26, cartY + 1, cz + 10, Material.DARK_OAK_TRAPDOOR);
            add(cx + 32, cartY + 1, cz + 14, Material.DARK_OAK_TRAPDOOR);
            add(cx + 28, cartY + 2, cz + 12, Material.HAY_BLOCK);
            add(cx + 30, cartY + 2, cz + 12, Material.BARREL);

            for (int yy = 1; yy <= 5; yy++) add(cx, base + yy, cz - 18, yy == 5 ? Material.BELL : Material.CHISELED_STONE_BRICKS);
            for (int[] p : new int[][]{{-2,-18},{2,-18},{0,-16},{0,-20}}) {
                add(cx + p[0], base + 1, cz + p[1], Material.FLOWER_POT);
            }
        }

        private void villageWallBlock(int x, int y, int z, int layer) {
            if ((x + z + layer) % 7 == 0) add(x, y, z, Material.MOSSY_COBBLESTONE);
            else if ((x * 13 + z * 7 + layer) % 9 == 0) add(x, y, z, Material.SPRUCE_PLANKS);
            else add(x, y, z, Material.COBBLESTONE);
        }

        private void buildWellAndBell(int cx, int y, int cz) {
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) == 3 || Math.abs(dz) == 3) add(cx + dx, y, cz + dz, Material.STONE_BRICKS);
                else add(cx + dx, y - 1, cz + dz, Material.WATER);
            }
            for (int[] p : new int[][]{{-3,-3},{-3,3},{3,-3},{3,3}}) {
                for (int yy = 1; yy <= 4; yy++) add(cx + p[0], y + yy, cz + p[1], Material.OAK_LOG);
            }
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 6) add(cx + dx, y + 5, cz + dz, Material.DARK_OAK_SLAB);
            }
            add(cx + 5, y, cz, Material.STONE_BRICKS);
            add(cx + 5, y + 1, cz, Material.OAK_FENCE);
            addData(cx + 5, y + 2, cz, Material.BELL, "minecraft:bell[attachment=single_wall,facing=west,powered=false]");
        }

        private void buildHouse(int cx, int cz, int width, int depth, String entrance, boolean smithy) {
            int y = localBuildY(cx, cz, width / 2 + 2, depth / 2 + 2);
            preparePlot(cx, y, cz, width / 2 + 1, depth / 2 + 1);
            int minX = cx - width / 2, maxX = cx + width / 2;
            int minZ = cz - depth / 2, maxZ = cz + depth / 2;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) add(x, y, z, Material.SPRUCE_PLANKS);
            for (int yy = 1; yy <= 5; yy++) {
                for (int x = minX; x <= maxX; x++) for (int z : new int[]{minZ, maxZ}) wallBlock(x, y + yy, z, yy);
                for (int z = minZ; z <= maxZ; z++) for (int x : new int[]{minX, maxX}) wallBlock(x, y + yy, z, yy);
            }
            for (int x : new int[]{minX, maxX}) for (int z : new int[]{minZ, maxZ}) for (int yy = 1; yy <= 7; yy++) add(x, y + yy, z, Material.DARK_OAK_LOG);
            int doorX = entrance.equals("west") ? minX : entrance.equals("east") ? maxX : cx;
            int doorZ = entrance.equals("north") ? minZ : entrance.equals("south") ? maxZ : cz;
            add(doorX, y + 1, doorZ, Material.AIR);
            add(doorX, y + 2, doorZ, Material.AIR);
            if (entrance.equals("west") || entrance.equals("east")) {
                addData(doorX, y + 1, doorZ, Material.SPRUCE_DOOR, "minecraft:spruce_door[facing=" + (entrance.equals("west") ? "west" : "east") + ",half=lower,hinge=left,open=false,powered=false]");
                addData(doorX, y + 2, doorZ, Material.SPRUCE_DOOR, "minecraft:spruce_door[facing=" + (entrance.equals("west") ? "west" : "east") + ",half=upper,hinge=left,open=false,powered=false]");
            }
            roofGable(minX - 1, maxX + 1, minZ - 1, maxZ + 1, y + 6, depth >= width);
            add(cx, y + 1, cz, smithy ? Material.BLAST_FURNACE : Material.CRAFTING_TABLE);
            add(cx + 2, y + 1, cz, smithy ? Material.ANVIL : Material.BARREL);
            placeLootContainer(new Location(world, cx - 2, y + 1, cz), smithy ? LootTables.VILLAGE_WEAPONSMITH : LootTables.VILLAGE_PLAINS_HOUSE, false);
        }

        private void buildStorehouse(int cx, int cz) {
            int y = localBuildY(cx, cz, 9, 7);
            preparePlot(cx, y, cz, 9, 7);
            for (int x = cx - 8; x <= cx + 8; x++) for (int z = cz - 6; z <= cz + 6; z++) add(x, y, z, Material.COBBLESTONE);
            for (int yy = 1; yy <= 6; yy++) {
                for (int x = cx - 8; x <= cx + 8; x++) for (int z : new int[]{cz - 6, cz + 6}) add(x, y + yy, z, (x + yy) % 5 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                for (int z = cz - 6; z <= cz + 6; z++) for (int x : new int[]{cx - 8, cx + 8}) add(x, y + yy, z, (z + yy) % 5 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
            }
            roofGable(cx - 9, cx + 9, cz - 7, cz + 7, y + 7, false);
            placeLootContainer(new Location(world, cx - 4, y + 1, cz), LootTables.VILLAGE_PLAINS_HOUSE, true);
            placeLootContainer(new Location(world, cx, y + 1, cz), LootTables.VILLAGE_TOOLSMITH, true);
            placeLootContainer(new Location(world, cx + 4, y + 1, cz), LootTables.VILLAGE_FISHER, true);
        }

        private void buildFarm(int cx, int cz, int width, int depth) {
            int y = medianHeight(cx, cz, Math.max(width, depth) / 2);
            preparePlot(cx, y, cz, width / 2 + 2, depth / 2 + 2);
            for (int x = cx - width / 2; x <= cx + width / 2; x++) for (int z = cz - depth / 2; z <= cz + depth / 2; z++) {
                if ((x - cx) % 4 == 0) add(x, y, z, Material.WATER);
                else {
                    add(x, y, z, Material.FARMLAND);
                    add(x, y + 1, z, ((x + z) & 1) == 0 ? Material.WHEAT : Material.CARROTS);
                }
            }
            fenceRectangle(cx, y + 1, cz, width / 2 + 1, depth / 2 + 1, Material.OAK_FENCE);
        }

        private List<Location> distributedPoints(int radius, int count, int minSpacing,
                                                 Location village, Location dungeon) {
            List<Location> result = new ArrayList<>();
            int attempts = 0;
            while (result.size() < count && attempts++ < count * 80) {
                int x = random.nextInt(radius * 2 + 1) - radius;
                int z = random.nextInt(radius * 2 + 1) - radius;
                if (horizontalDistance(x, z, village.getBlockX(), village.getBlockZ()) < 170) continue;
                if (horizontalDistance(x, z, dungeon.getBlockX(), dungeon.getBlockZ()) < 260) continue;
                boolean close = result.stream().anyMatch(other -> horizontalDistance(x, z, other.getBlockX(), other.getBlockZ()) < minSpacing);
                if (close) continue;
                int y = surfaceY(x, z);
                Material below = world.getBlockAt(x, y - 1, z).getType();
                if (!below.isSolid() || below == Material.ICE || y > 185) continue;
                result.add(new Location(world, x, y, z));
            }
            return result;
        }

        private void buildMineEntrance(int cx, int cz, boolean hostile) {
            int y = localBuildY(cx, cz, 8, 8);
            preparePlot(cx, y, cz, 8, 8);
            for (int x = cx - 6; x <= cx + 6; x++) for (int z = cz - 6; z <= cz + 6; z++) add(x, y, z, Material.COBBLED_DEEPSLATE);
            for (int yy = 1; yy <= 6; yy++) {
                for (int x = cx - 6; x <= cx + 6; x++) for (int z : new int[]{cz - 6, cz + 6}) add(x, y + yy, z, Material.DEEPSLATE_BRICKS);
                for (int z = cz - 6; z <= cz + 6; z++) for (int x : new int[]{cx - 6, cx + 6}) add(x, y + yy, z, Material.DEEPSLATE_BRICKS);
            }
            for (int step = 0; step < 24; step++) {
                int z = cz + 5 + step;
                int sy = y - step / 2;
                for (int x = cx - 2; x <= cx + 2; x++) {
                    for (int yy = 0; yy <= 4; yy++) add(x, sy + yy, z, yy == 0 ? Material.COBBLED_DEEPSLATE : Material.AIR);
                }
            }
            placeLootContainer(new Location(world, cx - 3, y + 1, cz), LootTables.ABANDONED_MINESHAFT, false);
            if (hostile) placeCustomSpawner(new Location(world, cx + 3, y + 1, cz), "arlightbosses:emerald_zombie_minion");
            crystalSpike(cx, y + 1, cz - 6, 7, new Random(cx * 31L + cz));
        }

        private void buildWatchTower(int cx, int cz, int index) {
            int y = localBuildY(cx, cz, 7, 7);
            preparePlot(cx, y, cz, 7, 7);
            int r = 6;
            for (int yy = 0; yy <= 15; yy++) {
                int rr = yy > 11 ? 7 : r;
                for (int x = cx - rr; x <= cx + rr; x++) for (int z = cz - rr; z <= cz + rr; z++) {
                    boolean wall = Math.abs(x - cx) == rr || Math.abs(z - cz) == rr;
                    if (yy == 0 || wall) add(x, y + yy, z, ((x + z + yy) % 7 == 0) ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                }
            }
            for (int x = cx - 8; x <= cx + 8; x++) for (int z = cz - 8; z <= cz + 8; z++) {
                if (Math.abs(x - cx) == 8 || Math.abs(z - cz) == 8) add(x, y + 16, z, Material.STONE_BRICK_SLAB);
            }
            placeLootContainer(new Location(world, cx, y + 12, cz), index % 3 == 0 ? LootTables.PILLAGER_OUTPOST : LootTables.SIMPLE_DUNGEON, false);
            placeCustomSpawner(new Location(world, cx + 2, y + 1, cz + 2), index % 4 == 0
                    ? "arlightbosses:emerald_skeleton_archer_minion" : "arlightbosses:emerald_zombie_minion");
            crystalSpike(cx - 5, y + 1, cz + 4, 11, new Random(index * 991L));
        }

        private void buildMiniFort(int cx, int cz, int index) {
            int y = localBuildY(cx, cz, 13, 13);
            preparePlot(cx, y, cz, 13, 13);
            int r = 12;
            for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) add(x, y, z, Material.COBBLESTONE);
            for (int yy = 1; yy <= 7; yy++) {
                for (int x = cx - r; x <= cx + r; x++) for (int z : new int[]{cz - r, cz + r}) add(x, y + yy, z, Material.STONE_BRICKS);
                for (int z = cz - r; z <= cz + r; z++) for (int x : new int[]{cx - r, cx + r}) add(x, y + yy, z, Material.STONE_BRICKS);
            }
            for (int[] corner : new int[][]{{-r,-r},{-r,r},{r,-r},{r,r}}) buildRoundTower(cx + corner[0], y, cz + corner[1], 4, 12);
            buildHouse(cx, cz, 13, 11, "south", false);
            placeLootContainer(new Location(world, cx - 5, y + 1, cz + 5), LootTables.SIMPLE_DUNGEON, false);
            placeLootContainer(new Location(world, cx + 5, y + 1, cz + 5), LootTables.PILLAGER_OUTPOST, true);
            placeCustomSpawner(new Location(world, cx, y + 1, cz - 5), index % 3 == 0
                    ? "arlightbosses:emerald_creeper_minion" : "arlightbosses:emerald_ravager_cub_minion");
        }

        private void buildDungeonRegion(Location center) {
            int cx = center.getBlockX();
            int cz = center.getBlockZ();
            int base = medianHeight(cx, cz, 58);
            dungeon = new Location(world, cx, base + 1, cz);

            Location residential = new Location(world, cx - 170,
                    surfaceY(cx - 170, cz + 100), cz + 100);
            Location commercial = new Location(world, cx + 170,
                    surfaceY(cx + 170, cz + 80), cz + 80);
            Location military = new Location(world, cx,
                    surfaceY(cx, cz - 190), cz - 190);

            buildResidentialDistrictV2(residential);
            buildCommercialDistrictV2(commercial);
            buildMilitaryDistrictV2(military);

            // Calzadas anchas y reconocibles. El jugador ve la siguiente zona desde
            // el camino, en vez de encontrar tres asentamientos aislados por el mapa.
            road(residential.getBlockX(), residential.getBlockZ(), cx - 34, cz + 76,
                    7, Material.MOSSY_COBBLESTONE);
            road(commercial.getBlockX(), commercial.getBlockZ(), cx + 34, cz + 76,
                    7, Material.COBBLESTONE);
            road(military.getBlockX(), military.getBlockZ(), cx, cz - 92,
                    8, Material.STONE_BRICKS);
            road(cx - 34, cz + 76, cx, cz + 88, 9, Material.STONE_BRICKS);
            road(cx + 34, cz + 76, cx, cz + 88, 9, Material.STONE_BRICKS);

            prepareCitadelBiome(cx, cz, 112);
            prepareBossBiome(cx, base + 2, cz + 130, 64);
            buildLinearCapitalV2(cx, cz, base);
        }

        /** Distrito inicial: casas utilizables, patios y una plaza de refugiados. */
        private void buildResidentialDistrictV2(Location center) {
            int cx = center.getBlockX(), cz = center.getBlockZ();
            int y = localBuildY(cx, cz, 45, 38);
            preparePlot(cx, y, cz, 45, 38);
            buildDistrictWallV2(cx, y, cz, 44, 37, true);

            road(cx, cz - 36, cx, cz + 36, 6, Material.MOSSY_COBBLESTONE);
            road(cx - 38, cz, cx + 38, cz, 5, Material.COBBLESTONE);
            int[][] homes = {
                    {-25,-22,13,11}, {-8,-23,11,9}, {18,-22,15,11},
                    {-27,18,11,13}, {-7,21,13,11}, {21,19,11,9}
            };
            for (int i = 0; i < homes.length; i++) {
                int[] h = homes[i];
                buildRuinedHouse(cx + h[0], cz + h[1], h[2], h[3], 110 + i);
            }
            buildVillageMarket(cx + 22, y + 1, cz + 1);
            buildWellAndBell(cx - 18, y + 1, cz + 3);
            buildDistrictRewardHallV2(cx, y, cz + 27, Material.EMERALD_BLOCK, 0);

            String[] mobs = {
                    "arlightbosses:emerald_zombie_minion",
                    "arlightbosses:emerald_creeper_minion",
                    "arlightbosses:mossbound_spider_minion",
                    "arlightbosses:emerald_zombie_minion"
            };
            int[][] positions = {{0,0},{-20,-10},{19,13},{0,27}};
            for (int i = 0; i < positions.length; i++) {
                placeCustomSpawner(new Location(world, cx + positions[i][0], y + 2,
                        cz + positions[i][1]), mobs[i]);
            }
            placeLootContainer(new Location(world, cx + 6, y + 2, cz + 27),
                    LootTables.VILLAGE_PLAINS_HOUSE, true);
        }

        /** Segundo distrito: mercado central, almacenes y rutas por tejados. */
        private void buildCommercialDistrictV2(Location center) {
            int cx = center.getBlockX(), cz = center.getBlockZ();
            int y = localBuildY(cx, cz, 48, 40);
            preparePlot(cx, y, cz, 48, 40);
            buildDistrictWallV2(cx, y, cz, 47, 39, true);

            road(cx, cz - 38, cx, cz + 38, 7, Material.COBBLESTONE);
            road(cx - 42, cz - 5, cx + 42, cz - 5, 6, Material.MOSSY_COBBLESTONE);
            buildVillageMarket(cx, y + 1, cz - 5);
            buildCitadelHall(cx - 25, y + 1, cz + 20, 1);
            buildCitadelHall(cx + 27, y + 1, cz + 20, 0);
            buildStorehouse(cx - 31, cz - 25);
            buildVillageWorkshop(cx + 32, cz - 24);
            buildDistrictRewardHallV2(cx, y, cz + 30, Material.GOLD_BLOCK, 1);

            String[] mobs = {
                    "arlightbosses:emerald_zombie_minion",
                    "arlightbosses:emerald_skeleton_archer_minion",
                    "arlightbosses:emerald_ravager_cub_minion",
                    "arlightbosses:mossbound_spider_minion"
            };
            int[][] positions = {{0,0},{-20,-10},{19,13},{0,27}};
            for (int i = 0; i < positions.length; i++) {
                placeCustomSpawner(new Location(world, cx + positions[i][0], y + 2,
                        cz + positions[i][1]), mobs[i]);
            }
            placeLootContainer(new Location(world, cx + 6, y + 2, cz + 30),
                    LootTables.STRONGHOLD_CROSSING, true);
        }

        /** Último distrito: cuarteles, torres y laboratorio esmeralda. */
        private void buildMilitaryDistrictV2(Location center) {
            int cx = center.getBlockX(), cz = center.getBlockZ();
            int y = localBuildY(cx, cz, 50, 43);
            preparePlot(cx, y, cz, 50, 43);
            buildDistrictWallV2(cx, y, cz, 49, 42, false);

            road(cx, cz - 41, cx, cz + 41, 8, Material.STONE_BRICKS);
            road(cx - 44, cz + 4, cx + 44, cz + 4, 6, Material.MOSSY_STONE_BRICKS);
            buildCitadelHall(cx - 27, y + 1, cz - 13, 2);
            buildCitadelHall(cx + 27, y + 1, cz - 13, 3);
            buildWatchTower(cx - 35, cz + 27, 71);
            buildWatchTower(cx + 35, cz + 27, 72);
            buildMineEntrance(cx, cz + 29, true);
            buildDistrictRewardHallV2(cx, y, cz + 32, Material.AMETHYST_BLOCK, 2);

            String[] mobs = {
                    "arlightbosses:emerald_golem_sentinel_minion",
                    "arlightbosses:emerald_skeleton_archer_minion",
                    "arlightbosses:emerald_ravager_cub_minion",
                    "arlightbosses:emerald_creeper_minion"
            };
            int[][] positions = {{0,0},{-20,-10},{19,13},{0,27}};
            for (int i = 0; i < positions.length; i++) {
                placeCustomSpawner(new Location(world, cx + positions[i][0], y + 2,
                        cz + positions[i][1]), mobs[i]);
            }
            placeLootContainer(new Location(world, cx + 6, y + 2, cz + 32),
                    LootTables.PILLAGER_OUTPOST, true);
        }

        private void buildDistrictWallV2(int cx, int y, int cz,
                                         int halfX, int halfZ, boolean woodenAccents) {
            for (int x = cx - halfX; x <= cx + halfX; x++) {
                for (int z : new int[]{cz - halfZ, cz + halfZ}) {
                    boolean gate = Math.abs(x - cx) <= 4;
                    for (int yy = 1; yy <= 7; yy++) {
                        add(x, y + yy, z, gate && yy <= 5 ? Material.AIR
                                : ((x + yy) % 9 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
                    }
                }
            }
            for (int z = cz - halfZ; z <= cz + halfZ; z++) {
                for (int x : new int[]{cx - halfX, cx + halfX}) {
                    for (int yy = 1; yy <= 7; yy++) {
                        add(x, y + yy, z, (z + yy) % 11 == 0
                                ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS);
                    }
                }
            }
            Material accent = woodenAccents ? Material.STRIPPED_DARK_OAK_LOG : Material.DEEPSLATE_BRICKS;
            for (int[] corner : new int[][]{{-halfX,-halfZ},{-halfX,halfZ},{halfX,-halfZ},{halfX,halfZ}}) {
                for (int yy = 1; yy <= 11; yy++) add(cx + corner[0], y + yy, cz + corner[1], accent);
                add(cx + corner[0], y + 12, cz + corner[1], Material.LANTERN);
            }
        }

        private void buildDistrictRewardHallV2(int cx, int y, int cz,
                                               Material emblem, int style) {
            for (int x = cx - 9; x <= cx + 9; x++) for (int z = cz - 7; z <= cz + 7; z++) {
                boolean wall = Math.abs(x - cx) == 9 || Math.abs(z - cz) == 7;
                add(x, y, z, Material.POLISHED_ANDESITE);
                for (int yy = 1; yy <= 7; yy++) {
                    boolean door = z == cz - 7 && Math.abs(x - cx) <= 2 && yy <= 4;
                    add(x, y + yy, z, wall && !door
                            ? ((x + z + yy) % 8 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS)
                            : Material.AIR);
                }
                if (wall) add(x, y + 8, z, style == 1 ? Material.DARK_OAK_SLAB : Material.STONE_BRICK_SLAB);
            }
            add(cx, y + 1, cz + 4, Material.CHISELED_STONE_BRICKS);
            add(cx, y + 2, cz + 4, emblem);
            add(cx - 3, y + 2, cz + 4, Material.LANTERN);
            add(cx + 3, y + 2, cz + 4, Material.LANTERN);
        }

        /** Capital rectangular, con avenida y alas visibles; sustituye la muralla circular. */
        private void buildLinearCapitalV2(int cx, int cz, int base) {
            preparePlot(cx, base, cz + 12, 68, 105);
            int minX = cx - 66, maxX = cx + 66;
            int minZ = cz - 92, maxZ = cz + 104;

            // Muralla rectangular y portón sur. Los bloques son continuos y transitables.
            for (int x = minX; x <= maxX; x++) {
                for (int z : new int[]{minZ, maxZ}) {
                    boolean southGate = z == minZ && Math.abs(x - cx) <= 5;
                    boolean northGate = z == maxZ && Math.abs(x - cx) <= 5;
                    for (int yy = 1; yy <= 13; yy++) {
                        boolean opening = (southGate || northGate) && yy <= 7;
                        add(x, base + yy, z, opening ? Material.AIR
                                : ((x + yy) % 12 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
                    }
                    add(x, base + 14, z, Math.floorMod(x, 2) == 0
                            ? Material.STONE_BRICK_WALL : Material.STONE_BRICK_SLAB);
                }
            }
            for (int z = minZ; z <= maxZ; z++) {
                for (int x : new int[]{minX, maxX}) {
                    for (int yy = 1; yy <= 13; yy++) add(x, base + yy, z,
                            (z + yy) % 13 == 0 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS);
                    add(x, base + 14, z, Math.floorMod(z, 2) == 0
                            ? Material.STONE_BRICK_WALL : Material.STONE_BRICK_SLAB);
                }
            }
            buildRoundTower(minX, base, minZ, 8, 28);
            buildRoundTower(maxX, base, minZ, 8, 28);
            buildRoundTower(minX, base, maxZ, 8, 28);
            buildRoundTower(maxX, base, maxZ, 8, 28);

            road(cx, minZ - 16, cx, maxZ + 16, 11, Material.STONE_BRICKS);
            road(minX + 8, cz - 22, maxX - 8, cz - 22, 7, Material.MOSSY_COBBLESTONE);
            road(minX + 8, cz + 42, maxX - 8, cz + 42, 7, Material.COBBLESTONE);

            buildCitadelHall(cx - 36, base + 1, cz - 48, 0);
            buildCitadelHall(cx + 36, base + 1, cz - 48, 1);
            buildCitadelHall(cx - 37, base + 1, cz + 20, 2);
            buildCitadelHall(cx + 37, base + 1, cz + 20, 3);
            buildCitadelKeep(cx, base + 4, cz + 69);

            // El sello de OverworldCampaignProgression queda en z+88, delante del
            // corredor final hacia el anfiteatro del Guardián.
            for (int x = cx - 7; x <= cx + 7; x++) for (int yy = 1; yy <= 9; yy++) {
                boolean opening = Math.abs(x - cx) <= 4 && yy <= 6;
                add(x, base + yy, cz + 88, opening ? Material.AIR : Material.DEEPSLATE_BRICKS);
            }
            buildBossAmphitheater(cx, base + 2, cz + 130);
            buildProgressionPortalDais(cx, base + 2, cz + 202);

            String[] guards = {
                    "arlightbosses:emerald_zombie_minion",
                    "arlightbosses:emerald_skeleton_archer_minion",
                    "arlightbosses:emerald_creeper_minion",
                    "arlightbosses:emerald_ravager_cub_minion",
                    "arlightbosses:emerald_golem_sentinel_minion"
            };
            int[][] positions = {{-42,-66},{42,-66},{-46,-6},{46,-6},{0,55}};
            for (int i = 0; i < positions.length; i++) {
                placeCustomSpawner(new Location(world, cx + positions[i][0], base + 2,
                        cz + positions[i][1]), guards[i]);
            }
            placeLootContainer(new Location(world, cx - 18, base + 2, cz + 72),
                    LootTables.STRONGHOLD_CROSSING, false);
            placeLootContainer(new Location(world, cx + 18, base + 2, cz + 72),
                    LootTables.STRONGHOLD_CORRIDOR, false);
        }

        private void prepareCitadelBiome(int cx, int cz, int radius) {
            for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
                double distance = Math.hypot(x - cx, z - cz);
                if (distance > radius) continue;
                int top = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE) - 1;
                int ground = top;
                int limit = Math.max(world.getMinHeight() + 2, top - 42);
                while (ground > limit && isVegetationMaterial(world.getBlockAt(x, ground, z).getType())) ground--;
                for (int yy = ground + 1; yy <= Math.min(top + 2, ground + 42); yy++) {
                    Material existing = world.getBlockAt(x, yy, z).getType();
                    if (!existing.isAir()) add(x, yy, z, Material.AIR);
                }
                Material surface = distance < radius - 12
                        ? (((x * 17 + z * 11) & 15) == 0 ? Material.MOSS_BLOCK : Material.COARSE_DIRT)
                        : Material.MOSS_BLOCK;
                add(x, ground, z, surface);
            }
        }

        private boolean isVegetationMaterial(Material material) {
            String name = material.name();
            return isTreeMaterial(material) || name.contains("VINE") || name.contains("GRASS")
                    || name.contains("FERN") || name.contains("BUSH") || name.contains("FLOWER")
                    || material == Material.MOSS_CARPET || material == Material.SNOW;
        }

        private void prepareBossBiome(int cx, int y, int cz, int radius) {
            int outer = radius + 10;
            for (int x = cx - outer; x <= cx + outer; x++) for (int z = cz - outer; z <= cz + outer; z++) {
                double distance = Math.hypot(x - cx, z - cz);
                if (distance > outer) continue;
                int natural = terrainSurfaceY(x, z) - 1;
                double blend = Math.max(0.0D, Math.min(1.0D, (outer - distance) / 10.0D));
                int target = (int) Math.round(natural * (1.0D - blend) + y * blend);
                if (natural < target) {
                    for (int yy = Math.max(natural + 1, target - 8); yy <= target; yy++) add(x, yy, z, yy == target ? Material.MOSS_BLOCK : Material.COBBLESTONE);
                } else {
                    for (int yy = target + 1; yy <= Math.min(natural + 24, target + 32); yy++) add(x, yy, z, Material.AIR);
                    add(x, target, z, distance < radius - 8 ? (((x + z) & 9) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS) : Material.MOSS_BLOCK);
                }
                if (distance <= radius) {
                    for (int yy = target + 1; yy <= target + 20; yy++) add(x, yy, z, Material.AIR);
                }
            }
        }

        private void buildFortifiedSettlement(Location center, int index) {
            int cx = center.getBlockX(), cz = center.getBlockZ();
            int y = localBuildY(cx, cz, 42, 42);
            terraceCircle(cx, y, cz, 43, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE);

            // Muralla orgánica con cinco baluartes y dos puertas. Ya no es un rectángulo cerrado.
            for (int degree = 0; degree < 360; degree += 2) {
                boolean gate = (degree >= 84 && degree <= 98) || (degree >= 260 && degree <= 276);
                if (gate) continue;
                double rad = Math.toRadians(degree);
                int radius = 34 + (int) Math.round(4.0D * Math.sin(rad * 3.0D + index));
                int x = cx + (int) Math.round(Math.cos(rad) * radius);
                int z = cz + (int) Math.round(Math.sin(rad) * (radius - 3));
                int wallHeight = 6 + Math.floorMod(degree / 2 + index, 4);
                for (int thick = -1; thick <= 1; thick++) {
                    int bx = cx + (int) Math.round(Math.cos(rad) * (radius + thick));
                    int bz = cz + (int) Math.round(Math.sin(rad) * (radius - 3 + thick));
                    for (int yy = 1; yy <= wallHeight; yy++) add(bx, y + yy, bz,
                            (degree + yy) % 13 == 0 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS);
                }
            }
            for (int i = 0; i < 5; i++) {
                double angle = i * Math.PI * 2.0D / 5.0D + index * 0.31D;
                int tx = cx + (int) Math.round(Math.cos(angle) * 36.0D);
                int tz = cz + (int) Math.round(Math.sin(angle) * 33.0D);
                buildRoundTower(tx, y, tz, 5 + i % 2, 14 + i * 2);
            }

            // Calle principal, callejones y edificios con distintos tamaños/orientaciones.
            road(cx, cz - 40, cx, cz + 40, 5, Material.MOSSY_COBBLESTONE);
            road(cx - 34, cz + 6, cx + 34, cz - 8, 4, Material.COBBLESTONE);
            int[][] houses = {{-21,-18,11,9},{18,-20,9,13},{-22,15,13,11},{20,16,15,9},{0,26,11,15},{5,-4,9,9}};
            for (int i = 0; i < houses.length; i++) {
                int[] h = houses[i];
                buildRuinedHouse(cx + h[0], cz + h[1], h[2], h[3], index * 20 + i);
            }
            buildWatchTower(cx - 12, cz + 2, index + 20);
            terraceCircle(cx + 12, y + 1, cz + 3, 8, Material.MOSSY_COBBLESTONE, Material.COBBLESTONE);

            String[] mobs = {
                    "arlightbosses:emerald_zombie_minion",
                    "arlightbosses:emerald_skeleton_archer_minion",
                    "arlightbosses:emerald_creeper_minion",
                    "arlightbosses:mossbound_spider_minion"
            };
            int[][] spawners = {{0,0},{-20,-10},{19,13},{0,27}};
            for (int i = 0; i < spawners.length; i++) {
                placeCustomSpawner(new Location(world, cx + spawners[i][0], y + 2, cz + spawners[i][1]), mobs[(index + i) % mobs.length]);
            }
            placeLootContainer(new Location(world, cx + 6, y + 2, cz + 3), LootTables.STRONGHOLD_CROSSING, false);
            placeLootContainer(new Location(world, cx - 7, y + 2, cz + 8), LootTables.SIMPLE_DUNGEON, true);
            crystalSpike(cx + 30, y + 1, cz - 8, 18, new Random(index * 4471L));
            crystalSpike(cx - 28, y + 1, cz + 12, 14, new Random(index * 8893L));
        }

        private void buildEmeraldCitadel(int cx, int cz, int base) {
            int radius = 96;
            terraceCircle(cx, base, cz, radius, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS);

            // La muralla anterior se dibujaba como columnas angulares y dejaba huecos,
            // saltos verticales y segmentos flotantes. Esta banda octogonal es continua,
            // tiene paseo de ronda y cuatro portones transitables.
            buildContinuousCitadelWall(cx, base, cz);

            int[][] towerData = {{-74,-50,7,25},{74,-50,9,32},{-78,47,8,29},{78,47,7,24},
                    {-45,76,6,22},{45,76,8,27}};
            for (int i = 0; i < towerData.length; i++) {
                int[] t = towerData[i];
                buildRoundTower(cx + t[0], base, cz + t[1], t[2], t[3]);
                crystalSpike(cx + t[0] + (i % 2 == 0 ? 3 : -3), base + 1, cz + t[1], 13 + i * 2, new Random(9000L + i));
            }

            // Distritos conectados, no tres cajas concéntricas.
            road(cx, cz - 92, cx, cz + 128, 9, Material.STONE_BRICKS);
            road(cx - 88, cz, cx + 88, cz, 7, Material.MOSSY_COBBLESTONE);
            road(cx - 66, cz - 58, cx + 64, cz + 62, 5, Material.COBBLESTONE);
            buildCitadelHall(cx - 52, base + 2, cz - 28, 0);
            buildCitadelHall(cx + 50, base + 2, cz - 25, 1);
            buildCitadelHall(cx - 48, base + 3, cz + 38, 2);
            buildCitadelHall(cx + 47, base + 2, cz + 42, 3);
            buildCitadelKeep(cx, base + 6, cz);
            buildBossAmphitheater(cx, base + 2, cz + 130);
            buildProgressionPortalDais(cx, base + 2, cz + 202);

            int wanted = Math.max(12, plugin.getConfig().getInt("template-worlds.overworld.dungeon-extra-spawners", 18));
            String[] mobs = {
                    "arlightbosses:emerald_zombie_minion", "arlightbosses:emerald_skeleton_archer_minion",
                    "arlightbosses:emerald_creeper_minion", "arlightbosses:emerald_ravager_cub_minion",
                    "arlightbosses:emerald_golem_sentinel_minion", "arlightbosses:mossbound_spider_minion"
            };
            for (int i = 0; i < wanted; i++) {
                double angle = Math.PI * 2.0D * i / wanted + (i % 3) * 0.17D;
                int distance = 30 + (i * 23 % 52);
                int x = cx + (int) Math.round(Math.cos(angle) * distance);
                int z = cz + (int) Math.round(Math.sin(angle) * distance);
                placeCustomSpawner(new Location(world, x, base + 3, z), mobs[i % mobs.length]);
            }

            for (int i = 0; i < 30; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                int distance = 18 + random.nextInt(70);
                int x = cx + (int) Math.round(Math.cos(angle) * distance);
                int z = cz + (int) Math.round(Math.sin(angle) * distance);
                crystalSpike(x, base + 1 + random.nextInt(5), z, 7 + random.nextInt(18), random);
            }
        }

        private void buildContinuousCitadelWall(int cx, int base, int cz) {
            for (int dx = -104; dx <= 104; dx++) for (int dz = -100; dz <= 100; dz++) {
                double ax = Math.abs(dx) / 94.0D;
                double az = Math.abs(dz) / 90.0D;
                double metric = Math.max(ax, az) + 0.40D * Math.min(ax, az);
                if (metric < 0.90D || metric > 1.02D) continue;
                boolean northSouthGate = Math.abs(dx) <= 6 && Math.abs(dz) >= 72;
                boolean eastWestGate = Math.abs(dz) <= 6 && Math.abs(dx) >= 76;
                if (northSouthGate || eastWestGate) continue;

                int x = cx + dx, z = cz + dz;
                int height = 14;
                for (int yy = 1; yy <= height; yy++) {
                    Material material = yy == 5 || yy == 11
                            ? Material.MOSSY_STONE_BRICKS
                            : (Math.floorMod(x * 13 + z * 7 + yy, 29) == 0
                            ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS);
                    add(x, base + yy, z, material);
                }
                if (metric <= 0.95D) add(x, base + height + 1, z, Material.STONE_BRICK_SLAB);
                if (metric >= 0.985D && Math.floorMod(x + z, 3) != 0) {
                    add(x, base + height + 1, z, Material.STONE_BRICK_WALL);
                }
            }

            buildCitadelGatehouse(cx, base, cz - 88, true);
            buildCitadelGatehouse(cx, base, cz + 88, true);
            buildCitadelGatehouse(cx - 92, base, cz, false);
            buildCitadelGatehouse(cx + 92, base, cz, false);
        }

        private void buildCitadelGatehouse(int cx, int base, int cz, boolean northSouth) {
            for (int a = -12; a <= 12; a++) for (int b = -4; b <= 4; b++) {
                int x = cx + (northSouth ? a : b);
                int z = cz + (northSouth ? b : a);
                for (int yy = 1; yy <= 16; yy++) {
                    boolean opening = Math.abs(a) <= 4 && yy <= 7;
                    add(x, base + yy, z, opening ? Material.AIR
                            : (yy == 6 || yy == 12 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
                }
                if (Math.abs(a) >= 9) add(x, base + 17, z, Material.STONE_BRICK_WALL);
                else add(x, base + 17, z, Material.STONE_BRICK_SLAB);
            }
            if (northSouth) {
                buildRoundTower(cx - 14, base, cz, 6, 23);
                buildRoundTower(cx + 14, base, cz, 6, 23);
            } else {
                buildRoundTower(cx, base, cz - 14, 6, 23);
                buildRoundTower(cx, base, cz + 14, 6, 23);
            }
        }

        private void buildProgressionPortalDais(int cx, int y, int cz) {
            terraceCircle(cx, y, cz, 13, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS);
            for (int x = cx - 7; x <= cx + 7; x++) for (int z = cz - 5; z <= cz + 5; z++) {
                add(x, y, z, ((x + z) & 5) == 0 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS);
                for (int yy = y + 1; yy <= y + 8; yy++) add(x, yy, z, Material.AIR);
            }
            for (int x : new int[]{cx - 7, cx + 7}) for (int yy = 1; yy <= 9; yy++) {
                add(x, y + yy, cz, yy % 4 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
            }
            for (int x = cx - 5; x <= cx + 5; x++) add(x, y + 8, cz, Material.STONE_BRICK_SLAB);
            // El borde del pedestal toca la entrada norte ya existente del anfiteatro.
            // No se dibuja ningún camino sobre la arena para conservarla intacta.
        }


        private void buildCitadelHall(int cx, int y, int cz, int style) {
            int length = 22 + (style % 2) * 6;
            int width = 12 + (style % 3) * 2;
            boolean rotate = (style & 1) == 1;
            for (int a = -length; a <= length; a++) for (int b = -width; b <= width; b++) {
                int x = cx + (rotate ? b : a);
                int z = cz + (rotate ? a : b);
                boolean wing = (Math.abs(a) <= length && Math.abs(b) <= width)
                        || (Math.abs(a - (style % 2 == 0 ? length - 5 : -length + 5)) <= 7 && Math.abs(b) <= width + 7);
                if (!wing) continue;
                boolean boundary = Math.abs(a) >= length - 1 || Math.abs(b) >= width - 1
                        || (Math.abs(a - (style % 2 == 0 ? length - 5 : -length + 5)) >= 6 && Math.abs(b) > width);
                add(x, y, z, Material.STONE_BRICKS);
                if (boundary) for (int yy = 1; yy <= 9 + style; yy++) {
                    boolean tallWindow = yy >= 3 && yy <= 6
                            && ((Math.abs(a) >= length - 1 && Math.floorMod(b, 7) == 0)
                            || (Math.abs(b) >= width - 1 && Math.floorMod(a, 8) == 0));
                    add(x, y + yy, z, tallWindow ? Material.LIME_STAINED_GLASS_PANE
                            : ((x + z + yy) % 10 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
                }
                int roofY = y + 10 + style - Math.min(width, Math.abs(b)) / 3;
                if (roofY > y + 7) add(x, roofY, z, Material.DARK_OAK_SLAB);

                // Vigas y pasarela interior: dan profundidad sin dividir el salón en cajas.
                if (!boundary && Math.abs(b) <= width - 3 && Math.floorMod(a, 9) == 0) {
                    add(x, y + 7, z, Material.STRIPPED_DARK_OAK_LOG);
                }
            }

            // Dos accesos monumentales alineados con las calles. Se escriben al final para
            // que las fachadas nunca tapen el recorrido de campaña.
            for (int offset = -2; offset <= 2; offset++) for (int yy = 1; yy <= 5; yy++) {
                int frontX = cx + (rotate ? offset : -length);
                int frontZ = cz + (rotate ? -length : offset);
                int backX = cx + (rotate ? offset : length);
                int backZ = cz + (rotate ? length : offset);
                add(frontX, y + yy, frontZ, Material.AIR);
                add(backX, y + yy, backZ, Material.AIR);
            }

            // Contrafuertes, luz e interiores funcionales para que cada distrito no sea
            // solamente una carcasa vacía.
            for (int a : new int[]{-length + 4, -length / 2, length / 2, length - 4}) {
                for (int side : new int[]{-1, 1}) {
                    int b = side * (width + 1);
                    int bx = cx + (rotate ? b : a);
                    int bz = cz + (rotate ? a : b);
                    for (int yy = 1; yy <= 6; yy++) add(bx, y + yy, bz,
                            yy == 6 ? Material.MOSSY_COBBLESTONE_WALL : Material.STONE_BRICKS);
                }
            }
            add(cx - (rotate ? 3 : 0), y + 1, cz - (rotate ? 0 : 3), Material.SMITHING_TABLE);
            add(cx + (rotate ? 3 : 0), y + 1, cz + (rotate ? 0 : 3), Material.CARTOGRAPHY_TABLE);
            add(cx, y + 2, cz + 4, Material.LANTERN);
            placeLootContainer(new Location(world, cx, y + 2, cz), style % 2 == 0 ? LootTables.STRONGHOLD_CORRIDOR : LootTables.SIMPLE_DUNGEON, style == 3);
        }

        private void buildCitadelKeep(int cx, int y, int cz) {
            // Núcleo octogonal con cuatro alas; conserva patios interiores abiertos.
            for (int x = cx - 52; x <= cx + 52; x++) for (int z = cz - 52; z <= cz + 52; z++) {
                int ax = Math.abs(x - cx), az = Math.abs(z - cz);
                double metric = Math.max(ax / 31.0D, az / 31.0D) + 0.42D * Math.min(ax / 31.0D, az / 31.0D);
                boolean central = metric <= 1.0D;
                boolean wing = (ax <= 14 && az <= 50) || (az <= 14 && ax <= 50);
                if (!central && !wing) continue;
                boolean innerCentral = metric < 0.78D;
                boolean innerWing = (ax <= 10 && az <= 44) || (az <= 10 && ax <= 44);
                boolean boundary = !(innerCentral || innerWing);
                add(x, y, z, ((x + z) & 7) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                if (boundary) {
                    int height = central ? 28 : 17;
                    for (int yy = 1; yy <= height; yy++) add(x, y + yy, z,
                            (x + z + yy) % 13 == 0 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS);
                }
                if ((central && metric > 0.88D) || (wing && (ax == 14 || az == 14))) add(x, y + 18, z, Material.STONE_BRICK_SLAB);
            }

            // Portones abiertos hacia las cuatro alas y arcos interiores. Mantienen el núcleo
            // legible desde la calle y evitan que el gran volumen funcione como un laberinto ciego.
            for (int offset = -4; offset <= 4; offset++) for (int yy = 1; yy <= 7; yy++) {
                add(cx + offset, y + yy, cz - 50, Material.AIR);
                add(cx + offset, y + yy, cz + 50, Material.AIR);
                add(cx - 50, y + yy, cz + offset, Material.AIR);
                add(cx + 50, y + yy, cz + offset, Material.AIR);
            }
            for (int offset : new int[]{-22, 22}) {
                for (int yy = 1; yy <= 9; yy++) {
                    add(cx + offset, y + yy, cz - 11, Material.STONE_BRICK_WALL);
                    add(cx + offset, y + yy, cz + 11, Material.STONE_BRICK_WALL);
                    add(cx - 11, y + yy, cz + offset, Material.STONE_BRICK_WALL);
                    add(cx + 11, y + yy, cz + offset, Material.STONE_BRICK_WALL);
                }
            }
            for (int[] light : new int[][]{{-20,-20},{20,-20},{-20,20},{20,20}}) {
                add(cx + light[0], y + 1, cz + light[1], Material.CHISELED_STONE_BRICKS);
                add(cx + light[0], y + 2, cz + light[1], Material.VERDANT_FROGLIGHT);
            }
            int[][] towers = {{-31,-31,8,34},{31,-31,7,29},{-31,31,9,37},{31,31,8,32},{0,-50,6,23},{50,0,7,26}};
            for (int[] t : towers) buildRoundTower(cx + t[0], y, cz + t[1], t[2], t[3]);
            placeLootContainer(new Location(world, cx - 18, y + 2, cz), LootTables.STRONGHOLD_CROSSING, false);
            placeLootContainer(new Location(world, cx + 18, y + 2, cz), LootTables.STRONGHOLD_CORRIDOR, false);
            placeCustomSpawner(new Location(world, cx, y + 2, cz), "arlightbosses:emerald_golem_sentinel_minion");
            placeCustomSpawner(new Location(world, cx, y + 2, cz - 34), "arlightbosses:emerald_skeleton_archer_minion");
            placeCustomSpawner(new Location(world, cx + 34, y + 2, cz), "arlightbosses:emerald_ravager_cub_minion");
        }

        private void buildBossAmphitheater(int cx, int y, int cz) {
            int outer = 56;
            terraceCircle(cx, y, cz, outer + 3, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS);
            // Gradas semicirculares, entradas amplias y centro totalmente libre para el jefe.
            for (int ring = outer; ring >= 30; ring -= 6) {
                int level = y + (outer - ring) / 6;
                for (int degree = 0; degree < 360; degree += 2) {
                    boolean entrance = (degree >= 82 && degree <= 100) || (degree >= 204 && degree <= 222) || (degree >= 318 && degree <= 338);
                    if (entrance) continue;
                    double rad = Math.toRadians(degree);
                    for (int inward = 0; inward < 5; inward++) {
                        int bx = cx + (int) Math.round(Math.cos(rad) * (ring - inward));
                        int bz = cz + (int) Math.round(Math.sin(rad) * (ring - inward));
                        add(bx, level, bz, ((degree + inward + ring) % 11 == 0) ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                    }
                }
            }
            for (int x = cx - 25; x <= cx + 25; x++) for (int z = cz - 25; z <= cz + 25; z++) {
                int d2 = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (d2 <= 25 * 25) {
                    add(x, y, z, d2 <= 10 * 10 ? (((x + z) & 3) == 0 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS) : Material.CRACKED_STONE_BRICKS);
                    for (int yy = y + 1; yy <= y + 14; yy++) add(x, yy, z, Material.AIR);
                }
            }
            // Cinco cristales quedan fuera del área de movimiento, nunca encima del punto de aparición.
            for (int i = 0; i < 5; i++) {
                double angle = -Math.PI / 2.0D + i * Math.PI * 2.0D / 5.0D;
                int px = cx + (int) Math.round(Math.cos(angle) * 38.0D);
                int pz = cz + (int) Math.round(Math.sin(angle) * 38.0D);
                buildRoundTower(px, y, pz, 4, 12 + i);
                crystalSpike(px, y + 1, pz, 10 + i, new Random(4000L + i));
            }
            // El núcleo de esmeralda se ubica detrás de las gradas, no en el centro del jefe.
            buildEmeraldTree(cx, y + 1, cz + 49);
            placeCustomSpawner(new Location(world, cx - 42, y + 2, cz), "arlightbosses:emerald_skeleton_archer_minion");
            placeCustomSpawner(new Location(world, cx + 42, y + 2, cz), "arlightbosses:emerald_creeper_minion");
            placeCustomSpawner(new Location(world, cx, y + 2, cz - 45), "arlightbosses:emerald_golem_sentinel_minion");
            placeLootContainer(new Location(world, cx + 27, y + 2, cz + 10), LootTables.STRONGHOLD_CROSSING, false);
            placeLootContainer(new Location(world, cx - 27, y + 2, cz + 10), LootTables.STRONGHOLD_CROSSING, false);
        }

        private void buildEmeraldTree(int cx, int y, int cz) {
            for (int yy = 0; yy < 15; yy++) {
                int radius = yy < 5 ? 3 : yy < 11 ? 2 : 1;
                for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
                    if ((x - cx) * (x - cx) + (z - cz) * (z - cz) <= radius * radius + 1) add(x, y + yy, z, yy % 4 == 0 ? Material.VERDANT_FROGLIGHT : Material.EMERALD_BLOCK);
                }
            }
            for (int branch = 0; branch < 8; branch++) {
                double angle = branch * Math.PI / 4.0D;
                for (int step = 1; step <= 12; step++) {
                    int x = cx + (int) Math.round(Math.cos(angle) * step * 0.7D);
                    int z = cz + (int) Math.round(Math.sin(angle) * step * 0.7D);
                    int yy = y + 8 + step / 2;
                    add(x, yy, z, step % 4 == 0 ? Material.VERDANT_FROGLIGHT : Material.EMERALD_BLOCK);
                }
            }
        }

        private void buildRuinedHouse(int cx, int cz, int width, int depth, int salt) {
            int y = localBuildY(cx, cz, width / 2 + 2, depth / 2 + 2);
            preparePlot(cx, y, cz, width / 2 + 1, depth / 2 + 1);
            Random local = new Random(salt * 7727L + cx * 31L + cz);
            int minX = cx - width / 2, maxX = cx + width / 2;
            int minZ = cz - depth / 2, maxZ = cz + depth / 2;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) add(x, y, z, Material.COBBLESTONE);
            for (int yy = 1; yy <= 6; yy++) {
                for (int x = minX; x <= maxX; x++) for (int z : new int[]{minZ, maxZ}) if (local.nextDouble() > 0.12D + yy * 0.03D) add(x, y + yy, z, local.nextBoolean() ? Material.STONE_BRICKS : Material.MOSSY_STONE_BRICKS);
                for (int z = minZ; z <= maxZ; z++) for (int x : new int[]{minX, maxX}) if (local.nextDouble() > 0.12D + yy * 0.03D) add(x, y + yy, z, local.nextBoolean() ? Material.DARK_OAK_LOG : Material.STONE_BRICKS);
            }
            roofGable(minX - 1, maxX + 1, minZ - 1, maxZ + 1, y + 7, depth >= width);
            placeLootContainer(new Location(world, cx, y + 1, cz), LootTables.SIMPLE_DUNGEON, local.nextBoolean());
        }

        private void buildRoundTower(int cx, int y, int cz, int radius, int height) {
            for (int yy = 0; yy <= height; yy++) {
                for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
                    int d2 = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                    boolean ring = d2 <= radius * radius + radius && d2 >= (radius - 1) * (radius - 1) - radius;
                    if (yy == 0 || ring) add(x, y + yy, z, ((x + z + yy) % 8 == 0) ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                }
            }
            for (int x = cx - radius - 1; x <= cx + radius + 1; x++) for (int z = cz - radius - 1; z <= cz + radius + 1; z++) {
                int d2 = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (d2 <= (radius + 1) * (radius + 1) + radius && d2 >= radius * radius - radius) add(x, y + height + 1, z, Material.STONE_BRICK_SLAB);
            }
            // Pisos, acceso y almenas hacen que las torres sean explorables y no tubos vacíos.
            for (int level = 7; level < height; level += 8) {
                for (int x = cx - radius + 2; x <= cx + radius - 2; x++) for (int z = cz - radius + 2; z <= cz + radius - 2; z++) {
                    if ((x - cx) * (x - cx) + (z - cz) * (z - cz) <= (radius - 2) * (radius - 2)) {
                        add(x, y + level, z, Material.STONE_BRICK_SLAB);
                    }
                }
                add(cx, y + level, cz, Material.AIR);
            }
            for (int dx = -1; dx <= 1; dx++) for (int yy = 1; yy <= 4; yy++) add(cx + dx, y + yy, cz - radius, Material.AIR);
            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI * 2.0D / 12.0D;
                int bx = cx + (int) Math.round(Math.cos(angle) * radius);
                int bz = cz + (int) Math.round(Math.sin(angle) * radius);
                add(bx, y + height + 2, bz, (i & 1) == 0 ? Material.STONE_BRICKS : Material.MOSSY_STONE_BRICKS);
            }
        }

        private void roofGable(int minX, int maxX, int minZ, int maxZ, int y, boolean ridgeAlongZ) {
            int layers = ridgeAlongZ ? (maxX - minX + 2) / 2 : (maxZ - minZ + 2) / 2;
            for (int layer = 0; layer <= layers; layer++) {
                if (ridgeAlongZ) {
                    int left = minX + layer, right = maxX - layer;
                    for (int z = minZ; z <= maxZ; z++) {
                        if (left <= right) add(left, y + layer, z, Material.DARK_OAK_PLANKS);
                        if (right != left) add(right, y + layer, z, Material.DARK_OAK_PLANKS);
                    }
                } else {
                    int front = minZ + layer, back = maxZ - layer;
                    for (int x = minX; x <= maxX; x++) {
                        if (front <= back) add(x, y + layer, front, Material.DARK_OAK_PLANKS);
                        if (back != front) add(x, y + layer, back, Material.DARK_OAK_PLANKS);
                    }
                }
            }
        }

        private void villageGabledRoof(int minX, int maxX, int minZ, int maxZ,
                                       int y, boolean ridgeAlongZ, int style) {
            String stair = (style & 1) == 0 ? "dark_oak_stairs" : "spruce_stairs";
            Material stairMaterial = (style & 1) == 0
                    ? Material.DARK_OAK_STAIRS : Material.SPRUCE_STAIRS;
            Material gable = (style & 1) == 0 ? Material.SPRUCE_PLANKS : Material.DARK_OAK_PLANKS;
            int layers = ridgeAlongZ ? Math.max(1, (maxX - minX) / 2)
                    : Math.max(1, (maxZ - minZ) / 2);

            for (int layer = 0; layer <= layers; layer++) {
                if (ridgeAlongZ) {
                    int left = minX + layer;
                    int right = maxX - layer;
                    if (left > right) break;
                    for (int z = minZ; z <= maxZ; z++) {
                        addData(left, y + layer, z, stairMaterial,
                                "minecraft:" + stair + "[facing=east,half=bottom,shape=straight,waterlogged=false]");
                        if (right != left) addData(right, y + layer, z, stairMaterial,
                                "minecraft:" + stair + "[facing=west,half=bottom,shape=straight,waterlogged=false]");
                    }
                    for (int x = left + 1; x < right; x++) {
                        add(x, y + layer, minZ + 1, gable);
                        add(x, y + layer, maxZ - 1, gable);
                    }
                    if (right - left <= 1) {
                        for (int z = minZ; z <= maxZ; z++) add(left, y + layer + 1, z, Material.SPRUCE_SLAB);
                    }
                } else {
                    int front = minZ + layer;
                    int back = maxZ - layer;
                    if (front > back) break;
                    for (int x = minX; x <= maxX; x++) {
                        addData(x, y + layer, front, stairMaterial,
                                "minecraft:" + stair + "[facing=south,half=bottom,shape=straight,waterlogged=false]");
                        if (back != front) addData(x, y + layer, back, stairMaterial,
                                "minecraft:" + stair + "[facing=north,half=bottom,shape=straight,waterlogged=false]");
                    }
                    for (int z = front + 1; z < back; z++) {
                        add(minX + 1, y + layer, z, gable);
                        add(maxX - 1, y + layer, z, gable);
                    }
                    if (back - front <= 1) {
                        for (int x = minX; x <= maxX; x++) add(x, y + layer + 1, front, Material.SPRUCE_SLAB);
                    }
                }
            }
        }

        private void wallBlock(int x, int y, int z, int layer) {
            if ((x * 31 + z * 17 + layer) % 11 == 0) add(x, y, z, Material.LIME_STAINED_GLASS_PANE);
            else if ((x + z + layer) % 7 == 0) add(x, y, z, Material.MOSSY_STONE_BRICKS);
            else add(x, y, z, Material.STONE_BRICKS);
        }

        /**
         * Recupera la altura del terreno ignorando tejados, muros y plataformas de
         * una revisión anterior. Esto hace que resume reconstruya en el mismo nivel
         * y no apile el pueblo o la ciudadela varios bloques más arriba.
         */
        private int terrainSurfaceY(int x, int z) {
            int y = surfaceY(x, z) - 1;
            int minimum = Math.max(world.getMinHeight() + 1, y - 64);
            while (y > minimum && isTemplateBuildMaterial(world.getBlockAt(x, y, z).getType())) y--;
            return y + 1;
        }

        private boolean isTemplateBuildMaterial(Material material) {
            String name = material.name();
            return name.contains("PLANKS") || name.contains("LOG") || name.contains("WOOD")
                    || name.contains("SLAB") || name.contains("STAIRS") || name.contains("FENCE")
                    || name.contains("WALL") || name.contains("GLASS") || name.contains("BRICK")
                    || name.contains("COBBLESTONE") || name.contains("DEEPSLATE")
                    || name.contains("TERRACOTTA") || name.contains("FROGLIGHT")
                    || name.contains("EMERALD") || name.contains("BARREL") || name.contains("CHEST")
                    || material == Material.BRICKS || material == Material.ANVIL
                    || material == Material.CAMPFIRE || material == Material.LANTERN
                    || material == Material.BELL || material == Material.IRON_BARS;
        }

        private int localBuildY(int cx, int cz, int halfX, int halfZ) {
            List<Integer> heights = new ArrayList<>();
            for (int dx : new int[]{-halfX, 0, halfX}) for (int dz : new int[]{-halfZ, 0, halfZ}) heights.add(terrainSurfaceY(cx + dx, cz + dz));
            heights.sort(Integer::compareTo);
            return heights.get(heights.size() / 2) - 1;
        }

        private int medianHeight(int cx, int cz, int radius) {
            List<Integer> heights = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx += Math.max(4, radius / 4)) {
                for (int dz = -radius; dz <= radius; dz += Math.max(4, radius / 4)) heights.add(terrainSurfaceY(cx + dx, cz + dz));
            }
            heights.sort(Integer::compareTo);
            return heights.get(heights.size() / 2) - 1;
        }

        private void preparePlot(int cx, int y, int cz, int halfX, int halfZ) {
            for (int x = cx - halfX; x <= cx + halfX; x++) for (int z = cz - halfZ; z <= cz + halfZ; z++) {
                int current = surfaceY(x, z) - 1;
                if (current < y) {
                    // La versión anterior rellenaba solo seis bloques y dejaba plataformas
                    // suspendidas sobre laderas. Ahora toda la diferencia se integra al terreno.
                    for (int yy = current + 1; yy <= y; yy++) {
                        boolean face = x == cx - halfX || x == cx + halfX || z == cz - halfZ || z == cz + halfZ;
                        Material material = yy == y ? Material.STONE
                                : (face && (yy + x + z) % 7 == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
                        add(x, yy, z, material);
                    }
                } else {
                    for (int yy = y + 1; yy <= Math.min(current + 12, world.getMaxHeight() - 2); yy++) add(x, yy, z, Material.AIR);
                    add(x, y, z, Material.STONE);
                }
            }
        }

        private void terraceCircle(int cx, int y, int cz, int radius, Material primary, Material accent) {
            for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
                int d2 = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (d2 > radius * radius) continue;
                int original = surfaceY(x, z) - 1;
                int target = y + Math.min(4, Math.max(-4, (original - y) / 3));
                if (original < target) {
                    for (int yy = original + 1; yy <= target; yy++) {
                        Material fill = yy == target ? ((x + z) % 6 == 0 ? accent : primary)
                                : (((x + z + yy) & 9) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
                        add(x, yy, z, fill);
                    }
                } else {
                    for (int yy = target + 1; yy <= Math.min(original + 6, world.getMaxHeight() - 2); yy++) add(x, yy, z, Material.AIR);
                    add(x, target, z, ((x + z) % 6 == 0 ? accent : primary));
                }
            }
        }

        private void road(int x1, int z1, int x2, int z2, int width, Material material) {
            int dx = Math.abs(x2 - x1), dz = Math.abs(z2 - z1);
            int sx = x1 < x2 ? 1 : -1;
            int sz = z1 < z2 ? 1 : -1;
            int err = dx - dz;
            int x = x1, z = z1;
            int lastY = terrainSurfaceY(x, z) - 1;
            while (true) {
                int natural = surfaceY(x, z) - 1;
                int y = Math.max(lastY - 1, Math.min(lastY + 1, natural));
                lastY = y;
                for (int wx = -width / 2; wx <= width / 2; wx++) for (int wz = -width / 2; wz <= width / 2; wz++) {
                    if (Math.abs(wx) + Math.abs(wz) > width / 2 + 1) continue;
                    int bx = x + wx, bz = z + wz;
                    int belowRoad = terrainSurfaceY(bx, bz) - 1;
                    if (belowRoad < y) {
                        for (int fy = belowRoad + 1; fy < y; fy++) {
                            add(bx, fy, bz, ((fy + bx + bz) & 7) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
                        }
                    }
                    add(bx, y, bz, ((bx + bz) % 8 == 0) ? Material.MOSSY_COBBLESTONE : material);
                    add(bx, y + 1, bz, Material.AIR);
                    add(bx, y + 2, bz, Material.AIR);
                    add(bx, y + 3, bz, Material.AIR);
                }
                if (x == x2 && z == z2) break;
                int e2 = 2 * err;
                if (e2 > -dz) { err -= dz; x += sx; }
                if (e2 < dx) { err += dx; z += sz; }
            }
        }

        private void fenceRectangle(int cx, int y, int cz, int halfX, int halfZ, Material fence) {
            for (int x = cx - halfX; x <= cx + halfX; x++) {
                add(x, y, cz - halfZ, fence);
                add(x, y, cz + halfZ, fence);
            }
            for (int z = cz - halfZ; z <= cz + halfZ; z++) {
                add(cx - halfX, y, z, fence);
                add(cx + halfX, y, z, fence);
            }
        }

        private void mossPatch(int cx, int y, int cz, int radius, Random local) {
            for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
                double distance = Math.hypot(x - cx, z - cz);
                if (distance > radius + local.nextDouble() * 1.8D) continue;
                int sy = surfaceY(x, z) - 1;
                Material original = world.getBlockAt(x, sy, z).getType();
                if (!original.isSolid()) continue;
                add(x, sy, z, local.nextDouble() < 0.18D ? Material.EMERALD_BLOCK : Material.MOSS_BLOCK);
                if (local.nextDouble() < 0.20D) add(x, sy + 1, z, Material.MOSS_CARPET);
            }
        }

        private void crystalSpike(int x, int y, int z, int height, Random local) {
            int dx = local.nextBoolean() ? 1 : -1;
            int dz = local.nextBoolean() ? 1 : -1;
            for (int i = 0; i < height; i++) {
                int bx = x + (i / 4) * dx;
                int bz = z + (i / 5) * dz;
                int radius = i < 3 ? 2 : i < height - 2 ? 1 : 0;
                for (int ox = -radius; ox <= radius; ox++) for (int oz = -radius; oz <= radius; oz++) {
                    if (Math.abs(ox) + Math.abs(oz) > radius + 1) continue;
                    Material material = (i % 6 == 0 || (ox == 0 && oz == 0 && i % 3 == 0))
                            ? Material.VERDANT_FROGLIGHT : (i % 4 == 0 ? Material.GREEN_GLAZED_TERRACOTTA : Material.EMERALD_BLOCK);
                    add(bx + ox, y + i, bz + oz, material);
                }
            }
        }

        private void placeLootContainer(Location location, LootTables table, boolean barrel) {
            add(location.getBlockX(), location.getBlockY(), location.getBlockZ(), barrel ? Material.BARREL : Material.CHEST);
            afterBlocks.add(() -> {
                Block block = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
                if (barrel && block.getState() instanceof Barrel state) setLoot(state, table.getLootTable());
                else if (!barrel && block.getState() instanceof Chest state) setLoot(state, table.getLootTable());
            });
        }

        private void setLoot(Lootable state, LootTable table) {
            if (table == null) return;
            state.setLootTable(table);
            state.setSeed(random.nextLong());
            if (state instanceof org.bukkit.block.BlockState blockState) blockState.update(true, false);
        }

        private void placeCustomSpawner(Location location, String entityId) {
            add(location.getBlockX(), location.getBlockY(), location.getBlockZ(), Material.SPAWNER);
            afterBlocks.add(() -> {
                String command = String.format(Locale.ROOT,
                        "execute in %s run data merge block %d %d %d {Delay:80s,MinSpawnDelay:240s,MaxSpawnDelay:420s,SpawnCount:1s,MaxNearbyEntities:3s,RequiredPlayerRange:18s,SpawnRange:5s,SpawnData:{entity:{id:\"%s\",Tags:[\"arlightbingo_template_mob\"],PersistenceRequired:1b}},SpawnPotentials:[{weight:1,data:{entity:{id:\"%s\",Tags:[\"arlightbingo_template_mob\"],PersistenceRequired:1b}}}]}",
                        world.getKey(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), entityId, entityId);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            });
        }

        private void summonSomita(Location location) {
            String command = String.format(Locale.ROOT,
                    "execute in %s run summon arlightbosses:somita_guide %.2f %.2f %.2f {Tags:[\"%s\"],PersistenceRequired:1b,Invulnerable:1b,NoAI:1b,Silent:1b,Rotation:[%.1ff,0.0f]}",
                    world.getKey(), location.getX(), location.getY(), location.getZ(), SOMITA_TAG, location.getYaw());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }

        private void cleanupTemplateEntities(World world) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (entity.getScoreboardTags().contains(SOMITA_TAG)
                        || entity.getScoreboardTags().contains(GUIDE_TAG)) entity.remove();
            }
        }

        private void apply(BlockOp op) {
            Block block = world.getBlockAt(op.x(), op.y(), op.z());
            if (block.getType() != op.material()) block.setType(op.material(), false);
            if (op.data() != null) {
                try { block.setBlockData(Bukkit.createBlockData(op.data()), false); }
                catch (IllegalArgumentException ignored) { }
            }
        }

        private void add(int x, int y, int z, Material material) {
            if (y <= world.getMinHeight() || y >= world.getMaxHeight()) return;
            blocks.add(new BlockOp(x, y, z, material, null));
        }

        private void addData(int x, int y, int z, Material material, String data) {
            if (y <= world.getMinHeight() || y >= world.getMaxHeight()) return;
            blocks.add(new BlockOp(x, y, z, material, data));
        }

        private double horizontalDistance(int x1, int z1, int x2, int z2) {
            return Math.hypot(x1 - x2, z1 - z2);
        }

        private record BlockOp(int x, int y, int z, Material material, String data) { }
        private record Progress(double value, String message) { }
        private interface BiProgress { void accept(double value, String message); }
    }
}
