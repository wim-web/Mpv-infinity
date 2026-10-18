#!/usr/bin/env python3
"""Package rendered Compose previews into a local, offline review gallery."""
from pathlib import Path
import html
import shutil

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/screenshotTestStandardDebug/reference'
out = root / 'app/build/tablet-previews'
out.mkdir(parents=True, exist_ok=True)
screens = {
    'SelectedFoldersPreview': ('folders', '対象フォルダ'),
    'EmptyLibraryPreview': ('empty', '未選択'),
    'FolderListPreview': ('library', 'フォルダ一覧'),
    'PlaylistsPreview': ('playlists', 'プレイリスト'),
}
modes = {'portrait': '縦', 'landscape': '横', 'dark': 'ダーク'}
sections = []
for mode, label in modes.items():
    cards = []
    for function, (slug, title) in screens.items():
        matches = list(source.rglob(f'{function}_Tablet {mode}_*.png'))
        if len(matches) != 1:
            raise SystemExit(f'Expected one rendered image for {function}/{mode}, got {len(matches)}')
        filename = f'{slug}-{mode}.png'
        shutil.copy2(matches[0], out / filename)
        cards.append(f'<article><h2>{html.escape(title)}</h2><a href="{filename}" target="_blank"><img src="{filename}" alt="{html.escape(title)}・{label}"></a></article>')
    sections.append(f'<section data-mode="{mode}" {"" if mode == "portrait" else "hidden"}>{"".join(cards)}</section>')
page = '''<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mpv∞ タブレットPreview</title><style>
*{box-sizing:border-box}body{margin:0;background:#e9e9eb;color:#242428;font:15px system-ui,sans-serif}header{position:sticky;top:0;z-index:1;background:#fafafaf2;border-bottom:1px solid #d6d6da;padding:18px 24px;backdrop-filter:blur(12px)}h1{font-size:20px;margin:0 0 6px}p{margin:0;color:#606066;font-size:13px}nav{display:flex;gap:8px;margin-top:14px}button{font:inherit;border:1px solid #ccc;border-radius:18px;padding:6px 20px;background:white;cursor:pointer}button[aria-pressed=true]{background:#242428;color:white;border-color:#242428}main{padding:20px}section{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:18px}section[hidden]{display:none}section[data-mode=landscape]{grid-template-columns:repeat(2,minmax(0,1fr))}article{min-width:0}h2{font-size:14px;margin:0 0 10px}img{width:100%;display:block;border:1px solid #ccc;border-radius:8px;box-shadow:0 3px 12px #0000000c}a{display:block}footer{padding:0 20px 24px;font-size:12px;color:#666}@media(max-width:900px){section{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:540px){section,section[data-mode=landscape]{grid-template-columns:1fr}}
</style><header><h1>Mpv∞ タブレットPreview</h1><p>日本語 · 800 × 1280 dp / 1280 × 800 dp · サンプルデータ · 画像をクリックすると拡大</p><nav aria-label="表示条件">'''
page += ''.join(f'<button data-mode="{mode}" aria-pressed="{str(mode == "portrait").lower()}">{label}</button>' for mode, label in modes.items())
page += '</nav></header><main>' + ''.join(sections) + '</main><footer>Composeが描画した画像です。対象フォルダは画面本体、一覧は本体の部品を組み合わせたPreviewです。リリース前の確認用。</footer>'
page += '''<script>document.querySelectorAll('button').forEach(b=>b.onclick=()=>{document.querySelectorAll('button').forEach(x=>x.setAttribute('aria-pressed',String(x===b)));document.querySelectorAll('section').forEach(s=>s.hidden=s.dataset.mode!==b.dataset.mode);});</script></html>'''
(out / 'index.html').write_text(page)
print(out / 'index.html')
