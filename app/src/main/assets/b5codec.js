// GERADO por tools/extrair_codec_b5.py a partir do Brastoy Simulator — não editar à mão.
"use strict";
const __inputs = {"stockMode": {"value": "limited"}, "inventoryFile": {}, "size": {"value": "grande"}, "rotationStep": {"value": "15"}, "color": {"value": "#78caff"}, "gridStep": {"value": "1"}, "buildHeight": {"value": "0", "min": "0", "max": "30"}, "moduleCount": {"value": "4", "min": "2", "max": "30"}, "codeAlphabet": {"value": "unicode"}, "lockExport": {}, "file": {}, "engineMode": {"value": "auto"}, "framePolicy": {"value": "auto"}, "smoothMotion": {}, "fieldB": {"value": "53", "min": "0", "max": "2000"}, "range": {"value": "5", "min": "2", "max": "15"}, "tileSide": {"value": "7.5", "min": "4", "max": "16"}, "core": {"value": "5", "min": "2", "max": "12"}, "tileMass": {"value": "115", "min": "5", "max": "150"}, "friction": {"value": "0.85", "min": "0", "max": "1.5"}, "bounce": {"value": "0", "min": "0", "max": "0.8"}, "showMagnets": {}, "magView": {"value": "pieces"}, "fieldLines": {"value": "24", "min": "8", "max": "80"}, "fieldQuality": {"value": "120"}, "magExtent": {"value": "12", "min": "4", "max": "32"}, "pieceOpacity": {"value": "22", "min": "8", "max": "85"}, "ballMass": {"value": "0.3", "min": "0.1", "max": "3"}, "ballRadius": {"value": "0.75", "min": "0.15", "max": "0.75"}, "ballHeight": {"value": "10", "min": "2", "max": "12"}};
function $(id){ const d = __inputs[id] || {}; return { value: d.value ?? "", min: d.min ?? "-Infinity", max: d.max ?? "Infinity" }; }
function log(){}
const inventory={
 grande:{quadrado:10,janela:10,moldura:10,retangulo:10,equilatero:10,retanguloTri:10,isoceles:10,E:10,L:10,porta:10},
 medio:{quadrado:10,janela:10,moldura:10,retangulo:10,equilatero:10,retanguloTri:10,isoceles:10,L:10,porta:10,encaixe:10,E:10},
 pequeno:{equilatero:10,retanguloTri:10,quadrado:10,janela:10,moldura:10,retangulo:10,isoceles:10,L:10,porta:10}
};
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
function finiteDenseVector(a,length,maxAbs=Infinity){
 if(!Array.isArray(a)||a.length!==length)return false;
 for(let i=0;i<length;i++)if(!Object.hasOwn(a,i)||!Number.isFinite(a[i])||Math.abs(a[i])>maxAbs)return false;
 return true;
}
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
function validatePhysics(obj){if(!obj)return {};const validated={};for(const k of physKeys){if(obj[k]===undefined)continue;const n=Number(obj[k]);if(!Number.isFinite(n))throw Error('Parâmetro físico inválido: '+k);const el=$(k);validated[k]=Math.max(Number(el.min),Math.min(Number(el.max),n))}return validated}
function crc32(bytes){let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0)}return (c^0xffffffff)>>>0}
function u8toB64url(bytes){let s='';const block=8192;for(let i=0;i<bytes.length;i+=block)s+=String.fromCharCode(...bytes.subarray(i,i+block));return btoa(s).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'')}
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
function templateCompatible(rows,start,other,count){
 const a=rows[start],b=rows[other];if(!a||!b)return false;
 const dx=b.xyz[0]-a.xyz[0],dy=b.xyz[1]-a.xyz[1],dz=b.xyz[2]-a.xyz[2];
 for(let i=0;i<count;i++){
  const x=rows[start+i],y=rows[other+i];if(!x||!y||!sameRecord(x,y))return false;
  if(y.xyz[0]!==x.xyz[0]+dx||y.xyz[1]!==x.xyz[1]+dy||y.xyz[2]!==x.xyz[2]+dz)return false;
 }return true;
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
async function decodeProject(text){if(typeof text!=='string'||text.length>1000000)throw Error('Projeto excede 1 milhão de caracteres');const code=text.trim();if(code.startsWith('B5:')){const encoded=code.slice(3),modern=encoded.startsWith('!');if(encoded.length<(modern?5:2))throw Error('B5 incompleto');const extra=modern?encoded.slice(1,3):encoded[0],inner=encoded.slice(modern?3:1);let project;try{project=await decodeB4('B4:'+inner)}catch(e){project=decodeBRS('B2:'+inner)}project.b5=true;project.b5extra=extra;return project}if(code.startsWith('B4:'))return await decodeB4(code);if(code.startsWith('B3:'))throw Error('B3 era uma fortaleza predefinida sem peças codificadas. Use B2: para construções livres.');if(/^B[12]:/.test(code))return decodeBRS(code);const obj=parseProjectJSON(text);return {...obj,items:parseProjectItems(obj.items)}}
function validateB5Extra(chars){
 if(typeof chars!=='string'||(chars.length!==1&&chars.length!==2))throw Error('Opções B5 incompletas');
 if(chars.length===2){
  const a=chars.charCodeAt(0)-0x4e00,b=chars.charCodeAt(1)-0x4e00;
  if(a<0||a>16383||b<0||b>16383)throw Error('Opções B5 fora do alfabeto Unicode');
  const flags=a+b*16384;
  if((flags&3)>2||((flags>>>2)&3)>2||((flags>>>9)&127)>100)throw Error('Opções B5 fora dos limites');
 }else if(chars.charCodeAt(0)<0x4e00)throw Error('Opções B5 legadas inválidas');
}

const BrastoyCodec = {
  palette, kinds, q24,
  /** Projeto {items:[{type,size,color,pos,rot}]} -> melhor código B4 (o mesmo que o simulador gera sem trava). */
  async encode(project, alphabet = 'unicode') { return (await encodeBestBRS(project, alphabet)).code; },
  async decode(text) { return await decodeProject(text); },
};
if (typeof module !== 'undefined') module.exports = BrastoyCodec;
if (typeof window !== 'undefined') window.BrastoyCodec = BrastoyCodec;
