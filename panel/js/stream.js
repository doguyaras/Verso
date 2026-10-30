// The streamed answer (ADR-0035) without the DOM: a server-sent events parser for a fetch body (EventSource cannot
// send the Authorization header), the stage texts, the provisional text shown while the model writes, and the rules
// for falling back to the JSON endpoint. The text of a delta is unvalidated model output: it is shown as plain text,
// its [n] markers are not shown, and it is replaced by the validated answer of the "citations" event. Nothing here is
// logged (llm-rules 2.1).

/** The events the server sends, in order: stage*, delta*, citations, done; or error last. */
export const EVENTS = Object.freeze(['stage', 'delta', 'citations', 'done', 'error']);

/**
 * An SSE parser (WHATWG "event stream interpretation") for text that arrives in arbitrary chunks: a line or a
 * multi-byte character may be split anywhere, lines end in LF, CRLF or CR, several "data:" lines join with a line
 * feed, comment lines (":keep-alive") and unknown fields are ignored, and an event is dispatched at a blank line.
 * onEvent gets { event, data } (data as text).
 */
export function createSseParser(onEvent) {
  let buffer = '';
  let event = '';
  let data = [];
  let pendingCr = false;
  const line = (text) => {
    if (text === '') {
      if (data.length) onEvent({ event: event || 'message', data: data.join('\n') });
      event = '';
      data = [];
      return;
    }
    if (text.startsWith(':')) return;
    const colon = text.indexOf(':');
    const field = colon < 0 ? text : text.slice(0, colon);
    let value = colon < 0 ? '' : text.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
  };
  return {
    push(chunk) {
      let text = buffer + chunk;
      buffer = '';
      // A CR at the end of the last chunk and an LF at the start of this one are one line end.
      if (pendingCr && text.startsWith('\n')) text = text.slice(1);
      pendingCr = false;
      let start = 0;
      for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (c !== '\n' && c !== '\r') continue;
        line(text.slice(start, i));
        if (c === '\r') {
          if (i + 1 < text.length) {
            if (text[i + 1] === '\n') i++;
          } else {
            pendingCr = true;
          }
        }
        start = i + 1;
      }
      buffer = text.slice(start);
    },
    /** The end of the body: an unterminated last event is dropped, as the specification says. */
    end() {
      buffer = '';
      event = '';
      data = [];
    },
  };
}

/** The screen text of a stage: only what the server really did (ADR-0017: no invented steps). */
export function stageText(stage) {
  switch (stage?.stage) {
    case 'REWRITING':
      // ADR-0046: only sent when a follow-up is really rewritten with the conversation.
      return 'Soru bağlamla yeniden yazılıyor';
    case 'RETRIEVING':
      return 'Belgelerinizde aranıyor';
    case 'MEMORY_FOUND':
      // ADR-0049: an expert-verified answer card matched; no passages are searched and no model writes.
      return 'Onaylı bilgi bulundu';
    case 'DECOMPOSING':
      // ADR-0055: a comparison or multi-part question is split into at most three sub-questions.
      return 'Soru alt sorulara ayrılıyor';
    case 'SUB_QUESTION':
      return Number.isInteger(stage.step) && Number.isInteger(stage.steps)
        ? `Alt soru ${stage.step}/${stage.steps} aranıyor` : 'Alt soru aranıyor';
    case 'PASSAGES_FOUND':
      return stage.relevant > 0 ? `${stage.relevant} ilgili pasaj bulundu` : 'İlgili pasaj bulunamadı';
    case 'WRITING':
      return 'Cevap yazılıyor';
    default:
      return 'Cevap hazırlanıyor';
  }
}

