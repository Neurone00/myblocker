// Executes the collapser script exactly as the proxy injects it (extracted from the Kotlin source)
// against a page shaped like an Italian news site: literal "ADV" boxes, AdSense slots inside
// wrappers with no ad-ish name that reserve space, a slot inserted long after load, a column
// whose real content arrives after its ad was collapsed, and look-alike names that must survive.
const { JSDOM, VirtualConsole } = require('jsdom');
const fs = require('fs');
const path = require('path');

const kt = fs.readFileSync(path.join(__dirname, '../../app/src/main/java/com/neurone/myblocker/proxy/InterceptProxy.kt'), 'utf8');
const m = kt.match(/val COLLAPSER_JS: String = """\n([\s\S]*?)\n""".trim\(\)/);
if (!m) { console.error('COLLAPSER_JS not found in InterceptProxy.kt'); process.exit(2); }
const js = m[1].split("${'$'}").join('$');

const html = `<!doctype html><html><head></head><body><main>
 <article id="a1"><h2>Titolo</h2><p>Testo dell'articolo abbastanza lungo per contare come contenuto reale.</p></article>
 <div class="adv-box" id="advLabelBox"><span class="adv-label">ADV</span><div class="adv-slot"></div></div>
 <div class="widget" id="widgetAroundBanner"><div class="banner-wrapper"><ins class="adsbygoogle" style="display:block;height:250px"></ins></div></div>
 <div class="box-300" id="reservedBox" style="min-height:250px;background:#eee"><ins class="adsbygoogle"></ins></div>
 <div id="plainContent" class="content"><p>Un paragrafo normale con testo che non deve sparire.</p></div>
 <div id="advertorialWithText" class="advertorial"><h3>Sponsor content</h3><p>Real editorial text long enough to keep.</p></div>
 <div id="loadMore" class="load-more"></div>
 <div id="advancedSearch" class="advanced-search"></div>
 <div id="adFreePitch" class="ad-free-banner">Passa alla versione senza pubblicità: abbonati ora e sostieni il giornale.</div>
 <div id="latinThread" class="thread"><span>Ad</span> <span>maiora semper</span></div>
 <div id="lateColumn" class="col"><div id="lateColumnAd" class="ad"></div></div>
 <div id="inArticle" class="post"><div class="wp-block-group"><div id="ad_inarticle_1"></div></div></div>
 <div id="sidebar" class="sidebar"><div class="widget"><h3>Più letti</h3><p>Un elenco di articoli con abbastanza testo.</p></div><div class="widget"><div id="sidebarAd" class="adv"></div></div></div>
</main></body></html>`;

const vc = new VirtualConsole(); // jsdom has no ::before styles; the script guards that, keep the log clean
const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true, virtualConsole: vc });
const w = dom.window, d = w.document;
w.eval(js);

const want = {
  advLabelBox: 'HIDDEN', widgetAroundBanner: 'HIDDEN', reservedBox: 'HIDDEN', inArticle: 'HIDDEN', sidebarAd: 'HIDDEN',
  plainContent: 'shown', advertorialWithText: 'shown', loadMore: 'shown', advancedSearch: 'shown', adFreePitch: 'shown', latinThread: 'shown',
  sidebar: 'shown', lateColumn: 'shown', lazy: 'HIDDEN', lazyWrap: 'HIDDEN',
};
const st = id => { const e = d.getElementById(id); return e ? (e.getAttribute('data-adb') ? 'HIDDEN' : 'shown') : '-'; };

setTimeout(() => {
  // the column's ad was collapsed with it; real widget content arriving later must bring it back
  d.getElementById('lateColumn').insertAdjacentHTML('beforeend', '<p>I più letti della settimana, contenuto reale del widget.</p>');
  // a slot inserted long after load (lazy ad loaders do this on scroll)
  setTimeout(() => {
    const l = d.createElement('div'); l.id = 'lazyWrap'; l.className = 'adv-container';
    l.innerHTML = '<div id="lazy" class="adv">ADV</div>'; d.querySelector('main').appendChild(l);
    setTimeout(() => {
      let fails = 0;
      for (const id of Object.keys(want)) {
        const got = st(id); const ok = got === want[id]; if (!ok) fails++;
        console.log((ok ? 'ok   ' : 'FAIL ') + id.padEnd(22) + got + (ok ? '' : '  (want ' + want[id] + ')'));
      }
      console.log(fails ? `\n${fails} failure(s)` : '\nall collapser cases pass');
      w.close(); process.exit(fails ? 1 : 0);
    }, 900);
  }, 13500);
}, 900);
