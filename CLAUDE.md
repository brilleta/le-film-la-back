# le-film-la (back)

API de l'appli de recherche de films et séries "le-film-la".
Le front React est dans un repo séparé et appelle ce back via `/api` (proxy nginx en prod).
Déploiement : Render (web service Docker, plan free, région Frankfurt) + Postgres Neon + Kafka Aiven (free, SASL_SSL via le profil `prod`, `application-prod.properties`) ; voir `render.yaml`. Le back s'endort après 15 min sans trafic.

## Stack
- Java 21, Spring Boot, Maven (wrapper)
- Spring AI (starter Anthropic), modèle Claude Haiku configurable
- Caffeine (cache), Bucket4j (rate limiting), Actuator (`/actuator/health` uniquement)
- Observabilité : `spring-boot-starter-opentelemetry` (Micrometer Observation + pont OTel). Spans : requêtes HTTP entrantes, appels TMDB (`RestClient.Builder` de Boot, module `spring-boot-starter-restclient`), appels Claude (observations Spring AI, sans le contenu des prompts). `ObservabilityConfig` écarte `/actuator/**`, le flux SSE des stats, les `@Scheduled` et les observations de Spring Security. Export OTLP **seulement si** `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` est défini (compose : conteneur `lgtm`, Grafana sur `http://localhost:3000`) ; échantillonnage `TRACING_SAMPLING_PROBABILITY` (défaut 1.0). traceId/spanId dans chaque ligne de log. Traces à travers Kafka : `spring.kafka.template/listener.observation-enabled` (header `traceparent`) ; l'outbox stocke le `traceparent` de la requête (colonne `outbox_events.traceparent`, V5) et `OutboxPublisher` le restaure dans un span `outbox publish` (`observability/TraceParents`) ; `SearchEventPublisher` capture le contexte (`ContextSnapshotFactory`) avant de passer la main à son thread ; Kafka Streams n'est pas tracé. Logs : appender `OpenTelemetryAppender` (`logback-spring.xml`, installé par `ObservabilityConfig`), export OTLP vers Loki seulement si `MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT` est défini. Métriques OTLP coupées par défaut (`MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true` + `MANAGEMENT_OTLP_METRICS_EXPORT_URL` pour les activer), histogrammes exponentiels ; métriques maison : `outbox.events.pending`, `outbox.events.oldest.pending.age` (`ObservabilityConfig`), `outbox.events.sent{outcome}` (`OutboxPublisher`), `search.events.dropped{reason}` (`SearchEventPublisher`) ; lag et tokens Claude fournis par Spring Kafka et Spring AI. Dashboard versionné `observability/grafana/dashboards/le-film-la-back.json`, monté dans `lgtm` par compose (en PromQL, histogrammes natifs : `histogram_quantile(0.95, rate(x_milliseconds[…]))`, `histogram_count(...)`). Tests : `@AutoConfigureTracing` + `support/InMemorySpans` (voir `TracingIntegrationTest`, `KafkaTracingIntegrationTest`)
- PostgreSQL, Spring Data JPA, Flyway (`ddl-auto=validate` : le schéma ne change que par migration `db/migration/V*__*.sql`)
- Spring Security 7 : BCrypt, JWT HS256 (`spring-security-oauth2-jose`) dans un cookie httpOnly, CSRF en mode SPA
- Kafka (`spring-boot-starter-kafka`) : KRaft local via docker compose (`localhost:9094` depuis l'hôte, `kafka:9092` dans le réseau compose), Kafka UI sur `http://localhost:8090` ; topics déclarés dans `config/KafkaTopics` et créés au démarrage ; un broker absent n'empêche pas le démarrage (`fail-fast=false`)
- Tests : Testcontainers PostgreSQL + Kafka (`TestcontainersConfiguration` à importer dans tout `@SpringBootTest`) ; Docker requis pour `./mvnw verify`

## Commandes
- `./mvnw spring-boot:run` / `./mvnw test` / `./mvnw verify`
- `docker compose up --build` pour lancer en local (`docker compose up -d postgres kafka kafka-ui lgtm` pour ne lancer que l'infra et le back via `spring-boot:run` ; ajouter alors `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4318/v1/traces` dans l'environnement pour voir les traces)

## Variables d'environnement
- `ANTHROPIC_API_KEY`, `TMDB_TOKEN`, `ANTHROPIC_MODEL` (optionnel)
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `JWT_SECRET` (base64, 32 octets min., sinon refus de démarrer), `JWT_TTL` (optionnel, défaut `PT24H`), `AUTH_COOKIE_SECURE` (optionnel, défaut `true` ; `false` seulement en dev HTTP)
- `KAFKA_BOOTSTRAP_SERVERS` (défaut `localhost:9094`), `KAFKA_TOPIC_PARTITIONS` (défaut 3), `KAFKA_TOPIC_REPLICATION_FACTOR` (défaut 1 ; = nombre de brokers Aiven)
- Profil `prod` seulement : `SPRING_PROFILES_ACTIVE=prod`, `KAFKA_USERNAME`, `KAFKA_PASSWORD`, `KAFKA_CA_CERT` (PEM inline)
- `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` (optionnel, URL OTLP/HTTP des traces, `…/v1/traces`), `MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT` (`…/v1/logs`), `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` + `MANAGEMENT_OTLP_METRICS_EXPORT_URL` (`…/v1/metrics`), `TRACING_SAMPLING_PROBABILITY` (optionnel, défaut 1.0) ; à passer en vraies variables d'environnement, pas dans `.env`
- `ADMIN_EMAIL` (optionnel) : au démarrage, ce compte passe ADMIN + accès IA, **seulement si aucun ADMIN n'existe encore** (pas de promotion à l'inscription : les emails ne sont pas vérifiés)
- Les tests importent aussi `.env` : toute valeur qui influe sur un test doit être fixée dans `src/test/resources/config/application.properties`
- Jamais en dur, jamais dans les logs
- `.env.example` à tenir à jour, `.env` non versionné

