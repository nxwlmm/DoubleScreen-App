package com.mediaplayer.core.source.push

/**
 * 手机端推源页的 HTML 模板。
 *
 * ## 实现说明
 * 模板以 `__KEY__` 占位符 + [String.replace] 方式注入变量，而不是用 Kotlin 字符串模板
 * （`${'$'}`）—— 原因是页面里含大量 CSS/JS，用字符串模板会产生几十处 `$` 转义，
 * 极易出错；占位符方案同时让 HTML 本身可以被单独拿出来预览与调整。
 *
 * ## 设计约束
 * - **无外部资源**：不引用任何 CDN / 字体 / 图片，纯内联。局域网内设备可能没有外网，
 *   任何外部依赖都会让页面卡在加载态。
 * - **体积**：控制在 8KB 以内，低端手机打开即渲染。
 * - **主题联动**：主色由客户端注入，与电视端当前主题（标准=电光青 / 无痕=极光紫）一致。
 */
internal object PushPage {

    /**
     * @param token      当前有效 Token，用于页面内所有回传请求
     * @param accentHex  主色十六进制，如 `#00F2FE`
     * @param accentRgb  主色的 RGB 三元组，如 `0,242,254`（用于 rgba() 半透明）
     * @param deviceName 目标设备名，让用户确认"推给哪台电视"
     */
    fun render(
        token: String,
        accentHex: String,
        accentRgb: String,
        deviceName: String
    ): String = TEMPLATE
        .replace("__TOKEN__", token)
        .replace("__ACCENT__", accentHex)
        .replace("__ACCENT_RGB__", accentRgb)
        .replace("__DEVICE__", escapeHtml(deviceName))

    /** 403 拦截页：明确告诉用户"为什么打不开"以及"该怎么办"。 */
    fun renderForbidden(reason: String, accentHex: String, accentRgb: String): String =
        FORBIDDEN_TEMPLATE
            .replace("__REASON__", escapeHtml(reason))
            .replace("__ACCENT__", accentHex)
            .replace("__ACCENT_RGB__", accentRgb)

    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    // ------------------------------------------------------------------ 主页面

