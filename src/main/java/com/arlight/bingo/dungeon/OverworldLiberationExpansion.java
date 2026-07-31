package com.arlight.bingo.dungeon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Anillo exterior de la ciudad musgosa: barrios ocupados, campamentos, jaulas de
 * aldeanos y calles que amplían la estructura base hasta parecer una ciudad real.
 */
final class OverworldLiberationExpansion {
    static final int MIN_X = -96;
    static final int MAX_X = 96;
    static final int MIN_Z = -122;
    static final int MAX_Z = 72;

    record Result(List<Location> checkpoints) { }

    private final JavaPlugin plugin;
    private final AdaptiveDungeonLootManager loot;
    private final DungeonMinionSpawnerManager spawners;

    OverworldLiberationExpansion(JavaPlugin plugin, AdaptiveDungeonLootManager loot,
                                  DungeonMinionSpawnerManager spawners) {
        this.plugin = plugin;
        this.loot = loot;
        this.spawners = spawners;
    }

    Result build(Location base) {
        World world = base.getWorld();
        if (world == null) return new Result(List.of());
        loadChunks(base);
        terraformOuterRing(base);
        buildOuterWall(base);
        buildGrandRoad(base);

        // Ocho manzanas urbanas grandes alrededor de la ciudad existente.
        int[][] districts = {
                {-76, -92}, {-46, -94}, {46, -94}, {76, -92},
                {-78, -48}, {78, -48}, {-78, 36}, {78, 36},
                {-48, 58}, {48, 58}
        };
        for (int i = 0; i < districts.length; i++) {
            boolean ruined = i % 3 == 0;
            buildDistrict(base.clone().add(districts[i][0], 0, districts[i][1]), ruined, i);
        }

        buildPrisonCamp(base.clone().add(-70, 0, -8), 0);
        buildPrisonCamp(base.clone().add(70, 0, 8), 1);
        buildRefugeChapel(base.clone().add(0, 0, 61));
        buildMossGardens(base.clone().add(0, 0, -101));
        registerOuterEncounters(base);
        placeOuterLoot(base);

        List<Location> checkpoints = new ArrayList<>();
        checkpoints.add(center(base.clone().add(0, 1, -109)));
        checkpoints.add(center(base.clone().add(-70, 1, -8)));
        checkpoints.add(center(base.clone().add(70, 1, 8)));
        checkpoints.add(center(base.clone().add(0, 1, 58)));
        return new Result(List.copyOf(checkpoints));
    }

