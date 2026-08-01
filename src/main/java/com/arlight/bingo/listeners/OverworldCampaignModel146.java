package com.arlight.bingo.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Locale;

final class OverworldCampaignModel146 {
    private OverworldCampaignModel146() { }

    record BlockEdit(int x, int y, int z, Material material) { }

    record Site(String id, int x, int baseY, int z, int radius, Style style) {
        Location floor(World world) { return new Location(world, x, baseY + 1, z); }
    }

    record Layout(Location village, Location residential, Location commercial,
                  Location military, Location citadel, Location boss, Location portal,
                  Location outerAltar, Location invocationAltar, Location ritualGate) { }

    enum Style { VILLAGE, RESIDENTIAL, COMMERCIAL, MILITARY, CITADEL, BOSS, PORTAL }

    static final class Report {
        long restoredColumns;
        long shapedColumns;
        long appliedBlocks;
        long clearedBlocks;
        int structures;
        int roads;
        int decorations;
        int villageBuildings;
    }

    static String format(Location location) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f",
                location.getX(), location.getY(), location.getZ());
    }
}
