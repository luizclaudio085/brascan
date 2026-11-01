// GERADO por tools/extrair_codec.py a partir do Brastoy Simulator MK-10.51 — não editar à mão.
"use strict";
const __inputs = {"stockMode": {"checked": false, "value": "limited"}, "inventoryFile": {"checked": false}, "size": {"checked": false, "value": "grande"}, "rotationStep": {"checked": false, "value": "1"}, "color": {"checked": false, "value": "#78caff"}, "gridStep": {"checked": false, "value": "1"}, "buildHeight": {"value": "0", "min": "0", "max": "2.4", "checked": false}, "moduleCount": {"value": "4", "min": "2", "max": "30", "checked": false}, "localSaveName": {"checked": false}, "localList": {"checked": false, "value": ""}, "draftList": {"checked": false, "value": ""}, "codeFormat": {"checked": false, "value": "b8"}, "b8Goal": {"checked": false, "value": "chars"}, "b8Pieces": {"checked": true}, "b8Physics": {"checked": true}, "b8Inventory": {"checked": false}, "b8Bench": {"checked": true}, "codeAlphabet": {"checked": false, "value": "balanced"}, "lockExport": {"checked": true}, "file": {"checked": false}, "convTarget": {"checked": false, "value": "b8"}, "camMode": {"checked": false, "value": "padrao"}, "gridStepQuick": {"checked": false, "value": ""}, "rotationStepQuick": {"checked": false, "value": ""}, "wallNX": {"value": "4", "min": "1", "max": "60", "checked": false}, "wallNY": {"value": "2", "min": "1", "max": "40", "checked": false}, "wallNZ": {"value": "0", "min": "0", "max": "60", "checked": false}, "simUseBake": {"checked": true}, "optimizeOnImport": {"checked": true}, "optimizeBeforeRun": {"checked": true}, "engineMode": {"checked": false, "value": "auto"}, "engineLive": {"checked": false, "value": "auto"}, "engineBake": {"checked": false, "value": "auto"}, "framePolicy": {"checked": false, "value": "auto"}, "smoothMotion": {"checked": true}, "bakeDuration": {"value": "5", "min": "0.5", "max": "60", "checked": false}, "bakeFps": {"checked": false, "value": "30"}, "bakeSpeed": {"checked": false, "value": "0.1"}, "bakeTime": {"value": "0", "min": "0", "max": "1000", "checked": false}, "showTrails": {"checked": true}, "bakeRateMode": {"checked": false, "value": "adapt"}, "bakeHz": {"value": "2000", "min": "120", "max": "2000", "checked": false}, "bakeUntilAsleep": {"checked": true}, "physicsRateMode": {"checked": false, "value": "auto"}, "physicsRate": {"value": "120", "min": "60", "max": "2000", "checked": false}, "timeScale": {"checked": false, "value": "1"}, "contactPrecision": {"checked": false, "value": "padrao"}, "deterministic": {"checked": true}, "fieldB": {"value": "53", "min": "0", "max": "2000", "checked": false}, "range": {"value": "5", "min": "2", "max": "15", "checked": false}, "tileSide": {"value": "7.5", "min": "4", "max": "16", "checked": false}, "core": {"value": "5", "min": "2", "max": "12", "checked": false}, "tileMass": {"value": "115", "min": "5", "max": "150", "checked": false}, "friction": {"value": "0.85", "min": "0", "max": "1.5", "checked": false}, "bounce": {"value": "0", "min": "0", "max": "0.8", "checked": false}, "showMagnets": {"checked": false}, "magView": {"checked": false, "value": "pieces"}, "fieldLines": {"value": "24", "min": "8", "max": "80", "checked": false}, "fieldQuality": {"checked": false, "value": "120"}, "magExtent": {"value": "0.9", "min": "0.3", "max": "2.4", "checked": false}, "gfxQuality": {"checked": false, "value": "high"}, "plateLook": {"checked": false, "value": "solid"}, "pieceOpacity": {"value": "72", "min": "8", "max": "85", "checked": false}, "ballUseBake": {"checked": true}, "ballMass": {"value": "0.3", "min": "0.1", "max": "3", "checked": false}, "ballRadius": {"value": "0.056", "min": "0.01", "max": "0.06", "checked": false}, "ballHeight": {"value": "0.75", "min": "0.15", "max": "0.9", "checked": false}, "ballX": {"value": "0", "min": "-1.5", "max": "1.5", "checked": false}, "ballZ": {"value": "0", "min": "-1.5", "max": "1.5", "checked": false}, "ballAzimuth": {"value": "0", "min": "0", "max": "355", "checked": false}, "ballElevation": {"value": "0", "min": "-89", "max": "89", "checked": false}, "ballSpeed": {"value": "0", "min": "0", "max": "30", "checked": false}, "ballShowAim": {"checked": true}, "ballAt": {"value": "0", "min": "0", "max": "60", "checked": false}, "ballList": {"checked": false, "value": ""}};
function $(id){ const d = __inputs[id] || {}; return { value: d.value ?? "", min: d.min ?? "-Infinity", max: d.max ?? "Infinity", checked: !!d.checked }; }
function log(){}
const inventory={
 grande:{quadrado:10,janela:10,moldura:10,retangulo:10,equilatero:10,retanguloTri:10,isoceles:10,E:10,L:10,porta:10},
 medio:{quadrado:10,janela:10,moldura:10,retangulo:10,equilatero:10,retanguloTri:10,isoceles:10,L:10,porta:10,encaixe:10,E:10},
 pequeno:{equilatero:10,retanguloTri:10,quadrado:10,janela:10,moldura:10,retangulo:10,isoceles:10,L:10,porta:10}
};
const total=()=>Object.values(inventory).reduce((a,b)=>a+Object.values(b).reduce((x,y)=>x+y,0),0);
function finiteDenseVector(a,length,maxAbs=Infinity){
 if(!Array.isArray(a)||a.length!==length)return false;
 for(let i=0;i<length;i++)if(!Object.hasOwn(a,i)||!Number.isFinite(a[i])||Math.abs(a[i])>maxAbs)return false;
 return true;
}
const physKeys=['fieldB','range','tileSide','core','tileMass','friction','bounce','ballMass','ballRadius','ballHeight'];
const palette=['#78caff','#b19aff','#ffba74','#ff738a','#ff98c4','#79ddb0','#ffe18a'];
const legacyKinds=[
 ...['quadrado','janela','moldura','retangulo','equilatero','retanguloTri','isoceles','E','L','porta'].map(type=>({size:'grande',type})),
 ...['quadrado','janela','moldura','retangulo','equilatero','retanguloTri','isoceles','L','porta','encaixe'].map(type=>({size:'medio',type})),
 ...['equilatero','retanguloTri'].map(type=>({size:'pequeno',type}))
];
const kinds=[...legacyKinds,...Object.entries(inventory).flatMap(([size,types])=>Object.keys(types).map(type=>({size,type}))).filter(k=>!legacyKinds.some(old=>old.size===k.size&&old.type===k.type))];
if(kinds.length!==30||kinds.length*palette.length>=250)throw Error('Catálogo não cabe no formato B2');
const kindCodes=new Map(kinds.map((k,i)=>[k.size+'/'+k.type,i]));
const factoryInventory=JSON.parse(JSON.stringify(inventory));
function inventoryData(){return Object.fromEntries(Object.entries(inventory).map(([size,types])=>[size,{...types}]))}
function parseProjectItems(items){
 if(!Array.isArray(items)||items.length>4000)throw new Error('Projeto deve conter até 4000 peças');
 return items.map((x,i)=>{
  if(!x||!Number.isInteger(kindCodes.get(x.size+'/'+x.type)))throw new Error(`Peça ${i+1}: formato ou tamanho desconhecido`);
  if(!finiteDenseVector(x.pos,3,127.99))throw new Error(`Peça ${i+1}: posição inválida (limite ±127,99)`);
  if(!finiteDenseVector(x.rot,4))throw new Error(`Peça ${i+1}: rotação inválida`);
  const mag=Math.hypot(...x.rot);if(mag<.0001)throw new Error(`Peça ${i+1}: rotação zerada`);
  const color=String(x.color||palette[0]).toLowerCase();if(!/^#[0-9a-f]{6}$/.test(color))throw new Error(`Peça ${i+1}: cor inválida`);
  return {type:x.type,size:x.size,color,pos:[...x.pos],rot:x.rot.map(v=>v/mag)};
 });
}
const tileUnitPhysics=new Set(['ballRadius','ballHeight']);
function validatePhysics(obj){if(!obj)return {};const validated={};const tile=$('tileSide'),side=Math.max(Number(tile.min),Math.min(Number(tile.max),Number(obj.tileSide??tile.value)))/100;
 for(const k of physKeys){if(obj[k]===undefined)continue;const n=Number(obj[k]);if(!Number.isFinite(n))throw Error('Parâmetro físico inválido: '+k);const el=$(k),f=tileUnitPhysics.has(k)?side:1;validated[k]=Math.max(Number(el.min)/f,Math.min(Number(el.max)/f,n))}return validated}
function crc32(bytes){let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0)}return (c^0xffffffff)>>>0}
function b64urlToU8(s){if(!/^[A-Za-z0-9_-]+$/.test(s))throw Error('Código contém caracteres inválidos');let raw=atob(s.replace(/-/g,'+').replace(/_/g,'/')+'='.repeat((4-s.length%4)%4));return Uint8Array.from(raw,c=>c.charCodeAt(0))}
function decodeBRSLegacy(s){const code=String(s).trim().replace(/\s+/g,'');if(!code.startsWith('B1:'))throw Error('Código deve começar com B1:');const b=b64urlToU8(code.slice(3));const v=new DataView(b.buffer,b.byteOffset,b.byteLength);if(b.length<51||String.fromCharCode(...b.subarray(0,4))!=='BRS1')throw Error('Cabeçalho BRS inválido');if(v.getUint8(4)!==1)throw Error('Versão BRS não reconhecida');const count=v.getUint16(5,true);if(count>4000||b.length!==4+1+2+40+count*16+4)throw Error('Comprimento/capacidade do código inválido');const wanted=v.getUint32(b.length-4,true),got=crc32(b.subarray(0,b.length-4));if(wanted!==got)throw Error('Código corrompido: verificação CRC falhou');let at=7;const physics={};for(const key of physKeys){physics[key]=v.getFloat32(at,true);at+=4}const items=[];
 for(let i=0;i<count;i++){
  const kind=kinds[v.getUint8(at++)];const color=palette[v.getUint8(at++)];if(!kind||!color)throw Error('Tipo/cor inválido na peça '+(i+1));const pos=[];const rot=[];for(let j=0;j<3;j++){pos.push(v.getInt16(at,true)/256);at+=2}for(let j=0;j<4;j++){rot.push(v.getInt16(at,true)/32767);at+=2}items.push({...kind,color,pos,rot})
 }
 return {format:'Brastoy',version:1,physics,items:parseProjectItems(items)};
}
const alphabet91='0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'+Array.from({length:94},(_,i)=>String.fromCharCode(i+33)).filter(c=>!/^[A-Za-z0-9]$/.test(c)&&c!=='"'&&c!=="'"&&c!=='\\').join('');
if(alphabet91.length!==91)throw Error('Alfabeto de 91 símbolos inconsistente');
const digit91=Object.fromEntries([...alphabet91].map((c,i)=>[c,i]));
function b91enc(data){let b=0,n=0,out='';for(const x of data){b|=x<<n;n+=8;if(n>13){let v=b&8191;if(v>88){b>>>=13;n-=13}else{v=b&16383;b>>>=14;n-=14}out+=alphabet91[v%91]+alphabet91[Math.floor(v/91)]}}if(n){out+=alphabet91[b%91];if(n>7||b>90)out+=alphabet91[Math.floor(b/91)]}return out}
const MAX_B91_BYTES=200000;
function b91dec(str){
 // Base91 emits at most 14 bits per TWO characters, plus one terminal byte.
 // This allocation is bounded independently of the untrusted input length.
 const out=new Uint8Array(Math.min(MAX_B91_BYTES,Math.ceil(str.length*7/8)+2));
 let b=0,n=0,v=-1,at=0;
 for(const c of str){
  const d=digit91[c];if(d===undefined)throw Error('Caractere inválido no código compacto: '+c);
  if(v<0)v=d;
  else{
   v+=d*91;b|=v<<n;n+=(v&8191)>88?13:14;
   while(n>=8){if(at>=MAX_B91_BYTES)throw Error('Código Base91 excede 200 KB');out[at++]=b&255;b>>>=8;n-=8}
   v=-1;
  }
 }
 if(v>=0){if(at>=MAX_B91_BYTES)throw Error('Código Base91 excede 200 KB');out[at++]=(b|(v<<n))&255}
 return out.slice(0,at);
}
function crc16(a){let c=0xffff;for(const x of a){c^=x<<8;for(let j=0;j<8;j++)c=(c&0x8000)?((c<<1)^0x1021)&65535:(c<<1)&65535}return c}
function pack16(a){let c=crc16(a);return Uint8Array.from([...a,c&255,c>>>8])}
function unpack16(bytes,header){if(bytes.length<4||bytes[0]!==header)throw Error('Cabeçalho compacto inválido');const c=crc16(bytes.subarray(0,-2));if(bytes[bytes.length-2]!== (c&255)||bytes[bytes.length-1]!== (c>>>8))throw Error('Código corrompido (CRC16)');return bytes.subarray(0,-2)}
function writeUV(buf,n){if(!Number.isSafeInteger(n)||n<0||n>0xffffffff)throw Error('Número fora do limite compacto');while(n>=128){buf.push((n&127)|128);n=Math.floor(n/128)}buf.push(n)}
function readUV(buf,state){let n=0,f=1;for(let i=0;i<5;i++){if(state.i>=buf.length)throw Error('Código incompleto');const x=buf[state.i++];n+=(x&127)*f;if(x<128){if(n>0xffffffff)throw Error('Varint inválido');return n}f*=128}throw Error('Varint longo demais')}
function writeDelta(a,v){writeUV(a,v>=0?2*v:(-2*v-1))}
function readDelta(a,state){const v=readUV(a,state);return v&1?-(v+1)/2:v/2}
function inventoryFromValues(values){if(values.length!==kinds.length)throw Error('Inventário incompatível');const result=inventoryData();kinds.forEach((k,i)=>result[k.size][k.type]=values[i]);return result}
const physFactor=[1,2,2,2,.2,20,50,10,20,2];
const defaultPhysics=Object.freeze({fieldB:53,range:5,tileSide:7.5,core:5,tileMass:115,friction:.85,bounce:0,ballMass:3,ballRadius:.75,ballHeight:10});
const q24=[[0,0,0,1]];
for(const axis of [[1,0,0],[0,1,0],[0,0,1]])for(const ang of [Math.PI/2,Math.PI,3*Math.PI/2])q24.push([...axis.map(n=>n*Math.sin(ang/2)),Math.cos(ang/2)]);
for(const y of [-1,1])for(const z of [-1,1])for(const ang of [2*Math.PI/3,4*Math.PI/3])q24.push([1,y,z].map(x=>x/Math.sqrt(3)*Math.sin(ang/2)).concat(Math.cos(ang/2)));
for(const v of [[1,1,0],[1,-1,0],[1,0,1],[1,0,-1],[0,1,1],[0,1,-1]])q24.push([...v.map(x=>x/Math.sqrt(2)),0]);
if(q24.length!==24)throw Error('24 orientações inválidas');
function rotId(q){for(let i=0;i<q24.length;i++)if(Math.abs(q[0]*q24[i][0]+q[1]*q24[i][1]+q[2]*q24[i][2]+q[3]*q24[i][3])>1-1e-8)return i;return 255}
function makeRecords(data){return parseProjectItems(data).map(x=>{const kc=kindCodes.get(x.size+'/'+x.type),color=palette.indexOf(x.color);if(color<0)throw Error('Cor não cadastrada na paleta: '+x.color);const xyz=x.pos.map(y=>Math.round(y*256));if(xyz.some(y=>y < -32768||y>32767))throw Error('Coordenadas fora dos limites');const r=rotId(x.rot);return {...x,combo:kc*palette.length+color,xyz,r,orient:r===255?x.rot.map(t=>Math.round(t*32767)):null}})}
function sameRecord(a,b){return a&&b&&a.combo===b.combo&&a.r===b.r&&(a.r!==255||a.orient.every((v,i)=>v===b.orient[i]))}
function encodeBRS(project){const rows=makeRecords(project.items);if(rows.length>4000)throw Error('Limite de 4000 peças');const b=[0xb2,2];writeUV(b,rows.length);
 const physics=validatePhysics(project.physics||{});let mask=0;const packed=[];for(let i=0;i<physKeys.length;i++){const key=physKeys[i],v=physics[key]??defaultPhysics[key];const quant=Math.round(v*physFactor[i]);if(quant!==Math.round(defaultPhysics[key]*physFactor[i])){mask|=1<<i;packed.push(quant)}}writeUV(b,mask|(project.inventoryMode==='unlimited'?1024:0));for(const val of packed)writeUV(b,val);
 let last=null,prev=[0,0,0];function appendDelta(to){for(let j=0;j<3;j++)writeDelta(b,to[j]-prev[j]);prev=[...to]}
 for(let i=0;i<rows.length;){const row=rows[i];if(last&&sameRecord(last,row)){const delta=row.xyz.map((v,j)=>v-prev[j]);let count=0;
  while(i+count<rows.length&&sameRecord(last,rows[i+count])&&rows[i+count].xyz.every((v,j)=>v===prev[j]+(count+1)*delta[j]))count++;
  if(count>=3){b.push(250);writeUV(b,count);for(const d of delta)writeDelta(b,d);prev=[...rows[i+count-1].xyz];last=rows[i+count-1];i+=count;continue}
  b.push(251);appendDelta(row.xyz);last=row;i++;continue;
 }
 if(last&&row.combo===last.combo){b.push(252)}else b.push(row.combo);
 if(!last||row.combo!==last.combo||!sameRecord(last,row)){b.push(row.r);if(row.r===255)for(const q of row.orient){b.push(q&255,(q>>8)&255)}}
 appendDelta(row.xyz);last=row;i++;
 }
 return 'B2:'+b91enc(pack16(b));
}
function decodeBRS(str){const code=String(str).trim().replace(/\s+/g,'');if(code.startsWith('B1:'))return decodeBRSLegacy(code);if(!code.startsWith('B2:'))throw Error('Construção deve começar com B2: (ou B1: antigo)');
 const a=unpack16(b91dec(code.slice(3)),0xb2);if(a[1]!==1&&a[1]!==2)throw Error('Versão B2 desconhecida');let s={i:2};const count=readUV(a,s);if(count>4000)throw Error('Peças demais');const mask=readUV(a,s);if(mask>(a[1]===2?2047:1023))throw Error('Máscara de física inválida');const inventoryMode=a[1]===2?(mask&1024?'unlimited':'limited'):undefined;const physics={...defaultPhysics};for(let j=0;j<physKeys.length;j++)if(mask&(1<<j))physics[physKeys[j]]=readUV(a,s)/physFactor[j];
 const result=[],prev=[0,0,0];let combo=-1,rotation=[0,0,0,1],rot=0;
 function push(coords){const kind=kinds[Math.floor(combo/palette.length)],color=palette[combo%palette.length];if(!kind||!color)throw Error('Formato/cor inválidos');if(coords.some(v=>Math.abs(v)>32768))throw Error('Coordenada excedeu o limite');result.push({...kind,color,pos:coords.map(v=>v/256),rot:[...rotation]});for(let j=0;j<3;j++)prev[j]=coords[j]}
 while(result.length<count){if(s.i>=a.length)throw Error('Peças incompletas');const token=a[s.i++];if(token===250){if(combo<0)throw Error('Repetição sem primeira peça');const reps=readUV(a,s);if(reps<3||result.length+reps>count)throw Error('Repetição inválida');const ds=[readDelta(a,s),readDelta(a,s),readDelta(a,s)];for(let j=0;j<reps;j++)push(prev.map((v,k)=>v+ds[k]));continue}
 if(token===251){if(combo<0)throw Error('Peça anterior ausente')}
 else if(token===252){if(combo<0)throw Error('Peça anterior ausente');rot=255 /* lê orientação na linha abaixo */}
 else if(token<kinds.length*palette.length){combo=token;rot=255}
 else throw Error('Token compacto inválido');
 if(token!==251){if(s.i>=a.length)throw Error('Rotação ausente');const r=a[s.i++];if(r<24){rotation=[...q24[r]];rot=r}else if(r===255){if(s.i+8>a.length)throw Error('Quaternion incompleto');const dv=new DataView(a.buffer,a.byteOffset+s.i,8);rotation=[0,1,2,3].map(j=>dv.getInt16(j*2,true)/32767);s.i+=8;const norm=Math.hypot(...rotation);if(norm<.01)throw Error('Quaternion inválido');rotation=rotation.map(x=>x/norm);rot=255}else throw Error('Índice de rotação inválido')}
 const delta=[readDelta(a,s),readDelta(a,s),readDelta(a,s)];push(prev.map((v,j)=>v+delta[j]));
 }
 if(s.i!==a.length)throw Error('Sobrou lixo após última peça');return {format:'Brastoy',version:2,inventoryMode,physics,items:parseProjectItems(result)};
}
function writeB4Header(b,project,rows){
 b.push(0xb4,1);writeUV(b,rows.length);
 const physics=validatePhysics(project.physics||{});let mask=0,packed=[];
 for(let i=0;i<physKeys.length;i++){const key=physKeys[i],v=physics[key]??defaultPhysics[key],n=Math.round(v*physFactor[i]);
  if(n!==Math.round(defaultPhysics[key]*physFactor[i])){mask|=1<<i;packed.push(n)}}
 writeUV(b,mask|(project.inventoryMode==='unlimited'?1024:0));for(const value of packed)writeUV(b,value);
}
function motifKey(a,b){return a.combo+','+a.r+','+(a.orient||[]).join(',')+'|'+b.combo+','+b.r+','+(b.orient||[]).join(',')+'|'+b.xyz.map((v,j)=>v-a.xyz[j]).join(',')}
function encodeB4Raw(project,rows){
 const b=[];writeB4Header(b,project,rows);
 let prev=[0,0,0],last=null;
 const seen=new Map();const addSeen=i=>{if(i+1>=rows.length)return;const key=motifKey(rows[i],rows[i+1]);const list=seen.get(key)||[];list.push(i);if(list.length>180)list.shift();seen.set(key,list)};
 function xyzDelta(current){for(let k=0;k<3;k++)writeDelta(b,current[k]-prev[k]);prev=[...current]}
 for(let i=0;i<rows.length;){const r=rows[i];let bestStart=-1,bestLen=0;
  if(i+1<rows.length){const list=seen.get(motifKey(rows[i],rows[i+1]))||[];
   for(let j=list.length-1;j>=0;j--){const start=list[j];if(start>=i-1)continue;
    const max=Math.min(72,i-start,rows.length-i);let len=2;
    while(len<max&&sameRecord(rows[start+len],rows[i+len])&&rows[i+len].xyz.every((n,k)=>n===rows[start+len].xyz[k]+r.xyz[k]-rows[start].xyz[k]))len++;
    if(len>bestLen){bestLen=len;bestStart=start;if(len>=72)break}
   }
  }
  if(bestLen>=3){
   b.push(3);writeUV(b,bestStart);writeUV(b,bestLen);
   for(let k=0;k<3;k++)writeDelta(b,r.xyz[k]-rows[bestStart].xyz[k]);
   for(let j=0;j<bestLen;j++)addSeen(i+j);
   prev=[...rows[i+bestLen-1].xyz];last=rows[i+bestLen-1];i+=bestLen;continue;
  }
  if(last&&sameRecord(last,r)){
   const step=r.xyz.map((v,k)=>v-last.xyz[k]);let run=0;
   while(i+run<rows.length&&sameRecord(last,rows[i+run])&&rows[i+run].xyz.every((v,k)=>v===last.xyz[k]+(run+1)*step[k]))run++;
   if(run>=3){b.push(2);writeUV(b,run);for(const d of step)writeDelta(b,d);
    for(let j=0;j<run;j++)addSeen(i+j);
    prev=[...rows[i+run-1].xyz];last=rows[i+run-1];i+=run;continue;
   }
   b.push(1);xyzDelta(r.xyz);
  }else{
   b.push(0);writeUV(b,r.combo);b.push(r.r);
   if(r.r===255)for(const n of r.orient){b.push(n&255,(n>>8)&255)}
   xyzDelta(r.xyz)
  }
  last=r;addSeen(i);i++;
 }
 return Uint8Array.from(b)
}
function decodeB4Raw(a){
 if(a.length<3||a[0]!==0xb4||a[1]!==1)throw Error('Cabeçalho B4 inválido');
 const s={i:2},count=readUV(a,s);if(count>4000)throw Error('Limite de peças excedido');
 const mask=readUV(a,s);if(mask>2047)throw Error('Máscara B4 inválida');
 const physics={...defaultPhysics};for(let i=0;i<physKeys.length;i++)if(mask&(1<<i))physics[physKeys[i]]=readUV(a,s)/physFactor[i];
 const rows=[];let prev=[0,0,0];
 function readXYZ(){const xyz=prev.map(v=>v);for(let k=0;k<3;k++)xyz[k]+=readDelta(a,s);prev=[...xyz];return xyz}
 function append(row){if(row.xyz.some(v=>Math.abs(v)>32768))throw Error('Coordenada B4 fora do limite');rows.push(row);prev=[...row.xyz]}
 while(rows.length<count){if(s.i>=a.length)throw Error('Código B4 incompleto');const op=a[s.i++];
  if(op===0){const combo=readUV(a,s),r=a[s.i++];if(combo>=kinds.length*palette.length)throw Error('Tipo B4 inválido');if(r!==255&&r>=24)throw Error('Rotação B4 inválida');let orient=null;
   if(r===255){if(s.i+8>a.length)throw Error('Rotação incompleta');const dv=new DataView(a.buffer,a.byteOffset+s.i,8);orient=[0,1,2,3].map(k=>dv.getInt16(2*k,true));s.i+=8}
   const xyz=readXYZ();append({combo,r,orient,xyz});continue;
  }
  if(op===1){if(!rows.length)throw Error('Sem peça anterior');const xyz=readXYZ(),p=rows.at(-1);append({...p,xyz});continue}
  if(op===2){if(!rows.length)throw Error('Sem padrão anterior');const n=readUV(a,s),step=[readDelta(a,s),readDelta(a,s),readDelta(a,s)];if(n<3||n>count-rows.length)throw Error('Fileira inválida');
   for(let j=0;j<n;j++){const p=rows.at(-1);append({...p,xyz:p.xyz.map((v,k)=>v+step[k])})}continue;
  }
  if(op===3){const start=readUV(a,s),n=readUV(a,s),shift=[readDelta(a,s),readDelta(a,s),readDelta(a,s)];if(start>=rows.length||n<3||start+n>rows.length||n>count-rows.length)throw Error('Base repetida inválida');
   // Copy existing rows before appending (bounded and deterministic).
   const copy=rows.slice(start,start+n);for(const p of copy)append({...p,xyz:p.xyz.map((v,k)=>v+shift[k])});continue;
  }throw Error('Comando de compressão desconhecido: '+op)
 }
 if(s.i!==a.length)throw Error('Código B4 contém dados extras');
 return {format:'Brastoy',version:4,inventoryMode:mask&1024?'unlimited':'limited',physics,
  items:parseProjectItems(rows.map(row=>{const kind=kinds[Math.floor(row.combo/palette.length)],color=palette[row.combo%palette.length];
   let rot=row.r===255?row.orient.map(n=>n/32767):q24[row.r];return {...kind,color,pos:row.xyz.map(n=>n/256),rot}}))};
}
function reorderedRows(rows,which){
 const a=[...rows];if(which===1)a.sort((u,v)=>u.combo-v.combo||u.r-v.r||u.xyz[1]-v.xyz[1]||u.xyz[2]-v.xyz[2]||u.xyz[0]-v.xyz[0]);
 if(which===2)a.sort((u,v)=>u.xyz[1]-v.xyz[1]||u.xyz[2]-v.xyz[2]||u.xyz[0]-v.xyz[0]||u.combo-v.combo);
 if(which===3)a.sort((u,v)=>u.combo-v.combo||u.r-v.r||u.xyz[0]-v.xyz[0]||u.xyz[1]-v.xyz[1]||u.xyz[2]-v.xyz[2]);
 return a;
}
async function deflateBytes(bytes){
 if(typeof CompressionStream==='undefined')return null;
 try{const stream=new Blob([bytes]).stream().pipeThrough(new CompressionStream('deflate-raw'));return new Uint8Array(await new Response(stream).arrayBuffer())}catch{return null}
}
async function inflateBytes(bytes){
 if(typeof DecompressionStream==='undefined')throw Error('Seu navegador não suporta descompactação B4. Atualize Chrome/Edge.');
 // Bound the output WHILE inflating: a malicious small stream must not allocate
 // hundreds of MB before the old post-hoc 200 KB limit could reject it.
 const reader=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw')).getReader();
 const chunks=[];let total=0;
 try{
  for(;;){const {done,value}=await reader.read();if(done)break;
   total+=value.byteLength;
   if(total>200000){await reader.cancel('Limite de 200 KB');throw Error('Arquivo descompactado grande demais')}
   chunks.push(value);
  }
 }finally{reader.releaseLock()}
 const data=new Uint8Array(total);let at=0;
 for(const chunk of chunks){data.set(chunk,at);at+=chunk.length}
 return data;
}
function encodeUnicode(bytes){
 const n=bytes.length;if(n>200000)throw Error('Código longo demais');let output=String.fromCharCode(0x4e00+(n&16383),0x4e00+(n>>14));
 let accum=0,bits=0;for(const x of bytes){accum|=x<<bits;bits+=8;while(bits>=14){output+=String.fromCharCode(0x4e00+(accum&16383));accum>>>=14;bits-=14}}
 if(bits)output+=String.fromCharCode(0x4e00+accum);return output;
}
function decodeUnicode(text){
 if(text.length>2+Math.ceil(200000*8/14))throw Error('Código Unicode excede 200 KB');
 if(text.length<2)throw Error('Código Unicode truncado');
 for(const ch of text)if(ch.charCodeAt(0)<0x4e00||ch.charCodeAt(0)>0x8dff)throw Error('Símbolo Unicode inválido');
 const size=text.charCodeAt(0)-0x4e00+(text.charCodeAt(1)-0x4e00)*16384;
 if(size>200000)throw Error('Código excede limite de dados');const out=new Uint8Array(size);let accum=0,bits=0,at=0;
 for(let i=2;i<text.length;i++){accum|=(text.charCodeAt(i)-0x4e00)<<bits;bits+=14;while(bits>=8&&at<size){out[at++]=accum&255;accum>>>=8;bits-=8}}
 if(at!==size||text.length!==2+Math.ceil(size*8/14))throw Error('Dados Unicode incompletos');return out;
}
async function encodeBestBRS(project,alphabet='unicode'){
 const old=encodeBRS(project),oldBytes=b91dec(old.slice(3)),rows=makeRecords(project.items);
 const candidates=[{kind:0,bytes:oldBytes,pattern:'B2 original'}];
 for(let order=0;order<4;order++){
  const candidate=encodeB4Raw(project,reorderedRows(rows,order));
  candidates.push({kind:1,bytes:candidate,pattern:order===0?'B4 peças na ordem':'B4 ordem espacial '+order});
 }
 if(typeof CompressionStream!=='undefined'){
  const all=await Promise.all(candidates.map(async c=>{const data=await deflateBytes(c.bytes);return data?{kind:c.kind+2,bytes:data,pattern:c.pattern+' + DEFLATE'}:null}));
  candidates.push(...all.filter(Boolean));
 }
 let best={code:old,pattern:'B2 original',chars:old.length,bytes:new TextEncoder().encode(old).length};
 for(const c of candidates){const packed=pack16([0xb5,1,c.kind,...c.bytes]),serials=[{code:'B4:'+b91enc(packed),label:'ASCII'}];
  if(alphabet==='unicode')serials.push({code:'B4:'+encodeUnicode(packed),label:'Unicode'});
  for(const option of serials){const bytes=new TextEncoder().encode(option.code).length;
   if(option.code.length<best.chars||(option.code.length===best.chars&&bytes<best.bytes))best={code:option.code,pattern:c.pattern+' / '+option.label,chars:option.code.length,bytes};
  }
 }
 return {...best,baseline:old.length,objects:rows.length};
}
async function decodeB4(code){
 const payload=code.slice(3),enc=payload.charCodeAt(0)>=0x4e00&&payload.charCodeAt(0)<=0x8dff;
 const bytes=unpack16(enc?decodeUnicode(payload):b91dec(payload),0xb5);
 if(bytes[1]!==1)throw Error('Versão B4 desconhecida');const kind=bytes[2];if(kind>3)throw Error('Método B4 inválido');
 const inner=kind>=2?await inflateBytes(bytes.subarray(3)):bytes.subarray(3);
 if(kind===0||kind===2)return decodeBRS('B2:'+b91enc(inner));return decodeB4Raw(inner);
}
const B6_RATE=Array.from({length:256},(_,n)=>Math.floor(65536/Math.min(n+2,16)));
function b6Model(n,p0=2048){return {p:new Uint16Array(n).fill(p0),n:new Uint8Array(n)}}
function b6Learn(m,i,b){const p=m.p[i],n=m.n[i],q=p+((((b?0:4096)-p)*B6_RATE[n])>>16);m.p[i]=q<31?31:q>4065?4065:q;if(n<255)m.n[i]=n+1}
class B6Reader{
 constructor(bytes){this.enc=false;this.b=bytes;this.i=0;this.range=0xffffffff;this.code=0;for(let k=0;k<4;k++)this.code=this.code*256+this.next()}
 next(){const i=this.i++;if(i<this.b.length)return this.b[i];if(i>this.b.length+8)throw Error('Código B6 truncado');return 0}
 norm(){while(this.range<0x1000000){this.range=(this.range*256)>>>0;this.code=this.code*256+this.next()}}
 bit(m,i){const bound=(this.range>>>12)*m.p[i];let b=0;if(this.code<bound)this.range=bound;else{this.code-=bound;this.range-=bound;b=1}b6Learn(m,i,b);this.norm();return b}
 raw(n){let v=0;for(let k=0;k<n;k++){this.range=this.range>>>1;let b=0;if(this.code>=this.range){this.code-=this.range;b=1}v=v*2+b;this.norm()}return v}
}
function b6U(c,m,v){ // inteiro >=0 em Elias-gamma adaptativo
 if(c.enc&&!(Number.isInteger(v)&&v>=0&&v<2**30))throw Error('B6: número fora do limite');
 const x=c.enc?v+1:0,nEnc=c.enc?31-Math.clz32(x):0;let n=0;while(n<30&&c.bit(m,n,n<nEnc?1:0))n++;
 let r=1;for(let k=n-1;k>=0;k--)r=r*2+c.bit(m,32+n*32+k,(x>>>k)&1);return r-1;
}
function b6UModel(){return b6Model(1024)}
function b6SModel(){return {z:b6Model(2),u:b6UModel()}}
function b6S(c,m,v){if(!c.bit(m.z,0,v!==0?1:0))return 0;const neg=c.bit(m.z,1,v<0?1:0),a=b6U(c,m.u,Math.abs(v)-1)+1;return neg?-a:a}
function b6T(c,m,bits,v){let r=1;for(let k=bits-1;k>=0;k--)r=r*2+c.bit(m,r,(v>>>k)&1);return r-(1<<bits)}
function b6Gcd(a,b){a=Math.abs(a);b=Math.abs(b);while(b)[a,b]=[b,a%b];return a}
function b6Stream(c,plan,locked){
 const enc=c.enc;
 const count=b6U(c,b6UModel(),enc?plan.rows.length:0);if(count>4000)throw Error('B6: peças demais');
 const unlimited=c.bit(b6Model(1),0,enc?+plan.unlimited:0),flags=b6Model(10,3686),phys=b6SModel(),physics={...defaultPhysics};
 for(let i=0;i<physKeys.length;i++){const def=Math.round(defaultPhysics[physKeys[i]]*physFactor[i]);
  if(c.bit(flags,i,enc?+(plan.phys[i]!==def):0)){const q=def+b6S(c,phys,enc?plan.phys[i]-def:0);if(q<0)throw Error('B6: física inválida');physics[physKeys[i]]=q/physFactor[i]}}
 const lockFlags=locked?c.raw(16,enc?plan.lock:0):null;
 const mGrid=b6UModel(),grid=[0,1,2].map(a=>{const g=b6U(c,mGrid,enc?plan.grid[a].g-1:0)+1,off=g>1?b6U(c,mGrid,enc?plan.grid[a].off:0):0;
  if(g>65536||off>=g)throw Error('B6: grade inválida');return {g,off}});
 // Listas move-to-front: índice 0 = valor usado por último; índice = tamanho da lista = valor novo.
 const find=(list,key)=>list.findIndex(e=>e.key===key);
 const put=(list,key,val,max)=>{const i=find(list,key);if(i>=0)list.splice(i,1);list.unshift({key,val});if(list.length>max)list.pop()};
 const cached=(list,m,key,escape)=>{let r=0;if(enc){r=find(list,key);if(r<0)r=list.length}r=b6U(c,m,r);
  if(r<list.length)return list[r].val;if(r===list.length)return escape();throw Error('B6: referência inválida')};
 const mOp=b6Model(6),mComboR=b6UModel(),mComboT=b6Model(256),mRot=b6Model(2),mRotR=b6UModel(),mQR=b6UModel(),mQS=b6Model(1);
 const mDeltaR=b6UModel(),mAbsR=[0,1,2].map(b6UModel),sPos=[0,1,2].map(b6SModel),mRunN=b6UModel(),sRun=b6SModel();
 const mDistR=b6UModel(),mDist=b6UModel(),mSizeR=b6UModel(),mSize=b6UModel(),mShiftR=b6UModel();
 const deltas=[],absK=[[],[],[]],combos=[],dists=[],sizes=[],shifts=[],qmag=[];
 const rows=[],rotList=q24.map((_,i)=>i),customs=[],customKey=new Map(),lastRid=new Map();
 let prev=[0,0,0],prevOp=0,oi=0;
 function append(combo,rid,k){const xyz=k.map((v,a)=>v*grid[a].g+grid[a].off);if(xyz.some(v=>Math.abs(v)>32768))throw Error('B6: coordenada fora do limite');
  const d=k.map((v,a)=>v-prev[a]);put(deltas,d.join(','),d,16);for(let a=0;a<3;a++)put(absK[a],k[a],k[a],32);put(combos,combo,combo,32);
  prev=k;lastRid.set(combo,rid);rows.push({combo,rid,k,xyz})}
 function codePos(k){ // posição na grade: delta 3D recente, ou por eixo: valor recente ou delta novo
  const d=enc?k.map((v,a)=>v-prev[a]):null;
  const dd=cached(deltas,mDeltaR,enc?d.join(','):'',()=>[0,1,2].map(a=>cached(absK[a],mAbsR[a],enc?k[a]:0,()=>prev[a]+b6S(c,sPos[a],enc?d[a]:0))-prev[a]));
  return prev.map((v,a)=>v+dd[a]);
 }
 const ridOf=row=>row.r!==255?row.r:customKey.get(row.orient.join(','))??-1;
 while(rows.length<count){const op=enc?plan.ops[oi++]:null,last=rows.at(-1);
  let t=c.bit(mOp,prevOp,enc?+(op.t!==0):0);if(t)t=c.bit(mOp,3+prevOp,enc?+(op.t===2):0)?2:1;
  if(t===0){const row=enc?op.row:null; // peça literal
   const combo=cached(combos,mComboR,enc?row.combo:0,()=>b6T(c,mComboT,8,enc?row.combo:0));if(combo>=kinds.length*palette.length)throw Error('B6: tipo/cor inválido');
   const want=enc?ridOf(row):0,before=lastRid.get(combo);let rid;
   if(before!==undefined&&c.bit(mRot,last&&combo===last.combo?1:0,enc?+(want===before):0))rid=before;
   else{const rank=b6U(c,mRotR,enc?(want<0?rotList.length:rotList.indexOf(want)):0);
    if(rank<rotList.length)rid=rotList.splice(rank,1)[0];
    else if(rank===rotList.length){ // quaternion livre: 4 inteiros de 16 bits (q·32767), magnitudes com lista recente
     const o=[0,1,2,3].map(j=>{const w=enc?row.orient[j]:0,mag=cached(qmag,mQR,Math.abs(w),()=>c.raw(15,Math.abs(w)));put(qmag,mag,mag,16);return mag&&c.bit(mQS,0,+(w<0))?-mag:mag});
     if(o.every(v=>v===0))throw Error('B6: quaternion inválido');rid=24+customs.length;customs.push(o);customKey.set(o.join(','),rid)}
    else throw Error('B6: rotação inválida');
    rotList.unshift(rid);
   }
   append(combo,rid,codePos(enc?row.k:null));
  }else if(t===1){if(!last)throw Error('B6: fileira sem peça anterior'); // fileira: repete a última peça com passo fixo
   const n=b6U(c,mRunN,enc?op.n-2:0)+2,step=[0,1,2].map(a=>b6S(c,sRun,enc?op.step[a]:0));if(n>count-rows.length)throw Error('B6: fileira inválida');
   for(let j=0;j<n;j++){const p=rows.at(-1);append(p.combo,p.rid,p.k.map((v,a)=>v+step[a]))}
  }else{ // cópia transladada de um bloco anterior
   const dist=cached(dists,mDistR,enc?op.dist:0,()=>b6U(c,mDist,enc?op.dist-1:0)+1);put(dists,dist,dist,16);
   const n=cached(sizes,mSizeR,enc?op.n:0,()=>b6U(c,mSize,enc?op.n-2:0)+2);put(sizes,n,n,16);
   const start=rows.length-dist;if(start<0||n<2||n>dist||n>count-rows.length)throw Error('B6: bloco repetido inválido');
   const want=enc?op.first.map((v,a)=>v-rows[start].k[a]):null;
   const shift=cached(shifts,mShiftR,enc?want.join(','):'',()=>codePos(enc?op.first:null).map((v,a)=>v-rows[start].k[a]));put(shifts,shift.join(','),shift,16);
   for(let j=0;j<n;j++){const p=rows[start+j];append(p.combo,p.rid,p.k.map((v,a)=>v+shift[a]))}
  }
  prevOp=t;
 }
 const check=[];for(const r of rows){check.push(r.combo&255,r.combo>>8,r.rid&255,r.rid>>8);for(const v of r.xyz)check.push(v&255,(v>>8)&255,(v>>16)&255);if(r.rid>=24)for(const v of customs[r.rid-24])check.push(v&255,(v>>8)&255)}
 const crc=crc16(check);if(c.raw(16,enc?crc:0)!==crc)throw Error('Código B6 corrompido (verificação falhou)');
 return {rows,customs,physics,unlimited,lockFlags};
}
const B6_ALPHABETS=(()=>{const u=[];for(const [s,e] of [[0x4e00,0x9fa5],[0x3400,0x4db5],[0xac00,0xd7a3]])for(let x=s;x<=e;x++)u.push(String.fromCharCode(x));
 const a=[...'0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()+,-./;<=>?@[]^{}'];
 return [{alpha:u,bytes:40,digits:21},{alpha:a,bytes:4,digits:5}].map(R=>{const N=BigInt(R.alpha.length);
  if(N**BigInt(R.digits)<1n<<BigInt(8*R.bytes))throw Error('Bloco B6 inconsistente');return {...R,N,index:new Map(R.alpha.map((ch,i)=>[ch,i]))}})})();
