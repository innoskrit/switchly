# Switchly

**A multi-tenant feature flag platform — built from scratch, one session at a time.**

Switchly lets other companies' applications turn features on and off remotely, without redeploying their code. It's the running project for a 25-session course on AI-first software engineering, and this repository grows as the course does.

> **Deploying code and releasing a feature are two different events. A feature flag is what separates them.**

---

## What it does

- **Two kinds of flags** — boolean (on/off) and dynamic config (any JSON value: banner text, a numeric limit).
- **Targeting** — percentage rollouts and user-attribute rules, with stable bucketing so the same user always gets the same answer.
- **Multi-tenancy** — unrelated companies sign up and each get their own isolated organizations → projects → environments (dev/staging/prod) → flags.
- **Two consoles, one app** — a Tenant Developer console for our customers, and an Owner console for the platform team, in a single React application.
- **Two kinds of auth** — email + password + JWT for humans; per-environment API keys for machines.
- **A client SDK** — a small JS/TS library that customer apps install to evaluate flags, with caching, polling and safe defaults.
- **A demo shop** — a separate toy app that signs up as a real tenant and visibly changes when a flag is flipped.
- **A small AI layer** — create flags from a sentence (with human confirmation), and search flags in plain English.

## Documentation

| Document | What's in it |
| --- | --- |
| [`docs/design.md`](docs/design.md) | Requirements, domain model, database schema, full API spec, the evaluation contract, why Redis, security |
| [`docs/architecture.md`](docs/architecture.md) | Monolith vs microservices, modules, how the AWS infrastructure grows, what works after each session |
| [`docs/ui-screens.md`](docs/ui-screens.md) | Every screen, for both personas, end to end |
| [`docs/sessions/`](docs/sessions/) | Notes for each session |

## Tech stack

| Layer | Choice |
| --- | --- |
| Backend | Spring Boot (Java 21), PostgreSQL (JSONB for config values) |
| Cache | Redis |
| Frontend | React + Vite + TypeScript, Tailwind CSS + shadcn/ui, React Router, TanStack Query, React Hook Form + Zod |
| AI | Claude, via tool calling |
| Infrastructure | Docker + Docker Compose, AWS (EC2, RDS, S3 + CloudFront, IAM, SSM), GitHub Actions |

## Repository layout

```
switchly/
├── api/          # Spring Boot backend
├── web/          # React app — both consoles
├── sdk/          # JS/TS client library
├── demo-shop/    # a toy shop that integrates the SDK
├── infra/        # Docker Compose, deploy scripts
└── docs/         # you are here
```

Folders appear as the course reaches them.

## Course sessions

| Phase | Sessions |
| --- | --- |
| **0 — Foundations** | 1 What are we building · 2 Data modeling & multi-tenancy · 3 Deployed to AWS on day one |
| **1 — Backend core** | 4 Orgs, projects, environments · 5 Boolean flags · 6 Config flags · 7 Authentication · 8 Roles & tenant isolation · 9 API keys |
| **2 — Frontend** | 10 Frontend foundations · 11 Flags console · 12 Config flags & keys UI · 13 Owner console · 14 UX polish |
| **3 — Make it real** | 15 Evaluation engine · 16 Client SDK · 17 Demo app · 18 Caching |
| **4 — AI layer** | 19 AI flag creation · 20 AI search |
| **5 — Hardening** | 21 Testing · 22 Security · 23 Real AWS infrastructure · 24 CI/CD |
| **6 — Capstone** | 25 Demo day |
