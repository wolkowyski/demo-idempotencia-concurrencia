-- =============================================================
-- V1: esquema de la demo (Tema 9 + Tema 10)
-- =============================================================

CREATE TABLE conductores (
    id      BIGINT       PRIMARY KEY,
    nombre  VARCHAR(100) NOT NULL
);

-- -------------------------------------------------------------
-- Versión CORREGIDA (POST /reservas).
-- La restricción UNIQUE es la red de seguridad del caso 5:
-- aunque fallara la lógica de bloqueo, la base rechaza el duplicado.
-- -------------------------------------------------------------
CREATE TABLE reservas (
    id            BIGSERIAL   PRIMARY KEY,
    cliente_id    BIGINT      NOT NULL,
    conductor_id  BIGINT      NOT NULL REFERENCES conductores(id),
    fecha_hora    TIMESTAMP   NOT NULL,
    creada_en     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_reserva_conductor_franja UNIQUE (conductor_id, fecha_hora)
);

-- -------------------------------------------------------------
-- Versión INGENUA (POST /ingenuo/reservas).
-- Misma forma, a propósito SIN restricción UNIQUE (caso 4).
-- -------------------------------------------------------------
CREATE TABLE reservas_ingenuas (
    id            BIGSERIAL   PRIMARY KEY,
    cliente_id    BIGINT      NOT NULL,
    conductor_id  BIGINT      NOT NULL REFERENCES conductores(id),
    fecha_hora    TIMESTAMP   NOT NULL,
    creada_en     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- -------------------------------------------------------------
-- Tema 9: registro de claves de idempotencia.
--   clave       valor del header Idempotency-Key (UNIQUE)
--   hash        SHA-256 (hex) del body canónico: el "fingerprint"
--   estado      EN_CURSO mientras la transacción no confirma
--   status_http y respuesta: lo que se devuelve en un replay
--   creado_en   base del TTL (ver PurgaIdempotencia)
-- -------------------------------------------------------------
CREATE TABLE idempotency_records (
    id           BIGSERIAL    PRIMARY KEY,
    clave        VARCHAR(255) NOT NULL,
    hash         CHAR(64)     NOT NULL,
    estado       VARCHAR(16)  NOT NULL CHECK (estado IN ('EN_CURSO', 'COMPLETADA')),
    status_http  INTEGER,
    respuesta    TEXT,
    creado_en    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_idempotency_clave UNIQUE (clave)
);

CREATE INDEX idx_idempotency_creado_en ON idempotency_records (creado_en);
