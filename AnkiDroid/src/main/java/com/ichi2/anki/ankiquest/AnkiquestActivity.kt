/*
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/** CookieManager is process-wide: no superseded screen may clear or install after its successor. */
internal class AnkiquestBrowserSessionBridge {
    private val mutex = Mutex()

    suspend fun prepare(
        current: () -> Boolean,
        clear: suspend () -> Boolean,
        bootstrap: suspend () -> String?,
        install: suspend (String) -> Boolean,
    ): Boolean =
        mutex.withLock {
            if (!current()) return@withLock false
            // A CookieManager mutation cannot be cancelled once enqueued. Wait for its
            // acknowledgement before releasing the lock, even when the screen closes.
            if (!withContext(NonCancellable) { clear() }) return@withLock false
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock false
            val cookie = bootstrap()
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock false
            if (cookie != null && !withContext(NonCancellable) { install(cookie) }) return@withLock false
            currentCoroutineContext().ensureActive()
            current()
        }
}

/** Shows the ankiquest dashboard: profile, quests, achievements and the leaderboard. */
class AnkiquestActivity : AnkiActivity(R.layout.activity_ankiquest) {
    private lateinit var webView: WebView
    private var account = ""

    companion object {
        const val EXTRA_PATH = "ankiquest.path"
        private val sessionBridge = AnkiquestBrowserSessionBridge()

        /** Only known, same-server read surfaces can be opened by a native shortcut. */
        internal fun destinationUrl(
            dashboard: String,
            path: String?,
        ): String {
            val base = dashboard.toHttpUrlOrNull() ?: return dashboard
            val route = path.orEmpty().substringBefore('#')
            val allowed = setOf("/", "/community", "/records", "/hour", "/day", "/week", "/month", "/year", "/all")
            if (path == null || route !in allowed) {
                return base
                    .newBuilder()
                    .setQueryParameter("embed", "1")
                    .build()
                    .toString()
            }
            return base
                .newBuilder()
                .encodedPath(base.encodedPath + route.removePrefix("/"))
                .encodedFragment(if ('#' in path) path.substringAfter('#') else null)
                .setQueryParameter("embed", "1")
                .build()
                .toString()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        val dashboard = Ankiquest.dashboardUrl()
        val base = dashboard?.toHttpUrlOrNull()
        if (dashboard == null || base == null) {
            startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))
            finish()
            return
        }
        account = AnkiquestNavigation.accountFingerprint()
        val destination = destinationUrl(dashboard, intent.getStringExtra(EXTRA_PATH))
        enableToolbar()
        setTitle(R.string.ankiquest_screen_title)
        applyInsets()

        val progress = findViewById<ProgressBar>(R.id.progress_bar)
        webView = findViewById(R.id.web_view)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(
                    view: WebView,
                    newProgress: Int,
                ) {
                    progress.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                }
            }
        val back =
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    webView.goBack()
                }
            }
        onBackPressedDispatcher.addCallback(this, back)
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val target = request.url.toString().toHttpUrlOrNull()
                    if (target != null && target.scheme == base.scheme && target.host == base.host && target.port == base.port) return false
                    openUrl(request.url)
                    return true
                }

                override fun doUpdateVisitedHistory(
                    view: WebView,
                    url: String?,
                    isReload: Boolean,
                ) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    back.isEnabled = view.canGoBack()
                }
            }
        lifecycleScope.launch {
            val ready =
                sessionBridge.prepare(
                    current = { account == AnkiquestNavigation.accountFingerprint() },
                    clear = { setSessionCookie(dashboard, "ankiquest_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax") },
                    bootstrap = {
                        try {
                            Ankiquest.dashboardSession(dashboard)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A public server or the website's sign-in screen can still be opened.
                            Timber.w(e, "ankiquest browser session failed")
                            null
                        }
                    },
                    install = { cookie -> setSessionCookie(dashboard, cookie) },
                )
            if (account != AnkiquestNavigation.accountFingerprint()) {
                finish()
                return@launch
            }
            if (!ready) {
                progress.visibility = View.GONE
                webView.loadData("<p>Unable to prepare your connection. Close this screen and try again.</p>", "text/html", "UTF-8")
                return@launch
            }
            if (savedInstanceState == null || savedInstanceState.getString("ankiquest.account") != account ||
                savedInstanceState.getString("ankiquest.destination") != destination ||
                webView.restoreState(savedInstanceState) == null
            ) {
                webView.loadUrl(destination)
            }
        }
    }

    private suspend fun setSessionCookie(
        url: String,
        cookie: String,
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            CookieManager.getInstance().setCookie(url, cookie) { accepted ->
                if (continuation.isActive) continuation.resume(accepted)
            }
        }

    override fun onResume() {
        super.onResume()
        if (account.isNotEmpty() && account != AnkiquestNavigation.accountFingerprint()) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("ankiquest.account", account)
        outState.putString("ankiquest.destination", Ankiquest.dashboardUrl()?.let { destinationUrl(it, intent.getStringExtra(EXTRA_PATH)) })
        if (::webView.isInitialized) webView.saveState(outState)
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(systemBars() or displayCutout())
            findViewById<View>(R.id.toolbar_container).updatePadding(left = bars.left, top = bars.top, right = bars.right)
            findViewById<View>(R.id.content).updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
    }
}
