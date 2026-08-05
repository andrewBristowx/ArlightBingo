package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import com.arlight.bingo.template.OverworldIslandGenerator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Restauración determinista de cicatrices del Overworld.
 *
 * <p>1.48.41 deja de adivinar pendientes mediante planos. La forma limpia de la isla
 * se reconstruye con el mismo generador y seed que creó la plantilla. Esto elimina
 * discos verdes, terrazas rectas y cubos sin deformar el borde sano. Las zonas con
 * arquitectura tienen políticas explícitas y nunca se detectan como minas.</p>
 */
final class OverworldTerrainBlendCommands {

    private static final String TEMPLATE_MARKER = "arlight-overworld-template.properties";
    private static final String LORE_MARKER = "arlight-overworld-lore-decoration.properties";
    private static final String LEGACY_DECORATION_MARKER = "arlight-overworld-decoration.properties";
    private static final String REVISION = "1.48.41-deterministic-zone-recovery-1";
    private static final DateTimeFormatter SNAPSHOT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int DEFAULT_RADIUS = 72;
    private static final int MIN_RADIUS = 16;
    private static final int MAX_RADIUS = 140;

    private final BingoPlugin plugin;
    private BukkitTask waitTask;
    private BukkitTask applyTask;
    private String detail = "sin iniciar";
    private int cursor;
    private int total;
    private int changed;

    OverworldTerrainBlendCommands(BingoPlugin plugin) {
        this.plugin = plugin;
    }

    boolean matches(String rawCommand) {
        String[] args = tokenize(rawCommand);
        return args.length >= 4
                && args[0].equalsIgnoreCase("bingo")
                && args[1].equalsIgnoreCase("template")
                && args[2].equalsIgnoreCase("overworld")
                && (args[3].equalsIgnoreCase("terrain") || args[3].equalsIgnoreCase("terreno"));
    }

    void execute(CommandSender sender, String rawCommand) {
        if (!sender.hasPermission("arlightbingo.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para reparar el terreno.");
            return;
        }
        String[] args = tokenize(rawCommand);
        String action = args.length >= 5 ? args[4].toLowerCase(Locale.ROOT) : "help";
        if (action.equals("status")) {
            sender.sendMessage(ChatColor.AQUA + "Restauración determinista 1.48.41: "
                    + ChatColor.WHITE + status());
            return;
        }
        if (action.equals("cancel")) {
            cancel(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "La previsualización y aplicación deben ejecutarse dentro de la revisión de trabajo.");
            return;
        }
        World world = resolveTemplateWorld(player);
        if (world == null) return;
        Selection selection = parseSelection(player, world, args.length >= 6 ? args[5] : null);
        if (selection == null) return;
        if (action.equals("preview")) {
            preview(player, world, selection);
            return;
        }
        if (action.equals("apply") || action.equals("restore") || action.equals("repair")) {
            start(player, world, selection);
            return;
        }
        sendUsage(sender);
    }

    List<String> completions(String buffer) {
        String clean = buffer == null ? "" : buffer.trim();
        if (clean.startsWith("/")) clean = clean.substring(1);
        String[] args = clean.isBlank() ? new String[0] : clean.split("\\s+");
        boolean trailing = buffer != null && buffer.endsWith(" ");
        int logical = trailing ? args.length + 1 : args.length;
        if (logical == 5) {
            String prefix = trailing ? "" : args[4];
            return filter(List.of("preview", "apply", "status", "cancel"), prefix);
        }
        if (logical == 6 && args.length >= 5
                && (args[4].equalsIgnoreCase("preview") || args[4].equalsIgnoreCase("apply"))) {
            String prefix = trailing ? "" : args[5];
            return filter(List.of("citadel", "coast", "mine-surface", "all-damage",
                    "48", "64", "72", "96", "112", "128"), prefix);
        }
        return List.of();
    }

    void onWorldUnload(World world) {
        // Los snapshots descargan y recargan deliberadamente la plantilla.
    }

