# Chaptarr Integration — What Operators Need to Know

This is field-verified operator knowledge accumulated while building and
live-testing this fork against real Chaptarr 0.9.418 instances. If you're
deploying this image, read this before you file a bug — most surprises below
are already known.

**Chaptarr version tested against:** 0.9.418.0 (develop branch).
**Fork known-good against:** Chaptarr 0.9.4xx series (alpha).

---

## 1. Required Chaptarr-side setup

Before pointing the bot at Chaptarr, the Chaptarr instance itself needs:

- **Metadata source flipped to `api2.chaptarr.com`** at `/settings/development`.
  The default `api.chaptarr.com` is dead — searches silently return nothing.
- **Two root folders configured**, one for ebooks and one for audiobooks. Our
  setup uses `/cw-book-ingest` for ebooks (CWA watches it) and
  `/audiobooks/audiobooks` for audiobooks (Audiobookshelf reads it directly).
- **Per-format quality profiles.** Chaptarr ships with `E-Book` (profileType
  `"ebook"`) and `Audiobook` (profileType `"audiobook"`). A single shared
  profile won't work — the two endpoints return different discriminators.
- **Per-format metadata profiles.** Chaptarr ships with `Ebook Default`
  (profileType 2), `Audiobook Default` (profileType 1), and `None`
  (profileType 0). Note the integer discriminator — it differs from quality
  profiles on purpose.
- **Indexer and download client** configured. The fork just adds the book
  and asks Chaptarr to search; the actual grab happens through Chaptarr's
  own indexer/client config.

---

## 2. Required Doplarr-side environment variables

Minimum:

```
DISCORD__TOKEN=<bot token>
CHAPTARR__URL=http://chaptarr:8789       # Docker-internal or LAN URL
CHAPTARR__API=<api key from Settings → General>
```

Strongly recommended (skips a dropdown per request):

```
CHAPTARR__EBOOK_ROOTFOLDER=/cw-book-ingest
CHAPTARR__AUDIOBOOK_ROOTFOLDER=/audiobooks/audiobooks
CHAPTARR__EBOOK_QUALITY_PROFILE=E-Book
CHAPTARR__AUDIOBOOK_QUALITY_PROFILE=Audiobook
CHAPTARR__EBOOK_METADATA_PROFILE=Ebook Default
CHAPTARR__AUDIOBOOK_METADATA_PROFILE=Audiobook Default
```

Optional (only if you want book covers rendered via absolute public URLs
instead of the bot downloading and attaching them directly):

```
CHAPTARR__PUBLIC_URL=https://chaptarr.example.com
```

Without `CHAPTARR__PUBLIC_URL`, the fork falls back to downloading cover
bytes via `CHAPTARR__URL` (the Docker-internal address) and attaching them
to the Discord confirmation embed as a file. Covers still render; only the
HTTP path differs. See §3.9 for the resolution cascade in detail.

---

## 3. Chaptarr data model — things that will surprise you

These are quirks where Chaptarr diverges from Sonarr/Radarr assumptions.
They're in the fork's code already; you don't need to do anything about them,
but knowing them helps diagnose bugs when Chaptarr gets a new release.

### 3.1 Author-centric model

Adding a book always adds the **whole author** plus every book in the author's
catalog. Other editions/languages of the same book are created as separate
book entities under the same author. All default to unmonitored unless you
explicitly say otherwise. This is inherited from Readarr's model — it's by
design, not a bug.

### 3.2 Four per-format profile IDs, not one

The author record in Chaptarr has `ebookQualityProfileId`,
`audiobookQualityProfileId`, `ebookMetadataProfileId`, and
`audiobookMetadataProfileId` — all four must be set on author-add or the
author lands without usable config. The singular `qualityProfileId` and
`metadataProfileId` accepted by other *arrs are **silently ignored**. HTTP
calls succeed, but the author is broken.

The fork always sends all four. When you haven't configured a per-format
default, the fork picks the first profile matching each format's
`profileType` automatically.

### 3.3 Quality and metadata profileType discriminators differ

- `/api/v1/qualityprofile` — `profileType` is a **string** (`"ebook"` or
  `"audiobook"`)
- `/api/v1/metadataprofile` — `profileType` is an **int** (`0` = None,
  `1` = audiobook, `2` = ebook)

The fork has two separate filter helpers for this. Don't unify them.

### 3.4 Both root folder paths go on the author

Always set both `ebookRootFolderPath` and `audiobookRootFolderPath` on
author-add, even when the first request is ebook-only. A future audiobook
request on the same author can't fill in a missing audiobook root folder
retroactively without editing the author record manually in the Chaptarr UI.

### 3.5 Multiple book entities per title

Chaptarr creates a separate book entity for each edition/language returned
by the metadata server. A single search for "Dune" can produce 3+ book rows
under the same author — one for the English ebook, one for the audiobook,
one for the German edition, etc. Each row has its own `ebookMonitored` and
`audiobookMonitored` flag.

### 3.6 POST /book auto-assigns format flags

