package com.arlight.bingo.listeners;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;
import static com.arlight.bingo.listeners.OverworldCampaignArchitecture146.*;

final class OverworldCampaignStructures146 {
    private OverworldCampaignStructures146() { }

    static List<BlockEdit> village(Site site, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int lowerY = site.baseY() + 1;
        int plazaY = site.baseY() + 3;
        int upperY = site.baseY() + 5;

        // Three connected terraces instead of one circular lawn.
        retainingWall(out, site.x() - 58, site.x() + 58, lowerY, site.z() + 20, 2);
        retainingWall(out, site.x() - 54, site.x() + 54, plazaY, site.z() - 23, 2);
        villageStreet(out, site.x(), lowerY, site.z() + 67,
                site.x(), plazaY, site.z() + 17, 5);
        villageStreet(out, site.x(), plazaY, site.z() + 15,
                site.x(), upperY, site.z() - 57, 5);
        villageStreet(out, site.x() - 54, plazaY, site.z(),
                site.x() + 66, plazaY, site.z(), 5);
        villagePlaza(out, site.x(), plazaY, site.z());

        // Southern service quarter.
        villageHouse(out, site.x() - 31, lowerY, site.z() + 43,
                23, 15, 2, true, Material.LIGHT_GRAY_TERRACOTTA, 1); // inn
        villageHouse(out, site.x() + 30, lowerY, site.z() + 42,
                19, 15, 1, false, Material.GRAY_TERRACOTTA, 2); // smithy
        garden(out, site.x() - 5, lowerY, site.z() + 57, 25, 13);
        out.add(e(site.x() + 39, lowerY, site.z() + 50, Material.BLAST_FURNACE));
        out.add(e(site.x() + 42, lowerY, site.z() + 50, Material.ANVIL));

        // Central civic and commercial quarter.
        villageHouse(out, site.x() - 34, plazaY, site.z() - 1,
                19, 15, 2, false, Material.WHITE_TERRACOTTA, 3); // cartographer
        villageHouse(out, site.x() + 34, plazaY, site.z() - 2,
                19, 15, 2, true, Material.YELLOW_TERRACOTTA, 4); // bakery
        villageHouse(out, site.x() - 34, plazaY, site.z() + 18,
                17, 13, 1, true, Material.GREEN_TERRACOTTA, 5);
        villageHouse(out, site.x() + 35, plazaY, site.z() + 18,
                17, 13, 1, false, Material.BROWN_TERRACOTTA, 6);
        stall(out, site.x() - 16, plazaY, site.z() + 12, true);
        stall(out, site.x() + 16, plazaY, site.z() + 12, false);

        // Upper quiet quarter and community hall.
        villageHouse(out, site.x() - 35, upperY, site.z() - 38,
                17, 13, 2, true, Material.PINK_TERRACOTTA, 7);
        villageHouse(out, site.x() + 35, upperY, site.z() - 39,
                17, 13, 2, false, Material.CYAN_TERRACOTTA, 8);
        villageHouse(out, site.x(), upperY, site.z() - 47,
                27, 17, 2, true, Material.WHITE_TERRACOTTA, 9); // hall
        out.add(e(site.x(), upperY, site.z() - 38, Material.LECTERN));
        out.add(e(site.x() - 2, upperY, site.z() - 38, Material.BOOKSHELF));
        out.add(e(site.x() + 2, upperY, site.z() - 38, Material.BOOKSHELF));

        villageGate(out, site.x() + 67, plazaY, site.z() + 4);
        lowWall(out, site, 71, Material.COBBLESTONE_WALL);
        for (int[] tree : new int[][]{{-53,34},{52,31},{-52,-18},{52,-20},{-17,-64},{19,-63}}) {
            villageTree(out, site.x() + tree[0],
                    tree[1] < -22 ? upperY : tree[1] > 20 ? lowerY : plazaY,
                    site.z() + tree[1], 5 + Math.floorMod(tree[0] + tree[1], 3));
        }
        for (int[] lamp : new int[][]{{-20,18},{20,18},{-20,-15},{20,-15},{0,31},{0,-27},{55,4}}) {
            OverworldCampaignTerrain146.lamp(out, site.x() + lamp[0],
                    lamp[1] < -22 ? upperY : lamp[1] > 20 ? lowerY : plazaY,
                    site.z() + lamp[1]);
        }
        report.villageBuildings += 9;
        report.structures += 17;
        return out;
    }

