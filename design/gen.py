import json, textwrap

BASE_CSS = """
    body { margin: 0; font-family: 'Roboto', system-ui, -apple-system, 'Segoe UI', sans-serif; -webkit-font-smoothing: antialiased; }
    a { color: #1E88E5; } a:hover { color: #0B3A63; }
    .phone { width: 390px; height: 844px; background: var(--bg); color: var(--on-surface); display: flex; flex-direction: column; overflow: hidden; position: relative; }
    .content { flex-grow: 1; overflow: hidden; display: flex; flex-direction: column; gap: 14px; padding: 56px 16px 16px 16px; }
    .content > * { flex-shrink: 0; }
    .card { background: var(--surface); border-radius: 18px; padding: 16px; display: flex; flex-direction: column; gap: 6px; }
    .title-m { font-size: 16px; font-weight: 500; line-height: 24px; letter-spacing: 0.15px; }
    .body-m { font-size: 14px; line-height: 20px; letter-spacing: 0.25px; }
    .body-s { font-size: 12px; line-height: 16px; letter-spacing: 0.4px; color: var(--on-surface-variant); }
    .label-m { font-size: 12px; line-height: 16px; font-weight: 500; letter-spacing: 0.5px; color: var(--on-surface-variant); }
    .nav { height: 80px; background: var(--surface-container); display: flex; flex-direction: row; align-items: center; justify-content: space-around; padding: 0 8px; }
    .nav-item { display: flex; flex-direction: column; align-items: center; gap: 4px; width: 80px; }
    .nav-pill { width: 64px; height: 32px; border-radius: 16px; display: flex; align-items: center; justify-content: center; }
    .nav-pill.on { background: var(--secondary-container); }
    .nav-label { font-size: 12px; line-height: 16px; font-weight: 500; letter-spacing: 0.5px; color: var(--on-surface-variant); }
    .nav-label.on { color: var(--on-surface); font-weight: 700; }
    .row { display: flex; flex-direction: row; align-items: center; gap: 12px; }
    .switch { width: 52px; height: 32px; border-radius: 16px; background: var(--primary); position: relative; flex-shrink: 0; }
    .switch::after { content: ''; position: absolute; top: 4px; right: 4px; width: 24px; height: 24px; border-radius: 12px; background: #fff; }
    .switch.off { background: var(--surface-variant); border: 2px solid var(--outline); box-sizing: border-box; }
    .switch.off::after { top: 6px; left: 6px; right: auto; width: 16px; height: 16px; background: var(--outline); }
    .divider { height: 1px; background: var(--surface-variant); }
    .btn { height: 40px; border-radius: 20px; padding: 0 24px; display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 500; letter-spacing: 0.1px; }
    .btn.filled { background: var(--primary); color: var(--on-primary); }
    .btn.tonal { background: var(--secondary-container); color: var(--on-secondary-container); }
    .btn.text { color: var(--primary); padding: 0 12px; }
    .chip { height: 32px; border-radius: 8px; padding: 0 12px; display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 500; border: 1px solid var(--outline); color: var(--on-surface-variant); }
    .chip.on { background: var(--secondary-container); border-color: transparent; color: var(--on-secondary-container); }
"""

# Adbrella brand scheme (lifted from Theme.kt) and a dynamic-color example (One UI wallpaper-derived, warm sand)
BRAND = dict(bg="#F3F8FD", surface="#FFFFFF", surface_container="#E9F1F8", surface_variant="#E6EFF7", on_surface="#0F1A26", on_surface_variant="#44546A", outline="#74849A",
             primary="#1E88E5", on_primary="#FFFFFF", primary_container="#D6ECFF", on_primary_container="#0B3A63", secondary_container="#D0F5F9", on_secondary_container="#00363C",
             error="#D84315", hero="linear-gradient(135deg, #4FC3F7 0%, #1E88E5 55%, #26C6DA 100%)", closed="linear-gradient(135deg, #90A4AE, #607D8B)")
DYNAMIC = dict(bg="#FBF8F3", surface="#FFFFFF", surface_container="#F2ECE3", surface_variant="#EDE6DB", on_surface="#1F1B16", on_surface_variant="#5A5245", outline="#8C8272",
               primary="#7A5B2A", on_primary="#FFFFFF", primary_container="#FFDEB0", on_primary_container="#2A1800", secondary_container="#E2DFD0", on_secondary_container="#1B1D10",
               error="#BA1A1A", hero="linear-gradient(135deg, #C9A46B 0%, #7A5B2A 60%, #5F6B3A 100%)", closed="linear-gradient(135deg, #A69B8C, #6E6558)")

