from pathlib import Path
root=Path(__file__).resolve().parents[1]
checks={
"pom.xml":"<version>1.48.26</version>",
"src/main/resources/plugin.yml":"version: 1.48.26",
"src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java":"void clearRoute(String id)",
"src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java":"terrain_following",
"src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java":"Five-block-deep masonry jambs",
}
# Terrain check uses actual implementation marker rather than metadata.
checks["src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java"]="registry.clearRoute(id);"
for rel,needle in checks.items():
    text=(root/rel).read_text()
    assert needle in text, f"missing {needle} in {rel}"
print("ArlightBingo 1.48.26 source validation OK")