    static List<BlockEdit> residential(Site site, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 15,
                Material.COBBLESTONE, Material.MOSSY_COBBLESTONE);
        well(out, site.x(), y, site.z());
        villageHouse(out, site.x() - 27, y, site.z() - 21, 15, 11, 2,
                true, Material.GREEN_TERRACOTTA, 1);
        villageHouse(out, site.x() + 28, y, site.z() - 19, 13, 11, 2,
                false, Material.WHITE_TERRACOTTA, 2);
        villageHouse(out, site.x() - 31, y, site.z() + 20, 17, 11, 1,
                false, Material.YELLOW_TERRACOTTA, 3);
        villageHouse(out, site.x() + 28, y, site.z() + 25, 15, 13, 1,
                true, Material.LIGHT_GRAY_TERRACOTTA, 4);
        villageHouse(out, site.x(), y + 1, site.z() + 35, 13, 9, 2,
                true, Material.BROWN_TERRACOTTA, 5);
        garden(out, site.x() - 3, y, site.z() - 34, 19, 13);
        lowWall(out, site, 48, Material.COBBLESTONE_WALL);
        spawnerShrines(out, site, y, Material.EMERALD_BLOCK);
        rewardPedestal(out, site.x() + 6, y, site.z() + 3, Material.EMERALD_BLOCK);
        barricade(out, site.x(), y, site.z() - 46, true);
        lampsAround(out, site.x(), y, site.z(), 32, 8);
        report.structures += 11;
        return out;
    }

    static List<BlockEdit> commercial(Site site, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 19,
                Material.POLISHED_ANDESITE, Material.MUD_BRICKS);
        market(out, site.x(), y, site.z());
        villageHouse(out, site.x() - 31, y, site.z() - 19, 21, 13, 2,
                true, Material.ORANGE_TERRACOTTA, 11);
        villageHouse(out, site.x() + 31, y, site.z() - 15, 19, 15, 2,
                false, Material.YELLOW_TERRACOTTA, 12);
        villageHouse(out, site.x() - 29, y, site.z() + 25, 15, 13, 2,
                true, Material.WHITE_TERRACOTTA, 13);
        villageHouse(out, site.x() + 30, y, site.z() + 27, 17, 11, 2,
                false, Material.RED_TERRACOTTA, 14);
        tower(out, site.x(), y, site.z() + 39, 5, 14, Material.CUT_COPPER);
        lowWall(out, site, 50, Material.STONE_BRICK_WALL);
        spawnerShrines(out, site, y, Material.GOLD_BLOCK);
        rewardPedestal(out, site.x() + 6, y, site.z() + 3, Material.GOLD_BLOCK);
        barricade(out, site.x() - 45, y, site.z(), false);
        for (int[] cart : new int[][]{{-15,24},{17,-25},{37,7}}) {
            out.add(e(site.x() + cart[0], y, site.z() + cart[1], Material.BARREL));
            out.add(e(site.x() + cart[0] + 1, y, site.z() + cart[1], Material.CHEST));
            out.add(e(site.x() + cart[0] - 1, y, site.z() + cart[1], Material.HAY_BLOCK));
        }
        lampsAround(out, site.x(), y, site.z(), 36, 10);
        report.structures += 12;
        return out;
    }

    static List<BlockEdit> military(Site site, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 22,
                Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS);
        house(out, site.x(), y, site.z() + 5, 27, 19, 3,
                true, Material.GRAY_TERRACOTTA);
        for (int[] point : new int[][]{{-39,-39},{39,-39},{-39,39},{39,39}}) {
            tower(out, site.x() + point[0], y, site.z() + point[1], 6, 18,
                    Material.DEEPSLATE_TILES);
        }
        fortWall(out, site.x(), y, site.z(), 48, 7, true);
        trainingYard(out, site.x() - 24, y, site.z() + 27);
        house(out, site.x() + 25, y, site.z() + 25, 17, 11, 1,
                false, Material.GRAY_TERRACOTTA); // armory
        house(out, site.x() - 25, y + 2, site.z() - 23, 15, 11, 1,
                true, Material.BROWN_TERRACOTTA); // supply loft
        spawnerShrines(out, site, y, Material.AMETHYST_BLOCK);
        rewardPedestal(out, site.x() + 6, y, site.z() + 3, Material.AMETHYST_BLOCK);
        barricade(out, site.x(), y, site.z() + 48, true);
        lampsAround(out, site.x(), y, site.z(), 31, 8);
        report.structures += 11;
        return out;
    }

    static List<BlockEdit> citadel(Site site, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 35,
                Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS);
        house(out, site.x(), y, site.z(), 43, 27, 3,
                true, Material.GRAY_TERRACOTTA);
        tower(out, site.x() - 40, y, site.z() + 24, 7, 22, Material.DEEPSLATE_TILES);
        tower(out, site.x() + 40, y, site.z() + 24, 7, 22, Material.DEEPSLATE_TILES);
        tower(out, site.x() - 36, y, site.z() - 31, 6, 18, Material.DARK_OAK_PLANKS);
        tower(out, site.x() + 36, y, site.z() - 31, 6, 18, Material.DARK_OAK_PLANKS);
        fortWall(out, site.x(), y, site.z(), 55, 8, true);
        gateFrame(out, site.x(), y, site.z() + 55, 8, 12);
        straightRoad(out, site.x(), y, site.z() + 35,
                site.x(), site.z() + 57, 7, Material.POLISHED_ANDESITE);
        report.structures += 7;
        return out;
    }

    static List<BlockEdit> bossArena(Site site, Location outerAltar, Location invocationAltar,
                                     Location ritualGate, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 45,
                Material.DEEPSLATE_TILES, Material.MOSSY_STONE_BRICKS);
        // One continuous wall with a single northern entrance.
        for (int radius = 48; radius <= 51; radius++) {
            circleWall(out, site.x(), y, site.z(), radius, 9,
                    radius % 2 == 0 ? Material.DEEPSLATE_BRICKS : Material.MOSSY_STONE_BRICKS);
        }
        monumentalArenaEntrance(out, ritualGate.getBlockX(), ritualGate.getBlockY(), ritualGate.getBlockZ());
        ritualSeal(out, ritualGate.getBlockX(), ritualGate.getBlockY(), ritualGate.getBlockZ());
        outerRitualAltar(out, outerAltar.getBlockX(), outerAltar.getBlockY(), outerAltar.getBlockZ());
        invocationAltar(out, invocationAltar.getBlockX(), invocationAltar.getBlockY(), invocationAltar.getBlockZ());
        terraces(out, site.x(), y, site.z());
        lampsAround(out, site.x(), y, site.z(), 39, 12);
        // Broken rear pylons keep the arena monumental without four corner towers.
        for (int[] rear : new int[][]{{-31,34},{31,34}}) {
            tower(out, site.x() + rear[0], y, site.z() + rear[1], 4, 11, Material.DEEPSLATE_TILES);
        }
        report.structures += 8;
        return out;
    }

    static List<BlockEdit> portal(Site site, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 20,
                Material.POLISHED_ANDESITE, Material.CHISELED_STONE_BRICKS);
        for (int x = -13; x <= 13; x++) for (int z = -10; z <= 10; z++) {
            if (Math.abs(x) != 13 && Math.abs(z) != 10) continue;
            for (int yy = 0; yy <= 7; yy++) {
                if (z == -10 && Math.abs(x) <= 3 && yy <= 5) continue;
                out.add(e(site.x() + x, y + yy, site.z() + z,
                        yy == 7 ? Material.CHISELED_STONE_BRICKS
                                : ((x + z + yy) & 7) == 0
                                ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
            }
        }
        gateFrame(out, site.x(), y, site.z() - 10, 5, 9);
        for (int x : new int[]{-9,9}) for (int z : new int[]{-6,6}) {
            pillar(out, site.x() + x, y, site.z() + z, 8,
                    Material.POLISHED_ANDESITE, Material.LANTERN);
        }
        report.structures += 2;
        return out;
    }

    static List<BlockEdit> decorations(Site village, Site residential, Site commercial,
                                       Site military, Site citadel, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        crystals(out, residential, 14);
        crystals(out, commercial, 18);
        crystals(out, military, 24);
        crystals(out, citadel, 28);
        for (int i = 0; i < 18; i++) {
            double angle = Math.toRadians(i * 137);
            int radius = 75 + Math.floorMod(i * 17, 18);
            int x = village.x() + (int) Math.round(Math.cos(angle) * radius);
            int z = village.z() + (int) Math.round(Math.sin(angle) * radius);
            int y = village.baseY() + 1;
            if (i % 3 == 0) villageTree(out, x, y, z, 4 + i % 3);
            else {
                out.add(e(x, y, z, Material.AZALEA));
                out.add(e(x + 1, y, z, Material.MOSS_CARPET));
            }
        }
        report.decorations += 102;
        return out;
    }
}
