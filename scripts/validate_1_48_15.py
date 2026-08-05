#!/usr/bin/env python3
from pathlib import Path
R = Path(__file__).resolve().parents[1]
def read(p): return (R / p).read_text(encoding="utf-8")
def need(c,m):
    if not c: raise SystemExit(m)
audit=read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java")
builder=read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java")
need(read("BUILD_VERSION.txt").strip()=="1.48.15","BUILD_VERSION")
need("<version>1.48.15</version>" in read("pom.xml"),"pom")
need("version: 1.48.15" in read("src/main/resources/plugin.yml"),"plugin")
need("roadMemberships" in audit and "roadRoutes" in audit,"multi-route")
need("isCriticalRoad" in audit,"critical route classification")
need("roadWarnings" in audit,"secondary road warnings")
need("no bloquean READY" in audit,"warning log")
need('"boss-north-approach".equals(id)' in audit,"boss critical")
need('id.startsWith("boss-portal-")' in audit,"boss rim critical")
need("repairRegisteredHouseShells(auditRegistry)" in builder,"house repair")
need("repairRegisteredTowerLandings(world, auditRegistry)" in builder,"landing repair")
need("repairRegisteredChimneys(auditRegistry)" in builder,"chimney repair")
print("validate_1_48_15: OK")

need("passed_1_48_15_critical_routes_soft_secondary_audit" in read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java"),"audit marker")
