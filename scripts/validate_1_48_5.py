#!/usr/bin/env python3
"""Deterministic source and geometry checks for Overworld 1.48.5."""

from collections import deque
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

# Release identity must remain coherent in runtime state, Maven and plugin metadata.
require(read("BUILD_VERSION.txt").strip() == "1.48.5", "BUILD_VERSION incorrecto")
require(read("SOURCE_VERSION.txt").strip()
        == "ArlightBingo 1.48.5 ENTRANCE-FINALIZATION-AUDIT",
        "SOURCE_VERSION incorrecto")
require("<version>1.48.5</version>" in read("pom.xml"), "pom.xml no declara 1.48.5")
require("version: 1.48.5" in read("src/main/resources/plugin.yml"),
        "plugin.yml no declara 1.48.5")
for source in (builder, audit, landscape):
    require("1.48.5" in source, "una clase activa no declara 1.48.5")
require("1.48.5-entrance-finalization-audit-1" in builder,
        "falta el marcador estructural 1.48.5")

# The legacy chunk decorator must never race the audited campaign layout.
guard = '"template-worlds.overworld.campaign-layout-1-48.enabled", true)) return;'
require(template.count(guard) >= 2,
        "el decorador global no está bloqueado en evento y tarea diferida")
require("decorate-on-chunk-load: false" in config,
        "la configuración todavía activa la corrupción global")
require(template.index(guard) < template.index("decorateLoadedTemplateChunk(chunk)"),
        "el bloqueo de campaña ocurre después de programar la decoración")

# Houses keep a two-block doorway, a flush full-block porch and connected panes.
require("int sideRadius = step == 0 ? 0 : 1" in architecture,
        "el camino privado todavía abre tres bloques en la fachada")
require("int clearance = step == 0 ? 1 : 2" in architecture,
        "el camino privado todavía elimina el dintel")
require("Material.SMOOTH_STONE" in architecture,
        "el porche no usa bloques completos")
village_house = architecture[architecture.index("static void villageHouse"):
                             architecture.index("private static void sideAwning")]
require("SMOOTH_STONE_SLAB" not in village_house,
        "el porche conserva losas que crean zanjas de medio bloque")
require("connectedGlassPane" in architecture
        and "north=true,east=false,south=true,west=false" in architecture
        and "north=false,east=true,south=false,west=true" in architecture,
        "los cristales no declaran conexiones paralelas a la fachada")
require("panel de cristal desconectado" in audit,
        "la auditoría no rechaza paneles aislados")
require("minecraft:spruce_trapdoor[facing=north,half=bottom," in architecture
        and "open=true,powered=false,waterlogged=false" in architecture,
        "los respaldos de banco siguen horizontales")
require("minecraft:spruce_door" in architecture,
        "las casas no reinstalan una puerta real")
require("piso intermedio abierto" in audit,
        "la auditoría no comprueba los pisos de las casas")

# Every tower has an inward-facing entrance and a supported, audited landing.
require("record TowerSpec" in audit and "Facing front" in audit,
        "las torres no conservan la orientación de su entrada")
require("towerEntranceLanding" in architecture
        and "supportedPathCell(out, world" in architecture,
        "las entradas de torre no reciben descansillos apoyados")
require("int sideRadius = step == 0 ? 0 : 2" in architecture
        and "int clearance = step == 0 ? 1 : 3" in architecture,
        "el descansillo vuelve a abrir el dintel o los laterales de la torre")
require("entrada de torre enterrada o sin descansillo" in audit,
        "la auditoría no comprueba los descansillos de torre")
require("for (int step = 1; step <= 5; step++)" in audit,
        "la auditoría vuelve a exigir aire donde existe la puerta real")
require("repairRegisteredEntrances" in audit
        and "for (HouseSpec house : registry.houses)" in audit
        and "for (TowerSpec tower : registry.towers)" in audit,
        "la fase final no restaura todas las entradas registradas")
require(audit.count("minecraft:spruce_door[facing=") >= 2,
        "la fase final no reinstala las dos mitades de cada puerta")
for expected in ("Facing.NORTH", "Facing.SOUTH"):
    require(expected in structures, "faltan torres orientadas hacia el recinto")
require(structures.count("auditedTower(out, world, registry") >= 6,
        "alguna familia de torres activas quedó fuera del registro")

# Late phases must finish public corridors first and private entrances last.
require(builder.index("decorando la isla reconstruida")
        < builder.index("restableciendo corredores auditados")
        < builder.index("sellando puertas y accesos auditados"),
        "las entradas no son la última geometría estructural aplicada")

# The raised military supply pad must be emitted before its street connector.
military = structures[structures.index("static List<BlockEdit> military"):
                      structures.index("static List<BlockEdit> citadel")]
supply_house = military.index('"military-supply"')
supply_connector = military.index("site.x() - 13, y + 2, site.z() - 23")
require(supply_house < supply_connector,
        "el cimiento de military-supply todavía se aplica después de su conexión")

# The arena's transverse road used to erase both monumental tower doors.
boss_arena = structures[structures.index("static List<BlockEdit> bossArena"):
                        structures.index("static List<BlockEdit> portal")]
require(boss_arena.index("straightRoad(out, ritualGate.getBlockX() - 16")
        < boss_arena.index("monumentalArenaEntrance(out, world, registry"),
        "el camino transversal de la arena todavía borra las puertas monumentales")

