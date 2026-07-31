package com.arlight.bingo.dungeon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Directional;
import org.bukkit.loot.LootTables;

import java.util.List;

/**
 * Pasada de ambientación y jugabilidad para las ciudades de campaña.
 *
 * <p>Las estructuras principales se encargan del casco y las rutas; esta clase
 * añade interiores ocupados, suministros, cobertura, pequeños puntos de interés
 * y contenedores Lootr/vanilla con tablas reales. Se mantiene separada para que
 * una futura ampliación no vuelva a convertir los generadores en clases inmensas.</p>
 */
final class CampaignStructureDetails {
    private CampaignStructureDetails() { }

    static void decorateOverworld(Location base, AdaptiveDungeonLootManager loot,
                                  DungeonMinionSpawnerManager spawners) {
        // Plaza de refugiados y enfermería.
        furnishedRoom(base.clone().add(-48, 1, -55), Material.OAK_PLANKS,
                Material.MOSSY_STONE_BRICKS, Material.RED_BED, Material.BREWING_STAND);
        furnishedRoom(base.clone().add(48, 1, -55), Material.SPRUCE_PLANKS,
                Material.COBBLESTONE, Material.SMITHING_TABLE, Material.ANVIL);
        furnishedRoom(base.clone().add(-50, 1, -8), Material.DARK_OAK_PLANKS,
                Material.MOSSY_COBBLESTONE, Material.LECTERN, Material.BOOKSHELF);
        furnishedRoom(base.clone().add(50, 1, -8), Material.OAK_PLANKS,
                Material.STONE_BRICKS, Material.FLETCHING_TABLE, Material.TARGET);
        furnishedRoom(base.clone().add(-49, 1, 39), Material.SPRUCE_PLANKS,
                Material.MOSSY_STONE_BRICKS, Material.CARTOGRAPHY_TABLE, Material.BARREL);
        furnishedRoom(base.clone().add(49, 1, 39), Material.DARK_OAK_PLANKS,
                Material.CRACKED_STONE_BRICKS, Material.LOOM, Material.CHEST);

        // Mercado central y barricadas que hacen que las calles no sean espacios vacíos.
        for (int x = -36; x <= 36; x += 12) {
            marketStall(base.clone().add(x, 1, -70), (x / 12 & 1) == 0
                    ? Material.GREEN_WOOL : Material.YELLOW_WOOL);
            marketStall(base.clone().add(x, 1, 23), (x / 12 & 1) == 0
                    ? Material.LIME_WOOL : Material.BROWN_WOOL);
        }
        for (int[] p : new int[][]{{-29,-82},{27,-80},{-34,-29},{35,-28},{-31,16},{30,18},{-18,52},{20,50}}) {
            barricade(base.clone().add(p[0], 1, p[1]), (p[0] & 1) == 0);
        }

        // Spawners integrados como altares de raíces/esmeralda. Son rompibles.
        int surfaceDistrict = 0;
        for (int[] p : new int[][]{{-54,-63},{54,-63},{-56,-15},{56,-15},{-54,35},{54,35}}) {
            Location shrine = base.clone().add(p[0], 1, p[1]);
            mossShrine(shrine);
            spawners.register(shrine, "surface_city_district_" + (++surfaceDistrict),
                    Math.abs(p[1]) < 30
                            ? List.of("arlightbosses:emerald_zombie_minion", "arlightbosses:mossbound_spider_minion")
                            : List.of("arlightbosses:emerald_skeleton_archer_minion", "arlightbosses:emerald_creeper_minion"));
        }

        // Suministros personales custom y contenedores Lootr ambientales.
        custom(loot, base.clone().add(-45, 1, -51), AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.RARE);
        custom(loot, base.clone().add(45, 1, -51), AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.RARE);
        custom(loot, base.clone().add(-47, 1, 42), AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.EPIC);
        custom(loot, base.clone().add(47, 1, 42), AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.EPIC);
        lootr(loot, base.clone().add(-27, 1, -69), AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.COMMON, Material.BARREL, LootTables.VILLAGE_PLAINS_HOUSE);
        lootr(loot, base.clone().add(27, 1, -69), AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.COMMON, Material.BARREL, LootTables.VILLAGE_WEAPONSMITH);
        lootr(loot, base.clone().add(-34, 1, -4), AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.RARE, Material.CHEST, LootTables.STRONGHOLD_CORRIDOR);
        lootr(loot, base.clone().add(34, 1, -4), AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.RARE, Material.CHEST, LootTables.PILLAGER_OUTPOST);
        lootr(loot, base.clone().add(0, 1, 51), AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                AdaptiveDungeonLootManager.ChestTier.EPIC, Material.CHEST, LootTables.STRONGHOLD_CROSSING);
        buildOverworldStreetLife(base, loot);
    }

