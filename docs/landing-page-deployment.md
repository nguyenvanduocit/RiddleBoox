# RiddleBoox landing page

This document is the source of truth for the public landing page and its Cloudflare Pages deployment.
It exists so a future agent can update or redeploy the site without having to rediscover the project
name, domain, directory layout, or DNS constraints.

## What is deployed

- Static source directory: `pages/`
- Entry point: `pages/index.html`
- Styles: `pages/styles.css`
- Small progressive-enhancement script: `pages/script.js`
- Hero visual: the CSS BOOX page simulation in `pages/index.html` and `pages/styles.css`
- Retained source reference: `pages/hero-companion-v2.png` (not mounted behind the hero device)
- Handwriting display font reused from the Android app: `pages/DancingScript.ttf`
- Product proof assets: the existing `pages/page-*.png` handwriting samples
- Cloudflare Pages project: `riddleboox`
- Production alias: `https://riddleboox.pages.dev`
- Custom hostname: `https://riddleboox.aiocean.io` (DNS is case-insensitive; lowercase is equivalent)
- Android CTA: set `androidDownloadUrl` in `pages/script.js` when the APK or Play Store URL is ready

## Domain inventory

The Cloudflare account used for this project currently manages these root zones:

- `aiocean.io`
- `aiocean.dev`
- `leadaship.io`
- `phúcthư-16072023.vn`

RiddleBoox uses `riddleboox.aiocean.io`. Its Pages custom domain is registered on the `riddleboox`
project, and the DNS record in `aiocean.io` is:

```text
CNAME  riddleboox  riddleboox.pages.dev  Proxied  Auto
```

Do not switch this project back to `RiddleBoox.aiocean.app` without first moving or adding the
`aiocean.app` zone to the same Cloudflare account. That domain currently uses the external
nameservers `ns1.helloserver.win` and `ns2.helloserver.win`.

The site is deliberately framework-free. There is no build step, package manager, or generated
output to keep in sync. Deploy the contents of `pages/` as-is.

The ritual and memory examples are HTML/CSS page states using the app's Dancing Script font. They
are intentionally not random exported test screenshots: readable example copy is part of the
product story, so update it with the same companion voice when the narrative changes. The older
`page-*.png` files remain available as raw handwriting samples but should not be placed into a
hero/card just because they are available.

The hero is a large BOOX page simulation rather than a raster image with a small overlay.
`pages/script.js` cycles it through two short turns. Each turn has a user-write state (with the
stylus tracking the two handwritten lines), dissolve, thinking, AI reply (without a stylus), and a
short blank pause before the next turn. CSS supplies the word reveal, ink fade, scan sweep, fairy
dust, and pulse effects in `pages/styles.css`. Line breaks are explicit in each stage's `lines`
array, so copy changes should update those arrays rather than relying on panel width to wrap the
message. Visitors who prefer reduced motion receive the final reply state without the loop.

## Content model

The page uses the product facts already documented in `README.md` and the research notes, but its
main story is the product belief behind them:

> RiddleBoox is a real AI companion that grows with what you write. It can be a teacher, friend,
> reading partner, or helper; handwriting is the interface because writing changes how people
> think, not merely how they enter text.

The “Agent support” section should make the app's extensibility concrete with examples such as:

- a book agent that searches the library and can download a chosen title;
- an English tutor that corrects and practices from the writer's own pages;
- a reflection companion that helps name feelings and think through next steps, without claiming
  to replace professional mental-health care;
- a sharing companion that gives the writer a consistent place to talk and return to earlier pages.

These are use-case examples, not four hard-coded built-ins. The app's agent model supports custom
names, prompts, greetings, private workspaces, and capability sets; the landing page should present
the examples as possible agents while keeping that distinction clear.

The primary visual story is one person confiding in a patient friend: “Today, something feels
heavy.” The page mockups follow the same thread from first sentence, through a quiet pause, to a
comforting reply and a remembered return. The agent cards keep the broader roles visible—book,
English, reflection, and sharing—but should continue to feel like different ways the same
companion can be there.

The download block is intentionally link-safe while the Android build is still being prepared. It
shows the Android icon and the CTA, but keeps the current `#download` placeholder instead of
pointing visitors to a guessed or broken URL. Once the APK or Play Store link exists, set
`androidDownloadUrl` at the top of `pages/script.js`; the header, download block, and final CTA all
switch to that URL automatically.

The page then grounds that belief in these implemented/product-facing facts:

1. handwriting is the primary input and a thinking practice, not just text entry;
2. a pause starts the diary turn;
3. a vision-capable OpenAI-compatible endpoint reads the page image;
4. the response is rendered back as handwriting on the BOOX page;
5. conversations, agents, BOOX notes, library context, and book downloads are supporting capabilities.

When changing copy, keep this boundary. It is okay to describe the companion vision and the roles
the existing agents can take, but do not promise a public app download until the link is live, a
hosted API, a specific model, or a feature that has not been implemented in the Android app. Keep
“grows with you” tied to persisted conversations/agents and the local BOOX/library context rather
than implying human memory or emotional consciousness.

