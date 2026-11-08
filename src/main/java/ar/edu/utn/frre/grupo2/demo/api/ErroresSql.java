package ar.edu.utn.frre.grupo2.demo.api;

import java.sql.SQLException;

/**
 * Clasifica errores de PostgreSQL por su SQLState, sin depender de cómo
 * Spring traduzca cada código a una subclase de DataAccessException.
 */
public final class ErroresSql {

    /** lock_not_available: se superó lock_timeout esperando un lock. */
    private static final String LOCK_TIMEOUT = "55P03";
    /** unique_violation. */
    private static final String VIOLACION_UNIQUE = "23505";
    /** deadlock_detected. */
    private static final String DEADLOCK = "40P01";
    /** serialization_failure ("could not serialize access ..."). */
    private static final String FALLA_SERIALIZACION = "40001";

    private ErroresSql() {
    }

    public static boolean esLockTimeout(Throwable t) {
        return LOCK_TIMEOUT.equals(sqlState(t));
    }

    public static boolean esViolacionUnique(Throwable t) {
        return VIOLACION_UNIQUE.equals(sqlState(t));
    }

    public static boolean esConflictoDeConcurrencia(Throwable t) {
        String estado = sqlState(t);
        return LOCK_TIMEOUT.equals(estado) || DEADLOCK.equals(estado) || FALLA_SERIALIZACION.equals(estado);
    }

    private static String sqlState(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            t = t.getCause();
        }
        return null;
    }
}