    static void decorateNether(Location base, AdaptiveDungeonLootManager loot,
                               DungeonMinionSpawnerManager spawners) {
        // Talleres, barracas, almacenes y bóvedas con siluetas distintas.
        netherWorkshop(base.clone().add(-58, 1, -76), false);
        netherWorkshop(base.clone().add(58, 1, -76), true);
        netherWorkshop(base.clone().add(-59, 1, -32), true);
        netherWorkshop(base.clone().add(59, 1, -32), false);
        netherVault(base.clone().add(-58, 1, 26));
        netherVault(base.clone().add(58, 1, 26));

        for (int[] p : new int[][]{{-39,-91},{39,-91},{-69,-55},{69,-55},{-67,9},{67,9},{-39,48},{39,48}}) {
            netherBarricade(base.clone().add(p[0], 1, p[1]), p[0] < 0);
        }

        // Forjas corruptas que contienen spawners reales y se pueden destruir.
        int netherDistrict = 0;
        for (int[] p : new int[][]{{-57,-70},{57,-70},{-58,-24},{58,-24},{-56,34},{56,34}}) {
            Location forge = base.clone().add(p[0], 1, p[1]);
            netherSpawnerForge(forge);
            spawners.register(forge, "nether_city_district_" + (++netherDistrict),
                    p[1] > 10
                            ? List.of("arlightbosses:gilded_hoglin_rider_minion", "arlightbosses:gilded_blaze_wraith_minion")
                            : List.of("arlightbosses:gilded_piglin_minion", "arlightbosses:gilded_wither_skeleton_vanguard_minion"));
        }

        custom(loot, base.clone().add(-51, 1, -72), AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.RARE);
        custom(loot, base.clone().add(51, 1, -72), AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.RARE);
        custom(loot, base.clone().add(-52, 1, 31), AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.EPIC);
        custom(loot, base.clone().add(52, 1, 31), AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.EPIC);
        lootr(loot, base.clone().add(-27, 1, -91), AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.COMMON, Material.BARREL, LootTables.NETHER_BRIDGE);
        lootr(loot, base.clone().add(27, 1, -91), AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.COMMON, Material.BARREL, LootTables.RUINED_PORTAL);
        lootr(loot, base.clone().add(-42, 1, 7), AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.RARE, Material.CHEST, LootTables.BASTION_OTHER);
        lootr(loot, base.clone().add(42, 1, 7), AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.RARE, Material.CHEST, LootTables.BASTION_BRIDGE);
        lootr(loot, base.clone().add(0, 1, 47), AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                AdaptiveDungeonLootManager.ChestTier.EPIC, Material.CHEST, LootTables.BASTION_TREASURE);
        buildNetherStreetLife(base, loot);
    }

