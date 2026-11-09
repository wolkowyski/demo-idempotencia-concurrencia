package ar.edu.utn.frre.grupo2.demo.soporte;

import org.springframework.http.HttpStatus;

/**
 * Error que la API devuelve como application/problem+json (RFC 9457).
 * Los títulos de idempotencia son los del draft IETF Idempotency-Key, sección 2.7.
 */
public class ProblemaHttp extends RuntimeException {

    private final HttpStatus status;
    private final String titulo;

    private ProblemaHttp(HttpStatus status, String titulo, String detalle) {
        super(detalle);
        this.status = status;
        this.titulo = titulo;
    }

    public static ProblemaHttp claveFaltante() {
        return new ProblemaHttp(HttpStatus.BAD_REQUEST, "Idempotency-Key is missing",
                "Esta operación requiere el header Idempotency-Key.");
    }

    public static ProblemaHttp claveInvalida() {
        return new ProblemaHttp(HttpStatus.BAD_REQUEST, "Idempotency-Key is invalid",
                "La Idempotency-Key debe tener entre 8 y 255 caracteres. Se recomienda un UUID.");
    }

    public static ProblemaHttp claveReusada() {
        return new ProblemaHttp(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key is already used",
                "La Idempotency-Key ya se usó con un body distinto. Una operación nueva necesita una clave nueva.");
    }

    public static ProblemaHttp peticionEnCurso() {
        return new ProblemaHttp(HttpStatus.CONFLICT, "A request is outstanding for this Idempotency-Key",
                "Otra transacción tiene el recurso bloqueado hace demasiado tiempo. Reintentá en unos segundos.");
    }

    public static ProblemaHttp conductorOcupado() {
        return new ProblemaHttp(HttpStatus.CONFLICT, "Conductor ocupado",
                "El conductor ya tiene una reserva en ese horario.");
    }

    public static ProblemaHttp conductorInexistente(Long conductorId) {
        return new ProblemaHttp(HttpStatus.NOT_FOUND, "Conductor inexistente",
                conductorId == null ? "No existe el conductor indicado." : "No existe el conductor " + conductorId + ".");
    }

    public static ProblemaHttp bodyInvalido(String detalle) {
        return new ProblemaHttp(HttpStatus.BAD_REQUEST, "Body inválido", detalle);
    }

    public HttpStatus status() {
        return status;
    }

    public String titulo() {
        return titulo;
    }
}
