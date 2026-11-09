package ar.edu.utn.frre.grupo2.demo.reserva;

import ar.edu.utn.frre.grupo2.demo.soporte.Pausa;
import ar.edu.utn.frre.grupo2.demo.soporte.ProblemaHttp;
import ar.edu.utn.frre.grupo2.demo.soporte.Resultado;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VERSIÓN INGENUA (caso 4): CHECK-THEN-ACT sin bloqueo y sin UNIQUE.
 *
 * Es @Transactional a propósito: una transacción en READ COMMITTED
 * (el nivel por defecto de PostgreSQL) NO evita la carrera.
 */
@Service
public class ReservaIngenuaService {

    private final JdbcTemplate jdbc;
    private final long ventanaDeCarreraMs;

    public ReservaIngenuaService(JdbcTemplate jdbc, @Value("${demo.ventana-carrera-ms}") long ventanaDeCarreraMs) {
        this.jdbc = jdbc;
        this.ventanaDeCarreraMs = ventanaDeCarreraMs;
    }

    @Transactional
    public Resultado reservar(ReservaRequest pedido) {

        // CHECK: ¿el conductor está libre en ese horario?
        Boolean ocupado = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM reservas_ingenuas WHERE conductor_id = ? AND fecha_hora = ?)",
                Boolean.class, pedido.conductorId(), pedido.fechaHora());
        if (Boolean.TRUE.equals(ocupado)) {
            throw ProblemaHttp.conductorOcupado();
        }

        // EL BUG: entre el check y el act, otro cliente puede hacer el mismo check
        // y ver "libre" también. La pausa solo agranda esta ventana para que se vea siempre.
        Pausa.de(ventanaDeCarreraMs);

        // ACT: los dos insertan → doble asignación
        Long id = jdbc.queryForObject("""
                INSERT INTO reservas_ingenuas (cliente_id, conductor_id, fecha_hora)
                VALUES (?, ?, ?)
                RETURNING id
                """, Long.class, pedido.clienteId(), pedido.conductorId(), pedido.fechaHora());

        return Resultado.nuevo(201, new ReservaResponse(id, pedido).aJson());
    }
}
