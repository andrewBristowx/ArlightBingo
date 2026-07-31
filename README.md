# ArlightBingo 1.38.0 — Overworld Playable Progression

Primera fase de la campaña jugable del Overworld sobre las plantillas actuales. La ciudad inicial
se puebla con habitantes y guardias, el cartógrafo entrega una brújula de misión y los tres
distritos se liberan en orden usando spawners de entidades de ArlightBosses. Cada distrito abre
un cofre personal de ArlightBosses con un objeto de progresión protegido por PDC; la ciudadela y
el Guardián de la Superficie permanecen bloqueados hasta recuperar los tres objetos.

No borra las plantillas ni repite Chunky. Consulta `CAMBIOS_1.38.0.txt` y `PROBAR_1.38.0.txt`.

---

# ArlightBingo 1.37.1 — Somita Scene Effect Control

Añade control de partículas personales, apariciones suaves, efectos de señalización,
celebraciones cute/elegant y comandos de prueba por variante. La configuración de Somita
queda separada en `config-sections/somita.yml`. No modifica el clonador de mundos ni las
estructuras existentes.

Consulta `CAMBIOS_1.37.1.txt` y `PROBAR_1.37.1.txt`.

---

# ArlightBingo 1.36.4 — Arclight DIM-1/DIM1 Runtime Layout Fix

Corrige el error confirmado en consola: Arclight cargaba el Nether de la arena
desde `<arena>/DIM-1`, pero la instantánea se había escrito en la raíz
`<arena>/`. Ahora el clonador conserva `level.dat` en la raíz y coloca los
datos críticos del Nether en `DIM-1` y los del End en `DIM1`, exactamente donde
`WorldCreator` los carga.

Consulta `CAMBIOS_1.36.4.txt` y `PROBAR_1.36.4.txt`.

---

# ArlightBingo 1.35.3 — Three-Dimension Clone Integrity Fix

## Parche 1.35.3

Corrige los slots del pool que podían considerarse listos comprobando solamente el Overworld.
Ahora Overworld, Nether y End deben conservar su carpeta, marcador de origen, entorno, `level.dat`,
archivos de región y marcador funcional de plantilla antes de cargar la arena. Si una dimensión falta,
el slot se invalida y se copian nuevamente las tres plantillas sin ejecutar Chunky.
También recupera los nombres del pool que una versión anterior haya creado como mundos vanilla sin marcador.

También bloquea portales con destino ausente o inseguro para impedir que Bukkit/Arclight cree o use un
mundo vanilla de respaldo y envíe jugadores al vacío.


## Parche 1.35.2

- Corrige el inicio duplicado de mundos con ArlightCore 1.20.0.
- Espera a Multiverse-Inventories antes de campaña, inventarios y teletransporte.
- Usa `legos` como respawn seguro cuando el mundo guardado ya no existe.
- Reutiliza los mundos preparados; no repite Chunky ni cambia semillas.
- Conserva la cerradura persistente 1.35.1 y el overhaul de los tres mundos 1.35.0.


## Parche 1.35.1

Corrige la desaparición de la Cerradura Ígnea del Nether cuando el constructor
diferido volvía a limpiar su chunk después de haber ejecutado la reparación.
La cerradura se coloca ahora al terminar ese chunk y vuelve a verificarse antes
de iniciar la campaña.


Esta revisión conserva las semillas y las plantillas existentes, pero aplica un
pase profesional de diseño sobre las tres dimensiones:

- Overworld: pueblo inicial más completo, edificios funcionales y cimientos
  integrados al terreno para eliminar plataformas flotantes.
- Nether: habitaciones temáticas con decoración, enemigos, cofres y tesoros.
- End: barrios e islas con identidad, rutas secundarias y recompensas de exploración.

Actualización segura, una dimensión a la vez:

```text
/bingo template overworld resume
/bingo template nether resume
/bingo template end resume
/bingo template all audit
```

