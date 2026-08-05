#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
checks = [
    ("pom.xml", "<version>1.48.22</version>"),
    ("src/main/resources/plugin.yml", "version: 1.48.22"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java",
     "1.48.22-strict-road-continuity-1"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java",
     "sellando continuidad final de todos los caminos"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "restoreBossNorthMonumentalStairs"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java",
     "restoreAllRoadCorridorsStrict"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java",
     "failures.add(roadFailure);"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java",
     "Exact-height validation is required in 1.48.22"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldAutoRepair148.java",
     "Rebuild the complete declared route on the first pass"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldAutoRepair148.java",
     "boss-north-monumental-stairs"),
]
for rel, token in checks:
    text = (root / rel).read_text(encoding="utf-8")
    if token not in text:
        raise SystemExit(f"missing {token} in {rel}")
for java in (root / "src/main/java").rglob("*.java"):
    text = java.read_text(encoding="utf-8")
    if text.count("{") != text.count("}"):
        raise SystemExit(f"brace mismatch in {java}")
print("ArlightBingo 1.48.22 static validation OK")
