# Session 1 — What are we building, and why?

**Phase 0 — Foundations** · No code this session.

This session is about one idea and the vocabulary around it. Everything in the next 24 sessions builds on it.

> **Deploying code and releasing a feature are two different events. A feature flag is what separates them.**

---

## The story

It's Friday, 11 PM. A team of four has spent six weeks building a new checkout flow for their shopping app. It's done and tested. They deploy it.

Twenty minutes later, orders start failing — about one in five, and only for users paying by UPI. Someone says "roll it back." Rolling back means re-deploying last week's version, which takes forty minutes and also removes three unrelated bug fixes that shipped in the same release.

Forty minutes of broken checkout on a Friday night.

Now rewind. Same team, same six weeks, same bug. But this time the new checkout was shipped **switched off**. The code went to production on Tuesday and sat there, doing nothing. On Friday they switched it on — for 5% of users first. Orders started failing. Someone opened a web page and clicked a toggle. **Eleven seconds later it was off.** No deploy. No rollback. Nothing else affected.

Same bug. Same code. The difference between forty minutes and eleven seconds is what we're building this semester.

---

## The light switch

Think about the electrical wiring in a house.

- An electrician running wires through the walls and installing the switchboard — that's **deploying**. Slow, disruptive, and not something you want to do at 11 PM on a Friday.
- Flipping the switch on the wall — that's **releasing**. Instant, reversible, anyone can do it, and if the bulb sparks you flip it straight back.

Many teams start with no switches at all. Every time they want a light on, they call the electrician. Every new feature means rewiring the house.

**A feature flag is a light switch for your code.** You do the wiring once, calmly, ahead of time. Then you control the light from the wall.

And once you have a switch, you can do more than on/off:
- Turn the light on in **one room only** → _targeting_
- Turn it on at **half brightness** → _percentage rollout_
- Turn it off **instantly** at the first sign of trouble → _kill switch_

That's the entire feature set of the product, and it falls straight out of the analogy.

### The one thing to remember

> **Deploy** = the code is on the server.
> **Release** = users can see it.
> **Without flags, these are the same event. With flags, they're separate — and that's the whole point.**

---

## New terms

| Term | Plain-language definition |
| --- | --- |
| **Deploy** | Putting a new version of your code onto the servers where it runs. |
| **Release** | Making a feature actually visible to users — which, with flags, is a separate event from deploying. |
| **Feature flag** (or _feature toggle_) | A switch, stored outside your code, that turns a piece of behaviour on or off without re-deploying. |
| **Boolean flag** | A flag whose value is just on or off, like a light switch. |
| **Dynamic config flag** | A flag whose value is data rather than on/off — a banner's text, a maximum upload size — changeable without a deploy. |
| **Kill switch** | A flag whose only job is to instantly disable something that's misbehaving. |
| **Percentage rollout** | Turning a feature on for a slice of your users — 5%, then 25%, then everyone — instead of all at once. |
| **Targeting rule** | A condition that decides who sees a feature, e.g. "on for users in India, off for everyone else." |
| **A/B test** | Showing two versions of something to two groups to find out which works better. |
| **Environment** | A separate copy of a running system for a different purpose — usually _dev_ (where you experiment), _staging_ (a rehearsal copy), and _prod_ (the real one users touch). |
| **SaaS** (Software as a Service) | Software you rent over the internet instead of installing, like Gmail or Canva. |
| **Tenant** | One customer company using our shared system, whose data must never mix with another customer's. |
| **Multi-tenant** | One running system serving many tenants at once, keeping each one's data separate. |
| **API** (Application Programming Interface) | A way for one program to ask another program for something over the network, with no human involved. |
| **SDK** (Software Development Kit) | A small library a developer installs into _their_ app, so talking to our API is a one-line call instead of hand-written network code. |
| **Console** / **dashboard** | The web screens where a human logs in to see and change things. |

Terms like JWT, RBAC, JSONB, caching and CI/CD are coming — each in its own session, defined when we need it.

---

## Why feature flags exist — four real uses

1. **Ship safely.** Deploy the code switched off, then release gradually: 1% → 10% → 100%, watching errors at each step. This is called a **canary release** — after the canaries once taken into coal mines, which showed signs of trouble before the miners felt it.
2. **Kill switch.** The eleven seconds from the story. When something breaks at 2 AM, the person on call needs a switch, not a deploy pipeline.
3. **Experiment.** A/B testing: half the users see a green button, half see blue, and you keep whichever sells more.
4. **Change settings without a deploy.** Banner text, a file-size limit, a "we're under maintenance" message. These change for business reasons, not engineering ones — and shouldn't need an engineer at all.