No ejecuta Chunky, no borra mundos y no cambia las semillas. Consulta
`CAMBIOS_1.35.1.txt` y `PROBAR_1.35.1.txt`.

---

# ArlightBingo 1.34.0 — Template Resume & Structure Hardening

Esta versión continúa sobre 1.33.0 y conserva el podio universal. Añade
comandos de auditoría/reanudación para las tres plantillas, permite continuar
el Overworld sin repetir Chunky ni borrar el mundo, protege y densifica la
zona custom del Nether y añade cimentaciones adaptativas al End.

Comandos principales:

```text
/bingo template all audit
/bingo template overworld resume
/bingo template nether resume
/bingo template end resume
```

Consulta `CAMBIOS_1.34.0.txt`, `PROBAR_1.34.0.txt` y
`VALIDACION_1.34.0.txt`.

---

# ArlightBingo 1.33.0 — Arclight Template Marker Fix

Esta versión corrige la detección de plantillas completas cuando Arclight
expone una carpeta real distinta de `world-container/nombre`. Conserva las
plantillas ya terminadas de 1.32.0/1.32.1 y el flujo anti-watchdog.

Después de instalarla, revisa:

```text
/bingo template all status
/bingo world prepare
```

No regeneres una dimensión que ya muestre `COMPLETE`.

Consulta `CAMBIOS_1.32.2.txt` y `PROBAR_1.32.2.txt`.

---

# ArlightBingo 1.32.1 — Watchdog-Safe Template Pipeline

Esta versión corrige el watchdog detectado al pasar del Overworld al Nether.
Chunky trabaja por tandas, espera la descarga estable de cada lote y el
constructor conserva temporalmente los tickets de chunks recién modificados.

Si el Nether de 1.32.0 quedó incompleto, instala esta versión y regenera sólo
esa plantilla. Las plantillas completas de Overworld y End siguen siendo
compatibles.

```text
/bingo template nether reset
/bingo template nether generate force
```

Consulta `CAMBIOS_1.32.1.txt` y `PROBAR_1.32.1.txt`.

---

# ArlightBingo 1.28.1 - SAFE-CHUNK-PREP

Parche de seguridad para Arclight: evita cargar o generar miles de chunks de forma síncrona al comenzar una partida. Requiere regenerar el slot del pool; las arenas marcadas por 1.28.0 no se reutilizan.

# ArlightBingo 1.20.0

Plugin de minijuego Bingo para servidores hibridos (Bukkit/Paper + Forge/NeoForge, tipo Arclight), Minecraft 1.21.1.

## Como compilar

Necesitas Java 21 y Maven instalados.

**Importante**: este proyecto tiene una dependencia (opcional, en `pom.xml`) a `ArlightCore`
(el nucleo compartido de minijuegos). Si tenes el proyecto `ArlightCore` aparte, compilalo e
instalalo PRIMERO con `mvn install` ahi, o si no queres usar esa integracion, borra el bloque
`<dependency>` de ArlightCore en `pom.xml` y la clase
`src/main/java/com/arlight/bingo/integration/CoreIntegration.java`.

```
cd BingoPlugin
mvn clean package
```

El jar final queda en `target/ArlightBingo-1.35.3.jar`. Ese es el archivo que copias a la carpeta `/plugins` del servidor.

Esta versión integra los iconos del resource pack ArlightChat 1.4.0 en el
scoreboard, cartón, bossbar, mensajes y títulos del minijuego.

La versión 1.0.20 fuerza blanco únicamente mientras dibuja cada icono, evitando que el color
del bossbar, del título o del lore altere los colores originales de su textura.




## Dragón Corrupto de Amatista — 1.19.0

El encuentro del End crea un EnderDragon vanilla etiquetado para que ArlightBosses 1.11.0 lo transforme en el boss de 500 HP y tres fases. Este diseño mantiene la compatibilidad con Arclight/Bukkit y permite que el plugin siga detectando su muerte y abriendo el gateway de recompensa. Somita continúa excluida.

## Cambios principales de 1.18.0

