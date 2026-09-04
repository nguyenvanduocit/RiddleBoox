# Phone Pairing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a writer set up base url / api key / model on RiddleBoox by scanning a QR with their phone instead of typing a long key on an e-ink keyboard.

**Architecture:** `PairActivity` runs a tiny ktor HTTP server (`PairingServer`) bound to the diary's own LAN address, shows a QR pointing at it. A phone scans, opens a one-page HTML form (`pairingPage`), submits, the server validates (`parsePairing`) and hands the payload back to `PairActivity`, which returns it as an activity result. `SettingsActivity` receives that result and fills its existing base-url/api-key/model fields — the writer still presses "save" through the normal path.

**Tech Stack:** Kotlin, Android `Activity` (no Jetpack Compose in this repo), `io.ktor:ktor-server-cio:3.3.3` (embedded HTTP server), `com.google.zxing:core:3.5.4` (QR encode only, no camera). JVM unit tests with JUnit4; Robolectric (`@Config(sdk = [35])`) only where `android.graphics`/`ConnectivityManager` is unavoidable.

**Spec:** `docs/superpowers/specs/2026-09-04-phone-pairing-design.md`

## Global Constraints

- minSdk 29, targetSdk 36, compileSdk 36 (`app/build.gradle.kts:41-43`) — no API used here needs anything above 29.
- `isMinifyEnabled = false` (`app/build.gradle.kts:67`) — every dependency added ships whole; keep both new libraries as small as approved (ktor-server-cio + zxing-core, no zxing-android-embedded/camera).
- Flat package placement: every new file lives in `com.riddleboox.app.settings`, alongside `SettingsActivity.kt`/`Providers.kt` — matches this repo's flat-package convention (see `.claude/CLAUDE.md` "Nguyên tắc kiến trúc").
- No new Android permission: `INTERNET` and `ACCESS_NETWORK_STATE` are already declared (`AndroidManifest.xml:5-6`).
- Robolectric tests that touch `android.graphics.Bitmap`/`ConnectivityManager` must pin `@Config(sdk = [35])` (`/Users/firegroup/projects/RiddleBoox/CLAUDE.md` "Unit test (Robolectric)").
- No HTTPS (approved in spec §2) — the pairing page must say so in its own copy.
- Server lifecycle: alive only while `PairActivity` is resumed (spec §2, §5).

**Note on execution mode:** this plan was requested via an autonomous `/goal` run (no interactive user available mid-task to pick "subagent-driven vs inline"). *Giả định: thực thi trực tiếp trong phiên hiện tại theo `superpowers:executing-plans` (không dispatch subagent riêng), vì phần nghiên cứu API ktor 3.3.3 (xác nhận bằng `javap` trên jar thật — xem lịch sử hội thoại) đã nằm sẵn trong context này; một subagent mới sẽ phải đoán lại đúng API đó.* Mỗi task vẫn chạy build/test thật trước khi commit.

---

### Task 1: Add ktor-server-cio and zxing-core dependencies

**Files:**
- Modify: `app/build.gradle.kts:115-138` (dependencies block)

**Interfaces:**
- Produces: `io.ktor.server.cio.CIO`, `io.ktor.server.engine.embeddedServer`, `io.ktor.server.routing.*` on the compile classpath; `com.google.zxing.qrcode.QRCodeWriter`, `com.google.zxing.BarcodeFormat`, `com.google.zxing.common.BitMatrix` likewise.

- [ ] **Step 1: Add the two dependencies**

In `app/build.gradle.kts`, inside the `dependencies { ... }` block, right after the existing `implementation("ai.koog:http-client-ktor:1.1.1")` line, add:

```kotlin
    // PairActivity's phone-pairing server (see settings/PairingServer.kt) and
    // its QR code — 3.3.3 matches the ktor version koog's http-client-ktor
    // already pulls in (ktor-http, ktor-network), so no version conflicts.
    implementation("io.ktor:ktor-server-cio:3.3.3")
    implementation("com.google.zxing:core:3.5.4")
```

- [ ] **Step 2: Sync and confirm the build still configures**

Run: `./gradlew -q :app:dependencies --configuration debugRuntimeClasspath 2>&1 | grep -E "ktor-server-cio|zxing"`
Expected: both lines present, resolved to `3.3.3` and `3.5.4` with no conflict warnings printed above them.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: add ktor-server-cio and zxing-core for phone pairing"
```

---

### Task 2: `PairingPayload.kt` — parse the phone's submitted form

**Files:**
- Create: `app/src/main/java/com/riddleboox/app/settings/PairingPayload.kt`
- Test: `app/src/test/java/com/riddleboox/app/settings/PairingPayloadTest.kt`

**Interfaces:**
- Consumes: `Provider`, `PROVIDERS` (`app/src/main/java/com/riddleboox/app/settings/Providers.kt`, already in this package — `data class Provider(val label: String, val baseUrl: String)`, `val PROVIDERS: List<Provider>`).
- Produces: `data class PairingPayload(val baseUrl: String, val apiKey: String, val model: String)`; `sealed interface PairingParse { data class Ok(val payload: PairingPayload); data class Rejected(val reason: String) }`; `fun parsePairing(form: Map<String, String>): PairingParse` — used by Task 6 (`PairingServer`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/riddleboox/app/settings/PairingPayloadTest.kt`:

