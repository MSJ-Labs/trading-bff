# CLAUDE.md

Guidance for Claude Code when working in `trading-bff`. Sibling repos: `trading-core` (backend), `trading-ui` (frontend), `trading-infra` (Terraform for Keycloak).

## Commands

```bash
mvn compile                                              # Compile only
mvn test                                                 # All tests (Docker needed: Testcontainers Redis)
mvn test -Dtest=GatewayRateLimitTest                     # Single test class
mvn spring-boot:run                                      # Run locally (needs BFF_CLIENT_SECRET, see .env.example, and Redis)
SPRING_PROFILES_ACTIVE=secure-file mvn spring-boot:run   # Same jar, secure-file configuration (needs the secure-file client secret)
```

Ports: **8081** public (browser and API), **9091** management (actuator, never routed by an Ingress/Service). Profile `secure-file`: **8083** and **9093**.

## One jar, one configuration per front

The code knows no front: what differs between the trading and the secure-file interface is configuration. Default (no profile) = trading: realm `trading`, client `trading-bff`, routes to trading-core, Redis DB 1. Profile `secure-file` (`application-secure-file.properties`) = realm `secure-file`, client `secure-file-bff`, routes to secure-file-service (`SECURE_FILE_URI`, default `http://localhost:8082`), Redis DB 2, port 8083. Run each one as its own deployment.

- A list replaces the one of `application.properties` as a whole (Spring does not merge lists): the profile declares its own `routes[]`, the trading-core routes do not exist there. `SecureFileProfileTest` asserts it.
- `/api/v1/files` (PUT) and `/api/v1/files/*/content` (GET) carry files up to 2 GB: no CircuitBreaker (its 10s time limiter would cut them) and no Retry. Not yet tried with a real large file: check that the body is streamed, not buffered.
- One stack at a time on a machine: both name their session cookie `SESSION` (fixed in `SecurityConfig` for the back-channel logout) and browsers share cookies between ports of a host; only one Keycloak can use 8180 anyway.
- A front that needs code of its own (SSE, presigned upload, per-role rules) is the moment to extract the shared engine into a starter, not to put front-specific code here.

## What the BFF is

Backend-for-frontend and API gateway in one Spring Boot app (Spring Boot 4.1, Java 25, Spring Cloud 2025.1 Gateway Server **MVC**, not WebFlux):

- **Confidential OAuth2 client** `trading-bff` (Authorization Code + PKCE) against Keycloak. Tokens stay server-side; the browser only holds an httpOnly session cookie. Sessions live in **Redis DB 1** (Spring Session). Back-channel logout closes sessions (`RedisOidcSessionRegistry`).
- **Gateway**: `/api/**` is proxied to trading-core with the access token attached by `TokenRelay` (profile `secure-file`: `/api/v1/**` to secure-file-service).
- `GET /bff/user`: identity of the logged-in user (sub, names, email, realm roles for display only).

## Request path through the gateway (hot path, latency matters)

Per route, filters run in index order (`filters[0]` is the outermost):

```
UserRateLimit -> TokenRelay -> CircuitBreaker (+TimeLimiter) -> Retry (safe methods only) -> trading-core
```

**Every filter added here costs latency on every API call.** For any new filter or per-request lookup: count its Redis/network round trips, say whether it blocks or hops threads, do constant work once at startup, and measure (Micrometer) instead of guessing. Keep Redis co-located with the pod in Kubernetes.

Two routes share `Path=/api/**` in `application.properties` (the first match wins):

| Route | Predicate | Filters |
|---|---|---|
| `trading-core-read` (`routes[0]`) | GET, HEAD, OPTIONS (safe methods) | UserRateLimit, TokenRelay, CircuitBreaker, Retry |
| `trading-core-write` (`routes[1]`) | any other method (POST, PUT, PATCH, DELETE) | UserRateLimit, TokenRelay, CircuitBreaker (no Retry) |

Why two routes: the framework Retry filter checks the HTTP method only for status-based retries, not for exception-based ones, so a POST that failed after trading-core processed it could be replayed (double order).

## Resilience (Resilience4j)

- Read timeout per route = **TimeLimiter** `resilience4j.timelimiter.configs.trading-core` (10s). The config name MUST equal the circuit breaker id, otherwise Resilience4j falls back to `default` (TimeLimiter default = 1s). There is no per-route HTTP timeout in Gateway MVC 5.0.x. Connect timeout is global: `spring.http.clients.connect-timeout=2s`.
- Circuit breaker `trading-core`: opens at 50% failures over 20 calls (min 10), open for 30s, 5 trial calls. Statuses 500/502/503/504 count as failures and are replaced by the fallback; 4xx are business errors, never counted.
- Fallback: `FallbackController` `/fallback/trading-core` answers a 503 ProblemDetail.
- Retry: GET/HEAD/OPTIONS only, 2 retries, 100ms to 500ms backoff, status series SERVER_ERROR plus `ResourceAccessException`/`UncheckedIOException` (not in the default exception list). The JDK HttpClient also replays an idempotent request once on a cut connection.

