package com.example.moviewebplayer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import com.example.moviewebplayer.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val foundSources = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private lateinit var adBlocker: AdBlocker
    private var adblockEnabled: Boolean = false
    private var exoPlayer: ExoPlayer? = null
    private val prefs by lazy { getSharedPreferences("mwp_prefs", MODE_PRIVATE) }
    private val sourcesAdapter = SourcesAdapter { url ->
        playUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Recycler list for sources
        binding.sourcesList.layoutManager = LinearLayoutManager(this)
        binding.sourcesList.adapter = sourcesAdapter

        // Adblocker
        adBlocker = AdBlocker(this)
        adblockEnabled = prefs.getBoolean("adblock_enabled", true)
        binding.adblockSwitch.isChecked = adblockEnabled
        binding.adblockSwitch.setOnCheckedChangeListener { _, isChecked ->
            adblockEnabled = isChecked
            prefs.edit { putBoolean("adblock_enabled", adblockEnabled) }
        }

        // ExoPlayer
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            val pv: PlayerView = binding.playerView
            pv.player = player
        }

        // WebView setup
        val wv = binding.webView
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString + " MovieWebPlayer/1.0"
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                request?.url?.toString()?.let { url ->
                    if (isVideoLike(url)) maybeAddSource(url)
                }
                if (adblockEnabled && adBlocker.shouldBlock(request?.url)) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", mapOf(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.detectBtn.isEnabled = false
                binding.pageProgress.visibility = View.VISIBLE
                binding.pageProgress.progress = 0
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.detectBtn.isEnabled = true
                installAggressiveSniffers()
                binding.pageProgress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    binding.pageProgress.visibility = View.VISIBLE
                    binding.pageProgress.progress = newProgress
                } else {
                    binding.pageProgress.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                binding.topAppBar.title = title ?: "Movie Web Player"
            }
        }
        wv.addJavascriptInterface(VideoSniffer(), "AndroidBridge")

        // Buttons
        binding.openBtn.setOnClickListener {
            val text = binding.urlInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = normalizeUrl(text)
            binding.webView.loadUrl(url)
        }

        binding.detectBtn.setOnClickListener {
            detectVideos()
        }

        binding.closePlayerBtn.setOnClickListener {
            stopPlayback()
        }

        // IME action Go on URL
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                binding.openBtn.performClick(); true
            } else false
        }

        // Swipe to refresh
        binding.swipeRefresh.setOnRefreshListener(SwipeRefreshLayout.OnRefreshListener {
            binding.webView.reload()
        })

        // Tabs: Browse and Sources
        binding.tabLayout.apply {
            addTab(newTab().setText("Browse"))
            addTab(newTab().setText("Sources"))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (tab.position == 0) {
                        binding.webContainer.visibility = View.VISIBLE
                        binding.sourcesPanel.visibility = View.GONE
                    } else {
                        binding.webContainer.visibility = View.GONE
                        binding.sourcesPanel.visibility = View.VISIBLE
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
            getTabAt(0)?.select()
        }

        // App bar menu
        binding.topAppBar.inflateMenu(R.menu.top_app_bar_menu)
        binding.topAppBar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_back -> {
                    if (binding.webView.canGoBack()) binding.webView.goBack(); true
                }
                R.id.action_refresh -> { binding.webView.reload(); true }
                R.id.action_open_external -> {
                    try {
                        val current = binding.webView.url ?: return@setOnMenuItemClickListener true
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(current))
                        startActivity(intent)
                    } catch (_: Throwable) {}
                    true
                }
                else -> false
            }
        }

        // Back press: go back in WebView if possible
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.playerOverlay.visibility == View.VISIBLE) {
                    stopPlayback()
                    return
                }
                if (binding.sourcesPanel.visibility == View.VISIBLE) {
                    binding.tabLayout.getTabAt(0)?.select()
                    return
                }
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    // Let system handle (finish activity)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun normalizeUrl(text: String): String {
        return if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
    }

    private fun detectVideos() {
        // Inject JS: full scan plus return aggregated list
        val js = buildScannerJs(returnList = true)
        binding.webView.evaluateJavascript(js, null)
    }

    private fun showSources(urls: List<String>) {
        sourcesAdapter.submit(urls)
        binding.emptyText.visibility = if (urls.isEmpty()) View.VISIBLE else View.GONE
        if (urls.isNotEmpty()) {
            binding.sourcesPanel.visibility = View.VISIBLE
            binding.tabLayout.getTabAt(1)?.select()
        }
    }

    private fun playUrl(url: String) {
        binding.sourcesPanel.visibility = View.GONE
        binding.playerOverlay.visibility = View.VISIBLE

        exoPlayer?.let { player ->
            try {
                player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                player.prepare()
                player.playWhenReady = true
            } catch (e: Exception) {
                Toast.makeText(this, "Playback error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopPlayback() {
        exoPlayer?.let { player ->
            try { player.pause() } catch (_: Throwable) {}
            try { player.stop() } catch (_: Throwable) {}
        }
        binding.playerOverlay.visibility = View.GONE
    }

    inner class VideoSniffer {
        @JavascriptInterface
        fun onVideoSources(json: String) {
            runOnUiThread {
                try {
                    val arr = JSONArray(json)
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val u = arr.getString(i)
                        if (foundSources.add(u)) {
                            // keep in set as well
                        }
                        list.add(u)
                    }
                    showSources(list)
                } catch (e: Exception) {
                    showSources(emptyList())
                }
            }
        }

        @JavascriptInterface
        fun onAggressiveHit(url: String) {
            maybeAddSource(url)
        }
    }

    private fun maybeAddSource(url: String) {
        if (!isVideoLike(url)) return
        if (url.startsWith("blob:") || url.startsWith("data:")) return
        if (foundSources.add(url)) {
            runOnUiThread {
                showSources(foundSources.toList())
            }
        }
    }

    private fun isVideoLike(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.contains(".mpd") ||
                u.contains(".mp4") || u.contains(".webm") ||
                u.contains(".m4v") || u.contains(".mov") ||
                u.contains(".m3u") || u.contains(".ogg")
    }

    private fun installAggressiveSniffers() {
        val js = buildScannerJs(returnList = false)
        binding.webView.evaluateJavascript(js, null)
    }

    private fun buildScannerJs(returnList: Boolean): String {
        // language=JavaScript, built as a Kotlin string
        val core = """
            (function(){
              function norm(u){
                if(!u) return null;
                try {
                  if (u.startsWith('blob:') || u.startsWith('data:')) return null;
                  if (!/^https?:/i.test(u)) { u = new URL(u, document.location.href).toString(); }
                  return u;
                } catch (e) { return null; }
              }
              function looksVideo(u){
                if(!u) return false;
                var l = u.toLowerCase();
                return l.includes('.m3u8') || l.includes('.mpd') || l.includes('.mp4') || l.includes('.webm') || l.includes('.m4v') || l.includes('.mov') || l.includes('.m3u') || l.includes('.ogg');
              }
              var set = new Set();
              function push(u){
                var n = norm(u);
                if(!n) return;
                if(!looksVideo(n)) return;
                if (!set.has(n)) {
                  set.add(n);
                  try { AndroidBridge.onAggressiveHit(n); } catch(e){}
                }
              }

              // 1) Scan <video> and <source>
              try {
                var vids = document.getElementsByTagName('video');
                for(var i=0;i<vids.length;i++){
                  var v=vids[i];
                  push(v.currentSrc); push(v.src);
                  var ss=v.getElementsByTagName('source');
                  for(var j=0;j<ss.length;j++){
                    push(ss[j].src);
                    push(ss[j].getAttribute('data-src'));
                  }
                }
              } catch(e){}

              // 2) Scan links
              try {
                var as = document.getElementsByTagName('a');
                for(var i=0;i<as.length;i++) push(as[i].href);
              } catch(e){}

              // 3) Monkey-patch fetch and XHR
              try {
                if (!window.__mwpHooked) {
                  window.__mwpHooked = true;
                  var origFetch = window.fetch;
                  if (origFetch) {
                    window.fetch = function(input, init){
                      try {
                        var url = (typeof input === 'string') ? input : (input && input.url);
                        push(url);
                      } catch(e){}
                      return origFetch.apply(this, arguments).then(function(resp){
                        try {
                          var url = resp && resp.url; push(url);
                          var ct = resp && resp.headers && resp.headers.get && resp.headers.get('content-type');
                          if (ct && /video|mpegurl|dash|application\/vnd\.apple\.mpegurl/i.test(ct)) push(resp.url);
                        } catch(e){}
                        return resp;
                      });
                    }
                  }
                  var X= window.XMLHttpRequest; if (X) {
                    var open = X.prototype.open;
                    X.prototype.open = function(method, url){ push(url); return open.apply(this, arguments); };
                  }

                  // Hook media src assignment
                  try {
                    var H = HTMLMediaElement && HTMLMediaElement.prototype;
                    if (H) {
                      var desc = Object.getOwnPropertyDescriptor(H, 'src');
                      if (desc && desc.set) {
                        Object.defineProperty(H, 'src', {
                          set: function(v){ push(v); return desc.set.call(this, v); },
                          get: function(){ return desc.get.call(this); }
                        });
                      }
                      var setAttr = Element.prototype.setAttribute;
                      Element.prototype.setAttribute = function(name, value){
                        try { if (name && name.toLowerCase()==='src') push(value); } catch(e){}
                        return setAttr.call(this, name, value);
                      }
                    }
                  } catch(e){}
                }
              } catch(e){}

              return Array.from(set);
            })();
        """.trimIndent()

        return if (returnList) {
            // Send entire list back for a one-shot scan
            """
            (function(){
              try {
                var list = $core
                AndroidBridge.onVideoSources(JSON.stringify(list));
              } catch(e) { AndroidBridge.onVideoSources(JSON.stringify([])); }
            })();
            """.trimIndent().replace("$core", core)
        } else {
            // Install hooks and push hits individually
            core + ";void 0;"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}

// Recycler adapter for sources
class SourcesAdapter(private val onClick: (String) -> Unit) : RecyclerView.Adapter<SourcesViewHolder>() {
    private val items = mutableListOf<String>()
    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SourcesViewHolder {
        val tv = android.widget.TextView(parent.context).apply {
            setPadding(16, 16, 16, 16)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }
        return SourcesViewHolder(tv, onClick)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: SourcesViewHolder, position: Int) = holder.bind(items[position])
}

class SourcesViewHolder(private val tv: android.widget.TextView, private val onClick: (String) -> Unit) : RecyclerView.ViewHolder(tv) {
    fun bind(url: String) {
        tv.text = url
        tv.setOnClickListener { onClick(url) }
        tv.setOnLongClickListener {
            try {
                val cm = tv.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Video URL", url))
                android.widget.Toast.makeText(tv.context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
            true
        }
    }
}
