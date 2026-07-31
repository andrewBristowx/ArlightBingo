# Integración de Lootr en la plantilla Overworld 1.29.0

## Método elegido

Los contenedores construidos por Bingo son cofres o barriles vanilla y reciben una `LootTable` real mediante la API de Bukkit/Paper. No se rellenan manualmente con objetos durante la generación.

Esto conserva el identificador de la tabla hasta la primera apertura y permite que Lootr trate el contenedor como botín generado. También deja un comportamiento vanilla funcional si Lootr no está disponible.

## Contenedores incluidos

- Casas y almacenes del pueblo.
- Minas custom.
- Torres y minicastillos.
- Asentamientos fortificados.
- Ciudadela y recompensas secundarias.
- Cofres de estructuras vanilla generadas por la semilla.

Las tablas usadas incluyen variantes de aldeas, minas abandonadas, puesto de saqueadores, mazmorra simple y fortaleza.

## Por qué no se usa `/lootr custom-map`

El mundo ya se construye con loot tables. La conversión masiva está pensada para mapas cuyos cofres fueron rellenados manualmente y no conservan una tabla. Además, recorrer y forzar la carga del mapa completo sería innecesario después de pregenerarlo con Chunky.

## Cofres animados de ArlightBosses

Un bloque custom del mod no puede integrarse de forma nativa solo desde el plugin Bukkit. La integración correcta necesita realizarse dentro de ArlightBosses:

1. Dependencia opcional de Lootr para NeoForge.
2. Identificador único para el tipo de contenedor.
3. Loot table asociada.
4. Clase de integración separada.
5. Llamada a LootrAPI al abrir el bloque cuando Lootr esté cargado.

Hasta implementar esa actualización mod-side, la plantilla utiliza cofres y barriles compatibles como fuente real del botín.

## Vaults

No se asume compatibilidad nativa de Lootr con el bloque Vault. Un Vault puede usarse como decoración o como elemento de progresión global, pero el botín personal se colocará en un cofre/barrel Lootr asociado o en el sistema personal de Bingo hasta confirmar soporte oficial.
