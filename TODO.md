# TODO: Arquitectura de primitivas (V1)

## Principios
- Event-driven: cada primitiva termina cuando llega el evento que confirma su efecto.
- Deadline: único uso de tiempo, solo para fallar; se resuelve antes si llega el evento. Autorizado solo para primitivas con condición esperada (ver `CLAUDE.md`).
- Fallar fuerte pero no letal: una falla no mata el proceso, pasa el bot a `error` con mensaje completo (esperado vs. encontrado).
- Cada primitiva se lanza sola desde la UI y dentro de un macro.
- Persistencia: las áreas se guardan en `AreaStore` al cambiar.

## Bot como máquina de estados
- Estados: `idle`, `doing <primitiva>`, `error <primitiva>: <mensaje>`, `running macro <nombre> (paso k de n)`.
- **Decisión:** en `error` el bot se queda esperando hasta recibir un nuevo estado (no reintenta ni se resetea solo).
- Procesos largos (horno, brewing) son un estado más: el bot queda quieto con la ventana abierta esperando el evento del slot.

## Primitivas
- [ ] **A. Movimiento**: `ir a área/posición`. Termina al llegar; sin camino → `error`.
- [ ] **B. `interact(target, botón, expect)`**
  - Target único y concreto (posición de bloque o entidad específica); sin selectores ni "más cercano".
  - Derecho = activar, izquierdo = romper/atacar.
  - Precondiciones: existe, al alcance, línea de vista.
  - `expect` obligatorio, lista cerrada: ventana abierta (tipo esperado) o bloque cambia a estado X; si no se cumple en el deadline → `error`.
  - **Decisión (UI):** el target (x, y, z) se elige clickeando el bloque, igual que con las áreas: se **resalta (highlight) el bloque al que apunta el cliente antes de clickear**, para saber qué se está seleccionando.
  - [ ] `romper bloque` y `poner bloque` como casos de `interact` con su `expect`.
- [ ] **C. Ventana abierta** (lista de slots + inventario del bot, queda como estado del bot)
  - [ ] `cerrar ventana` (explícita).
  - [ ] `mover(origen, destino, cantidad)`: origen/destino = slot concreto, "cualquier slot con item X" o "donde entre". Cantidad exacta en V1; si no se puede → `error` ("faltaron 13"). Confirma por actualización de slots del servidor.
  - [ ] `esperar slot(condición)`: con deadline; si falla → `error ... missing "X" expected on slot N`.
  - Mesa de crafteo, aldeano, horno, etc. = `interact` + `mover` + `esperar slot`; nada nuevo.

## Tabla de slots por tipo de ventana
- Solo para UI y validación (horno: 0 input, 1 combustible, 2 output; brewing, aldeano...). Las primitivas no dependen de ella (un NPC custom funciona sin soporte).
- Recetas desde minecraft-data por versión, usadas al armar macros.

## Macros y Jobs
- [ ] **Macros**: secuencias de primitivas con parámetros, repetición hasta condición, y qué hacer si un paso falla (= `error` en ese paso).
- [ ] **Jobs**: macro asignada a uno o más bots, con reparto de cantidades, reserva de contenedores, seguimiento de progreso y reasignación si un bot cae en `error` o se desconecta.
