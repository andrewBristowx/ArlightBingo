#!/usr/bin/env python3
from pathlib import Path
R = Path(__file__).resolve().parents[1]
def read(p): return (R / p).read_text(encoding="utf-8")
def need(c, m):
    if not c: raise SystemExit(m)

need(read("BUILD_VERSION.txt").strip() == "1.48.10", "BUILD_VERSION")
need(read("SOURCE_VERSION.txt").strip() == "ArlightBingo 1.48.10 ROAD-SAFETY-VILLAGE-RECOVERY", "SOURCE_VERSION")
need("<version>1.48.10</version>" in read("pom.xml"), "pom")
need("version: 1.48.10" in read("src/main/resources/plugin.yml"), "plugin")
a = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java")
t = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java")
u = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java")
b = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java")
s = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignStructures148.java")

for token in ["closeEaves", "ceilingBeams", "interiorColumns", "greatHallDetails", "monumentalArenaEntrance"]:
    need(token in a, token)
need("1.48.10-road-safety-village-recovery-1" in b, "structure marker")
need("passed_1_48_10_road_safety_village_recovery" in b, "audit marker")
need('cell.id.startsWith("local-street-")' in u, "local streets are replayable")
need('cell.id.startsWith("house-path-")' in u, "house paths are replayable")
need("registry.blocksStructureBody" in u, "missing structure mask")
need("roadColumnMayBeCleared" in u, "missing material safety guard")
need("for (int y = cell.walkY; y <= cell.walkY + 2; y++)" in u, "road replay still clears four blocks")
need("repairRoadCorridors(World world, Registry registry)" in u, "road repair is not world-aware")
need("repairRegisteredEntrances(World world, Registry registry)" in u, "entrances are not world-aware")
need("blocksOtherStructure" in u, "entrances can cut neighbouring buildings")
need("repairRoadCorridors(world, auditRegistry)" in b, "builder uses unsafe road repair")
need("repairRegisteredEntrances(world, auditRegistry)" in b, "builder uses unsafe entrance repair")
need("step <= 8" in a, "house connector still too long")
need("int sideRadius = step == 0 ? 0 : 1" in a, "house connector still too wide")
need("yy <= 2" in a[a.index("private static void frontPath"):a.index("static void foundation")], "house connector clears too much height")
need("if (!footprint && natural >= targetY) continue;" in t, "building pad still cuts high terrain")
need("if (!footprint && columnTop <= natural) continue;" in t, "building apron still excavates")
need("blendRoadShoulders(out" not in t, "external road shoulders still carve terrain")
shoulders = t[t.index("private static void landscapeBossApproachShoulders"):t.index("private static Material designedSurface")]
need("if (natural < bankTop)" in shoulders and "else {\n                    continue;" in shoulders, "boss approach shoulders still cut hills")
need("villageStreet(out, registry" in s, "local streets missing")
need("local-street-" in a, "local street IDs missing")
need("farmDistrict" not in s, "farms returned")
print("validate_1_48_10: OK")
