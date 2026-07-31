package com.arlight.bingo.dungeon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;
import com.arlight.bingo.util.CampaignItemBridge;

import java.util.ArrayList;
import java.util.List;

/** Ciudad gigante exterior que rodea la ciudadela militar del Nether. */
final class NetherLiberationExpansion {
    static final int MIN_X = -82;
    static final int MAX_X = 82;
    static final int MIN_Z = -108;
    static final int MAX_Z = 62;

    record Result(List<Location> keyChests, Location keyLock, Location gateCenter,
                  List<Location> checkpoints) { }

    private final JavaPlugin plugin;
    private final AdaptiveDungeonLootManager loot;
    private final DungeonMinionSpawnerManager spawners;

    NetherLiberationExpansion(JavaPlugin plugin, AdaptiveDungeonLootManager loot,
                               DungeonMinionSpawnerManager spawners) {
        this.plugin=plugin; this.loot=loot; this.spawners=spawners;
    }

    Result build(Location base, int bossLevel) {
        loadAndClearOuter(base);
        buildOuterFortifications(base);
        buildRoads(base);
        buildDistrict(base.clone().add(-59,0,-79), "barracas", 0);
        buildDistrict(base.clone().add(59,0,-79), "mercado", 1);
        buildDistrict(base.clone().add(-60,0,-35), "minas", 2);
        buildDistrict(base.clone().add(60,0,-35), "herrerias", 3);
        buildDistrict(base.clone().add(-61,0,27), "criaderos", 4);
        buildDistrict(base.clone().add(61,0,27), "tesoros", 5);
        buildLavaCanals(base);
        buildWatchTowers(base);

        List<Location> keyChests = new ArrayList<>();
        for (int[] p : new int[][]{{-67,-87},{67,-87},{-69,-37},{69,-37},{-66,30},{66,30},{0,-91},{0,49}}) {
            Location chest = base.clone().add(p[0],1,p[1]);
            placeSharedMissionChest(chest);
            keyChests.add(chest.clone());
        }

        registerEncounters(base);
        int bossY = bossLevel * 8;
        Location gateCenter=base.clone().add(0,bossY+1,9);
        buildBossGate(gateCenter);
        Location lock=base.clone().add(0,bossY+1,7);
        lock.clone().add(0,-1,0).getBlock().setType(Material.GILDED_BLACKSTONE,false);
        lock.clone().add(0,1,0).getBlock().setType(Material.AIR,false);
        lock.clone().add(0,2,0).getBlock().setType(Material.AIR,false);
        if (!CampaignItemBridge.setModBlockState(lock,
                CampaignItemBridge.NETHER_DUNGEON_LOCK + "[facing=north,lock_state=locked]")) {
            lock.getBlock().setType(Material.RESPAWN_ANCHOR,false);
        }

        List<Location> checkpoints=List.of(
                center(base.clone().add(0,1,-99)),
                center(base.clone().add(0,1,-70)),
                center(base.clone().add(-59,1,-35)),
                center(base.clone().add(59,1,-35)),
                center(base.clone().add(0,1,-12)),
                center(base.clone().add(0,bossY+1,4))
        );
        return new Result(List.copyOf(keyChests),lock,gateCenter,List.copyOf(checkpoints));
    }

    private void loadAndClearOuter(Location b) {
        World w=b.getWorld();
        int minCX=(b.getBlockX()+MIN_X-3)>>4,maxCX=(b.getBlockX()+MAX_X+3)>>4;
        int minCZ=(b.getBlockZ()+MIN_Z-3)>>4,maxCZ=(b.getBlockZ()+MAX_Z+3)>>4;
        for(int cx=minCX;cx<=maxCX;cx++)for(int cz=minCZ;cz<=maxCZ;cz++)w.getChunkAt(cx,cz).load(true);
        int top=Math.min(w.getMaxHeight()-2,b.getBlockY()+48);
        for(int x=MIN_X;x<=MAX_X;x++)for(int z=MIN_Z;z<=MAX_Z;z++){
            // Mantiene intacta la ciudadela interior ya construida.
            if(x>=-43&&x<=43&&z>=-60&&z<=42)continue;
            for(int y=0;y<=top-b.getBlockY();y++)b.clone().add(x,y,z).getBlock().setType(Material.AIR,false);
            b.clone().add(x,-1,z).getBlock().setType((x+z)%13==0?Material.MAGMA_BLOCK:Material.BLACKSTONE,false);
            b.clone().add(x,0,z).getBlock().setType((x-z)%9==0?Material.POLISHED_BLACKSTONE:Material.NETHER_BRICKS,false);
        }
    }

