"""Порт дизайн-токенов сайта (tokens.css) в ресурсы Android: цвета обеих тем, размеры px→dp 1:1."""
import re, sys
src = open(sys.argv[1]).read(); res = sys.argv[2]
def block(sel):
    m = re.search(re.escape(sel) + r'\s*\{(.*?)\n\}', src, re.S); return m.group(1)
def vars_(b): return dict(re.findall(r'--([a-z0-9-]+)\s*:\s*([^;]+);', b))
light = vars_(block(':root, [data-theme="light"]')); dark = dict(light); dark.update(vars_(block('[data-theme="dark"]')))
def resolve(v, env, depth=0):
    m = re.fullmatch(r'var\(--([a-z0-9-]+)\)', v.strip())
    return resolve(env[m.group(1)], env, depth + 1) if m and depth < 9 else v.strip()
def color(v):
    v = v.strip()
    if re.fullmatch(r'#[0-9A-Fa-f]{6}', v): return '#FF' + v[1:].upper()
    m = re.fullmatch(r'rgba\((\d+),\s*(\d+),\s*(\d+),\s*([.\d]+)\)', v)
    if m: r, g, b, a = m.groups(); return '#%02X%02X%02X%02X' % (round(float(a) * 255), int(r), int(g), int(b))
    return None
def name(k): return 'web_' + k.replace('-', '_')
def write(path, env, dims):
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- СГЕНЕРИРОВАНО tools/tokens2res.py из web/tokens.css. Не править руками. -->', '<resources>']
    for k, v in env.items():
        c = color(resolve(v, env))
        if c: out.append('    <color name="%s">%s</color>' % (name(k), c))
    if dims:
        for k, v in env.items():
            v = resolve(v, env)
            m = re.fullmatch(r'(\d+(?:\.\d+)?)px', v)
            if m: out.append('    <dimen name="%s">%sdp</dimen>' % (name(k), m.group(1)) if not k.startswith('fs-') else '    <dimen name="%s">%ssp</dimen>' % (name(k), m.group(1)))
            elif k == 'line-height': out.append('    <item name="web_line_height" format="float" type="dimen">%s</item>' % v)
    out.append('</resources>'); open(path, 'w').write('\n'.join(out) + '\n')
write(res + '/values/web_tokens.xml', light, True)
write(res + '/values-night/web_tokens.xml', dark, False)
print(len(light), 'tokens')
