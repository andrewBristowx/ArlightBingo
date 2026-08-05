from pathlib import Path

def need(path, text):
    data=Path(path).read_text(encoding='utf-8')
    assert text in data, f'falta {text!r} en {path}'

need('pom.xml','<version>1.48.38</version>')
need('src/main/resources/plugin.yml','version: 1.48.38')
java='src/main/java/com/arlight/bingo/listeners/OverworldIslandLoreDecorator.java'
need(java,'1.48.38-fixed-mine-legacy-scan-progressive-moss-biome-1')
need(java,'detectLegacyMineSitesRobust')
need(java,'fixedMainMineSite')
need(java,'planRestoreMineSignatureSweep')
need(java,'legacyMineRestorationIndependentOfNewSite')
need(java,'progressive-100-percent-mountain-and-brown-zone')
need(java,'villageCleanRadius')
need(java,'smoothStep')
print('OK ArlightBingo 1.48.38')
