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
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.updatePadding
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Shows the ankiquest dashboard: profile, quests, achievements and the leaderboard. */
class AnkiquestActivity : AnkiActivity(R.layout.activity_ankiquest) {
    private lateinit var webView: WebView
    private var fileResult: ValueCallback<Array<Uri>>? = null
    private val choosePicture =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            fileResult?.onReceiveValue(uri?.let { arrayOf(it) })
            fileResult = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        val dashboard = Ankiquest.dashboardUrl()
        if (dashboard == null) {
            startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))
            finish()
            return
        }
        enableToolbar()
        setTitle(R.string.ankiquest_screen_title)
        applyInsets()

        val base = dashboard.toUri()
        val progress = findViewById<ProgressBar>(R.id.progress_bar)
        webView = findViewById(R.id.web_view)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    if (!acceptsPictureOrigin(view.url?.toUri(), base)) return false
                    fileResult?.onReceiveValue(null)
                    fileResult = callback
                    return try {
                        choosePicture.launch("image/*")
                        true
                    } catch (_: android.content.ActivityNotFoundException) {
                        fileResult = null
                        false
                    }
                }

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
                    if (request.url.host == base.host) return false
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
        if (savedInstanceState == null) {
            webView.loadUrl(dashboard)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onDestroy() {
        fileResult?.onReceiveValue(null)
        fileResult = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
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

    companion object {
        internal fun acceptsPictureOrigin(
            current: Uri?,
            base: Uri,
        ): Boolean {
            val expected = base.toString().toHttpUrlOrNull() ?: return false
            val actual = current?.toString()?.toHttpUrlOrNull() ?: return false
            return actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port
        }
    }
}
