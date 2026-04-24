# Chaptarr integration

This fork adds `/request book` and `/request audiobook` to Doplarr,
backed by [Chaptarr](https://github.com/robertlordhood/chaptarr)
(a Readarr fork that manages ebooks and audiobooks from one
backend). This document covers what's needed on the Chaptarr side,
the environment variables the fork reads, and the Chaptarr quirks
that shaped the integration.

The rest of Doplarr's behaviour — Sonarr, Radarr, Overseerr,
Discord registration, etc. — is untouched.

## Chaptarr-side setup

Chaptarr must be configured and reachable before the Discord bot
will do anything useful:

- **Download client** connected (MyAnonaMouse through Deluge or
  qBittorrent is the common path).
- **Indexer** for books (MaM) with an appropriate release profile.
- **Root folders** for ebooks and audiobooks. The fork writes the
  requested format to its own folder, so either have both
  pre-configured or let the user pick at request time.
- **Quality profiles**: at least one of `profileType: "ebook"` and
  one of `profileType: "audiobook"`. These are the strings returned
  by `GET /api/v1/qualityprofile`.
- **Metadata profiles** for each format. Chaptarr uses integer
  discriminators here: `2` for ebook, `1` for audiobook, `0` for
  none.
- **API key** from Chaptarr → Settings → General.

## Environment variables

Required:

```
CHAPTARR__URL=http://chaptarr:8789
CHAPTARR__API=<api key>
```

Recommended, so requests skip the root-folder and
quality-profile dropdowns:

```
CHAPTARR__EBOOK_ROOTFOLDER=/cw-book-ingest
CHAPTARR__AUDIOBOOK_ROOTFOLDER=/audiobooks/audiobooks
CHAPTARR__EBOOK_QUALITY_PROFILE=E-Book
CHAPTARR__AUDIOBOOK_QUALITY_PROFILE=Audiobook
CHAPTARR__EBOOK_METADATA_PROFILE=Ebook Default
CHAPTARR__AUDIOBOOK_METADATA_PROFILE=Audiobook Default
```

Optional — only if you want the bot to rewrite relative Chaptarr
cover paths into absolute public URLs instead of attaching the
image bytes directly to the Discord embed:

```
CHAPTARR__PUBLIC_URL=https://chaptarr.example.com
```

This URL must be publicly reachable from Discord's servers. Leave
it unset on deployments where Chaptarr is only reachable inside
the Docker network — the fork will fall back to downloading the
cover over `CHAPTARR__URL` and attaching the bytes to the embed.

## Mental model

Chaptarr's Readarr-v1-compatible API has a few surprises worth
internalising before reading the code.

### Chaptarr is author-centric

- A book cannot be added in isolation. `POST /api/v1/book` creates
  the author and seeds its full catalogue.
- The author record owns the language/edition filters, so both
  ebook and audiobook root folder paths and all four per-format
  profile IDs (`ebook-quality-profile-id`,
  `audiobook-quality-profile-id`, `ebook-metadata-profile-id`,
  `audiobook-metadata-profile-id`) have to be on the POST body,
  even though the request only cares about one format. The
  singular `qualityProfileId` / `metadataProfileId` fields are
  silently ignored.
- Every piece of metadata refresh is author-level. There is no
  reliable per-book refresh — `RefreshBook` throws errors in
  community reports; `RefreshAuthor` is the working unstick path.

### The book catalogue is a mix of resolved rows and placeholders

After `POST /book`, Chaptarr's background metadata refresh
materialises book rows from its upstream sources (Hardcover,
Goodreads, Amazon scrape). Until a row is resolved it looks like a
placeholder:

- `foreignEditionId` starts with `default-` (literal prefix).
- `releaseDate` is null, `images` is empty, `ratings` is zero.
- `anyEditionOk` is false.

Resolved rows have a real `foreignEditionId` (`gr:...`, `hc:...`,
`az:...`), a populated `releaseDate`, non-empty `images`, and
`anyEditionOk: true`. The predicate `book-row-complete?` in
`impl.clj` codifies this distinction.

Placeholders can persist permanently if the upstream metadata
source never returns an edition for that work. Chaptarr also
silently drops monitor-flag PUTs against placeholder rows, so the
fork re-fires a `RefreshAuthor` and re-picks before monitoring —
see the Ranker section below.

### Provider namespaces drift

Lookup results carry `foreignAuthorId` in the provider namespace
used by the external metadata lookup (e.g. `gr:38550` from
Goodreads). After import, Chaptarr often normalises authors to
Hardcover (`hc:...`) — but not always, and the numeric IDs don't
share a namespace across providers (Brandon Sanderson is
`gr:38550` in Goodreads and `hc:204214` in Hardcover). Edition
rows can end up under any of `gr:`, `hc:`, or `az:` depending on
which source materialised them first.

This is why `find-existing-author` tries `foreignAuthorId`
equality first and falls back to normalised author-name on a
single match — the only reliable bridge when provider IDs don't
agree.

### Monitor flags have a two-tier gate

Per-book monitoring requires **both**:

1. The book row's `ebookMonitored` / `audiobookMonitored` flag set.
2. The author's `ebookMonitorFuture` / `audiobookMonitorFuture`
   flag set.

If (2) is false, Chaptarr silently drops (1) on write and logs
"author is not monitored for ebooks" server-side. The fork's
`ensure-author-enabled-for-format` flips the author-level flag
before attempting the per-book PUT on any cross-format re-request.

### Two monitor endpoints, only one works

`PUT /api/v1/book/{id}` with a monitor-flag-flipped body returns
2xx but silently drops the flag change. The bulk
`PUT /api/v1/book/monitor` with `{bookIds, monitored}` actually
lands and returns 202 Accepted. The response body doesn't include
the updated record, so the fork re-GETs `/book/{id}` to verify
the flip.

### Chaptarr feeds the row's title to the indexer verbatim

When `BookSearch` fires, Chaptarr builds an indexer query from the
selected row's title. A row titled *"The Trial by Franz Kafka: A
Masterpiece of Modern Literature Exploring Power, Bureaucracy, and
Existential Struggle (Grapevine Edition)"* produces an indexer
query that matches no actual release. This is why the ranker
prefers shorter titles within tier — the fork can't reshape the
search, but it can pick a row that searches better.

### Cover URLs depend on row lifecycle

- Lookup results carry relative `/MediaCoverProxy/...` paths.
  Chaptarr builds with Plex auth 401 those paths to anyone without
  a Plex cookie.
- Post-POST resolved rows can carry absolute upstream URLs
  (Amazon, Hardcover CDN). Not universal — some resolved rows
  still carry only the proxy path.

The fork runs POST at confirmation-embed render (not at
Request-click) so the embed gets the resolved row's cover URL
when available. If it's still a relative path, `CHAPTARR__PUBLIC_URL`
rewrites it to a public absolute; without that env var the fork
downloads the cover inside the container and attaches the bytes
to the Discord embed as a multipart upload.

## Ranker design

Multiple rows under one author for one format is the norm — one
row per edition/language. Selecting the right row matters because
Chaptarr feeds the row's title to the indexer. The ranker lives
in `impl/preferred-book-for-format` and works in two phases.

**Tier filter** on title affinity:

1. **Exact match** after title normalisation (lowercase, strip
   Unicode punctuation, collapse whitespace).
2. **Substring match** either direction — handles subtitled
   editions ("The Women: A Novel" vs requested "The Women").
3. **No title match** — fall back to all format-matching rows and
   pick by completeness + popularity. Warns in logs.

Exact beats substring even when the exact match is a placeholder.
This is deliberate: Chaptarr's `BookSearch` uses the row's title
verbatim, and an anthology titled *"<book> By <author>, <other
work>..."* would substring-match but can't be found at the
indexer.

**Within the selected tier**, sort by:

1. `format-match-rank`: resolved rows (+10) outrank placeholders;
   explicit `mediaType` match (+2) beats `edition.isEbook` fallback
   (+1).
2. `title-length-affinity`: negative absolute distance between the
   row's normalised title length and the request's. Breaks ties in
   favour of cleaner-titled editions over marketing-heavy ones.
3. `ratings.popularity`
4. `ratings.votes`
5. `releaseDate` (newer wins)

Every selection emits a single log line with the tier, candidate
counts, and the winner's scoring components — enough to diagnose
"why did we pick that row?" without re-running.

## Request flow

1. User runs `/request book query:<title>`. Doplarr calls
   `GET /book/lookup?term=<query>` and dedupes obvious junk
   (study guides, summaries) from the results.
2. User picks a result. The fork resolves the Chaptarr author
   via this order:
   - `:existing-book-id` — lookup returned an indexed book.
   - `:existing-author-id` — lookup returned an indexed author.
   - `:foreign-author-id` — `find-existing-author` matches by
     foreignAuthorId equality against `/api/v1/author`.
   - `:author-name` — single-match normalised author name (bridges
     provider-namespace drift).
   - `:post` — POST /book creates the author and seeds its
     catalogue as placeholders.
3. At confirmation-embed render, the fork does the POST (if
   needed) and runs selection. This is the first place the user
   waits — the embed uses the resolved row's cover if we got one.
4. User clicks Request. The state machine immediately edits the
   ephemeral message to a progress string, strips the Request
   button (via `:components []` on the Discord response), and
   unblocks the user from double-clicking.
5. The fork re-runs selection (fast path via the cached
   `:existing-book-id`), remediates any placeholder by firing
   `RefreshAuthor` and polling, flips the book's monitor flag via
   `PUT /book/monitor`, verifies the flip landed by re-GETting the
   row, then fires a `BookSearch` command.
6. If the target row is still a placeholder after `RefreshAuthor`
   times out, the fork returns a 403 ex-info whose body message
   becomes the ephemeral Discord reply.

## Limitations

What this fork explicitly doesn't fix:

- **Chaptarr's indexer matcher is strict.** If Chaptarr rejects a
  legitimate release with `RejectionReason: Title/Author mismatch`,
  no row selection will help. Remedy is Chaptarr-side release
  profile / preferred-word configuration.
- **Upstream metadata gaps.** If Hardcover / Goodreads / Amazon
  don't carry an edition for a requested work, `RefreshAuthor`
  won't conjure one and the request will fail with the
  placeholder-unresolved message.
- **Duplicate placeholder rows.** Chaptarr sometimes mints a
  second placeholder on a retry rather than reusing the existing
  one. The fork doesn't reap these — they can pile up under an
  author and are harmless but noisy. Operator can delete them via
  the Chaptarr UI if desired.
- **Rapid double-tap race.** If a user double-taps Request fast
  enough that the second click's interaction arrives before the
  progress-message edit lands on Discord's side, both clicks run
  concurrent request flows. Narrow window; not closed yet.

## Debugging

The fork emits three high-signal log lines per request:

```
Chaptarr resolve-target-book!: via=... posted?=... target-book-id=... target-title=...
Chaptarr preferred-book-for-format: tier=... winner-id=... winner-length-affinity=... winner-any-edition-ok=...
Chaptarr request: monitoring book ... / short-circuiting on status=... / returning Throwable to channel — ...
```

Reading them in order tells you how the author was resolved,
which row the ranker picked and why, and what happened at the
request layer.

When a request produces "Request performed!" in Discord but no
grab follows, the question lives in Chaptarr's own logs, not
Doplarr's — specifically `ReleaseSearchService`,
`DownloadDecisionMaker`, and any `Rejection` lines near the
timestamp of the fork's `monitoring book N` entry. Those show
what Chaptarr searched for and why each returned release was
rejected.

Useful field probes from the host Doplarr runs on:

```bash
# All books under an author, filtered to a title
curl -s -H "X-Api-Key: $CHAPTARR__API" \
  "$CHAPTARR__URL/api/v1/book?authorId=N" \
  | jq '.[] | select(.title | ascii_downcase | contains("elantris"))
         | {id, title, foreignEditionId, monitored, ebookMonitored,
            anyEditionOk, releaseDate, images: (.images | length)}'

# An author record
curl -s -H "X-Api-Key: $CHAPTARR__API" \
  "$CHAPTARR__URL/api/v1/author/N" | jq '{id, authorName, foreignAuthorId, ebookMonitorFuture, audiobookMonitorFuture}'

# A single book row
curl -s -H "X-Api-Key: $CHAPTARR__API" \
  "$CHAPTARR__URL/api/v1/book/N" | jq .
```
