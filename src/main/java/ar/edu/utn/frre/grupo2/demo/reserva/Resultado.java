package ar.edu.utn.frre.grupo2.demo.reserva;

/**
 * Respuesta HTTP ya serializada: así un replay devuelve exactamente lo mismo que la primera vez.
 */
public record Resultado(int status, String cuerpoJson, boolean replay) {

    public static Resultado nuevo(int status, String cuerpoJson) {
        return new Resultado(status, cuerpoJson, false);
    }

    public static Resultado replay(int status, String cuerpoJson) {
        return new Resultado(status, cuerpoJson, true);
    }
}