function b6FromText(text){if(!text.length)throw Error('Código B6 vazio');if(text.length>300000)throw Error('Código B6 longo demais');
 const R=B6_ALPHABETS[text.charCodeAt(0)<128?1:0],out=[];
 const vals=Array.from(text,ch=>{const v=R.index.get(ch);if(v===undefined)throw Error('Caractere inválido no código B6: '+ch);return BigInt(v)});
 const big=(a,b)=>{let v=0n;for(let j=a;j<b;j++)v=v*R.N+vals[j];return v},nfull=Math.floor((vals.length-1)/R.digits);
 for(let b=0;b<nfull;b++){const v=big(b*R.digits,(b+1)*R.digits);if(v>>BigInt(8*R.bytes))throw Error('Bloco B6 inválido');const h=v.toString(16).padStart(2*R.bytes,'0');for(let j=0;j<h.length;j+=2)out.push(parseInt(h.slice(j,j+2),16))}
 const h=big(nfull*R.digits,vals.length).toString(16);if(h[0]!=='1'||h.length%2!==1)throw Error('Final do código B6 inválido');
 for(let j=1;j<h.length;j+=2)out.push(parseInt(h.slice(j,j+2),16));
 return Uint8Array.from(out);
}
function decodeB6(code){
 const t=String(code).normalize('NFC').replace(/\s+/g,'');if(!/^B6[:!]/.test(t))throw Error('Código B6 deve começar com B6: ou B6!');
 const locked=t[2]==='!',s=b6Stream(new B6Reader(b6FromText(t.slice(3))),null,locked);
 const items=s.rows.map(r=>({...kinds[Math.floor(r.combo/palette.length)],color:palette[r.combo%palette.length],
  pos:r.xyz.map(n=>n/256),rot:r.rid<24?q24[r.rid]:s.customs[r.rid-24].map(n=>n/32767)}));
 const project={format:'Brastoy',version:6,inventoryMode:s.unlimited?'unlimited':'limited',physics:s.physics,items:parseProjectItems(items)};
 if(locked){project.b5=true;project.b5extra=String.fromCharCode(0x4e00+(s.lockFlags&16383),0x4e00+(s.lockFlags>>>14))}
 return project;
}
const B7_RF=Array.from({length:256},(_,n)=>Math.floor(65536/Math.min(n+2,4))),B7_RS=Array.from({length:256},(_,n)=>Math.floor(65536/Math.min(n+2,32)));
function b7M(n){return {a:new Uint16Array(n).fill(32768),s:new Uint16Array(n).fill(32768),n:new Uint8Array(n)}}
function b7P(m,i){const p=(m.a[i]+m.s[i])>>>1;return p<64?64:p>65472?65472:p}
function b7Learn(m,i,b){const n=m.n[i],t=b?0:65535;let a=m.a[i],s=m.s[i];a+=Math.trunc((t-a)*B7_RF[n]/65536);s+=Math.trunc((t-s)*B7_RS[n]/65536);m.a[i]=a;m.s[i]=s;if(n<255)m.n[i]=n+1}
class B7Reader{
 constructor(bytes){this.enc=false;this.b=bytes;this.i=0;this.range=0xffffffff;this.code=0;for(let k=0;k<4;k++)this.code=this.code*256+this.next()}
 next(){const i=this.i++;if(i<this.b.length)return this.b[i];if(i>this.b.length+8)throw Error('Código B7 truncado');return 0}
 norm(){while(this.range<0x1000000){this.range=(this.range*256)>>>0;this.code=this.code*256+this.next()}}
 bit(m,i){const bound=(this.range>>>16)*b7P(m,i);let b=0;if(this.code<bound)this.range=bound;else{this.code-=bound;this.range-=bound;b=1}b7Learn(m,i,b);this.norm();return b}
 raw(n){let v=0;for(let k=0;k<n;k++){this.range=this.range>>>1;let b=0;if(this.code>=this.range){this.code-=this.range;b=1}v=v*2+b;this.norm()}return v}
}
function b7U(c,m,v){
 if(c.enc&&!(Number.isInteger(v)&&v>=0&&v<2**30))throw Error('B7: número fora do limite');
 const x=c.enc?v+1:0,nEnc=c.enc?31-Math.clz32(x):0;let n=0;while(n<30&&c.bit(m,n,n<nEnc?1:0))n++;
 let r=1;for(let k=n-1;k>=0;k--)r=r*2+c.bit(m,32+n*32+k,(x>>>k)&1);return r-1;
}
const b7UM=()=>b7M(1024),b7SM=()=>({z:b7M(2),u:b7UM()});
function b7S(c,m,v){if(!c.bit(m.z,0,v!==0?1:0))return 0;const neg=c.bit(m.z,1,v<0?1:0),a=b7U(c,m.u,Math.abs(v)-1)+1;return neg?-a:a}
function b7T(c,m,bits,v){let r=1;for(let k=bits-1;k>=0;k--)r=r*2+c.bit(m,r,(v>>>k)&1);return r-(1<<bits)}
const B7_UI_DEFAULT=[53,5,7.5,5,115,.85,0,.3,.056,.75],B7_TIME=[1,.5,.25,.1,.05,.0167],B7_CONTACT=['alta','padrao','maxima'];
function b7Dec(v){for(let e=0;e<6;e++){const m=v*10**e;if(Math.abs(m-Math.round(m))<1e-7*Math.max(1,Math.abs(m)))return {e,m:Math.round(m)}}return {e:6,m:Math.round(v*1e6)}}
function b7DecCode(c,mE,sM,def,v){const d=c.enc?b7Dec(v):null,e=b7U(c,mE,c.enc?d.e:0);if(e>6)throw Error('B7: decimal inválido');const base=Math.round(def*10**e);
 return (base+b7S(c,sM,c.enc?d.m-base:0))/10**e}
