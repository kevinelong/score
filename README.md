# Score — Android scoreboard

Single-screen score keeper for two teams: **HOME on the left**, **AWAY on the right**.
Two artifacts come out of the build, both SHA-256'd in `out/`. The build is byte-for-byte
reproducible (the DEX entry timestamp is pinned to the DOS epoch aapt2 uses), so - given the same
signing keys - these hashes identify the *source revision* rather than the moment the build ran, which
is what makes a published hash worth anything:

| Artifact | Size | Purpose |
| --- | --- | --- |
| `out/score.apk` | 58,684 bytes, `c2f0d259…46ca1d2` | debug-signed, for the dev loop (`sh build.sh`) |
| `out/score-release.apk` | 62,774 bytes, `4b69eba8…f0107de` | release-signed with the project's own 4096-bit key (`SIGN=release sh build.sh`) |

## Behaviour

- **HOME is white on green**, **AWAY is mustard on tomato**; each side is a coloured panel, the whole
  palette is half-saturated, and the `+` / `−` tiles are the one green and one red.
- Each side shows its name, a two-digit score (`00`), a `−` button, a `+` button and a `Reset` button.
- **Tapping the big score adds a point**, same as `+` (with the same haptic tick); the tile flashes
  while pressed.
- **Swipe up or down anywhere on a side** to move that side one point: up adds, down subtracts. One
  point per flick, and a flick that starts on a button is taken from the button, so it cannot count
  twice.
- Score range per side is `00`–`99`; `−` at zero and `+` at 99 do nothing.
- **Rename by dwell**: rest a finger on a team name for 0.9 s. The name dims while the dwell
  runs, then the rename dialog opens. Lifting the finger early cancels, so a name cannot be
  renamed by accident. Names are capped at 16 characters; a blank name reverts to `HOME` / `AWAY`.
- Scores and names survive rotation (the activity handles the configuration change in place)
  and process death (saved instance state).
- The screen stays awake while the app is open - a scoreboard that sleeps mid-match is useless.
- Zero permissions. No network access, no ads, no analytics, no third-party libraries.

## Build

No Gradle, no Android Studio, no AndroidX. The APK is assembled from the SDK tools directly,
which keeps the build deterministic and offline after the first bootstrap.

```sh
sh tools/bootstrap.sh    # one-off: portable JDK 17 + cmdline-tools + platform-34 + build-tools 34.0.0
sh build.sh              # -> out/score.apk   (re-runs in ~5 s)
```

Pipeline: `aapt2 compile` -> `aapt2 link` -> `javac` (source/target 8, bootclasspath
`android.jar`) -> `d8` -> splice `classes.dex` into the resource APK -> `zipalign` ->
`apksigner` (debug keystore at `tools/debug.keystore`, created on first build).

Install on a connected device:

```sh
toolchain/sdk/platform-tools/adb.exe install -r out/score.apk
```

Scripts must run under Git for Windows' shell (`sh build.sh`); plain `bash build.sh` on this
machine resolves to the WSL stub, which cannot see Windows paths.

## Workflow

Every change is committed and pushed, so the repository is the record of the work rather than a
snapshot taken afterwards. Secrets never enter the tree: `tools/release.keystore` and
`tools/release-credentials.txt` are git-ignored, along with the ~7 GiB toolchain, the download caches
(`dl/`) and all build output (`build/`, `out/`).

## How this was built (process and reasoning)

### 1. Start from the constraint, not the template

The usual Android answer is Android Studio or `gradle init`. Neither was available: the machine
had **no JDK, no SDK, no Gradle, no adb** and the project directory was empty. Rather than pull
in Android Studio (gigabytes, GUI, an IDE-managed toolchain), the SDK command-line tools were
installed into the project itself (`toolchain/`, `dl/`), and the build was written as a shell
pipeline over `aapt2`/`javac`/`d8`/`zipalign`/`apksigner`.

Why that is the better fit here:

| Decision | Reason |
| --- | --- |
| No Gradle / AGP | No dependency resolution, no daemon, no network at build time, no version drift. Full rebuild is ~5 s. |
| No AndroidX / no libraries at all | The app only needs platform APIs, so `d8` runs with zero library inputs and the APK stays 54 KB with no transitive surface. |
| JDK 17 (Temurin, portable zip) | `javac` is only needed to emit Java 8 bytecode against `android.jar`; a portable zip avoids an installer and registry state. |
| Tools pinned into the project | `dl/` keeps the 336 MiB of download caches so rebuilds work offline; `toolchain/` is disposable. |

### 2. Design decisions

Everything the UI does is deliberately boring and framework-native. The interesting choices:

- **Palette: everything at half saturation, with one green and one red tile.** The colours are
  computed rather than picked. The previous palette's hues were kept and HSV saturation halved
  (`#15803D` -> `#4B805F`, `#D6402A` -> `#99635B`, `#FFD24A` -> `#FFE8A4`, the window colour
  -> `#0E1013`), while the step tiles are the green and red hues at 75% saturation (`+` `#1B6B39`,
  `−` `#852E21`). Contrast was re-checked after the change, because desaturating both colours of a
  pair can quietly destroy it: white on the green panel is 4.6:1, mustard on the tomato panel is
  4.0:1, white on the two tiles is 6.5:1 and 8.7:1. One deliberate deviation: halving the tomato's
  saturation *at its original brightness* leaves mustard-on-tomato at 2.2:1, so the tomato keeps the
  halved saturation but drops in value (V 0.84 -> 0.60), which is what buys back the 4.0:1.
- **Swipes are taken, not shared.** The gesture lives in `SwipePanel`, the root `LinearLayout`
  subclass, and is handled in `onInterceptTouchEvent`: once a gesture qualifies as a vertical flick
  the parent takes it, and the framework then hands the child an `ACTION_CANCEL`. That is what stops
  a flick beginning on `+` from also registering as a press, and it cancels the rename dwell for
  free, so a finger that drifts mid-dwell cannot rename a team. The distance test is 48 dp of travel
  with vertical dominating horizontal, and the gesture yields exactly one point.
- **The score tile is the biggest target on screen, so it behaves like one.** Tapping anywhere in the
  score box adds a point (same code path as `+`, same haptic feedback), with a translucent
  press flash. It costs nothing and removes the need to aim at a small button mid-match.
- **Dwell instead of the platform long-press.** `setOnLongClickListener` would fire at the
  platform's 500 ms default, which on a scoreboard sitting on a bench is easy to trigger by
  accident. A 0.9 s dwell with a visible dim-the-name progress cue was implemented with a
  `View.OnTouchListener` + `postDelayed` + `ValueAnimator`, and the touch listener consumes the
  gesture so no framework press state competes with the cue.
- **`00` is a layout constraint, not decoration.** With a two-digit display the score is clamped
  to `0..99` rather than allowed to overflow to three digits.
- **Text sizing is computed, not hardcoded.** A fixed `sp` size either clips on a small phone or
  wastes half the screen on a tablet. The score `TextView` is monospace and resized on layout to
  `min(0.75 * height, width / 1.2)`, so `00` fills its box in both orientations without clipping;
  the guard against re-entrant layout is the "different by more than 1 px" check.
- **Plain `LinearLayout` weights** rather than `ConstraintLayout`: the screen is two equal halves
  with a vertical stack each, which is exactly what weights express, and it needs no library.
- **Rotation handled in place** (`configChanges`) with state also written to
  `onSaveInstanceState`, so nothing is lost and no flicker occurs, and process death is covered too.
- **Dark theme, keep-screen-on, haptic tick on button press** - field usability for a scoreboard
  used at arm's length. **Zero permissions** falls out of the design; `performHapticFeedback`
  needs none.
- **No persistence of names across app restarts.** Renaming is cheap and a stale name from last
  week's match is worse than a blank one. This is revisited in the deferred phase below.

