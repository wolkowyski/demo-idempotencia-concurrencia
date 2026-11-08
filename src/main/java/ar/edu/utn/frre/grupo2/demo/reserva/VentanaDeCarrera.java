package ar.edu.utn.frre.grupo2.demo.reserva;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pausa artificial entre el "check" y el "act".
 * <p>
 * En producción esa ventana existe igual, pero dura microsegundos y la carrera aparece
 * de vez en cuando. Agrandarla (300 ms por defecto) hace que el caso 4 se reproduzca
 * siempre. La versión corregida tiene la misma pausa y sigue siendo correcta, porque
 * la pausa ocurre con el lock tomado.
 */
@Component
public class VentanaDeCarrera {

    private final long milisegundos;

    public VentanaDeCarrera(@Value("${demo.ventana-carrera-ms}") long milisegundos) {
        this.milisegundos = milisegundos;
    }

    public void esperar() {
        if (milisegundos <= 0) {
            return;
        }
        try {
            Thread.sleep(milisegundos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido durante la ventana de carrera", e);
        }
    }
}
