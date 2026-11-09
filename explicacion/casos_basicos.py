"""
10 CASOS BÁSICOS · TP de Investigación · Grupo 2

  Tema 9  · Idempotencia y deduplicación ............. casos 1 a 5
  Tema 10 · Condiciones de carrera y concurrencia ..... casos 6 a 10

Python puro, sin base de datos ni librerías: para explicar las ideas en clase.
Cada "cliente" es un hilo; al_mismo_tiempo(...) los larga a todos juntos.

Uso:
  python casos_basicos.py        → los 10 casos
  python casos_basicos.py 6      → solo el caso 6
"""
import hashlib
import sys
import threading
import time


# ════════════════════════════════════════════════════════════════════════════════
#  TEMA 9 · IDEMPOTENCIA Y DEDUPLICACIÓN
# ════════════════════════════════════════════════════════════════════════════════

class Cuenta:
    def __init__(self, saldo=0):
        self.saldo = saldo

    def fijar(self, monto):          # IDEMPOTENTE: repetirla no cambia nada
        self.saldo = monto

    def sumar(self, monto):          # NO idempotente: cada repetición suma otra vez
        self.saldo += monto


def caso_1():
    """Idempotente vs no idempotente: f(f(x)) = f(x)."""
    cuenta = Cuenta()
    for _ in range(3):
        cuenta.fijar(100)
    print(f"  fijar(100) tres veces → saldo = {cuenta.saldo}")
    verificar("saldo después de fijar (idempotente)", cuenta.saldo, 100)

    cuenta = Cuenta()
    for _ in range(3):
        cuenta.sumar(100)
    print(f"  sumar(100) tres veces → saldo = {cuenta.saldo}")
    verificar("saldo después de sumar (NO idempotente)", cuenta.saldo, 300)


def caso_2():
    """Importa el EFECTO, no la respuesta: DELETE dos veces."""
    archivos = {"foto.png", "cv.pdf"}

    def delete(nombre):
        if nombre in archivos:
            archivos.remove(nombre)
            return "200 borrado"
        return "404 no existe"

    print(f"  1er DELETE foto.png → {delete('foto.png')}")
    print(f"  2do DELETE foto.png → {delete('foto.png')}")
    verificar("la respuesta cambió, pero el efecto es el mismo; archivos que quedan",
              sorted(archivos), ["cv.pdf"])


class UserManager:
    """Ejemplo de la investigación: deduplicación por clave natural (el username)."""

    def __init__(self):
        self.users = {}

    def add_user(self, username, details):
        if username not in self.users:              # CHECK: ¿ya existe?
            self.users[username] = details          # ACT: lo agrego
            return f"Usuario '{username}' agregado."
        return f"El usuario '{username}' ya existe. No se realizaron cambios."


def caso_3():
    """Deduplicación por clave natural: el mismo usuario dos veces."""
    manager = UserManager()
    print("  " + manager.add_user("ana_perez", {"email": "ana@example.com"}))
    print("  " + manager.add_user("ana_perez", {"email": "ana@example.com"}))
    verificar("usuarios registrados", len(manager.users), 1)


class ApiPagos:
    """Un POST /pagos con Idempotency-Key (como Stripe)."""

    def __init__(self):
        self.cobros = []        # el efecto real: cada cobro a la tarjeta
        self.claves = {}        # Idempotency-Key → (huella del pedido, respuesta guardada)

    def pagar_sin_clave(self, monto):
        self.cobros.append(monto)
        return f"201 cobro #{len(self.cobros)} por ${monto}"

    def pagar(self, clave, monto):
        huella = hashlib.sha256(str(monto).encode()).hexdigest()     # "huella" del pedido

        if clave in self.claves:                                      # CHECK: ¿ya vi esta clave?
            huella_guardada, respuesta = self.claves[clave]
            if huella != huella_guardada:
                return "422 la clave ya se usó con otro pedido"      # caso 5
            return respuesta + "  (replay)"                          # caso 4

        time.sleep(0.1)                                               # el cobro tarda (llamada al banco)
        self.cobros.append(monto)                                     # ACT: cobrar
        respuesta = f"201 cobro #{len(self.cobros)} por ${monto}"
        self.claves[clave] = (huella, respuesta)                      # guardar para los reintentos
        return respuesta