    private void buildOuterFortifications(Location b){
        for(int x=MIN_X;x<=MAX_X;x++){wall(b.clone().add(x,1,MIN_Z));wall(b.clone().add(x,1,MAX_Z));}
        for(int z=MIN_Z;z<=MAX_Z;z++){wall(b.clone().add(MIN_X,1,z));wall(b.clone().add(MAX_X,1,z));}
        for(int x=-8;x<=8;x++)for(int y=1;y<=12;y++){
            b.clone().add(x,y,MIN_Z).getBlock().setType(Math.abs(x)<=5&&y<=8?Material.AIR:
                    (y%4==0?Material.GILDED_BLACKSTONE:Material.POLISHED_BLACKSTONE_BRICKS),false);
        }
        for(int[] p:new int[][]{{MIN_X+6,MIN_Z+6},{MAX_X-6,MIN_Z+6},{MIN_X+6,MAX_Z-6},{MAX_X-6,MAX_Z-6}})
            tower(b.clone().add(p[0],1,p[1]),9,22);
    }

    private void wall(Location l){
        for(int y=0;y<=10;y++)l.clone().add(0,y,0).getBlock().setType(y==3||y==8?Material.GILDED_BLACKSTONE:Material.POLISHED_BLACKSTONE_BRICKS,false);
        if((l.getBlockX()+l.getBlockZ())%3==0)l.clone().add(0,11,0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICK_WALL,false);
    }

    private void buildRoads(Location b){
        for(int z=MIN_Z+1;z<=MAX_Z-1;z++)for(int x=-6;x<=6;x++){
            b.clone().add(x,0,z).getBlock().setType((x+z)%8==0?Material.GILDED_BLACKSTONE:Material.POLISHED_BLACKSTONE,false);
            for(int y=1;y<=7;y++)b.clone().add(x,y,z).getBlock().setType(Material.AIR,false);
        }
        for(int x=MIN_X+1;x<=MAX_X-1;x++)for(int z=-4;z<=4;z++)
            b.clone().add(x,0,z).getBlock().setType((x-z)%9==0?Material.MAGMA_BLOCK:Material.NETHER_BRICKS,false);
    }

    private void buildDistrict(Location c,String type,int v){
        for(int x=-15;x<=15;x++)for(int z=-13;z<=13;z++)c.clone().add(x,0,z).getBlock().setType(
                (x+z)%11==0?Material.MAGMA_BLOCK:Material.BLACKSTONE,false);
        buildHall(c.clone().add(-7,1,0),v);
        buildHall(c.clone().add(8,1,0),v+1);
        if(type.equals("herrerias")){
            for(int x=-10;x<=10;x+=5)c.clone().add(x,1,8).getBlock().setType(Material.BLAST_FURNACE,false);
        } else if(type.equals("tesoros")){
            for(int x=-8;x<=8;x+=4)c.clone().add(x,1,7).getBlock().setType(Material.GOLD_BLOCK,false);
        } else if(type.equals("criaderos")){
            for(int x=-10;x<=10;x++)for(int z=7;z<=11;z++)if(Math.abs(x)==10||z==7||z==11)
                c.clone().add(x,1,z).getBlock().setType(Material.IRON_BARS,false);
        }
    }

