# Reglas del repo

## Todo event driven
- Prohibido usar timeouts, timers, reintentos por tiempo (tryouts), polling, busy waits, autosaves periódicos o cualquier lógica basada en "esperar X ms / cada N ticks".
- Todo se dispara por eventos: el cambio que ocurre es el que provoca la acción.
- Si no queda otra que usar algo basado en tiempo, consultar antes. Nunca implementarlo sin preguntar.

## Fallar fuerte
- Esto es un entorno de dev, no prod: si algo falla, que rompa. Nada de fallar en silencio.
- Prohibido tragarse excepciones (`catch` vacío o que solo ignora), devolver `null`/valores por defecto para tapar errores, o saltear datos inválidos. Relanzar (p. ej. `UncheckedIOException`, `IllegalStateException`) con un mensaje que diga qué falló.

## Persistencia
- Las áreas son persistentes y se guardan en disco (`AreaStore`), en el momento en que cambian.
