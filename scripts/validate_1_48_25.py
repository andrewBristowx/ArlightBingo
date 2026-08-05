#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
checks = [
    ("pom.xml", "<version>1.48.26</version>"),
    ("src/main/resources/plugin.yml", "version: 1.48.26"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java",
     "1.48.26-final-gate-road-terrain-integration-1"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java",
     "reconciliando alturas y rutas actuales"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java",
     "restoreClosedBossRitualGate"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignLandscape148.java",
     "isGateClosed"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignLandscape148.java",
     "Material.IRON_BARS"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java",
     "registerAuthoritativeRoadCell"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java",
     "portón ritual de barras ausente o incompleto"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignStructures148.java",
     "boss-ritual-interior"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldAutoRepair148.java",
     "+full-width-gate-single-height-roads"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java",
     "minecraft:iron_bars[east=true,north=false,south=false,"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignLandscape148.java",
     "Bukkit.createBlockData(edit.data())"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java",
     '"boss-north-monumental-stairs".equals(routeId)'),
]
for rel, token in checks:
    text = (root / rel).read_text(encoding="utf-8")
    if token not in text:
        raise SystemExit(f"missing {token} in {rel}")
structures = (root / "src/main/java/com/arlight/bingo/listeners/OverworldCampaignStructures148.java").read_text(encoding="utf-8")
if "ritualGate.getBlockX() - 16" in structures:
    raise SystemExit("obsolete transverse boss crossroad still present")
extra_checks = [
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java",
     "BOSS_RITUAL_GATE_HALF_WIDTH = BOSS_GATE_CLEAR_HALF_WIDTH"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java",
     "reconcileRouteMembershipsToPhysicalPlan"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java",
     "repairRegisteredTowerFoundations"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "shapeRoadEmbankment"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "blendCitadelOuterSlope"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java",
     "restoreBossRearTowers"),
]
for rel, token in extra_checks:
    if token not in (root / rel).read_text(encoding="utf-8"):
        raise SystemExit(f"missing {token} in {rel}")
for java in (root / "src/main/java").rglob("*.java"):
    text = java.read_text(encoding="utf-8")
    if text.count("{") != text.count("}"):
        raise SystemExit(f"brace mismatch in {java}")
print("ArlightBingo 1.48.26 static validation OK")
