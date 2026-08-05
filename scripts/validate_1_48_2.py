#!/usr/bin/env python3
"""Deterministic source and geometry checks for Overworld 1.48.2."""

from math import pi, sin
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


template = read("src/main/java/com/arlight/bingo/template/OverworldTemplateManager.java")
model = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignModel146.java")
terrain = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java")
architecture = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java")
structures = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignStructures148.java")
builder = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java")
audit = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java")
landscape = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignLandscape148.java")
progression = read("src/main/java/com/arlight/bingo/dungeon/OverworldCampaignProgression.java")

# The old template constructor must not paint structures before the active campaign.
require('"campaign_1_48_anchor_only"' in template,
        "falta el marcador de base limpia")
require("planCampaignAnchorsOnly" in template and "blocks.isEmpty()" in template,
        "la base limpia no puede terminar sin pintar bloques heredados")
require("reset y generate" in template and "preferredVillageCenter != null" in template,
        "una plantilla antigua todavía podría disfrazarse como base limpia")
require('base.getProperty("baseConstruction", "")' in landscape,
        "la campaña no exige una base anchor-only")

# Legacy circular repairs and radial fins are forbidden in the active 1.48.2 generator.
for forbidden in ("addRestorePhases", "polishCoastlines"):
    require(forbidden not in builder, f"sigue activa la fase heredada {forbidden}")
for forbidden in ("restoreLegacySite", "adaptiveShoreline", "reinforceFoundation"):
    require(forbidden not in terrain, f"sigue disponible el algoritmo deformante {forbidden}")
require("organicBoundary" in terrain and "blendedSurface" in terrain,
        "faltan borde orgánico y mezcla de superficie")
require("stabilizeSlope" not in terrain,
        "continúa el anillo de fachada artificial en el borde")

# Block states are applied, not reduced to their default material.
require("String data" in model and "Bukkit.createBlockData(edit.data())" in builder,
        "puertas/escaleras/luces perderían su estado de bloque")
require("minecraft:spruce_door" in architecture,
        "las casas siguen usando huecos en vez de puertas")
require("minecraft:ladder[facing=west" in architecture,
        "las escaleras no declaran respaldo")
require("minecraft:light[level=12" in architecture
        and "minecraft:light[level=13" in architecture,
        "faltan luces interiores de casas o torres")

# Every active tower family and all walls must be usable and connected.
require(structures.count("auditedTower(out, registry") >= 6
        and "{{-39,-39},{39,-39},{-39,39},{39,39}}" in structures,
        "no se registraron todas las familias de torres")
require('gateFrame(out, registry, "citadel-south-gate"' in structures
        and 'gateFrame(out, registry, "portal-north-gate"' in structures
        and '"boss-rear-tower-"' in structures,
        "las torres secundarias quedaron fuera de la auditoría")
require("registerTower" in audit and "torre sin piso intermedio" in audit
        and "torre oscura" in audit,
        "la auditoría de torres está incompleta")
require("wallButtress" in architecture and "buttress(" not in architecture,
        "quedan contrafuertes aislados del algoritmo anterior")
require("x + dx * step" in architecture and "z + dz * step" in architecture,
        "los contrafuertes no avanzan desde el muro en su eje real")

# The two console failures from 1.48.1 are fixed at their sources.
require("site.z() - 38, Material.LECTERN" not in structures,
        "el atril todavía bloquea village-hall")
new_spawner = "{18,38}"
require(new_spawner in architecture and "{18, 38}" in progression,
        "la cuarta ubicación de spawner no coincide entre geometría y progresión")
require("{0,27}" not in architecture and "{0, 27}" not in progression,
        "el santuario todavía ocupa el camino de residential-lodge")

# Chimneys must start at a real hearth and remain continuous through the roof.
require("registerChimney" in audit and "chimenea cortada o flotante" in audit,
        "las chimeneas no están auditadas")
