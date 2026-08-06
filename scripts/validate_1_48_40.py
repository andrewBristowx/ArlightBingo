from pathlib import Path
root = Path(__file__).resolve().parents[1]
terrain = (root / "src/main/java/com/arlight/bingo/listeners/OverworldTerrainBlendCommands.java").read_text()
decorator = (root / "src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java").read_text()
safety = (root / "src/main/java/com/arlight/bingo/listeners/OverworldVillageSafety.java").read_text()
assert "1.48.40-player-centered-terrain-blend-1" in terrain
assert "snapshot create" in terrain
assert "columnContainsArchitecture" in terrain
assert "fitPlane" in terrain
assert "return List.of();" in decorator[decorator.index("detectLegacyMineSitesRobust"):decorator.index("searchLegacyMineAround")]
assert "mineIntersectsCampaign" in decorator
assert "OverworldTerrainBlendCommands" in safety
assert "<version>1.48.40</version>" in (root / "pom.xml").read_text()
assert "version: 1.48.40" in (root / "src/main/resources/plugin.yml").read_text()
print("validate_1_48_40: OK")
