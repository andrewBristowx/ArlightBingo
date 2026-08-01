from pathlib import Path

path = Path("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture146.java")
source = path.read_text(encoding="utf-8")
old = '''    static void villageStreet(List<BlockEdit> out, int x1, int y, int z1, int x2, int z2, int width) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int step = 0; step <= steps; step++) {
            double t = steps == 0 ? 0.0D : step / (double) steps;
            int cx = (int) Math.round(x1 + (x2 - x1) * t);
            int cz = (int) Math.round(z1 + (z2 - z1) * t);
            for (int side = -width / 2; side <= width / 2; side++) {
                int x = Math.abs(x2 - x1) >= Math.abs(z2 - z1) ? cx : cx + side;
                int z = Math.abs(x2 - x1) >= Math.abs(z2 - z1) ? cz + side : cz;
                out.add(e(x, y - 1, z, Math.floorMod(step + side, 11) == 0
                        ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE));
                for (int yy = 0; yy <= 3; yy++) out.add(e(x, y + yy, z, Material.AIR));
            }
        }
    }
'''
new = '''    static void villageStreet(List<BlockEdit> out, int x1, int y1, int z1,
                              int x2, int y2, int z2, int width) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        boolean mostlyHorizontal = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
        for (int step = 0; step <= steps; step++) {
            double t = steps == 0 ? 0.0D : step / (double) steps;
            int cx = (int) Math.round(x1 + (x2 - x1) * t);
            int cy = (int) Math.round(y1 + (y2 - y1) * t);
            int cz = (int) Math.round(z1 + (z2 - z1) * t);
            for (int side = -width / 2; side <= width / 2; side++) {
                int x = mostlyHorizontal ? cx : cx + side;
                int z = mostlyHorizontal ? cz + side : cz;
                Material surface = Math.floorMod(step + side, 11) == 0
                        ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE;
                out.add(e(x, cy - 1, z, surface));
                out.add(e(x, cy - 2, z, Material.STONE_BRICKS));
                out.add(e(x, cy - 3, z, Material.STONE));
                for (int yy = 0; yy <= 3; yy++) out.add(e(x, cy + yy, z, Material.AIR));
            }
        }
    }
'''
count = source.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one legacy villageStreet method, found {count}")
path.write_text(source.replace(old, new), encoding="utf-8")
print("Updated villageStreet with interpolated terrace heights and foundations.")
