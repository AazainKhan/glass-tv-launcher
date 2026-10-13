package com.glasslauncher.browser

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A link or button's box in page (document) pixels, as the injected script reports it. */
data class PageBox(val x: Float, val y: Float, val w: Float, val h: Float, val text: Boolean = false) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
}

enum class Dir { Up, Down, Left, Right }

/** Where the focus ring goes next. Pure, so the D-pad rules are tested on the JVM. */
object LinkNav {
    private const val EPS = 2f
    // Down/Up weigh sideways drift heavier than distance, so a column of links stays a column.
    private const val DRIFT = 2.5f

    private fun sameRow(a: PageBox, b: PageBox): Boolean {
        val overlap = min(a.y + a.h, b.y + b.h) - max(a.y, b.y)
        return overlap > 0.5f * min(a.h, b.h)
    }

    /** The target [dir] of [current], or null when nothing lies that way. */
    fun next(targets: List<PageBox>, current: Int, dir: Dir): Int? {
        val cur = targets.getOrNull(current) ?: return null
        var best: Int? = null
        var bestScore = Float.MAX_VALUE
        targets.forEachIndexed { i, t ->
            if (i == current) return@forEachIndexed
            val score = when (dir) {
                Dir.Right -> if (sameRow(cur, t) && t.cx > cur.cx + EPS) t.cx - cur.cx else null
                Dir.Left -> if (sameRow(cur, t) && t.cx < cur.cx - EPS) cur.cx - t.cx else null
                Dir.Down -> if (!sameRow(cur, t) && t.cy > cur.cy + EPS) (t.cy - cur.cy) + DRIFT * abs(t.cx - cur.cx) else null
                Dir.Up -> if (!sameRow(cur, t) && t.cy < cur.cy - EPS) (cur.cy - t.cy) + DRIFT * abs(t.cx - cur.cx) else null
            }
            if (score != null && score < bestScore) { best = i; bestScore = score }
        }
        return best
    }

    /** The first target (reading order) that is in view, else the first of all; null for none. */
    fun first(targets: List<PageBox>, viewTop: Float, viewBottom: Float): Int? =
        targets.indexOfFirst { it.y + it.h > viewTop && it.y < viewBottom }.takeIf { it >= 0 }
            ?: if (targets.isEmpty()) null else 0
}

/** What the script's scan() returned: the focused target's index (-1 none), every target, and the viewport. */
class Scan(val current: Int, val boxes: List<PageBox>, val viewTop: Float, val viewBottom: Float) {
    companion object {
        const val MAX_BOXES = 200
        private const val LIMIT = 1e7f

        private fun ok(v: Float) = v.isFinite() && abs(v) < LIMIT

        /** [raw] is evaluateJavascript's result for a reply `nonce~cur;x,y,w,h,t|...;vw,vh,sx,sy`; anything else (or NaN, negative sizes) is null. */
        fun parse(raw: String?, nonce: String): Scan? {
            val s = Reply.body(raw, nonce) ?: return null
            val parts = s.split(';')
            if (parts.size != 3) return null
            val cur = parts[0].toIntOrNull() ?: return null
            val view = parts[2].split(',').map { it.toFloatOrNull() ?: return null }
            if (view.size != 4 || !view.all(::ok)) return null
            val boxes = if (parts[1].isEmpty()) emptyList() else parts[1].split('|').map { b ->
                val n = b.split(',').map { it.toFloatOrNull() ?: return null }
                if (n.size != 5 || !n.all(::ok) || n[2] <= 0f || n[3] <= 0f || (n[4] != 0f && n[4] != 1f)) return null
                PageBox(n[0], n[1], n[2], n[3], n[4] == 1f)
            }
            if (boxes.size > MAX_BOXES || cur < -1 || cur >= boxes.size) return null
            return Scan(cur, boxes, view[3], view[3] + view[1])
        }
    }
}

/** Replies from the page script. A reply counts only if it starts with this load's nonce, which a page cannot know in advance. */
object Reply {
    fun body(raw: String?, nonce: String): String? =
        Js.unquote(raw)?.takeIf { it.startsWith("$nonce~") }?.substring(nonce.length + 1)