const B7_LOCK_DEFAULT=2|1<<2|4<<4|72<<9; // peças e campo · 400 dipolos · 24 linhas · 72%
function b7Header(c,P,v2=false){
 const enc=c.enc,H={};
 H.count=b7U(c,b7UM(),enc?P.count:0);if(H.count>4000)throw Error('B7: peças demais');
 const mh=b7M(16);H.unlimited=c.bit(mh,0,enc?+P.unlimited:0);
 // física: valores da tela (ballRadius/ballHeight em metros)
 const fl=b7M(physKeys.length),mE=b7UM(),sM=b7SM();H.ui=B7_UI_DEFAULT.slice();
 for(let i=0;i<physKeys.length;i++)if(c.bit(fl,i,enc?+(P.ui[i]!==B7_UI_DEFAULT[i]):0))H.ui[i]=b7DecCode(c,mE,sM,B7_UI_DEFAULT[i],enc?P.ui[i]:0);
 const xDef=x=>x.rateMode==='auto'&&x.physicsRate===120&&x.timeScale===1&&x.contactPrecision==='alta';
 if(c.bit(mh,1,enc?+!!P.extras:0)){if(v2&&c.bit(mh,9,enc?+xDef(P.extras):0))H.extras={rateMode:'auto',physicsRate:120,timeScale:1,contactPrecision:'alta'};else{const x=enc?P.extras:null,ms=b7SM(),mi=b7UM();
  const rateMode=c.bit(mh,2,enc?+(x.rateMode==='fixed'):0)?'fixed':'auto';
  const physicsRate=120+b7S(c,ms,enc?x.physicsRate-120:0);
  let ti=b7U(c,mi,enc?(B7_TIME.indexOf(x.timeScale)+1||B7_TIME.length+1)-1:0),timeScale;
  if(ti<B7_TIME.length)timeScale=B7_TIME[ti];else if(ti===B7_TIME.length)timeScale=b7DecCode(c,mE,sM,1,enc?x.timeScale:0);else throw Error('B7: velocidade inválida');
  const ci=b7U(c,mi,enc?Math.max(0,B7_CONTACT.indexOf(x.contactPrecision)):0);if(ci>=B7_CONTACT.length)throw Error('B7: precisão inválida');
  H.extras={rateMode,physicsRate,timeScale,contactPrecision:B7_CONTACT[ci]}}}
 // benchmark da construção: {best:{threads,magPool,magGpu,magLag},joltMs,magMs,stepMs} + máquina (hc, isolamento)
 if(c.bit(mh,3,enc?+!!P.bench:0)){const b=enc?P.bench:null,mb=b7UM(),cs=x=>Math.round(x*100);
  const hc=b7U(c,mb,enc?b.hc:0),iso=c.bit(mh,4,enc?+b.iso:0),threads=b7U(c,mb,enc?b.best.threads:0),magPool=c.bit(mh,5,enc?+!!b.best.magPool:0),magGpu=c.bit(mh,6,enc?+!!b.best.magGpu:0);
  const magLag=b7U(c,mb,enc?(b.best.magLag|0):0),jolt=b7U(c,mb,enc?cs(b.joltMs):0),mag=b7U(c,mb,enc?cs(b.magMs||0):0),step=jolt+mag+b7S(c,b7SM(),enc?cs(b.stepMs)-cs(b.joltMs)-cs(b.magMs||0):0);
  H.bench={hc,iso:!!iso,best:{threads,magPool:!!magPool,magGpu:!!magGpu,magLag},joltMs:jolt/100,magMs:mag/100,stepMs:step/100}}
 H.lock=c.bit(mh,7,enc?+(P.lock!==null):0)?(v2&&c.bit(mh,8,enc?+(P.lock===B7_LOCK_DEFAULT):0)?B7_LOCK_DEFAULT:c.raw(16,enc?P.lock:0)):null;
 const mGrid=b7UM();H.grid=[0,1,2].map(a=>{const g=b7U(c,mGrid,enc?P.grid[a].g-1:0)+1,off=g>1?b7U(c,mGrid,enc?P.grid[a].off:0):0;
  if(g>65536||off>=g)throw Error('B7: grade inválida');return {g,off}});
 return H;
}
function b7Pieces(c,H,ops){
 const enc=c.enc,count=H.count,grid=H.grid;
 const find=(list,key)=>list.findIndex(e=>e.key===key);
 const put=(list,key,val,max)=>{const i=find(list,key);if(i>=0)list.splice(i,1);list.unshift({key,val});if(list.length>max)list.pop()};
 // lista recente com bit "está na lista" (contexto fm[fi]) + posição (U); fora dela, escape()
 const cached=(list,fm,fi,mr,key,escape,skip=0)=>{let r=-1;if(enc){r=find(list,key);if(r>=0&&r<skip)throw Error('B7: lista')}
  if(list.length>skip&&c.bit(fm,fi,enc?+(r>=0):0)){const k=b7U(c,mr,enc?r-skip:0)+skip;if(k>=list.length)throw Error('B7: referência inválida');return list[k].val}return escape()};
 const sv=(m,a,v)=>{ // inteiro com sinal, zero com contexto
  if(!c.bit(m.z,a,enc?+(v!==0):0))return 0;const neg=c.bit(m.z,3+a,enc?+(v<0):0),x=b7U(c,m.u[a],enc?Math.abs(v)-1:0)+1;return neg?-x:x};
 const vecM=()=>({z:b7M(8),u:[b7UM(),b7UM(),b7UM()]});
 // inteiro != 0: sinal + magnitude (lista recente de magnitudes, compartilhada entre eixos e usos)
 const mags=[],byDist=new Map(),magM=()=>({s:b7M(3),h:b7M(3),r:b7UM(),u:[b7UM(),b7UM(),b7UM()]});
 const sm=(m,a,v)=>{const neg=c.bit(m.s,a,enc?+(v<0):0),x=cached(mags,m.h,a,m.r,enc?Math.abs(v):0,()=>b7U(c,m.u[a],enc?Math.abs(v)-1:0)+1);put(mags,x,x,32);return neg?-x:x};
 const mRm=b7M(4),mRotR2=b7UM(),mMir=b7M(3),mNewM=magM(),mShI=b7M(2),mShZ=b7M(16),mShM=magM();
 const mOp=b7M(64),mCS=b7M(8),mCH=b7M(4),mComboR=b7UM(),mKind=b7M(32),mCol=b7M(8),mRot=b7M(8),mRotR=b7UM(),mQR=b7UM(),mQH=b7M(2),mQS=b7M(1),mQW=b7SM();
 const mDH=b7M(8),mDeltaR=b7UM(),mAZ=b7M(16),mAH=b7M(4),mAbsR=[0,1,2].map(b7UM);
 const mRunN=b7UM(),mStep=vecM(),mDistH=b7M(2),mDistR=b7UM(),mDist=b7UM(),mNEq=b7M(4),mSizeH=b7M(2),mSizeR=b7UM(),mSize=b7UM(),mShH=b7M(4),mShR=b7UM(),mSh=vecM();
 const deltas=[],absK=[[],[],[]],combos=[],dists=[],sizes=[],shifts=[],qmag=[];
 const rows=[],rotList=q24.map((_,i)=>({key:i,val:i})),customs=[],customKey=new Map(),lastRid=new Map();
 let prev=[0,0,0],prevOp=0,oi=0,lits=0;
 function append(combo,rid,k){const xyz=k.map((v,a)=>v*grid[a].g+grid[a].off);if(xyz.some(v=>Math.abs(v)>32768))throw Error('B7: coordenada fora do limite');
  const d=k.map((v,a)=>v-prev[a]);put(deltas,d.join(','),d,16);for(let a=0;a<3;a++)put(absK[a],k[a],k[a],32);put(combos,combo,combo,32);
  prev=k;lastRid.set(combo,rid);rows.push({combo,rid,k,xyz})}
 function codePos(k,ctx){ // posição nova: delta 3D recente; senão, por eixo: igual, valor recente ou delta novo
  const d=enc?k.map((v,a)=>v-prev[a]):null;
  const dd=cached(deltas,mDH,ctx,mDeltaR,enc?d.join(','):'',()=>{let zc=0;return [0,1,2].map(a=>{
   if(!c.bit(mAZ,a*4+zc,enc?+(d[a]!==0):0)){zc++;return 0}
   const others=absK[a].filter(e=>e.key!==prev[a]);
   const v=cached(others,mAH,a,mAbsR[a],enc?k[a]:0,()=>{const m=-prev[a];if(m!==0&&!others.some(e=>e.key===m)&&c.bit(mMir,a,enc?+(k[a]===m):0))return m;return prev[a]+sm(mNewM,a,enc?d[a]:0)});
   return v-prev[a]})});
  return prev.map((v,a)=>v+dd[a]);
 }
 const codeRot=(want,not)=>{const L=rotList.filter(e=>e.val!==not),r=b7U(c,mRotR2,enc?L.findIndex(e=>e.val===want):0);if(r>=L.length)throw Error('B7: rotação inválida');const rid=L[r].val;rotList.splice(find(rotList,rid),1);rotList.unshift({key:rid,val:rid});return rid};
 const ridOf=row=>row.r!==255?row.r:customKey.get(row.orient.join(','))??-1;
 while(rows.length<count){const op=enc?ops[oi++]:null,last=rows.at(-1);
  const oc=prevOp*4+Math.min(lits,3);let t=c.bit(mOp,oc,enc?+(op.t!==0):0);if(t)t=c.bit(mOp,16+oc,enc?+(op.t===2):0)?2:1;
  if(t===0){const row=enc?op.row:null;
   let combo;
   if(last&&c.bit(mCS,prevOp,enc?+(row.combo===last.combo):0))combo=last.combo;
   else combo=cached(combos,mCH,0,mComboR,enc?row.combo:0,()=>{const kd=b7T(c,mKind,5,enc?Math.floor(row.combo/palette.length):0),cl=b7T(c,mCol,3,enc?row.combo%palette.length:0);
    if(kd>=kinds.length||cl>=palette.length)throw Error('B7: tipo/cor inválido');return kd*palette.length+cl},last?1:0);
   const same=last&&combo===last.combo,want=enc?ridOf(row):0,before=lastRid.get(combo);let rid;
   if(before!==undefined&&c.bit(mRot,same?1:0,enc?+(want===before):0))rid=before;
   else{const rank=b7U(c,mRotR,enc?(want<0?rotList.length:find(rotList,want)):0);
    if(rank<rotList.length)rid=rotList.splice(rank,1)[0].val;
    else if(rank===rotList.length){
     // x, y, z: magnitude (lista recente ou 15 bits) e sinal; w: previsto pela norma 32767, grava só a diferença e o sinal
     const o=[0,1,2].map(j=>{const w=enc?row.orient[j]:0,mag=cached(qmag,mQH,0,mQR,Math.abs(w),()=>c.raw(15,Math.abs(w)));put(qmag,mag,mag,16);return mag&&c.bit(mQS,0,+(w<0))?-mag:mag});
     const rest=Math.max(0,32767*32767-o[0]*o[0]-o[1]*o[1]-o[2]*o[2]);let r=Math.floor(Math.sqrt(rest));while(r*r>rest)r--;while((r+1)*(r+1)<=rest)r++;
     const wp=rest-r*r>r?r+1:r,w3=enc?row.orient[3]:0,wm=wp+b7S(c,mQW,enc?Math.abs(w3)-wp:0);if(wm<0||wm>32767)throw Error('B7: quaternion inválido');
     o.push(wm&&c.bit(mQS,0,+(w3<0))?-wm:wm);
     if(o.every(v=>v===0))throw Error('B7: quaternion inválido');rid=24+customs.length;customs.push(o);customKey.set(o.join(','),rid)}
    else throw Error('B7: rotação inválida');
    rotList.unshift({key:rid,val:rid});
   }
   append(combo,rid,codePos(enc?row.k:null,same?1:0));
  }else if(t===1){if(!last)throw Error('B7: fileira sem peça anterior');
   const n=b7U(c,mRunN,enc?op.n-2:0)+2,step=[0,1,2].map(a=>sv(mStep,a,enc?op.step[a]:0));if(n>count-rows.length)throw Error('B7: fileira inválida');
   for(let j=0;j<n;j++){const p=rows.at(-1);append(p.combo,p.rid,p.k.map((v,a)=>v+step[a]))}
  }else{
   const dist=cached(dists,mDistH,0,mDistR,enc?op.dist:0,()=>b7U(c,mDist,enc?op.dist-1:0)+1);put(dists,dist,dist,16);
   const left=count-rows.length,info=byDist.get(dist);
   // tamanho: o da última cópia com esta distância, o bloco inteiro (n = dist), lista recente ou novo
   const cand=[];if(info&&info.n<=Math.min(dist,left))cand.push(info.n);if(dist<=left&&dist>=2&&!cand.includes(dist))cand.push(dist);
   let n=-1;for(let j=0;j<cand.length&&n<0;j++)if(c.bit(mNEq,j*2+(info?1:0),enc?+(op.n===cand[j]):0))n=cand[j];
   if(n<0)n=cached(sizes,mSizeH,0,mSizeR,enc?op.n:0,()=>b7U(c,mSize,enc?op.n-2:0)+2);put(sizes,n,n,16);
   const start=rows.length-dist;if(start<0||n<2||n>dist||n>left)throw Error('B7: bloco repetido inválido');
   const want=enc?op.first.map((v,a)=>v-rows[start].k[a]):null;
   const shift=info&&c.bit(mShI,0,enc?+(want.join(',')===info.shift.join(',')):0)?info.shift:
    cached(shifts,mShH,info?1:0,mShR,enc?want.join(','):'',()=>{let zc=0;return [0,1,2].map(a=>{if(!c.bit(mShZ,a*4+zc,enc?+(want[a]!==0):0)){zc++;return 0}return sm(mShM,a,enc?want[a]:0)})});
   put(shifts,shift.join(','),shift,16);byDist.set(dist,{n,shift});
   // troca: para cada peça distinta (tipo/cor + rotação) do bloco de origem, na ordem de aparição: mantém ou troca
   const map=new Map();
   if(c.bit(mRm,0,enc?+!!op.map:0)){for(let j=0;j<n;j++){const sp=rows[start+j],sk=sp.combo+'/'+sp.rid;if(map.has(sk))continue;const tg=enc?op.map.get(sk):null;
     if(c.bit(mRm,1,enc?+(tg.combo===sp.combo&&tg.rid===sp.rid):0)){map.set(sk,sp);continue}
     const cb=c.bit(mRm,2,enc?+(tg.combo===sp.combo):0)?sp.combo:cached(combos,mCH,1,mComboR,enc?tg.combo:0,()=>{const kd=b7T(c,mKind,5,enc?Math.floor(tg.combo/palette.length):0),cl=b7T(c,mCol,3,enc?tg.combo%palette.length:0);
      if(kd>=kinds.length||cl>=palette.length)throw Error('B7: tipo/cor inválido');return kd*palette.length+cl});
     const rid=cb!==sp.combo&&c.bit(mRm,3,enc?+(tg.rid===sp.rid):0)?sp.rid:codeRot(enc?tg.rid:0,sp.rid);
     if(cb===sp.combo&&rid===sp.rid)throw Error('B7: troca vazia');map.set(sk,{combo:cb,rid})}}
   for(let j=0;j<n;j++){const p=rows[start+j],m=map.get(p.combo+'/'+p.rid)||p;append(m.combo,m.rid,p.k.map((v,a)=>v+shift[a]))}
  }
  prevOp=t;lits=t?0:lits+1;
 }
 const check=[];for(const r of rows){check.push(r.combo&255,r.combo>>8,r.rid&255,r.rid>>8);for(const v of r.xyz)check.push(v&255,(v>>8)&255,(v>>16)&255);if(r.rid>=24)for(const v of customs[r.rid-24])check.push(v&255,(v>>8)&255)}
 const crc=crc16(check);if(c.raw(16,enc?crc:0)!==crc)throw Error('Código B7 corrompido (verificação falhou)');
 return {rows,customs};
}
const B7_RANGES=[[0x4e00,0x9fea],[0x3400,0x4db5],[0xac00,0xd7a3],[0xa000,0xa014],[0xa016,0xa48c],[0x1401,0x166c],[0x166f,0x167f],
 [0x20000,0x2a6d6],[0x2a700,0x2b734],[0x2b740,0x2b81d],[0x2b820,0x2cea1],[0x2ceb0,0x2ebe0]];
