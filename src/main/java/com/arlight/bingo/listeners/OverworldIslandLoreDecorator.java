package com.arlight.bingo.listeners;

import com.arlight.bingo.BingoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Decorador manual y seguro del exterior de la plantilla Overworld.
 *
 * Esta capa intercepta exclusivamente /bingo template overworld decorate para
 * impedir que el decorador 1.48.31 vuelva a reconstruir el puerto que el usuario
 * ya editó a mano. Todas las operaciones nuevas se mantienen tierra adentro y
 * sólo reemplazan aire, plantas o terreno natural. La mina valida una montaña
 * natural antes de excavar y registra las coordenadas de la geoda y de la futura
 * arena opcional de jefe para poder conectarla más adelante con la campaña.
 */
final class OverworldIslandLoreDecorator {

    private static final String TEMPLATE_MARKER = "arlight-overworld-template.properties";
    private static final String LEGACY_DECORATION_MARKER = "arlight-overworld-decoration.properties";
    private static final String LORE_MARKER = "arlight-overworld-lore-decoration.properties";
    private static final String REVISION = "1.48.35-connected-mine-persistent-ecology-corruption-1";
    private static final int DEFAULT_BATCH = 1400;
    private static final int PORT_PROTECTION_RADIUS = 220;

    private static final List<String> ACTIONS = List.of(
            "preview", "apply", "upgrade", "all", "forests", "camps", "mine", "corruption",
            "status", "cancel", "force", "help"
    );

    private final BingoPlugin plugin;
    private BukkitTask task;
    private World activeWorld;
    private String detail = "sin iniciar";
    private int processed;
    private int changed;
    private int total;
    private String activeCategory = "ninguna";

    OverworldIslandLoreDecorator(BingoPlugin plugin) {
        this.plugin = plugin;
    }

    boolean matches(String rawCommand) {
        String[] args = tokenize(rawCommand);
        return args.length >= 4
                && args[0].equalsIgnoreCase("bingo")
                && args[1].equalsIgnoreCase("template")
                && args[2].equalsIgnoreCase("overworld")
                && args[3].equalsIgnoreCase("decorate");
    }

    void execute(CommandSender sender, String rawCommand) {
        if (!sender.hasPermission("arlightbingo.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para decorar la plantilla.");
            return;
        }

        String[] args = tokenize(rawCommand);
        String action = args.length >= 5 ? args[4].toLowerCase(Locale.ROOT) : "help";
        String secondary = args.length >= 6 ? args[5].toLowerCase(Locale.ROOT) : "";

        if (action.equals("help")) {
            sendUsage(sender);
            return;
        }
        if (action.equals("status")) {
            sender.sendMessage(ChatColor.GREEN + "Decoración Overworld 1.48.35: "
                    + ChatColor.WHITE + status(sender));
            return;
        }
        if (action.equals("cancel")) {
            cancel(sender);
            return;
        }

        boolean force = action.equals("force") || secondary.equals("force");
        String category = action.equals("force")
                ? (secondary.isBlank() ? "all" : secondary)
                : action;
        if (category.equals("apply")) category = "all";
        if (category.equals("upgrade")) category = "all";

        if (category.equals("preview")) {
            preview(sender, secondary.isBlank() ? "all" : secondary);
            return;
        }
        if (!Set.of("all", "forests", "camps", "mine", "corruption").contains(category)) {
            sendUsage(sender);
            return;
        }
        start(sender, category, force);
    }

    List<String> completions(String buffer) {
        String clean = buffer == null ? "" : buffer.trim();
        if (clean.startsWith("/")) clean = clean.substring(1);
        String[] args = clean.isEmpty() ? new String[0] : clean.split("\\s+");
        boolean trailingSpace = buffer != null && buffer.endsWith(" ");
        int logicalLength = trailingSpace ? args.length + 1 : args.length;
        if (logicalLength == 5) {
            String prefix = trailingSpace ? "" : args[4];
            return filter(ACTIONS, prefix);
        }
        if (logicalLength == 6 && args.length >= 5
                && (args[4].equalsIgnoreCase("preview") || args[4].equalsIgnoreCase("force"))) {
            String prefix = trailingSpace ? "" : args[5];
            return filter(List.of("all", "forests", "camps", "mine", "corruption"), prefix);
        }
        return List.of();
    }

    void onWorldUnload(World world) {
        if (world == null || activeWorld == null || !world.getUID().equals(activeWorld.getUID())) return;
        if (task != null) task.cancel();
        task = null;
        activeWorld = null;
        detail = "cancelada porque el mundo fue descargado";
    }

    private void preview(CommandSender sender, String requestedCategory) {
        String category = normalizeCategory(requestedCategory);
        if (category == null) {
            sendUsage(sender);
            return;
        }
        World world = resolveWorkingWorld(sender);
        if (world == null) return;
        Anchors anchors = loadAnchors(world);
        MineSite mine = findMineSite(world, anchors);

        sender.sendMessage(ChatColor.AQUA + "=== Previsualización decoración Overworld 1.48.35 ===");
        sender.sendMessage(ChatColor.GRAY + "- categoría=" + ChatColor.WHITE + category);
        sender.sendMessage(ChatColor.GRAY + "- puerto=" + ChatColor.GREEN
                + "PROTEGIDO: el comando no planifica ni cambia ningún bloque del puerto");
        if (anchors.harbor() != null) {
            sender.sendMessage(ChatColor.GRAY + "- exclusión del puerto=" + ChatColor.WHITE
                    + coordinates(anchors.harbor()) + " · radio " + PORT_PROTECTION_RADIUS);
        }
        sender.sendMessage(ChatColor.GRAY + "- bosques=" + ChatColor.WHITE
                + "5 masas forestales grandes, sotobosque denso, troncos y hojas persistentes");
        sender.sendMessage(ChatColor.GRAY + "- campamentos=" + ChatColor.WHITE
                + "reconstrucción sobre terrazas y soportes para que ninguno quede flotando");
        sender.sendMessage(ChatColor.GRAY + "- corrupción=" + ChatColor.WHITE
                + "varias venas desde la mina, parches mayores, raíces, cristales y árboles enfermos");
        if (mine == null) {
            sender.sendMessage(ChatColor.RED + "- mina=no se encontró todavía una montaña natural segura");
        } else {
            sender.sendMessage(ChatColor.GRAY + "- entrada de la mina=" + ChatColor.WHITE
                    + mine.entranceX() + "," + mine.entranceY() + "," + mine.entranceZ());
            sender.sendMessage(ChatColor.GRAY + "- geoda gigante=" + ChatColor.LIGHT_PURPLE
                    + mine.geodeX() + "," + mine.geodeY() + "," + mine.geodeZ());
            sender.sendMessage(ChatColor.GRAY + "- futura arena opcional=" + ChatColor.GOLD
                    + mine.bossX() + "," + mine.bossY() + "," + mine.bossZ());
            sender.sendMessage(ChatColor.GRAY + "- conectividad=" + ChatColor.GREEN
                    + "galería principal, cuartos, ramales y geoda conectados sin picar");
        }
        sender.sendMessage(ChatColor.YELLOW + "Aplicar con: " + ChatColor.WHITE
                + "/bingo template overworld decorate " + category);
    }

    private synchronized void start(CommandSender sender, String requestedCategory, boolean force) {
        String category = normalizeCategory(requestedCategory);
        if (category == null) {
            sendUsage(sender);
            return;
        }
        if (task != null) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una decoración activa: " + status(sender));
            return;
        }
        var templateStage = plugin.getOverworldTemplateManager().stage();
        if (templateStage == com.arlight.bingo.template.OverworldTemplateManager.Stage.CREATING_WORLD
                || templateStage == com.arlight.bingo.template.OverworldTemplateManager.Stage.PREGENERATING
                || templateStage == com.arlight.bingo.template.OverworldTemplateManager.Stage.PLANNING
                || templateStage == com.arlight.bingo.template.OverworldTemplateManager.Stage.BUILDING
                || templateStage == com.arlight.bingo.template.OverworldTemplateManager.Stage.STABILIZING) {
            sender.sendMessage(ChatColor.RED + "La plantilla todavía está ocupada. Estado: "
                    + plugin.getOverworldTemplateManager().status());
            return;
        }
        if (plugin.getOverworldTemplateManager().workingRevision().isBlank()) {
            sender.sendMessage(ChatColor.RED + "No existe una revisión de trabajo. "
                    + "La plantilla estable no se modificará directamente.");
            return;
        }

