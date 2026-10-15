"""Extrai o codec B2/B4/B5 do index.html do Brastoy Simulator para um módulo JS independente.

O app NÃO reimplementa o formato: usa exatamente as funções do simulador (num WebView no celular
e no Node nos testes). Rodar de novo sempre que o simulador mudar:
    python tools/extrair_codec_b5.py caminho/index.html app/src/main/assets/b5codec.js
"""
import json
import re
import sys

src = open(sys.argv[1], encoding='utf-8').read()
script = "\n".join(re.findall(r"<script[^>]*>(.*?)</script>", src, re.S))
LINES = script.split("\n")

NAMES = [
    'inventory', 'physKeys', 'palette', 'legacyKinds', 'kinds', 'kindCodes', 'finiteDenseVector', 'parseProjectItems',
    'validatePhysics', 'crc32', 'u8toB64url', 'b64urlToU8', 'decodeBRSLegacy', 'alphabet91', 'digit91', 'b91enc',
    'MAX_B91_BYTES', 'b91dec', 'crc16', 'pack16', 'unpack16', 'writeUV', 'readUV', 'writeDelta', 'readDelta',
    'physFactor', 'defaultPhysics', 'q24', 'rotId', 'makeRecords', 'sameRecord', 'encodeBRS', 'decodeBRS',
    'writeB4Header', 'templateCompatible', 'motifKey', 'encodeB4Raw', 'decodeB4Raw', 'reorderedRows', 'deflateBytes',
    'inflateBytes', 'encodeUnicode', 'decodeUnicode', 'encodeBestBRS', 'decodeB4', 'jsonDiagnostic', 'parseProjectJSON',
    'decodeProject', 'validateB5Extra',
]

# Declaração de topo: o simulador escreve o código de topo sempre na coluna 0; continuações
# (ex.: "for(...)q24.push", "if(kinds.length...)") não começam com uma palavra de declaração.
TOP = re.compile(r"^(async function |function |const |let |var |//|\$\(|class |window\.|document\.|<)")


def grab(name):
    pat = re.compile(r"^(?:async )?function %s\b|^const %s\b|^let %s\b" % (name, name, name))
    for i, line in enumerate(LINES):
        if pat.match(line):
            j = i + 1
            while j < len(LINES) and not TOP.match(LINES[j]):
                j += 1
            return "\n".join(LINES[i:j]).rstrip()
    raise SystemExit("não achei " + name)


# Valores padrão dos campos que o codec lê da tela ($(id).value/min/max)
inputs = {}
for m in re.finditer(r"<(input|select)[^>]*id=\"([A-Za-z0-9_]+)\"[^>]*>", src):
    tag = m.group(0)
    d = {}
    for k in ('value', 'min', 'max'):
        mm = re.search(r'\b%s="([^"]*)"' % k, tag)
        if mm:
            d[k] = mm.group(1)
    if m.group(1) == 'select':
        mm = re.search(r'<option value="([^"]*)"', src[m.end():m.end() + 400])
        d.setdefault('value', mm.group(1) if mm else '')
    inputs[m.group(2)] = d

out = [
    '// GERADO por tools/extrair_codec_b5.py a partir do Brastoy Simulator — não editar à mão.',
    '"use strict";',
    'const __inputs = ' + json.dumps(inputs) + ';',
    'function $(id){ const d = __inputs[id] || {}; return { value: d.value ?? "", min: d.min ?? "-Infinity", max: d.max ?? "Infinity" }; }',
    'function log(){}',
]
for n in NAMES:
    out.append(grab(n))
out.append('''
const BrastoyCodec = {
  palette, kinds, q24,
  /** Projeto {items:[{type,size,color,pos,rot}]} -> melhor código B4 (o mesmo que o simulador gera sem trava). */
  async encode(project, alphabet = 'unicode') { return (await encodeBestBRS(project, alphabet)).code; },
  async decode(text) { return await decodeProject(text); },
};
if (typeof module !== 'undefined') module.exports = BrastoyCodec;
if (typeof window !== 'undefined') window.BrastoyCodec = BrastoyCodec;
''')
open(sys.argv[2], 'w', encoding='utf-8').write("\n".join(out))
print('ok', len(NAMES), 'declarações')
