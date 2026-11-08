package ar.edu.utn.frre.grupo2.demo.idempotencia;

/**
 * Fila de idempotency_records.
 */
public record RegistroIdempotencia(String clave, String hash, String estado, Integer statusHttp, String respuesta) {

    public boolean completada() {
        return "COMPLETADA".equals(estado);
    }
}
