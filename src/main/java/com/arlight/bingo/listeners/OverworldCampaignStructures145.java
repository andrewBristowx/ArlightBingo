package com.arlight.bingo.listeners;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

import static com.arlight.bingo.listeners.OverworldCampaignModel145.*;
import static com.arlight.bingo.listeners.OverworldCampaignArchitecture145.*;

final class OverworldCampaignStructures145 {
    private OverworldCampaignStructures145() { }

    static List<BlockEdit> residential(Site site, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 14, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE);
        well(out, site.x(), y, site.z());
        house(out, site.x() - 27, y, site.z() - 21, 15, 11, 2, true, Material.GREEN_TERRACOTTA);
        house(out, site.x() + 28, y, site.z() - 19, 13, 11, 2, false, Material.WHITE_TERRACOTTA);
        house(out, site.x() - 31, y, site.z() + 20, 17, 11, 1, false, Material.YELLOW_TERRACOTTA);
        house(out, site.x() + 28, y, site.z() + 25, 15, 13, 1, true, Material.LIGHT_GRAY_TERRACOTTA);
        house(out, site.x(), y, site.z() + 35, 13, 9, 2, true, Material.BROWN_TERRACOTTA);
        garden(out, site.x() - 3, y, site.z() - 34, 17, 11);
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
        circleFloor(out, site.x(), y - 1, site.z(), 17, Material.POLISHED_ANDESITE, Material.MUD_BRICKS);
        market(out, site.x(), y, site.z());
        house(out, site.x() - 31, y, site.z() - 19, 21, 13, 2, true, Material.ORANGE_TERRACOTTA);
        house(out, site.x() + 31, y, site.z() - 15, 19, 15, 2, false, Material.YELLOW_TERRACOTTA);
        house(out, site.x() - 29, y, site.z() + 25, 15, 13, 2, true, Material.WHITE_TERRACOTTA);
        house(out, site.x() + 30, y, site.z() + 27, 17, 11, 2, false, Material.RED_TERRACOTTA);
        tower(out, site.x(), y, site.z() + 39, 5, 14, Material.CUT_COPPER);
        lowWall(out, site, 50, Material.STONE_BRICK_WALL);
        spawnerShrines(out, site, y, Material.GOLD_BLOCK);
        rewardPedestal(out, site.x() + 6, y, site.z() + 3, Material.GOLD_BLOCK);
        barricade(out, site.x() - 45, y, site.z(), false);
        lampsAround(out, site.x(), y, site.z(), 35, 10);
        report.structures += 10;
        return out;
    }

    static List<BlockEdit> military(Site site, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 21, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS);
        house(out, site.x(), y, site.z() + 5, 25, 19, 3, true, Material.GRAY_TERRACOTTA);
        for (int sx : new int[]{-39, 39}) for (int sz : new int[]{-39, 39})
            tower(out, site.x() + sx, y, site.z() + sz, 6, 18, Material.DEEPSLATE_TILES);
        fortWall(out, site.x(), y, site.z(), 48, 7, true);
        trainingYard(out, site.x() - 24, y, site.z() + 27);
        spawnerShrines(out, site, y, Material.AMETHYST_BLOCK);
        rewardPedestal(out, site.x() + 6, y, site.z() + 3, Material.AMETHYST_BLOCK);
        barricade(out, site.x(), y, site.z() + 48, true);
        lampsAround(out, site.x(), y, site.z(), 31, 8);
        report.structures += 9;
        return out;
    }

    static List<BlockEdit> citadel(Site site, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 34, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS);
        house(out, site.x(), y, site.z(), 43, 27, 3, true, Material.GRAY_TERRACOTTA);
        tower(out, site.x() - 40, y, site.z() + 24, 7, 22, Material.DEEPSLATE_TILES);
        tower(out, site.x() + 40, y, site.z() + 24, 7, 22, Material.DEEPSLATE_TILES);
        tower(out, site.x() - 36, y, site.z() - 31, 6, 18, Material.DARK_OAK_PLANKS);
        tower(out, site.x() + 36, y, site.z() - 31, 6, 18, Material.DARK_OAK_PLANKS);
        fortWall(out, site.x(), y, site.z(), 55, 8, true);
        gateFrame(out, site.x(), y, site.z() + 88, 10, 13);
        straightRoad(out, site.x(), y, site.z() + 35, site.x(), site.z() + 87, 7, Material.POLISHED_ANDESITE);
        report.structures += 7;
        return out;
    }

    static List<BlockEdit> bossArena(Site site, int altarY, int altarZ, int gateY, int gateZ, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 45, Material.DEEPSLATE_TILES, Material.MOSSY_STONE_BRICKS);
        for (int radius = 47; radius <= 51; radius++)
            circleWall(out, site.x(), y, site.z(), radius, 8,
                    radius % 2 == 0 ? Material.DEEPSLATE_BRICKS : Material.MOSSY_STONE_BRICKS);
        for (int[] point : new int[][]{{-38,-38},{38,-38},{-38,38},{38,38}})
            tower(out, site.x() + point[0], y, site.z() + point[1], 6, 20, Material.DARK_OAK_PLANKS);
        terraces(out, site.x(), y, site.z());
        ritualAltar(out, site.x(), y, altarZ);
        gateFrame(out, site.x(), gateY, gateZ, 7, 11);
        ritualSeal(out, site.x(), gateY, gateZ);
        bossDais(out, site.x(), y, site.z());
        lampsAround(out, site.x(), y, site.z(), 39, 12);
        report.structures += 9;
        return out;
    }

    static List<BlockEdit> portal(Site site, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 20, Material.POLISHED_ANDESITE, Material.CHISELED_STONE_BRICKS);
        for (int x = -13; x <= 13; x++) for (int z = -10; z <= 10; z++) {
            if (Math.abs(x) != 13 && Math.abs(z) != 10) continue;
            for (int yy = 0; yy <= 7; yy++) {
                if (z == -10 && Math.abs(x) <= 3 && yy <= 5) continue;
                out.add(e(site.x() + x, y + yy, site.z() + z,
                        yy == 7 ? Material.CHISELED_STONE_BRICKS
                                : ((x + z + yy) & 7) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
            }
        }
        gateFrame(out, site.x(), y, site.z() - 10, 5, 9);
        for (int x : new int[]{-9,9}) for (int z : new int[]{-6,6})
            pillar(out, site.x() + x, y, site.z() + z, 8, Material.POLISHED_ANDESITE, Material.LANTERN);
        report.structures += 2;
        return out;
    }

    static List<BlockEdit> decorations(Site residential, Site commercial, Site military, Site citadel, Report report) {
        List<BlockEdit> out = new ArrayList<>();
        crystals(out, residential, 18);
        crystals(out, commercial, 22);
        crystals(out, military, 28);
        crystals(out, citadel, 34);
        report.decorations += 102;
        return out;
    }
}