def vars_css(t):
    return ":root { --bg:%(bg)s; --surface:%(surface)s; --surface-container:%(surface_container)s; --surface-variant:%(surface_variant)s; --on-surface:%(on_surface)s; --on-surface-variant:%(on_surface_variant)s; --outline:%(outline)s; --primary:%(primary)s; --on-primary:%(on_primary)s; --primary-container:%(primary_container)s; --on-primary-container:%(on_primary_container)s; --secondary-container:%(secondary_container)s; --on-secondary-container:%(on_secondary_container)s; --error:%(error)s; }" % t

ICON = {
 'umbrella': '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 0 1 18 0"></path><path d="M3 12c1.5-2 3-2 4.5 0 1.5-2 3-2 4.5 0 1.5-2 3-2 4.5 0 1.5-2 3-2 4.5 0"></path><path d="M12 12v7a2 2 0 0 1-4 0"></path></svg>',
 'activity': '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12h3l3-7 4 14 3-7h3"></path></svg>',
 'chart': '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M5 20V10"></path><path d="M12 20V4"></path><path d="M19 20v-6"></path></svg>',
 'settings': '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"></path></svg>',
 'chev': '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"></path></svg>',
 'check': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7"></path></svg>',
 'block': '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="8"></circle><path d="M6.5 6.5l11 11"></path></svg>',
 'back': '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 6l-6 6 6 6"></path></svg>',
 'warn': '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l10 18H2z"></path><path d="M12 10v5"></path><path d="M12 18h.01"></path></svg>',
}
UMBRELLA_HERO = '<svg width="104" height="104" viewBox="0 0 108 108" fill="none"><path fill="#FFFFFF" d="M22,58 A32,32 0 0 1 86,58 A5.33,5.33 0 0 1 75.33,58 A5.33,5.33 0 0 1 64.67,58 A5.33,5.33 0 0 1 54,58 A5.33,5.33 0 0 1 43.33,58 A5.33,5.33 0 0 1 32.67,58 A5.33,5.33 0 0 1 22,58 Z"></path><path stroke="#000000" stroke-opacity="0.12" stroke-width="1.5" d="M54,27 L38,58 M54,27 L70,58"></path><circle cx="54" cy="22.5" r="3" fill="#FFFFFF"></circle><path stroke="#FFFFFF" stroke-width="4" stroke-linecap="round" d="M54,58 V80 A6,6 0 0 1 42,80"></path><path fill="#FFFFFF" fill-opacity="0.7" d="M80,20 c0,0 -3.5,4.6 -3.5,7 a3.5,3.5 0 0 0 7,0 c0,-2.4 -3.5,-7 -3.5,-7 z"></path><path fill="#FFFFFF" fill-opacity="0.5" d="M31,24 c0,0 -2.6,3.4 -2.6,5.2 a2.6,2.6 0 0 0 5.2,0 c0,-1.8 -2.6,-5.2 -2.6,-5.2 z"></path></svg>'


