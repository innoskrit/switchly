# Switchly — System Design

What this system does, what it stores, what you can call, and how a request flows through it — in one place.

If the code and this document ever disagree, one of them has a bug. Raise it.

**Related:** [`architecture.md`](architecture.md) (how it's built and deployed, session by session) · [`ui-screens.md`](ui-screens.md) (what every screen looks like)

---

## How to read this

This is a reference, not a novel. You don't need to read it all at once — each part becomes relevant when we build it.

| Section | You'll need it from |
| --- | --- |
| [1. Functional requirements](#1-functional-requirements) | Session 1–2 |
| [2. Non-functional requirements](#2-non-functional-requirements) | Session 1 (three of them), Session 22 (all) |
| [3. Domain model](#3-domain-model--the-class-level-view) and [4. Database schema](#4-database-schema) | Session 2 |
| [5. API design conventions](#5-api-design-conventions) | Session 4 |
| [6. API specification](#6-api-specification) | Sessions 4–9, one resource at a time |
| [7. The `/evaluate` contract](#7-the-evaluate-contract) | Session 15 |
| [8. High-level design](#8-high-level-design) | Session 1 (one diagram), Session 15 (the rest) |
| [9. Why Redis](#9-why-redis--and-exactly-where) | Session 18 |
| [10. Security & tenant isolation](#10-security--tenant-isolation) | Session 8, again in Session 22 |
| [11. Failure modes](#11-failure-modes) | Sessions 16 and 22 |

If you only have ten minutes, read [§8.1](#81-the-big-picture) — one diagram — and the three sentences at the [end](#three-sentences-to-remember).

---

## 1. Functional requirements

_A **functional requirement** is a thing the system must let someone do. Written as "actor can do X."_

Four actors:

| Actor | Who they are | Authenticates with |
| --- | --- | --- |
| **Owner** | Us. The platform team running Switchly. | Email + password → JWT |
| **Tenant Admin** | Senior person at a customer company (e.g. Zomato) | Email + password → JWT |
| **Tenant Developer** | Engineer at a customer company | Email + password → JWT |
| **Client Application** | A _program_ — the customer's running app | API key |

The fourth actor is not a person. Half of this system's design follows from that fact.

### 1.1 What we build

| ID | Requirement | Actor | Session |
| --- | --- | --- | --- |
| FR-01 | Sign up, creating an organization and its first admin user | Anyone | 7 |
| FR-02 | Log in and receive a token; log out; refresh an expired token | All humans | 7 |
| FR-03 | Create/rename/archive projects within my organization | Tenant Admin | 4 |
| FR-04 | Each project has environments (dev/staging/prod), created by default | Tenant Admin | 4 |
| FR-05 | Create a boolean flag with a unique key within a project | Tenant Dev | 5 |
| FR-06 | Turn a flag on/off **independently per environment** | Tenant Dev | 5 |
| FR-07 | Create a dynamic-config flag holding arbitrary JSON | Tenant Dev | 6 |
| FR-08 | Edit a config flag's JSON value per environment | Tenant Dev | 6 |
| FR-09 | List and search my project's flags | Tenant Dev | 5, 11 |
| FR-10 | Delete/archive a flag | Tenant Dev | 5 |
| FR-11 | Generate an API key scoped to exactly one environment | Tenant Admin | 9 |
| FR-12 | View key metadata (prefix, created, last used); **plaintext shown once** | Tenant Admin | 9, 12 |
| FR-13 | Revoke an API key immediately | Tenant Admin | 9 |
| FR-14 | Invite users to my org with a role (Admin / Developer) | Tenant Admin | 8 |
| FR-15 | **Never** see data belonging to another organization | All tenants | 8 |
| FR-16 | Evaluate a flag for a user context, via API key | Client App | 15 |
| FR-17 | Percentage rollout — flag on for N% of users, **stable per user** | Tenant Dev | 15 |
| FR-18 | Attribute targeting — e.g. `country == "IN"`, `plan == "pro"` | Tenant Dev | 15 |
| FR-19 | Ordered rule evaluation, first match wins, else default | System | 15 |
| FR-20 | Evaluate many flags in one request | Client App | 15 |
| FR-21 | SDK: init, `isEnabled()`, `getConfig()`, caching, polling, safe defaults | Client App | 16 |
| FR-22 | Every state-changing action written to an immutable audit log | System | 8 |
| FR-23 | View my organization's audit log | Tenant Admin | 13 |
| FR-24 | Owner: list all organizations, see usage, suspend one | Owner | 13 |
| FR-25 | Owner: read audit logs across all tenants | Owner | 13 |
| FR-26 | Create a flag from a natural-language sentence, **with confirmation** | Tenant Dev | 19 |
| FR-27 | Search flags in natural language (read-only) | Tenant Dev | 20 |

### 1.2 What we deliberately don't build

A real feature-flag product has every one of these. We're skipping them to keep the course focused — not because they don't matter. Knowing they exist stops you mistaking Switchly for the complete picture.

Billing and payments · scheduled flag changes · flag dependencies · approval workflows · SSO/SAML · webhooks · streaming updates (SSE/WebSocket) · client-side rule evaluation · experiment metrics and statistical significance · multi-region · soft-delete restore UI · email delivery (invites are links copied by hand).

---

## 2. Non-functional requirements

_A **non-functional requirement** is not a feature — it's a quality the system must have while doing its features. "Fast," "safe," "stays up." They're what separate a working demo from a product._

Vague NFRs are useless: "the system should be fast" can't be tested. Every NFR below has a number.

| ID | Category | Requirement | Verified in |
| --- | --- | --- | --- |
| NFR-01 | **Isolation** | No API response ever contains data from an org the caller doesn't belong to. Zero tolerance. | 21 (automated test) |
| NFR-02 | **Latency (eval)** | `POST /evaluate` p95 < **50 ms** server-side once cached | 18 |
| NFR-03 | **Latency (console)** | Console reads p95 < **500 ms** | 14 |
| NFR-04 | **Availability** | If Switchly is unreachable, the customer's app **still works** using SDK defaults | 16 |
| NFR-05 | **Freshness** | A flag change reaches running client apps within **60 s** | 18 |
| NFR-06 | **Consistency** | The same `userId` on the same flag+rollout always gets the same answer | 15 |
| NFR-07 | **Security — passwords** | bcrypt, cost ≥ 10. Never stored or logged in plaintext | 7 |
| NFR-08 | **Security — API keys** | Stored as SHA-256 hash; plaintext irrecoverable after creation | 9 |
| NFR-09 | **Security — transport** | HTTPS in production; no secrets in git; secrets from SSM | 22, 23 |
| NFR-10 | **Rate limiting** | Per API key: 1000 req/min. Per IP on `/auth/login`: 10/min | 22 |
| NFR-11 | **Auditability** | Every mutation records actor, action, target, timestamp. Append-only. | 8 |
| NFR-12 | **Observability** | Every request has a trace ID, returned in errors and logged | 22 |
| NFR-13 | **Portability** | `docker compose up` gives a working local system from a clean clone | 3 |
| NFR-14 | **Cost** | Entire production footprint stays inside AWS free-tier limits | 23 |
| NFR-15 | **AI safety** | LLM output is _never_ trusted: server re-validates and a human confirms before any mutation | 19 |

**If you remember only three**, remember these. They carry the weight of the whole product:
- **NFR-01** — Tenant A never sees Tenant B's data.
- **NFR-04** — Our outage must not become our customer's outage.
- **NFR-06** — Same user, same answer.

**Scale target for this course:** ~100 tenants, ~50 flags each, ~100 req/s evaluation. Real products handle 10,000× this. The _shape_ of the design doesn't change until far beyond our numbers — which is exactly why it's worth learning.

---

## 3. Domain model — the class-level view

Before any SQL, this is the shape of the world in objects. Java-flavoured, trimmed to the fields that matter.

### 3.1 Class diagram

```
                        ┌──────────────────┐
                        │   Organization   │  ◄── the TENANT boundary
                        │  id, name, slug  │      everything below belongs to exactly one
                        │  status          │
                        └────────┬─────────┘
                    ┌────────────┼────────────┐
                    │            │            │
          ┌─────────▼──┐  ┌──────▼─────┐  ┌───▼──────────┐
          │ Membership │  │  Project   │  │  AuditLog    │
          │ user+org   │  │ id, name   │  │ actor,action │
          │ role       │  │ key        │  │ target, at   │
          └─────┬──────┘  └──────┬─────┘  └──────────────┘
                │           ┌────┴────┐
          ┌─────▼────┐      │         │
          │   User   │  ┌───▼────┐ ┌──▼──────────┐
          │ email    │  │  Flag  │ │ Environment │
          │ pwd hash │  │ key    │ │ key: dev... │
          └──────────┘  │ type   │ └──┬──────────┘
                        └───┬────┘    │
                            │         │  ┌────────────┐
                            │         └─►│   ApiKey   │
                            │            │ hash,prefix│
                            │            └────────────┘
              ┌─────────────▼──────────────┐
              │   FlagEnvironmentState     │ ◄── flag × environment
              │   enabled, value(JSON)     │     THE row that gets read
              │   rolloutPercentage        │     on every evaluation
              └─────────────┬──────────────┘
                            │ 0..n, ordered
                   ┌────────▼─────────┐
                   │  TargetingRule   │
                   │ priority, attr,  │
                   │ operator, values │
                   └──────────────────┘
```

**The most important modelling decision in this diagram:** `Flag` does _not_ hold `enabled`.

A flag is a **definition** — its name, key and type, shared across the whole project. Whether it's on lives in `FlagEnvironmentState`, one row per flag per environment. That's the only shape that lets `new-checkout` be on in dev and off in prod at the same moment. Put `enabled` on `Flag` and that becomes impossible.

### 3.2 Entity sketches

```java
// tenancy module
class Organization {
    UUID id;
    String name;                 // "Zomato"
    String slug;                 // "zomato" — URL-safe, unique globally
    OrgStatus status;            // ACTIVE, SUSPENDED
    Instant createdAt;
}

class Project {
    UUID id;
    UUID organizationId;         // ◄── tenant discriminator, on EVERY tenant table
    String name;                 // "Consumer App"
    String key;                  // "consumer-app" — unique within org
    Instant createdAt;
}

class Environment {
    UUID id;
    UUID organizationId;         // denormalised on purpose — see §4.3
    UUID projectId;
    String key;                  // "dev" | "staging" | "prod" — unique within project
    String name;
    boolean production;          // drives UI warnings on dangerous edits
}

// flags module
class Flag {
    UUID id;
    UUID organizationId;
    UUID projectId;
    String key;                  // "holiday-banner" — unique within project; what the SDK sends
    String name;
    String description;
    FlagType type;               // BOOLEAN | CONFIG
    boolean archived;
    Instant createdAt;
}

class FlagEnvironmentState {
    UUID id;
    UUID organizationId;
    UUID flagId;
    UUID environmentId;
    boolean enabled;             // the master switch for this env
    JsonNode value;              // CONFIG flags only; null for BOOLEAN
    Integer rolloutPercentage;   // null = 100%; 0-99 = partial rollout
    List<TargetingRule> rules;   // ordered, first match wins
    Instant updatedAt;
}

// targeting module
class TargetingRule {
    UUID id;
    UUID flagEnvironmentStateId;
    int priority;                // 0 = evaluated first
    String attribute;            // "country", "plan", "email"
    Operator operator;           // EQUALS, NOT_EQUALS, IN, CONTAINS, ENDS_WITH
    List<String> values;         // ["IN", "LK"]
    boolean resultEnabled;       // what to return when this rule matches
    JsonNode resultValue;        // for CONFIG flags
}

// identity module
class User {
    UUID id;
    String email;                // unique, lowercased
    String passwordHash;         // bcrypt
    String name;
    boolean platformOwner;       // true only for us — grants cross-tenant access
}

class Membership {
    UUID id;
    UUID userId;
    UUID organizationId;
    Role role;                   // TENANT_ADMIN | TENANT_DEVELOPER
}

// apikeys module
class ApiKey {
    UUID id;
    UUID organizationId;
    UUID environmentId;          // a key unlocks exactly ONE environment
    String name;                 // "prod backend"
    String prefix;               // "sk_prod_a8f3" — safe to display
    String keyHash;              // SHA-256 of the full key. Plaintext is never stored.
    Instant lastUsedAt;
    Instant revokedAt;           // non-null = dead
}

// audit module
class AuditLog {
    UUID id;
    UUID organizationId;
    UUID actorUserId;            // null when the actor was a machine
    String actorType;            // USER | API_KEY | SYSTEM | AI
    String action;               // "flag.enabled", "apikey.revoked"
    String targetType;           // "Flag"
    UUID targetId;
    JsonNode metadata;           // { "before": false, "after": true }
    Instant createdAt;           // append-only. No update, no delete, ever.
}
```

---

## 4. Database schema

PostgreSQL. Schema changes are applied by Flyway migrations (`V1__create_tenancy.sql` onward), starting in session 4.

_**Flyway** is a tool that applies numbered `.sql` files to your database in order, so every machine ends up with exactly the same schema._

### 4.1 Conventions

| Convention | Choice | Why |
| --- | --- | --- |
| Table names | `snake_case`, **singular** (`flag`, not `flags`) | Matches JPA entity names. URLs use plural, tables don't. Either is defensible — pick one, never mix. |
| Primary keys | `UUID` | No guessable sequential IDs in URLs; safe to generate before insert |
| Timestamps | `TIMESTAMPTZ`, always UTC | Timezone bugs are silent and awful |
| Tenant column | `organization_id UUID NOT NULL` on every tenant-owned table | The isolation story |
| Soft delete | `archived_at TIMESTAMPTZ NULL` on `flag` | Deleting a flag that a customer's code still references is a foot-gun |
| JSON | `JSONB`, not `JSON` or `TEXT` | Indexable, validated, queryable |
| Naming | `idx_` for indexes, `uq_` for unique, `fk_` for foreign keys | Readable migrations |

### 4.2 Core tables

```sql
CREATE TABLE organization (
    id          UUID PRIMARY KEY,
    name        TEXT        NOT NULL,
    slug        TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_org_slug UNIQUE (slug)
);

CREATE TABLE project (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    key              TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_key_per_org UNIQUE (organization_id, key)
);
CREATE INDEX idx_project_org ON project(organization_id);

CREATE TABLE environment (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    project_id       UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    key              TEXT NOT NULL,           -- dev | staging | prod
    name             TEXT NOT NULL,
    production       BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT uq_env_key_per_project UNIQUE (project_id, key)
);

CREATE TABLE flag (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    project_id       UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    key              TEXT NOT NULL,           -- what the SDK sends
    name             TEXT NOT NULL,
    description      TEXT,
    type             TEXT NOT NULL,           -- BOOLEAN | CONFIG
    archived_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_flag_key_per_project UNIQUE (project_id, key),
    CONSTRAINT ck_flag_type CHECK (type IN ('BOOLEAN','CONFIG'))
);
CREATE INDEX idx_flag_project ON flag(project_id) WHERE archived_at IS NULL;

CREATE TABLE flag_environment_state (
    id                  UUID PRIMARY KEY,
    organization_id     UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    flag_id             UUID NOT NULL REFERENCES flag(id) ON DELETE CASCADE,
    environment_id      UUID NOT NULL REFERENCES environment(id) ON DELETE CASCADE,
    enabled             BOOLEAN NOT NULL DEFAULT false,
    value               JSONB,                -- CONFIG flags only
    rollout_percentage  INT,                  -- NULL = 100%
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_state_per_flag_env UNIQUE (flag_id, environment_id),
    CONSTRAINT ck_rollout CHECK (rollout_percentage IS NULL
                                 OR rollout_percentage BETWEEN 0 AND 100)
);
-- THE index that matters: the evaluation hot path reads by environment
CREATE INDEX idx_state_env ON flag_environment_state(environment_id);

CREATE TABLE targeting_rule (
    id                        UUID PRIMARY KEY,
    organization_id           UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    flag_environment_state_id UUID NOT NULL
                              REFERENCES flag_environment_state(id) ON DELETE CASCADE,
    priority                  INT  NOT NULL,
    attribute                 TEXT NOT NULL,
    operator                  TEXT NOT NULL,
    values                    JSONB NOT NULL,   -- ["IN","LK"]
    result_enabled            BOOLEAN NOT NULL,
    result_value              JSONB,
    CONSTRAINT uq_rule_priority UNIQUE (flag_environment_state_id, priority)
);

CREATE TABLE app_user (                        -- "user" is reserved in Postgres
    id             UUID PRIMARY KEY,
    email          TEXT NOT NULL,
    password_hash  TEXT NOT NULL,
    name           TEXT NOT NULL,
    platform_owner BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_email UNIQUE (email)
);

CREATE TABLE membership (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    organization_id  UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    role             TEXT NOT NULL,            -- TENANT_ADMIN | TENANT_DEVELOPER
    CONSTRAINT uq_membership UNIQUE (user_id, organization_id)
);

CREATE TABLE api_key (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    environment_id   UUID NOT NULL REFERENCES environment(id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    prefix           TEXT NOT NULL,            -- displayable
    key_hash         TEXT NOT NULL,            -- SHA-256
    last_used_at     TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_api_key_hash UNIQUE (key_hash)
);
CREATE INDEX idx_apikey_hash ON api_key(key_hash) WHERE revoked_at IS NULL;

CREATE TABLE audit_log (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL,
    actor_user_id    UUID,
    actor_type       TEXT NOT NULL,
    action           TEXT NOT NULL,
    target_type      TEXT NOT NULL,
    target_id        UUID,
    metadata         JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_org_time ON audit_log(organization_id, created_at DESC);
```

### 4.3 Two schema decisions worth understanding

**① Why is `organization_id` on `environment`, `flag_environment_state` and `targeting_rule`, when you could find it by joining up to `project`?**

This is _denormalisation_ — storing the same fact in more than one place — and it's deliberate. Two reasons:

- **Safety.** Every tenant-owned query can filter on `organization_id` directly. If someone forgets a join, they still can't accidentally cross tenants: the `WHERE organization_id = ?` is right there on the table they're already querying.
- **Speed.** The evaluation hot path avoids a three-table join.

The cost is the classic denormalisation cost: the same fact is stored twice, so it could disagree with itself. The mitigation is simple — `organization_id` is set once on insert and never updated. Records never move between tenants.

**② Why is `flag_environment_state` a separate table rather than columns on `flag`?**

Because a flag must be able to be on in dev and off in prod at the same time. One row per flag per environment is the only shape that allows it. See §3.1.

---

## 5. API design conventions

_An **API** is how one program asks another for something. **REST** is a widely-used style for arranging those requests around "resources" — nouns you can create, read, update and delete._

Every endpoint in Switchly follows these rules.

### 5.1 The rules

| Rule | Do | Don't |
| --- | --- | --- |
| **Version in the path** | `/api/v1/flags` | `/api/flags` |
| **Plural nouns for collections** | `/orgs`, `/projects`, `/flags` | `/getFlag`, `/flagList` |
| **No verbs in URLs** | `DELETE /api/v1/flags/{id}` | `POST /api/v1/deleteFlag` |
| **Nest at most one level** | `/projects/{projectId}/flags` then `/flags/{flagId}` | `/orgs/{o}/projects/{p}/envs/{e}/flags/{f}` |
| **kebab-case in URLs** | `/api-keys`, `/audit-logs` | `/apiKeys`, `/api_keys` |
| **camelCase in JSON** | `{"rolloutPercentage": 20}` | `{"rollout_percentage": 20}` |
| **Plural collection, singular item** | `GET /flags` → list · `GET /flags/{id}` → one | — |
| **Query params for filtering** | `/flags?type=CONFIG&page=0&size=20` | `/flags/type/CONFIG` |

**On versioning:** `v1` is a promise. Once customers' SDKs call `/api/v1/evaluate`, you can add optional fields but you can never remove one or change what one means. A breaking change needs `/api/v2`, running alongside v1. This surprises most people, and it's the most important thing about publishing an API.

**On nesting depth:** the deep version is technically expressive and miserable in practice — the client has to remember four IDs to fetch one flag. The convention: **nest one level to create or list within a parent; address a resource by its own ID once it exists.** So `POST /api/v1/projects/{projectId}/flags` creates, and `GET /api/v1/flags/{flagId}` reads. The server works out the parents from the ID.

### 5.2 HTTP status codes

| Code | Meaning | Used when |
| --- | --- | --- |
| `200 OK` | Fine | Successful GET, PATCH, PUT |
| `201 Created` | Made a thing | Successful POST; include `Location` header |
| `204 No Content` | Fine, nothing to say | Successful DELETE |
| `400 Bad Request` | Your request is malformed | Missing field, bad JSON, invalid enum |
| `401 Unauthorized` | **I don't know who you are** | Missing/expired/invalid token or key |
| `403 Forbidden` | **I know who you are, you may not** | Developer attempting an admin-only action |
| `404 Not Found` | No such thing | Bad ID — **and cross-tenant access, see §10.3** |
| `409 Conflict` | Clashes with existing state | Duplicate flag key in a project |
| `422 Unprocessable` | Well-formed but semantically wrong | `rolloutPercentage: 150` |
| `429 Too Many Requests` | Slow down | Rate limit hit; include `Retry-After` |
| `500 Internal Server Error` | We broke | Never leak a stack trace to the client |

401 vs 403 is the most commonly confused pair. _401 = who are you? 403 = I know who you are, and no._

### 5.3 Error response format

One shape, everywhere. Consistency here is worth more than cleverness.

```json
{
  "error": {
    "code": "FLAG_KEY_ALREADY_EXISTS",
    "message": "A flag with key 'holiday-banner' already exists in this project.",
    "details": [
      { "field": "key", "issue": "must be unique within the project" }
    ]
  },
  "traceId": "0f3a9c21-7b4e-4a12-9d88-1c2f5b6e7a90"
}
```

- `code` — stable, machine-readable, `SCREAMING_SNAKE`. Clients branch on this. Never changes.
- `message` — for a human. Can be reworded freely; never parsed.
- `details` — per-field validation problems.
- `traceId` — matches the server log line. When something breaks, find the trace ID first: it links what you saw to the exact line in the server log.

### 5.4 Pagination

**Page-based** for console lists (small, browsed by humans):
```
GET /api/v1/projects/{projectId}/flags?page=0&size=20&sort=createdAt,desc
```
```json
{ "items": [ ... ], "page": 0, "size": 20, "totalElements": 47, "totalPages": 3 }
```

**Cursor-based** for audit logs (large, append-only, constantly growing):
```
GET /api/v1/orgs/{orgId}/audit-logs?limit=50&cursor=eyJ0cyI6IjIwMjYtMDktMTgifQ
```
```json
{ "items": [ ... ], "nextCursor": "eyJ0cyI6IjIwMjYtMDktMTcifQ", "hasMore": true }
```

_Why two?_ With page numbers, new rows arriving shift everything — page 2 shows you a row you already saw on page 1. Audit logs are written constantly, so page numbers are actively wrong there. A cursor says "give me what comes after _this specific row_," which stays correct no matter what arrives in the meantime.

### 5.5 Idempotency

_An operation is **idempotent** if doing it twice has the same effect as doing it once. Flipping a light switch to "on" is idempotent; pressing a doorbell isn't._

- `GET`, `PUT`, `DELETE` — idempotent. `PUT` a flag state to `enabled: true` ten times and it's on. Once.
- `POST` — **not** idempotent. Ten calls make ten flags.

That's why we `PUT` flag state — declaring the desired end state — rather than `POST /toggle`. If a toggle request times out, you don't know whether it happened, so you don't know whether retrying will turn the flag on or off again. A `PUT` is safe to retry.

Real APIs often add an `Idempotency-Key` header to make `POST` safe to retry too. We don't build it, but you'll see it in real APIs.

---

## 6. API specification

Base URL: `/api/v1`. All requests and responses are `application/json`.

Auth column: **JWT** = human bearer token · **KEY** = API key · **—** = public.

### 6.1 Authentication

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| `POST` | `/auth/signup` | — | Create user + organization + default project + 3 environments |
| `POST` | `/auth/login` | — | Returns access + refresh token |
| `POST` | `/auth/refresh` | — | Exchange refresh token for a new access token |
| `POST` | `/auth/logout` | JWT | Invalidate the refresh token |
| `GET` | `/me` | JWT | Current user, memberships, roles |

```http
POST /api/v1/auth/signup
{
  "email": "dev@zomato.com",
  "password": "correct-horse-battery-staple",
  "name": "Riya Sharma",
  "organizationName": "Zomato"
}

201 Created
{
  "user":         { "id": "...", "email": "dev@zomato.com", "name": "Riya Sharma" },
  "organization": { "id": "...", "name": "Zomato", "slug": "zomato" },
  "accessToken":  "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
  "expiresIn": 900
}
```

> **Design note — signup does five things at once.** It creates a user, an organization, a membership, a default project, and three environments, in **one database transaction**. Either all of it happens or none of it does. A half-created tenant (a user with no org) is unrecoverable garbage. You'll implement this with `@Transactional` in session 7.

### 6.2 Organizations

| Method | Path | Auth | Role | Description |
| --- | --- | --- | --- | --- |
| `GET` | `/orgs` | JWT | any | Orgs I belong to (Owner: all) |
| `GET` | `/orgs/{orgId}` | JWT | member | One org |
| `PATCH` | `/orgs/{orgId}` | JWT | admin | Rename |
| `GET` | `/orgs/{orgId}/members` | JWT | member | List members + roles |
| `POST` | `/orgs/{orgId}/members` | JWT | admin | Invite a user |
| `DELETE` | `/orgs/{orgId}/members/{userId}` | JWT | admin | Remove |
| `GET` | `/orgs/{orgId}/audit-logs` | JWT | admin | Cursor-paginated |

### 6.3 Projects & environments

| Method | Path | Auth | Role | Description |
| --- | --- | --- | --- | --- |
| `GET` | `/orgs/{orgId}/projects` | JWT | member | List |
| `POST` | `/orgs/{orgId}/projects` | JWT | admin | Create (+3 default envs) |
| `GET` | `/projects/{projectId}` | JWT | member | One project |
| `PATCH` | `/projects/{projectId}` | JWT | admin | Rename |
| `DELETE` | `/projects/{projectId}` | JWT | admin | Archive |
| `GET` | `/projects/{projectId}/environments` | JWT | member | List |
| `POST` | `/projects/{projectId}/environments` | JWT | admin | Create extra env |
| `GET` | `/environments/{environmentId}` | JWT | member | One env |

### 6.4 Flags

| Method | Path | Auth | Role | Description |
| --- | --- | --- | --- | --- |
| `GET` | `/projects/{projectId}/flags` | JWT | member | List; filters `?type=`, `?q=`, `?archived=` |
| `POST` | `/projects/{projectId}/flags` | JWT | dev+ | Create definition |
| `GET` | `/flags/{flagId}` | JWT | member | Definition + all env states |
| `PATCH` | `/flags/{flagId}` | JWT | dev+ | Rename / edit description |
| `DELETE` | `/flags/{flagId}` | JWT | admin | Archive (soft) |

```http
POST /api/v1/projects/{projectId}/flags
{
  "key": "holiday-banner",
  "name": "Holiday Banner",
  "description": "Festive sale banner on the home page",
  "type": "CONFIG"
}

201 Created
Location: /api/v1/flags/8c1f...
{
  "id": "8c1f...", "key": "holiday-banner", "name": "Holiday Banner",
  "type": "CONFIG", "projectId": "...", "createdAt": "2026-09-18T12:00:00Z",
  "environments": [
    { "environmentId": "...", "environmentKey": "dev",     "enabled": false, "value": null },
    { "environmentId": "...", "environmentKey": "staging", "enabled": false, "value": null },
    { "environmentId": "...", "environmentKey": "prod",    "enabled": false, "value": null }
  ]
}
```

> **Design note — creating a flag creates its state rows too**, one per environment, all defaulting to `enabled: false`. Safe by default: a new flag is never accidentally live in production. It also means the evaluation code never has to special-case "this row doesn't exist yet."

### 6.5 Flag state per environment — the important one

| Method | Path | Auth | Role | Description |
| --- | --- | --- | --- | --- |
| `GET` | `/flags/{flagId}/environments/{environmentId}` | JWT | member | State in one env |
| `PUT` | `/flags/{flagId}/environments/{environmentId}` | JWT | dev+ | **Set the state** |
| `GET` | `/flags/{flagId}/environments/{environmentId}/rules` | JWT | member | Targeting rules |
| `PUT` | `/flags/{flagId}/environments/{environmentId}/rules` | JWT | dev+ | Replace all rules (ordered) |

```http
PUT /api/v1/flags/8c1f.../environments/env-prod-id
{
  "enabled": true,
  "value": { "text": "Diwali Sale — 20% off", "color": "#e8590c" },
  "rolloutPercentage": 20
}

200 OK
{
  "flagId": "8c1f...", "environmentId": "env-prod-id",
  "enabled": true,
  "value": { "text": "Diwali Sale — 20% off", "color": "#e8590c" },
  "rolloutPercentage": 20,
  "updatedAt": "2026-09-18T12:05:00Z"
}
```

`PUT`, not `PATCH`, and not `POST /toggle`: the client declares the complete desired state, so a retry after a timeout is harmless (§5.5). **This one call is what the console's toggle switch sends, and it's what clears the Redis cache in §9.**

Rules are replaced as an ordered whole rather than edited one at a time, because their _order_ is part of their meaning — "first match wins." If you edit rule #3 while someone else reorders the list, one of you silently loses your change.

### 6.6 API keys

| Method | Path | Auth | Role | Description |
| --- | --- | --- | --- | --- |
| `GET` | `/environments/{environmentId}/api-keys` | JWT | admin | Metadata only |
| `POST` | `/environments/{environmentId}/api-keys` | JWT | admin | **Plaintext returned once** |
| `DELETE` | `/api-keys/{apiKeyId}` | JWT | admin | Revoke immediately |

```http
POST /api/v1/environments/env-prod-id/api-keys
{ "name": "prod backend" }

201 Created
{
  "id": "...", "name": "prod backend", "prefix": "sk_prod_a8f3",
  "key": "sk_prod_a8f3d9e1c4b7...ff20",
  "warning": "Copy this key now. It will never be shown again.",
  "createdAt": "2026-09-18T12:10:00Z"
}
```

Every later `GET` returns only `prefix`, never `key`. We store a SHA-256 hash of the key; the plaintext genuinely cannot be recovered, by us or by anyone.

**Try it in session 9:** generate a key, close the dialog without copying it, and try to get it back. You can't — and that's the point.

### 6.7 Evaluation — machine-facing

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| `POST` | `/evaluate` | KEY | Evaluate one or many flags for a context |
| `GET` | `/sdk/manifest` | KEY | Cheap freshness check — version + flag keys |

Detailed in §7. Notice what's **missing**: an API key cannot list projects, read audit logs, or create anything. One environment, read-only, one purpose. If a key leaks, the worst that happens is someone learns whether your flags are on.

### 6.8 Owner / platform admin

| Method | Path | Auth | Role | Description |
| --- | --- | --- | --- | --- |
| `GET` | `/admin/orgs` | JWT | **owner** | All tenants + counts |
| `GET` | `/admin/orgs/{orgId}` | JWT | owner | Detail + usage |
| `PATCH` | `/admin/orgs/{orgId}/status` | JWT | owner | Suspend / reactivate |
| `GET` | `/admin/audit-logs` | JWT | owner | Cross-tenant |
| `GET` | `/admin/usage` | JWT | owner | Evaluations per tenant |

> **Design note.** Owner endpoints live under a separate `/admin` prefix on purpose. Reading across tenants is _the_ dangerous capability in this system, so it's confined to one path prefix guarded by one security filter — rather than scattered as `?allTenants=true` parameters across normal endpoints. One place to audit, one place to get right. Built in session 13, re-audited in session 22.

### 6.9 AI endpoints

| Method | Path | Auth | Role | Description |
| --- | --- | --- | --- | --- |
| `POST` | `/ai/flags/propose` | JWT | dev+ | Sentence → **proposed** `createFlag`. Changes nothing. |
| `POST` | `/ai/flags/confirm` | JWT | dev+ | Execute a proposal after a human confirms |
| `POST` | `/ai/flags/search` | JWT | member | Sentence → structured filter → results. Read-only. |

```http
POST /api/v1/ai/flags/propose
{ "projectId": "...", "prompt": "make a config flag for the new checkout banner, off in prod" }

200 OK
{
  "proposalId": "prop_7f2a...",
  "tool": "createFlag",
  "arguments": {
    "key": "checkout-banner", "name": "Checkout Banner", "type": "CONFIG",
    "environments": { "dev": {"enabled": true}, "staging": {"enabled": true},
                      "prod": {"enabled": false} }
  },
  "explanation": "Creating a config flag 'checkout-banner', enabled in dev and staging, off in prod.",
  "expiresAt": "2026-09-18T12:20:00Z"
}
```

Splitting this into two endpoints _is_ the safety design:

1. `propose` calls the model and returns a **structured suggestion**. Nothing is written.
2. The UI shows it as a preview with a Confirm button.
3. `confirm` re-validates **on the server, from scratch** — is the key valid, does it clash, is this user allowed, is `projectId` in _their_ org — and only then writes.

The model's output is treated as **untrusted user input**, because that's exactly what it is. It never reaches the database without server-side validation and a human click. Proposals expire after 10 minutes, so a stale one can't be replayed. (NFR-15.)

---

## 7. The `/evaluate` contract

The most important interface in the system. Sessions 15, 16, 17 and 18 all depend on it, which is why it's fixed early and changed only carefully.

### 7.1 Request

```http
POST /api/v1/evaluate
Authorization: Bearer sk_prod_a8f3d9e1c4b7...
Content-Type: application/json

{
  "context": {
    "userId": "user-8821",
    "attributes": { "country": "IN", "plan": "pro", "appVersion": "4.2.0" }
  },
  "flagKeys": ["holiday-banner", "new-checkout"]
}
```

- The environment is **never** in the body — the API key implies it. A client can't ask about an environment it doesn't hold a key for (§10.3).
- Leave out `flagKeys` to evaluate every flag in the environment. The SDK does this when it starts up.
- `context.userId` is whatever identifies a user in the _customer's_ system. We never store it.
- `attributes` is free-form; targeting rules match against these keys.

### 7.2 Response

```json
{
  "environmentKey": "prod",
  "evaluatedAt": "2026-09-18T12:15:00Z",
  "version": 47,
  "flags": {
    "holiday-banner": {
      "enabled": true,
      "value": { "text": "Diwali Sale — 20% off", "color": "#e8590c" },
      "reason": "RULE_MATCH"
    },
    "new-checkout": {
      "enabled": false,
      "value": null,
      "reason": "ROLLOUT_EXCLUDED"
    }
  }
}
```

`reason` isn't decoration — it's the difference between a debuggable product and a support nightmare. When a customer asks "why isn't my flag on for this user?", `reason` answers it. Possible values: `FLAG_OFF`, `RULE_MATCH`, `ROLLOUT_INCLUDED`, `ROLLOUT_EXCLUDED`, `DEFAULT_ON`, `FLAG_NOT_FOUND`.

`version` is the environment's change counter, increased on every state change. The SDK compares it with `GET /sdk/manifest` to decide whether it needs to fetch again.

**An unknown flag key returns `FLAG_NOT_FOUND` with `enabled: false` — it does not make the whole request fail with a 404.** One typo shouldn't break a page that evaluates twelve flags. For a batch endpoint, partial success is the correct behaviour.

### 7.3 Evaluation algorithm

Evaluation follows this exact ladder. Top to bottom; the first step that decides, wins.

```
1. Flag missing?                    → enabled:false, FLAG_NOT_FOUND
2. state.enabled == false?          → enabled:false, FLAG_OFF        ← master switch, always first
3. Any targeting rule matches?      → rule.resultEnabled, RULE_MATCH ← first by priority wins
4. rolloutPercentage set?           → bucket(flagKey, userId) < pct
                                       ? ROLLOUT_INCLUDED : ROLLOUT_EXCLUDED
5. Otherwise                        → enabled:true, DEFAULT_ON
```

Step 2 comes before step 3 on purpose. The master switch must beat every rule — otherwise your kill switch isn't a kill switch.

### 7.4 Stable bucketing (NFR-06)

```java
int bucket(String flagKey, String userId) {
    String seed = flagKey + ":" + userId;
    byte[] digest = MessageDigest.getInstance("SHA-256")
                                 .digest(seed.getBytes(UTF_8));
    int value = ((digest[0] & 0xFF) << 16)
              | ((digest[1] & 0xFF) << 8)
              |  (digest[2] & 0xFF);
    return value % 100;                        // 0..99
}
```

Three properties to remember:

1. **Deterministic** — same input, same bucket, forever. No database, no randomness, no stored decision. `user-8821` refreshing the page ten times gets the same answer ten times.
2. **Seeded with the flag key** — so a user who lands in the excluded 80% of _one_ flag isn't automatically excluded from every other flag too. Without `flagKey` in the seed, the same "unlucky" users would miss every rollout, forever. Real products have shipped this bug.
3. **Stateless** — nothing to store, nothing to migrate, identical on every server.

> **A note on the name.** You'll often hear this called "consistent hashing." Strictly, what we're doing is _stable bucketing_ — hashing into a fixed 0–99 range. True consistent hashing is a different technique (a hash ring) for spreading keys across a changing set of servers. Related idea, different problem. If you study distributed systems later you'll meet the real thing — and knowing the difference is a good thing to be able to say in an interview.

---

## 8. High-level design

### 8.1 The big picture

```
    ┌──────────────┐                      ┌──────────────┐
    │  Tenant Dev  │                      │    Owner     │
    │  (browser)   │                      │  (browser)   │
    └──────┬───────┘                      └──────┬───────┘
           │        switchly-web (ONE React app) │
           └──────────────────┬──────────────────┘
                              │  JWT  (humans)
                              ▼
                   ┌─────────────────────┐
                   │    switchly-api     │
                   │   (Spring Boot)     │
                   └──┬───────────────┬──┘
                      │               ▲
              ┌───────▼─────┐         │  API key (machines)
              │  Postgres   │         │
              │   Redis     │   ┌─────┴──────────────┐
              └─────────────┘   │  switchly-sdk      │
                                │  inside customer   │
                                │  apps, e.g.        │
                                │  demo-shop         │
                                └────────────────────┘
```

Two doors into one building. Humans come in on the left with a session token; machines come in on the right with an API key. Different doors, different locks, same building.

### 8.2 Layers inside `switchly-api`

```
   HTTP request
        │
        ▼
  ┌───────────────────────────────────────────────┐
  │  Filters      JWT filter · API-key filter     │  who are you?
  │               rate limiter · trace ID         │
  ├───────────────────────────────────────────────┤
  │  Controller   HTTP only. Parse, validate      │  no business logic here
  │               shape, return status codes.     │
  ├───────────────────────────────────────────────┤
  │  Service      Business rules. Transactions.   │  the actual thinking
  │               Tenant scoping. Audit writes.   │
  ├───────────────────────────────────────────────┤
  │  Repository   Database access only.           │  no business logic here either
  ├───────────────────────────────────────────────┤
  │  Postgres  ·  Redis                           │
  └───────────────────────────────────────────────┘
```

The most common mistake is putting business logic in the controller. A quick test: _could you call this service from a scheduled job, with no HTTP request anywhere? If not, HTTP has leaked too deep._

### 8.3 Flow A — a human toggles a flag

```
Browser              API                          Postgres      Redis
  │  PUT /flags/{id}/environments/{envId}
  ├────────────────►│
  │                 │ 1. JWT filter → who is this user?
  │                 │ 2. Load membership → which org? which role?
  │                 │ 3. Load state BY id AND organization_id   ◄── the isolation line
  │                 ├─────────────────────────────►│
  │                 │ 4. Not found / other org → 404 (§10.3)
  │                 │ 5. Update state, bump env version
  │                 ├─────────────────────────────►│
  │                 │ 6. Write audit_log row
  │                 ├─────────────────────────────►│
  │                 │ 7. DELETE cache key env:{envId}:flags     ◄── §9
  │                 ├────────────────────────────────────────►│
  │  200 OK         │
  │◄────────────────┤
```

Step 3 is the whole security model in one line: **the scope comes from the authenticated session, never from the request body.**

### 8.4 Flow B — a machine evaluates a flag

```
demo-shop      SDK (in-process)        API                   Redis      Postgres
    │ isEnabled('holiday-banner', {userId})
    ├──────────────►│
    │               │ cache hit & fresh? → return immediately (0 network calls)
    │◄──────────────┤
    │               │ …otherwise, or on the 30s poll:
    │               │  POST /evaluate  (Authorization: sk_prod_…)
    │               ├──────────────────►│
    │               │                   │ 1. Hash key (SHA-256) → look up api_key
    │               │                   │    → gives environmentId + organizationId
    │               │                   │ 2. GET env:{envId}:flags
    │               │                   ├───────────────────►│
    │               │                   │  HIT → skip to 4   │
    │               │                   │  MISS ↓            │
    │               │                   │ 3. Load flags+states+rules for env
    │               │                   ├──────────────────────────────►│
    │               │                   │    SET cache, TTL 300s
    │               │                   ├───────────────────►│
    │               │                   │ 4. Evaluate in memory (§7.3) — no I/O
    │               │  200 + flags map  │
    │               │◄──────────────────┤
    │◄──────────────┤ cache locally, return
```

Look at what step 4 _doesn't_ do: no database call per flag, no network call per flag. Once the environment's flags are in memory, evaluating twelve flags is twelve hash-map lookups and one SHA-256. That's how a 50 ms p95 (NFR-02) is achievable.

---

## 9. Why Redis — and exactly where

Before adding a cache, measure the problem. A cache added without a measured reason is cargo-culting; a cache added after measuring is engineering.

### 9.1 The problem

Do the arithmetic:

> The demo shop renders its home page. Every render evaluates 3 flags. Each evaluation currently goes to Postgres for the flag, its environment state, and its targeting rules.
>
> - 1 page view = **~3 database round trips**
> - The shop gets 100 page views/second → **300 queries/second**
> - Now 50 tenants doing the same → **15,000 queries/second**
>
> On a `t3.micro`, running Postgres in a container. What happens?

And then notice this:

> **Those 15,000 queries return the same answer every time.** Flags change a few times a _day_. We're asking the database the same question thousands of times a second and getting an identical answer.

That's the whole argument. Redis is the fix for a problem you just calculated yourself.

### 9.2 What Redis is

_**Redis** is a database that keeps everything in RAM instead of on disk. That makes it roughly 100× faster to read — and means it forgets everything when it restarts. That's fine, because we only put things there that we can rebuild from Postgres._

An analogy: Postgres is the library — everything's there, permanently, but you walk to the shelf every time. Redis is the stack of books on your desk — small, instantly within reach, and if someone clears your desk you just fetch them again.

| | Postgres | Redis |
| --- | --- | --- |
| Stores | On disk | In memory |
| Read latency | 1–10 ms | 0.1–1 ms |
| Survives restart | Yes | No (as we configure it) |
| Holds | The truth | A copy of the truth, temporarily |

**The rule:** never put anything in Redis that you can't rebuild from Postgres. Redis is a speed-up, never the source of truth.

### 9.3 Use 1 — cache-aside for evaluation (session 18)

_**Cache-aside**: check the cache first; on a miss, read the real source and put the answer in the cache on the way back._

```
Key:    switchly:env:{environmentId}:flags
Value:  JSON — every flag in that environment, with state and rules
TTL:    300 seconds
```

```java
public EnvironmentFlags load(UUID environmentId) {
    String key = "switchly:env:" + environmentId + ":flags";
    String cached = redis.get(key);
    if (cached != null) {
        return deserialize(cached);                       // HIT — no database
    }
    EnvironmentFlags fresh = repository.loadAllForEnvironment(environmentId);   // MISS
    redis.setex(key, 300, serialize(fresh));
    return fresh;
}
```

Cache the **whole environment**, not individual flags. One key covers every flag in one environment, which is exactly what a batch `/evaluate` needs: one Redis round trip serves a twelve-flag evaluation.

**Invalidation on write** — step 7 of Flow A (§8.3):

```java
@Transactional
public void updateState(UUID flagId, UUID envId, StateUpdate update) {
    repository.save(...);                                 // Postgres is the truth
    auditLog.record(...);
    redis.del("switchly:env:" + envId + ":flags");        // drop the stale copy
}
```

Delete the cached copy; don't update it. A deleted key is simply rebuilt from the source of truth on the next read. Trying to keep a cached copy _in sync_ by writing to both places is how you end up with a cache that disagrees with the database — one of the classic hard bugs.

**So how long until a flag change is visible?** Follow the chain: write → cache deleted instantly → next evaluate rebuilds it → SDK polls every 30 s. So **typically under 30 seconds, 60 s at worst** — which is NFR-05. The 300 s TTL is only a safety net, for the rare case where an invalidation gets missed.

### 9.4 Use 2 — API key lookups (session 18)

Every single `/evaluate` needs to turn an API key into an environment. That's a database lookup on the busiest path in the system.

```
Key:    switchly:apikey:{sha256-of-key}
Value:  { environmentId, organizationId, apiKeyId }
TTL:    60 seconds — short, so a revoked key dies quickly
```

The TTL here is a real security trade-off. Cache it for an hour and revoking a key takes up to an hour to work. 60 seconds is a sensible compromise. The stricter alternative — explicitly deleting this cache entry when a key is revoked — is a good exercise.

> **One of the most interesting security ideas in the course lives here.**
>
> Passwords use **bcrypt**, which is _deliberately slow_ (~100 ms), so an attacker who steals the database can't brute-force them quickly. Humans pick weak passwords, so we make every guess expensive.
>
> API keys use **SHA-256**, which is _fast_. Why the inconsistency? Because an API key is 256 bits of randomness that we generated. There's no dictionary of likely keys to guess from — brute-forcing it is infeasible no matter how fast the hash is. And bcrypt on the evaluation path would add 100 ms to every request, breaking NFR-02.
>
> **Same-looking problem, opposite correct answers — and the reason is how guessable the secret is, not which API it belongs to.** If you can explain this in your own words, you understand password hashing better than most people who memorised "always use bcrypt."

### 9.5 Use 3 — rate limiting (session 22)

Redis is shared between servers, its operations are atomic, and its keys can expire on their own — exactly what a counter needs.

```
Key:    switchly:ratelimit:{apiKeyId}:{currentMinute}
Value:  integer counter
TTL:    60 seconds — expires itself, no cleanup job
```

```java
String key = "switchly:ratelimit:" + apiKeyId + ":" + currentMinute();
long count = redis.incr(key);
if (count == 1) redis.expire(key, 60);
if (count > 1000) throw new RateLimitExceededException();   // → 429
```

`INCR` is atomic, so two simultaneous requests can't both read 999 and both get through. Doing this in Postgres would mean a database write on every request — turning a protection mechanism into a bottleneck. Doing it in application memory would break the moment you run a second server.

### 9.6 What we deliberately do NOT put in Redis

| Not cached | Why |
| --- | --- |
| Console reads (flag lists, projects) | Low traffic, and stale data in a UI is confusing — you toggle something and see the old value |
| JWT sessions | Stateless by design; the token carries its own claims |
| Audit logs | Append-only, rarely read, must be exact |
| Anything that exists only in Redis | It forgets on restart. The truth lives in Postgres, always. |

**Caching is not free.** It adds a failure mode (stale data), another component to run, and a class of bug that's hard to reproduce. We cache exactly where we measured a problem, and nowhere else. That restraint is the real lesson here — "add Redis everywhere" is the wrong takeaway.

---

## 10. Security & tenant isolation

NFR-01 is the requirement this entire product lives or dies by. Built in session 8, tested in session 21, re-audited in session 22.

### 10.1 The two authentication mechanisms

_A **JWT** (JSON Web Token) is a signed token that carries who you are. The server checks the signature instead of looking you up in the database on every request._

| | Human auth | Machine auth |
| --- | --- | --- |
| Credential | Email + password → JWT | API key |
| Lifetime | Access 15 min, refresh 7 days | Until revoked |
| Scope | All orgs the user belongs to | Exactly one environment |
| Can write? | Yes, depending on role | **No** — read-only evaluation |
| Revocation | Delete the refresh token | Set `revoked_at` |
| Stored as | bcrypt (password) | SHA-256 (key) |
| Filter | `JwtAuthenticationFilter` | `ApiKeyAuthenticationFilter` |

**Two completely separate filters, neither aware of the other.** A JWT sent to `/evaluate` fails. An API key sent to `/flags` fails.

It's tempting to merge them "to reduce duplication." Don't. Humans and machines fail in different ways, and keeping their auth paths separate is a feature. Merging them invites the kind of bug where one kind of credential accidentally gets the other kind's permissions.

### 10.2 `TenantContext` — the chokepoint

The one mechanism that makes isolation enforceable rather than hoped-for. Built in session 8.

```java
// Filled in by the auth filter, once per request. Read everywhere.
public record TenantContext(
    UUID userId,
    UUID organizationId,     // ◄── from the verified token. NEVER from the request.
    Role role,
    boolean platformOwner
) {}
```

Every service method that touches tenant data takes its `organizationId` from here:

```java
public FlagEnvironmentState updateState(UUID flagId, UUID envId, StateUpdate u) {
    UUID orgId = tenantContext.organizationId();          // from the token
    var state = repo.findByFlagIdAndEnvironmentIdAndOrganizationId(flagId, envId, orgId)
                    .orElseThrow(NotFoundException::new); // ◄── 404, not 403
    ...
}
```

**The rule:** _the `organizationId` in a query comes from the session — never from the URL, the body, or a header._ A request body containing `"organizationId": "..."` is ignored or rejected. Never trusted.

### 10.3 Why cross-tenant access returns 404, not 403

Subtle, and important.

If Zomato asks for one of Swiggy's flags and we answer **403 Forbidden**, we've told them: _this flag exists, and it isn't yours._ That's already a leak — an attacker could try IDs one after another and map out what exists on the platform.

Answering **404 Not Found** means: _as far as you're concerned, this doesn't exist._ Which is exactly true — within Zomato's tenant, it doesn't.

`403` is still right for a _permission_ failure inside your own tenant — a Developer trying an Admin-only action. They know the resource exists; they just aren't allowed to act on it. `404` is right for a _tenancy_ failure. Two different situations, two different answers.

### 10.4 The mandatory regression test (session 21)

It runs on every commit for the rest of the course.

```java
@Test
void tenantA_cannot_read_tenantB_flag() {
    var tenantA = createTenantWithFlag("org-a", "secret-flag");
    var tenantB = createTenant("org-b");

    mockMvc.perform(get("/api/v1/flags/" + tenantA.flagId())
            .header("Authorization", "Bearer " + tenantB.accessToken()))
           .andExpect(status().isNotFound());       // 404, per §10.3
}
```

It gets extended to every tenant-owned endpoint. When it fails, nothing else ships until it's green. That's how real product teams treat isolation.

---

## 11. Failure modes

Designing for what happens when things break — the difference between a demo and a product.

| What fails | What should happen | Built in |
| --- | --- | --- |
| **Switchly is down** | SDK returns its **default values**; the customer's app works normally. Our outage must never be theirs. | 16 |
| **Switchly is slow** | SDK times out at 2 s, uses its cached or default value, retries with backoff | 16 |
| **Redis is down** | API falls back to Postgres. Slower, still correct. Never a 500. | 18 |
| **Postgres is down** | API returns 503 with a clear error. We can't serve truth we don't have. | 23 |
| **API key revoked mid-flight** | Next evaluation gets 401; SDK uses defaults and logs loudly | 9 |
| **Flag deleted while in use** | `FLAG_NOT_FOUND` + default. Never a crash. | 15 |
| **LLM returns nonsense** | Server validation rejects the proposal; nothing is written | 19 |
| **LLM is down** | AI box shows an error; **every other feature keeps working** | 19 |
| **Two admins edit the same flag** | Last write wins; the audit log shows both. (Optimistic locking would prevent this — out of scope, but worth knowing the name.) | 8 |

The row worth rereading is _Redis is down → fall back to Postgres_. It's a direct consequence of "Redis is never the source of truth" (§9.2), and it's why that rule matters rather than being a slogan.

---

## Three sentences to remember

1. **The tenant ID comes from the session, never from the request.**
2. **Redis is never the source of truth.**
3. **Our outage must not become our customer's outage.**
