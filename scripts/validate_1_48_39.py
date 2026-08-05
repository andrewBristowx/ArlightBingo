from pathlib import Path
root = Path(__file__).resolve().parents[1]
text = (root / "src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java").read_text()
assert "1.48.39-mine-cleanup-independent-final-accessibility-spine-1" in text
assert "planFinalAccessibilitySpine" in text
assert "plannedWalkableNear" in text
assert "mine-cleanup" in text
assert "mergePlan" in text
assert "<version>1.48.39</version>" in (root / "pom.xml").read_text()
assert "version: 1.48.39" in (root / "src/main/resources/plugin.yml").read_text()
print("validate_1_48_39: OK")