const B7_TEXT=(()=>{const ranges=[];let N=0,bmp=0;for(const [s,e] of B7_RANGES){ranges.push([s,e,N]);N+=e-s+1;if(e<0x10000)bmp=N}
 if(N!==100843||bmp!==40526)throw Error('Alfabeto B7 inconsistente');
 const a=[...'0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()+,-./;<=>?@[]^{}'];
 return {ranges,N:{unicode:N,bmp,ascii:a.length},ascii:a,aIndex:new Map(a.map((ch,i)=>[ch.codePointAt(0),i]))}})();
function b7Index(cp,mode){if(mode==='ascii')return B7_TEXT.aIndex.get(cp)??-1;for(const [s,e,b] of B7_TEXT.ranges)if(cp>=s&&cp<=e){const i=b+cp-s;return i<B7_TEXT.N[mode]?i:-1}return -1}
function b7FromText(text,le=false){
 let cps=Array.from(text,ch=>ch.codePointAt(0));if(!cps.length)throw Error('Código B7 vazio');if(cps.length>200000)throw Error('Código B7 longo demais');
 // 1º caractere. B7: → ' equilibrado, " menos bytes, 一 só BMP, outro ASCII = só ASCII, senão menos caracteres.
 // 7 → " menos bytes, ' só ASCII, ¡ menos caracteres, ¿ só BMP; qualquer outro = equilibrado (sem marcador).
 let mode;
 if(le){mode=cps[0]===0x22?'bytes':cps[0]===0x27?'ascii':cps[0]===0xa1?'unicode':cps[0]===0xbf?'bmp':'balanced2';if(mode!=='balanced2')cps=cps.slice(1)}
 else{mode=cps[0]===0x27?'balanced':cps[0]===0x22?'bytes':cps[0]<128?'ascii':cps[0]===0x4e00?'bmp':'unicode';if(mode!=='ascii'&&mode!=='unicode')cps=cps.slice(1)}
 if(!cps.length)throw Error('Código B7 vazio');
 let value;
 if(B7_MIX.modes[mode])value=b7MixValue(cps,mode);
 else{const N=BigInt(B7_TEXT.N[mode]);
  const ds=cps.map(cp=>{const i=b7Index(cp,mode);if(i<0)throw Error('Caractere inválido no código B7: '+String.fromCodePoint(cp));return BigInt(i)});
  const pows=new Map(),pow=k=>{if(!pows.has(k))pows.set(k,N**BigInt(k));return pows.get(k)};
  const rec=(a,b)=>{if(b-a<=8){let v=0n;for(let j=a;j<b;j++)v=v*N+ds[j];return v}const m=(a+b)>>1;return rec(a,m)*pow(b-m)+rec(m,b)};
  value=rec(0,ds.length)}
 if(le){if(value===0n)throw Error('Código B7 inválido');let h=value.toString(16);if(h.length%2)h='0'+h;const out=new Uint8Array(h.length/2);for(let j=0;j<out.length;j++)out[j]=parseInt(h.substr(h.length-2-2*j,2),16);return {bytes:out,mode}}
 const h=value.toString(16);if(h[0]!=='1'||h.length%2!==1)throw Error('Código B7 inválido (início)');
 const out=new Uint8Array((h.length-1)/2);for(let j=0;j<out.length;j++)out[j]=parseInt(h.substr(1+2*j,2),16);return {bytes:out,mode};
}
const B7_TWO=[[0xc0,0xd6],[0xd8,0xf6],[0xf8,0x131],[0x134,0x13e],[0x141,0x148],[0x14a,0x17e],[0x180,0x1c3],[0x1cd,0x1f0],[0x1f4,0x2af],
 [0x370,0x373],[0x376,0x377],[0x37b,0x37d],[0x37f,0x37f],[0x386,0x386],[0x388,0x38a],[0x38c,0x38c],[0x38e,0x3a1],[0x3a3,0x3cf],[0x3d7,0x3ef],
 [0x3f3,0x3f3],[0x3f7,0x3f8],[0x3fa,0x481],[0x48a,0x52f],[0x531,0x556],[0x561,0x586]];
