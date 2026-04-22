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

### 3.13 Placeholder book rows AND the author-add race

Two intertwined issues, documented together because the fix for one
exposed the other.

**(a) Placeholder rows.** When Chaptarr adds a new author, it creates
book entities in its local DB before the metadata source has fully
resolved them. These placeholder rows are visible via
`GET /api/v1/book?authorId=<id>` immediately after POST. Fingerprint of
a placeholder:

- `releaseDate` is `null`
- `images` is `[]`
- `ratings: { votes: 0, popularity: 0 }`
- **`foreignEditionId` starts with `"default-"`** — a literal Chaptarr
  marker for unresolved placeholders (e.g., `"default-10962"`)

Chaptarr silently rejects monitor-flag PUTs against placeholder rows.
The HTTP PUT returns 2xx; the change doesn't persist. Chaptarr logs
`"author is not monitored for <format>"` server-side even when the
author IS correctly enabled — the log is misleading for this failure
mode (same symptom as §3.12, different root cause).

**(b) The race.** Chaptarr's author-add POST returns fast (tens of ms),
but real edition rows only materialize seconds later once the metadata
source (Hardcover / Goodreads / Amazon via `api2.chaptarr.com`) responds.
So the fork's flow is racing Chaptarr's background refresh — a naïve
`POST → GET /book?authorId → PUT` sequence often PUTs a placeholder.

Live example (test run 5): a request for "Everyone in My Family Has
Killed Someone" targeted placeholder row 10962
(`foreignEditionId: "default-10962"`, no metadata). The real edition
row 10963 (`foreignEditionId: "az:B09Y94K74X-ebook"`, full metadata)
appeared ~10 seconds later — after the PUT and BookSearch had already
targeted the placeholder.

**Fork mitigation (two layers):**

1. `wait-for-resolved-book` polls `/book?authorId=<id>` every 1 second
   (up to 20 attempts = ~20s ceiling) immediately after author-add.
   Returns as soon as at least one book under the author matches the
   requested format AND passes `book-row-complete?`. Only runs on
   fresh author-adds — cross-format re-requests against existing
   authors skip it (those books are already resolved).

2. `book-row-complete?` requires ALL of: non-null `releaseDate`,
   non-empty `images`, and `foreignEditionId` NOT starting with
   `"default-"`. The third check is the most reliable — it's an
   explicit Chaptarr-internal marker.

3. `preferred-book-for-format` still ranks resolved editions above
   placeholders via a +10 completeness bonus, as a belt-and-suspenders
   tiebreaker after the polling step.

4. If polling hits the attempt cap and no resolved editions have
   appeared, the fork proceeds with placeholder rows (logged as a
   warning) rather than blocking the user's request. The PUT-response
   check in `chaptarr.clj/request` then catches the silent failure and
   logs a second warning that uniquely identifies this scenario:
   `"Chaptarr silently rejected monitor flip on book <id>"`.

Expected operator signal when things work: both warnings stay quiet,
log shows `Chaptarr request: selected book <id> for <format> request
(N/M matching rows under author)`, and the book id is a resolved
edition (not a `default-*` placeholder).

### 3.14 Image URL availability shifts after author-add

Related to §3.13 but worth noting separately: the image URLs on search
results (`/api/v1/book/lookup`) are relative proxy paths, but on
resolved book rows returned by `/api/v1/book?authorId=<id>`, images
come back as **absolute upstream URLs** (e.g.
`https://m.media-amazon.com/images/...`). This opens a potential future
refactor where the confirmation embed's cover is fetched from the
post-add book record instead of pre-add lookup — which would sidestep
`/MediaCoverProxy/` authentication entirely. Not implemented yet; would
require restructuring the interaction flow so the POST happens before
the user confirms. Logged here for future reference.

### 3.15 `PUT /book/{id}` silently drops monitor-flag changes — use `/book/monitor`

Chaptarr has **two PUT endpoints** that look like they edit a book and
do very different things:

