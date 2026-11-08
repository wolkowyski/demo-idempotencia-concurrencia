package ar.edu.utn.frre.grupo2.demo.api;

import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

/**
 * Error de negocio o de protocolo que se responde como application/problem+json (RFC 9457).
 * Los títulos de idempotencia siguen los ejemplos del draft IETF
 * draft-ietf-httpapi-idempotency-key-header, sección 2.7.
 */
public class ProblemaHttp extends RuntimeException {

    private final HttpStatus status;
    private final String tipo;
    private final String titulo;

    private ProblemaHttp(HttpStatus status, String tipo, String titulo, String detalle) {
        super(detalle);
        this.status = status;
        this.tipo = tipo;
        this.titulo = titulo;
    }

    // ---- Tema 9: idempotencia -------------------------------------------------

    public static ProblemaHttp claveFaltante() {
        return new ProblemaHttp(HttpStatus.BAD_REQUEST,
                "urn:demo:problema:idempotency-key-faltante",
                "Idempotency-Key is missing",
                "Esta operación es idempotente y requiere el header Idempotency-Key.");
    }

    public static ProblemaHttp claveInvalida() {
        return new ProblemaHttp(HttpStatus.BAD_REQUEST,
                "urn:demo:problema:idempotency-key-invalida",
                "Idempotency-Key is invalid",
                "La Idempotency-Key debe tener entre 8 y 255 caracteres (letras, números, '-', '_', '.', ':'). "
                        + "Se recomienda un UUID v4.");
    }

    public static ProblemaHttp claveReusada() {
        return new ProblemaHttp(HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:demo:problema:idempotency-key-reusada",
                "Idempotency-Key is already used",
                "La Idempotency-Key ya se usó con un body distinto. "
                        + "Una operación nueva necesita una clave nueva.");
    }

    public static ProblemaHttp peticionEnCurso() {
        return new ProblemaHttp(HttpStatus.CONFLICT,
                "urn:demo:problema:peticion-en-curso",
                "A request is outstanding for this Idempotency-Key",
                "Otra petición con la misma Idempotency-Key se está procesando. Reintentá en unos segundos.");
    }

    // ---- Tema 10: concurrencia sobre el conductor -----------------------------

    public static ProblemaHttp conductorOcupado(Long conductorId, LocalDateTime fechaHora) {
        return new ProblemaHttp(HttpStatus.CONFLICT,
                "urn:demo:problema:conductor-ocupado",
                "Conductor ocupado",
                "El conductor " + conductorId + " ya tiene una reserva para " + fechaHora + ".");
    }

    public static ProblemaHttp conductorBloqueado(Long conductorId) {
        return new ProblemaHttp(HttpStatus.CONFLICT,
                "urn:demo:problema:conductor-bloqueado",
                "Conductor bloqueado por otra transacción",
                "Otra reserva sobre el conductor " + conductorId + " sigue en curso. Reintentá en unos segundos.");
    }

    public static ProblemaHttp conductorInexistente(Long conductorId) {
        return new ProblemaHttp(HttpStatus.NOT_FOUND,
                "urn:demo:problema:conductor-inexistente",
                "Conductor inexistente",
                "No existe el conductor " + conductorId + ".");
    }

    // ---- Genéricos ------------------------------------------------------------

    public static ProblemaHttp bodyInvalido(String detalle) {
        return new ProblemaHttp(HttpStatus.BAD_REQUEST,
                "urn:demo:problema:body-invalido",
                "Body inválido",
                detalle);
    }

    public static ProblemaHttp conflictoDeConcurrencia() {
        return new ProblemaHttp(HttpStatus.CONFLICT,
                "urn:demo:problema:conflicto-concurrencia",
                "Conflicto de concurrencia",
                "La operación chocó con otra transacción concurrente. Reintentá.");
    }

    public HttpStatus status() {
        return status;
    }

    public String tipo() {
        return tipo;
    }

    public String titulo() {
        return titulo;
    }
}
