package com.arlight.bingo.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

import static com.arlight.bingo.listeners.OverworldCampaignModel146.*;
import static com.arlight.bingo.listeners.OverworldCampaignArchitecture148.*;
import static com.arlight.bingo.listeners.OverworldCampaignAudit148.*;

final class OverworldCampaignStructures148 {
    private OverworldCampaignStructures148() { }

    static List<BlockEdit> village(World world, Site site, Report report, Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        int lowerY = site.baseY() + 1;
        int plazaY = site.baseY() + 3;
        int upperY = site.baseY() + 5;

        // Streets are written first. Houses then preserve their complete envelopes and doors.
        villageStreet(out, registry, site.x(), lowerY, site.z() + 67,
                site.x(), plazaY, site.z() + 17, 5);
        // Split the central avenue around the fountain and then climb toward the hall.
        villageStreet(out, registry, site.x(), plazaY, site.z() + 15,
                site.x(), plazaY, site.z() + 9, 5);
        villageStreet(out, registry, site.x(), plazaY, site.z() + 9,
                site.x() - 12, plazaY, site.z() + 9, 5);
        villageStreet(out, registry, site.x() - 12, plazaY, site.z() + 9,
                site.x() - 12, plazaY, site.z() - 11, 5);
        villageStreet(out, registry, site.x() - 12, plazaY, site.z() - 11,
                site.x(), plazaY, site.z() - 11, 5);
        villageStreet(out, registry, site.x(), plazaY, site.z() - 11,
                site.x(), upperY, site.z() - 24, 5);
        villageStreet(out, registry, site.x(), upperY, site.z() - 24,
                site.x(), upperY, site.z() - 36, 5);
        // The old east-west avenue crossed both civic houses and the fountain. This U-shaped
        // public street uses the narrow gap between the civic and southern homes instead.
        villageStreet(out, registry, site.x() - 54, plazaY, site.z(),
                site.x() - 49, plazaY, site.z() + 9, 5);
        villageStreet(out, registry, site.x() - 49, plazaY, site.z() + 9,
                site.x() - 17, plazaY, site.z() + 9, 5);
        villageStreet(out, registry, site.x() - 17, plazaY, site.z() + 9,
                site.x() - 17, plazaY, site.z() - 11, 5);
        villageStreet(out, registry, site.x() - 17, plazaY, site.z() - 11,
                site.x() + 17, plazaY, site.z() - 11, 5);
        villageStreet(out, registry, site.x() + 17, plazaY, site.z() - 11,
                site.x() + 17, plazaY, site.z() + 9, 5);
        villageStreet(out, registry, site.x() + 17, plazaY, site.z() + 9,
                site.x() + 49, plazaY, site.z() + 9, 5);
        villageStreet(out, registry, site.x() + 49, plazaY, site.z() + 9,
                site.x() + 66, plazaY, site.z(), 5);
        // Every front path reaches this public network; none ends as a decorative stub.
        villageStreet(out, registry, site.x() - 15, lowerY, site.z() + 43,
                site.x(), lowerY, site.z() + 43, 3);
        villageStreet(out, registry, site.x(), lowerY, site.z() + 42,
                site.x() + 16, lowerY, site.z() + 42, 3);
        villageStreet(out, registry, site.x() - 44, lowerY, site.z() + 61,
                site.x(), lowerY, site.z() + 61, 3);
        villageStreet(out, registry, site.x() - 21, plazaY, site.z() + 18,
                site.x(), plazaY, site.z() + 18, 3);
        villageStreet(out, registry, site.x(), plazaY, site.z() + 18,
                site.x() + 22, plazaY, site.z() + 18, 3);
        villageStreet(out, registry, site.x() - 22, upperY, site.z() - 38,
                site.x() - 12, upperY, site.z() - 28, 3);
        villageStreet(out, registry, site.x() - 12, upperY, site.z() - 28,
                site.x(), upperY, site.z() - 28, 3);
        villageStreet(out, registry, site.x() + 22, upperY, site.z() - 39,
                site.x() + 12, upperY, site.z() - 29, 3);
        villageStreet(out, registry, site.x() + 12, upperY, site.z() - 29,
                site.x(), upperY, site.z() - 28, 3);
        // The hall connector is already part of the corrected central avenue.
        villageStreet(out, registry, site.x() + 54, plazaY, site.z(),
                site.x() + 72, plazaY, site.z() + 4, 5);
        villageStreet(out, registry, site.x() - 56, lowerY, site.z() + 47,
                site.x() - 45, lowerY, site.z() + 53, 3);
        villageStreet(out, registry, site.x() - 45, lowerY, site.z() + 53,
                site.x() - 44, lowerY, site.z() + 61, 3);
        villageStreet(out, registry, site.x() + 56, upperY, site.z() - 49,
                site.x() + 67, upperY, site.z() - 53, 3);
        villageStreet(out, registry, site.x() + 67, upperY, site.z() - 53,
                site.x() + 67, plazaY, site.z() + 4, 3);
        villagePlaza(out, site.x(), plazaY, site.z());

        // Southern service quarter: one inn, one smithy and a real animal yard, no crop stamps.
        villageHouse(out, world, registry, "village-inn", site.x() - 31, lowerY, site.z() + 43,
                23, 15, 2, true, Material.LIGHT_GRAY_TERRACOTTA, 1, Facing.EAST);
        villageHouse(out, world, registry, "village-smithy", site.x() + 30, lowerY, site.z() + 42,
                19, 15, 1, false, Material.GRAY_TERRACOTTA, 2, Facing.WEST);
        villageHouse(out, world, registry, "village-barn", site.x() - 56, lowerY, site.z() + 61,
                15, 11, 1, true, Material.LIGHT_GRAY_TERRACOTTA, 10, Facing.EAST);
        animalYard(out, site.x() - 56, lowerY, site.z() + 40, 17, 15, Facing.SOUTH);
        out.add(e(site.x() + 39, lowerY, site.z() + 50, Material.BLAST_FURNACE));
        out.add(e(site.x() + 42, lowerY, site.z() + 50, Material.ANVIL));

        // The palette remains neutral and shared; variation comes from form and function.
        villageHouse(out, world, registry, "village-cartographer", site.x() - 34, plazaY, site.z() - 1,
                19, 15, 2, false, Material.WHITE_TERRACOTTA, 3, Facing.EAST);
        villageHouse(out, world, registry, "village-bakery", site.x() + 34, plazaY, site.z() - 2,
                19, 15, 2, true, Material.LIGHT_GRAY_TERRACOTTA, 4, Facing.WEST);
        villageHouse(out, world, registry, "village-home-southwest", site.x() - 34, plazaY, site.z() + 18,
                17, 13, 1, true, Material.GRAY_TERRACOTTA, 5, Facing.EAST);
        villageHouse(out, world, registry, "village-home-southeast", site.x() + 35, plazaY, site.z() + 18,
                17, 13, 1, false, Material.WHITE_TERRACOTTA, 6, Facing.WEST);
        stall(out, site.x() - 16, plazaY, site.z() + 12, true);
        stall(out, site.x() + 16, plazaY, site.z() + 12, false);

        villageHouse(out, world, registry, "village-home-northwest", site.x() - 35, upperY, site.z() - 38,
                17, 13, 2, true, Material.LIGHT_GRAY_TERRACOTTA, 7, Facing.EAST);
        villageHouse(out, world, registry, "village-home-northeast", site.x() + 35, upperY, site.z() - 39,
                17, 13, 2, false, Material.GRAY_TERRACOTTA, 8, Facing.WEST);
        villageHouse(out, world, registry, "village-hall", site.x(), upperY, site.z() - 47,
                27, 17, 2, true, Material.WHITE_TERRACOTTA, 9, Facing.SOUTH);
        out.add(e(site.x() - 8, upperY, site.z() - 44, Material.LECTERN));
        out.add(e(site.x() - 10, upperY, site.z() - 45, Material.BOOKSHELF));
        out.add(e(site.x() - 9, upperY, site.z() - 45, Material.BOOKSHELF));
        out.add(e(site.x() - 8, upperY, site.z() - 45, Material.BOOKSHELF));
        animalYard(out, site.x() + 56, upperY, site.z() - 42, 17, 15, Facing.NORTH);

        villageEntry(out, site.x() + 67, plazaY, site.z() + 4, Facing.EAST);
        for (int[] tree : new int[][]{{-53,34},{52,31},{-52,-18},{52,-20},{-17,-64},{19,-63}}) {
            villageTree(out, site.x() + tree[0],
                    tree[1] < -22 ? upperY : tree[1] > 20 ? lowerY : plazaY,
                    site.z() + tree[1], 5 + Math.floorMod(tree[0] + tree[1], 3));
        }
        for (int[] lamp : new int[][]{{-20,13},{20,18},{-20,-15},{20,-15},{0,31},{0,-27},{55,4}}) {
            placeLampIfClear(out, registry, site.x() + lamp[0],
                    lamp[1] < -22 ? upperY : lamp[1] > 20 ? lowerY : plazaY,
                    site.z() + lamp[1]);
        }
        report.villageBuildings += 10;
        report.structures += 18;
        return out;
    }