// [2], [1, 3], [1-3] and a marker still being written at the end ("[1", "[1,").
const MARKERS = /\[\s*\d[\d\s,;–-]*\]/g;
const OPEN_MARKER = /\[\s*[\d\s,;–-]*$/;

/**
 * The model text while it is written: markers are left out (they are validated only at the end, so a number must not
 * look like a source yet) and so is a marker cut at the end of the text so far.
 */
export function provisionalText(text) {
  return String(text).replace(MARKERS, '').replace(OPEN_MARKER, '').replace(/[ \t]+([.,;:!?])/g, '$1').replace(/ {2,}/g, ' ');
}

/**
 * The answer as POST /v1/questions returns it, from the last two events: the validated text and citations of
 * "citations", the outcome, mode, model, history ids and the follow-up's search query (ADR-0046) of "done".
 */
export function resultOf(citations, done) {
  return { answer: citations.answer, citations: citations.citations ?? [], outcome: done.outcome, found: Boolean(done.found),
    mode: done.mode ?? null, model: done.model ?? null, conversationId: done.conversationId ?? null, turnId: done.turnId ?? null,
    contextQuery: done.contextQuery ?? null, unverified: citations.unverified ?? null, memory: done.memory ?? null,
    // ADR-0053: an approved procedure that matched the question, shown above the answer.
    procedure: done.procedure ?? null,
    // ADR-0055: how a multi-step answer was researched ("Nasıl araştırıldı").
    reasoning: done.reasoning ?? null };
}

/**
 * Thrown when the connection was cut after events had arrived: the server cancels the model call at its next
 * keep-alive and stores nothing, so the question can be asked again. Not a StreamUnavailable: the stream works, and a
 * JSON fallback would ask the same question a second time.
 */
export class StreamCut extends Error {
  constructor() {
    super('stream ended early');
    this.name = 'StreamCut';
  }
}

/** Codes an "error" event may carry for a service that is down or busy; everything else of a module is a refusal. */
const SERVICE_CODES = new Set([10030, 10031, 11001, 11002, 90103, 99997, 99998]);

/**
 * The HTTP status an "error" event stands for. The event carries only the API code (no status), so the code family
 * decides until the server sends the status itself: a `status` in the event wins; a Retry-After means 503; the
 * security block 90100-90103 keeps its own statuses; the common validation block 90000-90099 and a module's refusals
 * are 400; the service codes above and the system block are 5xx. The screen (answer.js errorState) decides by code
 * first, so the status only separates "down, try again" from "refused".
 */
export function streamErrorStatus(failure) {
  if (Number.isInteger(failure?.status)) return failure.status;
  if (failure?.retryAfter) return 503;
  const code = Number(failure?.code);
  if (code === 90100) return 401;
  if (code === 90101) return 403;
  if (code === 90102) return 429;
  if (SERVICE_CODES.has(code) || code >= 99997) return code === 99999 ? 500 : 503;
  if (code >= 10000 && code < 99997) return 400;
  return 500;
}

/** Thrown when the stream cannot be used at all (an old proxy or server): the JSON endpoint is asked instead. */
export class StreamUnavailable extends Error {
  constructor(reason) {
    super('stream unavailable');
    this.name = 'StreamUnavailable';
    this.reason = reason;
  }
}

/**
 * Whether a response of the stream endpoint means "no stream here" rather than an answer: a success that is not an
 * event stream (a proxy or an older server answered), or the endpoint itself missing (405, 415, 501, or a 404 that is
 * not one of the API's own "not found" codes, such as a conversation that is gone).
 */
export function noStream(status, contentType, code) {
  if (status >= 200 && status < 300) return !String(contentType ?? '').toLowerCase().startsWith('text/event-stream');
  if (status === 405 || status === 415 || status === 501) return true;
  return status === 404 && (code === undefined || code === null || code === 90010);
}

/**
 * Reads an event stream body to its end, chunk by chunk, and hands every event to onEvent with its data parsed as
 * JSON (an event that is not JSON is skipped). Returns the number of events read.
 */
export async function readEvents(body, onEvent) {
  let count = 0;
  const parser = createSseParser(({ event, data }) => {
    let value;
    try {
      value = JSON.parse(data);
    } catch {
      return;
    }
    count++;
    onEvent(event, value);
  });
  const reader = body.getReader();
  const decoder = new TextDecoder();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.push(decoder.decode(value, { stream: true }));
    }
    parser.push(decoder.decode());
    parser.end();
  } finally {
    reader.releaseLock?.();
  }
  return count;
}