const B7_MIX=(()=>{
 const cls=ranges=>{const r=[];let n=0;for(const [s,e] of ranges){r.push([s,e,n]);n+=e-s+1}
  return {n,sym:i=>{for(const [s,e,b] of r)if(i<b+e-s+1)return String.fromCodePoint(s+i-b)},idx:cp=>{for(const [s,e,b] of r)if(cp>=s&&cp<=e)return b+cp-s;return -1}}};
 const mk=a=>{const idx=new Map(a.map((ch,i)=>[ch.codePointAt(0),i]));return {n:a.length,sym:i=>a[i],idx:cp=>idx.get(cp)??-1}};
 const rest=[cls(B7_TWO),cls(B7_RANGES.filter(([,e])=>e<0x10000)),cls(B7_RANGES.filter(([s])=>s>=0x10000))],c87=[mk([...B7_TEXT.ascii,"'",'"']),...rest],c85=[mk(B7_TEXT.ascii),...rest];
 if(c87.map(c=>c.n).join()!=='87,962,40526,60317')throw Error('Alfabeto misto B7 inconsistente');
 // custo por símbolo de 1/2/3/4 bytes. bytes: só bytes. balanced: 3·caracteres + 2·bytes (1 caractere vale 1,5 byte).
 // balanced2 (prefixo 7): sem marcador e sem aspas no ASCII, porque as aspas marcam os outros objetivos.
 return {maxBytes:2048,modes:{bytes:{marker:'"',classes:c87,cost:[1,2,3,4],maxCost:3000,fallback:'ascii'},balanced:{marker:"'",classes:c87,cost:[5,7,9,11],maxCost:11000,fallback:'unicode'},balanced2:{marker:'',classes:c85,cost:[5,7,9,11],maxCost:11000,fallback:'unicode'}}};
})();
function b7MixE(M,E,c){const cost=M.cost;while(E.length<=c){const k=E.length;let e=0n;for(let q=0;q<4;q++)if(k>=cost[q])e+=BigInt(M.classes[q].n)*E[k-cost[q]];E.push(e)}return E}
function b7MixValue(cps,mode){
 const M=B7_MIX.modes[mode],K=M.classes,cost=M.cost;
 const syms=cps.map(cp=>{for(let q=0;q<4;q++){const j=K[q].idx(cp);if(j>=0)return [q,j]}throw Error('Caractere inválido no código B7: '+String.fromCodePoint(cp))});
 let C=0;for(const [q] of syms)C+=cost[q];if(C>M.maxCost)throw Error('Código B7 longo demais');
 const E=b7MixE(M,[1n],C);let v=0n;for(let k=0;k<C;k++)v+=E[k];
 let cr=C;for(const [q,j] of syms){for(let p=0;p<q;p++)if(cr>=cost[p])v+=BigInt(K[p].n)*E[cr-cost[p]];v+=BigInt(j)*E[cr-cost[q]];cr-=cost[q]}
 return v;
}
const B7_IGNORE=/[\s\u00ad\u034f\u061c\u115f\u1160\u180b-\u180f\u200b-\u200f\u202a-\u202e\u2060-\u206f\u3164\ufe00-\ufe0f\ufeff\uffa0]|\udb40[\udd00-\uddef]/g;
function decodeB7(code){
 const t=String(code).normalize('NFC').replace(B7_IGNORE,''),v2=t[0]==='7';if(!v2&&!t.startsWith('B7:'))throw Error('Código B7 deve começar com 7 (ou B7:)');
 const r=new B7Reader(b7FromText(t.slice(v2?1:3),v2).bytes),H=b7Header(r,null,v2),s=b7Pieces(r,H,null);
 const side=H.ui[2]/100,physics=Object.fromEntries(physKeys.map((k,i)=>[k,H.ui[i]/(k==='ballRadius'||k==='ballHeight'?side:1)]));
 const items=s.rows.map(x=>({...kinds[Math.floor(x.combo/palette.length)],color:palette[x.combo%palette.length],
  pos:x.xyz.map(n=>n/256),rot:x.rid<24?q24[x.rid]:s.customs[x.rid-24].map(n=>n/32767)}));
 const project={format:'Brastoy',version:7,inventoryMode:H.unlimited?'unlimited':'limited',physics,items:parseProjectItems(items)};
 if(H.extras)project.physicsExtras=H.extras;
 if(H.bench)project.bench=H.bench;
 if(H.lock!==null){project.b5=true;project.b5extra=String.fromCharCode(0x4e00+(H.lock&16383),0x4e00+(H.lock>>>14))}
 return project;
}
const B8_RF=Array.from({length:256},(_,n)=>Math.floor(65536/Math.min(n+2,4))),B8_RS=Array.from({length:256},(_,n)=>Math.floor(65536/Math.min(n+2,32)));
function b8M(n){return {a:new Uint16Array(n).fill(32768),s:new Uint16Array(n).fill(32768),n:new Uint8Array(n)}}
function b8P(m,i){const p=(m.a[i]+m.s[i])>>>1;return p<64?64:p>65472?65472:p}
function b8Learn(m,i,b){const n=m.n[i],t=b?0:65535;let a=m.a[i],s=m.s[i];a+=Math.trunc((t-a)*B8_RF[n]/65536);s+=Math.trunc((t-s)*B8_RS[n]/65536);m.a[i]=a;m.s[i]=s;if(n<255)m.n[i]=n+1}
class B8Writer{
 constructor(){this.enc=true;this.low=0;this.range=0xffffffff;this.cache=0;this.pending=1;this.out=[]}
 shift(){if(this.low<0xff000000||this.low>=0x100000000){const carry=this.low>=0x100000000?1:0;let t=this.cache;do{this.out.push((t+carry)&255);t=255}while(--this.pending);this.cache=Math.floor(this.low/0x1000000)&255}this.pending++;this.low=(this.low%0x1000000)*256}
 norm(){while(this.range<0x1000000){this.range=(this.range*256)>>>0;this.shift()}}
 bit(m,i,b){const bound=(this.range>>>16)*b8P(m,i);if(b){this.low+=bound;this.range-=bound}else this.range=bound;b8Learn(m,i,b);this.norm();return b}
 raw(n,v){for(let k=n-1;k>=0;k--){this.range=this.range>>>1;if(Math.floor(v/2**k)&1)this.low+=this.range;this.norm()}return v}
 finish(){for(let k=32;k>0;k--){const m=2**k,x=Math.ceil(this.low/m)*m;if(x<this.low+this.range){this.low=x;break}} // fim mínimo (MK-10.38)
  for(let k=0;k<5;k++)this.shift();const o=this.out;if(o[0]!==0)throw Error('B8: estado inicial inválido');let end=o.length;while(end>1&&o[end-1]===0)end--;return Uint8Array.from(o.slice(1,end))}
}
class B8Reader{
 constructor(bytes){this.enc=false;this.b=bytes;this.i=0;this.range=0xffffffff;this.code=0;for(let k=0;k<4;k++)this.code=this.code*256+this.next()}
 next(){const i=this.i++;if(i<this.b.length)return this.b[i];if(i>this.b.length+8)throw Error('Código B8 truncado');return 0}
 norm(){while(this.range<0x1000000){this.range=(this.range*256)>>>0;this.code=this.code*256+this.next()}}
 bit(m,i){const bound=(this.range>>>16)*b8P(m,i);let b=0;if(this.code<bound)this.range=bound;else{this.code-=bound;this.range-=bound;b=1}b8Learn(m,i,b);this.norm();return b}
 raw(n){let v=0;for(let k=0;k<n;k++){this.range=this.range>>>1;let b=0;if(this.code>=this.range){this.code-=this.range;b=1}v=v*2+b;this.norm()}return v}
}
function b8U(c,m,v){
 if(c.enc&&!(Number.isInteger(v)&&v>=0&&v<2**30))throw Error('B8: número fora do limite');
 const x=c.enc?v+1:0,nEnc=c.enc?31-Math.clz32(x):0;let n=0;while(n<30&&c.bit(m,n,n<nEnc?1:0))n++;
 let r=1;for(let k=n-1;k>=0;k--)r=r*2+c.bit(m,32+n*32+k,(x>>>k)&1);return r-1;
}
const b8UM=()=>b8M(1024),b8SM=()=>({z:b8M(2),u:b8UM()});
function b8S(c,m,v){if(!c.bit(m.z,0,v!==0?1:0))return 0;const neg=c.bit(m.z,1,v<0?1:0),a=b8U(c,m.u,Math.abs(v)-1)+1;return neg?-a:a}
function b8T(c,m,bits,v){let r=1;for(let k=bits-1;k>=0;k--)r=r*2+c.bit(m,r,(v>>>k)&1);return r-(1<<bits)}
function b8Dec(v){for(let e=0;e<6;e++){const m=v*10**e;if(Math.abs(m-Math.round(m))<1e-7*Math.max(1,Math.abs(m)))return {e,m:Math.round(m)}}return {e:6,m:Math.round(v*1e6)}}
function b8DecCode(c,mE,sM,def,v){const d=c.enc?b8Dec(v):null,e=b8U(c,mE,c.enc?d.e:0);if(e>6)throw Error('B8: decimal inválido');const base=Math.round(def*10**e);
 return (base+b8S(c,sM,c.enc?d.m-base:0))/10**e}