MASCOTS = """<div style="position: relative; width: 300px; height: 150px;">
  <svg class="drop d1" width="14" height="20" viewBox="0 0 14 20" style="position: absolute; left: 60px; top: -10px;"><path d="M7 1 C7 1 1 9 1 13 A6 6 0 0 0 13 13 C13 9 7 1 7 1 Z" fill="#FFFFFF" fill-opacity="0.85"></path><text x="7" y="16" font-size="8" text-anchor="middle" fill="#1E88E5" font-weight="700">AD</text></svg>
  <svg class="drop d2" width="14" height="20" viewBox="0 0 14 20" style="position: absolute; left: 150px; top: -10px;"><path d="M7 1 C7 1 1 9 1 13 A6 6 0 0 0 13 13 C13 9 7 1 7 1 Z" fill="#FFFFFF" fill-opacity="0.85"></path><text x="7" y="16" font-size="8" text-anchor="middle" fill="#1E88E5" font-weight="700">AD</text></svg>
  <svg class="drop d3" width="14" height="20" viewBox="0 0 14 20" style="position: absolute; left: 235px; top: -10px;"><path d="M7 1 C7 1 1 9 1 13 A6 6 0 0 0 13 13 C13 9 7 1 7 1 Z" fill="#FFFFFF" fill-opacity="0.85"></path><text x="7" y="16" font-size="8" text-anchor="middle" fill="#1E88E5" font-weight="700">AD</text></svg>
  <svg class="umb" width="220" height="90" viewBox="0 0 220 90" style="position: absolute; left: 40px; top: 6px;"><path d="M10 70 A100 62 0 0 1 210 70 A16.7 12 0 0 1 176.6 70 A16.7 12 0 0 1 143.3 70 A16.7 12 0 0 1 110 70 A16.7 12 0 0 1 76.6 70 A16.7 12 0 0 1 43.3 70 A16.7 12 0 0 1 10 70 Z" fill="#FFFFFF"></path><path d="M110 12 L60 70 M110 12 L160 70" stroke="#000000" stroke-opacity="0.1" stroke-width="2"></path><circle cx="110" cy="9" r="4" fill="#FFFFFF"></circle></svg>
  <svg class="seal" width="120" height="100" viewBox="0 0 120 100" style="position: absolute; left: 20px; top: 56px;">
    <ellipse cx="62" cy="76" rx="40" ry="20" fill="#8FA9BF"></ellipse>
    <ellipse cx="18" cy="82" rx="12" ry="6" fill="#8FA9BF" transform="rotate(-25 18 82)"></ellipse>
    <ellipse cx="66" cy="84" rx="24" ry="10" fill="#B9CBD9"></ellipse>
    <circle cx="66" cy="44" r="24" fill="#8FA9BF"></circle>
    <ellipse cx="66" cy="54" rx="13" ry="8" fill="#B9CBD9"></ellipse>
    <ellipse cx="66" cy="50" rx="4.5" ry="3.2" fill="#2B3A47"></ellipse>
    <g class="blink"><circle cx="56" cy="40" r="3.6" fill="#1F2A36"></circle><circle cx="76" cy="40" r="3.6" fill="#1F2A36"></circle><circle cx="57.4" cy="38.8" r="1.2" fill="#FFFFFF"></circle><circle cx="77.4" cy="38.8" r="1.2" fill="#FFFFFF"></circle></g>
    <circle cx="50" cy="49" r="4" fill="#F48FB1" fill-opacity="0.45"></circle><circle cx="82" cy="49" r="4" fill="#F48FB1" fill-opacity="0.45"></circle>
    <path d="M54 55 L40 52 M54 57 L41 59 M78 55 L92 52 M78 57 L91 59" stroke="#2B3A47" stroke-opacity="0.5" stroke-width="1.2" stroke-linecap="round"></path>
    <path d="M60 58 Q66 63 72 58" stroke="#2B3A47" stroke-width="1.6" fill="none" stroke-linecap="round"></path>
    <ellipse cx="104" cy="74" rx="11" ry="6" fill="#8FA9BF" transform="rotate(30 104 74)"></ellipse>
  </svg>
  <svg class="yeti" width="110" height="112" viewBox="0 0 110 112" style="position: absolute; left: 172px; top: 42px;">
    <ellipse cx="55" cy="80" rx="34" ry="30" fill="#F4F8FB"></ellipse>
    <circle cx="55" cy="44" r="30" fill="#F4F8FB"></circle>
    <path d="M28 30 Q34 18 44 22 Q50 12 58 20 Q66 12 74 22 Q82 18 84 30" fill="#F4F8FB"></path>
    <ellipse cx="55" cy="50" rx="19" ry="17" fill="#9ED4F2"></ellipse>
    <g class="blink"><circle cx="48" cy="46" r="3.2" fill="#1F2A36"></circle><circle cx="62" cy="46" r="3.2" fill="#1F2A36"></circle><circle cx="49.2" cy="44.9" r="1" fill="#FFFFFF"></circle><circle cx="63.2" cy="44.9" r="1" fill="#FFFFFF"></circle></g>
    <path d="M44 40 L51 42 M66 40 L59 42" stroke="#1F2A36" stroke-width="1.8" stroke-linecap="round"></path>
    <path d="M46 56 Q55 64 64 56" stroke="#1F2A36" stroke-width="1.8" fill="none" stroke-linecap="round"></path>
    <path d="M50 56.5 L52 60 L56 56.5" fill="#FFFFFF"></path>
    <g class="wave"><path d="M84 70 Q98 60 96 42" stroke="#F4F8FB" stroke-width="12" stroke-linecap="round" fill="none"></path><circle cx="96" cy="40" r="8" fill="#F4F8FB"></circle></g>
    <path d="M26 72 Q14 80 18 92" stroke="#F4F8FB" stroke-width="12" stroke-linecap="round" fill="none"></path>
    <ellipse cx="42" cy="106" rx="11" ry="5" fill="#DCE8F2"></ellipse><ellipse cx="68" cy="106" rx="11" ry="5" fill="#DCE8F2"></ellipse>
  </svg>
</div>"""
MASCOT_CSS = """
    @keyframes bob { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-5px); } }
    @keyframes bobSeal { 0%,100% { transform: translateY(0) rotate(0deg); } 50% { transform: translateY(-3px) rotate(-2deg); } }
    @keyframes blink { 0%,92%,100% { transform: scaleY(1); } 96% { transform: scaleY(0.1); } }
    @keyframes wave { 0%,100% { transform: rotate(0deg); } 30% { transform: rotate(18deg); } 60% { transform: rotate(-8deg); } }
    @keyframes umb { 0%,100% { transform: rotate(-2deg); } 50% { transform: rotate(2deg); } }
    @keyframes drop { 0% { transform: translateY(-20px); opacity: 0; } 15% { opacity: 1; } 55% { transform: translateY(30px) rotate(0deg); opacity: 1; } 75% { transform: translate(28px, 8px) rotate(60deg); opacity: 0.9; } 100% { transform: translate(60px, 80px) rotate(120deg); opacity: 0; } }
    .seal { animation: bobSeal 2.8s ease-in-out infinite; transform-origin: 60px 90px; }
    .yeti { animation: bob 2.2s ease-in-out infinite; }
    .blink { animation: blink 4s infinite; transform-box: fill-box; transform-origin: center; }
    .wave { animation: wave 2.6s ease-in-out infinite; transform-box: fill-box; transform-origin: 10% 90%; }
    .umb { animation: umb 3s ease-in-out infinite; transform-origin: 110px 90px; }
    .drop { animation: drop 3.2s ease-in infinite; }
    .d2 { animation-delay: 1.1s; } .d3 { animation-delay: 2.2s; }
"""

