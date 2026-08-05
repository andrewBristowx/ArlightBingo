#!/usr/bin/env python3
from pathlib import Path

R = Path(__file__).resolve().parents[1]

def read(path):
    return (R / path).read_text(encoding="utf-8")

def need(condition, message):
    if not condition:
        raise SystemExit(message)

audit = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java")
builder = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java")
landscape = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignLandscape148.java")

need(read("BUILD_VERSION.txt").strip() == "1.48.14", "BUILD_VERSION")
need("<version>1.48.14</version>" in read("pom.xml"), "pom")
need("version: 1.48.14" in read("src/main/resources/plugin.yml"), "plugin.yml")
need("roadMemberships" in audit and "roadRoutes" in audit, "multi-route registry")
need("computeIfAbsent(id" in audit, "route registration")
need("endpointRegion" in audit, "real endpoint regions")
need("maximumX - minimumX" not in audit, "legacy bounding-box axis remains")
need("boss-north-approach" in audit, "ritual route reuse")
need("new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4}" in audit,
     "final-height search")
need("1.48.14-route-membership-endpoint-audit-1" in builder, "structure marker")
need("passed_1_48_14_route_membership_endpoint_audit" in builder, "audit marker")
need("Overworld 1.48.11" not in landscape, "obsolete failure label")
need("repairRegisteredHouseShells(auditRegistry)" in builder, "house repair preserved")
need("repairRegisteredTowerLandings(world, auditRegistry)" in builder,
     "tower landing repair preserved")
print("validate_1_48_14: OK")