If you POST a book with `monitored: true, ebookMonitored: true`, Chaptarr
will set `ebookMonitored: true` on the ebook edition **and** set
`audiobookMonitored: true` on the audiobook edition automatically — your
explicit `audiobookMonitored: false` in the payload is ignored. To get
format-specific monitoring, the fork uses a **two-step flow**:

1. POST `/book` with all monitor flags set to `false`. Chaptarr creates the
   author and every book entity, all unmonitored.
2. `GET /book?authorId=<id>` to list them, find the one matching the
   requested format (by `editions[].isEbook` / `mediaType`).
3. PUT `/book/{id}` on that specific book with `monitored: true` and the
   one correct format flag.
4. POST `/command {"name":"BookSearch","bookIds":[id]}` to trigger an
   active grab.

### 3.7 Active BookSearch is mandatory

`addOptions.searchForNewBook: true` on the POST payload is not reliable —
observed to add-and-monitor without firing a release search. The fork
always fires an explicit `BookSearch` command after flipping monitor flags.
Without this, a user's request sits in limbo until Chaptarr's next RSS cycle
(potentially hours) with no feedback signal.

### 3.8 monitorNewItems on the author

We add authors with `monitorNewItems: "none"` so Chaptarr doesn't
auto-monitor every book in the author's catalog. Users can change this in
the Chaptarr UI later if they want to pull future releases from a
newly-added author automatically.

### 3.9 Cover images are relative paths (and covers may not work without a public URL)

`remoteCover` and `images[].url` on search results come back as paths like
`/MediaCoverProxy/.../...jpeg`, relative to Chaptarr's own server. Discord
embeds reject non-absolute URLs with a 50035 Invalid Form Body. The fork
handles this in a cascade:

