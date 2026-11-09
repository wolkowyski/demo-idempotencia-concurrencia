"""
DEMO SIMPLE EN PYTHON · TP de Investigación · Grupo 2 · Temas 9 y 10

Los mismos 5 casos que DemoSimple.java, con la misma estructura: Python + SQL, sin frameworks.
Cada "cliente" es un hilo; los que corren a la vez simulan pedidos simultáneos.

  reservar_ingenuo(...)    → caso 4: check-then-act sin protección (el bug)
  reservar_corregido(...)  → casos 1, 2, 3 y 5: idempotencia + bloqueo, en una transacción

Se corre con:  docker compose run --rm simple-py
"""
import hashlib
import os
import threading
import time
from datetime import datetime

import psycopg

URL = os.environ.get("DB_URL", "postgresql://demo:demo@localhost:5433/postgres")
FECHA = datetime(2026, 11, 12, 18, 0)

# ════════════════════════════════════════════════════════════════════════════════
#  LAS TABLAS
# ════════════════════════════════════════════════════════════════════════════════
ESQUEMA = """
    CREATE TABLE IF NOT EXISTS conductores (id INT PRIMARY KEY);

    -- Tema 10 · versión corregida: UNIQUE = red de seguridad contra la doble asignación
    CREATE TABLE IF NOT EXISTS reservas (
        id SERIAL PRIMARY KEY, cliente_id INT, conductor_id INT, fecha_hora TIMESTAMP,
        UNIQUE (conductor_id, fecha_hora));

    -- Tema 10 · versión ingenua: SIN UNIQUE
    CREATE TABLE IF NOT EXISTS reservas_ingenuas (
        id SERIAL PRIMARY KEY, cliente_id INT, conductor_id INT, fecha_hora TIMESTAMP);

    -- Tema 9 · claves de idempotencia: UNIQUE (clave) + hash del pedido + respuesta guardada
    CREATE TABLE IF NOT EXISTS idempotency_records (
        clave TEXT UNIQUE, hash TEXT, respuesta TEXT);

    INSERT INTO conductores VALUES (1), (2), (3), (4), (5), (6) ON CONFLICT DO NOTHING;
    TRUNCATE reservas, reservas_ingenuas, idempotency_records RESTART IDENTITY;
"""


# ════════════════════════════════════════════════════════════════════════════════
#  VERSIÓN INGENUA · CASO 4
# ════════════════════════════════════════════════════════════════════════════════
def reservar_ingenuo(cliente, conductor):
    with psycopg.connect(URL) as db:                            # BEGIN (psycopg abre la transacción)

        # CHECK: ¿está libre?
        ocupado = db.execute(
            "SELECT 1 FROM reservas_ingenuas WHERE conductor_id = %s AND fecha_hora = %s",
            (conductor, FECHA)).fetchone()
        if ocupado:
            db.rollback()
            return "409 conductor ocupado"

        time.sleep(0.3)   # EL BUG: mientras tanto, otro cliente hace el mismo CHECK y también ve "libre"

        # ACT: los dos insertan → doble asignación
        db.execute(
            "INSERT INTO reservas_ingenuas (cliente_id, conductor_id, fecha_hora) VALUES (%s, %s, %s)",
            (cliente, conductor, FECHA))
        db.commit()                                              # COMMIT
        return "201 reserva creada"