    private static void furnishedRoom(Location c, Material floor, Material wall,
                                      Material workstation, Material storage) {
        for (int x=-6;x<=6;x++) for (int z=-5;z<=5;z++) {
            c.clone().add(x,-1,z).getBlock().setType(wall,false);
            c.clone().add(x,0,z).getBlock().setType(floor,false);
            for (int y=1;y<=5;y++) {
                boolean edge=Math.abs(x)==6||Math.abs(z)==5;
                if (edge) c.clone().add(x,y,z).getBlock().setType((y==3&&Math.floorMod(x+z,4)==0)
                        ? Material.GLASS_PANE:wall,false);
                else c.clone().add(x,y,z).getBlock().setType(Material.AIR,false);
            }
            c.clone().add(x,6,z).getBlock().setType(floor,false);
        }
        for(int y=1;y<=3;y++) c.clone().add(0,y,-5).getBlock().setType(Material.AIR,false);
        c.clone().add(-3,1,2).getBlock().setType(workstation,false);
        c.clone().add(3,1,2).getBlock().setType(storage,false);
        c.clone().add(0,1,3).getBlock().setType(Material.CRAFTING_TABLE,false);
        c.clone().add(-4,1,-2).getBlock().setType(Material.OAK_STAIRS,false);
        c.clone().add(4,1,-2).getBlock().setType(Material.OAK_STAIRS,false);
        c.clone().add(0,4,0).getBlock().setType(Material.LANTERN,false);
    }

    private static void marketStall(Location c, Material canopy) {
        for(int x=-3;x<=3;x++)for(int z=-2;z<=2;z++)c.clone().add(x,0,z).getBlock().setType(Material.OAK_PLANKS,false);
        for(int x:new int[]{-3,3})for(int z:new int[]{-2,2})for(int y=1;y<=4;y++)c.clone().add(x,y,z).getBlock().setType(Material.OAK_FENCE,false);
        for(int x=-3;x<=3;x++)for(int z=-2;z<=2;z++)c.clone().add(x,5,z).getBlock().setType(canopy,false);
        c.clone().add(0,1,0).getBlock().setType(Material.BARREL,false);
        c.clone().add(-1,1,0).getBlock().setType(Material.HAY_BLOCK,false);
    }

    private static void barricade(Location c, boolean rotate) {
        for(int i=-4;i<=4;i++){
            int x=rotate?0:i,z=rotate?i:0;
            c.clone().add(x,0,z).getBlock().setType(i%3==0?Material.MOSSY_COBBLESTONE:Material.OAK_LOG,false);
            if((i&1)==0)c.clone().add(x,1,z).getBlock().setType(Material.OAK_FENCE,false);
        }
    }

    private static void mossShrine(Location c) {
        c.clone().add(0,-1,0).getBlock().setType(Material.MOSSY_COBBLESTONE,false);
        for(int[]p:new int[][]{{-2,0},{2,0},{0,-2},{0,2}}){
            c.clone().add(p[0],0,p[1]).getBlock().setType(Material.MOSSY_STONE_BRICK_WALL,false);
            c.clone().add(p[0],1,p[1]).getBlock().setType(Material.VINE,false);
        }
        for(int[]p:new int[][]{{-1,-1},{1,-1},{-1,1},{1,1}})c.clone().add(p[0],0,p[1]).getBlock().setType(Material.EMERALD_ORE,false);
    }

    private static void netherWorkshop(Location c, boolean gold) {
        for(int x=-7;x<=7;x++)for(int z=-6;z<=6;z++){
            c.clone().add(x,0,z).getBlock().setType(Material.POLISHED_BLACKSTONE,false);
            for(int y=1;y<=7;y++){
                boolean edge=Math.abs(x)==7||Math.abs(z)==6;
                c.clone().add(x,y,z).getBlock().setType(edge?(y==4?Material.GILDED_BLACKSTONE:Material.POLISHED_BLACKSTONE_BRICKS):Material.AIR,false);
            }
            c.clone().add(x,8,z).getBlock().setType(Material.RED_NETHER_BRICKS,false);
        }
        for(int y=1;y<=4;y++)c.clone().add(0,y,-6).getBlock().setType(Material.AIR,false);
        for(int x=-4;x<=4;x+=4)c.clone().add(x,1,2).getBlock().setType(gold?Material.GOLD_BLOCK:Material.BLAST_FURNACE,false);
        c.clone().add(0,1,3).getBlock().setType(Material.SMITHING_TABLE,false);
        c.clone().add(0,6,0).getBlock().setType(Material.SOUL_LANTERN,false);
    }

