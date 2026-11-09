package ar.edu.utn.frre.grupo2.demo.soporte;

/**
 * Pausa artificial entre el "check" y el "act" (300 ms por defecto).
 * En un sistema real esa ventana dura microsegundos y la carrera aparece de vez en cuando;
 * agrandarla hace que el caso 4 se reproduzca siempre en una notebook.
 */
public final class Pausa {

    private Pausa() {
    }

    public static void de(long milisegundos) {
        if (milisegundos <= 0) {
            return;
        }
        try {
            Thread.sleep(milisegundos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
