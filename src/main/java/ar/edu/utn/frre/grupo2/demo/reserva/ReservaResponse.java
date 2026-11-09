package ar.edu.utn.frre.grupo2.demo.reserva;

import java.time.LocalDateTime;

/** Respuesta de una reserva creada. Se guarda tal cual para devolverla en los reintentos. */
public record ReservaResponse(Long id, Long clienteId, Long conductorId, LocalDateTime fechaHora) {

    public ReservaResponse(Long id, ReservaRequest pedido) {
        this(id, pedido.clienteId(), pedido.conductorId(), pedido.fechaHora());
    }

    public String aJson() {
        return "{\"id\":" + id
                + ",\"clienteId\":" + clienteId
                + ",\"conductorId\":" + conductorId
                + ",\"fechaHora\":\"" + fechaHora + "\"}";
    }
}