    private void loadChunks(Location base) {
        World world = base.getWorld();
        int minCX = (base.getBlockX() + MIN_X - 4) >> 4;
        int maxCX = (base.getBlockX() + MAX_X + 4) >> 4;
        int minCZ = (base.getBlockZ() + MIN_Z - 4) >> 4;
        int maxCZ = (base.getBlockZ() + MAX_Z + 4) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) for (int cz = minCZ; cz <= maxCZ; cz++) {
            world.getChunkAt(cx, cz).load(true);
        }
    }

    private void terraformOuterRing(Location base) {
        World world = base.getWorld();
        int y0 = base.getBlockY();
        for (int x = MIN_X; x <= MAX_X; x++) for (int z = MIN_Z; z <= MAX_Z; z++) {
            // El centro ya lo prepara OverworldInfestedCity; aquí solo se altera el anillo nuevo.
            if (x >= OverworldInfestedCity.MIN_X - 3 && x <= OverworldInfestedCity.MAX_X + 3
                    && z >= OverworldInfestedCity.MIN_Z - 3 && z <= OverworldInfestedCity.MAX_Z + 3) continue;
            int wx = base.getBlockX() + x;
            int wz = base.getBlockZ() + z;
            for (int y = Math.max(world.getMinHeight() + 1, y0 - 14); y < y0; y++) {
                if (!world.getBlockAt(wx, y, wz).getType().isSolid()) {
                    world.getBlockAt(wx, y, wz).setType(y == y0 - 1 ? Material.DIRT : Material.STONE, false);
                }
            }
            world.getBlockAt(wx, y0, wz).setType(((x + z) % 11 == 0)
                    ? Material.MOSS_BLOCK : Material.COARSE_DIRT, false);
            for (int y = y0 + 1; y <= Math.min(world.getMaxHeight() - 2, y0 + 22); y++) {
                world.getBlockAt(wx, y, wz).setType(Material.AIR, false);
            }
        }
    }

    private void buildOuterWall(Location b) {
        for (int x = MIN_X; x <= MAX_X; x++) {
            wallColumn(b.clone().add(x, 1, MIN_Z));
            wallColumn(b.clone().add(x, 1, MAX_Z));
        }
        for (int z = MIN_Z; z <= MAX_Z; z++) {
            wallColumn(b.clone().add(MIN_X, 1, z));
            wallColumn(b.clone().add(MAX_X, 1, z));
        }
        // Entrada sur monumental.
        for (int x = -7; x <= 7; x++) for (int y = 1; y <= 9; y++) {
            b.clone().add(x, y, MIN_Z).getBlock().setType(Math.abs(x) <= 4 && y <= 6
                    ? Material.AIR : (y % 4 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS), false);
        }
        for (int[] p : new int[][]{{MIN_X + 4, MIN_Z + 4},{MAX_X - 4,MIN_Z + 4},
                {MIN_X + 4,MAX_Z - 4},{MAX_X - 4,MAX_Z - 4}}) buildTower(b.clone().add(p[0], 1, p[1]));
    }

    private void wallColumn(Location at) {
        for (int y = 0; y <= 7; y++) at.clone().add(0, y, 0).getBlock().setType(
                y == 2 || y == 6 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS, false);
        if ((at.getBlockX() + at.getBlockZ()) % 3 == 0) at.clone().add(0, 8, 0).getBlock().setType(Material.STONE_BRICK_WALL, false);
    }

    private void buildTower(Location c) {
        for (int x=-5;x<=5;x++) for (int z=-5;z<=5;z++) for (int y=0;y<=13;y++) {
            boolean edge=Math.abs(x)==5||Math.abs(z)==5;
            if (y==0 || edge) c.clone().add(x,y,z).getBlock().setType(y==4||y==9?Material.MOSSY_STONE_BRICKS:Material.STONE_BRICKS,false);
            else c.clone().add(x,y,z).getBlock().setType(Material.AIR,false);
        }
        c.clone().add(0, 14, 0).getBlock().setType(Material.LANTERN,false);
    }

    private void buildGrandRoad(Location b) {
        for (int z = MIN_Z + 1; z <= MAX_Z - 1; z++) for (int x = -5; x <= 5; x++) {
            b.clone().add(x, 0, z).getBlock().setType((x + z) % 7 == 0
                    ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE, false);
            for (int y=1;y<=5;y++) b.clone().add(x,y,z).getBlock().setType(Material.AIR,false);
        }
        for (int x = MIN_X + 1; x <= MAX_X - 1; x++) for (int z = -3; z <= 3; z++) {
            b.clone().add(x,0,z).getBlock().setType((x-z)%8==0?Material.MOSS_BLOCK:Material.COBBLESTONE,false);
        }
    }

    private void buildDistrict(Location c, boolean ruined, int variant) {
        for (int x=-11;x<=11;x++) for (int z=-10;z<=10;z++) {
            c.clone().add(x,0,z).getBlock().setType((x+z)%9==0?Material.MOSS_BLOCK:Material.STONE_BRICKS,false);
        }
        // Dos casas por manzana, con interiores transitables.
        buildHouse(c.clone().add(-6,1,0), ruined, variant);
        buildHouse(c.clone().add(7,1,0), ruined && variant%2==0, variant+1);
        c.clone().add(0,1,0).getBlock().setType(Material.LANTERN,false);
    }

    private void buildHouse(Location c, boolean ruined, int variant) {
        int w=6,d=8,h=7;
        for(int x=-w/2;x<=w/2;x++) for(int z=-d/2;z<=d/2;z++) for(int y=0;y<=h;y++) {
            boolean edge=Math.abs(x)==w/2||Math.abs(z)==d/2;
            Material mat=Material.AIR;
            if(y==0) mat=Material.COBBLESTONE;
            else if(edge && y<h) mat=(y==2||y==5)?Material.MOSSY_STONE_BRICKS:Material.STONE_BRICKS;
            else if(y==h) mat=(variant%2==0?Material.DARK_OAK_PLANKS:Material.SPRUCE_PLANKS);
            if(ruined && y>3 && Math.floorMod(x*13+z*7+y,11)<3) mat=Material.AIR;
            c.clone().add(x,y,z).getBlock().setType(mat,false);
        }
        for(int y=1;y<=3;y++) c.clone().add(0,y,-d/2).getBlock().setType(Material.AIR,false);
        c.clone().add(0,1,1).getBlock().setType(Material.CRAFTING_TABLE,false);
        c.clone().add(2,1,2).getBlock().setType(Material.BARREL,false);
    }

    private void buildPrisonCamp(Location c, int index) {
        for(int x=-12;x<=12;x++) for(int z=-10;z<=10;z++) {
            c.clone().add(x,0,z).getBlock().setType(Material.MUD_BRICKS,false);
            if(Math.abs(x)==12||Math.abs(z)==10) {
                c.clone().add(x,1,z).getBlock().setType(Material.IRON_BARS,false);
                c.clone().add(x,2,z).getBlock().setType(Material.IRON_BARS,false);
                c.clone().add(x,3,z).getBlock().setType(Material.IRON_BARS,false);
            }
        }
        for(int[] pen:new int[][]{{-6,-3},{0,3},{6,-3}}) {
            for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) if(Math.abs(x)==3||Math.abs(z)==3) {
                c.clone().add(pen[0]+x,1,pen[1]+z).getBlock().setType(Material.IRON_BARS,false);
                c.clone().add(pen[0]+x,2,pen[1]+z).getBlock().setType(Material.IRON_BARS,false);
            }
            Villager villager=(Villager)c.getWorld().spawnEntity(c.clone().add(pen[0]+0.5,1,pen[1]+0.5), EntityType.VILLAGER);
            villager.setAI(false);
            villager.setInvulnerable(true);
            villager.setPersistent(true);
            villager.setRemoveWhenFarAway(false);
            villager.addScoreboardTag("arlightbingo_prisoner");
            villager.setCustomName("§aAldeano prisionero");
            villager.setCustomNameVisible(true);
        }
        spawners.register(c.clone().add(index==0?-9:9,1,7), "surface_prison_guard",
                List.of("arlightbosses:emerald_zombie_minion","arlightbosses:emerald_skeleton_archer_minion"));
    }

    private void buildRefugeChapel(Location c) {
        for(int x=-13;x<=13;x++) for(int z=-9;z<=9;z++) for(int y=0;y<=12;y++) {
            boolean edge=Math.abs(x)==13||Math.abs(z)==9;
            Material mat=y==0?Material.STONE_BRICKS:(edge&&y<10?Material.MOSSY_STONE_BRICKS:Material.AIR);
            if(y>=10 && Math.abs(x)<=13-Math.abs(y-10)*3) mat=Material.DARK_OAK_PLANKS;
            c.clone().add(x,y,z).getBlock().setType(mat,false);
        }
        for(int y=1;y<=5;y++) c.clone().add(0,y,-9).getBlock().setType(Material.AIR,false);
        c.clone().add(0,1,4).getBlock().setType(Material.LECTERN,false);
    }

    private void buildMossGardens(Location c) {
        for(int x=-22;x<=22;x++) for(int z=-12;z<=12;z++) {
            c.clone().add(x,0,z).getBlock().setType((x*x+z*z)%7==0?Material.FLOWERING_AZALEA:Material.MOSS_BLOCK,false);
            if((x*31+z*17)%41==0) c.clone().add(x,1,z).getBlock().setType(Material.AZALEA,false);
        }
        for(int x=-20;x<=20;x+=10) c.clone().add(x,1,0).getBlock().setType(Material.EMERALD_BLOCK,false);
    }

    private void registerOuterEncounters(Location b) {
        List<String> common=List.of("arlightbosses:emerald_zombie_minion","arlightbosses:mossbound_spider_minion");
        List<String> ranged=List.of("arlightbosses:emerald_skeleton_archer_minion","arlightbosses:emerald_creeper_minion");
        List<String> elite=List.of("arlightbosses:emerald_ravager_cub_minion","arlightbosses:emerald_golem_sentinel_minion");
        for(int[] p:new int[][]{{-77,-92},{77,-92},{-78,-48},{78,-48},{-48,58},{48,58}}) {
            Location at=b.clone().add(p[0],1,p[1]);
            at.clone().add(0,-1,0).getBlock().setType(Material.MOSSY_COBBLESTONE,false);
            spawners.register(at,"surface_outer_city",(p[0]+p[1])%2==0?common:ranged);
        }
        spawners.register(b.clone().add(0,1,-103),"surface_outer_elite",elite);
    }

    private void placeOuterLoot(Location b) {
        int[][] points={{-82,-86},{82,-86},{-84,42},{84,42},{-52,61},{52,61}};
        for(int i=0;i<points.length;i++) {
            Location at=b.clone().add(points[i][0],1,points[i][1]);
            at.clone().add(0,-1,0).getBlock().setType(Material.STONE_BRICKS,false);
            loot.placeCustomChest(at, AdaptiveDungeonLootManager.DimensionGroup.OVERWORLD,
                    i>=4?AdaptiveDungeonLootManager.ChestTier.RARE:AdaptiveDungeonLootManager.ChestTier.COMMON);
        }
    }

    private Location center(Location l){l.setX(l.getBlockX()+0.5);l.setZ(l.getBlockZ()+0.5);return l;}
}