### 3. Verification

Static checks come first (`apksigner verify -v`, `aapt2 dump badging`, `aapt2 dump xmltree` of the
manifest and the layout, `dexdump`), because they check the artifact that actually shipped rather
than the source. Behavioural proof came from running it: an AVD (`sh tools/make-avd.sh`) booted
headless (`sh tools/run-emulator.sh`, WHPX-accelerated, 40 s to boot), the APK installed, and
`sh tools/verify-ui.sh` driving real taps through `adb shell input` while reading state back from
`uiautomator` dumps - so every assertion is about the rendered widget tree, not about a mock.

The trace covers the happy path, both boundaries (`-` at 0, reset), the negative case for the
dwell threshold (300 ms must **not** open the dialog), the rename round trip, rotation in both
directions, and an `AndroidRuntime` log scan. Screenshots are additionally probed pixel-wise
(background, divider column, glyph bounding boxes, green/red button pixels) because a widget dump
proves values but not that anything is visible in the right place.

### 4. Problems hit, and what fixed them

| Symptom | Cause | Fix |
| --- | --- | --- |
| `bootstrap.sh: No such file or directory` although the file existed | `bash` on this machine is the WSL stub; the Windows path never resolved | run scripts with Git's `sh`, and self-locate the root from `$0` |
| `d8: Unsupported source file type` for the classes directory | build-tools 34 `d8` does not accept a directory input | jar the classes first, pass the jar |
| `adb pull /sdcard/ui.xml` failed with `/Files/Git/sdcard/ui.xml` | Git Bash's MSYS argument rewriting mangles device paths | `export MSYS_NO_PATHCONV=1` in the driving script |
| absolute paths behaved differently between `grep` and `adb` | `pwd` returns `C:\Users\...`; MSYS and native tools treat the backslash form differently | normalise every path with `cygpath -m` |
| the rename dialog would not close in the test | `AlertDialog` buttons render all-caps (`SAVE`), so a `text="Save"` matcher missed | match labels case-insensitively |
| a stale dump file hid a failure | the script's dump wrote to a path that never refreshed | `rm -f` the dump before each capture, and fail loudly after retries |
| `aapt2` refused to link after the palette swap (`resource color/reset_text not found`) | the `ResetButton` style still named a colour the new palette had deleted | grep the tree for `@color/` references after touching a palette instead of trusting the diff |
| the landscape probe called the divider 18 px off-centre | the scan ran the full row and the black status-bar stripe matched the background tolerance | measure against the panel content span, not the framebuffer midpoint, and ignore the outer quarters |
| a flick that began on bare panel did nothing (swipe over the score worked) | once no child consumes the down, `ViewGroup` stops consulting `onInterceptTouchEvent` and routes the gesture to its own `onTouchEvent` | run the same detection from both callbacks, and test a flick on bare panel, not only over a widget |

Two results worth spelling out, because they look like defects and are not:

- A pure-black 136 px strip on the left in the landscape screenshot is the emulator's
  display-cutout / status-bar area that the platform paints black for non-fullscreen apps; the
  app's own background and widgets render correctly across the rest of the surface. In portrait
  the status and navigation bars take the app's background colour (`#0A0E13`).
- After a dwell the `uiautomator` dump contains only the dialog window, so the activity's ids read
  as `<missing>` while a dialog is open. That is how Android reports windows, not a lost view.

### 5. Verification results

`sh tools/verify-ui.sh` against the installed build on the Android 14 AVD - all rows as expected:

```
space check: uiautomator root [0,0][1080,2340] vs screencap 1080x2340
initial                          home=00 away=00 | names=HOME/AWAY
tap home score (increments)      home=01 away=00
tap home score again             home=02 away=00
home_plus                        home=03 away=00
home_minus                       home=02 away=00
tap away score (increments)      home=02 away=01
away_plus                        home=02 away=02
away_reset                       home=02 away=00
swipe up on home score (+1)      home=03 away=00
swipe down on home score (-1)    home=02 away=00
swipe up on away score (+1)      home=02 away=01
swipe down on away score (-1)    home=02 away=00
swipe up starting on the + tile (must be +1, no press) home=03 away=00
swipe up on bare panel (+1)      home=04 away=00
swipe down on bare panel (-1)    home=03 away=00
home_minus x4 (floor at 0)       home=00 away=00
home_plus x3                     home=03 away=00
hold 300ms (below dwell)         home=03 away=00 | names=HOME/AWAY   <- no dialog
hold 1500ms (dwell reached)      dialog editbox=HOME
typed HAWKS                      dialog editbox=HAWKS
saved rename                     home=03 away=00 | names=HAWKS/AWAY
rotated to landscape             home=03 away=00 | names=HAWKS/AWAY
rotated back to portrait         home=03 away=00 | names=HAWKS/AWAY
crash log                        empty
```

Three of those rows exist because of bugs they caught, not because they were obvious:

- *swipe starting on the + tile* - fails if the flick is not taken from the button, by counting twice.
- *swipe on bare panel* - failed on the first build of this feature (see the table above) because no
  child consumed the down, so `ViewGroup` never asked `onInterceptTouchEvent` again.
- *hold 300 ms* - the negative case for the rename dwell, which must not open a dialog.

Pixel probe of the captures (`python tools/probe_screens.py verify/emu-*.png`). This is what proves
the palette actually renders, since a widget dump only carries text and geometry:

```
verify/emu-02-portrait.png  1080x2340  (portrait)
  app content columns 0..1079 (midpoint 539); divider x 537..542 -> off by -2 px
  HOME: panel px 101162  glyphs x[74..468] y[199..1167]
  AWAY: panel px 101177  glyphs x[617..1017] y[201..1169]
  step tiles: green  20290 px   red  20617 px
verify/emu-06-landscape.png  2340x1080  (landscape)
  app content columns 136..2207 (midpoint 1171); divider x 1169..1174 -> off by -2 px
  HOME: panel px  76634  glyphs x[468..842] y[130..560]
  AWAY: panel px  76672  glyphs x[1507..1885] y[130..560]
  step tiles: green  45586 px   red  45913 px
```

Both halves paint their own panel colour (101,162 vs 101,177 px - they are the same size), the digits
sit centred inside them, the two step tiles really are the green and the red they claim to be
(20,290 vs 20,617 px), and the divider is on the content midpoint in both orientations (the -2 px is
the 6 px seam straddling the centre line). The landscape figure shows why the probe measures against
the *content* span rather than the framebuffer: the window is inset by a 136 px status bar on the
left and a navigation bar on the right.

Extra checks: `apksigner verify -v` -> v1, v2 and v3 all verify (so it installs on API 21 and up);
`aapt2 dump badging` -> `com.kevin.score`, v1.0, minSdk 21, targetSdk 34, launchable activity, **0
permissions**; `aapt2 dump xmltree` of the layout -> HOME half before AWAY half, both scores `00`,
monospace, `-`/`+` on equal weights with `Reset` under each; `dexdump` -> valid dex containing
`com.kevin.score.MainActivity`.

### 6. Real device (S23 Ultra, Android 16)

The build at the time of the swipe/palette work was installed on the phone over wireless ADB and
driven with the same harness:

```sh
sh tools/verify-ui.sh <phone-ip>:<connect-port> phone-
```

That run produced the same behavioural rows as the AVD, and two device-specific facts, both of which
changed the harness:

- **A rotated display breaks naive tap coordinates.** On the phone in landscape, `uiautomator`
  reported bounds in the display's *natural* orientation while `adb shell input` interpreted
  coordinates in the *current* one, so taps derived from a dump landed on other widgets - the first
  phone run produced nonsense like `78 -> 99 -> 0` where `03` was expected. The harness now pins the
  display to portrait for the tapping phase, asserts that the dump root matches the screencap size
  before tapping, and restores the user's original rotation settings at the end. The emulator never
  showed this because its panel follows the rotation.
