package ar.edu.utn.frre.grupo2.demo.idempotencia;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprint de la petición (draft IETF, sección 2.4): SHA-256 del body canónico, en hex.
 */
public final class Huella {

    private Huella() {
    }

    public static String sha256(String texto) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