# ════════════════════════════════════════════════════════════════════════════════
#  VERSIÓN CORREGIDA · CASOS 1, 2, 3 y 5
# ════════════════════════════════════════════════════════════════════════════════
def reservar_corregido(clave, cliente, conductor):
    huella = sha256(f"{cliente}|{conductor}|{FECHA}")            # "huella" del pedido

    with psycopg.connect(URL) as db:                            # BEGIN: todo o nada

        # PASO 1 · TEMA 9: registrar la clave.
        # Por el UNIQUE, si otra petición con la MISMA clave está en curso, este INSERT ESPERA su COMMIT.
        insertadas = db.execute(
            "INSERT INTO idempotency_records (clave, hash) VALUES (%s, %s) ON CONFLICT (clave) DO NOTHING",
            (clave, huella)).rowcount

        if insertadas == 0:                                      # la clave ya existía → es un reintento
            hash_guardado, respuesta_guardada = db.execute(
                "SELECT hash, respuesta FROM idempotency_records WHERE clave = %s", (clave,)).fetchone()
            db.rollback()
            if huella != hash_guardado:
                return "422 la clave ya se usó con otro pedido"  # CASO 3
            return respuesta_guardada + "  (replay)"             # CASOS 1 y 2: la respuesta guardada

        # PASO 2 · TEMA 10: bloqueo pesimista. Si otro tiene a este conductor, ESPERA acá.
        db.execute("SELECT id FROM conductores WHERE id = %s FOR UPDATE", (conductor,))

        # PASO 3 · TEMA 10: CHECK, ahora seguro porque tenemos el lock
        ocupado = db.execute(
            "SELECT 1 FROM reservas WHERE conductor_id = %s AND fecha_hora = %s",
            (conductor, FECHA)).fetchone()
        if ocupado:
            db.rollback()                                        # el ROLLBACK también borra la clave
            return "409 conductor ocupado"                       # CASO 5

        time.sleep(0.3)   # la misma pausa que la versión ingenua: acá no hace daño

        # PASO 4 · TEMA 10: ACT
        (reserva_id,) = db.execute(
            "INSERT INTO reservas (cliente_id, conductor_id, fecha_hora) VALUES (%s, %s, %s) RETURNING id",
            (cliente, conductor, FECHA)).fetchone()

        # PASO 5 · TEMA 9: guardar la respuesta para los futuros reintentos
        respuesta = f"201 reserva #{reserva_id}"
        db.execute("UPDATE idempotency_records SET respuesta = %s WHERE clave = %s", (respuesta, clave))

        db.commit()                                              # COMMIT
        return respuesta


# ════════════════════════════════════════════════════════════════════════════════
#  LOS 5 CASOS
# ════════════════════════════════════════════════════════════════════════════════
def main():
    with psycopg.connect(URL) as db:
        db.execute(ESQUEMA)

    titulo("CASO 1 · Reintento secuencial: la misma petición 10 veces, una tras otra")
    for i in range(1, 11):
        print(f"  intento {i} → {reservar_corregido('clave-caso-1', 42, 1)}")
    verificar("reservas del conductor 1", contar("reservas", 1), 1)

    titulo("CASO 2 · Reintento paralelo: 10 clientes con la misma clave, al mismo tiempo")
    al_mismo_tiempo(10, lambda i: reservar_corregido("clave-caso-2", 42, 2))
    verificar("reservas del conductor 2", contar("reservas", 2), 1)

    titulo("CASO 3 · Misma clave, otro pedido (conductor 3 → 6)")
    print(f"  original → {reservar_corregido('clave-caso-3', 42, 3)}")
    print(f"  reuso    → {reservar_corregido('clave-caso-3', 42, 6)}")
    verificar("reservas del conductor 6", contar("reservas", 6), 0)

    titulo("CASO 4 · Versión INGENUA: 2 clientes reservan al conductor 4 a la vez")
    al_mismo_tiempo(2, lambda i: reservar_ingenuo(100 + i, 4))
    verificar("reservas del conductor 4 (2 = BUG de doble asignación)", contar("reservas_ingenuas", 4), 2)

    titulo("CASO 5 · Versión CORREGIDA: la misma carrera con el conductor 5")
    al_mismo_tiempo(2, lambda i: reservar_corregido(f"clave-caso-5-cliente-{i}", 100 + i, 5))
    verificar("reservas del conductor 5", contar("reservas", 5), 1)


# ════════════════════════════════════════════════════════════════════════════════
#  AYUDANTES (no hace falta explicarlos)
# ════════════════════════════════════════════════════════════════════════════════
def al_mismo_tiempo(cantidad, pedido):
    """Lanza N hilos y los suelta a todos en el mismo instante."""
    largada = threading.Barrier(cantidad)                        # nadie arranca hasta que estén todos
    resultados = [None] * cantidad

    def cliente(i):
        largada.wait()
        resultados[i - 1] = pedido(i)

    hilos = [threading.Thread(target=cliente, args=(i,)) for i in range(1, cantidad + 1)]
    for hilo in hilos:
        hilo.start()
    for hilo in hilos:
        hilo.join()
    for i, resultado in enumerate(resultados, start=1):
        print(f"  cliente {i} → {resultado}")


def contar(tabla, conductor):
    with psycopg.connect(URL) as db:
        return db.execute(f"SELECT count(*) FROM {tabla} WHERE conductor_id = %s", (conductor,)).fetchone()[0]


def sha256(texto):
    return hashlib.sha256(texto.encode()).hexdigest()


def titulo(texto):
    print(f"\n━━ {texto} ━━")


def verificar(que, obtenido, esperado):
    marca = "✔" if obtenido == esperado else "✘"
    extra = "" if obtenido == esperado else f" (esperado {esperado})"
    print(f"  {marca} {que}: {obtenido}{extra}")


if __name__ == "__main__":
    main()