- The phone is set to Samsung's **HD+ screen resolution**: `wm size` reports `Physical size:
  720x1544`, density override 320. So the app has to lay out correctly at 720x1544/320 dpi as well,
  and it does - verified by pixel probe (both panels painted, divider on the content midpoint, digits
  centred).

Status of the swipe/palette build: it is **installed on the phone and verified byte-identical** (the
installed `base.apk` md5 `27befa34e0fd232b4a638c8842c35c24` matches `out/score.apk` exactly), but the
on-device behavioural trace has not run yet. The first attempt was contaminated and then blocked:
the phone locked mid-run (`mWakefulness=Dozing`, `isKeyguardShowing=true`, focus on NotificationShade)
so every capture was a black 7,638-byte frame and every widget id read `<missing>`, and it stayed
locked through a nine-minute watch. The behavioural evidence for this build is therefore the AVD run
above, not a device run.

Reconnecting needs no pairing code - the pairing persists in `~/.android/adbkey` - but wireless
debugging switches itself off on reboot, so after a restart it is `adb connect <ip>:<port>` with the
address taken from `adb mdns services`. Note that the trace force-stops and foregrounds the app,
taps, flicks and rotates the display, so it needs the phone unlocked and undisturbed for ~2 minutes.

## Layout

```
+=====================+=====================+
|        HOME         |        AWAY         |   name (dwell 0.9 s to rename)
|  (white on green)   | (mustard on tomato) |
|         12          |         07          |   two-digit score - tap it to add a point
|     [-]      [+]    |     [-]      [+]    |   red - / green +, 76 dp tiles
|        Reset        |        Reset        |   per-side reset
+=====================+=====================+
   swipe up/down here    swipe up/down here    up = +1, down = -1, one point per flick
