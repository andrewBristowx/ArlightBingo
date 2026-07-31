package com.arlight.bingo.end;

import com.arlight.bingo.dungeon.AdaptiveDungeonLootManager;
import com.arlight.bingo.dungeon.DungeonMinionSpawnerManager;
import com.arlight.bingo.dungeon.ReferenceDrivenCityPass;
import com.arlight.bingo.dungeon.TerrainAdaptiveCampaignPass;
import com.arlight.bingo.util.CampaignItemBridge;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Directional;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Isla custom completa del End. Antes de construir elimina la isla central,
 * pilares y portales vanilla de la zona de campaña. La ciudadela se integra en
 * una isla orgánica con profundidad real; la arena del dragón ocupa otra isla
 * orgánica conectada mediante un puente ritual.
 */
final class EndCampaignStructure {
    record Bounds(int minX,int maxX,int minY,int maxY,int minZ,int maxZ) {
        boolean contains(Location l){return l!=null&&l.getWorld()!=null&&l.getBlockX()>=minX&&l.getBlockX()<=maxX
                &&l.getBlockY()>=minY&&l.getBlockY()<=maxY&&l.getBlockZ()>=minZ&&l.getBlockZ()<=maxZ;}
        boolean containsXZ(Location l){return l!=null&&l.getWorld()!=null&&l.getBlockX()>=minX&&l.getBlockX()<=maxX
                &&l.getBlockZ()>=minZ&&l.getBlockZ()<=maxZ;}
    }

    record Result(Location arrival, Location golemHome, Bounds golemRoom,
                  List<Location> keyChests, Location altar, Location dragonSpawn,
                  Location exitPortal, Location bridgeGate, List<Location> checkpoints,
                  Bounds campaignBounds, List<Bounds> criticalRegions,
                  List<Location> criticalBlocks, Bounds dragonArena, int rescueY) { }

    private final JavaPlugin plugin;
    private final AdaptiveDungeonLootManager loot;
    private final DungeonMinionSpawnerManager spawners;
    private final List<Location> criticalBlocks = new ArrayList<>();
    private final List<Bounds> criticalRegions = new ArrayList<>();

    EndCampaignStructure(JavaPlugin plugin,AdaptiveDungeonLootManager loot,DungeonMinionSpawnerManager spawners){
        this.plugin=plugin;this.loot=loot;this.spawners=spawners;
    }

    Result build(World world) {
        int y=Math.max(82,Math.min(world.getMaxHeight()-82,plugin.getConfig().getInt("end-campaign.base-y",88)));
        Location city=new Location(world,0,y,-92);
        Location dragonIsland=new Location(world,0,y,68);

        requirePregeneratedChunks(world,-17,17,-20,20);
        if(plugin.getConfig().getBoolean("end-campaign.remove-vanilla-island",true)) {
            removeVanillaCentralIsland(world, y);
        }

        buildOrganicIsland(city,110,88,0x51A7, true);
        buildOrganicIsland(dragonIsland,100,68,0xD4A6, false);
        buildSatelliteFragments(city);
        buildSatelliteFragments(dragonIsland);
        buildCityWall(city);
        buildArrivalDistrict(city);
        buildMarketDistrict(city);
        buildLibraryDistrict(city);
        buildShulkerPrison(city);
        buildChorusGardens(city);
        buildCrystalObservatory(city);
        buildGolemKeep(city);
        buildTreasuryDistrict(city);
        buildLivingCitadelDetails(city);
        ReferenceDrivenCityPass.Expansion referenceCity = ReferenceDrivenCityPass.buildEnd(
                plugin, city, dragonIsland, loot, spawners);
        buildRitualBridge(city,dragonIsland);
        buildDragonArena(dragonIsland);
        buildDragonPillars(dragonIsland);
        TerrainAdaptiveCampaignPass.adaptEnd(plugin, city, dragonIsland, loot);
        registerEncounters(city,dragonIsland);

        List<Location> keyChests=placeMissionAndExplorationLoot(city);
        Location arrival=centered(city.clone().add(0,2,-62)); arrival.setYaw(0); arrival.setPitch(0);
        Location golem=centered(city.clone().add(0,3,30));
        Bounds golemRoom=new Bounds(-26,26,y+1,y+24,-75,-29);

        Location gate=centered(city.clone().add(0,2,62));
        buildBridgeGate(gate);
        criticalRegions.add(new Bounds(-9,9,y,y+13,-35,-25));
        criticalBlocks.add(gate.clone());

        Location altar=centered(dragonIsland.clone().add(0,2,-48));
        buildAltarDais(altar);
        if(!CampaignItemBridge.placeModBlock(altar,CampaignItemBridge.CORRUPTED_ALTAR)) {
            altar.getBlock().setType(Material.RESPAWN_ANCHOR,false);
        }
        criticalRegions.add(new Bounds(-10,10,y-1,y+9,11,31));
        criticalBlocks.add(altar.clone());

        Location dragonSpawn=centered(dragonIsland.clone().add(0,26,8));
        Location exit=centered(dragonIsland.clone().add(0,2,21));
        criticalRegions.add(new Bounds(-7,7,y-1,y+7,84,98));
        criticalBlocks.add(exit.clone());

        for(Location chest:keyChests)criticalBlocks.add(chest.clone());

        List<Location> checkpoints=new ArrayList<>(List.of(
                arrival,
                centered(city.clone().add(0,2,-42)),
                centered(city.clone().add(-34,2,-10)),
                centered(city.clone().add(34,2,-10)),
                centered(city.clone().add(0,2,18)),
                centered(city.clone().add(0,2,54)),
                centered(dragonIsland.clone().add(0,2,-52)),
                centered(dragonIsland.clone().add(0,2,-20)),
                centered(dragonIsland.clone().add(0,2,4))
        ));
        checkpoints.addAll(referenceCity.checkpoints());
        Bounds campaign=new Bounds(-182,182,y-72,y+126,-268,224);
        Bounds dragonArena=new Bounds(-108,108,y-36,y+96,-18,176);
        return new Result(arrival,golem,golemRoom,List.copyOf(keyChests),altar,dragonSpawn,exit,gate,
                List.copyOf(checkpoints),campaign,List.copyOf(criticalRegions),
                List.copyOf(criticalBlocks),dragonArena,y-34);
    }

