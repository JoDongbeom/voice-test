# Android Reading App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wrap the existing `index.html` in an Android app whose native speech service keeps reading exam answers with the screen off, controllable from the lock screen and earphone buttons.

**Architecture:** A WebView shows the unchanged-in-spirit `index.html` (served via `WebViewAssetLoader` so it has an https origin). When `window.AndroidSpeech` exists, the page hands its line list to a foreground `SpeechService` that owns reading position (`PlayerState`), Android `TextToSpeech`, a `MediaSession`, a media notification, and a silent `AudioTrack` so media buttons route to the app. GitHub Actions builds, tests, signs, and publishes the APK to Releases.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Gradle 8.11.1, compileSdk/targetSdk 35, minSdk 26, JDK 17, androidx (core-ktx 1.13.1, activity-ktx 1.9.3, webkit 1.12.1, media 1.7.0), JUnit 4.13.2, Node 26 `node:test` for the web page, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-29-android-app-design.md`

## Global Constraints

- Repository: `github.com/JoDongbeom/voice-test`, branch `main`. Work directory: `C:\Users\User\Desktop\음성 자동화`.
- `index.html` at repo root stays the single source for the UI; it must keep working in a normal browser (speechSynthesis path) exactly as today.
- Android project lives in `android/`; the build copies `../index.html` into generated assets. Never commit a copy of `index.html` under `android/`.
- applicationId / namespace: `com.jodongbeom.voicetest`. App label: `답안 듣기`.
- minSdk 26, compileSdk 35, targetSdk 35. JDK 17.
- Install link must stay: `https://github.com/JoDongbeom/voice-test/releases/latest/download/voice-test.apk`
- Signing: PKCS12 keystore, alias `voice`, stored only as GitHub Secrets `KEYSTORE_B64` and `KEYSTORE_PASSWORD`. Never commit keystore or password; delete local temp copies after upload.
- No API key anywhere in code, logs, or secrets. The key is typed by the user into the app and lives in WebView localStorage.
- Speech rate range 0.1–1.5.
- No Java/Android SDK on the work PC: Kotlin compiles and unit tests run only in GitHub Actions. JS tests run locally with `node --test`.
- Windows PowerShell 5.1 is the shell; `gh` is at `C:\Program Files\GitHub CLI\gh.exe` (not on PATH in older shells).
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Camera cancelled or no camera app** — tapping 📷 again must still open the camera (the WebView file-chooser callback must always receive a value, `null` on cancel). Owner: Task 5, phone checklist item.
2. **Pressing ▶ after the answer finished** — must start again from line 1, not do nothing. Owner: Task 3 `play after end restarts from zero` test.
3. **New photo answer arrives while the old one is reading** — stale TTS callbacks must not advance the new list, and the new list must be sent to the service. Owner: Task 3 `playFrom bumps generation` test, Task 6 `new answer re-sends load` test.
4. **Earphones disconnect mid-reading** — reading pauses instead of blasting from the phone speaker. Owner: Task 4 (noisy receiver), phone checklist item.
5. **Phone call or another app starts audio** — reading pauses and does not auto-resume. Owner: Task 4 (audio focus), phone checklist item.

---

## File Structure

| Path | Responsibility |
|---|---|
| `index.html` (modify) | Detect `window.AndroidSpeech`; route `playFrom`/`pausePlayer`/rate to native; receive `onNativeSpeech`/`onNativeNotice` |
| `tests/web/harness.mjs` (create) | Loads `index.html`'s script into a Node `vm` with a fake DOM and optional fake bridge |
| `tests/web/native-bridge.test.mjs` (create) | Tests for the page's app mode |
| `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties` (create) | Gradle project setup |
| `android/app/build.gradle.kts` (create) | App module, signing from env, asset copy task |
| `android/app/src/main/AndroidManifest.xml` (create) | Permissions, activity, service, FileProvider, TTS query |
| `android/app/src/main/res/...` (create) | Icon, colors, FileProvider paths |
| `android/app/src/main/java/com/jodongbeom/voicetest/PlayerState.kt` (create) | Pure reading-position logic (unit tested) |
| `android/app/src/main/java/com/jodongbeom/voicetest/SpeechService.kt` (create) | Foreground service: TTS, MediaSession, notification, focus, silent track |
| `android/app/src/main/java/com/jodongbeom/voicetest/SpeechBridge.kt` (create) | `@JavascriptInterface` → service intents |
| `android/app/src/main/java/com/jodongbeom/voicetest/MainActivity.kt` (create) | WebView host, camera, permissions, notices, state relay |
| `android/app/src/test/java/com/jodongbeom/voicetest/PlayerStateTest.kt` (create) | JUnit tests for PlayerState |
| `android/.gitignore` (create) | Ignore build outputs |
| `.github/workflows/android.yml` (create) | Test, build, sign, release |
| `README.md` (modify) | App install/update guide and phone test checklist |

---

### Task 1: Signing key and GitHub secrets

**Files:** none in the repo (secrets only).

**Interfaces:**
- Produces: GitHub Secrets `KEYSTORE_B64` (base64 of PKCS12 file) and `KEYSTORE_PASSWORD`; key alias `voice`.

- [ ] **Step 1: Generate the keystore in the scratchpad and upload secrets**

Run in PowerShell (the scratchpad path is session-specific; use the current one):

