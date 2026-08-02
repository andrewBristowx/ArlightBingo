# ArlightBingo 1.48.19 — finalización segura por zonas

## Base

Este candidato parte de ArlightBingo 1.48.18 y no modifica ni sustituye la rama estable.

## Causa raíz

La ruta `citadel-central-court` atravesaba el `citadel-great-hall`, borraba mobiliario y después quedaba bloqueada al restaurarse la envolvente del edificio. Al mismo tiempo, el gran salón, el cimiento de `boss-wall` y las rutas se reparaban en una sola transacción, por lo que un fallo posterior hacía desaparecer también las correcciones válidas.

## Cambios

- El corredor central rodea el gran salón por el este y queda fuera de las cuatro torres.
- Se elimina la calle recta antigua que invadía el salón.
- El mobiliario del gran salón se restaura al final y supera con margen el objetivo de auditoría.
- La cimentación de `boss-wall` se sella al final en los radios 48–51 y con profundidad superior a 12 bloques.
- La escalera monumental norte sella su núcleo de apoyo para no aceptar una capa superficial sobre una cueva.
- La autorreparación procesa una zona conocida por pasada: salón, muralla y rutas.
- Nether y End no se modifican.

## Validación

- Validador estático 1.48.19 en verde.
- ZIP fuente comprobado con `unzip -t` y SHA-256.
- La compilación de Java 21 se ejecuta en GitHub Actions.
- La generación completa en Arclight 1.21.1 sigue siendo obligatoria antes de fusionar.
