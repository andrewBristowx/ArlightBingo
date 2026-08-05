from pathlib import Path

root = Path(__file__).resolve().parents[1]

checks = {
    "pom.xml": ["<version>1.48.28</version>"],
    "src/main/resources/plugin.yml": ["version: 1.48.28"],
    "src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java": [
        'addShapePhases("ciudadela", citadel, 54, 60);',
        "1.48.28-fortress-finish-light-portal-clear-1",
        "finished_gate_cuts_strict_citadel_routes_v18",
        "restoreCriticalPassageLighting",
        "portalSanctuaryFurnishings",
    ],
    "src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java": [
        "shapeCitadelSite",
        "citadelGateOuterPoint",
        '"boss-approach-rim-connector"',
        'id.endsWith("-citadel")',
        "applyMonotonicTerrainGrade",
        "finishBossApproachCut",
        "sealCitadelTowerCorridors",
        "CITADEL_GATE_TRANSITION_LENGTH = 18",
    ],
    "src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java": [
        "auditCitadelSubsurface",
        "auditCriticalPassageLighting",
        "zona reservada del portal contiene atriles",
        "pasaje de la puerta del boss oscuro",
    ],
    "src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java": [
        "Keep a completely empty 13x9 bay",
        "bossGatehouseApron",
        "restoreCriticalPassageLighting",
    ],
    "src/main/java/com/arlight/bingo/listeners/OverworldAutoRepair148.java": [
        'categories.contains("portal")',
        'categories.contains("lighting")',
        "finished-gates-lit-passages-portal-clear",
    ],
}

for rel, needles in checks.items():
    text = (root / rel).read_text(encoding="utf-8")
    for needle in needles:
        assert needle in text, f"missing {needle!r} in {rel}"

terrain = (root / "src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java").read_text()
assert "int outer = 60;" in terrain, "citadel exterior blend became broad again"
assert "for (int depth = 0; depth <= 3; depth++)" in terrain, "buttress projection regressed"
assert "report.roads = 9;" in terrain, "connector route is not counted"
assert "shoulder <= 3" in terrain, "road shoulders regressed"

architecture = (root / "src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java").read_text()
start = architecture.index("static void portalSanctuaryFurnishings")
end = architecture.index("static void arenaFurnishings", start)
portal_method = architecture[start:end]
assert "Material.LECTERN" not in portal_method, "lecterns returned to the Nether portal reserve"
assert "Material.LIGHT" in portal_method, "portal reserve lost its invisible lighting"

builder = (root / "src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java").read_text()
final_phase = builder[builder.index("cerrando estructuras, caminos y portón en orden definitivo"):]
assert final_phase.index("restoreClosedBossRitualGate") < final_phase.index("portalSanctuaryFurnishings")
assert final_phase.index("portalSanctuaryFurnishings") < final_phase.index("restoreCriticalPassageLighting")

print("ArlightBingo 1.48.28 source validation OK")
