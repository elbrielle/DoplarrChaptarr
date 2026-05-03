# Changelog

Fork-specific changes only. Upstream Doplarr changes aren't duplicated here — see [kiranshila/Doplarr](https://github.com/kiranshila/Doplarr) for those. The fork diverged from upstream `v3.7.0`.

Format roughly follows [Keep a Changelog](https://keepachangelog.com/). Version numbers are independent of upstream's.

## [Unreleased]

### Changed
- Reworked cover sourcing away from a multipart proxy through Chaptarr, so `CHAPTARR__PUBLIC_URL` is no longer required and Chaptarr can stay on an internal network. Cover attachment via public CDNs (Hardcover, Amazon, Goodreads) with OpenLibrary-by-ISBN and Amazon-by-ASIN fallbacks is in place but currently inconsistent — see Known issues in the README.

### Fixed
- Decode JSON API responses so non-string fields render correctly in confirmations.

## [0.2.0] - 2026-04-23

### Added
- Pre-request POST during embed render, so cover URLs resolve before the user clicks Request.
- Filter out study guides and summaries from lookup results.
- Existing-author fast path with a tier-preferred exact-title match.
- Selection diagnostic logging (`anyEditionOk`, `hardcoverBookId`).

### Changed
- Renamed `CHAPTARR_FORK.md` to `FORK_NOTES.md`.
- Prefer shorter edition titles over marketing-heavy ones.
- Trim verbose user-facing copy.
- Show a progress message and strip the Request button as soon as it's clicked, so duplicate clicks don't double-submit.
- Doc consolidation and copy edits across the README and integration notes.

### Fixed
- Cross-namespace author name normalization when provider namespaces differ.
- Match `foreignAuthorId` against indexed authors to skip a duplicate POST.
- `RefreshAuthor` now remediates placeholder rows before the `:processing` short-circuit fires.
- Wrap the request body in try/catch so exceptions render to Discord instead of vanishing on a hato HTTP worker thread.
- Guard against a bogus `:processing` status that could trap a request mid-flight.

## [0.1.0] - 2026-04-21

Initial fork. Adds Chaptarr support via two new Discord subcommands:

- `/request book`
- `/request audiobook`

### Added
- Chaptarr backend on the Readarr-v1-compatible API (`backends/chaptarr.clj` + `backends/chaptarr/impl.clj`).
- Per-format config keys for root folder, quality profile, and metadata profile (ebook + audiobook), so requests skip the dropdowns when defaults are set.
- Cover images on Discord confirmation embeds (initial implementation, later reworked).
- Two-step monitor flow with an explicit indexer search, HTML stripping in the embed body, and author name in the result dropdown.
- Best-match book picker for ambiguous lookup results.
- Author POST sends all four per-format profile ids (`bookQualityProfileId`, `audiobookQualityProfileId`, `bookMetadataProfileId`, `audiobookMetadataProfileId`); Chaptarr silently ignores the singular variants other *arrs accept.
- Author-level `bookMonitorFuture` / `audiobookMonitorFuture` handling so per-book monitor flips actually persist.
- Fork README and CI workflow publishing multi-arch (linux/amd64, linux/arm64) images to GHCR.

### Fixed
- Constrain polling and book selection to the requested title.
- Switch monitor flips to `PUT /book/monitor`. The per-book `PUT /book/{id}` returns 2xx but silently drops monitor-flag changes.
- Poll for the resolved edition row before the monitor-flip PUT.
- Prefer resolved editions over placeholder rows in book selection.
- Discord 50035 on the book confirmation embed: only assoc `:image` when `poster` is non-nil.