- `PUT /api/v1/book/{id}` — edits book metadata (the UI's Edit Book
  dialog). Accepts a full kebab→camel roundtrip of the existing record
  with fields modified. Returns 2xx with what looks like the updated
  record. **But monitor-flag changes in this payload are silently
  dropped** — the HTTP response reflects the new state, but the
  persisted state keeps the old monitor flags. Chaptarr emits a
  misleading log message in this case, e.g. "Cannot enable ebook
  monitoring for book 'X' — author 'Y' is not monitored for ebooks",
  even when the author gate (§3.12) is actually correct.

- `PUT /api/v1/book/monitor` — bulk monitor toggle. Payload:
  ```json
  {"bookIds": [10982], "monitored": true}
  ```
  Returns `202 Accepted` with a short status snippet (NOT the updated
  book record). Chaptarr derives which per-format flag to flip
  (`ebookMonitored` vs `audiobookMonitored`) from the book row's own
  `mediaType`, so the payload does not need to carry a format flag.
  **This is the only endpoint in Chaptarr that actually persists
  monitor changes.**

**How this was confirmed.** Against author 190 / book 10982 (a real
resolved ebook edition under an author with `ebookMonitorFuture: true`):

| Step | Endpoint | Response | Persisted state |
|---|---|---|---|
| Before | — | — | `ebookMonitored: false` |
| `PUT /book/10982` with flag flipped | 2xx | body shows `ebookMonitored: true` | `ebookMonitored: false` (still!) |
| `PUT /book/monitor` `{bookIds:[10982],monitored:true}` | 202 | small status snippet | `ebookMonitored: true` ✅ |

**Fork behavior.** `impl/monitor-book` calls `PUT /book/monitor`. Since
the 202 body is not the updated record, the request flow follows up
with `GET /book/{id}` and checks the format-specific flag before firing
`BookSearch`. If the flag still reads false, a WARN is logged with a
pointer back to this section — the endpoint is right, so a false-state
result here means Chaptarr hit an unexpected internal rejection and
the operator should capture full author + book state for diagnosis.

**Do not go back to `PUT /book/{id}`** for monitor flags, even though
it returns 2xx and a body that looks correct. It does not work.
Upstream Doplarr's Sonarr and Radarr backends use per-resource PUTs
and those DO persist monitor changes — this is Chaptarr-specific
behavior, not a Readarr v1 API convention.

### 3.16 Title affinity must gate selection on big-catalog authors

Chaptarr's POST /book creates the author AND pulls in their entire
catalog. For prolific authors, this is a *lot* of rows — Kristin Hannah
surfaced 164 books, 82 of them ebook format, across dozens of decades
of backlist. Each of those rows resolves from placeholder to real
edition asynchronously, and the order isn't predictable.

If selection ranks purely by completeness + popularity + release-date,
the author's most famous book wins regardless of what the user asked
for. Live Test 8 saw a `/request book query:The Women Kristin Hannah`
request end up selecting **The Nightingale** (book 11386, popularity
1.84M) instead of **The Women** (book 11389, popularity 1.46M) — both
were real resolved ebook editions under the author at final state, but
The Nightingale won the popularity tiebreak.

**Two guards in the fork:**

1. **Polling waits for the requested title, not just any resolved
   format row.** `wait-for-resolved-book` takes an optional
   `requested-title` parameter (sourced from the payload's
   `:raw-title`, which is the pre-formatted lookup-result title the
   user clicked on). Exit condition is flipped from "at least one
   format-matching resolved row exists" to "at least one resolved row
   matches both the format AND the title." Stops short-circuiting when
   the user's specific book hasn't resolved yet but sibling backlog
   books have.

2. **Ranker filters to title-matched candidates first, then ranks.**
   `preferred-book-for-format` takes an optional `requested-title` and
   constrains candidates to rows passing `title-matches?` before
   applying the completeness/popularity tiebreak. If no row matches
   (e.g. series-name lookup case like Live Test 7's Monk & Robot →
   A Psalm for the Wild-Built — not a bug, the dropdown result carries
   the resolved title so it matches post-add), a WARN logs and the
   ranker falls back across all format-matching rows.

**Title normalization.** Comparison goes through `normalize-title`:
lowercase, Unicode-punctuation stripped, whitespace collapsed. Handles
stylistic variation like "Monk & Robot" vs "Monk and Robot" and
subtitled editions like "The Women: A Novel" vs "The Women". Does
exact-after-normalization first, then substring either direction, then
fails. Not a fuzzy matcher — if the row's title is genuinely
different, we want the fallback path, not a false match.

