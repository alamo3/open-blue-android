package io.github.alamo3.openblue.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.os.Message
import android.view.View
import android.webkit.URLUtil
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.alamo3.openblue.MainViewModel

/**
 * The Bluelink Store (Hyundai's "features on demand"), the same web page the official app shows,
 * opened with the same cookie and user agent. The official app only offers it when the car's
 * feature list includes "Fod"; this offers it for every car and lets Hyundai's page decide.
 *
 * Like the official app, a page that opens a new window ("My page", checkout) gets its own layer
 * on top, closed again by the page itself (window.close(), CloseHandler.closeWindow() or a
 * hmg-commerce://close link); closing one with window.close() tells the page underneath
 * (notifyChildWindowClosed()), which is how the store refreshes after a purchase.
 */
@Composable
fun StoreScreen(vm: MainViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.openStore() }
    DisposableEffect(Unit) { onDispose { vm.closeStore() } }
    val page = vm.storePage
    val main = remember { Layer(0, null) }
    val children = remember { mutableStateListOf<Layer>() }
    var nextId by remember { mutableIntStateOf(1) }
    val log = remember { mutableStateListOf<String>() }
    val note: (String) -> Unit = { line ->
        if (log.size >= 200) log.removeAt(0)
        log.add(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + line)
    }
    val top = children.lastOrNull() ?: main

    fun close(layer: Layer, ok: Boolean) {
        if (layer === main) { onBack(); return }
        val i = children.indexOf(layer)
        if (i < 0) return
        children.removeAt(i)
        val below = if (i == 0) main else children[i - 1]
        if (ok) below.web?.evaluateJavascript("javascript:notifyChildWindowClosed()", null)
    }

    BackHandler(enabled = children.isNotEmpty() || top.canGoBack) {
        if (top.canGoBack) top.web?.goBack() else close(top, ok = false)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Bluelink Store", onBack)
        if (vm.storeLoading || (page?.url != null && top.progress < 100)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (vm.developerMode) StoreCheck(vm, log)
        when {
            vm.storeError != null -> Explain(
                listOfNotNull(
                    "Bluelink wouldn't open the store for this car: ${vm.storeError}",
                    if (!vm.caps.listsStore) "Hyundai doesn't list the store for this car, which may be why." else null,
                ),
            ) { OutlinedButton(onClick = { vm.openStore() }) { Text("Try again") } }

            page != null && page.url == null -> NoAddress(vm)

            page?.url != null -> {
                if (!vm.caps.listsStore) {
                    Text(
                        "Hyundai doesn't list the store for this car; opened anyway. What you can buy is still up to Hyundai.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val all = listOf(main) + children.toList()
                    all.forEach { layer ->
                        key(layer.id) {
                            StoreWeb(
                                layer = layer,
                                url = layer.url ?: page.url.orEmpty(),
                                page = page,
                                visible = layer === top,
                                debugging = vm.developerMode,
                                onLog = note,
                                onClose = { ok -> close(layer, ok) },
                                onOpenChild = { url -> children.add(Layer(nextId++, url)) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One page of the store: the first, or one the page opened in a new window. */
private class Layer(val id: Int, val url: String?) {
    var web: WebView? = null
    var canGoBack by mutableStateOf(false)
    var progress by mutableIntStateOf(100)
}

/** Developer mode: what Bluelink says about the car that decides what the store offers. */
@Composable
private fun StoreCheck(vm: MainViewModel, log: List<String>) {
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(open, vm.storePage?.url) { if (open) vm.loadStoreCheck() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide store check" else "Store check (developer)") }
            if (open) {
                TextButton(onClick = {
                    val rows = vm.storeCheck.orEmpty().joinToString("\n") { (k, v) -> "$k: $v" }
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(rows + "\n\nPage log:\n" + log.joinToString("\n").ifEmpty { "(nothing yet)" }))
                }) { Text("Copy") }
            }
        }
        if (open) {
            val rows = vm.storeCheck
            Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                if (rows == null) Text("Loading…", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Column {
                        rows?.forEach { (k, v) ->
                            Text(k, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted, modifier = Modifier.padding(top = 6.dp))
                            Text(v, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Page log", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted, modifier = Modifier.padding(top = 10.dp))
                        Text(
                            "Failed requests and page errors, without addresses' query strings. Open the store, use My page, then copy.",
                            style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted,
                        )
                        if (log.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                        log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
    }
}

/** Keeps a logged address to scheme, host and path: queries can carry tokens. */
private fun plain(url: String?): String = url?.substringBefore('?')?.substringBefore('#').orEmpty()

/** Hides long token-like runs in a message. */
private fun masked(text: String): String = text.replace(Regex("[A-Za-z0-9_\\-.=+/]{40,}"), "…").take(300)

@Composable
private fun Explain(lines: List<String>, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
        lines.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
        }
        action()
    }
}

/** Bluelink gave a token but no store address: say so, and let the user try one. */
@Composable
private fun NoAddress(vm: MainViewModel) {
    var url by rememberSaveable { mutableStateOf("") }
    Explain(
        listOf(
            "Bluelink handed out a store token for this car, but no store address. The official app takes the " +
                "address from the car's details, and Hyundai only fills it in for cars it sells features for.",
            "If you know the store's address (from Hyundai's app in another region, say), you can open it here " +
                "with this car's token.",
        ),
    ) {
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Store address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.openStoreAt(url) }, enabled = url.isNotBlank()) { Text("Open") }
        Spacer(Modifier.height(20.dp))
        Text("Or try", style = MaterialTheme.typography.titleSmall)
        GUESSES.forEach { (label, address) ->
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.openStoreAt(address) }, modifier = Modifier.fillMaxWidth()) {
                Text(label)
            }
        }
    }
}

/**
 * Hyundai's store lives at commerce.hyundai.com/<country>/<language>/commerce/fod (the US one is
 * public). Canada's follows the same pattern if it exists; the US one shows what's sold there.
 */
private val GUESSES = listOf(
    "Canada, English (guessed address)" to "https://commerce.hyundai.com/ca/en/commerce/fod",
    "Canada, French (guessed address)" to "https://commerce.hyundai.com/ca/fr/commerce/fod",
    "US store" to "https://commerce.hyundai.com/us/en/commerce/fod",
)

/** Cookie name the store reads the token from (production; staging uses t-stg). */
private const val COOKIE = "t"

/** What the official app adds to the WebView's user agent; the store page looks for it. */
private const val UA_SUFFIX = "_CCS_APP_AOS"

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun StoreWeb(
    layer: Layer,
    url: String,
    page: MainViewModel.StorePage,
    visible: Boolean,
    debugging: Boolean,
    onClose: (ok: Boolean) -> Unit,
    onOpenChild: (String) -> Unit,
    onLog: (String) -> Unit,
    modifier: Modifier,
) {
    val logNow by rememberUpdatedState(onLog)
    val onCloseNow by rememberUpdatedState(onClose)
    val onOpenNow by rememberUpdatedState(onOpenChild)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.userAgentString = settings.userAgentString + UA_SUFFIX
                if (debugging) WebView.setWebContentsDebuggingEnabled(true)
                // The page's "close" button calls CloseHandler.closeWindow(), as in the official app.
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun closeWindow() = post { onCloseNow(false) }
                }, "CloseHandler")
                setDownloadListener { dl, userAgent, disposition, mime, _ -> download(ctx, dl, userAgent, disposition, mime) }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) { layer.progress = newProgress }

                    // The page opened a window. Like the official app, take the address it
                    // wanted and show it as a layer of our own; the throwaway view that carries
                    // the request is never shown.
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                        val msg = resultMsg ?: return false
                        val transport = msg.obj as? WebView.WebViewTransport ?: return false
                        val carrier = WebView(view.context)
                        carrier.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                logNow("new window ${plain(u)}")
                                if (u.startsWith(CLOSE_LINK)) onCloseNow(false) else onOpenNow(u)
                                v.post { v.destroy() }
                                return true
                            }
                        }
                        transport.webView = carrier
                        msg.sendToTarget()
                        return true
                    }

                    override fun onCloseWindow(window: WebView) { onCloseNow(true) }

                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                            logNow("console ${m.messageLevel().name.lowercase()}: ${masked(m.message())} (${plain(m.sourceId()).substringAfterLast('/')}:${m.lineNumber()})")
                        }
                        return false
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        val text = u.toString()
                        if (text.startsWith(CLOSE_LINK)) { onCloseNow(false); return true }
                        if (u.scheme == "https" || u.scheme == "http") {
                            // Links the store marks to open in the phone's own browser.
                            val marker = EXTERNAL_MARKERS.firstOrNull { text.contains(it) } ?: return false
                            openExternally(ctx, Uri.parse(text.replace(marker, "")))
                            return true
                        }
                        // tel:, mailto:, intent: and the like go to whatever handles them.
                        openExternally(ctx, u)
                        return true
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        layer.canGoBack = view.canGoBack()
                        logNow("page ${plain(url)}")
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                        logNow("HTTP ${response.statusCode} ${request.method} ${plain(request.url.toString())}" + if (request.isForMainFrame) " (page)" else "")
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        logNow("error ${error.description} ${plain(request.url.toString())}")
                    }
                }
                layer.web = this
            }
        },
        update = { view ->
            view.visibility = if (visible) View.VISIBLE else View.GONE
            if (view.tag != url) {
                view.tag = url
                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(view, true)
                (listOf(url) + page.otherHosts).forEach { setStoreCookie(cookies, it, page.token, page.expiresIn) }
                cookies.flush()
                view.loadUrl(url)
            }
        },
        onRelease = { if (layer.web === it) layer.web = null; it.destroy() },
    )
}

