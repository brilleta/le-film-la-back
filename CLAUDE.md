# le-film-la (back)

API de l'appli de recherche de films et séries "le-film-la".
Le front React est dans un repo séparé et appelle ce back via `/api` (proxy nginx en prod).
Déploiement : Docker sur Coolify.

## Stack
- Java 21, Spring Boot, Maven (wrapper)
- Spring AI (starter Anthropic), modèle Claude Haiku configurable
- Caffeine (cache), Bucket4j (rate limiting), Actuator (`/actuator/health` uniquement)

## Commandes
- `./mvnw spring-boot:run` / `./mvnw test` / `./mvnw verify`
- `docker compose up --build` pour lancer en local

## Variables d'environnement
- `ANTHROPIC_API_KEY`, `TMDB_TOKEN`, `ANTHROPIC_MODEL` (optionnel). Jamais en dur, jamais dans les logs
- `.env.example` à tenir à jour, `.env` non versionné

## Structure
<!-- À ajuster selon l'arborescence réelle -->
- `controller/` : endpoints REST, validation des entrées
- `service/` : logique métier (recherche IA, vérification TMDB)
- `client/` : appels TMDB et Claude
- `dto/` : records Java
- `config/` : cache, rate limiting, Spring AI
- `exception/` : @ControllerAdvice et exceptions métier

## Contrat d'API (ne pas le casser : le front en dépend)
- `GET /api/movies/search?q=` : TMDB `/search/multi`, `language=fr-FR`
- `GET /api/movies/{type}/{id}` : détail (`movie` ou `tv`) avec credits et videos
- `GET /api/trending` : tendances de la semaine
- `POST /api/ai/search` `{ "description" }` → `{ "results": [{ "tmdbId", "type", "title", "year", "posterPath", "voteAverage", "reason" }] }`
- Erreurs : `{ "error", "message" }`
- Toute modification du contrat doit m'être signalée explicitement

## Recherche IA
1. Claude renvoie une sortie structurée (record Java) : 5 à 10 candidats (titre, année, type, raison en français)
2. Chaque candidat est vérifié sur TMDB ; les introuvables sont éliminés
3. Garde-fous : description ≤ 300 caractères, max tokens limité, rate limiting par IP, cache par description normalisée

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