        World world = resolveWorkingWorld(sender);
        if (world == null) return;
        Properties existing = readProperties(world.getWorldFolder().toPath().resolve(LORE_MARKER));
        Set<String> categories = categoriesFor(category, existing, force);
        if (categories.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "La categoría ya está aplicada. Usa 'force "
                    + category + "' únicamente si deseas reconstruirla.");
            return;
        }

        Anchors anchors = loadAnchors(world);
        MineSite mine = categories.contains("mine") || categories.contains("corruption")
                ? findMineSite(world, anchors) : readMineSite(existing);
        if (categories.contains("mine") && mine == null) {
            sender.sendMessage(ChatColor.RED + "No se encontró una montaña natural segura para la mina. "
                    + "No se realizó ningún cambio.");
            return;
        }

        Plan plan = new Plan();
        if (categories.contains("forests")) planForests(world, plan, anchors);
        if (categories.contains("camps")) planCamps(world, plan, anchors, mine);
        boolean preserveExistingGeode = Boolean.parseBoolean(existing.getProperty("mine", "false"))
                && readMineSite(existing) != null;
        if (categories.contains("mine")) {
            planLargeLoreMine(world, plan, anchors, mine, preserveExistingGeode);
        }
        if (categories.contains("corruption")) planCorruption(world, plan, anchors, mine);

        if (plan.blocks.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No se encontraron ubicaciones naturales seguras para esta decoración.");
            return;
        }

        List<PlannedBlock> operations = new ArrayList<>(plan.blocks.values());
        int batch = Math.max(300, plugin.getConfig().getInt(
                "template-worlds.overworld.lore-decoration.blocks-per-tick", DEFAULT_BATCH));
        activeWorld = world;
        activeCategory = String.join(",", categories);
        processed = 0;
        changed = 0;
        total = operations.size();
        detail = "aplicando " + activeCategory;
        final int[] cursor = {0};
        final MineSite finalMine = mine;

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int limit = Math.min(operations.size(), cursor[0] + batch);
            for (; cursor[0] < limit; cursor[0]++) {
                PlannedBlock operation = operations.get(cursor[0]);
                if (applyBlock(world, operation)) changed++;
            }
            processed = cursor[0];
            detail = "procesando " + processed + "/" + total + " · " + changed + " cambios";
            if (cursor[0] < operations.size()) return;

            BukkitTask finished = task;
            task = null;
            if (finished != null) finished.cancel();
            for (Consumer<World> postAction : plan.postActions) {
                try {
                    postAction.accept(world);
                } catch (Throwable error) {
                    plugin.getLogger().warning("Postproceso de decoración omitido: " + error.getMessage());
                }
            }
            world.save();
            writeMarker(world, existing, categories, anchors, finalMine, changed);
            detail = "completa · " + changed + " bloques cambiados · puerto intacto";
            activeWorld = null;

            sender.sendMessage(ChatColor.GREEN + "Decoración terminada: " + activeCategory + ".");
            if (categories.contains("mine") && finalMine != null) {
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "Geoda gigante: " + ChatColor.WHITE
                        + finalMine.geodeX() + ", " + finalMine.geodeY() + ", " + finalMine.geodeZ());
                sender.sendMessage(ChatColor.GOLD + "Arena opcional futura: " + ChatColor.WHITE
                        + finalMine.bossX() + ", " + finalMine.bossY() + ", " + finalMine.bossZ());
            }
            sender.sendMessage(ChatColor.YELLOW + "El puerto fue excluido por completo. "
                    + "Revisa la isla y guarda con /bingo template overworld commit.");
        }, 1L, 1L);

        sender.sendMessage(ChatColor.GREEN + "Decoración 1.48.35 iniciada: " + activeCategory
                + " · " + operations.size() + " operaciones protegidas.");
        sender.sendMessage(ChatColor.GRAY + "El puerto existente no forma parte del plan y no será modificado.");
    }

    private void cancel(CommandSender sender) {
        if (task == null) {
            sender.sendMessage(ChatColor.YELLOW + "No hay una decoración activa.");
            return;
        }
        task.cancel();
        task = null;
        if (activeWorld != null) activeWorld.save();
        activeWorld = null;
        detail = "cancelada en " + processed + "/" + total + " · " + changed + " cambios conservados";
        sender.sendMessage(ChatColor.YELLOW + "Decoración cancelada. Los cambios ya hechos se conservaron; "
                + "la categoría no se marcó como terminada.");
    }

    private String status(CommandSender sender) {
        if (task != null) {
            int percentage = total <= 0 ? 0 : (int) Math.round(processed * 100.0D / total);
            return percentage + "% · " + detail + " · categoría=" + activeCategory;
        }
        World world = resolveWorldQuietly(sender);
        if (world == null) return detail;
        Properties properties = readProperties(world.getWorldFolder().toPath().resolve(LORE_MARKER));
        String revision = properties.getProperty("revision", "sin marcador");
        String categories = properties.getProperty("categories", "ninguna");
        String mine = properties.getProperty("mineEntrance", "sin mina");
        String geode = properties.getProperty("geodeCenter", "sin geoda");
        return revision + " · categorías=" + categories + " · mina=" + mine
                + " · geoda=" + geode + " · " + detail;
    }

    private Set<String> categoriesFor(String category, Properties existing, boolean force) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        if (category.equals("all")) requested.addAll(List.of("forests", "camps", "mine", "corruption"));
        else requested.add(category);
        if (force) return requested;
        requested.removeIf(value -> REVISION.equals(existing.getProperty(value + "Revision", "")));
        return requested;
    }

    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("apply") || raw.equals("upgrade")) return "all";
        String category = raw.toLowerCase(Locale.ROOT);
        if (Set.of("all", "forests", "camps", "mine", "corruption").contains(category)) return category;
        return null;
    }

    private World resolveWorkingWorld(CommandSender sender) {
        World world = resolveWorldQuietly(sender);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "No se encontró cargada la revisión Overworld de trabajo.");
            return null;
        }
        Path marker = world.getWorldFolder().toPath().resolve(TEMPLATE_MARKER);
        if (!Files.isRegularFile(marker)) {
            sender.sendMessage(ChatColor.RED + "El mundo " + world.getName()
                    + " no tiene el marcador de plantilla Overworld.");
            return null;
        }
        return world;
    }

    private World resolveWorldQuietly(CommandSender sender) {
        String configured = plugin.getConfig().getString(
                "template-worlds.overworld.name", "bingo_template_overworld");
        if (sender instanceof Player player) {
            World current = player.getWorld();
            if (current.getName().equalsIgnoreCase(configured)
                    || current.getName().startsWith(configured)
                    || Files.isRegularFile(current.getWorldFolder().toPath().resolve(TEMPLATE_MARKER))) {
                return current;
            }
        }
        World direct = Bukkit.getWorld(configured);
        if (direct != null) return direct;
        return Bukkit.getWorlds().stream()
                .filter(world -> world.getName().startsWith(configured)
                        && Files.isRegularFile(world.getWorldFolder().toPath().resolve(TEMPLATE_MARKER)))
                .findFirst().orElse(null);
    }

    private Anchors loadAnchors(World world) {
        Properties template = readProperties(world.getWorldFolder().toPath().resolve(TEMPLATE_MARKER));
        Anchor village = parseAnchor(template.getProperty("village"));
        Anchor dungeon = parseAnchor(template.getProperty("dungeon"));
        if (village == null) {
            int x = plugin.getConfig().getInt("template-worlds.overworld.layout.village-x", -360);
            int z = plugin.getConfig().getInt("template-worlds.overworld.layout.village-z", 0);
            village = surfaceAnchor(world, x, z);
        }
        if (dungeon == null) {
            int x = plugin.getConfig().getInt("template-worlds.overworld.layout.city-x", 420);
            int z = plugin.getConfig().getInt("template-worlds.overworld.layout.city-z", 0);
            dungeon = surfaceAnchor(world, x, z);
        }

        Properties legacy = readProperties(world.getWorldFolder().toPath().resolve(LEGACY_DECORATION_MARKER));
        Anchor harbor = parseAnchor(legacy.getProperty("harbor"));
        return new Anchors(village, dungeon, harbor);
    }

    private MineSite findMineSite(World world, Anchors anchors) {
        Anchor dungeon = anchors.dungeon();
        Anchor village = anchors.village();
        List<MineCandidate> candidates = new ArrayList<>();
        int[] radii = {190, 225, 260, 300, 340, 385, 430};
        int[][] directions = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        for (int radius : radii) {
            for (int[] direction : directions) {
                int scale = direction[0] != 0 && direction[1] != 0
                        ? (int) Math.round(radius / Math.sqrt(2.0D)) : radius;
                int x = dungeon.x() + direction[0] * scale;
                int z = dungeon.z() + direction[1] * scale;
                MineCandidate candidate = scoreMineCandidate(world, anchors, village, dungeon, x, z);
                if (candidate != null) candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparingDouble(MineCandidate::score).reversed());
        for (MineCandidate candidate : candidates) {
            MineSite site = buildMineSite(world, candidate);
            if (site != null && isMineVolumeNatural(world, site)) return site;
        }
        return null;
    }

    private MineCandidate scoreMineCandidate(World world, Anchors anchors, Anchor village,
                                              Anchor dungeon, int x, int z) {
        int y = surfaceY(world, x, z);
        Material top = world.getBlockAt(x, y, z).getType();
        if (y < 96 || !isNatural(top) || isProtectedPort(anchors, x, z)) return null;
        if (distanceSquared(x, z, village.x(), village.z()) < 150L * 150L) return null;
        if (distanceSquared(x, z, dungeon.x(), dungeon.z()) < 145L * 145L) return null;
        double natural = naturalSurfaceRatio(world, x, z, 18, 6);
        if (natural < 0.90D || constructedCount(world, x, y, z, 16, 10) > 8) return null;

        int bestDx = 0;
        int bestDz = 0;
        int bestRise = Integer.MIN_VALUE;
        int[][] headings = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] heading : headings) {
            int ahead24 = surfaceY(world, x + heading[0] * 24, z + heading[1] * 24);
            int ahead52 = surfaceY(world, x + heading[0] * 52, z + heading[1] * 52);
            int behind = surfaceY(world, x - heading[0] * 14, z - heading[1] * 14);
            int rise = (ahead24 - y) * 3 + (ahead52 - y) * 2 + (y - behind);
            if (rise > bestRise) {
                bestRise = rise;
                bestDx = heading[0];
                bestDz = heading[1];
            }
        }
        if (bestRise < 10) return null;
        double score = y * 4.0D + bestRise * 8.0D + natural * 100.0D
                - Math.sqrt(distanceSquared(x, z, dungeon.x(), dungeon.z())) * 0.08D;
        return new MineCandidate(x, y + 1, z, bestDx, bestDz, score);
    }

    private MineSite buildMineSite(World world, MineCandidate candidate) {
        int tunnelLength = 136;
        int geodeDistance = 136;
        int geodeX = candidate.x() + candidate.dx() * geodeDistance;
        int geodeZ = candidate.z() + candidate.dz() * geodeDistance;
        int calculatedY = candidate.y() - 8 - geodeDistance / 3;
        int surfaceAtGeode = surfaceY(world, geodeX, geodeZ);
        int geodeY = Math.max(world.getMinHeight() + 34,
                Math.min(calculatedY, surfaceAtGeode - 28));
        if (surfaceAtGeode - geodeY < 22) return null;
        int bossX = geodeX;
        int bossY = geodeY - 6;
        int bossZ = geodeZ;
        return new MineSite(candidate.x(), candidate.y(), candidate.z(),
                candidate.dx(), candidate.dz(), tunnelLength,
                geodeX, geodeY, geodeZ, bossX, bossY, bossZ);
    }

    private boolean isMineVolumeNatural(World world, MineSite site) {
        int samples = 0;
        int natural = 0;
        for (int t = 10; t <= site.tunnelLength(); t += 12) {
            int floor = site.entranceY() - 2 - t / 3;
            int x = site.entranceX() + site.dx() * t;
            int z = site.entranceZ() + site.dz() * t;
            for (int side = -3; side <= 3; side += 3) {
                for (int height = 0; height <= 5; height += 2) {
                    int sx = x - site.dz() * side;
                    int sz = z + site.dx() * side;
                    Material material = world.getBlockAt(sx, floor + height, sz).getType();
                    samples++;
                    if (isMineReplaceable(material)) natural++;
                }
            }
        }
        for (int ox = -18; ox <= 18; ox += 6) {
            for (int oy = -12; oy <= 12; oy += 6) {
                for (int oz = -18; oz <= 18; oz += 6) {
                    samples++;
                    if (isMineReplaceable(world.getBlockAt(
                            site.geodeX() + ox, site.geodeY() + oy, site.geodeZ() + oz).getType())) {
                        natural++;
                    }
                }
            }
        }
        return samples > 0 && natural >= samples * 0.82D;
    }

    private void planForests(World world, Plan plan, Anchors anchors) {
        List<Anchor> centers = findDecorationSites(world, anchors, 5, 120, 405, 58, true);
        long seed = 148350100L;
        for (int index = 0; index < centers.size(); index++) {
            Anchor center = centers.get(index);
            boolean infected = index >= Math.max(2, centers.size() - 2);
            int radius = infected ? 68 + index * 2 : 76 + index * 5;
            int trees = infected ? 106 + index * 8 : 132 + index * 18;
            planLargeForest(world, plan, anchors, center, radius, trees, infected,
                    seed + index * 104729L);
        }
        planExistingLeafPersistence(world, plan, anchors);
    }

    private void planExistingLeafPersistence(World world, Plan plan, Anchors anchors) {
        int minimumX = Math.min(anchors.village().x(), anchors.dungeon().x()) - 430;
        int maximumX = Math.max(anchors.village().x(), anchors.dungeon().x()) + 430;
        int minimumZ = Math.min(anchors.village().z(), anchors.dungeon().z()) - 430;
        int maximumZ = Math.max(anchors.village().z(), anchors.dungeon().z()) + 430;
        Set<Long> visited = new HashSet<>();
        for (int x = minimumX; x <= maximumX; x += 6) {
            for (int z = minimumZ; z <= maximumZ; z += 6) {
                if (isProtectedPort(anchors, x, z)) continue;
                int ground = surfaceY(world, x, z);
                for (int y = ground + 1; y <= ground + 22; y++) {
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (!isLeaf(material)) continue;
                    for (int ox = -4; ox <= 4; ox++) {
                        for (int oy = -5; oy <= 5; oy++) {
                            for (int oz = -4; oz <= 4; oz++) {
                                int bx = x + ox;
                                int by = y + oy;
                                int bz = z + oz;
                                long key = Plan.blockKey(bx, by, bz);
                                if (!visited.add(key)) continue;
                                Material leaf = world.getBlockAt(bx, by, bz).getType();
                                if (isLeaf(leaf)) {
                                    plan.put(bx, by, bz, leaf, Rule.LEAF_PERSISTENCE);
                                }
                            }
                        }
                    }
                    break;
                }
            }
        }
    }

    private void planLargeForest(World world, Plan plan, Anchors anchors, Anchor center,
                                 int radius, int treeCount, boolean infected, long seed) {
        Random random = new Random(seed);
        int accepted = 0;
        int attempts = treeCount * 12;
        for (int attempt = 0; attempt < attempts && accepted < treeCount; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(random.nextDouble()) * radius
                    * (0.72D + random.nextDouble() * 0.38D);
            int x = center.x() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.z() + (int) Math.round(Math.sin(angle) * distance);
            if (!safeNaturalSurface(world, anchors, x, z, 5)) continue;
            int y = surfaceY(world, x, z) + 1;
            int height = infected ? 5 + random.nextInt(7) : 5 + random.nextInt(5);
            planTree(plan, x, y, z, height, infected, random);
            accepted++;

            if (random.nextDouble() < 0.52D) {
                planUndergrowth(world, plan, anchors, x + random.nextInt(9) - 4,
                        z + random.nextInt(9) - 4, infected, random);
            }
            if (random.nextDouble() < 0.16D) {
                planFallenLog(world, plan, anchors, x + random.nextInt(13) - 6,
                        z + random.nextInt(13) - 6, random);
            }
            if (random.nextDouble() < 0.11D) {
                planForestBoulder(world, plan, anchors, x + random.nextInt(17) - 8,
                        z + random.nextInt(17) - 8, infected, random);
            }
        }

        for (int i = 0; i < radius * 5; i++) {
            int x = center.x() + random.nextInt(radius * 2 + 1) - radius;
            int z = center.z() + random.nextInt(radius * 2 + 1) - radius;
            planUndergrowth(world, plan, anchors, x, z, infected, random);
        }
        planForestWaypoint(world, plan, anchors, center, infected);
    }

    private void planTree(Plan plan, int x, int y, int z, int height,
                          boolean infected, Random random) {
        Material log;
        Material leaves;
        if (infected) {
            log = random.nextBoolean() ? Material.STRIPPED_DARK_OAK_LOG : Material.DARK_OAK_LOG;
            leaves = random.nextBoolean() ? Material.AZALEA_LEAVES : Material.DARK_OAK_LEAVES;
        } else if (random.nextInt(4) == 0) {
            log = Material.BIRCH_LOG;
            leaves = Material.BIRCH_LEAVES;
        } else if (random.nextInt(5) == 0) {
            log = Material.DARK_OAK_LOG;
            leaves = Material.DARK_OAK_LEAVES;
        } else {
            log = Material.OAK_LOG;
            leaves = Material.OAK_LEAVES;
        }

        for (int trunk = 0; trunk < height; trunk++) {
            plan.put(x, y + trunk, z, log, Rule.SURFACE_DECORATION);
        }
        int canopyStart = y + Math.max(2, height - 3);
        int canopyTop = y + height + (infected ? 1 : 2);
        for (int cy = canopyStart; cy <= canopyTop; cy++) {
            int layer = cy - canopyStart;
            int radius = infected ? Math.max(1, 2 - layer / 2) : (layer == 0 ? 3 : 2);
            for (int ox = -radius; ox <= radius; ox++) {
                for (int oz = -radius; oz <= radius; oz++) {
                    if (ox * ox + oz * oz > radius * radius + random.nextInt(3)) continue;
                    if (infected && random.nextDouble() < 0.42D) continue;
                    plan.put(x + ox, cy, z + oz, leaves, Rule.AIR_OR_PLANT);
                }
            }
        }
        if (infected) {
            plan.put(x, y - 1, z, Material.MOSS_BLOCK, Rule.NATURAL);
            if (random.nextBoolean()) {
                planCrystal(plan, x + (random.nextBoolean() ? 2 : -2), y,
                        z + (random.nextBoolean() ? 2 : -2), 2 + random.nextInt(3));
            }
        }
    }

    private void planUndergrowth(World world, Plan plan, Anchors anchors, int x, int z,
                                 boolean infected, Random random) {
        if (!safeNaturalSurface(world, anchors, x, z, 2)) return;
        int y = surfaceY(world, x, z) + 1;
        Material material;
        if (infected) {
            material = switch (random.nextInt(5)) {
                case 0 -> Material.DEAD_BUSH;
                case 1 -> Material.MOSS_CARPET;
                case 2 -> Material.BROWN_MUSHROOM;
                case 3 -> Material.FERN;
                default -> Material.AZALEA;
            };
        } else {
            material = switch (random.nextInt(7)) {
                case 0 -> Material.FERN;
                case 1 -> Material.FERN;
                case 2 -> Material.AZALEA;
                case 3 -> Material.FLOWERING_AZALEA;
                case 4 -> Material.DANDELION;
                case 5 -> Material.POPPY;
                default -> Material.SHORT_GRASS;
            };
        }
        plan.put(x, y, z, material, Rule.AIR_OR_PLANT);
    }

    private void planFallenLog(World world, Plan plan, Anchors anchors,
                               int x, int z, Random random) {
        if (!safeNaturalSurface(world, anchors, x, z, 4)) return;
        int dx = random.nextBoolean() ? 1 : 0;
        int dz = dx == 0 ? 1 : 0;
        int length = 4 + random.nextInt(5);
        for (int i = 0; i < length; i++) {
            int bx = x + dx * i;
            int bz = z + dz * i;
            int y = surfaceY(world, bx, bz) + 1;
            plan.put(bx, y, bz, Material.STRIPPED_OAK_LOG, Rule.AIR_OR_PLANT);
        }
    }

    private void planForestBoulder(World world, Plan plan, Anchors anchors,
                                   int x, int z, boolean infected, Random random) {
        if (!safeNaturalSurface(world, anchors, x, z, 3)) return;
        int y = surfaceY(world, x, z) + 1;
        int radius = 2 + random.nextInt(2);
        for (int ox = -radius; ox <= radius; ox++) {
            for (int oy = 0; oy <= radius; oy++) {
                for (int oz = -radius; oz <= radius; oz++) {
                    if (ox * ox + oz * oz + oy * oy > radius * radius + 1) continue;
                    Material material = infected && Math.floorMod(ox + oy + oz, 5) == 0
                            ? Material.MOSS_BLOCK
                            : (random.nextBoolean() ? Material.MOSSY_COBBLESTONE : Material.ANDESITE);
                    plan.put(x + ox, y + oy, z + oz, material, Rule.NATURAL_OR_AIR);
                }
            }
        }
    }

    private void planForestWaypoint(World world, Plan plan, Anchors anchors,
                                    Anchor center, boolean infected) {
        int x = center.x();
        int z = center.z();
        if (!safeNaturalSurface(world, anchors, x, z, 5)) return;
        int y = surfaceY(world, x, z) + 1;
        Material base = infected ? Material.MOSSY_STONE_BRICKS : Material.COBBLESTONE;
        for (int ox = -3; ox <= 3; ox++) {
            for (int oz = -3; oz <= 3; oz++) {
                if (ox * ox + oz * oz > 10) continue;
                plan.put(x + ox, y - 1, z + oz, base, Rule.NATURAL);
            }
        }
        for (int h = 0; h <= 5; h++) {
            plan.put(x, y + h, z, infected ? Material.DARK_OAK_LOG : Material.SPRUCE_LOG,
                    Rule.AIR_OR_PLANT);
        }
        plan.put(x, y + 6, z, infected ? Material.SOUL_LANTERN : Material.LANTERN,
                Rule.AIR_OR_PLANT);
        plan.put(x + 2, y, z, Material.BARREL, Rule.AIR_OR_PLANT);
        plan.put(x - 2, y, z, Material.CAMPFIRE, Rule.AIR_OR_PLANT);
    }

    private void planCamps(World world, Plan plan, Anchors anchors, MineSite mine) {
        List<Anchor> sites = findDecorationSites(world, anchors, 4, 120, 340, 24, false);
        for (int index = 0; index < sites.size(); index++) {
            planCamp(world, plan, anchors, sites.get(index), index % 3,
                    148350700L + index * 8191L);
        }
        if (mine != null) planMineSurfaceCamp(world, plan, anchors, mine);
    }

    private void planCamp(World world, Plan plan, Anchors anchors, Anchor center,
                          int type, long seed) {
        Random random = new Random(seed);
        int baseY = medianSurfaceY(world, center.x(), center.z(), 11, 3);
        Material cloth = type == 0 ? Material.WHITE_WOOL
                : type == 1 ? Material.GRAY_WOOL : Material.GREEN_WOOL;
        Material frame = type == 2 ? Material.DARK_OAK_LOG : Material.SPRUCE_LOG;

        clearOldCampVolume(world, plan, center.x(), baseY, center.z(), 18, 11);
        planCampTerrace(world, plan, center.x(), baseY, center.z(), 15, 12);

        for (int tent = -1; tent <= 1; tent += 2) {
            int tx = center.x() + tent * 8;
            int tz = center.z() + (tent == -1 ? -3 : 4);
            int tentBase = baseY + 1;
            for (int length = -4; length <= 4; length++) {
                for (int width = -3; width <= 3; width++) {
                    int roofHeight = 4 - Math.abs(width);
                    if (roofHeight >= 1) {
                        plan.put(tx + width, tentBase + roofHeight, tz + length,
                                cloth, Rule.CAMP_REPAIR);
                    }
                    if (Math.abs(width) == 3 && (length == -4 || length == 4)) {
                        for (int h = 0; h <= 4; h++) {
                            plan.put(tx + width, tentBase + h, tz + length,
                                    frame, Rule.CAMP_REPAIR);
                        }
                        for (int depth = 1; depth <= 6; depth++) {
                            plan.put(tx + width, tentBase - depth, tz + length,
                                    Material.COBBLESTONE, Rule.CAMP_REPAIR);
                        }
                    }
                    if (Math.abs(width) <= 2) {
                        plan.put(tx + width, baseY, tz + length,
                                Math.floorMod(length + width, 7) == 0
                                        ? Material.COARSE_DIRT : Material.PACKED_MUD,
                                Rule.CAMP_REPAIR);
                    }
                }
            }
        }

        plan.put(center.x(), baseY + 1, center.z(), Material.CAMPFIRE, Rule.CAMP_REPAIR);
        for (int[] offset : new int[][]{{-2, 0}, {2, 0}, {0, -2}, {0, 2}}) {
            plan.put(center.x() + offset[0], baseY + 1, center.z() + offset[1],
                    Material.STRIPPED_SPRUCE_LOG, Rule.CAMP_REPAIR);
        }
        for (int i = 0; i < 10; i++) {
            int x = center.x() + random.nextInt(17) - 8;
            int z = center.z() + random.nextInt(17) - 8;
            Material decoration = switch (i % 5) {
                case 0 -> Material.BARREL;
                case 1 -> Material.CHEST;
                case 2 -> Material.OAK_FENCE;
                case 3 -> Material.CRAFTING_TABLE;
                default -> Material.HAY_BLOCK;
            };
            plan.put(x, baseY + 1, z, decoration, Rule.CAMP_REPAIR);
            if (i % 3 == 1) {
                plan.put(x, baseY + 2, z, Material.LANTERN, Rule.CAMP_REPAIR);
            }
        }

        // Pequeña empalizada y acceso nivelado para integrar el campamento con el terreno.
        for (int x = -10; x <= 10; x++) {
            if (Math.abs(x) <= 2) continue;
            if (Math.floorMod(x, 3) == 0) {
                plan.put(center.x() + x, baseY + 1, center.z() - 10,
                        Material.SPRUCE_FENCE, Rule.CAMP_REPAIR);
            }
        }
        for (int step = 0; step <= 14; step++) {
            int x = center.x();
            int z = center.z() - 10 - step;
            int ground = surfaceY(world, x, z);
            int target = Math.max(Math.min(baseY, ground + 1), ground - 1);
            plan.put(x, target, z, Material.DIRT_PATH, Rule.CAMP_REPAIR);
            plan.put(x + 1, target, z, Material.COARSE_DIRT, Rule.CAMP_REPAIR);
            plan.put(x - 1, target, z, Material.COARSE_DIRT, Rule.CAMP_REPAIR);
        }

        if (type == 2) {
            for (int i = 0; i < 8; i++) {
                planCrystal(plan, center.x() + random.nextInt(19) - 9, baseY + 1,
                        center.z() + random.nextInt(19) - 9, 2 + random.nextInt(4));
            }
        }
    }

    private void clearOldCampVolume(World world, Plan plan, int cx, int baseY, int cz,
                                    int radius, int height) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (distanceSquared(x, z, cx, cz) > (long) radius * radius) continue;
                for (int y = baseY + 1; y <= baseY + height; y++) {
                    Material current = world.getBlockAt(x, y, z).getType();
                    if (isCampDecoration(current)) {
                        plan.put(x, y, z, Material.AIR, Rule.CAMP_REPAIR);
                    }
                }
            }
        }
    }

    private void planCampTerrace(World world, Plan plan, int cx, int baseY, int cz,
                                 int radiusX, int radiusZ) {
        for (int ox = -radiusX; ox <= radiusX; ox++) {
            for (int oz = -radiusZ; oz <= radiusZ; oz++) {
                double normalized = square(ox / (double) radiusX)
                        + square(oz / (double) radiusZ);
                if (normalized > 1.0D) continue;
                int x = cx + ox;
                int z = cz + oz;
                int surface = surfaceY(world, x, z);
                int target = normalized <= 0.68D
                        ? baseY
                        : (int) Math.round(baseY + (surface - baseY) * ((normalized - 0.68D) / 0.32D));
                target = Math.max(baseY - 2, Math.min(baseY + 2, target));

                if (surface > target) {
                    for (int y = target + 1; y <= Math.min(surface + 1, target + 10); y++) {
                        plan.put(x, y, z, Material.AIR, Rule.CAMP_REPAIR);
                    }
                } else if (surface < target) {
                    for (int y = surface + 1; y <= target; y++) {
                        plan.put(x, y, z,
                                normalized > 0.78D ? Material.COBBLESTONE : Material.PACKED_MUD,
                                Rule.CAMP_REPAIR);
                    }
                }
                Material top = normalized > 0.78D ? Material.MOSSY_COBBLESTONE
                        : (Math.floorMod(x * 17 + z * 31, 9) == 0
                        ? Material.COARSE_DIRT : Material.PACKED_MUD);
                plan.put(x, target, z, top, Rule.CAMP_REPAIR);
                plan.put(x, target - 1, z,
                        normalized > 0.72D ? Material.COBBLESTONE : Material.DIRT,
                        Rule.CAMP_REPAIR);

                if (normalized > 0.82D && Math.floorMod(x + z, 3) == 0) {
                    for (int depth = 2; depth <= 7; depth++) {
                        plan.put(x, target - depth, z, Material.COBBLESTONE, Rule.CAMP_REPAIR);
                    }
                }
            }
        }
    }

    private void planMineSurfaceCamp(World world, Plan plan, Anchors anchors, MineSite mine) {
        int px = -mine.dz();
        int pz = mine.dx();
        int cx = mine.entranceX() - mine.dx() * 12 + px * 16;
        int cz = mine.entranceZ() - mine.dz() * 12 + pz * 16;
        if (!safeNaturalSurface(world, anchors, cx, cz, 8)
                && !looksLikeCamp(world, cx, cz, 24)) return;
        planCamp(world, plan, anchors, surfaceAnchor(world, cx, cz), 0, 148321111L);
    }

    private void planLargeLoreMine(World world, Plan plan, Anchors anchors, MineSite mine,
                                   boolean preserveExistingGeode) {
        planMineEntrance(world, plan, mine);
        planMainTunnel(plan, mine);
        planMineWorkshop(plan, mine, 18, -15);
        planExtractionHall(plan, mine, 55);
        planMinersRest(plan, mine, 34);
        planPumpStation(plan, mine, 68, -15);
        planQuarantineLab(plan, mine, 79, 16);
        planCollapsedGallery(plan, mine, 88);
        planOreDepot(plan, mine, 108, 14);
        planBranchTunnel(plan, mine, 28, 1, 38, false);
        planBranchTunnel(plan, mine, 47, -1, 46, false);
        planBranchTunnel(plan, mine, 70, 1, 52, true);
        planBranchTunnel(plan, mine, 94, -1, 38, true);
        planBranchTunnel(plan, mine, 112, 1, 30, true);
        planMineConnections(plan, mine);
        planFinalGeodeApproach(plan, mine, preserveExistingGeode);
        if (!preserveExistingGeode) {
            planGeode(plan, mine);
        } else {
            planPreservedGeodeAccess(plan, mine);
        }
        planMineLore(plan, mine);
    }

    private void planMineEntrance(World world, Plan plan, MineSite mine) {
        int dx = mine.dx();
        int dz = mine.dz();
        int px = -dz;
        int pz = dx;
        int baseY = mine.entranceY() - 1;

        for (int forward = -7; forward <= 9; forward++) {
            for (int side = -7; side <= 7; side++) {
                int x = mine.entranceX() + dx * forward + px * side;
                int z = mine.entranceZ() + dz * forward + pz * side;
                int surface = surfaceY(world, x, z);
                if (Math.abs(surface - baseY) <= 4) {
                    plan.put(x, surface, z,
                            Math.floorMod(forward + side, 7) == 0
                                    ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE,
                            Rule.NATURAL);
                }
            }
        }

        for (int side = -5; side <= 5; side++) {
            int x = mine.entranceX() + px * side;
            int z = mine.entranceZ() + pz * side;
            for (int h = 0; h <= 8; h++) {
                boolean frame = Math.abs(side) >= 4 || h >= 7;
                if (frame) {
                    plan.put(x, baseY + h, z,
                            h >= 7 ? Material.DEEPSLATE_BRICKS : Material.POLISHED_DEEPSLATE,
                            Rule.MINE);
                } else if (h >= 1) {
                    plan.put(x, baseY + h, z, Material.AIR, Rule.MINE);
                }
            }
        }

        for (int supportSide : new int[]{-5, 5}) {
            int x = mine.entranceX() - dx * 2 + px * supportSide;
            int z = mine.entranceZ() - dz * 2 + pz * supportSide;
            for (int h = 0; h <= 9; h++) {
                plan.put(x, baseY + h, z, Material.SPRUCE_LOG, Rule.MINE);
            }
        }
        for (int side = -5; side <= 5; side++) {
            plan.put(mine.entranceX() - dx * 2 + px * side, baseY + 9,
                    mine.entranceZ() - dz * 2 + pz * side,
                    Material.SPRUCE_LOG, Rule.MINE);
        }
        plan.put(mine.entranceX() - dx * 4, baseY + 1,
                mine.entranceZ() - dz * 4, Material.OAK_SIGN, Rule.MINE);
        plan.postActions.add(worldRef -> setSign(worldRef,
                mine.entranceX() - dx * 4, baseY + 1, mine.entranceZ() - dz * 4,
                "Mina Umbraverde", "Acceso clausurado", "Veta 7", "No bajar solo"));
    }

    private void planMainTunnel(Plan plan, MineSite mine) {
        int px = -mine.dz();
        int pz = mine.dx();
        for (int t = 0; t <= mine.tunnelLength(); t++) {
            int x = mine.entranceX() + mine.dx() * t;
            int z = mine.entranceZ() + mine.dz() * t;
            int floor = mine.entranceY() - 2 - t / 3;
            for (int side = -3; side <= 3; side++) {
                for (int height = 1; height <= 6; height++) {
                    int bx = x + px * side;
                    int bz = z + pz * side;
                    boolean rounded = Math.abs(side) == 3 && height >= 5;
                    if (!rounded) plan.put(bx, floor + height, bz, Material.AIR, Rule.MINE);
                }
                Material floorMaterial = Math.floorMod(t + side, 11) == 0
                        ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_ANDESITE;
                plan.put(x + px * side, floor, z + pz * side, floorMaterial, Rule.MINE);
                plan.put(x + px * side, floor - 1, z + pz * side,
                        Material.COBBLED_DEEPSLATE, Rule.MINE);
            }
            plan.put(x, floor + 1, z, Material.RAIL, Rule.MINE);

            if (t % 7 == 0) {
                for (int side : new int[]{-3, 3}) {
                    for (int height = 1; height <= 5; height++) {
                        plan.put(x + px * side, floor + height, z + pz * side,
                                Material.SPRUCE_LOG, Rule.MINE);
                    }
                }
                for (int side = -3; side <= 3; side++) {
                    plan.put(x + px * side, floor + 6, z + pz * side,
                            Material.SPRUCE_LOG, Rule.MINE);
                }
                if (t % 14 == 0) {
                    plan.put(x + px * 2, floor + 6, z + pz * 2,
                            Material.CHAIN, Rule.MINE);
                    plan.put(x + px * 2, floor + 5, z + pz * 2,
                            Material.LANTERN, Rule.MINE);
                }
            }
            if (t % 19 == 0 && t > 8) {
                plan.put(x + px * 2, floor + 1, z + pz * 2,
                        Material.BARREL, Rule.MINE);
            }
        }
    }

    private void planBranchTunnel(Plan plan, MineSite mine, int start,
                                  int sideDirection, int length, boolean infected) {
        int branchDx = -mine.dz() * sideDirection;
        int branchDz = mine.dx() * sideDirection;
        int originX = mine.entranceX() + mine.dx() * start;
        int originZ = mine.entranceZ() + mine.dz() * start;
        int floor = mine.entranceY() - 2 - start / 3;
        int px = -branchDz;
        int pz = branchDx;
        for (int t = 0; t <= length; t++) {
            int x = originX + branchDx * t;
            int z = originZ + branchDz * t;
            int localFloor = floor - t / 12;
            for (int side = -2; side <= 2; side++) {
                plan.put(x + px * side, localFloor, z + pz * side,
                        infected && Math.floorMod(t + side, 7) == 0
                                ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE,
                        Rule.MINE);
                for (int h = 1; h <= 4; h++) {
                    plan.put(x + px * side, localFloor + h, z + pz * side,
                            Material.AIR, Rule.MINE);
                }
            }
            if (t % 8 == 0) {
                for (int side : new int[]{-2, 2}) {
                    for (int h = 1; h <= 4; h++) {
                        plan.put(x + px * side, localFloor + h, z + pz * side,
                                Material.DARK_OAK_LOG, Rule.MINE);
                    }
                }
                for (int side = -2; side <= 2; side++) {
                    plan.put(x + px * side, localFloor + 5, z + pz * side,
                            Material.DARK_OAK_LOG, Rule.MINE);
                }
            }
            if (infected && t % 11 == 5) {
                planCrystal(plan, x + px, localFloor + 1, z + pz, 2 + t % 3);
            }
        }
        int endX = originX + branchDx * length;
        int endZ = originZ + branchDz * length;
        int endFloor = floor - length / 12;
        for (int ox = -3; ox <= 3; ox++) {
            for (int oz = -3; oz <= 3; oz++) {
                for (int h = 1; h <= 5; h++) {
                    if (Math.abs(ox) + Math.abs(oz) + h % 2 < 7) {
                        plan.put(endX + ox, endFloor + h, endZ + oz,
                                Material.AIR, Rule.MINE);
                    }
                }
            }
        }
        planOreVein(plan, endX, endFloor + 2, endZ,
                infected ? Material.EMERALD_ORE : Material.IRON_ORE, 14);
    }

    private void planExtractionHall(Plan plan, MineSite mine, int at) {
        int cx = mine.entranceX() + mine.dx() * at;
        int cz = mine.entranceZ() + mine.dz() * at;
        int floor = mine.entranceY() - 2 - at / 3;
        int px = -mine.dz();
        int pz = mine.dx();
        for (int forward = -12; forward <= 13; forward++) {
            for (int side = -10; side <= 10; side++) {
                int x = cx + mine.dx() * forward + px * side;
                int z = cz + mine.dz() * forward + pz * side;
                plan.put(x, floor, z,
                        Math.floorMod(forward + side, 13) == 0
                                ? Material.MOSSY_STONE_BRICKS : Material.DEEPSLATE_TILES,
                        Rule.MINE);
                for (int h = 1; h <= 9; h++) {
                    boolean edge = Math.abs(side) == 10 || Math.abs(forward) >= 12;
                    if (edge) {
                        plan.put(x, floor + h, z,
                                h <= 2 ? Material.DEEPSLATE_BRICKS : Material.STONE_BRICKS,
                                Rule.MINE);
                    } else {
                        plan.put(x, floor + h, z, Material.AIR, Rule.MINE);
                    }
                }
                plan.put(x, floor + 10, z, Material.DEEPSLATE_BRICKS, Rule.MINE);
            }
        }
        for (int forward = -8; forward <= 8; forward += 8) {
            for (int side : new int[]{-7, 7}) {
                int x = cx + mine.dx() * forward + px * side;
                int z = cz + mine.dz() * forward + pz * side;
                for (int h = 1; h <= 8; h++) {
                    plan.put(x, floor + h, z, Material.SPRUCE_LOG, Rule.MINE);
                }
                plan.put(x, floor + 7, z, Material.LANTERN, Rule.MINE);
            }
        }
        for (int i = -7; i <= 7; i += 3) {
            plan.put(cx + px * i, floor + 1, cz + pz * i,
                    i % 2 == 0 ? Material.BARREL : Material.CHEST, Rule.MINE);
        }
    }

    private void planMinersRest(Plan plan, MineSite mine, int at) {
        int px = -mine.dz();
        int pz = mine.dx();
        int cx = mine.entranceX() + mine.dx() * at + px * 10;
        int cz = mine.entranceZ() + mine.dz() * at + pz * 10;
        int floor = mine.entranceY() - 2 - at / 3;
        planRectangularRoom(plan, cx, floor, cz, mine.dx(), mine.dz(), 8, 7, 6,
                Material.STONE_BRICKS, Material.SPRUCE_PLANKS);
        for (int i = -5; i <= 5; i += 5) {
            plan.put(cx + px * i, floor + 1, cz + pz * i, Material.RED_BED, Rule.MINE);
            plan.put(cx + mine.dx() * 3 + px * i, floor + 1,
                    cz + mine.dz() * 3 + pz * i, Material.BARREL, Rule.MINE);
        }
        plan.put(cx, floor + 1, cz, Material.CAMPFIRE, Rule.MINE);
    }

    private void planMineWorkshop(Plan plan, MineSite mine, int at, int sideOffset) {
        int px = -mine.dz();
        int pz = mine.dx();
        int cx = mine.entranceX() + mine.dx() * at + px * sideOffset;
        int cz = mine.entranceZ() + mine.dz() * at + pz * sideOffset;
        int floor = mine.entranceY() - 2 - at / 3;
        planRectangularRoom(plan, cx, floor, cz, mine.dx(), mine.dz(), 9, 8, 7,
                Material.STONE_BRICKS, Material.POLISHED_ANDESITE);
        int[][] stations = {{-5, -5}, {-5, 0}, {-5, 5}, {5, -5}, {5, 0}, {5, 5}};
        Material[] blocks = {
                Material.CRAFTING_TABLE, Material.SMITHING_TABLE, Material.STONECUTTER,
                Material.BLAST_FURNACE, Material.ANVIL, Material.GRINDSTONE
        };
        for (int i = 0; i < stations.length; i++) {
            int forward = stations[i][0];
            int side = stations[i][1];
            plan.put(cx + mine.dx() * forward + px * side, floor + 1,
                    cz + mine.dz() * forward + pz * side, blocks[i], Rule.MINE);
        }
        for (int side = -5; side <= 5; side += 5) {
            plan.put(cx + px * side, floor + 1, cz + pz * side, Material.BARREL, Rule.MINE);
            plan.put(cx + px * side, floor + 6, cz + pz * side, Material.LANTERN, Rule.MINE);
        }
    }

    private void planPumpStation(Plan plan, MineSite mine, int at, int sideOffset) {
        int px = -mine.dz();
        int pz = mine.dx();
        int cx = mine.entranceX() + mine.dx() * at + px * sideOffset;
        int cz = mine.entranceZ() + mine.dz() * at + pz * sideOffset;
        int floor = mine.entranceY() - 2 - at / 3;
        planRectangularRoom(plan, cx, floor, cz, mine.dx(), mine.dz(), 8, 8, 8,
                Material.DEEPSLATE_BRICKS, Material.COBBLED_DEEPSLATE);
        for (int forward = -5; forward <= 5; forward += 5) {
            for (int side = -4; side <= 4; side += 4) {
                int x = cx + mine.dx() * forward + px * side;
                int z = cz + mine.dz() * forward + pz * side;
                plan.put(x, floor + 1, z, Material.CAULDRON, Rule.MINE);
                plan.put(x, floor + 2, z, Material.CHAIN, Rule.MINE);
            }
        }
        for (int forward = -6; forward <= 6; forward++) {
            int x = cx + mine.dx() * forward;
            int z = cz + mine.dz() * forward;
            plan.put(x, floor + 1, z, Material.WATER, Rule.MINE);
            plan.put(x + px, floor + 1, z + pz, Material.IRON_BARS, Rule.MINE);
            plan.put(x - px, floor + 1, z - pz, Material.IRON_BARS, Rule.MINE);
        }
        plan.put(cx, floor + 6, cz, Material.LANTERN, Rule.MINE);
    }

    private void planQuarantineLab(Plan plan, MineSite mine, int at, int sideOffset) {
        int px = -mine.dz();
        int pz = mine.dx();
        int cx = mine.entranceX() + mine.dx() * at + px * sideOffset;
        int cz = mine.entranceZ() + mine.dz() * at + pz * sideOffset;
        int floor = mine.entranceY() - 2 - at / 3;
        planRectangularRoom(plan, cx, floor, cz, mine.dx(), mine.dz(), 10, 8, 7,
                Material.POLISHED_DEEPSLATE, Material.DEEPSLATE_TILES);
        for (int side = -5; side <= 5; side += 5) {
            for (int forward = -6; forward <= 6; forward += 6) {
                int x = cx + mine.dx() * forward + px * side;
                int z = cz + mine.dz() * forward + pz * side;
                plan.put(x, floor + 1, z, Material.GLASS, Rule.MINE);
                plan.put(x, floor + 2, z, Material.GLASS, Rule.MINE);
                plan.put(x, floor + 3, z, Material.GLASS, Rule.MINE);
                plan.put(x + px, floor + 1, z + pz, Material.MOSS_BLOCK, Rule.MINE);
                plan.put(x - px, floor + 1, z - pz, Material.EMERALD_BLOCK, Rule.MINE);
            }
        }
        plan.put(cx, floor + 1, cz, Material.LECTERN, Rule.MINE);
        plan.postActions.add(world -> setLecternBook(world, new Anchor(cx, floor + 1, cz),
                "Protocolo de cuarentena", "Dra. S. Vale", List.of(
                        "La Veta 7 no es una veta mineral. Es tejido cristalino que reacciona al calor, al sueño y a la sangre.",
                        "Los síntomas comienzan con musgo en la ropa. Después aparecen filamentos verdes bajo la piel.",
                        "Nadie debe abandonar la mina con muestras. La orden llegó demasiado tarde.")));
        plan.put(cx, floor + 6, cz, Material.SOUL_LANTERN, Rule.MINE);
    }

    private void planOreDepot(Plan plan, MineSite mine, int at, int sideOffset) {
        int px = -mine.dz();
        int pz = mine.dx();
        int cx = mine.entranceX() + mine.dx() * at + px * sideOffset;
        int cz = mine.entranceZ() + mine.dz() * at + pz * sideOffset;
        int floor = mine.entranceY() - 2 - at / 3;
        planRectangularRoom(plan, cx, floor, cz, mine.dx(), mine.dz(), 9, 7, 6,
                Material.DEEPSLATE_BRICKS, Material.POLISHED_DEEPSLATE);
        for (int forward = -6; forward <= 6; forward += 3) {
            for (int side : new int[]{-5, 5}) {
                int x = cx + mine.dx() * forward + px * side;
                int z = cz + mine.dz() * forward + pz * side;
                plan.put(x, floor + 1, z,
                        Math.floorMod(forward + side, 2) == 0 ? Material.BARREL : Material.CHEST,
                        Rule.MINE);
            }
        }
        planOreVein(plan, cx + mine.dx() * 6, floor + 2, cz + mine.dz() * 6,
                Material.EMERALD_ORE, 24);
        planOreVein(plan, cx - mine.dx() * 6, floor + 2, cz - mine.dz() * 6,
                Material.DEEPSLATE_IRON_ORE, 20);
        plan.put(cx, floor + 5, cz, Material.LANTERN, Rule.MINE);
    }

    private void planCollapsedGallery(Plan plan, MineSite mine, int at) {
        int px = -mine.dz();
        int pz = mine.dx();
        int cx = mine.entranceX() + mine.dx() * at - px * 11;
        int cz = mine.entranceZ() + mine.dz() * at - pz * 11;
        int floor = mine.entranceY() - 2 - at / 3;
        planRectangularRoom(plan, cx, floor, cz, mine.dx(), mine.dz(), 10, 9, 7,
                Material.DEEPSLATE_BRICKS, Material.POLISHED_DEEPSLATE);
        Random random = new Random(148322222L);
        for (int i = 0; i < 65; i++) {
            int x = cx + random.nextInt(19) - 9;
            int z = cz + random.nextInt(17) - 8;
            int y = floor + 1 + random.nextInt(5);
            plan.put(x, y, z, random.nextBoolean() ? Material.GRAVEL : Material.COBBLESTONE,
                    Rule.MINE);
        }
        for (int i = 0; i < 9; i++) {
            planCrystal(plan, cx + random.nextInt(15) - 7, floor + 1,
                    cz + random.nextInt(13) - 6, 2 + random.nextInt(4));
        }
    }

    private void planMineConnections(Plan plan, MineSite mine) {
        planSideConnector(plan, mine, 18, -15, 2, 6);
        planSideConnector(plan, mine, 34, 10, 2, 6);
        planSideConnector(plan, mine, 68, -15, 2, 6);
        planSideConnector(plan, mine, 79, 16, 2, 6);
        planSideConnector(plan, mine, 88, -11, 2, 6);
        planSideConnector(plan, mine, 108, 14, 2, 6);

        // El gran salón cruza el eje principal; esta pasada final abre las dos paredes
        // que antes podían volver a cerrarlo al construir la sala.
        planMainAxisOpening(plan, mine, 55, 15, 4, 8);

        // El derrumbe queda como ambientación en los laterales, pero conserva un pasillo
        // central completamente transitable hasta la veta infectada.
        int px = -mine.dz();
        int pz = mine.dx();
        int centerX = mine.entranceX() + mine.dx() * 88 - px * 11;
        int centerZ = mine.entranceZ() + mine.dz() * 88 - pz * 11;
        int floor = mine.entranceY() - 2 - 88 / 3;
        for (int forward = -9; forward <= 9; forward++) {
            for (int side = -2; side <= 2; side++) {
                int x = centerX + mine.dx() * forward + px * side;
                int z = centerZ + mine.dz() * forward + pz * side;
                plan.put(x, floor, z, Material.COBBLED_DEEPSLATE, Rule.MINE);
                plan.put(x, floor - 1, z, Material.DEEPSLATE, Rule.MINE);
                for (int h = 1; h <= 6; h++) {
                    plan.put(x, floor + h, z, Material.AIR, Rule.MINE);
                }
            }
        }
    }

    private void planSideConnector(Plan plan, MineSite mine, int at, int targetSide,
                                   int halfWidth, int height) {
        int px = -mine.dz();
        int pz = mine.dx();
        int direction = Integer.signum(targetSide);
        int distance = Math.abs(targetSide);
        int originX = mine.entranceX() + mine.dx() * at;
        int originZ = mine.entranceZ() + mine.dz() * at;
        int floor = mine.entranceY() - 2 - at / 3;
        for (int step = 0; step <= distance; step++) {
            int centerX = originX + px * direction * step;
            int centerZ = originZ + pz * direction * step;
            for (int width = -halfWidth; width <= halfWidth; width++) {
                int x = centerX + mine.dx() * width;
                int z = centerZ + mine.dz() * width;
                plan.put(x, floor, z,
                        Math.floorMod(step + width, 9) == 0
                                ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_ANDESITE,
                        Rule.MINE);
                plan.put(x, floor - 1, z, Material.COBBLED_DEEPSLATE, Rule.MINE);
                for (int h = 1; h <= height; h++) {
                    plan.put(x, floor + h, z, Material.AIR, Rule.MINE);
                }
            }
            if (step > 0 && step % 6 == 0) {
                for (int width : new int[]{-halfWidth, halfWidth}) {
                    int x = centerX + mine.dx() * width;
                    int z = centerZ + mine.dz() * width;
                    for (int h = 1; h <= height; h++) {
                        plan.put(x, floor + h, z, Material.SPRUCE_LOG, Rule.MINE);
                    }
                }
                plan.put(centerX, floor + height - 1, centerZ, Material.LANTERN, Rule.MINE);
            }
        }
    }

    private void planMainAxisOpening(Plan plan, MineSite mine, int at, int halfLength,
                                     int halfWidth, int height) {
        int px = -mine.dz();
        int pz = mine.dx();
        int centerX = mine.entranceX() + mine.dx() * at;
        int centerZ = mine.entranceZ() + mine.dz() * at;
        int floor = mine.entranceY() - 2 - at / 3;
        for (int forward = -halfLength; forward <= halfLength; forward++) {
            for (int side = -halfWidth; side <= halfWidth; side++) {
                int x = centerX + mine.dx() * forward + px * side;
                int z = centerZ + mine.dz() * forward + pz * side;
                plan.put(x, floor, z, Material.DEEPSLATE_TILES, Rule.MINE);
                plan.put(x, floor - 1, z, Material.COBBLED_DEEPSLATE, Rule.MINE);
                for (int h = 1; h <= height; h++) {
                    plan.put(x, floor + h, z, Material.AIR, Rule.MINE);
                }
            }
            plan.put(centerX + mine.dx() * forward, floor + 1,
                    centerZ + mine.dz() * forward, Material.RAIL, Rule.MINE);
        }
    }

    private void planFinalGeodeApproach(Plan plan, MineSite mine, boolean preserveExistingGeode) {
        int projection = Math.abs((mine.geodeX() - mine.entranceX()) * mine.dx()
                + (mine.geodeZ() - mine.entranceZ()) * mine.dz());
        int start = Math.max(96, Math.min(mine.tunnelLength(), projection - 30));
        int end = Math.max(start + 1, projection - 18);
        int startFloor = mine.entranceY() - 2 - start / 3;
        int targetFloor = mine.bossY();
        int px = -mine.dz();
        int pz = mine.dx();
        for (int t = start; t <= end; t++) {
            double progress = (t - start) / (double) Math.max(1, end - start);
            int floor = (int) Math.round(startFloor + (targetFloor - startFloor) * progress);
            int x = mine.entranceX() + mine.dx() * t;
            int z = mine.entranceZ() + mine.dz() * t;
            for (int side = -4; side <= 4; side++) {
                int bx = x + px * side;
                int bz = z + pz * side;
                plan.put(bx, floor, bz,
                        Math.floorMod(t + side, 11) == 0
                                ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_DEEPSLATE,
                        Rule.MINE);
                plan.put(bx, floor - 1, bz, Material.DEEPSLATE_BRICKS, Rule.MINE);
                plan.put(bx, floor - 2, bz, Material.COBBLED_DEEPSLATE, Rule.MINE);
                for (int h = 1; h <= 8; h++) {
                    plan.put(bx, floor + h, bz, Material.AIR, Rule.MINE);
                }
            }
            if ((t - start) % 7 == 0) {
                plan.put(x + px * 3, floor + 6, z + pz * 3, Material.SOUL_LANTERN, Rule.MINE);
            }
        }

        // Pasarela final desde el umbral hasta el corazón de la geoda.
        int gateDistance = Math.max(0, projection - 19);
        int walkwayEnd = preserveExistingGeode ? Math.max(gateDistance, projection - 7) : projection;
        for (int t = gateDistance; t <= walkwayEnd; t++) {
            int x = mine.entranceX() + mine.dx() * t;
            int z = mine.entranceZ() + mine.dz() * t;
            for (int side = -5; side <= 5; side++) {
                int bx = x + px * side;
                int bz = z + pz * side;
                plan.put(bx, targetFloor, bz,
                        Math.abs(side) == 5 ? Material.DEEPSLATE_BRICKS : Material.POLISHED_DEEPSLATE,
                        Rule.MINE);
                plan.put(bx, targetFloor - 1, bz, Material.DEEPSLATE_BRICKS, Rule.MINE);
                for (int h = 1; h <= 9; h++) {
                    plan.put(bx, targetFloor + h, bz, Material.AIR, Rule.MINE);
                }
                if (Math.abs(side) == 5 && t % 4 == 0) {
                    plan.put(bx, targetFloor + 1, bz, Material.IRON_BARS, Rule.MINE);
                }
            }
        }
    }

    private void planPreservedGeodeAccess(Plan plan, MineSite mine) {
        int floor = mine.bossY();
        for (int ox = -15; ox <= 15; ox++) {
            for (int oz = -15; oz <= 15; oz++) {
                int distanceSq = ox * ox + oz * oz;
                if (distanceSq < 8 * 8 || distanceSq > 15 * 15) continue;
                int x = mine.bossX() + ox;
                int z = mine.bossZ() + oz;
                plan.put(x, floor, z,
                        Math.floorMod(ox * 7 + oz * 11, 17) == 0
                                ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_DEEPSLATE,
                        Rule.MINE);
                plan.put(x, floor - 1, z, Material.DEEPSLATE_BRICKS, Rule.MINE);
                for (int h = 1; h <= 6; h++) {
                    plan.put(x, floor + h, z, Material.AIR, Rule.MINE);
                }
            }
        }
    }

    private void planRectangularRoom(Plan plan, int cx, int floor, int cz,
                                     int dx, int dz, int halfForward, int halfSide,
                                     int height, Material wall, Material floorMaterial) {
        int px = -dz;
        int pz = dx;
        for (int forward = -halfForward; forward <= halfForward; forward++) {
            for (int side = -halfSide; side <= halfSide; side++) {
                int x = cx + dx * forward + px * side;
                int z = cz + dz * forward + pz * side;
                plan.put(x, floor, z, floorMaterial, Rule.MINE);
                plan.put(x, floor - 1, z, Material.COBBLED_DEEPSLATE, Rule.MINE);
                for (int h = 1; h <= height; h++) {
                    boolean edge = Math.abs(forward) == halfForward || Math.abs(side) == halfSide;
                    plan.put(x, floor + h, z, edge ? wall : Material.AIR, Rule.MINE);
                }
                plan.put(x, floor + height + 1, z, wall, Rule.MINE);
                if (Math.floorMod(forward, 5) == 0 && Math.floorMod(side, 5) == 0) {
                    for (int depth = 2; depth <= 8; depth++) {
                        plan.put(x, floor - depth, z, Material.DEEPSLATE_BRICKS, Rule.MINE);
                    }
                }
            }
        }
    }

    private void planGeode(Plan plan, MineSite mine) {
        int rx = 24;
        int ry = 17;
        int rz = 24;
        for (int ox = -rx - 3; ox <= rx + 3; ox++) {
            for (int oy = -ry - 3; oy <= ry + 3; oy++) {
                for (int oz = -rz - 3; oz <= rz + 3; oz++) {
                    double normalized = square(ox / (double) rx)
                            + square(oy / (double) ry)
                            + square(oz / (double) rz);
                    int x = mine.geodeX() + ox;
                    int y = mine.geodeY() + oy;
                    int z = mine.geodeZ() + oz;
                    if (normalized <= 0.72D) {
                        plan.put(x, y, z, Material.AIR, Rule.MINE);
                    } else if (normalized <= 1.00D) {
                        Material inner = Math.floorMod(ox * 31 + oy * 17 + oz * 13, 23) == 0
                                ? Material.BUDDING_AMETHYST : Material.AMETHYST_BLOCK;
                        plan.put(x, y, z, inner, Rule.MINE);
                    } else if (normalized <= 1.18D) {
                        plan.put(x, y, z, Material.CALCITE, Rule.MINE);
                    } else if (normalized <= 1.38D) {
                        plan.put(x, y, z, Material.SMOOTH_BASALT, Rule.MINE);
                    }
                }
            }
        }

        int floorY = mine.bossY();
        for (int ox = -15; ox <= 15; ox++) {
            for (int oz = -15; oz <= 15; oz++) {
                if (ox * ox + oz * oz > 15 * 15) continue;
                Material floor = Math.floorMod(ox * 7 + oz * 11, 17) == 0
                        ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_DEEPSLATE;
                plan.put(mine.bossX() + ox, floorY, mine.bossZ() + oz, floor, Rule.MINE);
                for (int h = 1; h <= 7; h++) {
                    plan.put(mine.bossX() + ox, floorY + h, mine.bossZ() + oz,
                            Material.AIR, Rule.MINE);
                }
            }
        }

        for (int[] pillar : new int[][]{{-11, -11}, {-11, 11}, {11, -11}, {11, 11}}) {
            for (int h = 1; h <= 8; h++) {
                plan.put(mine.bossX() + pillar[0], floorY + h,
                        mine.bossZ() + pillar[1],
                        h % 3 == 0 ? Material.EMERALD_BLOCK : Material.DEEPSLATE_BRICKS,
                        Rule.MINE);
            }
            plan.put(mine.bossX() + pillar[0], floorY + 9,
                    mine.bossZ() + pillar[1], Material.SOUL_LANTERN, Rule.MINE);
        }

        for (int ox = -3; ox <= 3; ox++) {
            for (int oz = -3; oz <= 3; oz++) {
                int height = Math.max(1, 4 - Math.max(Math.abs(ox), Math.abs(oz)));
                for (int h = 1; h <= height; h++) {
                    Material material = h == height && Math.abs(ox) <= 1 && Math.abs(oz) <= 1
                            ? Material.EMERALD_BLOCK
                            : (Math.floorMod(ox + oz + h, 3) == 0
                            ? Material.MOSS_BLOCK : Material.AMETHYST_BLOCK);
                    plan.put(mine.bossX() + ox, floorY + h,
                            mine.bossZ() + oz, material, Rule.MINE);
                }
            }
        }

        Random random = new Random(148323333L);
        for (int i = 0; i < 42; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int x = mine.geodeX() + (int) Math.round(Math.cos(angle) * (17 + random.nextInt(5)));
            int z = mine.geodeZ() + (int) Math.round(Math.sin(angle) * (17 + random.nextInt(5)));
            int y = mine.geodeY() - 8 + random.nextInt(17);
            plan.put(x, y, z, random.nextBoolean()
                    ? Material.LARGE_AMETHYST_BUD : Material.AMETHYST_CLUSTER, Rule.MINE);
        }

        // Umbral del futuro boss: queda abierto visualmente, pero sin spawner ni lógica aún.
        int gateX = mine.geodeX() - mine.dx() * 19;
        int gateZ = mine.geodeZ() - mine.dz() * 19;
        int px = -mine.dz();
        int pz = mine.dx();
        for (int side = -6; side <= 6; side++) {
            for (int h = 0; h <= 8; h++) {
                boolean frame = Math.abs(side) >= 5 || h >= 7;
                int x = gateX + px * side;
                int z = gateZ + pz * side;
                if (frame) {
                    plan.put(x, floorY + h, z,
                            Math.floorMod(side + h, 5) == 0
                                    ? Material.EMERALD_BLOCK : Material.DEEPSLATE_BRICKS,
                            Rule.MINE);
                } else {
                    plan.put(x, floorY + h, z, Material.AIR, Rule.MINE);
                }
            }
        }
    }

    private void planMineLore(Plan plan, MineSite mine) {
        addDiary(plan, tunnelPoint(mine, 5, 5),
                "Registro del capataz", "Maese Orin",
                List.of(
                        "Día 3\n\nAbrimos la Veta 7. La piedra es más fría que el resto de la montaña y las brújulas se desvían hacia el fondo.",
                        "Los hombres encontraron vetas verdes bajo el musgo. No es esmeralda común: parece crecer incluso después de extraída.",
                        "Ordené guardar cada fragmento. La ciudad pagará una fortuna por esto."));

        addDiary(plan, tunnelPoint(mine, 35, 10),
                "Diario de Lysa", "Lysa, minera",
                List.of(
                        "Día 17\n\nEl musgo apareció sobre las vigas nuevas. Lo raspamos por la mañana y regresó antes del cambio de turno.",
                        "Taro jura que oyó golpes detrás de la roca. Tres golpes, pausa, y otros tres. Allí no existe ninguna galería.",
                        "He escondido una muestra. Brilla cuando alguien duerme cerca."));

        addDiary(plan, tunnelPoint(mine, 58, -8),
                "Parte de extracción", "Supervisor Bel",
                List.of(
                        "Día 29\n\nLa producción se triplicó. También los accidentes. Las herramientas se cubren de raíces verdes y los animales rehúsan entrar.",
                        "La administración exige seguir. Dicen que la fortaleza necesita el mineral para sus sellos y sus armas.",
                        "Hoy una veta se movió como si respirara."));

        addDiary(plan, tunnelPoint(mine, 89, -11),
                "Cuaderno roto", "Autor desconocido",
                List.of(
                        "Día 41\n\nLa galería oriental cayó. No fue un derrumbe: la piedra cerró el paso detrás de nosotros.",
                        "Los cristales están creciendo dentro de los cuerpos. La esmeralda no corrompe el musgo. El musgo intenta contenerla.",
                        "Sellad la entrada. No dejéis que la ciudad excave más."));

        Anchor threshold = new Anchor(mine.geodeX() - mine.dx() * 21,
                mine.bossY() + 1, mine.geodeZ() - mine.dz() * 21);
        addDiary(plan, threshold,
                "Última entrada", "Capataz Orin",
                List.of(
                        "No hay más veta. Hay una geoda, tan grande como una catedral, y algo late en su centro.",
                        "La corrupción empezó aquí. Cada túnel que abrimos fue una vena que la llevó hacia la superficie.",
                        "He dejado los registros para quien venga después. Si el corazón despierta, no intentéis extraerlo. Destruid el acceso."));

        Anchor research = new Anchor(mine.bossX() + 5, mine.bossY() + 1, mine.bossZ());
        addDiary(plan, research,
                "Informe de la geoda", "Investigadora S. Vale",
                List.of(
                        "La cámara central responde a la presencia de llaves y reliquias de la campaña. Aún no sabemos qué entidad duerme debajo del altar.",
                        "Recomendación: mantener la arena sellada hasta diseñar un protocolo de invocación opcional.",
                        "Designación provisional del guardián: Corazón de Umbraverde."));
    }

    private Anchor tunnelPoint(MineSite mine, int t, int side) {
        int px = -mine.dz();
        int pz = mine.dx();
        int x = mine.entranceX() + mine.dx() * t + px * side;
        int z = mine.entranceZ() + mine.dz() * t + pz * side;
        int y = mine.entranceY() - 1 - t / 3;
        return new Anchor(x, y, z);
    }

    private void addDiary(Plan plan, Anchor anchor, String title,
                          String author, List<String> pages) {
        plan.put(anchor.x(), anchor.y(), anchor.z(), Material.LECTERN, Rule.MINE);
        plan.postActions.add(world -> setLecternBook(world, anchor, title, author, pages));
        plan.put(anchor.x(), anchor.y() + 1, anchor.z(), Material.LANTERN, Rule.MINE);
    }

    private void planOreVein(Plan plan, int cx, int cy, int cz,
                             Material material, int amount) {
        Random random = new Random(cx * 341873128712L + cz * 132897987541L + cy);
        int x = cx;
        int y = cy;
        int z = cz;
        for (int i = 0; i < amount; i++) {
            plan.put(x, y, z, material, Rule.MINE);
            x += random.nextInt(3) - 1;
            y += random.nextInt(3) - 1;
            z += random.nextInt(3) - 1;
        }
    }

    private void planCorruption(World world, Plan plan, Anchors anchors, MineSite mine) {
        if (mine == null) {
            Properties marker = readProperties(world.getWorldFolder().toPath().resolve(LORE_MARKER));
            mine = readMineSite(marker);
        }
        Random random = new Random(148354444L);
        if (mine != null) {
            int startX = mine.entranceX();
            int startZ = mine.entranceZ();
            int endX = anchors.dungeon().x();
            int endZ = anchors.dungeon().z();
            int vx = endX - startX;
            int vz = endZ - startZ;
            double length = Math.max(1.0D, Math.sqrt((double) vx * vx + (double) vz * vz));
            double nx = -vz / length;
            double nz = vx / length;
            int segments = Math.max(24, (int) length / 8);
            for (int lane : new int[]{-1, 0, 1}) {
                double laneOffset = lane * 17.0D;
                for (int i = 0; i <= segments; i++) {
                    double progress = i / (double) segments;
                    int cx = (int) Math.round(startX + vx * progress + nx * laneOffset)
                            + random.nextInt(13) - 6;
                    int cz = (int) Math.round(startZ + vz * progress + nz * laneOffset)
                            + random.nextInt(13) - 6;
                    int radius = 9 + random.nextInt(10) + (lane == 0 ? 3 : 0);
                    planCorruptionPatch(world, plan, anchors, cx, cz, radius,
                            2 + random.nextInt(4), random);
                }
            }

            for (int ring = 0; ring < 10; ring++) {
                double angle = ring * Math.PI * 2.0D / 10.0D;
                int cx = mine.entranceX() + (int) Math.round(Math.cos(angle) * (42 + ring % 3 * 11));
                int cz = mine.entranceZ() + (int) Math.round(Math.sin(angle) * (42 + ring % 3 * 11));
                planCorruptionPatch(world, plan, anchors, cx, cz,
                        14 + random.nextInt(9), 3 + random.nextInt(4), random);
            }
        }

        int dx = anchors.dungeon().x();
        int dz = anchors.dungeon().z();
        int[][] patches = {
                {-190, -125}, {-175, -45}, {-160, 120}, {-105, 185},
                {-35, 230}, {55, 220}, {145, 165}, {195, 92},
                {205, 12}, {178, -105}, {115, -188}, {35, -235},
                {-55, -225}, {-145, -175}, {-235, -55}, {-230, 70}
        };
        for (int[] patch : patches) {
            planCorruptionPatch(world, plan, anchors, dx + patch[0], dz + patch[1],
                    16 + random.nextInt(13), 3 + random.nextInt(6), random);
        }
    }

    private void planCorruptionPatch(World world, Plan plan, Anchors anchors,
                                     int cx, int cz, int radius, int crystals,
                                     Random random) {
        for (int ox = -radius; ox <= radius; ox++) {
            for (int oz = -radius; oz <= radius; oz++) {
                double normalized = (ox * ox + oz * oz) / (double) (radius * radius);
                if (normalized > 1.0D || random.nextDouble() < normalized * 0.34D) continue;
                int x = cx + ox;
                int z = cz + oz;
                if (!safeNaturalSurface(world, anchors, x, z, 2)) continue;
                int y = surfaceY(world, x, z);
                Material material;
                int roll = random.nextInt(20);
                if (roll <= 8) material = Material.MOSS_BLOCK;
                else if (roll <= 12) material = Material.MOSSY_COBBLESTONE;
                else if (roll <= 14) material = Material.COARSE_DIRT;
                else if (roll <= 16) material = Material.ROOTED_DIRT;
                else if (roll == 17) material = Material.VERDANT_FROGLIGHT;
                else material = world.getBlockAt(x, y, z).getType();
                plan.put(x, y, z, material, Rule.NATURAL);
                if (random.nextDouble() < 0.27D) {
                    Material growth = switch (random.nextInt(6)) {
                        case 0 -> Material.MOSS_CARPET;
                        case 1 -> Material.DEAD_BUSH;
                        case 2 -> Material.BROWN_MUSHROOM;
                        case 3 -> Material.AZALEA;
                        case 4 -> Material.FERN;
                        default -> Material.MOSS_CARPET;
                    };
                    plan.put(x, y + 1, z, growth, Rule.AIR_OR_PLANT);
                }
            }
        }
        for (int i = 0; i < crystals; i++) {
            int x = cx + random.nextInt(radius * 2 + 1) - radius;
            int z = cz + random.nextInt(radius * 2 + 1) - radius;
            if (!safeNaturalSurface(world, anchors, x, z, 2)) continue;
            int y = surfaceY(world, x, z) + 1;
            planCrystal(plan, x, y, z, 3 + random.nextInt(6));
        }
        int sickTrees = Math.max(1, radius / 9);
        for (int i = 0; i < sickTrees; i++) {
            int x = cx + random.nextInt(radius * 2 + 1) - radius;
            int z = cz + random.nextInt(radius * 2 + 1) - radius;
            planCorruptedTree(world, plan, anchors, x, z, random);
        }
        for (int i = 0; i < Math.max(2, radius / 6); i++) {
            int x = cx + random.nextInt(radius * 2 + 1) - radius;
            int z = cz + random.nextInt(radius * 2 + 1) - radius;
            planCorruptionRoot(world, plan, anchors, x, z, random);
        }
    }

    private void planCorruptedTree(World world, Plan plan, Anchors anchors,
                                   int x, int z, Random random) {
        if (!safeNaturalSurface(world, anchors, x, z, 4)) return;
        int y = surfaceY(world, x, z) + 1;
        int height = 7 + random.nextInt(7);
        for (int h = 0; h < height; h++) {
            int leanX = h > height / 2 ? (h - height / 2) / 3 : 0;
            plan.put(x + leanX, y + h, z,
                    h % 4 == 0 ? Material.STRIPPED_DARK_OAK_LOG : Material.DARK_OAK_LOG,
                    Rule.SURFACE_DECORATION);
        }
        for (int branch = 0; branch < 5; branch++) {
            int directionX = random.nextBoolean() ? 1 : -1;
            int directionZ = random.nextBoolean() ? 1 : -1;
            int branchY = y + height - 3 - random.nextInt(4);
            for (int step = 1; step <= 3 + random.nextInt(3); step++) {
                plan.put(x + directionX * step, branchY + step / 2,
                        z + directionZ * step, Material.DARK_OAK_LOG,
                        Rule.AIR_OR_PLANT);
            }
        }
        for (int ox = -3; ox <= 3; ox++) {
            for (int oz = -3; oz <= 3; oz++) {
                if (ox * ox + oz * oz > 10 || random.nextDouble() < 0.48D) continue;
                plan.put(x + ox, y + height - 1 + random.nextInt(3), z + oz,
                        random.nextBoolean() ? Material.AZALEA_LEAVES : Material.DARK_OAK_LEAVES,
                        Rule.AIR_OR_PLANT);
            }
        }
        plan.put(x, y - 1, z, Material.MOSS_BLOCK, Rule.NATURAL);
        if (random.nextBoolean()) {
            plan.put(x + 1, y + 1, z, Material.VERDANT_FROGLIGHT, Rule.NATURAL_OR_AIR);
        }
    }

    private void planCorruptionRoot(World world, Plan plan, Anchors anchors,
                                    int x, int z, Random random) {
        if (!safeNaturalSurface(world, anchors, x, z, 2)) return;
        int dx = random.nextBoolean() ? 1 : 0;
        int dz = dx == 0 ? 1 : 0;
        int length = 5 + random.nextInt(8);
        for (int step = 0; step < length; step++) {
            int bx = x + dx * step;
            int bz = z + dz * step;
            int y = surfaceY(world, bx, bz) + 1;
            plan.put(bx, y, bz,
                    step % 4 == 0 ? Material.EMERALD_BLOCK : Material.MOSS_BLOCK,
                    Rule.NATURAL_OR_AIR);
            if (step % 3 == 1) {
                plan.put(bx, y + 1, bz, Material.MOSS_CARPET, Rule.AIR_OR_PLANT);
            }
        }
    }

    private void planCrystal(Plan plan, int x, int y, int z, int height) {
        for (int h = 0; h < height; h++) {
            Material material = h == height - 1
                    ? Material.AMETHYST_CLUSTER
                    : (h % 3 == 0 ? Material.EMERALD_BLOCK : Material.AMETHYST_BLOCK);
            plan.put(x, y + h, z, material, Rule.NATURAL_OR_AIR);
        }
        plan.put(x + 1, y, z, Material.MOSS_BLOCK, Rule.NATURAL_OR_AIR);
        plan.put(x - 1, y, z, Material.MOSS_BLOCK, Rule.NATURAL_OR_AIR);
        plan.put(x, y, z + 1, Material.MOSS_BLOCK, Rule.NATURAL_OR_AIR);
        plan.put(x, y, z - 1, Material.MOSS_BLOCK, Rule.NATURAL_OR_AIR);
    }

    private List<Anchor> findDecorationSites(World world, Anchors anchors, int count,
                                             int minDistance, int maxDistance,
                                             int sampleRadius, boolean forest) {
        List<ScoredAnchor> candidates = new ArrayList<>();
        Anchor origin = forest ? anchors.village() : midpoint(anchors.village(), anchors.dungeon());
        for (int radius = minDistance; radius <= maxDistance; radius += 28) {
            for (int angle = 0; angle < 360; angle += 20) {
                double radians = Math.toRadians(angle);
                int x = origin.x() + (int) Math.round(Math.cos(radians) * radius);
                int z = origin.z() + (int) Math.round(Math.sin(radians) * radius);
                if (isProtectedPort(anchors, x, z)) continue;
                if (distanceSquared(x, z, anchors.village().x(), anchors.village().z()) < 105L * 105L) continue;
                if (distanceSquared(x, z, anchors.dungeon().x(), anchors.dungeon().z()) < 125L * 125L) continue;
                int y = surfaceY(world, x, z);
                if (!isNatural(world.getBlockAt(x, y, z).getType())) continue;
                double natural = naturalSurfaceRatio(world, x, z, sampleRadius, Math.max(3, sampleRadius / 7));
                int constructed = constructedCount(world, x, y, z, sampleRadius / 2, 8);
                boolean existingCamp = !forest && looksLikeCamp(world, x, z, 24);
                int relief = surfaceRelief(world, x, z, Math.max(8, sampleRadius / 2), 4);
                if (!existingCamp && (natural < (forest ? 0.82D : 0.91D)
                        || constructed > (forest ? 14 : 3))) continue;
                if (!forest && !existingCamp && relief > 5) continue;
                double score = natural * 100.0D - constructed * 6.0D
                        - Math.abs(y - origin.y()) * 0.15D - relief * 2.5D
                        + (existingCamp ? 280.0D : 0.0D);
                candidates.add(new ScoredAnchor(new Anchor(x, y, z), score));
            }
        }
        candidates.sort(Comparator.comparingDouble(ScoredAnchor::score).reversed());
        List<Anchor> selected = new ArrayList<>();
        int minimumSeparation = forest ? 115 : 80;
        for (ScoredAnchor candidate : candidates) {
            boolean tooClose = selected.stream().anyMatch(existing -> distanceSquared(
                    existing.x(), existing.z(), candidate.anchor().x(), candidate.anchor().z())
                    < (long) minimumSeparation * minimumSeparation);
            if (!tooClose) selected.add(candidate.anchor());
            if (selected.size() >= count) break;
        }
        return selected;
    }

    private boolean looksLikeCamp(World world, int cx, int cz, int radius) {
        int campfires = 0;
        int campBlocks = 0;
        for (int x = cx - radius; x <= cx + radius; x += 2) {
            for (int z = cz - radius; z <= cz + radius; z += 2) {
                int top = surfaceY(world, x, z);
                for (int y = top - 3; y <= top + 10; y++) {
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE) campfires++;
                    if (isCampDecoration(material)) campBlocks++;
                }
            }
        }
        return campfires > 0 && campBlocks >= 5;
    }

    private int surfaceRelief(World world, int cx, int cz, int radius, int step) {
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int x = cx - radius; x <= cx + radius; x += Math.max(1, step)) {
            for (int z = cz - radius; z <= cz + radius; z += Math.max(1, step)) {
                int y = surfaceY(world, x, z);
                minimum = Math.min(minimum, y);
                maximum = Math.max(maximum, y);
            }
        }
        return minimum == Integer.MAX_VALUE ? Integer.MAX_VALUE : maximum - minimum;
    }

    private boolean safeNaturalSurface(World world, Anchors anchors, int x, int z, int radius) {
        if (isProtectedPort(anchors, x, z)) return false;
        int y = surfaceY(world, x, z);
        Block top = world.getBlockAt(x, y, z);
        if (!isNatural(top.getType())) return false;
        if (!world.getBlockAt(x, y + 1, z).isEmpty()
                && !isPlant(world.getBlockAt(x, y + 1, z).getType())) return false;
        if (nearWater(world, x, y, z, 18)) return false;
        return constructedCount(world, x, y, z, radius, 6) == 0;
    }

    private boolean isProtectedPort(Anchors anchors, int x, int z) {
        return anchors.harbor() != null && distanceSquared(
                x, z, anchors.harbor().x(), anchors.harbor().z())
                <= (long) PORT_PROTECTION_RADIUS * PORT_PROTECTION_RADIUS;
    }

    private boolean nearWater(World world, int x, int y, int z, int radius) {
        int step = Math.max(4, radius / 4);
        for (int ox = -radius; ox <= radius; ox += step) {
            for (int oz = -radius; oz <= radius; oz += step) {
                for (int oy = -3; oy <= 2; oy++) {
                    if (world.getBlockAt(x + ox, y + oy, z + oz).getType() == Material.WATER) return true;
                }
            }
        }
        return false;
    }

    private double naturalSurfaceRatio(World world, int cx, int cz, int radius, int step) {
        int total = 0;
        int natural = 0;
        for (int x = cx - radius; x <= cx + radius; x += step) {
            for (int z = cz - radius; z <= cz + radius; z += step) {
                int y = surfaceY(world, x, z);
                total++;
                if (isNatural(world.getBlockAt(x, y, z).getType())) natural++;
            }
        }
        return total == 0 ? 0.0D : natural / (double) total;
    }

    private int constructedCount(World world, int cx, int cy, int cz,
                                 int radius, int vertical) {
        int count = 0;
        int step = Math.max(2, radius / 6);
        for (int x = cx - radius; x <= cx + radius; x += step) {
            for (int z = cz - radius; z <= cz + radius; z += step) {
                for (int y = cy - 2; y <= cy + vertical; y += 2) {
                    if (isConstructed(world.getBlockAt(x, y, z).getType())) count++;
                }
            }
        }
        return count;
    }

    private boolean applyBlock(World world, PlannedBlock operation) {
        if (operation.y() < world.getMinHeight() || operation.y() >= world.getMaxHeight()) return false;
        Block block = world.getBlockAt(operation.x(), operation.y(), operation.z());
        Material current = block.getType();
        if (!canReplace(current, operation.rule())) return false;
        if (current == operation.material()) {
            if (isLeaf(current) && block.getBlockData() instanceof Leaves leaves
                    && !leaves.isPersistent()) {
                leaves.setPersistent(true);
                block.setBlockData(leaves, false);
                return true;
            }
            return false;
        }

        if (isLeaf(operation.material())) {
            BlockData data = operation.material().createBlockData();
            if (data instanceof Leaves leaves) leaves.setPersistent(true);
            block.setBlockData(data, false);
        } else {
            block.setType(operation.material(), false);
        }
        return true;
    }

    private boolean canReplace(Material current, Rule rule) {
        return switch (rule) {
            case AIR -> current.isAir();
            case AIR_OR_PLANT -> current.isAir() || isPlant(current);
            case NATURAL -> isNatural(current);
            case NATURAL_OR_AIR -> current.isAir() || isPlant(current) || isNatural(current);
            case SURFACE_DECORATION -> current.isAir() || isPlant(current) || isNatural(current);
            case MINE -> isMineReplaceable(current);
            case CAMP_REPAIR -> current.isAir() || isPlant(current) || isNatural(current)
                    || isCampDecoration(current);
            case LEAF_PERSISTENCE -> isLeaf(current);
        };
    }

    private boolean isCampDecoration(Material material) {
        String name = material.name();
        return material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
                || material == Material.BARREL || material == Material.CHEST
                || material == Material.CRAFTING_TABLE || material == Material.HAY_BLOCK
                || material == Material.LANTERN || material == Material.SOUL_LANTERN
                || material == Material.CHAIN || material == Material.PACKED_MUD
                || material == Material.DIRT_PATH || material == Material.COARSE_DIRT
                || name.endsWith("_WOOL") || name.endsWith("_FENCE")
                || name.endsWith("_LOG") || name.endsWith("_PLANKS")
                || name.endsWith("_SLAB") || name.endsWith("_STAIRS");
    }

    private boolean isMineReplaceable(Material material) {
        return material.isAir() || isPlant(material) || isNatural(material)
                || material == Material.WATER || material == Material.LAVA
                || material.name().endsWith("_ORE") || material.name().contains("DEEPSLATE_");
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
                || material == Material.MOSS_BLOCK || material == Material.SNOW_BLOCK
                || material == Material.PACKED_MUD || material == Material.MYCELIUM
                || name.endsWith("_TERRACOTTA") || name.equals("TERRACOTTA")
                || name.endsWith("_ORE");
    }

    private boolean isConstructed(Material material) {
        String name = material.name();
        return name.contains("PLANKS") || name.contains("BRICKS") || name.contains("TILES")
                || name.endsWith("_WALL") || name.endsWith("_STAIRS") || name.endsWith("_SLAB")
                || name.endsWith("_FENCE") || name.endsWith("_DOOR") || name.endsWith("_GLASS")
                || name.contains("CONCRETE") || name.contains("COPPER")
                || material == Material.BARREL || material == Material.CHEST
                || material == Material.LANTERN || material == Material.TORCH
                || material == Material.RAIL || material == Material.IRON_BARS;
    }

    private boolean isPlant(Material material) {
        String name = material.name();
        return isLeaf(material) || name.contains("GRASS") || name.contains("FERN")
                || name.contains("FLOWER") || name.contains("AZALEA")
                || name.contains("MUSHROOM") || name.contains("VINE")
                || name.contains("SAPLING") || material == Material.DEAD_BUSH
                || material == Material.MOSS_CARPET || material == Material.SNOW
                || material == Material.LILY_PAD;
    }

    private boolean isLeaf(Material material) {
        return material.name().endsWith("_LEAVES");
    }

    private int surfaceY(World world, int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private int medianSurfaceY(World world, int cx, int cz, int radius, int step) {
        List<Integer> heights = new ArrayList<>();
        int increment = Math.max(1, step);
        for (int x = cx - radius; x <= cx + radius; x += increment) {
            for (int z = cz - radius; z <= cz + radius; z += increment) {
                heights.add(surfaceY(world, x, z));
            }
        }
        if (heights.isEmpty()) return surfaceY(world, cx, cz);
        heights.sort(Integer::compareTo);
        return heights.get(heights.size() / 2);
    }

    private Anchor surfaceAnchor(World world, int x, int z) {
        int y = surfaceY(world, x, z);
        return new Anchor(x, y, z);
    }

    private Anchor midpoint(Anchor first, Anchor second) {
        return new Anchor((first.x() + second.x()) / 2,
                (first.y() + second.y()) / 2,
                (first.z() + second.z()) / 2);
    }

    private void setLecternBook(World world, Anchor anchor, String title,
                                String author, List<String> pages) {
        Block block = world.getBlockAt(anchor.x(), anchor.y(), anchor.z());
        if (!(block.getState() instanceof Lectern lectern)) return;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(title.length() > 32 ? title.substring(0, 32) : title);
        meta.setAuthor(author);
        meta.setPages(pages);
        book.setItemMeta(meta);
        lectern.getInventory().setItem(0, book);
        lectern.update(true, false);
    }

    private void setSign(World world, int x, int y, int z, String... lines) {
        Block block = world.getBlockAt(x, y, z);
        if (!(block.getState() instanceof Sign sign)) return;
        for (int i = 0; i < Math.min(4, lines.length); i++) sign.setLine(i, lines[i]);
        sign.update(true, false);
    }

    private void writeMarker(World world, Properties existing, Set<String> categories,
                             Anchors anchors, MineSite mine, int changedBlocks) {
        for (String category : categories) {
            existing.setProperty(category, "true");
            existing.setProperty(category + "Revision", REVISION);
        }
        existing.setProperty("revision", REVISION);
        existing.setProperty("updatedAt", String.valueOf(System.currentTimeMillis()));
        existing.setProperty("changedBlocksLastRun", String.valueOf(changedBlocks));
        existing.setProperty("portProtected", "true");
        existing.setProperty("portTouched", "false");
        if (anchors.harbor() != null) {
            existing.setProperty("protectedHarbor", coordinates(anchors.harbor()));
            existing.setProperty("protectedHarborRadius", String.valueOf(PORT_PROTECTION_RADIUS));
        }
        if (mine != null) {
            existing.setProperty("mineEntrance", mine.entranceX() + ","
                    + mine.entranceY() + "," + mine.entranceZ());
            existing.setProperty("mineHeading", mine.dx() + "," + mine.dz());
            existing.setProperty("geodeCenter", mine.geodeX() + ","
                    + mine.geodeY() + "," + mine.geodeZ());
            existing.setProperty("optionalBossArena", mine.bossX() + ","
                    + mine.bossY() + "," + mine.bossZ());
            existing.setProperty("optionalBossId", "mossy_emerald_geode_heart");
            existing.setProperty("optionalBossImplemented", "false");
            existing.setProperty("loreOrigin", "mossy_emerald_corruption_origin");
            existing.setProperty("mineAccessibleWithoutMining", "true");
            existing.setProperty("geodePreservedAsFinalZone", "true");
        }
        existing.setProperty("categories", appliedCategoryList(existing));

        Path marker = world.getWorldFolder().toPath().resolve(LORE_MARKER);
        try {
            StringBuilder text = new StringBuilder();
            existing.stringPropertyNames().stream().sorted().forEach(key ->
                    text.append(key).append('=').append(existing.getProperty(key)).append('\n'));
            Files.writeString(marker, text.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo escribir el marcador de decoración 1.48.35: "
                    + error.getMessage());
        }
    }

    private String appliedCategoryList(Properties properties) {
        List<String> applied = new ArrayList<>();
        for (String category : List.of("forests", "camps", "mine", "corruption")) {
            if (Boolean.parseBoolean(properties.getProperty(category, "false"))) applied.add(category);
        }
        return String.join(",", applied);
    }

    private MineSite readMineSite(Properties properties) {
        Anchor entrance = parseAnchor(properties.getProperty("mineEntrance"));
        Anchor geode = parseAnchor(properties.getProperty("geodeCenter"));
        Anchor boss = parseAnchor(properties.getProperty("optionalBossArena"));
        String heading = properties.getProperty("mineHeading", "");
        String[] parts = heading.split(",");
        if (entrance == null || geode == null || boss == null || parts.length < 2) return null;
        try {
            int dx = Integer.parseInt(parts[0].trim());
            int dz = Integer.parseInt(parts[1].trim());
            int projection = Math.abs((geode.x() - entrance.x()) * dx
                    + (geode.z() - entrance.z()) * dz);
            int tunnelLength = Math.max(118, projection - 18);
            return new MineSite(entrance.x(), entrance.y(), entrance.z(), dx, dz, tunnelLength,
                    geode.x(), geode.y(), geode.z(), boss.x(), boss.y(), boss.z());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Properties readProperties(Path path) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(path)) return properties;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo leer " + path.getFileName() + ": " + error.getMessage());
        }
        return properties;
    }

    private Anchor parseAnchor(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.split(",");
        if (parts.length < 3) return null;
        try {
            return new Anchor(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String coordinates(Anchor anchor) {
        return anchor.x() + "," + anchor.y() + "," + anchor.z();
    }

    private String[] tokenize(String rawCommand) {
        if (rawCommand == null) return new String[0];
        String clean = rawCommand.trim();
        if (clean.startsWith("/")) clean = clean.substring(1);
        return clean.isEmpty() ? new String[0] : clean.split("\\s+");
    }

    private List<String> filter(List<String> values, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo template overworld decorate "
                + "<preview|upgrade|all|forests|camps|mine|corruption|status|cancel|force> [categoría]");
        sender.sendMessage(ChatColor.GRAY + "El puerto editado manualmente queda protegido y nunca se reconstruye.");
        sender.sendMessage(ChatColor.GRAY + "Ejemplos: decorate preview upgrade · decorate upgrade · decorate force mine");
    }

    private static long distanceSquared(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }

    private static double square(double value) {
        return value * value;
    }

    private enum Rule {
        AIR,
        AIR_OR_PLANT,
        NATURAL,
        NATURAL_OR_AIR,
        SURFACE_DECORATION,
        MINE,
        CAMP_REPAIR,
        LEAF_PERSISTENCE
    }

    private static final class Plan {
        private final LinkedHashMap<Long, PlannedBlock> blocks = new LinkedHashMap<>();
        private final List<Consumer<World>> postActions = new ArrayList<>();

        void put(int x, int y, int z, Material material, Rule rule) {
            blocks.put(blockKey(x, y, z), new PlannedBlock(x, y, z, material, rule));
        }

        private static long blockKey(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38)
                    | ((long) (z & 0x3FFFFFF) << 12)
                    | (y & 0xFFFL);
        }
    }

    private record PlannedBlock(int x, int y, int z, Material material, Rule rule) { }
    private record Anchor(int x, int y, int z) { }
    private record Anchors(Anchor village, Anchor dungeon, Anchor harbor) { }
    private record ScoredAnchor(Anchor anchor, double score) { }
    private record MineCandidate(int x, int y, int z, int dx, int dz, double score) { }
    private record MineSite(int entranceX, int entranceY, int entranceZ,
                            int dx, int dz, int tunnelLength,
                            int geodeX, int geodeY, int geodeZ,
                            int bossX, int bossY, int bossZ) { }
}