- Reemplaza la torre simple del Nether por una ciudadela militar de aproximadamente 89 x 101 bloques.
- Añade plataforma segura, puente, puerta fortificada, barracas, herrería, bóveda de oro, corrales de hoglins, torres de blazes, galerías de lava y fortaleza central vertical.
- Integra los cinco grupos nuevos del Nether de ArlightBosses 1.9.0 mediante spawners reales: piglin dorado, vanguardia Wither, strider fundido, jinete hoglin y espectro blaze.
- Reemplaza la torre del End por una isla flotante grande con cuatro islas secundarias, puentes, jardines de chorus, patios de cristales, biblioteca en ruinas, torres de amatista y castillo vertical.
- Integra los siete grupos nuevos del End mediante spawners reales: Enderman del Vacío, centinela, shulker, ojo, phantom, endermite corrompido y fragmento guardián.
- Los spawners de apoyo de cada jefe permanecen apagados hasta que un jugador entra en la arena.
- Nether y End incluyen rescate contra caídas: checkpoints en la ciudadela del Nether y retorno seguro desde el vacío en el End.
- Las escaleras de Nether y End tienen dos bloques de ancho, huecos despejados, soporte y descansos.
- Los cofres de las rutas secundarias y cámaras finales conservan Lootr y botín adaptativo.
- La entidad Somita y `dragon_guardian` no se usan; `dragon_guardian` también queda excluido de los objetivos automáticos. El encuentro inicial del End continúa con el dragón controlado por Bingo y el jefe final es `void_guardian`.
- Requiere ArlightBosses 1.9.0 o superior para los IDs configurados por defecto.

## Cambios principales de 1.17.0

- El spawn del mundo Bingo queda dentro de la plaza sur de la estructura.
- Se elimina la plataforma flotante independiente de coordenadas 0,100,0.
- El castillo ahora forma parte de una ciudad infestada de aproximadamente 125 x 133 bloques.
- La ciudad incluye murallas, cuatro torres, mercado, viviendas, herrería, almacén, enfermería, capilla, cementerio, barracas, puestos de guardia, ruinas y alcantarillas.
- Antes de construir, el plugin carga los chunks, elimina árboles/colinas/agua de la huella y nivela el terreno para que la estructura tenga prioridad.
- Se corrigieron huecos de escalera, techos bajos y cofres Lootr colocados sobre los recorridos.
- Los nuevos barrios usan spawners reales de ArlightBosses y cofres personales Lootr.

## Cambios principales de 1.16.0

- Castillo del Overworld reconstruido por completo con muralla, patio, habitaciones temáticas, torres, sótano, jefe y tesorería.
- Escaleras de dos bloques de ancho con orientación, soportes y descansos corregidos.
- Los esbirros ahora salen de spawners vanilla reales configurados con los IDs de ArlightBosses; ya no se usa una tarea periódica de `/summon`.
- Requiere ArlightBosses 1.8.2 o superior para todos los mobs configurados por defecto.

## Flujo de una partida

1. Los jugadores se unen con `/bingo join` o con el cartel `[Bingo]` (ver mas abajo). Mientras se
   espera, el scoreboard muestra `Jugadores: X/10`.
2. En cuanto hay `min-players-to-start` (2 por defecto) o mas jugadores anotados, arranca solo una
   cuenta regresiva de `auto-start-countdown-seconds` (50s por defecto), visible en el scoreboard y
   anunciada en el chat. Si baja gente y quedan menos del minimo, la cuenta se cancela.
3. Al llegar esa cuenta a 0 (o si un admin fuerza `/bingo start` antes), el pool copia las plantillas
   completas de Overworld, Nether y End a una arena desechable y teletransporta al pueblo inicial.
4. RECIEN AHI, ya parados en la arena, arranca la cuenta final "5, 4, 3, 2, 1... COMIENZA!" y se
   reparten los cartones -- empieza la partida de verdad.
