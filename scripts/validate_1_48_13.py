#!/usr/bin/env python3
from pathlib import Path
R = Path(__file__).resolve().parents[1]
def read(p): return (R / p).read_text(encoding='utf-8')
def need(c, m):
    if not c: raise SystemExit(m)

need(read('BUILD_VERSION.txt').strip() == '1.48.13', 'BUILD_VERSION')
need(read('SOURCE_VERSION.txt').strip() == 'ArlightBingo 1.48.13 CORRIDOR-CONNECTIVITY-AUDIT', 'SOURCE_VERSION')
need('<version>1.48.13</version>' in read('pom.xml'), 'pom')
need('version: 1.48.13' in read('src/main/resources/plugin.yml'), 'plugin')
u = read('src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java')
b = read('src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java')
need('1.48.13-corridor-connectivity-audit-1' in b, 'structure marker')
need('passed_1_48_13_corridor_connectivity_audit' in b, 'audit marker')
need('disconnectedRoadSample' in u, 'corridor connectivity audit')
need('resolveRoadWalkY' in u, 'dynamic road height')
need('Math.abs(current.actualWalkY - next.actualWalkY) > 1' in u, 'one-block step rule')
need('name.endsWith("_DOOR") || name.endsWith("_FENCE_GATE")' in u, 'openable route blocks')
need('camino cortado en todo el corredor' in u, 'corridor diagnostic')
need('Map<String, List<RoadCell>> roadRoutes' in u, 'route grouping')
need('repairRegisteredTowerLandings(world, auditRegistry)' in b, 'preserve tower landing phase')
need('repairRegisteredChimneys(auditRegistry)' in b, 'preserve chimney phase')
need('repairRegisteredHouseShells(auditRegistry)' in b, 'preserve house phase')
print('validate_1_48_13: OK')
