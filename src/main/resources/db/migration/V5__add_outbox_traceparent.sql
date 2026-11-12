-- Contexte de trace W3C (« 00-<traceId>-<spanId>-<flags> ») de la requête qui a écrit l'événement.
-- Le relais publie plus tard, sur un autre thread : sans cette colonne, la trace s'arrêterait au commit.
-- NULL pour les événements écrits hors trace (ou avant cette migration).
ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(55);