```powershell
$gh = "C:\Program Files\GitHub CLI\gh.exe"
$openssl = "C:\Program Files\Git\usr\bin\openssl.exe"  # Git for Windows 에 포함
$dir = Join-Path $env:TEMP ("vt-key-" + [guid]::NewGuid())
New-Item -ItemType Directory $dir | Out-Null
$pw = & $openssl rand -hex 24
& $openssl req -x509 -newkey rsa:2048 -nodes -keyout "$dir\key.pem" -out "$dir\cert.pem" -days 10000 -subj "/CN=JoDongbeom voice-test" 2>$null
& $openssl pkcs12 -export -inkey "$dir\key.pem" -in "$dir\cert.pem" -name voice -out "$dir\release.p12" -passout "pass:$pw"
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$dir\release.p12"))
# --body 사용: PowerShell 파이프는 끝에 줄바꿈을 붙여 비밀번호가 달라짐
& $gh secret set KEYSTORE_B64 --repo JoDongbeom/voice-test --body $b64
& $gh secret set KEYSTORE_PASSWORD --repo JoDongbeom/voice-test --body $pw
Remove-Item -Recurse -Force $dir
```

- [ ] **Step 2: Verify**

Run: `& "C:\Program Files\GitHub CLI\gh.exe" secret list --repo JoDongbeom/voice-test`
Expected: two rows, `KEYSTORE_B64` and `KEYSTORE_PASSWORD`. Confirm `$dir` no longer exists.

No commit (nothing in the repo changed).

---

### Task 2: Android project skeleton, CI build, and first release

Deliverable: an installable signed APK in Releases that opens `index.html` in a WebView (speech still browser-based / non-functional in WebView; that is fine at this stage).

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/.gitignore`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/colors.xml`, `android/app/src/main/res/drawable/ic_launcher_foreground.xml`, `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `android/app/src/main/res/xml/file_paths.xml`
- Create: `android/app/src/main/java/com/jodongbeom/voicetest/MainActivity.kt` (minimal; Task 5 replaces it)
- Create: `.github/workflows/android.yml`

**Interfaces:**
- Consumes: secrets from Task 1.
- Produces: Gradle tasks `testReleaseUnitTest`, `assembleRelease`; asset `index.html` available at `https://appassets.androidplatform.net/assets/index.html`; Releases asset `voice-test.apk`. The manifest in this task has no `<service>` entry; Task 4 adds `SpeechService` and its permissions.

- [ ] **Step 1: Gradle files**

`android/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "voice-test"
include(":app")
```

`android/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
```

`android/gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`android/.gitignore`:
```
.gradle/
build/
local.properties
*.p12
*.jks
```

`android/app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jodongbeom.voicetest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jodongbeom.voicetest"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.$versionCode"
    }

    // 서명 열쇠는 GitHub Actions 에서만 환경변수로 들어옴 (저장소에는 없음)
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "voice"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webassets"))
}

