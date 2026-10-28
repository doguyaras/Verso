// ADR-0049 kart eşleşme eşiği ölçümü (Docker'sız analiz).
// Girdi: kart-esik-set.json ve aynı sırayla üretilmiş Ollama /api/embed cevabı
// (her kart için: soru, yeniden ifadeler, yakın ıskalar).
// Kullanım: node eval/embedding-olcumu/kart-esik.mjs <emb.json>
// Embedding: Ollama'nın host portu yok; ağ içinden tek istek, ör.
//   docker run --rm --network verso_models -v <dir>:/w curlimages/curl -s -X POST \
//     http://ollama:11434/api/embed -H 'Content-Type: application/json' --data @/w/req.json -o /w/emb.json
// req.json = {"model":"bge-m3:567m","input":[...kart sırasıyla bütün metinler]}
import { readFileSync } from 'node:fs';

const set = JSON.parse(readFileSync(new URL('./kart-esik-set.json', import.meta.url), 'utf8'));
const emb = JSON.parse(readFileSync(process.argv[2], 'utf8')).embeddings;

const cos = (a, b) => {
  let d = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
  return d / Math.sqrt(na * nb);
};

let k = 0;
const cards = [], probes = [];
for (const c of set.cards) {
  cards.push({ id: c.id, v: emb[k++] });
  for (const p of c.paraphrases) probes.push({ card: c.id, kind: 'para', text: p, v: emb[k++] });
  for (const n of c.nearMiss) probes.push({ card: c.id, kind: 'near', text: n, v: emb[k++] });
}
if (k !== emb.length) throw new Error(`metin/embedding sayısı uyuşmuyor: ${k} != ${emb.length}`);

for (const p of probes) {
  const ranked = cards.map(c => ({ id: c.id, s: cos(p.v, c.v) })).sort((a, b) => b.s - a.s);
  p.top = ranked[0];
  p.own = ranked.find(r => r.id === p.card).s;
}
const paras = probes.filter(p => p.kind === 'para'), nears = probes.filter(p => p.kind === 'near');
const f = x => x.toFixed(3);

console.log(`kart ${cards.length}, yeniden ifade ${paras.length}, yakın ıska ${nears.length}`);
console.log('| eşik | doğru kart (recall) | yanlış karta düşen yeniden ifade | yakın ıska eşleşmesi | precision |');
console.log('|---|---|---|---|---|');
for (let t = 0.80; t <= 0.9801; t += 0.01) {
  const ok = paras.filter(p => p.top.s >= t && p.top.id === p.card).length;
  const wrongPara = paras.filter(p => p.top.s >= t && p.top.id !== p.card).length;
  const near = nears.filter(p => p.top.s >= t).length;
  const hits = ok + wrongPara + near;
  const prec = hits ? ok / hits : 1;
  console.log(`| ${t.toFixed(2)} | ${ok}/${paras.length} (${f(ok / paras.length)}) | ${wrongPara} | ${near}/${nears.length} | ${f(prec)} |`);
}
const sorted = (xs) => xs.map(x => x).sort((a, b) => a - b);
const ownS = sorted(paras.map(p => p.own)), nearS = sorted(nears.map(p => p.top.s));
const q = (xs, r) => xs[Math.min(xs.length - 1, Math.floor(r * (xs.length - 1)))];
console.log(`\nyeniden ifade → kendi kartı: min ${f(ownS[0])}, p10 ${f(q(ownS, 0.1))}, medyan ${f(q(ownS, 0.5))}, maks ${f(ownS.at(-1))}`);
console.log(`yakın ıska → en yakın kart: min ${f(nearS[0])}, medyan ${f(q(nearS, 0.5))}, p90 ${f(q(nearS, 0.9))}, maks ${f(nearS.at(-1))}`);
console.log('\nen yüksek yakın ıskalar:');
for (const p of nears.sort((a, b) => b.top.s - a.top.s).slice(0, 6)) console.log(`  ${f(p.top.s)} ${p.card} → ${p.top.id}: ${p.text}`);
console.log('en düşük yeniden ifadeler:');
for (const p of paras.sort((a, b) => a.own - b.own).slice(0, 6)) console.log(`  ${f(p.own)} ${p.card}${p.top.id !== p.card ? ' (en yakın ' + p.top.id + ')' : ''}: ${p.text}`);
