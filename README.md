# DoplarrChaptarr

A fork of [Kiran Shila's Doplarr](https://github.com/kiranshila/Doplarr) that adds **[Chaptarr](https://github.com/robertlordhood/chaptarr)** support. Chaptarr is a Readarr fork that manages both ebooks and audiobooks from one backend. This fork registers two new Discord slash subcommands:

- `/request book` — request an ebook
- `/request audiobook` — request an audiobook

Everything upstream Doplarr does (`/request movie`, `/request series`, Sonarr / Radarr / Overseerr backends) continues to work unchanged.

## What this fork adds

- Chaptarr backend wired to Chaptarr's Readarr-v1-compatible API
- Per-format config keys for root folders and quality profiles, so ebook and audiobook requests land in the right location without prompting the user at request time
- Cross-format handling: requesting an audiobook for a book you already have as an ebook (or vice versa) flips the relevant `monitored` flag via `PUT /book/{id}` and triggers `BookSearch`, instead of 409-ing on a duplicate `foreignBookId`
- Author adds use `monitorNewItems: "none"` + `addOptions.monitor: "specificBook"` so only the requested book is monitored — Chaptarr does not pull in the author's entire back catalog
- Both ebook and audiobook root folder paths are always set on the author record to avoid Chaptarr's "switched save locations" bug

See [CHAPTARR_FORK.md](CHAPTARR_FORK.md) for the exact list of additive new files vs. localized modifications — designed so future upstream Doplarr releases can be merged with minimal friction.

## Quick start

### 1. Pull the image

Public multi-arch image (linux/amd64, linux/arm64) published on every push to `main`:

```
ghcr.io/elbrielle/doplarrchaptarr:main
```

### 2. Configure environment variables

At minimum:

```
DISCORD__TOKEN=<your discord bot token>
CHAPTARR__URL=http://localhost:8789
CHAPTARR__API=<your chaptarr api key from Settings → General>
```

Recommended (so requests skip the root-folder and quality-profile dropdowns):

```
CHAPTARR__EBOOK_ROOTFOLDER=/cw-book-ingest
CHAPTARR__AUDIOBOOK_ROOTFOLDER=/audiobooks/audiobooks
CHAPTARR__EBOOK_QUALITY_PROFILE=<your ebook quality profile name, e.g. E-Book>
CHAPTARR__AUDIOBOOK_QUALITY_PROFILE=<your audiobook quality profile name, e.g. Audiobook>
CHAPTARR__EBOOK_METADATA_PROFILE=<your ebook metadata profile name, e.g. Ebook Default>
CHAPTARR__AUDIOBOOK_METADATA_PROFILE=<your audiobook metadata profile name, e.g. Audiobook Default>
```

Optional — only if you want book cover images in the Discord confirmation
embed (Chaptarr returns relative cover paths that Discord can't fetch on its
own):

```
CHAPTARR__PUBLIC_URL=https://chaptarr.example.com
```

This must be publicly reachable from Discord's servers — a Docker-internal
`http://chaptarr:8789` won't work. If left unset, book requests still succeed;
the confirmation embed just renders without a cover thumbnail.

If you also want movie / TV requests through the same bot, add `OVERSEERR__URL` + `OVERSEERR__API` (or `SONARR__*` + `RADARR__*`) alongside the Chaptarr keys. All three backend families can coexist.

Full config reference: [docs/configuration.md](docs/configuration.md).

### 3. Deploy

Docker Compose snippet:

```yaml
doplarr:
  image: ghcr.io/elbrielle/doplarrchaptarr:main
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

All modifications in this fork are purely additive insertions into a small set of upstream files — conflicts during merge should be mechanical. [CHAPTARR_FORK.md](CHAPTARR_FORK.md) documents each modification block and where to re-apply it if the upstream surface shifts.

## Credits

- [kiranshila/Doplarr](https://github.com/kiranshila/Doplarr) — all of Doplarr's architecture, Discord wiring, and backend patterns. This fork only adds the Chaptarr backend.
- [robertlordhood/chaptarr](https://github.com/robertlordhood/chaptarr) — the book manager being integrated.

## License

MIT — same as upstream. See [LICENSE](LICENSE).
