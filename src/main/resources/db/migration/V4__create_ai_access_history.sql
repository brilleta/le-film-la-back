-- Historique des transitions d'accès IA, alimenté par le consommateur Kafka de ai-access.events.
-- Pas de clé étrangère : l'audit survit à la suppression d'un compte ou au renommage d'un admin.
CREATE TABLE ai_access_history (
    id                  BIGSERIAL   PRIMARY KEY,
    event_id            UUID        NOT NULL UNIQUE,  -- idempotence : un événement rejoué n'est pas réinséré
    user_id             UUID        NOT NULL,
    event_type          VARCHAR(20) NOT NULL
        CONSTRAINT ai_access_history_event_type_check CHECK (event_type IN ('REQUESTED', 'GRANTED', 'REJECTED', 'REVOKED')),
    previous_status     VARCHAR(10) NOT NULL,
    new_status          VARCHAR(10) NOT NULL,
    decided_by_id       UUID,
    decided_by_username VARCHAR(30),
    occurred_at         TIMESTAMPTZ NOT NULL,         -- horodatage métier, porté par l'événement
    recorded_at         TIMESTAMPTZ NOT NULL          -- horodatage de consommation
);

CREATE INDEX ai_access_history_user_idx ON ai_access_history (user_id, occurred_at DESC);
