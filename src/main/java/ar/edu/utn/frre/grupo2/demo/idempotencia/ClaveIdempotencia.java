package ar.edu.utn.frre.grupo2.demo.idempotencia;

import ar.edu.utn.frre.grupo2.demo.api.ProblemaHttp;

import java.util.regex.Pattern;

/**
 * Lee y valida el header Idempotency-Key.
 * <p>
 * El draft IETF define el valor como un String de Structured Fields (RFC 9651),
 * o sea entre comillas: {@code Idempotency-Key: "8e03978e-..."}. Se aceptan también
 * valores sin comillas, como hacen Stripe y otras APIs.
 * <p>
 * Validar el formato antes de usar la clave es una recomendación de seguridad del draft
 * (sección 5): evita inyecciones y claves de baja entropía.
 */
public final class ClaveIdempotencia {

    private static final Pattern FORMATO = Pattern.compile("^[A-Za-z0-9._:-]{8,255}$");

    private ClaveIdempotencia() {
    }

    public static String leer(String header) {
        if (header == null || header.isBlank()) {
            throw ProblemaHttp.claveFaltante();
        }
        String clave = header.strip();
        if (clave.length() >= 2 && clave.startsWith("\"") && clave.endsWith("\"")) {
            clave = clave.substring(1, clave.length() - 1);
        }
        if (!FORMATO.matcher(clave).matches()) {
            throw ProblemaHttp.claveInvalida();
        }
        return clave;
    }
}