```kotlin
package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun `a known provider label resolves to its canonical base url`() {
        val form = mapOf("provider" to "OpenRouter", "api_key" to "sk-mine", "model" to "")
        val result = parsePairing(form)
        assertTrue(result is PairingParse.Ok)
        assertEquals("https://openrouter.ai/api", (result as PairingParse.Ok).payload.baseUrl)
    }

    @Test
    fun `other with a base url is accepted`() {
        val form = mapOf("provider" to "other", "base_url" to "https://llm.local", "api_key" to "sk-mine")
        val result = parsePairing(form)
        assertTrue(result is PairingParse.Ok)
        assertEquals("https://llm.local", (result as PairingParse.Ok).payload.baseUrl)
    }

    @Test
    fun `other with no base url is rejected`() {
        val form = mapOf("provider" to "other", "api_key" to "sk-mine")
        assertTrue(parsePairing(form) is PairingParse.Rejected)
    }

    @Test
    fun `an unknown provider label is rejected`() {
        val form = mapOf("provider" to "made-up", "api_key" to "sk-mine")
        assertTrue(parsePairing(form) is PairingParse.Rejected)
    }

    @Test
    fun `a missing api key is rejected`() {
        val form = mapOf("provider" to "OpenAI", "api_key" to "  ")
        assertTrue(parsePairing(form) is PairingParse.Rejected)
    }

    @Test
    fun `stray keyboard spaces are trimmed`() {
        val form = mapOf("provider" to " OpenAI ", "api_key" to " sk-mine ", "model" to " gpt-5 ")
        val result = parsePairing(form) as PairingParse.Ok
        assertEquals("sk-mine", result.payload.apiKey)
        assertEquals("gpt-5", result.payload.model)
    }

    @Test
    fun `a blank model is accepted and stays blank`() {
        val form = mapOf("provider" to "OpenAI", "api_key" to "sk-mine")
        val result = parsePairing(form) as PairingParse.Ok
        assertEquals("", result.payload.model)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.PairingPayloadTest"`