    /** `T|kind` after a Select on, or a tap in, a text field: the field kind from the allowed list (never page text). */
    fun textKind(raw: String?, nonce: String): String? {
        val b = body(raw, nonce)?.takeIf { it.startsWith("T|") } ?: return null
        val kind = b.substring(2).lowercase(Locale.ROOT)
        return if (kind in setOf("password", "search", "email", "number", "url", "tel")) kind else "text"
    }
}

/** The tiny JS string helpers the script bridge needs (no addJavascriptInterface, only evaluateJavascript). */
object Js {
    /** A JS string literal for [s]. */
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == ' ' || c == ' ' || c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /** The string inside evaluateJavascript's JSON result; null for a missing/`null`/non-string/malformed result. */
    fun unquote(raw: String?): String? {
        if (raw == null || raw.length < 2 || raw.first() != '"' || raw.last() != '"') return null
        val body = raw.substring(1, raw.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') { out.append(c); i++; continue }
            val n = body.getOrNull(i + 1) ?: return null
            when (n) {
                'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                'u' -> {
                    val hex = body.substring(min(i + 2, body.length), min(i + 6, body.length))
                    if (hex.length != 4 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    out.append(hex.toInt(16).toChar()); i += 4
                }
                else -> out.append(n)
            }
            i += 2
        }
        return out.toString()
    }
}

/**
 * The page script, injected with evaluateJavascript after each load. It numbers the page's links and buttons in reading
 * order. Its API lives under a per-load random name and every reply starts with the nonce, so a page can neither pre-define
 * it nor forge a reply. Nothing in it can call back into the browser or Glass. Never returns a field's current value.
 */
class PageScript(val nonce: String) {
    private val key = "__gl$nonce"

    fun inject(): String = SOURCE.replace("%N%", nonce)
    fun call(expr: String) = "window['$key'].$expr"
    fun scan() = call("scan()")
    fun focus(i: Int) = call("focus($i)")
    fun act() = call("act()")
    fun activeText() = "(window['$key']?window['$key'].activeText():'')"
    fun setText(text: String) = call("setText(${Js.quote(text)})")

    private companion object {
        const val SOURCE = """
(function(){
var N='%N%',K='__gl'+N,MAX=200;
var SEL='a[href],button,input,select,textarea,summary,[role=button],[role=link],[onclick],[tabindex]:not([tabindex="-1"])';
var KEEP={A:1,BUTTON:1,INPUT:1,SELECT:1,TEXTAREA:1};
var NT={button:1,submit:1,reset:1,checkbox:1,radio:1,file:1,image:1,range:1,color:1,hidden:1};
var els=[],cur=null,prev=null;
function vis(e){var r=e.getBoundingClientRect();if(r.width<4||r.height<4)return false;var s=getComputedStyle(e);
 return s.visibility!=='hidden'&&s.display!=='none'&&!e.disabled&&e.type!=='hidden'&&e.getAttribute('aria-hidden')!=='true';}
function isText(e){var t=e.tagName;if(t==='TEXTAREA')return true;if(t!=='INPUT')return e.isContentEditable===true;return !NT[e.type];}
function scan(){
 var all=document.querySelectorAll(SEL),list=[];
 for(var i=0;i<all.length&&i<2000&&list.length<MAX;i++){if(vis(all[i]))list.push(all[i]);}
 list=list.filter(function(e){if(KEEP[e.tagName])return true;for(var j=0;j<list.length;j++){if(list[j]!==e&&e.contains(list[j]))return false;}return true;});
 var sx=window.scrollX,sy=window.scrollY;
 var it=list.map(function(e){var r=e.getBoundingClientRect();return{e:e,x:r.left+sx,y:r.top+sy,w:r.width,h:r.height};});
 it.sort(function(a,b){return Math.abs(a.y-b.y)<12?a.x-b.x:a.y-b.y;});
 els=it.map(function(i){return i.e;});
 return N+'~'+(cur?els.indexOf(cur):-1)+';'+it.map(function(i){return [i.x,i.y,i.w,i.h,isText(i.e)?1:0].map(Math.round).join(',');}).join('|')+';'+window.innerWidth+','+window.innerHeight+','+sx+','+sy;
}
function unmark(){if(cur&&prev){cur.style.outline=prev[0];cur.style.outlineOffset=prev[1];}}
function focus(i){var e=els[i];if(!e)return 0;unmark();cur=e;prev=[e.style.outline,e.style.outlineOffset];
 e.style.outline='4px solid #0a84ff';e.style.outlineOffset='2px';
 var r=e.getBoundingClientRect();if(r.top<60||r.bottom>window.innerHeight-60||r.left<0||r.right>window.innerWidth){e.scrollIntoView({block:'center',inline:'center'});}return 1;}
function info(e){return N+'~T|'+(e.tagName==='INPUT'?String(e.type||'text'):'text');}
function act(){var e=cur;if(!e)return N+'~N';if(isText(e)){e.focus();return info(e);}e.click();return N+'~C';}
function activeText(){var e=document.activeElement;return e&&e!==document.body&&isText(e)?info(e):N+'~';}
function setText(v){var e=cur&&document.activeElement===cur?cur:(document.activeElement&&isText(document.activeElement)?document.activeElement:cur);if(!e)return 0;
 if(e.isContentEditable){e.textContent=v;}else{var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
  var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,v);else e.value=v;}
 e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 1;}
window[K]={scan:scan,focus:focus,act:act,activeText:activeText,setText:setText};
})();
"""
    }
}