    private static void netherVault(Location c) {
        netherWorkshop(c,true);
        for(int x=-4;x<=4;x+=2)for(int z=0;z<=4;z+=2)c.clone().add(x,1,z).getBlock().setType(
                Math.floorMod(x+z,4)==0?Material.GOLD_BLOCK:Material.RAW_GOLD_BLOCK,false);
        for(int x=-6;x<=6;x++)c.clone().add(x,1,-2).getBlock().setType(Material.IRON_BARS,false);
    }

    private static void netherBarricade(Location c, boolean rotate) {
        for(int i=-5;i<=5;i++){
            int x=rotate?0:i,z=rotate?i:0;
            c.clone().add(x,0,z).getBlock().setType(i%3==0?Material.MAGMA_BLOCK:Material.POLISHED_BLACKSTONE_BRICKS,false);
            if((i&1)==0)c.clone().add(x,1,z).getBlock().setType(Material.IRON_BARS,false);
        }
    }

    private static void netherSpawnerForge(Location c) {
        c.clone().add(0,-1,0).getBlock().setType(Material.MAGMA_BLOCK,false);
        for(int[]p:new int[][]{{-2,0},{2,0},{0,-2},{0,2}}){
            c.clone().add(p[0],0,p[1]).getBlock().setType(Material.POLISHED_BASALT,false);
            c.clone().add(p[0],1,p[1]).getBlock().setType(Material.CHAIN,false);
        }
        for(int[]p:new int[][]{{-1,-1},{1,-1},{-1,1},{1,1}})c.clone().add(p[0],0,p[1]).getBlock().setType(Material.GILDED_BLACKSTONE,false);
    }


    /** Segunda pasada: convierte las calles rectas en una ciudad invadida y habitada. */
    private static void buildOverworldStreetLife(Location base, AdaptiveDungeonLootManager loot) {
        // Carretas destruidas, campamentos de refugiados, puestos saqueados y escombros.
        for (int[] p : new int[][]{{-22,-58,0},{24,-51,1},{-18,-18,1},{21,-7,0},{-36,31,0},{34,38,1}}) {
            ruinedCart(base.clone().add(p[0],1,p[1]), p[2] == 1);
        }
        for (int[] p : new int[][]{{-42,-37},{41,-38},{-41,9},{42,10},{-20,37},{21,35}}) {
            refugeeCamp(base.clone().add(p[0],1,p[1]));
        }
        for (int[] p : new int[][]{{-55,-48},{55,-47},{-52,2},{52,3},{-53,44},{53,43}}) {
            ruinedFacade(base.clone().add(p[0],1,p[1]));
        }
        // Faroles, vegetación, bancos y montones de cajas rompen la repetición del trazado.
        for (int z=-72; z<=42; z+=9) {
            streetLamp(base.clone().add(-8,1,z), false);
            streetLamp(base.clone().add(8,1,z+4), false);
            if (Math.floorMod(z,18)==0) cratePile(base.clone().add(-13,1,z+2), false);
        }
        for (int x=-48;x<=48;x+=16) {
            streetLamp(base.clone().add(x,1,-34), false);
            if (x%32==0) overgrownWell(base.clone().add(x,1,15));
        }
        // Más Lootr real: barriles, cofres y depósitos distribuidos en interiores y callejones.
        Object[][] lootPoints={
                {-51,-35,Material.BARREL,LootTables.VILLAGE_TOOLSMITH,AdaptiveDungeonLootManager.ChestTier.COMMON},
                {51,-35,Material.BARREL,LootTables.VILLAGE_FLETCHER,AdaptiveDungeonLootManager.ChestTier.COMMON},
                {-25,-15,Material.CHEST,LootTables.SIMPLE_DUNGEON,AdaptiveDungeonLootManager.ChestTier.RARE},
                {26,-14,Material.BARREL,LootTables.VILLAGE_ARMORER,AdaptiveDungeonLootManager.ChestTier.RARE},
                {-45,27,Material.CHEST,LootTables.STRONGHOLD_LIBRARY,AdaptiveDungeonLootManager.ChestTier.EPIC},
                {44,28,Material.BARREL,LootTables.VILLAGE_WEAPONSMITH,AdaptiveDungeonLootManager.ChestTier.EPIC},
                {-12,43,Material.CHEST,LootTables.STRONGHOLD_CROSSING,AdaptiveDungeonLootManager.ChestTier.RARE},
                {13,44,Material.BARREL,LootTables.VILLAGE_TEMPLE,AdaptiveDungeonLootManager.ChestTier.RARE}
        };
        for(Object[] p:lootPoints) lootr(loot,base.clone().add((int)p[0],1,(int)p[1]),
                AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                (AdaptiveDungeonLootManager.ChestTier)p[4],(Material)p[2],(LootTables)p[3]);
    }

