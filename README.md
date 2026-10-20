# RescueMesh (Phase 1)
Offline direct messaging between two+ Android phones (Android 12+, Google Play services required).

## Build on GitHub
1. Create a repo, upload all files (keep the `.github` folder), push to `main`.
2. Actions tab → "Build APK" → open the run → download artifact `RescueMesh-debug-apk`.
3. Unzip, install `app-debug.apk` on two phones (allow unknown sources).

## Or build locally
Open in Android Studio (it creates the Gradle wrapper) and Run.

## Test (2 phones)
1. Both: open app, set a name, tap "Start searching" and allow Nearby devices permission. Turn on Bluetooth and Wi-Fi (no internet needed).
2. Phone A: "My QR code". Phone B: Contacts → Scan. Repeat the other way (both must add each other).
3. Wait for "Linked now", open Chat, send. Status goes Queued → Sent → Delivered ✓ (Delivered only after the other phone's ACK).
4. Queue test: stop searching on B, send from A (stays Queued), start B again; it should flush.

## Not implemented yet
Multi-hop relay, SOS, broadcasts, location, foreground service/notifications, Rescue Team mode, expiry, rate limits.
Security notes: AES-256-GCM over ECDH (P-256) with Keystore identity key; no forward secrecy; messages stored unencrypted locally; untested on real devices.
