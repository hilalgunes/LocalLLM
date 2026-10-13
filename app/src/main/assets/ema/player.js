// Android integration: incremental text, incremental PCM playback, complete WAV for replay.
import { EMA, wavBlob } from './tts.js';
const BASE = 'https://huggingface.co/ozcancelik/ema-lightning-onnx/resolve/13c431db0356b2f7fafb1247cd823ec0d777c820/';
const $ = id => document.getElementById(id);
let enginePromise, answer = '', complete = false, enabled = false, consumed = 0;
let sequence = 0, running = false, stopped = false, audioUrl, context, nextTime = 0;
let chunks = [], sources = new Set();
function loadEngine() {
  return enginePromise ??= EMA.load('wasm', BASE, name => {
    if (!stopped) $('status').textContent = `Model yükleniyor: ${name}…`;
  }).catch(error => { enginePromise = null; throw error; });
}
function controls() {
  $('answer').disabled = running || !complete || !answer.trim();
  $('stop').disabled = stopped || (!running && !sources.size && !audioUrl);
}
function reset() {
  sequence++;
  for (const source of sources) { source.onended = null; source.stop(); }
  sources.clear(); nextTime = 0; consumed = 0; chunks = [];
  $('audio').pause(); $('audio').removeAttribute('src'); $('audio').load();
  if (audioUrl) URL.revokeObjectURL(audioUrl);
  audioUrl = null;
}
window.setAnswer = (text, autoRead = false, ready = true) => {
  if (!text.startsWith(answer) || (text === '' && answer !== '')) {
    reset(); stopped = false;
  }
  answer = text; complete = ready; enabled = autoRead;
  if (!text.trim()) $('status').textContent = 'Yanıt bekleniyor…';
  controls();
  if (enabled && !stopped) pump();
};
// Start at a sentence boundary or a short group of whole words; never read a partial token.
function nextText() {
  const rest = answer.slice(consumed);
  if (!rest.trim()) return '';
  const sentence = (complete ? /[.!?](?=\s|$)/ : /[.!?](?=\s)/).exec(rest);
  let end = sentence ? sentence.index + 1 : 0;
  if (!end && rest.length >= 32) end = rest.lastIndexOf(' ', 64);
  if (!end && complete) end = rest.length;
  if (end <= 0) return '';
  consumed += end;
  return rest.slice(0, end);
}
async function playChunk(chunk, request) {
  context ??= new AudioContext();
  await context.resume();
  if (request !== sequence) return;
  const buffer = context.createBuffer(1, chunk.length, 48000);
  buffer.copyToChannel(chunk, 0);
  const source = context.createBufferSource();
  source.buffer = buffer; source.connect(context.destination);
  sources.add(source);
  source.onended = () => { sources.delete(source); controls(); };
  const start = Math.max(context.currentTime + 0.02, nextTime);
  source.start(start); nextTime = start + buffer.duration;
}
function finishReplay() {
  if (!complete || !chunks.length || audioUrl) return;
  audioUrl = URL.createObjectURL(wavBlob(chunks)); $('audio').src = audioUrl;
  $('status').textContent = 'Ses çalıyor · Tekrar dinleme hazır';
}
async function pump() {
  if (running || stopped || !enabled) return;
  const text = nextText();
  if (!text) { finishReplay(); return; }
  const request = sequence;
  running = true; controls(); $('error').textContent = '';
  try {
    const engine = await loadEngine();
    let piece = text;
    while (piece && request === sequence) {
      $('status').textContent = 'Yanıt seslendiriliyor…';
      for await (const chunk of engine.stream(piece, { speed: 1, seed: 0 })) {
        if (request !== sequence) return;
        chunks.push(chunk);
        await playChunk(chunk, request);
        if (request === sequence) $('status').textContent = 'Ses çalıyor · Yanıt akışı sürüyor…';
        if (request !== sequence) return;
      }
      piece = nextText();
    }
    if (request === sequence) finishReplay();
  } catch (error) {
    if (request === sequence) {
      stopped = true;
      $('error').textContent = `Seslendirme başarısız: ${error.message}`;
      $('status').textContent = 'Tamamlanamadı.';
    }
  } finally {
    running = false; controls();
    if (request !== sequence && !stopped) pump();
  }
}
$('stop').onclick = () => { reset(); stopped = true; $('status').textContent = 'Durduruldu.'; controls(); };
$('answer').onclick = () => {
  reset(); stopped = false; enabled = true; complete = true; pump();
};
// Native replay controls must not overlap the streaming playback.
$('audio').onplay = () => {
  for (const source of sources) { source.onended = null; source.stop(); }
  sources.clear(); nextTime = 0;
};
window.addEventListener('pagehide', () => {
  stopped = true; reset(); context?.close();
  enginePromise?.then(engine => engine.release()).catch(() => {});
});
// Load once when voice is enabled, before the answer arrives.
loadEngine().catch(() => {});