**Try this:** think of an app you use that obviously has feature flags. A friend got a new WhatsApp or Instagram feature weeks before you did? An app changed its behaviour without an app-store update? That's a flag.

---

## Multi-tenancy — the hotel

Who pays for Switchly? Not the people buying shoes in an online shop — they've never heard of us. **The shop's developers** pay us. We sell to companies.

Imagine starting a business that gives people a place to sleep in a new city.

> **Option A:** build a separate house for each customer. Total privacy — and also fifty houses to maintain, fifty roofs to fix, and unaffordable prices.
>
> **Option B:** build **one hotel**. Every guest gets a room. Their keycard opens _their_ room and no one else's. The plumbing, the lifts and the staff are shared — that's what makes it affordable. But the separation between rooms has to be _absolute_, because "I walked into another guest's room" isn't a small bug. It's the end of the hotel.
>
> **That's multi-tenant software.** One system, many customer companies, shared infrastructure, strictly separated data. Each customer company is a **tenant**.

> **Tenant A must never, under any circumstances, see Tenant B's data.**

This isn't a bug that makes a page look wrong. It's the kind of bug that ends a company — which is why session 8 is about building it, session 21 has a required test for it, and session 22 re-checks everything.

In the hotel, what's the keycard? Something you're _given_ when you check in, which the _building_ checks — not something you tell the door about yourself. Hold on to that; it's exactly how session 8 works.

---

## Two kinds of people log into Switchly

| | **Tenant Developer** (our customer) | **Owner / Platform Admin** (us) |
| --- | --- | --- |
| Works at | Zomato, Swiggy, a startup | Switchly |
| Wants to | Create and toggle _their own_ flags, get an API key, see their own history | See every tenant, spot who's about to hit limits, investigate problems, suspend accounts |
| Can see | **Only their own organization.** Zomato can't even tell that Swiggy exists. | Everything, across every tenant |
| How many | Many, and growing | A handful |

These are really two different products — different users, different screens, different permissions.

**And we build both as ONE React application.**

Why not two separate apps?
- One login system, one design system, one deployment, one codebase to maintain.
- The screens differ, but the machinery underneath — talking to the API, handling errors, layout — is the same. Two apps would mean fixing every bug twice.
- What actually differs is **which sections your role unlocks**. That's a permissions problem, not an architecture problem.

You can see both consoles in [`ui-screens.md`](../ui-screens.md).