5. Durante la partida, el scoreboard muestra dos lineas por jugador/equipo: sus puntos, y el
   ultimo objetivo que completo (se actualiza en el mismo lugar, no se acumula una lista larga).
   Si hay `time-limit-minutes` configurado, tambien aparece una boss bar arriba con el tiempo restante.
6. Cuando alguien gana (o se acaba el tiempo en modo por puntos y alguien sumo puntos), se festeja:
   titulo en pantalla "¡GANASTE EL BINGO!" + fuegos artificiales para el ganador, y un titulo
   distinto para el resto avisando quien gano. Recien despues de `celebration-seconds` (8s por
   defecto) se devuelve a todos al lobby.

## Comandos

- `/bingo start` (admin) - fuerza el inicio ya mismo (salta la cuenta de 50s, hace la cuenta final 5-4-3-2-1)
- `/bingo stop` (admin) - detiene la partida y devuelve jugadores al lobby (si aplica)
- `/bingo reload` (admin) - recarga config.yml
- `/bingo trigger <jugador> <idObjetivo>` (admin) - completa manualmente un objetivo CUSTOM_TRIGGER
- `/bingo world lobby <mundo>` (admin) - setea el mundo lobby
- `/bingo world game <mundo>` (admin) - setea el (unico) mundo de partida
- `/bingo world info` (admin) - muestra los mundos configurados
- `/bingo world resetarena [mundo]` (admin) - confirma que el plugin tome control de un mundo
  YA EXISTENTE en el disco (que no creo el mismo) y lo regenera ahora mismo. Necesario una vez
  si apuntaste `game-world` a un mundo que ya tenias, para que el plugin lo pueda regenerar despues.
- `/bingo vanillaonly <true|false>` (admin) - activa/desactiva incluir contenido de mods en la generacion automatica
- `/bingo blacklist item [namespace:id]` (admin) - banea un item de la generacion automatica (si no das el id, usa el item en tu mano)
- `/bingo blacklist mod [modid]` (admin) - banea un mod entero (si no das el modid, usa el mod del item en tu mano)
- `/bingo blacklist removeitem <namespace:id>` / `removemod <modid>` / `list` (admin)
- `/bingo join [equipo]` - se une a la partida (el nombre de equipo solo aplica si `team-mode: true`)
- `/bingo leave` - abandona la partida
- `/bingo card` - abre una GUI (inventario) con tu carton: verde = completado, item real = pendiente
- `/bingo carditem` - te da (o te vuelve a dar si lo perdiste) el item fisico "Carton de Bingo"
- `/bingo lobby` - te teletransporta al mundo lobby
- `/bingo arena` - te teletransporta al mundo de la partida en curso (si estas anotado)

## Item fisico del carton

Al unirte con `/bingo join` (o con el cartel) recibis automaticamente un item "Carton de Bingo"
(un mapa). Click derecho con el en la mano abre la misma GUI que `/bingo card`. Si lo pierdes o
lo tiras, `/bingo carditem` te da uno nuevo.

Ademas, una vez que tenes un carton asignado (arranco la partida), el mapa en si mismo muestra
una grilla de colores con tu progreso (verde = casilla completada, gris = pendiente) y se
actualiza solo mientras lo sostenes -- una vista rapida sin tener que abrir la GUI. No llega a
mostrar el icono real de cada item dentro del mapa (Minecraft no da una forma simple de dibujar
iconos de items ahi sin recursos graficos externos), pero da un pantallazo del progreso general.

## Deteccion de objetivos: como funciona por dentro

- **Romper/colocar bloques, matar entidades, logros**: se detectan al instante con eventos de Bukkit.
- **Conseguir items / craftear items**: en vez de depender de eventos (recoger del suelo, craftear en
  una mesa vanilla -- poco confiables, no cubren hornos, cofres, trueques, ni sistemas de
  auto-recogida de mods), el plugin escanea el inventario completo de cada jugador **cada segundo**
  mientras la partida esta corriendo, y usa el maximo historico visto de cada item objetivo. Asi,
  aunque despues gastes o craftees el item en otra cosa, ya cuenta como conseguido.

