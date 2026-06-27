package com.homeos.app

/**
 * 向 WebView 注入固定宽度的 viewport（事件驱动，无 RAF 循环、无 transform 二次缩放）。
 * 实际的等比铺满由 WebSettings 的 useWideViewPort + loadWithOverviewMode 完成，
 * 这里只负责保证页面以「设计宽度」布局，并防止站点脚本篡改 viewport。
 */
object ViewportInjector {

    /** 页面导航开始时重置注入标记，允许新页面重新安装守护逻辑。 */
    fun buildResetScript(): String = """
        (function(){
          window.__homeosViewportInjected = false;
          if (typeof window.__homeosViewportTeardown === 'function') {
            window.__homeosViewportTeardown();
            window.__homeosViewportTeardown = null;
          }
        })();
    """.trimIndent()

    /**
     * 注入并守护 viewport 宽度。observer 带防抖，避免 SPA 频繁改动 head 时高频回调。
     */
    fun buildInstallScript(designWidth: Int): String {
        return """
            (function(){
              if (window.__homeosViewportInjected) return;
              window.__homeosViewportInjected = true;

              var W = $designWidth;

              function applyViewport() {
                var m = document.querySelector('meta[name=viewport]');
                if (!m) {
                  m = document.createElement('meta');
                  m.name = 'viewport';
                  document.head.appendChild(m);
                }
                var c = 'width=' + W + ', user-scalable=no';
                if (m.getAttribute('content') !== c) {
                  m.setAttribute('content', c);
                }
                if (!document.getElementById('homeos-touch-fix')) {
                  var s = document.createElement('style');
                  s.id = 'homeos-touch-fix';
                  s.textContent = 'html,body{-webkit-overflow-scrolling:touch;touch-action:pan-x pan-y;}';
                  document.head.appendChild(s);
                }
              }

              applyViewport();

              if (window.__homeosViewportTeardown) return;

              var head = document.head || document.getElementsByTagName('head')[0];
              var observer = null;
              var debounceTimer = null;
              function scheduleApply() {
                if (debounceTimer) return;
                debounceTimer = setTimeout(function(){
                  debounceTimer = null;
                  applyViewport();
                }, 200);
              }

              if (head && window.MutationObserver) {
                observer = new MutationObserver(function(mutations) {
                  for (var i = 0; i < mutations.length; i++) {
                    var t = mutations[i].target;
                    // 仅在 viewport meta 节点被改动时才重新应用，减少无谓回调。
                    if (t && t.nodeName === 'META' && t.getAttribute && t.getAttribute('name') === 'viewport') {
                      scheduleApply();
                      return;
                    }
                    if (mutations[i].type === 'childList') {
                      scheduleApply();
                      return;
                    }
                  }
                });
                observer.observe(head, {
                  childList: true,
                  subtree: false,
                  attributes: true,
                  attributeFilter: ['content', 'name']
                });
              }

              window.__homeosViewportTeardown = function() {
                if (observer) { observer.disconnect(); observer = null; }
                if (debounceTimer) { clearTimeout(debounceTimer); debounceTimer = null; }
              };
            })();
        """.trimIndent()
    }
}