def caso_4():
    """Idempotency-Key: el cliente reintenta 3 veces un pago (timeout)."""
    api = ApiPagos()
    print("  Sin clave:")
    for intento in range(1, 4):
        print(f"    intento {intento} → {api.pagar_sin_clave(500)}")
    verificar("cobros sin clave (¡cobró tres veces!)", len(api.cobros), 3)

    api = ApiPagos()
    print("  Con Idempotency-Key:")
    for intento in range(1, 4):
        print(f"    intento {intento} → {api.pagar('clave-abc', 500)}")
    verificar("cobros con clave", len(api.cobros), 1)


def caso_5():
    """Misma clave, otro pedido: la huella (hash) lo detecta."""
    api = ApiPagos()
    print(f"  pagar('clave-abc', 500) → {api.pagar('clave-abc', 500)}")
    print(f"  pagar('clave-abc', 900) → {api.pagar('clave-abc', 900)}")
    verificar("cobros realizados (el de $900 se rechazó)", len(api.cobros), 1)


# ════════════════════════════════════════════════════════════════════════════════
#  TEMA 10 · CONDICIONES DE CARRERA Y CONTROL DE CONCURRENCIA
# ════════════════════════════════════════════════════════════════════════════════

class Agenda:
    def __init__(self):
        self.reservas = []                          # (conductor, hora, cliente)

    def reservar(self, cliente, conductor, hora):
        ocupado = any(c == conductor and h == hora for c, h, _ in self.reservas)   # CHECK
        if ocupado:
            return "409 conductor ocupado"
        time.sleep(0.1)                             # ventana entre el check y el act
        self.reservas.append((conductor, hora, cliente))                            # ACT
        return "201 reservado"


def caso_6():
    """Condición de carrera (check-then-act): 2 clientes, el mismo conductor, a la vez."""
    agenda = Agenda()
    respuestas = al_mismo_tiempo(
        lambda: agenda.reservar("Ana", conductor=7, hora="18:00"),
        lambda: agenda.reservar("Beto", conductor=7, hora="18:00"))
    mostrar(respuestas, ["Ana", "Beto"])
    verificar("reservas del conductor 7 (BUG: doble asignación)", len(agenda.reservas), 2)


def caso_7():
    """Actualización perdida (read-modify-write): dos depósitos a la vez."""
    cuenta = Cuenta(saldo=100)

    def depositar(monto):
        leido = cuenta.saldo                        # READ
        time.sleep(0.1)
        cuenta.saldo = leido + monto                # MODIFY + WRITE: pisa al otro

    al_mismo_tiempo(lambda: depositar(10), lambda: depositar(20))
    print(f"  saldo inicial 100, depósitos de 10 y 20 → saldo final = {cuenta.saldo}")
    verificar("se perdió un depósito (BUG: debería ser 130)", cuenta.saldo != 130, True)


class AgendaConLock(Agenda):
    """Bloqueo PESIMISTA: el mismo código, pero de a un cliente por vez."""

    def __init__(self):
        super().__init__()
        self.lock = threading.Lock()

    def reservar(self, cliente, conductor, hora):
        with self.lock:                             # el segundo cliente ESPERA acá
            return super().reservar(cliente, conductor, hora)


def caso_8():
    """Solución pesimista: lock alrededor del check-then-act."""
    agenda = AgendaConLock()
    respuestas = al_mismo_tiempo(
        lambda: agenda.reservar("Ana", conductor=7, hora="18:00"),
        lambda: agenda.reservar("Beto", conductor=7, hora="18:00"))
    mostrar(respuestas, ["Ana", "Beto"])
    verificar("reservas del conductor 7", len(agenda.reservas), 1)


class CuentaConVersion:
    """Bloqueo OPTIMISTA: no se espera; se detecta el conflicto al escribir."""

    def __init__(self, saldo):
        self.saldo = saldo
        self.version = 0
        self._atomico = threading.Lock()

    def leer(self):
        return self.saldo, self.version

    def escribir_si_no_cambio(self, nuevo_saldo, version_leida):
        # En una base de datos: UPDATE cuenta SET saldo = ?, version = version + 1 WHERE version = ?
        with self._atomico:
            if self.version != version_leida:
                return False                        # alguien escribió antes: conflicto
            self.saldo = nuevo_saldo
            self.version += 1
            return True


