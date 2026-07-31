package com.arlight.bingo.listeners;

import com.arlight.bingo.game.BingoGame;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Aísla el chat normal de los jugadores que pertenecen a la sesión de Bingo.
 * Los formateadores como ArlightChat/LPC pueden seguir aplicando prefijos y
 * emotes; este listener solo limita los destinatarios al grupo de la partida.
 */
@SuppressWarnings("deprecation")
public final class BingoChatListener implements Listener {
    private final BingoGame game;

    public BingoChatListener(BingoGame game) {
        this.game = game;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        boolean senderInBingo = game.isParticipant(sender);

        if (senderInBingo) {
            event.getRecipients().removeIf(recipient -> !game.isParticipant(recipient));
            String format = event.getFormat();
            if (!format.contains("[Bingo]")) {
                event.setFormat(ChatColor.DARK_PURPLE + "[Bingo] " + ChatColor.RESET + format);
            }
        } else {
            // Los jugadores de la sesión tampoco reciben el chat exterior mientras juegan.
            event.getRecipients().removeIf(game::isParticipant);
        }
    }
}