```

## Files

```
app/AndroidManifest.xml                      package com.kevin.score, minSdk 21, targetSdk 34
app/java/com/kevin/score/MainActivity.java   all behaviour (single activity, ~300 lines)
app/java/com/kevin/score/SwipePanel.java     root layout that owns the swipe gesture
app/res/layout/activity_main.xml             the single screen
app/res/values/{strings,colors,styles}.xml
app/res/drawable/btn_plus.xml                green + tile
app/res/drawable/btn_minus.xml               red - tile
app/res/drawable/btn_reset.xml               reset button background selector
app/res/drawable/score_press.xml             score tile press flash
app/res/drawable/ic_launcher_foreground.xml  adaptive icon foreground ("00")
app/res/mipmap-*/ic_launcher.png             legacy icons (regenerate: python tools/make_icons.py)
build.sh                                     build + sign + verify
tools/bootstrap.sh                           one-off toolchain install
tools/make_icons.py                          launcher icon renderer (Pillow)
tools/add_dex.py                             splices classes.dex into the aapt2 APK
tools/sdkmanager.sh                          sdkmanager wrapper with the right JDK/SDK root
tools/make-avd.sh                            create the throwaway verification AVD
tools/run-emulator.sh                        boot it headless (WHPX)
tools/verify-ui.sh                           drive the app and print the behavioural trace
tools/probe_screens.py                       pixel-probe screenshots (panel colours, divider, glyphs)
tools/make-release-key.sh                    one-off: the 4096-bit release signing key
tools/bootstrap-go.sh                        portable Go, for the reference tournament server
verify/                                      phone/AVD screenshots from the current build (probe inputs)
LICENSE                                      FSL-1.1-MIT (source-available; MIT after two years)
docs/LINKED-MATCH-DESIGN.md                  linked-match mode: server protocol + design
dl/, toolchain/, build/, out/                generated (see .gitignore)
tools/release.keystore, tools/release-credentials.txt   secrets - git-ignored, back up off-machine
```

## Getting the APK onto a Samsung Galaxy phone

Install `out/score-release.apk` (the publicly shareable, release-signed build; `out/score.apk` is the
debug-signed one used for development). Two routes get it onto the phone; both end in the same install
prompts.

### Phone Link (no cable)

1. Phone: **Settings -> Advanced features -> Link to Windows** must show this PC as connected, on
   the same Microsoft account. File sharing needs Link to Windows **1.24032.518.0** or later; the
   pre-installed build is often behind, so update it from the **Galaxy Store**.
2. PC: right-click the APK -> **Share** -> **Phone Link** -> your Galaxy. (Samsung-only
   alternative: Phone Link -> **Apps -> Open phone screen**, then drag the file onto the mirrored
   screen - Samsung is in the supported drag-and-drop set, and the file lands in `Downloads`.)
3. Phone: the file arrives in **My Files -> Downloads -> Sent from your PC**.

### USB cable

1. Connect and unlock; in the USB notification choose **Transferring files / MTP**.
2. Drag the APK into the phone's `Download` folder (Samsung's MTP limit: 512 MB per file, folders
   not supported).

### Then, on the phone, for either route

1. **Settings -> Security and privacy -> Auto Blocker -> Off.** Auto Blocker is on by default on
   devices that shipped with One UI 6.1.1 or later and always refuses installs from outside the
   Galaxy Store / Play Store; it also blocks commands over USB, so it blocks `adb install` too.
   Samsung's own FAQ: "temporarily disable this feature and try again". It can be turned back on
   afterwards - the installed app keeps working, only reinstalls and updates need it off.
2. Tap the APK in **My Files**. The first tap offers a shortcut into the right permission - tap
   **Settings** in that prompt and allow **Install unknown apps** for My Files. Menu path on
   current One UI (verified against One UI 8 / Android 16, the build on the S23 Ultra):
   **Settings -> Security and privacy -> More security settings -> Install unknown apps**; older
   builds hid the same toggle under Settings -> Apps -> Special access.
3. Play Protect will flag it ("Unsafe app blocked" / unknown developer) because it is debug-signed;
   **Install anyway** is expected.
4. Open **Score**.

With USB debugging enabled and Auto Blocker off, the no-prompt route is
`toolchain/sdk/platform-tools/adb.exe install -r out/score.apk` - it also updates in place, which is
the fastest loop while iterating. `tools/verify-ui.sh` takes an optional adb serial and screenshot
prefix, so the same behavioural trace runs against the emulator or the phone:
`sh tools/verify-ui.sh <phone-ip>:<connect-port> phone-`.

### Wireless ADB (how the S23 Ultra was actually installed, no cable)

1. Phone: **Developer options -> Wireless debugging -> on**, and accept *"Always allow on this
   network"*.
2. Phone: tap **Pair device with pairing code**. That dialog is what publishes
   `_adb-tls-pairing._tcp` on the LAN, and it shows the 6-digit code; both die when the dialog
   closes.
3. PC: `adb mdns services` then lists both endpoints, e.g.
   `adb-XXXXXXXX-XXXXXX  _adb-tls-pairing._tcp  <phone-ip>:<pairing-port>` (only while the dialog is
   open) and `... _adb-tls-connect._tcp  <phone-ip>:<connect-port>`. Both ports change whenever wireless
   debugging is toggled.
4. `adb pair <ip>:<pairing-port> <code>` then `adb connect <ip>:<connect-port>`.
5. The device then appears **twice** - the `ip:port` transport plus its mDNS alias - and every
   command fails with *"more than one device/emulator"*. Target the transport explicitly:
   `adb -s <phone-ip>:<connect-port> install -r out/score.apk`.

Pairing is per-PC and persists in `~/.android/adbkey`, so later sessions only need `adb connect`; a
phone reboot or a wireless-debugging toggle invalidates the port, not the pairing.

> Note (as of October 2026): Google's **Android developer verification** began enforcing on
> 30 September 2026 - on *certified* devices in **Brazil, Indonesia, Singapore and Thailand**,
> installing an app whose developer is not registered with Google is blocked, even outside the Play
> Store, with global expansion planned for 2027. This debug-signed build is not registered, so in
> those regions the install needs either a registered signing key or the power-user advanced flow;
> elsewhere it installs normally today. A **limited distribution account** (hobbyist / student /
> teacher, up to 20 devices, no government ID or fee) covers it if that ever bites.

## Signing

Development builds use a throwaway debug key. Public builds use a key that is generated once and then
guarded:

```sh
sh tools/make-release-key.sh     # once: tools/release.keystore + tools/release-credentials.txt
SIGN=release sh build.sh         # -> out/score-release.apk
```

The key is RSA 4096 / SHA256withRSA in PKCS12, valid 30 years (to 2056), with one random password for
both store and key. Both files are git-ignored. The APK is signed with **v1 + v2 + v3**: v1 is what
makes it installable on API 21-23, v2/v3 cover everything newer. `build.sh` runs `apksigner verify
--print-certs`, checks that neither `debuggable` nor `testOnly` is in the shipped manifest, and prints
the badging - so a release cannot be produced without those checks passing. Rebuilds are byte-identical
given the same signing keys (see the artifact table above): the payload is reproducible from source by
anyone, and the published release APK only by whoever holds this keystore - which is the point of
guarding it.

Three things worth internalising:

- **The signing key is the app's identity.** Installing a differently-signed build over an existing
  one is refused outright; the first attempt after adding release signing produced exactly that:
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.kevin.score signatures do not match newer
  version`. So losing this keystore means never shipping an update to the same package.
