# Demo · Idempotencia y control de concurrencia

TP de Investigación de Desarrollo de Software (UTN FRRe 2026), **Grupo 2**:

- **Tema 9:** idempotencia y deduplicación
- **Tema 10:** condiciones de carrera y control de concurrencia

Una sola operación, `POST /reservas`, demuestra los dos temas. Los cinco casos de la presentación se corren con un script, y cada resultado se verifica con una consulta directa a PostgreSQL.

La aplicación de estos conceptos en el sistema real está en el TPI, módulo M3: [`FRRe-DS/2026-02-TPI`](https://github.com/FRRe-DS/2026-02-TPI), clase `RegisterDriverLicenseService` (bloqueo pesimista del conductor, tabla `idempotency_records` y ETag/If-Match).

**Stack:** Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Docker Compose · header `Idempotency-Key` (draft IETF `draft-ietf-httpapi-idempotency-key-header`).

---

## Cómo correrla

**Requisitos:** Docker Desktop (o Docker Engine con Compose) y una terminal con `bash` y `curl`: Linux, macOS, WSL o Git Bash en Windows. No hace falta tener Java ni Maven instalados, porque se compila dentro de Docker.

```bash
# 1. Levantar PostgreSQL y la API (la primera vez tarda unos minutos en compilar)
docker compose up --build -d

# 2a. Panel visual: abrir http://localhost:8080 en el navegador
# 2b. O correr los 5 casos por consola
./demo.sh

# 3. (Opcional) Ver en vivo qué hace cada petición
docker compose logs -f app

# 4. Apagar todo y borrar la base
docker compose down -v
```

- **API:** `http://localhost:8080`
- **PostgreSQL:** `localhost:5433`, con usuario, contraseña y base `demo`. Usa el puerto 5433 para no chocar con el PostgreSQL del TPI.
- **Reinicio:** `demo.sh` vacía las tablas al empezar, así que se puede correr todas las veces que haga falta.

Si `./demo.sh` da *permission denied*, ejecutá `chmod +x demo.sh` o corré `bash demo.sh`.

### Salida esperada

```
━━ Caso 1 · Reintento secuencial: 10 peticiones idénticas, misma clave ━━
  #1  → 201
  #2  → 201 (replay)
  ...
  ✔ reservas creadas para el conductor 1: 1
  ✔ body del reintento #10 frente al #1: idéntico

━━ Caso 2 · Reintento paralelo: 10 peticiones idénticas al mismo tiempo ━━
  #1  → 201 (replay)
  #5  → 201            ← la única que creó la reserva (el número cambia en cada corrida)
  ...
  ✔ respuestas 201 que crearon algo: 1
  ✔ reservas creadas para el conductor 2: 1

━━ Caso 3 · Reuso de la clave con un body distinto ━━
  Original  conductorId=3 → 201
  Reuso     conductorId=6 → 422  "Idempotency-Key is already used"

━━ Caso 4 · Versión ingenua: 2 clientes reservan el mismo conductor a la vez ━━
  Cliente 101 → 201
  Cliente 102 → 201
  ✔ reservas del conductor 4 (bug de doble asignación reproducido): 2

━━ Caso 5 · Versión corregida: la misma carrera con FOR UPDATE + UNIQUE ━━
  Cliente 101 → 409  "Conductor ocupado"
  Cliente 102 → 201
  ✔ respuestas: 201 409
  ✔ reservas creadas para el conductor 5: 1

━━ Resumen ━━
  9 verificaciones OK, 0 fallas
```

---

## Panel visual

Con la demo levantada, abrí **http://localhost:8080**. Es la forma recomendada de mostrarla en una exposición:

- **Una pestaña por caso.** Cada una explica el escenario, qué se espera y cómo lo resuelve el código. Tiene un botón **Ejecutar**.
- **Peticiones.** Una línea de tiempo con una barra por petición: cuándo salió, cuánto tardó en el servidor y qué respondió (creó, replay, 409, 422). Así se ve, por ejemplo, que en el caso 2 las peticiones esperaron a la primera.
- **Detalle.** Al tocar una petición se ve exactamente qué se mandó (headers y body) y qué respondió la API, incluido el problem+json.
- **Base de datos en vivo.** Las tablas `reservas`, `reservas_ingenuas` e `idempotency_records`. La doble asignación del caso 4 aparece resaltada.
- **Verificación.** Al terminar cada caso, consulta la base y marca OK, FALLA o BUG.
- **Petición manual.** Sirve para responder preguntas en el coloquio: armar una petición a mano, repetirla, cambiarle el body o quitarle la clave.

Las peticiones las hace el navegador, contra la misma API que usa `demo.sh`. El navegador abre como máximo 6 conexiones a la vez, así que en el caso 2 las peticiones 7 a 10 esperan un momento en su cola; el panel lo muestra como un tramo punteado. `demo.sh` manda las 10 juntas.

El panel no depende de internet: es un único archivo, `src/main/resources/static/index.html`. Usa dos endpoints de apoyo que no son parte de la API de reservas: `GET /demo/estado` (el contenido de las tablas) y `POST /demo/reset` (vaciarlas). Están en `panel/PanelController.java`.

---

## Los cinco casos

| Caso | Endpoint | Qué se hace | Resultado esperado |
|---|---|---|---|
| 1 | `POST /reservas` | 10 peticiones idénticas, una tras otra, misma clave | 1 reserva; los reintentos devuelven el mismo `201` con `Idempotent-Replayed: true` |
| 2 | `POST /reservas` | 10 peticiones idénticas **en paralelo**, misma clave | 1 reserva; las otras 9 esperan el COMMIT y hacen replay |
| 3 | `POST /reservas` | Misma clave, body distinto | `422 Unprocessable Content`, sin reserva nueva |
| 4 | `POST /ingenuo/reservas` | 2 clientes, mismo conductor y horario, en paralelo | **Bug:** 2 reservas (doble asignación) |
| 5 | `POST /reservas` | Lo mismo que el caso 4, en la versión corregida | 1 `201` + 1 `409 Conflict`, 1 reserva |

---

## Probarla a mano

```bash
# Crear una reserva
curl -i -X POST http://localhost:8080/reservas \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: "8e03978e-40d5-43e8-bc93-6894a57f9324"' \
  -d '{"clienteId": 42, "conductorId": 7, "fechaHora": "2026-11-12T18:00:00"}'

# Repetir exactamente lo mismo → mismo 201, con Idempotent-Replayed: true
# Cambiar conductorId con la misma clave → 422
# Sin el header Idempotency-Key → 400

# Ver la tabla de claves
docker compose exec db psql -U demo -d demo \
  -c "SELECT clave, estado, status_http, creado_en FROM idempotency_records;"
```

### Respuestas posibles de `POST /reservas`

| Status | Cuándo |
|---|---|
| `201 Created` | Reserva creada. `Idempotent-Replayed: false` |
| `201 Created` (replay) | Misma clave y mismo body que una petición ya completada. Devuelve el body original. `Idempotent-Replayed: true` |
| `400 Bad Request` | Falta `Idempotency-Key`, la clave tiene un formato inválido o el body está incompleto |
| `404 Not Found` | El conductor no existe |
| `409 Conflict` | El conductor ya tiene una reserva en ese horario, o la petición original con la misma clave sigue en curso y se superó `lock_timeout` |
| `422 Unprocessable Content` | La clave ya se usó con un body distinto |

Los errores se devuelven como `application/problem+json` (RFC 9457).

---

## Cómo está hecho

Toda la lógica de la versión corregida ocurre en **una sola transacción**, en `ReservaService`:

```sql
BEGIN;
  SET LOCAL lock_timeout = 5000;

  -- Tema 9: registrar la clave. Si otra transacción ya la insertó y no confirmó,
  -- esta sentencia ESPERA en el índice UNIQUE hasta su COMMIT.
  INSERT INTO idempotency_records (clave, hash, estado)
  VALUES (:clave, :sha256_del_body, 'EN_CURSO')
  ON CONFLICT (clave) DO NOTHING;
  -- 0 filas → leer el registro: otro hash → 422 · COMPLETADA → replay

  -- Tema 10: bloqueo pesimista del conductor
  SELECT id FROM conductores WHERE id = :conductor FOR UPDATE;

  -- check-then-act seguro: nadie más puede estar en este punto
  SELECT EXISTS (SELECT 1 FROM reservas WHERE conductor_id = :c AND fecha_hora = :f);  -- → 409
  INSERT INTO reservas (...) VALUES (...);  -- UNIQUE (conductor_id, fecha_hora) como red de seguridad

  -- Respuesta guardada para los replays, en el mismo COMMIT
  UPDATE idempotency_records SET estado = 'COMPLETADA', status_http = 201, respuesta = :json
  WHERE clave = :clave;
COMMIT;
```

Si algo falla, el ROLLBACK borra también la clave, y un reintento vuelve a procesar desde cero.

| Concepto (presentación) | Dónde está |
|---|---|
| Header `Idempotency-Key`, validación de formato | `idempotencia/ClaveIdempotencia.java` |
| Fingerprint: SHA-256 del body canónico | `idempotencia/Huella.java`, `ReservaRequest.canonico()` |
| Tabla de claves, `UNIQUE (clave)` | `db/migration/V1__esquema.sql`, `idempotencia/IdempotenciaRepository.java` |
| TTL de las claves (24 h) | `idempotencia/PurgaIdempotencia.java` |
| Bloqueo pesimista `SELECT … FOR UPDATE` | `reserva/ReservaService.java` |
| `UNIQUE (conductor_id, fecha_hora)` | `db/migration/V1__esquema.sql` |
| Versión ingenua (check-then-act) | `reserva/ReservaIngenuaService.java` |
| Respuestas `problem+json` (400 / 409 / 422) | `api/ProblemaHttp.java`, `api/ManejadorErrores.java` |
| Panel visual | `resources/static/index.html`, `panel/PanelController.java` |

### Política de idempotencia (lo que el draft pide publicar)

- **Clave:** obligatoria en `POST /reservas`. Debe tener entre 8 y 255 caracteres (letras, números, `- _ . :`); se recomienda un UUID v4. Se acepta con comillas (formato del draft) o sin ellas.
- **Unicidad:** la clave es única en todo el sistema. Reusarla con otro body devuelve `422`.
- **Expiración:** las claves se borran después de **24 horas**. Pasado ese tiempo, la misma clave se trata como nueva.
- **Qué se guarda:** solo las respuestas exitosas (`201`). Si la operación falla (409, 404), la clave no queda registrada y el cliente puede reintentar.

### Configuración

Se configura con variables de entorno en `compose.yaml`:

| Variable | Default | Qué controla |
|---|---|---|
| `VENTANA_CARRERA_MS` | `300` | Pausa entre el *check* y el *act* (ver más abajo) |
| `LOCK_TIMEOUT_MS` | `5000` | Espera máxima por un lock antes de responder 409 |
| `IDEMPOTENCIA_TTL_HORAS` | `24` | Tiempo de vida de las claves |

---

## Aclaraciones para la defensa

- **La ventana de carrera está agrandada a propósito.** En un sistema real, entre el *check* y el *act* pasan microsegundos, así que la doble asignación aparece de vez en cuando y no a demanda. La pausa de 300 ms hace que el caso 4 se reproduzca siempre. La versión corregida tiene **la misma pausa** y aun así no falla, porque la pausa ocurre con el lock tomado.
- **La versión ingenua también es `@Transactional`.** Eso muestra que una transacción en `READ COMMITTED` (el nivel por defecto de PostgreSQL) no alcanza para evitar el check-then-act.
- **Por qué la exclusión vive en PostgreSQL y no en un `synchronized`.** Con dos o más réplicas de la API, cada JVM tiene su propio monitor. El lock de la base es el único que todas comparten.
- **Simplificaciones respecto de un sistema productivo:** no hay autenticación, así que la clave no se combina con la identidad del cliente, como sugiere la sección 5 del draft. Tampoco se guardan las respuestas de error.

## Fuentes

- RFC 9110, *HTTP Semantics*, §9.2.2 (métodos idempotentes)
- IETF, *draft-ietf-httpapi-idempotency-key-header*
- Stripe API, *Idempotent requests*
- PostgreSQL 16, cap. 13.2 (aislamiento) y 13.3 (bloqueo explícito)
