package ar.edu.utn.frre.grupo2.demo.reserva;

import ar.edu.utn.frre.grupo2.demo.api.ProblemaHttp;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versión INGENUA (caso 4): check-then-act sin bloqueo ni restricción UNIQUE.
 * <p>
 * Es @Transactional a propósito, para mostrar que una transacción en READ COMMITTED
 * (el nivel por defecto de PostgreSQL) no evita la carrera: dos transacciones leen
 * "libre" antes de que cualquiera de las dos inserte.
 */
@Service
public class ReservaIngenuaService {

    private static final Logger log = LoggerFactory.getLogger(ReservaIngenuaService.class);

    private final JdbcTemplate jdbc;
    private final VentanaDeCarrera ventana;
    private final ObjectMapper mapper;

    public ReservaIngenuaService(JdbcTemplate jdbc, VentanaDeCarrera ventana, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.ventana = ventana;
        this.mapper = mapper;
    }

    @Transactional
    public Resultado crear(ReservaRequest req) {
        Boolean existe = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM conductores WHERE id = ?)", Boolean.class, req.conductorId());
        if (!Boolean.TRUE.equals(existe)) {
            throw ProblemaHttp.conductorInexistente(req.conductorId());
        }

        // CHECK: ¿el conductor está libre en esa franja?
        Boolean ocupado = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM reservas_ingenuas WHERE conductor_id = ? AND fecha_hora = ?)",
                Boolean.class, req.conductorId(), req.fechaHora());
        if (Boolean.TRUE.equals(ocupado)) {
            throw ProblemaHttp.conductorOcupado(req.conductorId(), req.fechaHora());
        }

        // Entre el check y el act, otra transacción puede hacer exactamente lo mismo.
        ventana.esperar();

        // ACT
        Long id = jdbc.queryForObject("""
                        INSERT INTO reservas_ingenuas (cliente_id, conductor_id, fecha_hora)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                Long.class, req.clienteId(), req.conductorId(), req.fechaHora());

        log.info("[ingenua] cliente={} conductor={} -> 201 reserva {}", req.clienteId(), req.conductorId(), id);
        try {
            return Resultado.nuevo(201, mapper.writeValueAsString(
                    new ReservaResponse(id, req.clienteId(), req.conductorId(), req.fechaHora())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la respuesta", e);
        }
    }
}