Expected: FAIL — `Unresolved reference: parsePairing` (the file doesn't exist yet).

- [ ] **Step 3: Write `PairingPayload.kt`**

```kotlin
package com.riddleboox.app.settings

/** What a phone's pairing form adds up to — see [parsePairing]. */
data class PairingPayload(val baseUrl: String, val apiKey: String, val model: String)

/** The result of validating a submitted pairing form. */
sealed interface PairingParse {
    data class Ok(val payload: PairingPayload) : PairingParse
    /** [reason] is shown to the phone, on [rejectedPage] — plain words, no field names. */
    data class Rejected(val reason: String) : PairingParse
}

/**
 * Turns the phone's submitted form fields into a [PairingPayload] — the same
 * checks [SettingsActivity] applies by hand: the provider resolves through
 * [PROVIDERS] by label, "other" needs its own url typed in, and the key
 * can't be blank. [form] keys match the field `name`s in [pairingPage].
 */
fun parsePairing(form: Map<String, String>): PairingParse {
    val providerLabel = form["provider"]?.trim().orEmpty()
    val baseUrl = when {
        providerLabel == "other" -> form["base_url"]?.trim().orEmpty()
        else -> PROVIDERS.firstOrNull { it.label == providerLabel }?.baseUrl.orEmpty()
    }
    if (baseUrl.isEmpty()) {
        return PairingParse.Rejected("choose a server, or pick \"other\" and type one in")
    }
    val apiKey = form["api_key"]?.trim().orEmpty()
    if (apiKey.isEmpty()) return PairingParse.Rejected("paste an API key")
    val model = form["model"]?.trim().orEmpty()
    return PairingParse.Ok(PairingPayload(baseUrl, apiKey, model))
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.PairingPayloadTest"`
Expected: PASS, 7/7.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/settings/PairingPayload.kt app/src/test/java/com/riddleboox/app/settings/PairingPayloadTest.kt
git commit -m "feat(settings): parse a phone-submitted pairing form"
```

---

### Task 3: `PairingPage.kt` — the HTML pages served to the phone

**Files:**
- Create: `app/src/main/java/com/riddleboox/app/settings/PairingPage.kt`
- Test: `app/src/test/java/com/riddleboox/app/settings/PairingPageTest.kt`

**Interfaces:**
- Consumes: `Provider`, `PROVIDERS` (`Providers.kt`).
- Produces: `fun pairingPage(token: String, providers: List<Provider>): String`, `fun confirmationPage(): String`, `fun rejectedPage(reason: String): String` — used by Task 6 (`PairingServer`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/riddleboox/app/settings/PairingPageTest.kt`:

```kotlin
package com.riddleboox.app.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPageTest {

    @Test
    fun `every provider label appears on the form`() {
        val html = pairingPage("tok123", PROVIDERS)
        for (provider in PROVIDERS) assertTrue(html.contains(provider.label))
    }

    @Test
    fun `the token rides along as a hidden field`() {
        val html = pairingPage("tok123", PROVIDERS)
        assertTrue(html.contains("tok123"))
        assertTrue(html.contains("type=\"hidden\""))
    }

    @Test
    fun `the form posts to slash pair`() {
        assertTrue(pairingPage("tok123", PROVIDERS).contains("action=\"/pair\""))
    }

    @Test
    fun `an other option is always offered`() {
        assertTrue(pairingPage("tok123", PROVIDERS).contains("value=\"other\""))
    }

    @Test
    fun `a token is html-escaped so it cannot break out of the attribute`() {
        val html = pairingPage("\"><script>", PROVIDERS)
        assertTrue(!html.contains("\"><script>"))
    }

    @Test
    fun `the confirmation page says it was sent`() {
        assertTrue(confirmationPage().contains("sent"))
    }

    @Test
    fun `the rejection page carries the reason, escaped`() {
        assertTrue(rejectedPage("paste an API key").contains("paste an API key"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.PairingPageTest"`
Expected: FAIL — `Unresolved reference: pairingPage`.

- [ ] **Step 3: Write `PairingPage.kt`**

```kotlin
package com.riddleboox.app.settings

/**
 * The one page a phone's browser ever sees for this: providers as radio
 * buttons (a small inline script shows the url field only for "other"), an
 * api key field, an optional model field, and the pairing [token] riding
 * along as a hidden field so [PairingServer] can tell this submission apart
 * from anyone else who happens to be on the same Wi-Fi. Self-contained —
 * no CDN, no external asset — so it renders the same whether the phone has
 * real internet or only this Wi-Fi.
 */
fun pairingPage(token: String, providers: List<Provider>): String {
    val radios = providers.joinToString("\n") { provider ->
        """<label><input type="radio" name="provider" value="${escapeHtml(provider.label)}" onchange="toggleOther()"> """ +
            "${escapeHtml(provider.label)} — ${escapeHtml(provider.baseUrl)}</label><br>"
    }
    return """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>set up your diary</title></head>
        <body style="font-family: sans-serif; max-width: 480px; margin: 24px auto; padding: 0 16px;">
        <h2>set up your diary</h2>
        <p>works on your home Wi-Fi only — this page is served by the diary itself, not the internet.</p>
        <form method="post" action="/pair">
        <input type="hidden" name="t" value="${escapeHtml(token)}">
        $radios
        <label><input type="radio" name="provider" value="other" onchange="toggleOther()"> other</label><br>
        <div id="other-url" style="display:none">
          <label>base url<br><input type="text" name="base_url" style="width:100%"></label>
        </div>
        <p><label>api key<br><input type="password" name="api_key" style="width:100%" autocomplete="off"></label></p>
        <p><label>model (optional)<br><input type="text" name="model" style="width:100%"></label></p>
        <p><button type="submit">send to my diary</button></p>
        </form>
        <script>
        function toggleOther() {
          var other = document.querySelector('input[name=provider][value=other]').checked;
          document.getElementById('other-url').style.display = other ? 'block' : 'none';
        }
        </script>
        </body></html>
    """.trimIndent()
}

/** Shown to the phone right after a valid submission — [PairingServer] stops itself immediately after. */
fun confirmationPage(): String = simplePage("sent", "Sent — look at your diary.")

/** Shown to the phone when [parsePairing] rejects the submission; the form is still open behind it. */
fun rejectedPage(reason: String): String = simplePage("couldn't send", "Couldn't send: ${escapeHtml(reason)}.")

private fun simplePage(title: String, message: String): String = """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>${escapeHtml(title)}</title></head>
    <body style="font-family: sans-serif; max-width: 480px; margin: 24px auto; padding: 0 16px;">
    <p>$message</p>
    </body></html>
""".trimIndent()

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.PairingPageTest"`
Expected: PASS, 7/7.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/settings/PairingPage.kt app/src/test/java/com/riddleboox/app/settings/PairingPageTest.kt
git commit -m "feat(settings): render the phone-facing pairing page"
```

---

### Task 4: `LanAddress.kt` — pick the address the QR points at

**Files:**
- Create: `app/src/main/java/com/riddleboox/app/settings/LanAddress.kt`
- Test: `app/src/test/java/com/riddleboox/app/settings/LanAddressTest.kt`

**Interfaces:**
- Produces: `fun pickLanAddress(addresses: List<InetAddress>): Inet4Address?` (pure — tested directly), `fun lanAddress(context: Context): Inet4Address?` (shell, used by Task 7's `PairActivity`, not unit-tested here — it's a thin wrapper the same way `OnyxLibrary` reads a system provider without its own unit test).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/riddleboox/app/settings/LanAddressTest.kt`:

```kotlin
package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class LanAddressTest {

    @Test
    fun `a site-local IPv4 address is picked`() {
        val addr = InetAddress.getByName("192.168.1.42")
        assertEquals(addr, pickLanAddress(listOf(addr)))
    }

    @Test
    fun `loopback is skipped`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        assertNull(pickLanAddress(listOf(loopback)))
    }

    @Test
    fun `link-local is skipped`() {
        val linkLocal = InetAddress.getByName("169.254.1.5")
        assertNull(pickLanAddress(listOf(linkLocal)))
    }

    @Test
    fun `an IPv6 address is skipped even if the list has nothing else`() {
        val v6 = InetAddress.getByName("::1")
        assertNull(pickLanAddress(listOf(v6)))
    }

    @Test
    fun `the first usable address wins when several are offered`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val real = InetAddress.getByName("10.0.0.5")
        assertEquals(real, pickLanAddress(listOf(loopback, real)))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.LanAddressTest"`
Expected: FAIL — `Unresolved reference: pickLanAddress`.

- [ ] **Step 3: Write `LanAddress.kt`**

```kotlin
package com.riddleboox.app.settings

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The address the pairing QR points at: a site-local IPv4 (what a home Wi-Fi
 * router hands out) rather than loopback or link-local, neither of which a
 * phone on the same network could ever reach.
 */
fun pickLanAddress(addresses: List<InetAddress>): Inet4Address? =
    addresses.filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }

/**
 * The shell [pickLanAddress] needs: the active network's own addresses, off
 * [ConnectivityManager] — `ACCESS_NETWORK_STATE` is already declared
 * (`AndroidManifest.xml`). Null with no active network, e.g. airplane mode.
 */
fun lanAddress(context: Context): Inet4Address? {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return null
    val addresses = connectivityManager.getLinkProperties(network)?.linkAddresses?.map { it.address }
        ?: return null
    return pickLanAddress(addresses)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.LanAddressTest"`
Expected: PASS, 5/5.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/settings/LanAddress.kt app/src/test/java/com/riddleboox/app/settings/LanAddressTest.kt
git commit -m "feat(settings): pick the LAN address the pairing QR points at"
```

---

### Task 5: `QrBitmap.kt` — render the pairing URL as a QR bitmap

**Files:**
- Create: `app/src/main/java/com/riddleboox/app/settings/QrBitmap.kt`
- Test: `app/src/test/java/com/riddleboox/app/settings/QrBitmapTest.kt`

**Interfaces:**
- Produces: `fun qrBitmap(text: String, sizePx: Int): Bitmap` — used by Task 7's `PairActivity`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/riddleboox/app/settings/QrBitmapTest.kt`. Verified against `com.google.zxing:core:3.5.4`'s real jar (`QRCodeWriter.encode(String, BarcodeFormat, Int, Int): BitMatrix`, `BitMatrix.get(x, y): Boolean`) — the test only checks size and that the render has both colors, not a specific pixel, because zxing pads the requested size with its own quiet zone and the exact finder-pattern offset is an implementation detail not worth pinning:

```kotlin
package com.riddleboox.app.settings

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QrBitmapTest {

    @Test
    fun `the bitmap is exactly the requested size`() {
        val bitmap = qrBitmap("http://192.168.1.5:8080/?t=abc", 200)
        assertEquals(200, bitmap.width)
        assertEquals(200, bitmap.height)
    }

    @Test
    fun `the render actually has both black and white pixels`() {
        val bitmap = qrBitmap("http://192.168.1.5:8080/?t=abc", 200)
        var sawBlack = false
        var sawWhite = false
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                when (bitmap.getPixel(x, y)) {
                    Color.BLACK -> sawBlack = true
                    Color.WHITE -> sawWhite = true
                }
            }
        }
        assertTrue(sawBlack)
        assertTrue(sawWhite)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.QrBitmapTest"`
Expected: FAIL — `Unresolved reference: qrBitmap`.

- [ ] **Step 3: Write `QrBitmap.kt`**

```kotlin
package com.riddleboox.app.settings

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders [text] as a black-on-white QR, [sizePx] square. Plain on/off
 * pixels with no anti-aliasing or grey — the shape an e-ink refresh keeps
 * crisp, the same reason [SettingsWidgets.kt] keeps every field's ink
 * pure black.
 */
fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.QrBitmapTest"`
Expected: PASS, 2/2.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/settings/QrBitmap.kt app/src/test/java/com/riddleboox/app/settings/QrBitmapTest.kt
git commit -m "feat(settings): render the pairing url as a QR bitmap"
```

---

### Task 6: `PairingServer.kt` — the embedded HTTP server

**Files:**
- Create: `app/src/main/java/com/riddleboox/app/settings/PairingServer.kt`
- Test: `app/src/test/java/com/riddleboox/app/settings/PairingServerTest.kt`

**Interfaces:**
- Consumes: `parsePairing(form: Map<String, String>): PairingParse`, `PairingParse.Ok`/`.Rejected` (Task 2); `pairingPage(token, providers): String`, `confirmationPage(): String`, `rejectedPage(reason): String` (Task 3); `PROVIDERS` (`Providers.kt`).
- Produces: `class PairingServer(token: String, onPaired: (PairingPayload) -> Unit) { fun start(): Int; fun stop() }` — used by Task 7's `PairActivity`.

This is a plain JVM test (ktor-server-cio has no Android dependency) — no Robolectric needed, verified by driving the real server over a loopback socket with `HttpURLConnection`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/riddleboox/app/settings/PairingServerTest.kt`:

```kotlin
package com.riddleboox.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun get(port: Int, path: String): HttpURLConnection =
    (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply { connect() }

private fun post(port: Int, path: String, body: String): HttpURLConnection {
    val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    connection.outputStream.use { it.write(body.toByteArray()) }
    connection.connect()
    return connection
}

class PairingServerTest {

    @Test
    fun `GET with the right token serves the pairing page`() {
        val server = PairingServer("secret") {}
        val port = server.start()
        try {
            val connection = get(port, "/?t=secret")
            assertEquals(200, connection.responseCode)
            assertEquals("no-store", connection.getHeaderField("Cache-Control"))
            assertEquals("send to my diary", true.let { connection.inputStream.bufferedReader().readText() }
                .let { if (it.contains("send to my diary")) "send to my diary" else it })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `GET with the wrong token is refused`() {
        val server = PairingServer("secret") {}
        val port = server.start()
        try {
            val connection = get(port, "/?t=wrong")
            assertEquals(403, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a valid POST calls back with the parsed payload and then stops itself`() {
        val latch = CountDownLatch(1)
        var received: PairingPayload? = null
        val server = PairingServer("secret") { received = it; latch.countDown() }
        val port = server.start()
        val connection = post(
            port,
            "/pair",
            "t=secret&provider=OpenAI&api_key=sk-mine&model=",
        )
        assertEquals(200, connection.responseCode)
        assertTrueWithinTimeout(latch)
        assertEquals(PairingPayload("https://api.openai.com", "sk-mine", ""), received)

        // The server already stopped itself after the valid POST — a second
        // request must not find anything listening.
        Thread.sleep(300)
        var refused = false
        try {
            get(port, "/?t=secret").responseCode
        } catch (e: java.io.IOException) {
            refused = true
        }
        assertTrueWithinTimeout(latch)
        assert(refused) { "expected the port to be closed after a successful pairing" }
    }

    @Test
    fun `a POST with the wrong token is refused and never calls back`() {
        var called = false
        val server = PairingServer("secret") { called = true }
        val port = server.start()
        try {
            val connection = post(port, "/pair", "t=wrong&provider=OpenAI&api_key=sk-mine")
            assertEquals(403, connection.responseCode)
            Thread.sleep(100)
            assertNull(if (called) "called" else null)
        } finally {
            server.stop()
        }
    }

    private fun assertTrueWithinTimeout(latch: CountDownLatch) {
        assert(latch.await(2, TimeUnit.SECONDS)) { "callback never arrived" }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.PairingServerTest"`
Expected: FAIL — `Unresolved reference: PairingServer`.

- [ ] **Step 3: Write `PairingServer.kt`**

API verified against the real `io.ktor:ktor-server-cio-jvm:3.3.3` / `ktor-server-core-jvm:3.3.3` jars (decompiled with `javap`): `embeddedServer(CIO, port = 0, host = "0.0.0.0") { routing { ... } }` returns `EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>`; `.start(wait: Boolean)`; `.stop(gracePeriodMillis: Long, timeoutMillis: Long)` (blocking); `.engine.resolvedConnectors(...)` is a suspend fun returning `List<EngineConnectorConfig>`, each with `.port: Int`. `Route.get(path) { }` / `Route.post(path) { }` take a `suspend RoutingContext.() -> Unit`; `RoutingContext.call: RoutingCall`, and `RoutingCall implements ApplicationCall`, so `call.respondText(...)`, `call.receiveParameters()`, `call.request.queryParameters[...]`, `call.response.header(name, value)` all resolve on it.

```kotlin
package com.riddleboox.app.settings

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

/**
 * The HTTP server [PairActivity] opens on the LAN for exactly one phone to
 * pair with. [PairActivity] starts it in `onResume` and stops it in
 * `onPause`, so the port is only ever open while the QR is on the panel.
 *
 * [token] is checked on both routes — a value neither route ever leaks back
 * on a mismatch, since the response bodies for a bad token carry nothing.
 */
class PairingServer(private val token: String, private val onPaired: (PairingPayload) -> Unit) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Starts the server on a free port chosen by the OS and returns it. */
    fun start(): Int {
        val embedded = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
            routing {
                get("/") { serveForm(call) }
                post("/pair") { acceptSubmission(call) }
            }
        }
        embedded.start(wait = false)
        server = embedded
        return runBlocking { embedded.engine.resolvedConnectors() }.first().port
    }

    // Plain parameters rather than extension functions on the routing
    // lambda's receiver: `call` is passed in explicitly so these read as
    // ordinary suspend functions, nothing tied to ktor's DSL receiver rules.
    private suspend fun serveForm(call: ApplicationCall) {
        call.response.header("Cache-Control", "no-store")
        if (call.request.queryParameters["t"] != token) {
            call.respondText(text = "", status = HttpStatusCode.Forbidden)
            return
        }
        call.respondText(text = pairingPage(token, PROVIDERS), contentType = ContentType.Text.Html)
    }

    private suspend fun acceptSubmission(call: ApplicationCall) {
        call.response.header("Cache-Control", "no-store")
        val form = call.receiveParameters()
        if (form["t"] != token) {
            call.respondText(text = "", status = HttpStatusCode.Forbidden)
            return
        }
        when (val parsed = parsePairing(form.toPlainMap())) {
            is PairingParse.Rejected ->
                call.respondText(text = rejectedPage(parsed.reason), contentType = ContentType.Text.Html, status = HttpStatusCode.BadRequest)
            is PairingParse.Ok -> {
                call.respondText(text = confirmationPage(), contentType = ContentType.Text.Html)
                onPaired(parsed.payload)
                // Not calling stop() inline: this coroutine is running on the
                // very engine it would be asking to shut down, and stop() is
                // a blocking call — doing it here risks the engine waiting on
                // its own request handler. A short-lived daemon thread does
                // it a beat later instead, once this response has shipped.
                Thread { stop() }.apply { isDaemon = true; start() }
            }
        }
    }

    /** Blocking, with a short grace period. Safe to call more than once. */
    fun stop() {
        server?.stop(200, 500)
        server = null
    }

    private fun Parameters.toPlainMap(): Map<String, String> =
        names().associateWith { this[it].orEmpty() }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.PairingServerTest"`
Expected: PASS, 4/4. If step 1's first test's `assertEquals` (the awkward `let`-chain checking `"send to my diary"` appears) is hard to read, simplify it once green:

```kotlin
    @Test
    fun `GET with the right token serves the pairing page`() {
        val server = PairingServer("secret") {}
        val port = server.start()
        try {
            val connection = get(port, "/?t=secret")
            assertEquals(200, connection.responseCode)
            assertEquals("no-store", connection.getHeaderField("Cache-Control"))
            assert(connection.inputStream.bufferedReader().readText().contains("send to my diary"))
        } finally {
            server.stop()
        }
    }
```

Run the tests again to confirm the simplified version still passes before moving on.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/settings/PairingServer.kt app/src/test/java/com/riddleboox/app/settings/PairingServerTest.kt
git commit -m "feat(settings): embedded HTTP server for phone pairing"
```

---

### Task 7: `PairActivity.kt` — the QR screen

**Files:**
- Create: `app/src/main/java/com/riddleboox/app/settings/PairActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml:93-94` (add the activity entry after `BookPickerActivity`'s)

**Interfaces:**
- Consumes: `lanAddress(context): Inet4Address?` (Task 4), `qrBitmap(text, sizePx): Bitmap` (Task 5), `PairingServer(token, onPaired): { start(): Int; stop() }`, `PairingPayload` (Task 6), `openPaperWindow()`/`paperPage()`/`runningHead()`/`textBlock()`/`dp()` (`ui/Paper.kt`), `statusField()` (`SettingsWidgets.kt`).
- Produces: `PairActivity.intent(context): Intent`; result extras `EXTRA_BASE_URL`, `EXTRA_API_KEY`, `EXTRA_MODEL` (all `String`) on `RESULT_OK` — used by Task 8's `SettingsActivity`.

No dedicated automated test for this file: it is a thin UI shell wiring already-tested pieces together (`lanAddress`, `qrBitmap`, `PairingServer`), the same way `BookPickerActivity` (which wires `OnyxLibrary` the same way) has none either. It is exercised end-to-end by Task 8's Robolectric test (drives the result contract) and by the manual device check in Task 9.

- [ ] **Step 1: Write `PairActivity.kt`**

```kotlin
package com.riddleboox.app.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock
import java.security.SecureRandom

/**
 * "set up from phone" (in [SettingsActivity]) opens this: a QR pointing at a
 * tiny HTTP server this screen runs on the diary's own Wi-Fi ([PairingServer]),
 * so a phone can hand over an api key without anyone typing one on an e-ink
 * keyboard. The server only exists while this screen is on top — started in
 * [onResume], stopped in [onPause] — so the port this diary opens to the LAN
 * is only ever open while the writer is looking at this exact page.
 *
 * Hands the result back the same way [BookPickerActivity] does: a
 * [RESULT_OK] with the three fields as extras, never touching
 * [SettingsStore] itself — the screen that opened this one owns what the
 * choice means, and "save" still happens on that screen, the normal way.
 */
class PairActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var qrView: ImageView
    private var server: PairingServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()
        statusText = statusField()
        urlText = statusField().apply {
            typeface = Typeface.MONOSPACE
            textSize = 15f
        }
        qrView = ImageView(this)
        val column = textBlock().apply {
            addView(statusText)
            addView(
                qrView,
                LinearLayout.LayoutParams(dp(280), dp(280)).apply { topMargin = dp(24) },
            )
            addView(urlText)
        }
        setContentView(paperPage(runningHead("set up from phone"), column))
    }

    /**
     * Starts fresh every time the screen comes to the front: a new random
     * [token] each visit, so a QR shown once cannot be scanned again later
     * after the writer has left this screen and come back.
     */
    override fun onResume() {
        super.onResume()
        val address = lanAddress(this)
        if (address == null) {
            statusText.text = "connect this diary to Wi-Fi first"
            urlText.text = ""
            qrView.visibility = View.GONE
            return
        }
        val token = randomToken()
        val pairing = PairingServer(token, ::onPaired)
        server = pairing
        val port = pairing.start()
        val url = "http://${address.hostAddress}:$port/?t=$token"
        statusText.text = "waiting for your phone…"
        urlText.text = url
        qrView.visibility = View.VISIBLE
        qrView.setImageBitmap(qrBitmap(url, dp(280)))
    }

    override fun onPause() {
        super.onPause()
        server?.stop()
        server = null
    }

    /** Called on [PairingServer]'s own thread once a phone submits a valid form. */
    private fun onPaired(payload: PairingPayload) {
        runOnUiThread {
            // A backgrounded screen the OS reclaimed still runs this
            // callback — same guard as SettingsActivity.pickModel().
            if (isFinishing || isDestroyed) return@runOnUiThread
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_BASE_URL, payload.baseUrl)
                    .putExtra(EXTRA_API_KEY, payload.apiKey)
                    .putExtra(EXTRA_MODEL, payload.model),
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_BASE_URL = "com.riddleboox.app.settings.PAIR_BASE_URL"
        const val EXTRA_API_KEY = "com.riddleboox.app.settings.PAIR_API_KEY"
        const val EXTRA_MODEL = "com.riddleboox.app.settings.PAIR_MODEL"

        fun intent(context: Context): Intent = Intent(context, PairActivity::class.java)
    }
}

