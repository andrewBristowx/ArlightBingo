package com.arlight.bingo.template;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/**
 * Generador determinista de la isla maestra del Overworld.
 *
 * <p>No parte de una seed vanilla ni intenta pegar una plataforma sobre un
 * terreno desconocido. Cada columna conoce su altura antes de que Bingo coloque
 * edificios, caminos o mazmorras. El océano, las playas y el volumen inferior
 * forman parte de la misma masa, por lo que no existen bordes verticales ni
 * estructuras flotantes.</p>
 */
public final class OverworldIslandGenerator extends ChunkGenerator {
    private final long seed;
    private final int islandRadius;
    private final int seaLevel;

    public OverworldIslandGenerator(long seed, int islandRadius, int seaLevel) {
        this.seed = seed;
        this.islandRadius = Math.max(420, islandRadius);
        this.seaLevel = Math.max(48, Math.min(80, seaLevel));
    }

    @Override
    public ChunkData generateChunkData(World world, Random ignored,
                                       int chunkX, int chunkZ, BiomeGrid biomeGrid) {
        ChunkData data = createChunkData(world);
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for (int localX = 0; localX < 16; localX++) {
            int x = (chunkX << 4) + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int z = (chunkZ << 4) + localZ;
                Column column = column(x, z);
                Biome biome = biomeFor(x, z, column);
                for (int y = minY; y < maxY; y += 4) {
                    biomeGrid.setBiome(localX, y, localZ, biome);
                }

                data.setBlock(localX, minY, localZ, Material.BEDROCK);
                for (int y = minY + 1; y <= column.surfaceY(); y++) {
                    Material material = terrainMaterial(x, y, z, column, minY);
                    if (material != Material.AIR) data.setBlock(localX, y, localZ, material);
                }
                if (column.surfaceY() < seaLevel) {
                    for (int y = column.surfaceY() + 1; y <= seaLevel; y++) {
                        data.setBlock(localX, y, localZ, Material.WATER);
                    }
                }
            }
        }

        decorateChunk(data, chunkX, chunkZ, minY, maxY);
        return data;
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateBedrock() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public boolean shouldGenerateMobs() { return true; }

    private Column column(int x, int z) {
        double angle = Math.atan2(z, x);
        double boundary = islandRadius * (1.0D
                + 0.075D * Math.sin(angle * 5.0D + seed * 0.000001D)
                + 0.045D * Math.sin(angle * 9.0D - 1.7D)
                + 0.030D * noise2(x * 0.012D, z * 0.012D));
        double normalized = Math.sqrt((double) x * x + (double) z * z) / boundary;
        double coast = 1.0D - smoothStep(0.70D, 1.02D, normalized);

        double broad = noise2(x * 0.0065D, z * 0.0065D) * 8.0D;
        double detail = noise2(x * 0.021D + 31.0D, z * 0.021D - 17.0D) * 4.5D;
        double mountain = gaussian(x, z, 105.0D, -205.0D, 175.0D) * 37.0D
                + gaussian(x, z, 245.0D, -95.0D, 135.0D) * 18.0D;
        double valley = gaussian(x, z, 0.0D, 35.0D, 155.0D) * 7.0D;
        double landHeight = seaLevel + 7.0D + coast * 22.0D
                + coast * (broad + detail + mountain - valley);
        double oceanFloor = seaLevel - 12.0D
                + noise2(x * 0.014D + 80.0D, z * 0.014D - 80.0D) * 4.0D;
        double blended = oceanFloor + coast * (landHeight - oceanFloor);
        int surface = (int) Math.round(blended);
        return new Column(surface, normalized, coast, mountain);
    }

    private Material terrainMaterial(int x, int y, int z, Column column, int minY) {
        int depth = column.surfaceY() - y;
        if (depth > 5 && y > minY + 5 && cave(x, y, z, column)) return Material.AIR;
        if (depth == 0) {
            if (column.surfaceY() <= seaLevel + 2) return Material.SAND;
            if (column.mountain() > 15.0D || column.surfaceY() >= 118) return Material.STONE;
            if (z < -40 && x < 80) return Material.PODZOL;
            if (x > 130 && z > 30) return Material.COARSE_DIRT;
            return Material.GRASS_BLOCK;
        }
        if (depth <= 3) {
            return column.surfaceY() <= seaLevel + 2 ? Material.SANDSTONE : Material.DIRT;
        }
        Material base = y < 0 ? Material.DEEPSLATE : Material.STONE;
        return ore(x, y, z, base);
    }

    private boolean cave(int x, int y, int z, Column column) {
        if (column.coast() < 0.12D || y > column.surfaceY() - 8) return false;
        double tunnel = Math.sin((x + seed * 0.00001D) * 0.072D)
                + Math.sin((z - seed * 0.000013D) * 0.067D)
                + Math.sin((x + z + y * 1.7D) * 0.041D)
                + Math.cos(y * 0.089D + x * 0.018D);
        return tunnel > 3.25D;
    }