## Rate limiting (in-memory counters, batch-synchronised with Redis)

- Limits per user (Keycloak `sub`): **1000 GET/min** and **200 writes/min**, separate counters per scope (`read`, `write`). Override with `app.rate-limit.read-capacity` / `app.rate-limit.write-capacity` / `app.rate-limit.period`. Over the limit: **429** (JSON problem) with `X-RateLimit-Remaining: 0` and `Retry-After` (seconds to the next window); trading-core is never called. Allowed responses carry `X-RateLimit-Remaining`. A request with no identifiable user gets a **401** (no shared "anonymous" bucket).
- Each route builds a validated `RateLimitPolicy(scope, limit, window)` at startup: limit > 0 and window >= 1s, otherwise the startup fails (a bare `period=60` would be 60 **milliseconds**: write `60s`).
- **Fixed window** counter (windows aligned on the clock, one counter per `scope:windowMillis:sub:windowId`). Known trade-off: up to 2x the limit around a window boundary. This limiter is abuse protection, not a business control: financial limits (exposure, idempotency) belong in trading-core. Safe methods (GET, HEAD, OPTIONS) use the read counter; everything else the write counter.
- **No Redis and no thread hop in the request**: `RateLimiter.check` is a few in-memory atomic operations. Each pod counts what it lets through; `RateLimiter.synchronize()` runs every `app.rate-limit.sync-interval` (default **1s**, `RateLimitSynchronizationScheduler`, `fixedDelay` so runs never overlap) and pushes ALL counters to Redis in ONE Lua script (`INCRBY` + `EXPIRE` per key, one round trip, shared Lettuce connection; a pipeline would open a dedicated connection at every call), learning the global totals. Redis load is one round trip per pod per interval, whatever the number of users. The script touches several keys: standalone Redis only (with Redis Cluster the keys would need a common hash tag).
- Each `WindowCounter` (domain) holds ONE immutable state `(knownGlobal, unsent)` replaced atomically (CAS), so a request is always counted in exactly one of the two, even while a synchronisation is in flight. `used = knownGlobal + unsent`. `knownGlobal` never goes backwards (a Redis restart does not make a pod forget). Finished windows are dropped at each synchronisation, sent or not (memory stays bounded, even during an outage). On shutdown `@PreDestroy` pushes one last time.
- **Overshoot**: a pod learns about the others in two steps (they push, then it pulls), so a user can exceed the limit by what the other pods let through in about two intervals, at worst pods x limit per window. A pod that has never seen a user counts its first requests blind until its first synchronisation.
- **Redis down**: `synchronize()` does not throw; pods limit on their local counts, one WARN per outage (not per run), metric `gateway.ratelimit.synchronization.failures`. After a **timeout** the batch may have been applied although the pod never saw the answer: it is sent again and counted twice (fails strict, never lenient). Redis timeouts are short (`spring.data.redis.timeout=500ms`, `connect-timeout=500ms`, shared with the sessions).
- Metrics (actuator, port 9091): `gateway.ratelimit.overhead` (cost of the decision itself, tag `scope`, ~50 µs measured), `gateway.ratelimit.denied` (429s, tag `scope`), `gateway.ratelimit.synchronization` (Redis round trip, off the request path), `gateway.ratelimit.synchronization.failures`, `gateway.ratelimit.counters` (counters in memory).
- History (kept for the reasoning): Bucket4j + Lettuce CAS cost 3.6 ms per request (a Redis round trip per request), delayed sync brought 1.0 ms, but the sync still blocked the request that triggered it (p99) and the filter hopped threads. Replaced by the design above.
- Properties args cannot reference beans (no SpEL in Gateway MVC 5.0.x), hence our own filter **`UserRateLimit(scope, capacity, period)`**: public static method of `UserRateLimitFilterFunctions`, registered by a `FilterSupplier` bean in `RateLimitConfig`. The Gateway finds it by reflection ONCE at startup (filter name = method name capitalised) and builds one instance per route. Per request there is no reflection; the `RateLimiter` and the meters are fetched from the application context on the first request of each route and memoised in an `AtomicReference` (a volatile read afterwards). Responses are built with `GatewayServerResponse` (writable headers, so an outer filter can still add its own).
- Spring does not merge lists across property sources: a test cannot override `routes[0].filters[0].args.capacity` alone (it replaces the whole `routes` list, hence "Route id is required"). Use placeholders like `app.rate-limit.read-capacity`. Tests set `app.rate-limit.period=1h` so they never straddle two windows.
- Before prod: Traefik (Layer 10) adds a coarse per-IP limit in front; the BFF keeps the per-user one. With several pods, re-check the overshoot with real traffic (Prometheus, Layer 9; prefer `publishPercentileHistogram`, already used, over per-pod percentiles).