    private val TEMPLATE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#050608">
<title>推送到 __DEVICE__</title>
<style>
*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{background:#050608;color:#fff;
  font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Segoe UI","Microsoft YaHei",system-ui,sans-serif;
  min-height:100%;padding:24px 18px 40px;
  background-image:radial-gradient(560px 300px at 50% -10%,rgba(__ACCENT_RGB__,.10),transparent 65%)}
.wrap{max-width:520px;margin:0 auto}
.brand{display:flex;align-items:center;gap:10px;margin-bottom:22px}
.dot{width:30px;height:30px;border-radius:10px;background:linear-gradient(135deg,__ACCENT__,rgba(__ACCENT_RGB__,.55));
  display:flex;align-items:center;justify-content:center;color:#04222b;font-weight:800;font-size:15px}
.brand b{font-size:16px;font-weight:600}
.brand i{font-style:normal;font-size:11px;color:#5A6472;display:block;margin-top:2px;
  font-family:ui-monospace,Menlo,Consolas,monospace;letter-spacing:.04em}
h1{font-size:23px;font-weight:700;letter-spacing:.01em}
.tip{font-size:12.5px;color:#8E99A8;line-height:1.7;margin-top:9px}
.card{background:rgba(22,25,35,.68);border:1px solid rgba(255,255,255,.08);border-radius:18px;
  padding:18px;margin-top:18px;backdrop-filter:blur(20px)}
label{display:block;font-size:12px;color:#8E99A8;margin:0 0 8px}
label:not(:first-child){margin-top:16px}
textarea,input{width:100%;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.14);
  border-radius:13px;padding:13px 14px;font-size:14px;color:#fff;outline:none;
  font-family:ui-monospace,Menlo,Consolas,monospace;resize:none;transition:border-color .18s,box-shadow .18s}
textarea{height:88px;line-height:1.55}
input{font-family:inherit}
textarea:focus,input:focus{border-color:rgba(__ACCENT_RGB__,.6);box-shadow:0 0 0 3px rgba(__ACCENT_RGB__,.14)}
::placeholder{color:#5A6472}
.row{display:flex;gap:8px;margin-top:10px;flex-wrap:wrap}
.mini{flex:1;min-width:130px;text-align:center;font-size:12.5px;color:__ACCENT__;
  border:1px dashed rgba(__ACCENT_RGB__,.4);border-radius:11px;padding:10px 12px;cursor:pointer;
  background:rgba(__ACCENT_RGB__,.04);transition:background .15s}
.mini:active{background:rgba(__ACCENT_RGB__,.14)}
.seg{display:flex;gap:8px}
.seg div{flex:1;text-align:center;font-size:13px;padding:11px 0;border-radius:12px;
  background:rgba(255,255,255,.03);border:1px solid rgba(255,255,255,.09);
  color:#8E99A8;cursor:pointer;transition:all .15s}
.seg div.on{border-color:rgba(__ACCENT_RGB__,.55);color:__ACCENT__;background:rgba(__ACCENT_RGB__,.08);font-weight:600}
button{width:100%;margin-top:20px;padding:16px 0;border:0;border-radius:15px;font-size:15.5px;font-weight:700;
  color:#04222b;cursor:pointer;background:linear-gradient(135deg,__ACCENT__,rgba(__ACCENT_RGB__,.6));
  box-shadow:0 10px 28px rgba(__ACCENT_RGB__,.26);font-family:inherit;transition:opacity .15s,transform .1s}
button:active{transform:scale(.985)}
button:disabled{opacity:.5;box-shadow:none}
.msg{margin-top:14px;font-size:13px;text-align:center;min-height:20px;line-height:1.6}
.msg.ok{color:#00F59B}
.msg.err{color:#FF4D6D}
h2{font-size:12px;color:#5A6472;font-weight:600;letter-spacing:.06em;
  font-family:ui-monospace,Menlo,Consolas,monospace;margin:26px 0 10px}
.hist{background:rgba(255,255,255,.025);border:1px solid rgba(255,255,255,.07);border-radius:14px;
  padding:12px 14px;margin-bottom:9px;display:flex;align-items:center;gap:10px;cursor:pointer}
.hist:active{background:rgba(255,255,255,.06)}
.hist .u{flex:1;min-width:0;font-size:11.5px;color:#8E99A8;
  font-family:ui-monospace,Menlo,Consolas,monospace;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.hist .t{font-size:10px;color:#5A6472;white-space:nowrap}
.empty{font-size:12px;color:#5A6472;text-align:center;padding:14px 0}
.foot{margin-top:26px;font-size:10.5px;color:#5A6472;text-align:center;line-height:1.8}
</style>
</head>
<body>
<div class="wrap">
  <div class="brand">
    <div class="dot">▸</div>
    <div><b>局域网推源</b><i>TO __DEVICE__</i></div>
  </div>

  <h1>把配置链接推过去</h1>
  <div class="tip">粘贴单仓 / 多仓 JSON、M3U 或 TXT 的地址，提交后本机会自动接收并校验。<br>此页面仅在同一 WiFi 下可访问，凭证已内置于地址中。</div>

  <div class="card">
    <label>配置链接</label>
    <textarea id="url" placeholder="https://example.com/config.json" autocomplete="off" autocapitalize="off" spellcheck="false"></textarea>
    <div class="row">
      <div class="mini" id="paste">从剪贴板粘贴</div>
      <div class="mini" id="clear">清空</div>
    </div>

    <label>配置类型</label>
    <div class="seg" id="seg">
      <div class="on" data-k="SINGLE">单仓</div>
      <div data-k="MULTI">多仓</div>
      <div data-k="LIVE">直播</div>
    </div>

    <label>备注名（可选）</label>
    <input id="name" type="text" placeholder="例如：备用配置" autocomplete="off">

    <button id="go">推 送</button>
    <div class="msg" id="msg"></div>
  </div>

  <h2>最近推送</h2>
  <div id="hist"></div>

  <div class="foot">推送即代表你自行对该内容来源的合法性负责。<br>应用侧保持内容中立，不内置任何影片源。</div>
</div>

<script>
var TOKEN = "__TOKEN__";
var HISTORY_KEY = "push_history_v1";
var MAX_HISTORY = 5;
var msg = document.getElementById("msg");
var urlEl = document.getElementById("url");
var nameEl = document.getElementById("name");

function setMsg(text, cls) {
  msg.textContent = text || "";
  msg.className = "msg" + (cls ? " " + cls : "");
}

function currentKind() {
  var nodes = document.querySelectorAll("#seg div");
  for (var i = 0; i < nodes.length; i++) {
    if (nodes[i].className.indexOf("on") >= 0) return nodes[i].getAttribute("data-k");
  }
  return "SINGLE";
}

document.getElementById("seg").addEventListener("click", function (e) {
  var t = e.target;
  if (!t.getAttribute || !t.getAttribute("data-k")) return;
  var nodes = document.querySelectorAll("#seg div");
  for (var i = 0; i < nodes.length; i++) nodes[i].className = "";
  t.className = "on";
});

document.getElementById("paste").addEventListener("click", function () {
  if (!navigator.clipboard || !navigator.clipboard.readText) {
    setMsg("当前浏览器不支持读取剪贴板，请长按输入框粘贴", "err");
    return;
  }
  navigator.clipboard.readText().then(function (text) {
    if (!text) { setMsg("剪贴板是空的", "err"); return; }
    urlEl.value = text.trim();
    setMsg("已从剪贴板填入", "ok");
  })["catch"](function () {
    setMsg("读取剪贴板被拒绝，请长按输入框手动粘贴", "err");
  });
});

document.getElementById("clear").addEventListener("click", function () {
  urlEl.value = "";
  setMsg("");
  urlEl.focus();
});

document.getElementById("go").addEventListener("click", function () {
  var url = urlEl.value.trim();
  if (!/^https?:\/\/.+/i.test(url)) {
    setMsg("链接需以 http:// 或 https:// 开头", "err");
    return;
  }
  var btn = this;
  btn.disabled = true;
  setMsg("正在提交…");

  fetch("/api/push?token=" + encodeURIComponent(TOKEN), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url: url, kind: currentKind(), name: nameEl.value.trim() })
  }).then(function (r) {
    return r.json();
  }).then(function (res) {
    btn.disabled = false;
    if (res && res.code === 200) {
      setMsg(res.message || "推送成功", "ok");
      saveHistory(url, nameEl.value.trim());
      urlEl.value = "";
      nameEl.value = "";
    } else {
      setMsg((res && res.message) || "推送失败", "err");
    }
  })["catch"](function () {
    btn.disabled = false;
    setMsg("网络错误：请确认手机与电视在同一 WiFi", "err");
  });
});

function readHistory() {
  try {
    var raw = localStorage.getItem(HISTORY_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch (e) { return []; }
}

function saveHistory(url, name) {
  var list = readHistory();
  list = list.filter(function (x) { return x.url !== url; });
  list.unshift({ url: url, name: name || "", t: Date.now() });
  if (list.length > MAX_HISTORY) list = list.slice(0, MAX_HISTORY);
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(list)); } catch (e) {}
  renderHistory();
}

function renderHistory() {
  var box = document.getElementById("hist");
  var list = readHistory();
  if (!list.length) {
    box.innerHTML = '<div class="empty">还没有推送记录</div>';
    return;
  }
  var html = "";
  for (var i = 0; i < list.length; i++) {
    var it = list[i];
    var when = new Date(it.t);
    var hh = ("0" + when.getHours()).slice(-2) + ":" + ("0" + when.getMinutes()).slice(-2);
    html += '<div class="hist" data-url="' + encodeURIComponent(it.url) + '" data-name="' + encodeURIComponent(it.name) + '">' +
              '<span class="u">' + escapeHtml(it.url) + '</span>' +
              '<span class="t">' + hh + '</span>' +
            '</div>';
  }
  box.innerHTML = html;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, function (c) {
    return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
  });
}

document.getElementById("hist").addEventListener("click", function (e) {
  var node = e.target;
  while (node && node !== this && !node.getAttribute("data-url")) node = node.parentNode;
  if (!node || node === this) return;
  urlEl.value = decodeURIComponent(node.getAttribute("data-url"));
  nameEl.value = decodeURIComponent(node.getAttribute("data-name") || "");
  setMsg("已回填，可直接再次推送");
  window.scrollTo({ top: 0, behavior: "smooth" });
});

renderHistory();
</script>
</body>
</html>
"""

    // ------------------------------------------------------------------ 拦截页

    private val FORBIDDEN_TEMPLATE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>需要访问凭证</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#050608;color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center;
  padding:30px 22px;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Segoe UI",system-ui,sans-serif}
.box{max-width:440px;text-align:center}
.ic{width:62px;height:62px;margin:0 auto 20px;border-radius:20px;display:flex;align-items:center;justify-content:center;
  background:rgba(__ACCENT_RGB__,.08);border:1px solid rgba(__ACCENT_RGB__,.28);font-size:26px;color:__ACCENT__}
h1{font-size:19px;font-weight:600;margin-bottom:12px}
p{font-size:13px;color:#8E99A8;line-height:1.85}
.hint{margin-top:20px;font-size:12px;color:#5A6472;line-height:1.8;
  border:1px dashed rgba(255,255,255,.14);border-radius:14px;padding:14px 16px;text-align:left}
code{font-family:ui-monospace,Menlo,Consolas,monospace;color:__ACCENT__;font-size:11.5px}
</style>
</head>
<body>
<div class="box">
  <div class="ic">⚠</div>
  <h1>访问被拒绝</h1>
  <p>__REASON__</p>
  <div class="hint">
    <b>怎么解决</b><br>
    ① 回到电视端「源配置管理 → 新增配置」，投源面板里会显示完整地址<br>
    ② 用手机扫描面板上的二维码，或手动输入带 <code>?token=</code> 的完整地址<br>
    ③ 若面板已关闭或超时，重新打开面板获取新的地址与凭证
  </div>
</div>
</body>
</html>
"""
}
