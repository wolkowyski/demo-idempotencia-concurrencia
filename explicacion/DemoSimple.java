import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * DEMO SIMPLE · TP de Investigación · Grupo 2 · Temas 9 y 10
 *
 * El código más corto posible para explicar los 5 casos: Java + SQL, sin frameworks.
 * Cada "cliente" es un hilo; los que corren a la vez simulan pedidos simultáneos.
 *
 *   reservarIngenuo(...)    → caso 4: check-then-act sin protección (el bug)
 *   reservarCorregido(...)  → casos 1, 2, 3 y 5: idempotencia + bloqueo, en una transacción
 */
public class DemoSimple {

    static final String FECHA = "2026-11-12 18:00";

    // ════════════════════════════════════════════════════════════════════════════
    //  LAS TABLAS
    // ════════════════════════════════════════════════════════════════════════════
    static final String ESQUEMA = """
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
            """;

    // ════════════════════════════════════════════════════════════════════════════
    //  VERSIÓN INGENUA · CASO 4
    // ════════════════════════════════════════════════════════════════════════════
    static String reservarIngenuo(int cliente, int conductor) throws Exception {
        try (Connection db = conectar()) {
            db.setAutoCommit(false);                                    // BEGIN

            // CHECK: ¿está libre?
            if (existe(db, "SELECT 1 FROM reservas_ingenuas WHERE conductor_id = ? AND fecha_hora = ?::timestamp",
                    conductor, FECHA)) {
                db.rollback();
                return "409 conductor ocupado";
            }

            Thread.sleep(300);   // EL BUG: mientras tanto, otro cliente hace el mismo CHECK y también ve "libre"

            // ACT: los dos insertan → doble asignación
            ejecutar(db, "INSERT INTO reservas_ingenuas (cliente_id, conductor_id, fecha_hora) VALUES (?, ?, ?::timestamp)",
                    cliente, conductor, FECHA);
            db.commit();                                                // COMMIT
            return "201 reserva creada";
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  VERSIÓN CORREGIDA · CASOS 1, 2, 3 y 5
    // ════════════════════════════════════════════════════════════════════════════
    static String reservarCorregido(String clave, int cliente, int conductor) throws Exception {
        String hash = sha256(cliente + "|" + conductor + "|" + FECHA);  // "huella" del pedido

        try (Connection db = conectar()) {
            db.setAutoCommit(false);                                    // BEGIN: todo o nada

            // PASO 1 · TEMA 9: registrar la clave.
            // Por el UNIQUE, si otra petición con la MISMA clave está en curso, este INSERT ESPERA su COMMIT.
            int insertadas = ejecutar(db,
                    "INSERT INTO idempotency_records (clave, hash) VALUES (?, ?) ON CONFLICT (clave) DO NOTHING",
                    clave, hash);

            if (insertadas == 0) {                                      // la clave ya existía → es un reintento
                String hashGuardado = leer(db, "SELECT hash FROM idempotency_records WHERE clave = ?", clave);
                String respuestaGuardada = leer(db, "SELECT respuesta FROM idempotency_records WHERE clave = ?", clave);
                db.rollback();
                if (!hash.equals(hashGuardado)) {
                    return "422 la clave ya se usó con otro pedido";    // CASO 3
                }
                return respuestaGuardada + "  (replay)";                // CASOS 1 y 2: la respuesta guardada
            }

            // PASO 2 · TEMA 10: bloqueo pesimista. Si otro tiene a este conductor, ESPERA acá.
            ejecutar(db, "SELECT id FROM conductores WHERE id = ? FOR UPDATE", conductor);

            // PASO 3 · TEMA 10: CHECK, ahora seguro porque tenemos el lock
            if (existe(db, "SELECT 1 FROM reservas WHERE conductor_id = ? AND fecha_hora = ?::timestamp",
                    conductor, FECHA)) {
                db.rollback();                                          // el ROLLBACK también borra la clave
                return "409 conductor ocupado";                         // CASO 5
            }

            Thread.sleep(300);   // la misma pausa que la versión ingenua: acá no hace daño

            // PASO 4 · TEMA 10: ACT
            String id = leer(db, "INSERT INTO reservas (cliente_id, conductor_id, fecha_hora) VALUES (?, ?, ?::timestamp) RETURNING id",
                    cliente, conductor, FECHA);

            // PASO 5 · TEMA 9: guardar la respuesta para los futuros reintentos
            String respuesta = "201 reserva #" + id;
            ejecutar(db, "UPDATE idempotency_records SET respuesta = ? WHERE clave = ?", respuesta, clave);

            db.commit();                                                // COMMIT
            return respuesta;
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  LOS 5 CASOS
    // ════════════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {
        try (Connection db = conectar()) {
            db.createStatement().execute(ESQUEMA);
        }

        titulo("CASO 1 · Reintento secuencial: la misma petición 10 veces, una tras otra");
        for (int i = 1; i <= 10; i++) {
            System.out.println("  intento " + i + " → " + reservarCorregido("clave-caso-1", 42, 1));
        }
        verificar("reservas del conductor 1", contar("reservas", 1), 1);

        titulo("CASO 2 · Reintento paralelo: 10 clientes con la misma clave, al mismo tiempo");
        alMismoTiempo(10, i -> reservarCorregido("clave-caso-2", 42, 2));
        verificar("reservas del conductor 2", contar("reservas", 2), 1);

        titulo("CASO 3 · Misma clave, otro pedido (conductor 3 → 6)");
        System.out.println("  original → " + reservarCorregido("clave-caso-3", 42, 3));
        System.out.println("  reuso    → " + reservarCorregido("clave-caso-3", 42, 6));
        verificar("reservas del conductor 6", contar("reservas", 6), 0);

        titulo("CASO 4 · Versión INGENUA: 2 clientes reservan al conductor 4 a la vez");
        alMismoTiempo(2, i -> reservarIngenuo(100 + i, 4));
        verificar("reservas del conductor 4 (2 = BUG de doble asignación)", contar("reservas_ingenuas", 4), 2);

        titulo("CASO 5 · Versión CORREGIDA: la misma carrera con el conductor 5");
        alMismoTiempo(2, i -> reservarCorregido("clave-caso-5-cliente-" + i, 100 + i, 5));
        verificar("reservas del conductor 5", contar("reservas", 5), 1);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  AYUDANTES (no hace falta explicarlos)
    // ════════════════════════════════════════════════════════════════════════════
    interface Pedido { String hacer(int cliente) throws Exception; }

    /** Lanza N hilos y los suelta a todos en el mismo instante. */
    static void alMismoTiempo(int cantidad, Pedido pedido) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(cantidad);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<String>> resultados = IntStream.rangeClosed(1, cantidad)
                .mapToObj(i -> hilos.submit((Callable<String>) () -> { largada.await(); return pedido.hacer(i); }))
                .toList();
        largada.countDown();                                            // ¡largaron!
        for (int i = 0; i < cantidad; i++) {
            System.out.println("  cliente " + (i + 1) + " → " + resultados.get(i).get());
        }
        hilos.shutdown();
    }

    static Connection conectar() throws Exception {
        return DriverManager.getConnection(
                System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/postgres"),
                System.getenv().getOrDefault("DB_USER", "demo"),
                System.getenv().getOrDefault("DB_PASSWORD", "demo"));
    }

    static PreparedStatement preparar(Connection db, String sql, Object... parametros) throws Exception {
        PreparedStatement sentencia = db.prepareStatement(sql);
        for (int i = 0; i < parametros.length; i++) {
            sentencia.setObject(i + 1, parametros[i]);
        }
        return sentencia;
    }

    static int ejecutar(Connection db, String sql, Object... parametros) throws Exception {
        PreparedStatement sentencia = preparar(db, sql, parametros);
        return sentencia.execute() ? 1 : sentencia.getUpdateCount();
    }

    static boolean existe(Connection db, String sql, Object... parametros) throws Exception {
        return preparar(db, sql, parametros).executeQuery().next();
    }

    static String leer(Connection db, String sql, Object... parametros) throws Exception {
        ResultSet fila = preparar(db, sql, parametros).executeQuery();
        return fila.next() ? fila.getString(1) : null;
    }

    static int contar(String tabla, int conductor) throws Exception {
        try (Connection db = conectar()) {
            return Integer.parseInt(leer(db, "SELECT count(*) FROM " + tabla + " WHERE conductor_id = ?", conductor));
        }
    }

    static String sha256(String texto) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
    }

    static void titulo(String texto) {
        System.out.println("\n━━ " + texto + " ━━");
    }

    static void verificar(String que, int obtenido, int esperado) {
        System.out.println("  " + (obtenido == esperado ? "✔" : "✘") + " " + que + ": " + obtenido
                + (obtenido == esperado ? "" : " (esperado " + esperado + ")"));
    }
}
