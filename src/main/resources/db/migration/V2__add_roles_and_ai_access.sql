ALTER TABLE users
    ADD COLUMN role                   VARCHAR(10) NOT NULL DEFAULT 'USER'
        CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN')),
    ADD COLUMN ai_access              VARCHAR(10) NOT NULL DEFAULT 'NONE'
        CONSTRAINT users_ai_access_check CHECK (ai_access IN ('NONE', 'PENDING', 'GRANTED', 'REJECTED')),
    ADD COLUMN ai_access_requested_at TIMESTAMPTZ,
    ADD COLUMN ai_access_decided_at   TIMESTAMPTZ,
    -- Admin ayant pris la dernière décision ; NULL si décision système (ADMIN_EMAIL) ou admin supprimé
    ADD COLUMN ai_access_decided_by   UUID REFERENCES users (id) ON DELETE SET NULL,
    -- Verrou optimiste : deux admins qui décident en même temps ne s'écrasent pas
    ADD COLUMN version                BIGINT      NOT NULL DEFAULT 0;

CREATE INDEX users_ai_access_idx ON users (ai_access);
CREATE INDEX users_role_idx ON users (role);