// 저장소 맨 위 index.html 을 앱에 넣음 (화면 파일은 하나만 관리)
val copyWebAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    into(layout.buildDirectory.dir("generated/webassets"))
}
tasks.named("preBuild") { dependsOn(copyWebAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Resources**

`android/app/src/main/res/values/colors.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="icon_bg">#2563EB</color>
</resources>
```

`android/app/src/main/res/drawable/ic_launcher_foreground.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="M38,46h10l12,-10v36l-12,-10h-10z"/>
    <path android:fillColor="#00000000" android:strokeColor="#FFFFFF" android:strokeWidth="5"
        android:strokeLineCap="round" android:pathData="M66,44a12,12 0 0,1 0,20"/>
</vector>
```

`android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_bg"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
```

`android/app/src/main/res/xml/file_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="photos" path="photos/"/>
</paths>
```

- [ ] **Step 3: Manifest (no service yet)**

`android/app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET"/>

    <queries>
        <intent><action android:name="android.intent.action.TTS_SERVICE"/></intent>
        <intent><action android:name="android.media.action.IMAGE_CAPTURE"/></intent>
    </queries>

    <application
        android:label="답안 듣기"
        android:icon="@mipmap/ic_launcher"
        android:theme="@android:style/Theme.Material.Light.NoActionBar"
        android:allowBackup="false">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths"/>
        </provider>
    </application>
</manifest>
```

- [ ] **Step 4: Minimal MainActivity**

`android/app/src/main/java/com/jodongbeom/voicetest/MainActivity.kt`:
```kotlin
package com.jodongbeom.voicetest

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        setContentView(web)
        web.loadUrl(PAGE_URL)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val PAGE_URL = "https://appassets.androidplatform.net/assets/index.html"
    }
}
```

- [ ] **Step 5: Workflow**

`.github/workflows/android.yml`:
```yaml
name: Android APK

on:
  push:
    branches: [main]
    paths:
      - "index.html"
      - "android/**"
      - ".github/workflows/android.yml"
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.11.1"

      - name: Decode keystore
        run: echo "${{ secrets.KEYSTORE_B64 }}" | base64 -d > "$RUNNER_TEMP/release.p12"

      - name: Test and build
        working-directory: android
        env:
          KEYSTORE_FILE: ${{ runner.temp }}/release.p12
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
        run: gradle --no-daemon testReleaseUnitTest assembleRelease

      - name: Publish release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          cp android/app/build/outputs/apk/release/app-release.apk voice-test.apk
          gh release create "v${{ github.run_number }}" voice-test.apk \
            --title "앱 v${{ github.run_number }}" \
            --notes "자동 빌드 ${GITHUB_SHA::7}" \
            --latest
```

- [ ] **Step 6: Commit, push, watch the run**

```powershell
Set-Location "C:\Users\User\Desktop\음성 자동화"
git add android .github
git commit -m "Add Android app skeleton and APK build workflow`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
& "C:\Program Files\GitHub CLI\gh.exe" run watch --exit-status (& "C:\Program Files\GitHub CLI\gh.exe" run list --workflow android.yml --limit 1 --json databaseId --jq ".[0].databaseId")
```
Expected: run succeeds. If it fails, read `gh run view <id> --log-failed`, fix, push again.

- [ ] **Step 7: Verify the release asset**

Run: `& "C:\Program Files\GitHub CLI\gh.exe" release view --repo JoDongbeom/voice-test --json tagName,assets --jq ".tagName + ' ' + (.assets[].name)"`
Expected: `v<N> voice-test.apk`.
Also `Invoke-WebRequest -Method Head https://github.com/JoDongbeom/voice-test/releases/latest/download/voice-test.apk` returns 200 after redirects.

---

### Task 3: PlayerState (reading position logic, TDD)

**Files:**
- Create: `android/app/src/test/java/com/jodongbeom/voicetest/PlayerStateTest.kt`
- Create: `android/app/src/main/java/com/jodongbeom/voicetest/PlayerState.kt`

**Interfaces:**
- Produces:
  - `data class Line(val chunks: List<String>, val section: Boolean)`
  - `class PlayerState` with read-only `lines: List<Line>`, `pos: Int`, `chunk: Int`, `playing: Boolean`, `generation: Int`
  - `fun load(newLines: List<Line>)` — drops lines with no chunks, pos=0, chunk=0, playing=false, generation++
  - `fun playFrom(index: Int): Boolean` — false if no lines; else clamp, chunk=0, playing=true, generation++
  - `fun play(): Boolean` = `playFrom(pos)`
  - `fun pause()` — playing=false, generation++
  - `fun prevLine(): Boolean` = `playFrom(pos - 1)`
  - `fun nextLine(): Boolean` — false (no change) at last line, else `playFrom(pos + 1)`
  - `fun currentText(): String?` — null unless playing
  - `fun advance(): Boolean` — next chunk/line; at the very end: playing=false, pos=0, chunk=0, returns false

- [ ] **Step 1: Write the failing tests**

`android/app/src/test/java/com/jodongbeom/voicetest/PlayerStateTest.kt`:
```kotlin
package com.jodongbeom.voicetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateTest {
    private fun sample() = PlayerState().apply {
        load(
            listOf(
                Line(listOf("로마자 일, 소재"), true),
                Line(listOf("점, 첫 문장.", "둘째 문장"), false),
                Line(emptyList(), false),
                Line(listOf("로마자 이, 결론"), true),
            )
        )
    }

    @Test fun `load drops empty lines and starts paused at zero`() {
        val s = sample()
        assertEquals(3, s.lines.size)
        assertEquals(0, s.pos)
        assertFalse(s.playing)
        assertNull(s.currentText())
    }

    @Test fun `empty list cannot play`() {
        val s = PlayerState()
        s.load(emptyList())
        assertFalse(s.playFrom(0))
        assertFalse(s.playing)
        assertNull(s.currentText())
    }

    @Test fun `playFrom clamps index`() {
        val s = sample()
        assertTrue(s.playFrom(99))
        assertEquals(2, s.pos)
        assertTrue(s.playFrom(-5))
        assertEquals(0, s.pos)
        assertEquals("로마자 일, 소재", s.currentText())
    }

    @Test fun `advance walks chunks then lines then ends at zero`() {
        val s = sample()
        s.playFrom(1)
        assertEquals("점, 첫 문장.", s.currentText())
        assertTrue(s.advance())
        assertEquals("둘째 문장", s.currentText())
        assertTrue(s.advance())
        assertEquals(2, s.pos)
        assertEquals("로마자 이, 결론", s.currentText())
        assertFalse(s.advance())
        assertFalse(s.playing)
        assertEquals(0, s.pos)
    }

    @Test fun `play after end restarts from zero`() {
        val s = sample()
        s.playFrom(2)
        s.advance()
        assertTrue(s.play())
        assertEquals(0, s.pos)
        assertEquals("로마자 일, 소재", s.currentText())
    }

    @Test fun `pause keeps position and silences`() {
        val s = sample()
        s.playFrom(1)
        s.advance()
        s.pause()
        assertFalse(s.playing)
        assertEquals(1, s.pos)
        assertNull(s.currentText())
        assertTrue(s.play())
        assertEquals("점, 첫 문장.", s.currentText()) // 줄 처음부터 다시
    }

    @Test fun `nextLine at last line does nothing`() {
        val s = sample()
        s.playFrom(2)
        val gen = s.generation
        assertFalse(s.nextLine())
        assertTrue(s.playing)
        assertEquals(2, s.pos)
        assertEquals(gen, s.generation)
    }

    @Test fun `prevLine at first line replays first line`() {
        val s = sample()
        s.playFrom(0)
        assertTrue(s.prevLine())
        assertEquals(0, s.pos)
    }

    @Test fun `playFrom bumps generation so stale callbacks can be ignored`() {
        val s = sample()
        s.playFrom(0)
        val old = s.generation
        s.playFrom(1)
        assertNotEquals(old, s.generation)
        val beforeLoad = s.generation
        s.load(listOf(Line(listOf("새 답안"), true)))
        assertNotEquals(beforeLoad, s.generation)
        assertFalse(s.playing)
    }
}
```

- [ ] **Step 2: Push and see it fail in CI**

```powershell
Set-Location "C:\Users\User\Desktop\음성 자동화"
git add android/app/src/test
git commit -m "Add PlayerState tests`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```
Watch the run (as in Task 2 Step 6). Expected: FAIL at `compileReleaseUnitTestKotlin` with `Unresolved reference 'PlayerState'`.

- [ ] **Step 3: Implement**

`android/app/src/main/java/com/jodongbeom/voicetest/PlayerState.kt`:
```kotlin
package com.jodongbeom.voicetest

/** 화면의 한 줄. chunks = 끊어 읽을 조각, section = Ⅰ. Ⅱ. 로 시작하는 목차 줄 */
data class Line(val chunks: List<String>, val section: Boolean)

/**
 * 읽는 위치만 관리 (안드로이드 코드 없음 → JVM 단위 테스트 가능).
 * generation 은 재생 명령마다 바뀌어서, 이전 명령의 TTS 완료 알림을 무시하는 데 쓴다.
 */
class PlayerState {
    var lines: List<Line> = emptyList(); private set
    var pos = 0; private set
    var chunk = 0; private set
    var playing = false; private set
    var generation = 0; private set

    fun load(newLines: List<Line>) {
        lines = newLines.filter { it.chunks.isNotEmpty() }
        pos = 0
        chunk = 0
        playing = false
        generation++
    }

    fun playFrom(index: Int): Boolean {
        if (lines.isEmpty()) return false
        pos = index.coerceIn(0, lines.size - 1)
        chunk = 0
        playing = true
        generation++
        return true
    }

    fun play(): Boolean = playFrom(pos)

    fun pause() {
        playing = false
        generation++
    }

    fun prevLine(): Boolean = playFrom(pos - 1)

    fun nextLine(): Boolean {
        if (pos + 1 >= lines.size) return false
        return playFrom(pos + 1)
    }

    fun currentText(): String? = if (playing && lines.isNotEmpty()) lines[pos].chunks[chunk] else null

    /** 지금 조각을 다 읽었을 때 호출. 더 읽을 게 있으면 true, 전체가 끝나면 false (다음 재생은 처음부터) */
    fun advance(): Boolean {
        if (!playing) return false
        chunk++
        if (chunk < lines[pos].chunks.size) return true
        if (pos + 1 < lines.size) {
            pos++
            chunk = 0
            return true
        }
        playing = false
        pos = 0
        chunk = 0
        return false
    }
}
```

- [ ] **Step 4: Push and see it pass**

```powershell
git add android/app/src/main/java/com/jodongbeom/voicetest/PlayerState.kt
git commit -m "Add PlayerState reading-position logic`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```
Watch the run. Expected: success; log shows `PlayerStateTest` with 9 tests passed (`gh run view <id> --log | Select-String PlayerStateTest`, or download the `build/reports/tests` if needed).

---

### Task 4: SpeechService (TTS, media session, notification, focus, silent track)

**Files:**
- Create: `android/app/src/main/java/com/jodongbeom/voicetest/SpeechService.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` (permissions + service)

**Interfaces:**
- Consumes: `PlayerState`, `Line` (Task 3).
- Produces (used by Tasks 5):
  - Actions: `SpeechService.ACTION_LOAD`, `ACTION_PLAY_FROM`, `ACTION_PLAY`, `ACTION_PAUSE`, `ACTION_TOGGLE`, `ACTION_PREV`, `ACTION_NEXT`, `ACTION_RATE`, `ACTION_CLOSE`
  - Extras: `EXTRA_LINES` (String JSON `[{"chunks":[...],"section":bool}]`), `EXTRA_INDEX` (Int), `EXTRA_RATE` (Float)
  - `SpeechService.listener: ((String) -> Unit)?` — receives state JSON `{"state":"playing|paused|ended","pos":Int,"total":Int,"message"?:String}` on the main thread
  - `SpeechService.instance: SpeechService?` and `fun reportNow()`

- [ ] **Step 1: Manifest additions**

In `android/app/src/main/AndroidManifest.xml`, after the INTERNET permission add:
```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
    <uses-permission android:name="android.permission.WAKE_LOCK"/>
```
Inside `<application>`, after the `</activity>`:
```xml
        <service
            android:name=".SpeechService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback"/>
```

- [ ] **Step 2: Service**

`android/app/src/main/java/com/jodongbeom/voicetest/SpeechService.kt`:
```kotlin
package com.jodongbeom.voicetest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** 화면이 꺼져도 답안을 끝까지 읽는 포그라운드 서비스. 읽는 위치는 PlayerState 가 관리. */
class SpeechService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_LOAD = "com.jodongbeom.voicetest.LOAD"
        const val ACTION_PLAY_FROM = "com.jodongbeom.voicetest.PLAY_FROM"
        const val ACTION_PLAY = "com.jodongbeom.voicetest.PLAY"
        const val ACTION_PAUSE = "com.jodongbeom.voicetest.PAUSE"
        const val ACTION_TOGGLE = "com.jodongbeom.voicetest.TOGGLE"
        const val ACTION_PREV = "com.jodongbeom.voicetest.PREV"
        const val ACTION_NEXT = "com.jodongbeom.voicetest.NEXT"
        const val ACTION_RATE = "com.jodongbeom.voicetest.RATE"
        const val ACTION_CLOSE = "com.jodongbeom.voicetest.CLOSE"
        const val EXTRA_LINES = "lines"
        const val EXTRA_INDEX = "index"
        const val EXTRA_RATE = "rate"

        private const val CHANNEL_ID = "reading"
        private const val NOTIF_ID = 1

        /** 화면(MainActivity)이 보일 때만 설정됨. 상태 JSON 을 받음 */
        @Volatile var listener: ((String) -> Unit)? = null
        @Volatile var instance: SpeechService? = null
    }

    private val state = PlayerState()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var rate = 0.8f
    private var ended = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private lateinit var audio: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var silence: AudioTrack
    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null

    // 이어폰 연결이 끊기면 스피커로 크게 나오지 않게 일시정지
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "답안 읽기", NotificationManager.IMPORTANCE_LOW))

        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ change ->
                // 전화, 다른 앱 음악 → 일시정지 (자동 재개하지 않음)
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }, main)
            .build()
        silence = buildSilentTrack()

        session = MediaSessionCompat(this, "voice-test").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = prev()
            })
            isActive = true
        }

        ContextCompat.registerReceiver(
            this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            report("음성 엔진을 시작할 수 없습니다")
            return
        }
        tts.setLanguage(Locale.KOREAN)
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { main.post { onChunkDone(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { main.post { onChunkDone(utteranceId) } }
            override fun onError(utteranceId: String, errorCode: Int) { main.post { onChunkDone(utteranceId) } }
        })
        ttsReady = true
        if (state.playing) speakCurrent()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 서비스가 살아 있는 동안은 항상 포그라운드 (안드로이드 12+ 백그라운드 재시작 제한 회피)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)

        when (intent?.action) {
            ACTION_LOAD -> {
                stopSpeaking()
                state.load(parseLines(intent.getStringExtra(EXTRA_LINES) ?: "[]"))
                rate = intent.getFloatExtra(EXTRA_RATE, rate)
                ended = false
                report()
            }
            ACTION_PLAY_FROM -> playFrom(intent.getIntExtra(EXTRA_INDEX, 0))
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> if (state.playing) pause() else play()
            ACTION_PREV -> prev()
            ACTION_NEXT -> next()
            ACTION_RATE -> rate = intent.getFloatExtra(EXTRA_RATE, rate)
            ACTION_CLOSE -> {
                pause()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        stopSpeaking()
        tts.shutdown()
        session.release()
        silence.release()
        unregisterReceiver(noisyReceiver)
        super.onDestroy()
    }

    // ---------- 재생 명령 ----------
    private fun playFrom(index: Int) { if (state.playFrom(index)) startSpeaking() else report() }
    private fun play() { if (state.play()) startSpeaking() }
    private fun prev() { if (state.prevLine()) startSpeaking() }
    private fun next() { if (state.nextLine()) startSpeaking() else report("마지막 줄입니다") }
    private fun pause() {
        if (!state.playing) return
        state.pause()
        stopSpeaking()
        report()
    }

    private fun startSpeaking() {
        ended = false
        audio.requestAudioFocus(focusRequest)
        if (silence.playState != AudioTrack.PLAYSTATE_PLAYING) silence.play()
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicetest:reading").apply {
                acquire(3 * 60 * 60 * 1000L) // 최대 3시간 안전장치
            }
        }
        speakCurrent()
        report()
    }

    private fun stopSpeaking() {
        if (ttsReady) tts.stop()
        if (silence.playState == AudioTrack.PLAYSTATE_PLAYING) silence.pause()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        audio.abandonAudioFocusRequest(focusRequest)
    }

    private fun speakCurrent() {
        if (!ttsReady) return // 초기화가 끝나면 onInit 에서 이어서 읽음
        val text = state.currentText() ?: return
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${state.generation}:${state.pos}:${state.chunk}")
    }

    private fun onChunkDone(utteranceId: String) {
        // 이동/일시정지/새 답안 이전에 시작된 조각의 알림은 무시
        if (!state.playing || !utteranceId.startsWith("${state.generation}:")) return
        if (state.advance()) {
            speakCurrent()
            report()
        } else {
            stopSpeaking()
            ended = true
            report()
        }
    }

    // ---------- 상태 알림 (화면, 잠금화면) ----------
    fun reportNow() { main.post { report() } }

    private fun report(message: String? = null) {
        val json = JSONObject()
            .put("state", when { state.playing -> "playing"; ended -> "ended"; else -> "paused" })
            .put("pos", state.pos)
            .put("total", state.lines.size)
        if (message != null) json.put("message", message)
        listener?.invoke(json.toString())
        updateSession()
    }

    private fun lineLabel(): String =
        if (state.lines.isEmpty()) "대기"
        else "${state.pos + 1}/${state.lines.size}줄 · " + (if (state.playing) "읽는 중" else "일시정지")

    private fun updateSession() {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (state.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .build()
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "시험 답안 듣기")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lineLabel())
                .build()
        )
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
            // 알림 권한이 없으면 표시만 안 됨 (읽기는 계속)
        }
    }

    private fun commandIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, requestCode,
            Intent(this, SpeechService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val playPause = if (state.playing)
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "일시정지", commandIntent(ACTION_TOGGLE, 2))
        else
            NotificationCompat.Action(android.R.drawable.ic_media_play, "재생", commandIntent(ACTION_TOGGLE, 2))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("시험 답안 듣기")
            .setContentText(lineLabel())
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_previous, "이전 줄", commandIntent(ACTION_PREV, 1))
            .addAction(playPause)
            .addAction(android.R.drawable.ic_media_next, "다음 줄", commandIntent(ACTION_NEXT, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "닫기", commandIntent(ACTION_CLOSE, 4))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    // ---------- 도우미 ----------
    private fun parseLines(json: String): List<Line> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val c = o.getJSONArray("chunks")
            Line((0 until c.length()).map { c.getString(it) }, o.optBoolean("section"))
        }
    }

    /** 무음 1초를 반복 재생. TTS 소리는 TTS 엔진 앱이 내므로, 이게 있어야 이어폰 버튼이 우리 앱으로 옴 */
    private fun buildSilentTrack(): AudioTrack {
        val sampleRate = 8000
        val frames = sampleRate
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
        track.write(ShortArray(frames), 0, frames)
        track.setLoopPoints(0, frames, -1)
        return track
    }
}
```

Note: `setOnAudioFocusChangeListener(listener, handler)` delivers on `main`, so `pause()` runs on the main thread like every other command.

- [ ] **Step 3: Build in CI**

```powershell
git add android/app/src/main
git commit -m "Add SpeechService with TTS, media session, and lock-screen controls`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```
Watch the run. Expected: success (PlayerState tests still pass, service compiles). Fix compile errors from the log if any.

---

### Task 5: MainActivity bridge, camera, permissions, notices

**Files:**
- Create: `android/app/src/main/java/com/jodongbeom/voicetest/SpeechBridge.kt`
- Modify (replace whole file): `android/app/src/main/java/com/jodongbeom/voicetest/MainActivity.kt`

**Interfaces:**
- Consumes: `SpeechService` actions/extras, `SpeechService.listener`, `SpeechService.instance?.reportNow()` (Task 4).
- Produces (used by Task 6, JS side): `window.AndroidSpeech` with `load(json: String, rate: Number)`, `playFrom(index: Number)`, `pause()`, `setRate(rate: Number)`; calls `window.onNativeSpeech(stateObject)` and `window.onNativeNotice(text)`.

- [ ] **Step 1: Bridge**

`android/app/src/main/java/com/jodongbeom/voicetest/SpeechBridge.kt`:
```kotlin
package com.jodongbeom.voicetest

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat

/** index.html 에서 window.AndroidSpeech 로 보이는 객체. 명령을 SpeechService 로 전달 */
class SpeechBridge(private val context: Context) {

    private fun send(action: String, fill: Intent.() -> Unit = {}) {
        val intent = Intent(context, SpeechService::class.java).setAction(action).apply(fill)
        ContextCompat.startForegroundService(context, intent)
    }

    @JavascriptInterface
    fun load(json: String, rate: Float) = send(SpeechService.ACTION_LOAD) {
        putExtra(SpeechService.EXTRA_LINES, json)
        putExtra(SpeechService.EXTRA_RATE, rate)
    }

    @JavascriptInterface
    fun playFrom(index: Int) = send(SpeechService.ACTION_PLAY_FROM) {
        putExtra(SpeechService.EXTRA_INDEX, index)
    }

    // 서비스가 없을 때 일시정지/속도 변경으로 알림을 띄우지 않도록
    @JavascriptInterface
    fun pause() {
        if (SpeechService.instance != null) send(SpeechService.ACTION_PAUSE)
    }

    @JavascriptInterface
    fun setRate(rate: Float) {
        if (SpeechService.instance != null) send(SpeechService.ACTION_RATE) { putExtra(SpeechService.EXTRA_RATE, rate) }
    }
}
```

- [ ] **Step 2: Full MainActivity**

Replace `android/app/src/main/java/com/jodongbeom/voicetest/MainActivity.kt` with:
```kotlin
package com.jodongbeom.voicetest

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pageReady = false
    private val pendingNotices = mutableListOf<String>()

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoUri: Uri? = null

    // 촬영 결과를 <input type=file> 에 돌려줌. 취소해도 반드시 null 을 돌려줘야 다음 촬영이 됨
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri
        fileCallback?.onReceiveValue(if (ok && uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) notice("⚠ 알림 권한이 꺼져 있어 화면이 꺼지면 읽기가 멈출 수 있습니다. 설정 > 앱 > 답안 듣기 > 알림에서 켜 주세요.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.addJavascriptInterface(SpeechBridge(applicationContext), "AndroidSpeech")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingNotices.forEach { sendNotice(it) }
                pendingNotices.clear()
                SpeechService.instance?.reportNow()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                openCamera()
                return true
            }
        }
        setContentView(web)
        web.loadUrl(PAGE_URL)

        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        checkKoreanVoice()
        showBatteryNoticeOnce()
    }

    override fun onResume() {
        super.onResume()
        SpeechService.listener = { json ->
            runOnUiThread { web.evaluateJavascript("window.onNativeSpeech && window.onNativeSpeech($json)", null) }
        }
        if (pageReady) SpeechService.instance?.reportNow()
    }

    override fun onPause() {
        SpeechService.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun openCamera() {
        val dir = File(cacheDir, "photos").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() } // 지난 사진 정리
        }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        photoUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            notice("⚠ 카메라 앱을 찾을 수 없습니다.")
        }
    }

    private fun checkKoreanVoice() {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS &&
                (tts?.isLanguageAvailable(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
            if (!ok) notice("⚠ 한국어 음성이 없습니다. 설정 > 텍스트 음성 변환(TTS)에서 한국어 음성 데이터를 설치하세요.")
            tts?.shutdown()
        }
    }

    private fun showBatteryNoticeOnce() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("batteryNoticeShown", false)) return
        prefs.edit().putBoolean("batteryNoticeShown", true).apply()
        notice("ℹ 삼성 등 일부 폰은 배터리 절약 때문에 화면이 꺼진 뒤 읽기가 멈출 수 있습니다. 그러면 설정 > 애플리케이션 > 답안 듣기 > 배터리 > '제한 없음'으로 바꾸세요.")
    }

    private fun notice(text: String) {
        runOnUiThread { if (pageReady) sendNotice(text) else pendingNotices += text }
    }

    private fun sendNotice(text: String) {
        web.evaluateJavascript("window.onNativeNotice && window.onNativeNotice(${JSONObject.quote(text)})", null)
    }

    companion object {
        const val PAGE_URL = "https://appassets.androidplatform.net/assets/index.html"
    }
}
```

- [ ] **Step 3: Build in CI**

```powershell
git add android/app/src/main
git commit -m "Wire WebView bridge, camera capture, permissions, and notices`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```
Watch the run. Expected: success.