def nav(active):
    items = [('umbrella','Umbrella'),('activity','Activity'),('chart','Stats'),('settings','Settings')]
    out = '<div class="nav">'
    for key,label in items:
        on = ' on' if key==active else ''
        out += f'<div class="nav-item"><div class="nav-pill{on}" style="color: var(--on-surface{"" if on else "-variant"})">{ICON[key]}</div><div class="nav-label{on}">{label}</div></div>'
    return out + '</div>'

def page(body, theme, css_extra=""):
    return f"""<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;700&display=swap">
  <style>
    {vars_css(theme)}
{BASE_CSS}{css_extra}
  </style>
</helmet>
{body}
</x-dc>
</body>
</html>
"""

def stat(value, label):
    return f'<div class="card" style="flex-grow: 1; align-items: center; padding: 14px 8px; gap: 2px;"><div style="font-size: 22px; line-height: 28px; font-weight: 700; color: var(--primary);">{value}</div><div class="label-m">{label}</div></div>'

def home(theme, running=True, checklist=True, name="Main"):
    hero_bg = theme['hero'] if running else theme['closed']
    title = "You're covered" if running else "Umbrella down"
    sub = "Ads keep knocking. Nobody's home." if running else "Ads are walking right in."
    detail = "Covering every app on this phone" if running else "Nothing is being blocked right now"
    btn = "Let the ads in (why though)" if running else "Seal my phone"
    btn_style = "background: #FFFFFF; color: var(--on-primary-container);" if running else "background: var(--primary); color: var(--on-primary);"
    check = ""
    if checklist:
        check = f'''
  <div class="card" style="gap: 0; padding: 0; overflow: hidden;">
    <div class="row" style="padding: 14px 16px 6px 16px;"><div style="color: var(--primary);">{ICON['warn']}</div><div class="title-m" style="flex-grow: 1;">Two phone settings to go</div></div>
    <div class="body-s" style="padding: 0 16px 10px 16px;">Your phone likes to close umbrellas in the background. This card leaves once both are done.</div>
    <div class="divider"></div>
    <div class="row" style="padding: 12px 16px; min-height: 48px;"><div style="width: 22px; height: 22px; border-radius: 11px; border: 2px solid var(--outline); box-sizing: border-box;"></div><div class="body-m" style="flex-grow: 1;">Let Adbrella run in the background</div><div style="color: var(--on-surface-variant);">{ICON['chev']}</div></div>
    <div class="divider"></div>
    <div class="row" style="padding: 12px 16px; min-height: 48px;"><div style="width: 22px; height: 22px; border-radius: 11px; background: var(--primary); color: var(--on-primary); display: flex; align-items: center; justify-content: center;">{ICON['check']}</div><div class="body-m" style="flex-grow: 1; color: var(--on-surface-variant); text-decoration: line-through;">Stop the phone from skipping the umbrella</div></div>
    <div class="divider"></div>
    <div class="row" style="padding: 12px 16px; min-height: 48px;"><div style="width: 22px; height: 22px; border-radius: 11px; border: 2px solid var(--outline); box-sizing: border-box;"></div><div class="body-m" style="flex-grow: 1;">Keep it open after a restart</div><div style="color: var(--on-surface-variant);">{ICON['chev']}</div></div>
  </div>'''
    body = f'''
<div class="phone">
 <div class="content" style="gap: 12px;">
  <div class="card" style="flex-direction: row; align-items: center; gap: 16px; padding: 20px; border-radius: 24px; background: {'var(--primary-container)' if running else 'var(--surface)'};">
    <div style="color: {'var(--on-primary-container)' if running else 'var(--on-surface-variant)'}; width: 32px; height: 32px;">{ICON['umbrella'].replace('width="24" height="24"','width="32" height="32"')}</div>
    <div style="flex-grow: 1; min-width: 0;">
      <div style="font-size: 22px; line-height: 28px; font-weight: 700;">{title}</div>
      <div class="body-m" style="color: var(--on-surface-variant);">{sub}</div>
    </div>
    <div class="switch{'' if running else ' off'}" style="transform: scale(1.15);"></div>
  </div>
  <div style="display: flex; flex-direction: row; gap: 10px;">{stat("1,284","bounced today")}{stat("38,902","bounced ever")}{stat("12","day streak")}</div>
  {check}
  <div class="card" style="gap: 10px;">
    <div class="row"><div class="title-m" style="flex-grow: 1;">Level 5 · Storm</div><div class="body-s">10,000 to go</div></div>
    <div style="height: 8px; border-radius: 4px; background: var(--surface-variant); overflow: hidden;"><div style="width: 62%; height: 8px; background: var(--primary); border-radius: 4px;"></div></div>
  </div>
  <div class="card" style="gap: 8px; padding: 12px 16px;">
    <div class="row"><div class="title-m" style="flex-grow: 1;">Bounced lately</div><div class="btn text" style="height: 32px;">See all</div></div>
    <div class="row" style="min-height: 44px;"><div style="color: var(--error);">{ICON['block']}</div><div style="flex-grow: 1; min-width: 0;"><div class="body-m">Solitaire tried to show an ad</div><div class="body-s" style="white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">googleads.g.doubleclick.net</div></div><div class="btn text" style="height: 32px;">Allow</div></div>
    <div class="row" style="min-height: 44px;"><div style="color: var(--error);">{ICON['block']}</div><div style="flex-grow: 1; min-width: 0;"><div class="body-m">Solitaire tried to show an ad</div><div class="body-s" style="white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">config.unityads.unity3d.com</div></div><div class="btn text" style="height: 32px;">Allow</div></div>
  </div>
 </div>
 {nav('umbrella')}
</div>'''
    return page(body, theme)

