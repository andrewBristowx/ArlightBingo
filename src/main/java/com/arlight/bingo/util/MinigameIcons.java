package com.arlight.bingo.util;

import org.bukkit.ChatColor;

/** Iconos compartidos del paquete ArlightChat 1.4.0. */
public final class MinigameIcons {
    public static final String CHECK = icon('\uE300');
    public static final String CROSS = icon('\uE301');
    public static final String WARNING = icon('\uE302');
    public static final String INFO = icon('\uE303');
    public static final String TROPHY = icon('\uE304');
    public static final String CLOCK = icon('\uE305');
    public static final String SWORDS = icon('\uE306');
    public static final String PLAYERS = icon('\uE307');
    public static final String STAR = icon('\uE308');

    private MinigameIcons() {
    }

    private static String icon(char character) {
        return ChatColor.WHITE + Character.toString(character) + ChatColor.RESET + " ";
    }
}
