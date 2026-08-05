package com.arlight.bingo.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Locale;

final class OverworldCampaignModel145 {
    private OverworldCampaignModel145() { }

    record BlockEdit(int x, int y, int z, Material material) { }

    record Site(String id, int x, int baseY, int z, int radius, Style style) {
        Location floor(World world) { return new Location(world, x, baseY + 1, z); }
    }

    record Layout(Location village, Location residential, Location commercial,
                  Location military, Location citadel, Location boss, Location portal,
                  Location altar, Location ritualGate) { }

    enum Style { RESIDENTIAL, COMMERCIAL, MILITARY, CITADEL, BOSS, PORTAL }

    static final class Report {
        long shapedColumns;
        long appliedBlocks;
        int structures;
        int roads;
        int decorations;
    }

    static String format(Location location) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f",
                location.getX(), location.getY(), location.getZ());
    }
}
