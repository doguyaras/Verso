// "Yapay zekâ sağlayıcıları" (ADR-0056): the rules of the model gateway screen as pure functions, so they are tested
// without a browser. The provider types and their form fields, the request a form makes (the key only when typed, never
// read back), what a connection's locality and test result mean in plain Turkish, the task names of the routes, and the
// usage summary of the value dashboard (never per user).

/** Provider types in menu order: label, the fields the form shows, whether a key is needed, where data goes. */
export const PROVIDER_TYPES = Object.freeze({
  OPENAI: { label: 'OpenAI', fields: ['baseUrl'], key: 'required', cloud: true,
    hint: 'Adres boşsa https://api.openai.com/v1 kullanılır.' },
  AZURE_OPENAI: { label: 'Azure OpenAI', fields: ['baseUrl', 'deployment'], key: 'required', cloud: true,
    hint: 'Kaynak adresi (https://<kaynak>.openai.azure.com) ve dağıtım adı. Veri bölgesi Azure dağıtım türüne bağlıdır.' },
  ANTHROPIC: { label: 'Anthropic (Claude)', fields: ['baseUrl'], key: 'required', cloud: true,
    hint: 'Adres boşsa https://api.anthropic.com kullanılır.' },
  BEDROCK: { label: 'AWS Bedrock', fields: ['region', 'baseUrl'], key: 'required', cloud: true,
    hint: 'Bölge (ör. eu-central-1). Anahtar biçimi: <erişim anahtarı kimliği>:<gizli erişim anahtarı>.' },
  GOOGLE_GENAI: { label: 'Google Gemini / Vertex AI', fields: ['project', 'region', 'baseUrl'], key: 'required', cloud: true,
    hint: 'Proje boşsa Gemini API anahtarı; proje ve konum verilirse Vertex AI ve servis hesabı JSON anahtarı.' },
  MISTRAL: { label: 'Mistral AI', fields: ['baseUrl'], key: 'required', cloud: true,
    hint: 'Adres boşsa https://api.mistral.ai/v1 kullanılır.' },
  OLLAMA: { label: 'Ollama (iç ağ)', fields: ['baseUrl'], key: 'none', cloud: false,
    hint: 'İç ağdaki Ollama adresi, ör. http://ollama:11434. Anahtar kullanılmaz.' },
  OPENAI_COMPATIBLE: { label: 'OpenAI uyumlu (vLLM, llama.cpp, ağ geçidi)', fields: ['baseUrl'], key: 'optional', cloud: false,
    hint: 'OpenAI uyumlu sunucunun adresi, ör. http://llama:8080/v1. İç ağda değilse dış bağlantı sayılır.' },
});

export const FIELD_LABELS = Object.freeze({
  baseUrl: 'Adres (base URL)', region: 'Bölge / konum', deployment: 'Dağıtım adı', project: 'Proje (Vertex AI)',
});

/** Route tasks; embeddings are listed but pinned to the local model (re-indexing, llm-rules 6.2). */
export const TASKS = Object.freeze([
  { id: 'ANSWER', label: 'Cevap' },
  { id: 'FOLLOW_UP', label: 'Takip sorusu yeniden yazımı' },
  { id: 'DECOMPOSITION', label: 'Çok adımlı soru ayrıştırma' },
  { id: 'SKILL_CONDENSE', label: 'Prosedür adımı özetleme' },
  { id: 'SKILL_RUN', label: 'Beceri çalıştırma (Beceriler)' },
]);

export const EMBEDDING_NOTE = 'Embedding her zaman yerel modeldedir: değiştirmek bütün belgelerin yeniden dizinlenmesi '
  + 'demektir, bu yüzden bu ekrandan değiştirilmez.';

/** The types local mode accepts (the server refuses the others with 409 11091 anyway). */
export function typesFor(mode) {
  return Object.keys(PROVIDER_TYPES).filter((type) => mode === 'cloud' || !PROVIDER_TYPES[type].cloud);
}

