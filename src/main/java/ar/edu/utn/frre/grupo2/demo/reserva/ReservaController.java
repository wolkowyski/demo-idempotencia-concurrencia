package ar.edu.utn.frre.grupo2.demo.reserva;

import ar.edu.utn.frre.grupo2.demo.idempotencia.ClaveIdempotencia;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservaController {

    /** Header de respuesta (convención de Stripe) para distinguir un replay en la demo. */
    static final String HEADER_REPLAY = "Idempotent-Replayed";

    private final ReservaService corregida;
    private final ReservaIngenuaService ingenua;

    public ReservaController(ReservaService corregida, ReservaIngenuaService ingenua) {
        this.corregida = corregida;
        this.ingenua = ingenua;
    }

    /** Versión corregida: Idempotency-Key obligatoria + bloqueo pesimista + UNIQUE. */
    @PostMapping("/reservas")
    public ResponseEntity<String> crear(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReservaRequest request) {
        String clave = ClaveIdempotencia.leer(idempotencyKey);
        request.validar();
        return responder(corregida.crear(clave, request));
    }

    /** Versión ingenua: sin idempotencia, sin bloqueo, sin UNIQUE. Solo para el caso 4. */
    @PostMapping("/ingenuo/reservas")
    public ResponseEntity<String> crearIngenua(@RequestBody ReservaRequest request) {
        request.validar();
        return responder(ingenua.crear(request));
    }

    private ResponseEntity<String> responder(Resultado resultado) {
        return ResponseEntity.status(resultado.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HEADER_REPLAY, String.valueOf(resultado.replay()))
                .body(resultado.cuerpoJson());
    }
}
