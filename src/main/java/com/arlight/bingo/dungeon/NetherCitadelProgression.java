package com.arlight.bingo.dungeon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Ciudadela militar del Nether:
 * llegada segura -> puente -> puerta fortificada -> patio de lava y barrios
 * -> fortaleza central vertical -> arena del Guardián -> cámara segura del End.
 *
 * Los enemigos se crean exclusivamente mediante spawners reales configurados
 * con los IDs de los huevos de ArlightBosses 1.9.0.
 */
final class NetherCitadelProgression {
    private static final int FLOOR_HEIGHT = 8;
    private static final int WALL_X = 38;
    private static final int FRONT_Z = -42;
    private static final int BACK_Z = 34;
    private static final int KEEP_X = 18;
    private static final int KEEP_FRONT_Z = -2;
    private static final int KEEP_BACK_Z = 30;

    record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) { }

    record Result(Bounds bounds, Location arrival, Location bossHome, Location portal,
                  Bounds bossRoom, Bounds portalRoom, Location portalFallback,
                  List<Location> checkpoints, int rescueY, List<Location> keyChests,
                  Location keyLock, Location gateCenter) { }

    private final JavaPlugin plugin;
    private final AdaptiveDungeonLootManager adaptiveLoot;
    private final DungeonMinionSpawnerManager spawners;

    NetherCitadelProgression(JavaPlugin plugin, AdaptiveDungeonLootManager adaptiveLoot,
                             DungeonMinionSpawnerManager spawners) {
        this.plugin = plugin;
        this.adaptiveLoot = adaptiveLoot;
        this.spawners = spawners;
    }

    Result build(Location base, int combatLevels) {
        int bossLevel = combatLevels - 1;
        int portalLevel = combatLevels;
        int roofY = (portalLevel + 1) * FLOOR_HEIGHT;

        loadChunks(base, 12);
        clearConstructionVolume(base, roofY + 18);
        buildFoundation(base);
        integrateWithNetherTerrain(base);
        buildArrivalAndBridge(base);
        buildOuterWalls(base);
        buildGatehouse(base);
        buildDistricts(base);
        buildLowerLavaTunnels(base);
        buildCentralKeep(base, bossLevel, portalLevel);
        buildCornerTowers(base, roofY);
        registerEncounters(base, bossLevel);
        placeLoot(base, bossLevel, portalLevel);
        NetherLiberationExpansion.Result liberation = new NetherLiberationExpansion(
                plugin, adaptiveLoot, spawners).build(base, bossLevel);
        CampaignStructureDetails.decorateNether(base, adaptiveLoot, spawners);
        ReferenceDrivenCityPass.Expansion referenceCity = ReferenceDrivenCityPass.buildNether(
                plugin, base, adaptiveLoot, spawners);
        TerrainAdaptiveCampaignPass.adaptNether(plugin, base, adaptiveLoot);

        Location arrival = centered(base.clone().add(0, 1, -99));
        Location bossHome = centered(base.clone().add(0, bossLevel * FLOOR_HEIGHT + 1, 14));
        Location portal = centered(base.clone().add(0, portalLevel * FLOOR_HEIGHT + 1, 15));
        Location portalFallback = centered(base.clone().add(0, bossLevel * FLOOR_HEIGHT + 1, 23));

        int minY = base.getBlockY() - 10;
        int maxY = base.getBlockY() + roofY + 18;
        Bounds all = new Bounds(base.getBlockX() - 182,
                base.getBlockX() + 182,
                minY, maxY + 70, base.getBlockZ() - 172,
                base.getBlockZ() + 136);
        Bounds bossRoom = new Bounds(base.getBlockX() - KEEP_X + 2, base.getBlockX() + KEEP_X - 2,
                base.getBlockY() + bossLevel * FLOOR_HEIGHT,
                base.getBlockY() + bossLevel * FLOOR_HEIGHT + FLOOR_HEIGHT - 1,
                base.getBlockZ() + KEEP_FRONT_Z + 2, base.getBlockZ() + KEEP_BACK_Z - 2);
        Bounds portalRoom = new Bounds(base.getBlockX() - KEEP_X + 2, base.getBlockX() + KEEP_X - 2,
                base.getBlockY() + portalLevel * FLOOR_HEIGHT,
                base.getBlockY() + portalLevel * FLOOR_HEIGHT + FLOOR_HEIGHT - 1,
                base.getBlockZ() + KEEP_FRONT_Z + 2, base.getBlockZ() + KEEP_BACK_Z - 2);

        List<Location> checkpoints = new ArrayList<>(liberation.checkpoints());
        checkpoints.addAll(referenceCity.checkpoints());
        checkpoints.add(centered(base.clone().add(0, 1, -36)));
        checkpoints.add(centered(base.clone().add(0, 1, -7)));
        checkpoints.add(centered(base.clone().add(12, FLOOR_HEIGHT + 1, 3)));
        checkpoints.add(centered(base.clone().add(0, bossLevel * FLOOR_HEIGHT + 1, 1)));
        return new Result(all, arrival, bossHome, portal, bossRoom, portalRoom,
                portalFallback, List.copyOf(checkpoints), base.getBlockY() - 8,
                liberation.keyChests(), liberation.keyLock(), liberation.gateCenter());
    }

    private void loadChunks(Location base, int radius) {
        World world = base.getWorld();
        if (world == null) return;
        int centerX = base.getBlockX() >> 4;
        int centerZ = base.getBlockZ() >> 4;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) world.getChunkAt(centerX + x, centerZ + z).load(true);
        }
    }

    private void clearConstructionVolume(Location base, int top) {
        if (!plugin.getConfig().getBoolean("nether-citadel.clear-volume", true)) return;
        // Se limpia solo la huella útil. Evita netherrack/lava atravesando edificios,
        // pero conserva el resto del Nether y limita el coste de generación.
        for (int x = -WALL_X - 4; x <= WALL_X + 4; x++) {
            for (int z = -56; z <= BACK_Z + 5; z++) {
                for (int y = 0; y <= top; y++) {
                    base.clone().add(x, y, z).getBlock().setType(Material.AIR, false);
                }
            }
        }
    }

    private void buildFoundation(Location base) {
        for (int x = -WALL_X - 3; x <= WALL_X + 3; x++) {
            for (int z = FRONT_Z - 2; z <= BACK_Z + 3; z++) {
                Location floor = base.clone().add(x, 0, z);
                floor.getBlock().setType(((x + z) & 7) == 0
                        ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS, false);
                floor.clone().add(0, -1, 0).getBlock().setType(Material.BLACKSTONE, false);
            }
        }
        for (int x = -WALL_X; x <= WALL_X; x += 8) {
            supportToGround(base.clone().add(x, -2, FRONT_Z), Material.POLISHED_BASALT, 96);
            supportToGround(base.clone().add(x, -2, BACK_Z), Material.POLISHED_BASALT, 96);
        }
        for (int z = FRONT_Z; z <= BACK_Z; z += 8) {
            supportToGround(base.clone().add(-WALL_X, -2, z), Material.POLISHED_BASALT, 96);
            supportToGround(base.clone().add(WALL_X, -2, z), Material.POLISHED_BASALT, 96);
        }
    }


    /**
     * Une la fortaleza a la caverna del Nether con contrafuertes, terrazas,
     * puentes laterales y roca irregular. Evita el efecto de caja suspendida.
     */
    private void integrateWithNetherTerrain(Location base) {
        World world = base.getWorld();
        if (world == null) return;
        int baseY = base.getBlockY();
        // Contrafuertes que bajan hasta roca real.
        for (int x=-WALL_X-3;x<=WALL_X+3;x+=7) {
            netherButtress(base.clone().add(x,-1,FRONT_Z-2));
            netherButtress(base.clone().add(x,-1,BACK_Z+3));
        }
        for (int z=FRONT_Z;z<=BACK_Z;z+=7) {
            netherButtress(base.clone().add(-WALL_X-3,-1,z));
            netherButtress(base.clone().add(WALL_X+3,-1,z));
        }
        // Terrazas exteriores que se mezclan con netherrack/basalto del bioma.
        for(int ring=1;ring<=12;ring++){
            Material top=ring<4?Material.POLISHED_BLACKSTONE_BRICKS:(ring<8?Material.BLACKSTONE:Material.NETHERRACK);
            int yOff=-(ring/3);
            for(int x=-WALL_X-ring;x<=WALL_X+ring;x++){
                if(Math.floorMod(x+ring,3)!=0)continue;
                set(base,x,yOff,FRONT_Z-ring,top);set(base,x,yOff,BACK_Z+ring,top);
            }
            for(int z=FRONT_Z-ring;z<=BACK_Z+ring;z++){
                if(Math.floorMod(z+ring,3)!=0)continue;
                set(base,-WALL_X-ring,yOff,z,top);set(base,WALL_X+ring,yOff,z,top);
            }
        }
        // Dos conexiones laterales hacia el terreno nativo, útiles como rutas opcionales.
        for(int dir:new int[]{-1,1}){
            for(int x=0;x<=28;x++){
                int rx=dir*(WALL_X+x);int y=2-(x/9);int z=-8+(x/4);
                for(int dz=-2;dz<=2;dz++)set(base,rx,y,z+dz,(x+dz)%5==0?Material.GILDED_BLACKSTONE:Material.BLACKSTONE);
                set(base,rx,y+1,z-3,Material.POLISHED_BLACKSTONE_BRICK_WALL);
                set(base,rx,y+1,z+3,Material.POLISHED_BLACKSTONE_BRICK_WALL);
            }
        }
    }

    private void netherButtress(Location start){
        World world=start.getWorld();if(world==null)return;
        int min=Math.max(world.getMinHeight()+2,start.getBlockY()-80);
        for(int y=start.getBlockY();y>=min;y--){
            Material existing=world.getBlockAt(start.getBlockX(),y,start.getBlockZ()).getType();
            world.getBlockAt(start.getBlockX(),y,start.getBlockZ()).setType(
                    Math.floorMod(y+start.getBlockX()+start.getBlockZ(),7)==0?Material.GILDED_BLACKSTONE:Material.POLISHED_BASALT,false);
            if(y<start.getBlockY()-4&&existing.isSolid()&&existing!=Material.LAVA)break;
        }
    }

    private void buildArrivalAndBridge(Location base) {
        // Plataforma protegida, barandillas y un puente ancho que señala la entrada.
        for (int x = -6; x <= 6; x++) for (int z = -58; z <= -50; z++) {
            set(base, x, 0, z, ((x + z) & 1) == 0 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.NETHER_BRICKS);
            clear(base.clone().add(x, 0, z), 1, 6);
            if (Math.abs(x) == 6) set(base, x, 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
        }
        for (int z = -50; z <= FRONT_Z; z++) {
            for (int x = -5; x <= 5; x++) {
                set(base, x, 0, z, ((x + z) & 3) == 0 ? Material.RED_NETHER_BRICKS : Material.NETHER_BRICKS);
                clear(base.clone().add(x, 0, z), 1, 7);
            }
            set(base, -6, 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
            set(base, 6, 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
            if (Math.floorMod(z, 6) == 0) {
                chainLantern(base.clone().add(-7, 5, z));
                chainLantern(base.clone().add(7, 5, z));
            }
        }
        // Protección contra caídas en el punto inicial.
        for (int x = -7; x <= 7; x++) {
            set(base, x, 1, -59, Material.POLISHED_BLACKSTONE_BRICK_WALL);
            set(base, x, 1, -49, Material.POLISHED_BLACKSTONE_BRICK_WALL);
        }
        carveDoorOnZWall(base, FRONT_Z, 0, 5, 7);
    }

    private void buildOuterWalls(Location base) {
        for (int x = -WALL_X; x <= WALL_X; x++) {
            if (Math.abs(x) > 5) wallColumn(base, x, FRONT_Z, 10);
            wallColumn(base, x, BACK_Z, 10);
        }
        for (int z = FRONT_Z; z <= BACK_Z; z++) {
            wallColumn(base, -WALL_X, z, 10);
            wallColumn(base, WALL_X, z, 10);
        }
        // Adarves transitables y almenas.
        for (int x = -WALL_X; x <= WALL_X; x++) {
            set(base, x, 10, FRONT_Z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(base, x, 10, BACK_Z, Material.POLISHED_BLACKSTONE_BRICKS);
            if ((x & 1) == 0 && Math.abs(x) > 5) set(base, x, 11, FRONT_Z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
            if ((x & 1) == 0) set(base, x, 11, BACK_Z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
        }
        for (int z = FRONT_Z; z <= BACK_Z; z++) {
            set(base, -WALL_X, 10, z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(base, WALL_X, 10, z, Material.POLISHED_BLACKSTONE_BRICKS);
            if ((z & 1) == 0) {
                set(base, -WALL_X, 11, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
                set(base, WALL_X, 11, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
            }
        }
    }

    private void buildGatehouse(Location base) {
        hollowRoom(base.clone().add(-14, 0, FRONT_Z + 5), 8, 7, 13,
                Material.POLISHED_BLACKSTONE_BRICKS, Material.CRACKED_POLISHED_BLACKSTONE_BRICKS,
                Material.NETHER_BRICKS);
        hollowRoom(base.clone().add(14, 0, FRONT_Z + 5), 8, 7, 13,
                Material.POLISHED_BLACKSTONE_BRICKS, Material.CRACKED_POLISHED_BLACKSTONE_BRICKS,
                Material.NETHER_BRICKS);
        for (int x = -9; x <= 9; x++) for (int y = 1; y <= 14; y++) {
            boolean opening = Math.abs(x) <= 5 && y <= 7;
            set(base, x, y, FRONT_Z, opening ? Material.AIR
                    : (y == 5 || y == 10 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS));
        }
        set(base, 0, 12, FRONT_Z - 1, Material.GOLD_BLOCK);
        for (int x = -5; x <= 5; x++) set(base, x, 8, FRONT_Z, Material.CHAIN);
        roomStairs(base.clone().add(-14, 0, FRONT_Z + 5), true, 9);
        roomStairs(base.clone().add(14, 0, FRONT_Z + 5), false, 9);
    }

    private void buildDistricts(Location base) {
        // Patio central, canales de lava contenidos y caminos muy legibles.
        for (int z = FRONT_Z + 2; z <= KEEP_FRONT_Z - 2; z++) {
            for (int x = -8; x <= 8; x++) set(base, x, 0, z,
                    ((x + z) & 3) == 0 ? Material.RED_NETHER_BRICKS : Material.NETHER_BRICKS);
        }
        for (int z = -31; z <= -9; z++) {
            // Canales hundidos y cerrados: la lava no puede invadir las calles.
            set(base, -10, 0, z, Material.LAVA);
            set(base, 10, 0, z, Material.LAVA);
            set(base, -11, 0, z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(base, -9, 0, z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(base, 9, 0, z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(base, 11, 0, z, Material.POLISHED_BLACKSTONE_BRICKS);
            if ((z & 1) == 0) {
                set(base, -11, 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
                set(base, -9, 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
                set(base, 9, 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
                set(base, 11, 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
            }
        }
        buildBarracks(base.clone().add(-25, 0, -25));
        buildForge(base.clone().add(25, 0, -25));
        buildGoldVault(base.clone().add(-25, 0, -5));
        buildHoglinPens(base.clone().add(25, 0, -5));
        buildBlazeWatch(base.clone().add(-27, 0, 20), true);
        buildBlazeWatch(base.clone().add(27, 0, 20), false);

        // Señales visuales hacia la fortaleza central.
        for (int z = -10; z <= KEEP_FRONT_Z; z++) {
            for (int x = -4; x <= 4; x++) set(base, x, 0, z, Material.POLISHED_BLACKSTONE_BRICKS);
            if (Math.floorMod(z, 5) == 0) {
                set(base, -6, 1, z, Material.SOUL_LANTERN);
                set(base, 6, 1, z, Material.SOUL_LANTERN);
            }
        }
    }

    private void buildBarracks(Location center) {
        hollowRoom(center, 10, 8, 8, Material.NETHER_BRICKS, Material.RED_NETHER_BRICKS, Material.CRIMSON_PLANKS);
        carveDoorOnXWall(center, 10, 0, 2, 4);
        for (int z = -6; z <= 6; z += 4) {
            set(center, -6, 1, z, Material.CRIMSON_TRAPDOOR);
            set(center, -5, 1, z, Material.CRIMSON_PLANKS);
            set(center, 5, 1, z, Material.CRIMSON_PLANKS);
            set(center, 6, 1, z, Material.CRIMSON_TRAPDOOR);
        }
    }

    private void buildForge(Location center) {
        hollowRoom(center, 10, 8, 9, Material.POLISHED_BLACKSTONE_BRICKS, Material.GILDED_BLACKSTONE,
                Material.BLACKSTONE);
        carveDoorOnXWall(center, -10, 0, 2, 4);
        for (int x = -6; x <= 6; x += 3) {
            set(center, x, 1, 5, Material.BLAST_FURNACE);
            set(center, x, 2, 6, Material.CHAIN);
        }
        for (int x = -4; x <= 4; x++) set(center, x, 0, -4, Material.MAGMA_BLOCK);
        set(center, 0, 1, -4, Material.LAVA);
    }

    private void buildGoldVault(Location center) {
        hollowRoom(center, 10, 8, 8, Material.POLISHED_BLACKSTONE_BRICKS, Material.GILDED_BLACKSTONE,
                Material.POLISHED_BLACKSTONE);
        carveDoorOnXWall(center, 10, 0, 2, 4);
        for (int x = -6; x <= 6; x += 3) for (int z = -4; z <= 4; z += 4) {
            set(center, x, 1, z, ((x + z) & 1) == 0 ? Material.GOLD_BLOCK : Material.NETHER_GOLD_ORE);
        }
        for (int x = -8; x <= 8; x++) set(center, x, 4, 0, Material.IRON_BARS);
    }

    private void buildHoglinPens(Location center) {
        for (int x = -10; x <= 10; x++) for (int z = -8; z <= 8; z++) {
            set(center, x, 0, z, Material.CRIMSON_NYLIUM);
            clear(center.clone().add(x, 0, z), 1, 6);
            if (Math.abs(x) == 10 || Math.abs(z) == 8) set(center, x, 1, z, Material.CRIMSON_FENCE);
        }
        for (int z = -5; z <= 5; z += 5) {
            set(center, -6, 1, z, Material.CRIMSON_STEM);
            set(center, 6, 1, z, Material.CRIMSON_STEM);
        }
    }

    private void buildBlazeWatch(Location center, boolean doorEast) {
        hollowRoom(center, 7, 7, 15, Material.POLISHED_BLACKSTONE_BRICKS, Material.GILDED_BLACKSTONE,
                Material.NETHER_BRICKS);
        carveDoorOnXWall(center, doorEast ? 7 : -7, 0, 2, 4);
        for (int y = 4; y <= 12; y += 4) {
            set(center, -6, y, 0, Material.IRON_BARS);
            set(center, 6, y, 0, Material.IRON_BARS);
            set(center, 0, y, -6, Material.IRON_BARS);
            set(center, 0, y, 6, Material.IRON_BARS);
        }
        for (int x = -5; x <= 5; x += 5) for (int z = -5; z <= 5; z += 5) {
            set(center, x, 1, z, Material.SOUL_FIRE);
        }
    }

    private void buildLowerLavaTunnels(Location base) {
        int y = -7;
        // Galerías inferiores con techo amplio; los striders se encuentran aquí,
        // sin bloquear la ruta principal de jugadores.
        for (int x = -16; x <= 16; x++) for (int z = -1; z <= 29; z++) {
            set(base, x, y, z, ((x + z) & 3) == 0 ? Material.MAGMA_BLOCK : Material.BLACKSTONE);
            for (int clearY = 1; clearY <= 5; clearY++) set(base, x, y + clearY, z, Material.AIR);
            if (Math.abs(x) == 16 || z == -1 || z == 29) {
                for (int wallY = 1; wallY <= 5; wallY++) set(base, x, y + wallY, z, Material.POLISHED_BLACKSTONE_BRICKS);
            }
        }
        for (int z = 3; z <= 25; z++) {
            set(base, -9, y, z, Material.LAVA);
            set(base, 9, y, z, Material.LAVA);
            set(base, -10, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(base, -8, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(base, 8, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(base, 10, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
        }
        // Escalera independiente hacia la planta baja.
        for (int step = 0; step < 7; step++) {
            for (int x : new int[]{-14, -13}) {
                Location stair = base.clone().add(x, y + 1 + step, 25 - step);
                setStair(stair, BlockFace.NORTH, Material.POLISHED_BLACKSTONE_BRICK_STAIRS);
                stair.clone().add(0, -1, 0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
                clear(stair, 1, 4);
            }
        }
    }

    private void buildCentralKeep(Location base, int bossLevel, int portalLevel) {
        int levels = portalLevel + 1;
        for (int level = 0; level < levels; level++) {
            int y0 = level * FLOOR_HEIGHT;
            buildKeepLevel(base, y0, level, bossLevel, portalLevel);
        }
        // Escaleras alternas de dos bloques, descansos y huecos despejados.
        for (int level = 0; level < levels - 1; level++) buildKeepStairs(base, level);

        int roofY = levels * FLOOR_HEIGHT;
        for (int x = -KEEP_X; x <= KEEP_X; x++) for (int z = KEEP_FRONT_Z; z <= KEEP_BACK_Z; z++) {
            set(base, x, roofY, z, Material.POLISHED_BLACKSTONE_BRICKS);
            if ((Math.abs(x) == KEEP_X || z == KEEP_FRONT_Z || z == KEEP_BACK_Z) && ((x + z) & 1) == 0) {
                set(base, x, roofY + 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
            }
        }
    }

    private void buildKeepLevel(Location base, int y0, int level, int bossLevel, int portalLevel) {
        for (int x = -KEEP_X; x <= KEEP_X; x++) for (int z = KEEP_FRONT_Z; z <= KEEP_BACK_Z; z++) {
            set(base, x, y0, z, ((x + z + level) & 4) == 0
                    ? Material.RED_NETHER_BRICKS : Material.NETHER_BRICKS);
            for (int y = 1; y < FLOOR_HEIGHT; y++) {
                boolean edge = Math.abs(x) >= KEEP_X - 1 || z <= KEEP_FRONT_Z + 1 || z >= KEEP_BACK_Z - 1;
                Material material = Material.AIR;
                if (edge) {
                    boolean window = y >= 3 && y <= 4 && ((x + z) % 6 == 0);
                    material = window ? Material.RED_STAINED_GLASS_PANE
                            : (y == 2 || y == 6 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS);
                }
                set(base, x, y0 + y, z, material);
            }
        }
        carveDoorOnZWall(base.clone().add(0, y0, 0), KEEP_FRONT_Z, 0, 4, 5);

        if (level < bossLevel) {
            // Habitaciones laterales, corredor central y balcones.
            for (int x = -KEEP_X + 2; x <= KEEP_X - 2; x++) {
                if (Math.abs(x) <= 4) continue;
                for (int y = 1; y <= 4; y++) set(base, x, y0 + y, 12,
                        y == 4 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS);
            }
            for (int z = 2; z <= KEEP_BACK_Z - 2; z += 9) {
                for (int y = 1; y <= 4; y++) {
                    for (int x = -KEEP_X + 2; x <= KEEP_X - 2; x++) {
                        if (Math.abs(x) <= 3) continue;
                        set(base, x, y0 + y, z, y == 4 ? Material.RED_NETHER_BRICKS : Material.NETHER_BRICKS);
                    }
                }
            }
            for (int[] p : new int[][]{{-14, 5}, {14, 5}, {-14, 23}, {14, 23}}) {
                set(base, p[0], y0 + 1, p[1], Material.SOUL_LANTERN);
            }
        } else if (level == bossLevel) {
            // Salón del trono y arena abierta.
            for (int z = 21; z <= 28; z++) for (int x = -8; x <= 8; x++) {
                set(base, x, y0 + 1, z, ((x + z) & 1) == 0 ? Material.GILDED_BLACKSTONE : Material.BLACKSTONE);
            }
            for (int y = 1; y <= 6; y++) {
                set(base, -9, y0 + y, 26, Material.POLISHED_BASALT);
                set(base, 9, y0 + y, 26, Material.POLISHED_BASALT);
            }
            set(base, 0, y0 + 2, 28, Material.GOLD_BLOCK);
            for (int x = -14; x <= 14; x += 4) set(base, x, y0 + 1, 1, Material.SOUL_FIRE);
        } else if (level == portalLevel) {
            // Cámara segura y visible desde las escaleras.
            for (int x = -14; x <= 14; x++) for (int z = 3; z <= 27; z++) {
                if (Math.abs(x) == 14 || z == 3 || z == 27) set(base, x, y0 + 1, z, Material.GILDED_BLACKSTONE);
            }
            for (int[] p : new int[][]{{-10, 7}, {10, 7}, {-10, 23}, {10, 23}}) {
                set(base, p[0], y0 + 1, p[1], Material.SOUL_LANTERN);
            }
        }
    }

    private void buildKeepStairs(Location base, int level) {
        int y0 = level * FLOOR_HEIGHT;
        boolean east = (level & 1) == 0;
        int x0 = east ? 12 : -13;
        int z0 = 3;
        // Se limpia todo el prisma antes de poner escalones para evitar techos bajos.
        for (int x = x0 - 1; x <= x0 + 2; x++) for (int z = z0 - 1; z <= z0 + 9; z++) {
            for (int y = y0 + 1; y <= y0 + FLOOR_HEIGHT + 4; y++) set(base, x, y, z, Material.AIR);
        }
        for (int step = 0; step < FLOOR_HEIGHT; step++) {
            int z = z0 + step;
            int y = y0 + 1 + step;
            for (int dx = 0; dx < 2; dx++) {
                Location stair = base.clone().add(x0 + dx, y, z);
                setStair(stair, BlockFace.SOUTH, Material.POLISHED_BLACKSTONE_BRICK_STAIRS);
                stair.clone().add(0, -1, 0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
                clear(stair, 1, 4);
            }
            set(base, east ? x0 - 1 : x0 + 2, y, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
        }
        for (int x = x0; x <= x0 + 1; x++) for (int z = z0 + FLOOR_HEIGHT - 1; z <= z0 + FLOOR_HEIGHT + 1; z++) {
            set(base, x, y0 + FLOOR_HEIGHT, z, Material.POLISHED_BLACKSTONE_BRICKS);
            clear(base.clone().add(x, y0 + FLOOR_HEIGHT, z), 1, 4);
        }
    }

    private void buildCornerTowers(Location base, int roofY) {
        for (int x : new int[]{-32, 32}) for (int z : new int[]{-35, 27}) {
            Location center = base.clone().add(x, 0, z);
            hollowRoom(center, 6, 6, 18, Material.POLISHED_BLACKSTONE_BRICKS,
                    Material.GILDED_BLACKSTONE, Material.NETHER_BRICKS);
            for (int y = 4; y <= 15; y += 4) {
                set(center, -5, y, 0, Material.IRON_BARS);
                set(center, 5, y, 0, Material.IRON_BARS);
                set(center, 0, y, -5, Material.IRON_BARS);
                set(center, 0, y, 5, Material.IRON_BARS);
            }
            for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
                if ((Math.abs(dx) == 6 || Math.abs(dz) == 6) && ((dx + dz) & 1) == 0) {
                    set(center, dx, 19, dz, Material.POLISHED_BLACKSTONE_BRICK_WALL);
                }
            }
        }
    }

    private void registerEncounters(Location base, int bossLevel) {
        register(base.clone().add(0, 1, -37), "nether_entrance", group("entrance", List.of(
                "gilded_piglin_minion", "gilded_wither_skeleton_vanguard_minion")), true);
        register(base.clone().add(-24, 1, -25), "nether_barracks", group("barracks", List.of(
                "gilded_piglin_minion", "gilded_wither_skeleton_vanguard_minion")), true);
        register(base.clone().add(24, 1, -25), "nether_forge", group("forge", List.of(
                "gilded_wither_skeleton_vanguard_minion", "gilded_blaze_wraith_minion")), true);
        register(base.clone().add(25, 1, -5), "nether_hoglin_pens", group("courtyard", List.of(
                "gilded_hoglin_rider_minion", "gilded_piglin_minion")), true);
        register(base.clone().add(-27, 1, 20), "nether_blaze_tower", group("high_towers", List.of(
                "gilded_blaze_wraith_minion")), true);
        register(base.clone().add(27, 1, 20), "nether_blaze_tower", group("high_towers", List.of(
                "gilded_blaze_wraith_minion")), true);
        register(base.clone().add(0, -5, 13), "nether_lava_tunnels", group("lava_tunnels", List.of(
                "molten_strider_minion", "gilded_blaze_wraith_minion")), true);

        int[][] keepPoints = {{-11, 5}, {11, 5}, {-11, 22}, {11, 22}};
        int perFloor = Math.max(1, Math.min(4,
                plugin.getConfig().getInt("nether-citadel.spawners-per-floor", 2)));
        List<String> interior = group("interior", List.of(
                "gilded_piglin_minion", "gilded_wither_skeleton_vanguard_minion",
                "gilded_hoglin_rider_minion"));
        List<String> elite = group("elite", List.of(
                "gilded_wither_skeleton_vanguard_minion", "gilded_hoglin_rider_minion",
                "gilded_blaze_wraith_minion"));
        for (int level = 0; level < bossLevel; level++) {
            List<String> pool = level >= bossLevel - 2 ? elite : interior;
            for (int i = 0; i < perFloor; i++) {
                register(base.clone().add(keepPoints[i][0], level * FLOOR_HEIGHT + 1, keepPoints[i][1]),
                        "nether_keep_" + level, pool, true);
            }
        }
        List<String> support = group("boss-support", List.of(
                "gilded_wither_skeleton_vanguard_minion", "gilded_blaze_wraith_minion"));
        register(base.clone().add(-12, bossLevel * FLOOR_HEIGHT + 1, 7),
                "nether_boss_support", support, false);
        register(base.clone().add(12, bossLevel * FLOOR_HEIGHT + 1, 7),
                "nether_boss_support", support, false);
    }

    private void placeLoot(Location base, int bossLevel, int portalLevel) {
        chest(base.clone().add(-29, 1, -25), AdaptiveDungeonLootManager.ChestTier.COMMON);
        chest(base.clone().add(29, 1, -25), AdaptiveDungeonLootManager.ChestTier.RARE);
        chest(base.clone().add(-29, 1, -5), AdaptiveDungeonLootManager.ChestTier.EPIC);
        chest(base.clone().add(29, 1, -5), AdaptiveDungeonLootManager.ChestTier.RARE);
        chest(base.clone().add(0, -5, 24), AdaptiveDungeonLootManager.ChestTier.EPIC);
        for (int level = 0; level < bossLevel; level++) {
            AdaptiveDungeonLootManager.ChestTier tier = level == 0
                    ? AdaptiveDungeonLootManager.ChestTier.COMMON
                    : level >= bossLevel - 2 ? AdaptiveDungeonLootManager.ChestTier.EPIC
                    : AdaptiveDungeonLootManager.ChestTier.RARE;
            chest(base.clone().add(-14, level * FLOOR_HEIGHT + 1, 26), tier);
            chest(base.clone().add(14, level * FLOOR_HEIGHT + 1, 26), tier);
        }
        for (int[] p : new int[][]{{-11, 7}, {11, 7}, {-11, 23}, {11, 23}}) {
            chest(base.clone().add(p[0], portalLevel * FLOOR_HEIGHT + 1, p[1]),
                    AdaptiveDungeonLootManager.ChestTier.LEGENDARY);
        }
    }

    private List<String> group(String key, List<String> fallback) {
        List<String> configured = plugin.getConfig().getStringList("nether-citadel.mob-groups." + key);
        return configured.isEmpty() ? fallback : configured;
    }

    private void register(Location at, String group, List<String> ids, boolean active) {
        if (spawners != null) spawners.register(at, group, ids, active);
    }

    private void chest(Location at, AdaptiveDungeonLootManager.ChestTier tier) {
        at.clone().add(0, -1, 0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
        clear(at, 1, 2);
        adaptiveLoot.placeCustomChest(at, AdaptiveDungeonLootManager.DimensionGroup.NETHER, tier);
    }

    private void hollowRoom(Location center, int radiusX, int radiusZ, int height,
                            Material wall, Material accent, Material floor) {
        for (int x = -radiusX; x <= radiusX; x++) for (int z = -radiusZ; z <= radiusZ; z++) {
            set(center, x, 0, z, ((x + z) & 7) == 0 ? accent : floor);
            for (int y = 1; y <= height; y++) {
                boolean edge = Math.abs(x) == radiusX || Math.abs(z) == radiusZ || y == height;
                set(center, x, y, z, edge ? (y == 4 || y == height ? accent : wall) : Material.AIR);
            }
        }
    }

    private void roomStairs(Location center, boolean east, int height) {
        int x0 = east ? 3 : -4;
        for (int step = 0; step < height; step++) {
            int z = -5 + step;
            int y = 1 + step;
            for (int dx = 0; dx < 2; dx++) {
                Location stair = center.clone().add(x0 + dx, y, z);
                setStair(stair, BlockFace.SOUTH, Material.POLISHED_BLACKSTONE_BRICK_STAIRS);
                stair.clone().add(0, -1, 0).getBlock().setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
                clear(stair, 1, 4);
            }
        }
    }

    private void wallColumn(Location base, int x, int z, int height) {
        for (int y = 1; y <= height; y++) set(base, x, y, z,
                y == 4 || y == 8 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS);
    }

    private void carveDoorOnZWall(Location center, int wallZ, int centerX, int halfWidth, int height) {
        for (int x = -halfWidth; x <= halfWidth; x++) for (int y = 1; y <= height; y++) {
            set(center, centerX + x, y, wallZ, Material.AIR);
        }
    }

    private void carveDoorOnXWall(Location center, int wallX, int centerZ, int halfWidth, int height) {
        for (int z = -halfWidth; z <= halfWidth; z++) for (int y = 1; y <= height; y++) {
            set(center, wallX, y, centerZ + z, Material.AIR);
        }
    }

    private void chainLantern(Location top) {
        top.getBlock().setType(Material.CHAIN, false);
        top.clone().add(0, -1, 0).getBlock().setType(Material.SOUL_LANTERN, false);
    }

    private void supportToGround(Location at, Material material, int maxDepth) {
        for (int i = 0; i < maxDepth && at.getBlockY() > at.getWorld().getMinHeight(); i++) {
            if (at.getBlock().getType().isSolid()) return;
            at.getBlock().setType(material, false);
            at.subtract(0, 1, 0);
        }
    }

    private void setStair(Location at, BlockFace facing, Material material) {
        Block block = at.getBlock();
        block.setType(material, false);
        if (block.getBlockData() instanceof Stairs stairs) {
            stairs.setFacing(facing);
            stairs.setHalf(Bisected.Half.BOTTOM);
            stairs.setShape(Stairs.Shape.STRAIGHT);
            block.setBlockData(stairs, false);
        }
    }

    private void clear(Location floor, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) floor.clone().add(0, y, 0).getBlock().setType(Material.AIR, false);
    }

    private void set(Location base, int x, int y, int z, Material material) {
        base.clone().add(x, y, z).getBlock().setType(material, false);
    }

    private Location centered(Location location) {
        location.setX(location.getBlockX() + 0.5D);
        location.setZ(location.getBlockZ() + 0.5D);
        return location;
    }
}