## Objetivos generados automaticamente

Por defecto (`goals.auto-generate: true`) el plugin genera solo un pool de objetivos aleatorios
(items para conseguir, bloques para romper, mobs para matar) a partir de los registros del propio
juego, sin que tengas que escribir nada a mano. Se descartan bloques/items no obtenibles en survival
normal: `bedrock`, `barrier`, `command_block`, `structure_block`, `jigsaw`, `spawner`, etc.

**Por defecto SOLO se usa contenido vanilla** (`goals.vanilla-only: true`) -- nada de mods. Esto
evita objetivos raros/imposibles de items de mods sin tener que andar armando una blacklist. Para
incluir tambien contenido de mods: `/bingo vanillaonly false` (o editar `goals.vanilla-only` en el
config). Si activas mods y aparece algo problematico, usa la blacklist (mas abajo) para excluirlo.

Esto es un filtro *best-effort* (no hay una lista 100% perfecta de "todo lo imposible de conseguir"),
asi que si ves algun objetivo raro colarse, puedes reportarlo o pasar a `auto-generate: false` y usar
la lista `goals.manual` (queda en el config.yml como referencia/ejemplo) para tener control total.

Tipos soportados: `ITEM_COLLECT`, `CRAFT_ITEM`, `BLOCK_BREAK`, `BLOCK_PLACE`, `ENTITY_KILL`,
`ADVANCEMENT`, `CUSTOM_TRIGGER` (este ultimo solo aplica en modo manual, para logica de mods que
no dispara eventos Bukkit; se completa con `/bingo trigger`).

Cada objetivo automatico pide **1 unidad** (conseguir/romper/matar 1), no cantidades variables.

### Blacklist de items y mods

Si algun item o mob generado no te gusta (muy raro, requiere mucho grindeo, rompe el balance, etc.),
podes banearlo sin tocar el config.yml a mano:

```
/bingo blacklist item          # banea el item que tenes en la mano
/bingo blacklist item minecraft:netherite_ingot   # o el id explicito
/bingo blacklist mod            # banea TODO el mod del item que tenes en la mano
/bingo blacklist mod modid_ejemplo
/bingo blacklist list           # ver que esta baneado
```

El cambio se guarda en `config.yml` (secciones `goals.blacklist-items` y `goals.blacklist-mods`)
y el pool de objetivos se regenera al instante, sin necesidad de `/bingo reload`.

## Inventario limpio en cada partida nueva

Al arrancar una partida (sea por auto-inicio o `/bingo start`), se limpia el inventario, armadura
y mano secundaria de todos los jugadores anotados antes de repartir cartones. Esto evita que items
de una partida anterior den progreso gratis en la nueva (el escaneo de inventario detectaria items
viejos como "ya conseguidos") y evita que el inventario se acumule partida tras partida. Se les
vuelve a dar el item del carton despues de limpiar.

## GUI en vivo

La GUI del carton (`/bingo card` o el item fisico) se actualiza sola mientras la tenes abierta:
no hace falta cerrarla y volver a abrirla para ver el progreso nuevo.

## Borde de mundo adaptativo (rendimiento + acceso a estructuras)

Cada vez que se prepara una arena, el plugin:

1. Busca (con la API de estructuras de Bukkit, sin necesidad de que nadie explore) donde cayo
   para esa seed en particular: el **stronghold** en el mundo principal, el **bastion/fortaleza**
   en el mundo Nether extra (si hay uno configurado), y la **ciudad del End** en el mundo End
   extra (si hay uno configurado).
2. Ajusta el `WorldBorder` de cada mundo para que esas estructuras queden DENTRO del area
   jugable (nunca las deja afuera, salvo que se pase del limite de seguridad `max-size`).
3. Pregenera (de forma asincrona, sin trabar el servidor) una zona bastante mas chica alrededor
   del spawn (`pregenerate-radius`) -- esto es lo que evita el lag de "generar terreno en vivo"
   mientras juegan, que es la causa mas comun de tirones durante una partida.

