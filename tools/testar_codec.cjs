// Codec do Brastoy Simulator extraído para o app (app/src/main/assets/brastoy-codec.js): ida e volta com códigos
// reais do simulador (B8 e B5), inventário sozinho, construção + inventário e o projeto no formato que o app gera.
// Uso: node tools/testar_codec.cjs   (roda no CI antes de publicar)
const fs = require('fs'), path = require('path');
const C = require('../app/src/main/assets/brastoy-codec.js');
// O B8 reordena as peças para comprimir melhor: compara o CONJUNTO (cada peça casa com uma peça igual do outro lado).
const samePiece = (x, y) => x.type === y.type && x.size === y.size && x.color === y.color &&
  x.pos.every((v, k) => Math.abs(v - y.pos[k]) < 1 / 256 + 1e-9) &&
  Math.abs(Math.abs(x.rot.reduce((s, v, k) => s + v * y.rot[k], 0)) - 1) < 1e-3;
const samePieces = (a, b) => {
  if (a.items.length !== b.items.length) return false;
  const key = x => x.type + '/' + x.size + '/' + x.color + '/' + x.pos.map(v => Math.round(v * 256)).join(',');
  const pool = new Map();
  for (const y of b.items) { const k = key(y); if (!pool.has(k)) pool.set(k, []); pool.get(k).push(y); }
  return a.items.every(x => { const l = pool.get(key(x)) || []; const i = l.findIndex(y => samePiece(x, y)); if (i < 0) return false; l.splice(i, 1); return true; });
};
const sameInventory = (a, b) => C.kinds.every(k => (a?.[k.size]?.[k.type] ?? 10) === (b?.[k.size]?.[k.type] ?? 10));
let fail = 0;
const check = (ok, msg) => { console.log(`${ok ? 'OK ' : 'FALHOU'} ${msg}`); if (!ok) fail++; };

(async () => {
  console.log('codec do simulador', C.version, '—', C.kinds.length, 'tipos,', C.palette.length, 'cores');
  check(C.kinds.length === 30, '30 pares tamanho/formato no inventário');
  // códigos reais: decodifica, regrava em B8 (só construção) e confere peça a peça
  for (const f of fs.readdirSync(path.join(__dirname, 'testes')).filter(f => /\.b[58]\.txt$/.test(f)).sort()) {
    const text = fs.readFileSync(path.join(__dirname, 'testes', f), 'utf8').trim();
    const a = await C.decode(text);
    for (const goal of ['chars', 'bytes']) {
      const code = C.encodeB8({ items: a.items, inventoryMode: 'unlimited' }, { pieces: true }, goal);
      const b = await C.decode(code);
      check(code[0] === '8' && samePieces(a, b), `${f} → B8 (${goal}): ${a.items.length} peças, ${[...text].length} → ${[...code].length} caracteres`);
    }
  }
  // inventário sozinho (o que a contagem do app exporta)
  const inv = JSON.parse(JSON.stringify(C.inventory));
  for (const k of C.kinds) inv[k.size][k.type] = 0;
  inv.grande.quadrado = 23; inv.grande.equilatero = 8; inv.medio.quadrado = 12; inv.medio.encaixe = 4; inv.grande.janela = 3;
  const ic = C.encodeB8({ inventory: inv, inventoryMode: 'limited' }, { pieces: false, inventory: true });
  const iq = await C.decode(ic);
  check(!iq.items && sameInventory(inv, iq.inventory) && iq.inventoryMode === 'limited' && iq.b8.inventory && !iq.b8.pieces,
    `inventário sozinho: ${JSON.stringify(ic)} (${[...ic].length} caracteres)`);
  // construção + inventário no mesmo código
  const app = { inventoryMode: 'limited', inventory: inv, items: [
    { type: 'isoceles', size: 'grande', color: '#ff738a', pos: [0.5, 0.0325, -1.25], rot: [0.7071068, 0, 0, 0.7071068] },
    { type: 'encaixe', size: 'medio', color: '#ff98c4', pos: [2, 0.0325, 1], rot: [0.7071068, 0, 0, 0.7071068] },
    { type: 'moldura', size: 'grande', color: '#b19aff', pos: [2, 0.0325, 1], rot: [0.7071068, 0, 0, 0.7071068] },
    { type: 'L', size: 'grande', color: '#79ddb0', pos: [-1, 0.5, 0], rot: [0, 0.3826834, 0, 0.9238795] },
    { type: 'quadrado', size: 'pequeno', color: '#78caff', pos: [1, 0.25, 0.5], rot: [0, 0, 0, 1] },
  ] };
  const both = C.encodeB8(app, { pieces: true, inventory: true });
  const bq = await C.decode(both);
  check(samePieces(app, bq) && sameInventory(inv, bq.inventory), `construção do app + inventário: ${app.items.length} peças, ${[...both].length} caracteres`);
  // código antigo continua saindo (simuladores anteriores ao B8)
  const legacy = await C.encodeLegacy({ items: app.items });
  check(samePieces(app, await C.decode(legacy)), `B4 antigo ainda funciona (${[...legacy].length} caracteres)`);
  // erro claro para código estragado
  let err = '';
  try { await C.decode(both.slice(0, -3) + '一一一'); } catch (e) { err = e.message; }
  check(/B8/.test(err), `código estragado é recusado: "${err}"`);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('ERRO', e.stack); process.exit(1); });
