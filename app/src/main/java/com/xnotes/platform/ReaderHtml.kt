package com.xnotes.platform

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.xmlpull.v1.XmlPullParser
import java.util.Base64

/**
 * Turns the reader's file kinds into one HTML body each, for a WebView with scripts off. Office
 * files are read entry by entry through [ZipRead]; nothing is ever written back.
 */
object ReaderHtml {

    private val MATH_FENCE = Regex("^\\s{0,3}\\\$\\\$\\s*$")

    /** The "$" span opening at [at], as its closing offset and whether it is the doubled display form, or null. */
    private fun mathSpan(text: String, at: Int): Pair<Int, Boolean>? {
        val display = text.startsWith("$$", at)
        val marker = if (display) "$$" else "$"
        val from = at + marker.length
        if (from >= text.length) return null
        val close = text.indexOf(marker, from)
        if (close < from + 1) return null
        // "$x$" and "$ x $" are maths, "$5 and $10" is money.
        if (text[from].isWhitespace() != text[close - 1].isWhitespace()) return null
        if (text.substring(from, close).isBlank()) return null
        if (!display && close + 1 < text.length && text[close + 1] == '$') return null
        return close to display
    }
    /** Reads a ZIP entry by path, or null when it is absent. */
    fun interface ZipRead { fun read(path: String): ByteArray? }

    /** Link scheme for Obsidian [[wikilinks]]; the reader resolves the target by file name. */
    const val WIKI_SCHEME = "nnotes-wiki"

    /** The few words the converters print, supplied from string resources. */
    class Labels(
        val slide: (Int, Int) -> String = { n, total -> "Slide $n of $total" },
        val notes: String = "Notes",
        val truncatedRows: (Int) -> String = { "Showing the first $it rows." },
        val truncatedCells: (Int, Int) -> String = { r, c -> "Showing the first $r rows and $c columns of this sheet." },
        val image: String = "image",
        val empty: String = "This file is empty.",
    )

    /** The app theme's colours as CSS hex: page, body text, secondary text, links/accents, raised surfaces and rules. */
    class Colors(val background: String, val text: String, val muted: String, val accent: String, val surface: String, val border: String)

    private const val MAX_CSV_ROWS = 10_000
    private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
    private const val MAX_IMAGE_TOTAL = 40 * 1024 * 1024

    // --- page shell ---

    /**
     * How the reader dresses a file beyond its colours: [around] fills what is behind the content and
     * keeps the app's theme; [docFilter] tints a document's own white pages to the reader's theme
     * ("" leaves them), [imageFilter] undoes it on pictures; [paperLook] is the CSS backgrounds a book's
     * pages are printed on ("" for plain). [docPaper] instead gives a document's pages the reader's own paper and text.
     */
    class Look(val around: String, val docFilter: String = "", val imageFilter: String = "", val paperLook: String = "", val docPaper: Boolean = false)

    /**
     * [body] as the reader shows it, with the reader's own script, the only one the page lets run ([nonce]).
     * [view] is "scroll" (one flowing column), "slides" (a deck, each slide counted as a page), or pages
     * cut to [paper] laid out "vertical", "horizontal", "single", "double" or "cover". [paper] is the page
     * size and margins in cm ("w h top right bottom left") or "screen"; a document's own page ([docPage])
     * wins over it. [smooth] slides a page turn across rather than jumping.
     */
    fun readerPage(body: String, c: Colors, view: String, paper: String, nonce: String, look: Look, wide: Boolean = false, smooth: Boolean = true): String {
        val attrs = " class=\"$view\" data-view=\"$view\" data-paper=\"$paper\"${if (smooth) " data-smooth" else ""}"
        val script = "<script nonce=\"$nonce\">$PAGER_JS</script>"
        return if (view == "scroll" || view == "slides") shell(body + script, c, wide, "", attrs, nonce, look = look)
        else shell("<div id=\"src\">${balanced(body)}</div><div id=\"pages\"></div>$script", c, true, pagesCss(c), attrs, nonce, minScale = true, look = look)
    }

    private val tagToken = Regex("<!--.*?-->|<(/?)([a-zA-Z][\\w:-]*)[^>]*>", RegexOption.DOT_MATCHES_ALL)
    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")

    /**
     * [html] with every end tag matched: one with nothing open to close is dropped and what is left open
     * is closed, so a broken book or a part cut inside a box never closes the page's own #src early.
     */
    internal fun balanced(html: String): String {
        val open = ArrayList<String>()
        val out = StringBuilder(html.length + 64)
        var last = 0
        for (m in tagToken.findAll(html)) {
            val name = m.groupValues[2].lowercase()
            if (name.isEmpty() || name in voidTags || m.value.endsWith("/>")) continue
            if (m.groupValues[1].isEmpty()) { open += name; continue }
            val at = open.lastIndexOf(name)
            out.append(html, last, m.range.first)
            if (at >= 0) { out.append(m.value); while (open.size > at) open.removeAt(open.lastIndex) }
            last = m.range.last + 1
        }
        out.append(html, last, html.length)
        for (i in open.indices.reversed()) out.append("</").append(open[i]).append('>')
        return out.toString()
    }

    /** A heading the reader's contents lists: the [part] it is in, its [index] among that part's h1 to h3, its [level] and [title]. */
    class PartHeading(val part: Int, val index: Int, val level: Int, val title: String)

    private val headingTag = Regex("<h([1-3])\\b[^>]*>(.*?)</h\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Every h1 to h3 of [parts], in order, as the page script finds them (R.heading). */
    fun headings(parts: List<String>): List<PartHeading> = parts.flatMapIndexed { part, html ->
        headingTag.findAll(html).mapIndexedNotNull { i, m ->
            plainText(m.groupValues[2]).trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }?.let { PartHeading(part, i, m.groupValues[1].toInt(), it) }
        }.toList()
    }

