import { describeError, generateSeed } from './page-logic.js';

const SESSION_KEY = 'dice.sessionId';
const ID_FORMAT = /^[0-9a-hjkmnp-tv-z]{16}$/;
const $ = (id) => document.getElementById(id);

function storedSession() {
  try {
    return localStorage.getItem(SESSION_KEY);
  } catch {
    return null;
  }
}

function storeSession(id) {
  try {
    if (id) localStorage.setItem(SESSION_KEY, id);
    else localStorage.removeItem(SESSION_KEY);
  } catch {
    // sem localStorage (modo privado): a mesa vale só nesta aba
  }
  sessionId = id;
  renderSession();
}

let sessionId = storedSession();

function renderSession() {
  $('session-current').hidden = !sessionId;
  $('session-forms').hidden = !!sessionId;
  $('session-id').textContent = sessionId ?? '';
}

async function postJson(url, body) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify(body),
  });
  const json = await response.json().catch(() => null);
  if (!response.ok) throw new Error(describeError(json, response.status));
  return json;
}

$('session-new').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('session-error').textContent = '';
  const name = new FormData(event.target).get('name').trim();
  try {
    const session = await postJson('/sessions', name ? { name } : {});
    storeSession(session.sessionId);
  } catch (e) {
    $('session-error').textContent = e.message;
  }
});

$('session-join').addEventListener('submit', (event) => {
  event.preventDefault();
  const id = new FormData(event.target).get('sessionId').trim().toLowerCase();
  if (!ID_FORMAT.test(id)) {
    $('session-error').textContent = 'O ID da mesa tem 16 caracteres (letras e números).';
    return;
  }
  $('session-error').textContent = '';
  storeSession(id);
});

$('session-forget').addEventListener('click', () => storeSession(null));

$('seed-gen').addEventListener('click', () => {
  $('roll-form').elements.clientSeed.value = generateSeed();
});

$('chips').addEventListener('click', (event) => {
  const expr = event.target.dataset?.expr;
  if (expr) $('roll-form').elements.expression.value = expr;
});

$('roll-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('roll-error').textContent = '';
  if (!sessionId) {
    $('roll-error').textContent = 'Crie uma mesa ou use o ID de uma existente.';
    return;
  }
  const form = new FormData(event.target);
  $('roll-submit').disabled = true;
  try {
    const receipt = await postJson(`/sessions/${encodeURIComponent(sessionId)}/rolls`, {
      clientSeed: form.get('clientSeed'),
      expression: form.get('expression'),
    });
    window.location.href = receipt.links.page;
  } catch (e) {
    $('roll-error').textContent = e.message;
    $('roll-submit').disabled = false;
  }
});

if (!$('roll-form').elements.clientSeed.value) $('roll-form').elements.clientSeed.value = generateSeed();
renderSession();