function b8Pieces(c,H,ops){
 const enc=c.enc,count=H.count,grid=H.grid;
 const find=(list,key)=>list.findIndex(e=>e.key===key);
 const put=(list,key,val,max)=>{const i=find(list,key);if(i>=0)list.splice(i,1);list.unshift({key,val});if(list.length>max)list.pop()};
 // lista recente com bit "está na lista" (contexto fm[fi]) + posição (U); fora dela, escape()
 const cached=(list,fm,fi,mr,key,escape,skip=0)=>{let r=-1;if(enc){r=find(list,key);if(r>=0&&r<skip)throw Error('B8: lista')}
  if(list.length>skip&&c.bit(fm,fi,enc?+(r>=0):0)){const k=b8U(c,mr,enc?r-skip:0)+skip;if(k>=list.length)throw Error('B8: referência inválida');return list[k].val}return escape()};
 const sv=(m,a,v)=>{ // inteiro com sinal, zero com contexto
  if(!c.bit(m.z,a,enc?+(v!==0):0))return 0;const neg=c.bit(m.z,3+a,enc?+(v<0):0),x=b8U(c,m.u[a],enc?Math.abs(v)-1:0)+1;return neg?-x:x};
 const vecM=()=>({z:b8M(8),u:[b8UM(),b8UM(),b8UM()]});
 // inteiro != 0: sinal + magnitude (lista recente de magnitudes, compartilhada entre eixos e usos)
 const mags=[],byDist=new Map(),magM=()=>({s:b8M(3),h:b8M(3),r:b8UM(),u:[b8UM(),b8UM(),b8UM()]});
 const sm=(m,a,v)=>{const neg=c.bit(m.s,a,enc?+(v<0):0),x=cached(mags,m.h,a,m.r,enc?Math.abs(v):0,()=>b8U(c,m.u[a],enc?Math.abs(v)-1:0)+1);put(mags,x,x,32);return neg?-x:x};
 const mRm=b8M(4),mRotR2=b8UM(),mMir=b8M(3),mNewM=magM(),mShI=b8M(2),mShZ=b8M(16),mShM=magM();
 const mOp=b8M(64),mCS=b8M(8),mCH=b8M(4),mComboR=b8UM(),mKind=b8M(32),mCol=b8M(8),mRot=b8M(8),mRotR=b8UM(),mQR=b8UM(),mQH=b8M(2),mQS=b8M(1),mQW=b8SM();
 const mDH=b8M(8),mDeltaR=b8UM(),mAZ=b8M(16),mAH=b8M(4),mAbsR=[0,1,2].map(b8UM);
 const mRunN=b8UM(),mStep=vecM(),mDistH=b8M(2),mDistR=b8UM(),mDist=b8UM(),mNEq=b8M(4),mSizeH=b8M(2),mSizeR=b8UM(),mSize=b8UM(),mShH=b8M(4),mShR=b8UM(),mSh=vecM();
 const deltas=[],absK=[[],[],[]],combos=[],dists=[],sizes=[],shifts=[],qmag=[];
 const rows=[],rotList=q24.map((_,i)=>({key:i,val:i})),customs=[],customKey=new Map(),lastRid=new Map();
 let prev=[0,0,0],prevOp=0,oi=0,lits=0;
 function append(combo,rid,k){const xyz=k.map((v,a)=>v*grid[a].g+grid[a].off);if(xyz.some(v=>Math.abs(v)>32768))throw Error('B8: coordenada fora do limite');
  const d=k.map((v,a)=>v-prev[a]);put(deltas,d.join(','),d,16);for(let a=0;a<3;a++)put(absK[a],k[a],k[a],32);put(combos,combo,combo,32);
  prev=k;lastRid.set(combo,rid);rows.push({combo,rid,k,xyz})}
 function codePos(k,ctx){ // posição nova: delta 3D recente; senão, por eixo: igual, valor recente ou delta novo
  const d=enc?k.map((v,a)=>v-prev[a]):null;
  const dd=cached(deltas,mDH,ctx,mDeltaR,enc?d.join(','):'',()=>{let zc=0;return [0,1,2].map(a=>{
   if(!c.bit(mAZ,a*4+zc,enc?+(d[a]!==0):0)){zc++;return 0}
   const others=absK[a].filter(e=>e.key!==prev[a]);
   const v=cached(others,mAH,a,mAbsR[a],enc?k[a]:0,()=>{const m=-prev[a];if(m!==0&&!others.some(e=>e.key===m)&&c.bit(mMir,a,enc?+(k[a]===m):0))return m;return prev[a]+sm(mNewM,a,enc?d[a]:0)});
   return v-prev[a]})});
  return prev.map((v,a)=>v+dd[a]);
 }
 const codeRot=(want,not)=>{const L=rotList.filter(e=>e.val!==not),r=b8U(c,mRotR2,enc?L.findIndex(e=>e.val===want):0);if(r>=L.length)throw Error('B8: rotação inválida');const rid=L[r].val;rotList.splice(find(rotList,rid),1);rotList.unshift({key:rid,val:rid});return rid};
 const ridOf=row=>row.r!==255?row.r:customKey.get(row.orient.join(','))??-1;
 while(rows.length<count){const op=enc?ops[oi++]:null,last=rows.at(-1);
  const oc=prevOp*4+Math.min(lits,3);let t=c.bit(mOp,oc,enc?+(op.t!==0):0);if(t)t=c.bit(mOp,16+oc,enc?+(op.t===2):0)?2:1;
  if(t===0){const row=enc?op.row:null;
   let combo;
   if(last&&c.bit(mCS,prevOp,enc?+(row.combo===last.combo):0))combo=last.combo;
   else combo=cached(combos,mCH,0,mComboR,enc?row.combo:0,()=>{const kd=b8T(c,mKind,5,enc?Math.floor(row.combo/palette.length):0),cl=b8T(c,mCol,3,enc?row.combo%palette.length:0);
    if(kd>=kinds.length||cl>=palette.length)throw Error('B8: tipo/cor inválido');return kd*palette.length+cl},last?1:0);
   const same=last&&combo===last.combo,want=enc?ridOf(row):0,before=lastRid.get(combo);let rid;
   if(before!==undefined&&c.bit(mRot,same?1:0,enc?+(want===before):0))rid=before;
   else{const rank=b8U(c,mRotR,enc?(want<0?rotList.length:find(rotList,want)):0);
    if(rank<rotList.length)rid=rotList.splice(rank,1)[0].val;
    else if(rank===rotList.length){
     // x, y, z: magnitude (lista recente ou 15 bits) e sinal; w: previsto pela norma 32767, grava só a diferença e o sinal
     const o=[0,1,2].map(j=>{const w=enc?row.orient[j]:0,mag=cached(qmag,mQH,0,mQR,Math.abs(w),()=>c.raw(15,Math.abs(w)));put(qmag,mag,mag,16);return mag&&c.bit(mQS,0,+(w<0))?-mag:mag});
     const rest=Math.max(0,32767*32767-o[0]*o[0]-o[1]*o[1]-o[2]*o[2]);let r=Math.floor(Math.sqrt(rest));while(r*r>rest)r--;while((r+1)*(r+1)<=rest)r++;
     const wp=rest-r*r>r?r+1:r,w3=enc?row.orient[3]:0,wm=wp+b8S(c,mQW,enc?Math.abs(w3)-wp:0);if(wm<0||wm>32767)throw Error('B8: quaternion inválido');
     o.push(wm&&c.bit(mQS,0,+(w3<0))?-wm:wm);
     if(o.every(v=>v===0))throw Error('B8: quaternion inválido');rid=24+customs.length;customs.push(o);customKey.set(o.join(','),rid)}
    else throw Error('B8: rotação inválida');
    rotList.unshift({key:rid,val:rid});
   }
   append(combo,rid,codePos(enc?row.k:null,same?1:0));
  }else if(t===1){if(!last)throw Error('B8: fileira sem peça anterior');
   const n=b8U(c,mRunN,enc?op.n-2:0)+2,step=[0,1,2].map(a=>sv(mStep,a,enc?op.step[a]:0));if(n>count-rows.length)throw Error('B8: fileira inválida');
   for(let j=0;j<n;j++){const p=rows.at(-1);append(p.combo,p.rid,p.k.map((v,a)=>v+step[a]))}
  }else{
   const dist=cached(dists,mDistH,0,mDistR,enc?op.dist:0,()=>b8U(c,mDist,enc?op.dist-1:0)+1);put(dists,dist,dist,16);
   const left=count-rows.length,info=byDist.get(dist);
   // tamanho: o da última cópia com esta distância, o bloco inteiro (n = dist), lista recente ou novo
   const cand=[];if(info&&info.n<=Math.min(dist,left))cand.push(info.n);if(dist<=left&&dist>=2&&!cand.includes(dist))cand.push(dist);
   let n=-1;for(let j=0;j<cand.length&&n<0;j++)if(c.bit(mNEq,j*2+(info?1:0),enc?+(op.n===cand[j]):0))n=cand[j];
   if(n<0)n=cached(sizes,mSizeH,0,mSizeR,enc?op.n:0,()=>b8U(c,mSize,enc?op.n-2:0)+2);put(sizes,n,n,16);
   const start=rows.length-dist;if(start<0||n<2||n>dist||n>left)throw Error('B8: bloco repetido inválido');
   const want=enc?op.first.map((v,a)=>v-rows[start].k[a]):null;
   const shift=info&&c.bit(mShI,0,enc?+(want.join(',')===info.shift.join(',')):0)?info.shift:
    cached(shifts,mShH,info?1:0,mShR,enc?want.join(','):'',()=>{let zc=0;return [0,1,2].map(a=>{if(!c.bit(mShZ,a*4+zc,enc?+(want[a]!==0):0)){zc++;return 0}return sm(mShM,a,enc?want[a]:0)})});
   put(shifts,shift.join(','),shift,16);byDist.set(dist,{n,shift});
   // troca: para cada peça distinta (tipo/cor + rotação) do bloco de origem, na ordem de aparição: mantém ou troca
   const map=new Map();
   if(c.bit(mRm,0,enc?+!!op.map:0)){for(let j=0;j<n;j++){const sp=rows[start+j],sk=sp.combo+'/'+sp.rid;if(map.has(sk))continue;const tg=enc?op.map.get(sk):null;
     if(c.bit(mRm,1,enc?+(tg.combo===sp.combo&&tg.rid===sp.rid):0)){map.set(sk,sp);continue}
     const cb=c.bit(mRm,2,enc?+(tg.combo===sp.combo):0)?sp.combo:cached(combos,mCH,1,mComboR,enc?tg.combo:0,()=>{const kd=b8T(c,mKind,5,enc?Math.floor(tg.combo/palette.length):0),cl=b8T(c,mCol,3,enc?tg.combo%palette.length:0);
      if(kd>=kinds.length||cl>=palette.length)throw Error('B8: tipo/cor inválido');return kd*palette.length+cl});
     const rid=cb!==sp.combo&&c.bit(mRm,3,enc?+(tg.rid===sp.rid):0)?sp.rid:codeRot(enc?tg.rid:0,sp.rid);
     if(cb===sp.combo&&rid===sp.rid)throw Error('B8: troca vazia');map.set(sk,{combo:cb,rid})}}
   for(let j=0;j<n;j++){const p=rows[start+j],m=map.get(p.combo+'/'+p.rid)||p;append(m.combo,m.rid,p.k.map((v,a)=>v+shift[a]))}
  }
  prevOp=t;lits=t?0:lits+1;
 }
 const check=H.check;for(const r of rows){check.push(r.combo&255,r.combo>>8,r.rid&255,r.rid>>8);for(const v of r.xyz)check.push(v&255,(v>>8)&255,(v>>16)&255);if(r.rid>=24)for(const v of customs[r.rid-24])check.push(v&255,(v>>8)&255)}
 return {rows,customs};
}
function b8Plan(rows,copyMin,mode){ // só no codificador: literal, fileira (>=2) ou cópia transladada (>=copyMin), com troca de tipo/cor/rotação
 // mode 1: a cópia exige as mesmas rotações; mode 2: também troca rotações. O codificador testa os dois.
 const ops=[],rk=rows.map(r=>r.r!==255?String(r.r):r.orient.join(',')),keys=rows.map((r,i)=>r.combo+'/'+rk[i]),seen=new Map();
 const cust=new Map(),firstAt=new Map(),rid=rows.map((r,i)=>{if(r.r!==255)return r.r;const k=rk[i];if(!cust.has(k)){cust.set(k,24+cust.size);firstAt.set(24+cust.size-1,i)}return cust.get(k)});
 const motif=i=>(mode>=2?'':rk[i]+'|'+rk[i+1]+'|')+rows[i+1].k.map((v,a)=>v-rows[i].k[a]).join(',');
 const addSeen=i=>{if(i+1>=rows.length)return;const m=motif(i),l=seen.get(m)||[];l.push(i);if(l.length>256)l.shift();seen.set(m,l)};
 for(let i=0;i<rows.length;){const r=rows[i];let best=null;
  if(i+1<rows.length)for(const start of (seen.get(motif(i))||[]).slice().reverse()){const max=Math.min(i-start,rows.length-i);if(max<2)continue;
   const map=new Map();let len=0,changes=0;
   while(len<max&&rows[i+len].k.every((v,a)=>v-rows[start+len].k[a]===r.k[a]-rows[start].k[a])){
    const sc=keys[start+len],tc=keys[i+len];if(map.has(sc)){if(map.get(sc)!==tc)break}else{if(sc!==tc&&(!mode||rk[start+len]!==rk[i+len]&&(mode<2||rid[i+len]>=24&&firstAt.get(rid[i+len])>=i)))break;map.set(sc,tc);if(sc!==tc)changes++}len++}
   const score=len-1.5*changes;if(len>=2&&(!best||score>best.score))best={start,len,changes,map,score}}
  if(best&&best.len>=copyMin+(best.changes?2:0)){ops.push({t:2,dist:i-best.start,n:best.len,first:r.k,map:best.changes?new Map([...best.map].map(([sk,tk])=>{const sj=keys.indexOf(sk),tj=keys.indexOf(tk);return [rows[sj].combo+'/'+rid[sj],{combo:rows[tj].combo,rid:rid[tj]}]})):null});for(let j=0;j<best.len;j++)addSeen(i+j);i+=best.len;continue}
  if(i>0&&keys[i]===keys[i-1]){const step=r.k.map((v,a)=>v-rows[i-1].k[a]);let n=0;
   while(i+n<rows.length&&keys[i+n]===keys[i]&&rows[i+n].k.every((v,a)=>v===rows[i-1].k[a]+(n+1)*step[a]))n++;
   if(n>=2){ops.push({t:1,n,step});for(let j=0;j<n;j++)addSeen(i+j);i+=n;continue}}
  ops.push({t:0,row:r});addSeen(i);i++;
 }
 return ops;
}
function b8Orders(rows){
 const by=(...f)=>[...rows].sort((u,v)=>{for(const g of f){const d=g(u)-g(v);if(d)return d}return 0});
 const X=r=>r.xyz[0],Y=r=>r.xyz[1],Z=r=>r.xyz[2],C=r=>r.combo,O=r=>r.r;
 return [rows,by(C,O,Y,Z,X),by(Y,Z,X,C),by(C,O,X,Y,Z),by(Y,X,Z,C),by(C,O,Y,X,Z),by(Y,C,O,Z,X),by(Y,C,O,X,Z)];
}
const B8_UI_DEFAULT=[53,5,7.5,5,115,.85,0,.3,.056,.75],B8_TIME=[1,.5,.25,.1,.05,.0167],B8_CONTACT=['alta','padrao','maxima'];
const B8_LOCK_DEFAULT=2|1<<2|4<<4|72<<9,B8_EXTRAS_DEFAULT={rateMode:'auto',physicsRate:120,timeScale:1,contactPrecision:'alta',deterministic:true};
const B8_FACTORY=kinds.map(()=>10); // inventário de fábrica: 10 de cada um dos 30 pares tamanho/formato
function b8Push(check,...vals){for(const v of vals){const n=Math.round(v);check.push(n&255,(n>>>8)&255,(n>>>16)&255,(n>>>24)&255)}}
function b8Header(c,P){
 const enc=c.enc,H={check:[]},mf=b8M(8),ck=H.check;
 H.hasPieces=!!c.bit(mf,0,enc?+!!P.pieces:0);H.hasPhysics=!!c.bit(mf,1,enc?+!!P.physics:0);H.hasInventory=!!c.bit(mf,2,enc?+!!P.inventory:0);
 H.hasBench=!!c.bit(mf,3,enc?+!!P.bench:0);H.hasLock=!!c.bit(mf,4,enc?+(P.lock!==null&&P.lock!==undefined):0);
 if(c.bit(mf,5,0))throw Error('B8: seção desconhecida (versão mais nova?)');
 H.unlimited=!!c.bit(mf,6,enc?+!!P.unlimited:0); // modo do inventário: sempre (1 bit), vale para P e para I
 ck.push((H.hasPieces?1:0)|(H.hasPhysics?2:0)|(H.hasInventory?4:0)|(H.hasBench?8:0)|(H.hasLock?16:0)|(H.unlimited?32:0));
 const mh=b8M(16),mE=b8UM(),sM=b8SM();
 if(H.hasPhysics){const Pp=enc?P.physics:null,fl=b8M(physKeys.length);H.ui=B8_UI_DEFAULT.slice();
  const allDef=enc?+(Pp.ui.every((v,i)=>v===B8_UI_DEFAULT[i])):0;
  if(!c.bit(mh,0,allDef))for(let i=0;i<physKeys.length;i++)if(c.bit(fl,i,enc?+(Pp.ui[i]!==B8_UI_DEFAULT[i]):0))H.ui[i]=b8DecCode(c,mE,sM,B8_UI_DEFAULT[i],enc?Pp.ui[i]:0);
  for(const v of H.ui){const d=b8Dec(v);b8Push(ck,d.e,d.m)}
  const x=enc?Pp.extras:null,xd=x=>x.rateMode==='auto'&&x.physicsRate===120&&x.timeScale===1&&x.contactPrecision==='alta';
  if(c.bit(mh,1,enc?+xd(x):0))H.extras={...B8_EXTRAS_DEFAULT};
  else{const ms=b8SM(),mi=b8UM();const rateMode=c.bit(mh,2,enc?+(x.rateMode==='fixed'):0)?'fixed':'auto';const physicsRate=120+b8S(c,ms,enc?x.physicsRate-120:0);
   let ti=b8U(c,mi,enc?(B8_TIME.indexOf(x.timeScale)+1||B8_TIME.length+1)-1:0),timeScale;
   if(ti<B8_TIME.length)timeScale=B8_TIME[ti];else if(ti===B8_TIME.length)timeScale=b8DecCode(c,mE,sM,1,enc?x.timeScale:0);else throw Error('B8: velocidade inválida');
   const ci=b8U(c,mi,enc?Math.max(0,B8_CONTACT.indexOf(x.contactPrecision)):0);if(ci>=B8_CONTACT.length)throw Error('B8: precisão inválida');
   if(physicsRate<1||physicsRate>100000)throw Error('B8: frequência inválida');
   H.extras={rateMode,physicsRate,timeScale,contactPrecision:B8_CONTACT[ci],deterministic:true}}
  H.extras.deterministic=!!c.bit(mh,3,enc?+(x.deterministic!==false):0);
  {const d=b8Dec(H.extras.timeScale);b8Push(ck,H.extras.rateMode==='fixed'?1:0,H.extras.physicsRate,d.e,d.m,B8_CONTACT.indexOf(H.extras.contactPrecision),H.extras.deterministic?1:0)}}
 if(H.hasInventory){const I=enc?P.inventory:null,mi=b8UM(),mv=b8M(4);
  let vals;
  if(c.bit(mh,5,enc?+(I.values.every((v,i)=>v===B8_FACTORY[i])):0))vals=B8_FACTORY.slice();
  else if(c.bit(mh,6,enc?+I.values.every(v=>v===I.values[0]):0)){const v=b8U(c,mi,enc?I.values[0]:0);vals=kinds.map(()=>v)}
  else{vals=[];for(let i=0;i<kinds.length;i++){const prev=i?vals[i-1]:10;vals.push(c.bit(mv,i?1:0,enc?+(I.values[i]===prev):0)?prev:b8U(c,mi,enc?I.values[i]:0))}}
  if(vals.some(v=>v>255))throw Error('B8: inventário fora do limite (0 a 255)');H.values=vals;ck.push(...vals)}
 if(H.hasBench){const b=enc?P.bench:null,mb=b8UM(),cs=x=>Math.round(x*100);
  const hc=b8U(c,mb,enc?b.hc:0),iso=c.bit(mh,7,enc?+b.iso:0),threads=b8U(c,mb,enc?b.best.threads:0),magPool=c.bit(mh,8,enc?+!!b.best.magPool:0),magGpu=c.bit(mh,9,enc?+!!b.best.magGpu:0);
  const magLag=b8U(c,mb,enc?(b.best.magLag|0):0),jolt=b8U(c,mb,enc?cs(b.joltMs):0),mag=b8U(c,mb,enc?cs(b.magMs||0):0),step=jolt+mag+b8S(c,b8SM(),enc?cs(b.stepMs)-cs(b.joltMs)-cs(b.magMs||0):0);
  H.bench={hc,iso:!!iso,best:{threads,magPool:!!magPool,magGpu:!!magGpu,magLag},joltMs:jolt/100,magMs:mag/100,stepMs:step/100};b8Push(ck,hc,iso,threads,magPool,magGpu,magLag,jolt,mag,step)}
 if(H.hasLock){H.lock=c.bit(mh,10,enc?+(P.lock===B8_LOCK_DEFAULT):0)?B8_LOCK_DEFAULT:c.raw(16,enc?P.lock:0);b8Push(ck,H.lock)}
 if(H.hasPieces){const Pc=enc?P.pieces:null;H.count=b8U(c,b8UM(),enc?Pc.count:0);if(H.count>4000)throw Error('B8: peças demais');
  const mGrid=b8UM();H.grid=[0,1,2].map(a=>{const g=b8U(c,mGrid,enc?Pc.grid[a].g-1:0)+1,off=g>1?b8U(c,mGrid,enc?Pc.grid[a].off:0):0;if(g>65536||off>=g)throw Error('B8: grade inválida');return {g,off}})}
 return H;
}
function b8Crc(c,check){const crc=crc16(check);if(c.raw(16,c.enc?crc:0)!==crc)throw Error('Código B8 corrompido (verificação falhou)')}
function b8Prepare(project,sections){
 const P={unlimited:project.inventoryMode==='unlimited',pieces:null,physics:null,inventory:null,bench:null,lock:null};
 if(sections.physics){const physics=validatePhysics(project.physics||{}),side=Number(physics.tileSide??defaultPhysics.tileSide)/100;
  const ui=physKeys.map((key,i)=>{const v=Number(physics[key]??defaultPhysics[key])*(key==='ballRadius'||key==='ballHeight'?side:1),d=b8Dec(v);return d.m/10**d.e});
  const x=project.physicsExtras||{},extras={rateMode:x.rateMode==='fixed'?'fixed':'auto',physicsRate:Math.round(Number(x.physicsRate)||120),timeScale:Number(x.timeScale)||1,contactPrecision:B8_CONTACT.includes(x.contactPrecision)?x.contactPrecision:'alta',deterministic:x.deterministic!==false};
  P.physics={ui,extras}}
 if(sections.inventory){const inv=project.inventory||{};const values=kinds.map(k=>{const v=inv?.[k.size]?.[k.type];return Number.isInteger(v)&&v>=0&&v<=255?v:10});P.inventory={values}}
 if(sections.bench&&project.bench)P.bench=project.bench;
 if(sections.lock!==undefined&&sections.lock!==null)P.lock=sections.lock;
 return P;
}
const B8_RANGES_EXTRA=[[0x1200,0x1248],[0x124a,0x124d],[0x1250,0x1256],[0x1258,0x1258],[0x125a,0x125d],[0x1260,0x1288],[0x128a,0x128d],[0x1290,0x12b0],[0x12b2,0x12b5],[0x12b8,0x12be],[0x12c0,0x12c0],[0x12c2,0x12c5],[0x12c8,0x12d6],[0x12d8,0x1310],[0x1312,0x1315],[0x1318,0x135a],[0x1380,0x138f],[0x1681,0x169a],[0x16a0,0x16ea],[0x16f1,0x16f8],[0x1900,0x191e],[0x1950,0x196d],[0x1970,0x1974],[0x2d80,0x2d96],[0x2da0,0x2da6],[0x2da8,0x2dae],[0x2db0,0x2db6],[0x2db8,0x2dbe],[0x2dc0,0x2dc6],[0x2dc8,0x2dce],[0x2dd0,0x2dd6],[0x2dd8,0x2dde],[0x3041,0x3096],[0x30a1,0x30fa],[0x3105,0x312f],[0x31a0,0x31bf],[0x31f0,0x31ff],[0x4db6,0x4dbf],[0x9feb,0x9fff],[0xa4d0,0xa4f7],[0xa500,0xa60b],[0xa610,0xa61f],[0xa62a,0xa62b],[0xa6a0,0xa6e5],[0xa800,0xa801],[0xa803,0xa805],[0xa807,0xa80a],[0xa80c,0xa822],[0x10000,0x1000b],[0x1000d,0x10026],[0x10028,0x1003a],[0x1003c,0x1003d],[0x1003f,0x1004d],[0x10050,0x1005d],[0x10080,0x100fa],[0x10450,0x1049d],[0x12000,0x12399],[0x12480,0x12543],[0x13000,0x1342f],[0x14400,0x14646],[0x16800,0x16a38],[0x17000,0x187f7],[0x18800,0x18cd5],[0x18cff,0x18d1e],[0x1b000,0x1b122],[0x1b132,0x1b132],[0x1b150,0x1b152],[0x1b155,0x1b155],[0x1b164,0x1b167],[0x1b170,0x1b2fb],[0x2ebf0,0x2ee5d],[0x30000,0x3134a],[0x31350,0x323af]];
const B8_RANGES=[...B7_RANGES,...B8_RANGES_EXTRA];
const B8_TEXT=(()=>{const ranges=[];let N=0;for(const [s,e] of B8_RANGES){ranges.push([s,e,N]);N+=e-s+1}
 if(N!==123651)throw Error('Alfabeto B8 inconsistente: '+N);
 const a=[...'0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()+,-./;<=>?@[]^{}',"'",'"']; // 87: os 85 do B6 + aspas
 return {ranges,N,ascii:a,aIndex:new Map(a.map((ch,i)=>[ch.codePointAt(0),i]))}})();
