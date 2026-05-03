# DoplarrChaptarr

A fork of [Kiran Shila's Doplarr](https://github.com/kiranshila/Doplarr) that adds **[Chaptarr](https://github.com/robertlordhood/chaptarr)** support. Chaptarr is a Readarr fork that manages both ebooks and audiobooks from one backend. This fork registers two new Discord slash subcommands:

- `/request book` — request an ebook
- `/request audiobook` — request an audiobook

Everything upstream Doplarr does (`/request movie`, `/request series`, Sonarr / Radarr / Overseerr backends) continues to work unchanged.

> A personal fork I run on my own server. Chaptarr is still pre-1.0 and ships changes regularly, so something here may break when Chaptarr or upstream Doplarr updates. File an issue if it does. See [CHANGELOG.md](CHANGELOG.md) for what's in each tag.

## What this fork adds

- Chaptarr backend on the Readarr-v1-compatible API.
- Per-format config keys for root folders, quality profiles, and
  metadata profiles, so requests usually skip the dropdowns.
- Cross-format requests (asking for an audiobook when you already
  have the ebook, or vice versa) flip the correct monitor flag and
  fire a fresh indexer search rather than 409-ing on duplicate IDs.
- Author adds only monitor the specific requested book — Chaptarr
  doesn't pull in the author's whole back catalogue.
- Cover images on confirmation embeds come from the resolved book's
  upstream CDN URL (Hardcover / Amazon / Goodreads) with an
  OpenLibrary-ISBN / Amazon-ASIN fallback — no need to expose your
  Chaptarr instance publicly.

If you're deploying this image, read
[`docs/CHAPTARR_INTEGRATION.md`](docs/CHAPTARR_INTEGRATION.md) for
Chaptarr-side setup, environment variables, and the data-model
quirks that shaped the fork. For fork-maintenance concerns (merging
upstream, insertion points, invariants) see
[`FORK_NOTES.md`](FORK_NOTES.md).

## Quick start

### 1. Pull the image

Public multi-arch images (linux/amd64, linux/arm64) are published
on every push to `main`:

```
ghcr.io/elbrielle/doplarrchaptarr:latest   # follows main
ghcr.io/elbrielle/doplarrchaptarr:v0.2.0   # pinned release
```

### 2. Configure environment variables

At minimum:

```
DISCORD__TOKEN=<your discord bot token>
CHAPTARR__URL=http://localhost:8789
CHAPTARR__API=<your chaptarr api key from Settings → General>
```

`CHAPTARR__URL` only needs to be reachable from Doplarr's container;
it can stay on an internal Docker network. Covers on Discord
confirmation embeds are sourced from public CDNs (see README note
above), so there's no need to expose Chaptarr to the public internet.

Recommended (so requests skip the root-folder and quality-profile dropdowns):

```
CHAPTARR__EBOOK_ROOTFOLDER=/cw-book-ingest
CHAPTARR__AUDIOBOOK_ROOTFOLDER=/audiobooks/audiobooks
CHAPTARR__EBOOK_QUALITY_PROFILE=<your ebook quality profile name, e.g. E-Book>
CHAPTARR__AUDIOBOOK_QUALITY_PROFILE=<your audiobook quality profile name, e.g. Audiobook>
CHAPTARR__EBOOK_METADATA_PROFILE=<your ebook metadata profile name, e.g. Ebook Default>
CHAPTARR__AUDIOBOOK_METADATA_PROFILE=<your audiobook metadata profile name, e.g. Audiobook Default>
```

If you also want movie / TV requests through the same bot, add `OVERSEERR__URL` + `OVERSEERR__API` (or `SONARR__*` + `RADARR__*`) alongside the Chaptarr keys. All three backend families can coexist.

Full config reference: [docs/configuration.md](docs/configuration.md).

### 3. Deploy

Docker Compose snippet:

```yaml
doplarr:
  image: ghcr.io/elbrielle/doplarrchaptarr:latest
  container_name: doplarr
  env_file:
    - /srv/appdata/doplarr/.env
  restart: unless-stopped
  networks:
    - mediaserver
```

The container needs outbound access to Discord and HTTP access to Chaptarr. No volume mounts required — Doplarr doesn't touch local disk.

### 4. Register the Discord bot

Follow the Discord bot setup section of [docs/configuration.md](docs/configuration.md) — create an application, enable `bot` + `applications.commands` scopes, and authorize it to your server. You can reuse an existing Doplarr bot; nothing about the bot registration changes in this fork.

## Verify

After the container starts, check the logs for:

```
Configuration is valid
Discord connection successful
Connected to guild
```

Then in Discord:

```
/request book query:Dune
/request audiobook query:Dune
```

A result dropdown should appear. Select a result and the bot walks you through any un-defaulted options, then confirms the request. It should land in Chaptarr's queue and flow through your configured download client.

## Merging upstream Doplarr releases

This fork tracks `kiranshila/Doplarr` as `upstream`:

```
git fetch upstream
git merge upstream/main
```

All modifications in this fork are purely additive insertions into a small set of upstream files — conflicts during merge should be mechanical. [FORK_NOTES.md](FORK_NOTES.md) documents each modification block and where to re-apply it if the upstream surface shifts.

## Credits

- [kiranshila/Doplarr](https://github.com/kiranshila/Doplarr) — all of Doplarr's architecture, Discord wiring, and backend patterns. This fork only adds the Chaptarr backend.
- [robertlordhood/chaptarr](https://github.com/robertlordhood/chaptarr) — the book manager being integrated.

## License

MIT — same as upstream. See [LICENSE](LICENSE).