1. If the URL is already absolute (http:// or https://) — use it directly.
2. If `CHAPTARR__PUBLIC_URL` is configured — prepend it to the relative path.
3. Otherwise — **try to download the cover bytes over `CHAPTARR__URL`**
   (the Docker-internal URL that Doplarr can always reach) and attach them
   to the Discord message as a file, referenced via `attachment://cover.jpeg`
   in the embed's image URL.

**Important caveat on path 3:** some Chaptarr builds protect
`/MediaCoverProxy/...` behind the same Plex/session authentication as the
rest of the UI, which means API-key auth gets a `401` and the download
silently fails. Chaptarr 0.9.418 with Plex auth is one of these. On those
builds, book confirmation embeds render **without a cover** unless
`CHAPTARR__PUBLIC_URL` is set to a reverse-proxied Chaptarr (where the
proxy handles the auth wall and rewrites image URLs to public endpoints).

In practice:

- Chaptarr build serves `/MediaCoverProxy/` with API-key auth: path 3 works,
  no extra config needed.
- Chaptarr build protects `/MediaCoverProxy/` with Plex session auth:
  `CHAPTARR__PUBLIC_URL` is required for covers to show. The embed still
  renders, just without the cover thumbnail.
- Lookup results that include `remoteUrl` on images (direct upstream
  URL from Goodreads/Hardcover): path 1 handles it automatically. Most
  Chaptarr builds don't expose `remoteUrl` on `/book/lookup`, but a few do.

The embed never fails outright from a cover issue — worst case is no
thumbnail in the confirmation. Everything functional still works.

Technical note: discljord's `edit-original-interaction-response!` doesn't
expose Discord's multipart-upload support, so the fork does a direct
multipart HTTP PATCH to `/webhooks/{app-id}/{token}/messages/@original`
for the attachment path. discljord's rate-limit tracking is bypassed for
that single call; acceptable given this code path runs at most once per
user book request.

### 3.10 HTML in book overviews

Chaptarr's `overview` field contains HTML markup (`<b>`, `<i>`, etc.)
sourced from upstream metadata. Often malformed. Discord embed descriptions
render Markdown only, so HTML tags appear as tag soup. The fork strips HTML
tags before rendering.

### 3.11 gr: vs hc: foreign IDs

Chaptarr aggregates metadata from both Hardcover (`hc:...`) and Goodreads
(`gr:...`). The search endpoint may return a book with `foreignAuthorId`
prefixed `gr:` at the nested author, then resolve it through its metadata
server on POST and end up with `hc:` on the final author record. This is
normal — Chaptarr doing format normalization — not a fork bug.

### 3.12 Author-level `*MonitorFuture` gates per-book monitoring

This one bit us hard in live testing. Chaptarr has per-format flags on
the author record: `ebookMonitorFuture` and `audiobookMonitorFuture`.
Their names imply "auto-monitor future books Chaptarr discovers in this
format," but in practice they're **also a binary gate**: when
`ebookMonitorFuture: false`, Chaptarr silently refuses any PUT that tries
to set `ebookMonitored: true` on a book under that author, even a book
that's already in the catalog. The Chaptarr server log on rejection reads
`"Cannot enable ebook monitoring for book '<title>' - author '<name>' is
not monitored for ebooks"`; the HTTP PUT itself returns 2xx and the
caller sees no error.

Live-verified against Chaptarr 0.9.418: this is how an author set up
via the Chaptarr UI with ebook enabled ends up with
`ebookMonitorFuture: true` even though the operator may not want every
future ebook auto-monitored. The auto-monitor-future behavior is
separately suppressed by the author-level `monitorNewItems: "none"`
flag.

The fork handles this:

- On fresh author-add (POST /book), `request-payload` sets
  `ebookMonitorFuture: true` for ebook requests and
  `audiobookMonitorFuture: true` for audiobook requests. `monitorNewItems:
  "none"` is also sent to prevent back-catalog flood.
- On cross-format re-request against an existing author (user runs
  `/request book Dune` then later `/request audiobook Dune`), the fork
  calls `ensure-author-enabled-for-format` right before the per-book PUT.
  That helper GETs the author record, checks the relevant
  `*MonitorFuture` flag, and if it's still false, PUTs the author with
  it flipped to true. Idempotent — no-op when the flag is already set.

Side effect the operator should know about: an author added for ebook
requests will have `ebookMonitorFuture: true` permanently. If a future
new book by that author shows up in Chaptarr's metadata source, it may
auto-monitor for ebook. `monitorNewItems: "none"` is the canonical
Chaptarr flag that suppresses this, and the fork sends it — but if you
see unwanted auto-monitoring, flip `ebookMonitorFuture: false` on the
author in the Chaptarr UI once you've grabbed the specific book you
wanted.

---

## 4. Request flow — what actually happens when a user types `/request book`

1. User runs `/request book query:<title>` in Discord.
2. Fork calls `GET /api/v1/book/lookup?term=<title>`, renders first 10 results
   as a dropdown. Each result is labeled `"Title — Author"`.
3. User picks a result. Fork fetches quality profiles, metadata profiles,
   and root folders, then shows a dropdown for the requested format's quality
   profile (unless a `CHAPTARR__*_QUALITY_PROFILE` default is set in the
   env).
4. User picks a profile. Fork shows a confirmation embed with title, stripped
   overview, profile names, root folder, and a "Request" button.
5. User clicks Request. Fork runs the two-step flow described in §3.6.
6. On success: Fork posts a public "Request performed!" announcement to the
   channel; Chaptarr starts its indexer search; a matching release should
   appear in the download client within minutes.

---

## 5. Troubleshooting

### "Request performed!" but no grab ever happens

- Check `POST /api/v1/command {"name":"BookSearch","bookIds":[<id>]}` in
  Chaptarr's Activity → Events log. If it didn't fire, the fork's explicit
  search step is broken.
- Check the indexer in Chaptarr — is the source (MyAnonamouse, etc.) actually
  returning hits for this title?
- Check the queue: `GET /api/v1/queue` — is something stuck?

### Wrong format got grabbed (ebook when user requested audiobook)

- Should not happen with fork version ≥ `fix(chaptarr): two-step flow`
  (commit `<TODO>`). If it does, check the `PUT /api/v1/book/{id}` call in
  doplarr logs — which book id did it target, and did Chaptarr accept only
  the one flag?

### Author landed with no profile or wrong rootfolder

- Confirm the four per-format IDs arrived in the POST payload: enable
  `LOG_LEVEL=:debug` in doplarr and re-request. The outgoing POST body should
  contain `ebookQualityProfileId`, `audiobookQualityProfileId`,
  `ebookMetadataProfileId`, `audiobookMetadataProfileId`.
- Double-check that the profile names in your env vars match Chaptarr's
  actual profile names *exactly* (case-sensitive; spaces and hyphens
  preserved). The fork logs a warning on config validation if a profile name
  doesn't resolve.

### "Configuration is valid" but `/request book` does nothing

- Check `available-media` on startup: the log line after "Configuration is
  valid" lists which media types are registered. `:book` and `:audiobook`
  should both appear. If only one appears, the `CHAPTARR__URL` env isn't
  being picked up.

### Cover images missing in confirmation embed

- Check doplarr logs for `Cover download failed` warnings. With Phase 2, a
  missing `CHAPTARR__PUBLIC_URL` alone is no longer a blocker — the fork
  should fall back to downloading the cover over `CHAPTARR__URL` and attaching
  it directly. See §3.9.

---

## 6. Version history of discovered quirks

| Date (found)  | Quirk                                  | Fix shipped in        |
|---------------|----------------------------------------|------------------------|
| 2026-04-20    | Four per-format profile IDs required   | `fix(chaptarr): per-format profile IDs` |
| 2026-04-21    | Relative cover paths break embeds      | `fix(chaptarr): Discord 50035`          |
| 2026-04-21    | POST monitored:true flips both formats | Two-step flow (this commit)             |
| 2026-04-21    | addOptions.searchForNewBook unreliable | Explicit BookSearch (this commit)       |
| 2026-04-21    | HTML in overviews                      | HTML strip (this commit)                |
| 2026-04-21    | Relative cover paths (retry)           | Download + attach as file (Phase 2)     |
| 2026-04-21    | Plex-auth Chaptarr 401s MediaCoverProxy | Documented — CHAPTARR__PUBLIC_URL needed |
| 2026-04-21    | Author *MonitorFuture gates per-book PUT | ensure-author-enabled-for-format        |

Add new rows when you find new surprises.