    private void preview(Player player, World world, Selection selection) {
        TerrainPlan plan = plan(world, selection);
        if (!plan.valid()) {
            player.sendMessage(ChatColor.RED + plan.message());
            return;
        }
        player.sendMessage(ChatColor.AQUA + "=== Restauración determinista Overworld 1.48.41 ===");
        player.sendMessage(ChatColor.GRAY + "- selección=" + ChatColor.WHITE + selection.label());
        player.sendMessage(ChatColor.GRAY + "- zonas=" + ChatColor.WHITE + selection.zones().size());
        player.sendMessage(ChatColor.GRAY + "- columnas restauradas=" + ChatColor.WHITE + plan.changedColumns());
        player.sendMessage(ChatColor.GRAY + "- columnas de arquitectura conservadas="
                + ChatColor.WHITE + plan.protectedColumns());
        player.sendMessage(ChatColor.GRAY + "- operaciones=" + ChatColor.WHITE + plan.edits().size());
        player.sendMessage(ChatColor.GREEN + "- método=" + ChatColor.WHITE
                + "misma seed, fórmula de isla, costa, cuevas y materiales de la plantilla limpia");
        if (selection.rebuildCitadel()) {
            player.sendMessage(ChatColor.GOLD + "- ciudadela=" + ChatColor.WHITE
                    + "se limpiará el volumen dañado y se reconstruirá automáticamente al terminar");
        }
        player.sendMessage(ChatColor.YELLOW + "Aplicar con: " + ChatColor.WHITE
                + "/bingo template overworld terrain apply " + selection.token());
    }

    private void start(Player player, World world, Selection selection) {
        if (busy()) {
            player.sendMessage(ChatColor.YELLOW + "Ya hay una restauración activa: " + status());
            return;
        }
        TerrainPlan preview = plan(world, selection);
        if (!preview.valid() || preview.edits().isEmpty()) {
            player.sendMessage(ChatColor.RED + (preview.message().isBlank()
                    ? "No se encontraron bloques que restaurar." : preview.message()));
            return;
        }
        String snapshot = "pre-deterministic-repair-" + SNAPSHOT_TIME.format(Instant.now());
        String worldName = world.getName();
        Path expected = plugin.getDataFolder().toPath().resolve("template-snapshots")
                .resolve("overworld").resolve(snapshot);
        detail = "esperando snapshot " + snapshot;
        player.sendMessage(ChatColor.YELLOW + "Creando snapshot completo antes de restaurar "
                + selection.label() + "...");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "bingo template overworld snapshot create " + snapshot);