---

### Task 6: index.html app mode (TDD with Node)

**Files:**
- Create: `tests/web/harness.mjs`
- Create: `tests/web/native-bridge.test.mjs`
- Modify: `index.html` (script section: speech setup, `unlockSpeech`, `playFrom`, `pausePlayer`, rate listener, new callbacks)

**Interfaces:**
- Consumes: `window.AndroidSpeech` shape from Task 5.
- Produces: `window.onNativeSpeech(s)`, `window.onNativeNotice(text)`; `nativeSpeech()` helper; `nativeSent` tracking.

- [ ] **Step 1: Test harness**

`tests/web/harness.mjs`:
```js
// index.html 의 <script> 를 Node vm 에서 가짜 DOM 으로 실행
import { readFileSync } from "node:fs";
import vm from "node:vm";

export function loadPage({ bridge = null, synth = undefined } = {}) {
  const html = readFileSync(new URL("../../index.html", import.meta.url), "utf8");
  const script = html.match(/<script>([\s\S]*)<\/script>/)[1];

  const els = new Map();
  const el = (id) => {
    if (!els.has(id)) {
      els.set(id, {
        id,
        textContent: "",
        value: id === "rate" ? "0.8" : "",
        hidden: false,
        files: null,
        listeners: {},
        classList: { toggle() {} },
        addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); },
        fire(type) { (this.listeners[type] || []).forEach((fn) => fn({ target: this })); },
        click() { this.fire("click"); },
      });
    }
    return els.get(id);
  };

  const store = new Map();
  const window = { AndroidSpeech: bridge, speechSynthesis: synth };
  const ctx = {
    window,
    document: { getElementById: el, createElement: () => ({}) },
    localStorage: {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, String(v)),
      removeItem: (k) => store.delete(k),
    },
    setTimeout, clearTimeout, console, URL, AbortController,
    Image: class {},
    SpeechSynthesisUtterance: class { constructor(t) { this.text = t; } },
    fetch: async () => { throw new Error("fetch disabled in tests"); },
  };
  vm.createContext(ctx);
  vm.runInContext(script, ctx);
  const run = (code) => vm.runInContext(code, ctx);
  return { el, window, run };
}

export function fakeBridge() {
  const calls = [];
  return {
    calls,
    load: (json, rate) => calls.push(["load", JSON.parse(json).length, rate]),
    playFrom: (i) => calls.push(["playFrom", i]),
    pause: () => calls.push(["pause"]),
    setRate: (r) => calls.push(["setRate", r]),
  };
}
```

