-- Outbox transactionnel : les événements sont écrits dans la même transaction que la donnée métier,
-- puis relayés vers Kafka par un processus séparé. Un événement non publié y reste jusqu'au succès.
CREATE TABLE outbox_events (
    id           BIGSERIAL    PRIMARY KEY,                -- ordre de publication
    event_id     UUID         NOT NULL UNIQUE,            -- identifiant métier, sert à dédoublonner côté consommateur
    topic        VARCHAR(100) NOT NULL,
    message_key  VARCHAR(100) NOT NULL,                   -- clé Kafka : id utilisateur, garantit l'ordre par compte
    event_type   VARCHAR(50)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT
);

-- Le relais ne lit que les événements en attente, dans l'ordre d'insertion
CREATE INDEX outbox_events_pending_idx ON outbox_events (id) WHERE published_at IS NULL;