private const val CLOSE_LINK = "hmg-commerce://close"

/** Store links carrying one of these open in the phone's browser, without the marker. */
private val EXTERNAL_MARKERS = listOf("&HBrowser=_Extetrnal", "&ICPBrowser=_Extetrnal")

private fun openExternally(ctx: Context, uri: Uri) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/** Hands an https download (an invoice PDF, say) to the system downloader, with the page's cookies. */
private fun download(ctx: Context, url: String, userAgent: String?, disposition: String?, mime: String?) {
    if (!url.startsWith("https://")) {
        Toast.makeText(ctx, "Open Blue can't save this kind of download yet.", Toast.LENGTH_LONG).show()
        return
    }
    try {
        val name = URLUtil.guessFileName(url, disposition, mime)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setMimeType(mime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }
        userAgent?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    } catch (e: RuntimeException) {
        Toast.makeText(ctx, "Couldn't start the download.", Toast.LENGTH_LONG).show()
    }
}

/** The cookie the official app sets: t=<token>;Max-Age=<expiresIn>;Domain=<host>; Path=/ on scheme://host. */
private fun setStoreCookie(cookies: CookieManager, url: String, token: String, expiresIn: String?) {
    val u = runCatching { Uri.parse(url) }.getOrNull() ?: return
    val host = u.host ?: return
    val age = expiresIn?.takeIf { it.isNotBlank() }?.let { ";Max-Age=$it" }.orEmpty()
    cookies.setCookie("${u.scheme ?: "https"}://$host", "$COOKIE=$token$age;Domain=$host; Path=/")
}