    private Material ore(int x, int y, int z, Material base) {
        long value = mix(seed ^ ((long) x * 341873128712L)
                ^ ((long) z * 132897987541L) ^ ((long) y * 42317861L));
        int roll = (int) (value & 4095L);
        if (y < 20 && roll < 4) return base == Material.DEEPSLATE
                ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE;
        if (y < 36 && roll < 14) return base == Material.DEEPSLATE
                ? Material.DEEPSLATE_REDSTONE_ORE : Material.REDSTONE_ORE;
        if (y < 72 && roll < 28) return base == Material.DEEPSLATE
                ? Material.DEEPSLATE_IRON_ORE : Material.IRON_ORE;
        if (y < 96 && roll < 38) return base == Material.DEEPSLATE
                ? Material.DEEPSLATE_COAL_ORE : Material.COAL_ORE;
        if (y < 48 && roll >= 4080) return base == Material.DEEPSLATE
                ? Material.DEEPSLATE_GOLD_ORE : Material.GOLD_ORE;
        return base;
    }

    private void decorateChunk(ChunkData data, int chunkX, int chunkZ, int minY, int maxY) {
        long hash = mix(seed ^ ((long) chunkX * 9182736451L) ^ ((long) chunkZ * 1928374657L));
        int localX = 3 + (int) ((hash >>> 8) & 7L);
        int localZ = 3 + (int) ((hash >>> 16) & 7L);
        int x = (chunkX << 4) + localX;
        int z = (chunkZ << 4) + localZ;
        Column column = column(x, z);
        if (column.surfaceY() <= seaLevel + 3 || column.surfaceY() + 8 >= maxY) return;
        if (nearDistrict(x, z) || (hash & 7L) > forestDensity(x, z)) return;

        Material log = z < -40 ? Material.SPRUCE_LOG : Material.OAK_LOG;
        Material leaves = z < -40 ? Material.SPRUCE_LEAVES : Material.OAK_LEAVES;
        int trunk = 4 + (int) ((hash >>> 24) & 2L);
        for (int y = 1; y <= trunk; y++) {
            data.setBlock(localX, column.surfaceY() + y, localZ, log);
        }
        for (int dy = trunk - 2; dy <= trunk + 1; dy++) {
            int radius = dy == trunk + 1 ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && dy != trunk) continue;
                    data.setBlock(localX + dx, column.surfaceY() + dy, localZ + dz, leaves);
                }
            }
        }
    }

    private int forestDensity(int x, int z) {
        if (z < -50 || x < -80) return 4;
        if (x > 140 && z > 20) return 2;
        return 0;
    }

    private boolean nearDistrict(int x, int z) {
        return distanceSq(x, z, -230, 0) < 145L * 145L
                || distanceSq(x, z, 230, 0) < 205L * 205L
                || distanceSq(x, z, 0, 0) < 70L * 70L;
    }

    private Biome biomeFor(int x, int z, Column column) {
        if (column.surfaceY() < seaLevel - 2) return Biome.OCEAN;
        if (column.surfaceY() <= seaLevel + 3) return Biome.BEACH;
        if (column.mountain() > 18.0D || column.surfaceY() > 116) return Biome.STONY_PEAKS;
        if (z < -70) return Biome.TAIGA;
        if (x < -90) return Biome.FOREST;
        if (x > 130 && z > 20) return Biome.DARK_FOREST;
        return Biome.PLAINS;
    }

    private double noise2(double x, double z) {
        int x0 = fastFloor(x), z0 = fastFloor(z);
        double tx = smooth(x - x0), tz = smooth(z - z0);
        double a = lerp(hashUnit(x0, z0), hashUnit(x0 + 1, z0), tx);
        double b = lerp(hashUnit(x0, z0 + 1), hashUnit(x0 + 1, z0 + 1), tx);
        return lerp(a, b, tz);
    }

    private double hashUnit(int x, int z) {
        long value = mix(seed ^ ((long) x * 0x9E3779B97F4A7C15L)
                ^ ((long) z * 0xC2B2AE3D27D4EB4FL));
        return ((value >>> 11) * 0x1.0p-53) * 2.0D - 1.0D;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double gaussian(double x, double z, double cx, double cz, double radius) {
        double dx = x - cx, dz = z - cz;
        return Math.exp(-(dx * dx + dz * dz) / (2.0D * radius * radius));
    }

    private static long distanceSq(int x, int z, int cx, int cz) {
        long dx = x - cx, dz = z - cz;
        return dx * dx + dz * dz;
    }

    private static double smoothStep(double edge0, double edge1, double value) {
        double t = Math.max(0.0D, Math.min(1.0D, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0D - 2.0D * t);
    }

    private static double smooth(double value) { return value * value * (3.0D - 2.0D * value); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static int fastFloor(double value) { int i = (int) value; return value < i ? i - 1 : i; }

    private record Column(int surfaceY, double normalizedRadius, double coast, double mountain) { }
}
