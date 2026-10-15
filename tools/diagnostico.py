"""Diagnóstico do scanner gravado no celular (botão ⏺ Gravar) → reprodução no PC.

    python tools/diagnostico.py extrair  <zip>          # pasta com os quadros em .argb.gz (formato dos testes) + JSONs
    python tools/diagnostico.py desenhar <pasta>        # quadros anotados: amarelo = celular, branco = reprodução no PC (replay.jsonl)

Depois de extrair, reproduza o pipeline no PC com:
    ./gradlew :app:testDebugUnitTest --tests '*DiagReplayProbe*' -Ddiag=<pasta>
"""
import gzip
import json
import os
import struct
import sys
import zipfile

from PIL import Image, ImageDraw


def extrair(zip_path):
    out = os.path.splitext(zip_path)[0]
    os.makedirs(out, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        for name in z.namelist():
            data = z.read(name)
            if name.endswith('.jpg'):
                im = Image.open(z.open(name)).convert('RGB')
                w, h = im.size
                px = im.tobytes()
                with gzip.open(os.path.join(out, name[:-4] + '.argb.gz'), 'wb') as g:
                    g.write(struct.pack('>ii', w, h))
                    # ARGB big-endian, como DataInputStream.readInt espera
                    g.write(b''.join(b'\xff' + px[i:i + 3] for i in range(0, len(px), 3)))
                im.save(os.path.join(out, name))
            else:
                with open(os.path.join(out, name), 'wb') as f:
                    f.write(data)
    n = len([f for f in os.listdir(out) if f.endswith('.argb.gz')])
    print(f'{n} quadros em {out}')
    return out


def desenhar(pasta):
    cel = {}
    with open(os.path.join(pasta, 'quadros.jsonl'), encoding='utf-8') as f:
        for line in f:
            if line.strip():
                o = json.loads(line); cel[o['i']] = o
    pc = {}
    rp = os.path.join(pasta, 'replay.jsonl')
    if os.path.exists(rp):
        with open(rp, encoding='utf-8') as f:
            for line in f:
                if line.strip():
                    o = json.loads(line); pc[o['i']] = o
    out = os.path.join(pasta, 'anotado'); os.makedirs(out, exist_ok=True)
    for i, o in sorted(cel.items()):
        p = os.path.join(pasta, 'q%05d.jpg' % i)
        if not os.path.exists(p):
            continue
        im = Image.open(p).convert('RGB'); d = ImageDraw.Draw(im)
        for det in o.get('dets', []):
            pts = [tuple(c) for c in det['corners']]
            d.polygon(pts, outline=(255, 220, 0)); d.text(pts[0], det['shape'], fill=(255, 220, 0))
        for det in pc.get(i, {}).get('dets', []):
            pts = [(c[0] + 1, c[1] + 1) for c in det['corners']]
            d.polygon(pts, outline=(255, 255, 255)); d.text((pts[0][0], pts[0][1] + 10), det['shape'], fill=(255, 255, 255))
        d.text((4, 4), f"q{i} cel {len(o.get('dets', []))} det / mapa {len(o.get('map', []))}" + (f" · PC {len(pc[i]['dets'])} det / mapa {pc[i]['map']}" if i in pc else ''), fill=(255, 255, 255))
        im.save(os.path.join(out, 'q%05d.jpg' % i), quality=88)
    print(f'{len(cel)} quadros anotados em {out}')


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    {'extrair': extrair, 'desenhar': desenhar}[sys.argv[1]](sys.argv[2])