function b8Sym(i){for(const [s,e,b] of B8_TEXT.ranges)if(i<b+e-s+1)return String.fromCodePoint(s+i-b);throw Error('B8: símbolo')}
function b8Index(cp){for(const [s,e,b] of B8_TEXT.ranges)if(cp>=s&&cp<=e)return b+cp-s;return -1}
function b8BytesToInt(bytes){if(!bytes.length||bytes[bytes.length-1]===0)throw Error('B8: carga inválida');let h='';for(let i=bytes.length-1;i>=0;i--)h+=bytes[i].toString(16).padStart(2,'0');return BigInt('0x'+h)}
function b8IntToBytes(v){if(v<=0n)throw Error('Código B8 inválido');let h=v.toString(16);if(h.length%2)h='0'+h;const out=new Uint8Array(h.length/2);for(let j=0;j<out.length;j++)out[j]=parseInt(h.substr(h.length-2-2*j,2),16);return out}
function b8Digits(v,N){N=BigInt(N);const pw=[N];while(pw.at(-1)*pw.at(-1)<=v)pw.push(pw.at(-1)*pw.at(-1));
 const rec=(x,i,fixed)=>{if(i<0)return [Number(x)];const q=x/pw[i],r=x%pw[i];return fixed||q>0n?rec(q,i-1,fixed).concat(rec(r,i-1,true)):rec(r,i-1,false)};return rec(v,pw.length-1,false)}