/** 128 bits, hex-encoded — fresh every time [PairActivity.onResume] runs. */
private fun randomToken(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 2: Register the activity in the manifest**

In `app/src/main/AndroidManifest.xml`, right after the `.BookPickerActivity` block (currently lines 90-93), add:

```xml
        <activity
            android:name=".settings.PairActivity"
            android:configChanges="keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize"
            android:exported="false" />
```

- [ ] **Step 3: Confirm it compiles**

Run: `./gradlew -q :app:compileDebugKotlin`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/settings/PairActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat(settings): PairActivity — the phone-pairing QR screen"
```

---

### Task 8: Wire "set up from phone" into `SettingsActivity`

> **Deviation actually taken during implementation:** `SettingsActivity.onCreate()`
> forces `SettingsStore`'s Keystore-backed `EncryptedSharedPreferences` open
> immediately (`store.readOrDefault(...)`), and there is no `AndroidKeyStore`
> provider under Robolectric — confirmed by actually running the test below and
> hitting `KeyStoreException: AndroidKeyStore not found` inside
> `MasterKey.Builder.build()`. No test in this repo has ever built a real
> `SettingsActivity` through Robolectric for that reason. Instead of the
> full-Activity test below, the result-Intent parsing was pulled out into a pure
> `fun pairingPayloadFrom(data: Intent): PairingPayload?` in `PairActivity.kt`
> (tested directly, no Activity involved — see `PairActivityResultTest.kt`), and
> `onActivityResult` stayed a thin, untested shell — matching how every other
> `onActivityResult` override in this codebase (`AgentsActivity`, `MainActivity`)
> already has no dedicated test. The steps below are kept as originally written
> for the historical record; do not follow them as-is.

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/settings/SettingsActivity.kt:150-155` (add the row), and add an `onActivityResult` override
- Test: `app/src/test/java/com/riddleboox/app/settings/SettingsActivityPairingTest.kt`

**Interfaces:**
- Consumes: `PairActivity.intent(context)`, `PairActivity.EXTRA_BASE_URL/.EXTRA_API_KEY/.EXTRA_MODEL` (Task 7); existing private members `chosenProvider`, `customBaseUrl`, `apiKeyField`, `chosenModel`, `showBaseUrl()`, `choose(modelId)` (all already in `SettingsActivity.kt`).

- [ ] **Step 1: Write the failing test**

Robolectric is needed here because it drives a real `SettingsActivity` through its Android lifecycle. Pin `@Config(sdk = [35])` per this repo's rule. Create `app/src/test/java/com/riddleboox/app/settings/SettingsActivityPairingTest.kt`:

```kotlin
package com.riddleboox.app.settings

import android.app.Activity
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsActivityPairingTest {

    @Test
    fun `a successful pairing result fills the base url, api key and model fields`() {
        val defaults = ReplySettings(baseUrl = "https://openrouter.ai/api", apiKey = "", model = "")
        val controller = Robolectric.buildActivity(
            SettingsActivity::class.java,
            SettingsActivity.intent(org.robolectric.RuntimeEnvironment.getApplication(), defaults),
        )
        val activity = controller.create().get()

        val result = Intent()
            .putExtra(PairActivity.EXTRA_BASE_URL, "https://api.openai.com")
            .putExtra(PairActivity.EXTRA_API_KEY, "sk-from-phone")
            .putExtra(PairActivity.EXTRA_MODEL, "gpt-5-vision")
        shadowOf(activity).receiveResultFromActivityForResult(
            PAIR_REQUEST_CODE_FOR_TEST,
            Activity.RESULT_OK,
            result,
        )

        val apiKeyField = activity.findFieldByHint("api key")
        assertEquals("sk-from-phone", apiKeyField.text.toString())
    }

    companion object {
        // Mirrors SettingsActivity's own private REQUEST_PAIR constant. If this
        // starts failing after a refactor, check that constant's value first.
        private const val PAIR_REQUEST_CODE_FOR_TEST = 2
    }
}
```

This reaches into `apiKeyField` through a helper (`findFieldByHint`) that doesn't exist yet, because `apiKeyField` is `private`. Two ways to make this checkable from the test without loosening the field's visibility for production code: either add a small `@VisibleForTesting` accessor, or assert on the rendered view tree. The simpler, lower-risk option here — no annotation, no new public surface — is to search the activity's view tree for the `EditText` and read its text; write that helper next to the test:

```kotlin
private fun Activity.findFieldByHint(@Suppress("UNUSED_PARAMETER") unused: String): android.widget.EditText {
    val root = (findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup)
    return findEditText(root) ?: error("no EditText found")
}

private fun findEditText(view: android.view.View): android.widget.EditText? {
    if (view is android.widget.EditText) return view
    if (view is android.view.ViewGroup) {
        for (i in 0 until view.childCount) {
            findEditText(view.getChildAt(i))?.let { return it }
        }
    }
    return null
}
```

(`SettingsActivity` has exactly one `EditText` on the page — the api key field, per its class doc: "a plain text field (api key), a choice picked from a list... plus everything wrapped in `EnumSettingRow`, or a self-contained toggle" — so the first `EditText` found in the tree is unambiguous.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.SettingsActivityPairingTest"`
Expected: FAIL — either a compile error (`PairActivity` unresolved is already fixed by Task 7; the real failure here is the result never being handled, so `apiKeyField.text` stays `""`, not `"sk-from-phone"`) or an assertion failure `expected:<sk-from-phone> but was:<>`.

- [ ] **Step 3: Add the row and the result handler to `SettingsActivity.kt`**

Add the import (near the other `com.riddleboox.app.reply.*`/`com.riddleboox.app.ui.*` imports, alphabetically among existing `com.riddleboox.app.*` imports):

```kotlin
import android.content.Intent
```

(Already imported at `SettingsActivity.kt:7` — skip if present; it is.)

Add the row, right after the `"api key"` field and before `"model"` (`SettingsActivity.kt:152-154`):

```kotlin
            addView(field("base url", baseUrlChooser))
            addView(field("api key", apiKeyField))
            addView(field("set up from phone", chooserField("scan a QR from your phone") { openPairing() }))
            addView(field("model", modelField))
```

Add the request constant next to `REQUEST_STORAGE_PERMISSIONS` in the companion object (`SettingsActivity.kt:566`):

```kotlin
        private const val REQUEST_STORAGE_PERMISSIONS = 1
        private const val REQUEST_PAIR = 2
```

Add `openPairing()` near `pickBaseUrl()`/`pickModel()` (after `effectiveBaseUrl()`, `SettingsActivity.kt:420`, is a natural spot):

```kotlin
    /**
     * Opens [PairActivity]. Its result — base url, api key, model — lands in
     * [onActivityResult] and is applied to this form exactly the way a
     * hand-typed edit would be: nothing reaches [SettingsStore] until "save"
     * is tapped, same as every other field here.
     */
    private fun openPairing() {
        startActivityForResult(PairActivity.intent(this), REQUEST_PAIR)
    }
```

Add `onActivityResult`, right after `onRequestPermissionsResult` (`SettingsActivity.kt:329`):

```kotlin
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PAIR || resultCode != RESULT_OK || data == null) return
        chosenProvider = providerFor(data.getStringExtra(PairActivity.EXTRA_BASE_URL).orEmpty())
        customBaseUrl = data.getStringExtra(PairActivity.EXTRA_BASE_URL).orEmpty()
        showBaseUrl()
        apiKeyField.setText(data.getStringExtra(PairActivity.EXTRA_API_KEY).orEmpty())
        val model = data.getStringExtra(PairActivity.EXTRA_MODEL).orEmpty()
        if (model.isNotEmpty()) choose(model)
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.SettingsActivityPairingTest"`
Expected: PASS.

- [ ] **Step 5: Run the whole settings test package to confirm nothing else broke**

Run: `./gradlew -q :app:testDebugUnitTest --tests "com.riddleboox.app.settings.*"`
Expected: PASS, all tests green (existing `ProvidersTest`, `ReplySettingsTest`, `PinHashTest`, `ReplyFontSizeTest`, plus everything added in Tasks 2-6 and 8).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/settings/SettingsActivity.kt app/src/test/java/com/riddleboox/app/settings/SettingsActivityPairingTest.kt
git commit -m "feat(settings): wire \"set up from phone\" into the settings form"
```

---

### Task 9: Docs, full verification, and a device check

**Files:**
- Modify: `README.md` (the `SettingsActivity` bullet list around the existing "api key che dạng password" line — see the excerpt read during brainstorming)

**Interfaces:** none — documentation and verification only.

- [ ] **Step 1: Update README**

In `README.md`, in the `SettingsActivity` section, the line listing connection-row features (containing `"api key che dạng password"`) gets one clause added, right after it:

```
  **api key che dạng password**, **"set up from phone" mở `PairActivity` — QR trỏ vào
  một HTTP server nhúng trên LAN, xem `settings/PairingServer.kt`** — **danh sách model
  hỏi thẳng
```

(i.e. insert the new clause between the existing "api key che dạng password," and "danh sách model hỏi thẳng" — keep the rest of that sentence exactly as it already reads.)

- [ ] **Step 2: Run the full unit test suite**

Run: `./gradlew -q :app:testDebugUnitTest`
Expected: PASS, 0 failing (this is the same suite the "617 tests" figure in the last release referred to, now +25 or so from this feature).

- [ ] **Step 3: Assemble the debug APK**

Run: `./gradlew -q :app:assembleDebug`
Expected: BUILD SUCCESSFUL, no errors from the new ktor-server-cio/zxing-core dependencies (R8/proguard is off for debug regardless, per Global Constraints, so no shrinker surprises expected here — only a release build would need re-checking, and this plan does not build one).

- [ ] **Step 4: Manual device check (do this on the actual BOOX, not emulated)**

1. Install the debug APK on the BOOX device.
2. Open Settings → tap "set up from phone" → confirm it shows a QR and a URL, or "connect this diary to Wi-Fi first" if the BOOX has no Wi-Fi connected (connect it, re-open the screen).
3. On a phone on the same Wi-Fi, scan the QR (or type the URL by hand). Confirm the page loads over plain HTTP, shows the provider choices, and picking "other" reveals the base-url field.
4. Fill in a real key, submit. Confirm the phone shows "Sent — look at your diary." and the BOOX's Settings screen now shows the base url/api key/model fields filled in.
5. Tap "save". Confirm the usual `/v1`-suffix warning behaves as before if applicable, and that the diary can now make a real request with the key that arrived this way.
6. Back out of the pairing screen without ever submitting from a phone (tap "‹ back"); confirm re-opening it shows a fresh QR (different token) rather than reusing the old one.

State plainly in the summary which of steps 1-6 were actually run on a device versus which are still pending — per this repo's evidence rule, "done" only follows a verification command or device check actually executed, not one merely described here.

- [ ] **Step 5: Commit the README update**

```bash
git add README.md
git commit -m "docs: document phone pairing in README"
```
