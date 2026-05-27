# Aegis — System-wide Ad Blocker for Android

A privacy-first, on-device ad blocker using a local VPN + DNS interception.
No data leaves your device. No remote servers. No subscriptions.

## How It Works

Aegis runs a local DNS server on `127.0.0.1:5053`. The VPN interface tells
Android to route all DNS queries there. Blocked domains return NXDOMAIN.
Allowed domains are forwarded to your chosen upstream (1.1.1.1 by default,
or any DoH server you configure).

Optional HTTPS filtering (Phase 2): a local TLS proxy intercepts HTTPS
connections, inspects the SNI/Host, and blocks ad endpoints. Requires
installing a CA certificate from the app.

## Architecture

```
Any app → DNS query → 127.0.0.1:5053 (LocalDnsServer)
                            ↓                    ↓
                     BlocklistEngine     forwardToUpstream()
                            ↓               (protect()d socket)
                       NXDOMAIN              1.1.1.1 / DoH
```

## Features

- ✅ System-wide DNS-based ad blocking (all apps, no root needed)
- ✅ 100k+ domain blocklist (StevenBlack + hagezi, auto-updated every 24h)
- ✅ YouTube ad domain blocking
- ✅ Real-time query log with filter/search
- ✅ Per-session and 30-day statistics
- ✅ DNS-over-HTTPS (Cloudflare, Google, AdGuard, custom)
- ✅ Custom block/allow rules
- ✅ Per-app exclusions (for banking apps with cert pinning)
- ✅ Boot auto-start with watchdog
- ✅ HTTPS filtering with local CA (optional, requires cert install)
- ✅ Dark mode

## Building

### Debug (sideload)
```bash
flutter build apk --debug
```

### Release (signed)
```bash
# 1. Generate keystore (once)
keytool -genkey -v -keystore aegis-release.jks \
        -alias aegis -keyalg RSA -keysize 2048 -validity 10000

# 2. Set environment variables
export AEGIS_KEYSTORE_PATH=/path/to/aegis-release.jks
export AEGIS_KEYSTORE_PASS=yourpassword
export AEGIS_KEY_ALIAS=aegis
export AEGIS_KEY_PASS=yourpassword

# 3. Build
flutter build apk --release
# Output: build/app/outputs/flutter-apk/app-release.apk
```

## Distribution

Google Play bans VPN-based ad blockers. Distribute via:
- **F-Droid** — see `fdroid/metadata/com.aegis.app.yml`
- **GitHub Releases** — attach the signed APK directly

## Limitations

- DNS blocking only catches domains resolved via DNS. Apps using hardcoded
  IPs or DNS-over-HTTPS bypass it (enable HTTPS filtering to catch more).
- HTTPS filtering requires the user to install a CA certificate. Certificate-
  pinned apps (banking, Signal, WhatsApp) must be added to the exclusion list.
- YouTube constantly changes its ad server infrastructure. The blocklist needs
  regular updates — Aegis fetches fresh lists automatically every 24 hours.

## License

GPL-3.0 — see LICENSE
