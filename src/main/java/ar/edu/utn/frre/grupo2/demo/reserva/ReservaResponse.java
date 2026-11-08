package ar.edu.utn.frre.grupo2.demo.reserva;

import java.time.LocalDateTime;

public record ReservaResponse(Long id, Long clienteId, Long conductorId, LocalDateTime fechaHora) {
}
