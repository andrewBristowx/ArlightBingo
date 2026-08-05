from pathlib import Path

root = Path(__file__).resolve().parents[1]
checks = {
    'pom.xml': ['<version>1.48.17</version>'],
    'src/main/resources/plugin.yml': ['version: 1.48.17'],
    'src/main/java/com/arlight/bingo/listeners/OverworldAutoRepair148.java': [
        'class OverworldAutoRepair148',
        'autorepair.max-passes',
        'saveRollback',
        'repairTerrainTransitions',
        'repairDiagnosedRoads',
        'parseRoadIssues',
        'stabilizeDiagnosedRoadEdges',
        'rollback(',
        'MEMORY_FILE',
    ],
    'src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java': [
        'inspectAndPlan(',
        'snapshotForBuild(',
        'Autorreparación 1.48.17',
        'passed_1_48_17_targeted_route_repair',
        'version=1.48.17',
    ],
    'src/main/java/com/arlight/bingo/commands/BingoCommand.java': [
        'case "repair"',
        '/bingo repair overworld',
        '"scan", "preview", "apply", "region"',
    ],
    'src/main/java/com/arlight/bingo/template/OverworldTemplateManager.java': [
        'repairScan(', 'repairPreview(', 'repairApply(', 'repairRollback(', 'repairStatus('
    ],
    'src/main/java/com/arlight/bingo/BingoPlugin.java': [
        'template-worlds.overworld.autorepair.enabled',
        'template-worlds.overworld.autorepair.max-passes',
    ],
}
errors = []
for relative, needles in checks.items():
    path = root / relative
    if not path.is_file():
        errors.append(f'Falta {relative}')
        continue
    text = path.read_text(encoding='utf-8')
    for needle in needles:
        if needle not in text:
            errors.append(f'Falta {needle!r} en {relative}')

java_files = list((root / 'src/main/java').rglob('*.java'))
if len(java_files) < 96:
    errors.append(f'Solo se encontraron {len(java_files)} archivos Java')
for path in java_files:
    text = path.read_text(encoding='utf-8')
    if text.count('{') != text.count('}'):
        errors.append(f'Llaves desbalanceadas en {path.relative_to(root)}')

if errors:
    print('\n'.join(f'ERROR: {e}' for e in errors))
    raise SystemExit(1)
print(f'OK: ArlightBingo 1.48.17 validado ({len(java_files)} archivos Java).')
