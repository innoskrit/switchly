# Switchly — UI Screens

Every screen in the product, end to end, for both kinds of user. These are design mockups — the real app we build in sessions 10–14 follows them.

**Related:** [`design.md`](design.md) · [`architecture.md`](architecture.md)

Two things to notice as you scroll:
- The **dark sidebar with a green mark** is the Tenant Developer console — what a customer like Zomato sees.
- The **brown sidebar with a "Platform Admin" badge** is the Owner console — what *we*, the Switchly team, see.

They are the **same React app**. Same login page, same code, same deployment. Only the signed-in user's role is different.

---

## Flow 1 · Getting in

### 1. Sign up — create an organization
One form creates a user, an organization, a first project, and three environments — all in one database transaction.

![Sign up](ui-screens/01-signup.png)

### 2. Sign in — one door, two destinations
Everyone uses the same login page. After the password is checked, the server looks at the user's role and sends them to a different landing page.

![Sign in](ui-screens/02-login.png)

### 3. First login — nothing here yet
A fresh organization: one project, three environments, zero flags.

![Empty state](ui-screens/03-empty-state.png)

---

## Flow 2 · Tenant Developer console — what Zomato sees

Every screen in this flow is scoped to **one organization**. That scope comes from the signed-in user's token — never from the URL or the request body.

### 4. Flags list — one flag, three switches
One column per environment. The same flag can be on in dev and off in prod — which is why the on/off state lives in its own table, not on the flag.

![Flags list](ui-screens/04-flags-list.png)

### 5. Create a flag
Every new flag starts **off** in all three environments. Nothing changes for users until someone turns it on.

![Create a flag](ui-screens/05-create-flag.png)

### 6. Boolean flag — state per environment
On for everyone in dev and staging; on for 20% of users in prod.

![Boolean flag detail](ui-screens/06-flag-boolean.png)

### 7. Config flag — a JSON value per environment
The banner text lives in Switchly, not in the shop's code. Changing it needs no engineer and no deploy.

![Config flag detail](ui-screens/07-flag-config.png)

### 8. Targeting — rules, rollout, stable bucketing
Rules are checked in order and the first match wins; otherwise, a percentage rollout. The "try a user" panel shows how a given user is bucketed — and why their answer never flickers.

![Targeting rules](ui-screens/08-targeting.png)

### 9. API keys — credentials for machines
Keys are how a customer's *servers* talk to Switchly. Each key unlocks exactly one environment, and can only read.

![API keys](ui-screens/09-api-keys.png)

### 10. Shown once, then never again
We store only a one-way hash of the key. Nobody — not even us — can show it to you again.

![API key created](ui-screens/10-api-key-created.png)

### 11. AI — propose, then confirm
The model *suggests* a flag. Nothing is created until a human clicks, and the server re-checks everything from scratch first.

![AI flag proposal](ui-screens/11-ai-propose.png)

### 12. Audit log — scoped to one tenant
Every change, in order, append-only. Riya sees only Zomato's events.

![Tenant audit log](ui-screens/12-audit-log.png)

---

## Flow 3 · Owner console — what we see

### 13. Every tenant on the platform
Same app as above. The sidebar changed because this user has `platformOwner: true` — nothing else.

![Owner — tenants](ui-screens/13-owner-tenants.png)

### 14. One tenant, and what we deliberately can't see
Counts, members and usage — yes. The actual values of a customer's flags and their targeting rules — no. Running the platform doesn't require reading customers' business logic.

![Owner — tenant detail](ui-screens/14-owner-tenant-detail.png)

### 15. Cross-tenant audit log
The most dangerous screen in the product, and the reason every owner endpoint lives behind its own `/admin` prefix.

![Owner — audit log](ui-screens/15-owner-audit.png)

---

## Flow 4 · The proof

### 16. Demo shop — flag off
A completely separate application that signed up for Switchly like any customer would.

![Demo shop, flag off](ui-screens/16-shop-off.png)

### 17. Demo shop — flag on, no deploy
Someone clicked a toggle in another browser tab. No code change, no deploy, no restart.

![Demo shop, flag on](ui-screens/17-shop-on.png)

### 18. Tenant isolation — the whole point
Same URL, two companies, two different answers. And when one company guesses the other's flag ID, it gets `404 Not Found`.

![Tenant isolation](ui-screens/18-isolation.png)

---

> **The tenant id comes from the session. Never from the request.**
