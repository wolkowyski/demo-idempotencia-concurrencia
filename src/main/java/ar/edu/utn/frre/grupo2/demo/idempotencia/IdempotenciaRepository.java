package ar.edu.utn.frre.grupo2.demo.idempotencia;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a idempotency_records con SQL explícito, para que se vea qué hace la base.
 */
@Repository
public class IdempotenciaRepository {

    private final JdbcTemplate jdbc;

    public IdempotenciaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Intenta registrar la clave como EN_CURSO.
     * <p>
     * Si otra transacción insertó la misma clave y todavía no confirmó, PostgreSQL hace
     * ESPERAR a esta sentencia en el índice UNIQUE hasta que la otra haga COMMIT o ROLLBACK
     * (o hasta que se supere lock_timeout). Ese es el control de concurrencia dentro de la
     * idempotencia: no hay ventana entre "¿existe la clave?" y "la guardo".
     *
     * @return true si la clave es nueva; false si ya existía.
     */
    public boolean registrar(String clave, String hash) {
        int filas = jdbc.update("""
                INSERT INTO idempotency_records (clave, hash, estado)
                VALUES (?, ?, 'EN_CURSO')
                ON CONFLICT (clave) DO NOTHING
                """, clave, hash);
        return filas == 1;
    }

    public Optional<RegistroIdempotencia> buscar(String clave) {
        List<RegistroIdempotencia> filas = jdbc.query("""
                        SELECT clave, hash, estado, status_http, respuesta
                        FROM idempotency_records
                        WHERE clave = ?
                        """,
                (rs, i) -> new RegistroIdempotencia(
                        rs.getString("clave"),
                        rs.getString("hash"),
                        rs.getString("estado"),
                        (Integer) rs.getObject("status_http"),
                        rs.getString("respuesta")),
                clave);
        return filas.stream().findFirst();
    }

    /** Guarda la respuesta para los replays. Corre en la misma transacción que la reserva. */
    public void completar(String clave, int statusHttp, String respuesta) {
        jdbc.update("""
                UPDATE idempotency_records
                SET estado = 'COMPLETADA', status_http = ?, respuesta = ?
                WHERE clave = ?
                """, statusHttp, respuesta, clave);
    }

    /** Borra las claves con más de {@code ttlHoras} de antigüedad. */
    public int purgarVencidas(int ttlHoras) {
        return jdbc.update("""
                DELETE FROM idempotency_records
                WHERE creado_en < now() - make_interval(hours => ?)
                """, ttlHoras);
    }
}
