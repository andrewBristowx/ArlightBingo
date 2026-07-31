package com.arlight.bingo.listeners;

import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.game.GameState;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maneja tres cosas independientes de la logica del juego en si:
 * - Si un jugador muere en la arena (o en uno de sus mundos extra, nether/end), respawnea
 *   en el spawn del mundo lobby en vez del mundo por defecto del servidor.
 * - Cada vez que un jugador ENTRA a la arena (o a un mundo extra), lo deja en modo
 *   supervivencia, le quita cualquier efecto de pocion activo, y le resetea la vida
 *   maxima/actual a la vanilla (20 corazones llenos) -- asi todos arrancan parejos.
 * - Si un jugador se desconecta durante una partida en curso, lo descalifica.
 */
public class GameListener implements Listener {

    private final BingoGame game;
    private final Map<UUID, Long> manualPortalCooldown = new HashMap<>();
    private final Map<UUID, Long> lockedPortalMessageCooldown = new HashMap<>();

    public GameListener(BingoGame game) {
        this.game = game;
    }

    /**
     * Prioridad HIGHEST: en un servidor hibrido (Arclight/NeoForge) puede haber otros
     * plugins/mods escuchando este mismo evento; corriendo en HIGHEST nos aseguramos de
     * que nuestro cambio de ubicacion de respawn sea el que quede (se aplica al final).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!isArenaRelatedWorld(player.getWorld().getName())) return;

        Location safeLobby = game.resolveSafeLobbySpawn();
        if (safeLobby == null || safeLobby.getWorld() == null) {
            Bukkit.getLogger().warning("[ArlightBingo] No existe un mundo de respawn seguro; "
                    + "se mantuvo la ubicación elegida por Bukkit.");
            return;
        }
        event.setRespawnLocation(safeLobby);
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!isArenaRelatedWorld(player.getWorld().getName())) return;

        player.setGameMode(GameMode.SURVIVAL);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        var maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealthAttr.setBaseValue(20.0);
        }
        player.setHealth(20.0);
        player.setFoodLevel(20);
        // OJO: a proposito NO llamamos setSaturation() aca. Forzar una saturacion alta
        // en cada entrada al mundo hace que el HUD de "Saturacion" del cliente quede
        // pegado en pantalla (no es un efecto de pocion real, es el indicador vanilla
        // de saturacion de hambre) -- dejamos que el juego la maneje solo.
        player.setFireTicks(0);
    }

    /**
     * Conecta los tres mundos nativos del Bingo sin depender de Multiverse-NetherPortals.
     * Como el jugador nunca cambia de servidor ni de perfil, conserva exactamente el
     * mismo inventario al cruzar entre Overworld, Nether y End.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArenaPortal(PlayerPortalEvent event) {
        String arenaName = game.getCurrentArenaWorld();
        if (arenaName == null || !isArenaRelatedWorld(event.getFrom().getWorld().getName())) return;

        World overworld = Bukkit.getWorld(arenaName);
        World nether = findExtraWorld(World.Environment.NETHER);
        World end = findExtraWorld(World.Environment.THE_END);
        World from = event.getFrom().getWorld();
        if (overworld == null || from == null) return;

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && from.getEnvironment() == World.Environment.NORMAL && nether == null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(org.bukkit.ChatColor.RED
                    + "La copia del Nether no está disponible. Se bloqueó el portal para evitar otro mundo o el vacío.");
            return;
        }
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                && from.getEnvironment() != World.Environment.THE_END && end == null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(org.bukkit.ChatColor.RED
                    + "La copia del End no está disponible. Se bloqueó el portal para evitar el vacío.");
            return;
        }

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL && nether != null) {
            if (from.getEnvironment() == World.Environment.NORMAL) {
                if (!game.isNetherUnlocked()) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(org.bukkit.ChatColor.RED + "Derrota al guardián del Overworld para abrir el Nether.");
                    return;
                }
                org.bukkit.Location fixed = game.getFixedNetherArrival();
                if (fixed == null || fixed.getWorld() == null) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(org.bukkit.ChatColor.RED
                            + "El Nether de esta arena no terminó de copiarse. La entrada fue bloqueada para evitar el vacío.");
                    return;
                }
                event.setTo(fixed);
            } else if (from.getEnvironment() == World.Environment.NETHER) {
                org.bukkit.Location fixed = game.getFixedOverworldReturn();
                if (fixed == null || fixed.getWorld() == null) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(org.bukkit.ChatColor.RED
                            + "La salida de esta arena todavía no está lista.");
                    return;
                }
                event.setTo(fixed);
            }
            event.setCanCreatePortal(false);
            return;
        }

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL && end != null) {
            if (from.getEnvironment() == World.Environment.THE_END) {
                org.bukkit.Location fixed = game.getFixedOverworldReturn();
                if (fixed == null || fixed.getWorld() == null) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(org.bukkit.ChatColor.RED
                            + "La salida de esta arena todavía no está lista.");
                    return;
                }
                event.setTo(fixed);
            } else {
                if (!game.isEndUnlocked()) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(org.bukkit.ChatColor.RED + "Derrota al guardián del Nether para abrir el End.");
                    return;
                }
                org.bukkit.Location safeArrival = game.getEndEncounterArrival(end);
                if (safeArrival == null || safeArrival.getWorld() == null) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(org.bukkit.ChatColor.RED
                            + "El End de esta arena no terminó de copiarse. La entrada fue bloqueada para evitar el vacío.");
                    return;
                }
                event.setTo(safeArrival);
            }
            event.setCanCreatePortal(false);
        }
    }

    private World findExtraWorld(World.Environment environment) {
        for (String name : game.getCurrentArenaExtraWorlds()) {
            World world = Bukkit.getWorld(name);
            if (world != null && world.getEnvironment() == environment) return world;
        }
        return null;
    }

    /** Si alguien se desconecta durante una partida en curso, se lo descalifica. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        game.disqualifyIfInMatch(event.getPlayer());
    }

    /**
     * Si el jugador se habia desconectado en pleno partido, le debemos su inventario
     * original (ver BingoGame#restoreSavedInventoryIfPending). Se espera un par de ticks
     * porque en Arclight el inventario del cliente a veces se resincroniza justo despues
     * del PlayerJoinEvent, y no queremos que esa resincronizacion pise nuestra restauracion.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // El cliente puede conservar el último cartón en memoria al desconectarse. Mandamos
        // CLEAR antes de cualquier resincronización y lo repetimos unos ticks después por
        // compatibilidad con Arclight/NeoForge y el registro tardío de canales del cliente.
        game.clearClientCard(player);
        Bukkit.getScheduler().runTaskLater(game.getPlugin(), () -> {
            if (player.isOnline()) game.clearClientCard(player);
        }, 2L);

        Bukkit.getScheduler().runTaskLater(game.getPlugin(), () -> {
            if (player.isOnline()) {
                game.restoreSavedInventoryIfPending(player);
                // Un jugador que reapareció dentro de un clon antiguo no debe quedar atado
                // a un respawn de Multiverse que ya no existe.
                if ((game.getState() == GameState.WAITING || game.getState() == GameState.COUNTDOWN)
                        && isArenaRelatedWorld(player.getWorld().getName())) {
                    Location lobby = game.resolveSafeLobbySpawn();
                    game.ensureSafeRespawn(player);
                    if (lobby != null) player.teleport(lobby, PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
                // Solo se vuelve a mostrar si el jugador realmente sigue teniendo equipo y cartón.
                game.syncCardForPlayer(player);
            }
        }, 8L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTiebreakDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (game.handleTiebreakDamage(player, event.getFinalDamage())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onTiebreakMove(PlayerMoveEvent event) {
        // PlayerMoveEvent se dispara tambien con solo mover la camara. Los portales
        // custom se detectan por bloque para que funcionen igualmente en supervivencia
        // aunque Arclight u otro plugin no llegue a emitir PlayerPortalEvent.
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) return;
        if (handleManualCampaignPortal(event.getPlayer(), event.getFrom(), event.getTo())) return;
        game.checkTiebreakFall(event.getPlayer());
    }

    private boolean handleManualCampaignPortal(Player player, Location from, Location to) {
        if (player == null || to == null || to.getWorld() == null) return false;
        if (!isArenaRelatedWorld(to.getWorld().getName())) return false;
        if (game.getState() != GameState.RUNNING && game.getState() != GameState.TIEBREAK) return false;

        Material portal = portalMaterialNear(to);
        if (portal == null) return false;

        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        if (manualPortalCooldown.getOrDefault(uuid, 0L) > now) return true;

        World overworld = Bukkit.getWorld(game.getCurrentArenaWorld());
        World nether = findExtraWorld(World.Environment.NETHER);
        World end = findExtraWorld(World.Environment.THE_END);
        World current = to.getWorld();
        Location target = null;

        if (portal == Material.NETHER_PORTAL) {
            if (current.getEnvironment() == World.Environment.NORMAL && nether == null) {
                player.sendMessage(org.bukkit.ChatColor.RED
                        + "La copia del Nether no está disponible; el portal fue bloqueado.");
                player.teleport(from);
                return true;
            }
            if (current.getEnvironment() == World.Environment.NORMAL) {
                if (!game.isNetherUnlocked()) {
                    lockedPortal(player, "Derrota al Guardián de la Superficie para abrir el Nether.", now);
                    player.teleport(from);
                    return true;
                }
                target = game.getFixedNetherArrival();
                if (target == null || target.getWorld() == null) {
                    player.sendMessage(org.bukkit.ChatColor.RED
                            + "El Nether de esta arena no está listo; no se realizó el teletransporte.");
                    player.teleport(from);
                    return true;
                }
            } else if (current.getEnvironment() == World.Environment.NETHER) {
                target = game.getFixedOverworldReturn();
                if (target == null || target.getWorld() == null) {
                    player.sendMessage(org.bukkit.ChatColor.RED
                            + "La salida de esta arena todavía no está lista.");
                    player.teleport(from);
                    return true;
                }
            }
        } else {
            if (current.getEnvironment() == World.Environment.THE_END) {
                target = game.getFixedOverworldReturn();
                if (target == null || target.getWorld() == null) {
                    player.sendMessage(org.bukkit.ChatColor.RED
                            + "La salida de esta arena todavía no está lista.");
                    player.teleport(from);
                    return true;
                }
            } else {
                if (end == null) {
                    player.sendMessage(org.bukkit.ChatColor.RED
                            + "La copia del End no está disponible; el portal fue bloqueado.");
                    player.teleport(from);
                    return true;
                }
                if (!game.isEndUnlocked()) {
                    lockedPortal(player, "Derrota al Guardián del Nether para abrir el End.", now);
                    player.teleport(from);
                    return true;
                }
                if (end != null) {
                    target = game.getEndEncounterArrival(end);
                    if (target == null || target.getWorld() == null) {
                        player.sendMessage(org.bukkit.ChatColor.RED
                                + "El End de esta arena no está listo; no se realizó el teletransporte.");
                        player.teleport(from);
                        return true;
                    }
                }
            }
        }

        if (target == null || target.getWorld() == null) return false;
        manualPortalCooldown.put(uuid, now + 3500L);
        target.getChunk().load(true);
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0.0F);
        player.setPortalCooldown(100);
        boolean teleported = player.teleport(target.clone().add(0, 0.12, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (!teleported) {
            // Reintento un tick después: algunos bridges de Arclight rechazan el primer
            // teletransporte mientras finalizan la colisión del portal.
            Location retry = target.clone().add(0, 0.12, 0);
            Bukkit.getScheduler().runTask(game.getPlugin(), () -> {
                if (!player.isOnline()) return;
                player.setVelocity(new Vector(0, 0, 0));
                player.setFallDistance(0.0F);
                player.teleport(retry, PlayerTeleportEvent.TeleportCause.PLUGIN);
            });
        }
        return true;
    }


    /** Busca el bloque de portal alrededor del volumen completo del jugador. */
    private Material portalMaterialNear(Location location) {
        if (location == null || location.getWorld() == null) return null;
        // En supervivencia la caja del jugador puede quedar en el bloque vecino al portal,
        // especialmente con marcos gruesos, slabs o portales de suelo.
        for (int y = -1; y <= 2; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    Material type = location.clone().add(x, y, z).getBlock().getType();
                    if (type == Material.NETHER_PORTAL || type == Material.END_PORTAL) return type;
                }
            }
        }
        return null;
    }

    private void lockedPortal(Player player, String message, long now) {
        UUID uuid = player.getUniqueId();
        if (lockedPortalMessageCooldown.getOrDefault(uuid, 0L) <= now) {
            lockedPortalMessageCooldown.put(uuid, now + 2500L);
            player.sendMessage(org.bukkit.ChatColor.RED + message);
        }
    }

    private boolean sameBlock(org.bukkit.Location from, org.bukkit.Location to) {
        return from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    /**
     * Mientras un jugador esta jugando una partida en curso, solo se le permite usar el
     * comando /bingo (cuyos subcomandos ya estan a su vez restringidos a "arena" y "leave"
     * por BingoCommand para jugadores no-admin) -- cualquier otro comando de otro plugin
     * queda bloqueado, para que no puedan usar /home, /tpa, etc. para hacer trampa.
     * Los admins (permiso arlightbingo.admin) quedan exentos de esta restriccion.
     */
    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (game.getState() != GameState.RUNNING && game.getState() != GameState.TIEBREAK) return;
        if (game.getTeamOf(player) == null) return;
        if (player.hasPermission("arlightbingo.admin")) return;

        String message = event.getMessage().toLowerCase();
        if (message.startsWith("/bingo")) return;

        event.setCancelled(true);
        player.sendMessage(org.bukkit.ChatColor.RED + "Durante la partida solo podes usar comandos de /bingo.");
    }

    private boolean isArenaRelatedWorld(String worldName) {
        String arena = game.getCurrentArenaWorld();
        if (arena != null && worldName.equalsIgnoreCase(arena)) return true;
        for (String extra : game.getCurrentArenaExtraWorlds()) {
            if (worldName.equalsIgnoreCase(extra)) return true;
        }
        return false;
    }
}