def activity(theme):
    rows = [("googleads.g.doubleclick.net","Solitaire","12:41:07",True),("i.instagram.com","Instagram","12:41:05",False),("graph.instagram.com","Instagram","12:41:05",False),("config.unityads.unity3d.com","Solitaire","12:41:02",True),("an.facebook.com","Weather","12:40:58",True),("api.openweathermap.org","Weather","12:40:58",False),("app-measurement.com","Solitaire","12:40:51",True),("samsungcloud.com","Samsung Cloud","12:40:40",False),("ads.samsungads.com","Galaxy Store","12:40:31",True),("play.googleapis.com","Google Play Store","12:40:12",False)]
    items = ""
    for host, app, t, blocked in rows:
        icon = f'<div style="color: var(--error);">{ICON["block"]}</div>' if blocked else f'<div style="color: var(--outline);">{ICON["check"]}</div>'
        action = '<div class="btn text" style="height: 36px;">Allow</div>' if blocked else ''
        what = "got the cold shoulder" if blocked else "went through"
        items += f'<div class="row" style="min-height: 56px; padding: 6px 0;">{icon}<div style="flex-grow: 1; min-width: 0;"><div class="body-m">{app} · {what}</div><div class="body-s" style="white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">{host} · {t}</div></div>{action}</div>'
    body = f'''
<div class="phone">
 <div class="content" style="gap: 10px; padding-bottom: 0;">
  <div style="font-size: 22px; line-height: 28px; font-weight: 500; padding: 0 0 4px 0;">Who tried what</div>
  <div style="height: 48px; border-radius: 24px; background: var(--surface-container); display: flex; align-items: center; padding: 0 16px; gap: 12px; color: var(--on-surface-variant);"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"></circle><path d="M20 20l-3.5-3.5"></path></svg><div class="body-m" style="color: var(--on-surface-variant);">Search apps</div></div>
  <div class="row" style="gap: 8px;"><div class="chip">Blocked only</div><div class="chip">Solitaire</div><div class="chip">Weather</div></div>
  <div class="card" style="gap: 0; padding: 4px 16px; flex-grow: 1;">{items}</div>
 </div>
 {nav('activity')}
</div>'''
    return page(body, theme)

def bars(values, color="var(--primary)", labels=None, h=100):
    m = max(values) or 1
    out = f'<div style="display: flex; flex-direction: row; align-items: flex-end; gap: 4px; height: {h}px;">'
    for v in values:
        bh = max(3, int(h*v/m))
        out += f'<div style="flex-grow: 1; height: {bh}px; border-radius: 3px; background: {color if v else "var(--surface-variant)"};"></div>'
    out += '</div>'
    if labels:
        out += '<div style="display: flex; flex-direction: row; justify-content: space-between;">' + ''.join(f'<div class="body-s">{l}</div>' for l in labels) + '</div>'
    return out

