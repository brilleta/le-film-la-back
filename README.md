# Mais Si ! Le film là ! (back)

> Décris-le mal, on le trouve quand même.

API Spring Boot de l'application de recherche de films et de séries. Le front React est dans un repo séparé (`le-film-la`) et appelle ce back via `/api`.

## Fonctionnalités

- Recherche classique et tendances via TMDB
- Recherche IA : une description approximative, Claude propose des candidats, chacun est vérifié sur TMDB avant d'être renvoyé
- Comptes utilisateurs (JWT en cookie httpOnly, CSRF en mode SPA), accès à la recherche IA accordé par un administrateur
- Événements Kafka : les transitions d'accès IA sont publiées sur un topic pour alimenter un historique et des tableaux de bord

## Stack

- Java 21, Spring Boot 4, Maven (wrapper)
- Spring AI (Anthropic), Spring Security, Spring Data JPA, Flyway, Spring Kafka
- PostgreSQL, Apache Kafka (KRaft)
- Caffeine (cache), Bucket4j (rate limiting), Actuator (`/actuator/health`)
- Observabilité : OpenTelemetry (via Micrometer), Grafana LGTM en local
- Tests : JUnit 5, Testcontainers (PostgreSQL et Kafka)

## Démarrer en local

Prérequis : Docker, Java 21 (ou juste Docker pour tout lancer en conteneurs).

1. Copier `.env.example` en `.env` et renseigner au minimum `ANTHROPIC_API_KEY`, `TMDB_TOKEN`, `DB_USERNAME`, `DB_PASSWORD` et `JWT_SECRET` (`openssl rand -base64 32`). En local, mettre `AUTH_COOKIE_SECURE=false`.
2. Lancer l'infrastructure :

   ```sh
   docker compose up -d postgres kafka kafka-ui lgtm
   ```

3. Lancer le back :

   ```sh
   ./mvnw spring-boot:run
   ```

L'API répond sur `http://localhost:8080`, Kafka UI sur `http://localhost:8090`, Grafana sur `http://localhost:3000`.

Pour tout lancer en conteneurs, back compris : `docker compose up --build`.

## Kafka

- Broker KRaft mono-nœud dans `docker-compose.yml`, sans ZooKeeper
- Depuis l'hôte : `localhost:9094` ; depuis le réseau compose : `kafka:9092`
- Les topics sont déclarés dans `config/KafkaTopics` et créés au démarrage
- Un broker absent n'empêche pas le back de démarrer

| Topic | Contenu | Clé |
|---|---|---|
| `ai-access.events` | Transitions d'accès à la recherche IA (demande, grant, reject, revoke) | id utilisateur |
| `ai-access.events.DLT` | Messages que le consommateur n'a pas pu traiter, avec la cause en headers | idem |

Les événements passent par un **outbox transactionnel** : écrits dans la table `outbox_events` dans la même transaction que la donnée métier, puis relayés vers Kafka juste après le commit et par un balayage périodique. Un broker indisponible ne fait perdre aucun événement, il retarde seulement leur publication. Livraison au moins une fois : les consommateurs dédoublonnent sur l'`event-id` porté en header.

Côté consommation, le back lui-même écoute `ai-access.events` et construit l'**historique des accès IA** (`ai_access_history`), exposé aux admins. L'insertion est idempotente (`INSERT … ON CONFLICT DO NOTHING` sur l'identifiant d'événement), les erreurs transitoires sont réessayées, et un message illisible part en Dead Letter Topic sans bloquer la partition.

## Observabilité

Chaque requête produit une **trace OpenTelemetry** : le span de la requête HTTP, avec en enfants les appels à TMDB et à Claude (durée, statut, erreur). Le `traceId` est ajouté à chaque ligne de log, ce qui permet de retrouver la trace complète à partir d'une erreur dans les logs.

