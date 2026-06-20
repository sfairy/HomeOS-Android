package com.homeos.app

/**
 * 向 WebView 注入 viewport 与 CSS 合成层缩放脚本（事件驱动，无 RAF 循环）。
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
     * 首次注入：固定 viewport 宽度、触摸滚动优化、CSS transform 等比缩放。
     */
    fun buildInstallScript(designWidth: Int, designHeight: Int, screenWidth: Int): String {
        return """
            (function(){
              if (window.__homeosViewportInjected) return;
              window.__homeosViewportInjected = true;

              var W = $designWidth;
              var H = $designHeight;

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

              function applyScale(sw) {
                var scale = sw / W;
                var root = document.documentElement;
                root.style.transform = 'scale(' + scale + ')';
                root.style.transformOrigin = 'top left';
                root.style.width = W + 'px';
                root.style.height = H + 'px';
              }

              function applyAll() {
                applyViewport();
                applyScale(window.innerWidth || ${screenWidth});
              }

              applyAll();

              if (window.__homeosViewportTeardown) return;

              var head = document.head || document.getElementsByTagName('head')[0];
              var observer = null;
              if (head && window.MutationObserver) {
                observer = new MutationObserver(function(mutations) {
                  for (var i = 0; i < mutations.length; i++) {
                    var m = mutations[i];
                    if (m.type === 'childList' || m.type === 'attributes') {
                      applyViewport();
                      break;
                    }
                  }
                });
                observer.observe(head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
              }

              var onResize = function() { applyScale(window.innerWidth || ${screenWidth}); };
              window.addEventListener('resize', onResize);
              window.addEventListener('orientationchange', onResize);

              window.__homeosViewportTeardown = function() {
                if (observer) { observer.disconnect(); observer = null; }
                window.removeEventListener('resize', onResize);
                window.removeEventListener('orientationchange', onResize);
              };
            })();
        """.trimIndent()
    }

    /** 屏幕尺寸变化时仅更新 transform，避免全页 reflow。 */
    fun buildScaleUpdateScript(designWidth: Int, designHeight: Int, screenWidth: Int): String {
        val scale = screenWidth.toDouble() / designWidth.toDouble()
        return """
            (function(){
              var W = $designWidth;
              var H = $designHeight;
              var scale = $scale;
              var root = document.documentElement;
              root.style.transform = 'scale(' + scale + ')';
              root.style.transformOrigin = 'top left';
              root.style.width = W + 'px';
              root.style.height = H + 'px';
            })();
        """.trimIndent()
    }
}