## Architecture (DDD / hexagonal, per feature)

New features are structured per bounded context, like trading-core: `domain/` (value objects, no framework types), `application/port/out/` (ports), `infrastructure/` (adapters, config), `api/` if it exposes HTTP. Value objects implement `shared.domain.ValueObject` (copied from trading-core on purpose: separate deployable, no shared dependency). Ports always live in `application/port/out`. The application layer is framework-free (no Spring annotation: beans are declared in `infrastructure/config`); `ArchitectureTest` (ArchUnit) enforces domain/application/infrastructure directions. Use Lombok (`@RequiredArgsConstructor`, `@Slf4j`) instead of hand-written boilerplate; records for value objects and DTOs.

```
com.msj.trading.bff/
├── api/            UserController (/bff/user), FallbackController, ApiExceptionHandler
├── config/         SecurityConfig (OIDC login, CSRF, back-channel logout)
├── security/       Redis OIDC session registry, realm roles extractor, logout strategy
├── shared/domain/  ValueObject marker
└── ratelimit/      First structured context
    ├── domain/
    │   ├── valueobject/         CallerId, RateLimitPolicy (validated rule), RateLimitDecision
    │   ├── model/               WindowCounter (lock-free per-pod counter of one caller and window)
    │   └── exception/           UnidentifiedCallerException
    ├── application/             RateLimiter (check + synchronize), SynchronizationResult
    │   └── port/out/            CallerIdentityProvider, RateLimitCounterStore (+ Increment)
    └── infrastructure/
        ├── adapters/security/   SecurityContextCallerIdentityProvider (reads the OIDC sub)
        ├── adapters/redis/      RedisRateLimitCounterStore (one Lua script for all counters)
        ├── scheduling/          RateLimitSynchronizationScheduler (sync + metrics + flush on shutdown)
        ├── config/              RateLimitConfig (RateLimiter bean, FilterSupplier, Clock, @EnableScheduling)
        └── gateway/             UserRateLimitFilterFunctions (the "UserRateLimit" filter)
```

`api/`, `config/` and `security/` are flat on purpose: wiring without a domain.

## Tests

- `RateLimiterTest`: the limiter alone (no Spring, no Redis), a fake shared store, two limiters = two pods (sync between pods, store down, answer lost, Redis restarted, request during a sync, many threads). `RateLimitPolicyTest`: startup validation. `RedisRateLimitCounterStoreTest`: the Lua script against a real Redis (Testcontainers). `ArchitectureTest`: layering rules.
- `GatewayResilienceTest` and `GatewayRateLimitTest`: full context + `FakeBackend` (JDK HttpServer with scripted behaviours and per-path hit counters) standing in for trading-core + Testcontainers Redis. `@WithFixedKeycloak` replaces OIDC discovery; `oidcLogin()` + `oauth2Client("keycloak")` feed TokenRelay.
- MockMvc does not execute servlet forwards: a circuit breaker fallback is asserted with `forwardedUrl("/fallback/trading-core")`.
- Rate limit tests lower the limits (3 reads, 2 writes) and use a random user per test so the limiter's in-memory counters never leak between tests (the Spring context is cached).
- After the tests, the cached context may log a Lettuce "Cannot reconnect" and one "Rate limit store unreachable" WARN (Redis container stopped while the context lives): harmless.

## Before prod

- `server.servlet.session.cookie.secure=true` (HTTPS only).
- Do NOT set `server.forward-headers-strategy` without revisiting the loopback CSRF exemption of the back-channel `/logout` in `SecurityConfig` (see the comment in `application.properties`).
- Management port 9091 must not be exposed by the Ingress/Service.
- Exact `https` redirect and post-logout URIs for the `trading-bff` client in Keycloak (see `trading-infra`).

## Roadmap (gateway features)

Done: resilience (timeout, retry, circuit breaker), rate limiting, routes for `secure-file-service` (profile `secure-file`). Next: observability (correlation id + OpenTelemetry propagation to downstream services), SSE/WebSocket proxying replacing the unauthenticated `/ws` of trading-core, security headers and explicit CORS decision, per-role authorization at the edge, request logging. Edge: Traefik first (Layer 10), then the GCP ingress.