    private val entityNames = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0", "emsp" to "\u2003", "ensp" to "\u2002",
        "thinsp" to "\u2009", "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026", "lsquo" to "\u2018", "rsquo" to "\u2019",
        "ldquo" to "\u201c", "rdquo" to "\u201d", "copy" to "\u00a9", "reg" to "\u00ae", "trade" to "\u2122", "shy" to "\u00ad",
    )

    /** [html]'s text as the page shows it: tags dropped, entities read. */
    fun plainText(html: String): String = html.replace(Regex("<[^>]*>"), "").replace(Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> entityNames[e]
        } ?: m.value
    }

    /** A match of a search: the [part] it is in, which match it is there ([nth], as R.find counts) and its context. */
    class PartHit(val part: Int, val nth: Int, val before: String, val match: String, val after: String)

    /** Every match of [query] in [parts], ignoring case, up to [max]. */
    fun search(parts: List<String>, query: String, max: Int = 500): List<PartHit> {
        val q = query.lowercase()
        if (q.isBlank()) return emptyList()
        val hits = ArrayList<PartHit>()
        for ((part, html) in parts.withIndex()) {
            val text = plainText(html)
            val low = text.lowercase()
            var at = low.indexOf(q)
            var nth = 0
            while (at >= 0) {
                fun clip(s: String) = s.replace(Regex("\\s+"), " ")
                hits += PartHit(part, nth++, clip(text.substring((at - 40).coerceAtLeast(0), at)).trimStart(), text.substring(at, at + q.length), clip(text.substring(at + q.length, (at + q.length + 60).coerceAtMost(text.length))).trimEnd())
                if (hits.size >= max) return hits
                at = low.indexOf(q, at + 1)
            }
        }
        return hits
    }

    /** A grain of paper fibres drawn by the page itself, grey so it shows on light and dark alike. */
    private const val GRAIN = "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='180' height='180'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.85' numOctaves='3' stitchTiles='stitch'/%3E%3CfeColorMatrix values='0 0 0 0 .5 0 0 0 0 .5 0 0 0 0 .5 0 0 0 .09 0'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E\")"

    /** The CSS backgrounds of a paper look: "grain", "old" (yellowed, darker to the edges) or plain. */
    fun paperLookCss(look: String): String = when (look) {
        "grain" -> GRAIN
        "old" -> "radial-gradient(ellipse at center,rgba(0,0,0,0) 55%,rgba(110,75,20,.22)),linear-gradient(rgba(190,145,60,.10),rgba(190,145,60,.10)),$GRAIN"
        else -> ""
    }

    /**
     * [body] cut into parts of about [max] characters at chapter, slide or paragraph ends, so the
     * WebView lays out one part at a time. A whole book at once takes it minutes. Pictures' data is not
     * counted: it costs the layout nothing, and counted, a chapter's opening picture alone filled a part.
     */
    fun pageChunks(body: String, max: Int = 100_000): List<String> {
        // A document's parts each keep its page and look.
        Regex("^<article class=\"doc\"[^>]*>").find(body)?.takeIf { body.endsWith("</article>") }?.let { open ->
            return pageChunks(body.substring(open.range.last + 1, body.length - "</article>".length), max).map { open.value + it + "</article>" }
        }
        val parts = ArrayList<String>()
        val pending = StringBuilder()
        var pendingWeight = 0
        // Short chapters and slides share a part; a long one is cut after a paragraph.
        for (section in body.split(Regex("(?=<section class=\"(?:chapter|slide)\")"))) {
            if (section.isBlank()) continue
            // A slide is drawn whole, so only flowing text is cut.
            for (piece in if (section.startsWith("<section class=\"slide\"")) listOf(section) else cutLong(section, max)) {
                val weight = textWeight(piece)
                if (pending.isNotEmpty() && pendingWeight + weight > max) { parts += pending.toString(); pending.clear(); pendingWeight = 0 }
                pending.append(piece)
                pendingWeight += weight
            }
        }
        if (pending.isNotEmpty()) parts += pending.toString()
        return parts.ifEmpty { listOf(body) }
    }

    private fun cutLong(html: String, max: Int): List<String> {
        val out = ArrayList<String>()
        // Only what is more than [max] from where the piece starts is cut, so no small tail is left alone.
        var left = textWeight(html)
        var start = 0
        var weighed = 0
        var weight = 0
        var end = html.indexOf("</p>")
        while (end >= 0 && left > max) {
            val cut = end + 4
            if (cut >= html.length) break
            // A paragraph ending at or past [max] ends the piece.
            weight += textWeight(html.subSequence(weighed, end))
            weighed = end
            if (weight >= max) { out += html.substring(start, cut); start = cut; left -= weight; weight = 0 }
            end = html.indexOf("</p>", cut)
        }
        out += html.substring(start)
        return out
    }

    private val dataUri = Regex("data:[^\"')\\s]+")

    /** [html]'s length without its pictures' data. */
    private fun textWeight(html: CharSequence): Int = html.length - dataUri.findAll(html).sumOf { it.value.length }

    private fun pagesCss(c: Colors) = """
        body{max-width:none;margin:0;padding:0;overflow-wrap:break-word;background:transparent}
        #src{display:none}
        #pages{display:flex;flex-direction:column;align-items:center;gap:16px;width:max-content;min-width:100%}
        .page{box-sizing:border-box;flex:none;overflow:hidden;background-color:${c.background};box-shadow:0 1px 5px rgba(0,0,0,.4)}
        .page:has(> .doc){background:#fff}
        .page > .content{height:100%;overflow:hidden;width:auto!important;max-width:none!important;padding:0!important;margin:0!important;box-shadow:none!important}
        .page > .content > :first-child{margin-top:0}
        .page img{max-width:100%;object-fit:contain}
        .page pre{white-space:pre-wrap;overflow:visible}
        .page table{display:table;overflow:visible}
        body.horizontal #pages,body.single #pages,body.double #pages,body.cover #pages{flex-direction:row;align-items:center;min-width:0}
        body.single #pages,body.double #pages,body.cover #pages{gap:0}
        .spread{display:flex;flex:none;align-items:center;justify-content:center;gap:12px;box-sizing:border-box}
        body.horizontal #pages{box-sizing:border-box}
    """.trimIndent()

    // Moves the content into fixed-size pages, splitting lists, tables and long paragraphs between
    // them, then fits the pages to the screen; a scroll or a deck is left as it flows. It tells the
    // app through the title: "count|page", and "count|page|edge|n" when a turn runs past either end.
    // The app drives it with R.go(page), R.turn(by), R.heading(n), R.find(text, nth) and R.inset(px).
    private val PAGER_JS = """
        (function(){
        var b=document.body,de=document.documentElement,src=document.getElementById('src'),out=document.getElementById('pages');
        var view=b.getAttribute('data-view'),smooth=b.hasAttribute('data-smooth'),cm=96/2.54;
        var flowing=view==='scroll'||view==='slides',paired=view==='double'||view==='cover',whole=view==='single'||paired;
        // The app's side panel lies over the left [inset] px, which the page leaves free (R.inset).
        var inset=+(b.getAttribute('data-inset')||0),ran=false;de.style.paddingLeft=inset+'px';
        var doc=src&&src.querySelector('article.doc'),spec=(doc&&doc.getAttribute('data-page'))||b.getAttribute('data-paper')||'screen',W,H,M;
        if(spec==='screen'){W=de.clientWidth-inset;H=de.clientHeight;M=[76,22,84,22];}
        else{var v=spec.split(' ').map(Number);W=v[0]*cm;H=v[1]*cm;M=v.slice(2).map(function(x){return x*cm;});}
        var c,stack=[],placed=0,pages=[],spreads=0,edges=0,cur=0,frac=0;
        function newPage(){
          var p=document.createElement('div');p.className='page';
          p.style.cssText='width:'+W+'px;height:'+H+'px;padding:'+M.join('px ')+'px';
          c=doc?doc.cloneNode(false):document.createElement('div');c.removeAttribute('data-page');c.classList.add('content');
          p.appendChild(c);out.appendChild(p);pages.push(p);placed=0;
          var parent=c;for(var i=0;i<stack.length;i++){var k=stack[i].o.cloneNode(false);k.removeAttribute('id');parent.appendChild(k);stack[i].k=k;parent=k;}
        }
        function target(){return stack.length?stack[stack.length-1].k:c;}
        function over(){return c.scrollHeight>c.clientHeight+1;}
        // Text blocks are cut by words, things like pictures and table rows never; any other box (a book's
        // wrapping divs, spans) is opened and its children flowed, so nothing is left clipped on one page.
        var TEXT=/^(P|LI|H[1-6]|PRE|BLOCKQUOTE|DD|DT|TD)$/,WHOLE=/^(IMG|SVG|TR|HR|BR|IFRAME|PICTURE|VIDEO|MATH)$/i;
        // A generator, so the cutting gives way to drawing between slices (see run).
        function* flow(n){
          var t=target();t.appendChild(n);
          // Blank space between blocks never starts a page of its own.
          if(n.nodeType===3&&!n.data.trim())return;
          if(!over()){if(n.nodeType===1||n.textContent.trim())placed++;yield;return;}
          t.removeChild(n);
          var el=n.nodeType===1;
          if(el&&!TEXT.test(n.tagName)&&!WHOLE.test(n.tagName)&&(n.childNodes.length>1||n.children.length===1)){
            var k=n.cloneNode(false);t.appendChild(k);stack.push({o:n,k:k});
            var kids=Array.prototype.slice.call(n.childNodes);for(var i=0;i<kids.length;i++)yield* flow(kids[i]);
            stack.pop();return;
          }
          if(placed>0){newPage();yield* flow(n);return;}
          // Bare text too long for a page is cut like a paragraph.
          if(!el){var s=document.createElement('span');s.appendChild(n);n=s;}
          if(!WHOLE.test(n.tagName)&&(yield* cut(n)))return;
          target().appendChild(n);placed++;yield;
        }
        // Splits a block too tall for an empty page after the last word that still fits; the rest is marked as a continuation.
        function* cut(el){
          target().appendChild(el);
          var limit=c.getBoundingClientRect().top+c.clientHeight,w=document.createTreeWalker(el,NodeFilter.SHOW_TEXT),pts=[],n;
          while((n=w.nextNode()))for(var i=0;i<n.data.length;i++)if(n.data.charCodeAt(i)===32)pts.push([n,i+1]);
          var r=document.createRange(),lo=0,hi=pts.length-1,best=-1;
          while(lo<=hi){var m=(lo+hi)>>1;r.setStart(el,0);r.setEnd(pts[m][0],pts[m][1]);var rs=r.getClientRects(),bot=rs.length?rs[rs.length-1].bottom:0;if(bot<=limit){best=m;lo=m+1;}else hi=m-1;}
          if(best<0){el.parentNode.removeChild(el);return false;}
          r.setStart(pts[best][0],pts[best][1]);r.setEndAfter(el.lastChild);
          var k=el.cloneNode(false);k.removeAttribute('id');k.classList.add('r-cont');k.appendChild(r.extractContents());k.style.textIndent='0';
          placed++;newPage();yield* flow(k);return true;
        }
        // Which spread a page is on and the first page of a spread: single, pairs, or the cover alone then pairs.
        function spreadOf(i){return view==='single'?i:view==='double'?Math.floor(i/2):(i===0?0:Math.floor((i+1)/2));}
        function firstOf(s){return view==='single'?s:view==='double'?2*s:(s===0?0:2*s-1);}
        var z=1,vw=de.clientWidth-inset,vh=de.clientHeight;
        function fit(){
          vw=de.clientWidth-inset;vh=de.clientHeight;
          var per=paired?2:1;
          if(view==='vertical')z=Math.min(1,(vw-24)/W);
          else if(view==='horizontal')z=Math.min(1,(vh-160)/H);
          else z=Math.min((vw-24-(per-1)*12)/(per*W),(vh-160)/H);
          out.style.zoom=z;
          if(view==='vertical')out.style.padding=(76/z)+'px 0 '+(84/z)+'px';
          else if(view==='horizontal'){out.style.height=(vh/z)+'px';out.style.padding=(76/z)+'px '+(16/z)+'px '+(84/z)+'px';}
          else Array.prototype.forEach.call(out.children,function(s){s.style.width=(vw/z)+'px';s.style.height=(vh/z)+'px';s.style.padding=(76/z)+'px 0 '+(84/z)+'px';});
        }
        function at(){
          if(!pages.length)return 0;
          if(whole)return firstOf(Math.max(0,Math.min(spreads-1,Math.round(scrollX/vw))));
          // The last page starting before the middle of the screen; slides differ in height.
          var x=view==='horizontal',mid=x?inset+vw/2:vh/2,lo=0,hi=pages.length-1;
          while(lo<hi){var m=(lo+hi+1)>>1,r=pages[m].getBoundingClientRect();if((x?r.left:r.top)<=mid)lo=m;else hi=m-1;}
          return lo;
        }
        function report(){if(!pages.length)return;cur=at();var t=pages.length+'|'+cur;if(document.title.split('|').slice(0,2).join('|')!==t)document.title=t;}
        function go(i,anim){
          if(!pages.length)return;
          i=Math.max(0,Math.min(pages.length-1,i));
          if(whole)scrollTo({left:spreadOf(i)*vw,top:0,behavior:anim&&smooth?'smooth':'instant'});
          else{var r=pages[i].getBoundingClientRect();if(view==='horizontal')scrollTo(r.left+scrollX-16-inset,scrollY);else scrollTo(scrollX,r.top+scrollY-76);}
          report();
        }
        // Turns [d] spreads on from page [p] (the page in view when left out), or reports running past an end.
        function turn(d,p){
          var from=p==null?at():p,s=whole?spreadOf(from)+d:from+d,last=whole?spreads-1:pages.length-1;
          if(s<0||s>last){document.title=pages.length+'|'+at()+'|'+d+'|'+(++edges);return;}
          go(whole?firstOf(s):s,true);
        }
        function reveal(el){
          if(!el)return;
          var holder=el.closest(view==='slides'?'section.slide':'.page');
          if(holder&&pages.indexOf(holder)>=0){go(pages.indexOf(holder));return;}
          el.scrollIntoView({block:'start'});scrollBy(0,-84);
        }
        function heading(k){reveal(document.querySelectorAll('h1:not(.r-cont),h2:not(.r-cont),h3:not(.r-cont)')[k]);}
        // The nth match of text, counted over the page's text as one string, so a match may cross tags.
        function find(q,n){
          q=q.toLowerCase();
          var w=document.createTreeWalker(b,NodeFilter.SHOW_TEXT,{acceptNode:function(t){return t.parentNode.closest('script')?2:1;}}),nodes=[],text='',t;
          while((t=w.nextNode())){nodes.push(t);text+=t.data;}
          var low=text.toLowerCase(),hit=-1;
          for(var k=0;k<=n;k++){hit=low.indexOf(q,hit+1);if(hit<0)return;}
          function point(o){var s=0;for(var i=0;i<nodes.length;i++){if(o<=s+nodes[i].data.length)return[nodes[i],o-s];s+=nodes[i].data.length;}return[nodes[nodes.length-1],nodes[nodes.length-1].data.length];}
          var a=point(hit),e=point(hit+q.length),r=document.createRange();r.setStart(a[0],a[1]);r.setEnd(e[0],e[1]);
          try{CSS.highlights.set('hit',new Highlight(r));}catch(x){}
          // Scrolling, the match itself is brought a third of the way down; its block may be long.
          if(view==='scroll'){var rr=r.getBoundingClientRect();scrollTo(scrollX,rr.top+scrollY-vh/3);return;}
          reveal(a[0].parentNode);
        }
        function run(){
          if(flowing){
            if(view==='slides')pages=Array.prototype.slice.call(document.querySelectorAll('section.slide'));
            var track=function(){report();var m=de.scrollHeight-innerHeight;frac=m>0?scrollY/m:0;};
            track();addEventListener('scroll',track,{passive:true});
            ran=true;addEventListener('resize',relayout);return;
          }
          // Pictures are capped to a page before cutting, so one never overflows the page it is put on; the room left is the line's descent.
          Array.prototype.forEach.call(src.querySelectorAll('img'),function(im){im.style.maxHeight=(H-M[0]-M[2]-12)+'px';});
          // Cut in slices of a few ms: one long run holds the renderer, and the app's frames wait on it.
          var all=Array.prototype.slice.call((doc||src).childNodes);
          var g=(function*(){for(var i=0;i<all.length;i++){if(!c)newPage();yield* flow(all[i]);}})();
          out.style.visibility='hidden';
          (function step(){
            var end=performance.now()+12;
            while(performance.now()<end)if(g.next().done){out.style.visibility='';finish();return;}
            setTimeout(step,0);
          })();
        }
        function finish(){
          if(!c)newPage();
          src.parentNode.removeChild(src);
          if(whole){
            var all=pages.slice();out.innerHTML='';
            for(var i=0;i<all.length;i=firstOf(spreadOf(i)+1)){var sp=document.createElement('div');sp.className='spread';for(var j=i;j<Math.min(firstOf(spreadOf(i)+1),all.length);j++)sp.appendChild(all[j]);out.appendChild(sp);}
            spreads=out.children.length;
          }
          fit();report();
          addEventListener('scroll',report,{passive:true});
          ran=true;addEventListener('resize',relayout);
        }
        // A new width (the side panel, a turn of the device) keeps the place last read; measuring now would read the old layout.
        function relayout(){
          if(!ran)return;
          if(!flowing){fit();go(cur);}
          else if(view==='slides')go(cur);
          else scrollTo(scrollX,frac*Math.max(0,de.scrollHeight-innerHeight));
        }
        function setInset(p){inset=p;de.style.paddingLeft=p+'px';relayout();}
        window.R={go:go,turn:turn,heading:heading,find:find,inset:setInset};
        if(document.readyState==='complete')run();else addEventListener('load',run);
        })();
    """.trimIndent()

    /** [page] laid out for print: backgrounds kept, nothing clipped by scroll boxes, blocks not split across pages. */
    fun printPage(body: String, c: Colors, wide: Boolean = false): String = shell(body, c, wide, """
        @page{margin:14mm 13mm}
        *{-webkit-print-color-adjust:exact;print-color-adjust:exact}
        body{max-width:none;padding:0}
        pre{white-space:pre-wrap;overflow:visible}
        table{display:table;overflow:visible}
        table.csv th{position:static}
        thead{display:table-header-group}
        tr,img,pre,blockquote,.slide,.callout,.props{break-inside:avoid}
        h1,h2,h3,h4,h5,h6{break-after:avoid}
        section.chapter+section.chapter{break-before:page}
    """.trimIndent())

    private fun shell(
        body: String, c: Colors, wide: Boolean, extraCss: String, bodyAttrs: String = "", nonce: String? = null, minScale: Boolean = false,
        look: Look = Look(c.background),
    ): String {
        val css = """
            html{-webkit-text-size-adjust:100%}
            body{background:${c.background};color:${c.text};font-family:sans-serif;font-size:17px;line-height:1.6;margin:0 auto;padding:76px 18px 84px;${if (wide) "" else "max-width:820px;"}overflow-wrap:break-word;word-wrap:break-word}
            a{color:${c.accent}}
            h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.2em 0 .5em}
            h1{font-size:1.8em}h2{font-size:1.45em}h3{font-size:1.2em}
            p{margin:.6em 0}
            p.gap{margin:0;height:.6em}
            p.subtitle{font-size:1.15em;color:${c.muted}}
            img{max-width:100%;height:auto}
            hr{border:0;border-top:1px solid ${c.border}}
            code{background:${c.surface};border-radius:4px;padding:.1em .3em;font-size:.9em}
            pre{background:${c.surface};border-radius:6px;padding:10px 12px;overflow-x:auto;line-height:1.4}
            pre code{background:none;padding:0}
            blockquote{margin:.8em 0;padding:.2em 0 .2em 14px;border-left:3px solid ${c.border};color:${c.muted}}
            .callout{border-left-color:${c.accent};background:${c.surface};border-radius:0 6px 6px 0;padding:8px 12px;color:inherit}
            .callout-title{font-weight:600;margin:.2em 0;color:${c.accent}}
            mark{background:rgba(255,208,0,.4);color:inherit;border-radius:2px}
            table{border-collapse:collapse;display:block;overflow-x:auto;margin:.8em 0;max-width:100%}
            th,td{border:1px solid ${c.border};padding:5px 9px;vertical-align:top}
            th:not([align]){text-align:left}
            th{background:${c.surface}}
            td.num{text-align:right;font-variant-numeric:tabular-nums}
            table.csv{font-size:14px;line-height:1.35}
            table.csv th{position:sticky;top:0}
            table.sheet td{white-space:nowrap}
            .sheets{display:flex;flex-wrap:wrap;gap:6px}
            .sheets a{border:1px solid ${c.border};border-radius:999px;padding:2px 12px;text-decoration:none;font-size:14px}
            li:has(> input[type=checkbox]){list-style:none}
            li > input[type=checkbox]{margin:0 .4em 0 -1.3em;accent-color:${c.accent}}
            .props{border:1px solid ${c.border};border-radius:6px;padding:6px 10px;margin:0 0 1em;font-size:.88em;color:${c.muted}}
            .props b{color:${c.text};font-weight:600}
            .missing{color:${c.muted};font-style:italic}
            .slide{border:1px solid ${c.border};border-radius:10px;padding:10px 18px 14px;margin:0 0 18px}
            .slide-no{font-size:12px;color:${c.muted};margin-bottom:4px}
            .slide h2:first-of-type{margin-top:.3em}
            .deck-slide{position:relative;width:100%;container-type:inline-size;overflow:hidden;border-radius:4px;box-shadow:0 0 0 1px ${c.border};font-family:sans-serif;line-height:1.15}
            .deck-slide .shape{position:absolute;display:flex;flex-direction:column;overflow:hidden;box-sizing:border-box;padding:.6cqw 1cqw}
            .deck-slide .shape p{margin:0}
            .deck-slide .shape.pic{padding:0}
            .deck-slide .shape img{width:100%;height:100%;object-fit:fill;max-width:none}
            .deck-slide .shape table{display:table;font-size:inherit;margin:0;width:100%}
            .deck-slide .shape th,.deck-slide .shape td{border-color:rgba(0,0,0,.25);padding:.3cqw .6cqw}
            .notes{border-top:1px dashed ${c.border};margin-top:12px;padding-top:6px;font-size:.88em;color:${c.muted}}
            .note{font-size:13px;color:${c.muted};margin:.6em 0}
            .math{display:inline-block;background:currentColor;-webkit-mask-size:100% 100%;-webkit-mask-repeat:no-repeat}
            .math-block{text-align:center;margin:.8em 0;overflow-x:auto}
            pre.text{white-space:pre-wrap;background:none;padding:0;font-family:inherit;font-size:1em;line-height:1.6}
            pre.code{white-space:pre-wrap;word-break:break-word;font-size:14px}
            .x-t{color:${c.accent}}
            .x-a{color:${c.text};opacity:.8}
            .x-v{color:${c.muted}}
            .x-c{color:${c.muted};font-style:italic}
            .poem{margin:.8em 0 .8em 1.5em}
            html{background:${look.around}}
            body{min-height:100vh;box-sizing:border-box}
            body:has(> article.doc),body:has(section.slide){background:transparent}
            ${if (look.docFilter.isEmpty()) "" else "article.doc:not(.content),.page:has(> .doc){filter:${look.docFilter}}"}
            ${if (look.imageFilter.isEmpty()) "" else ".doc img{filter:${look.imageFilter}}"}
            ${if (!look.docPaper) "" else ".doc,.page:has(> .doc){background:${c.background}!important}.doc{color:${c.text}!important}.doc [style*=\"color:#000000\"]{color:inherit!important}.doc a{color:${c.accent}!important}.doc th,.doc td{border-color:${c.border}!important}"}
            ${if (look.paperLook.isEmpty()) "" else "body.scroll:not(:has(> article.doc)),.page:not(:has(> .doc)){background-image:${look.paperLook}}"}
            ::highlight(hit){background:rgba(255,200,0,.55);color:inherit}
            .doc{display:block;background:#fff;color:#000;font-size:11pt;line-height:1.15;margin:0 auto;box-sizing:border-box;max-width:100%;box-shadow:0 1px 5px rgba(0,0,0,.4)}
            .doc a{color:#1155cc}
            .doc p,.doc h1,.doc h2,.doc h3,.doc h4,.doc h5,.doc h6{margin:0}
            .doc table{display:table}
            .doc ul,.doc ol{margin:0;padding-left:1.6em}
            .doc li > p{margin-left:0!important;text-indent:0!important}
            .doc th,.doc td{border-color:#999}
        """.trimIndent()
        // Only the reader's own script may run; anything a file brings stays inert.
        val policy = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src " + (nonce?.let { "'nonce-$it'" } ?: "'none'")
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1${if (minScale) ", minimum-scale=1" else ""}\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$policy\">" +
            "<style>$css\n$extraCss</style></head><body$bodyAttrs>$body</body></html>"
    }

    // --- Markdown ---

    private val mdExtensions = listOf(
        TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create(), AutolinkExtension.create(),
    )
    private val mdParser: Parser by lazy { Parser.builder().extensions(mdExtensions).build() }
    private val mdRenderer: HtmlRenderer by lazy { HtmlRenderer.builder().extensions(mdExtensions).build() }

    /**
     * [markdown] typesets LaTeX through this: the formula and whether it is display maths, to HTML
     * (see [mathHtml]), or null to leave its source as written.
     */
    fun interface MathTypeset { fun html(latex: String, display: Boolean): String? }

    /** [source] is the Markdown file; [image] reads a local image it links to (path as written), or null. */
    fun markdown(source: String, labels: Labels = Labels(), math: MathTypeset? = null, image: ZipRead? = null): String {
        val text = source.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (text.isBlank()) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val (front, body) = splitFrontmatter(text)
        var html = mdRenderer.render(mdParser.parse(obsidianSyntax(body, math)))
        html = callouts(html)
        html = localImages(html, labels, image)
        return frontmatterHtml(front) + html
    }

    /** Splits a leading YAML block (between --- lines) from the Markdown body. */
    internal fun splitFrontmatter(text: String): Pair<String?, String> {
        if (!text.startsWith("---\n")) return null to text
        val end = Regex("^(---|\\.\\.\\.)\\s*$", RegexOption.MULTILINE).find(text, 4) ?: return null to text
        return text.substring(4, end.range.first).trimEnd('\n') to text.substring(end.range.last + 1).trimStart('\n')
    }

    private fun frontmatterHtml(front: String?): String {
        if (front.isNullOrBlank()) return ""
        val rows = ArrayList<Pair<String, MutableList<String>>>()
        for (line in front.lines()) {
            val item = Regex("^\\s+-\\s*(.*)$").find(line)
            val pair = Regex("^([^\\s:#][^:]*):\\s*(.*)$").find(line)
            when {
                item != null && rows.isNotEmpty() -> rows.last().second.add(item.groupValues[1].trim().trim('"', '\''))
                pair != null -> rows.add(pair.groupValues[1].trim() to mutableListOf<String>().apply {
                    pair.groupValues[2].trim().trim('"', '\'').takeIf { it.isNotEmpty() }?.let { add(it) }
                })
            }
        }
        if (rows.isEmpty()) return ""
        return rows.joinToString("", "<div class=\"props\">", "</div>") { (k, v) ->
            "<div><b>${esc(k)}</b>: ${esc(v.joinToString(", "))}</div>"
        }
    }

    private val wikiEmbed = Regex("!\\[\\[([^\\]\\n]+)]]")
    private val wikiLink = Regex("\\[\\[([^\\]\\n]+)]]")
    private val highlight = Regex("==([^=\\n](?:[^\\n]*?[^=\\n])?)==")

    /**
     * Rewrites Obsidian-only syntax ([[links]], ![[embeds]], ==highlights==) into CommonMark, and
     * $maths$ into HTML through [math], leaving code alone.
     */
    internal fun obsidianSyntax(body: String, math: MathTypeset? = null): String {
        val out = StringBuilder(body.length + 64)
        var fence: String? = null
        var mathBlock: MutableList<String>? = null
        for (line in body.split('\n')) {
            val trimmed = line.trimStart()
            val marker = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
            val mathFence = math != null && MATH_FENCE.matches(line)
            when {
                mathBlock != null -> {
                    if (!mathFence) { mathBlock.add(line.trim()); continue }
                    val latex = mathBlock.joinToString(" ").trim()
                    out.append("\n").append(math!!.html(latex, true) ?: "$$\n$latex\n$$").append("\n")
                    mathBlock = null
                }
                fence != null -> { if (marker != null && marker[0] == fence[0] && marker.length >= fence.length) fence = null; out.append(line) }
                marker != null -> { fence = marker; out.append(line) }
                mathFence -> { mathBlock = ArrayList(); continue }
                line.startsWith("    ") || line.startsWith("\t") -> out.append(line)
                else -> out.append(rewriteInline(line, math))
            }
            out.append('\n')
        }
        mathBlock?.let { out.append("$$\n").append(it.joinToString("\n")).append('\n') }
        return out.toString().removeSuffix("\n")
    }

    /** Applies the wikilink/highlight/maths rewrite outside `code spans` only. */
    private fun rewriteInline(line: String, math: MathTypeset?): String {
        if (!line.contains("[[") && !line.contains("==") && (math == null || !line.contains('$'))) return line
        val parts = line.split('`')
        // An odd part is code only when a closing backtick follows it.
        return parts.mapIndexed { i, part ->
            if (i % 2 == 1 && i < parts.lastIndex) part else {
                var p = wikiEmbed.replace(part) { m ->
                    val target = m.groupValues[1].substringBefore('|').trim()
                    if (mimeOf(target) != null) "![](<$target>)" else wikiMarkdown(m.groupValues[1])
                }
                p = wikiLink.replace(p) { wikiMarkdown(it.groupValues[1]) }
                p = highlight.replace(p) { "<mark>${it.groupValues[1]}</mark>" }
                if (math != null) mathSpans(p, math) else p
            }
        }.joinToString("`")
    }

    /** Replaces each $inline$ or $$display$$ span in [text] with its typeset HTML, by the note import's rules. */
    private fun mathSpans(text: String, math: MathTypeset): String {
        if ('$' !in text) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val span = if (text[i] == '$' && (i == 0 || text[i - 1] != '\\')) mathSpan(text, i) else null
            if (span == null) { out.append(text[i]); i++; continue }
            val n = if (span.second) 2 else 1
            val html = math.html(text.substring(i + n, span.first).trim(), span.second)
            out.append(html ?: text.substring(i, span.first + n))
            i = span.first + n
        }
        return out.toString()
    }

    /** A typeset formula as an alpha [png] tinted with the text colour, so it follows the theme; sizes in CSS px. */
    fun mathHtml(latex: String, png: ByteArray, width: Double, ascent: Double, descent: Double, display: Boolean): String {
        fun px(v: Double) = String.format(java.util.Locale.ROOT, "%.2fpx", v)
        val url = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        val span = "<span class=\"math\" role=\"img\" aria-label=\"${esc(latex)}\" style=\"width:${px(width)};height:${px(ascent + descent)};" +
            "vertical-align:-${px(descent)};-webkit-mask-image:url($url)\"></span>"
        return if (display) "<div class=\"math-block\">$span</div>" else span
    }

    private fun wikiMarkdown(inner: String): String {
        val target = inner.substringBefore('|').trim()
        val alias = inner.substringAfter('|', "").trim()
        val file = target.substringBefore('#').trim()
        val shown = alias.ifEmpty { if (file.isEmpty()) target.removePrefix("#") else target.replace("#", " > ") }
        val label = shown.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
        if (file.isEmpty()) return label
        return "[$label](<$WIKI_SCHEME:${percent(file)}>)"
    }

    private fun percent(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private val calloutStart = Regex("<blockquote>\\s*<p>\\[!([A-Za-z0-9_-]+)][+-]?[ \\t]*([^\\n<]*)(\\n|</p>)")

    /** Obsidian callouts (> [!note] Title) as styled boxes instead of a literal "[!note]". */
    internal fun callouts(html: String): String = calloutStart.replace(html) { m ->
        val type = m.groupValues[1].lowercase()
        val title = m.groupValues[2].trim().ifEmpty { type.replaceFirstChar { it.uppercase() } }
        val rest = if (m.groupValues[3] == "</p>") "" else "<p>"
        "<blockquote class=\"callout callout-$type\"><p class=\"callout-title\">$title</p>$rest"
    }

    private val imgTag = Regex("<img\\s[^>]*>")
    private val attr = { name: String -> Regex("\\s$name=\"([^\"]*)\"") }

    /** Local images are embedded through [read]; the rest (not synced, or remote with the network off) become their alt text. */
    private fun localImages(html: String, labels: Labels, read: ZipRead?): String {
        val images = read?.let { Images(it, labels) }
        return imgTag.replace(html) { m ->
            val src = attr("src").find(m.value)?.groupValues?.get(1).orEmpty()
            val local = images != null && !src.contains("://") && mimeOf(src) != null
            val alt = attr("alt").find(m.value)?.groupValues?.get(1).orEmpty()
            when {
                src.startsWith("data:") -> m.value
                local -> images!!.tag(java.net.URLDecoder.decode(src.replace("&amp;", "&").replace("+", "%2B"), "UTF-8"), alt)
                else -> "<span class=\"missing\">[${alt.ifEmpty { labels.image }}]</span>"
            }
        }
    }

    // --- Images ---

    /** A picture file shown on its own, at any size (the embed budget is for images inside documents). */
    fun image(bytes: ByteArray, name: String): String =
        "<img src=\"data:${mimeOf(name)};base64,${Base64.getEncoder().encodeToString(bytes)}\" alt=\"${esc(name)}\">"

    // --- CSV ---

    fun csv(source: String, labels: Labels = Labels()): String {
        val text = source.removePrefix("\uFEFF")
        if (text.isBlank()) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val rows = parseCsv(text, detectDelimiter(text), MAX_CSV_ROWS + 1)
        val truncated = rows.size > MAX_CSV_ROWS
        val shown = if (truncated) rows.subList(0, MAX_CSV_ROWS) else rows
        val width = shown.maxOf { it.size }
        val sb = StringBuilder("<table class=\"csv\"><thead><tr>")
        for (c in 0 until width) sb.append("<th>").append(esc(shown[0].getOrElse(c) { "" })).append("</th>")
        sb.append("</tr></thead><tbody>")
        for (r in 1 until shown.size) {
            sb.append("<tr>")
            for (c in 0 until width) {
                val v = shown[r].getOrElse(c) { "" }
                sb.append(if (numeric.matches(v.trim())) "<td class=\"num\">" else "<td>").append(esc(v)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        if (truncated) sb.append("<p class=\"note\">").append(esc(labels.truncatedRows(MAX_CSV_ROWS))).append("</p>")
        return sb.toString()
    }

    private val numeric = Regex("^[-+]?[\\d\\s.,]*\\d[%]?$")

    /** The delimiter that splits the first line most often, outside quotes. */
    internal fun detectDelimiter(text: String): Char {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return listOf(',', ';', '\t', '|').maxBy { d ->
            var n = 0
            var quoted = false
            for (ch in first) { if (ch == '"') quoted = !quoted else if (ch == d && !quoted) n++ }
            n
        }
    }

    /** RFC 4180 rows (quoted fields, doubled quotes, newlines in quotes), at most [limit] of them. */
    internal fun parseCsv(text: String, delimiter: Char, limit: Int = Int.MAX_VALUE): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endRow() {
            row.add(field.toString()); field.setLength(0)
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = ArrayList()
        }
        while (i < text.length && rows.size < limit) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> if (field.isEmpty()) quoted = true else field.append(ch)
                delimiter -> { row.add(field.toString()); field.setLength(0) }
                '\r' -> Unit
                '\n' -> endRow()
                else -> field.append(ch)
            }
            i++
        }
        if (rows.size < limit && (field.isNotEmpty() || row.isNotEmpty())) endRow()
        return rows
    }

    // --- shared XML helpers ---

    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""

    private fun XmlPullParser.attr(localName: String): String? {
        for (i in 0 until attributeCount) if (local(getAttributeName(i)) == localName) return getAttributeValue(i)
        return null
    }

    /** The relationship id (r:id), which can sit beside an unprefixed id attribute. */
    private fun XmlPullParser.relId(): String? {
        for (i in 0 until attributeCount) getAttributeName(i).let { if (it.contains(':') && local(it) == "id") return getAttributeValue(i) }
        return null
    }

    /** Streams [bytes] through a fresh parser, calling [onStart]/[onText]/[onEnd] with local tag names. */
    private inline fun walk(
        newParser: () -> XmlPullParser, bytes: ByteArray,
        onStart: (XmlPullParser, String) -> Unit, onText: (String) -> Unit = {}, onEnd: (String) -> Unit = {},
    ) {
        val p = newParser()
        p.setInput(bytes.inputStream(), "UTF-8")
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> onStart(p, local(p.name))
                XmlPullParser.TEXT -> onText(p.text)
                XmlPullParser.END_TAG -> onEnd(local(p.name))
            }
            e = p.next()
        }
    }

    private class Rel(val target: String, val type: String, val external: Boolean)

    /** A part's relationships (id -> resolved target), from its _rels file. [baseDir] is the part's folder. */
    private fun rels(newParser: () -> XmlPullParser, bytes: ByteArray?, baseDir: String): Map<String, Rel> {
        if (bytes == null) return emptyMap()
        val out = HashMap<String, Rel>()
        walk(newParser, bytes, onStart = { p, name ->
            if (name == "Relationship") {
                val id = p.attr("Id") ?: return@walk
                val target = p.attr("Target") ?: return@walk
                val external = p.attr("TargetMode") == "External"
                out[id] = Rel(if (external) target else resolvePath(baseDir, target), p.attr("Type").orEmpty(), external)
            }
        })
        return out
    }

    internal fun resolvePath(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = ArrayList(baseDir.split('/').filter { it.isNotEmpty() })
        for (seg in target.split('/')) when (seg) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(seg)
        }
        return parts.joinToString("/")
    }

    /** Embeds images as data URIs within an overall byte budget. */
    private class Images(private val zip: ZipRead, private val labels: Labels) {
        private var spent = 0L
        fun tag(path: String, alt: String = ""): String {
            val mime = mimeOf(path)
            val bytes = if (mime == null) null else zip.read(path)
            if (bytes == null || bytes.size > MAX_IMAGE_BYTES || spent + bytes.size > MAX_IMAGE_TOTAL) {
                return "<span class=\"missing\">[${alt.ifEmpty { esc(labels.image) }}]</span>"
            }
            spent += bytes.size
            return "<img src=\"data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}\" alt=\"$alt\">"
        }
    }

    private fun mimeOf(path: String): String? = when (path.substringBefore('?').substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> null
    }

    private fun isOff(v: String?) = v == "0" || v == "false" || v == "off" || v == "none"

    private fun styled(text: String, bold: Boolean, italic: Boolean, underline: Boolean, strike: Boolean, vert: String?): String {
        if (text.isEmpty()) return ""
        var s = esc(text)
        if (bold) s = "<b>$s</b>"
        if (italic) s = "<i>$s</i>"
        if (underline) s = "<u>$s</u>"
        if (strike) s = "<s>$s</s>"
        if (vert == "superscript") s = "<sup>$s</sup>" else if (vert == "subscript") s = "<sub>$s</sub>"
        return s
    }

    // --- DOCX ---

    fun docx(zip: ZipRead, newParser: () -> XmlPullParser, labels: Labels = Labels()): String {
        val document = zip.read("word/document.xml") ?: throw IllegalArgumentException("not a Word document")
        val rels = rels(newParser, zip.read("word/_rels/document.xml.rels"), "word")
        val headings = docxHeadings(newParser, zip.read("word/styles.xml"))
        val (defaults, styleCss) = docxStyles(newParser, zip.read("word/styles.xml"))
        var pageSize = 21.0 to 29.7
        var margins = listOf(2.54, 2.54, 2.54, 2.54)
        val ordered = docxNumbering(newParser, zip.read("word/numbering.xml"))
        val images = Images(zip, labels)

        val out = StringBuilder()
        val lists = ArrayList<Boolean>() // open list levels, true = ordered
        fun closeLists() { while (lists.isNotEmpty()) out.append(if (lists.removeAt(lists.lastIndex)) "</ol>" else "</ul>") }

        class Para { val html = StringBuilder(); var style: String? = null; var numId: String? = null; var ilvl = 0; var align: String? = null; val css = LinkedHashMap<String, String>() }
        // A stack, because a text box inside a paragraph holds paragraphs of its own.
        val paras = ArrayList<Para>()
        var inPPr = false
        var inRPr = false
        var inText = false
        var skipDepth = 0 // inside mc:Fallback, which repeats mc:Choice
        var bold = false; var italic = false; var underline = false; var strike = false; var vert: String? = null
        val runCss = LinkedHashMap<String, String>()
        val linkOpen = ArrayList<Boolean>()

        walk(newParser, document, onStart = { p, name ->
            if (skipDepth > 0) { skipDepth++; return@walk }
            when (name) {
                "Fallback" -> skipDepth = 1
                "tbl" -> { closeLists(); out.append("<table>") }
                "tr" -> out.append("<tr>")
                "tc" -> out.append("<td>")
                "p" -> paras.add(Para())
                "pPr" -> inPPr = true
                "pStyle" -> if (inPPr) paras.lastOrNull()?.style = p.attr("val")
                "numId" -> if (inPPr) paras.lastOrNull()?.numId = p.attr("val")
                "ilvl" -> if (inPPr) paras.lastOrNull()?.ilvl = p.attr("val")?.toIntOrNull() ?: 0
                "jc" -> if (inPPr) paras.lastOrNull()?.align = p.attr("val")
                "spacing", "ind" -> if (inPPr) paras.lastOrNull()?.let { docxCss(p, name, it.css) }
                "sz", "color", "rFonts", "highlight", "shd" -> if (inRPr) docxCss(p, name, runCss)
                "pgSz" -> { val w = twipsCm(p.attr("w")); val h = twipsCm(p.attr("h")); if (w != null && h != null && w > 0 && h > 0) pageSize = w to h }
                "pgMar" -> margins = listOf("top", "right", "bottom", "left").mapIndexed { i, k -> twipsCm(p.attr(k))?.coerceAtLeast(0.0) ?: margins[i] }
                "r" -> { bold = false; italic = false; underline = false; strike = false; vert = null; runCss.clear() }
                "rPr" -> inRPr = !inPPr
                "b" -> if (inRPr) bold = !isOff(p.attr("val"))
                "i" -> if (inRPr) italic = !isOff(p.attr("val"))
                "u" -> if (inRPr) underline = !isOff(p.attr("val"))
                "strike", "dstrike" -> if (inRPr) strike = !isOff(p.attr("val"))
                "vertAlign" -> if (inRPr) vert = p.attr("val")
                "t" -> inText = true
                "tab" -> if (!inPPr) paras.lastOrNull()?.html?.append("&emsp;")
                "br", "cr" -> paras.lastOrNull()?.html?.append("<br>")
                "hyperlink" -> {
                    val href = p.relId()?.let { rels[it] }?.takeIf { it.external }?.target
                        ?: p.attr("anchor")?.let { "#$it" }
                    if (href != null) paras.lastOrNull()?.html?.append("<a href=\"${esc(href)}\">")
                    linkOpen.add(href != null)
                }
                "blip" -> p.attr("embed")?.let { rels[it] }?.let { paras.lastOrNull()?.html?.append(images.tag(it.target)) }
                "imagedata" -> p.relId()?.let { rels[it] }?.let { paras.lastOrNull()?.html?.append(images.tag(it.target)) }
            }
        }, onText = {
            if (inText && skipDepth == 0) paras.lastOrNull()?.html?.let { h ->
                val text = styled(it, bold, italic, underline, strike, vert)
                if (runCss.isEmpty() || text.isEmpty()) h.append(text) else h.append("<span style=\"").append(cssOf(runCss)).append("\">").append(text).append("</span>")
            }
        }, onEnd = { name ->
            if (skipDepth > 0) { skipDepth--; return@walk }
            when (name) {
                "t" -> inText = false
                "pPr" -> inPPr = false
                "rPr" -> inRPr = false
                "hyperlink" -> if (linkOpen.removeLastOrNull() == true) paras.lastOrNull()?.html?.append("</a>")
                "tc" -> { closeLists(); out.append("</td>") }
                "tr" -> out.append("</tr>")
                "tbl" -> out.append("</table>")
                "p" -> {
                    val para = paras.removeLastOrNull() ?: return@walk
                    val content = para.html.toString()
                    val align = para.align
                    val ilvl = para.ilvl
                    val level = para.style?.let { headings[it] ?: headingFromId(it) }
                    val listId = para.numId?.takeIf { it != "0" }
                    // The paragraph's style, then its own settings over it.
                    val look = LinkedHashMap<String, String>().apply {
                        // Spacing is not inherited, so the base paragraph spacing goes on each paragraph.
                        defaults.filterKeys { it.startsWith("margin") || it == "text-indent" }.let(::putAll)
                        para.style?.let { styleCss[it] }?.let(::putAll)
                        putAll(para.css)
                    }
                    val styleAttr = if (look.isEmpty()) "" else " style=\"${cssOf(look)}\""
                    when {
                        level != null && level > 0 -> { closeLists(); out.append("<h$level$styleAttr>$content</h$level>") }
                        level == 0 -> { closeLists(); out.append("<p class=\"subtitle\">$content</p>") }
                        listId != null -> {
                            val isOrdered = ordered[listId]?.get(ilvl) ?: false
                            val depth = ilvl.coerceIn(0, 8) + 1
                            while (lists.size > depth) out.append(if (lists.removeAt(lists.lastIndex)) "</ol>" else "</ul>")
                            if (lists.size == depth && lists.last() != isOrdered) out.append(if (lists.removeAt(lists.lastIndex)) "</ol>" else "</ul>")
                            while (lists.size < depth) { lists.add(isOrdered); out.append(if (isOrdered) "<ol>" else "<ul>") }
                            out.append("<li>$content</li>")
                        }
                        content.isBlank() -> { closeLists(); out.append("<p class=\"gap\"></p>") }
                        else -> {
                            closeLists()
                            when (align) { "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "justify"; "left", "start" -> "left"; else -> null }?.let { look["text-align"] = it }
                            out.append(if (look.isEmpty()) "<p>" else "<p style=\"${cssOf(look)}\">").append(content).append("</p>")
                        }
                    }
                }
            }
        })
        closeLists()
        if (out.isBlank()) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        return docPage(pageSize, margins, cssOf(defaults.filterKeys { !it.startsWith("margin") && it != "text-indent" }), out.toString())
    }

    /** Twentieths of a point, as Word measures, in centimetres. */
    private fun twipsCm(v: String?): Double? = v?.toDoubleOrNull()?.let { it / 1440 * 2.54 }

    private fun twipsPt(v: String?): String? = v?.toDoubleOrNull()?.let { String.format(java.util.Locale.ROOT, "%.1fpt", it / 20) }

    internal fun cssOf(css: Map<String, String>) = css.entries.joinToString("") { "${it.key}:${it.value};" }

    /**
     * A document laid out on pages: its page size and margins in cm for the reader's pages, and
     * its base look ([css]). It keeps its own colours, like paper, whatever the reader's theme.
     */
    internal fun docPage(size: Pair<Double, Double>, margins: List<Double>, css: String, body: String): String {
        fun cm(v: Double) = String.format(java.util.Locale.ROOT, "%.3f", v)
        val page = (listOf(size.first, size.second) + margins).joinToString(" ") { cm(it) }
        // Scrolling, it is one long sheet as wide as its page, its margins narrower on a small screen.
        val sheet = "width:${cm(size.first)}cm;padding:" + margins.joinToString(" ") { "min(${cm(it)}cm,6vw)" } + ";"
        return "<article class=\"doc\" data-page=\"$page\" style=\"$sheet$css\">$body</article>"
    }

    /** One Word paragraph or run setting as CSS, into [css]. */
    private fun docxCss(p: XmlPullParser, name: String, css: MutableMap<String, String>) {
        when (name) {
            "jc" -> when (p.attr("val")) { "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "justify"; "left", "start" -> "left"; else -> null }?.let { css["text-align"] = it }
            "spacing" -> {
                twipsPt(p.attr("before"))?.let { css["margin-top"] = it }
                twipsPt(p.attr("after"))?.let { css["margin-bottom"] = it }
                p.attr("line")?.toDoubleOrNull()?.let { line ->
                    css["line-height"] = if ((p.attr("lineRule") ?: "auto") == "auto") String.format(java.util.Locale.ROOT, "%.2f", line / 240) else twipsPt(line.toString())!!
                }
            }
            "ind" -> {
                (twipsPt(p.attr("left")) ?: twipsPt(p.attr("start")))?.let { css["margin-left"] = it }
                (twipsPt(p.attr("right")) ?: twipsPt(p.attr("end")))?.let { css["margin-right"] = it }
                twipsPt(p.attr("firstLine"))?.let { css["text-indent"] = it }
                twipsPt(p.attr("hanging"))?.let { css["text-indent"] = "-$it" }
            }
            "sz" -> p.attr("val")?.toDoubleOrNull()?.let { css["font-size"] = String.format(java.util.Locale.ROOT, "%.1fpt", it / 2) }
            "color" -> p.attr("val")?.takeIf { it.length == 6 && it != "auto" }?.let { css["color"] = "#$it" }
            "rFonts" -> p.attr("ascii")?.let { css["font-family"] = "'${it.replace("'", "")}',sans-serif" }
            "highlight" -> p.attr("val")?.takeIf { it != "none" }?.let { css["background"] = it.lowercase() }
            "shd" -> p.attr("fill")?.takeIf { it.length == 6 && it != "auto" }?.let { css["background"] = "#$it" }
            "b" -> css["font-weight"] = if (isOff(p.attr("val"))) "normal" else "bold"
            "i" -> css["font-style"] = if (isOff(p.attr("val"))) "normal" else "italic"
        }
    }

    /** The document's default look, and each style's own look over the styles it is based on. */
    private fun docxStyles(newParser: () -> XmlPullParser, bytes: ByteArray?): Pair<Map<String, String>, Map<String, Map<String, String>>> {
        val defaults = LinkedHashMap<String, String>()
        if (bytes == null) return defaults to emptyMap()
        val own = HashMap<String, LinkedHashMap<String, String>>()
        val basedOn = HashMap<String, String>()
        var id: String? = null
        var normal: String? = null
        var inDefaults = false
        walk(newParser, bytes, onStart = { p, name ->
            when (name) {
                "docDefaults" -> inDefaults = true
                "style" -> {
                    id = p.attr("styleId")?.takeIf { p.attr("type") == "paragraph" }
                    if (id != null && (p.attr("default") == "1" || p.attr("default") == "true")) normal = id
                }
                "basedOn" -> id?.let { s -> p.attr("val")?.let { basedOn[s] = it } }
                "jc", "spacing", "ind", "sz", "color", "rFonts", "b", "i" -> when {
                    inDefaults -> docxCss(p, name, defaults)
                    id != null -> docxCss(p, name, own.getOrPut(id!!) { LinkedHashMap() })
                }
            }
        }, onEnd = { name ->
            if (name == "docDefaults") inDefaults = false
            if (name == "style") id = null
        })
        fun resolve(s: String, depth: Int = 0): Map<String, String> {
            val parent = basedOn[s]?.takeIf { depth < 10 }?.let { resolve(it, depth + 1) }.orEmpty()
            return LinkedHashMap(parent).apply { own[s]?.let(::putAll) }
        }
        // Paragraphs without a style take the default one, so it is the document's base look.
        normal?.let { defaults.putAll(resolve(it)) }
        return defaults to own.keys.associateWith { resolve(it) }
    }

    private fun headingFromId(id: String): Int? {
        val lower = id.lowercase()
        if (lower == "title") return 1
        if (lower == "subtitle") return 0
        return Regex("^heading\\s*(\\d)$").find(lower)?.groupValues?.get(1)?.toInt()?.coerceIn(1, 6)
    }

    /** Style id -> heading level (0 = subtitle), from each style's name or outline level. */
    private fun docxHeadings(newParser: () -> XmlPullParser, bytes: ByteArray?): Map<String, Int> {
        if (bytes == null) return emptyMap()
        val out = HashMap<String, Int>()
        var id: String? = null
        walk(newParser, bytes, onStart = { p, name ->
            when (name) {
                "style" -> id = p.attr("styleId")
                "name" -> id?.let { sid -> headingFromId(p.attr("val").orEmpty())?.let { out[sid] = it } }
                "outlineLvl" -> id?.let { sid -> p.attr("val")?.toIntOrNull()?.takeIf { it in 0..5 }?.let { if (sid !in out) out[sid] = it + 1 } }
            }
        }, onEnd = { if (it == "style") id = null })
        return out
    }

    /** numId -> (level -> ordered), resolving each num through its abstract definition. */
    private fun docxNumbering(newParser: () -> XmlPullParser, bytes: ByteArray?): Map<String, Map<Int, Boolean>> {
        if (bytes == null) return emptyMap()
        val abstract = HashMap<String, HashMap<Int, Boolean>>()
        val numToAbstract = HashMap<String, String>()
        var absId: String? = null
        var lvl = -1
        var numId: String? = null
        walk(newParser, bytes, onStart = { p, name ->
            when (name) {
                "abstractNum" -> absId = p.attr("abstractNumId")
                "lvl" -> lvl = p.attr("ilvl")?.toIntOrNull() ?: -1
                "numFmt" -> {
                    val a = absId
                    if (a != null && lvl >= 0) abstract.getOrPut(a) { HashMap() }[lvl] = p.attr("val").let { it != "bullet" && it != "none" }
                }
                "num" -> numId = p.attr("numId")
                "abstractNumId" -> numId?.let { n -> p.attr("val")?.let { numToAbstract[n] = it } }
            }
        }, onEnd = { name ->
            when (name) {
                "abstractNum" -> absId = null
                "lvl" -> lvl = -1
                "num" -> numId = null
            }
        })
        return numToAbstract.mapValues { (_, a) -> abstract[a].orEmpty() }
    }

    // --- PPTX ---

    private class SlideBlock(val title: Boolean) {
        var x = Long.MAX_VALUE
        var y = Long.MAX_VALUE
        val html = StringBuilder()
    }

    fun pptx(zip: ZipRead, newParser: () -> XmlPullParser, labels: Labels = Labels()): String {
        val presentation = zip.read("ppt/presentation.xml") ?: throw IllegalArgumentException("not a PowerPoint file")
        val presRels = rels(newParser, zip.read("ppt/_rels/presentation.xml.rels"), "ppt")
        val order = ArrayList<String>()
        var size: Pair<Long, Long>? = null
        walk(newParser, presentation, onStart = { p, name ->
            if (name == "sldId") p.relId()?.let { presRels[it] }?.let { order.add(it.target) }
            if (name == "sldSz") size = (p.attr("cx")?.toLongOrNull() ?: 0L) to (p.attr("cy")?.toLongOrNull() ?: 0L)
        })
        if (order.isEmpty()) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val images = Images(zip, labels)
        val deck = size?.takeIf { it.first > 0 && it.second > 0 }?.let { Deck(zip, newParser, it.first, it.second) }
        val out = StringBuilder()
        order.forEachIndexed { i, path ->
            val bytes = zip.read(path) ?: return@forEachIndexed
            val dir = path.substringBeforeLast('/', "")
            val slideRels = rels(newParser, zip.read("$dir/_rels/${path.substringAfterLast('/')}.rels"), dir)
            out.append("<section class=\"slide\"><div class=\"slide-no\">").append(esc(labels.slide(i + 1, order.size))).append("</div>")
            // Drawn where the deck places it when its size is known; listed top to bottom otherwise.
            out.append(deck?.let { runCatching { it.slide(bytes, slideRels, images) }.getOrNull() } ?: slideBody(bytes, slideRels, images, newParser, notes = false))
            slideRels.values.firstOrNull { it.type.endsWith("/notesSlide") }?.let { zip.read(it.target) }?.let { notes ->
                val text = slideBody(notes, emptyMap(), images, newParser, notes = true)
                if (text.isNotBlank()) out.append("<div class=\"notes\"><b>").append(esc(labels.notes)).append("</b>").append(text).append("</div>")
            }
            out.append("</section>")
        }
        return out.toString()
    }

    /** A length such as 2.5cm, 12pt or 1in, in centimetres. */
    internal fun odfCm(v: String?): Double? {
        val m = Regex("(-?[0-9.]+)([a-z]*)").matchEntire(v?.trim() ?: return null) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) { "cm", "" -> n; "mm" -> n / 10; "in" -> n * 2.54; "pt" -> n * 2.54 / 72; "pc" -> n * 2.54 / 6; "px" -> n * 2.54 / 96; else -> null }
    }

    /** An OpenDocument presentation, each page drawn where its shapes sit on its own colours, like [pptx]. */
    fun odp(zip: ZipRead, newParser: () -> XmlPullParser, labels: Labels = Labels()): String {
        val content = zip.read("content.xml") ?: throw IllegalArgumentException("not an OpenDocument file")
        // Every named style's properties, flattened, with "parent" pointing at the style it builds on.
        val styles = HashMap<String, HashMap<String, String>>()
        val layouts = HashMap<String, Pair<Double, Double>>()
        val masters = HashMap<String, Pair<String?, String?>>() // page layout, page style
        val fills = HashMap<String, String>() // gradients as CSS, fill images as their path
        for (bytes in listOfNotNull(zip.read("styles.xml"), content)) {
            var style: HashMap<String, String>? = null
            var layout: String? = null
            walk(newParser, bytes, onStart = { p, name ->
                when (name) {
                    "style" -> style = styles.getOrPut(p.attr("name") ?: "") { HashMap() }.also { s -> p.attr("parent-style-name")?.let { s["parent"] = it } }
                    "graphic-properties", "paragraph-properties", "text-properties", "drawing-page-properties" ->
                        style?.let { s -> for (i in 0 until p.attributeCount) s[local(p.getAttributeName(i))] = p.getAttributeValue(i) }
                    "page-layout" -> layout = p.attr("name")
                    "page-layout-properties" -> layout?.let { l ->
                        val w = odfCm(p.attr("page-width")); val h = odfCm(p.attr("page-height"))
                        if (w != null && h != null && w > 0 && h > 0) layouts[l] = w to h
                    }
                    "master-page" -> p.attr("name")?.let { masters[it] = p.attr("page-layout-name") to p.attr("style-name") }
                    "gradient" -> p.attr("name")?.let { fills[it] = "linear-gradient(${p.attr("start-color") ?: "#ffffff"},${p.attr("end-color") ?: "#ffffff"})" }
                    "fill-image" -> p.attr("name")?.let { n -> p.attr("href")?.let { fills[n] = it } }
                }
            }, onEnd = { name -> if (name == "style") style = null })
        }
        fun look(name: String?, key: String): String? {
            var n = name
            repeat(8) {
                val s = styles[n ?: return null] ?: return null
                s[key]?.let { return it }
                n = s["parent"]
            }
            return null
        }
        val images = Images(zip, labels)
        var size = 28.0 to 21.0
        fun pct(v: Double, of: Double) = String.format(java.util.Locale.ROOT, "%.3f%%", v * 100 / of)
        fun textCss(style: String?): String {
            if (style == null) return ""
            val css = StringBuilder()
            look(style, "color")?.let { css.append("color:").append(it).append(';') }
            odfCm(look(style, "font-size"))?.let { css.append("font-size:").append(String.format(java.util.Locale.ROOT, "%.3fcqw", it / size.first * 100)).append(';') }
            look(style, "font-weight")?.let { if (it == "bold" || (it.toIntOrNull() ?: 0) >= 600) css.append("font-weight:bold;") }
            if (look(style, "font-style") == "italic") css.append("font-style:italic;")
            look(style, "text-underline-style")?.let { if (it != "none") css.append("text-decoration:underline;") }
            return css.toString()
        }
        // A fill as CSS and, for a picture fill, the picture's path.
        fun fillOf(style: String?): Pair<String?, String?> = when (look(style, "fill")) {
            "solid" -> look(style, "fill-color") to null
            "gradient" -> fills[look(style, "fill-gradient-name")] to null
            "bitmap" -> null to fills[look(style, "fill-image-name")]
            else -> null to null
        }

        val total = Regex("<draw:page[\\s>]").findAll(String(content, Charsets.UTF_8)).count()
        if (total == 0) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val out = StringBuilder()
        var number = 0
        var depth = 0
        var slide: StringBuilder? = null
        var background = "#ffffff"
        var notes = StringBuilder()
        var inNotes = false
        var shape: StringBuilder? = null
        var shapeAt = 0
        var shapeCss = ""
        var picture = false
        var para: StringBuilder? = null
        var paraStyle: String? = null
        val spans = ArrayList<String?>()
        var lists = 0
        walk(newParser, content, onStart = { p, name ->
            depth++
            when (name) {
                "page" -> if (!inNotes) {
                    slide = StringBuilder(); notes = StringBuilder(); background = "#ffffff"
                    val master = masters[p.attr("master-page-name")]
                    size = master?.first?.let { layouts[it] } ?: size
                    // shortcut: the master page's own shapes (logos, footers) are not drawn, only its fill.
                    val from = listOf(p.attr("style-name"), master?.second).firstOrNull { look(it, "fill").let { f -> f != null && f != "none" } }
                    val (css, image) = fillOf(from)
                    css?.let { background = it }
                    image?.let { slide?.append("<div class=\"shape pic\" style=\"left:0;top:0;width:100%;height:100%\">")?.append(images.tag(it))?.append("</div>") }
                }
                "notes" -> inNotes = true
                "frame", "custom-shape", "rect", "ellipse" -> if (shape == null && !inNotes) {
                    val x = odfCm(p.attr("x")); val y = odfCm(p.attr("y")); val w = odfCm(p.attr("width")); val h = odfCm(p.attr("height"))
                    if (x != null && y != null && w != null && h != null) {
                        val graphic = p.attr("style-name")
                        val css = StringBuilder("left:${pct(x, size.first)};top:${pct(y, size.second)};width:${pct(w, size.first)};height:${pct(h, size.second)};")
                        css.append(String.format(java.util.Locale.ROOT, "font-size:%.3fcqw;", 18 * 2.54 / 72 / size.first * 100))
                        css.append(textCss(graphic)).append(textCss(p.attr("text-style-name")))
                        fillOf(graphic).first?.let { css.append("background:").append(it).append(';') }
                        if (name == "ellipse") css.append("border-radius:50%;")
                        css.append("justify-content:").append(when (look(graphic, "textarea-vertical-align")) { "middle" -> "center"; "bottom" -> "flex-end"; else -> "flex-start" }).append(';')
                        shape = StringBuilder(); shapeAt = depth; shapeCss = css.toString(); picture = false
                    }
                }
                "image" -> if (!picture) shape?.let { s -> p.attr("href")?.let { s.append(images.tag(it)); picture = true } }
                "p", "h" -> { para = StringBuilder(); paraStyle = p.attr("style-name"); spans.clear() }
                "span" -> spans.add(p.attr("style-name"))
                "s" -> para?.append("&nbsp;".repeat(p.attr("c")?.toIntOrNull()?.coerceIn(1, 50) ?: 1))
                "tab" -> para?.append(' ')
                "line-break" -> para?.append("<br>")
                "list" -> lists++
            }
        }, onText = { t ->
            para?.let { b ->
                // Notes sit outside the slide, so they keep the reader's own type.
                val css = if (inNotes) "" else textCss(spans.lastOrNull())
                if (css.isEmpty()) b.append(esc(t)) else b.append("<span style=\"").append(css).append("\">").append(esc(t)).append("</span>")
            }
        }, onEnd = { name ->
            when (name) {
                "span" -> spans.removeLastOrNull()
                "list" -> lists--
                "p", "h" -> para?.let { text ->
                    if (inNotes) {
                        if (text.isNotBlank()) notes.append("<p>").append(text).append("</p>")
                    } else shape?.let { s ->
                        val css = StringBuilder()
                        look(paraStyle, "text-align")?.let { a ->
                            css.append("text-align:").append(when (a) { "center" -> "center"; "end", "right" -> "right"; "justify" -> "justify"; else -> "left" }).append(';')
                        }
                        css.append(textCss(paraStyle))
                        val bullet = lists > 0 && text.isNotBlank()
                        if (bullet) css.append(String.format(java.util.Locale.ROOT, "padding-left:%.1fem;text-indent:-1.1em;", 1.1 + (lists - 1) * 1.2))
                        s.append(if (css.isEmpty()) "<p>" else "<p style=\"$css\">")
                        if (bullet) s.append("• ")
                        s.append(if (text.isBlank()) "&nbsp;" else text).append("</p>")
                    }
                    para = null
                }
                "frame", "custom-shape", "rect", "ellipse" -> if (depth == shapeAt) shape?.let { s ->
                    if (s.isNotEmpty() || "background:" in shapeCss) {
                        slide?.append(if (picture) "<div class=\"shape pic\" style=\"" else "<div class=\"shape\" style=\"")?.append(shapeCss)?.append("\">")?.append(s)?.append("</div>")
                    }
                    shape = null
                }
                "notes" -> inNotes = false
                "page" -> if (!inNotes) slide?.let { s ->
                    number++
                    out.append("<section class=\"slide\"><div class=\"slide-no\">").append(esc(labels.slide(number, total))).append("</div>")
                    out.append("<div class=\"deck-slide\" style=\"aspect-ratio:").append(String.format(java.util.Locale.ROOT, "%.3f/%.3f", size.first, size.second))
                        .append(";background:").append(background).append(";color:#000000\">").append(s).append("</div>")
                    if (notes.isNotEmpty()) out.append("<div class=\"notes\"><b>").append(esc(labels.notes)).append("</b>").append(notes).append("</div>")
                    out.append("</section>")
                    slide = null
                }
            }
            depth--
        })
        return out.toString()
    }

    /** A placeholder's or shape's box on the slide, in EMU. */
    private class Frame(val x: Long, val y: Long, val cx: Long, val cy: Long)

    /** A slide layout or master as far as the slides drawn on it use it: its placeholders' boxes, background, colours and text sizes. */
    private class Part(
        val frames: Map<String, Frame>,
        val background: String?,
        val colorMap: Map<String, String>,
        val text: Map<String, Pair<Int?, String?>>,
        val rels: Map<String, Rel>,
    )

    /** Reads a DrawingML colour (rgb, scheme or system, with luminance, tint and shade) as CSS hex. */
    private class ColorRead(private val scheme: (String) -> String?) {
        private var rgb: DoubleArray? = null

        /** True when [name] was a colour element or one of its adjustments. */
        fun start(p: XmlPullParser, name: String): Boolean {
            val v = p.attr("val")
            when (name) {
                "srgbClr" -> rgb = v?.let(::parse)
                "schemeClr" -> rgb = v?.let(scheme)?.let(::parse)
                "sysClr" -> rgb = parse(p.attr("lastClr") ?: "000000")
                "lumMod" -> rgb = rgb?.map { it * pct(v) }?.toDoubleArray()
                "lumOff" -> rgb = rgb?.map { it + 255 * pct(v) }?.toDoubleArray()
                "tint" -> rgb = rgb?.map { 255 - (255 - it) * pct(v) }?.toDoubleArray()
                "shade" -> rgb = rgb?.map { it * pct(v) }?.toDoubleArray()
                else -> return false
            }
            return true
        }

        fun end(name: String): String? {
            if (name != "srgbClr" && name != "schemeClr" && name != "sysClr") return null
            val c = rgb ?: return null
            rgb = null
            return "#" + c.joinToString("") { "%02x".format(it.toInt().coerceIn(0, 255)) }
        }

        private fun pct(v: String?) = (v?.toDoubleOrNull() ?: 100000.0) / 100000.0
        private fun parse(hex: String) = hex.takeLast(6).padStart(6, '0').chunked(2).map { (it.toIntOrNull(16) ?: 0).toDouble() }.toDoubleArray()
    }

    /** One deck's slides drawn as the deck lays them out, on its own colours, whatever the reader's theme. */
    private class Deck(private val zip: ZipRead, private val newParser: () -> XmlPullParser, private val width: Long, private val height: Long) {
        private val parts = HashMap<String, Part>()
        private val themes = HashMap<String, Map<String, String>>()
        private val widthPt = width / 12700.0

        private fun theme(path: String): Map<String, String> = themes.getOrPut(path) {
            val out = HashMap<String, String>()
            val bytes = zip.read(path) ?: return@getOrPut out
            var slot: String? = null
            var inScheme = false
            walk(newParser, bytes, onStart = { p, name ->
                when {
                    name == "clrScheme" -> inScheme = true
                    inScheme && slot == null -> slot = name
                    inScheme && name == "srgbClr" -> slot?.let { s -> p.attr("val")?.let { out[s] = it } }
                    inScheme && name == "sysClr" -> slot?.let { s -> out[s] = p.attr("lastClr") ?: "000000" }
                }
            }, onEnd = { name ->
                if (name == "clrScheme") inScheme = false
                if (name == slot) slot = null
            })
            out
        }

        /** The layout or master at [path], reading through to what it is drawn on in turn. */
        private fun part(path: String): Part = parts.getOrPut(path) {
            val bytes = zip.read(path) ?: return@getOrPut Part(emptyMap(), null, emptyMap(), emptyMap(), emptyMap())
            val dir = path.substringBeforeLast('/', "")
            val rels = rels(newParser, zip.read("$dir/_rels/${path.substringAfterLast('/')}.rels"), dir)
            val under = rels.values.firstOrNull { it.type.endsWith("/slideMaster") }?.let { part(it.target) }
            val themeColors = rels.values.firstOrNull { it.type.endsWith("/theme") }?.let { theme(it.target) }
            val colorMap = HashMap(under?.colorMap.orEmpty())
            themeColors?.let { colorMap.putAll(it) }
            val frames = HashMap<String, Frame>()
            val text = HashMap(under?.text.orEmpty())
            var background: String? = null
            val stack = ArrayList<String>()
            val color = ColorRead { colorMap[it] }
            var key: List<String> = emptyList()
            var off: Pair<Long, Long>? = null
            var style: String? = null
            walk(newParser, bytes, onStart = { p, name ->
                stack.add(name)
                if (color.start(p, name)) return@walk
                when (name) {
                    "clrMap" -> for (k in listOf("bg1", "tx1", "bg2", "tx2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")) {
                        p.attr(k)?.let { to -> (themeColors?.get(to) ?: colorMap[to])?.let { colorMap[k] = it } }
                    }
                    "sp" -> { key = emptyList(); off = null }
                    "ph" -> key = listOfNotNull(p.attr("idx")?.let { "idx:$it" }, "type:${p.attr("type") ?: "body"}")
                    "off" -> if ("spPr" in stack) off = (p.attr("x")?.toLongOrNull() ?: 0) to (p.attr("y")?.toLongOrNull() ?: 0)
                    "ext" -> if ("spPr" in stack) off?.let { (x, y) ->
                        val f = Frame(x, y, p.attr("cx")?.toLongOrNull() ?: 0, p.attr("cy")?.toLongOrNull() ?: 0)
                        for (k in key) frames.putIfAbsent(k, f)
                    }
                    "titleStyle" -> style = "title"
                    "bodyStyle" -> style = "body"
                    "otherStyle" -> style = "other"
                    "defRPr" -> if (style != null && "lvl1pPr" in stack) {
                        val s = style!!
                        text[s] = (p.attr("sz")?.toIntOrNull() ?: text[s]?.first) to text[s]?.second
                    }
                }
            }, onEnd = { name ->
                color.end(name)?.let { c ->
                    when {
                        "bg" in stack -> background = c
                        style != null && "defRPr" in stack && "lvl1pPr" in stack -> style?.let { s -> text[s] = text[s]?.first to c }
                    }
                }
                if (name == "titleStyle" || name == "bodyStyle" || name == "otherStyle") style = null
                stack.removeLastOrNull()
            })
            Part(frames, background ?: under?.background, colorMap, text, rels)
        }

        private class Block {
            var frame: Frame? = null
            var keys: List<String> = emptyList()
            var kind = "other"
            var fill: String? = null
            var round: String? = null
            var anchor: String? = null
            var scale = 1.0
            val html = StringBuilder()
        }

        private fun pct(v: Long, of: Long) = String.format(java.util.Locale.ROOT, "%.3f%%", v * 100.0 / of)

        private fun size(hundredths: Int, scale: Double) = String.format(java.util.Locale.ROOT, "%.3fcqw", hundredths / 100.0 * scale / widthPt * 100)

        fun slide(bytes: ByteArray, rels: Map<String, Rel>, images: Images): String {
            val layout = rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.let { part(it.target) }
            val master = layout?.rels?.values?.firstOrNull { it.type.endsWith("/slideMaster") }?.let { part(it.target) }
            val colors = layout?.colorMap.orEmpty()
            val color = ColorRead { colors[it] }
            val blocks = ArrayList<Block>()
            val stack = ArrayList<String>()
            // Each group maps its children's space onto the slide: offset, child offset and both scales.
            val groups = ArrayList<DoubleArray>()
            var groupOff: Pair<Long, Long>? = null
            var groupChild: Pair<Long, Long>? = null
            var groupExt: Pair<Long, Long>? = null
            var block: Block? = null
            var blockDepth = 0
            var background: String? = null
            var off: Pair<Long, Long>? = null
            var para: StringBuilder? = null
            var paraAlign: String? = null
            var paraBullet: Boolean? = null
            var paraLevel = 0
            var run: StringBuilder? = null
            var runCss = StringBuilder()
            var inText = false
            var bold = false; var italic = false; var underline = false; var strike = false
            var table: StringBuilder? = null
            var cell: StringBuilder? = null
            var skip = false

            fun place(x: Long, y: Long, cx: Long, cy: Long): Frame {
                var fx = x.toDouble(); var fy = y.toDouble(); var fw = cx.toDouble(); var fh = cy.toDouble()
                for (g in groups.asReversed()) {
                    fx = g[0] + (fx - g[2]) * g[4]; fy = g[1] + (fy - g[3]) * g[5]; fw *= g[4]; fh *= g[5]
                }
                return Frame(fx.toLong(), fy.toLong(), fw.toLong(), fh.toLong())
            }

            walk(newParser, bytes, onStart = { p, name ->
                stack.add(name)
                if (color.start(p, name)) return@walk
                when (name) {
                    "grpSp" -> { groupOff = null; groupChild = null; groupExt = null; groups.add(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 1.0)) }
                    "sp", "pic", "graphicFrame", "cxnSp" -> if (block == null) { block = Block(); blockDepth = stack.size; off = null; skip = false }
                }
                val b = block
                if (b == null) {
                    // The slide's own tree has a grpSpPr too, often with a zero chExt; only real groups map space.
                    if ("grpSpPr" in stack && groups.isNotEmpty()) when (name) {
                        "off" -> groupOff = (p.attr("x")?.toLongOrNull() ?: 0) to (p.attr("y")?.toLongOrNull() ?: 0)
                        "ext" -> groupExt = (p.attr("cx")?.toLongOrNull() ?: 0) to (p.attr("cy")?.toLongOrNull() ?: 0)
                        "chOff" -> groupChild = (p.attr("x")?.toLongOrNull() ?: 0) to (p.attr("y")?.toLongOrNull() ?: 0)
                        "chExt" -> {
                            val (ox, oy) = groupOff ?: (0L to 0L)
                            val (ex, ey) = groupExt ?: (1L to 1L)
                            val (cxo, cyo) = groupChild ?: (0L to 0L)
                            val cw = p.attr("cx")?.toLongOrNull() ?: ex
                            val ch = p.attr("cy")?.toLongOrNull() ?: ey
                            fun scale(e: Long, c: Long) = if (e > 0 && c > 0) e.toDouble() / c else 1.0
                            groups[groups.lastIndex] = doubleArrayOf(ox.toDouble(), oy.toDouble(), cxo.toDouble(), cyo.toDouble(), scale(ex, cw), scale(ey, ch))
                        }
                    }
                    return@walk
                }
                when (name) {
                    "ph" -> {
                        val type = p.attr("type")
                        b.keys = listOfNotNull(p.attr("idx")?.let { "idx:$it" }, "type:${type ?: "body"}")
                        b.kind = when (type) { "title", "ctrTitle" -> "title"; "dt", "ftr", "sldNum", "hdr", "sldImg" -> { skip = true; "other" }; else -> "body" }
                    }
                    "off" -> if (b.frame == null && ("spPr" in stack || "xfrm" in stack)) off = (p.attr("x")?.toLongOrNull() ?: 0) to (p.attr("y")?.toLongOrNull() ?: 0)
                    "ext" -> if (b.frame == null) off?.let { (x, y) -> b.frame = place(x, y, p.attr("cx")?.toLongOrNull() ?: 0, p.attr("cy")?.toLongOrNull() ?: 0) }
                    "prstGeom" -> b.round = when (p.attr("prst")) { "ellipse" -> "50%"; "roundRect" -> "8%"; else -> null }
                    "bodyPr" -> b.anchor = p.attr("anchor")
                    "normAutofit" -> p.attr("fontScale")?.toDoubleOrNull()?.let { b.scale = it / 100000.0 }
                    "p" -> { para = StringBuilder(); paraAlign = null; paraBullet = null; paraLevel = 0 }
                    "pPr" -> if (para != null) { paraLevel = p.attr("lvl")?.toIntOrNull() ?: 0; paraAlign = p.attr("algn") }
                    "buNone" -> if (para != null) paraBullet = false
                    "buChar", "buAutoNum" -> if (para != null) paraBullet = true
                    "r", "fld" -> { bold = false; italic = false; underline = false; strike = false; runCss = StringBuilder(); run = StringBuilder() }
                    "rPr" -> {
                        bold = p.attr("b") == "1" || p.attr("b") == "true"
                        italic = p.attr("i") == "1" || p.attr("i") == "true"
                        underline = p.attr("u").let { it != null && it != "none" }
                        strike = p.attr("strike").let { it != null && it != "noStrike" }
                        p.attr("sz")?.toIntOrNull()?.let { runCss.append("font-size:").append(size(it, b.scale)).append(';') }
                    }
                    "t" -> inText = true
                    "br" -> para?.append("<br>")
                    "tbl" -> table = StringBuilder("<table>")
                    "tr" -> table?.append("<tr>")
                    "tc" -> if (table != null) cell = StringBuilder()
                    "blip" -> p.attr("embed")?.let { rels[it] }?.let { b.html.append(images.tag(it.target)) }
                }
            }, onText = { t ->
                if (inText) (run ?: para)?.append(styled(t, bold, italic, underline, strike, null))
            }, onEnd = { name ->
                color.end(name)?.let { c ->
                    when {
                        "bg" in stack -> background = c
                        block != null && "rPr" in stack -> runCss.append("color:").append(c).append(';')
                        block != null && "spPr" in stack && "ln" !in stack && stack.getOrNull(stack.size - 2) == "solidFill" -> block?.fill = c
                    }
                }
                val b = block
                if (b != null) when (name) {
                    "t" -> inText = false
                    "r", "fld" -> {
                        run?.let { r -> if (r.isNotEmpty()) para?.append(if (runCss.isEmpty()) r else "<span style=\"$runCss\">$r</span>") }
                        run = null
                    }
                    "p" -> para?.let { text ->
                        val c = cell
                        if (c != null) { if (c.isNotEmpty()) c.append("<br>"); c.append(text) }
                        else {
                            val bullet = paraBullet ?: (b.kind == "body")
                            val css = StringBuilder()
                            when (paraAlign) { "ctr" -> "center"; "r" -> "right"; "just", "dist" -> "justify"; else -> null }?.let { css.append("text-align:").append(it).append(';') }
                            if (bullet && text.isNotBlank()) css.append("padding-left:").append(String.format(java.util.Locale.ROOT, "%.1fem", 1.1 + paraLevel * 1.2)).append(";text-indent:-1.1em;")
                            b.html.append(if (css.isEmpty()) "<p>" else "<p style=\"$css\">")
                            if (bullet && text.isNotBlank()) b.html.append("\u2022\u00a0")
                            b.html.append(if (text.isBlank()) "&nbsp;" else text).append("</p>")
                        }
                        para = null
                    }
                    "tc" -> cell?.let { c -> table?.append("<td>")?.append(c)?.append("</td>"); cell = null }
                    "tr" -> table?.append("</tr>")
                    "tbl" -> table?.let { t -> b.html.append(t).append("</table>"); table = null }
                }
                if (b != null && stack.size == blockDepth) {
                    if (!skip && (b.html.isNotEmpty() || b.fill != null)) blocks.add(b)
                    block = null
                }
                if (name == "grpSp" && block == null) groups.removeLastOrNull()
                stack.removeLastOrNull()
            })

            val bg = background ?: layout?.background ?: master?.background ?: "#ffffff"
            val ink = colors["tx1"]?.let { "#" + it.takeLast(6) } ?: "#000000"
            val out = StringBuilder("<div class=\"deck-slide\" style=\"aspect-ratio:$width/$height;background:$bg;color:$ink\">")
            val loose = StringBuilder()
            for (b in blocks) {
                val frame = b.frame ?: b.keys.firstNotNullOfOrNull { layout?.frames?.get(it) } ?: b.keys.firstNotNullOfOrNull { master?.frames?.get(it) }
                if (frame == null) { loose.append(b.html); continue }
                val (sz, textColor) = layout?.text?.get(b.kind) ?: (null to null)
                val defaultSize = sz ?: when (b.kind) { "title" -> 4400; "body" -> 2800; else -> 1800 }
                val css = StringBuilder("left:${pct(frame.x, width)};top:${pct(frame.y, height)};width:${pct(frame.cx, width)};height:${pct(frame.cy, height)};")
                css.append("font-size:").append(size(defaultSize, b.scale)).append(';')
                textColor?.let { css.append("color:").append(it).append(';') }
                b.fill?.let { css.append("background:").append(it).append(';') }
                b.round?.let { css.append("border-radius:").append(it).append(';') }
                css.append("justify-content:").append(when (b.anchor) { "ctr" -> "center"; "b" -> "flex-end"; else -> if (b.kind == "title") "center" else "flex-start" }).append(';')
                val picture = b.html.startsWith("<img")
                out.append(if (picture) "<div class=\"shape pic\" style=\"" else "<div class=\"shape\" style=\"").append(css).append("\">").append(b.html).append("</div>")
            }
            out.append("</div>")
            if (loose.isNotEmpty()) out.append(loose)
            return out.toString()
        }
    }

    /** One slide's shapes as HTML, titles first then top-to-bottom; with [notes], only the speaker-notes body text. */
    private fun slideBody(bytes: ByteArray, rels: Map<String, Rel>, images: Images, newParser: () -> XmlPullParser, notes: Boolean): String {
        val blocks = ArrayList<SlideBlock>()
        var block: SlideBlock? = null
        var depth = 0 // nesting of the current top shape element
        var phType: String? = null
        var isPlaceholder = false
        var skip = false
        var para: StringBuilder? = null
        var paraBullet: Boolean? = null
        var paraLevel = 0
        val paras = ArrayList<Triple<String, Boolean?, Int>>()
        var inText = false
        var bold = false; var italic = false; var underline = false; var strike = false
        var table: StringBuilder? = null
        var cell: StringBuilder? = null

        fun flushText(b: SlideBlock) {
            if (paras.all { it.first.isBlank() }) { paras.clear(); return }
            val title = phType == "title" || phType == "ctrTitle"
            val bodyDefault = isPlaceholder && phType !in setOf("title", "ctrTitle", "subTitle")
            if (title) {
                b.html.append("<h2>").append(paras.filter { it.first.isNotBlank() }.joinToString("<br>") { it.first }).append("</h2>")
            } else {
                var open = false
                for ((text, bullet, level) in paras) {
                    val asBullet = bullet ?: bodyDefault
                    if (asBullet && text.isNotBlank()) {
                        if (!open) { b.html.append("<ul>"); open = true }
                        b.html.append("<li style=\"margin-left:${level * 1.2}em\">").append(text).append("</li>")
                    } else {
                        if (open) { b.html.append("</ul>"); open = false }
                        b.html.append(if (text.isBlank()) "<p class=\"gap\"></p>" else "<p>$text</p>")
                    }
                }
                if (open) b.html.append("</ul>")
            }
            paras.clear()
        }

        walk(newParser, bytes, onStart = { p, name ->
            if (block != null) depth++
            when (name) {
                "sp", "pic", "graphicFrame" -> if (block == null) {
                    block = SlideBlock(false); depth = 1; phType = null; isPlaceholder = false; skip = false
                }
                "ph" -> if (block != null) {
                    isPlaceholder = true
                    phType = p.attr("type")
                    if (phType in setOf("dt", "ftr", "sldNum", "hdr", "sldImg")) skip = true
                    if (notes && phType != "body") skip = true
                }
                "off" -> block?.let { b -> if (b.y == Long.MAX_VALUE) { b.x = p.attr("x")?.toLongOrNull() ?: 0; b.y = p.attr("y")?.toLongOrNull() ?: 0 } }
                "p" -> if (block != null) { para = StringBuilder(); paraBullet = null; paraLevel = 0 }
                "pPr" -> if (para != null) paraLevel = p.attr("lvl")?.toIntOrNull() ?: 0
                "buNone" -> if (para != null) paraBullet = false
                "buChar", "buAutoNum" -> if (para != null) paraBullet = true
                "r" -> { bold = false; italic = false; underline = false; strike = false }
                "rPr" -> {
                    bold = p.attr("b") == "1" || p.attr("b") == "true"
                    italic = p.attr("i") == "1" || p.attr("i") == "true"
                    underline = p.attr("u").let { it != null && it != "none" }
                    strike = p.attr("strike").let { it != null && it != "noStrike" }
                }
                "t" -> inText = true
                "br" -> para?.append("<br>")
                "tbl" -> if (block != null) table = StringBuilder("<table>")
                "tr" -> table?.append("<tr>")
                "tc" -> if (table != null) cell = StringBuilder()
                "blip" -> if (!notes) block?.let { b -> p.attr("embed")?.let { rels[it] }?.let { b.html.append(images.tag(it.target)) } }
            }
        }, onText = { if (inText) para?.append(styled(it, bold, italic, underline, strike, null)) }, onEnd = { name ->
            when (name) {
                "t" -> inText = false
                "p" -> para?.let { text ->
                    val c = cell
                    if (c != null) { if (c.isNotEmpty()) c.append("<br>"); c.append(text) } else paras.add(Triple(text.toString(), paraBullet, paraLevel))
                    para = null
                }
                "tc" -> cell?.let { c -> table?.append("<td>")?.append(c)?.append("</td>"); cell = null }
                "tr" -> table?.append("</tr>")
                "tbl" -> table?.let { t -> block?.html?.append(t)?.append("</table>"); table = null }
            }
            val b = block
            if (b != null) {
                depth--
                if (depth == 0) {
                    flushText(b)
                    val title = phType == "title" || phType == "ctrTitle"
                    if (!skip && b.html.isNotEmpty()) blocks.add(SlideBlock(title).also { it.x = b.x; it.y = b.y; it.html.append(b.html) })
                    block = null
                }
            }
        })
        return blocks.sortedWith(compareBy<SlideBlock>({ !it.title }, { it.y }, { it.x })).joinToString("") { it.html }
    }

    // --- XLSX ---

    private const val MAX_SHEET_ROWS = 2000
    private const val MAX_SHEET_COLS = 100

    fun xlsx(zip: ZipRead, newParser: () -> XmlPullParser, labels: Labels = Labels()): String {
        val workbook = zip.read("xl/workbook.xml") ?: throw IllegalArgumentException("not an Excel workbook")
        val rels = rels(newParser, zip.read("xl/_rels/workbook.xml.rels"), "xl")
        val sheets = ArrayList<Pair<String, String>>()
        walk(newParser, workbook, onStart = { p, name ->
            if (name == "sheet" && p.attr("state").let { it != "hidden" && it != "veryHidden" }) {
                p.relId()?.let { rels[it] }?.let { sheets.add(p.attr("name").orEmpty() to it.target) }
            }
        })
        if (sheets.isEmpty()) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val shared = sharedStrings(newParser, zip.read("xl/sharedStrings.xml"))
        val styles = cellStyles(newParser, zip.read("xl/styles.xml"), themeColors(newParser, zip.read("xl/theme/theme1.xml")))
        val out = StringBuilder()
        // Several sheets get a row of links to each, as Excel's tabs.
        if (sheets.size > 1) {
            out.append("<p class=\"sheets\">")
            sheets.forEachIndexed { i, (name, _) -> out.append("<a href=\"#sheet$i\">").append(esc(name)).append("</a>") }
            out.append("</p>")
        }
        sheets.forEachIndexed { i, (name, path) ->
            val bytes = zip.read(path) ?: return@forEachIndexed
            if (sheets.size > 1) out.append("<h2 id=\"sheet$i\">").append(esc(name)).append("</h2>")
            out.append(sheetTable(bytes, shared, styles, newParser, labels))
        }
        return out.toString()
    }

    /** A cell style (cellXfs entry): how its number reads, and its font, fill and alignment as CSS. */
    internal class CellStyle(val date: Boolean, val format: NumberFormat?, val css: String)

    /** The part of an Excel number format the reader honours: decimals, thousands grouping, percent and the text around the number. */
    internal class NumberFormat(val decimals: Int, val grouping: Boolean, val percent: Boolean, val prefix: String, val suffix: String) {
        fun format(value: Double): String {
            val v = if (percent) value * 100 else value
            val pattern = (if (grouping) "#,##0" else "0") + (if (decimals > 0) "." + "0".repeat(decimals) else "")
            return prefix + java.text.DecimalFormat(pattern).format(v) + suffix
        }

        companion object {
            private val builtIn = mapOf(1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%", 37 to "#,##0", 38 to "#,##0", 39 to "#,##0.00", 40 to "#,##0.00")

            fun of(id: Int, custom: Map<Int, String>): NumberFormat? = (custom[id] ?: builtIn[id])?.let(::parse)

            /** The first section of [code], or null for General, text and the forms it does not read (scientific, fractions). */
            fun parse(code: String): NumberFormat? {
                val first = code.split(';').first()
                val literal = first
                    .replace(Regex("\\[\\$([^-\\]]*)[^\\]]*]")) { it.groupValues[1] }
                    .replace(Regex("\\[[^\\]]*]"), "")
                    .replace(Regex("\"([^\"]*)\"")) { it.groupValues[1] }
                    .replace(Regex("[_*]."), "")
                    .replace(Regex("\\\\(.)")) { it.groupValues[1] }
                if (literal.equals("General", ignoreCase = true) || '@' in literal || 'E' in literal || '/' in literal) return null
                val start = literal.indexOfFirst { it == '0' || it == '#' || it == '?' }
                if (start < 0) return null
                val end = literal.indexOfLast { it == '0' || it == '#' || it == '?' || it == '.' || it == ',' }
                val number = literal.substring(start, end + 1)
                val decimals = number.substringAfter('.', "").count { it == '0' || it == '#' || it == '?' }
                return NumberFormat(decimals, ',' in number.substringBefore('.'), '%' in literal, literal.substring(0, start), literal.substring(end + 1))
            }
        }
    }

    private class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val color: String?)

    /** The workbook theme's colours in the order cells refer to them by index: light 1, dark 1, light 2, dark 2, then the accents. */
    private fun themeColors(newParser: () -> XmlPullParser, bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val out = ArrayList<String>()
        var inScheme = false
        walk(newParser, bytes, onStart = { p, name ->
            when (name) {
                "clrScheme" -> inScheme = true
                "srgbClr" -> if (inScheme) p.attr("val")?.let { out.add("#" + it.takeLast(6).lowercase()) }
                "sysClr" -> if (inScheme) out.add("#" + (p.attr("lastClr") ?: "000000").takeLast(6).lowercase())
            }
        }, onEnd = { if (it == "clrScheme") inScheme = false })
        if (out.size >= 4) { java.util.Collections.swap(out, 0, 1); java.util.Collections.swap(out, 2, 3) }
        return out
    }

    /** A colour element (rgb, or theme index with a tint) as CSS hex; null when it names none the reader knows. */
    private fun colorOf(p: XmlPullParser, theme: List<String>): String? {
        val base = p.attr("rgb")?.takeIf { it.length >= 6 }?.let { "#" + it.takeLast(6).lowercase() }
            ?: p.attr("theme")?.toIntOrNull()?.let(theme::getOrNull)
            ?: return null
        val tint = p.attr("tint")?.toDoubleOrNull() ?: return base
        val rgb = base.removePrefix("#").chunked(2).map { it.toInt(16) }
        return "#" + rgb.joinToString("") { c ->
            val t = if (tint < 0) c * (1 + tint) else c + (255 - c) * tint
            "%02x".format(t.toInt().coerceIn(0, 255))
        }
    }

    private val builtInDateFormats = (14..22).toSet() + setOf(27, 30, 36, 45, 46, 47, 50, 57)

    /** Every cell style in styles.xml, by index. */
    private fun cellStyles(newParser: () -> XmlPullParser, bytes: ByteArray?, theme: List<String>): List<CellStyle> {
        if (bytes == null) return emptyList()
        val customCodes = HashMap<Int, String>()
        val fonts = ArrayList<Font>()
        val fills = ArrayList<String?>()
        val out = ArrayList<CellStyle>()
        var section = ""
        var bold = false; var italic = false; var underline = false; var strike = false; var color: String? = null
        var solid = false; var fill: String? = null
        var xfFormat = 0; var xfFont = 0; var xfFill = 0; var align: String? = null; var wrap = false
        walk(newParser, bytes, onStart = { p, name ->
            when (name) {
                "fonts", "fills", "cellXfs", "cellStyleXfs", "dxfs" -> section = name
                "numFmt" -> p.attr("numFmtId")?.toIntOrNull()?.let { customCodes[it] = p.attr("formatCode").orEmpty() }
                "font" -> if (section == "fonts") { bold = false; italic = false; underline = false; strike = false; color = null }
                "b" -> bold = !isOff(p.attr("val"))
                "i" -> italic = !isOff(p.attr("val"))
                "u" -> underline = !isOff(p.attr("val"))
                "strike" -> strike = !isOff(p.attr("val"))
                "color" -> if (section == "fonts") color = colorOf(p, theme)
                "patternFill" -> if (section == "fills") { solid = p.attr("patternType") == "solid"; fill = null }
                "fgColor" -> if (section == "fills") fill = colorOf(p, theme)
                "xf" -> if (section == "cellXfs") {
                    xfFormat = p.attr("numFmtId")?.toIntOrNull() ?: 0
                    xfFont = p.attr("fontId")?.toIntOrNull() ?: 0
                    xfFill = p.attr("fillId")?.toIntOrNull() ?: 0
                    align = null; wrap = false
                }
                "alignment" -> if (section == "cellXfs") { align = p.attr("horizontal"); wrap = p.attr("wrapText") == "1" }
            }
        }, onEnd = { name ->
            when (name) {
                "fonts", "fills", "cellXfs", "cellStyleXfs", "dxfs" -> section = ""
                "font" -> if (section == "fonts") fonts.add(Font(bold, italic, underline, strike, color))
                "fill" -> if (section == "fills") fills.add(if (solid) fill else null)
                "xf" -> if (section == "cellXfs") {
                    val code = customCodes[xfFormat].orEmpty().replace(Regex("\"[^\"]*\"|\\[[^\\]]*]"), "").lowercase()
                    val date = xfFormat in builtInDateFormats ||
                        (xfFormat in customCodes && (code.any { it == 'y' || it == 'd' || it == 'h' } || (code.contains('m') && !code.contains('0'))))
                    val font = fonts.getOrNull(xfFont)
                    // White is what a sheet looks like unfilled; on a dark reader it would only glare.
                    val background = fills.getOrNull(xfFill)?.takeIf { it != "#ffffff" }
                    val css = StringBuilder()
                    if (font?.bold == true) css.append("font-weight:600;")
                    if (font?.italic == true) css.append("font-style:italic;")
                    if (font?.underline == true || font?.strike == true) {
                        css.append("text-decoration:").append(listOfNotNull("underline".takeIf { font.underline }, "line-through".takeIf { font.strike }).joinToString(" ")).append(';')
                    }
                    val ink = font?.color?.takeIf { it != "#000000" }
                    if (background != null) css.append("background:").append(background).append(";color:").append(ink ?: "#000000").append(';')
                    else if (ink != null && ink != "#ffffff") css.append("color:").append(ink).append(';')
                    when (align) { "center", "centerContinuous" -> "center"; "right" -> "right"; "left" -> "left"; else -> null }?.let { css.append("text-align:").append(it).append(';') }
                    if (wrap) css.append("white-space:pre-wrap;")
                    out.add(CellStyle(date, if (date) null else NumberFormat.of(xfFormat, customCodes), css.toString()))
                }
            }
        })
        return out
    }

    /** One worksheet as a table headed by column letters, each row led by its number, styled and merged as in Excel. */
    private fun sheetTable(bytes: ByteArray, shared: List<String>, styles: List<CellStyle>, newParser: () -> XmlPullParser, labels: Labels): String {
        class Cell(val text: String, val style: CellStyle?)
        val rows = java.util.TreeMap<Int, HashMap<Int, Cell>>()
        val hiddenRows = HashSet<Int>()
        val hiddenCols = HashSet<Int>()
        val widths = HashMap<Int, Double>()
        val merges = HashMap<Pair<Int, Int>, Pair<Int, Int>>() // top-left (row, col) -> (rows, cols)
        val covered = HashSet<Pair<Int, Int>>()
        var maxCol = -1
        var truncated = false
        var rowNo = 0
        var col = -1
        var type: String? = null
        var style = 0
        val value = StringBuilder()
        var inValue = false
        var inInline = false
        walk(newParser, bytes, onStart = { p, name ->
            when (name) {
                "col" -> {
                    val min = (p.attr("min")?.toIntOrNull() ?: 1) - 1
                    val max = ((p.attr("max")?.toIntOrNull() ?: 1) - 1).coerceAtMost(MAX_SHEET_COLS)
                    for (c in min..max) {
                        if (p.attr("hidden") == "1") hiddenCols.add(c)
                        p.attr("width")?.toDoubleOrNull()?.let { widths[c] = it }
                    }
                }
                "row" -> {
                    rowNo = p.attr("r")?.toIntOrNull() ?: (rowNo + 1); col = -1
                    if (p.attr("hidden") == "1") hiddenRows.add(rowNo)
                }
                "c" -> {
                    col = p.attr("r")?.let { columnIndex(it) } ?: (col + 1)
                    type = p.attr("t")
                    style = p.attr("s")?.toIntOrNull() ?: 0
                    value.setLength(0)
                }
                "v" -> inValue = true
                "is" -> inInline = true
                "t" -> if (inInline) inValue = true
                "mergeCell" -> p.attr("ref")?.split(':')?.takeIf { it.size == 2 }?.let { (a, b) ->
                    val r0 = a.filter(Char::isDigit).toIntOrNull() ?: return@let
                    val r1 = b.filter(Char::isDigit).toIntOrNull() ?: return@let
                    val c0 = columnIndex(a)
                    val c1 = columnIndex(b)
                    merges[r0 to c0] = (r1 - r0 + 1) to (c1 - c0 + 1)
                    for (r in r0..r1) for (c in c0..c1) if (r != r0 || c != c0) covered.add(r to c)
                }
            }
        }, onText = { if (inValue) value.append(it) }, onEnd = { name ->
            when (name) {
                "v", "t" -> inValue = false
                "is" -> inInline = false
                "c" -> {
                    val cs = styles.getOrNull(style)
                    val shown = cellText(value.toString(), type, cs, shared)
                    if (shown.isNotEmpty() || cs?.css?.contains("background") == true) {
                        if (rowNo > MAX_SHEET_ROWS || col >= MAX_SHEET_COLS) truncated = truncated || shown.isNotEmpty()
                        else {
                            rows.getOrPut(rowNo) { HashMap() }[col] = Cell(shown, cs)
                            if (shown.isNotEmpty()) maxCol = maxOf(maxCol, col)
                        }
                    }
                }
            }
        })
        // A merge reaches as far right as its last column.
        for ((at, span) in merges) if (rows[at.first]?.get(at.second)?.text?.isNotEmpty() == true) maxCol = maxOf(maxCol, (at.second + span.second - 1).coerceAtMost(MAX_SHEET_COLS - 1))
        if (maxCol < 0) return "<p class=\"missing\">${esc(labels.empty)}</p>"
        val cols = (0..maxCol).filter { it !in hiddenCols }
        val sb = StringBuilder("<table class=\"csv sheet\"><thead><tr><th></th>")
        for (c in cols) {
            val w = widths[c]?.let { (it * 7 + 5).toInt() }
            sb.append(if (w != null) "<th style=\"min-width:${w}px\">" else "<th>").append(columnName(c)).append("</th>")
        }
        sb.append("</tr></thead><tbody>")
        for ((r, cells) in rows) {
            if (r in hiddenRows || cells.values.none { it.text.isNotEmpty() || it.style != null } ) continue
            sb.append("<tr><th>").append(r).append("</th>")
            for (c in cols) {
                if ((r to c) in covered) continue
                val cell = cells[c]
                val v = cell?.text.orEmpty()
                val attrs = StringBuilder()
                if (numeric.matches(v) || cell?.style?.format != null && v.isNotEmpty()) attrs.append(" class=\"num\"")
                merges[r to c]?.let { (rs, cs) ->
                    if (rs > 1) attrs.append(" rowspan=\"").append(rs).append('"')
                    val shownCols = (c until c + cs).count { it in cols }
                    if (shownCols > 1) attrs.append(" colspan=\"").append(shownCols).append('"')
                }
                cell?.style?.css?.takeIf { it.isNotEmpty() }?.let { attrs.append(" style=\"").append(it).append('"') }
                sb.append("<td").append(attrs).append('>').append(esc(v)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        if (truncated) sb.append("<p class=\"note\">").append(esc(labels.truncatedCells(MAX_SHEET_ROWS, MAX_SHEET_COLS))).append("</p>")
        return sb.toString()
    }

    private fun cellText(raw: String, type: String?, style: CellStyle?, shared: List<String>): String = when (type) {
        "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
        "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
        "str", "inlineStr", "e" -> raw
        else -> {
            val d = raw.trim().toDoubleOrNull()
            when {
                d == null -> raw
                style?.date == true -> excelDate(d)
                style?.format != null -> style.format.format(d)
                else -> java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
            }
        }
    }

    /** An Excel date serial (days since 1899-12-30) as ISO date, with the time when it has one. */
    internal fun excelDate(serial: Double): String {
        val days = kotlin.math.floor(serial).toLong()
        val date = java.time.LocalDate.of(1899, 12, 30).plusDays(days)
        val seconds = Math.round((serial - days) * 86400).toInt()
        if (seconds == 0 || seconds == 86400) return date.toString()
        val time = java.time.LocalTime.ofSecondOfDay(seconds.toLong())
        return if (days == 0L) time.toString() else "$date $time"
    }

    /** "C12" -> 2. */
    internal fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch !in 'A'..'Z' && ch !in 'a'..'z') break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    internal fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    /** Each shared string's plain text, rich runs joined, phonetic guides left out. */
    private fun sharedStrings(newParser: () -> XmlPullParser, bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inT = false
        var inPhonetic = false
        walk(newParser, bytes, onStart = { _, name ->
            when (name) {
                "si" -> cur.setLength(0)
                "rPh" -> inPhonetic = true
                "t" -> inT = !inPhonetic
            }
        }, onText = { if (inT) cur.append(it) }, onEnd = { name ->
            when (name) {
                "t" -> inT = false
                "rPh" -> inPhonetic = false
                "si" -> out.add(cur.toString())
            }
        })
        return out
    }

    // --- text utils ---

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(ch)
        }
        return sb.toString()
    }
}