    /** Segunda pasada del Nether: barrios militares, tuberías, puentes y ruinas conectadas al terreno. */
    private static void buildNetherStreetLife(Location base, AdaptiveDungeonLootManager loot) {
        for (int[] p : new int[][]{{-31,-72,0},{32,-69,1},{-33,-36,1},{34,-34,0},{-31,12,0},{32,15,1},{-22,43,1},{23,44,0}}) {
            netherCargo(base.clone().add(p[0],1,p[1]), p[2]==1);
        }
        for (int[] p : new int[][]{{-65,-78},{65,-76},{-66,-12},{66,-10},{-61,39},{61,38}}) {
            netherTempleRuins(base.clone().add(p[0],1,p[1]));
        }
        for (int z=-82;z<=46;z+=10) {
            streetLamp(base.clone().add(-9,1,z), true);
            streetLamp(base.clone().add(9,1,z+5), true);
            if(Math.floorMod(z,20)==0) lavaPipe(base.clone().add(-15,1,z+2));
        }
        for (int x=-60;x<=60;x+=15) {
            if(Math.abs(x)<12)continue;
            chainArch(base.clone().add(x,1,-52));
            if(x%30==0)netherMarketStall(base.clone().add(x,1,5));
        }
        Object[][] lootPoints={
                {-61,-61,Material.BARREL,LootTables.NETHER_BRIDGE,AdaptiveDungeonLootManager.ChestTier.COMMON},
                {61,-60,Material.BARREL,LootTables.RUINED_PORTAL,AdaptiveDungeonLootManager.ChestTier.COMMON},
                {-35,-17,Material.CHEST,LootTables.BASTION_OTHER,AdaptiveDungeonLootManager.ChestTier.RARE},
                {35,-16,Material.BARREL,LootTables.BASTION_HOGLIN_STABLE,AdaptiveDungeonLootManager.ChestTier.RARE},
                {-58,21,Material.CHEST,LootTables.BASTION_BRIDGE,AdaptiveDungeonLootManager.ChestTier.EPIC},
                {58,22,Material.BARREL,LootTables.BASTION_TREASURE,AdaptiveDungeonLootManager.ChestTier.EPIC},
                {-17,42,Material.CHEST,LootTables.NETHER_BRIDGE,AdaptiveDungeonLootManager.ChestTier.RARE},
                {18,42,Material.BARREL,LootTables.BASTION_OTHER,AdaptiveDungeonLootManager.ChestTier.RARE}
        };
        for(Object[] p:lootPoints) lootr(loot,base.clone().add((int)p[0],1,(int)p[1]),
                AdaptiveDungeonLootManager.DimensionGroup.NETHER,
                (AdaptiveDungeonLootManager.ChestTier)p[4],(Material)p[2],(LootTables)p[3]);
    }