## Local preview

From the repository root:

```bash
python3 -m http.server 4173 --directory pages
```

Open `http://127.0.0.1:4173/`. The page has no server-side behavior, so this is enough to check
layout, images, navigation, accordion behavior, the hero's multi-stage BOOX animation, and
reduced-motion behavior.

Useful checks before deployment:

```bash
test -s pages/index.html
test -s pages/styles.css
test -s pages/script.js
for asset in pages/page-*.png; do test -s "$asset" || exit 1; done
```

## Cloudflare deployment

The repeatable command is:

```bash
./scripts/deploy-landing.sh
```

The script verifies that `wrangler` is installed, that the static directory is populated, and then
deploys `pages/` to the `riddleboox` Pages project. It never builds or rewrites the source.

Latest verified deploy (2026-08-21):

- Production: `https://riddleboox.pages.dev` — HTTP/2 200
- Preview: `https://b9bc9525.riddleboox.pages.dev` — HTTP/2 200
- Live browser check confirmed the Android CTA, four Android icons, the fixed-width BOOX hero, and
  no horizontal overflow on the tested desktop viewport.
- `https://riddleboox.aiocean.io` is live with HTTP/2 200 after the proxied CNAME and SSL
  certificate finished provisioning.

For a direct deploy, use:

```bash
CLOUDFLARE_ACCOUNT_ID="$CLOUDFLARE_ACCOUNT_ID" \
  wrangler pages deploy pages \
  --project-name=riddleboox \
  --branch=main \
  --commit-dirty=true
```

The first deploy only works after the Pages project exists. If it has not been created yet, create
it once after verifying the intended Cloudflare account:

```bash
CLOUDFLARE_ACCOUNT_ID="$CLOUDFLARE_ACCOUNT_ID" \
  wrangler pages project create riddleboox --production-branch=main
```

The project name is permanent and becomes `riddleboox.pages.dev`. Do not silently choose another
account when the authenticated user has more than one account. Set `CLOUDFLARE_ACCOUNT_ID` to the
chosen account ID for all subsequent commands.

## Custom domain setup

`riddleboox.aiocean.io` must be registered on the same Cloudflare account as the Pages project.
The parent zone is `aiocean.io`. Before registering the hostname, verify that the zone and Pages
project belong to the same account.

The one-time sequence is:

1. Register `riddleboox.aiocean.io` in the Pages project under **Custom domains**.
2. Create a DNS `CNAME` record in the `aiocean.io` zone:
   - Name: `riddleboox` (the subdomain only)
   - Target: `riddleboox.pages.dev`
   - Proxy status: **Proxied** / orange cloud
3. Wait for Cloudflare to issue the certificate and route the hostname.
4. Verify the result:

```bash
dig @1.1.1.1 +short riddleboox.aiocean.io
curl -sIL --max-time 15 https://riddleboox.aiocean.io | head -1
```

The CNAME must be proxied. DNS-only mode bypasses Cloudflare's Pages SSL termination and can make
the hostname look broken even though the Pages project is healthy. A Pages project and parent zone
on different Cloudflare accounts are not covered by this automation; stop and complete ownership
verification manually instead.

### Current verification (2026-08-21)

The authenticated Wrangler account owns the active `aiocean.io` zone. The custom hostname is
registered on the `riddleboox` Pages project and the DNS row is:

```text
CNAME  riddleboox  riddleboox.pages.dev  Proxied  Auto
```

DNS and SSL were polled after creation; `https://riddleboox.aiocean.io` returned HTTP/2 200.
The originally requested `RiddleBoox.aiocean.app` is a separate domain whose nameservers point to
`ns1.helloserver.win` and `ns2.helloserver.win`, so it is intentionally not part of this setup.

## Safe update loop

1. Edit only the static source in `pages/` and update this document if deployment behavior changes.
2. Run the local preview and asset checks above.
3. Deploy with `./scripts/deploy-landing.sh`.
4. Check both the `pages.dev` production alias and the custom hostname.
5. Keep the deploy output's preview URL in the handoff if visual review is needed; the stable public
   link is the production alias or custom domain.

Cloudflare Pages keeps previous deployments. If a new version is wrong, use the Pages dashboard to
promote a previous deployment rather than deleting source files or changing DNS records.

## Automation notes

- `wrangler whoami` is the first auth check. OAuth may list more than one account.
- The first project creation, first public deploy, Pages custom-domain registration, and DNS record
  creation are intentionally human-confirmed actions.
- Wrangler OAuth can manage Pages but may not have `dns:edit`. Use the authenticated Cloudflare
  dashboard for the DNS CNAME when the API returns a permissions error.
- Keep API tokens out of the repository. Prefer the existing Wrangler login or environment-managed
  credentials.
- The deploy script accepts `RIDDLEBOOX_CF_PROJECT` and `RIDDLEBOOX_CF_DOMAIN` overrides for a
  future staging project, but the defaults above are the production values.
