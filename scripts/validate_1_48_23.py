#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
checks = [
    ("pom.xml", "<version>1.48.23</version>"),
    ("src/main/resources/plugin.yml", "version: 1.48.23"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java",
     "1.48.23-fortress-integration-coliseum-closure-1"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java",
     "cerrando fortaleza, accesos y coliseo en una fase final"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "finalizeFortressAndColiseum"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "levelCitadelPerimeterApron"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "groundCitadelButtresses"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "citadel-south-final"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java",
     "boss-north-monumental-stairs"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java",
     "restoreBossColiseumClosure"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java",
     "auditBossColiseumClosure"),
    ("src/main/java/com/arlight/bingo/listeners/OverworldAutoRepair148.java",
     "+fortress-coliseum-final"),
]
for rel, token in checks:
    text = (root / rel).read_text(encoding="utf-8")
    if token not in text:
        raise SystemExit(f"missing {token} in {rel}")
for java in (root / "src/main/java").rglob("*.java"):
    text = java.read_text(encoding="utf-8")
    if text.count("{") != text.count("}"):
        raise SystemExit(f"brace mismatch in {java}")
print("ArlightBingo 1.48.23 static validation OK")
