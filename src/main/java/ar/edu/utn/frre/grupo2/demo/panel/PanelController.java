package ar.edu.utn.frre.grupo2.demo.panel;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints de apoyo para el panel visual (src/main/resources/static/index.html).
 * Solo muestran o limpian el estado de la base: no forman parte de la API de reservas.
 */
@RestController
@RequestMapping("/demo")
public class PanelController {

    private final JdbcTemplate jdbc;

    public PanelController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Contenido actual de las tres tablas, para dibujarlas en el panel. */
    @GetMapping("/estado")
    public Map<String, List<Map<String, Object>>> estado() {
        Map<String, List<Map<String, Object>>> estado = new LinkedHashMap<>();
        estado.put("reservas", jdbc.queryForList("""
                SELECT id, cliente_id, conductor_id, to_char(fecha_hora, 'YYYY-MM-DD HH24:MI') AS fecha_hora
                FROM reservas ORDER BY id
                """));
        estado.put("reservasIngenuas", jdbc.queryForList("""
                SELECT id, cliente_id, conductor_id, to_char(fecha_hora, 'YYYY-MM-DD HH24:MI') AS fecha_hora
                FROM reservas_ingenuas ORDER BY id
                """));
        estado.put("claves", jdbc.queryForList("""
                SELECT clave, left(hash, 10) AS hash, estado, status_http
                FROM idempotency_records ORDER BY id
                """));
        return estado;
    }

    /** Vacía las tablas: cada caso del panel arranca con la base limpia. */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        jdbc.execute("TRUNCATE reservas, reservas_ingenuas, idempotency_records RESTART IDENTITY");
        return ResponseEntity.noContent().build();
    }
}
