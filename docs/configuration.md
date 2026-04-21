# Configuration

## Discord

The first step in configuration is creating the bot in Discord itself.

1. Create a new [Application](https://discord.com/developers/applications) in Discord
2. Go to the Bot tab and add a new bot
3. Copy out the token, this will be used for the DISCORD__TOKEN setting
4. Go to OAuth2 and under "OAuth2 URL Generator", enable `applications.commands` and `bot`
5. Copy the resulting URL and open it in a web browser to authorise the bot to join your server

### Permissions

As of Doplarr v3.5.0, we removed the ability to role-gate the bot via our
configuration file as Discord launched the command permissions system within the client itself.

To access this, after adding the bot to your server, navigate to `Server Settings -> Integrations -> Doplarr (or whatever you named it) -> Manage` and
from there you can configure the channels for which the bot is active and who
has access to the bot. This is a lot more powerful than the previous system and
users of the previous `ROLE_ID`-based approach must update as Discord broke the
old system.

## Sonarr/Radarr

All you need here are the API keys from `Settings->General`.
For these backends, you need to set the `SONARR__URL` and `SONARR__API`
environment variables or `:sonarr/url` and `:sonarr/api` config file entries to
their appropriate values The URLs _must_ contain the leading protocols (i.e.
`http://` or `https://`).

## Overseerr

Sonarr/Radarr and Overseerr are mutually exclusive - you only need to configure
one. If you are using Overseerr, your users must have associated discord IDs, or
the request will fail. For this backend, you will set `OVERSEERR__URL` and
`OVERSEERR__API`, just like radarr and sonarr. Again, this is set _instead of_
the values for radarr/sonarr.

As a note, this bot isn't meant to wrap the entirety of what Overseerr can do, just the
necessary bits for requesting with optional 4K and quota support. Just use the
web interface to Overseerr if you need more features.

## Chaptarr

Chaptarr is a Readarr fork that manages both ebooks and audiobooks. When
`CHAPTARR__URL` and `CHAPTARR__API` are configured, this fork registers two
slash subcommands: `/request book` (ebook) and `/request audiobook`. The
API key is under `Settings → General` in Chaptarr.

Set `CHAPTARR__URL` and `CHAPTARR__API` in the same form as the other backends
(the URL must include the protocol, e.g. `http://localhost:8789`). Requests
submitted through this bot monitor only the specific book requested — the
author is added with `monitorNewItems: "none"` so Chaptarr does not flood the
library with the entire back catalog.

Because ebooks and audiobooks typically live under separate root folders and
often use different quality profiles, the per-format defaults below are
recommended. When a default is not set and the backend has multiple options,
Doplarr will prompt the user with a dropdown at request time.

## Optional Settings

| Environment Variable (Docker)  | Config File Keyword            | Type    | Default Value | Description                                                                                                                                 |
| ------------------------------ | ------------------------------ | ------- | ------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `DISCORD__MAX_RESULTS`         | `:discord/max-results`         | Integer | `25`          | Sets the maximum size of the search results selection                                                                                       |
| `DISCORD__REQUESTED_MSG_STYLE` | `:discord/requested-msg-style` | Keyword | `:plain`      | Sets the style of the request alert message. One of `:plain :embed :none`                                                                   |
| `SONARR__QUALITY_PROFILE`      | `:sonarr/quality-profile`      | String  | N/A           | The name of the quality profile to use by default for Sonarr                                                                                |
| `RADARR__QUALITY_PROFILE`      | `:radarr/quality-profile`      | String  | N/A           | The name of the quality profile to use by default for Radarr                                                                                |
| `SONARR__ROOTFOLDER`           | `:sonarr/rootfolder`           | String  | N/A           | The root folder to use by default for Sonarr                                                                                                |
| `RADARR__ROOTFOLDER`           | `:radarr/rootfolder`           | String  | N/A           | The root folder to use by default for Radarr                                                                                                |
| `SONARR__SEASON_FOLDERS`       | `:sonarr/season-folders`       | Boolean | `false`       | Sets whether you're using season folders in Sonarr                                                                                          |
| `SONARR__LANGUAGE_PROFILE`     | `:sonarr/language-profile`     | String  | N/A           | The name of the language profile to use by default for Sonarr                                                                               |
| `OVERSEERR__DEFAULT_ID`        | `:overseerr/default-id`        | Integer | N/A           | The Overseerr user id to use by default if there is no associated discord account for the requester                                         |
| `CHAPTARR__EBOOK_ROOTFOLDER`         | `:chaptarr/ebook-rootfolder`         | String  | N/A | The Chaptarr root folder path to use by default for `/request book`. Example: `/cw-book-ingest`                                           |
| `CHAPTARR__AUDIOBOOK_ROOTFOLDER`     | `:chaptarr/audiobook-rootfolder`     | String  | N/A | The Chaptarr root folder path to use by default for `/request audiobook`. Example: `/audiobooks/audiobooks`                              |
| `CHAPTARR__EBOOK_QUALITY_PROFILE`    | `:chaptarr/ebook-quality-profile`    | String  | N/A | The Chaptarr quality profile name to use by default for ebook requests                                                                    |
| `CHAPTARR__AUDIOBOOK_QUALITY_PROFILE`| `:chaptarr/audiobook-quality-profile`| String  | N/A | The Chaptarr quality profile name to use by default for audiobook requests                                                                |
| `CHAPTARR__EBOOK_METADATA_PROFILE`   | `:chaptarr/ebook-metadata-profile`   | String  | N/A | The Chaptarr metadata profile name to use for ebook requests. Chaptarr stores ebook and audiobook metadata profiles separately (profileType 2 vs 1) |
| `CHAPTARR__AUDIOBOOK_METADATA_PROFILE`| `:chaptarr/audiobook-metadata-profile`| String | N/A | The Chaptarr metadata profile name to use for audiobook requests                                                                          |
| `PARTIAL_SEASONS`              | `:partial-seasons`             | Boolean | `true`        | Sets whether users can request partial seasons.                                                                                             |
| `LOG_LEVEL`                    | `:log-level`                   | Keyword | `:info`       | The log level for the logging backend. This can be changed for debugging purposes. One of `:trace :debug :info :warn :error :fatal :report` |

## Windows

Be careful when setting path variables on Windows, you need to use `\\` instead of `\` for path separators.
