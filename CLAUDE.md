# le-film-la (back)

API de l'appli de recherche de films et séries "le-film-la".
Le front React est dans un repo séparé et appelle ce back via `/api` (proxy nginx en prod).
Déploiement : Render (web service Docker, plan free, région Frankfurt) + Postgres Neon ; voir `render.yaml`. Le back s'endort après 15 min sans trafic.

## Stack
- Java 21, Spring Boot, Maven (wrapper)
- Spring AI (starter Anthropic), modèle Claude Haiku configurable
- Caffeine (cache), Bucket4j (rate limiting), Actuator (`/actuator/health` uniquement)
- PostgreSQL, Spring Data JPA, Flyway (`ddl-auto=validate` : le schéma ne change que par migration `db/migration/V*__*.sql`)
- Spring Security 7 : BCrypt, JWT HS256 (`spring-security-oauth2-jose`) dans un cookie httpOnly, CSRF en mode SPA
- Kafka (`spring-boot-starter-kafka`) : KRaft local via docker compose (`localhost:9094` depuis l'hôte, `kafka:9092` dans le réseau compose), Kafka UI sur `http://localhost:8090` ; topics déclarés dans `config/KafkaTopics` et créés au démarrage ; un broker absent n'empêche pas le démarrage (`fail-fast=false`)
- Tests : Testcontainers PostgreSQL + Kafka (`TestcontainersConfiguration` à importer dans tout `@SpringBootTest`) ; Docker requis pour `./mvnw verify`

## Commandes
- `./mvnw spring-boot:run` / `./mvnw test` / `./mvnw verify`
- `docker compose up --build` pour lancer en local (`docker compose up -d postgres kafka kafka-ui` pour ne lancer que l'infra et le back via `spring-boot:run`)

## Variables d'environnement
- `ANTHROPIC_API_KEY`, `TMDB_TOKEN`, `ANTHROPIC_MODEL` (optionnel)
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `JWT_SECRET` (base64, 32 octets min., sinon refus de démarrer), `JWT_TTL` (optionnel, défaut `PT24H`), `AUTH_COOKIE_SECURE` (optionnel, défaut `true` ; `false` seulement en dev HTTP)
- `KAFKA_BOOTSTRAP_SERVERS` (défaut `localhost:9094`), `KAFKA_TOPIC_PARTITIONS` (défaut 3), `KAFKA_TOPIC_REPLICATION_FACTOR` (défaut 1 ; 3 sur Aiven)
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
- `events/` : événements métier (`AiAccessEvent`, `AiAccessEventPublisher`) ; `events/outbox/` : outbox transactionnel (`Outbox.append` exige une transaction, `OutboxRelay` publie après commit et toutes les `outbox.poll-interval`, `OutboxPublisher` envoie séquentiellement et s'arrête au premier échec pour garder l'ordre)
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
- Seule la dernière décision est conservée sur `users` ; chaque transition dépose un `AiAccessEvent` (REQUESTED/GRANTED/REJECTED/REVOKED, statuts avant/après, admin décideur, `schemaVersion`) dans `outbox_events`, publié sur `ai-access.events` clé par id utilisateur, headers `event-id` et `event-type`. Livraison au moins une fois : les consommateurs dédoublonnent sur `eventId`. Historique consommé côté back : étape suivante
- `POST /api/ai/access-request` → 200 `User` (statut `PENDING`) ; 409 si déjà `PENDING` ou `GRANTED`

### Admin (rôle ADMIN, sinon 403 `FORBIDDEN`)
- `GET /api/admin/users?aiAccess=&q=&page=0&size=20` (size ≤ 100) → `{ "content": [AdminUser], "page", "size", "totalElements", "totalPages" }`
  - `AdminUser` : `{ "id", "email", "username", "role", "aiAccess", "createdAt", "aiAccessRequestedAt", "aiAccessDecidedAt", "aiAccessDecidedBy": { "id", "username" } | null }`
  - `q` : recherche dans l'email ou le pseudo, insensible à la casse ; tri : demandes les plus récentes, puis inscriptions
- `POST /api/admin/users/{id}/ai-access/grant` | `reject` | `revoke` → 200 `AdminUser` ; 404 `NOT_FOUND` ; 409 transition invalide ou `CONCURRENT_MODIFICATION`
- Un admin ne peut ni révoquer ni refuser son propre accès : 403 `SELF_ACCESS_CHANGE`

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