**Something to think about:** Switchly is a tool for developers. Could Switchly use Switchly? (Yes. Real companies do exactly this. It's called _dogfooding_.)

---

## The tech stack — the map, not the territory

You don't need to know any of these yet. This is so you recognise the names when we get there.

| Layer | Choice | What it's for |
| --- | --- | --- |
| Backend | **Spring Boot** (Java) | The program that holds the rules and answers questions. You already know Java — this is a framework, not a new language. |
| Database | **PostgreSQL** | Remembers everything permanently, even when the server restarts. You know SQL; this is that. |
| Cache | **Redis** | A small, very fast memory that saves us asking the database the same question thousands of times a second. (Session 18.) |
| Frontend | **React + Vite + TypeScript** | The screens people click. TypeScript is JavaScript that catches your typos before your users do. |
| Styling | **Tailwind + shadcn/ui** | Ready-made, good-looking components, so we spend our time on behaviour rather than CSS. |
| AI | **Claude, with tool calling** | Lets a developer type "make a flag for the new checkout, off in prod" in plain English. (Session 19.) |
| Packaging | **Docker** | Puts the app and everything it needs in a box, so "works on my machine" becomes "works on every machine." |
| Hosting | **AWS** | Someone else's computers, rented by the hour, reachable from the internet. |
| Pipeline | **GitHub Actions** | Deploys automatically when you push code. (Session 24.) |

### "Is this microservices?"

No — it's one backend application, built in clean modules. Not because microservices are bad:

- Microservices solve a _team_ problem: many teams shipping without waiting for each other. We're one team.
- Our free-tier machine has 1 GB of memory. One Spring Boot app uses about half of it. Six don't fit.
- Splitting early buys you network calls, harder debugging and eight deploy pipelines — in exchange for nothing, at our size.

**But** we'll build one part — the piece that answers "is this flag on?" — as a deliberately separate module that depends on nothing else. It's the one part with genuinely different demands: other companies' apps call it thousands of times a second, and if it's slow, _their_ websites are slow. In session 25 we'll look at exactly when a real company would split it out.

The full reasoning is in [`architecture.md`](../architecture.md#1-monolith-or-microservices).

---

## Where we're going

Three milestones to look forward to:

- **Session 3:** your code is live on the internet, at a real IP address.
- **Session 17:** a separate shop application, plugged into your platform, changes when you flip a switch.
- **Session 25:** you demo the whole thing end to end — and you have a portfolio project most graduates don't.

The full session-by-session plan is in [`architecture.md` §8](../architecture.md#8-what-works-after-each-session).

---

## Common misconceptions

**"A feature flag is just an `if` statement."**
Half true — there is an `if` in the customer's code. But the value it checks is the whole product: it lives outside the codebase, changes without a deploy, differs per environment and per user, and every change is recorded. An `if` on a hard-coded `true` needs a deploy to change. A flag doesn't. That difference is a whole company.

**"Feature flags are the same as configuration."**
A database password in a config file is read once at startup, and changes when you restart. A flag is read _continuously_, changes _while the app is running_, and can be different _for each user_. Overlapping ideas, different lifecycles.

**"Multi-tenant means lots of users."**
Instagram has billions of users and isn't multi-tenant — everyone's in the same pot. Multi-tenancy is about separate _customer organizations_ with walls between them. Many users ≠ many tenants.

**"Two consoles means two projects."**
One app, one login, sections unlocked by role.

**"The people shopping in the demo shop are Switchly users."**
Someone buying shoes has never heard of Switchly and never will. Our customer is the developer who built the shoe shop. This extra layer is the trickiest idea in session 1 — it's fine if it takes a moment to settle.

**"Why not just deploy faster?"**
Even with a 2-minute deploy, you still can't turn a feature on for 5% of users, leave it switched off for three weeks, hand the switch to someone who isn't an engineer, or turn it off without shipping _something_. Speed and control are different things.

---

## Check yourself

<details>
<summary><strong>1. In your own words: what's the difference between deploying and releasing, and why would a company want them separate?</strong></summary>

Deploying puts code on the server; releasing makes the feature visible to users. Separating them means you can ship code switched off, turn it on gradually, and switch it off in seconds if something goes wrong — without another deploy.
</details>

<details>
<summary><strong>2. Zomato and Swiggy are both Switchly customers. What's the worst bug we could possibly ship?</strong></summary>

One of them seeing the other's flags or data. It's worse than the site going down for an hour: downtime is embarrassing, but a data leak between customers destroys trust — and trust doesn't come back.
</details>

<details>
<summary><strong>3. The code for a feature is on the production server, but switched off. Is the feature "done"?</strong></summary>

There's no single right answer — and that's the point. "Done" becomes a business decision (when to release) rather than an engineering one (when to deploy).
</details>

---

## Homework

Due before session 2. The AWS part must be finished before session 3.

### A. Create your AWS account — start tonight ⚠️

You'll need a debit or credit card (a small refundable verification charge, usually ₹2) and a phone number. Activation can take several hours. **Starting this the night before session 3 will not work.**

1. Sign up at `aws.amazon.com` → **Create an AWS Account**.
2. Turn on **MFA** (multi-factor authentication) for the root account — the login you just created. Use an authenticator app.
3. Set a **billing alarm at $5** _and_ an AWS Budgets zero-spend alert. Not optional. We'll be careful, but a billing alarm is a seatbelt.
4. Set your region to **Asia Pacific (Mumbai) / `ap-south-1`**, and never change it.
5. **Submit a screenshot of your billing alarm** on the course sheet.

AWS changed its free tier for new accounts recently. We'll confirm the exact terms for your account in class, before session 3.

### B. Install your development environment

So that session 3 is about deploying, not debugging your laptop:

- **JDK 21** — `java -version` prints 21.x
- **Node.js 20+** — `node -v`
- **Docker Desktop** — `docker run hello-world` succeeds
- **Git** — `git --version`
- An editor: IntelliJ IDEA Community or VS Code

Post one screenshot showing all four version commands in a single terminal window.

### C. One paragraph, in your own words (5 sentences max)

Find a feature in an app you use that you think is controlled by a feature flag. Explain what made you think so.

### D. Optional

Skim the public landing page of LaunchDarkly or Flagsmith — commercial versions of what we're building. Note one thing they do that we haven't discussed, and bring it to session 2.

---

## Checkpoint

By the end of this session, you should be able to explain — without notes:

> **"Deploying means my code is on the server. Releasing means users can actually see the feature. A feature flag is what lets those be two separate events — and it's why a bad feature can be switched off in seconds instead of rolled back in an hour."**

And:
- ✅ Explain what a _tenant_ is, using an analogy of your own.
- ✅ Name the two kinds of people who log into Switchly, and say why Zomato must never see Swiggy's flags.
- ✅ Have your AWS account creation **started** (verification may still be pending).
- ✅ Have your dev environment installed — or know exactly what's failing.

**Next session:** we design the database — and you'll see why the hotel analogy matters.