- **Back up `tools/release.keystore` and `tools/release-credentials.txt` off this machine** and put
  the password in a password manager. If you later publish on Play with Play App Signing, Google holds
  the app signing key and the local one becomes an upload key that support can reset - do not rely on
  that for direct distribution.
- The SDK's `.bat` tools need `JAVA_HOME`; `build.sh` sets it, one-off `apksigner`/`keytool` calls
  from a bare shell do not.

## Making it public

There is no single "publish" button; the routes differ a lot in cost. Requirements are as of October
2026.

| Route | What it needs | Fit |
| --- | --- | --- |
| **Direct APK** (GitHub Releases, a web page) | Nothing beyond the signed APK. Users must allow sideloading (see the Samsung section). Since 30 Sep 2026 in BR/ID/SG/TH - globally from 2027 - certified devices want the developer registered; the free **limited distribution account** authorises **20 devices per package name**, so broad public reach implies full verification | Fastest to public, no review, no fee - but no discovery and a sideloading hurdle per user |
| **Google Play** | $25 once; an **AAB**, which needs Gradle/AGP rather than this hand-rolled pipeline; **target API 36** for new apps and updates since 31 Aug 2026 (this build targets 34); for new *personal* accounts, a **closed test with 12 opted-in testers for 14 continuous days** before production; store listing (512x512 icon, 1024x500 feature graphic), data-safety form, content rating; Play App Signing | Real discovery, by far the most work |
| **Samsung Galaxy Store** | Samsung seller account, uploads an APK, own review | Sensible given the target hardware |
| **F-Droid** | A public source repo and a **Gradle** build it can reproduce; a **FLOSS** licence | Ruled out by `FSL-1.1-MIT`: F-Droid ships only free licences, and Fair Source is deliberately not one. It also wants a Gradle build, which this pipeline is not |

Decide these **before** the first public release, because they are effectively permanent:

- **Package name** - `com.kevin.score` today. Publishing fixes it; changing it later is a different
  app with no upgrade path, so choose the final name now (a domain you control, or the common
  `io.github.<you>.<app>` convention).
- **versionCode / versionName** - every upload needs a higher versionCode (now `1` / `1.0`).
- **Licence** - decided: **`FSL-1.1-MIT`**, described below and applied to both repositories. Still
  open: a one-paragraph privacy policy. This app collects nothing, has no network access and no
  third-party SDKs, so the policy is trivial - but reviews ask for it and the data-safety declaration
  is easier once it is written down.

