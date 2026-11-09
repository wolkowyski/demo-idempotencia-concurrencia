package ar.edu.utn.frre.grupo2.demo.reserva;

import ar.edu.utn.frre.grupo2.demo.soporte.Pausa;
import ar.edu.utn.frre.grupo2.demo.soporte.ProblemaHttp;
import ar.edu.utn.frre.grupo2.demo.soporte.Resultado;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * VERSIÓN CORREGIDA de POST /reservas.
 *
 * Los cinco pasos corren en UNA sola transacción: si algo falla, el ROLLBACK
 * deshace todo, incluida la clave de idempotencia.
 *
 *   Paso 1 · Tema 9  · registrar la Idempotency-Key       → replay o 422
 *   Paso 2 · Tema 10 · bloquear al conductor (FOR UPDATE)
 *   Paso 3 · Tema 10 · verificar que esté libre (check)   → 409
 *   Paso 4 · Tema 10 · crear la reserva (act)
 *   Paso 5 · Tema 9  · guardar la respuesta para los reintentos
 */
@Service
public class ReservaService {

    private final JdbcTemplate jdbc;
    private final long ventanaDeCarreraMs;

    public ReservaService(JdbcTemplate jdbc, @Value("${demo.ventana-carrera-ms}") long ventanaDeCarreraMs) {
        this.jdbc = jdbc;
        this.ventanaDeCarreraMs = ventanaDeCarreraMs;
    }

    @Transactional
    public Resultado reservar(String clave, ReservaRequest pedido) {

        // ── PASO 1 · TEMA 9: registrar la clave de idempotencia ────────────────────
        // clave tiene una restricción UNIQUE. Si otra petición con la MISMA clave
        // todavía no terminó, PostgreSQL hace esperar a este INSERT hasta su COMMIT.
        // Ese es el punto de unión de los dos temas: no hay hueco entre
        // "¿existe la clave?" y "la guardo".
        String hash = pedido.huella();
        int insertadas = jdbc.update("""
                INSERT INTO idempotency_records (clave, hash, estado)
                VALUES (?, ?, 'EN_CURSO')
                ON CONFLICT (clave) DO NOTHING
                """, clave, hash);

        if (insertadas == 0) {
            // La clave ya existía: es un reintento.
            Map<String, Object> anterior = jdbc.queryForMap(
                    "SELECT hash, status_http, respuesta FROM idempotency_records WHERE clave = ?", clave);

            if (!hash.equals(anterior.get("hash"))) {
                throw ProblemaHttp.claveReusada();                              // CASO 3 → 422
            }
            return Resultado.replay((Integer) anterior.get("status_http"),     // CASOS 1 y 2 → replay
                    (String) anterior.get("respuesta"));
        }

        // ── PASO 2 · TEMA 10: bloqueo pesimista del conductor ───────────────────────
        // Si otra transacción ya tiene el lock de este conductor, esta ESPERA acá.
        List<Long> conductor = jdbc.queryForList(
                "SELECT id FROM conductores WHERE id = ? FOR UPDATE", Long.class, pedido.conductorId());
        if (conductor.isEmpty()) {
            throw ProblemaHttp.conductorInexistente(pedido.conductorId());     // → 404
        }

        // ── PASO 3 · TEMA 10: CHECK, ahora seguro porque tenemos el lock ───────────
        Boolean ocupado = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM reservas WHERE conductor_id = ? AND fecha_hora = ?)",
                Boolean.class, pedido.conductorId(), pedido.fechaHora());
        if (Boolean.TRUE.equals(ocupado)) {
            throw ProblemaHttp.conductorOcupado();                              // CASO 5 → 409
        }

        Pausa.de(ventanaDeCarreraMs);   // misma pausa que la versión ingenua: acá no hace daño

        // ── PASO 4 · TEMA 10: ACT. UNIQUE (conductor_id, fecha_hora) es la red de seguridad
        Long id = jdbc.queryForObject("""
                INSERT INTO reservas (cliente_id, conductor_id, fecha_hora)
                VALUES (?, ?, ?)
                RETURNING id
                """, Long.class, pedido.clienteId(), pedido.conductorId(), pedido.fechaHora());

        // ── PASO 5 · TEMA 9: guardar la respuesta para los futuros reintentos ───────
        String respuesta = new ReservaResponse(id, pedido).aJson();
        jdbc.update("""
                UPDATE idempotency_records
                SET estado = 'COMPLETADA', status_http = 201, respuesta = ?
                WHERE clave = ?
                """, respuesta, clave);

        return Resultado.nuevo(201, respuesta);                                 // → 201 Created
    }
}