# Lamps remain collision-aware and all special block data is validated by Bukkit.
require("blocksHouseOrEntrance" in audit and "placeLampIfClear" in architecture,
        "los faroles no consultan edificios y accesos")
require(structures.count("lampsAround(out, registry") == 4,
        "algún anillo de faroles sigue usando colocación ciega")
require("Bukkit.createBlockData(edit.data())" in audit,
        "la fase no valida datos de paneles, puertas y trampillas")

# The military reward no longer deletes one of the twelve barracks lights.
require("site.x() + 20, y, site.z() + 3" in military,
        "el pedestal militar no fue desplazado fuera de los barracones")
require("site.x() + 6, y, site.z() + 3" not in military,
        "el pedestal militar todavía borra una luz interior")
barracks_lights = {(sx * 6, floor * 5 + 3, 5 + sz * 4)
                   for floor in range(3) for sx in (-1, 1) for sz in (-1, 1)}
pedestal = {(20 + x, y, 3 + z)
            for x in range(-3, 4) for z in range(-3, 4) for y in range(4)}
require(barracks_lights.isdisjoint(pedestal),
        "la geometría del pedestal aún intersecta una luz de barracón")

# Roads use a full rounded brush instead of separated perpendicular stripes.
brush_guard = "ox * ox + oz * oz > halfWidth * halfWidth + halfWidth"
require(architecture.count(brush_guard) >= 2 and brush_guard in terrain,
        "algún generador de caminos conserva la brocha con huecos diagonales")
require("registry.registerRoadCell(id, x, walkY, z, floor)" in terrain,
        "los caminos continuos no registran todas sus celdas")
require("static void supportedPathCell" in terrain,
        "los senderos de casas y torres no se apoyan hasta terreno real")


def rounded_brush(cx: int, cz: int, half: int) -> set[tuple[int, int]]:
    return {(cx + ox, cz + oz)
            for ox in range(-half, half + 1)
            for oz in range(-half, half + 1)
            if ox * ox + oz * oz <= half * half + half}


# Reproduce the worst raster case: a one-block diagonal road. It must be one
# connected surface and cannot leave an enclosed single-cell hole.
road_cells: set[tuple[int, int]] = set()
for step in range(48):
    road_cells |= rounded_brush(step, step, 2)
queue = deque([next(iter(road_cells))])
visited = {queue[0]}
while queue:
    x, z = queue.popleft()
    for candidate in ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1)):
        if candidate in road_cells and candidate not in visited:
            visited.add(candidate)
            queue.append(candidate)
require(visited == road_cells, "la brocha diagonal produce islas desconectadas")
for x in range(1, 47):
    for z in range(1, 47):
        if (x, z) in road_cells:
            continue
        neighbours = {(x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1)}
        require(not neighbours.issubset(road_cells),
                "la brocha diagonal conserva un agujero rodeado por camino")

# All visible gates receive supported approaches and retaining walls.
for gate in ("military-west-gate", "citadel-west-gate", "citadel-east-gate",
             "citadel-south-approach", "citadel-north-gate",
             "boss-north-approach", "portal-north-approach"):
    require(f'"{gate}"' in structures, f"falta el acceso apoyado {gate}")
require("static void supportedGateApproach" in terrain
        and "wallTop" in terrain and "step >= 0" in terrain,
        "los accesos no revisten los cortes de terreno expuesto")

# Terrain blending stays outside infrastructure and no wall support rewrites deep terrain.
require(terrain.count("Math.max(flatRadius + 18.0D") == 2,
        "la transición de terreno puede encogerse sobre el radio construido")
require("blocksTerrainAudit" in audit and "previous = null" in audit,
        "la auditoría de terreno vuelve a medir edificios o murallas")
require("roadCells.containsKey(new RoadColumn" in audit,
        "la auditoría de terreno no excluye caminos registrados")
require("y < target - 1 && existing.isSolid()" in terrain,
        "los cimientos de muralla todavía reemplazan terreno profundo")
require("case MILITARY ->" in terrain and "Material.GRAVEL" in terrain
        and "Material.ANDESITE" in terrain and "Material.TUFF" in terrain,
        "la explanada militar conserva una sola textura de tierra")
require("squareWallSupport" in terrain and "circleWallSupport" in terrain,
        "los soportes no distinguen murallas cuadradas y circulares")
require("cimiento de edificio flotante" in audit
        and "cimiento de muralla flotante" in audit,
        "la auditoría no bloquea cimientos flotantes")

# Preserve the route blockers and terminal FAILED state fixed in prior revisions.
require("site.z() - 38, Material.LECTERN" not in structures,
        "el atril volvió a bloquear village-hall")
require("{18,38}" in architecture and "{18, 38}" in progression,
        "la cuarta ubicación de spawner no coincide con la progresión")
require("{0,27}" not in architecture and "{0, 27}" not in progression,
        "un santuario volvió a ocupar residential-lodge")
require("Files.getLastModifiedTime(baseMarker).toMillis()" in landscape
        and "> Files.getLastModifiedTime(failed).toMillis()" in landscape,
        "FAILED todavía puede reintentarse sin reset")
require("Files.deleteIfExists(folder.resolve(PROGRESS_MARKER))" in landscape,
        "un fallo deja el marcador in-progress")

# The active source remains free of legacy terrain repairs and farms.
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

print("validate_1_48_5: OK")