    private void buildHall(Location c,int v){
        int w=9,d=11,h=9;
        for(int x=-w;x<=w;x++)for(int z=-d;z<=d;z++)for(int y=0;y<=h;y++){
            boolean edge=Math.abs(x)==w||Math.abs(z)==d;
            Material m=y==0?Material.NETHER_BRICKS:(edge&&y<h?((y==3||y==7)?Material.GILDED_BLACKSTONE:Material.POLISHED_BLACKSTONE_BRICKS):Material.AIR);
            if(y==h)m=(v%2==0?Material.RED_NETHER_BRICKS:Material.NETHER_BRICKS);
            c.clone().add(x,y,z).getBlock().setType(m,false);
        }
        for(int y=1;y<=5;y++)c.clone().add(0,y,-d).getBlock().setType(Material.AIR,false);
        c.clone().add(0,1,0).getBlock().setType(v%2==0?Material.SMITHING_TABLE:Material.BARREL,false);
    }

    private void buildLavaCanals(Location b){
        for(int z=-93;z<=46;z++)for(int x:new int[]{-32,-31,31,32}){
            b.clone().add(x,0,z).getBlock().setType(Material.LAVA,false);
            b.clone().add(x+(x<0?-1:1),0,z).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS,false);
        }
        for(int z=-90;z<=45;z+=25)for(int x=-35;x<=35;x++){
            b.clone().add(x,0,z).getBlock().setType(Material.POLISHED_BLACKSTONE,false);
        }
    }

    private void buildWatchTowers(Location b){
        for(int[] p:new int[][]{{-42,-92},{42,-92},{-72,0},{72,0},{-44,49},{44,49}})tower(b.clone().add(p[0],1,p[1]),6,16);
    }

    private void tower(Location c,int r,int h){
        for(int x=-r;x<=r;x++)for(int z=-r;z<=r;z++)for(int y=0;y<=h;y++){
            boolean edge=Math.abs(x)==r||Math.abs(z)==r;
            c.clone().add(x,y,z).getBlock().setType(y==0||edge?(y%5==0?Material.GILDED_BLACKSTONE:Material.POLISHED_BLACKSTONE_BRICKS):Material.AIR,false);
        }
        c.clone().add(0,h+1,0).getBlock().setType(Material.SOUL_LANTERN,false);
    }

    private void registerEncounters(Location b){
        List<String> patrol=List.of("arlightbosses:gilded_piglin_minion","arlightbosses:gilded_wither_skeleton_vanguard_minion");
        List<String> heavy=List.of("arlightbosses:gilded_hoglin_rider_minion","arlightbosses:gilded_blaze_wraith_minion");
        List<String> lava=List.of("arlightbosses:molten_strider_minion","arlightbosses:gilded_blaze_wraith_minion");
        for(int[]p:new int[][]{{-59,-79},{59,-79},{-60,-35},{60,-35},{-61,27},{61,27}})
            spawners.register(b.clone().add(p[0],1,p[1]),"nether_liberation_city",p[1]>0?heavy:patrol);
        spawners.register(b.clone().add(-31,1,15),"nether_lava_city",lava);
        spawners.register(b.clone().add(31,1,-55),"nether_lava_city",lava);
    }


    /** Cofre compartido: uno de ellos recibe la Llave Ígnea global de la partida. */
    private void placeSharedMissionChest(Location at){
        at.clone().add(0,-1,0).getBlock().setType(Material.GILDED_BLACKSTONE,false);
        at.getBlock().setType(Material.CHEST,false);
        if(at.getBlock().getState() instanceof Chest chest){
            chest.setLootTable(LootTables.BASTION_TREASURE.getLootTable());
            chest.update(true,false);
        }
    }

    private void placeChest(Location at,AdaptiveDungeonLootManager.ChestTier tier){
        at.clone().add(0,-1,0).getBlock().setType(Material.GILDED_BLACKSTONE,false);
        loot.placeCustomChest(at,AdaptiveDungeonLootManager.DimensionGroup.NETHER,tier);
    }

    private void buildBossGate(Location c){
        for(int x=-16;x<=16;x++)for(int y=0;y<=6;y++){
            boolean door=Math.abs(x)<=3&&y<=5;
            c.clone().add(x,y,0).getBlock().setType(door?Material.IRON_BARS:
                    (y==2||y==5?Material.GILDED_BLACKSTONE:Material.POLISHED_BLACKSTONE_BRICKS),false);
        }
    }

    private Location center(Location l){l.setX(l.getBlockX()+0.5);l.setZ(l.getBlockZ()+0.5);return l;}
}
