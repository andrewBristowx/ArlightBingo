#!/usr/bin/env python3
from pathlib import Path
R=Path(__file__).resolve().parents[1]
def read(p): return (R/p).read_text()
def need(c,m):
    if not c: raise SystemExit(m)
need(read("BUILD_VERSION.txt").strip()=="1.48.9","BUILD_VERSION")
need("<version>1.48.9</version>" in read("pom.xml"),"pom")
need("version: 1.48.9" in read("src/main/resources/plugin.yml"),"plugin")
a=read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java")
t=read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java")
u=read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java")
s=read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignStructures148.java")
b=read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java")
for token in ["closeEaves", "house-path-", "ceilingBeams", "interiorColumns", "greatHallDetails", "complete_gatehouse_wall_wings"]:
    need(token in a or token in b, token)
for token in ["bossApproachWalkY", "blendRoadShoulders", "isRoadSurface"]: need(token in t,token)
for token in ["hueco bajo alero", "camino de casa sin conectar", "auditBossGatehouse"]: need(token in u,token)
need("villageStreet(out, registry" in s,"local streets not registered")
need("1.48.9-overworld-roads-gate-houses-polish-1" in b,"marker")
print("validate_1_48_9: OK")
