# Switchly — Architecture & Build Plan

How Switchly is structured, what gets deployed where, and how the whole system grows over 25 sessions.

**Related:** [`design.md`](design.md) (requirements, API, schema, security) · [`ui-screens.md`](ui-screens.md) (every screen)

---

## 1. Monolith or microservices?

> **A modular monolith. One backend service, four deployable artifacts.**

You've probably heard that modern systems are built as microservices. Here's why this one isn't — and it's not because microservices are bad.

### What we're building

**One** Spring Boot application, internally divided into strict modules with enforced package boundaries. Not a ball of mud, and not eight services.

```
                  ┌──────────────────────────────┐
                  │   switchly-api               │
                  │   (ONE Spring Boot process)  │
                  │                              │
  Tenant devs ───►│  identity  tenancy  flags    │
  Platform team ─►│  targeting  apikeys  audit   │
  Demo app SDK ──►│  evaluation ◄── hot path     │
                  │  ai                          │
                  └──────────┬───────────────────┘
                             │
                  ┌──────────┴──────────┐
                  │  Postgres    Redis  │
                  └─────────────────────┘
```

### Why not microservices

Four reasons. The last one matters most.

1. **Our scale doesn't need it.** Microservices solve an _organizational_ problem — many teams shipping independently without coordinating their deploys. We are one team. Splitting a codebase that one team owns buys you network calls, distributed transactions, and eight deploy pipelines, in exchange for nothing.
2. **The free tier physically can't host it.** A `t3.micro` has 1 GB of RAM. One Spring Boot JVM comfortably uses 400–600 MB. Six of them don't fit. Microservices would mean six instances or Kubernetes — either way, real money, on your personal AWS account.
3. **It would eat the course.** Service discovery, service-to-service auth, distributed tracing, and "why did my transaction half-commit across two services" are each worth a full session. We'd rather spend those sessions on multi-tenancy, targeting, auth and deployment — the things this product actually needs.
4. **Splitting too early is the more common real-world failure.** Far more teams have been hurt by splitting too early than by splitting too late. The industry lesson of the last decade: _build a modular monolith first, and split along a seam you've actually measured._ Reaching for microservices by reflex is a habit worth unlearning early.

### Where the seam is — and when we'd split

This matters more than the decision itself.

Switchly has **two workloads that behave very differently**:

| | Control plane | Evaluation plane |
| --- | --- | --- |
| **What** | Create/edit flags, manage users, API keys, audit | `POST /evaluate` — "is flag X on for user Y?" |
| **Who calls it** | Humans in a browser | Machines, via the SDK |
| **Auth** | JWT (human, session-based) | API key (per-environment, machine) |
| **Traffic** | Dozens of requests/day per tenant | Potentially thousands/second |
| **Latency budget** | 500 ms is fine | Single-digit ms — it's inside the customer's own request |
| **If it goes down** | Annoying: nobody can edit flags | **Catastrophic: customer apps stall** |
| **Read/write mix** | Mixed | ~100% reads, cacheable |

Those last three rows are what a real split is made of. So:

- We build `evaluation` as its **own module**, from session 15 — its own package, its own auth filter, its own cache, and **no compile-time dependency on the console modules**. It reads the same database and talks to nothing else.
- Because of that, pulling it out into a separate service later is a build-file change and a deployment change — not a rewrite.
- **In session 25 we'll ask:** _at what point would you actually split this?_ A good answer: when evaluation traffic forces you to scale the whole monolith for a workload that's 1% of the code — or when a bug in the console takes down evaluation. Not "when we have 10,000 users."

This is one of the most interview-worthy ideas in the course. _"We kept it a monolith, but drew the seam at the evaluation path — here's why"_ is far more credible than _"we used microservices."_

### What actually ships

Four things get deployed. Only one of them is a backend service.

| # | Artifact | What it is | Runs where | From session |
| --- | --- | --- | --- | --- |
| 1 | `switchly-api` | Spring Boot monolith | EC2 (Docker) | 3 |
| 2 | `switchly-web` | React SPA with two role-gated consoles | nginx on EC2 → later S3 + CloudFront | 3 (stub), 10 (real) |
| 3 | `switchly-sdk` | JS/TS client library | Installed inside customer apps | 16 |
| 4 | `switchly-demo-shop` | A toy shop that signs up as a real tenant | Same EC2, separate container | 17 |

Plus two pieces of infrastructure that aren't our code: **Postgres** and **Redis**.

