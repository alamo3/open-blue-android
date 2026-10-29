package io.github.alamo3.openblue.ui

import android.annotation.SuppressLint
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
 */
@Composable
fun StoreScreen(vm: MainViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.openStore() }
    DisposableEffect(Unit) { onDispose { vm.closeStore() } }
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    val page = vm.storePage

    BackHandler(enabled = canGoBack) { web?.goBack() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Bluelink Store", onBack)
        if (vm.storeLoading || (page?.url != null && progress < 100)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
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
                StoreWeb(
                    page, onClose = onBack,
                    onCreated = { web = it },
                    onProgress = { progress = it },
                    onHistory = { canGoBack = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

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
    page: MainViewModel.StorePage,
    onClose: () -> Unit,
    onCreated: (WebView) -> Unit,
    onProgress: (Int) -> Unit,
    onHistory: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val url = page.url ?: return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.userAgentString = settings.userAgentString + UA_SUFFIX
                // The page's "close" button calls CloseHandler.closeWindow(), as in the official app.
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun closeWindow() = post { onClose() }
                }, "CloseHandler")
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        if (u.scheme == "https" || u.scheme == "http") return false
                        // tel:, mailto:, intent: and the like go to whatever handles them.
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                        }
                        return true
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
                        onHistory(view.canGoBack())
                }
                onCreated(this)
            }
        },
        update = { view ->
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
        onRelease = { it.destroy() },
    )
}

/** The cookie the official app sets: t=<token>;Max-Age=<expiresIn>;Domain=<host>; Path=/ on scheme://host. */
private fun setStoreCookie(cookies: CookieManager, url: String, token: String, expiresIn: String?) {
    val u = runCatching { Uri.parse(url) }.getOrNull() ?: return
    val host = u.host ?: return
    val age = expiresIn?.takeIf { it.isNotBlank() }?.let { ";Max-Age=$it" }.orEmpty()
    cookies.setCookie("${u.scheme ?: "https"}://$host", "$COOKIE=$token$age;Domain=$host; Path=/")
}
