#!/usr/bin/env python3
"""Deterministic source and geometry checks for Overworld 1.48.3."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


template = read("src/main/java/com/arlight/bingo/template/OverworldTemplateManager.java")
terrain = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java")
architecture = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java")
structures = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignStructures148.java")
builder = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java")
audit = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java")
landscape = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignLandscape148.java")
config = read("src/main/resources/config-sections/worlds.yml")
progression = read("src/main/java/com/arlight/bingo/dungeon/OverworldCampaignProgression.java")

# Release identity must be coherent everywhere that controls runtime state.
require(read("BUILD_VERSION.txt").strip() == "1.48.3", "BUILD_VERSION incorrecto")
require(read("SOURCE_VERSION.txt").strip()
        == "ArlightBingo 1.48.3 VISUAL-COLLISION-FOUNDATION-AUDIT",
        "SOURCE_VERSION incorrecto")
for source in (builder, audit, landscape):
    require("1.48.3" in source, "una clase activa no declara 1.48.3")
require("1.48.3-visual-collision-foundation-audit-1" in builder,
        "falta la revisión estructural 1.48.3")

# The legacy chunk decorator must never race the audited campaign layout.
guard = '"template-worlds.overworld.campaign-layout-1-48.enabled", true)) return;'
require(template.count(guard) >= 2,
        "el decorador global no está bloqueado en evento y tarea diferida")
require("decorate-on-chunk-load: false" in config,
        "la configuración todavía activa la corrupción global")
require(template.index(guard) < template.index("decorateLoadedTemplateChunk(chunk)"),
        "el bloqueo de campaña ocurre después de programar la decoración")

# A house path may replace only the real two-block doorway at step zero.
require("int sideRadius = step == 0 ? 0 : 1" in architecture,
        "el camino privado todavía abre tres bloques en la fachada")
require("int clearance = step == 0 ? 1 : 2" in architecture,
        "el camino privado todavía elimina el dintel")
require("minecraft:spruce_door" in architecture,
        "las casas no reinstalan una puerta real")
require("piso intermedio abierto" in audit,
        "la auditoría no comprueba los pisos de las casas")
require("x != ladderLocalX || z != ladderLocalZ" in architecture,
        "los pisos no reservan únicamente el hueco de la escalera")

# Lamps need collision-aware placement against both envelopes and entrances.
require("blocksHouseOrEntrance" in audit and "placeLampIfClear" in architecture,
        "los faroles no consultan edificios y accesos")
require(structures.count("lampsAround(out, registry") == 4,
        "algún anillo de faroles sigue usando colocación ciega")
require("OverworldCampaignTerrain148.lamp(out, site.x() + lamp[0]" not in structures,
        "los faroles explícitos del pueblo omiten la comprobación de colisión")
require(structures.index('"boss-rear-tower-"')
        < structures.index("lampsAround(out, registry, site.x(), y, site.z(), 39, 12)"),
        "los faroles de la arena se eligen antes de registrar las torres traseras")
require(structures.count("auditedTower(out, world, registry") >= 6,
        "alguna familia de torres activas quedó fuera del registro")

# Building foundations blend into terrain and the active walls use matching shapes.
require("buildingPad(out, world" in architecture,
        "casas o torres siguen usando solo una placa fija")
require("static void buildingPad" in terrain and "edgeDistance" in terrain,
        "falta la transición local de cimientos")
require("squareWallSupport" in terrain and "circleWallSupport" in terrain,
        "los soportes no distinguen murallas cuadradas y circulares")
require("ringSupport(" not in terrain,
        "sigue activo el soporte circular genérico de 1.48.2")
require("FortificationShape.SQUARE" in terrain
        and "FortificationShape.CIRCLE" in terrain,
        "las murallas reales no quedaron registradas para auditoría")
require("cimiento de edificio flotante" in audit
        and "cimiento de muralla flotante" in audit,
        "la auditoría no bloquea cimientos flotantes")

# Preserve the route blockers fixed in 1.48.2 while changing foundations.
require("site.z() - 38, Material.LECTERN" not in structures,
        "el atril volvió a bloquear village-hall")
require("{18,38}" in architecture and "{18, 38}" in progression,
        "la cuarta ubicación de spawner no coincide con la progresión")
require("{0,27}" not in architecture and "{0, 27}" not in progression,
        "un santuario volvió a ocupar la entrada de residential-lodge")
require("registerChimney" in audit and "chimenea cortada o flotante" in audit,
        "la auditoría dejó de comprobar chimeneas continuas")

# The village edge must not receive independent vertical noise per column.
village_shape = terrain[terrain.index("static List<BlockEdit> shapeVillage"):
                        terrain.index("static List<BlockEdit> shapeSite")]
require("noise(x, z" not in village_shape,
        "el pueblo conserva el ruido vertical que producía huecos")
require("organicBoundary" in terrain and "blendedSurface" in terrain,
        "se perdió la integración orgánica del borde")

# The buried north gate is now a supported, registered, final corridor.
require("supportedGateApproach" in terrain
        and '"citadel-north-gate"' in structures,
        "la puerta norte de la ciudadela sigue sin acceso nivelado")
require("registry.registerRoadCell" in terrain,
        "el acceso de ciudadela no participa en la repetición final")
require(builder.index("buildRoadNetwork")
        < builder.index("OverworldCampaignStructures148.village"),
        "los caminos dejaron de planificarse antes de los edificios")
require(builder.index("decorando la isla reconstruida")
        < builder.index("restableciendo corredores auditados"),
        "la decoración todavía puede bloquear el corredor final")

# Failed layouts remain terminal until a genuinely newer clean base exists.
require("Files.getLastModifiedTime(baseMarker).toMillis()" in landscape
        and "> Files.getLastModifiedTime(failed).toMillis()" in landscape,
        "FAILED todavía puede reintentarse sin reset")
require("Files.deleteIfExists(folder.resolve(PROGRESS_MARKER))" in landscape,
        "un fallo deja el marcador in-progress")

# The active source must stay free of legacy terrain repairs and rectangular farms.
for forbidden in ("addRestorePhases", "polishCoastlines"):
    require(forbidden not in builder, f"sigue activa la fase heredada {forbidden}")
for forbidden in ("restoreLegacySite", "adaptiveShoreline", "reinforceFoundation"):
    require(forbidden not in terrain, f"sigue activo el algoritmo heredado {forbidden}")
require("FARMLAND" not in structures and "farmDistrict" not in structures,
        "volvieron los cultivos al generador activo")


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
            walk += (desired > walk) - (desired < walk)
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

print("validate_1_48_3: OK")