**What is the SDK, exactly?** An **SDK** (Software Development Kit) is a small library a developer installs into _their own_ app, so that asking Switchly a question is one line instead of hand-written network code. It's the only one of the four that doesn't run on our servers. It holds the API key, caches answers in memory, polls for changes, and — most importantly — falls back to safe default values if Switchly is unreachable. See [`design.md` §7](design.md#7-the-evaluate-contract) and [§11](design.md#11-failure-modes).

---

## 2. Modules inside the monolith

Package root: `live.switchly.api`. Each module is a package that exposes a service interface or two; everything else inside it is package-private. The rule, from session 4 onward: **modules talk to each other through service interfaces, never by reaching into each other's repositories.**

| Module | Owns | Built in |
| --- | --- | --- |
| `platform` | Config, global error handling, `TenantContext`, base entities | 3–4 |
| `tenancy` | Organization, Project, Environment | 4 |
| `flags` | Flag, FlagType, variations, JSONB config values | 5–6 |
| `identity` | User, signup/login, bcrypt, JWT, roles | 7–8 |
| `apikeys` | Per-environment machine credentials, hashing, rotation | 9 |
| `targeting` | Rules, percentage rollout, stable bucketing | 15 |
| `evaluation` | `POST /evaluate`, API-key filter, cache read | 15, 18 |
| `audit` | Append-only audit log; cross-tenant read for the Owner | 8, 13 |
| `ai` | Tool calling, `createFlag` proposals, natural-language search | 19–20 |

**A note on `TenantContext`:** introduced in session 8 as the single chokepoint that makes tenant isolation enforceable. Every repository query for tenant-owned data takes its scope from it. The entire security story of the product rests on this one idea — which is why it gets its own session and its own dedicated test in session 21.

---

## 3. "Environment" means two different things

This trips almost everyone up, so it's worth pinning down now.

- **Switchly's _product_ environments (dev / staging / prod)** — rows in a database table. A tenant's flag can be on in their dev environment and off in prod. This is a _feature we sell_. It isn't infrastructure.
- **Our _deployment_ environments** — where our own code actually runs. In this course there are exactly two: your laptop, and your one EC2 machine. We don't build a staging cluster; it would cost money and teach nothing new.

So "Switchly has three environments" and "Switchly is deployed to one environment" are both true, and they don't contradict each other.

---

## 4. Repository layout

One repo, several folders. No monorepo tooling (Turborepo/Nx) — at this size it would cost more than it saves.

```
switchly/
├── api/                    # Spring Boot
│   ├── src/main/java/live/switchly/api/
│   │   ├── platform/  tenancy/  flags/  identity/
│   │   ├── apikeys/  targeting/  evaluation/  audit/  ai/
│   ├── src/main/resources/db/migration/   # Flyway — V1__*.sql onward, from session 4
│   └── Dockerfile
├── web/                    # React + Vite + TypeScript
│   ├── src/routes/  src/components/  src/api/  src/lib/
│   └── Dockerfile
├── sdk/                    # switchly-js — plain TypeScript, no framework dependencies
├── demo-shop/              # the toy shop that becomes a real tenant
├── infra/
│   ├── docker-compose.yml          # local development (session 3)
│   ├── docker-compose.prod.yml     # what actually runs on EC2 (session 3, grows)
│   └── deploy.sh                   # manual deploy, replaced by CI in session 24
├── .github/workflows/      # from session 24
└── docs/                   # you are here
```

**Database migrations start in session 4, not later.** _Flyway applies numbered `.sql` files to your database in order, so every machine ends up with the same schema._ It goes in the moment there's a second table — adding it later, after everyone's local database has drifted, is painful.

---

## 5. How the infrastructure grows — four stages

We deploy to the real internet in session 3, before there's a single real feature. That's deliberate: deploying early and often makes AWS stop being scary. After that, the infrastructure grows in place.

### Stage 0 — Local only (sessions 1–2)
Nothing deployed. Your laptop, Docker Desktop, Postgres in a container.

### Stage 1 — One machine, everything on it (sessions 3–17)

```
        Internet
           │
           ▼
   ┌───────────────────────── EC2 t3.micro (public subnet) ─────────┐
   │                                                                │
   │   nginx:80  ──────► static React build (stub → real in S10)    │
   │      └── /api/* proxy ──► switchly-api:8080  (Docker)          │
   │                                    │                           │
   │                              postgres:5432   (Docker + volume) │
   └────────────────────────────────────────────────────────────────┘
      Security group: 22 (your IP only), 80 (world). Nothing else.
```

One `docker-compose.prod.yml`, one `git pull && docker compose up -d --build`. Deliberately simple. Postgres runs in a container with a mounted volume — **not** RDS yet, because RDS costs money and we don't need it yet.

### Stage 2 — Add the cache (session 18)
Redis joins as another container on the same machine, as a cache for flag evaluation. Still one instance. This is where you'll see the latency difference for yourself.

### Stage 3 — Real managed infrastructure (session 23)

```
        Internet
           │
           ▼
   ┌──── EC2 t3.micro ────┐        ┌─────────────────┐
   │  nginx + api + redis │───────►│  RDS Postgres   │
   │  (IAM instance role) │        │  db.t4g.micro   │
   └──────────┬───────────┘        │  single-AZ      │
              │                    └─────────────────┘
              └──► SSM Parameter Store (DB password, JWT secret, LLM key)
```

What changes: the Postgres container → **RDS** (Amazon's managed Postgres); hardcoded secrets → **SSM Parameter Store** (a safe place to keep them); broad permissions → an **IAM instance role with least privilege** (only the permissions it actually needs). RDS accepts connections _only_ from the EC2 machine's security group, never from the internet.

**Redis stays in a container on the EC2 machine.** Amazon's managed Redis (ElastiCache) has no meaningful free tier and would be the biggest line on your bill. This is a cost decision, and it's worth seeing it plainly: here's the managed option, here's what it costs, here's why we're not using it.

### Stage 4 — CDN and pipeline (session 24)

```
   Browser ──► CloudFront ──► S3 (React build)
      │
      └─────► EC2 (API only; nginx now just forwards to the API)

   git push main ──► GitHub Actions ──► build ──► push ──► deploy over SSH
                                     └──► aws s3 sync + CloudFront invalidation
```

The frontend moves off EC2 into S3 + CloudFront. _A **CDN** (content delivery network) is a network of servers near your users that serve your static files fast._ GitHub Actions replaces `deploy.sh`, so pushing to `main` deploys. A smoke test runs at the end.

### Real, and deliberately not built here

Kubernetes, load balancers, auto-scaling groups, multi-AZ, NAT gateways, Terraform, service meshes, blue-green deploys. Each one is real, each one costs money or a session or both, and none is needed to make this product work. They're the natural next steps after this course.

**No NAT gateway, ever.** It costs about $32/month, it's one of the most common ways people accidentally spend real money on AWS, and it only exists to give private subnets internet access — which we avoid by keeping EC2 in a public subnet behind a tight security group.

---

## 6. Keeping your AWS bill at zero

You're on your own AWS account, with your own card attached. Treat cost as a first-class topic, not an afterthought.

**Set these up before session 3:**
1. A billing alarm at **$5**, plus an AWS Budgets zero-spend alert.
2. MFA on your root account. Do day-to-day work as an IAM user, never as root.
3. One region for the whole course: **`ap-south-1` (Mumbai)**. Never change it. Resources left running in a forgotten region are one of the most common sources of mystery charges.
4. **Stop your EC2 instance between sessions.** A stopped instance costs nothing for compute. You'll learn `aws ec2 stop-instances` in session 3 — make it a habit.

**The most you'll run at once, for the whole course:** 1 × `t3.micro`, 1 × `db.t4g.micro` single-AZ with 20 GB gp3, 1 S3 bucket, 1 CloudFront distribution, and 1 Elastic IP — **always attached to your instance**. An Elastic IP that isn't attached to anything is billed, and it's a classic surprise charge.

> **Check your own free-tier terms.** AWS changed its free tier for newly created accounts in 2025, so the rules that apply to your brand-new account may differ from what older tutorials describe. Read the current AWS free-tier page for your account — we'll confirm the numbers in class.

---

## 7. How the data model grows

Tables appear only when a session needs them.

| Session | Tables added | Note |
| --- | --- | --- |
| 2 | _(none — on paper)_ | ER diagram |
| 3 | `flyway_schema_history` | Baseline migration only |
| 4 | `organization`, `project`, `environment` | The tenancy spine |
| 5 | `flag`, `flag_environment_state` | Boolean on/off, per environment |
| 6 | + `value` JSONB on `flag_environment_state` | Dynamic config |
| 7 | `app_user`, `refresh_token` | bcrypt hashes, never plaintext |
| 8 | `membership` (user ↔ org ↔ role), `audit_log` | Roles + tenant isolation |
| 9 | `api_key` | Store a **hash** of the key; show the plaintext exactly once |
| 15 | `targeting_rule` | Ordered rules per flag + environment |
| 19 | `ai_proposal` | Record of what the model suggested |

**Multi-tenancy strategy: one shared database, one shared schema, and an `organization_id` column on every tenant-owned table.** Chosen in session 2 over two alternatives:
- _Database per tenant_ — strong isolation, but operationally heavy and expensive, and pointless at our scale.
- _Schema per tenant_ — a migration nightmare once you have 200 tenants.

Every tenant-owned table carries `organization_id`, every query filters on it, and session 21 adds a required test that fails if any endpoint leaks data across tenants.

---

## 8. What works after each session

The one thing that must be working when each session ends. If yours isn't, fix it before the next session — each one builds directly on the last.

| # | Session | Checkpoint |
| --- | --- | --- |
| 1 | What are we building | You can explain _deploy vs release_ in your own words; dev environment installed |
| 2 | Data modeling & multi-tenancy | You can draw the ER diagram and defend `organization_id` |
| 3 | Vertical slice on AWS | **A public IP serves your own page, and `/api/health` returns JSON** |
| 4 | Org/project/env API | `POST /organizations` → nested project → environment, all saved |
| 5 | Boolean flag CRUD | Create a flag in an environment, toggle it, `GET` shows the change |
| 6 | Dynamic config flags | A flag holds `{"banner":"Sale!"}` in JSONB and comes back intact |
| 7 | Auth basics | Signup → login → JWT → a protected endpoint rejects a missing token |
| 8 | Roles & tenant isolation | **Tenant A's token gets 404 on Tenant B's flag** |
| 9 | API keys | `curl` with an API key authenticates; with a JWT it doesn't |
| 10 | Frontend foundations | Log in in the browser; token stored; a protected route redirects |
| 11 | Developer console — flags | Toggle a flag in the UI, refresh, and it stuck |
| 12 | Config flags & keys UI | Edit JSON config in the UI; generate a key, shown once |
| 13 | Owner console | Owner sees every tenant; a tenant developer visiting `/owner` is denied |
| 14 | UX polish | Every screen has real loading, error, and empty states |
| 15 | Evaluation engine | The same user ID always gets the same answer on a 30% rollout |
| 16 | Client SDK | `switchly.isEnabled('x')` works from a blank Node script |
| 17 | Demo app | The toy shop signs up as a tenant, integrates the SDK, and changes behaviour |
| 18 | Caching | Evaluation latency visibly drops; an edit shows up within seconds |
| 19 | AI flag creation | The text box proposes `createFlag`; **nothing changes without confirmation** |
| 20 | AI search | "config flags changed this week" returns the right list |
| 21 | Testing | All tests green, **including the tenant-isolation test** |
| 22 | Security hardening | The rate limit returns 429; no secret is in git; scoping re-checked |
| 23 | Real AWS infrastructure | App runs against RDS, secrets come from SSM, the DB container is gone |
| 24 | CI/CD | `git push main` deploys; the smoke test passes |
| 25 | Demo day | End to end: toggle in the console → the shop changes, live |

---

## 9. Decision log

Decisions made once and referenced everywhere. If you ever wonder "why did we do it this way?", the answer should be here.

| # | Decision | Why |
| --- | --- | --- |
| D1 | Modular monolith, not microservices | One team, 1 GB of RAM, course time better spent elsewhere |
| D2 | Shared database + `organization_id` | Simplest correct isolation at our scale |
| D3 | Evaluation as an isolated module | The one real seam; it's what makes D1 defensible |
| D4 | Postgres in a container until session 23 | Cost; RDS complexity isn't needed before then |
| D5 | Redis in a container, never ElastiCache | No meaningful free tier |
| D6 | Deploy in session 3, before any real feature | Removes deployment fear early |
| D7 | Flyway migrations from session 4 | Schema drift across many laptops is unrecoverable |
| D8 | One LLM provider (Claude), picked once | Consistency beats comparison |
| D9 | API keys stored hashed, shown once | Mirrors real practice; the irreversibility is the lesson |
| D10 | No NAT gateway; public subnet + tight security group | ~$32/month, and the most common accidental AWS spend |
| D11 | AWS account set up as homework after session 1 | Verification takes time; unblocks session 3 |
| D12 | SDK polls for changes; push is future work | Streaming is real, but not needed for this product |