Importante: el TAMANO DEL BORDE y el TAMANO DE LO PREGENERADO son cosas distintas a proposito.
Un stronghold puede estar, por diseno del propio Minecraft, a varios miles de bloques del spawn
-- pregenerar TODO ese camino tardaria demasiado antes de cada partida. Por eso el borde se
agranda lo necesario para que sea *alcanzable*, pero solo se pregenera la zona chica cercana al
spawn; si un equipo camina hasta el stronghold lejano, esa zona puntual se genera normalmente en
el momento (un costo raro y puntual, no el lag constante que queriamos evitar).

Se configura en la seccion `world-border` de `config.yml` (ver los comentarios ahi para cada
opcion: `min-size`, `padding`, `max-size`, `search-radius`, `pregenerate-radius`, y los
`locate-*` para activar/desactivar la busqueda de cada tipo de estructura).

**Nota de compatibilidad**: esto usa `World#locateNearestStructure` y el registro de
estructuras de Bukkit (`org.bukkit.generator.structure`), que es API "clasica" de Bukkit (no
una extension exclusiva de Paper), asi que deberia funcionar en Arclight -- pero como no lo pude
probar en tu servidor real, si algo falla avisame el error de consola y lo ajustamos. Tambien
puede haber una pausa breve (hasta un par de segundos) durante "Preparando la arena..." mientras
se buscan las estructuras -- es una operacion sincrona por diseno de Minecraft, pero solo pasa
una vez por partida, en un momento donde nadie esta jugando activamente todavia.

## Mundo de arena (nativo, sin plugins externos)

Ya NO depende de Multiverse-Core ni de ningun otro plugin: el mundo de arena se crea y se
regenera (se borra del disco y se vuelve a crear con una seed aleatoria) con la propia API
de Bukkit. Si el mundo no existe todavia, el plugin lo crea solo la primera vez.

Pasos para configurarlo (por defecto ya viene activado):

1. En `config.yml`, seccion `arena-world`: `enabled: true` (default), `lobby-world` (mundo donde
   esperan los jugadores -- por defecto `"world"`, el mundo principal, que ya existe de entrada) y
   `game-world` (el mundo de la partida -- por defecto `"bingo_arena"`, se crea solo).
   Tambien podes usar `/bingo world lobby <mundo>` / `/bingo world game <mundo>` en vez de editar
   el archivo a mano.
2. Cada vez que arranca una partida, el plugin borra y vuelve a crear ese mundo (seed nueva) y
   teletransporta a todos los jugadores anotados. Al terminar la partida, todos vuelven al `lobby-world`.
3. Cada vez que se regenera, se construye una pequena plataforma seria y segura (una "mini isla",
   9x9, en coordenadas fijas) y se fija ahi el punto de spawn del mundo -- para que nadie aparezca
   nunca enterrado ni en un lugar peligroso al entrar a la arena.

**Proteccion anti-borrado**: si apuntas `game-world` a un mundo que YA EXISTIA en el disco de antes
(por ejemplo un mapa a medida que tenias para otra cosa) y el plugin nunca lo creo, el plugin **no lo
borra automaticamente** -- lo carga tal cual y avisa en la consola. Si de verdad queres que el
plugin tome control de ese mundo y lo regenere en cada partida, confirmalo una vez con
`/bingo world resetarena`.

**Importante**: si cambias `lobby-world` a un mundo que no existe, el teletransporte al lobby
fallara silenciosamente (revisa la consola). Asegurate de que ese mundo si exista.

### Cartel para unirse/entrar

Cualquier jugador con permiso `arlightbingo.admin` puede crear un cartel con la primera
linea `[Bingo]`. Al hacer click derecho sobre el:
- Si la partida esta esperando jugadores o en cuenta regresiva: el jugador se une (como `/bingo join`)
  y es teletransportado al lobby.
- Si la partida ya esta corriendo y el jugador esta anotado: lo teletransporta directo a la arena.