    private static void ruinedCart(Location c, boolean rotated) {
        for(int i=-2;i<=2;i++){
            int x=rotated?0:i,z=rotated?i:0;
            c.clone().add(x,0,z).getBlock().setType(i==0?Material.STRIPPED_SPRUCE_LOG:Material.SPRUCE_SLAB,false);
        }
        for(int[]p:new int[][]{{-2,0},{2,0}}){
            int x=rotated?p[1]:p[0],z=rotated?p[0]:p[1];
            c.clone().add(x,0,z).getBlock().setType(Material.DARK_OAK_TRAPDOOR,false);
        }
        c.clone().add(rotated?1:0,1,rotated?0:1).getBlock().setType(Material.BARREL,false);
        c.clone().add(rotated?-1:0,1,rotated?0:-1).getBlock().setType(Material.HAY_BLOCK,false);
    }

    private static void refugeeCamp(Location c){
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(Math.abs(x)+Math.abs(z)<6)
            c.clone().add(x,0,z).getBlock().setType((x+z)%5==0?Material.MOSS_CARPET:Material.COARSE_DIRT,false);
        for(int x=-3;x<=3;x++)c.clone().add(x,3,-3).getBlock().setType(x%2==0?Material.GREEN_WOOL:Material.BROWN_WOOL,false);
        c.clone().add(-2,1,0).getBlock().setType(Material.RED_BED,false);
        c.clone().add(2,1,0).getBlock().setType(Material.CAMPFIRE,false);
        c.clone().add(0,1,2).getBlock().setType(Material.BARREL,false);
    }

    private static void ruinedFacade(Location c){
        for(int x=-4;x<=4;x++)for(int y=0;y<=6;y++){
            if(Math.floorMod(x*7+y*11,17)<3)continue;
            c.clone().add(x,y,0).getBlock().setType(y==3?Material.MOSSY_STONE_BRICKS:Material.STONE_BRICKS,false);
        }
        for(int x=-4;x<=4;x+=4)c.clone().add(x,7,0).getBlock().setType(Material.CRACKED_STONE_BRICKS,false);
        c.clone().add(0,1,0).getBlock().setType(Material.AIR,false);
        c.clone().add(0,2,0).getBlock().setType(Material.AIR,false);
    }

    private static void streetLamp(Location c, boolean nether){
        Material post=nether?Material.POLISHED_BLACKSTONE_BRICK_WALL:Material.STONE_BRICK_WALL;
        for(int y=0;y<=3;y++)c.clone().add(0,y,0).getBlock().setType(post,false);
        c.clone().add(0,4,0).getBlock().setType(nether?Material.SOUL_LANTERN:Material.LANTERN,false);
        c.clone().add(0,5,0).getBlock().setType(nether?Material.CHAIN:Material.IRON_BARS,false);
    }

    private static void cratePile(Location c, boolean nether){
        Material plank=nether?Material.CRIMSON_PLANKS:Material.SPRUCE_PLANKS;
        c.getBlock().setType(Material.BARREL,false);
        c.clone().add(1,0,0).getBlock().setType(plank,false);
        c.clone().add(0,1,0).getBlock().setType(plank,false);
        c.clone().add(-1,0,1).getBlock().setType(nether?Material.GILDED_BLACKSTONE:Material.HAY_BLOCK,false);
    }