const trimmed = (value) => (typeof value === 'string' ? value.trim() : '');

/** "a, b\nc" → ['a', 'b', 'c'] (at most 20, no duplicates). */
export function parseModels(text) {
  return [...new Set(trimmed(text).split(/[\s,]+/).filter(Boolean))].slice(0, 20);
}

/**
 * The JSON body of a create or change. Only the type's own fields are sent; the key only when one was typed (an empty
 * key field keeps the stored key on a change). The returned object is sent once and not kept.
 */
export function connectionRequest(form) {
  const type = PROVIDER_TYPES[form.type] ? form.type : 'OPENAI_COMPATIBLE';
  const body = { name: trimmed(form.name), type, models: parseModels(form.models), enabled: form.enabled !== false };
  for (const field of PROVIDER_TYPES[type].fields) {
    const value = trimmed(form[field]);
    if (value) body[field] = value;
  }
  const key = typeof form.apiKey === 'string' ? form.apiKey.trim() : '';
  if (key && PROVIDER_TYPES[type].key !== 'none') body.apiKey = key;
  return body;
}

/** Client-side checks that save a round trip; the server checks everything again. */
export function formProblems(form, { creating = true } = {}) {
  const problems = {};
  const type = PROVIDER_TYPES[form.type];
  if (!type) problems.type = 'Bir sağlayıcı türü seçin.';
  if (!trimmed(form.name)) problems.name = 'Bir ad yazın.';
  if (!parseModels(form.models).length) problems.models = 'En az bir model adı yazın.';
  if (type?.key === 'required' && creating && !trimmed(form.apiKey)) problems.apiKey = 'Bu sağlayıcı bir API anahtarı ister.';
  if (type && type.fields.includes('baseUrl') && ['AZURE_OPENAI', 'OLLAMA', 'OPENAI_COMPATIBLE'].includes(form.type)
      && !trimmed(form.baseUrl)) problems.baseUrl = 'Bu tür bir adres ister.';
  if (form.type === 'AZURE_OPENAI' && !trimmed(form.deployment)) problems.deployment = 'Dağıtım adını yazın.';
  if (form.type === 'BEDROCK' && !trimmed(form.region)) problems.region = 'Bölgeyi yazın.';
  return problems;
}

/** Where a connection sends data, for the list and the trust screen. */
export function localityText(connection) {
  return connection?.locality === 'LOCAL'
    ? 'İç ağ: veri kurumdan çıkmaz'
    : `Dış bağlantı: soru ve seçilen pasajlar ${connection?.egressHost ?? 'sağlayıcıya'} adresine gider`;
}

const STATUS_TEXT = Object.freeze({
  OK: { label: 'Çalışıyor', tone: 'good' },
  AUTH_FAILED: { label: 'Anahtar reddedildi', tone: 'bad' },
  UNREACHABLE: { label: 'Ulaşılamıyor', tone: 'bad' },
  MODEL_NOT_FOUND: { label: 'Model bulunamadı', tone: 'bad' },
  RATE_LIMITED: { label: 'Hız sınırında', tone: 'warn' },
  FAILED: { label: 'Başarısız', tone: 'bad' },
});

export function statusText(status) {
  return STATUS_TEXT[status] ?? { label: 'Denenmedi', tone: 'neutral' };
}

/** The key as the panel may show it: whether one is stored and its last four characters. Never more. */
export function keyText(connection) {
  if (!connection?.keyStored) return PROVIDER_TYPES[connection?.type]?.key === 'none' ? 'Anahtar kullanılmaz' : 'Anahtar yok';
  return `Anahtar kayıtlı (${connection.keyHint ?? '…'}, sürüm ${connection.keyVersion ?? 1})`;
}

/** The route choices of a task: every enabled connection's models, plus the built-in model. */
export function routeOptions(connections, builtInModel) {
  const options = [{ value: '', label: `Yerleşik model (${builtInModel ?? 'yapılandırma'})` }];
  for (const c of connections ?? []) {
    if (!c.enabled) continue;
    for (const model of c.models ?? []) options.push({ value: `${c.id}/${model}`, label: `${c.name} · ${model}` });
  }
  return options;
}