**The user-visible log line** on successful selection includes both
the picked book's title and the requested title, so tests can confirm
at a glance that title affinity worked:

```
Chaptarr request: monitoring book 11389 ('The Women') for book request
(requested-title='The Women')
```

### 3.17 POST runs at confirmation-embed render, not at Request-click

On Chaptarr builds that use Plex authentication (common), the
`/MediaCoverProxy/` endpoint rejects API-key-authenticated requests
with 401. That endpoint is where `/book/lookup` results embed their
relative cover URLs — so the fork can neither hand Discord the URL
(Discord's CDN can't authenticate) nor download the bytes to attach
(Doplarr also gets 401).

**Fix:** reorder the flow so POST /book runs during `request-embed`,
not during `request`. Post-add book rows returned by
`/book?authorId=...` carry **absolute upstream URLs** in their
images[] (e.g. `https://m.media-amazon.com/...`) that Discord can
fetch without any auth at all. See §3.14.

**New flow:**

1. User picks lookup result → `additional-options` resolves profiles
   + rootfolders (unchanged).
2. User picks profile (if any pending) → `query-for-option-or-request`
   calls `request-embed`.
3. `request-embed` POSTs /book, polls for the requested title to
   resolve, picks the target book row, extracts the absolute cover
   URL from that row's images[], and stashes the resolved book id back
   into the cached payload (via `sm-uuid`).
4. Confirmation embed renders **with** a working cover.
5. User clicks Request → `request` sees `:existing-book-id` in the
   payload (stashed by step 3) and takes the fast path: just
   `ensure-author-enabled-for-format` + `monitor-book` + `BookSearch`.
   No re-POST, no re-polling.

**Failure modes handled:**

- POST fails in request-embed → WARN logs, returns embed with lookup's
  relative cover (degrades via `CHAPTARR__PUBLIC_URL` if set, otherwise
  no cover). User clicks Request, which re-attempts POST via the
  normal `request` path and surfaces the real error to them with a
  proper failure message.
- Polling times out without finding the requested title → WARN logs
  via `wait-for-resolved-book`, falls back to whatever rows are
  present. `preferred-book-for-format` may return nil if nothing
  matches; the embed renders without cover and the Request-click
  error surfaces the same scenario.
- sm-uuid missing from payload (shouldn't happen — state machine
  passes it) → don't stash. `request` re-POSTs on Request-click.
  Idempotent-ish: the second POST would 409 on foreignBookId, but
  the author record is already present so re-lookup via existing-book
  machinery works.

**Tradeoff accepted — confirm-before-commit is gone.** In the old
flow, clicking Cancel or letting the interaction time out left
Chaptarr untouched. In the new flow, Chaptarr has the author added
with all books unmonitored if the user never clicks Request. It's
inert cruft — no downloads, no bandwidth, just a row in the author
list that can be deleted from Chaptarr's UI. Adding a proper "delete
author on cancel" path would require a new interaction hook in
upstream Doplarr's state machine and is deferred.

**State machine modification needed.** Passing `sm-uuid` into
request-embed requires a one-line additive change to upstream's
`interaction_state_machine.clj` (merging `:sm-uuid uuid` into the
payload before the backend call). Other backends ignore the extra
key. See CHAPTARR_FORK.md for the exact diff and merge strategy.

### 3.18 `/book/lookup` returns study guides / summaries alongside real books

Chaptarr's upstream metadata source indexes third-party study guides,
summaries (SparkNotes, CliffsNotes, BookRags, SuperSummary), and
"unauthorized companion" books as separate book entries in the same
author's feed — or sometimes as distinct results when searching by
title. These show up in `/book/lookup` results intermixed with the
real editions. On a Discord dropdown capped at ~10 results, a popular
book's three legitimate ebook editions can end up competing for space
with four different study-guide titles, and a user who wants the
original might not even see it in the list.

**Fork behavior.** `impl/lookup-book` filters results whose titles
contain any phrase in `junk-title-phrases` (case-insensitive substring
match). The list is intentionally conservative — only multi-word
phrases that essentially never appear in legitimate book titles:

- "study guide" / "study guides"
- "sparknotes" / "cliffsnotes" / "cliff notes" / "cliff's notes"
- "bookrags" / "supersummary"
- "summary and analysis" / "summary & analysis" / "summary of"
- "reader's companion" / "reading companion" / "novel companion"
- "unauthorized companion" / "unofficial companion"
- "an unauthorized" / "an unofficial" (catches phrasings like
  "An Unauthorized Companion to ...")
- "conversation starters" / "discussion questions" / "quizzes and"
- "reading guide" / "reader's guide" / "lesson plans"
- "key takeaways" / "deep analysis" / "unofficial summary"

**What's deliberately NOT filtered.** Single-word markers like `guide`,
`summary`, `analysis`, `companion` — these appear in plenty of
legitimate books ("A Field Guide to Birds", "Summary Judgment", "The
Analyst", "A Companion to Beowulf"). The substring match only fires
on multi-word phrases with near-zero false-positive rate.

**Operator visibility.** When the filter drops anything, an INFO log
fires with the drop count:

```
Chaptarr lookup: filtered 3 study-guide/summary result(s) from 12 total for 'The Women'
```

If a user ever reports a legitimate title being filtered out, grep
the log for that message to see the count and adjust the phrase list.
The escape hatch is direct Chaptarr UI request for anyone who
specifically wants a study guide — Doplarr is for reading material,
not companion material.

---

## 4. Request flow — what actually happens when a user types `/request book`

1. User runs `/request book query:<title>` in Discord.
2. Fork calls `GET /api/v1/book/lookup?term=<title>`, renders first 10 results
   as a dropdown. Each result is labeled `"Title — Author"`.
3. User picks a result. Fork fetches quality profiles, metadata profiles,
   and root folders, then shows a dropdown for the requested format's quality
   profile (unless a `CHAPTARR__*_QUALITY_PROFILE` default is set in the
   env).
4. User picks a profile (or this step is skipped if all profiles have
   defaults). Fork runs the **pre-request phase**: POST /book to add the
   author + catalog, poll `/book?authorId=...` until the requested title
   resolves to a real edition row (up to ~20s), pick the target book,
   stash its id back into the cached payload. See §3.17 for why this
   happens before confirmation rather than after.
5. Fork renders the confirmation embed with title, stripped overview,
   profile names, root folder, and a working cover image (pulled from
   the resolved row's absolute upstream URL, see §3.14).
6. User clicks Request. Fork takes the fast path because the book is
   already indexed: `ensure-author-enabled-for-format`, `PUT /book/monitor`
   (flip the requested format's flag — §3.15), and fire `BookSearch`.
7. On success: Fork posts a public "Request performed!" announcement to
   the channel (unless `DISCORD__REQUESTED_MSG_STYLE=:none`); Chaptarr
   starts its indexer search; a matching release should appear in the
   download client within minutes.

**Note on the pre-request phase tradeoff.** If a user picks options but
then closes Discord without clicking Request, the author remains in
Chaptarr as inert cruft (no monitored books, no active downloads). This
is the price of showing a working cover before confirmation on
Plex-auth Chaptarr builds. See §3.17.

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
| 2026-04-21    | Chaptarr creates placeholder book rows | Completeness boost in preferred-book ranking |
| 2026-04-21    | PUT response silently 2xxs on rejection | Log warning from updated flag in response body |
| 2026-04-21    | Race: placeholders only exist for ~seconds after POST | wait-for-resolved-book polls until real edition materializes |
| 2026-04-21    | `PUT /book/{id}` silently drops monitor-flag changes | Switched to `PUT /book/monitor` (§3.15) |
| 2026-04-21    | Big-catalog authors' popular sibling titles out-rank requested book | Title-affinity gate in polling + ranker (§3.16) |
| 2026-04-21    | `/MediaCoverProxy/` 401s on Plex-auth Chaptarr builds | Moved POST into request-embed so embed uses post-add absolute cover URL (§3.17) |
| 2026-04-21    | `/book/lookup` returns study guides alongside real editions | Title-phrase filter in `lookup-book` (§3.18) |

Add new rows when you find new surprises.
