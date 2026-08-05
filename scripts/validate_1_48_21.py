#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
checks = [
    ("pom.xml", "<version>1.48.21</version>"),
    ("src/main/resources/plugin.yml", "version: 1.48.21"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java",
     "1.48.21-paths-fix-1"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "int cell = 4;"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "int bridgeSteps = 14;"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "registry.registerRoadCell(\"boss-portal-north-rim\""),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "supportedPathCell(out, world, bx, cy, cz, Material.DEEPSLATE_TILES);"),
]
for rel, token in checks:
    text = (root / rel).read_text(encoding="utf-8")
    if token not in text:
        raise SystemExit(f"missing {token} in {rel}")
for java in (root / "src/main/java").rglob("*.java"):
    text = java.read_text(encoding="utf-8")
    if text.count("{") != text.count("}"):
        raise SystemExit(f"brace mismatch in {java}")
print("ArlightBingo 1.48.21 static validation OK")
