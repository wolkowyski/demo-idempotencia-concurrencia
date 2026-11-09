package ar.edu.utn.frre.grupo2.demo.soporte;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TEMA 9 · TTL de las claves (draft IETF, sección 2.3): cada hora borra las
 * claves con más de 24 horas. Pasado ese tiempo, la misma clave se trata como nueva.
 */
@Component
public class PurgaIdempotencia {

    private static final Logger log = LoggerFactory.getLogger(PurgaIdempotencia.class);

    private final JdbcTemplate jdbc;
    private final int ttlHoras;

    public PurgaIdempotencia(JdbcTemplate jdbc, @Value("${demo.idempotencia.ttl-horas}") int ttlHoras) {
        this.jdbc = jdbc;
        this.ttlHoras = ttlHoras;
    }

    @Scheduled(fixedDelayString = "${demo.idempotencia.purga-cada-ms}",
            initialDelayString = "${demo.idempotencia.purga-cada-ms}")
    public void purgar() {
        int borradas = jdbc.update(
                "DELETE FROM idempotency_records WHERE creado_en < now() - make_interval(hours => ?)", ttlHoras);
        log.info("[purga] {} claves vencidas (TTL {} h)", borradas, ttlHoras);
    }
}
