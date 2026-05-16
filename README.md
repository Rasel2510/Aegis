# 🛡️ Real Ad Blocker — Flutter + Local VPN

This app **actually blocks ads** using a local VPN that intercepts DNS traffic on your Android phone. No data leaves your device.

## How it works

1. App starts a local VPN (Android `VpnService`)
2. All traffic is routed through the VPN
3. DNS queries are intercepted and parsed
4. If the domain matches the blocklist → NXDOMAIN (blocked)
5. Otherwise the request passes through normally

This is the same technique used by AdGuard, Blokada, and DNS66.

---

## Build Instructions

### Requirements
- Flutter 3.10+ installed → https://flutter.dev/docs/get-started/install
- Android Studio with Android SDK (API 21+)
- A physical Android phone or emulator

### Steps

```bash
# 1. Unzip and enter directory
unzip adblocker_real.zip
cd adblocker_real

# 2. Edit android/local.properties — set your paths:
#    sdk.dir=/Users/YOU/Library/Android/sdk
#    flutter.sdk=/Users/YOU/flutter

# 3. Install dependencies
flutter pub get

# 4. Run on connected phone (debug)
flutter run

# 5. Build release APK
flutter build apk --release
# APK will be at: build/app/outputs/flutter-apk/app-release.apk
```

### Install APK on phone
```bash
adb install build/app/outputs/flutter-apk/app-release.apk
```
Or copy the APK to your phone and open it (enable "Install unknown apps" in Settings first).

---

## First Launch

1. Open the app
2. Tap the big shield button
3. Android will show a VPN permission dialog — tap **OK**
4. The shield turns ON — ads are now blocked system-wide

---

## What's blocked

- YouTube ad servers (pre-roll, banner, mid-roll)
- Google Ads / DoubleClick
- Facebook / Meta ads
- TikTok, Snapchat, Twitter ad networks
- 80+ major ad networks
- Tracking & analytics domains
- Known malware domains

---

## Limitations

- **YouTube Premium ads**: YouTube increasingly serves ads from the same domain as videos (`youtube.com`), so those specific server-side injected ads can't be blocked by DNS. For full YouTube blocking, use **YouTube ReVanced**.
- **iOS**: Apple does not allow the `VpnService` API that makes this work on Android. Use AdGuard from the App Store on iOS instead.
- **Always-on VPN**: You can enable this in Android Settings → Network → VPN → Ad Blocker → Always-on VPN for auto-start on reboot.

---

## Project Structure

```
lib/
├── main.dart
├── providers/ad_block_provider.dart   # State + VPN polling
├── services/vpn_service.dart          # Flutter ↔ Kotlin bridge
└── screens/home_screen.dart           # UI

android/app/src/main/kotlin/.../
├── MainActivity.kt                    # VPN permission flow
└── AdBlockVpnService.kt               # REAL VPN + DNS filter

assets/
└── blocklist.txt                      # Ad domain blocklist
```
