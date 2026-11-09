package ar.edu.utn.frre.grupo2.demo.soporte;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.sql.SQLException;

/**
 * Traduce excepciones a respuestas application/problem+json (RFC 9457).
 *
 * Errores de PostgreSQL, por su SQLState:
 *   55P03 lock_not_available  → se superó lock_timeout esperando un lock      → 409
 *   23505 unique_violation    → la red de seguridad UNIQUE frenó un duplicado → 409
 *   23503 foreign_key         → el conductor no existe                        → 404
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(ProblemaHttp.class)
    public ResponseEntity<ProblemDetail> problema(ProblemaHttp ex, HttpServletRequest request) {
        return responder(ex, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> bodyIlegible(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return responder(ProblemaHttp.bodyInvalido("El body no es un JSON válido. Formato esperado: "
                + "{\"clienteId\": 42, \"conductorId\": 7, \"fechaHora\": \"2026-11-12T18:00:00\"}"), request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> baseDeDatos(DataAccessException ex, HttpServletRequest request) {
        String estado = sqlState(ex);
        if ("55P03".equals(estado)) {
            return responder(ProblemaHttp.peticionEnCurso(), request);
        }
        if ("23505".equals(estado)) {
            return responder(ProblemaHttp.conductorOcupado(), request);
        }
        if ("23503".equals(estado)) {
            return responder(ProblemaHttp.conductorInexistente(null), request);
        }
        log.error("Error de base de datos no esperado", ex);
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Error inesperado de base de datos.");
        problema.setTitle("Error interno");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problema);
    }

    private ResponseEntity<ProblemDetail> responder(ProblemaHttp ex, HttpServletRequest request) {
        log.info("→ {} {}", ex.status().value(), ex.titulo());
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problema.setTitle(ex.titulo());
        problema.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(ex.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }

    private static String sqlState(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            t = t.getCause();
        }
        return null;
    }
}