/** Route selects → API entries; empty selects are skipped; '' as the first step means the built-in model. */
export function routeEntries(values, builtInModel) {
  const entries = [];
  values.forEach((value, index) => {
    if (value === '' && index === 0) entries.push({ connectionId: null, model: builtInModel });
    else if (typeof value === 'string' && value.includes('/')) {
      const at = value.indexOf('/');
      entries.push({ connectionId: value.slice(0, at), model: value.slice(at + 1) });
    }
  });
  return entries;
}

/** The value dashboard's model card: totals, the top rows and the budgets; teams only as the server sends them. */
// ADR-0059: the masked-value types the server reports, in the words the admin reads.
const MASKED_LABELS = { TCKN: 'TCKN', VKN: 'VKN', IBAN: 'IBAN', CARD: 'kart', EMAIL: 'e-posta', PHONE: 'telefon', PLATE: 'plaka' };

/** "Bu ay N değer maskelendi (TCKN 3, IBAN 1)": counts only; null when the server sent none (older server). */
export function maskedNote(usage) {
  if (!usage || typeof usage.maskedValues !== 'number') return null;
  const parts = Object.entries(usage.masked ?? {}).filter(([, n]) => typeof n === 'number' && n > 0)
    .sort((a, b) => b[1] - a[1]).map(([type, n]) => `${MASKED_LABELS[type] ?? type} ${n}`);
  return `Bu ay bulut çağrılarında ${usage.maskedValues} değer maskelendi${parts.length ? ` (${parts.join(', ')})` : ''}.`;
}

export function usageSummary(usage) {
  if (!usage || typeof usage !== 'object') return null;
  const money = (v) => (typeof v === 'number' ? v.toLocaleString('tr-TR', { maximumFractionDigits: 2 }) : String(v ?? '0'));
  return {
    month: usage.month,
    maskedNote: maskedNote(usage),
    totals: [
      { label: 'Model çağrısı', value: String(usage.requests ?? 0) },
      { label: 'Girdi token', value: String(usage.promptTokens ?? 0) },
      { label: 'Çıktı token', value: String(usage.completionTokens ?? 0) },
      { label: 'Maliyet', value: money(usage.cost) },
    ],
    rows: (usage.rows ?? []).slice(0, 10).map((r) => ({
      label: `${r.connectionName ?? (r.connectionId ? 'Silinmiş bağlantı' : 'Yerleşik model')} · ${r.model}`,
      task: TASKS.find((t) => t.id === r.task)?.label ?? r.task,
      text: `${r.requests} çağrı, ${r.promptTokens + r.completionTokens} token, ${money(r.cost)}${r.failures ? `, ${r.failures} hata` : ''}`,
    })),
    teams: (usage.teams ?? []).map((t) => `${t.team}: ${t.requests} çağrı, ${money(t.cost)}`),
    teamNote: usage.hiddenTeams
      ? `${usage.hiddenTeams} ekip ${usage.minTeamUsers} kişiden az kullanıcı içerdiği için gösterilmiyor (kişi bazında rapor yok).`
      : 'Raporlar kişi bazında değildir; ekipler en az ' + (usage.minTeamUsers ?? 5) + ' kullanıcıyla gösterilir.',
    budgets: (usage.budgets ?? []).map((b) => ({
      label: `${b.scope === 'TEAM' ? 'Ekip' : 'Bağlantı'} ${b.scopeKey}`,
      text: `${money(b.spent)} / ${money(b.hardLimit)}`,
      tone: b.state === 'HARD' ? 'bad' : b.state === 'SOFT' ? 'warn' : 'good',
      state: b.state === 'HARD' ? 'Engellendi' : b.state === 'SOFT' ? 'Uyarı sınırında' : 'Normal',
    })),
  };
}
