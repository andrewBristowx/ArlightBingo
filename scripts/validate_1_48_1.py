#!/usr/bin/env python3
"""Deterministic source checks for the 1.48.1 route/audit recovery patch."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


terrain = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignTerrain148.java")
structures = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignStructures148.java")
builder = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignBuilder148.java")
audit = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignAudit148.java")
landscape = read("src/main/java/com/arlight/bingo/listeners/OverworldCampaignLandscape148.java")

require("remaining = steps - step" in terrain, "falta convergencia de altura al waypoint")
require("registerRoadCell" in terrain, "la red no registra todas sus celdas")
require("repairRoadCorridors" in audit, "falta el cierre final de corredores")
require(builder.index("decorando la isla reconstruida")
        < builder.index("restableciendo corredores auditados"),
        "los corredores deben restaurarse después de la decoración")
require("{-20,13}" in structures and "{-20,18}" not in structures,
        "el farol sigue bloqueando village-home-southwest")
require("site.z() - 33" in structures and "site.z() - 28" in structures,
        "village-hall todavía no tiene enlace público")
require('"residential-lodge", site.x(), y,' in structures,
        "residential-lodge continúa un bloque por encima de su calle")
require(structures.count("wallOpening(out") >= 4,
        "faltan accesos enmarcados para military-citadel y la ciudadela")
require("FAILED_MARKER" in landscape, "falta el estado terminal FAILED")
require("Files.deleteIfExists(folder.resolve(PROGRESS_MARKER))" in landscape,
        "un fallo todavía deja el marcador in-progress")
require("if (!completedThisRun) return" in landscape,
        "FAILED todavía permite un reintento automático")


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
                require(heights[-1] == end, "la ruta no alcanza el waypoint exacto")
                require(all(abs(a - b) <= 1 for a, b in zip(heights, heights[1:])),
                        "la ruta contiene un salto vertical mayor de un bloque")

print("validate_1_48_1: OK")