    private static void overgrownWell(Location c){
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(Math.abs(x)==3||Math.abs(z)==3)
            c.clone().add(x,0,z).getBlock().setType((x+z)%3==0?Material.MOSSY_COBBLESTONE:Material.COBBLESTONE,false);
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)c.clone().add(x,-1,z).getBlock().setType(Material.WATER,false);
        for(int[]p:new int[][]{{-3,-3},{3,-3},{-3,3},{3,3}})for(int y=1;y<=4;y++)
            c.clone().add(p[0],y,p[1]).getBlock().setType(Material.OAK_FENCE,false);
    }

    private static void netherCargo(Location c, boolean rotated){
        cratePile(c,true);cratePile(c.clone().add(rotated?0:2,0,rotated?2:0),true);
        c.clone().add(rotated?0:-2,0,rotated?-2:0).getBlock().setType(Material.RAW_GOLD_BLOCK,false);
        c.clone().add(0,2,0).getBlock().setType(Material.CHAIN,false);
    }

    private static void netherTempleRuins(Location c){
        for(int a=0;a<8;a++){
            double r=a*Math.PI/4.0;int x=(int)Math.round(Math.cos(r)*4),z=(int)Math.round(Math.sin(r)*4);
            for(int y=0;y<5+(a%3);y++)c.clone().add(x,y,z).getBlock().setType(y%3==0?Material.GILDED_BLACKSTONE:Material.POLISHED_BLACKSTONE_BRICKS,false);
        }
        c.clone().add(0,0,0).getBlock().setType(Material.MAGMA_BLOCK,false);
        c.clone().add(0,1,0).getBlock().setType(Material.SOUL_FIRE,false);
    }

    private static void lavaPipe(Location c){
        for(int y=0;y<=5;y++)c.clone().add(0,y,0).getBlock().setType(Material.POLISHED_BASALT,false);
        for(int x=1;x<=4;x++)c.clone().add(x,5,0).getBlock().setType(x==4?Material.MAGMA_BLOCK:Material.POLISHED_BASALT,false);
        c.clone().add(4,4,0).getBlock().setType(Material.LAVA,false);
    }

    private static void chainArch(Location c){
        for(int y=0;y<=6;y++){
            c.clone().add(-3,y,0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS,false);
            c.clone().add(3,y,0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS,false);
        }
        for(int x=-3;x<=3;x++)c.clone().add(x,7,0).getBlock().setType(Material.RED_NETHER_BRICKS,false);
        c.clone().add(0,6,0).getBlock().setType(Material.CHAIN,false);
        c.clone().add(0,5,0).getBlock().setType(Material.SOUL_LANTERN,false);
    }

    private static void netherMarketStall(Location c){
        marketStall(c,Material.RED_NETHER_BRICKS);
        c.clone().add(-2,1,0).getBlock().setType(Material.GOLD_BLOCK,false);
        c.clone().add(2,1,0).getBlock().setType(Material.NETHER_GOLD_ORE,false);
    }

    private static void custom(AdaptiveDungeonLootManager loot, Location at,
                               AdaptiveDungeonLootManager.DimensionGroup dimension,
                               AdaptiveDungeonLootManager.ChestTier tier) {
        at.clone().add(0,-1,0).getBlock().setType(support(dimension),false);
        at.clone().add(0,1,0).getBlock().setType(Material.AIR,false);
        loot.placeCustomChest(at,dimension,tier);
    }

    private static void lootr(AdaptiveDungeonLootManager loot, Location at,
                              AdaptiveDungeonLootManager.DimensionGroup dimension,
                              AdaptiveDungeonLootManager.ChestTier tier, Material container,
                              LootTables table) {
        at.clone().add(0,-1,0).getBlock().setType(support(dimension),false);
        at.clone().add(0,1,0).getBlock().setType(Material.AIR,false);
        at.getBlock().setType(container,false);
        if(at.getBlock().getState() instanceof Chest chest){
            chest.setLootTable(table.getLootTable()); chest.update(true,false);
        } else if(at.getBlock().getState() instanceof Barrel barrel){
            barrel.setLootTable(table.getLootTable()); barrel.update(true,false);
        }
        if(at.getBlock().getBlockData() instanceof Directional directional){
            directional.setFacing(BlockFace.SOUTH); at.getBlock().setBlockData(directional,false);
        }
        loot.registerChest(at,dimension,tier);
    }

    private static Material support(AdaptiveDungeonLootManager.DimensionGroup dimension) {
        return switch(dimension){
            case OVERWORLD -> Material.MOSSY_STONE_BRICKS;
            case NETHER -> Material.GILDED_BLACKSTONE;
            case END -> Material.END_STONE_BRICKS;
        };
    }
}