def caso_9():
    """Solución optimista: versión + reintento ante conflicto."""
    cuenta = CuentaConVersion(saldo=100)
    eventos = []

    def depositar(quien, monto):
        while True:
            saldo, version = cuenta.leer()                          # READ (con su versión)
            time.sleep(0.1)
            if cuenta.escribir_si_no_cambio(saldo + monto, version):
                eventos.append(f"{quien}: depositó {monto}")
                return
            eventos.append(f"{quien}: conflicto, la versión cambió → reintenta")

    al_mismo_tiempo(lambda: depositar("A", 10), lambda: depositar("B", 20))
    for evento in eventos:
        print(f"  {evento}")
    verificar("saldo final", cuenta.saldo, 130)


class ApiPagosSegura(ApiPagos):
    """Idempotencia + control de concurrencia: el chequeo de la clave, de a uno por vez."""

    def __init__(self):
        super().__init__()
        self.lock = threading.Lock()

    def pagar(self, clave, monto):
        with self.lock:
            return super().pagar(clave, monto)


def caso_10():
    """Punto de unión: dos reintentos con la MISMA clave, al mismo tiempo."""
    api = ApiPagos()
    print("  Idempotencia SIN control de concurrencia:")
    mostrar(al_mismo_tiempo(lambda: api.pagar("clave-xyz", 500),
                            lambda: api.pagar("clave-xyz", 500)), ["reintento 1", "reintento 2"], "    ")
    verificar("cobros (BUG: los dos pasaron el chequeo de la clave)", len(api.cobros), 2)

    api = ApiPagosSegura()
    print("  Idempotencia CON control de concurrencia:")
    mostrar(al_mismo_tiempo(lambda: api.pagar("clave-xyz", 500),
                            lambda: api.pagar("clave-xyz", 500)), ["reintento 1", "reintento 2"], "    ")
    verificar("cobros", len(api.cobros), 1)
    print("  Nota: este lock vive en la memoria de UN proceso. Con varias réplicas el lock tiene que")
    print("  estar en la base de datos: es el UNIQUE (clave) de la demo integrada.")


# ════════════════════════════════════════════════════════════════════════════════
#  AYUDANTES (no hace falta explicarlos)
# ════════════════════════════════════════════════════════════════════════════════

def al_mismo_tiempo(*funciones):
    """Corre cada función en su propio hilo, las suelta juntas y devuelve sus respuestas en orden."""
    largada = threading.Barrier(len(funciones))
    respuestas = [None] * len(funciones)

    def correr(i, funcion):
        largada.wait()
        respuestas[i] = funcion()

    hilos = [threading.Thread(target=correr, args=(i, f)) for i, f in enumerate(funciones)]
    for hilo in hilos:
        hilo.start()
    for hilo in hilos:
        hilo.join()
    return respuestas


def mostrar(respuestas, quienes, sangria="  "):
    for quien, respuesta in zip(quienes, respuestas):
        print(f"{sangria}{quien} → {respuesta}")


def verificar(que, obtenido, esperado):
    marca = "✔" if obtenido == esperado else "✘"
    extra = "" if obtenido == esperado else f" (esperado {esperado})"
    valor = "sí" if obtenido is True else obtenido
    print(f"  {marca} {que}: {valor}{extra}")


CASOS = {
    1: ("Tema 9", caso_1), 2: ("Tema 9", caso_2), 3: ("Tema 9", caso_3),
    4: ("Tema 9", caso_4), 5: ("Tema 9", caso_5),
    6: ("Tema 10", caso_6), 7: ("Tema 10", caso_7), 8: ("Tema 10", caso_8),
    9: ("Tema 10", caso_9), 10: ("Tema 9 + Tema 10", caso_10),
}

if __name__ == "__main__":
    elegidos = [int(a) for a in sys.argv[1:]] or list(CASOS)
    for numero in elegidos:
        tema, caso = CASOS[numero]
        print(f"\n━━ CASO {numero} · {tema} · {caso.__doc__} ━━")
        caso()
