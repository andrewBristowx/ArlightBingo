#!/usr/bin/env python3
"""Deterministic source and geometry checks for Overworld 1.48.8."""

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
require(read("BUILD_VERSION.txt").strip() == "1.48.8", "BUILD_VERSION incorrecto")
require(read("SOURCE_VERSION.txt").strip()
        == "ArlightBingo 1.48.8 INTERIOR-LANDMARK-FURNISHING-AUDIT",
        "SOURCE_VERSION incorrecto")
require("<version>1.48.8</version>" in read("pom.xml"), "pom.xml no declara 1.48.8")
require("version: 1.48.8" in read("src/main/resources/plugin.yml"),
        "plugin.yml no declara 1.48.8")
for source in (builder, audit, landscape):
    require("1.48.8" in source, "una clase activa no declara 1.48.8")
require("1.48.8-interior-landmark-furnishing-audit-1" in builder,
        "falta el marcador estructural 1.48.8")

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

# Final structural phase order from 1.48.6 remains unchanged.
require(builder.index("restableciendo corredores auditados")
        < builder.index("sellando puertas y accesos auditados")
        < builder.index("restaurando accesos verticales auditados"),
        "el acceso vertical no es la última geometría estructural")
require("WALL_FOUNDATION_DEPTH = 12" in terrain,
        "se perdió el núcleo continuo de cimientos")
require("repairRegisteredVerticalAccess" in audit
        and "minecraft:ladder[facing=west,waterlogged=false]" in audit,
        "se perdió la restauración final de escaleras")

# The boss road exactly matches the 15-wide opening between two radius-7 towers.
for phrase in ("BOSS_GATE_TOWER_OFFSET = 15", "BOSS_GATE_TOWER_RADIUS = 7",
               "BOSS_GATE_CLEAR_HALF_WIDTH"):
    require(phrase in architecture, f"falta la geometría compartida: {phrase}")
require("BOSS_GATE_CLEAR_HALF_WIDTH," in structures,
        "boss-north-approach sigue usando un ancho literal")
tower_offset = 15
tower_radius = 7
clear_half_width = tower_offset - tower_radius - 1
west_inner_wall = -tower_offset + tower_radius
east_inner_wall = tower_offset - tower_radius
road_columns = set(range(-clear_half_width, clear_half_width + 1))
require(clear_half_width == 7, "la abertura ceremonial no mide quince bloques")
require(west_inner_wall not in road_columns and east_inner_wall not in road_columns,
        "el corredor todavía contiene una pared de torre")

# Reproduce the exact 1.48.6 collision: west ladder backing stays at -8, outside road -7..7.
west_ladder_backing = -tower_offset + tower_radius
require(west_ladder_backing == -8 and min(road_columns) == -7,
        "la pared de boss-gate-west vuelve a ocupar boss-north-approach")
require("suelo=" in audit and "paso=" in audit and "cabeza=" in audit,
        "el diagnóstico de camino no informa los materiales finales")

# The northern terrain no longer applies a binary two-block platform.
require("dz < -20 ? 2" not in terrain,
        "sigue activa la meseta binaria del acceso del jefe")
require("designed -= smooth((dz - 22.0D) / 28.0D)" in terrain,
        "falta la transición suave del terreno del jefe")
require("landscapeBossApproachShoulders" in terrain
        and '"boss-north-approach".equals(id)' in terrain,
        "el acceso del jefe no recibe hombros paisajísticos propios")

# Arena seating is made of filled bands, not isolated polar rings.
terrace_start = architecture.index("static void terraces")
terrace_end = architecture.index("static void bossDais", terrace_start)
terraces = architecture[terrace_start:terrace_end]
require("for (int dx = -44; dx <= 44; dx++)" in terraces
        and "for (int dz = -44; dz <= 44; dz++)" in terraces,
        "las gradas no forman bandas continuas")
require("distance < 37.5D ? 0 : distance < 41.0D ? 1 : 2" in terraces,
        "las tres alturas de grada no están definidas")
require("northOpening" in terraces and "Material.AIR" in terraces,
        "las gradas pueden cerrar el pasillo norte")
require("for (int radius = 35; radius <= 43; radius += 4)" not in terraces,
        "siguen activas las líneas circulares flotantes")

# Village detail fixes are structural and auditable.
require("clusteredVillageSurface" in terrain
        and "Math.floorDiv(x, size)" in terrain,
        "el césped continúa usando ruido por bloque")
require("registerPlanter" in architecture and "PlanterSpec" in audit,
        "las macetas no quedaron registradas")
require("Material.SPRUCE_PLANKS" in architecture
        and "maceta flotante" in audit,
        "las macetas no tienen soporte completo auditado")
require("for (int layer = 0; layer <= 4; layer++)" in architecture
        and "Material.SPRUCE_FENCE" in architecture,
        "el pabellón sigue siendo un techo plano bajo")
require("minecraft:bell[attachment=ceiling,facing=north,powered=false]" in architecture,
        "la campana del pabellón no está declarada como colgante")
require("Material.AMETHYST_CLUSTER" in architecture
        and "Material.SEA_LANTERN" in architecture,
        "la fuente no conserva su nuevo remate")
require("auditBellPavilion" in audit
        and "pabellón de campana sin soporte continuo" in audit,
        "el pabellón no quedó protegido por la auditoría")
require("auditArenaTerraces" in audit
        and "gradas bloquean el pasillo ceremonial norte" in audit,
        "las gradas no quedaron protegidas por la auditoría")

# Preserve all structural checks fixed in 1.48.2-1.48.6.
for phrase in ("frontón abierto", "pared abierta", "piso intermedio abierto",
               "entrada de torre enterrada o sin descansillo",
               "panel de cristal desconectado", "corte abrupto del terreno",
               "cimiento de edificio flotante", "cimiento de muralla flotante",
               "torre inaccesible por escalera"):
    require(phrase in audit, f"se perdió la auditoría: {phrase}")
require("boss-portal-north-rim" in terrain and "boss-portal-east-rim" in terrain,
        "la ruta exterior vuelve a cruzar la arena")
require("FARMLAND" not in structures and "farmDistrict" not in structures,
        "volvieron cultivos al generador activo")

print("validate_1_48_8: OK")

# Interior and landmark furnishing checks for 1.48.8
architecture = (ROOT / "src/main/java/com/arlight/bingo/listeners/OverworldCampaignArchitecture148.java").read_text()
audit = (ROOT / "src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java").read_text()
structures = (ROOT / "src/main/java/com/arlight/bingo/listeners/OverworldCampaignStructures148.java").read_text()
for token in ["decorateHouseInterior", "bedroom(", "workshop(", "portalSanctuaryFurnishings", "arenaFurnishings", "decorateTowerLevel"]:
    assert token in architecture, token
assert "interior sin mobiliario suficiente" in audit
assert "auditPortalFurnishings" in audit
assert "auditArenaFurnishings" in audit
assert "portalSanctuaryFurnishings(out" in structures
assert "arenaFurnishings(out" in structures
print("Validación ArlightBingo 1.48.8 completada")