def stats(theme):
    week = [820,1140,960,1310,1284,0,0]
    day = [12,8,4,2,1,0,3,30,75,110,96,88,120,130,101,90,84,70,66,59,72,80,64,0]
    body = f'''
<div class="phone">
 <div class="content" style="gap: 12px;">
  <div style="font-size: 22px; line-height: 28px; font-weight: 500;">Stats</div>
  <div style="display: flex; flex-direction: row; gap: 10px;">{stat("38,902","bounced")}{stat("41%","of all knocks")}{stat("~1.7 GB","never downloaded")}</div>
  <div class="card" style="gap: 8px;"><div class="title-m">This week</div>{bars(week, labels=["Mon","Tue","Wed","Thu","Fri","Sat","Sun"], h=96)}</div>
  <div class="card" style="gap: 8px;"><div class="title-m">Last 24 hours</div>{bars(day, h=72)}</div>
  <div class="card" style="gap: 0; padding: 12px 16px;">
    <div class="title-m" style="padding-bottom: 6px;">Apps that keep trying</div>
    <div class="row" style="min-height: 44px;"><div class="body-m" style="flex-grow: 1;">Solitaire</div><div class="body-m" style="color: var(--on-surface-variant);">9,412</div></div>
    <div class="row" style="min-height: 44px;"><div class="body-m" style="flex-grow: 1;">Weather</div><div class="body-m" style="color: var(--on-surface-variant);">3,208</div></div>
    <div class="row" style="min-height: 44px;"><div class="body-m" style="flex-grow: 1;">Galaxy Store</div><div class="body-m" style="color: var(--on-surface-variant);">1,930</div></div>
  </div>
  <div class="card" style="gap: 0; padding: 12px 16px;">
    <div class="row" style="padding-bottom: 6px;"><div class="title-m" style="flex-grow: 1;">Badges</div><div class="body-s">6 of 13</div></div>
    <div style="display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px;">
      <div style="display: flex; flex-direction: column; align-items: center; gap: 4px;"><div style="width: 48px; height: 48px; border-radius: 24px; background: var(--primary-container); color: var(--on-primary-container); display: flex; align-items: center; justify-content: center;">{ICON['umbrella']}</div><div class="body-s" style="text-align: center;">First bounce</div></div>
      <div style="display: flex; flex-direction: column; align-items: center; gap: 4px;"><div style="width: 48px; height: 48px; border-radius: 24px; background: var(--primary-container); color: var(--on-primary-container); display: flex; align-items: center; justify-content: center;">{ICON['chart']}</div><div class="body-s" style="text-align: center;">Downpour</div></div>
      <div style="display: flex; flex-direction: column; align-items: center; gap: 4px;"><div style="width: 48px; height: 48px; border-radius: 24px; background: var(--primary-container); color: var(--on-primary-container); display: flex; align-items: center; justify-content: center;">{ICON['activity']}</div><div class="body-s" style="text-align: center;">Dry week</div></div>
      <div style="display: flex; flex-direction: column; align-items: center; gap: 4px; opacity: 0.4;"><div style="width: 48px; height: 48px; border-radius: 24px; border: 2px dashed var(--outline); box-sizing: border-box;"></div><div class="body-s" style="text-align: center;">Dry month</div></div>
    </div>
  </div>
 </div>
 {nav('chart')}
</div>'''
    return page(body, theme)

def settings(theme):
    def item(title, sub, trailing="chev"):
        tr = f'<div style="color: var(--on-surface-variant);">{ICON["chev"]}</div>' if trailing=="chev" else (f'<div class="switch"></div>' if trailing=="on" else f'<div class="switch off"></div>')
        subhtml = f'<div class="body-s">{sub}</div>' if sub else ''
        return f'<div class="row" style="min-height: 56px; padding: 8px 16px;"><div style="flex-grow: 1;"><div class="body-m" style="font-size: 16px; line-height: 24px;">{title}</div>{subhtml}</div>{tr}</div>'
    div = '<div class="divider" style="margin: 0 16px;"></div>'
    body = f'''
<div class="phone">
 <div class="content" style="gap: 12px;">
  <div style="font-size: 22px; line-height: 28px; font-weight: 500;">Settings</div>
  <div class="card" style="gap: 0; padding: 4px 0;">
    {item("Protection strength","Strong")}
    {div}
    {item("Apps that skip the umbrella","Banking · 1 app")}
  </div>
  <div class="card" style="gap: 0; padding: 4px 0;">
    {item("Keep the umbrella open","2 of 3 phone settings done")}
    {div}
    {item("Open after a restart","","on")}
    {div}
    {item("Quick Settings tile","Add Adbrella to the notification shade")}
  </div>
  <div class="card" style="gap: 0; padding: 4px 0;">
    {item("Update automatically","Version 1.0.0-b12 · up to date","on")}
    {div}
    {item("Badge notifications","","off")}
  </div>
  <div class="card" style="gap: 0; padding: 4px 0;">
    {item("What Adbrella can and cannot block","")}
    {div}
    {item("Advanced","Blocklists, your own rules, DNS, logging")}
  </div>
 </div>
 {nav('settings')}
</div>'''
    return page(body, theme)

