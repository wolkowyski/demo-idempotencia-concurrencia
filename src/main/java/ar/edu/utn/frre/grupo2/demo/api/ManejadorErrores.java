package ar.edu.utn.frre.grupo2.demo.api;

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

/**
 * Traduce los errores a application/problem+json (RFC 9457).
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
        return responder(ProblemaHttp.bodyInvalido(
                "El body no es un JSON válido. Formato esperado: "
                        + "{\"clienteId\": 42, \"conductorId\": 7, \"fechaHora\": \"2026-11-12T18:00:00\"}"), request);
    }

    /** Red de seguridad: cualquier lock timeout, deadlock o falla de serialización que no se haya manejado antes. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> baseDeDatos(DataAccessException ex, HttpServletRequest request) {
        if (ErroresSql.esConflictoDeConcurrencia(ex)) {
            return responder(ProblemaHttp.conflictoDeConcurrencia(), request);
        }
        log.error("Error de base de datos no esperado", ex);
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Error inesperado de base de datos.");
        problema.setTitle("Error interno");
        problema.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }

    private ResponseEntity<ProblemDetail> responder(ProblemaHttp ex, HttpServletRequest request) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problema.setType(URI.create(ex.tipo()));
        problema.setTitle(ex.titulo());
        problema.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(ex.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }
}