## Structure
<!-- À ajuster selon l'arborescence réelle -->
- `controller/` : endpoints REST, validation des entrées
- `service/` : logique métier (recherche IA, vérification TMDB)
- `client/` : appels TMDB et Claude
- `entity/`, `repository/` : entités JPA et repositories Spring Data
- `security/` : filtre JWT cookie, filtre CSRF, cookies d'auth, réponses 401/403
- `dto/` : records Java
- `config/` : cache, rate limiting, Spring AI, sécurité (`SecurityConfig`), JWT, topics Kafka (`KafkaTopics`), planification (`SchedulingConfig`, bean `Clock`)
- `events/` : événements métier (`AiAccessEvent`, `AiAccessEventPublisher`), listener `AiAccessEventsListener` (groupe `le-film-la-back.ai-access-history`) ; `events/history/` : table `ai_access_history` alimentée par le listener, insertion idempotente `INSERT … ON CONFLICT (event_id) DO NOTHING` ; `config/KafkaConsumerConfig` : `DefaultErrorHandler`, 2 nouvelles tentatives à 1 s puis DLT `<topic>.DLT` (cause en headers `kafka_dlt-*`), `InvalidEventException` non réessayée ; `events/outbox/` : outbox transactionnel (`Outbox.append` exige une transaction, `OutboxRelay` publie après commit et toutes les `outbox.poll-interval`, `OutboxPublisher` envoie séquentiellement et s'arrête au premier échec pour garder l'ordre)
- `events/search/` : `SearchTracker` (appelé par les controllers, hors du proxy `@Cacheable` : latence, hit de cache via les mêmes clés que les services, résultats, succès/erreur) publie un `SearchEvent` anonyme (requête normalisée tronquée à 100 caractères, 5 titres max) sur `search.events`, clé = mode `CLASSIC`/`AI` ; `SearchEventPublisher` : fire and forget sans outbox (mesures, pas des faits métier), thread dédié + file bornée (`search-events.queue-capacity`) : la recherche n'attend jamais Kafka
- `stats/` : Kafka Streams (`@EnableKafkaStreams`, application id `le-film-la-back.search-stats`) ; `SearchStatsTopology` agrège `search.events` par mode (`groupByKey`, pas de repartition) en fenêtres d'une minute (grâce 10 s) dans le store `search-stats` **en mémoire et sans changelog** (aucun topic interne, pas de RocksDB : image alpine) → stats remises à zéro à chaque redémarrage, rétention 1 h ; `SearchStatsService` recompose les N dernières minutes par interactive query (`SearchStatsStore`, une seule instance) ; flux pas prêt → 503 `STATS_UNAVAILABLE` ; `SearchStatsBroadcaster` : SSE, un snapshot par tick (`stats.stream.interval`, 2 s, qui sert aussi de heartbeat) et par profondeur demandée quel que soit le nombre d'abonnés, timeout `stats.stream.timeout` (15 min) puis reconnexion par `EventSource`, flux fermés au `@PreDestroy` ; `SecurityConfig` laisse passer le dispatch `ASYNC` (fin de flux, requête déjà autorisée) ; tests de topologie avec `TopologyTestDriver` ; en test, `state-dir` unique par contexte
- `exception/` : @ControllerAdvice et exceptions métier