def advanced(theme):
    def item(title, sub, trailing="chev"):
        tr = f'<div style="color: var(--on-surface-variant);">{ICON["chev"]}</div>' if trailing=="chev" else (f'<div class="switch"></div>' if trailing=="on" else f'<div class="switch off"></div>')
        subhtml = f'<div class="body-s">{sub}</div>' if sub else ''
        return f'<div class="row" style="min-height: 56px; padding: 8px 16px;"><div style="flex-grow: 1;"><div class="body-m" style="font-size: 16px; line-height: 24px;">{title}</div>{subhtml}</div>{tr}</div>'
    div = '<div class="divider" style="margin: 0 16px;"></div>'
    body = f'''
<div class="phone">
 <div class="content" style="gap: 12px;">
  <div class="row" style="gap: 8px;"><div style="color: var(--on-surface);">{ICON['back']}</div><div style="font-size: 22px; line-height: 28px; font-weight: 500;">Advanced</div></div>
  <div class="body-s" style="padding: 0 4px;">Everything here works out of the box. Change it only if you know what you are looking for.</div>
  <div class="card" style="gap: 0; padding: 4px 0;">
    <div class="label-m" style="padding: 8px 16px 0 16px; color: var(--primary);">Filtering</div>
    {item("Blocklists","6 lists on · 288,412 domains · updated 2 h ago")}
    {div}
    {item("Your allow and block rules","3 allowed · 1 blocked")}
    {div}
    {item("Blocked answer","Null IP (recommended)")}
    {div}
    {item("Safety allowlist","Never blocks Play, push notifications, Samsung account","on")}
  </div>
  <div class="card" style="gap: 0; padding: 4px 0;">
    <div class="label-m" style="padding: 8px 16px 0 16px; color: var(--primary);">DNS</div>
    {item("Upstream resolver","Quad9 · DNS-over-HTTPS")}
    {div}
    {item("Catch hard-coded resolvers","Filters apps that talk to 8.8.8.8 or 1.1.1.1 directly","on")}
  </div>
  <div class="card" style="gap: 0; padding: 4px 0;">
    <div class="label-m" style="padding: 8px 16px 0 16px; color: var(--primary);">Diagnostics</div>
    {item("Query log","Keep the last 1,500 lookups with the app that made them","on")}
    {div}
    {item("Reset statistics","")}
    {div}
    {item("Version","1.0.0-b12 · 79db37f")}
  </div>
 </div>
</div>'''
    return page(body, theme)

def lists(theme):
    def level(name, desc, on=False):
        dot = '<div style="width: 10px; height: 10px; border-radius: 5px; background: var(--primary);"></div>' if on else ''
        bc = 'primary' if on else 'outline'
        radio = f'<div style="width: 20px; height: 20px; border-radius: 10px; border: 2px solid var(--{bc}); box-sizing: border-box; display: flex; align-items: center; justify-content: center;">{dot}</div>'
        return f'<div class="row" style="min-height: 56px; padding: 8px 16px;">{radio}<div style="flex-grow: 1;"><div class="body-m" style="font-size: 16px; line-height: 24px;">{name}</div><div class="body-s">{desc}</div></div></div>'
    def lst(name, desc, on=True):
        return f'<div class="row" style="min-height: 56px; padding: 8px 16px;"><div style="flex-grow: 1;"><div class="body-m" style="font-size: 16px; line-height: 24px;">{name}</div><div class="body-s">{desc}</div></div><div class="switch{"" if on else " off"}"></div></div>'
    body = f'''
<div class="phone">
 <div class="content" style="gap: 12px;">
  <div class="row" style="gap: 8px;"><div style="color: var(--on-surface);">{ICON['back']}</div><div style="font-size: 22px; line-height: 28px; font-weight: 500;">Protection strength</div></div>
  <div class="card" style="gap: 0; padding: 4px 0;">
    {level("Light","Blocks the big ad networks. Nothing ever breaks.")}
    <div class="divider" style="margin: 0 16px;"></div>
    {level("Balanced","Blocks ads and most trackers. Rarely needs a fix.")}
    <div class="divider" style="margin: 0 16px;"></div>
    {level("Strong","Blocks ads, trackers and phone telemetry. If an app misbehaves, allow it from Activity.", True)}
    <div class="divider" style="margin: 0 16px;"></div>
    {level("Custom","Your own selection, set under Advanced › Blocklists.")}
  </div>
  <div class="body-s" style="padding: 0 4px;">Stronger settings block more but may occasionally stop a feature in an app. When that happens, open Activity and tap Allow next to the app.</div>
 </div>
</div>'''
    return page(body, theme)


