#!/usr/bin/env python3
from pathlib import Path
R = Path(__file__).resolve().parents[1]
def read(p): return (R / p).read_text(encoding="utf-8")
def need(c, m):
    if not c: raise SystemExit(m)

need(read("BUILD_VERSION.txt").strip() == "1.48.11", "BUILD_VERSION")
need(read("SOURCE_VERSION.txt").strip() == "ArlightBingo 1.48.11 HOUSE-SHELL-BOSS-TERRAIN-RECOVERY", "SOURCE_VERSION")
need("<version>1.48.11</version>" in read("pom.xml"), "pom")
need("version: 1.48.11" in read("src/main/resources/plugin.yml"), "plugin")
a = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java")
t = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java")
u = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java")
b = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java")

need("1.48.11-house-shell-boss-terrain-recovery-1" in b, "structure marker")
need("passed_1_48_11_house_shell_boss_terrain_recovery" in b, "audit marker")
need('addShapePhases("arena sellada", boss, 46, 54)' in b, "boss shaping radii")
need("repairRegisteredHouseShells(auditRegistry)" in b, "house shell phase")
need("repairRegisteredHouseShells(Registry registry)" in u, "house shell repair")
need("restoreHouseRoof" in u and "restoreHouseEaves" in u, "roof/eave repair")
need("Material plaster" in u, "house plaster missing from audit spec")
need("shapeBossArena" in t, "boss terrain specialization")
need("if (target <= natural) continue;" in t, "boss outer ring still cuts hills")
need("distance <= flatRadius" in t and "distance > blendRadius" in t, "boss core/ring bounds")
need('cell.id.startsWith("local-street-")' in u, "local road replay guard")
need('cell.id.startsWith("house-path-")' in u, "house path replay guard")
need("registry.blocksStructureBody" in u, "structure road mask")
need("roadColumnMayBeCleared" in u, "road material safety")
need("repairRegisteredEntrances(World world, Registry registry)" in u, "entrance safety")
need("step <= 8" in a, "house path length")
need("int sideRadius = step == 0 ? 0 : 1" in a, "house path width")
print("validate_1_48_11: OK")
