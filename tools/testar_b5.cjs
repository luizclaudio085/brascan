// Ida e volta do codec B5 extraído do simulador: decodificar códigos reais, recodificar, decodificar de novo e comparar.
// Uso: node tools/testar_b5.cjs   (roda no CI antes de publicar)
const fs = require('fs'), path = require('path');
const C = require('../app/src/main/assets/b5codec.js');
const same = (a, b) => a.items.length === b.items.length && a.items.every((x, i) => {
  const y = b.items[i];
  return x.type === y.type && x.size === y.size && x.color === y.color &&
    x.pos.every((v, k) => Math.abs(v - y.pos[k]) < 1 / 256 + 1e-9) &&
    Math.abs(Math.abs(x.rot.reduce((s, v, k) => s + v * y.rot[k], 0)) - 1) < 1e-3;
});
(async () => {
  let fail = 0;
  for (const f of fs.readdirSync(path.join(__dirname, 'testes')).filter(f => f.endsWith('.b5.txt'))) {
    const text = fs.readFileSync(path.join(__dirname, 'testes', f), 'utf8').trim();
    const a = await C.decode(text);
    const code = await C.encode({ items: a.items });
    const b = await C.decode(code);
    const ok = same(a, b);
    console.log(`${ok ? 'OK ' : 'FALHOU'} ${f}: ${a.items.length} peças, ${text.length} → ${code.length} caracteres`);
    if (!ok) fail++;
  }
  // projeto no formato que o app exporta (cores fora da paleta são mapeadas antes)
  const app = { items: [
    { type: 'isoceles', size: 'grande', color: '#ff738a', pos: [0.5, 0.0325, -1.25], rot: [0.7071068, 0, 0, 0.7071068] },
    { type: 'encaixe', size: 'medio', color: '#ff98c4', pos: [2, 0.0325, 1], rot: [0.7071068, 0, 0, 0.7071068] },
    { type: 'moldura', size: 'grande', color: '#b19aff', pos: [2, 0.0325, 1], rot: [0.7071068, 0, 0, 0.7071068] },
    { type: 'L', size: 'grande', color: '#79ddb0', pos: [-1, 0.5, 0], rot: [0, 0.3826834, 0, 0.9238795] },
  ] };
  const back = await C.decode(await C.encode(app));
  const ok = same(app, back); console.log(`${ok ? 'OK ' : 'FALHOU'} projeto do app: ${app.items.length} peças`); if (!ok) fail++;
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('ERRO', e.stack); process.exit(1); });