## Persistencia

El estado de la partida (equipos, cartones, progreso, mundo de arena) se guarda automaticamente en
`plugins/ArlightBingo/gamedata.yml` despues de cada cambio importante y tambien al apagar el plugin.
Al arrancar de nuevo, si habia una partida en curso, se restaura tal cual estaba (incluyendo el
tiempo restante si hay limite configurado). Nota: la cuenta regresiva de sala de espera y la cuenta
final NO se restauran tras un reinicio (solo aplica a partidas ya en estado RUNNING).

## Modo de victoria

- **POINTS** (por defecto): 1 punto por casilla completada. Se sigue jugando hasta que se acaba
  el `time-limit-minutes` configurado, y ahi gana quien tenga mas puntos (empate si hay igualdad).
  Completar el carton entero sigue siendo una victoria instantanea (bonus). **Importante**: este
  modo necesita `time-limit-minutes` mayor a 0 para poder terminar solo (si lo dejas en 0, la
  partida nunca termina sola salvo que alguien complete el carton entero).
- **LINE**: gana el primero en completar una fila/columna/diagonal.
- **FULL_CARD** / **BLACKOUT**: gana el primero en completar el carton entero.

## Configuracion

Todo se ajusta en `config.yml`: tamano del carton, modo equipos on/off, condicion de victoria,
umbral de jugadores y duracion de las cuentas regresivas, generacion de objetivos, y el
manejo del mundo de arena.

## Ideas para seguir extendiendo

- Integracion con PlaceholderAPI para reusar estos datos en otros scoreboards/menus.
- Barra de progreso (boss bar) ademas del scoreboard durante la cuenta regresiva.
- Objetivos de "visitar bioma/estructura" usando PlayerMoveEvent con throttling.

## Autocompletado de comandos

`/bingo <tab>` sugiere todos los subcomandos, y varios de ellos autocompletan tambien sus
argumentos (nombres de jugadores conectados, nombres de mundos cargados, ids de objetivos del
carton de un jugador para `/bingo trigger`, items/mods ya baneados para los `removeitem`/`removemod`
de la blacklist, etc).

## Sala de espera: items de informacion y salir

Al unirte (por `/bingo join` o por el cartel) tu inventario se limpia por completo y recibis dos
items temporales mientras esperas que arranque la partida:
- **¿De que va el Bingo?** (libro): explica el objetivo, el modo (individual/equipos), como se
  gana y la duracion, tomando los valores reales de tu `config.yml`.
- **Salir de la sala** (tinte rojo): te saca de la partida en espera y te devuelve al mundo
  principal del servidor con el inventario limpio.

Estos dos items se reemplazan por el item real del carton (ver mas abajo) recien cuando arranca
la partida de verdad.

## Objetivos en espanol

Los objetivos generados automaticamente (items, bloques, mobs) se muestran en espanol, usando un
diccionario interno con las traducciones de lo mas comun del juego vanilla. Si algun nombre no
esta en el diccionario, se muestra en ingles como respaldo (nunca se rompe, solo se ve en ingles
ese caso puntual) -- se puede ampliar editando `SpanishNames.java` y recompilando.

Tambien se saco a los huevos de generar criaturas (`_SPAWN_EGG`) del pool automatico, ya que no
tiene sentido pedir "consigue el huevo de X" como objetivo de supervivencia.

## Nether/End propios de la arena (mundos vinculados)

Si armaste un Nether y/o un End propios para la arena del bingo (por ejemplo con
Multiverse-NetherPortals, conectando `bingo_arena` <-> `bingo_arena_nether` / `bingo_arena_the_end`),
agregalos a `arena-world.extra-worlds` en el config (o con `/bingo world addextra <mundo>`) para
que el plugin los regenere junto con el mundo principal en cada partida:

```
/bingo world addextra bingo_arena_nether
/bingo world addextra bingo_arena_the_end
```