- [ ] **Step 2: Write the failing tests**

`tests/web/native-bridge.test.mjs`:
```js
import test from "node:test";
import assert from "node:assert/strict";
import { loadPage, fakeBridge } from "./harness.mjs";

const ANSWER = "Ⅰ. 소재\n* 항목 A\n* 항목 B\nⅡ. 물음 1\n1. 요금제 A\n* 예산식\nⅢ. 결론\n* 끝";

function appPage() {
  const bridge = fakeBridge();
  const page = loadPage({ bridge });
  page.run(`lastAnswer = ${JSON.stringify(ANSWER)}`);
  return { ...page, bridge };
}

test("app mode shows no browser-speech warning", () => {
  const { el } = appPage();
  assert.doesNotMatch(el("voiceWarn").textContent, /지원하지 않습니다/);
});

test("browser mode without speechSynthesis still warns", () => {
  const { el } = loadPage({ bridge: null, synth: undefined });
  assert.match(el("voiceWarn").textContent, /지원하지 않습니다/);
});

test("play sends lines once, then plays from current line", () => {
  const { el, bridge } = appPage();
  el("btnPlay").click();
  assert.deepEqual(bridge.calls, [["load", 8, 0.8], ["playFrom", 0]]);
});

test("native state updates status and play button", () => {
  const { el, window } = appPage();
  el("btnPlay").click();
  window.onNativeSpeech({ state: "playing", pos: 2, total: 8 });
  assert.equal(el("status").textContent, "읽는 중 · 3/8줄");
  assert.equal(el("btnPlay").textContent, "⏸ 일시정지");
});

test("section navigation uses synced position and does not resend lines", () => {
  const { el, window, bridge } = appPage();
  el("btnPlay").click();
  window.onNativeSpeech({ state: "playing", pos: 4, total: 8 }); // Ⅱ 목차(3) 안
  el("btnPrevSection").click();
  el("btnNextSection").click();
  assert.deepEqual(bridge.calls.slice(2), [["playFrom", 3], ["playFrom", 6]]);
});

test("pause goes to native, not speechSynthesis", () => {
  const { el, window, bridge } = appPage();
  el("btnPlay").click();
  window.onNativeSpeech({ state: "playing", pos: 0, total: 8 });
  el("btnPlay").click();
  assert.deepEqual(bridge.calls.at(-1), ["pause"]);
});

test("rate slider forwards to native", () => {
  const { el, bridge } = appPage();
  el("rate").value = "0.3";
  el("rate").fire("input");
  assert.deepEqual(bridge.calls.at(-1), ["setRate", 0.3]);
});

test("ended resets UI to idle", () => {
  const { el, window } = appPage();
  el("btnPlay").click();
  window.onNativeSpeech({ state: "ended", pos: 0, total: 8 });
  assert.equal(el("status").textContent, "대기");
  assert.equal(el("btnPlay").textContent, "▶ 재생");
});

test("native message is shown with position", () => {
  const { el, window } = appPage();
  el("btnPlay").click();
  window.onNativeSpeech({ state: "playing", pos: 7, total: 8, message: "마지막 줄입니다" });
  assert.equal(el("status").textContent, "마지막 줄입니다 · 8/8줄");
});

test("new answer re-sends load", () => {
  const { el, run, bridge } = appPage();
  el("btnPlay").click();
  run(`lastAnswer = "Ⅰ. 새 답안\\n* 하나"`);
  el("btnReplay").click();
  assert.deepEqual(bridge.calls.slice(2), [["load", 2, 0.8], ["playFrom", 0]]);
});

test("quiet error message keeps error status through native updates", () => {
  const { el, window, run } = appPage();
  run(`reportError("401 인증 실패", "키 오류")`);
  window.onNativeSpeech({ state: "playing", pos: 0, total: 1 });
  window.onNativeSpeech({ state: "ended", pos: 0, total: 1 });
  assert.equal(el("status").textContent, "오류: 401 인증 실패");
});

test("native notice is shown in the warning area", () => {
  const { el, window } = appPage();
  window.onNativeNotice("⚠ 한국어 음성이 없습니다.");
  assert.equal(el("voiceWarn").hidden, false);
  assert.match(el("voiceWarn").textContent, /한국어 음성이 없습니다/);
});
```

