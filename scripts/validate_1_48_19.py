from pathlib import Path
root = Path(__file__).resolve().parent
if root.name == 'scripts':
    root = root.parent
checks = {
    'pom.xml': ['<version>1.48.19</version>'],
    'src/main/resources/plugin.yml': ['version: 1.48.19'],
    'src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java': [
        'int bypassX = cx + 25;',
        'paveRegisteredCitadelSegment',
        'reinforceBossWallFoundation',
        '"citadel-central-court"',
    ],
    'src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java': [
        'restoreCitadelGreatHallInterior',
        'Extra side benches and archives',
    ],
    'src/main/java/com/arlight/bingo/listeners/OverworldAutoRepair148.java': [
        'zone-great-hall-furniture',
        'zone-boss-wall-foundation',
        'Repair one structural zone per pass',
    ],
    'src/main/java/com/arlight/bingo/listeners/OverworldCampaignStructures148.java': [
        'fortified bypass registered later',
    ],
}
for relative, needles in checks.items():
    text = (root / relative).read_text(encoding='utf-8')
    for needle in needles:
        assert needle in text, f'Missing {needle!r} in {relative}'
structures = (root / 'src/main/java/com/arlight/bingo/listeners/OverworldCampaignStructures148.java').read_text()
assert 'villageStreet(out, registry, site.x(), y, site.z() - 50, site.x(), y, site.z() + 61, 9);' not in structures
for java in (root / 'src/main/java').rglob('*.java'):
    text = java.read_text(encoding='utf-8')
    assert text.count('{') == text.count('}'), f'Brace mismatch in {java}'
print('VALIDACION 1.48.19 OK')
