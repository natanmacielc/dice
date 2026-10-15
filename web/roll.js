// Página /r/{rollId}: acompanha o roll, anima o resultado e confere tudo no navegador.
import { getJson, verifyRoll, verifyTable } from './api.js';
import { criticalOf, formatBreakdown, playerColor } from './page-logic.js';
import { parseExpression } from './verifier/dice-v1.js';
import {
  $, DIE_COLORS, Tray, celebrate, countUp, replay, ring, runTableCheck, runVerificationBadge, setupDialogs, shareLink, sleep,
} from './ui.js';

const STATUS = {
  PENDING: 'Aguardando o drand',
  RESOLVED: 'Resolvido',
  EXPIRED: 'Atrasado: o drand ainda não respondeu',
};

// Cor de quem rolou: a mesma do histórico, a partir da impressão da identidade.
$('roll-player')?.style.setProperty('--p', playerColor($('roll-player').dataset.fingerprint));

const root = $('roll');
const rollId = root.dataset.rollId;
const { count, sides } = parseExpression(root.dataset.expression);
const sessionId = $('session-id').textContent.trim();
const tray = new Tray($('tray'), sides, count);
const stageRing = ring($('ring'));
document.documentElement.style.setProperty('--c', DIE_COLORS[sides]);

function setStatus(status) {
  $('status').textContent = STATUS[status] ?? status;
  $('status').dataset.status = status;
}

async function reveal(roll) {
  const { dice, total, modifier, round } = roll.result;
  setStatus('RESOLVED');
  stageRing.progress(1);
  stageRing.mode('done');
  $('countdown').textContent = `round #${round} do drand`;
  await tray.land(dice);
  celebrate($('stage'), $('banner'), criticalOf(roll.expression, dice));
  replay($('result'), 'is-in');
  countUp($('total'), total, Math.min(900, 300 + total * 8));
  $('dice').textContent = formatBreakdown(dice, modifier);
  runVerificationBadge($('verify-badge'), $('verify-dialog'), rollId, verifyRoll);
}

/** Anel e contagem até o round alvo; devolve a função que para a animação. */
function countdown(roll) {
  const target = Date.parse(roll.expectedAt);
  const start = Math.min(Date.now(), Date.parse(roll.requestedAt));
  const span = Math.max(1000, target - start);
  let running = true;
  const frame = () => {
    if (!running) return;
    const left = Math.ceil((target - Date.now()) / 1000);
    stageRing.progress((Date.now() - start) / span);
    if ($('status').dataset.status !== 'EXPIRED') {
      $('countdown').textContent = left > 0
        ? `O round #${roll.targetRound} sai em ${left} s. Esse número ainda não existe.`
        : `Buscando o round #${roll.targetRound} no drand…`;
    }
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
  return () => {
    running = false;
  };
}

async function follow() {
  let roll;
  try {
    roll = await getJson(`/api/rolls/${rollId}`);
  } catch (e) {
    $('countdown').textContent = e.message;
    if (e.status !== 404) setTimeout(follow, 3000);
    return;
  }
  if (roll.status === 'RESOLVED') {
    tray.tumble();
    await sleep(450);
    await reveal(roll);
    return;
  }

  setStatus(roll.status);
  $('total').textContent = '?';
  tray.tumble();
  stageRing.mode('waiting');
  const stop = countdown(roll);
  await sleep(Math.min(Math.max(0, Date.parse(roll.expectedAt) - Date.now()) + 300, 8000));
  for (;;) {
    try {
      roll = await getJson(`/api/rolls/${rollId}`);
      setStatus(roll.status);
      if (roll.status === 'RESOLVED') break;
      if (roll.status === 'EXPIRED') $('countdown').textContent = 'O drand está demorando. O pedido continua registrado e público.';
    } catch (e) {
      $('countdown').textContent = e.message;
      if (e.status === 404) {
        stop();
        return;
      }
    }
    await sleep(roll.status === 'EXPIRED' ? 3000 : 1000);
  }
  stop();
  await reveal(roll);
}

$('share-btn').addEventListener('click', () => shareLink(location.href, document.title));
$('verify-table').addEventListener('click', () => {
  runTableCheck({ button: $('verify-table'), chain: $('chain'), out: $('chain-result') }, sessionId, verifyTable);
});

setupDialogs();
follow();
