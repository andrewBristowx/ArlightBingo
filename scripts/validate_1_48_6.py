#!/usr/bin/env python3
"""Deterministic source and geometry checks for Overworld 1.48.6."""

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

# Release identity and active runtime markers.
require(read("BUILD_VERSION.txt").strip() == "1.48.6", "BUILD_VERSION incorrecto")
require(read("SOURCE_VERSION.txt").strip()
        == "ArlightBingo 1.48.6 FOUNDATION-LADDER-FINALIZATION-AUDIT",
        "SOURCE_VERSION incorrecto")
require("<version>1.48.6</version>" in read("pom.xml"), "pom.xml no declara 1.48.6")
require("version: 1.48.6" in read("src/main/resources/plugin.yml"),
        "plugin.yml no declara 1.48.6")
for source in (builder, audit, landscape):
    require("1.48.6" in source, "una clase activa no declara 1.48.6")
require("1.48.6-foundation-ladder-finalization-audit-1" in builder,
        "falta el marcador estructural 1.48.6")

# Preserve the clean anchor-only generator and terminal failure behavior.
guard = '"template-worlds.overworld.campaign-layout-1-48.enabled", true)) return;'
require(template.count(guard) >= 2,
        "el decorador global no está bloqueado durante la campaña")
require("decorate-on-chunk-load: false" in config,
        "la configuración todavía activa la corrupción global")
require("Files.getLastModifiedTime(baseMarker).toMillis()" in landscape
        and "> Files.getLastModifiedTime(failed).toMillis()" in landscape,
        "FAILED todavía puede reintentarse sin reset")
require("Files.deleteIfExists(folder.resolve(PROGRESS_MARKER))" in landscape,
        "un fallo deja el marcador in-progress")
for forbidden in ("addRestorePhases", "polishCoastlines"):
    require(forbidden not in builder, f"sigue activa la fase heredada {forbidden}")

# Doors and roads remain finalized before the new last vertical-access phase.
require("repairRoadCorridors" in builder
        and "repairRegisteredEntrances" in builder
        and "repairRegisteredVerticalAccess" in builder,
        "falta una fase final registrada")
require(builder.index("restableciendo corredores auditados")
        < builder.index("sellando puertas y accesos auditados")
        < builder.index("restaurando accesos verticales auditados"),
        "el acceso vertical no es la última geometría estructural")
require("for (HouseSpec house : registry.houses)" in audit
        and "for (TowerSpec tower : registry.towers)" in audit,
        "la fase de entradas ya no cubre casas y torres")
require("for (int step = 1; step <= 5; step++)" in audit,
        "la auditoría vuelve a tratar una puerta real como aire")
require("boss-gate-west" in architecture and "boss-gate-east" in architecture,
        "faltan las torres de la puerta monumental")

# The last phase restores every ladder cell and its east-side backing.
vertical_start = audit.index("static List<BlockEdit> repairRegisteredVerticalAccess")
vertical_end = audit.index("private static void replayEntrance", vertical_start)
vertical = audit[vertical_start:vertical_end]
require("for (TowerSpec tower : registry.towers)" in vertical,
        "la reparación vertical no cubre todas las torres")
require("offset < tower.height" in vertical,
        "la reparación vertical no cubre la altura completa")
require("ladderX + 1" in vertical and "Material.STONE_BRICKS" in vertical,
        "la escalera no restaura un respaldo sólido")
require("Material.LADDER" in vertical
        and "minecraft:ladder[facing=west,waterlogged=false]" in vertical,
        "la escalera final no conserva BlockData válido")
require("ladderX - 1" in vertical and "Material.DEEPSLATE_TILES" in vertical,
        "los pisos no reciben desembarco junto a la escalera")
require("escalera=" in audit and "respaldo=" in audit,
        "el diagnóstico de escalera no informa los materiales finales")

# Reproduce a 24-block gate tower after arbitrary earlier overlaps.
final_blocks: dict[tuple[int, int, int], str] = {}
cx, base_y, cz, radius, height = 125, 104, 190, 7, 24
ladder_x = cx + radius - 1
ladder_z = cz + min(2, radius - 2)
for offset in range(height):
    final_blocks[(ladder_x + 1, base_y + offset, ladder_z)] = "backing"
    final_blocks[(ladder_x, base_y + offset, ladder_z)] = "ladder"
for level in range(6, height, 6):
    final_blocks[(ladder_x - 1, base_y + level, ladder_z)] = "landing"
    final_blocks[(ladder_x - 1, base_y + level + 1, ladder_z)] = "air"
for offset in range(height):
    require(final_blocks[(ladder_x, base_y + offset, ladder_z)] == "ladder",
            "boss-gate-west conserva un corte vertical")
    require(final_blocks[(ladder_x + 1, base_y + offset, ladder_z)] == "backing",
            "boss-gate-west conserva una escalera sin respaldo")
for level in range(6, height, 6):
    require(final_blocks[(ladder_x - 1, base_y + level, ladder_z)] == "landing"
            and final_blocks[(ladder_x - 1, base_y + level + 1, ladder_z)] == "air",
            "boss-gate-west no permite desmontar en un piso")

# Wall generation and auditing share one twelve-block continuous core.
require("static final int WALL_FOUNDATION_DEPTH = 12" in terrain,
        "la profundidad de cimiento no está centralizada")
support_start = terrain.index("private static void supportColumn")
support_end = terrain.index("static void supportedGateApproach", support_start)
support = terrain[support_start:support_end]
require("target - WALL_FOUNDATION_DEPTH" in support,
        "el soporte no llega a la profundidad auditada")
require("y < target - WALL_FOUNDATION_DEPTH && existing.isSolid()" in support,
        "el soporte todavía se detiene en el primer bloque superficial")
require(audit.count("OverworldCampaignTerrain148.WALL_FOUNDATION_DEPTH") >= 5,
        "la auditoría no comparte la profundidad del generador")
require("offset=" in audit and "x=" in audit and "z=" in audit,
        "el diagnóstico de cimientos no identifica la columna")

# Model the old shallow-solid/cave case: all twelve audited cells must be rewritten.
target = 110
generated = set()
for y in range(target - 1, target - 13, -1):
    generated.add(y)
require(generated == set(range(target - 12, target)),
        "el núcleo de cimiento no cubre doce capas continuas")

# Preserve the structural constraints already fixed in 1.48.2-1.48.5.
for phrase in ("frontón abierto", "pared abierta", "piso intermedio abierto",
               "entrada de torre enterrada o sin descansillo",
               "panel de cristal desconectado", "corte abrupto del terreno",
               "cimiento de edificio flotante", "cimiento de muralla flotante"):
    require(phrase in audit, f"se perdió la auditoría: {phrase}")
require("connectedGlassPane" in architecture
        and "minecraft:spruce_trapdoor[facing=north,half=bottom," in architecture,
        "se perdió la corrección de cristales o bancos")
require("supportedPathCell" in architecture
        and "supportedGateApproach" in terrain,
        "se perdió el soporte de descansillos o accesos")
require("boss-portal-north-rim" in terrain and "boss-portal-east-rim" in terrain,
        "la ruta exterior vuelve a cruzar la arena")
require("FARMLAND" not in structures and "farmDistrict" not in structures,
        "volvieron cultivos al generador activo")

print("validate_1_48_6: OK")
