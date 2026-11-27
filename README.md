# Looking For Team server

Backend for the [Looking For Team](https://github.com/milftastic/lft-plugin) RuneLite plugin. It keeps LFT
statuses in memory and answers lookups. It never sees player names: only
SHA-256 hashes (see the plugin README for how visibility keys work).

Statuses expire 5 minutes after their last refresh and plugins refresh every
15 seconds while looking, so nothing needs to survive a restart and there is no database.

## Run locally

```
LFT_DEV=true ./gradlew run      # http://localhost:8080, with /dev test endpoints
./gradlew test
```

`/dev/seed` (dev only) marks a player as LFT, visible to the listed viewers:

```
curl -X POST localhost:8080/dev/seed -d '{"name":"Bob","visibleTo":["Alice"],"activities":["TOB"]}'
```

## Configuration

| Env var | Default | |
|---|---|---|
| `PORT` | 8080 | |
| `MAX_STORED_KEYS` | 600000 | Total visibility keys held (~300 bytes each). Beyond this, new publishes get 503. |
| `RATE_PER_MINUTE` / `RATE_BURST` | 60 / 30 | Per-client limit. A plugin that is looking uses ~8/min; one that is not uses none. |
| `LFT_DEV` | off | Enables `/dev/*`. **Never set on a public server.** |

## Deploy to Fly.io

Run **exactly one machine**: statuses live in memory, so two machines would each
hold half of them.

```
fly launch --no-deploy      # first time: creates the app, fills in fly.toml's app name
fly deploy --ha=false       # builds the Dockerfile remotely and starts one machine
fly scale count 1           # make sure there's only one
curl https://<app>.fly.dev/health
```

`fly.toml` keeps the machine always on (a sleeping server would empty everyone's
list) on a 512 MB shared CPU.