    static List<BlockEdit> residential(World world, Site site, Report report, Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 15,
                Material.COBBLESTONE, Material.MOSSY_COBBLESTONE);
        // End at the lodge forecourt; the old endpoint continued through the lodge.
        villageStreet(out, registry, site.x(), y, site.z() - 43, site.x(), y, site.z() + 28, 5);
        villageStreet(out, registry, site.x() - 42, y, site.z(), site.x() + 42, y, site.z(), 5);
        villageStreet(out, registry, site.x() - 16, y, site.z() - 21, site.x(), y, site.z() - 21, 3);
        villageStreet(out, registry, site.x(), y, site.z() - 19, site.x() + 17, y, site.z() - 19, 3);
        villageStreet(out, registry, site.x() - 19, y, site.z() + 20, site.x(), y, site.z() + 20, 3);
        villageStreet(out, registry, site.x(), y, site.z() + 25, site.x() + 17, y, site.z() + 25, 3);
        villageHouse(out, world, registry, "residential-home-northwest", site.x() - 27, y,
                site.z() - 21, 15, 11, 2, true, Material.LIGHT_GRAY_TERRACOTTA,
                21, Facing.EAST);
        villageHouse(out, world, registry, "residential-home-northeast", site.x() + 28, y,
                site.z() - 19, 13, 11, 2, false, Material.WHITE_TERRACOTTA,
                22, Facing.WEST);
        villageHouse(out, world, registry, "residential-home-southwest", site.x() - 31, y,
                site.z() + 20, 17, 11, 1, false, Material.GRAY_TERRACOTTA,
                23, Facing.EAST);
        villageHouse(out, world, registry, "residential-home-southeast", site.x() + 28, y,
                site.z() + 25, 15, 13, 1, true, Material.LIGHT_GRAY_TERRACOTTA,
                24, Facing.WEST);
        villageHouse(out, world, registry, "residential-lodge", site.x(), y,
                site.z() + 35, 13, 9, 2, true, Material.WHITE_TERRACOTTA,
                25, Facing.NORTH);
        serviceYard(out, site.x() - 8, y, site.z() - 35, 19, 13, true);
        occupationCamp(out, site.x() + 25, y, site.z() - 34, false);
        spawnerShrines(out, site, y, Material.EMERALD_BLOCK);
        rewardPedestal(out, site.x() + 6, y, site.z() + 3, Material.EMERALD_BLOCK);
        barricade(out, site.x(), y, site.z() - 46, true);
        lampsAround(out, registry, site.x(), y, site.z(), 32, 8);
        report.structures += 11;
        return out;
    }

    static List<BlockEdit> commercial(World world, Site site, Report report, Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 19,
                Material.POLISHED_ANDESITE, Material.MUD_BRICKS);
        // Route both public axes around the occupied market stalls.
        villageStreet(out, registry, site.x(), y, site.z() - 48,
                site.x(), y, site.z() - 12, 5);
        villageStreet(out, registry, site.x(), y, site.z() - 12,
                site.x() + 17, y, site.z() - 12, 5);
        villageStreet(out, registry, site.x() + 17, y, site.z() - 12,
                site.x() + 17, y, site.z() + 12, 5);
        villageStreet(out, registry, site.x() + 17, y, site.z() + 12,
                site.x(), y, site.z() + 12, 5);
        villageStreet(out, registry, site.x(), y, site.z() + 12,
                site.x(), y, site.z() + 31, 5);
        villageStreet(out, registry, site.x() - 45, y, site.z() + 5,
                site.x() - 16, y, site.z() + 5, 5);
        villageStreet(out, registry, site.x() - 16, y, site.z() + 5,
                site.x() - 16, y, site.z() - 12, 5);
        villageStreet(out, registry, site.x() - 16, y, site.z() - 12,
                site.x() + 16, y, site.z() - 12, 5);
        villageStreet(out, registry, site.x() + 16, y, site.z() - 12,
                site.x() + 16, y, site.z() + 5, 5);
        villageStreet(out, registry, site.x() + 16, y, site.z() + 5,
                site.x() + 45, y, site.z() + 5, 5);
        villageStreet(out, registry, site.x() - 16, y, site.z() - 19,
                site.x(), y, site.z() - 19, 3);
        villageStreet(out, registry, site.x(), y, site.z() - 15,
                site.x() + 17, y, site.z() - 15, 3);
        villageStreet(out, registry, site.x() - 17, y, site.z() + 25,
                site.x(), y, site.z() + 25, 3);
        villageStreet(out, registry, site.x(), y, site.z() + 27,
                site.x() + 17, y, site.z() + 27, 3);
        market(out, site.x(), y, site.z());
        villageHouse(out, world, registry, "commercial-warehouse", site.x() - 31, y,
                site.z() - 19, 21, 13, 2, true, Material.GRAY_TERRACOTTA,
                31, Facing.EAST);
        villageHouse(out, world, registry, "commercial-inn", site.x() + 31, y,
                site.z() - 15, 19, 15, 2, false, Material.LIGHT_GRAY_TERRACOTTA,
                32, Facing.WEST);
        villageHouse(out, world, registry, "commercial-shop-west", site.x() - 29, y,
                site.z() + 25, 15, 13, 2, true, Material.WHITE_TERRACOTTA,
                33, Facing.EAST);
        villageHouse(out, world, registry, "commercial-shop-east", site.x() + 30, y,
                site.z() + 27, 17, 11, 2, false, Material.LIGHT_GRAY_TERRACOTTA,
                34, Facing.WEST);
        serviceYard(out, site.x(), y, site.z() + 39, 21, 11, true);
        spawnerShrines(out, site, y, Material.GOLD_BLOCK);
        rewardPedestal(out, site.x() + 6, y, site.z() + 3, Material.GOLD_BLOCK);
        barricade(out, site.x() - 45, y, site.z(), false);
        for (int[] cart : new int[][]{{-15,24},{17,-25},{37,7}}) {
            out.add(e(site.x() + cart[0], y, site.z() + cart[1], Material.BARREL));
            out.add(e(site.x() + cart[0] + 1, y, site.z() + cart[1], Material.CHEST));
            out.add(e(site.x() + cart[0] - 1, y, site.z() + cart[1], Material.HAY_BLOCK));
        }
        lampsAround(out, registry, site.x(), y, site.z(), 36, 10);
        report.structures += 12;
        return out;
    }

    static List<BlockEdit> military(World world, Site site, Report report, Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 22,
                Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS);
        // The main military avenue bends west around the barracks instead of cutting it.
        villageStreet(out, registry, site.x(), y, site.z() - 46,
                site.x(), y, site.z() - 8, 7);
        villageStreet(out, registry, site.x(), y, site.z() - 8,
                site.x() - 18, y, site.z() - 8, 7);
        villageStreet(out, registry, site.x() - 18, y, site.z() - 8,
                site.x() - 18, y, site.z() + 18, 7);
        villageStreet(out, registry, site.x() - 18, y, site.z() + 18,
                site.x(), y, site.z() + 18, 7);
        villageStreet(out, registry, site.x(), y, site.z() + 18,
                site.x(), y, site.z() + 51, 7);
        villageStreet(out, registry, site.x(), y, site.z() + 25,
                site.x() + 12, y, site.z() + 25, 3);
        // Horizontal northern branches reach the tower doors without crossing the supply house.
        villageStreet(out, registry, site.x(), y, site.z() - 33,
                site.x() - 39, y, site.z() - 33, 3);
        villageStreet(out, registry, site.x(), y, site.z() - 33,
                site.x() + 39, y, site.z() - 33, 3);
        house(out, world, registry, "military-barracks", site.x(), y, site.z() + 5,
                27, 19, 3, true, Material.GRAY_TERRACOTTA, Facing.SOUTH);
        int towerIndex = 0;
        for (int[] point : new int[][]{{-39,-39},{39,-39},{-39,39},{39,39}}) {
            auditedTower(out, world, registry, "military-tower-" + towerIndex++,
                    site.x() + point[0], y, site.z() + point[1], 6, 18,
                    Material.DEEPSLATE_TILES,
                    point[1] > 0 ? Facing.NORTH : Facing.SOUTH);
        }
        fortWall(out, site.x(), y, site.z(), 48, 7, true);
        wallOpening(out, site.x() - 48, y, site.z() - 23, false, 5, 5);
        OverworldCampaignTerrain148.supportedGateApproach(out, world,
                "military-west-gate", site.x() - 48, y, site.z() - 23,
                -1, 0, 8, 14, 3, Material.MOSSY_COBBLESTONE, registry);
        trainingYard(out, site.x() - 24, y, site.z() + 27);
        house(out, world, registry, "military-armory", site.x() + 25, y, site.z() + 25,
                17, 11, 1, false, Material.LIGHT_GRAY_TERRACOTTA, Facing.WEST);
        house(out, world, registry, "military-supply", site.x() - 25, y + 2, site.z() - 23,
                15, 11, 1, true, Material.GRAY_TERRACOTTA, Facing.EAST);
        // Build this connector after the raised supply-house pad. In 1.48.4 the
        // pad was appended later and could rewrite the exact public-street join.
        villageStreet(out, registry, site.x() - 10, y + 2, site.z() - 23,
                site.x(), y, site.z() - 23, 3);
        spawnerShrines(out, site, y, Material.AMETHYST_BLOCK);
        // The former +6,+3 position was inside the barracks and erased one of its
        // twelve LIGHT blocks. This offset keeps the entire 7x7 pedestal outside.
        rewardPedestal(out, site.x() + 20, y, site.z() + 3, Material.AMETHYST_BLOCK);
        barricade(out, site.x(), y, site.z() + 48, true);
        lampsAround(out, registry, site.x(), y, site.z(), 31, 8);
        report.structures += 11;
        return out;
    }

    static List<BlockEdit> citadel(World world, Site site, Report report, Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        circleFloor(out, site.x(), y - 1, site.z(), 35,
                Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS);
        // The former straight local street crossed the complete footprint of the great hall.
        // 1.48.28 uses the authoritative fortified route registered by the terrain phase instead.
        house(out, world, registry, "citadel-great-hall", site.x(), y, site.z(),
                43, 27, 3, true, Material.GRAY_TERRACOTTA, Facing.SOUTH);
        auditedTower(out, world, registry, "citadel-tower-southwest",
                site.x() - 40, y, site.z() + 24, 7, 22,
                Material.DEEPSLATE_TILES, Facing.NORTH);
        auditedTower(out, world, registry, "citadel-tower-southeast",
                site.x() + 40, y, site.z() + 24, 7, 22,
                Material.DEEPSLATE_TILES, Facing.NORTH);
        auditedTower(out, world, registry, "citadel-tower-northwest",
                site.x() - 36, y, site.z() - 31, 6, 18,
                Material.DARK_OAK_PLANKS, Facing.SOUTH);
        auditedTower(out, world, registry, "citadel-tower-northeast",
                site.x() + 36, y, site.z() - 31, 6, 18,
                Material.DARK_OAK_PLANKS, Facing.SOUTH);
        fortWall(out, site.x(), y, site.z(), 55, 8, true);
        wallOpening(out, site.x(), y, site.z() - 55, true, 6, 6);
        wallOpening(out, site.x() - 55, y, site.z() + 10, false, 6, 6);
        wallOpening(out, site.x() + 55, y, site.z() + 10, false, 6, 6);
        OverworldCampaignTerrain148.supportedGateApproach(out, world,
                "citadel-west-gate", site.x() - 55, y, site.z() + 10,
                -1, 0, 8, 14, 4, Material.POLISHED_ANDESITE, registry);
        OverworldCampaignTerrain148.supportedGateApproach(out, world,
                "citadel-east-gate", site.x() + 55, y, site.z() + 10,
                1, 0, 8, 14, 4, Material.POLISHED_ANDESITE, registry);
        gateFrame(out, world, registry, "citadel-south-gate", site.x(), y,
                site.z() + 55, 9, 14);
        OverworldCampaignTerrain148.supportedGateApproach(out, world,
                "citadel-south-approach", site.x(), y, site.z() + 55,
                0, 1, 8, 14, 4, Material.POLISHED_ANDESITE, registry);
        OverworldCampaignTerrain148.supportedGateApproach(out, world,
                "citadel-north-gate", site.x(), y, site.z() - 55,
                0, -1, 8, 14, 4, Material.POLISHED_ANDESITE, registry);
        straightRoad(out, site.x(), y, site.z() + 33,
                site.x(), site.z() + 60, 9, Material.POLISHED_ANDESITE);
        straightRoad(out, site.x() - 12, y, site.z() + 52,
                site.x() + 12, site.z() + 52, 5, Material.MOSSY_STONE_BRICKS);
        serviceYard(out, site.x() - 28, y, site.z() + 35, 17, 11, false);
        serviceYard(out, site.x() + 28, y, site.z() + 35, 17, 11, false);
        citadelCourtyardDetails(out, site.x(), y, site.z());
        report.structures += 9;
        return out;
    }

    static List<BlockEdit> bossArena(World world, Site site, Location outerAltar, Location invocationAltar,
                                     Location ritualGate, Report report, Registry registry) {
        List<BlockEdit> out = new ArrayList<>();
        int y = site.baseY() + 1;
        // Build one continuous ceremonial approach before the arena. The entrance,
        // outer altar and inner invocation point therefore share the same walkable axis.
        OverworldCampaignTerrain148.supportedGateApproach(out, world,
                "boss-ritual-interior", ritualGate.getBlockX(), ritualGate.getBlockY(),
                ritualGate.getBlockZ() + 6, 0, 1, 0,
                Math.max(0, invocationAltar.getBlockZ() - ritualGate.getBlockZ() - 6),
                2, Material.POLISHED_ANDESITE, registry);
        circleFloor(out, site.x(), y - 1, site.z(), 45,
                Material.DEEPSLATE_TILES, Material.MOSSY_STONE_BRICKS);
        // One continuous wall with a single northern entrance.
        for (int radius = 48; radius <= 51; radius++) {
            circleWall(out, site.x(), y, site.z(), radius, 9,
                    radius % 2 == 0 ? Material.DEEPSLATE_BRICKS : Material.MOSSY_STONE_BRICKS);
        }
        OverworldCampaignTerrain148.supportedGateApproach(out, world,
                "boss-north-approach", ritualGate.getBlockX(), ritualGate.getBlockY(),
                ritualGate.getBlockZ(), 0, -1, 6, 24,
                BOSS_GATE_CLEAR_HALF_WIDTH,
                Material.POLISHED_DEEPSLATE, registry);
        // The obsolete transverse crossroad used by early revisions is intentionally not
        // generated. It crossed the gate towers and competed with the ceremonial axis.
        monumentalArenaEntrance(out, world, registry, ritualGate.getBlockX(),
                ritualGate.getBlockY(), ritualGate.getBlockZ());
        outerRitualAltar(out, outerAltar.getBlockX(), outerAltar.getBlockY(), outerAltar.getBlockZ());
        invocationAltar(out, invocationAltar.getBlockX(), invocationAltar.getBlockY(), invocationAltar.getBlockZ());
        terraces(out, site.x(), y, site.z());
        arenaFurnishings(out, site.x(), y, site.z());
        // Broken rear pylons keep the arena monumental without four corner towers.
        int rearIndex = 0;
        for (int[] rear : new int[][]{{-31,34},{31,34}}) {
            auditedTower(out, world, registry, "boss-rear-tower-" + rearIndex++,
                    site.x() + rear[0], y, site.z() + rear[1], 4, 11,
                    Material.DEEPSLATE_TILES, Facing.NORTH);
        }
        // Register every tower before choosing lamps so no post can be embedded
        // in a gatehouse or rear-tower entrance.
        lampsAround(out, registry, site.x(), y, site.z(), 39, 12);
        report.structures += 8;
        return out;
    }

    static List<BlockEdit> portal(World world, Site site, Report report, Registry registry) {
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
        OverworldCampaignTerrain148.supportedGateApproach(out, world,
                "portal-north-approach", site.x(), y, site.z() - 10,
                0, -1, 6, 18, 4, Material.POLISHED_ANDESITE, registry);
        gateFrame(out, world, registry, "portal-north-gate", site.x(), y,
                site.z() - 10, 6, 10);
        for (int side = -12; side <= 12; side++) {
            if (Math.abs(side) <= 6) continue;
            out.add(e(site.x() + side, y + 1, site.z() - 10, Material.STONE_BRICK_WALL));
        }
        for (int x : new int[]{-9,9}) for (int z : new int[]{-6,6}) {
            pillar(out, site.x() + x, y, site.z() + z, 8,
                    Material.POLISHED_ANDESITE, Material.LANTERN);
        }
        portalSanctuaryFurnishings(out, site.x(), y, site.z());
        report.structures += 2;
        return out;
    }

    static List<BlockEdit> decorations(World world, Site village, Site residential,
                                       Site commercial, Site military, Site citadel,
                                       Report report) {
        List<BlockEdit> out = new ArrayList<>();
        // Corruption is concentrated at occupied camps and objectives, not scattered as noise.
        corruptionCluster(out, residential.x() - 20, residential.baseY() + 1,
                residential.z() - 10, 7);
        corruptionCluster(out, commercial.x() + 19, commercial.baseY() + 1,
                commercial.z() + 13, 9);
        corruptionCluster(out, military.x(), military.baseY() + 1,
                military.z() + 27, 11);
        corruptionCluster(out, citadel.x() - 28, citadel.baseY() + 1,
                citadel.z() + 35, 8);

        for (int i = 0; i < 16; i++) {
            double angle = Math.toRadians(i * 137);
            int radius = 69 + Math.floorMod(i * 17, 13);
            int x = village.x() + (int) Math.round(Math.cos(angle) * radius);
            int z = village.z() + (int) Math.round(Math.sin(angle) * radius);
            int ground = OverworldCampaignTerrain148.terrainY(world, x, z);
            Material support = world.getBlockAt(x, ground, z).getType();
            if (!support.isSolid() || support == Material.ICE) continue;
            int y = ground + 1;
            if (i % 3 == 0) villageTree(out, x, y, z, 4 + i % 3);
            else {
                out.add(e(x, y, z, Material.AZALEA));
                int adjacentGround = OverworldCampaignTerrain148.terrainY(world, x + 1, z);
                if (world.getBlockAt(x + 1, adjacentGround, z).getType().isSolid())
                    out.add(e(x + 1, adjacentGround + 1, z, Material.MOSS_CARPET));
            }
        }
        report.decorations += 74;
        return out;
    }
}