    /**
     * Borra la isla central vanilla y cualquier isla residual debajo de la
     * ciudadela, el puente y la arena. Se usan tres discos solapados para cubrir
     * toda la campaña sin recorrer un rectángulo gigantesco de aire.
     */
    private void removeVanillaCentralIsland(World world,int baseY){
        int radiusX=Math.max(145,Math.min(260,plugin.getConfig().getInt("end-campaign.clear-radius-x",190)));
        int radiusZ=Math.max(185,Math.min(340,plugin.getConfig().getInt("end-campaign.clear-radius-z",260)));
        int minY=Math.max(world.getMinHeight(),plugin.getConfig().getInt("end-campaign.clear-min-y",-32));
        int configuredTop=plugin.getConfig().getInt("end-campaign.clear-max-y",210);
        int maxY=Math.min(world.getMaxHeight()-1,Math.max(baseY+96,configuredTop));

        // Elimina primero dragón/cristales antiguos. Una arena regenerada no debe
        // heredar entidades ni la batalla vanilla de la seed anterior.
        for(Entity entity:new ArrayList<>(world.getEntities())){
            if(entity.getType()!=EntityType.ENDER_DRAGON&&entity.getType()!=EntityType.END_CRYSTAL)continue;
            Location at=entity.getLocation();
            double nx=at.getX()/radiusX,nz=at.getZ()/radiusZ;
            if(nx*nx+nz*nz<=1.18D)entity.remove();
        }

        // Limpieza elíptica de columna completa. La versión anterior empezaba en
        // baseY-72 y dejaba la parte inferior de la isla vanilla visible debajo de
        // la ciudad custom. Ahora se limpia hasta el mínimo configurado y también
        // estructuras altas/pilares por encima del terreno reportado.
        double rx2=(double)radiusX*radiusX,rz2=(double)radiusZ*radiusZ;
        for(int x=-radiusX;x<=radiusX;x++)for(int z=-radiusZ;z<=radiusZ;z++){
            if((x*x)/rx2+(z*z)/rz2>1.0D)continue;
            // No fuerces una altura artificial en columnas vacías: eso multiplicaba
            // millones de lecturas de aire. El bloque más alto real sigue incluyendo
            // pilares, portales y restos de la isla antigua.
            int highest=Math.min(maxY,world.getHighestBlockYAt(x,z)+10);
            if(highest<minY)continue;
            for(int yy=minY;yy<=highest;yy++){
                Material type=world.getBlockAt(x,yy,z).getType();
                if(!type.isAir())world.getBlockAt(x,yy,z).setType(Material.AIR,false);
            }
        }
    }