## Contrat d'API (ne pas le casser : le front en dépend)
- `GET /api/movies/search?q=` : TMDB `/search/multi`, `language=fr-FR`
- `GET /api/movies/{type}/{id}` : détail (`movie` ou `tv`) avec credits et videos
- `GET /api/trending` : tendances de la semaine
- `POST /api/ai/search` `{ "description" }` → `{ "results": [{ "tmdbId", "type", "title", "year", "posterPath", "voteAverage", "reason" }] }`
  - connecté (401 `UNAUTHENTICATED`) avec accès IA `GRANTED` (403 `AI_ACCESS_REQUIRED`), header CSRF obligatoire
- Erreurs : `{ "error", "message" }`
- Toute modification du contrat doit m'être signalée explicitement

### Auth
- Objet utilisateur (`User`) : `{ "id", "email", "username", "role": "USER"|"ADMIN", "aiAccess": "NONE"|"PENDING"|"GRANTED"|"REJECTED" }`
- `POST /api/auth/register` `{ "email", "username", "password" }` → 201 `User` + cookie de session
  - email valide (stocké en minuscules), pseudo 3-30 caractères, mot de passe 8-72 caractères
  - 409 `EMAIL_TAKEN` / `USERNAME_TAKEN` (comparaison insensible à la casse)
