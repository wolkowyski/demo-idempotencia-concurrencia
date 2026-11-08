package ar.edu.utn.frre.grupo2.demo.idempotencia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TTL de las claves (draft IETF, sección 2.3): la política se publica en el README
 * y se aplica con esta purga periódica.
 * <p>
 * Con varias réplicas, cada instancia corre la purga; no es un problema porque
 * el DELETE es idempotente.
 */
@Component
public class PurgaIdempotencia {

    private static final Logger log = LoggerFactory.getLogger(PurgaIdempotencia.class);

    private final IdempotenciaRepository repositorio;
    private final int ttlHoras;

    public PurgaIdempotencia(IdempotenciaRepository repositorio,
                             @Value("${demo.idempotencia.ttl-horas}") int ttlHoras) {
        this.repositorio = repositorio;
        this.ttlHoras = ttlHoras;
    }

    @Scheduled(fixedDelayString = "${demo.idempotencia.purga-cada-ms}",
            initialDelayString = "${demo.idempotencia.purga-cada-ms}")
    public void purgar() {
        int borradas = repositorio.purgarVencidas(ttlHoras);
        log.info("[purga] {} claves de idempotencia vencidas (TTL {} h)", borradas, ttlHoras);
    }
}
