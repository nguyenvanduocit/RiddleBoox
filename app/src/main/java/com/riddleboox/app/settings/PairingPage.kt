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
