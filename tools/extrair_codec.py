"""Extrai o codec de códigos do Brastoy Simulator (B8, B7, B6, B5, B4, B2, B1 e JSON) para um módulo JS independente.

O app NÃO reimplementa os formatos: usa exatamente as funções do simulador (num WebView no celular e no Node
nos testes). Rodar de novo sempre que o simulador mudar:
    python tools/extrair_codec.py <pasta do simulador>/index.html app/src/main/assets/brastoy-codec.js

Em vez de uma lista fixa de funções, parte das funções de entrada (ENTRY) e puxa, por fechamento, toda
declaração de topo cujo nome aparece no código já puxado. Assim, quando o simulador ganha um formato novo
ou uma função auxiliar, a extração acompanha sozinha.
"""
import json
import re
import sys

src = open(sys.argv[1], encoding='utf-8').read()
script = "\n".join(s for s in re.findall(r"<script(?![^>]*type=\"text/plain\")[^>]*>(.*?)</script>", src, re.S))
LINES = script.split("\n")

# Funções que o app chama
ENTRY = ['decodeProject', 'encodeB8', 'encodeBestB8', 'decodeB8', 'encodeBestBRS', 'parseProjectItems', 'kinds', 'palette',
         'inventory', 'kindCodes', 'q24', 'factoryInventory']
# Nunca puxar (dependem da tela/estado do simulador); se o codec precisar de algum, a extração falha de propósito
DENY = {'applyInventory', 'applyImported', 'applyProject', 'renderPending', 'renderHolds', 'runSceneAction', 'scene',
        'items', 'jolt', 'benchCacheAll', 'benchSig', 'b7ExportBench'}

# Declaração de topo: o simulador escreve o código de topo sempre na coluna 0; continuações não começam com
# uma palavra de declaração.
TOP = re.compile(r"^(async function |function |const |let |var |//|\$\(|class |window\.|document\.|<)")
# (de propósito NÃO inclui "for(" / "if(": linhas como "for(...)q24.push(...)" completam a declaração de cima)
DECL = re.compile(r"^(?:async )?function (\w+)|^class (\w+)|^(?:const|let|var) (.*)")


def names_of(line):
    m = DECL.match(line)
    if not m:
        return []
    if m.group(1) or m.group(2):
        return [m.group(1) or m.group(2)]
    # const a=..., b=... (só nomes no nível zero de chaves/parênteses)
    out, depth, i, s = [], 0, 0, m.group(3)
    expect = True
    while i < len(s):
        c = s[i]
        if expect:
            mm = re.match(r"\s*(\w+)\s*=", s[i:])
            if mm and depth == 0:
                out.append(mm.group(1)); i += mm.end(); expect = False; continue
            mm = re.match(r"\s*\{([^}]*)\}\s*=", s[i:])  # const {a,b}=...
            if mm and depth == 0:
                out += [x.split(':')[-1].strip() for x in mm.group(1).split(',') if x.strip()]; i += mm.end(); expect = False; continue
            break
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif c in '\'"`':
            j = i + 1
            while j < len(s) and s[j] != c:
                j += 2 if s[j] == '\\' else 1
            i = j
        elif c == ',' and depth == 0:
            expect = True
        elif c == ';' and depth == 0:
            break
        i += 1
    return out


decls = {}  # nome -> (início, fim)
order = []
i = 0
while i < len(LINES):
    ns = names_of(LINES[i])
    if ns:
        j = i + 1
        while j < len(LINES) and not TOP.match(LINES[j]):
            j += 1
        for n in ns:
            if n not in decls:
                decls[n] = (i, j)
        order.append((i, j))
        i = j
    else:
        i += 1

# nome solto ou depois de spread ("...X"); depois de um ponto só é propriedade (Math.floor não é a "floor" da cena)
ident = re.compile(r"(?<![\w$])(?:(?<=\.\.\.)|(?<!\.))([A-Za-z_]\w*)")
want, todo = set(), list(ENTRY)
while todo:
    n = todo.pop()
    if n in want or n in DENY:
        continue
    if n not in decls:
        continue
    want.add(n)
    a, b = decls[n]
    body = "\n".join(LINES[a:b])
    for x in set(ident.findall(body)):
        if x in decls and x not in want:
            if x in DENY:
                continue
            todo.append(x)

blocks = sorted({decls[n] for n in want})
for n in ENTRY:
    if n not in want:
        raise SystemExit('não achei ' + n)

# Valores padrão dos campos que o codec lê da tela ($(id).value/min/max/checked)
inputs = {}
for m in re.finditer(r"<(input|select)[^>]*id=\"([A-Za-z0-9_]+)\"[^>]*>", src):
    tag = m.group(0)
    d = {}
    for k in ('value', 'min', 'max'):
        mm = re.search(r'\b%s="([^"]*)"' % k, tag)
        if mm:
            d[k] = mm.group(1)
    d['checked'] = bool(re.search(r'\bchecked\b', tag))
    if m.group(1) == 'select':
        mm = re.search(r'<option value="([^"]*)"', src[m.end():m.end() + 400])
        d.setdefault('value', mm.group(1) if mm else '')
    inputs[m.group(2)] = d

ver = re.search(r"<title>Brastoy Simulator (MK-[\d.]+)", src)
out = [
    '// GERADO por tools/extrair_codec.py a partir do Brastoy Simulator %s — não editar à mão.' % (ver.group(1) if ver else '?'),
    '"use strict";',
    'const __inputs = ' + json.dumps(inputs) + ';',
    'function $(id){ const d = __inputs[id] || {}; return { value: d.value ?? "", min: d.min ?? "-Infinity", max: d.max ?? "Infinity", checked: !!d.checked }; }',
    'function log(){}',
]
for a, b in blocks:
    out.append("\n".join(LINES[a:b]).rstrip())
out.append('''
const BrastoyCodec = {
  version: %s,
  palette, kinds, q24, inventory, factoryInventory,
  /** Projeto {items, inventoryMode?, inventory?} -> código B8. sections: {pieces, inventory, physics}. goal: 'chars' | 'bytes'. */
  encodeB8(project, sections = {}, goal = 'chars') {
    return encodeBestB8(project, goal, { pieces: sections.pieces !== false, physics: !!sections.physics, inventory: !!sections.inventory, bench: false, lock: null }).code;
  },
  /** Qualquer formato que o simulador importa (B8…B1, JSON) -> projeto. */
  async decode(text) { return await decodeProject(text); },
  /** Código B4 antigo (para quem ainda usa simuladores anteriores ao B8). */
  async encodeLegacy(project, alphabet = 'unicode') { return (await encodeBestBRS(project, alphabet)).code; },
};
if (typeof module !== 'undefined') module.exports = BrastoyCodec;
if (typeof window !== 'undefined') window.BrastoyCodec = BrastoyCodec;
''' % json.dumps(ver.group(1) if ver else '?'))
open(sys.argv[2], 'w', encoding='utf-8').write("\n".join(out))
print('ok', len(want), 'declarações em', len(blocks), 'blocos;', ver.group(1) if ver else '?')