Como esos mundos probablemente ya existian en el disco (los creaste vos a mano antes de instalar
el plugin), aplica la misma proteccion anti-borrado que el mundo principal: la primera vez hay que
confirmar que el plugin puede tomar control de cada uno:

```
/bingo world resetarena bingo_arena_nether
/bingo world resetarena bingo_arena_the_end
```

Despues de esa confirmacion, se regeneran solos en cada partida igual que la arena principal.

## Muerte, respawn y estado al entrar a la arena

- Si un jugador muere estando en la arena (o en uno de sus mundos extra), respawnea en el spawn
  del `lobby-world` en vez del mundo por defecto del servidor.
- Cada vez que un jugador entra a la arena (o a uno de sus mundos extra) -- ya sea al arrancar la
  partida, por respawn, o usando `/bingo arena` -- se lo pone en modo supervivencia, se le quitan
  todos los efectos de pocion activos, y se le resetea la vida (maxima y actual) a 20 corazones
  llenos y el hambre al maximo, para que todos arranquen en igualdad de condiciones.

## Integracion con ArlightCore

Si tenes instalado el plugin `ArlightCore` (el nucleo compartido de minijuegos), el Bingo se
registra solo en su item selector de minijuegos, y da la XP configurada ahi (+5 por defecto) a
quien gane. Esto es 100% opcional: si ArlightCore no esta instalado, el Bingo funciona
exactamente igual sin esto.

## Desempate PvP

Con la condicion `POINTS`, si dos o mas jugadores/equipos terminan con la misma puntuacion,
se crea automaticamente una plataforma temporal sobre la arena. Los finalistas reciben el
mismo equipo, esperan una cuenta regresiva y pelean hasta que quede uno con vida. Sus opciones
estan en la seccion `tiebreak` de `config.yml`.

Al finalizar cualquier partida todos los participantes salen por completo de la sala de Bingo,
vuelven al lobby y no se inicia una nueva cola automaticamente.

## Versión 1.10.0 — Fortalezas de progresión

Las tres mazmorras se construyen ahora como castillos de varios pisos. Los niveles de
combate usan spawners visuales que invocan esbirros de ArlightBosses; el penúltimo nivel
es una sala amplia de jefe y el superior es una sala segura con cuatro cofres Lootr y el
portal. Bingo configura vida, daño, rango, alcance, partículas, fases y BossBar de cada
jefe según el archivo `config.yml` y la cantidad de jugadores.


## Versión 1.11.0 — Cola privada, chat aislado y cartón cliente

La preparación y los mensajes operativos se envían únicamente a los participantes. El chat de la sesión queda separado del exterior. ArlightChatClient 2.5.0 muestra el cartón lateral y una vista completa con objetos reales y nombres traducidos por el cliente.


## Integración Multiverse en 1.12.1

Cuando `world-pool.inventories.auto-configure-groups` está en `true`, Bingo pide a
ArlightCore 1.15.0 que:

1. registre en Multiverse-Core los tres mundos recién creados;
2. añada Overworld, Nether y End al grupo `bingo` de Multiverse-Inventories;
3. cree enlaces bidireccionales de Nether y End con Multiverse-NetherPortals.

Al entrar a la arena, Bingo deja que Multiverse-Inventories cambie el perfil y después limpia
el inventario del grupo de minijuego. Al salir, el cambio de mundo restaura el perfil del grupo
Survival. Bingo mantiene una copia local solamente como respaldo ante fallos o desconexiones.


## Versión 1.24.0 — Reconstrucción de campaña

La campaña usa progresión obligatoria entre ciudad musgosa, ciudad fortaleza del Nether,
ciudadela del End e isla independiente del Dragón Corrupto. Los spawners son rompibles y
permiten conquistar distritos, mientras que cofres, llaves, cerradura, altar, portales y
accesos de jefe tienen protección selectiva. El End elimina la isla central vanilla dentro
del radio configurado y construye una isla orgánica completa. Consulta `PROBAR_1.24.0.txt`
antes de instalarla en el servidor público.
