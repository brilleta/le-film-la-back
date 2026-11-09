# le-film-la (back)

API de l'appli de recherche de films et séries "le-film-la".
Le front React est dans un repo séparé et appelle ce back via `/api` (proxy nginx en prod).
Déploiement : Docker sur Coolify.

## Stack
- Java 21, Spring Boot, Maven (wrapper)
- Spring AI (starter Anthropic), modèle Claude Haiku configurable
- Caffeine (cache), Bucket4j (rate limiting), Actuator (`/actuator/health` uniquement)
- PostgreSQL, Spring Data JPA, Flyway (`ddl-auto=validate` : le schéma ne change que par migration `db/migration/V*__*.sql`)
- Spring Security 7 : BCrypt, JWT HS256 (`spring-security-oauth2-jose`) dans un cookie httpOnly, CSRF en mode SPA
- Tests : Testcontainers PostgreSQL (`PostgresTestConfiguration` à importer dans tout `@SpringBootTest`) ; Docker requis pour `./mvnw verify`

## Commandes
- `./mvnw spring-boot:run` / `./mvnw test` / `./mvnw verify`
- `docker compose up --build` pour lancer en local

## Variables d'environnement
- `ANTHROPIC_API_KEY`, `TMDB_TOKEN`, `ANTHROPIC_MODEL` (optionnel)
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `JWT_SECRET` (base64, 32 octets min., sinon refus de démarrer), `JWT_TTL` (optionnel, défaut `PT24H`), `AUTH_COOKIE_SECURE` (optionnel, défaut `true` ; `false` seulement en dev HTTP)
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
- `config/` : cache, rate limiting, Spring AI, sécurité (`SecurityConfig`), JWT
- `exception/` : @ControllerAdvice et exceptions métier

## Contrat d'API (ne pas le casser : le front en dépend)
- `GET /api/movies/search?q=` : TMDB `/search/multi`, `language=fr-FR`
- `GET /api/movies/{type}/{id}` : détail (`movie` ou `tv`) avec credits et videos
- `GET /api/trending` : tendances de la semaine
- `POST /api/ai/search` `{ "description" }` → `{ "results": [{ "tmdbId", "type", "title", "year", "posterPath", "voteAverage", "reason" }] }`
- Erreurs : `{ "error", "message" }`
- Toute modification du contrat doit m'être signalée explicitement

### Auth
- `POST /api/auth/register` `{ "email", "username", "password" }` → 201 `{ "id", "email", "username" }` + cookie de session
  - email valide (stocké en minuscules), pseudo 3-30 caractères, mot de passe 8-72 caractères
  - 409 `EMAIL_TAKEN` / `USERNAME_TAKEN` (comparaison insensible à la casse)
- `POST /api/auth/login` `{ "email", "password" }` → 200 `{ "id", "email", "username" }` + cookie ; 401 `INVALID_CREDENTIALS` (même réponse que l'email existe ou non)
- `POST /api/auth/logout` → 204, cookie supprimé
- `GET /api/auth/me` → `{ "id", "email", "username" }` ou 401 `UNAUTHENTICATED`
- Cookie de session `auth_token` : JWT, httpOnly, Secure (configurable), SameSite=Lax, Path=/
- Login et register limités par IP (`rate-limit.auth.*`, 429 `RATE_LIMITED`)

### CSRF (à implémenter côté front, étape 2)
- Le back émet un cookie `XSRF-TOKEN` lisible par le JS dès la première requête (n'importe quel GET)
- Le front doit renvoyer sa valeur dans le header `X-XSRF-TOKEN` sur tout POST/PUT/PATCH/DELETE, sinon 403 `CSRF_INVALID`
- Le jeton change après register/login/logout : relire le cookie à chaque requête, ne pas le mettre en cache
- Seule exception : `POST /api/ai/search` (anonyme, sans effet de bord, le front actuel n'envoie pas le header)

### Règles d'accès
- Publics : `/api/movies/**`, `/api/trending`, `/api/ai/search`, `/api/auth/**`, `/actuator/health`
- Tout le reste exige le cookie de session : 401 `UNAUTHENTICATED` sinon, y compris sur une route inexistante
- Session stateless ; un cookie invalide ou expiré est ignoré sur les routes publiques
- Pas de CORS (même domaine via nginx)

## Recherche IA
1. Claude renvoie une sortie structurée (record Java) : 5 à 10 candidats (titre, année, type, raison en français)
2. Chaque candidat est vérifié sur TMDB ; les introuvables sont éliminés
3. Garde-fous : description ≤ 300 caractères, max tokens limité, rate limiting par IP (`rate-limit.ai.*`), cache par description normalisée

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
