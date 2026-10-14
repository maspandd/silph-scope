# Silph Scope 🔮

**`cordova-plugin-tapjack-protection`**: reveals the ghosts hiding over your app.

Tapjacking / screen overlay protection for Cordova apps on **Android 9 to 17 (API 28 to 37)**.

A malicious app with the "Display over other apps" permission can draw a window over your app and trick the user into tapping something they can't see (confirming a payment, granting a permission). This plugin stops those taps from reaching your app, hides third-party overlays where Android allows it, and tells your JS when it happens so you can warn the user.

Protection is **on by default from app launch**, before any of your JS runs.

## Install

```bash
cordova plugin add https://github.com/<owner>/silph-scope.git#0.1.0
```

Pin a tag. Once published to npm, `cordova plugin add cordova-plugin-tapjack-protection` also works.

Set the Android range in your app's `config.xml`:

```xml
<platform name="android">
    <preference name="android-minSdkVersion" value="28" />
    <preference name="android-targetSdkVersion" value="36" />
</platform>
```

The plugin works at runtime on every API level from 28 to 37. Which `targetSdkVersion` you can set depends on your cordova-android version (15.1 defaults to 36).

## What it does, per Android version

| Layer | Android 9 (28) | 10–11 (29–30) | 12–17 (31–37) |
|---|---|---|---|
| Drop touches where the touch point is covered by another window (`setFilterTouchesWhenObscured` + window-level guard) | ✅ | ✅ | ✅ |
| Detect touches when *another part* of the screen is covered (strict mode can block these) | – | ✅ | ✅ |
| Hide third-party overlays while the app is visible (`setHideOverlayWindows`) | – | – | ✅ |
| OS blocks most pass-through touches by itself (untrusted touch occlusion) | – | – | ✅ |

The plugin adds the normal (install-time) permission `android.permission.HIDE_OVERLAY_WINDOWS` to your manifest. No runtime prompt and no Play Console declaration are needed.

## Configuration (`config.xml`)

| Preference | Values | Default | Meaning |
|---|---|---|---|
| `TapjackProtectionMode` | `block`, `detect`, `off` | `block` | `block` swallows obscured touches. `detect` only reports them. An unknown value falls back to `block`. |
| `TapjackStrictMode` | `true`, `false` | `false` | In block mode, also block touches while *any* part of the window is covered (Android 10+). |
| `TapjackHideOverlays` | `true`, `false` | `true` | Hide third-party overlay windows on Android 12+. |

## JS API

Available as `cordova.plugins.tapjackProtection` after `deviceready`. TypeScript types ship in `types/index.d.ts`.

```js
const tapjack = cordova.plugins.tapjackProtection;

// Change options at runtime. Options left out keep their current value.
await tapjack.enable({ mode: 'block', strict: true });   // e.g. on a payment screen
await tapjack.enable({ strict: false });                  // leaving it

await tapjack.disable();
const status = await tapjack.getStatus();

const unsubscribe = tapjack.onObscuredTouch(({ partial, blocked, timestamp }) => {
    if (blocked) showBanner('Another app is drawing over this screen. Close it to continue.');
});

// Or as a document event:
document.addEventListener('tapjackdetected', (e) => console.log(e.partial, e.blocked));
```

`getStatus()` resolves with:

```js
{
  supported: true, platform: 'android', sdkInt: 34,
  mode: 'block', strict: false, hideOverlays: true,
  filterTouchesActive: true,     // setFilterTouchesWhenObscured is on
  hideOverlaysActive: true,      // setHideOverlayWindows took effect (API 31+)
  hideOverlaysSupported: true,
  guardActive: true              // window-level touch guard installed
}
```

Events are sent at most once per gesture and throttled to one per 500 ms. A gesture that escalates to blocked is always reported. Up to 10 events that happen before JS subscribes are buffered and delivered on startup.

On iOS every call resolves with `{ supported: false, platform: 'ios' }`. iOS doesn't let apps draw over other apps, so no protection is needed there.

## Choosing a mode

- **`block` (default):** best for banking, payments and anything where a stray tap is costly. Some users run screen dimmers, blue-light filters or chat heads, which also count as overlays. Listen for `onObscuredTouch` and explain why their taps are ignored, or the app will look broken to them.
- **`detect`:** for telemetry, or apps where false positives would hurt more than tapjacking.
- **`strict`:** turn it on only for the most sensitive screens. It blocks even when the overlay isn't over the touch point.

## Known limitations

- **Accessibility-service overlays can't be stopped.** Android treats them as trusted, so it doesn't hide them, doesn't flag touches under them and doesn't block them. Malware that tricks users into enabling an accessibility service is outside what any app-level protection can handle.
- **Only the Cordova activity is protected.** Other activities (such as those opened by other plugins) and separate dialog windows don't get the decor-view filter. The window-level guard covers the main activity window only.
- **Other libraries that replace `Window.Callback`.** The guard re-installs itself on start/resume and hands off cleanly if something wraps it. `setFilterTouchesWhenObscured` protects even if the guard is removed.
- **JS can turn protection off.** Anyone running script in your WebView can call `disable()`, but at that point tapjacking is the least of your problems.

## Development

```bash
npm install
npm test               # Jest (JS bridge) + JUnit (touch decision logic)
npm run check:native   # javac all Android sources against android-all API 35 + cordova-android 15 (~185 MB download, cached)
```

Neither command needs the Android SDK or Gradle, only Node 20+ and a JDK.

Building the example app or the overlay attacker needs:
- JDK 17 or 21 in `JAVA_HOME`. JDK 25 is too new for the Gradle 8.14 that cordova-android 15 uses.
- The Android SDK (`ANDROID_HOME`) with platform 36 and build-tools 36.0.0.
- Gradle 8.14.x on `PATH`. cordova-android uses it to generate its wrapper.

`example/.npmrc` installs the plugin as a copy, not a symlink, which avoids an endless `ELOOP` because the plugin folder contains `example/`. After changing plugin code, run `npm install` in `example/` again.

### Manual test on a device or emulator

1. Build and install the overlay attacker: open `tests/overlay-attacker` in Android Studio and run it. Grant it "Display over other apps".
2. Build the example: `cd example && npm install && npx cordova platform add android && npx cordova run android`.
3. Start an overlay from the attacker, switch to the example and tap **Tap me**.

| Overlay | API 28–30, block | API 28–30, detect | API 31+ with hideOverlays | API 31+ without hideOverlays |
|---|---|---|---|---|
| Full, opaque | count unchanged, event `blocked` | count +1, event | overlay disappears, count +1 | count unchanged (OS + plugin) |
| Full, alpha 0.5 | count unchanged, event `blocked` | count +1, event | overlay disappears, count +1 | count unchanged, event `blocked` |
| Partial (top quarter) | count +1 (API 29+: event `partial`) | count +1, event | overlay disappears | count +1, event `partial`. With strict: unchanged |

Check both the expected and the opposite outcome on API 28, 30, 31, 34, 35 and 36/37 images.

## License

MIT