For this app the honest recommendation is **direct APK + the Samsung Galaxy Store**: with no server,
no accounts and no data, the expensive parts of Play (AAB, targetSdk 36, the 12-tester gate) buy
discoverability, not capability. Play becomes the right answer when you want strangers to find it.

The direct-APK route is live: **https://github.com/kevinelong/score-android** is the public
distribution repository (`kevinelong/score-android`, a separate repo from this source one) holding the
release-signed APK, a `v1.0` GitHub Release, screenshots, and the install/use/privacy text a stranger
needs. Publishing a new version means: bump the version, rebuild, copy the artifact over, commit there
and cut a new release.

## Linked match mode (design)

The app is being extended to open a tournament **scoring link** — the same link the tournament
director already shares as a QR code — load that match from the live server, score it and submit the
result. If the app is not installed, the same link opens the tournament's web console in the browser.
The server is `kevinelong/15ball-scoresheet` (Go + SQLite) and needs no change for this.

The protocol, the exact call sequence, the score-payload convention, the App Links / browser-fallback
mechanics, and the consequences for this app's permissions and privacy claims are written up in
**[docs/LINKED-MATCH-DESIGN.md](docs/LINKED-MATCH-DESIGN.md)** — with every fact verified against a
running server rather than read from their documentation.

## Licence

**`FSL-1.1-MIT`** — the [Functional Source License](https://fsl.software/) 1.1, verbatim in
[`LICENSE`](LICENSE) with the copyright line filled in, and applied to both this source repository and
the public distribution repository so the app binary carries the same terms as the code.

In plain words, what you may and may not do:

- **You may** read, use, copy, modify and redistribute this software — including for commercial
  purposes — for any purpose **except** a competing one. Internal use at a for-profit company is fine;
  so are non-commercial education and research.
- **You may not** make it available to others in a commercial product or service that substitutes for
  this app or offers the same or substantially similar functionality. Republishing a rebranded build
  as your own scoreboard app is the thing this forbids.
- **You must** pass these terms along with any copies, modifications or derivatives, and keep the
  copyright notice.
- **On the second anniversary of each release, that release becomes plain MIT.** No permission needed,
  no strings attached — that is the "delayed open source publication" half of the licence, and it is
  irreversible.

Two consequences worth stating plainly: this is **source-available, not OSI open source** (the
non-compete clause is a field-of-use restriction, which is exactly why it cannot be open source), which
means F-Droid and Linux-distro packaging are off the table. And the licence binds people who accept
it — GitHub's own terms allow anyone to fork a public repository, so the protection here is a clear
legal position against a *competing use*, not a technical block.

## Footprint

| Path | Size | Notes |
| --- | --- | --- |
| `app/` + `out/` | ~1 MiB | source and the signed APK |
| `dl/` | 328 MiB | toolchain download caches; delete to force a re-download |
| `toolchain/` | 7.1 GiB | JDK 303 MiB, platform 95 MiB, build-tools 137 MiB, emulator 1.1 GiB, system image 1.7 GiB, verification AVD 3.6 GiB |
| `toolchain/avd` | 3.6 GiB | `rm -rf toolchain/avd` reclaims it; `sh tools/make-avd.sh` recreates it in seconds |

The emulator holds roughly 3 GB of RAM while it is up; it was shut down after the verification run,
and WSL was shut down too (`wsl --shutdown`), so the machine's memory state is as it was found.

## Not implemented yet (next phase)

Kevin's stated follow-up: open local URLs that carry a return URL, and add a `Submit match` button
that POSTs the result to a receiver server URL. Nothing in the current code stubs this. The change
will need an `INTERNET` permission and a `VIEW` intent filter (plus `onNewIntent`/`singleTop`,
already set in the manifest) in `MainActivity`, a return-URL round trip, and then the submit path
over the existing `score`/`name` state; team names are view state today, so that phase also has to
decide whether they should persist in `SharedPreferences`.
