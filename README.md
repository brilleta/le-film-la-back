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
- Tests : JUnit 5, Testcontainers (PostgreSQL et Kafka)

## Démarrer en local

Prérequis : Docker, Java 21 (ou juste Docker pour tout lancer en conteneurs).

1. Copier `.env.example` en `.env` et renseigner au minimum `ANTHROPIC_API_KEY`, `TMDB_TOKEN`, `DB_USERNAME`, `DB_PASSWORD` et `JWT_SECRET` (`openssl rand -base64 32`). En local, mettre `AUTH_COOKIE_SECURE=false`.
2. Lancer l'infrastructure :

   ```sh
   docker compose up -d postgres kafka kafka-ui
   ```

3. Lancer le back :

   ```sh
   ./mvnw spring-boot:run
   ```

L'API répond sur `http://localhost:8080`, Kafka UI sur `http://localhost:8090`.

Pour tout lancer en conteneurs, back compris : `docker compose up --build`.

## Kafka

- Broker KRaft mono-nœud dans `docker-compose.yml`, sans ZooKeeper
- Depuis l'hôte : `localhost:9094` ; depuis le réseau compose : `kafka:9092`
- Les topics sont déclarés dans `config/KafkaTopics` et créés au démarrage
- Un broker absent n'empêche pas le back de démarrer

| Topic | Contenu | Clé |
|---|---|---|
| `ai-access.events` | Transitions d'accès à la recherche IA (demande, grant, reject, revoke) | id utilisateur |

Les événements passent par un **outbox transactionnel** : écrits dans la table `outbox_events` dans la même transaction que la donnée métier, puis relayés vers Kafka juste après le commit et par un balayage périodique. Un broker indisponible ne fait perdre aucun événement, il retarde seulement leur publication. Livraison au moins une fois : les consommateurs dédoublonnent sur l'`event-id` porté en header.

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
- `GET /api/admin/users`, `POST /api/admin/users/{id}/ai-access/grant|reject|revoke` (rôle ADMIN)

## Déploiement

Render (web service Docker, plan gratuit, région Frankfurt) et PostgreSQL Neon. Le blueprint est dans `render.yaml` ; les secrets sont saisis dans le dashboard Render, jamais versionnés. Le service s'endort après 15 minutes sans trafic.

---

This product uses the TMDB API but is not endorsed or certified by TMDB.