    private void requirePregeneratedChunks(World w,int minX,int maxX,int minZ,int maxZ){
        int missing=0;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
            if(!w.isChunkGenerated(x,z)) missing++;
        }
        if(missing>0){
            throw new IllegalStateException("El End custom necesita chunks pregenerados; faltan "
                    +missing+". Chunky debe completar un radio mínimo de 384 antes de construir.");
        }
    }

    private void buildOrganicIsland(Location c,int radiusX,int radiusZ,int seed,boolean cityIsland){
        for(int x=-radiusX-5;x<=radiusX+5;x++)for(int z=-radiusZ-5;z<=radiusZ+5;z++){
            double nx=x/(double)radiusX,nz=z/(double)radiusZ;
            double radial=nx*nx+nz*nz;
            double noise=0.10*Math.sin((x+seed)*0.19)+0.08*Math.cos((z-seed)*0.23)
                    +0.05*Math.sin((x+z)*0.31);
            if(radial>1.0+noise)continue;
            double edge=Math.max(0.0,1.0-radial);
            int depth=4+(int)Math.round(edge*(cityIsland?20:17));
            depth+=Math.floorMod(x*31+z*17+seed,4);
            for(int dy=-depth;dy<=0;dy++){
                double taper=1.0-(Math.abs(dy)/(double)Math.max(1,depth))*0.34;
                if(radial>taper+noise)continue;
                Material material;
                if(dy==0)material=Math.floorMod(x*13+z*29+seed,23)==0?Material.AMETHYST_BLOCK:Material.END_STONE;
                else if(dy<-depth*0.62)material=Math.floorMod(x+z+dy,11)==0?Material.CRYING_OBSIDIAN:Material.OBSIDIAN;
                else material=Math.floorMod(x*7-z*5+dy,17)==0?Material.CALCITE:Material.END_STONE;
                c.clone().add(x,dy,z).getBlock().setType(material,false);
            }
            for(int yy=1;yy<=35;yy++)c.clone().add(x,yy,z).getBlock().setType(Material.AIR,false);
        }
        // Estalactitas inferiores que eliminan el aspecto de disco flotante plano.
        for(int a=0;a<360;a+=12){
            double r=Math.toRadians(a);int x=(int)Math.round(Math.cos(r)*(radiusX-10));
            int z=(int)Math.round(Math.sin(r)*(radiusZ-9));
            int length=5+Math.floorMod(a+seed,13);
            for(int dy=1;dy<=length;dy++)c.clone().add(x,-dy,z).getBlock().setType(
                    dy%5==0?Material.CRYING_OBSIDIAN:Material.END_STONE,false);
        }
    }

    private void buildSatelliteFragments(Location c){
        int[][] points={{-91,-32},{91,-28},{-88,44},{87,48},{-54,81},{57,80}};
        for(int i=0;i<points.length;i++){
            Location s=c.clone().add(points[i][0],-3,points[i][1]);
            int r=9+(i%3)*3;
            for(int x=-r;x<=r;x++)for(int z=-r;z<=r;z++)if(x*x+z*z<=r*r){
                int depth=2+Math.max(0,(r*r-x*x-z*z)/(r*3));
                for(int y=-depth;y<=0;y++)s.clone().add(x,y,z).getBlock().setType(y==0?Material.END_STONE:Material.OBSIDIAN,false);
            }
            s.clone().add(0,1,0).getBlock().setType(Material.AMETHYST_CLUSTER,false);
        }
    }

    private void buildCityWall(Location c){
        for(int a=0;a<360;a+=3){
            double r=Math.toRadians(a);int x=(int)Math.round(Math.cos(r)*96);int z=(int)Math.round(Math.sin(r)*76);
            // Portones sur/norte y aberturas laterales intencionadas.
            if((Math.abs(x)<=8&&Math.abs(z)>=72)||(Math.abs(z)<=6&&Math.abs(x)>=91))continue;
            for(int y=1;y<=9;y++)c.clone().add(x,y,z).getBlock().setType(y==4||y==8?Material.AMETHYST_BLOCK:Material.PURPUR_PILLAR,false);
            if(a%9==0)c.clone().add(x,10,z).getBlock().setType(Material.END_STONE_BRICK_WALL,false);
        }
        for(int[]p:new int[][]{{-80,-56},{80,-56},{-84,54},{84,54}})buildTower(c.clone().add(p[0],1,p[1]),9,25);
    }

    private void buildArrivalDistrict(Location c){
        Location gate=c.clone().add(0,1,-61);
        for(int x=-9;x<=9;x++)for(int y=0;y<=13;y++){
            boolean opening=Math.abs(x)<=5&&y<=8;
            gate.clone().add(x,y,0).getBlock().setType(opening?Material.AIR:(y==4||y==10?Material.AMETHYST_BLOCK:Material.PURPUR_PILLAR),false);
        }
        avenue(c,-60,46,6);
        for(int[]p:new int[][]{{-28,-49},{28,-49},{-43,-35},{43,-35}})buildHouse(c.clone().add(p[0],1,p[1]),9,8,p[0]<0);
        for(int x=-18;x<=18;x+=9)marketStall(c.clone().add(x,1,-40),x%18==0?Material.MAGENTA_WOOL:Material.PURPLE_WOOL);
    }

    private void buildMarketDistrict(Location c){
        for(int x=-38;x<=38;x++)for(int z=-31;z<=-20;z++)c.clone().add(x,0,z).getBlock().setType(
                Math.floorMod(x+z,9)==0?Material.AMETHYST_BLOCK:Material.END_STONE_BRICKS,false);
        for(int x=-30;x<=30;x+=12)marketStall(c.clone().add(x,1,-25),x%24==0?Material.CYAN_WOOL:Material.PURPLE_WOOL);
        Location fountain=c.clone().add(0,1,-25);
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)if(x*x+z*z<=25)fountain.clone().add(x,0,z).getBlock().setType(Material.CRYING_OBSIDIAN,false);
        fountain.getBlock().setType(Material.AMETHYST_CLUSTER,false);
    }

    private void buildLibraryDistrict(Location c){
        Location hall=c.clone().add(-38,1,2);buildHall(hall,15,13,12);
        for(int x=-11;x<=11;x+=4)for(int z=-8;z<=8;z+=4){
            hall.clone().add(x,1,z).getBlock().setType(Material.CHISELED_BOOKSHELF,false);
            hall.clone().add(x,2,z).getBlock().setType(Material.BOOKSHELF,false);
        }
        hall.clone().add(0,1,0).getBlock().setType(Material.LECTERN,false);
        hall.clone().add(0,8,0).getBlock().setType(Material.END_ROD,false);
        customChest(hall.clone().add(-9,1,8),AdaptiveDungeonLootManager.ChestTier.EPIC);
        lootrContainer(hall.clone().add(9,1,8),AdaptiveDungeonLootManager.ChestTier.RARE,Material.CHEST,LootTables.STRONGHOLD_LIBRARY);
    }

    private void buildShulkerPrison(Location c){
        Location hall=c.clone().add(38,1,2);buildHall(hall,15,13,12);
        for(int x=-10;x<=10;x+=5)for(int z=-8;z<=8;z+=8){
            for(int y=1;y<=5;y++)hall.clone().add(x,y,z).getBlock().setType(Material.IRON_BARS,false);
            hall.clone().add(x+1,1,z).getBlock().setType(Material.PURPLE_SHULKER_BOX,false);
        }
        customChest(hall.clone().add(-9,1,8),AdaptiveDungeonLootManager.ChestTier.RARE);
        lootrContainer(hall.clone().add(9,1,8),AdaptiveDungeonLootManager.ChestTier.RARE,Material.BARREL,LootTables.END_CITY_TREASURE);
    }

    private void buildChorusGardens(Location c){
        for(int[]origin:new int[][]{{-35,31},{35,31}}){
            Location g=c.clone().add(origin[0],1,origin[1]);
            for(int x=-16;x<=16;x++)for(int z=-12;z<=12;z++)if(x*x+z*z<=230){
                g.clone().add(x,0,z).getBlock().setType(Math.floorMod(x+z,8)==0?Material.AMETHYST_BLOCK:Material.END_STONE,false);
                if(Math.floorMod(x*17+z*31,19)==0){
                    g.clone().add(x,1,z).getBlock().setType(Material.CHORUS_PLANT,false);
                    g.clone().add(x,2,z).getBlock().setType(Material.CHORUS_FLOWER,false);
                }
            }
        }
    }

    private void buildCrystalObservatory(Location c){
        Location tower=c.clone().add(-42,1,48);buildTower(tower,10,29);
        for(int x=-6;x<=6;x++)for(int z=-6;z<=6;z++)tower.clone().add(x,18,z).getBlock().setType(
                Math.abs(x)==6||Math.abs(z)==6?Material.TINTED_GLASS:Material.PURPUR_BLOCK,false);
        tower.clone().add(0,20,0).getBlock().setType(Material.BEACON,false);
        customChest(tower.clone().add(0,1,4),AdaptiveDungeonLootManager.ChestTier.EPIC);
    }

    private void buildGolemKeep(Location c){
        Location keep=c.clone().add(0,1,30);
        for(int x=-25;x<=25;x++)for(int z=-20;z<=20;z++)for(int y=0;y<=22;y++){
            boolean edge=Math.abs(x)==25||Math.abs(z)==20;
            Material m=Material.AIR;
            if(y==0)m=Math.floorMod(x+z,7)==0?Material.CRYING_OBSIDIAN:Material.END_STONE_BRICKS;
            else if(edge&&y<22)m=y==6||y==14?Material.AMETHYST_BLOCK:Material.PURPUR_PILLAR;
            else if(y==22)m=Material.PURPUR_BLOCK;
            keep.clone().add(x,y,z).getBlock().setType(m,false);
        }
        for(int x=-6;x<=6;x++)for(int y=1;y<=10;y++)keep.clone().add(x,y,-20).getBlock().setType(Material.AIR,false);
        for(int[]p:new int[][]{{-16,-10},{16,-10},{-16,10},{16,10}}){
            for(int y=1;y<=6;y++)keep.clone().add(p[0],y,p[1]).getBlock().setType(Material.CRYING_OBSIDIAN,false);
            keep.clone().add(p[0],7,p[1]).getBlock().setType(Material.AMETHYST_CLUSTER,false);
        }
        // Balcones/cobertura para que la sala no sea una caja vacía.
        for(int x=-22;x<=22;x++)for(int z:new int[]{-16,16})keep.clone().add(x,7,z).getBlock().setType(Material.PURPUR_BLOCK,false);
        for(int z=-14;z<=14;z++)for(int x:new int[]{-20,20})keep.clone().add(x,7,z).getBlock().setType(Material.PURPUR_BLOCK,false);
        criticalRegions.add(new Bounds(-27,27,c.getBlockY(),c.getBlockY()+25,-84,-28));
    }

    private void buildTreasuryDistrict(Location c){
        Location treasury=c.clone().add(40,1,47);buildHall(treasury,13,11,11);
        for(int x=-9;x<=9;x+=3)for(int z=-7;z<=7;z+=3)treasury.clone().add(x,1,z).getBlock().setType(
                Math.floorMod(x+z,4)==0?Material.AMETHYST_BLOCK:Material.GOLD_BLOCK,false);
        customChest(treasury.clone().add(-7,1,7),AdaptiveDungeonLootManager.ChestTier.LEGENDARY);
        lootrContainer(treasury.clone().add(7,1,7),AdaptiveDungeonLootManager.ChestTier.EPIC,Material.CHEST,LootTables.END_CITY_TREASURE);
    }

    private void buildRitualBridge(Location city,Location dragon){
        int start=city.getBlockZ()+62,end=dragon.getBlockZ()-51;
        for(int z=start;z<=end;z++)for(int x=-7;x<=7;x++){
            Location at=new Location(city.getWorld(),city.getBlockX()+x,city.getBlockY()+1,z);
            at.getBlock().setType(Math.floorMod(x+z,9)==0?Material.AMETHYST_BLOCK:Material.END_STONE_BRICKS,false);
            for(int y=1;y<=8;y++)at.clone().add(0,y,0).getBlock().setType(Material.AIR,false);
            if(Math.abs(x)==7)at.clone().add(0,1,0).getBlock().setType(Material.END_STONE_BRICK_WALL,false);
            if(Math.floorMod(z,13)==0&&Math.abs(x)==6)at.clone().add(0,3,0).getBlock().setType(Material.END_ROD,false);
        }
    }

    private void buildDragonArena(Location c){
        for(int r:new int[]{12,28,45,60})for(int a=0;a<360;a+=8){
            double rad=Math.toRadians(a);int x=(int)Math.round(Math.cos(rad)*r),z=(int)Math.round(Math.sin(rad)*r);
            c.clone().add(x,1,z).getBlock().setType(r==12?Material.CRYING_OBSIDIAN:(r==60?Material.END_STONE_BRICKS:Material.PURPUR_BLOCK),false);
        }
        // Ruinas/cobertura y plataformas de recuperación.
        for(int[]p:new int[][]{{-30,-18},{30,-18},{-35,18},{35,18},{0,38}}){
            Location ruin=c.clone().add(p[0],1,p[1]);
            for(int x=-5;x<=5;x++)for(int z=-4;z<=4;z++)ruin.clone().add(x,0,z).getBlock().setType(Material.END_STONE_BRICKS,false);
            for(int y=1;y<=7;y++)for(int x:new int[]{-5,5})ruin.clone().add(x,y,0).getBlock().setType(y%3==0?Material.AMETHYST_BLOCK:Material.PURPUR_PILLAR,false);
        }
    }

    private void buildDragonPillars(Location c){
        int[][] positions={{-55,-33},{-27,-58},{27,-58},{55,-33},{62,8},{39,51},{0,63},{-39,51},{-62,8}};
        for(int i=0;i<positions.length;i++){
            int h=24+(i%3)*8;Location p=c.clone().add(positions[i][0],1,positions[i][1]);
            for(int y=0;y<h;y++)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)if(x*x+z*z<=5)
                p.clone().add(x,y,z).getBlock().setType(y%10==0?Material.CRYING_OBSIDIAN:Material.OBSIDIAN,false);
            for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)p.clone().add(x,h,z).getBlock().setType(
                    Math.abs(x)==4||Math.abs(z)==4?Material.PURPUR_BLOCK:Material.AMETHYST_BLOCK,false);
            p.clone().add(0,h+1,0).getBlock().setType(Material.AMETHYST_CLUSTER,false);
        }
    }


    /**
     * Añade vida a la ciudadela: viviendas rotas, talleres de shulkers,
     * callejones, puentes altos, puestos de mercado, jardines y ruinas. Cada
     * conjunto usa una silueta distinta para evitar el efecto copy/paste.
     */
    private void buildLivingCitadelDetails(Location city){
        // Casas y talleres asimétricos.
        int[][] homes={{-49,-38,0},{-31,-42,1},{31,-42,0},{49,-38,1},{-54,-2,1},{54,-2,0},{-43,28,0},{43,28,1}};
        for(int[]p:homes){
            Location h=city.clone().add(p[0],1,p[1]);
            if(p[2]==0)buildRuinedEndHouse(h,6,5);else buildCrystalWorkshop(h,6,5);
        }
        // Callejones elevados y puentes entre barrios.
        for(int z=-48;z<=42;z+=18){
            buildHighBridge(city.clone().add(-42,7,z),city.clone().add(42,7,z));
        }
        // Jardines, estatuas y pequeños puntos de interés.
        for(int[]p:new int[][]{{-20,-51},{20,-51},{-51,15},{51,15},{-24,44},{24,44}}){
            buildChorusCourtyard(city.clone().add(p[0],1,p[1]));
        }
        for(int[]p:new int[][]{{-62,-31},{62,-31},{-61,31},{61,31}}){
            buildVoidStatue(city.clone().add(p[0],1,p[1]));
        }
        // Escombros y fragmentos de geoda en calles para romper superficies planas.
        for(int x=-58;x<=58;x+=8)for(int z=-54;z<=52;z+=11){
            int hash=Math.floorMod(x*31+z*17,19);
            if(hash<5){
                Location at=city.clone().add(x,2,z);
                at.getBlock().setType(hash==0?Material.BUDDING_AMETHYST:(hash<3?Material.PURPUR_SLAB:Material.END_STONE_BRICK_WALL),false);
                if(hash==0)at.clone().add(0,1,0).getBlock().setType(Material.SMALL_AMETHYST_BUD,false);
            }
        }
        // Iluminación y señales de ruta sin llenar todo de líneas idénticas.
        for(int z=-58;z<=54;z+=12){
            for(int x:new int[]{-10,10}){
                Location post=city.clone().add(x,2,z);
                post.getBlock().setType(Material.PURPUR_PILLAR,false);
                post.clone().add(0,1,0).getBlock().setType(Material.END_ROD,false);
            }
        }
    }

    private void buildRuinedEndHouse(Location c,int w,int d){
        for(int x=-w;x<=w;x++)for(int z=-d;z<=d;z++)for(int y=0;y<=8;y++){
            boolean edge=Math.abs(x)==w||Math.abs(z)==d;
            int damage=Math.floorMod(x*11+z*7+y*13,23);
            Material m=Material.AIR;
            if(y==0)m=Material.END_STONE_BRICKS;
            else if(edge&&y<8&&damage>4)m=(y==4?Material.AMETHYST_BLOCK:Material.PURPUR_BLOCK);
            else if(y==8&&damage>7)m=Material.PURPUR_SLAB;
            c.clone().add(x,y,z).getBlock().setType(m,false);
        }
        for(int y=1;y<=4;y++)c.clone().add(0,y,-d).getBlock().setType(Material.AIR,false);
        c.clone().add(-3,1,1).getBlock().setType(Material.PURPLE_BED,false);
        c.clone().add(3,1,1).getBlock().setType(Material.BARREL,false);
        c.clone().add(0,1,3).getBlock().setType(Material.CHISELED_BOOKSHELF,false);
        c.clone().add(2,2,-1).getBlock().setType(Material.COBWEB,false);
    }

    private void buildCrystalWorkshop(Location c,int w,int d){
        buildRuinedEndHouse(c,w,d);
        for(int x=-3;x<=3;x+=3)for(int z=-2;z<=2;z+=2){
            c.clone().add(x,1,z).getBlock().setType(Material.AMETHYST_BLOCK,false);
            c.clone().add(x,2,z).getBlock().setType(Material.AMETHYST_CLUSTER,false);
        }
        c.clone().add(0,1,2).getBlock().setType(Material.SMITHING_TABLE,false);
        c.clone().add(0,2,2).getBlock().setType(Material.END_ROD,false);
    }

    private void buildHighBridge(Location from,Location to){
        int min=Math.min(from.getBlockX(),to.getBlockX()),max=Math.max(from.getBlockX(),to.getBlockX());
        int y=from.getBlockY(),z=from.getBlockZ();
        for(int x=min;x<=max;x++){
            new Location(from.getWorld(),x,y,z).getBlock().setType((x%9==0)?Material.AMETHYST_BLOCK:Material.END_STONE_BRICKS,false);
            new Location(from.getWorld(),x,y+1,z-2).getBlock().setType(Material.END_STONE_BRICK_WALL,false);
            new Location(from.getWorld(),x,y+1,z+2).getBlock().setType(Material.END_STONE_BRICK_WALL,false);
            for(int dz=-1;dz<=1;dz++)new Location(from.getWorld(),x,y,z+dz).getBlock().setType(Material.END_STONE_BRICKS,false);
        }
    }

    private void buildChorusCourtyard(Location c){
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)if(x*x+z*z<=28)
            c.clone().add(x,0,z).getBlock().setType((x+z)%5==0?Material.AMETHYST_BLOCK:Material.END_STONE,false);
        for(int[]p:new int[][]{{-3,-2},{3,-2},{-3,2},{3,2},{0,0}}){
            c.clone().add(p[0],1,p[1]).getBlock().setType(Material.CHORUS_PLANT,false);
            c.clone().add(p[0],2,p[1]).getBlock().setType(Material.CHORUS_FLOWER,false);
        }
    }

    private void buildVoidStatue(Location c){
        for(int y=0;y<=8;y++)c.clone().add(0,y,0).getBlock().setType(y%3==0?Material.CRYING_OBSIDIAN:Material.OBSIDIAN,false);
        c.clone().add(-1,9,0).getBlock().setType(Material.AMETHYST_CLUSTER,false);
        c.clone().add(1,9,0).getBlock().setType(Material.AMETHYST_CLUSTER,false);
        for(int x=-3;x<=3;x++)c.clone().add(x,4,0).getBlock().setType(Material.PURPUR_PILLAR,false);
    }

    private List<Location> placeMissionAndExplorationLoot(Location city){
        List<Location> keys=new ArrayList<>();
        int[][] keyPoints={{-52,-49},{52,-49},{-52,-8},{52,-8},{-48,38},{48,38},{-20,48},{20,48},{0,-45},{0,50}};
        for(int[]p:keyPoints){Location at=city.clone().add(p[0],2,p[1]);placeSharedMissionChest(at);keys.add(at.clone());}
        int[][] common={{-30,-51},{30,-51},{-57,-24},{57,-24},{-27,4},{27,4},{-51,26},{51,26},{-12,-34},{12,-34},{-43,10},{43,10},{-24,36},{24,36}};
        for(int[]p:common)lootrContainer(city.clone().add(p[0],2,p[1]),AdaptiveDungeonLootManager.ChestTier.COMMON,Material.BARREL,LootTables.END_CITY_TREASURE);
        int[][] rare={{-40,-16},{40,-16},{-33,22},{33,22},{-12,47},{12,47},{-58,4},{58,4},{-34,49},{34,49}};
        for(int[]p:rare)customChest(city.clone().add(p[0],2,p[1]),AdaptiveDungeonLootManager.ChestTier.RARE);
        return keys;
    }

    private void registerEncounters(Location city,Location dragon){
        List<String> normal=List.of("arlightbosses:void_enderman_minion","arlightbosses:corrupted_ender_mite_minion");
        List<String> ranged=List.of("arlightbosses:amethyst_shulker_minion","arlightbosses:amethyst_eye_minion");
        List<String> aerial=List.of("arlightbosses:amethyst_phantom_minion","arlightbosses:amethyst_guardian_shard_minion");
        int district=0;
        for(int[]p:new int[][]{{-47,-46},{47,-46},{-55,-4},{55,-4},{-42,35},{42,35}}){
            Location shrine=city.clone().add(p[0],2,p[1]);buildGeodeSpawner(shrine);
            spawners.register(shrine,"end_citadel_district_"+(++district),p[1]>20?aerial:(p[1]<-20?normal:ranged));
        }
        for(int[]p:new int[][]{{-45,-30},{45,-30},{-48,25},{48,25}}){
            Location shrine=dragon.clone().add(p[0],2,p[1]);buildGeodeSpawner(shrine);
            spawners.register(shrine,"end_dragon_outer",aerial,false);
        }
    }

    private void buildGeodeSpawner(Location c){
        c.clone().add(0,-1,0).getBlock().setType(Material.BUDDING_AMETHYST,false);
        for(int[]p:new int[][]{{-2,0},{2,0},{0,-2},{0,2}}){
            c.clone().add(p[0],0,p[1]).getBlock().setType(Material.AMETHYST_BLOCK,false);
            c.clone().add(p[0],1,p[1]).getBlock().setType(Material.AMETHYST_CLUSTER,false);
        }
    }

    private void buildBridgeGate(Location g){
        for(int x=-8;x<=8;x++)for(int y=-1;y<=10;y++)g.clone().add(x,y,0).getBlock().setType(
                Math.abs(x)<=4&&y<=6?Material.IRON_BARS:(y==3||y==8?Material.AMETHYST_BLOCK:Material.PURPUR_PILLAR),false);
    }

    private void buildAltarDais(Location a){
        for(int x=-9;x<=9;x++)for(int z=-9;z<=9;z++)if(x*x+z*z<=81){
            a.clone().add(x,-1,z).getBlock().setType(Math.floorMod(x+z,4)==0?Material.AMETHYST_BLOCK:Material.CRYING_OBSIDIAN,false);
        }
        for(int i=0;i<12;i++){double r=Math.toRadians(i*30);int x=(int)Math.round(Math.cos(r)*8),z=(int)Math.round(Math.sin(r)*8);
            a.clone().add(x,0,z).getBlock().setType(Material.END_ROD,false);}
    }

    private void avenue(Location c,int startZ,int endZ,int halfWidth){
        for(int z=startZ;z<=endZ;z++)for(int x=-halfWidth;x<=halfWidth;x++){
            c.clone().add(x,1,z).getBlock().setType(Math.floorMod(x+z,10)==0?Material.AMETHYST_BLOCK:Material.END_STONE_BRICKS,false);
            for(int y=2;y<=8;y++)c.clone().add(x,y,z).getBlock().setType(Material.AIR,false);
        }
    }

    private void buildHouse(Location c,int w,int d,boolean mirrored){
        int h=10;
        for(int x=-w-2;x<=w+2;x++)for(int z=-d-2;z<=d+2;z++)for(int y=0;y<=h+8;y++)
            c.clone().add(x,y,z).getBlock().setType(Material.AIR,false);
        adaptiveSupportFootprint(c,w,d,36);
        for(int x=-w;x<=w;x++)for(int z=-d;z<=d;z++){
            c.clone().add(x,0,z).getBlock().setType(Material.END_STONE_BRICKS,false);
            for(int y=1;y<=h;y++){
                boolean edge=Math.abs(x)==w||Math.abs(z)==d;
                Material m=Material.AIR;
                if(edge){
                    boolean rib=(Math.abs(x)==w&&Math.floorMod(z+d,4)==0)
                            ||(Math.abs(z)==d&&Math.floorMod(x+w,4)==0);
                    m=rib||y==4||y==8?Material.PURPUR_PILLAR:Material.PURPUR_BLOCK;
                    if((y==3||y==7)&&Math.floorMod(x+z,4)==0)m=Material.PURPLE_STAINED_GLASS_PANE;
                    if(mirrored&&y>6&&Math.floorMod(x*17+z*11+y,29)<3)m=Material.AIR;
                }
                c.clone().add(x,y,z).getBlock().setType(m,false);
            }
        }
        for(int y=1;y<=4;y++)c.clone().add(0,y,-d).getBlock().setType(Material.AIR,false);
        for(int x=-w+1;x<=w-1;x++)for(int z=-d+1;z<=d-1;z++)
            c.clone().add(x,5,z).getBlock().setType(Math.floorMod(x+z,9)==0?Material.AMETHYST_BLOCK:Material.PURPUR_BLOCK,false);
        // Tejado a dos aguas con nervaduras de amatista: elimina la caja plana.
        for(int layer=0;layer<=w+1;layer++){
            int ox=w+1-layer;
            for(int z=-d-1;z<=d+1;z++)for(int x:new int[]{-ox,ox})
                c.clone().add(x,h+1+layer,z).getBlock().setType(layer%3==0?Material.AMETHYST_BLOCK:Material.PURPUR_BLOCK,false);
        }
        c.clone().add(mirrored?-w+2:w-2,1,2).getBlock().setType(Material.CHISELED_BOOKSHELF,false);
        c.clone().add(mirrored?w-2:-w+2,1,2).getBlock().setType(Material.PURPLE_BED,false);
        c.clone().add(0,2,0).getBlock().setType(Material.END_ROD,false);
    }

    private void buildHall(Location c,int w,int d,int h){
        for(int x=-w-3;x<=w+3;x++)for(int z=-d-3;z<=d+3;z++)for(int y=0;y<=h+11;y++)
            c.clone().add(x,y,z).getBlock().setType(Material.AIR,false);
        adaptiveSupportFootprint(c,w,d,42);
        for(int x=-w;x<=w;x++)for(int z=-d;z<=d;z++)for(int y=0;y<=h;y++){
            boolean edge=Math.abs(x)==w||Math.abs(z)==d;
            Material m=Material.AIR;
            if(y==0)m=Math.floorMod(x+z,7)==0?Material.AMETHYST_BLOCK:Material.END_STONE_BRICKS;
            else if(edge){
                boolean buttress=(Math.abs(x)==w&&Math.floorMod(z+d,6)==0)
                        ||(Math.abs(z)==d&&Math.floorMod(x+w,6)==0);
                m=buttress||y==5||y==10?Material.CRYING_OBSIDIAN:Material.PURPUR_PILLAR;
                if((y>=3&&y<=7||y>=11&&y<=h-2)&&Math.floorMod(x+z,6)==0)
                    m=Material.PURPLE_STAINED_GLASS_PANE;
            }
            c.clone().add(x,y,z).getBlock().setType(m,false);
        }
        for(int y=1;y<=6;y++)c.clone().add(0,y,-d).getBlock().setType(Material.AIR,false);
        // Naves laterales y columnas interiores para que el edificio sea funcional.
        for(int z=-d+4;z<=d-4;z+=6)for(int y=1;y<=Math.min(h-2,14);y++){
            c.clone().add(-w/2,y,z).getBlock().setType(y%5==0?Material.AMETHYST_BLOCK:Material.PURPUR_PILLAR,false);
            c.clone().add(w/2,y,z).getBlock().setType(y%5==0?Material.AMETHYST_BLOCK:Material.PURPUR_PILLAR,false);
        }
        // Bóveda escalonada en lugar del techo plano.
        int layers=Math.min(9,w);
        for(int layer=0;layer<=layers;layer++){
            int ox=w-layer;
            for(int z=-d-1;z<=d+1;z++)for(int x:new int[]{-ox,ox})
                c.clone().add(x,h+1+layer,z).getBlock().setType(layer%2==0?Material.PURPUR_BLOCK:Material.AMETHYST_BLOCK,false);
        }
        for(int z=-d+3;z<=d-3;z+=5){
            c.clone().add(-3,1,z).getBlock().setType(Material.PURPUR_STAIRS,false);
            c.clone().add(3,1,z).getBlock().setType(Material.PURPUR_STAIRS,false);
        }
    }

    private void buildTower(Location c,int r,int h){
        for(int x=-r-2;x<=r+2;x++)for(int z=-r-2;z<=r+2;z++)for(int y=0;y<=h+5;y++)
            c.clone().add(x,y,z).getBlock().setType(Material.AIR,false);
        adaptiveSupportFootprint(c,r,r,48);
        for(int x=-r;x<=r;x++)for(int z=-r;z<=r;z++)for(int y=0;y<=h;y++){
            boolean cornerCut=Math.abs(x)+Math.abs(z)>r*2-2;
            boolean edge=Math.max(Math.abs(x),Math.abs(z))==r&&!cornerCut;
            Material m=y==0?Material.END_STONE_BRICKS:(edge?(y%6==0?Material.AMETHYST_BLOCK:Material.PURPUR_PILLAR):Material.AIR);
            c.clone().add(x,y,z).getBlock().setType(m,false);
        }
        for(int y=1;y<=5;y++)c.clone().add(0,y,-r).getBlock().setType(Material.AIR,false);
        for(int x=-r-2;x<=r+2;x++)for(int z=-r-2;z<=r+2;z++)
            if(Math.max(Math.abs(x),Math.abs(z))==r+2&&Math.abs(x)+Math.abs(z)<=r*2+2)
                c.clone().add(x,h+1,z).getBlock().setType(Material.PURPUR_SLAB,false);
        c.clone().add(0,h+2,0).getBlock().setType(Material.END_ROD,false);
        c.clone().add(0,h-2,0).getBlock().setType(Material.RESPAWN_ANCHOR,false);
    }

    private void adaptiveSupportFootprint(Location center,int halfWidth,int halfDepth,int maximumDepth){
        int step=Math.max(3,Math.min(6,Math.min(halfWidth,halfDepth)/2+2));
        for(int x=-halfWidth;x<=halfWidth;x++)for(int z=-halfDepth;z<=halfDepth;z++){
            boolean perimeter=Math.abs(x)==halfWidth||Math.abs(z)==halfDepth;
            boolean grid=Math.floorMod(x+halfWidth,step)==0&&Math.floorMod(z+halfDepth,step)==0;
            if(!perimeter&&!grid)continue;
            Location anchor=center.clone().add(x,-1,z);
            int depth=0;
            while(depth<maximumDepth&&!anchor.getBlock().getType().isSolid()){
                Material material=(depth%5==0)?Material.AMETHYST_BLOCK:
                        (perimeter?Material.END_STONE_BRICKS:Material.PURPUR_PILLAR);
                anchor.getBlock().setType(material,false);
                anchor.subtract(0,1,0);
                depth++;
            }
            if(depth>=maximumDepth){
                // Si debajo sólo hay vacío, termina en un contrapeso afilado en vez de
                // dejar una columna cortada de forma abrupta.
                for(int taper=0;taper<5;taper++){
                    Location tip=anchor.clone().add(0,-taper,0);
                    tip.getBlock().setType(taper==4?Material.END_ROD:Material.AMETHYST_BLOCK,false);
                }
            }
        }
    }

    private void marketStall(Location c,Material canopy){
        for(int x=-3;x<=3;x++)for(int z=-2;z<=2;z++)c.clone().add(x,0,z).getBlock().setType(Material.END_STONE_BRICKS,false);
        for(int x:new int[]{-3,3})for(int z:new int[]{-2,2})for(int y=1;y<=4;y++)c.clone().add(x,y,z).getBlock().setType(Material.PURPUR_PILLAR,false);
        for(int x=-3;x<=3;x++)for(int z=-2;z<=2;z++)c.clone().add(x,5,z).getBlock().setType(canopy,false);
        c.clone().add(0,1,0).getBlock().setType(Material.BARREL,false);
    }

    private void placeSharedMissionChest(Location at){
        at.clone().add(0,-1,0).getBlock().setType(Material.END_STONE_BRICKS,false);
        at.clone().add(0,1,0).getBlock().setType(Material.AIR,false);
        at.getBlock().setType(Material.CHEST,false);
        if(at.getBlock().getState() instanceof Chest chest){chest.setLootTable(LootTables.END_CITY_TREASURE.getLootTable());chest.update(true,false);}
    }

    private void customChest(Location at,AdaptiveDungeonLootManager.ChestTier tier){
        at.clone().add(0,-1,0).getBlock().setType(Material.END_STONE_BRICKS,false);
        at.clone().add(0,1,0).getBlock().setType(Material.AIR,false);
        if(loot!=null)loot.placeCustomChest(at,AdaptiveDungeonLootManager.DimensionGroup.END,tier);
    }

    private void lootrContainer(Location at,AdaptiveDungeonLootManager.ChestTier tier,Material type,LootTables table){
        at.clone().add(0,-1,0).getBlock().setType(Material.END_STONE_BRICKS,false);
        at.clone().add(0,1,0).getBlock().setType(Material.AIR,false);
        at.getBlock().setType(type,false);
        if(at.getBlock().getState() instanceof Chest chest){chest.setLootTable(table.getLootTable());chest.update(true,false);}
        else if(at.getBlock().getState() instanceof Barrel barrel){barrel.setLootTable(table.getLootTable());barrel.update(true,false);}
        if(at.getBlock().getBlockData() instanceof Directional directional){directional.setFacing(BlockFace.SOUTH);at.getBlock().setBlockData(directional,false);}
        if(loot!=null)loot.registerChest(at,AdaptiveDungeonLootManager.DimensionGroup.END,tier);
    }

    private Location centered(Location l){l.setX(l.getBlockX()+0.5);l.setZ(l.getBlockZ()+0.5);return l;}
}
