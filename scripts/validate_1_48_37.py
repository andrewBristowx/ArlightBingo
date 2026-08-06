from pathlib import Path
root=Path(__file__).resolve().parents[1]
checks=[]
def need(path,text):
 s=(root/path).read_text(encoding='utf-8')
 assert text in s, f'{text!r} missing from {path}'
 checks.append(f'{path}: {text}')
need('pom.xml','<version>1.48.37</version>')
need('src/main/resources/plugin.yml','version: 1.48.37')
need('src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java','1.48.37-single-accessible-mine-expanded-forest-corruption-1')
need('src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java','detectLegacyMineSites')
need('src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java','planMineRestoration')
need('src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java','planAccessibleServiceLoop')
need('src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java','validateAccessibleMinePlan')
need('src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java','planContaminatedHalfBiome')
need('src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java','mineGenerationId')
java=(root/'src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java').read_text()
assert java.count('{') == java.count('}'), 'unbalanced braces'
assert java.count('(') == java.count(')'), 'unbalanced parentheses'
assert 'findMineSite(world, anchors, legacyMines)' in java
assert 'case MINE_RESTORE -> isMineRestorable(current);' in java
print(f'OK ArlightBingo 1.48.37: {len(checks)+5} checks')
