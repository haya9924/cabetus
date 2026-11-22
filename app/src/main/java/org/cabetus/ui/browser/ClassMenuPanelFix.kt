package org.cabetus.ui.browser

import android.webkit.WebView

/**
 * CLASS のスマートフォン表示で `#menuPanel` の高さが 0 になる実機不具合の最小補正。
 *
 * - `https://class.admin.tus.ac.jp`（完全一致ホスト）のページにだけ適用する。
 * - 対象要素の inline style（height / min-height / max-height）以外は変更しない。
 * - 固定文字列のスクリプトを [WebView.evaluateJavascript] で実行するだけで、
 *   `addJavascriptInterface` による Android 側ブリッジは公開しない。
 * - 画面回転・ソフトキーボード等の resize、同一 document 内の再描画に追従する。
 *   通常のページ遷移は [InAppBrowserScreen] の `onPageCommitVisible` / `onPageFinished` で再適用する。
 */
object ClassMenuPanelFix {

    fun isTarget(url: String?): Boolean = BrowserUrlPolicy.isClassConsole(url)

    fun applyIfNeeded(webView: WebView, url: String?) {
        // コールバック URL だけでなく、現在の main frame が完全一致ホストであることも確認する。
        if (!isTarget(url) || !isTarget(webView.url)) return
        webView.evaluateJavascript(SCRIPT, null)
    }

    /**
     * #menuPanel の高さを現在の viewport (window.innerHeight) に合わせるだけのスクリプト。
     * 入力欄・Cookie・ストレージ・ネットワークには触れない。
     */
    internal val SCRIPT: String = """
        (function () {
          'use strict';
          var PANEL_ID = 'menuPanel';
          var INSTALLED_FLAG = '__cabetusClassMenuPanelFixInstalled';

          function apply() {
            var panel = document.getElementById(PANEL_ID);
            if (!panel) return;
            var viewportHeight = window.innerHeight;
            if (!viewportHeight || viewportHeight <= 0) return;
            var height = viewportHeight + 'px';
            panel.style.setProperty('height', height, 'important');
            panel.style.setProperty('min-height', height, 'important');
            panel.style.setProperty('max-height', height, 'important');
          }

          if (!window[INSTALLED_FLAG]) {
            window[INSTALLED_FLAG] = true;

            var pending = false;
            function scheduleApply() {
              if (pending) return;
              pending = true;
              var run = function () {
                pending = false;
                apply();
              };
              if (window.requestAnimationFrame) {
                window.requestAnimationFrame(run);
              } else {
                window.setTimeout(run, 16);
              }
            }

            window.addEventListener('resize', scheduleApply, { passive: true });
            window.addEventListener('orientationchange', scheduleApply, { passive: true });
            window.addEventListener('pageshow', scheduleApply, { passive: true });
            if (window.visualViewport) {
              window.visualViewport.addEventListener('resize', scheduleApply, { passive: true });
            }

            var root = document.documentElement;
            if (root && window.MutationObserver) {
              // SPA 的な画面更新で #menuPanel が作り直された場合にも追従する。
              new MutationObserver(scheduleApply).observe(root, {
                childList: true,
                subtree: true
              });
            }
          }

          apply();
        })();
    """.trimIndent()
}
