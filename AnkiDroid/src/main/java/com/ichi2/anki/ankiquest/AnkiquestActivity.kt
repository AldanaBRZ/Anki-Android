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
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity

/** Shows the ankiquest dashboard: profile, quests, achievements and the leaderboard. */
class AnkiquestActivity : AnkiActivity(R.layout.activity_ankiquest) {
    private lateinit var webView: WebView
    private var session: AnkiquestWebSession? = null
    private var refreshAfterSettings = false
    private var clearHistoryAfterLoad = false
    private var pendingDashboard: String? = null
    private var browserBack: OnBackPressedCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        session = Ankiquest.webSession()
        val dashboard = initialUrl()
        if (dashboard == null) {
            startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))
            finish()
            return
        }
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
        browserBack = back
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (session?.allows(request.url.toString()) == true) return false
                    openUrl(request.url)
                    return true
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    if (pendingDashboard != null) {
                        // A changed player differs only by the fragment, which otherwise retains the old document and credentials.
                        if (url == "about:blank" && view.url == url) {
                            val destination = checkNotNull(pendingDashboard)
                            pendingDashboard = null
                            view.loadUrl(destination)
                        }
                        return
                    }
                    val current = Ankiquest.webSession()
                    if (current != session || current?.allows(view.url) != true || !current.allows(url)) return
                    if (clearHistoryAfterLoad) {
                        if (url != view.url) return
                        view.clearHistory()
                        clearHistoryAfterLoad = false
                        back.isEnabled = view.canGoBack()
                    }
                    current.script(url)?.let { view.evaluateJavascript(it, null) }
                }

                override fun doUpdateVisitedHistory(
                    view: WebView,
                    url: String?,
                    isReload: Boolean,
                ) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    back.isEnabled = pendingDashboard == null && !clearHistoryAfterLoad && view.canGoBack()
                }
            }
        if (savedInstanceState == null || savedInstanceState.getString(DASHBOARD_STATE) != session?.dashboard) {
            webView.loadUrl(dashboard)
        } else {
            if (webView.restoreState(savedInstanceState) == null) webView.loadUrl(dashboard)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ankiquest, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.ankiquest_settings) return super.onOptionsItemSelected(item)
        refreshAfterSettings = true
        startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))
        return true
    }

    override fun onResume() {
        super.onResume()
        if (!::webView.isInitialized) return
        val current = Ankiquest.webSession()
        if (current == null) {
            session = null
            pendingDashboard = null
            webView.loadUrl("about:blank")
            finish()
            return
        }
        if (current != session) {
            session = current
            clearHistoryAfterLoad = true
            browserBack?.isEnabled = false
            pendingDashboard = checkNotNull(initialUrl())
            webView.stopLoading()
            webView.loadUrl("about:blank")
        } else if (refreshAfterSettings) {
            webView.reload()
        }
        refreshAfterSettings = false
    }

    private fun initialUrl(): String? = if (intent.getBooleanExtra(COMMUNITY_REMINDERS, false)) session?.community else session?.dashboard

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(DASHBOARD_STATE, session?.dashboard)
        if (::webView.isInitialized && pendingDashboard == null && !clearHistoryAfterLoad) webView.saveState(outState)
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

    companion object {
        const val COMMUNITY_REMINDERS = "ankiquestCommunityReminders"
        private const val DASHBOARD_STATE = "ankiquestDashboard"
    }
}