def splash(theme):
    body = f'''
<div class="phone" style="background: linear-gradient(135deg, #4FC3F7 0%, #1E88E5 55%, #26C6DA 100%); align-items: center; justify-content: center; color: #FFFFFF;">
  <div style="display: flex; flex-direction: column; align-items: center; gap: 6px;">
    <svg width="220" height="200" viewBox="0 0 220 200" fill="none"><path d="M18 112 A92 62 0 0 1 202 112 A15.3 11 0 0 1 171.3 112 A15.3 11 0 0 1 140.7 112 A15.3 11 0 0 1 110 112 A15.3 11 0 0 1 79.3 112 A15.3 11 0 0 1 48.7 112 A15.3 11 0 0 1 18 112 Z" fill="#FFFFFF"></path><path d="M110 40 L64 112 M110 40 L156 112" stroke="#000000" stroke-opacity="0.08" stroke-width="2"></path><circle cx="110" cy="36" r="4.5" fill="#FFFFFF"></circle><path d="M110 108 V164 A10 10 0 0 1 90 164" stroke="#FFFFFF" stroke-width="8" stroke-linecap="round"></path><circle cx="60" cy="14" r="6" fill="#FFFFFF" fill-opacity="0.9"></circle><circle cx="118" cy="30" r="6" fill="#FFFFFF" fill-opacity="0.8"></circle><circle cx="182" cy="66" r="6" fill="#FFFFFF" fill-opacity="0.5"></circle></svg>
    <div style="font-size: 30px; line-height: 36px; font-weight: 700;">Adbrella</div>
    <div style="font-size: 16px; line-height: 24px; opacity: 0.85;">Keeps the ads off you.</div>
  </div>
</div>'''
    return page(body, theme)

files = {
 "Splash.dc.html": splash(BRAND),
 "Main.dc.html": home(BRAND, True, True),
 "HomeClosed.dc.html": home(BRAND, False, False),
 "HomeDynamicColor.dc.html": home(DYNAMIC, True, False),
 "Activity.dc.html": activity(BRAND),
 "Stats.dc.html": stats(BRAND),
 "Settings.dc.html": settings(BRAND),
 "ProtectionLevel.dc.html": lists(BRAND),
 "Advanced.dc.html": advanced(BRAND),
}
for k,v in files.items(): open(k,'w').write(v)
canvas = {"artboards": [
  {"file":"Splash.dc.html","x":-480,"y":0,"w":390,"h":844,"title":"Launch splash (1.6 s)"},
  {"file":"Main.dc.html","x":0,"y":0,"w":390,"h":844,"title":"Home · open"},
  {"file":"HomeClosed.dc.html","x":480,"y":0,"w":390,"h":844,"title":"Home · closed"},
  {"file":"HomeDynamicColor.dc.html","x":960,"y":0,"w":390,"h":844,"title":"Home · wallpaper colors (Material You)"},
  {"file":"Activity.dc.html","x":0,"y":980,"w":390,"h":844,"title":"Activity"},
  {"file":"Stats.dc.html","x":480,"y":980,"w":390,"h":844,"title":"Stats"},
  {"file":"Settings.dc.html","x":960,"y":980,"w":390,"h":844,"title":"Settings"},
  {"file":"ProtectionLevel.dc.html","x":1440,"y":980,"w":390,"h":844,"title":"Protection strength"},
  {"file":"Advanced.dc.html","x":1920,"y":980,"w":390,"h":844,"title":"Settings › Advanced"},
 ],
 "annotations": [
  {"id":"brief","x":0,"y":-170,"w":440,"text":"Adbrella on the S23 — quiet and native.\nBranding lives in a 1.6 s launch splash (umbrella pops open, three ad-drops bounce off, wordmark fades in) and then stays out of the way: a status card with one switch, three numbers, a setup card that disappears once One UI is configured, a level line, and the last bounces. Wallpaper colors on device (third artboard). Every technical control sits under Settings › Advanced."},
  {"id":"dyn","x":960,"y":-90,"w":390,"text":"On the phone the palette follows the wallpaper (dynamicColorScheme). The brand blue is the fallback and the umbrella stays white, so the identity survives any wallpaper."}
 ],
 "launch": {"view":"canvas"}}
json.dump(canvas, open("canvas.json","w"), indent=1)
print("ok", list(files))
