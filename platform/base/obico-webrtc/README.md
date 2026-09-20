# Obico WebRTC peer (Artisan)

This 32-bit Android/ARM sidecar publishes the existing FabScreen JPEG camera
frames as Obico's Janus-compatible `mjpeg_webrtc` data-only stream (mountpoint
ID 2). It does not replace the separate JPEG snapshot uploads used for Obico's
AI failure detection. The service is available only on the Artisan Android 10
firmware and must be supervised by FabScreen's Java Obico connector.

Build on Windows with Go 1.24+, Android NDK 22.1.7171670, and PowerShell:

```powershell
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\platform\base\obico-webrtc\build-android.ps1
```

Alternatively pass `-NdkRoot` pointing at the NDK installation. The script runs
the Go tests and builds `apps/a400/libs/armeabi-v7a/libobicopeer.so`. Android
requires an externally linked PIE executable for `GOOS=android/GOARCH=arm`, so
the build uses `CGO_ENABLED=1` and the NDK's API 26 ARM compiler. The `.so`
suffix lets Android extract it to the app's executable `nativeLibraryDir`; this
is a standalone executable, not a JNI library. The APK build checks both its
binary hash and a hash of the Go sources/modules, failing if it is stale.

Pion depends on `github.com/wlynxg/anet`, whose Android-specific `go:linkname`
to Go's internal `net.zoneCache` fails with Go 1.24. The local compatibility
shim uses the same standard `net` interface enumeration that upstream `anet`
uses on Android 10 (API 29). It is **not** a general Android 11+ replacement.

The Java service opens a loopback listener and launches the sidecar with only a
port and random nonce in argv. Each side sends a JSON-line `hello` with that
nonce and rejects a mismatch. The Obico authentication token is sent only after
this handshake in `{"type":"init","auth_token":"..."}`. No token is logged or
placed in argv. The sidecar emits `{"type":"status","state":"ready"}` after a
successful init; `idle` and `streaming` are also ready states, `error` is not.

Java-to-Go messages are `{"type":"janus","payload":"<raw Janus JSON>"}` and
`{"type":"frame","jpeg":"<base64 JPEG>"}`. Go-to-Java Janus responses use
the same `type`/`payload` shape. Messages are capped at 2 MiB per line; decoded
JPEGs at 512 KiB. Java should scale frames to at most 640×360, typically ~60
KiB, and target up to 5 FPS for Obico Free. The ordered DataChannel sends the
Obico MJPEG header followed by 12 KiB base64 chunks with bounded backpressure;
slow viewers drop stale whole frames. Multiple viewers use separate peer
connections (maximum four).
