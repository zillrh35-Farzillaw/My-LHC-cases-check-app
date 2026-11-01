package pk.advocate.casediary.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import pk.advocate.casediary.databinding.ActivityBrowserBinding
import pk.advocate.casediary.util.Notifications
import pk.advocate.casediary.util.Prefs
import pk.advocate.casediary.work.CauseListScraper

/**
 * A browser with a "Scan page" button.
 *
 * The automatic checker can only read pages that answer a plain GET. Several
 * LHC lists are behind a form (pick a date, pick a bench, submit). Here you
 * drive the site yourself and then hand whatever is on screen to the same
 * matcher, which sidesteps the problem entirely.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var b: ActivityBrowserBinding
    private lateinit var prefs: Prefs
    private lateinit var loading: LoadingOverlay
    private var hasAutoScanned = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        loading = LoadingOverlay(b.loadingOverlay)

        b.toolbar.setNavigationOnClickListener { finish() }

        with(b.web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        b.web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                b.toolbar.subtitle = url?.take(60) ?: ""
                if (prefs.autoScanInBrowser && !hasAutoScanned) {
                    hasAutoScanned = true
                    // Give client-side tables a moment to render.
                    b.web.postDelayed({ scan(silentIfEmpty = true) }, 1200)
                }
            }
        }

        b.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                b.progress.progress = newProgress
                b.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        b.btnScan.setOnClickListener { scan(silentIfEmpty = false) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.web.canGoBack()) {
                    hasAutoScanned = false
                    b.web.goBack()
                } else {
                    finish()
                }
            }
        })

        val start = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL
        b.web.loadUrl(start)
    }

    private fun scan(silentIfEmpty: Boolean) {
        val url = b.web.url ?: DEFAULT_URL
        val title = b.web.title ?: "Cause list"

        loading.show("Reading this page…")
        b.web.evaluateJavascript("(function(){return document.body.innerText;})();") { raw ->
            val text = decodeJsString(raw)
            if (text.isBlank()) {
                loading.hide()
                if (!silentIfEmpty) {
                    Snackbar.make(b.root, "Nothing readable on this page yet", Snackbar.LENGTH_SHORT)
                        .show()
                }
                return@evaluateJavascript
            }

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    CauseListScraper(applicationContext).scanText(text, title.take(60), url)
                }
                loading.hide()

                if (result.newHits > 0) {
                    Notifications.notifyFixtures(applicationContext, result.newHits, result.lines)
                    MaterialAlertDialogBuilder(this@BrowserActivity)
                        .setTitle("${result.newHits} new match(es)")
                        .setMessage(result.lines.joinToString("\n").take(1500))
                        .setPositiveButton("OK", null)
                        .show()
                } else if (!silentIfEmpty) {
                    val msg = if (result.totalHits > 0) {
                        "Nothing new — ${result.totalHits} match(es) already recorded"
                    } else {
                        "No matches in ${result.rowsScanned} lines on this page"
                    }
                    Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    /** evaluateJavascript hands back a JSON-encoded string. */
    private fun decodeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            val parsed = JSONTokener(raw).nextValue()
            if (parsed is String) parsed else raw
        } catch (_: Exception) {
            raw.trim('"').replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
        }
    }

    override fun onDestroy() {
        loading.cancel()
        b.web.stopLoading()
        b.web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val DEFAULT_URL =
            "https://data.lhc.gov.pk/index.php/case_management/urgent_cause_list_final"
    }
}
