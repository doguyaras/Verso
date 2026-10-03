// System (operators): the working mode and what it means for the data, the models, the session, and observability.
import { api } from '../api.js';
import { CONFIG } from '../config.js';
import { icon } from '../icons.js';
import { el } from '../render.js';

function row(glyph, key, value) {
  return el('div', { class: 'kv-row' }, el('span', { class: 'k' }, icon(glyph, 'sm'), key), el('span', { class: 'v' }, value));
}

export async function renderSystem(ctx) {
  const { main, guarded, identity, roles, expiresAt } = ctx;
  main.replaceChildren(el('div', { class: 'grid halves' }, el('div', { class: 'skeleton block' }), el('div', { class: 'skeleton block' })));
  const info = await guarded(() => api.info());
  if (!ctx.isCurrent()) return;
  const local = info?.mode !== 'cloud';
  const left = el('span', {});
  const meter = el('span');
  const meterWrap = el('div', { class: 'meter', 'aria-hidden': 'true' }, meter);
  const lifetime = Math.max(1, expiresAt() - Date.now());
  const tick = () => {
    const ms = Math.max(0, expiresAt() - Date.now());
    const minutes = Math.floor(ms / 60000);
    const seconds = Math.floor((ms % 60000) / 1000);
    left.textContent = `${minutes} dk ${String(seconds).padStart(2, '0')} sn`;
    meter.style.width = `${Math.min(100, (ms / lifetime) * 100)}%`;
  };
  tick();
  const timer = setInterval(tick, 1000);

  main.replaceChildren(
    el('div', { class: 'page-head' }, el('div', {}, el('h2', {}, 'Sistem'),
      el('p', {}, 'Asistanın hangi modda çalıştığı, hangi modelleri kullandığı ve oturumunuz.'))),
    info ? el('section', { class: `card mode-hero ${local ? 'local' : 'cloud'}` },
      el('span', { class: 'big-icon' }, icon(local ? 'shield' : 'cloud', 'lg')),
      el('div', {}, el('h3', {}, local ? 'Yerel mod' : 'Bulut modu'),
        el('p', {}, local
          ? 'Belgeler, sorular ve cevaplar bu sunucudan çıkmaz. Embedding ve sohbet modeli aynı ağda çalışır; uygulamanın internete çıkışı ağ, uygulama ve açılış katmanlarında kapalıdır.'
          : 'Sorular ve seçilen pasajlar bulut sağlayıcısına gönderilir (KVKK md. 9 kapsamında yurt dışına aktarım). Belgelerin kendisi ve embedding yine bu sunucuda kalır.'))) : null,
    el('div', { class: 'grid halves' },
      el('section', { class: 'card' }, el('div', { class: 'card-head' }, el('h3', {}, 'Modeller')),
        el('div', { class: 'card-body' },
          row('chat', 'Sohbet modeli', info ? el('span', { class: 'code' }, info.chatModel) : '–'),
          row('layers', 'Embedding modeli', info ? el('span', { class: 'code' }, info.embeddingModel) : '–'),
          row(local ? 'shield' : 'cloud', 'Mod', info ? (local ? 'local' : 'cloud') : '–'))),
      el('section', { class: 'card' }, el('div', { class: 'card-head' }, el('h3', {}, 'Oturum')),
        el('div', { class: 'card-body' },
          row('user', 'Kullanıcı', identity.preferred_username ?? '–'),
          row('key', 'Roller', roles.join(', ')),
          row('clock', 'Token geçerliliği', left),
          meterWrap,
          el('p', { class: 'stat-note' }, 'Token yalnız bu sekmenin belleğinde durur; süresi dolmadan kendiliğinden yenilenir.')))),
    el('section', { class: 'card' }, el('div', { class: 'card-head' }, el('h3', {}, 'Gözlem')),
      el('div', { class: 'card-body' },
        row('chart', 'Grafana', el('a', { href: CONFIG.grafana, target: '_blank', rel: 'noopener noreferrer' }, CONFIG.grafana, ' ', icon('external', 'sm'))),
        row('book', 'Runbook\'lar', el('span', { class: 'code' }, 'docs/runbooks/')),
        el('p', { class: 'stat-note' }, 'Metrikler, alarmlar ve log\'lar gözlem profili açıksa görünür (docker compose --profile obs).'))));
  return () => clearInterval(timer);
}