function b8FromDigits(ds,N){N=BigInt(N);const pows=new Map(),pow=k=>{if(!pows.has(k))pows.set(k,N**BigInt(k));return pows.get(k)};
 const rec=(a,b)=>{if(b-a<=8){let v=0n;for(let j=a;j<b;j++)v=v*N+BigInt(ds[j]);return v}const m=(a+b)>>1;return rec(a,m)*pow(b-m)+rec(m,b)};return rec(0,ds.length)}
const B8_MIX=(()=>{
 const cls=ranges=>{const r=[];let n=0;for(const [s,e] of ranges){r.push([s,e,n]);n+=e-s+1}
  return {n,sym:i=>{for(const [s,e,b] of r)if(i<b+e-s+1)return String.fromCodePoint(s+i-b)},idx:cp=>{for(const [s,e,b] of r)if(cp>=s&&cp<=e)return b+cp-s;return -1}}};
 const K0={n:87,sym:i=>B8_TEXT.ascii[i],idx:cp=>B8_TEXT.aIndex.get(cp)??-1};
 const K=[K0,cls(B7_TWO),cls(B8_RANGES.filter(([,e])=>e<0x10000)),cls(B8_RANGES.filter(([s])=>s>=0x10000))];
 if(K.map(c=>c.n).join()!=='87,962,41848,81803')throw Error('Alfabeto misto B8 inconsistente');
 return {K,cost:[1,2,3,4],maxBytes:3072,maxCost:4000};
})();
function b8MixE(E,c){const {K,cost}=B8_MIX;while(E.length<=c){const k=E.length;let e=0n;for(let q=0;q<4;q++)if(k>=cost[q])e+=BigInt(K[q].n)*E[k-cost[q]];E.push(e)}return E}
const b8MixF=(E,c)=>{const {K,cost}=B8_MIX;let f=0n;for(let q=0;q<2;q++)if(c>=cost[q])f+=BigInt(K[q].n)*E[c-cost[q]];return f} // sequências de custo c que começam em K0/K1
function b8MixToText(v){
 const {K,cost}=B8_MIX,E=[1n];let c=0,f;
 while(v>=(f=b8MixF(b8MixE(E,c),c))){v-=f;c++}      // c = custo total; v = posição entre as sequências de custo c
 let out='',first=true;
 while(c>0){let q=0;for(;q<(first?2:4);q++){if(c<cost[q])continue;const sub=E[c-cost[q]],block=BigInt(K[q].n)*sub;if(v<block){out+=K[q].sym(Number(v/sub));v%=sub;c-=cost[q];break}v-=block}
  if(q===(first?2:4))throw Error('B8: texto misto inconsistente');first=false}
 return out;
}
function b8MixValue(cps){
 const {K,cost}=B8_MIX;
 const syms=cps.map(cp=>{for(let q=0;q<4;q++){const j=K[q].idx(cp);if(j>=0)return [q,j]}throw Error('Caractere inválido no código B8: '+String.fromCodePoint(cp))});
 if(syms[0][0]>1)throw Error('Código B8 inválido (início)');
 let C=0;for(const [q] of syms)C+=cost[q];if(C>B8_MIX.maxCost)throw Error('Código B8 longo demais');
 const E=b8MixE([1n],C);let v=0n;for(let k=0;k<C;k++)v+=b8MixF(E,k);
 let cr=C,first=true;for(const [q,j] of syms){for(let p=0;p<q;p++)if(cr>=cost[p])v+=BigInt(K[p].n)*E[cr-cost[p]];v+=BigInt(j)*E[cr-cost[q]];cr-=cost[q];first=false}
 return v;
}
const B8_IGNORE=/[\s\u00ad\u034f\u061c\u115f\u1160\u180b-\u180f\u200b-\u200f\u202a-\u202e\u2060-\u206f\u3164\ufe00-\ufe0f\ufeff\uffa0]|\udb40[\udd00-\uddef]/g;
function b8ToText(bytes,goal='chars'){
 const v=b8BytesToInt(bytes);
 if(goal==='bytes'){if(bytes.length<=B8_MIX.maxBytes)return b8MixToText(v);return '¡'+b8Digits(v,87).map(d=>B8_TEXT.ascii[d]).join('')}
 return b8Digits(v,B8_TEXT.N).map(b8Sym).join('');
}
function b8FromText(text){
 let cps=Array.from(text,ch=>ch.codePointAt(0));if(!cps.length)throw Error('Código B8 vazio');if(cps.length>200000)throw Error('Código B8 longo demais');
 let goal,v;
 if(cps[0]===0xa1){goal='bytes';cps=cps.slice(1);if(!cps.length)throw Error('Código B8 vazio');v=b8FromDigits(cps.map(cp=>{const i=B8_TEXT.aIndex.get(cp);if(i===undefined)throw Error('Caractere inválido no código B8: '+String.fromCodePoint(cp));return i}),87)}
 else if(cps[0]<0x800){goal='bytes';v=b8MixValue(cps)}
 else{goal='chars';v=b8FromDigits(cps.map(cp=>{const i=b8Index(cp);if(i<0)throw Error('Caractere inválido no código B8: '+String.fromCodePoint(cp));return i}),B8_TEXT.N)}
 return {bytes:b8IntToBytes(v),goal};
}
function b8PrepareRows(project){
 const rows=makeRecords(project.items||[]);if(rows.length>4000)throw Error('Limite de 4000 peças');
 const grid=[0,1,2].map(a=>{let g=0;for(const r of rows)g=b6Gcd(g,r.xyz[a]-rows[0].xyz[a]);g=Math.min(g||1,65536);return {g,off:rows.length?((rows[0].xyz[a]%g)+g)%g:0}});
 for(const r of rows)r.k=r.xyz.map((x,a)=>(x-grid[a].off)/grid[a].g);return {rows,grid};
}
function b8Payload(project,sections){
 const P=b8Prepare(project,sections);let rows=null;
 if(sections.pieces){const prep=b8PrepareRows(project);rows=prep.rows;P.pieces={count:rows.length,grid:prep.grid}}
 const trial=(order,copyMin,mode)=>{const w=new B8Writer();const H=b8Header(w,P);if(rows)b8Pieces(w,H,b8Plan(order,copyMin,mode));b8Crc(w,H.check);return w.finish()};
 if(!rows)return trial(null,0,0);
 let best=null;for(const order of b8Orders(rows))for(const copyMin of [3,4])for(const mode of [1,2]){const b=trial(order,copyMin,mode);if(!best||b.length<best.length)best=b}
 return best;
}
function b8DecodePayload(bytes){
 const r=new B8Reader(bytes),H=b8Header(r,null),s=H.hasPieces?b8Pieces(r,H,null):null;b8Crc(r,H.check);
 const project={format:'Brastoy',version:8,b8:{pieces:H.hasPieces,physics:H.hasPhysics,inventory:H.hasInventory,bench:H.hasBench,lock:H.hasLock},inventoryMode:H.unlimited?'unlimited':'limited'};
 if(H.hasPhysics){const side=H.ui[2]/100;project.physics=Object.fromEntries(physKeys.map((k,i)=>[k,H.ui[i]/(k==='ballRadius'||k==='ballHeight'?side:1)]));project.physicsExtras=H.extras}
 if(H.hasInventory)project.inventory=inventoryFromValues(H.values);
 if(H.hasBench)project.bench=H.bench;
 if(H.hasLock){project.b5=true;project.b5extra=String.fromCharCode(0x4e00+(H.lock&16383),0x4e00+(H.lock>>>14))}
 if(H.hasPieces){const items=s.rows.map(x=>({...kinds[Math.floor(x.combo/palette.length)],color:palette[x.combo%palette.length],pos:x.xyz.map(n=>n/256),rot:x.rid<24?q24[x.rid]:s.customs[x.rid-24].map(n=>n/32767)}));project.items=parseProjectItems(items)}
 return project;
}
const B8_GOALS=['chars','bytes'],B8_GOAL_LABEL={chars:'menos caracteres (alfabeto de 123 651 letras)',bytes:'menos bytes (1–4 bytes por símbolo)'};
function b8Sections(opts={}){return {pieces:opts.pieces!==false,physics:opts.physics!==false,inventory:!!opts.inventory,bench:!!opts.bench,lock:Number.isInteger(opts.lock)?opts.lock:null}}
function encodeB8(project,goal='chars',opts={}){return '8'+b8ToText(b8Payload(project,b8Sections(opts)),B8_GOALS.includes(goal)?goal:'chars')}
function decodeB8(code){const t=String(code).normalize('NFC').replace(B8_IGNORE,'');if(t[0]!=='8')throw Error('Código B8 deve começar com 8');return b8DecodePayload(b8FromText(t.slice(1)).bytes)}
function encodeBestB8(project,goal,opts){
 const payload=b8Payload(project,b8Sections(opts)),size=c=>({code:c,chars:[...c].length,bytes:new TextEncoder().encode(c).length,u16:c.length});
 const variants={chars:size('8'+b8ToText(payload,'chars')),bytes:size('8'+b8ToText(payload,'bytes'))};goal=B8_GOALS.includes(goal)?goal:'chars';
 return {code:variants[goal].code,pattern:'B8 / '+B8_GOAL_LABEL[goal],chars:variants[goal].chars,variants,payload:payload.length,sections:b8Sections(opts)};
}
function jsonDiagnostic(error,raw){
 const message=String(error?.message||error),m=message.match(/\bposition (\d+)\b/);
 let at=m?Number(m[1]):null;
 if(at===null){const loc=message.match(/line (\d+) column (\d+)/);if(loc){const lines=raw.split('\n');at=lines.slice(0,Number(loc[1])-1).reduce((n,line)=>n+line.length+1,0)+Number(loc[2])-1}}
 if(at===null||!Number.isFinite(at))return Error('JSON inválido: '+message);
 at=Math.max(0,Math.min(raw.length,at));
 const before=raw.slice(0,at),line=before.split('\n').length,col=before.length-before.lastIndexOf('\n');
 const content=(raw.split('\n')[line-1]||'').slice(0,160),indicator=' '.repeat(Math.min(160,Math.max(0,col-1)))+'↑';
 const e=new Error(`JSON inválido na linha ${line}, coluna ${col}: ${message}\n${content}\n${indicator}\nVerifique vírgulas, aspas duplas e chaves. A construção anterior foi preservada.`);
 e.jsonPosition=at;return e;
}
function parseProjectJSON(raw){
 let working=raw,inserted=0;
 for(let attempt=0;attempt<4;attempt++){
  try{
   const obj=JSON.parse(working);
   if(Array.isArray(obj))return {format:'Brastoy',items:obj,jsonRepairs:inserted};
   if(!obj||typeof obj!=='object'||!Array.isArray(obj.items))throw Error('JSON precisa de um objeto com lista "items" (ou um array de peças)');
   return {...obj,jsonRepairs:inserted};
  }catch(error){
   const m=String(error.message).match(/\bposition (\d+)\b/);
   const pos=m?Number(m[1]):-1;
   if(attempt>=3||!(error instanceof SyntaxError)||!/(Expected ',' or '[}\]]' after property value|Expected ',' or '[}\]]' after array element)/.test(error.message)||pos<0)
    throw error instanceof SyntaxError?jsonDiagnostic(error,working):error;
   const before=working.slice(0,pos),prev=before.trimEnd(),next=working.slice(pos).trimStart();
   // Apenas o padrão `"valor" \n "proximaChave":` pode receber uma vírgula.
   if(!/["}\]0-9eEls]$/.test(prev)||!/^"(?:[^"\\]|\\.)+"\s*:/.test(next))throw jsonDiagnostic(error,working);
   working=working.slice(0,prev.length)+','+working.slice(prev.length);inserted++;
  }
 }
 throw Error('JSON não pôde ser recuperado com segurança');
}
async function decodeProject(text){if(typeof text!=='string'||text.length>1000000)throw Error('Projeto excede 1 milhão de caracteres');const code=text.trim();if(code.startsWith('B5:')){const encoded=code.slice(3),modern=encoded.startsWith('!');if(encoded.length<(modern?5:2))throw Error('B5 incompleto');const extra=modern?encoded.slice(1,3):encoded[0],inner=encoded.slice(modern?3:1);let project;try{project=await decodeB4('B4:'+inner)}catch(e){project=decodeBRS('B2:'+inner)}project.b5=true;project.b5extra=extra;return project}if(code[0]==='8')return decodeB8(code);if(code.startsWith('B7:')||/^7/.test(code))return decodeB7(code);if(/^B6[:!]/.test(code))return decodeB6(code);if(code.startsWith('B4:'))return await decodeB4(code);if(code.startsWith('B3:'))throw Error('B3 era uma fortaleza predefinida sem peças codificadas. Use B2: para construções livres.');if(/^B[12]:/.test(code))return decodeBRS(code);const obj=parseProjectJSON(text);return {...obj,items:parseProjectItems(obj.items)}}

const BrastoyCodec = {
  version: "MK-10.51",
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
