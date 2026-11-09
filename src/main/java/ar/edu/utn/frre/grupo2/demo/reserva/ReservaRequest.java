package ar.edu.utn.frre.grupo2.demo.reserva;

import ar.edu.utn.frre.grupo2.demo.soporte.ProblemaHttp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/** Body de POST /reservas: { "clienteId", "conductorId", "fechaHora" }. */
public record ReservaRequest(Long clienteId, Long conductorId, LocalDateTime fechaHora) {

    public void validar() {
        if (clienteId == null || conductorId == null || fechaHora == null) {
            throw ProblemaHttp.bodyInvalido("Campos obligatorios: clienteId, conductorId y fechaHora.");
        }
    }

    /**
     * TEMA 9 · Fingerprint: SHA-256 de los datos del pedido.
     * Se guarda junto a la clave para detectar que la reusaron con otro body (caso 3).
     */
    public String huella() {
        String datos = clienteId + "|" + conductorId + "|" + fechaHora;
        try {
            byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(datos.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
