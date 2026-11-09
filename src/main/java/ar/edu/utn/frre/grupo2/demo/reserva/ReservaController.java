package ar.edu.utn.frre.grupo2.demo.reserva;

import ar.edu.utn.frre.grupo2.demo.soporte.ProblemaHttp;
import ar.edu.utn.frre.grupo2.demo.soporte.Resultado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los dos endpoints de la demo:
 *   POST /reservas          → versión corregida (casos 1, 2, 3 y 5)
 *   POST /ingenuo/reservas  → versión ingenua   (caso 4)
 */
@RestController
public class ReservaController {

    private static final Logger log = LoggerFactory.getLogger(ReservaController.class);

    private final ReservaService corregida;
    private final ReservaIngenuaService ingenua;

    public ReservaController(ReservaService corregida, ReservaIngenuaService ingenua) {
        this.corregida = corregida;
        this.ingenua = ingenua;
    }

    @PostMapping("/reservas")
    public ResponseEntity<String> reservar(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReservaRequest pedido) {

        // TEMA 9: en un POST la clave es obligatoria (draft IETF Idempotency-Key)
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ProblemaHttp.claveFaltante();                                 // → 400
        }
        String clave = idempotencyKey.strip().replace("\"", "");   // el draft la manda entre comillas
        if (clave.length() < 8 || clave.length() > 255) {
            throw ProblemaHttp.claveInvalida();                                 // → 400
        }
        pedido.validar();

        return responder(corregida.reservar(clave, pedido));
    }

    @PostMapping("/ingenuo/reservas")
    public ResponseEntity<String> reservarIngenuo(@RequestBody ReservaRequest pedido) {
        pedido.validar();
        return responder(ingenua.reservar(pedido));
    }

    /** "Idempotent-Replayed: true" avisa que la respuesta es la guardada (convención de Stripe). */
    private ResponseEntity<String> responder(Resultado resultado) {
        log.info("→ {}{}", resultado.status(), resultado.replay() ? " replay (respuesta guardada)" : " creó la reserva");
        return ResponseEntity.status(resultado.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", String.valueOf(resultado.replay()))
                .body(resultado.cuerpoJson());
    }
}