- [ ] **Step 3: Run tests to see them fail**

Run: `node --test tests/web/`
Expected: failures such as `onNativeSpeech is not a function` and missing `load`/`playFrom` calls (browser-mode test passes).

- [ ] **Step 4: Implement in index.html**

4a. After `const synth = window.speechSynthesis;` add:
```js
// 앱(안드로이드) 안에서 열리면 음성은 앱이 담당 (화면이 꺼져도 읽기 위해)
const nativeSpeech = () => window.AndroidSpeech || null;
let nativeSent = null; // 앱에 마지막으로 보낸 줄 목록 (같으면 다시 보내지 않음)
```

4b. Replace the line `if (!synth) {` (the startup warning block) with:
```js
if (nativeSpeech()) {
  // 한국어 음성 확인은 앱이 onNativeNotice 로 알려줌
} else if (!synth) {
```

4c. In `unlockSpeech`, replace its first line with:
```js
  if (nativeSpeech() || !synth || synth.speaking) return; // 앱이면 불필요, 읽는 중이면 끊지 않도록
```

4d. At the top of `playFrom(lineIdx)` (before `if (!synth || !player.lines.length) return;`) insert:
```js
  const nat = nativeSpeech();
  if (nat) {
    if (!player.lines.length) return;
    if (nativeSent !== player.lines) {
      nat.load(JSON.stringify(player.lines), parseFloat(rateEl.value));
      nativeSent = player.lines;
    }
    nat.playFrom(Math.max(0, Math.min(lineIdx, player.lines.length - 1)));
    return; // 상태 표시는 onNativeSpeech 가 갱신
  }
```

