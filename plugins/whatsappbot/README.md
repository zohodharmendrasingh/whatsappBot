# FloChat — WhatsApp automation SaaS (Apache OFBiz 24.09 plugin, PostgreSQL)

Multi-tenant WhatsApp chatbot platform built as an OFBiz plugin (`plugins/whatsappbot`).
FloChat (https://flochat.flolink.ai) runs one platform, each customer business is a **tenant** with its own
WhatsApp numbers, bot flows, inbox, templates, API keys and plan limits.

## What's inside

| Area | Features |
|---|---|
| **SaaS** | Tenants, plans (Starter $19 / Growth $49 / Pro $99, USD), PayPal checkout (monthly or yearly), monthly usage counters, quota + subscription-status enforcement, tenant users (owner/agent) isolated to their own data, platform-admin tenant switcher |
| **WhatsApp Cloud API** | Webhook with `X-Hub-Signature-256` verification, async processing, duplicate protection, delivery status tracking (sent/delivered/read/failed), read receipts, **Embedded Signup** (Tech Provider) onboarding or manual token |
| **Bot builder** | Flows triggered by keywords or as default reply. Step types: TEXT, IMAGE, BUTTONS (≤3), LIST (≤10), ASK (saves answer to a variable, optional regex), HANDOFF (to human), GOTO_FLOW, END. `{{name}}`, `{{phone}}`, `{{anyVariable}}` placeholders. STOP/START opt-out handling, MENU restart |
| **Inbox** | WhatsApp-style conversation view, agent reply, take over / hand back to bot, 24-hour window indicator, template send outside window |
| **Templates & broadcast** | Sync templates + approval status from Meta, send approved template to a list of numbers |
| **REST API** | `POST /api/v1/messages`, `GET /v1/contacts`, `GET /v1/messages`, API-key auth (keys stored SHA-256 hashed) — call it from Zoho Deluge / Flow |

## SaaS experience

* **Public website** at `https://flochat.flolink.ai/` (`/control/home`): landing page, pricing read live from
  the Plans table, FAQ, and **self sign-up** (`/control/signup`). Sign-up creates a trial workspace
  (`saas.trial.days`, default 14), the owner login (email) and a starter "Welcome menu" bot, then signs the owner in.
  Spam guards: hidden honeypot field + `saas.signup.max.per.hour` per IP. Turn off with `saas.signup.enabled=false`.
* **Customer app**: left sidebar (Dashboard, Inbox, Bot Flows, Templates, Broadcasts, WhatsApp Numbers, Team & Profile,
  API & Integrations), plan/usage/trial card, dashboard with 14-day chart, delivery rate and onboarding checklist,
  split-view inbox with search and Unread / Needs-agent filters.
* **Platform admin console** (WABOT_ADMIN only): Overview (customers, MRR, sign-ups, trials ending, most active),
  Customers, Plans & Pricing, Webhook Log, and a workspace switcher to open any customer's workspace.
* **Owners vs agents**: owners manage business profile, team members and API keys; agents work the inbox and flows.

## Deploy to a server (Ubuntu 22.04 / 24.04)

The private GitHub repo `zohodharmendrasingh/whatsappBot` holds the complete OFBiz 24.09 with this plugin
in `plugins/whatsappbot`, including its settings.

1. Point the DNS A record of `flochat.flolink.ai` at the server.
2. Create a GitHub token with read access to the repo (GitHub > Settings > Developer settings > Fine-grained tokens).
3. On the server:
   ```bash
   curl -fsSL -H "Authorization: token <GH_TOKEN>" \
     https://raw.githubusercontent.com/zohodharmendrasingh/whatsappBot/main/plugins/whatsappbot/deploy/install.sh -o install.sh
   sudo GH_TOKEN=<GH_TOKEN> DOMAIN=flochat.flolink.ai EMAIL=info@msoftdynamic.com bash install.sh
   ```
   Installs Java 17, PostgreSQL, nginx and an SSL certificate, clones the repo to `/opt/flochat/ofbiz`, creates the
   database from `entityengine.xml`, loads the data and starts the `flochat` service.
4. Updates: commit and push from your Mac, then on the server `sudo bash /opt/flochat/ofbiz/plugins/whatsappbot/deploy/update.sh`
   (add `LOAD_SEED=1` when plans or seed data changed).

Logs: `journalctl -u flochat -f` and `/opt/flochat/ofbiz/runtime/logs/ofbiz.log`.

## Install (local development)

```bash
cd ofbiz                                   # OFBiz 24.09 root
cp -R /path/to/whatsappbot plugins/        # (already done if you received it in plugins/)

# 1. PostgreSQL (creates role+db, switches entityengine.xml to localpostgres)
plugins/whatsappbot/scripts/setup-postgres.sh ofbiz ofbiz 'StrongPassword' 127.0.0.1

# 2. Load seed + demo data and start
./gradlew cleanAll loadAll
./gradlew ofbiz
```

Open **https://localhost:8443/** — login `admin` / `ofbiz` (platform admin)
or `wademo` / `ofbiz` (demo tenant user). Change both passwords before going live.

The PostgreSQL JDBC driver is added by `plugins/whatsappbot/build.gradle`, so the root
`build.gradle` does not need changes.

## Connect Meta

1. developers.facebook.com → your app → WhatsApp. Put App ID / App Secret in
   `plugins/whatsappbot/config/whatsappbot.properties` (`meta.app.id`, `meta.app.secret`).
2. Webhook: Callback URL `https://flochat.flolink.ai/webhook`, Verify token = `webhook.verify.token`,
   subscribe to **messages**. Meta requires HTTPS with a valid certificate (use Nginx + Let's Encrypt
   in front of OFBiz on the VPS).
3. Tenants add numbers from **WhatsApp Numbers**:
   * **Embedded Signup** (Tech Provider): set `meta.embedded.signup.config.id` and add your domain to
     the app's *Allowed domains* for Facebook Login for Business.
   * **Manual**: phone number id, WABA id and a permanent system-user token.
4. Build a flow in **Bot Flows** (mark one *Default*), send "hi" to the number.

## Billing with PayPal

Set `paypal.mode`, `paypal.client.id`, `paypal.client.secret` in `config/whatsappbot.properties`
(developer.paypal.com → Apps & Credentials; sandbox keys with `sandbox`, live keys with `live`).
Owners open **Plan & Billing**, pick a plan and monthly/yearly (yearly = `paypal.yearly.months.charged` months,
default 10) and pay with PayPal or card. The server creates the PayPal order with the price from `WaPlan`,
captures it itself, checks amount and currency, then activates the workspace and extends
`subscriptionThruDate`. Every attempt is stored in `WaPayment`; the admin Overview lists recent payments.

## Security model

* Permission `WABOT_ADMIN` (group `WABOT_ADMIN`, also granted to `SUPER`) → everything.
* Group `WABOT_TENANT` → `WABOT_VIEW/CREATE/UPDATE/DELETE`, but every service goes through
  `waTenantPermissionCheck`, which resolves the tenant from `tenantId`/`channelId`/`flowId`/`contactId`/…
  and only allows tenants linked to the login in `WaTenantUser`. Screens filter by the same tenant.
* Inside a tenant, **owners** (`WA_OWNER`) manage users and API keys; **agents** (`WA_AGENT`) work the inbox and flows.
* A number can only be added if its token can read it from Meta **and** it belongs to the given WABA id
  (stops one tenant from squatting another business's phone-number id).
* Channel access tokens are stored with OFBiz field encryption (`encrypt="true"`) and never
  rendered back into forms.
* Public endpoints `/webhook` and `/api/*` are plain servlets outside the ControlServlet.

## Timezone note (India)

macOS/Java report the zone as `Asia/Calcutta`, which PostgreSQL rejects
(`FATAL: invalid value for parameter "TimeZone"`). `setup-postgres.sh` pins OFBiz's JVM to
`Asia/Kolkata` through `jvmArgs=` in the OFBiz `gradle.properties`. On an existing install add
`-Duser.timezone=Asia/Kolkata` to your JVM arguments yourself.

## Tested

Verified on OFBiz 24.09 + PostgreSQL 16 with a mock Meta Graph API: 89 end-to-end checks covering
webhook verification and signatures, the full demo bot flow (buttons, list, typed choices,
questions with validation, variables, hand-off), agent inbox, delivery statuses, 24-hour window,
template sync and broadcast, REST API, STOP/START opt-out, plan limits, suspended tenants,
tenant isolation (including owner vs agent) and rendering of every screen for both roles.

## Housekeeping

Schedule `waPurgeWebhookLogs` daily (Webtools → Job Scheduler), `daysToKeep` default 15.

## Data model

`WaPlan`, `WaTenant`, `WaTenantUser`, `WaUsage`, `WaApiKey`, `WaChannel`, `WaContact`,
`WaMessage`, `WaFlow`, `WaFlowNode`, `WaFlowNodeOption`, `WaTemplate`, `WaWebhookLog`
(see `entitydef/entitymodel.xml`). Tables are created automatically in PostgreSQL on start.

## Not included yet (next steps)

PayPal recurring subscriptions (auto-renew) and refunds webhook, media download/upload
to OFBiz content, per-tenant webhooks to push inbound messages to the tenant's systems,
AI replies on fallback.

## flolink.ai landing page

* **https://flolink.ai** is the product landing page for FloChat, FlowLinker and BillEase: a static page in
  `deploy/flolink-site/`, served by nginx from `/var/www/flolink`. `www.flolink.ai` redirects to it.
* **FloChat stays on https://flochat.flolink.ai**, so there's nothing to change in Meta, Zoho or PayPal.
* **app.flolink.ai** is FlowLinker on Zoho Catalyst (GoDaddy CNAME). **app.msoftdynamic.com** is BillEase.
* DNS (GoDaddy): `@` A `103.48.51.17`, `www` CNAME `@`, `flochat` A `103.48.51.17`, `app` CNAME Catalyst.
* Install or update the page: `sudo bash /opt/flochat/plugins/whatsappbot/deploy/setup-flolink-site.sh`
  (copies the page, writes the nginx config using the existing certificate, and keeps FloChat's address settings).