- `POST /api/auth/login` `{ "email", "password" }` → 200 `User` + cookie ; 401 `INVALID_CREDENTIALS` (même réponse que l'email existe ou non)
- `POST /api/auth/logout` → 204, cookie supprimé
- `GET /api/auth/me` → `User` ou 401 `UNAUTHENTICATED`
- Cookie de session `auth_token` : JWT, httpOnly, Secure (configurable), SameSite=Lax, Path=/
- Login et register limités par IP (`rate-limit.auth.*`, 429 `RATE_LIMITED`)

### Accès à la recherche IA
- Transitions : `NONE`/`REJECTED` → `PENDING` (demande) ; `NONE`/`PENDING`/`REJECTED` → `GRANTED` (grant, idempotent si déjà `GRANTED`) ; `PENDING` → `REJECTED` (reject) ; `GRANTED` → `NONE` (revoke)
- Transition invalide : 409 `INVALID_AI_ACCESS_TRANSITION` avec message explicite
- Seule la dernière décision est conservée sur `users` ; chaque transition dépose un `AiAccessEvent` (REQUESTED/GRANTED/REJECTED/REVOKED, statuts avant/après, admin décideur, `schemaVersion`) dans `outbox_events`, publié sur `ai-access.events` clé par id utilisateur, headers `event-id` et `event-type`. Livraison au moins une fois : les consommateurs dédoublonnent sur `eventId`. Le listener du back alimente `ai_access_history` (cohérence à terme : quelques ms après la réponse HTTP)
- `POST /api/ai/access-request` → 200 `User` (statut `PENDING`) ; 409 si déjà `PENDING` ou `GRANTED`

### Admin (rôle ADMIN, sinon 403 `FORBIDDEN`)
- `GET /api/admin/users?aiAccess=&q=&page=0&size=20` (size ≤ 100) → `{ "content": [AdminUser], "page", "size", "totalElements", "totalPages" }`
  - `AdminUser` : `{ "id", "email", "username", "role", "aiAccess", "createdAt", "aiAccessRequestedAt", "aiAccessDecidedAt", "aiAccessDecidedBy": { "id", "username" } | null }`
  - `q` : recherche dans l'email ou le pseudo, insensible à la casse ; tri : demandes les plus récentes, puis inscriptions
- `POST /api/admin/users/{id}/ai-access/grant` | `reject` | `revoke` → 200 `AdminUser` ; 404 `NOT_FOUND` ; 409 transition invalide ou `CONCURRENT_MODIFICATION`
- `GET /api/admin/users/{id}/ai-access/history` → `[{ "eventId", "type": "REQUESTED"|"GRANTED"|"REJECTED"|"REVOKED", "previousStatus", "newStatus", "decidedBy": { "id", "username" } | null, "occurredAt" }]`, le plus récent d'abord ; 404 `NOT_FOUND`. Alimenté par Kafka : juste après une action, le front doit refetch (quelques ms de retard possibles)
- Un admin ne peut ni révoquer ni refuser son propre accès : 403 `SELF_ACCESS_CHANGE`
- `GET /api/admin/stats?minutes=15` (1 à 60, sinon 400) → `{ "generatedAt", "windowMinutes", "modes": { "CLASSIC"|"AI": { "searches", "errors", "errorRate", "cacheHitRate", "avgLatencyMs", "maxLatencyMs", "topQueries": [{ "value", "count" }], "topTitles": [...] } }, "timeline": [{ "start", "classic", "ai" }] }` ; taux entre 0 et 1, une entrée de `timeline` par minute (minute en cours incluse, zéros compris), top 10 ; 503 `STATS_UNAVAILABLE` au démarrage ou pendant un rééquilibrage. Remis à zéro à chaque redémarrage du back
- `GET /api/admin/stats/stream?minutes=15` (`text/event-stream`, à consommer avec `EventSource`, cookie de session envoyé automatiquement) : événement `stats` (même JSON que ci-dessus) immédiatement puis toutes les 2 s ; événement `unavailable` (`{ "error": "STATS_UNAVAILABLE", "message" }`) tant que le flux Kafka Streams n'est pas prêt, le flux continue ; `retry: 5000` dans le premier événement ; header `X-Accel-Buffering: no` (nginx ne met pas en tampon) ; fermé par le serveur après 15 min, le navigateur se reconnecte seul

### CSRF
- Le back émet un cookie `XSRF-TOKEN` lisible par le JS dès la première requête (n'importe quel GET)
- Le front doit renvoyer sa valeur dans le header `X-XSRF-TOKEN` sur tout POST/PUT/PATCH/DELETE, sinon 403 `CSRF_INVALID`
- Le jeton change après register/login/logout : relire le cookie à chaque requête, ne pas le mettre en cache
- Aucune exception, `POST /api/ai/search` compris

### Règles d'accès
- Publics : `/api/movies/**`, `/api/trending`, `/api/auth/**`, `/actuator/health`
- `/api/admin/**` : rôle ADMIN
- `/api/ai/search` : connecté + accès `GRANTED` (vérifié par `AiAccessInterceptor`, avant le cache et le rate limiting)
- Tout le reste exige le cookie de session : 401 `UNAUTHENTICATED` sinon, y compris sur une route inexistante
- Session stateless ; le filtre JWT relit l'utilisateur en base à chaque requête (rôle, accès IA, compte supprimé) : un retrait est immédiat
- Un cookie invalide ou expiré est ignoré sur les routes publiques
- Pas de CORS (même domaine via nginx)

## Recherche IA
1. Claude renvoie une sortie structurée (record Java) : 5 à 10 candidats (titre, année, type, raison en français)
2. Chaque candidat est vérifié sur TMDB ; les introuvables sont éliminés
3. Garde-fous : description ≤ 300 caractères, max tokens limité, rate limiting par utilisateur (`rate-limit.ai.*`), cache par description normalisée

## Conventions
- Records pour les DTO, injection par constructeur, pas de logique dans les controllers
- Pas de `@Autowired` sur les champs, pas de `Optional` en paramètre
- Toute nouvelle logique de service accompagnée d'un test unitaire (Claude et TMDB mockés)

## Façon de travailler (économiser le contexte)
- Ne lire que les fichiers nécessaires à la tâche ; pas d'exploration globale sans raison
- Ne jamais ouvrir `target/`, `.mvn/` ni les logs complets
- Modifications ciblées, pas de réécriture de fichier entier pour un petit changement
- Pas de refactor ni de fonctionnalité non demandés ; proposer plutôt que faire
- Réponses courtes : résumé des changements, sans recopier le code
- Lancer `./mvnw verify` une fois à la fin d'une étape, pas après chaque micro-modif
- Si une demande est ambiguë, poser une question plutôt que deviner
