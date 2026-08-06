package com.arlight.bingo.dungeon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Segunda pasada arquitectónica basada en principios de ciudades creíbles:
 * parcelas distintas, calles conectadas, edificios con uso reconocible,
 * interiores, ruinas y transición con el terreno. No importa mapas ajenos ni
 * pega schematics completos: genera módulos originales y deterministas.
 */
public final class ReferenceDrivenCityPass {
    private ReferenceDrivenCityPass() { }

    public record Expansion(int minX, int maxX, int minZ, int maxZ,
                            List<Location> checkpoints) { }

    private enum Facing {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);
        final int dx;
        final int dz;
        Facing(int dx, int dz) { this.dx = dx; this.dz = dz; }
        BlockFace blockFace() {
            return switch (this) {
                case NORTH -> BlockFace.NORTH;
                case EAST -> BlockFace.EAST;
                case SOUTH -> BlockFace.SOUTH;
                case WEST -> BlockFace.WEST;
            };
        }
        Facing left() { return values()[(ordinal() + 3) % 4]; }
        Facing right() { return values()[(ordinal() + 1) % 4]; }
        Facing opposite() { return values()[(ordinal() + 2) % 4]; }
    }

    private enum OverworldUse { INN, BLACKSMITH, CHAPEL, WAREHOUSE, HOME, APOTHECARY, GUARDHOUSE, LIBRARY }
    private enum NetherUse { BARRACKS, FOUNDRY, MARKET, VAULT, BEAST_PENS, TEMPLE }

    public static Expansion buildOverworld(JavaPlugin plugin, Location base,
                                           AdaptiveDungeonLootManager loot,
                                           DungeonMinionSpawnerManager spawners) {
        if (base == null || base.getWorld() == null
                || !plugin.getConfig().getBoolean("reference-city-pass.overworld.enabled", true)) {
            return new Expansion(-102, 102, -128, 78, List.of());
        }
        Random random = new Random(base.getWorld().getSeed() ^ 0x4F564552574F524CL);
        List<Location> checkpoints = new ArrayList<>();

        // La plantilla 1.43.0 ya contiene pueblos completos. Esta pasada de partida no vuelve
        // a vaciar parcelas ni a pegar catorce manzanas: solo añade huellas de la ocupación.
        int[][] districts = {
                {-118,-126},{-78,-132},{-38,-130},{38,-130},{78,-132},{118,-126},
                {-126,-72},{126,-72},{-132,-8},{132,-8},
                {-126,62},{126,62},{-78,96},{78,96}
        };
        for (int i = 0; i < districts.length; i++) {
            Location desired = base.clone().add(districts[i][0], 0, districts[i][1]);
            Location center = terrainAnchored(desired, base.getBlockY(), 18).add(0, 1, 0);
            buildOccupiedStreetDetail(center, i, random);
            checkpoints.add(centered(center));
        }

        // El alcantarillado se conserva bajo el núcleo, pero los barrios externos ya no
        // dependen de una plataforma rectangular común. La red de calles orgánicas
        // pertenece a la plantilla y esta pasada de partida nunca vuelve a cortarla.
        buildSewerNetwork(base, loot, spawners);

        return new Expansion(-164, 164, -176, 132, List.copyOf(checkpoints));
    }

    public static Expansion buildNether(JavaPlugin plugin, Location base,
                                        AdaptiveDungeonLootManager loot,
                                        DungeonMinionSpawnerManager spawners) {
        if (base == null || base.getWorld() == null
                || !plugin.getConfig().getBoolean("reference-city-pass.nether.enabled", true)) {
            return new Expansion(-88, 88, -114, 68, List.of());
        }
        Random random = new Random(base.getWorld().getSeed() ^ 0x4E45544845524349L);
        List<Location> checkpoints = new ArrayList<>();
        int[][] centers = {
                {-88,-112},{0,-118},{88,-112},
                {-96,-55},{96,-55},{-104,18},{104,18},
                {-88,78},{0,88},{88,78}
        };
        NetherUse[] uses = {
                NetherUse.BARRACKS, NetherUse.MARKET, NetherUse.BARRACKS,
                NetherUse.FOUNDRY, NetherUse.FOUNDRY, NetherUse.BEAST_PENS,
                NetherUse.VAULT, NetherUse.TEMPLE, NetherUse.MARKET, NetherUse.VAULT
        };
        for (int i = 0; i < centers.length; i++) {
            int level = i < 3 ? 0 : (i < 7 ? 5 : 10);
            Location center = base.clone().add(centers[i][0], 1 + level, centers[i][1]);
            loot.unregisterInBox(center, 21, 20, -12, 36);
            spawners.unregisterInBox(center, 21, 20, -12, 36);
            buildNetherDistrict(center, uses[i], Facing.values()[(i + 1) % 4], random, loot);
            connectNetherTerrace(base.clone().add(centers[i][0], 1, centers[i][1]), center, i % 2 == 0);
            checkpoints.add(centered(center));
        }

        // Barrios verticales incrustados en la caverna, unidos por puentes y soportes.
        Location westFoundry = base.clone().add(-105, 14, -32);
        Location eastTemple = base.clone().add(105, 22, 6);
        buildCliffFoundry(westFoundry, Facing.EAST, random, loot, spawners);
        buildSuspendedTemple(eastTemple, Facing.WEST, random, loot, spawners);
        buildNetherBridge(base.clone().add(-82, 9, -32), westFoundry.clone().add(13, 0, 0), Facing.WEST, 4);
        buildNetherBridge(base.clone().add(82, 13, 6), eastTemple.clone().add(-13, 0, 0), Facing.EAST, 4);
        checkpoints.add(centered(westFoundry));
        checkpoints.add(centered(eastTemple));

        buildLavaAqueduct(base.clone().add(-110, 28, -78), base.clone().add(110, 28, -78));
        buildHangingWatch(base.clone().add(-106, 34, 48), loot, spawners);
        buildHangingWatch(base.clone().add(106, 30, -58), loot, spawners);

        return new Expansion(-174, 174, -164, 126, List.copyOf(checkpoints));
    }

    /** Detalles adicionales del End; la isla base y los edificios principales los genera EndCampaignStructure. */
    public static Expansion buildEnd(JavaPlugin plugin, Location city, Location dragonIsland,
                                     AdaptiveDungeonLootManager loot,
                                     DungeonMinionSpawnerManager spawners) {
        if (city == null || city.getWorld() == null
                || !plugin.getConfig().getBoolean("reference-city-pass.end.enabled", true)) {
            return new Expansion(-112, 112, -183, 151, List.of());
        }
        Random random = new Random(city.getWorld().getSeed() ^ 0x454E44434954594CL);
        List<Location> checkpoints = new ArrayList<>();

        int[][] houses = {
                {-72,-66},{-36,-72},{36,-72},{72,-66},
                {-78,-22},{78,-22},{-76,32},{76,32},
                {-56,72},{0,82},{56,72},{-92,68},{92,68}
        };
        for (int i = 0; i < houses.length; i++) {
            Location at = city.clone().add(houses[i][0], 1 + (i % 3), houses[i][1]);
            loot.unregisterInBox(at, 13, 14, -10, 30);
            spawners.unregisterInBox(at, 13, 14, -10, 30);
            buildEndResidence(at, 7 + i % 3, 8 + (i + 1) % 3, 13 + i % 6,
                    Facing.values()[i % 4], i % 3 == 0, random, loot);
            checkpoints.add(centered(at));
        }

        buildEndCathedral(city.clone().add(0, 1, 8), Facing.SOUTH, loot, spawners);
        buildEndCliffTower(city.clone().add(-61, 7, 43), 7, 28, loot, spawners);
        buildEndCliffTower(city.clone().add(61, 12, 43), 6, 24, loot, spawners);
        buildEndSkyBridge(city.clone().add(-54, 22, 43), city.clone().add(-18, 22, 22));
        buildEndSkyBridge(city.clone().add(54, 24, 43), city.clone().add(18, 24, 22));

        // La arena deja de ser un disco vacío: graderíos rotos, corredores y altares laterales.
        buildDragonArenaDistrict(dragonIsland, loot, spawners, random);
        return new Expansion(-174, 174, -238, 206, List.copyOf(checkpoints));
    }

    // ---------------------------------------------------------------------
    // OVERWORLD
    // ---------------------------------------------------------------------

    private static void buildOccupiedStreetDetail(Location center, int index, Random random) {
        World world = center.getWorld();
        if (world == null) return;
        Facing direction = Facing.values()[index % Facing.values().length];

        // Barricada corta que sigue la calle existente sin despejar edificios ni interiores.
        for (int offset = -3; offset <= 3; offset++) {
            Location at = local(center, direction.left(), offset, direction, 0);
            int y = world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ());
            Material material = (offset & 1) == 0 ? Material.DARK_OAK_FENCE : Material.SPRUCE_PLANKS;
            world.getBlockAt(at.getBlockX(), y, at.getBlockZ()).setType(material, false);
            if (Math.abs(offset) == 2) {
                world.getBlockAt(at.getBlockX(), y + 1, at.getBlockZ()).setType(Material.IRON_BARS, false);
            }
        }

        Location camp = local(center, direction.right(), 5, direction.opposite(), 4);
        int campY = world.getHighestBlockYAt(camp.getBlockX(), camp.getBlockZ());
        world.getBlockAt(camp.getBlockX(), campY, camp.getBlockZ()).setType(
                index % 3 == 0 ? Material.SOUL_CAMPFIRE : Material.CAMPFIRE, false);
        world.getBlockAt(camp.getBlockX() + 1, campY, camp.getBlockZ()).setType(Material.BARREL, false);
        world.getBlockAt(camp.getBlockX() - 1, campY, camp.getBlockZ()).setType(Material.COBWEB, false);

        Location lamp = local(center, direction.left(), 6, direction, 5);
        int lampY = world.getHighestBlockYAt(lamp.getBlockX(), lamp.getBlockZ());
        world.getBlockAt(lamp.getBlockX(), lampY, lamp.getBlockZ()).setType(Material.COBBLESTONE_WALL, false);
        world.getBlockAt(lamp.getBlockX(), lampY + 1, lamp.getBlockZ()).setType(Material.DARK_OAK_FENCE, false);
        world.getBlockAt(lamp.getBlockX(), lampY + 2, lamp.getBlockZ()).setType(
                random.nextBoolean() ? Material.SOUL_LANTERN : Material.LANTERN, false);
    }

    private static void rebuildOverworldBlock(Location c, OverworldUse use, Facing facing,
                                              boolean ruined, Random random,
                                              AdaptiveDungeonLootManager loot) {
        clearBox(c, 17, 18, 0, 24);
        for (int x = -16; x <= 16; x++) for (int z = -15; z <= 15; z++) {
            Material floor = Math.floorMod(x * 17 + z * 31, 19) == 0
                    ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE;
            c.clone().add(x, -1, z).getBlock().setType(floor, false);
        }
        // Dos edificios de tamaños diferentes y un callejón interior.
        int mainW = use == OverworldUse.CHAPEL || use == OverworldUse.WAREHOUSE ? 10 : 9;
        int mainD = use == OverworldUse.INN || use == OverworldUse.CHAPEL ? 11 : 10;
        Location main = local(c, facing.left(), 7, facing.opposite(), 1);
        buildOverworldBuilding(main, mainW, mainD, 14 + random.nextInt(5), facing,
                use, ruined, random, loot);

        OverworldUse annexUse = switch (use) {
            case CHAPEL -> OverworldUse.LIBRARY;
            case INN -> OverworldUse.HOME;
            case BLACKSMITH -> OverworldUse.WAREHOUSE;
            case WAREHOUSE -> OverworldUse.GUARDHOUSE;
            default -> OverworldUse.HOME;
        };
        Location annex = local(c, facing.right(), 9, facing, 5);
        buildOverworldBuilding(annex, 6, 7, 10 + random.nextInt(4), facing.opposite(),
                annexUse, ruined && random.nextBoolean(), random, loot);
        buildCourtyard(c, facing, use, random);
    }

    private static void buildOverworldBuilding(Location c, int halfW, int halfD, int h,
                                               Facing front, OverworldUse use, boolean ruined,
                                               Random random, AdaptiveDungeonLootManager loot) {
        Material wall = switch (use) {
            case CHAPEL, GUARDHOUSE -> Material.STONE_BRICKS;
            case BLACKSMITH, WAREHOUSE -> Material.TUFF_BRICKS;
            default -> Material.SPRUCE_PLANKS;
        };
        Material frame = switch (use) {
            case CHAPEL, GUARDHOUSE -> Material.MOSSY_STONE_BRICKS;
            default -> Material.DARK_OAK_LOG;
        };
        Material floor = use == OverworldUse.BLACKSMITH ? Material.POLISHED_ANDESITE : Material.OAK_PLANKS;
        Material roof = use == OverworldUse.CHAPEL ? Material.DEEPSLATE_TILES : Material.DARK_OAK_PLANKS;

        prepareFoundation(c, halfW, halfD, Material.COBBLESTONE, 18);
        for (int x = -halfW; x <= halfW; x++) for (int z = -halfD; z <= halfD; z++) {
            c.clone().add(x, 0, z).getBlock().setType(floor, false);
            for (int y = 1; y <= h; y++) {
                boolean edge = Math.abs(x) == halfW || Math.abs(z) == halfD;
                Material material = Material.AIR;
                if (edge) {
                    boolean timber = (Math.abs(x) == halfW && Math.floorMod(z + halfD, 4) == 0)
                            || (Math.abs(z) == halfD && Math.floorMod(x + halfW, 4) == 0)
                            || y == 1 || y == h;
                    material = timber ? frame : wall;
                    if (y == 3 || y == 7) material = wall == Material.SPRUCE_PLANKS
                            ? Material.STRIPPED_SPRUCE_LOG : frame;
                    if ((y == 3 || y == 7) && isWindowPosition(x, z, halfW, halfD, front)) {
                        material = Material.GLASS_PANE;
                    }
                    if (ruined && y > 5 && Math.floorMod(x * 19 + z * 23 + y * 7, 29) < 3) {
                        material = Material.AIR;
                    }
                }
                c.clone().add(x, y, z).getBlock().setType(material, false);
            }
        }
        carveFrontDoor(c, halfW, halfD, front, 3, 4);
        int middle = Math.min(h - 3, 5);
        if (h >= 9) {
            for (int x = -halfW + 1; x <= halfW - 1; x++) for (int z = -halfD + 1; z <= halfD - 1; z++) {
                if (Math.floorMod(x + z, 11) != 0) c.clone().add(x, middle, z).getBlock().setType(floor, false);
            }
            buildInteriorStair(c, halfW, halfD, middle, front.right(), roof == Material.DARK_OAK_PLANKS
                    ? Material.DARK_OAK_STAIRS : Material.STONE_BRICK_STAIRS);
        }
        buildGabledRoof(c, halfW, halfD, h + 1, front, roof, ruined);
        furnishOverworld(c, halfW, halfD, middle, front, use, loot);
        addExteriorDetails(c, halfW, halfD, front, use);
    }

    private static boolean isWindowPosition(int x, int z, int halfW, int halfD, Facing front) {
        if (Math.abs(x) == halfW && Math.abs(z) < halfD - 1) return Math.floorMod(z, 4) == 0;
        if (Math.abs(z) == halfD && Math.abs(x) < halfW - 1) {
            if ((front == Facing.NORTH && z == -halfD) || (front == Facing.SOUTH && z == halfD)) {
                return Math.abs(x) >= 2 && Math.floorMod(x, 4) == 0;
            }
            return Math.floorMod(x, 4) == 0;
        }
        return false;
    }

    private static void buildGabledRoof(Location c, int halfW, int halfD, int y,
                                        Facing front, Material roof, boolean ruined) {
        boolean ridgeAlongZ = front == Facing.NORTH || front == Facing.SOUTH;
        int slope = ridgeAlongZ ? halfW + 2 : halfD + 2;
        for (int layer = 0; layer <= slope; layer++) {
            int offset = slope - layer;
            if (ridgeAlongZ) {
                for (int z = -halfD - 1; z <= halfD + 1; z++) for (int x : new int[]{-offset, offset}) {
                    if (ruined && Math.floorMod(x * 11 + z * 7 + layer, 37) < 2) continue;
                    c.clone().add(x, y + layer, z).getBlock().setType(roof, false);
                }
            } else {
                for (int x = -halfW - 1; x <= halfW + 1; x++) for (int z : new int[]{-offset, offset}) {
                    if (ruined && Math.floorMod(x * 11 + z * 7 + layer, 37) < 2) continue;
                    c.clone().add(x, y + layer, z).getBlock().setType(roof, false);
                }
            }
        }
    }

    private static void furnishOverworld(Location c, int w, int d, int middle, Facing front,
                                         OverworldUse use, AdaptiveDungeonLootManager loot) {
        Location backLeft = local(c, front, d - 2, front.left(), w - 2);
        Location backRight = local(c, front, d - 2, front.right(), w - 2);
        switch (use) {
            case INN -> {
                c.clone().add(0, 1, 0).getBlock().setType(Material.CAMPFIRE, false);
                for (int i = -2; i <= 2; i += 2) {
                    local(c, front.left(), i, front, 2).getBlock().setType(Material.OAK_STAIRS, false);
                    local(c, front.right(), i, front, -2).getBlock().setType(Material.OAK_STAIRS, false);
                }
                backLeft.getBlock().setType(Material.BARREL, false);
                backRight.getBlock().setType(Material.SMOKER, false);
                if (middle > 0) {
                    for (int i = -2; i <= 2; i += 2) local(c.clone().add(0, middle + 1, 0), front.left(), i, front, 2)
                            .getBlock().setType(Material.RED_BED, false);
                }
            }
            case BLACKSMITH -> {
                backLeft.getBlock().setType(Material.BLAST_FURNACE, false);
                backRight.getBlock().setType(Material.SMITHING_TABLE, false);
                c.clone().add(0, 1, 0).getBlock().setType(Material.ANVIL, false);
                local(c, front.left(), 2, front, 2).getBlock().setType(Material.GRINDSTONE, false);
            }
            case CHAPEL -> {
                for (int i = -3; i <= 3; i += 2) {
                    local(c, front.left(), i, front, 1).getBlock().setType(Material.OAK_STAIRS, false);
                    local(c, front.left(), i, front, -2).getBlock().setType(Material.OAK_STAIRS, false);
                }
                backLeft.getBlock().setType(Material.LECTERN, false);
                backRight.getBlock().setType(Material.CHISELED_BOOKSHELF, false);
                c.clone().add(0, 2, 0).getBlock().setType(Material.CHAIN, false);
                c.clone().add(0, 1, 0).getBlock().setType(Material.LANTERN, false);
            }
            case WAREHOUSE -> {
                for (int x = -w + 2; x <= w - 2; x += 3) for (int z = -d + 2; z <= d - 2; z += 4) {
                    c.clone().add(x, 1, z).getBlock().setType((x + z) % 2 == 0 ? Material.BARREL : Material.CHEST, false);
                }
            }
            case APOTHECARY -> {
                backLeft.getBlock().setType(Material.BREWING_STAND, false);
                backRight.getBlock().setType(Material.CAULDRON, false);
                c.clone().add(0, 1, 0).getBlock().setType(Material.CRAFTING_TABLE, false);
                for (int x = -2; x <= 2; x += 2) c.clone().add(x, 2, d - 1).getBlock().setType(Material.FLOWER_POT, false);
            }
            case GUARDHOUSE -> {
                backLeft.getBlock().setType(Material.IRON_BARS, false);
                backRight.getBlock().setType(Material.FLETCHING_TABLE, false);
                c.clone().add(0, 1, 0).getBlock().setType(Material.SMITHING_TABLE, false);
            }
            case LIBRARY -> {
                for (int x = -w + 1; x <= w - 1; x++) {
                    c.clone().add(x, 1, d - 1).getBlock().setType(Material.BOOKSHELF, false);
                    if (Math.floorMod(x, 3) == 0) c.clone().add(x, 2, d - 1).getBlock().setType(Material.CHISELED_BOOKSHELF, false);
                }
                c.clone().add(0, 1, 0).getBlock().setType(Material.LECTERN, false);
            }
            case HOME -> {
                backLeft.getBlock().setType(Material.CRAFTING_TABLE, false);
                backRight.getBlock().setType(Material.FURNACE, false);
                c.clone().add(0, 1, 2).getBlock().setType(Material.BLUE_BED, false);
            }
        }
        // Cofre real con tabla de botín y registro personal; no se crean por comando.
        Location chestAt = local(c, front.opposite(), Math.max(1, d - 2), front.right(), Math.max(1, w - 2));
        placeLootContainer(chestAt, Material.BARREL, LootTables.VILLAGE_TOOLSMITH,
                AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                use == OverworldUse.CHAPEL || use == OverworldUse.LIBRARY
                        ? AdaptiveDungeonLootManager.ChestTier.RARE
                        : AdaptiveDungeonLootManager.ChestTier.COMMON, loot);
    }

    private static void addExteriorDetails(Location c, int w, int d, Facing front, OverworldUse use) {
        // Awning / sign / chimney make each use readable from the street.
        Location door = local(c, front, d + 1, front.left(), 0);
        door.getBlock().setType(Material.COBBLESTONE, false);
        if (use == OverworldUse.INN || use == OverworldUse.APOTHECARY) {
            local(c, front, d + 1, front.left(), 2).getBlock().setType(Material.OAK_FENCE, false);
            local(c, front, d + 1, front.right(), 2).getBlock().setType(Material.OAK_FENCE, false);
            local(c, front, d + 1, front.left(), 0).clone().add(0, 2, 0).getBlock().setType(Material.LANTERN, false);
        }
        if (use == OverworldUse.BLACKSMITH || use == OverworldUse.INN) {
            Location chimney = local(c, front.opposite(), d - 2, front.right(), w - 2);
            for (int y = 1; y <= 14; y++) chimney.clone().add(0, y, 0).getBlock().setType(Material.BRICKS, false);
            chimney.clone().add(0, 15, 0).getBlock().setType(Material.CAMPFIRE, false);
        }
    }

    private static void buildCourtyard(Location c, Facing facing, OverworldUse use, Random random) {
        Location center = local(c, facing, 0, facing.right(), 0);
        if (use == OverworldUse.CHAPEL) {
            center.getBlock().setType(Material.MOSS_BLOCK, false);
            center.clone().add(0, 1, 0).getBlock().setType(Material.FLOWERING_AZALEA, false);
        } else if (use == OverworldUse.BLACKSMITH) {
            center.getBlock().setType(Material.CAULDRON, false);
            local(center, facing.left(), 2, facing, 0).getBlock().setType(Material.ANVIL, false);
        } else {
            center.getBlock().setType(Material.COBBLESTONE, false);
            center.clone().add(0, 1, 0).getBlock().setType(random.nextBoolean() ? Material.LANTERN : Material.OAK_FENCE, false);
        }
    }

    private static void buildOverworldTerrainTransition(Location base, int side, Random random) {
        World world = base.getWorld();
        int wallX = side * 96;
        for (int z = -108; z <= 58; z += 22) {
            int outsideX = side * 113;
            int naturalY = naturalSurfaceY(world, base.getBlockX() + outsideX, base.getBlockZ() + z,
                    base.getBlockY());
            int targetY = Math.max(base.getBlockY() - 10, Math.min(base.getBlockY() + 16, naturalY));
            // A series of irregular terraces hides the flat cut and gives the wall a believable foundation.
            for (int step = 0; step < 4; step++) {
                int x0 = wallX + side * (2 + step * 4);
                int y = base.getBlockY() + Math.round((targetY - base.getBlockY()) * (step + 1) / 4.0F);
                for (int dz = -8; dz <= 8; dz++) for (int dx = 0; dx <= 3; dx++) {
                    Location at = base.clone().add(x0 + side * dx, y, z + dz);
                    supportDown(at.clone().add(0, -1, 0), step % 2 == 0 ? Material.STONE : Material.COBBLESTONE, 32);
                    at.getBlock().setType(Math.floorMod(dz + step, 5) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE, false);
                    for (int up = 1; up <= 5; up++) at.clone().add(0, up, 0).getBlock().setType(Material.AIR, false);
                }
                if (step < 3) buildShortRamp(base.clone().add(x0 + side * 2, y + 1, z), side > 0 ? Facing.EAST : Facing.WEST,
                        targetY >= base.getBlockY(), Material.STONE_BRICK_STAIRS);
            }
            Location tower = base.clone().add(side * 108, Math.max(base.getBlockY(), targetY) + 1, z + (random.nextBoolean() ? 5 : -5));
            buildRuinWatchpost(tower, side > 0 ? Facing.WEST : Facing.EAST);
        }
    }

    private static void buildOverworldSouthApproach(Location base, Random random) {
        int y0 = base.getBlockY();
        for (int z = -148; z <= -123; z++) {
            double t = (z + 148) / 25.0;
            int halfWidth = 4 + (int) Math.round(t * 3);
            for (int x = -halfWidth; x <= halfWidth; x++) {
                Location at = base.clone().add(x, 0, z);
                supportDown(at.clone().add(0, -1, 0), Material.STONE, 36);
                at.getBlock().setType(Math.floorMod(x * 13 + z, 11) == 0 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE, false);
                for (int up = 1; up <= 6; up++) at.clone().add(0, up, 0).getBlock().setType(Material.AIR, false);
            }
            if (Math.floorMod(z, 7) == 0) {
                Location left = base.clone().add(-halfWidth - 2, 1, z);
                Location right = base.clone().add(halfWidth + 2, 1, z);
                buildRoadLamp(left, false);
                buildRoadLamp(right, false);
            }
        }
        // Two ruined farms make the approach part of the settlement rather than empty terrain.
        buildOverworldBuilding(base.clone().add(-18, 1, -138), 5, 6, 8, Facing.SOUTH,
                OverworldUse.HOME, true, random, null);
        buildOverworldBuilding(base.clone().add(18, 1, -136), 6, 6, 9, Facing.SOUTH,
                OverworldUse.WAREHOUSE, true, random, null);
    }

    private static void buildRuinWatchpost(Location c, Facing front) {
        prepareFoundation(c, 4, 4, Material.COBBLESTONE, 30);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 0; y <= 9; y++) {
            boolean edge = Math.abs(x) == 4 || Math.abs(z) == 4;
            Material m = y == 0 ? Material.COBBLESTONE : (edge ? (y % 4 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS) : Material.AIR);
            if (y > 5 && Math.floorMod(x * 7 + z * 11 + y, 17) < 3) m = Material.AIR;
            c.clone().add(x, y, z).getBlock().setType(m, false);
        }
        carveFrontDoor(c, 4, 4, front, 2, 4);
    }

    private static void buildSewerNetwork(Location base, AdaptiveDungeonLootManager loot,
                                          DungeonMinionSpawnerManager spawners) {
        int y = -5;
        for (int z = -98; z <= 48; z++) {
            buildSewerSection(base.clone().add(-22, y, z), true);
            buildSewerSection(base.clone().add(22, y, z), true);
        }
        for (int x = -70; x <= 70; x++) buildSewerSection(base.clone().add(x, y, -20), false);
        for (int[] p : new int[][]{{-22,-82},{22,-65},{-22,-20},{22,6},{-22,40}}) {
            Location room = base.clone().add(p[0], y, p[1]);
            buildSewerRoom(room);
            if (loot != null) placeLootContainer(room.clone().add(3, 1, 3), Material.BARREL,
                    LootTables.ABANDONED_MINESHAFT, AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                    AdaptiveDungeonLootManager.ChestTier.COMMON, loot);
        }
        if (spawners != null) {
            spawners.register(base.clone().add(-22, y + 1, -52), "surface_sewers",
                    List.of("arlightbosses:mossbound_spider_minion", "arlightbosses:emerald_zombie_minion"));
            spawners.register(base.clone().add(22, y + 1, 22), "surface_sewers",
                    List.of("arlightbosses:mossbound_spider_minion", "arlightbosses:emerald_creeper_minion"));
        }
    }

    private static void buildSewerSection(Location c, boolean alongZ) {
        for (int side = -3; side <= 3; side++) for (int up = 0; up <= 5; up++) {
            int x = alongZ ? side : 0;
            int z = alongZ ? 0 : side;
            boolean shell = Math.abs(side) == 3 || up == 0 || up == 5;
            c.clone().add(x, up, z).getBlock().setType(shell
                    ? (up == 0 && Math.abs(side) <= 1 ? Material.WATER : Material.DEEPSLATE_BRICKS)
                    : Material.AIR, false);
        }
        if (Math.floorMod(c.getBlockX() + c.getBlockZ(), 13) == 0) {
            c.clone().add(alongZ ? 2 : 0, 3, alongZ ? 0 : 2).getBlock().setType(Material.SOUL_LANTERN, false);
        }
    }

    private static void buildSewerRoom(Location c) {
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = 0; y <= 6; y++) {
            boolean shell = Math.abs(x) == 5 || Math.abs(z) == 5 || y == 0 || y == 6;
            c.clone().add(x, y, z).getBlock().setType(shell
                    ? (Math.floorMod(x + z + y, 9) == 0 ? Material.MOSSY_COBBLESTONE : Material.DEEPSLATE_BRICKS)
                    : Material.AIR, false);
        }
    }

    // ---------------------------------------------------------------------
    // NETHER
    // ---------------------------------------------------------------------

    private static void buildNetherDistrict(Location c, NetherUse use, Facing front, Random random,
                                            AdaptiveDungeonLootManager loot) {
        clearBox(c, 21, 19, -1, 30);
        prepareFoundation(c, 20, 18, Material.BLACKSTONE, 48);
        for (int x = -20; x <= 20; x++) for (int z = -18; z <= 18; z++) {
            c.clone().add(x, 0, z).getBlock().setType(Math.floorMod(x * 17 + z * 13, 17) == 0
                    ? Material.MAGMA_BLOCK : Material.POLISHED_BLACKSTONE, false);
        }
        switch (use) {
            case BARRACKS -> buildNetherBarracks(c, front, loot);
            case FOUNDRY -> buildNetherFoundry(c, front, loot);
            case MARKET -> buildNetherMarket(c, front, loot);
            case VAULT -> buildNetherVault(c, front, loot);
            case BEAST_PENS -> buildNetherPens(c, front, loot);
            case TEMPLE -> buildNetherTemple(c, front, loot);
        }
        // Irregular skyline: a secondary tower or ruined annex instead of two identical halls.
        Location annex = local(c, front.right(), 10, front.opposite(), 6);
        if (random.nextBoolean()) buildNetherTower(annex, 4, 14 + random.nextInt(7), front);
        else buildNetherRuin(annex, front);
    }

    private static void buildNetherBarracks(Location c, Facing front, AdaptiveDungeonLootManager loot) {
        buildNetherHall(c, 10, 9, 12, front, Material.POLISHED_BLACKSTONE_BRICKS, Material.RED_NETHER_BRICKS, false);
        for (int z = -5; z <= 5; z += 5) {
            local(c, front.left(), 6, front, z).getBlock().setType(Material.BLACK_BED, false);
            local(c, front.right(), 6, front, z).getBlock().setType(Material.BARREL, false);
        }
        c.clone().add(0, 1, 0).getBlock().setType(Material.SMITHING_TABLE, false);
        placeLootContainer(local(c, front.opposite(), 7, front.right(), 7), Material.BARREL,
                LootTables.NETHER_BRIDGE, AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.COMMON, loot);
    }

    private static void buildNetherFoundry(Location c, Facing front, AdaptiveDungeonLootManager loot) {
        buildNetherHall(c, 12, 10, 16, front, Material.POLISHED_BLACKSTONE_BRICKS, Material.GILDED_BLACKSTONE, false);
        for (int i = -6; i <= 6; i += 4) {
            local(c, front.left(), i, front, 4).getBlock().setType(Material.BLAST_FURNACE, false);
            local(c, front.left(), i, front, -4).getBlock().setType(Material.ANVIL, false);
        }
        for (int y = 1; y <= 8; y++) {
            local(c, front.opposite(), 8, front.right(), 8).clone().add(0, y, 0).getBlock().setType(Material.LAVA, false);
            local(c, front.opposite(), 8, front.left(), 8).clone().add(0, y, 0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
        }
        placeLootContainer(local(c, front.opposite(), 7, front.left(), 8), Material.CHEST,
                LootTables.BASTION_OTHER, AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.RARE, loot);
    }

    private static void buildNetherMarket(Location c, Facing front, AdaptiveDungeonLootManager loot) {
        buildNetherArcade(c, front);
        for (int row = -1; row <= 1; row++) for (int col = -2; col <= 2; col++) {
            Location stall = local(c, front.left(), col * 5, front, row * 6);
            buildNetherStall(stall, (row + col) % 2 == 0 ? Material.CRIMSON_HYPHAE : Material.WARPED_HYPHAE);
        }
        placeLootContainer(local(c, front.opposite(), 9, front.right(), 11), Material.BARREL,
                LootTables.BASTION_HOGLIN_STABLE, AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.COMMON, loot);
    }

    private static void buildNetherVault(Location c, Facing front, AdaptiveDungeonLootManager loot) {
        buildNetherHall(c, 11, 11, 15, front, Material.REINFORCED_DEEPSLATE, Material.GILDED_BLACKSTONE, false);
        for (int x = -7; x <= 7; x += 4) for (int z = -7; z <= 7; z += 4) {
            c.clone().add(x, 1, z).getBlock().setType(Math.floorMod(x + z, 3) == 0 ? Material.GOLD_BLOCK : Material.RAW_GOLD_BLOCK, false);
        }
        for (int y = 1; y <= 5; y++) local(c, front, 11, front.left(), 0).clone().add(0, y, 0).getBlock().setType(Material.IRON_BARS, false);
        placeLootContainer(c.clone().add(0, 1, 0), Material.CHEST, LootTables.BASTION_TREASURE,
                AdaptiveDungeonLootManager.DimensionGroup.NETHER, AdaptiveDungeonLootManager.ChestTier.EPIC, loot);
    }

    private static void buildNetherPens(Location c, Facing front, AdaptiveDungeonLootManager loot) {
        buildNetherHall(local(c, front.opposite(), 5, front.left(), 0), 8, 7, 10, front,
                Material.NETHER_BRICKS, Material.RED_NETHER_BRICKS, true);
        for (int pen = -1; pen <= 1; pen++) {
            Location p = local(c, front.left(), pen * 8, front, 6);
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) if (Math.abs(x) == 3 || Math.abs(z) == 3) {
                p.clone().add(x, 1, z).getBlock().setType(Material.IRON_BARS, false);
                p.clone().add(x, 2, z).getBlock().setType(Material.IRON_BARS, false);
            }
            p.getBlock().setType(Material.SOUL_SAND, false);
        }
        placeLootContainer(local(c, front.opposite(), 8, front.right(), 9), Material.BARREL,
                LootTables.BASTION_HOGLIN_STABLE, AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.RARE, loot);
    }

    private static void buildNetherTemple(Location c, Facing front, AdaptiveDungeonLootManager loot) {
        buildNetherHall(c, 9, 13, 20, front, Material.POLISHED_BLACKSTONE_BRICKS, Material.CRYING_OBSIDIAN, false);
        for (int y = 1; y <= 12; y++) c.clone().add(0, y, 5).getBlock().setType(y % 3 == 0 ? Material.GILDED_BLACKSTONE : Material.CRYING_OBSIDIAN, false);
        c.clone().add(0, 13, 5).getBlock().setType(Material.SOUL_FIRE, false);
        for (int z = -5; z <= 5; z += 5) {
            local(c, front.left(), 5, front, z).getBlock().setType(Material.SOUL_LANTERN, false);
            local(c, front.right(), 5, front, z).getBlock().setType(Material.SOUL_LANTERN, false);
        }
        placeLootContainer(local(c, front.opposite(), 10, front.right(), 6), Material.CHEST,
                LootTables.RUINED_PORTAL, AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.EPIC, loot);
    }

    private static void buildNetherHall(Location c, int w, int d, int h, Facing front,
                                        Material wall, Material accent, boolean openRoof) {
        prepareFoundation(c, w, d, Material.BLACKSTONE, 48);
        for (int x = -w; x <= w; x++) for (int z = -d; z <= d; z++) for (int y = 0; y <= h; y++) {
            boolean edge = Math.abs(x) == w || Math.abs(z) == d;
            Material m = Material.AIR;
            if (y == 0) m = Material.POLISHED_BLACKSTONE;
            else if (edge) {
                boolean buttress = (Math.abs(x) == w && Math.floorMod(z + d, 5) == 0)
                        || (Math.abs(z) == d && Math.floorMod(x + w, 5) == 0);
                m = buttress || y == 4 || y == 9 ? accent : wall;
                if ((y == 4 || y == 8) && Math.floorMod(x + z, 6) == 0) m = Material.IRON_BARS;
            } else if (!openRoof && y == h) m = Material.RED_NETHER_BRICKS;
            c.clone().add(x, y, z).getBlock().setType(m, false);
        }
        carveFrontDoor(c, w, d, front, 3, 6);
        if (!openRoof) buildNetherSteppedRoof(c, w, d, h + 1, front);
        for (int x : new int[]{-w - 1, w + 1}) for (int z = -d; z <= d; z += 5) {
            for (int y = 0; y <= h / 2; y++) c.clone().add(x, y, z).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
        }
    }

    private static void buildNetherSteppedRoof(Location c, int w, int d, int y, Facing front) {
        boolean alongZ = front == Facing.NORTH || front == Facing.SOUTH;
        int layers = alongZ ? Math.min(6, w / 2) : Math.min(6, d / 2);
        for (int layer = 0; layer < layers; layer++) {
            if (alongZ) for (int x = -w + layer; x <= w - layer; x++) for (int z = -d - 1; z <= d + 1; z++) {
                if (Math.abs(x) == w - layer) c.clone().add(x, y + layer, z).getBlock().setType(layer % 2 == 0 ? Material.RED_NETHER_BRICKS : Material.NETHER_BRICKS, false);
            }
            else for (int z = -d + layer; z <= d - layer; z++) for (int x = -w - 1; x <= w + 1; x++) {
                if (Math.abs(z) == d - layer) c.clone().add(x, y + layer, z).getBlock().setType(layer % 2 == 0 ? Material.RED_NETHER_BRICKS : Material.NETHER_BRICKS, false);
            }
        }
    }

    private static void buildNetherArcade(Location c, Facing front) {
        for (int x = -14; x <= 14; x++) for (int z = -12; z <= 12; z++) {
            c.clone().add(x, 0, z).getBlock().setType(Math.floorMod(x + z, 7) == 0 ? Material.GILDED_BLACKSTONE : Material.NETHER_BRICKS, false);
        }
        for (int x = -14; x <= 14; x += 7) for (int y = 1; y <= 8; y++) {
            c.clone().add(x, y, -12).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
            c.clone().add(x, y, 12).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
        }
        for (int x = -14; x <= 14; x++) {
            c.clone().add(x, 9, -12).getBlock().setType(Material.RED_NETHER_BRICKS, false);
            c.clone().add(x, 9, 12).getBlock().setType(Material.RED_NETHER_BRICKS, false);
        }
    }

    private static void buildNetherStall(Location c, Material canopy) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) c.clone().add(x, 0, z).getBlock().setType(Material.POLISHED_BLACKSTONE, false);
        for (int x : new int[]{-2, 2}) for (int z : new int[]{-2, 2}) for (int y = 1; y <= 4; y++) c.clone().add(x, y, z).getBlock().setType(Material.CRIMSON_FENCE, false);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) c.clone().add(x, 5, z).getBlock().setType(canopy, false);
        c.clone().add(0, 1, 0).getBlock().setType(Material.BARREL, false);
    }

    private static void buildNetherTower(Location c, int r, int h, Facing front) {
        prepareFoundation(c, r, r, Material.BLACKSTONE, 64);
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) for (int y = 0; y <= h; y++) {
            int max = Math.max(Math.abs(x), Math.abs(z));
            boolean cornerCut = Math.abs(x) + Math.abs(z) > r * 2 - 2;
            boolean edge = max == r && !cornerCut;
            Material m = y == 0 ? Material.POLISHED_BLACKSTONE : (edge ? (y % 5 == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS) : Material.AIR);
            c.clone().add(x, y, z).getBlock().setType(m, false);
        }
        carveFrontDoor(c, r, r, front, 2, 4);
        for (int x = -r - 1; x <= r + 1; x++) for (int z = -r - 1; z <= r + 1; z++) if (Math.max(Math.abs(x), Math.abs(z)) == r + 1) {
            c.clone().add(x, h + 1, z).getBlock().setType(Material.RED_NETHER_BRICK_WALL, false);
        }
        c.clone().add(0, h + 2, 0).getBlock().setType(Material.SOUL_LANTERN, false);
    }

    private static void buildNetherRuin(Location c, Facing front) {
        for (int x = -5; x <= 5; x++) for (int z = -6; z <= 6; z++) for (int y = 0; y <= 9; y++) {
            boolean edge = Math.abs(x) == 5 || Math.abs(z) == 6;
            Material m = y == 0 ? Material.BLACKSTONE : (edge ? Material.POLISHED_BLACKSTONE_BRICKS : Material.AIR);
            if (y > 3 && Math.floorMod(x * 13 + z * 17 + y, 11) < 4) m = Material.AIR;
            c.clone().add(x, y, z).getBlock().setType(m, false);
        }
        carveFrontDoor(c, 5, 6, front, 2, 4);
    }

    private static void connectNetherTerrace(Location low, Location high, boolean clockwise) {
        int dy = high.getBlockY() - low.getBlockY();
        if (dy <= 0) return;
        Facing run = clockwise ? Facing.EAST : Facing.WEST;
        for (int step = 0; step <= dy * 2; step++) {
            int y = low.getBlockY() + step / 2;
            int x = low.getBlockX() + run.dx * step;
            int z = low.getBlockZ() + run.dz * step;
            Location at = new Location(low.getWorld(), x, y, z);
            setStair(at, run, Material.POLISHED_BLACKSTONE_BRICK_STAIRS);
            supportDown(at.clone().add(0, -1, 0), Material.POLISHED_BLACKSTONE_BRICKS, 48);
            at.clone().add(run.left().dx * 2, 1, run.left().dz * 2).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICK_WALL, false);
        }
    }

    private static void buildCliffFoundry(Location c, Facing front, Random random,
                                          AdaptiveDungeonLootManager loot,
                                          DungeonMinionSpawnerManager spawners) {
        clearBox(c, 16, 14, -1, 24);
        prepareFoundation(c, 15, 13, Material.BLACKSTONE, 80);
        buildNetherFoundry(c, front, loot);
        for (int y = 0; y <= 20; y++) for (int side : new int[]{-13, 13}) {
            local(c, front.left(), side, front.opposite(), 8).clone().add(0, y, 0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
        }
        spawners.register(c.clone().add(0, 1, 0), "nether_cliff_foundry",
                List.of("arlightbosses:gilded_wither_skeleton_vanguard_minion", "arlightbosses:gilded_blaze_wraith_minion"));
    }

    private static void buildSuspendedTemple(Location c, Facing front, Random random,
                                             AdaptiveDungeonLootManager loot,
                                             DungeonMinionSpawnerManager spawners) {
        clearBox(c, 15, 17, -1, 30);
        buildNetherTemple(c, front, loot);
        // Chains tie the structure to the cavern roof; support piers tie it to the floor.
        for (int x : new int[]{-9, 9}) for (int z : new int[]{-12, 12}) {
            Location chain = c.clone().add(x, 21, z);
            for (int y = 0; y <= 24; y++) chain.clone().add(0, y, 0).getBlock().setType(Material.CHAIN, false);
            supportDown(c.clone().add(x, -1, z), Material.POLISHED_BLACKSTONE_BRICKS, 96);
        }
        spawners.register(c.clone().add(0, 1, 5), "nether_suspended_temple",
                List.of("arlightbosses:gilded_blaze_wraith_minion", "arlightbosses:gilded_hoglin_rider_minion"));
    }

    private static void buildNetherBridge(Location from, Location to, Facing direction, int halfWidth) {
        int dx = Integer.compare(to.getBlockX(), from.getBlockX());
        int dz = Integer.compare(to.getBlockZ(), from.getBlockZ());
        int length = Math.max(Math.abs(to.getBlockX() - from.getBlockX()), Math.abs(to.getBlockZ() - from.getBlockZ()));
        for (int i = 0; i <= length; i++) {
            int y = Math.round(from.getBlockY() + (to.getBlockY() - from.getBlockY()) * (i / (float) Math.max(1, length)));
            Location center = new Location(from.getWorld(), from.getBlockX() + dx * i, y, from.getBlockZ() + dz * i);
            Facing side = (Math.abs(dx) > Math.abs(dz) ? Facing.NORTH : Facing.WEST);
            for (int w = -halfWidth; w <= halfWidth; w++) {
                Location at = local(center, side, w, direction, 0);
                at.getBlock().setType(Math.floorMod(i + w, 9) == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE, false);
                if (Math.abs(w) == halfWidth) at.clone().add(0, 1, 0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICK_WALL, false);
            }
        }
    }

    private static void buildLavaAqueduct(Location from, Location to) {
        int length = Math.abs(to.getBlockX() - from.getBlockX());
        int step = Integer.compare(to.getBlockX(), from.getBlockX());
        for (int i = 0; i <= length; i++) {
            Location c = from.clone().add(step * i, 0, 0);
            c.getBlock().setType(Material.LAVA, false);
            c.clone().add(0, -1, 0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
            c.clone().add(0, 0, -1).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
            c.clone().add(0, 0, 1).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
            if (i % 12 == 0) {
                for (int y = 1; y <= 5; y++) {
                    c.clone().add(0, y, -1).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
                    c.clone().add(0, y, 1).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
                }
            }
        }
    }

    private static void buildHangingWatch(Location c, AdaptiveDungeonLootManager loot,
                                          DungeonMinionSpawnerManager spawners) {
        clearBox(c, 8, 8, -1, 20);
        buildNetherTower(c, 6, 17, Facing.SOUTH);
        for (int x : new int[]{-5, 5}) for (int z : new int[]{-5, 5}) {
            for (int y = 18; y <= 42; y++) c.clone().add(x, y, z).getBlock().setType(Material.CHAIN, false);
        }
        placeLootContainer(c.clone().add(0, 1, 0), Material.BARREL, LootTables.NETHER_BRIDGE,
                AdaptiveDungeonLootManager.DimensionGroup.NETHER, AdaptiveDungeonLootManager.ChestTier.RARE, loot);
        spawners.register(c.clone().add(0, 2, 0), "nether_hanging_watch",
                List.of("arlightbosses:gilded_blaze_wraith_minion"));
    }

    // ---------------------------------------------------------------------
    // END
    // ---------------------------------------------------------------------

    private static void buildEndResidence(Location c, int w, int d, int h, Facing front,
                                          boolean ruined, Random random,
                                          AdaptiveDungeonLootManager loot) {
        clearBox(c, w + 2, d + 2, -1, h + 8);
        prepareFoundation(c, w, d, Material.END_STONE_BRICKS, 36);
        for (int x = -w; x <= w; x++) for (int z = -d; z <= d; z++) for (int y = 0; y <= h; y++) {
            boolean edge = Math.abs(x) == w || Math.abs(z) == d;
            Material m = Material.AIR;
            if (y == 0) m = Material.END_STONE_BRICKS;
            else if (edge) {
                boolean rib = (Math.abs(x) == w && Math.floorMod(z + d, 4) == 0)
                        || (Math.abs(z) == d && Math.floorMod(x + w, 4) == 0);
                m = rib || y == 4 || y == 8 ? Material.PURPUR_PILLAR : Material.PURPUR_BLOCK;
                if ((y == 3 || y == 7) && Math.floorMod(x + z, 4) == 0) m = Material.PURPLE_STAINED_GLASS_PANE;
                if (ruined && y > 5 && Math.floorMod(x * 17 + z * 11 + y, 23) < 3) m = Material.AIR;
            }
            c.clone().add(x, y, z).getBlock().setType(m, false);
        }
        carveFrontDoor(c, w, d, front, 2, 4);
        buildEndRibbedRoof(c, w, d, h + 1, front, ruined);
        c.clone().add(0, 1, 0).getBlock().setType(Material.CHORUS_FLOWER, false);
        local(c, front.opposite(), d - 2, front.left(), w - 2).getBlock().setType(Material.CHISELED_BOOKSHELF, false);
        local(c, front.opposite(), d - 2, front.right(), w - 2).getBlock().setType(Material.PURPLE_BED, false);
        placeLootContainer(local(c, front.opposite(), d - 1, front.right(), Math.max(1, w - 2)), Material.BARREL,
                LootTables.END_CITY_TREASURE, AdaptiveDungeonLootManager.DimensionGroup.END,
                ruined ? AdaptiveDungeonLootManager.ChestTier.COMMON : AdaptiveDungeonLootManager.ChestTier.RARE, loot);
    }

    private static void buildEndRibbedRoof(Location c, int w, int d, int y, Facing front, boolean ruined) {
        boolean ridgeZ = front == Facing.NORTH || front == Facing.SOUTH;
        int layers = ridgeZ ? w + 1 : d + 1;
        for (int layer = 0; layer <= layers; layer++) {
            int offset = layers - layer;
            if (ridgeZ) {
                for (int z = -d - 1; z <= d + 1; z++) for (int x : new int[]{-offset, offset}) {
                    if (ruined && Math.floorMod(x * 7 + z * 13 + layer, 31) < 2) continue;
                    c.clone().add(x, y + layer, z).getBlock().setType(layer % 3 == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_BLOCK, false);
                }
            } else {
                for (int x = -w - 1; x <= w + 1; x++) for (int z : new int[]{-offset, offset}) {
                    if (ruined && Math.floorMod(x * 7 + z * 13 + layer, 31) < 2) continue;
                    c.clone().add(x, y + layer, z).getBlock().setType(layer % 3 == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_BLOCK, false);
                }
            }
        }
    }

    private static void buildEndCathedral(Location c, Facing front,
                                          AdaptiveDungeonLootManager loot,
                                          DungeonMinionSpawnerManager spawners) {
        int w = 14, d = 22, h = 24;
        clearBox(c, w + 3, d + 3, -1, h + 12);
        prepareFoundation(c, w, d, Material.OBSIDIAN, 48);
        for (int x = -w; x <= w; x++) for (int z = -d; z <= d; z++) for (int y = 0; y <= h; y++) {
            boolean outer = Math.abs(x) == w || Math.abs(z) == d;
            boolean nave = Math.abs(x) <= 7;
            Material m = Material.AIR;
            if (y == 0) m = Math.floorMod(x + z, 7) == 0 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS;
            else if (outer) {
                boolean buttress = (Math.abs(x) == w && Math.floorMod(z + d, 6) == 0)
                        || (Math.abs(z) == d && Math.floorMod(x + w, 6) == 0);
                m = buttress ? Material.CRYING_OBSIDIAN : Material.PURPUR_PILLAR;
                if ((y >= 5 && y <= 11 || y >= 15 && y <= 19) && Math.floorMod(x + z, 6) == 0) {
                    m = Material.PURPLE_STAINED_GLASS_PANE;
                }
            } else if (!nave && y <= 10 && Math.abs(x) == 8) m = Material.END_STONE_BRICKS;
            c.clone().add(x, y, z).getBlock().setType(m, false);
        }
        carveFrontDoor(c, w, d, front, 4, 8);
        for (int z = -d + 4; z <= d - 4; z += 6) {
            for (int y = 1; y <= 15; y++) {
                c.clone().add(-8, y, z).getBlock().setType(y % 5 == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_PILLAR, false);
                c.clone().add(8, y, z).getBlock().setType(y % 5 == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_PILLAR, false);
            }
            c.clone().add(-4, 1, z).getBlock().setType(Material.PURPUR_STAIRS, false);
            c.clone().add(4, 1, z).getBlock().setType(Material.PURPUR_STAIRS, false);
        }
        for (int layer = 0; layer <= 8; layer++) for (int z = -d - 1; z <= d + 1; z++) for (int x : new int[]{-(8 - layer), 8 - layer}) {
            c.clone().add(x, h + 1 + layer, z).getBlock().setType(layer % 2 == 0 ? Material.PURPUR_BLOCK : Material.AMETHYST_BLOCK, false);
        }
        Location altar = local(c, front.opposite(), d - 5, front.left(), 0);
        altar.getBlock().setType(Material.CRYING_OBSIDIAN, false);
        altar.clone().add(0, 1, 0).getBlock().setType(Material.END_ROD, false);
        placeLootContainer(local(c, front.opposite(), d - 4, front.right(), 6), Material.CHEST,
                LootTables.END_CITY_TREASURE, AdaptiveDungeonLootManager.DimensionGroup.END,
                AdaptiveDungeonLootManager.ChestTier.EPIC, loot);
        spawners.register(c.clone().add(0, 1, 4), "end_void_cathedral",
                List.of("arlightbosses:void_enderman_sentinel_minion", "arlightbosses:amethyst_shulker_minion"));
    }

    private static void buildEndCliffTower(Location c, int r, int h,
                                           AdaptiveDungeonLootManager loot,
                                           DungeonMinionSpawnerManager spawners) {
        clearBox(c, r + 2, r + 2, -1, h + 6);
        prepareFoundation(c, r, r, Material.OBSIDIAN, 64);
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) for (int y = 0; y <= h; y++) {
            boolean cut = Math.abs(x) + Math.abs(z) > r * 2 - 2;
            boolean edge = Math.max(Math.abs(x), Math.abs(z)) == r && !cut;
            Material m = y == 0 ? Material.END_STONE_BRICKS : (edge ? (y % 6 == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_PILLAR) : Material.AIR);
            c.clone().add(x, y, z).getBlock().setType(m, false);
        }
        for (int y = 1; y <= 5; y++) c.clone().add(0, y, -r).getBlock().setType(Material.AIR, false);
        for (int x = -r - 2; x <= r + 2; x++) for (int z = -r - 2; z <= r + 2; z++) if (Math.max(Math.abs(x), Math.abs(z)) == r + 2) {
            c.clone().add(x, h + 1, z).getBlock().setType(Material.PURPUR_SLAB, false);
        }
        placeLootContainer(c.clone().add(0, 1, 0), Material.BARREL, LootTables.END_CITY_TREASURE,
                AdaptiveDungeonLootManager.DimensionGroup.END, AdaptiveDungeonLootManager.ChestTier.RARE, loot);
        spawners.register(c.clone().add(0, 2, 0), "end_cliff_watch",
                List.of("arlightbosses:amethyst_phantom_minion", "arlightbosses:amethyst_eye_minion"));
    }

    private static void buildEndSkyBridge(Location from, Location to) {
        int dx = to.getBlockX() - from.getBlockX();
        int dz = to.getBlockZ() - from.getBlockZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) Math.max(1, steps);
            int x = (int) Math.round(from.getBlockX() + dx * t);
            int z = (int) Math.round(from.getBlockZ() + dz * t);
            int y = (int) Math.round(from.getBlockY() + (to.getBlockY() - from.getBlockY()) * t + Math.sin(Math.PI * t) * 3.0);
            Location c = new Location(from.getWorld(), x, y, z);
            for (int w = -2; w <= 2; w++) {
                Location at = c.clone().add(Math.abs(dx) >= Math.abs(dz) ? 0 : w, 0,
                        Math.abs(dx) >= Math.abs(dz) ? w : 0);
                at.getBlock().setType(i % 9 == 0 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS, false);
                if (Math.abs(w) == 2) at.clone().add(0, 1, 0).getBlock().setType(Material.END_STONE_BRICK_WALL, false);
            }
        }
    }

    private static void buildDragonArenaDistrict(Location c, AdaptiveDungeonLootManager loot,
                                                 DungeonMinionSpawnerManager spawners, Random random) {
        // Broken outer stands at three heights.
        for (int ring = 0; ring < 3; ring++) {
            int radius = 50 + ring * 7;
            int y = 1 + ring * 3;
            for (int degrees = 0; degrees < 360; degrees += 3) {
                if ((degrees > 45 && degrees < 75) || (degrees > 195 && degrees < 225)) continue;
                double rad = Math.toRadians(degrees);
                int x = (int) Math.round(Math.cos(rad) * radius);
                int z = (int) Math.round(Math.sin(rad) * radius);
                Location at = c.clone().add(x, y, z);
                at.getBlock().setType(degrees % 18 == 0 ? Material.AMETHYST_BLOCK : Material.END_STONE_BRICKS, false);
                supportDown(at.clone().add(0, -1, 0), Material.OBSIDIAN, 36);
            }
        }
        int[][] shrines = {{-43,-30},{43,-30},{-44,28},{44,28},{0,47}};
        for (int i = 0; i < shrines.length; i++) {
            Location s = c.clone().add(shrines[i][0], 1, shrines[i][1]);
            buildEndArenaShrine(s, i);
            if (i < 4) placeLootContainer(s.clone().add(0, 1, 2), Material.BARREL, LootTables.END_CITY_TREASURE,
                    AdaptiveDungeonLootManager.DimensionGroup.END, AdaptiveDungeonLootManager.ChestTier.RARE, loot);
        }
        spawners.register(c.clone().add(-38, 2, 0), "end_dragon_arena_west",
                List.of("arlightbosses:amethyst_phantom_minion", "arlightbosses:void_enderman_minion"), false);
        spawners.register(c.clone().add(38, 2, 0), "end_dragon_arena_east",
                List.of("arlightbosses:amethyst_guardian_shard_minion", "arlightbosses:amethyst_eye_minion"), false);
    }

    private static void buildEndArenaShrine(Location c, int variant) {
        for (int x = -5; x <= 5; x++) for (int z = -4; z <= 4; z++) {
            c.clone().add(x, 0, z).getBlock().setType(Math.floorMod(x + z + variant, 5) == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_BLOCK, false);
        }
        for (int x : new int[]{-4, 4}) for (int z : new int[]{-3, 3}) for (int y = 1; y <= 8; y++) {
            c.clone().add(x, y, z).getBlock().setType(y % 3 == 0 ? Material.CRYING_OBSIDIAN : Material.PURPUR_PILLAR, false);
        }
        c.clone().add(0, 1, 0).getBlock().setType(Material.RESPAWN_ANCHOR, false);
    }

    // ---------------------------------------------------------------------
    // SHARED BUILD HELPERS
    // ---------------------------------------------------------------------

    private static Location terrainAnchored(Location desired, int fallbackY, int maxDelta) {
        if (desired == null || desired.getWorld() == null) return desired;
        int natural = naturalSurfaceY(desired.getWorld(), desired.getBlockX(), desired.getBlockZ(), fallbackY);
        int y = Math.max(fallbackY - Math.max(4, maxDelta),
                Math.min(fallbackY + Math.max(4, maxDelta), natural));
        Location result = desired.clone();
        result.setY(y);
        return result;
    }

    private static void buildTerrainStreet(Location start, Location end, int halfWidth,
                                           Material primary, Material accent) {
        if (start == null || end == null || start.getWorld() == null
                || end.getWorld() == null || start.getWorld() != end.getWorld()) return;
        World world = start.getWorld();
        int dx = end.getBlockX() - start.getBlockX();
        int dz = end.getBlockZ() - start.getBlockZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        int previousY = start.getBlockY();
        for (int i = 0; i <= steps; i++) {
            double t = i / (double)Math.max(1, steps);
            int x = (int)Math.round(start.getBlockX() + dx * t);
            int z = (int)Math.round(start.getBlockZ() + dz * t);
            int expected = (int)Math.round(start.getBlockY()
                    + (end.getBlockY() - start.getBlockY()) * t);
            int natural = naturalSurfaceY(world, x, z, expected);
            // Los edificios ya construidos pueden ser el bloque más alto. El camino
            // sólo acepta pequeñas variaciones respecto a la pendiente esperada.
            int desired = Math.max(expected - 2, Math.min(expected + 2, natural));
            int y = Math.max(previousY - 1, Math.min(previousY + 1, desired));
            previousY = y;
            boolean alongX = Math.abs(dx) >= Math.abs(dz);
            for (int width = -halfWidth; width <= halfWidth; width++) {
                int wx = alongX ? x : x + width;
                int wz = alongX ? z + width : z;
                Location at = new Location(world, wx, y, wz);
                supportDown(at.clone().add(0, -1, 0), Material.COBBLESTONE, 12);
                at.getBlock().setType(Math.floorMod(i + width, 11) == 0 ? accent : primary, false);
                for (int up = 1; up <= 5; up++) at.clone().add(0, up, 0).getBlock().setType(Material.AIR, false);
            }
        }
    }

    private static void buildDiagonalStreet(Location base, int x1, int z1, int x2, int z2,
                                            int halfWidth, Material primary, Material accent) {
        int dx = x2 - x1;
        int dz = z2 - z1;
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) Math.max(1, steps);
            int x = (int) Math.round(x1 + dx * t);
            int z = (int) Math.round(z1 + dz * t);
            for (int w = -halfWidth; w <= halfWidth; w++) {
                int wx = Math.abs(dx) >= Math.abs(dz) ? x : x + w;
                int wz = Math.abs(dx) >= Math.abs(dz) ? z + w : z;
                Location at = base.clone().add(wx, 0, wz);
                at.getBlock().setType(Math.floorMod(i + w, 9) == 0 ? accent : primary, false);
                for (int y = 1; y <= 5; y++) at.clone().add(0, y, 0).getBlock().setType(Material.AIR, false);
            }
        }
    }

    private static void prepareFoundation(Location c, int halfW, int halfD, Material material, int maxDepth) {
        for (int x = -halfW; x <= halfW; x++) for (int z = -halfD; z <= halfD; z++) {
            supportDown(c.clone().add(x, -1, z), material, maxDepth);
        }
    }

    private static void supportDown(Location start, Material material, int maxDepth) {
        if (start == null || start.getWorld() == null) return;
        World world = start.getWorld();
        int min = Math.max(world.getMinHeight(), start.getBlockY() - Math.max(1, maxDepth));
        for (int y = start.getBlockY(); y >= min; y--) {
            Material existing = world.getBlockAt(start.getBlockX(), y, start.getBlockZ()).getType();
            if (existing.isSolid() && y < start.getBlockY()) break;
            world.getBlockAt(start.getBlockX(), y, start.getBlockZ()).setType(material, false);
        }
    }

    private static int naturalSurfaceY(World world, int x, int z, int fallback) {
        if (world == null) return fallback;
        int[] samples = new int[9];
        int cursor = 0;
        for (int dx = -2; dx <= 2; dx += 2) for (int dz = -2; dz <= 2; dz += 2) {
            int y = world.getHighestBlockYAt(x + dx, z + dz);
            while (y > world.getMinHeight() + 1) {
                Material m = world.getBlockAt(x + dx, y, z + dz).getType();
                if (m.isSolid() && !m.name().endsWith("_LEAVES") && m != Material.SNOW) break;
                y--;
            }
            samples[cursor++] = y;
        }
        java.util.Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static void clearBox(Location c, int halfW, int halfD, int minY, int maxY) {
        for (int x = -halfW; x <= halfW; x++) for (int z = -halfD; z <= halfD; z++) for (int y = minY; y <= maxY; y++) {
            c.clone().add(x, y, z).getBlock().setType(Material.AIR, false);
        }
    }

    private static void carveFrontDoor(Location c, int halfW, int halfD, Facing front,
                                       int halfWidth, int height) {
        for (int w = -halfWidth; w <= halfWidth; w++) for (int y = 1; y <= height; y++) {
            Location at;
            if (front == Facing.NORTH || front == Facing.SOUTH) {
                at = c.clone().add(w, y, front == Facing.NORTH ? -halfD : halfD);
            } else {
                at = c.clone().add(front == Facing.WEST ? -halfW : halfW, y, w);
            }
            at.getBlock().setType(Material.AIR, false);
        }
    }

    private static void buildInteriorStair(Location c, int w, int d, int targetY, Facing direction,
                                           Material stairMaterial) {
        for (int step = 0; step < targetY; step++) {
            Location at = local(c, direction, -Math.min(w - 2, d - 2) + step, direction.right(), -Math.min(w - 2, d - 2));
            at.add(0, 1 + step, 0);
            setStair(at, direction, stairMaterial);
            at.clone().add(0, -1, 0).getBlock().setType(Material.COBBLESTONE, false);
            at.clone().add(0, 1, 0).getBlock().setType(Material.AIR, false);
        }
    }

    private static void buildShortRamp(Location start, Facing direction, boolean upward, Material material) {
        for (int i = 0; i < 7; i++) {
            Location at = local(start, direction, i, direction.right(), 0).add(0, upward ? i / 2 : -(i / 2), 0);
            setStair(at, upward ? direction : direction.opposite(), material);
            supportDown(at.clone().add(0, -1, 0), material == Material.STONE_BRICK_STAIRS
                    ? Material.STONE_BRICKS : Material.POLISHED_BLACKSTONE_BRICKS, 32);
        }
    }

    private static void setStair(Location at, Facing facing, Material material) {
        at.getBlock().setType(material, false);
        if (at.getBlock().getBlockData() instanceof Stairs stairs) {
            stairs.setFacing(facing.blockFace());
            stairs.setHalf(Bisected.Half.BOTTOM);
            at.getBlock().setBlockData(stairs, false);
        }
    }

    private static Location local(Location base, Facing a, int amountA, Facing b, int amountB) {
        return base.clone().add(a.dx * amountA + b.dx * amountB, 0,
                a.dz * amountA + b.dz * amountB);
    }

    private static Location centered(Location location) {
        Location result = location.clone();
        result.setX(result.getBlockX() + 0.5D);
        result.setZ(result.getBlockZ() + 0.5D);
        return result;
    }

    private static void buildRoadLamp(Location at, boolean soul) {
        at.getBlock().setType(Material.COBBLESTONE_WALL, false);
        at.clone().add(0, 1, 0).getBlock().setType(Material.OAK_FENCE, false);
        at.clone().add(0, 2, 0).getBlock().setType(soul ? Material.SOUL_LANTERN : Material.LANTERN, false);
    }

    private static void placeLootContainer(Location at, Material type, LootTables table,
                                           AdaptiveDungeonLootManager.DimensionGroup dimension,
                                           AdaptiveDungeonLootManager.ChestTier tier,
                                           AdaptiveDungeonLootManager loot) {
        if (loot == null || at == null || at.getWorld() == null) return;
        at.getBlock().setType(type, false);
        if (at.getBlock().getState() instanceof Chest chest) {
            chest.setLootTable(table.getLootTable());
            chest.update(true, false);
        } else if (at.getBlock().getState() instanceof Barrel barrel) {
            barrel.setLootTable(table.getLootTable());
            barrel.update(true, false);
        }
        if (at.getBlock().getBlockData() instanceof Directional directional) {
            directional.setFacing(BlockFace.SOUTH);
            at.getBlock().setBlockData(directional, false);
        }
        loot.registerChest(at, dimension, tier);
    }
}