4e. At the top of `pausePlayer()` insert:
```js
  const nat = nativeSpeech();
  if (nat) { nat.pause(); return; }
```

4f. In the rate slider `input` listener, after `lsSet(RATE_STORAGE, rateEl.value);` add:
```js
  const nat = nativeSpeech();
  if (nat) nat.setRate(parseFloat(rateEl.value));
```

4g. After the `sectionStarts()` function add:
```js
// ---------- 앱이 보내는 상태 ----------
window.onNativeSpeech = (s) => {
  player.playing = s.state === "playing";
  if (typeof s.pos === "number") player.pos = s.pos;
  if (s.state === "ended") {
    $("btnPlay").textContent = "▶ 재생";
    if (!player.quiet) setStatus("대기");
    const done = player.onDone;
    player.onDone = null;
    if (done) done();
    return;
  }
  updatePlayUI();
  if (s.message && !player.quiet) setStatus(s.message + posLabel());
};

window.onNativeNotice = (text) => {
  const warn = $("voiceWarn");
  warn.textContent = warn.hidden || !warn.textContent ? text : warn.textContent + "\n" + text;
  warn.hidden = false;
};
```

4h. In CSS, make multi-line notices readable: change `#voiceWarn { color: #b45309; font-weight: 700; margin: 0 0 12px; }` to
```css
  #voiceWarn { color: #b45309; font-weight: 700; margin: 0 0 12px; white-space: pre-line; }
```

