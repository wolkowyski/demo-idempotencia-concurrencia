package ar.edu.utn.frre.grupo2.demo.soporte;

/** Respuesta HTTP ya armada; replay = true cuando es la respuesta guardada de un reintento. */
public record Resultado(int status, String cuerpoJson, boolean replay) {

    public static Resultado nuevo(int status, String cuerpoJson) {
        return new Resultado(status, cuerpoJson, false);
    }

    public static Resultado replay(int status, String cuerpoJson) {
        return new Resultado(status, cuerpoJson, true);
    }
}
