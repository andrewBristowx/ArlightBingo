package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
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
import java.util.Set;

/**
 * Reparador regional, manual y reversible para cicatrices de terreno producidas por
 * la restauración defectuosa de minas 1.48.39. Nunca escanea toda la isla: el admin
 * debe colocarse sobre la zona dañada y elegir el radio. Conserva columnas que
 * contienen arquitectura y crea un snapshot completo antes de aplicar cambios.
 */
final class OverworldTerrainBlendCommands {

    private static final String TEMPLATE_MARKER = "arlight-overworld-template.properties";
    private static final String LEGACY_DECORATION_MARKER = "arlight-overworld-decoration.properties";
    private static final String REVISION = "1.48.40-player-centered-terrain-blend-1";
    private static final DateTimeFormatter SNAPSHOT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int DEFAULT_RADIUS = 72;
    private static final int MIN_RADIUS = 16;
    private static final int MAX_RADIUS = 128;

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
                && (args[3].equalsIgnoreCase("terrain")
                || args[3].equalsIgnoreCase("terreno"));
    }

    void execute(CommandSender sender, String rawCommand) {
        if (!sender.hasPermission("arlightbingo.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para reparar el terreno.");
            return;
        }
        String[] args = tokenize(rawCommand);
        String action = args.length >= 5 ? args[4].toLowerCase(Locale.ROOT) : "help";
        if (action.equals("status")) {
            sender.sendMessage(ChatColor.AQUA + "Reparación regional 1.48.40: "
                    + ChatColor.WHITE + status());
            return;
        }
        if (action.equals("cancel")) {
            cancel(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Debes ejecutar este comando dentro de la zona dañada.");
            return;
        }
        int radius = parseRadius(sender, args.length >= 6 ? args[5] : null);
        if (radius < 0) return;
        if (action.equals("preview")) {
            preview(player, radius);
            return;
        }
        if (action.equals("apply") || action.equals("blend") || action.equals("repair")) {
            start(player, radius);
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
        if (logical == 5 && args.length >= 4
                && (args[3].equalsIgnoreCase("terrain") || args[3].equalsIgnoreCase("terreno"))) {
            String prefix = trailing ? "" : args[4];
            return filter(List.of("preview", "apply", "status", "cancel"), prefix);
        }
        if (logical == 6 && args.length >= 5
                && (args[4].equalsIgnoreCase("preview") || args[4].equalsIgnoreCase("apply"))) {
            String prefix = trailing ? "" : args[5];
            return filter(List.of("48", "64", "72", "96", "112", "128"), prefix);
        }
        return List.of();
    }

    void onWorldUnload(World world) {
        // El snapshot descarga y vuelve a cargar la plantilla deliberadamente.
    }

    private void preview(Player player, int radius) {
        World world = resolveTemplateWorld(player);
        if (world == null) return;
        Center center = new Center(player.getLocation().getBlockX(), player.getLocation().getBlockZ());
        TerrainPlan plan = plan(world, center, radius);
        if (!plan.valid()) {
            player.sendMessage(ChatColor.RED + plan.message());
            return;
        }
        player.sendMessage(ChatColor.AQUA + "=== Previsualización terreno Overworld 1.48.40 ===");
        player.sendMessage(ChatColor.GRAY + "- centro=" + ChatColor.WHITE
                + center.x() + ", " + center.z());
        player.sendMessage(ChatColor.GRAY + "- radio=" + ChatColor.WHITE + radius);
        player.sendMessage(ChatColor.GRAY + "- columnas reparables=" + ChatColor.WHITE
                + plan.changedColumns());
        player.sendMessage(ChatColor.GRAY + "- columnas protegidas por arquitectura="
                + ChatColor.WHITE + plan.protectedColumns());
        player.sendMessage(ChatColor.GRAY + "- operaciones=" + ChatColor.WHITE + plan.edits().size());
        player.sendMessage(ChatColor.GREEN + "- método=" + ChatColor.WHITE
                + "pendiente calculada desde el borde, transición radial y decoración musgosa");
        player.sendMessage(ChatColor.GREEN + "- protegido=" + ChatColor.WHITE
                + "puerto, cofres, muros, casas, torres, caminos construidos y mobiliario");
        player.sendMessage(ChatColor.YELLOW + "Aplicar desde este mismo punto con: "
                + ChatColor.WHITE + "/bingo template overworld terrain apply " + radius);
    }

    private void start(Player player, int radius) {
        if (busy()) {
            player.sendMessage(ChatColor.YELLOW + "Ya hay una reparación activa: " + status());
            return;
        }
        World world = resolveTemplateWorld(player);
        if (world == null) return;
        Center center = new Center(player.getLocation().getBlockX(), player.getLocation().getBlockZ());
        TerrainPlan preview = plan(world, center, radius);
        if (!preview.valid()) {
            player.sendMessage(ChatColor.RED + preview.message());
            return;
        }
        String snapshot = "pre-terrain-blend-" + SNAPSHOT_TIME.format(Instant.now());
        String worldName = world.getName();
        Path expected = plugin.getDataFolder().toPath().resolve("template-snapshots")
                .resolve("overworld").resolve(snapshot);
        detail = "esperando snapshot " + snapshot;
        player.sendMessage(ChatColor.YELLOW + "Creando snapshot completo antes de reparar el terreno...");
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
                startApply(player, reloaded, center, radius, snapshot);
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

    private void startApply(Player player, World world, Center center, int radius, String snapshot) {
        TerrainPlan plan = plan(world, center, radius);
        if (!plan.valid() || plan.edits().isEmpty()) {
            detail = "sin operaciones";
            player.sendMessage(ChatColor.RED + (plan.message().isBlank()
                    ? "No se encontraron columnas reparables." : plan.message()));
            return;
        }
        List<Edit> edits = plan.edits();
        cursor = 0;
        total = edits.size();
        changed = 0;
        detail = "aplicando 0/" + total;
        int perTick = Math.max(300, plugin.getConfig().getInt(
                "template-worlds.overworld.terrain-blend.blocks-per-tick", 1200));
        long maxNanos = Math.max(2L, plugin.getConfig().getLong(
                "template-worlds.overworld.terrain-blend.max-millis-per-tick", 6L))
                * 1_000_000L;

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
            writeMarker(world, center, radius, snapshot, plan, changed);
            world.save();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
            detail = "completa · " + changed + " bloques cambiados";
            player.sendMessage(ChatColor.GREEN + "Terreno reparado y redecorado en radio " + radius + ".");
            player.sendMessage(ChatColor.YELLOW + "Revisa el borde completo antes de reparar otra zona.");
        }, 1L, 1L);
        player.sendMessage(ChatColor.GREEN + "Snapshot confirmado: " + snapshot + ".");
        player.sendMessage(ChatColor.GREEN + "Reparación regional iniciada: "
                + total + " operaciones protegidas.");
    }

    private TerrainPlan plan(World world, Center center, int radius) {
        if (isProtectedHarbor(world, center.x(), center.z(), radius)) {
            return TerrainPlan.invalid("La zona seleccionada entra en el puerto protegido.");
        }
        List<Sample> samples = boundarySamples(world, center, radius);
        if (samples.size() < 14) {
            return TerrainPlan.invalid("No hay suficiente borde natural para calcular una pendiente segura.");
        }
        Plane plane = fitPlane(samples);
        if (plane == null) {
            return TerrainPlan.invalid("No se pudo calcular la pendiente del terreno.");
        }

        LinkedHashMap<Long, Edit> edits = new LinkedHashMap<>();
        List<GroundPoint> decoration = new ArrayList<>();
        int protectedColumns = 0;
        int changedColumns = 0;
        int radiusSq = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distSq = dx * dx + dz * dz;
                if (distSq > radiusSq) continue;
                int x = center.x() + dx;
                int z = center.z() + dz;
                int top = terrainSurfaceY(world, x, z);
                if (columnContainsArchitecture(world, x, z, top)) {
                    protectedColumns++;
                    continue;
                }
                double distance = Math.sqrt(distSq);
                double radial = Math.max(0.0D, 1.0D - distance / Math.max(1.0D, radius));
                double strength = Math.pow(radial, 1.35D) * 0.94D;
                if (strength < 0.025D) continue;
                double noise = terrainNoise(x, z) * Math.min(2.2D, 0.6D + radial * 1.7D);
                int expected = (int) Math.round(plane.yAt(x, z) + noise);
                int delta = expected - top;
                delta = Math.max(-52, Math.min(42, delta));
                int target = (int) Math.round(top + delta * strength);
                if (Math.abs(target - top) <= 1 && !looksLikeDamageSurface(world, x, top, z)) continue;
                target = Math.max(world.getMinHeight() + 8,
                        Math.min(world.getMaxHeight() - 24, target));

                int clearTop = Math.min(world.getMaxHeight() - 2, Math.max(top + 14, target + 12));
                for (int y = target + 1; y <= clearTop; y++) {
                    Material current = world.getBlockAt(x, y, z).getType();
                    if (isTerrainRepairable(current)) put(edits, new Edit(x, y, z, Material.AIR));
                }
                int fillFrom = Math.max(world.getMinHeight() + 1, Math.min(top, target) - 12);
                for (int y = fillFrom; y <= target; y++) {
                    Material material;
                    if (y == target) material = infectedTop(x, z);
                    else if (y >= target - 3) material = Material.DIRT;
                    else material = subsurfaceMaterial(y, x, z);
                    put(edits, new Edit(x, y, z, material));
                }
                decoration.add(new GroundPoint(x, target, z, radial));
                changedColumns++;
            }
        }
        planDecoration(world, edits, decoration);
        return new TerrainPlan(true, "", new ArrayList<>(edits.values()),
                changedColumns, protectedColumns, samples.size());
    }

    private void planDecoration(World world, LinkedHashMap<Long, Edit> edits,
                                List<GroundPoint> points) {
        for (GroundPoint point : points) {
            if (point.radial() < 0.18D) continue;
            int hash = stableHash(point.x(), point.z());
            int above = point.y() + 1;
            if (Math.floorMod(hash, 23) == 0) {
                put(edits, new Edit(point.x(), above, point.z(), Material.MOSS_CARPET));
            } else if (Math.floorMod(hash, 79) == 0) {
                put(edits, new Edit(point.x(), above, point.z(),
                        Math.floorMod(hash, 2) == 0 ? Material.AZALEA : Material.FLOWERING_AZALEA));
            } else if (Math.floorMod(hash, 101) == 0) {
                put(edits, new Edit(point.x(), above, point.z(), Material.FERN));
            }
            if (point.radial() > 0.35D && Math.floorMod(hash, 157) == 0) {
                planMossRock(edits, point, hash);
            }
            if (point.radial() > 0.42D && Math.floorMod(hash, 263) == 0) {
                planCrystal(edits, point, hash);
            }
            if (point.radial() > 0.48D && Math.floorMod(hash, 337) == 0
                    && clearTreeColumn(world, point.x(), point.y(), point.z())) {
                planSmallTree(edits, point, hash);
            }
        }
    }

    private void planMossRock(LinkedHashMap<Long, Edit> edits, GroundPoint point, int hash) {
        int height = 1 + Math.floorMod(hash >>> 3, 3);
        for (int h = 1; h <= height; h++) {
            put(edits, new Edit(point.x(), point.y() + h, point.z(),
                    h == height ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE));
        }
        if (height >= 2) {
            put(edits, new Edit(point.x() + 1, point.y() + 1, point.z(), Material.MOSSY_COBBLESTONE));
        }
    }

    private void planCrystal(LinkedHashMap<Long, Edit> edits, GroundPoint point, int hash) {
        int height = 2 + Math.floorMod(hash >>> 4, 4);
        for (int h = 1; h <= height; h++) {
            Material material = h == height ? Material.AMETHYST_BLOCK
                    : (h % 3 == 0 ? Material.BUDDING_AMETHYST : Material.AMETHYST_BLOCK);
            put(edits, new Edit(point.x(), point.y() + h, point.z(), material));
        }
    }

    private void planSmallTree(LinkedHashMap<Long, Edit> edits, GroundPoint point, int hash) {
        int height = 5 + Math.floorMod(hash >>> 5, 4);
        for (int h = 1; h <= height; h++) {
            put(edits, new Edit(point.x(), point.y() + h, point.z(), Material.DARK_OAK_LOG));
        }
        int crownY = point.y() + height;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                for (int oy = -1; oy <= 2; oy++) {
                    if (Math.abs(ox) + Math.abs(oz) + Math.max(0, oy) > 5) continue;
                    if (ox == 0 && oz == 0 && oy <= 0) continue;
                    Material leaves = Math.floorMod(hash + ox * 17 + oz * 31 + oy * 13, 7) == 0
                            ? Material.FLOWERING_AZALEA_LEAVES : Material.AZALEA_LEAVES;
                    put(edits, new Edit(point.x() + ox, crownY + oy, point.z() + oz, leaves));
                }
            }
        }
    }

    private List<Sample> boundarySamples(World world, Center center, int radius) {
        List<Sample> samples = new ArrayList<>();
        int ring = radius + 8;
        for (int degrees = 0; degrees < 360; degrees += 8) {
            double angle = Math.toRadians(degrees);
            int x = center.x() + (int) Math.round(Math.cos(angle) * ring);
            int z = center.z() + (int) Math.round(Math.sin(angle) * ring);
            int y = terrainSurfaceY(world, x, z);
            if (columnContainsArchitecture(world, x, z, y)) continue;
            samples.add(new Sample(x, z, y));
        }
        return samples;
    }

    private Plane fitPlane(List<Sample> samples) {
        double sX = 0, sZ = 0, sY = 0, sXX = 0, sZZ = 0, sXZ = 0, sXY = 0, sZY = 0;
        for (Sample sample : samples) {
            double x = sample.x();
            double z = sample.z();
            double y = sample.y();
            sX += x;
            sZ += z;
            sY += y;
            sXX += x * x;
            sZZ += z * z;
            sXZ += x * z;
            sXY += x * y;
            sZY += z * y;
        }
        double[][] matrix = {
                {sXX, sXZ, sX, sXY},
                {sXZ, sZZ, sZ, sZY},
                {sX, sZ, samples.size(), sY}
        };
        for (int pivot = 0; pivot < 3; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < 3; row++) {
                if (Math.abs(matrix[row][pivot]) > Math.abs(matrix[best][pivot])) best = row;
            }
            if (Math.abs(matrix[best][pivot]) < 1.0E-8D) return null;
            double[] swap = matrix[pivot];
            matrix[pivot] = matrix[best];
            matrix[best] = swap;
            double divisor = matrix[pivot][pivot];
            for (int col = pivot; col < 4; col++) matrix[pivot][col] /= divisor;
            for (int row = 0; row < 3; row++) {
                if (row == pivot) continue;
                double factor = matrix[row][pivot];
                for (int col = pivot; col < 4; col++) {
                    matrix[row][col] -= factor * matrix[pivot][col];
                }
            }
        }
        return new Plane(matrix[0][3], matrix[1][3], matrix[2][3]);
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
        int min = Math.max(world.getMinHeight(), top - 18);
        int max = Math.min(world.getMaxHeight() - 1, top + 18);
        int architecture = 0;
        for (int y = min; y <= max; y++) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (isArchitectureMaterial(material)) architecture++;
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
                || material == Material.SOUL_LANTERN || material == Material.CHAIN
                || material == Material.RAIL || material == Material.POWERED_RAIL
                || material == Material.DETECTOR_RAIL || material == Material.ACTIVATOR_RAIL;
    }

    private boolean looksLikeDamageSurface(World world, int x, int y, int z) {
        Material top = world.getBlockAt(x, y, z).getType();
        if (!(top == Material.GRASS_BLOCK || top == Material.STONE
                || top == Material.ANDESITE || top == Material.DIORITE
                || top == Material.GRANITE || top == Material.COBBLESTONE
                || top == Material.COBBLED_DEEPSLATE || top == Material.MOSS_BLOCK)) return false;
        int same = 0;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                if (terrainSurfaceY(world, x + ox, z + oz) == y) same++;
            }
        }
        return same >= 20;
    }

    private boolean isTerrainRepairable(Material material) {
        String name = material.name();
        return material.isAir() || isPlant(material) || isNatural(material)
                || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_LEAVES") || name.endsWith("_ORE")
                || material == Material.WATER || material == Material.LAVA
                || material == Material.RAIL || material == Material.POWERED_RAIL
                || material == Material.DETECTOR_RAIL || material == Material.ACTIVATOR_RAIL
                || material == Material.BARREL || material == Material.CHEST
                || material == Material.CRAFTING_TABLE || material == Material.LANTERN
                || material == Material.SOUL_LANTERN || material == Material.CHAIN;
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

    private Material infectedTop(int x, int z) {
        int value = Math.floorMod(stableHash(x, z), 100);
        if (value < 58) return Material.MOSS_BLOCK;
        if (value < 73) return Material.GRASS_BLOCK;
        if (value < 84) return Material.PODZOL;
        if (value < 93) return Material.ROOTED_DIRT;
        return Material.COARSE_DIRT;
    }

    private Material subsurfaceMaterial(int y, int x, int z) {
        if (y < 16) return Material.DEEPSLATE;
        int value = Math.floorMod(x * 31 + y * 17 + z * 13, 17);
        if (value == 0) return Material.TUFF;
        if (value == 1) return Material.ANDESITE;
        return Material.STONE;
    }

    private boolean clearTreeColumn(World world, int x, int y, int z) {
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                for (int h = 1; h <= 10; h++) {
                    Material material = world.getBlockAt(x + ox, y + h, z + oz).getType();
                    if (!material.isAir() && !isPlant(material)
                            && !material.name().endsWith("_LOG")
                            && !material.name().endsWith("_WOOD")) return false;
                }
            }
        }
        return true;
    }

    private double terrainNoise(int x, int z) {
        double first = Math.sin(x * 0.071D + z * 0.043D);
        double second = Math.cos(x * 0.029D - z * 0.061D);
        return (first + second) * 0.5D;
    }

    private int stableHash(int x, int z) {
        int value = x * 73428767 ^ z * 912931;
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        return value;
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

    private void writeMarker(World world, Center center, int radius, String snapshot,
                             TerrainPlan plan, int changedBlocks) {
        String text = "version=1.48.40\n"
                + "revision=" + REVISION + "\n"
                + "appliedAt=" + System.currentTimeMillis() + "\n"
                + "center=" + center.x() + "," + center.z() + "\n"
                + "radius=" + radius + "\n"
                + "snapshot=" + snapshot + "\n"
                + "changedBlocks=" + changedBlocks + "\n"
                + "changedColumns=" + plan.changedColumns() + "\n"
                + "protectedColumns=" + plan.protectedColumns() + "\n"
                + "harborProtected=true\n"
                + "architectureProtected=true\n";
        try {
            Files.writeString(world.getWorldFolder().toPath().resolve(
                            "arlight-overworld-terrain-blend.properties"), text,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo escribir el marcador de terreno 1.48.40: "
                    + error.getMessage());
        }
    }

    private int parseRadius(CommandSender sender, String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_RADIUS;
        try {
            int radius = Integer.parseInt(raw);
            if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
                sender.sendMessage(ChatColor.RED + "El radio debe estar entre "
                        + MIN_RADIUS + " y " + MAX_RADIUS + ".");
                return -1;
            }
            return radius;
        } catch (NumberFormatException error) {
            sender.sendMessage(ChatColor.RED + "El radio debe ser un número.");
            return -1;
        }
    }

    private boolean busy() {
        return waitTask != null || applyTask != null;
    }

    private String status() {
        if (waitTask != null) return detail;
        if (applyTask != null) return detail;
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
        sender.sendMessage(cancelled ? ChatColor.YELLOW + "Reparación regional cancelada."
                : ChatColor.GRAY + "No hay una reparación regional activa.");
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
                + "<preview|apply|status|cancel> [radio 16-128]");
    }

    private record Center(int x, int z) { }
    private record Sample(int x, int z, int y) { }
    private record Plane(double a, double b, double c) {
        double yAt(int x, int z) { return a * x + b * z + c; }
    }
    private record Edit(int x, int y, int z, Material material) { }
    private record GroundPoint(int x, int y, int z, double radial) { }
    private record TerrainPlan(boolean valid, String message, List<Edit> edits,
                               int changedColumns, int protectedColumns, int boundarySamples) {
        static TerrainPlan invalid(String message) {
            return new TerrainPlan(false, message, List.of(), 0, 0, 0);
        }
    }
}
