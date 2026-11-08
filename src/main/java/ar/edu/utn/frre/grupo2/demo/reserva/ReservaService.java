package ar.edu.utn.frre.grupo2.demo.reserva;

import ar.edu.utn.frre.grupo2.demo.api.ErroresSql;
import ar.edu.utn.frre.grupo2.demo.api.ProblemaHttp;
import ar.edu.utn.frre.grupo2.demo.idempotencia.Huella;
import ar.edu.utn.frre.grupo2.demo.idempotencia.IdempotenciaRepository;
import ar.edu.utn.frre.grupo2.demo.idempotencia.RegistroIdempotencia;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Versión CORREGIDA de POST /reservas: Tema 9 + Tema 10 en una sola transacción.
 *
 * <pre>
 * BEGIN;
 *   SET LOCAL lock_timeout = ...;
 *   INSERT INTO idempotency_records ... ON CONFLICT (clave) DO NOTHING;  -- Tema 9 (+ concurrencia)
 *   SELECT ... FROM conductores WHERE id = ? FOR UPDATE;                 -- Tema 10: bloqueo pesimista
 *   SELECT EXISTS (... reservas ...);                                    -- check
 *   INSERT INTO reservas ...;                                            -- act (UNIQUE como red)
 *   UPDATE idempotency_records SET estado = 'COMPLETADA', ...;           -- respuesta para replays
 * COMMIT;
 * </pre>
 *
 * Si algo falla, el ROLLBACK borra también la clave: un reintento vuelve a procesar.
 */
@Service
public class ReservaService {

    private static final Logger log = LoggerFactory.getLogger(ReservaService.class);

    private final JdbcTemplate jdbc;
    private final IdempotenciaRepository idempotencia;
    private final VentanaDeCarrera ventana;
    private final ObjectMapper mapper;
    private final long lockTimeoutMs;

    public ReservaService(JdbcTemplate jdbc,
                          IdempotenciaRepository idempotencia,
                          VentanaDeCarrera ventana,
                          ObjectMapper mapper,
                          @Value("${demo.lock-timeout-ms}") long lockTimeoutMs) {
        this.jdbc = jdbc;
        this.idempotencia = idempotencia;
        this.ventana = ventana;
        this.mapper = mapper;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    @Transactional
    public Resultado crear(String clave, ReservaRequest req) {
        // Ninguna espera por locks dura más que esto (valor numérico de configuración, no del cliente).
        jdbc.execute("SET LOCAL lock_timeout = " + lockTimeoutMs);

        String hash = Huella.sha256(req.canonico());

        // ---- 1. Tema 9: registrar la clave ------------------------------------------
        boolean claveNueva;
        try {
            claveNueva = idempotencia.registrar(clave, hash);
        } catch (DataAccessException e) {
            if (ErroresSql.esLockTimeout(e)) {
                log.info("[corregida] clave={} -> 409 (la petición original sigue en curso)", clave);
                throw ProblemaHttp.peticionEnCurso();
            }
            throw e;
        }

        if (!claveNueva) {
            // Llegamos acá después de que la transacción original confirmó: vemos su registro.
            RegistroIdempotencia registro = idempotencia.buscar(clave)
                    .orElseThrow(ProblemaHttp::peticionEnCurso);
            if (!registro.hash().equals(hash)) {
                log.info("[corregida] clave={} -> 422 (misma clave, body distinto)", clave);
                throw ProblemaHttp.claveReusada();
            }
            if (!registro.completada()) {
                throw ProblemaHttp.peticionEnCurso();
            }
            log.info("[corregida] clave={} -> replay {}", clave, registro.statusHttp());
            return Resultado.replay(registro.statusHttp(), registro.respuesta());
        }

        // ---- 2. Tema 10: bloqueo pesimista sobre el conductor -------------------------
        List<Long> conductor;
        try {
            conductor = jdbc.queryForList(
                    "SELECT id FROM conductores WHERE id = ? FOR UPDATE",
                    Long.class, req.conductorId());
        } catch (DataAccessException e) {
            if (ErroresSql.esLockTimeout(e)) {
                throw ProblemaHttp.conductorBloqueado(req.conductorId());
            }
            throw e;
        }
        if (conductor.isEmpty()) {
            throw ProblemaHttp.conductorInexistente(req.conductorId());
        }

        // ---- 3. Check: con el lock tomado, nadie más puede estar en este punto --------
        Boolean ocupado = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM reservas WHERE conductor_id = ? AND fecha_hora = ?)",
                Boolean.class, req.conductorId(), req.fechaHora());
        if (Boolean.TRUE.equals(ocupado)) {
            log.info("[corregida] clave={} conductor={} -> 409 (conductor ocupado)", clave, req.conductorId());
            throw ProblemaHttp.conductorOcupado(req.conductorId(), req.fechaHora());
        }

        ventana.esperar();

        // ---- 4. Act: la restricción UNIQUE es la red de seguridad ---------------------
        Long id;
        try {
            id = jdbc.queryForObject("""
                            INSERT INTO reservas (cliente_id, conductor_id, fecha_hora)
                            VALUES (?, ?, ?)
                            RETURNING id
                            """,
                    Long.class, req.clienteId(), req.conductorId(), req.fechaHora());
        } catch (DataAccessException e) {
            if (ErroresSql.esViolacionUnique(e)) {
                throw ProblemaHttp.conductorOcupado(req.conductorId(), req.fechaHora());
            }
            throw e;
        }

        // ---- 5. Guardar la respuesta en el mismo COMMIT -------------------------------
        String json = aJson(new ReservaResponse(id, req.clienteId(), req.conductorId(), req.fechaHora()));
        idempotencia.completar(clave, 201, json);
        log.info("[corregida] clave={} conductor={} -> 201 reserva {}", clave, req.conductorId(), id);
        return Resultado.nuevo(201, json);
    }

    private String aJson(Object valor) {
        try {
            return mapper.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la respuesta", e);
        }
    }
}