        final int[] waited = {0};
        waitTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            waited[0]++;
            World reloaded = Bukkit.getWorld(worldName);
            if (Files.isDirectory(expected) && reloaded != null
                    && Files.isRegularFile(reloaded.getWorldFolder().toPath().resolve(TEMPLATE_MARKER))) {
                BukkitTask finished = waitTask;
                waitTask = null;
                if (finished != null) finished.cancel();
                startApply(player, reloaded, selection, snapshot);
                return;
            }
            if (waited[0] < 300) return;
            BukkitTask finished = waitTask;
            waitTask = null;
            if (finished != null) finished.cancel();
            detail = "cancelada: snapshot incompleto";
            player.sendMessage(ChatColor.RED + "No se aplicó ningún bloque porque el snapshot no terminó.");
        }, 20L, 20L);
    }

    private void startApply(Player player, World world, Selection selection, String snapshot) {
        TerrainPlan plan = plan(world, selection);
        if (!plan.valid() || plan.edits().isEmpty()) {
            detail = "sin operaciones";
            player.sendMessage(ChatColor.RED + (plan.message().isBlank()
                    ? "No se encontraron bloques que restaurar." : plan.message()));
            return;
        }
        List<Edit> edits = plan.edits();
        cursor = 0;
        total = edits.size();
        changed = 0;
        detail = "aplicando 0/" + total;
        int perTick = Math.max(300, plugin.getConfig().getInt(
                "template-worlds.overworld.terrain-blend.blocks-per-tick", 1400));
        long maxNanos = Math.max(2L, plugin.getConfig().getLong(
                "template-worlds.overworld.terrain-blend.max-millis-per-tick", 7L)) * 1_000_000L;

        applyTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long deadline = System.nanoTime() + maxNanos;
            int applied = 0;
            while (cursor < total && applied < perTick && System.nanoTime() < deadline) {
                if (apply(world, edits.get(cursor++))) changed++;
                applied++;
            }
            detail = "aplicando " + cursor + "/" + total + " · " + changed + " cambios";
            if (cursor < total) return;
            BukkitTask finished = applyTask;
            applyTask = null;
            if (finished != null) finished.cancel();
            writeMarker(world, selection, snapshot, plan, changed);
            world.save();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
            detail = "completa · " + changed + " bloques cambiados";
            player.sendMessage(ChatColor.GREEN + "Restauración determinista terminada: "
                    + selection.label() + ".");
            if (selection.rebuildCitadel()) {
                player.sendMessage(ChatColor.YELLOW + "La base quedó limpia. Iniciando reconstrucción exacta de la ciudadela...");
                Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.dispatchCommand(
                        Bukkit.getConsoleSender(), "bingo template overworld recover apply citadel"), 40L);
            } else {
                player.sendMessage(ChatColor.YELLOW + "Revisa toda la transición antes de aplicar otra zona.");
            }
        }, 1L, 1L);
        player.sendMessage(ChatColor.GREEN + "Snapshot confirmado: " + snapshot + ".");
        player.sendMessage(ChatColor.GREEN + "Restauración iniciada con " + total + " operaciones.");
    }

    private TerrainPlan plan(World world, Selection selection) {
        long seed = plugin.getConfig().getLong("template-worlds.overworld.seed", 741905270311L);
        int islandRadius = Math.max(420, plugin.getConfig().getInt(
                "template-worlds.overworld.island.land-radius", 500));
        int seaLevel = plugin.getConfig().getInt(
                "template-worlds.overworld.island.sea-level", 62);
        OverworldIslandGenerator generator = new OverworldIslandGenerator(seed, islandRadius, seaLevel);
        LinkedHashMap<Long, Edit> edits = new LinkedHashMap<>();
        int changedColumns = 0;
        int protectedColumns = 0;

        for (Zone zone : selection.zones()) {
            if (isProtectedHarbor(world, zone.centerX(), zone.centerZ(), zone.radius())) {
                return TerrainPlan.invalid("La zona " + zone.name() + " entra en el puerto protegido.");
            }
            int radiusSq = zone.radius() * zone.radius();
            int feather = Math.max(6, Math.min(12, zone.radius() / 8));
            for (int dx = -zone.radius(); dx <= zone.radius(); dx++) {
                for (int dz = -zone.radius(); dz <= zone.radius(); dz++) {
                    int distSq = dx * dx + dz * dz;
                    if (distSq > radiusSq) continue;
                    int x = zone.centerX() + dx;
                    int z = zone.centerZ() + dz;
                    double distance = Math.sqrt(distSq);
                    double strength = distance <= zone.radius() - feather ? 1.0D
                            : smooth01((zone.radius() - distance) / Math.max(1.0D, feather));
                    if (strength <= 0.01D) continue;

                    int currentTop = terrainSurfaceY(world, x, z);
                    int expected = generator.surfaceYAt(x, z);
                    int target = strength >= 0.999D ? expected
                            : (int) Math.round(currentTop + (expected - currentTop) * strength);
                    target = Math.max(world.getMinHeight() + 2,
                            Math.min(world.getMaxHeight() - 20, target));
                    boolean architecture = columnContainsArchitecture(world, x, z, currentTop);
                    boolean clearStructure = zone.clearStructures()
                            && distance <= zone.structureClearRadius();
                    if (architecture && !clearStructure) protectedColumns++;

                    int clearTop = Math.min(world.getMaxHeight() - 2,
                            Math.max(currentTop + 18, target + 24));
                    for (int y = target + 1; y <= clearTop; y++) {
                        Material current = world.getBlockAt(x, y, z).getType();
                        if (shouldClear(zone.policy(), current, architecture, clearStructure)) {
                            put(edits, new Edit(x, y, z, Material.AIR));
                        }
                    }

                    int fillFrom = Math.max(world.getMinHeight() + 1,
                            Math.min(currentTop, target) - 20);
                    for (int y = fillFrom; y <= target; y++) {
                        if (architecture && !clearStructure && y >= currentTop - 2) continue;
                        Material material = target == expected
                                ? generator.terrainMaterialAt(x, y, z, world.getMinHeight())
                                : featherMaterial(generator, x, y, z, target, expected, world.getMinHeight());
                        put(edits, new Edit(x, y, z, material));
                    }
                    if (target < generator.seaLevel()) {
                        for (int y = target + 1; y <= generator.seaLevel(); y++) {
                            if (!architecture || clearStructure) {
                                put(edits, new Edit(x, y, z, Material.WATER));
                            }
                        }
                    }
                    changedColumns++;
                }
            }
        }
        return new TerrainPlan(true, "", new ArrayList<>(edits.values()),
                changedColumns, protectedColumns);
    }

    private Material featherMaterial(OverworldIslandGenerator generator, int x, int y, int z,
                                     int target, int expected, int minY) {
        int depth = target - y;
        Material expectedTop = generator.terrainMaterialAt(x, expected, z, minY);
        if (depth == 0) return expectedTop == Material.STONE ? Material.MOSS_BLOCK : expectedTop;
        if (depth <= 3) return expectedTop == Material.SAND ? Material.SANDSTONE : Material.DIRT;
        return y < 0 ? Material.DEEPSLATE : Material.STONE;
    }

    private boolean shouldClear(Policy policy, Material material,
                                boolean architectureColumn, boolean clearStructure) {
        if (material == Material.BEDROCK) return false;
        if (clearStructure) return true;
        if (architectureColumn && policy == Policy.PRESERVE_ARCHITECTURE) return false;
        if (policy == Policy.CLEAR_GENERATED_VOLUME) {
            return isTerrainRepairable(material) || isMineGeneratedMaterial(material);
        }
        return isTerrainRepairable(material);
    }

    private Selection parseSelection(Player player, World world, String raw) {
        String token = raw == null || raw.isBlank() ? String.valueOf(DEFAULT_RADIUS)
                : raw.toLowerCase(Locale.ROOT);
        if (token.equals("citadel") || token.equals("ciudadela") || token.equals("castle")) {
            int x = plugin.getConfig().getInt(
                    "template-worlds.overworld.campaign-layout-1-48.citadel-x", 140);
            int z = plugin.getConfig().getInt(
                    "template-worlds.overworld.campaign-layout-1-48.citadel-z", 30);
            Zone zone = new Zone("ciudadela", x, z, 100,
                    Policy.CLEAR_GENERATED_VOLUME, true, 82);
            return new Selection("citadel", "ciudadela y base deformada", List.of(zone), true);
        }
        if (token.equals("coast") || token.equals("costa")) {
            Zone zone = new Zone("cicatriz costera", 50, -317, 98,
                    Policy.CLEAR_GENERATED_VOLUME, false, 0);
            return new Selection("coast", "cicatriz costera", List.of(zone), false);
        }
        if (token.equals("mine-surface") || token.equals("mina") || token.equals("mine")) {
            Center center = storedMineCenter(world);
            Zone zone = new Zone("superficie de mina", center.x(), center.z(), 82,
                    Policy.CLEAR_GENERATED_VOLUME, false, 0);
            return new Selection("mine-surface", "superficie y entrada de mina", List.of(zone), false);
        }
        if (token.equals("all-damage") || token.equals("todo") || token.equals("all")) {
            int citadelX = plugin.getConfig().getInt(
                    "template-worlds.overworld.campaign-layout-1-48.citadel-x", 140);
            int citadelZ = plugin.getConfig().getInt(
                    "template-worlds.overworld.campaign-layout-1-48.citadel-z", 30);
            Center mine = storedMineCenter(world);
            List<Zone> zones = List.of(
                    new Zone("ciudadela", citadelX, citadelZ, 100,
                            Policy.CLEAR_GENERATED_VOLUME, true, 82),
                    new Zone("cicatriz costera", 50, -317, 98,
                            Policy.CLEAR_GENERATED_VOLUME, false, 0),
                    new Zone("superficie de mina", mine.x(), mine.z(), 82,
                            Policy.CLEAR_GENERATED_VOLUME, false, 0));
            return new Selection("all-damage", "todas las cicatrices conocidas", zones, true);
        }
        try {
            int radius = Integer.parseInt(token);
            if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
                player.sendMessage(ChatColor.RED + "El radio debe estar entre "
                        + MIN_RADIUS + " y " + MAX_RADIUS + ".");
                return null;
            }
            Center center = new Center(player.getLocation().getBlockX(), player.getLocation().getBlockZ());
            Zone zone = new Zone("zona manual", center.x(), center.z(), radius,
                    Policy.PRESERVE_ARCHITECTURE, false, 0);
            return new Selection(String.valueOf(radius), "zona manual en "
                    + center.x() + "," + center.z(), List.of(zone), false);
        } catch (NumberFormatException error) {
            player.sendMessage(ChatColor.RED + "Usa un radio o una zona: citadel, coast, mine-surface, all-damage.");
            return null;
        }
    }

    private Center storedMineCenter(World world) {
        Properties marker = readProperties(world.getWorldFolder().toPath().resolve(LORE_MARKER));
        String raw = marker.getProperty("mineEntrance", "237,0,-149");
        String[] parts = raw.split(",");
        try {
            if (parts.length >= 3) {
                return new Center(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[2].trim()));
            }
        } catch (NumberFormatException ignored) { }
        return new Center(237, -149);
    }

    private int terrainSurfaceY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        while (y > world.getMinHeight() + 2) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (!isPlant(material) && !material.name().endsWith("_LOG")
                    && !material.name().endsWith("_WOOD")) return y;
            y--;
        }
        return y;
    }

    private boolean columnContainsArchitecture(World world, int x, int z, int top) {
        int min = Math.max(world.getMinHeight(), top - 24);
        int max = Math.min(world.getMaxHeight() - 1, top + 28);
        int architecture = 0;
        for (int y = min; y <= max; y++) {
            if (isArchitectureMaterial(world.getBlockAt(x, y, z).getType())) architecture++;
            if (architecture >= 2) return true;
        }
        return false;
    }

    private boolean isArchitectureMaterial(Material material) {
        String name = material.name();
        return name.contains("BRICKS") || name.contains("TILES")
                || name.contains("PLANKS") || name.contains("CONCRETE")
                || name.contains("COPPER") || name.endsWith("_WALL")
                || name.endsWith("_STAIRS") || name.endsWith("_SLAB")
                || name.endsWith("_FENCE") || name.endsWith("_GATE")
                || name.endsWith("_DOOR") || name.contains("GLASS")
                || name.endsWith("_WOOL") || name.endsWith("_BED")
                || name.endsWith("_SIGN") || material == Material.IRON_BARS
                || material == Material.BARREL || material == Material.CHEST
                || material == Material.TRAPPED_CHEST || material == Material.LECTERN
                || material == Material.CRAFTING_TABLE || material == Material.SMITHING_TABLE
                || material == Material.STONECUTTER || material == Material.BLAST_FURNACE
                || material == Material.ANVIL || material == Material.GRINDSTONE
                || material == Material.CAULDRON || material == Material.LANTERN
                || material == Material.SOUL_LANTERN || material == Material.CHAIN;
    }

    private boolean isMineGeneratedMaterial(Material material) {
        String name = material.name();
        return material == Material.RAIL || material == Material.POWERED_RAIL
                || material == Material.DETECTOR_RAIL || material == Material.ACTIVATOR_RAIL
                || material == Material.SPRUCE_LOG || material == Material.DARK_OAK_LOG
                || material == Material.POLISHED_ANDESITE || material == Material.POLISHED_DEEPSLATE
                || material == Material.DEEPSLATE_BRICKS || material == Material.DEEPSLATE_TILES
                || material == Material.MOSSY_STONE_BRICKS || material == Material.SMOOTH_BASALT
                || material == Material.AMETHYST_BLOCK || material == Material.BUDDING_AMETHYST
                || material == Material.AMETHYST_CLUSTER || material == Material.LARGE_AMETHYST_BUD
                || material == Material.MEDIUM_AMETHYST_BUD || material == Material.SMALL_AMETHYST_BUD
                || material == Material.EMERALD_BLOCK || material == Material.CHAIN
                || material == Material.LANTERN || material == Material.SOUL_LANTERN
                || material == Material.BARREL || material == Material.CHEST
                || material == Material.LECTERN || name.endsWith("_SIGN");
    }

    private boolean isTerrainRepairable(Material material) {
        String name = material.name();
        return material.isAir() || isPlant(material) || isNatural(material)
                || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_LEAVES") || name.endsWith("_ORE")
                || material == Material.WATER || material == Material.LAVA;
    }

    private boolean isNatural(Material material) {
        String name = material.name();
        return material == Material.STONE || material == Material.DEEPSLATE
                || material == Material.TUFF || material == Material.CALCITE
                || material == Material.DRIPSTONE_BLOCK || material == Material.ANDESITE
                || material == Material.DIORITE || material == Material.GRANITE
                || material == Material.GRAVEL || material == Material.DIRT
                || material == Material.GRASS_BLOCK || material == Material.COARSE_DIRT
                || material == Material.ROOTED_DIRT || material == Material.PODZOL
                || material == Material.MUD || material == Material.CLAY
                || material == Material.SAND || material == Material.RED_SAND
                || material == Material.SANDSTONE || material == Material.RED_SANDSTONE
                || material == Material.MOSS_BLOCK || material == Material.MYCELIUM
                || material == Material.COBBLESTONE || material == Material.MOSSY_COBBLESTONE
                || material == Material.COBBLED_DEEPSLATE || material == Material.AMETHYST_BLOCK
                || material == Material.BUDDING_AMETHYST || name.endsWith("_TERRACOTTA")
                || name.equals("TERRACOTTA") || name.endsWith("_ORE");
    }

    private boolean isPlant(Material material) {
        String name = material.name();
        return name.endsWith("_LEAVES") || name.contains("GRASS")
                || name.contains("FERN") || name.contains("FLOWER")
                || name.contains("AZALEA") || name.contains("MUSHROOM")
                || name.contains("VINE") || name.contains("SAPLING")
                || material == Material.DEAD_BUSH || material == Material.MOSS_CARPET
                || material == Material.SNOW || material == Material.LILY_PAD;
    }

    private boolean apply(World world, Edit edit) {
        if (edit.y() < world.getMinHeight() || edit.y() >= world.getMaxHeight()) return false;
        Block block = world.getBlockAt(edit.x(), edit.y(), edit.z());
        if (block.getType() == edit.material()) return false;
        block.setType(edit.material(), false);
        if (edit.material().name().endsWith("_LEAVES") && block.getBlockData() instanceof Leaves leaves) {
            leaves.setPersistent(true);
            block.setBlockData(leaves, false);
        }
        return true;
    }

    private void put(Map<Long, Edit> edits, Edit edit) {
        edits.put(key(edit.x(), edit.y(), edit.z()), edit);
    }

    private long key(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) y & 0xFFFL);
    }

    private double smooth01(double value) {
        double t = Math.max(0.0D, Math.min(1.0D, value));
        return t * t * (3.0D - 2.0D * t);
    }

    private boolean isProtectedHarbor(World world, int x, int z, int radius) {
        Properties properties = readProperties(world.getWorldFolder().toPath()
                .resolve(LEGACY_DECORATION_MARKER));
        String raw = properties.getProperty("harbor", "");
        String[] parts = raw.split(",");
        if (parts.length < 3) return false;
        try {
            int hx = Integer.parseInt(parts[0].trim());
            int hz = Integer.parseInt(parts[2].trim());
            int protectedRadius = 220 + radius;
            long dx = (long) x - hx;
            long dz = (long) z - hz;
            return dx * dx + dz * dz <= (long) protectedRadius * protectedRadius;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private World resolveTemplateWorld(CommandSender sender) {
        World world = sender instanceof Player player ? player.getWorld() : resolveTemplateWorldQuietly();
        if (world == null || !Files.isRegularFile(
                world.getWorldFolder().toPath().resolve(TEMPLATE_MARKER))) {
            sender.sendMessage(ChatColor.RED + "Debes estar dentro de la revisión Overworld de trabajo.");
            return null;
        }
        return world;
    }

    private World resolveTemplateWorldQuietly() {
        String configured = plugin.getConfig().getString(
                "template-worlds.overworld.name", "bingo_template_overworld");
        World direct = Bukkit.getWorld(configured);
        if (direct != null && Files.isRegularFile(
                direct.getWorldFolder().toPath().resolve(TEMPLATE_MARKER))) return direct;
        return Bukkit.getWorlds().stream()
                .filter(world -> world.getName().startsWith(configured)
                        && Files.isRegularFile(world.getWorldFolder().toPath().resolve(TEMPLATE_MARKER)))
                .findFirst().orElse(null);
    }

    private Properties readProperties(Path path) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(path)) return properties;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) { }
        return properties;
    }

    private void writeMarker(World world, Selection selection, String snapshot,
                             TerrainPlan plan, int changedBlocks) {
        String text = "version=1.48.41\n"
                + "revision=" + REVISION + "\n"
                + "appliedAt=" + System.currentTimeMillis() + "\n"
                + "selection=" + selection.token() + "\n"
                + "zones=" + selection.zones().size() + "\n"
                + "snapshot=" + snapshot + "\n"
                + "changedBlocks=" + changedBlocks + "\n"
                + "changedColumns=" + plan.changedColumns() + "\n"
                + "protectedColumns=" + plan.protectedColumns() + "\n"
                + "deterministicGenerator=true\n"
                + "harborProtected=true\n"
                + "automaticMineScan=false\n";
        try {
            Files.writeString(world.getWorldFolder().toPath().resolve(
                            "arlight-overworld-terrain-blend.properties"), text,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo escribir el marcador de terreno 1.48.41: "
                    + error.getMessage());
        }
    }

    private boolean busy() {
        return waitTask != null || applyTask != null;
    }

    private String status() {
        return detail;
    }

    private void cancel(CommandSender sender) {
        boolean cancelled = false;
        if (waitTask != null) {
            waitTask.cancel();
            waitTask = null;
            cancelled = true;
        }
        if (applyTask != null) {
            applyTask.cancel();
            applyTask = null;
            cancelled = true;
        }
        detail = cancelled ? "cancelada manualmente" : "sin operación activa";
        sender.sendMessage(cancelled ? ChatColor.YELLOW + "Restauración cancelada."
                : ChatColor.GRAY + "No hay una restauración activa.");
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT)
                .startsWith(lower)).toList();
    }

    private String[] tokenize(String raw) {
        String clean = raw == null ? "" : raw.trim();
        if (clean.startsWith("/")) clean = clean.substring(1);
        return clean.isBlank() ? new String[0] : clean.split("\\s+");
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo template overworld terrain "
                + "<preview|apply|status|cancel> <citadel|coast|mine-surface|all-damage|radio>");
    }

    private enum Policy { PRESERVE_ARCHITECTURE, CLEAR_GENERATED_VOLUME }
    private record Center(int x, int z) { }
    private record Zone(String name, int centerX, int centerZ, int radius,
                        Policy policy, boolean clearStructures, int structureClearRadius) { }
    private record Selection(String token, String label, List<Zone> zones,
                             boolean rebuildCitadel) { }
    private record Edit(int x, int y, int z, Material material) { }
    private record TerrainPlan(boolean valid, String message, List<Edit> edits,
                               int changedColumns, int protectedColumns) {
        static TerrainPlan invalid(String message) {
            return new TerrainPlan(false, message, List.of(), 0, 0);
        }
    }
}
