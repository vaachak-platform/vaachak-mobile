# Self-Hosted Calibre with Caddy + Tailscale

This guide explains how to expose a personal Calibre Content Server over HTTPS for **Leisure Vaachak** using:

- **Calibre Content Server**
- **Caddy** as a reverse proxy
- **Tailscale MagicDNS / `*.ts.net` hostname**
- **Basic Auth** on the Calibre server

This is intended for a **personal/private homelab** setup.

## Architecture

```text
Leisure Vaachak on Android
        │
        │ HTTPS
        ▼
https://mycalibre.tailxxxxx.ts.net/calibre/opds
        │
        ▼
Caddy reverse proxy on Mac mini
        │
        ▼
Calibre Content Server on localhost:8080
```

## Important requirements

1. The Android device running Leisure Vaachak must also be connected to the **same Tailscale tailnet**.
2. **MagicDNS** must be enabled in Tailscale.
3. Calibre should run locally on the Mac mini and be exposed through **Caddy over HTTPS**.
4. In Vaachak, use the OPDS URL:

```text
https://mycalibre.tailxxxxx.ts.net/calibre/opds
```

5. Save the Calibre username and password in the catalog settings inside the app.

## Why this setup

This setup keeps the mobile client simple and secure:

- Vaachak uses **strict HTTPS only**
- no insecure HTTP fallback
- no trust-all TLS client code
- hostname-based access instead of raw local IPs
- Caddy handles HTTPS, while Calibre stays local behind the proxy

## Step 1: Start Calibre Content Server

Run Calibre with a URL prefix so it works cleanly behind the reverse proxy under `/calibre`.

Example:

```bash
calibre-server \
  /path/to/your/calibre-library \
  --port 8080 \
  --listen-on 127.0.0.1 \
  --url-prefix /calibre \
  --enable-auth \
  --userdb /path/to/calibre-users.sqlite
```

### Notes

- `--listen-on 127.0.0.1` keeps Calibre local to the Mac mini.
- `--url-prefix /calibre` is important because the reverse proxy will publish Calibre under `/calibre`.
- `--enable-auth` and `--userdb` let you protect the server with Calibre’s own user accounts.

## Step 2: Configure Caddy

Use a Caddyfile like this:

```caddy
mycalibre.tailxxxxx.ts.net {
    encode zstd gzip

    @health path /health
    handle @health {
        respond "ok" 200
    }

    handle /calibre/* {
        reverse_proxy 127.0.0.1:8080
    }

    handle {
        respond "Not Found" 404
    }
}
```

### Why `handle /calibre/*`

Calibre is already configured with:

```text
--url-prefix /calibre
```

So the proxy should **preserve** the `/calibre` prefix when forwarding requests upstream.

## Step 3: Reload Caddy

After updating the Caddyfile:

```bash
caddy validate --config /path/to/Caddyfile
caddy reload --config /path/to/Caddyfile
```

## Step 4: Verify from a trusted client

On a machine that is already on the same tailnet, test:

```bash
curl -v https://mycalibre.tailxxxxx.ts.net/health
curl -v https://mycalibre.tailxxxxx.ts.net/calibre/
curl -u YOUR_USERNAME:YOUR_PASSWORD -v https://mycalibre.tailxxxxx.ts.net/calibre/opds
```

Expected behavior:

- `/health` returns `200 ok`
- `/calibre/` may return `401` until credentials are supplied
- `/calibre/opds` should return the OPDS feed when valid credentials are provided

## Step 5: Add the OPDS feed in Leisure Vaachak

Inside the app, add a catalog with:

- **Title:** anything you like, for example `My Calibre Server`
- **URL:** `https://mycalibre.tailxxxxx.ts.net/calibre/opds`
- **Username:** your Calibre username
- **Password:** your Calibre password

Then save the catalog and test loading it.

## Android / Tailscale behavior

If the URL works on your Mac but **does not resolve on Android**, the most common cause is that the phone is not currently connected to the same Tailscale tailnet.

Typical symptom in the phone browser:

```text
DNS_PROBE_FINISHED_NXDOMAIN
```

### Fix

- install/open the Tailscale app on the phone
- sign in to the same tailnet
- confirm the phone is connected
- retry:

```text
https://mycalibre.tailxxxxx.ts.net/health
```

If the browser can reach the URL, Vaachak should be able to use the same OPDS endpoint too.

## Troubleshooting

### 1. `401 Unauthorized`

Calibre authentication is enabled and the request is missing valid credentials.

Check:

- username
- password
- Calibre user database path
- that the same credentials work with `curl -u ...`

### 2. `DNS_PROBE_FINISHED_NXDOMAIN` on Android

The phone is not resolving the `*.ts.net` hostname.

Check:

- Tailscale app is installed
- signed into the same tailnet
- MagicDNS is enabled
- Tailscale connection is active

### 3. `404 Not Found`

Usually means one of these:

- Caddy is not matching `/calibre/*`
- Calibre was started without `--url-prefix /calibre`
- the OPDS path being used is wrong

Verify:

```text
https://mycalibre.tailxxxxx.ts.net/calibre/
https://mycalibre.tailxxxxx.ts.net/calibre/opds
```

### 4. Vaachak cannot load the feed but browser works

Check:

- saved URL inside Vaachak exactly matches the working OPDS URL
- credentials were saved correctly
- there are no older `http://` or raw IP feed entries still stored in the app

## Security model

This setup is intentionally scoped for personal homelab use:

- Tailscale limits access to devices on your tailnet
- Caddy provides HTTPS on the published hostname
- Calibre authentication protects the OPDS feed
- Vaachak uses strict HTTPS and does not rely on insecure trust-all TLS behavior

## Recommended future extension

When you later add **Audiobookshelf** on the same Mac mini, use the same pattern:

- keep the backend service local
- expose it through Caddy
- publish it on a stable hostname/path
- access it from Vaachak only over HTTPS
- require Tailscale on client devices for private homelab access

Example future layout:

```text
https://mycalibre.tailxxxxx.ts.net/calibre/...
https://mycalibre.tailxxxxx.ts.net/audiobookshelf/...
```

## Summary

For a private homelab setup, this is the recommended pattern for Vaachak:

- **Calibre local on Mac mini**
- **Caddy reverse proxy**
- **HTTPS only**
- **Tailscale MagicDNS hostname**
- **Tailscale required on the client device**
- **Vaachak configured with the `/calibre/opds` URL and login info**