package com.arlight.bingo.commands;

import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.game.BingoGoal;
import com.arlight.bingo.game.BingoTeam;
import com.arlight.bingo.game.GameState;
import com.arlight.bingo.gui.BingoCardItem;
import com.arlight.bingo.gui.CardGUI;
import com.arlight.bingo.util.ConfigManager;
import com.arlight.bingo.util.ArenaWorldManager;
import com.arlight.bingo.util.BingoWaitingNetwork;
import com.arlight.bingo.util.PreparationNetwork;
import com.arlight.bingo.BingoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class BingoCommand implements CommandExecutor, TabCompleter {

    private final BingoGame game;
    private final JavaPlugin plugin;

    private org.bukkit.scheduler.BukkitTask templateSequenceTask;
    private int templateSequenceStep = -1;
    private boolean templateSequenceForce;
    private String templateSequenceState = "inactiva";

    public BingoCommand(BingoGame game, JavaPlugin plugin) {
        this.game = game;
        this.plugin = plugin;
    }

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "start", "stop", "join", "leave", "card", "cardclassic", "carditem", "lobby", "arena",
            "trigger", "world", "campaign", "template", "repair", "blacklist", "vanillaonly", "screen", "reload"
    );

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            switch (sub) {
                case "world":
                    return filter(Arrays.asList("lobby", "game", "info", "prepare", "status", "resetarena", "addextra", "removeextra"), args[1]);
                case "blacklist":
                    return filter(Arrays.asList("item", "mod", "removeitem", "removemod", "list"), args[1]);
                case "vanillaonly":
                    return filter(Arrays.asList("true", "false"), args[1]);
                case "trigger":
                    return filter(onlinePlayerNames(), args[1]);
                case "screen":
                    return filter(Arrays.asList("waiting", "preparation", "notice", "hide"), args[1]);
                case "campaign":
                    return filter(Arrays.asList("status", "giveigneous", "unlocknether", "givedragon", "opengolem", "summondragon", "tp", "tpnether", "tpend", "survival", "somita"), args[1]);
                case "template":
                    return filter(Arrays.asList("overworld", "nether", "end", "all"), args[1]);
                case "repair":
                    return filter(List.of("overworld"), args[1]);
                default:
                    return List.of();
            }
        }

        if (args.length == 3) {
            if (sub.equals("screen")) {
                if (args[1].equalsIgnoreCase("preparation")) return filter(Arrays.asList("0","25","50","75","100"), args[2]);
                return filter(onlinePlayerNames(), args[2]);
            }
            switch (sub) {
                case "world":
                    if (args[1].equalsIgnoreCase("lobby") || args[1].equalsIgnoreCase("game")
                            || args[1].equalsIgnoreCase("resetarena") || args[1].equalsIgnoreCase("addextra")) {
                        return filter(worldNames(), args[2]);
                    }
                    if (args[1].equalsIgnoreCase("removeextra")) {
                        return filter(new ArrayList<>(game.getConfigManager().getExtraArenaWorlds()), args[2]);
                    }
                    return List.of();
                case "blacklist":
                    if (args[1].equalsIgnoreCase("removeitem")) {
                        return filter(new ArrayList<>(game.getConfigManager().getBlacklistItems()), args[2]);
                    }
                    if (args[1].equalsIgnoreCase("removemod")) {
                        return filter(new ArrayList<>(game.getConfigManager().getBlacklistMods()), args[2]);
                    }
                    return List.of();
                case "campaign":
                    if (args[1].equalsIgnoreCase("tp")) {
                        return filter(Arrays.asList("village", "residential", "commercial", "military", "citadel", "surfaceboss", "surfaceportal", "nether", "lock", "netherboss", "end", "golem", "altar", "dragon"), args[2]);
                    }
                    if (args[1].equalsIgnoreCase("somita")) {
                        return filter(Arrays.asList("show", "anim", "effect", "scene", "demo", "hide", "clear", "list"), args[2]);
                    }
                    return List.of();
                case "template":
                    if (args[1].equalsIgnoreCase("all")) {
                        return filter(Arrays.asList("generate", "status", "cancel", "audit"), args[2]);
                    }
                    if (args[1].equalsIgnoreCase("overworld")) {
                        return filter(Arrays.asList("generate", "resume", "status", "audit",
                                "revisions", "promote", "rollback", "force-stage",
                                "cancel", "reset", "tp", "lootr", "testboss"), args[2]);
                    }
                    if (Arrays.asList("nether", "end").contains(args[1].toLowerCase())) {
                        return filter(Arrays.asList("generate", "resume", "status", "audit",
                                "cancel", "reset", "tp", "lootr", "testboss"), args[2]);
                    }
                    return List.of();
                case "repair":
                    if (args[1].equalsIgnoreCase("overworld")) {
                        return filter(Arrays.asList("scan", "preview", "apply", "region",
                                "rollback", "status"), args[2]);
                    }
                    return List.of();
                case "trigger": {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    BingoTeam team = target != null ? game.getTeamOf(target) : null;
                    if (team == null || team.getCard() == null) return List.of();
                    List<String> ids = team.getCard().getGoals().stream().map(BingoGoal::getId).collect(Collectors.toList());
                    return filter(ids, args[2]);
                }
                default:
                    return List.of();
            }
        }

        if (args.length == 4 && sub.equals("campaign") && args[1].equalsIgnoreCase("somita")) {
            return switch (args[2].toLowerCase()) {
                case "show" -> filter(Arrays.asList("overworld", "nether", "end", "celebration"), args[3]);
                case "anim" -> filter(Arrays.asList("idle", "wave", "point", "walk", "look", "bow", "celebrate_cute", "celebrate_elegant", "vanish", "hold", "cross_arms"), args[3]);
                case "effect" -> filter(Arrays.asList("overworld_appear", "overworld_point", "nether_appear", "nether_lock", "end_appear", "end_altar", "celebration_burst", "vanish", "wave_sparkle", "hold_orbit"), args[3]);
                case "scene", "demo" -> filter(Arrays.asList("overworld", "nether", "end", "celebration", "altar"), args[3]);
                default -> List.of();
            };
        }

        if (sub.equals("template") && args[1].equalsIgnoreCase("overworld")) {
            if (args.length == 4) {
                if (args[2].equalsIgnoreCase("generate")) {
                    return filter(Arrays.asList("r001-zone-a"), args[3]);
                }
                if (args[2].equalsIgnoreCase("promote")
                        || args[2].equalsIgnoreCase("force-stage")) {
                    if (plugin instanceof BingoPlugin bingoPlugin) {
                        String working = bingoPlugin.getOverworldTemplateManager().workingRevision();
                        return working.isBlank() ? List.of() : filter(List.of(working), args[3]);
                    }
                }
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("force-stage")) {
                return filter(List.of("custom"), args[4]);
            }
        }

        return List.of();
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private List<String> worldNames() {
        return Bukkit.getWorlds().stream().map(w -> w.getName()).collect(Collectors.toList());
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).collect(Collectors.toList());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo <start|stop|join|leave|card|screen|world|campaign|template|repair|reload>");
            return true;
        }

        String sub = args[0].toLowerCase();

        // Mientras la partida esta EN CURSO, los jugadores normales (sin permiso de admin) solo
        // pueden usar /bingo arena y /bingo leave -- el resto (join, card, lobby, etc.) no tiene
        // sentido a mitad de partida o ya lo reemplaza el item fisico del carton.
        boolean isEssentialDuringMatch = sub.equals("arena") || sub.equals("leave")
                || sub.equals("card") || sub.equals("cardclassic");
        if ((game.getState() == GameState.RUNNING || game.getState() == GameState.TIEBREAK) && sender instanceof Player
                && !sender.hasPermission("arlightbingo.admin") && !isEssentialDuringMatch) {
            sender.sendMessage(ChatColor.RED + "Durante la partida solo puedes usar /bingo arena, leave o card.");
            return true;
        }

        switch (sub) {

            case "screen": {
                if (!checkAdmin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo screen <waiting|preparation|notice|hide> [progreso] [jugador]");
                    return true;
                }
                String mode = args[1].toLowerCase();
                int progress = 50;
                String playerName = null;
                if (mode.equals("preparation")) {
                    if (args.length > 2) {
                        try { progress = Math.max(0, Math.min(100, Integer.parseInt(args[2]))); }
                        catch (NumberFormatException ignored) { playerName = args[2]; }
                    }
                    if (args.length > 3) playerName = args[3];
                } else if (args.length > 2) playerName = args[2];
                Player target = playerName == null && sender instanceof Player p ? p : Bukkit.getPlayerExact(playerName);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Jugador no encontrado. Desde consola indica el nombre al final.");
                    return true;
                }
                BingoWaitingNetwork waiting = new BingoWaitingNetwork(plugin);
                PreparationNetwork preparation = new PreparationNetwork(plugin);
                switch (mode) {
                    case "waiting" -> waiting.show(target, 1, game.getConfigManager().getMaxPlayersScoreboard(), null);
                    case "preparation" -> preparation.show(List.of(target), "Prueba de preparación", 0, progress / 100.0);
                    case "notice" -> preparation.noticeShow(List.of(target), game.getConfigManager());
                    case "hide" -> { waiting.hide(target); preparation.hide(List.of(target)); preparation.noticeHide(List.of(target)); }
                    default -> { sender.sendMessage(ChatColor.RED + "Pantalla desconocida."); return true; }
                }
                sender.sendMessage(ChatColor.GREEN + "Pantalla " + mode + " enviada a " + target.getName() + ".");
                return true;
            }

            case "start":
                if (!checkAdmin(sender)) return true;
                game.start();
                return true;

            case "stop":
                if (!checkAdmin(sender)) return true;
                game.stop();
                sender.sendMessage(ChatColor.YELLOW + "Partida detenida.");
                return true;

            case "reload":
                if (!checkAdmin(sender)) return true;
                game.getConfigManager().load();
                sender.sendMessage(ChatColor.GREEN + "Configuracion recargada.");
                return true;

            case "join": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo un jugador puede usar esto.");
                    return true;
                }
                Player player = (Player) sender;
                String teamName = args.length > 1 ? args[1] : null;
                game.addPlayer(player, teamName);
                return true;
            }

            case "leave": {
                if (!(sender instanceof Player)) return true;
                Player player = (Player) sender;
                if (game.getState() == GameState.RUNNING || game.getState() == GameState.TIEBREAK) {
                    game.disqualifyPlayer(player, ChatColor.RED + player.getName() + " abandono el Bingo y quedo descalificado.");
                } else {
                    game.removePlayer(player);
                    sender.sendMessage(ChatColor.YELLOW + "Has salido de la partida.");
                }
                return true;
            }

            case "card": {
                if (!(sender instanceof Player player)) return true;
                BingoTeam team = game.getTeamOf(player);
                if (team == null || team.getCard() == null) {
                    sender.sendMessage(ChatColor.RED + "No tienes un cartón activo todavía.");
                    return true;
                }
                game.openClientCard(player);
                return true;
            }

            case "cardclassic": {
                if (!(sender instanceof Player player)) return true;
                BingoTeam team = game.getTeamOf(player);
                if (team == null || team.getCard() == null) {
                    sender.sendMessage(ChatColor.RED + "No tienes un cartón activo todavía.");
                    return true;
                }
                player.openInventory(getOrBuildInventory(team));
                return true;
            }

            case "trigger": {
                if (!checkAdmin(sender)) return true;
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Uso: /bingo trigger <jugador> <idObjetivo>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Jugador no encontrado (debe estar conectado).");
                    return true;
                }
                BingoTeam team = game.getTeamOf(target);
                if (team == null || team.getCard() == null) {
                    sender.sendMessage(ChatColor.RED + "Ese jugador no tiene carton activo.");
                    return true;
                }
                BingoGoal goal = team.getCard().findById(args[2]);
                if (goal == null) {
                    sender.sendMessage(ChatColor.RED + "No existe ese id de objetivo en el carton.");
                    return true;
                }
                if (!goal.isCompleted()) {
                    goal.forceComplete();
                    game.onGoalCompleted(team, goal);
                }
                sender.sendMessage(ChatColor.GREEN + "Objetivo marcado como completado.");
                return true;
            }

            case "carditem": {
                if (!(sender instanceof Player)) return true;
                Player player = (Player) sender;
                BingoTeam team = game.getTeamOf(player);
                org.bukkit.inventory.ItemStack cardItem = team != null
                        ? BingoCardItem.createForTeam(plugin, team)
                        : BingoCardItem.create(plugin);
                player.getInventory().addItem(cardItem);
                player.sendMessage(ChatColor.GREEN + "Te dimos un carton de Bingo (click derecho para verlo).");
                return true;
            }

            case "vanillaonly": {
                if (!checkAdmin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo vanillaonly <true|false>  (actual: "
                            + game.getConfigManager().isVanillaOnly() + ")");
                    return true;
                }
                boolean value = Boolean.parseBoolean(args[1]);
                game.getConfigManager().setVanillaOnly(value);
                sender.sendMessage(ChatColor.GREEN + "vanilla-only ahora en " + value
                        + " (pool de objetivos regenerado).");
                return true;
            }

            case "blacklist": {
                if (!checkAdmin(sender)) return true;
                if (!(sender instanceof Player) && args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Desde consola necesitas dar el id explicito.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo blacklist <item|mod|removeitem|removemod|list> [valor]");
                    return true;
                }
                Player p = (sender instanceof Player) ? (Player) sender : null;

                switch (args[1].toLowerCase()) {
                    case "item": {
                        String key = args.length >= 3 ? args[2] : heldItemKey(p, sender);
                        if (key == null) return true;
                        boolean added = game.getConfigManager().addBlacklistItem(key);
                        sender.sendMessage(added
                                ? ChatColor.GREEN + key + " agregado a la blacklist de items."
                                : ChatColor.YELLOW + "Ese item ya estaba en la blacklist.");
                        return true;
                    }
                    case "mod": {
                        String modId = args.length >= 3 ? args[2] : heldItemModId(p, sender);
                        if (modId == null) return true;
                        boolean added = game.getConfigManager().addBlacklistMod(modId);
                        sender.sendMessage(added
                                ? ChatColor.GREEN + "Mod '" + modId + "' baneado por completo de los objetivos."
                                : ChatColor.YELLOW + "Ese mod ya estaba baneado.");
                        return true;
                    }
                    case "removeitem": {
                        if (args.length < 3) {
                            sender.sendMessage(ChatColor.RED + "Uso: /bingo blacklist removeitem <namespace:id>");
                            return true;
                        }
                        boolean removed = game.getConfigManager().removeBlacklistItem(args[2]);
                        sender.sendMessage(removed
                                ? ChatColor.GREEN + "Quitado de la blacklist."
                                : ChatColor.YELLOW + "No estaba en la blacklist.");
                        return true;
                    }
                    case "removemod": {
                        if (args.length < 3) {
                            sender.sendMessage(ChatColor.RED + "Uso: /bingo blacklist removemod <modid>");
                            return true;
                        }
                        boolean removed = game.getConfigManager().removeBlacklistMod(args[2]);
                        sender.sendMessage(removed
                                ? ChatColor.GREEN + "Mod quitado del ban."
                                : ChatColor.YELLOW + "Ese mod no estaba baneado.");
                        return true;
                    }
                    case "list": {
                        sender.sendMessage(ChatColor.GOLD + "Items baneados: " + ChatColor.WHITE + game.getConfigManager().getBlacklistItems());
                        sender.sendMessage(ChatColor.GOLD + "Mods baneados: " + ChatColor.WHITE + game.getConfigManager().getBlacklistMods());
                        return true;
                    }
                    default:
                        sender.sendMessage(ChatColor.RED + "Subcomando de blacklist desconocido.");
                        return true;
                }
            }

            case "lobby": {
                if (!(sender instanceof Player)) return true;
                ArenaWorldManager mv = game.getArenaWorldManager();
                String lobby = game.getConfigManager().getLobbyWorld();
                if (mv == null || lobby == null || lobby.isEmpty() || !mv.teleportToWorld((Player) sender, lobby)) {
                    sender.sendMessage(ChatColor.RED + "No hay un lobby configurado o no esta cargado.");
                }
                return true;
            }

            case "arena": {
                if (!(sender instanceof Player)) return true;
                Player player = (Player) sender;
                ArenaWorldManager mv = game.getArenaWorldManager();
                String arena = game.getCurrentArenaWorld();
                if (game.getState() != GameState.RUNNING || arena == null || mv == null) {
                    sender.sendMessage(ChatColor.RED + "No hay ninguna partida en curso.");
                    return true;
                }
                if (game.getTeamOf(player) == null) {
                    sender.sendMessage(ChatColor.RED + "No estas anotado en la partida en curso.");
                    return true;
                }
                if (!mv.teleportToWorld(player, arena)) {
                    sender.sendMessage(ChatColor.RED + "El mundo de la arena no esta cargado.");
                }
                return true;
            }

            case "world": {
                if (!checkAdmin(sender)) return true;
                ConfigManager cfg = game.getConfigManager();
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo world <prepare|status|lobby|game|info|resetarena|addextra|removeextra> [mundo]");
                    return true;
                }
                switch (args[1].toLowerCase()) {
                    case "prepare": {
                        if (!(plugin instanceof BingoPlugin bingoPlugin)) {
                            sender.sendMessage(ChatColor.RED + "No se pudo acceder al administrador de arenas.");
                            return true;
                        }
                        boolean started = bingoPlugin.getWorldPoolManager().prepareNext();
                        sender.sendMessage(started
                                ? ChatColor.GREEN + (cfg.isTemplateMatchesEnabled()
                                ? "Preparación iniciada. Se copiarán las plantillas completas de Overworld, Nether y End."
                                : "Preparación iniciada. Chunky procesará Overworld, Nether y End en orden.")
                                : ChatColor.YELLOW + "No se inició: ya hay una preparación activa o no quedan slots pendientes.");
                        return true;
                    }
                    case "status": {
                        if (plugin instanceof BingoPlugin bingoPlugin) {
                            sender.sendMessage(ChatColor.GOLD + "Pool: " + ChatColor.WHITE
                                    + bingoPlugin.getWorldPoolManager().status());
                        }
                        return true;
                    }
                    case "lobby":
                        if (args.length < 3) {
                            sender.sendMessage(ChatColor.RED + "Uso: /bingo world lobby <mundo>");
                            return true;
                        }
                        cfg.setLobbyWorld(args[2]);
                        sender.sendMessage(ChatColor.GREEN + "Mundo lobby seteado a " + args[2] + ".");
                        return true;
                    case "game":
                        if (args.length < 3) {
                            sender.sendMessage(ChatColor.RED + "Uso: /bingo world game <mundo>");
                            return true;
                        }
                        cfg.setGameWorld(args[2]);
                        sender.sendMessage(ChatColor.GREEN + "Mundo de partida seteado a " + args[2] + ".");
                        return true;
                    case "info":
                        sender.sendMessage(ChatColor.GOLD + "Lobby: " + ChatColor.WHITE + cfg.getLobbyWorld());
                        sender.sendMessage(ChatColor.GOLD + "Mundo de partida: " + ChatColor.WHITE + cfg.getGameWorld());
                        sender.sendMessage(ChatColor.GOLD + "Mundos extra (nether/end, etc.): " + ChatColor.WHITE + cfg.getExtraArenaWorlds());
                        return true;
                    case "addextra": {
                        if (args.length < 3) {
                            sender.sendMessage(ChatColor.RED + "Uso: /bingo world addextra <mundo>  (ej. el nether/end de la arena)");
                            return true;
                        }
                        boolean added = cfg.addExtraArenaWorld(args[2]);
                        sender.sendMessage(added
                                ? ChatColor.GREEN + args[2] + " agregado como mundo extra (se regenerara junto con la arena)."
                                : ChatColor.YELLOW + "Ese mundo ya estaba en la lista.");
                        return true;
                    }
                    case "removeextra": {
                        if (args.length < 3) {
                            sender.sendMessage(ChatColor.RED + "Uso: /bingo world removeextra <mundo>");
                            return true;
                        }
                        boolean removed = cfg.removeExtraArenaWorld(args[2]);
                        sender.sendMessage(removed
                                ? ChatColor.GREEN + args[2] + " quitado de los mundos extra."
                                : ChatColor.YELLOW + "Ese mundo no estaba en la lista.");
                        return true;
                    }
                    case "resetarena": {
                        String target = args.length >= 3 ? args[2] : cfg.getGameWorld();
                        ArenaWorldManager mgr = game.getArenaWorldManager();
                        if (mgr == null || target == null || target.isEmpty()) {
                            sender.sendMessage(ChatColor.RED + "No hay mundo de partida configurado.");
                            return true;
                        }
                        sender.sendMessage(ChatColor.YELLOW + "Confirmando y regenerando '" + target + "' ahora mismo...");
                        mgr.forceClaimAndRegenerate(target);
                        sender.sendMessage(ChatColor.GREEN + "Listo. De ahora en mas el plugin regenerara ese mundo solo en cada partida.");
                        return true;
                    }
                    default:
                        sender.sendMessage(ChatColor.RED + "Subcomando de world desconocido.");
                        return true;
                }
            }



            case "repair": {
                if (!checkAdmin(sender)) return true;
                if (!(plugin instanceof BingoPlugin bingoPlugin)) {
                    sender.sendMessage(ChatColor.RED + "No se pudo acceder al reparador de Bingo.");
                    return true;
                }
                if (args.length < 3 || !args[1].equalsIgnoreCase("overworld")) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo repair overworld "
                            + "<scan|preview|apply|region|rollback|status> [radio]");
                    return true;
                }
                var templates = bingoPlugin.getOverworldTemplateManager();
                String action = args[2].toLowerCase();
                int radius = 96;
                if (args.length >= 4) {
                    try { radius = Math.max(16, Math.min(256, Integer.parseInt(args[3]))); }
                    catch (NumberFormatException ignored) {
                        sender.sendMessage(ChatColor.RED + "El radio debe ser un número entre 16 y 256.");
                        return true;
                    }
                }
                switch (action) {
                    case "scan" -> templates.repairScan(sender);
                    case "status" -> sender.sendMessage(ChatColor.AQUA
                            + "Autorreparación Overworld: " + ChatColor.WHITE
                            + templates.repairStatus());
                    case "preview" -> {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(ChatColor.RED + "La previsualización debe usarla un jugador.");
                        } else templates.repairPreview(player, radius);
                    }
                    case "apply" -> {
                        Location center = args.length >= 4 && sender instanceof Player player
                                ? player.getLocation() : null;
                        templates.repairApply(sender, center, args.length >= 4 ? radius : 0);
                    }
                    case "region" -> {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(ChatColor.RED + "La reparación regional debe usarla un jugador.");
                        } else templates.repairApply(sender, player.getLocation(), radius);
                    }
                    case "rollback" -> templates.repairRollback(sender);
                    default -> sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo repair overworld "
                            + "<scan|preview|apply|region|rollback|status> [radio]");
                }
                return true;
            }

            case "template": {
                if (!checkAdmin(sender)) return true;
                if (!(plugin instanceof BingoPlugin bingoPlugin)) {
                    sender.sendMessage(ChatColor.RED + "No se pudo acceder al generador de plantillas.");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo template <overworld|nether|end|all> "
                            + "<generate|resume|status|audit|revisions|promote|rollback|force-stage|cancel|reset|tp> [revisión]");
                    return true;
                }

                String dimension = args[1].toLowerCase();
                String action = args[2].toLowerCase();
                boolean force = args.length >= 4 && args[3].equalsIgnoreCase("force");

                if (dimension.equals("all")) {
                    switch (action) {
                        case "generate" -> startTemplateSequence(sender, bingoPlugin, force);
                        case "status" -> {
                            sender.sendMessage(ChatColor.AQUA + "Secuencia: " + ChatColor.WHITE + templateSequenceState);
                            sender.sendMessage(ChatColor.GREEN + "Overworld: " + ChatColor.WHITE + bingoPlugin.getOverworldTemplateManager().status());
                            sender.sendMessage(ChatColor.GOLD + "Nether: " + ChatColor.WHITE + bingoPlugin.getNetherTemplateManager().status());
                            sender.sendMessage(ChatColor.LIGHT_PURPLE + "End: " + ChatColor.WHITE + bingoPlugin.getEndTemplateManager().status());
                        }
                        case "cancel" -> cancelTemplateSequence(sender, bingoPlugin);
                        case "audit" -> {
                            bingoPlugin.getOverworldTemplateManager().audit(sender);
                            bingoPlugin.getNetherTemplateManager().audit(sender);
                            bingoPlugin.getEndTemplateManager().audit(sender);
                        }
                        default -> sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo template all <generate|status|audit|cancel> [force]");
                    }
                    return true;
                }

                if (dimension.equals("overworld")) {
                    var templates = bingoPlugin.getOverworldTemplateManager();
                    switch (action) {
                        case "generate" -> {
                            if (args.length >= 4 && !force) templates.generate(sender, args[3]);
                            else templates.generate(sender, force);
                        }
                        case "resume" -> templates.resume(sender);
                        case "status" -> sender.sendMessage(ChatColor.GREEN + "Plantilla Overworld: " + ChatColor.WHITE + templates.status());
                        case "audit" -> templates.audit(sender);
                        case "revisions" -> templates.revisions(sender);
                        case "promote" -> {
                            if (args.length < 4) {
                                sender.sendMessage(ChatColor.RED + "Uso: /bingo template overworld promote <revisión>");
                            } else templates.promote(sender, args[3]);
                        }
                        case "rollback" -> {
                            if (args.length < 4) {
                                sender.sendMessage(ChatColor.RED + "Uso: /bingo template overworld rollback <revisión>");
                            } else templates.rollback(sender, args[3]);
                        }
                        case "force-stage" -> {
                            if (args.length < 5) {
                                sender.sendMessage(ChatColor.RED + "Uso: /bingo template overworld force-stage <revisión> custom");
                            } else templates.forceStage(sender, args[3], args[4]);
                        }
                        case "cancel" -> templates.cancel(sender);
                        case "reset" -> templates.reset(sender);
                        case "lootr" -> sender.sendMessage(ChatColor.LIGHT_PURPLE + "Lootr: " + ChatColor.WHITE + templates.lootrStatus());
                        case "tp" -> {
                            if (!(sender instanceof Player player)) sender.sendMessage(ChatColor.RED + "Este comando debe usarlo un jugador.");
                            else if (!templates.teleport(player)) sender.sendMessage(ChatColor.RED + "La plantilla todavía no está cargada.");
                        }
                        case "testboss" -> {
                            if (!(sender instanceof Player player)) sender.sendMessage(ChatColor.RED + "Este comando debe usarlo un jugador.");
                            else if (!templates.testBoss(player)) sender.sendMessage(ChatColor.RED + "La arena del jefe aún no está disponible.");
                        }
                        default -> sender.sendMessage(ChatColor.RED + "Acción de plantilla desconocida.");
                    }
                    return true;
                }

                if (dimension.equals("nether")) {
                    var templates = bingoPlugin.getNetherTemplateManager();
                    switch (action) {
                        case "generate" -> templates.generate(sender, force);
                        case "resume" -> templates.resume(sender);
                        case "status" -> sender.sendMessage(ChatColor.GOLD + "Plantilla Nether: " + ChatColor.WHITE + templates.status());
                        case "audit" -> templates.audit(sender);
                        case "cancel" -> templates.cancel(sender);
                        case "reset" -> templates.reset(sender);
                        case "lootr" -> sender.sendMessage(ChatColor.LIGHT_PURPLE + "Lootr: cofres y barriles de la plantilla conservan loot tables personales.");
                        case "tp" -> {
                            if (!(sender instanceof Player player)) sender.sendMessage(ChatColor.RED + "Este comando debe usarlo un jugador.");
                            else if (!templates.teleport(player)) sender.sendMessage(ChatColor.RED + "La plantilla todavía no está cargada.");
                        }
                        case "testboss" -> {
                            if (!(sender instanceof Player player)) sender.sendMessage(ChatColor.RED + "Este comando debe usarlo un jugador.");
                            else if (!templates.testBoss(player)) sender.sendMessage(ChatColor.RED + "La arena del jefe aún no está disponible.");
                        }
                        default -> sender.sendMessage(ChatColor.RED + "Acción de plantilla desconocida.");
                    }
                    return true;
                }

                if (dimension.equals("end")) {
                    var templates = bingoPlugin.getEndTemplateManager();
                    switch (action) {
                        case "generate" -> templates.generate(sender, force);
                        case "resume" -> templates.resume(sender);
                        case "status" -> sender.sendMessage(ChatColor.LIGHT_PURPLE + "Plantilla End: " + ChatColor.WHITE + templates.status());
                        case "audit" -> templates.audit(sender);
                        case "cancel" -> templates.cancel(sender);
                        case "reset" -> templates.reset(sender);
                        case "lootr" -> sender.sendMessage(ChatColor.LIGHT_PURPLE + "Lootr: cofres y barriles de la plantilla conservan loot tables personales.");
                        case "tp" -> {
                            if (!(sender instanceof Player player)) sender.sendMessage(ChatColor.RED + "Este comando debe usarlo un jugador.");
                            else if (!templates.teleport(player)) sender.sendMessage(ChatColor.RED + "La plantilla todavía no está cargada.");
                        }
                        case "testboss" -> {
                            if (!(sender instanceof Player player)) sender.sendMessage(ChatColor.RED + "Este comando debe usarlo un jugador.");
                            else if (!templates.testBoss(player)) sender.sendMessage(ChatColor.RED + "La arena del jefe aún no está disponible.");
                        }
                        default -> sender.sendMessage(ChatColor.RED + "Acción de plantilla desconocida.");
                    }
                    return true;
                }

                sender.sendMessage(ChatColor.RED + "Dimensión de plantilla desconocida.");
                return true;
            }

            case "campaign": {
                if (!checkAdmin(sender)) return true;
                if (!(plugin instanceof BingoPlugin bingoPlugin)) {
                    sender.sendMessage(ChatColor.RED + "No se pudo acceder al administrador de la campaña.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo campaign <status|giveigneous|unlocknether|givedragon|opengolem|summondragon|tp|tpnether|tpend|survival|somita> [etapa]");
                    return true;
                }

                var dimensions = bingoPlugin.getDimensionDungeonManager();
                var end = bingoPlugin.getEndEncounterManager();
                String action = args[1].toLowerCase();

                if (action.equals("status")) {
                    sender.sendMessage(ChatColor.GOLD + "Campaña dimensional: " + ChatColor.WHITE
                            + (dimensions == null ? "no preparada" : dimensions.debugStatus()));
                    sender.sendMessage(ChatColor.DARK_PURPLE + "Campaña del End: " + ChatColor.WHITE
                            + (end == null ? "no preparada" : end.debugStatus()));
                    return true;
                }

                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Esta acción de prueba debe ejecutarla un jugador.");
                    return true;
                }

                if (action.equals("somita")) {
                    if (dimensions == null) {
                        sender.sendMessage(ChatColor.RED + "La campaña todavía no está disponible.");
                        return true;
                    }
                    if (args.length < 3) {
                        sender.sendMessage(ChatColor.YELLOW + "Uso: /bingo campaign somita <show|anim|effect|scene|demo|hide|clear|list> [valor]");
                        return true;
                    }
                    String somitaAction = args[2].toLowerCase();
                    boolean somitaSuccess;
                    switch (somitaAction) {
                        case "show" -> {
                            if (args.length < 4) {
                                sender.sendMessage(ChatColor.RED + "Uso: /bingo campaign somita show <overworld|nether|end|celebration>");
                                return true;
                            }
                            somitaSuccess = dimensions.debugSomitaShow(player, args[3]);
                        }
                        case "anim" -> {
                            if (args.length < 4) {
                                sender.sendMessage(ChatColor.RED + "Uso: /bingo campaign somita anim <idle|wave|point|walk|look|bow|celebrate_cute|celebrate_elegant|vanish|hold|cross_arms>");
                                return true;
                            }
                            somitaSuccess = dimensions.debugSomitaAnimation(player, args[3]);
                        }
                        case "effect" -> {
                            if (args.length < 4) {
                                sender.sendMessage(ChatColor.RED + "Uso: /bingo campaign somita effect <overworld_appear|overworld_point|nether_appear|nether_lock|end_appear|end_altar|celebration_burst|vanish|wave_sparkle|hold_orbit>");
                                return true;
                            }
                            somitaSuccess = dimensions.debugSomitaEffect(player, args[3]);
                        }
                        case "scene" -> {
                            if (args.length < 4) {
                                sender.sendMessage(ChatColor.RED + "Uso: /bingo campaign somita scene <overworld|nether|end|altar|celebration>");
                                return true;
                            }
                            somitaSuccess = dimensions.debugSomitaScene(player, args[3]);
                        }
                        case "demo" -> somitaSuccess = args.length >= 4
                                ? dimensions.debugSomitaDemo(player, args[3])
                                : dimensions.debugSomitaDemo(player);
                        case "hide" -> somitaSuccess = dimensions.debugSomitaHide(player);
                        case "clear" -> somitaSuccess = dimensions.debugSomitaClear(player);
                        case "list" -> {
                            sender.sendMessage(ChatColor.LIGHT_PURPLE + "Variantes: overworld, nether, end, celebration");
                            sender.sendMessage(ChatColor.AQUA + "Animaciones: idle, wave, point, walk, look, bow, celebrate_cute, celebrate_elegant, vanish, hold, cross_arms");
                            sender.sendMessage(ChatColor.GOLD + "Efectos: overworld_appear, overworld_point, nether_appear, nether_lock, end_appear, end_altar, celebration_burst, vanish, wave_sparkle, hold_orbit");
                            sender.sendMessage(ChatColor.GOLD + "Escenas: overworld, nether, end, altar, celebration");
                            return true;
                        }
                        default -> {
                            sender.sendMessage(ChatColor.RED + "Acción de Somita desconocida.");
                            return true;
                        }
                    }
                    sender.sendMessage(somitaSuccess
                            ? ChatColor.GREEN + "Prueba de Somita ejecutada: " + somitaAction + "."
                            : ChatColor.RED + "No se pudo ejecutar la prueba de Somita.");
                    return true;
                }

                boolean success;
                switch (action) {
                    case "giveigneous" -> success = dimensions != null && dimensions.debugGiveIgneousKey(player);
                    case "unlocknether" -> success = dimensions != null && dimensions.debugUnlockNetherGate(player);
                    case "givedragon" -> success = end != null && end.debugGiveDragonKey(player);
                    case "opengolem" -> success = end != null && end.debugOpenGolemGate();
                    case "summondragon" -> success = end != null && end.debugInvokeDragon(player);
                    case "tpnether" -> {
                        player.setGameMode(GameMode.CREATIVE);
                        success = dimensions != null && dimensions.debugTeleport(player, "nether");
                        if (!success) player.sendMessage(ChatColor.RED + "No hay una arena Nether activa o preparada.");
                    }
                    case "tpend" -> {
                        player.setGameMode(GameMode.CREATIVE);
                        success = end != null && end.debugTeleport(player, "end");
                        if (!success) player.sendMessage(ChatColor.RED + "No hay una arena End activa o preparada.");
                    }
                    case "survival" -> {
                        player.setGameMode(GameMode.SURVIVAL);
                        success = true;
                    }
                    case "tp" -> {
                        if (args.length < 3) {
                            sender.sendMessage(ChatColor.RED + "Uso: /bingo campaign tp <village|residential|commercial|military|citadel|surfaceboss|surfaceportal|nether|lock|netherboss|end|golem|altar|dragon>");
                            return true;
                        }
                        String stage = args[2].toLowerCase();
                        success = (dimensions != null && dimensions.debugTeleport(player, stage))
                                || (end != null && end.debugTeleport(player, stage));
                    }
                    default -> {
                        sender.sendMessage(ChatColor.RED + "Acción de campaña desconocida.");
                        return true;
                    }
                }

                sender.sendMessage(success
                        ? ChatColor.GREEN + "Acción de prueba ejecutada: " + action + "."
                        : ChatColor.RED + "No se pudo ejecutar la acción. Inicia o prepara una arena primero.");
                return true;
            }

            default:
                sender.sendMessage(ChatColor.RED + "Subcomando desconocido.");
                return true;
        }
    }

    private void startTemplateSequence(CommandSender sender, BingoPlugin bingoPlugin, boolean force) {
        if (templateSequenceTask != null) {
            sender.sendMessage(ChatColor.YELLOW + "Ya hay una secuencia de plantillas activa: " + templateSequenceState);
            return;
        }

        templateSequenceForce = force;
        templateSequenceStep = 0;
        templateSequenceState = "iniciando Overworld";

        boolean started = bingoPlugin.getOverworldTemplateManager()
                .generate(Bukkit.getConsoleSender(), force);
        if (!started) {
            templateSequenceStep = -1;
            templateSequenceState = "no pudo iniciar Overworld";
            sender.sendMessage(ChatColor.RED + "No se pudo iniciar el Overworld. Revisa su estado o usa 'force'.");
            return;
        }

        sender.sendMessage(ChatColor.GREEN + "Secuencia iniciada: Overworld → Nether → End.");
        sender.sendMessage(ChatColor.YELLOW + "El siguiente mundo comenzará automáticamente cuando el anterior termine.");
        announceTemplateSequence(ChatColor.GREEN + "[Bingo] Generando plantilla 1/3: Overworld.");

        templateSequenceTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> tickTemplateSequence(bingoPlugin), 20L, 20L);
    }

    private void tickTemplateSequence(BingoPlugin bingoPlugin) {
        if (templateSequenceTask == null) return;

        if (templateSequenceStep == 0) {
            var manager = bingoPlugin.getOverworldTemplateManager();
            templateSequenceState = "1/3 Overworld: " + manager.status();
            switch (manager.stage()) {
                case COMPLETE -> {
                    templateSequenceStep = 1;
                    templateSequenceState = "iniciando Nether";
                    announceTemplateSequence(ChatColor.GOLD + "[Bingo] Overworld terminado. Generando plantilla 2/3: Nether.");
                    if (!bingoPlugin.getNetherTemplateManager().generate(Bukkit.getConsoleSender(), templateSequenceForce)) {
                        abortTemplateSequence("no se pudo iniciar Nether");
                    }
                }
                case FAILED -> abortTemplateSequence("falló Overworld: " + manager.status());
                case CANCELLED -> abortTemplateSequence("Overworld fue cancelado");
                default -> { }
            }
            return;
        }

        if (templateSequenceStep == 1) {
            var manager = bingoPlugin.getNetherTemplateManager();
            templateSequenceState = "2/3 Nether: " + manager.status();
            switch (manager.stage()) {
                case COMPLETE -> {
                    templateSequenceStep = 2;
                    templateSequenceState = "iniciando End";
                    announceTemplateSequence(ChatColor.LIGHT_PURPLE + "[Bingo] Nether terminado. Generando plantilla 3/3: End.");
                    if (!bingoPlugin.getEndTemplateManager().generate(Bukkit.getConsoleSender(), templateSequenceForce)) {
                        abortTemplateSequence("no se pudo iniciar End");
                    }
                }
                case FAILED -> abortTemplateSequence("falló Nether: " + manager.status());
                case CANCELLED -> abortTemplateSequence("Nether fue cancelado");
                default -> { }
            }
            return;
        }

        if (templateSequenceStep == 2) {
            var manager = bingoPlugin.getEndTemplateManager();
            templateSequenceState = "3/3 End: " + manager.status();
            switch (manager.stage()) {
                case COMPLETE -> {
                    templateSequenceState = "completa: Overworld, Nether y End preparados";
                    announceTemplateSequence(ChatColor.GREEN + "[Bingo] Las tres plantillas terminaron correctamente.");
                    stopTemplateSequenceTask();
                }
                case FAILED -> abortTemplateSequence("falló End: " + manager.status());
                case CANCELLED -> abortTemplateSequence("End fue cancelado");
                default -> { }
            }
        }
    }

    private void cancelTemplateSequence(CommandSender sender, BingoPlugin bingoPlugin) {
        if (templateSequenceTask == null) {
            sender.sendMessage(ChatColor.YELLOW + "No hay una secuencia automática activa.");
            return;
        }

        if (templateSequenceStep == 0) bingoPlugin.getOverworldTemplateManager().cancel(Bukkit.getConsoleSender());
        else if (templateSequenceStep == 1) bingoPlugin.getNetherTemplateManager().cancel(Bukkit.getConsoleSender());
        else if (templateSequenceStep == 2) bingoPlugin.getEndTemplateManager().cancel(Bukkit.getConsoleSender());

        templateSequenceState = "cancelada por administrador";
        stopTemplateSequenceTask();
        sender.sendMessage(ChatColor.YELLOW + "Secuencia automática cancelada. Los mundos incompletos se conservaron para revisión.");
    }

    private void abortTemplateSequence(String reason) {
        templateSequenceState = "detenida: " + reason;
        announceTemplateSequence(ChatColor.RED + "[Bingo] Secuencia de plantillas detenida: " + reason);
        stopTemplateSequenceTask();
    }

    private void stopTemplateSequenceTask() {
        if (templateSequenceTask != null) templateSequenceTask.cancel();
        templateSequenceTask = null;
        templateSequenceStep = -1;
    }

    private void announceTemplateSequence(String message) {
        Bukkit.getConsoleSender().sendMessage(message);
        Bukkit.broadcast(message, "arlightbingo.admin");
    }

    private org.bukkit.inventory.Inventory getOrBuildInventory(BingoTeam team) {
        org.bukkit.inventory.Inventory inv = team.getGuiInventory();
        if (inv == null) {
            inv = CardGUI.build(team.getCard());
            team.setGuiInventory(inv);
        }
        return inv;
    }

    private boolean checkAdmin(CommandSender sender) {
        if (!sender.hasPermission("arlightbingo.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para hacer eso.");
            return false;
        }
        return true;
    }

    /** Devuelve el namespaced key ("modid:item") del item en la mano principal, o null (con mensaje) si no hay uno valido. */
    private String heldItemKey(Player player, CommandSender sender) {
        if (player == null) {
            sender.sendMessage(ChatColor.RED + "Necesitas ser un jugador sosteniendo el item, o dar el id explicito.");
            return null;
        }
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage(ChatColor.RED + "Tenes que sostener el item en la mano principal.");
            return null;
        }
        return held.getType().getKey().getNamespace() + ":" + held.getType().getKey().getKey();
    }

    /** Devuelve solo el namespace/modid del item en la mano principal. */
    private String heldItemModId(Player player, CommandSender sender) {
        String key = heldItemKey(player, sender);
        if (key == null) return null;
        return key.contains(":") ? key.split(":", 2)[0] : key;
    }
}
