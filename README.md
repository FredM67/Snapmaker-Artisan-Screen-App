# FabScreen

Android touchscreen control application for Snapmaker 3-in-1 3D printers, including Artisan (A400), J1, and A350 series devices.

FabScreen provides a native Android interface to control 3D printing, laser engraving, and CNC machining workflows directly from the device touchscreen.

## Supported Devices

- **Snapmaker Artisan** (A400) — 3D printing, laser engraving, CNC machining
- **Snapmaker J1** — Dual-extruder IDEX 3D printing
- **Snapmaker A350** — 3D printing, laser engraving, CNC machining

## Features

- 3D printing, laser engraving, and CNC machining control
- Wi-Fi and USB file transfer
- Real-time machine status monitoring
- Firmware over-the-air (OTA) updates
- Laser camera and auto-focus support (Artisan series)
- Bluetooth-based laser camera capture
- Modular tool head detection and management
- Multi-language UI support

## Project Structure

```
apps/
  a400/          Artisan (A400) main application
  a350/          A350 main application
  j1/            J1 main application
  updater/       OTA updater app
features/
  print/         3D print workflow
  settings/      Device settings and firmware update
  machine-tools/ Machine tool control (laser, CNC)
  file-manager/  File browsing and transfer
  remote/        Remote control features
  home/          Home screen
  guide/         Onboarding and setup guide
  welcome/       First-time setup wizard
  add-ons/       Accessory management
platform/
  base/          Core platform layer (services, machine models, connection)
  core/          UI framework and shared components
  lib/           Utility libraries (serial port, checksum, etc.)
buildSrc/        Gradle dependency version catalog
scripts/         Internal build and deployment scripts
ijkplayer_java/  Video player integration
```

## Dependencies

Essential third-party libraries:

- [ButterKnife](https://github.com/JakeWharton/butterknife) — view binding
- [RxJava](https://github.com/ReactiveX/RxJava) — reactive state management
- [RxAndroid](https://github.com/ReactiveX/RxAndroid) — Android main thread scheduler
- [Okio](https://github.com/square/okio) — byte array and I/O processing
- [AndServer](https://github.com/yanzhenjie/AndServer) — embedded HTTP server for remote commands
- [Retrofit 2](https://github.com/square/retrofit) — HTTP API client
- [ARouter](https://github.com/alibaba/ARouter) — in-app routing
- [Firebase Crashlytics](https://firebase.google.com/products/crashlytics) — crash reporting
- [ijkplayer](https://github.com/bilibili/ijkplayer) — video playback

## Prerequisites

- **Android Studio** 3.5+
- **JDK** 1.8
- **Android NDK** 22.1.7171670
- **Gradle** 5.4.1+ (wrapper included)

## Obico integration on Artisan

FabScreen can connect directly to Obico without a Raspberry Pi or other companion computer. To link it:

1. Open the web dashboard, go to **Settings > Obico**, and save the server settings.
2. In Obico, choose **Link New Printer** (or **Link Printer** on the web), then **OctoPrint**. Continue past the plugin prompt (**Next** on the web or **Yes, plugin is installed** on mobile). This choice only selects Obico's six-digit-code setup route; you do not need to install OctoPrint.
3. On Obico's printer-scanning page, choose **Manual Setup** (or **Switch to Manual Linking**), then continue until Obico shows a six-digit verification code. Do not use the nearby-printer **Link** button; its OctoPrint discovery flow does not work with FabScreen.
4. Enter that six-digit code in FabScreen's **Settings > Obico** and press **Verify code**. FabScreen should then show **Connected**.

For a self-hosted Obico server, use the same server address in Obico and FabScreen. Obico's [manual OctoPrint linking guide](https://www.obico.io/docs/user-guides/octoprint-plugin-setup-manual-link/) shows the six-digit-code screens.

The integration provides printer status, job progress, two-second temperature telemetry, print events and notifications, optional USB or IP-camera snapshots for monitoring and AI detection, and separately opt-in remote controls. The allowlisted control surface includes pause/resume/cancel, XYZ jogging and homing, heater targets, extrusion, feed/flow/fan tuning, and secure Obico cloud G-code download and print start. Arbitrary G-code remains rejected.

The Artisan build offers live camera viewing through a Janus-compatible MJPEG-over-WebRTC data channel, using the selected USB or IP camera. Reload an Obico printer page after updating FabScreen: an already-open Obico page keeps its original snapshot-only player. On Obico Free, press Play to start a live-view cycle; Obico limits this to up to 5 FPS for 30 seconds followed by a cooldown, during which its captured-image fallback warning may still appear. The local WebRTC peer does not need extra printer-side hardware.

JPEG uploads remain separate from live viewing because Obico uses unboosted images for AI failure detection. FabScreen prioritizes these images when requested, normally about twelve seconds after Obico accepts the previous one, and otherwise sends one unboosted image per minute during an active print. Viewer-only fallback uploads stop while a WebRTC data channel is actually open; during a print they are paced to ten seconds, and a cloud HTTP 429 pauses uploads for 65 seconds before retrying at a reduced fallback cadence. Actual AI analysis and automatic pausing are controlled by Obico and are not guaranteed merely by a live feed.

To use an IP camera, open the web dashboard's **Camera** page, select **IP camera (MJPEG)**, enter its LAN MJPEG URL (for example `http://192.168.1.24:81/stream?raw=1`), and enable the camera. Some cameras display an HTML viewer at `/stream` and put the actual MJPEG feed in an image URL on that page; FabScreen follows one same-origin image URL when it detects this. The camera is fetched by FabScreen and served through the existing authenticated dashboard endpoint; Obico camera uploads use the same selected source when separately enabled. HTTP and HTTPS private-LAN streams are supported. URL query parameters are supported, but embedded credentials and fragments are not; redirects and non-LAN destinations are rejected. Capture stops when no dashboard or Obico viewer has a lease.

For an RTSP camera, select **IP camera (RTSP)** and enter the full camera-specific address, including credentials if needed: `rtsp://USERNAME:PASSWORD@IP_ADDRESS:554/stream_path`. The address must use a numeric private-LAN IP and a stream path. Port 554 is the usual RTSP default and may be omitted; the stream path is supplied by the camera maker (not every camera has `stream1` or `stream2`). Percent-encode reserved characters in credentials. The URL field clears after saving and a blank field retains the saved address. FabScreen stores network-camera URLs using the Android Keystore and does not return them from the dashboard status API. RTSP is decoded on the Artisan through ExoPlayer's RTSP source and the Android video decoder, then delivered as JPEG frames to the Camera page, Obico snapshots, and the existing WebRTC live-view bridge. **Output resolution** sets the JPEG dimensions up to 1920×1080; first-time RTSP setup defaults to 640×360 for a responsive preview, and higher sizes can be selected for snapshots or timelapses. It does not change the resolution of the stream sent by the camera. The Artisan H.264 decoder supports a camera input up to 1920×1088 (use at most 1920×1080 in the camera's own quality settings). A higher-resolution camera stream can fail before FabScreen can resize it. The preview frame-rate setting limits published JPEGs, not the camera's native RTSP frame rate. Higher output resolution uses more processor time; a lower frame rate is suitable for high-detail snapshots and timelapses. Capture stops when there are no viewers or Obico consumers. RTSP itself does not encrypt camera traffic, so use a trusted LAN and a dedicated camera account.

MJPEG and RTSP addresses are saved separately. Switching to a USB camera or between IP camera types reuses each saved address without displaying its credentials again; enter a new address only to replace the saved one.

On the web dashboard's **Camera** page, turn on **Show on Dashboard** to display a live camera view beside the print job. This is a browser preference, off by default; the Camera and Dashboard views share one capture session, which stops when neither view needs it.

Obico connections require HTTPS. Printer tokens are kept in the Android Keystore and are never returned by the dashboard API or written to logs. Camera uploads and remote controls are disabled independently, and remote commands are state-checked, rate-limited, and deduplicated before reaching the machine. Treat the local FabScreen dashboard as a trusted-LAN interface: anyone who can open it already has access to printer controls and should not be considered an untrusted guest.

## Getting Started

### 1. Clone the repository

```bash
git https://github.com/Snapmaker/Snapmaker-Artisan-Screen-App.git
cd Snapmaker-Artisan-Screen-App
```

### 2. Configure Firebase (optional)

Firebase Crashlytics and Analytics are used by default. If you want to use Firebase services, copy the example templates and fill in your own Firebase project credentials:

```bash
cp apps/a400/google-services.json.example apps/a400/google-services.json
cp apps/a350/google-services.json.example apps/a350/google-services.json
cp apps/j1/google-services.json.example apps/j1/google-services.json
```

If you do not need Firebase, remove the `com.google.gms.google-services` and `com.google.firebase.crashlytics` plugins from each app's `build.gradle`, and remove the Firebase dependency entries.

### 3. Configure signing (optional)

Debug builds do not require signing configuration. For release builds, create a keystore and configure signing credentials via environment variables or `gradle.properties`:

```bash
export KEYSTORE_PATH=/path/to/your.keystore
export KEYSTORE_PASSWORD=your_store_password
export KEY_ALIAS=your_key_alias
export KEY_PASSWORD=your_key_password
```

Alternatively, add these to your `~/.gradle/gradle.properties` or project-level `local.properties`:

```properties
KEYSTORE_PATH=/path/to/your.keystore
KEYSTORE_PASSWORD=your_store_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

### 4. Build

```bash
# Build all debug APKs
./gradlew assembleDebug

# Build a specific variant
./gradlew :apps:a400:assembleDebug
./gradlew :apps:j1:assembleDebug
```

## Style Guide

```java
class FooFragment extends BaseFragment {

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // initialize
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_foo;
    }

    private void initView() {

    }

    private void initData() {

    }

    private void handleBar() {

    }

    @OnClick(R.id.btn_baz)
    void onClickBaz() {

    }
}
```

## Author

Snapmaker Software Team

## License

TBD
