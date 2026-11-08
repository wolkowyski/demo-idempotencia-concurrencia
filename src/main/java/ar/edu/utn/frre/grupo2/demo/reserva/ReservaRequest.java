package ar.edu.utn.frre.grupo2.demo.reserva;

import ar.edu.utn.frre.grupo2.demo.api.ProblemaHttp;

import java.time.LocalDateTime;

/**
 * Body de POST /reservas: { "clienteId", "conductorId", "fechaHora" }.
 */
public record ReservaRequest(Long clienteId, Long conductorId, LocalDateTime fechaHora) {

    public void validar() {
        if (clienteId == null || conductorId == null || fechaHora == null) {
            throw ProblemaHttp.bodyInvalido("Campos obligatorios: clienteId, conductorId y fechaHora.");
        }
    }

    /**
     * Forma canónica del body, base del fingerprint (Tema 9).
     * Dos JSON con distinto orden de campos o distintos espacios
     * producen la misma forma canónica y, por lo tanto, el mismo hash.
     */
    public String canonico() {
        return "clienteId=" + clienteId
                + "|conductorId=" + conductorId
                + "|fechaHora=" + fechaHora;
    }
}
