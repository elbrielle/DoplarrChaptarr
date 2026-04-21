# DoplarrChaptarr — Fork Notes

This fork adds support for [Chaptarr](https://github.com/robertlordhood/chaptarr)
(a Readarr fork handling both ebooks and audiobooks) to upstream
[Doplarr](https://github.com/kiranshila/Doplarr). It registers two additional
Discord slash subcommands: `/request book` and `/request audiobook`.

This document tracks exactly which files are new and which were modified so
future upstream Doplarr releases can be merged with minimal friction.

---

## Additive-only files (zero merge conflict risk)

These files exist only in this fork. A `git pull upstream main` will never
touch them.

| Path | Purpose |
|---|---|
| `src/doplarr/backends/chaptarr.clj` | Public backend API — `search`, `additional-options`, `request-embed`, `request` |
| `src/doplarr/backends/chaptarr/impl.clj` | HTTP client, payload construction, existing-book status logic |
| `CHAPTARR_FORK.md` | This file |

---

## Modified files (small, localized edits)

Every modification is a **pure insertion** — no upstream lines were rewritten
or reordered. When merging a new upstream release, conflicts (if any) will be
mechanical: re-apply the insertion blocks below near their original neighbors.

### `src/doplarr/config.clj`

Four insertion blocks:

1. **`valid-keys` set** — added the Chaptarr config key block between the
   Overseerr block and the Discord block:
   ```clojure
   ; Chaptarr (fork addition)
   :chaptarr/url
   :chaptarr/api
   :chaptarr/public-url
   :chaptarr/ebook-rootfolder
   :chaptarr/audiobook-rootfolder
   :chaptarr/ebook-quality-profile
   :chaptarr/audiobook-quality-profile
   :chaptarr/ebook-metadata-profile
   :chaptarr/audiobook-metadata-profile
   ```

2. **`redact-secrets`** — added one line before the `:discord/token` redaction:
   ```clojure
   (redact :chaptarr/api)
   ```

3. **`backend-media` map** — added the Chaptarr entry as the final key:
   ```clojure
   :chaptarr [:book :audiobook]
   ```

4. **`available-backends`** — added one `cond->` clause:
   ```clojure
   (:chaptarr/url env) (conj :chaptarr)
   ```

### `src/doplarr/config/specs.clj`

Four insertion blocks:

1. **Backend endpoint/api spec defs** — added two lines after the Overseerr entries:
   ```clojure
   (spec/def :chaptarr/url string?)
   (spec/def :chaptarr/api string?)
   ```

2. **Chaptarr optionals block** — added before the Doplarr-optionals section:
   ```clojure
   (spec/def :chaptarr/public-url string?)
   (spec/def :chaptarr/ebook-rootfolder string?)
   (spec/def :chaptarr/audiobook-rootfolder string?)
   (spec/def :chaptarr/ebook-quality-profile string?)
   (spec/def :chaptarr/audiobook-quality-profile string?)
   (spec/def :chaptarr/ebook-metadata-profile string?)
   (spec/def :chaptarr/audiobook-metadata-profile string?)
   ```

3. **`::has-backend`** — added `:chaptarr/url` to the predicate's list and to
   the expound message.

4. **`::config`** — added the Chaptarr optional keys to the `:opt` vector and
   added one `matched-keys` line at the bottom:
   ```clojure
   (matched-keys :chaptarr/url :chaptarr/api)
   ```

### `docs/configuration.md`

Inserted a new `## Chaptarr` section between the Overseerr section and the
Optional Settings table, plus five rows in the Optional Settings table for the
`CHAPTARR__*` env vars. No existing text was changed.

### `src/doplarr/discord.clj`

Three localized changes:

1. **`request-thumbnail` map** (additive) — two entries added (`:book`,
   `:audiobook`) reusing the Doplarr logo URL from README.md. Without these,
   `request-embed` would emit `{:thumbnail {:url nil}}` for book requests,
   which Discord may reject as an invalid embed.
2. **`request-embed` signature and fields vector** (additive) — added a
   `:metadata-profile` destructure binding and a corresponding "Metadata
   Profile" field inside the `filterv`. Chaptarr's metadata profile concept
   (edition filtering by language/format/rating/pages) does not map onto
   Sonarr's `:language-profile`, so the hardcoded "Language Profile" label
   would be misleading for book requests. The original `:language-profile`
   path is preserved for Sonarr.
3. **`request-embed` restructured to `cond->`** (minor rewrite) — the
   top-level map constructor was wrapped in a `cond->` so `:image` is only
   `assoc`'d when `poster` is non-nil. Chaptarr returns relative cover paths
   (e.g. `/MediaCoverProxy/...`) which Discord's embed validator rejects
   with 50035 Invalid Form Body; without the guard, the book request
   confirmation embed fails to send. Sonarr/Radarr flows still get `:image`
   as before because their `poster` values are always absolute URLs from
   TheMovieDB.

---

## Files intentionally NOT modified

- `src/doplarr/core.clj` — no changes needed; Discord-slash-command
  registration already reads from `config/available-media`, which picks up
  `:book` and `:audiobook` automatically once Chaptarr is configured.
- `src/doplarr/interaction_state_machine.clj` — the state machine dispatches
  to backend functions via `utils/media-fn`, which resolves the Chaptarr
  namespace automatically.
- `src/doplarr/utils.clj` — the existing `process-profile`,
  `process-rootfolders`, `from-camel`, `to-camel`, and `request-and-process-body`
  helpers are all reused directly by the Chaptarr backend. No shared helpers
  were modified.
- `deps.edn`, `config.edn`, `build/build.clj`, `docker/Dockerfile` — all
  upstream as shipped.

---

## Merging a new upstream release

Typical flow:

```bash
git fetch upstream
git merge upstream/main
```

If git reports conflicts, they will almost always be in `config.clj` or
`specs.clj` when upstream adds a new backend or config key near the spots we
inserted into. Resolve by keeping both changes — our insertion blocks and the
upstream additions sit alongside each other.

Higher-risk scenarios (not seen as of this writing):

- **Upstream ships its own Readarr or audiobook backend**: `backend-media` will
  have a real competing claim on `:book`. Decide whether to delete the
  `:chaptarr` entry in favor of upstream or keep both and update
  `available-backend-for-media` to prefer Chaptarr when both URLs are set.
- **Upstream reshapes `config.clj` or `specs.clj`** (e.g., replacing the
  hand-maintained `valid-keys` set with a registry): re-derive the insertion
  points in the new structure. The list of added keys stays the same.

---

## Chaptarr-specific design decisions (for future maintainers)

- **Per-specific-book monitoring.** `request-payload` sets
  `author.monitorNewItems: "none"` and `addOptions.monitor: "specificBook"` so
  only the requested book is monitored, not the author's full catalog. This
  matches the advice in CHAPTARR_KNOWLEDGE.md §7 against flooding the library.
- **Both root folder paths on every author add.** Even when a request is for
  an ebook only, the POST includes both `ebookRootFolderPath` and
  `audiobookRootFolderPath`. This prevents the "switched save locations" bug
  documented in CHAPTARR_KNOWLEDGE.md §3 where a later audiobook request on an
  author added via ebook-only config could land in the ebook folder.
- **Per-format config keys.** Ebooks and audiobooks usually have different
  quality profiles and root folders in Chaptarr. A single
  `:chaptarr/quality-profile` would force the user to click through a dropdown
  for whichever format they didn't default. See the configuration docs for
  the two separate env vars.
- **Readarr stub left in place.** `backend-media` keeps its upstream
  `:readarr [:book]` entry. Since upstream has no `doplarr.backends.readarr`
  namespace, that entry only activates if the user sets `READARR__URL` — which
  would crash anyway. Keeping it preserves clean merges with upstream.
- **Four per-format profile IDs on author POST.** Chaptarr silently ignores
  the singular `qualityProfileId` / `metadataProfileId` fields accepted by
  other *arrs. The author record needs `ebookQualityProfileId`,
  `audiobookQualityProfileId`, `ebookMetadataProfileId`, and
  `audiobookMetadataProfileId` all set, or the author lands with no usable
  config. `request-payload` sends all four.
- **Profile type discriminators differ between endpoints.** `/qualityprofile`
  returns `profileType` as a string (`"ebook"` / `"audiobook"`);
  `/metadataprofile` returns it as an int (`0` = None, `1` = audiobook,
  `2` = ebook). `quality-profiles-for` and `metadata-profiles-for` branch on
  this — don't unify them into a single helper.
- **Only the requested format's quality profile prompts.** The non-requested
  format's quality profile and both metadata profiles are auto-resolved from
  config defaults → single-match fallback → first profile of the right type
  → first profile overall. Keeps the Discord interaction to one dropdown max
  while still satisfying Chaptarr's four-field requirement.