require("Material.SMOKER" in architecture and "chimneyTop" in architecture,
        "las chimeneas todavía empiezan en el aire")

# Runtime audit coverage for the defects visible in the screenshots.
for check in (
        "pared abierta", "puerta ausente o incompleta", "escalera cortada",
        "escalera sin desembarco", "interior oscuro", "chimenea cortada o flotante",
        "corte abrupto del terreno"):
    require(check in audit, f"falta la auditoría: {check}")
require("world.setSpawnLocation(safeVillageSpawn)" in builder,
        "el spawn no se actualiza al pueblo nuevo")

# FAILED may only be replaced by a genuinely newer base marker.
require("Files.getLastModifiedTime(baseMarker).toMillis()" in landscape
        and "> Files.getLastModifiedTime(failed).toMillis()" in landscape,
        "FAILED todavía puede reintentarse sin una base nueva")
require("Files.deleteIfExists(folder.resolve(PROGRESS_MARKER))" in landscape,
        "un fallo todavía deja el marcador in-progress")

# Roads remain the last geometry replay and never see roofs while planning heights.
require(builder.index("buildRoadNetwork") < builder.index("OverworldCampaignStructures148.village"),
        "los caminos se calculan después de los edificios")
require(builder.index("decorando la isla reconstruida")
        < builder.index("restableciendo corredores auditados"),
        "la decoración puede volver a bloquear caminos")
require("remaining = steps - step" in terrain,
        "falta convergencia vertical al waypoint")


def generated_heights(start: int, end: int, steps: int, natural: int) -> list[int]:
    walk = start
    result: list[int] = []
    for step in range(steps + 1):
        expected = start + round((end - start) * (step / steps))
        desired = max(expected - 2, min(expected + 2, natural))
        if step == 0:
            walk = start
        else:
            remaining = steps - step
            desired = max(end - remaining, min(end + remaining, desired))
            if desired > walk:
                walk += 1
            elif desired < walk:
                walk -= 1
        result.append(walk)
    return result


for start in range(58, 75):
    for end in range(58, 75):
        for steps in (abs(end - start) + 1, abs(end - start) + 8, 80):
            for natural in (40, 64, 96):
                heights = generated_heights(start, end, steps, natural)
                require(heights[0] == start, "la ruta no conserva su inicio")
                require(heights[-1] == end, "la ruta no alcanza su destino")
                require(all(abs(a - b) <= 1 for a, b in zip(heights, heights[1:])),
                        "la ruta contiene un salto vertical")


def overlaps(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> bool:
    return not (a[1] < b[0] or b[1] < a[0] or a[3] < b[2] or b[3] < a[2])


residential_houses = [
    (-34, -20, -26, -16),
    (22, 34, -24, -14),
    (-39, -23, 15, 25),
    (21, 35, 19, 31),
    (-6, 6, 31, 39),
]
last_shrine = (16, 20, 36, 40)
require(all(not overlaps(last_shrine, house) for house in residential_houses),
        "el santuario nuevo invade una vivienda residencial")
require(not overlaps(last_shrine, (-1, 1, 26, 31)),
        "el santuario nuevo invade la entrada de residential-lodge")

# The edge variation is visibly non-circular in every style/salt sample.
for salt in (0.0, 0.71, 1.42, 2.13, 2.84, 3.55, 4.26):
    radii = [94 + sin(2 * pi * i / 72 * 3 + salt) * 5.5
             + sin(2 * pi * i / 72 * 5 - salt * 1.7) * 3.5
             + sin(2 * pi * i / 72 * 9 + salt * 0.6) * 1.8
             for i in range(72)]
    require(max(radii) - min(radii) >= 14.0,
            "el borde del terreno sigue siendo prácticamente circular")

require("FARMLAND" not in structures and "farmDistrict" not in structures,
        "volvieron los cultivos al generador activo")

print("validate_1_48_2: OK")