- Instrumentation par Micrometer Observation (`spring-boot-starter-opentelemetry`), sans agent Java
- Export OTLP/HTTP, activé seulement si `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` est défini : sans collecteur, rien n'est envoyé
- En local, le conteneur `lgtm` (Grafana, Tempo, Loki, Prometheus) reçoit les traces : Grafana → *Explore* → *Tempo*
- Non tracés : les sondes `/actuator`, le flux SSE des stats, les tâches planifiées et les étapes internes de Spring Security
- Le contenu des prompts envoyés à Claude n'est jamais enregistré dans les traces

```sh
docker compose up -d postgres kafka kafka-ui lgtm
MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4318/v1/traces ./mvnw spring-boot:run
```

## Tests

```sh
./mvnw test      # tests unitaires et d'intégration
./mvnw verify    # idem, à lancer avant de pousser
```

Docker est requis : les tests qui chargent le contexte Spring démarrent un PostgreSQL et un Kafka jetables via Testcontainers.

## Variables d'environnement

| Variable | Rôle | Défaut |
|---|---|---|
| `ANTHROPIC_API_KEY` | Clé API Anthropic | obligatoire |
| `ANTHROPIC_MODEL` | Modèle Claude | `claude-haiku-5-5` |
| `TMDB_TOKEN` | Jeton TMDB (v4) | obligatoire |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL | obligatoires |
| `JWT_SECRET` | Clé de signature, base64, 32 octets min. | obligatoire |
| `JWT_TTL` | Durée de session (ISO-8601) | `PT24H` |
| `AUTH_COOKIE_SECURE` | Cookie Secure | `true` |
| `ADMIN_EMAIL` | Premier admin, promu au démarrage si aucun admin n'existe | vide |
| `KAFKA_BOOTSTRAP_SERVERS` | Brokers Kafka | `localhost:9094` |
| `KAFKA_TOPIC_PARTITIONS` | Partitions des topics créés | `3` |
| `KAFKA_TOPIC_REPLICATION_FACTOR` | Réplication des topics créés | `1` |

Le fichier `.env` est lu au démarrage et n'est jamais versionné.

## API

Voir le contrat détaillé dans `CLAUDE.md`. En résumé :

- `GET /api/movies/search?q=`, `GET /api/movies/{movie|tv}/{id}`, `GET /api/trending`
- `POST /api/ai/search` (connecté, accès IA accordé)
- `POST /api/auth/register|login|logout`, `GET /api/auth/me`
- `POST /api/ai/access-request`
- `GET /api/admin/users`, `POST /api/admin/users/{id}/ai-access/grant|reject|revoke`, `GET /api/admin/users/{id}/ai-access/history` (rôle ADMIN)

## Déploiement

Render (web service Docker, plan gratuit, région Frankfurt), PostgreSQL Neon et Kafka Aiven (plan free). Le blueprint est dans `render.yaml` ; les secrets sont saisis dans le dashboard Render, jamais versionnés. Le service s'endort après 15 minutes sans trafic : grâce à l'outbox, les événements produits juste avant la mise en veille sont publiés au réveil.

### Kafka sur Aiven

1. Créer un service Apache Kafka (plan free) dans le projet Aiven.
2. Activer SASL : Service settings → Advanced configuration → `kafka_authentication_methods.sasl` = true. Le « Service URI » affiche alors un hôte:port SASL.
3. Récupérer l'utilisateur et le mot de passe (onglet Users) et le certificat CA du projet (Overview → « CA certificate »).
4. Renseigner dans Render : `SPRING_PROFILES_ACTIVE=prod`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_USERNAME`, `KAFKA_PASSWORD`, `KAFKA_CA_CERT` (contenu PEM complet) et `KAFKA_TOPIC_REPLICATION_FACTOR` (nombre de brokers du plan, 1 sur le free).

Le profil `prod` (`application-prod.properties`) bascule le client en `SASL_SSL` + `SCRAM-SHA-256` et fait confiance au CA Aiven via un SSL bundle Spring Boot. Les topics sont créés au premier démarrage.

---

This product uses the TMDB API but is not endorsed or certified by TMDB.