- [ ] **Step 5: Run tests to see them pass**

Run: `node --test tests/web/`
Expected: 12 tests pass.

- [ ] **Step 6: Browser regression check (web version unchanged)**

Open `index.html` in the browser pane and run the existing mock check (speechSynthesis stubbed, `lastAnswer` set, click play/next/prev section) — expect the same results as before this task: statuses `읽는 중 · N/8줄`, section jumps 3 → 0 → 3 → 6.

- [ ] **Step 7: Commit and push (triggers a new APK)**

```powershell
git add index.html tests
git commit -m "Route speech to the Android app when opened inside it`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```
Watch the run; expect success and a new release.

---

### Task 7: README install guide and phone checklist

**Files:**
- Modify: `README.md` (add an "안드로이드 앱" section at the top, keep the web section)

- [ ] **Step 1: Add the section**

Insert after the first paragraph of `README.md`:
```markdown
## 📱 안드로이드 앱 (화면 꺼져도 읽기)

**설치 / 업데이트 링크** (폰 크롬에서 열기)
https://github.com/JoDongbeom/voice-test/releases/latest/download/voice-test.apk

1. 링크를 눌러 `voice-test.apk` 를 받습니다.
2. "출처를 알 수 없는 앱" 설치를 허용합니다 (크롬에 대해 1번만).
3. 설치 → 열기 → **알림 허용** (잠금화면 조작에 필요)
4. 맨 아래에 API 키 입력 → [저장] (웹과 따로 저장되므로 앱에서 한 번 더)
5. 업데이트: 같은 링크로 받아 설치하면 덮어쓰기됨 (키/속도 유지)

**조작**
- 잠금화면/알림: ◀ 이전 줄, ⏯ 재생·일시정지, ▶ 다음 줄, ✕ 닫기
- 이어폰 버튼: 한 번 = 재생/일시정지, 두 번 = 다음 줄 (기종마다 다를 수 있음)
- 목차 이동, 처음부터는 앱 화면 버튼으로

**폰 테스트 체크리스트**
- [ ] 음성 테스트가 이어폰으로 들림
- [ ] 사진 → 답안 → 읽기 시작
- [ ] 화면 끄고 끝까지 읽음
- [ ] 잠금화면 ◀ ⏯ ▶ 동작
- [ ] 이어폰 버튼 재생/일시정지, 다음 줄 동작
- [ ] 화면 다시 켜면 줄 위치 표시가 맞음
- [ ] 카메라에서 취소 → 📷 다시 눌러도 카메라 열림
- [ ] 읽는 중 이어폰 연결 끊기 → 스피커로 안 나오고 일시정지
- [ ] 읽는 중 전화/음악 앱 → 일시정지
- [ ] 새 버전 덮어쓰기 설치 후 키/속도 유지

**멈춘다면**: 설정 > 애플리케이션 > 답안 듣기 > 배터리 > '제한 없음'
```

- [ ] **Step 2: Commit and push**

```powershell
git add README.md
git commit -m "Document Android app install, controls, and phone checklist`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```
(README-only change does not trigger the APK workflow.)