/** What a typed address turns into. */
sealed interface Address {
    data class Go(val url: String) : Address
    /** http:// or a custom scheme: nothing is loaded. */
    data class Refused(val message: String) : Address
}

/** What to do with a typed address or a link the page wants to follow. */
object BrowserRules {
    private const val SEARCH = "https://duckduckgo.com/?q="
    // "mailto:a@b.com" would otherwise read as https://mailto:a@b.com; a port (digits) or "//" after the colon is a real host.
    private val OPAQUE_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?![0-9/])")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
    const val ONLY_WEB = "Only https web addresses can be opened here."
    const val NEEDS_HTTPS = "That link needs https; plain http is not opened."

    private fun search(t: String) = Address.Go(SEARCH + URLEncoder.encode(t, "UTF-8"))

    /** The https rules of the address bar; text that isn't an address (spaces, or a bare word) becomes a search. */
    fun address(input: String): Address {
        val t = input.trim()
        if (t.isEmpty() || t.length > 2048 || OPAQUE_SCHEME.containsMatchIn(t)) return Address.Refused(ONLY_WEB)
        val scheme = SCHEME.find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        return when {
            scheme == "http" -> Address.Refused(NEEDS_HTTPS)
            scheme != null && scheme != "https" -> Address.Refused(ONLY_WEB)
            scheme == null && t.any { it.isWhitespace() } -> search(t)
            scheme == null && '.' !in t && '/' !in t && ':' !in t -> search(t)
            else -> {
                val url = if (scheme == null) "https://$t" else "https" + t.substring(5)
                if (host(url) != null) Address.Go(url) else Address.Refused(ONLY_WEB)
            }
        }
    }

    /** Whether the page may navigate to [url]: https only (cleartext is blocked app-wide), never another app or a custom scheme. */
    fun allowNavigation(url: String): Boolean {
        val u = url.trim().lowercase(Locale.ROOT)
        return u.startsWith("https://") || u == "about:blank"
    }

    /** The host of an https [url] for the address bar and for Glass's label; "" when there is none. */
    fun host(url: String): String? = dev.glasslauncher.browserlink.Link.httpsHost(url)

    /** A URL Glass asked to open: https only, checked again here whatever Glass already did. */
    fun openable(url: String?): String? = url?.takeIf { host(it) != null }
}

/** Pointer-mode speed: pixels per key event, ramping while a D-pad key is held. */
object PointerSpeed {
    fun step(repeat: Int): Int = min(10 + repeat * 3, 60)
}
