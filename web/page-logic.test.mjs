import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  buildExpression, contentSha256, criticalOf, formatBreakdown, describeError, drandRoundUrl, explainRoll, formatModifier, generateSeed, historyFromLog,
  historyLoadError, initialOf, mergeTables, playerColor, tableLabel, tablesCacheFresh, tablesPage, upsertTable,
  parseRollInput, relativeTime, requestRoll, runChecks, secondsUntil, shellQuote, summarizeLog,
} from './page-logic.js';
import { timeOf, verifyProof } from './verifier/chain-v1.js';
import { fingerprint, verifyProof as verifyProofV2 } from './verifier/chain-v2.js';
import { roll } from './verifier/dice-v1.js';

const vectors = JSON.parse(readFileSync(new URL('../spec/test-vectors.json', import.meta.url), 'utf8'));
const validProof = vectors.proofVectors.find((v) => v.id === 'proof-valid-adjacent').proof;

test('mensagens de erro em português pelo código da API', () => {
  assert.equal(describeError({ error: 'UNSUPPORTED_DIE' }, 400), 'Dado não suportado. Use d4, d6, d8, d10, d12, d20 ou d100.');
  assert.equal(describeError({ error: 'X', message: 'm' }, 500), 'm');
  assert.equal(describeError(null, 502), 'Erro inesperado (HTTP 502).');
  assert.equal(describeError({ error: 'SERVER_BUSY' }, 503), 'O servidor está no limite agora. Tente de novo em instantes.');
  assert.match(describeError(null, 429), /Muitos pedidos/);
});

test('hash do corpo para o x-amz-content-sha256 (bytes UTF-8)', async () => {
  assert.equal(await contentSha256(''), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  assert.equal(await contentSha256('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  assert.equal(await contentSha256('é'), await contentSha256('\u00e9'));
  assert.notEqual(await contentSha256('é'), await contentSha256('e'));
});

test('formatação', () => {
  assert.equal(formatBreakdown([17], 0), '');
  assert.equal(formatBreakdown([4, 2], 0), '4 + 2');
  assert.equal(formatBreakdown([4, 2], -1), '4 + 2 − 1');
  assert.equal(formatBreakdown([12], 5), '12 + 5');
  assert.equal(formatModifier(3), '+3');
  assert.equal(formatModifier(-2), '-2');
  assert.equal(formatModifier(0), '');
  assert.equal(secondsUntil('2026-01-01T00:00:05.000Z', Date.parse('2026-01-01T00:00:00.500Z')), 5);
  assert.equal(secondsUntil('2026-01-01T00:00:05.000Z', Date.parse('2026-01-01T00:00:09Z')), 0);
});

test('histórico que não carrega vira mensagem, não lista vazia (regressão do log acima de 8 KB)', () => {
  assert.equal(historyLoadError(Object.assign(new Error('x'), { name: 'TimeoutError' })), 'O servidor demorou demais para mandar o histórico.');
  assert.equal(historyLoadError(Object.assign(new Error('Mesa ocupada. Tente de novo.'), { status: 503 })),
    'Não consegui carregar o histórico: Mesa ocupada. Tente de novo.');
  assert.match(historyLoadError(new TypeError('Failed to fetch')), /Confira a conexão/);
});

test('rótulo de mesa, páginas sem repetição, cor e inicial do jogador', () => {
  assert.equal(tableLabel({ name: 'Mesa do Dragão', sessionId: 'kq1t9m3x7c2a8z5r' }), 'Mesa do Dragão');
  assert.equal(tableLabel({ name: '  ', sessionId: 'kq1t9m3x7c2a8z5r' }), 'Mesa kq1t');
  assert.equal(tableLabel({ sessionId: 'kq1t9m3x7c2a8z5r' }), 'Mesa kq1t');
  const a = { sessionId: 'a' };
  const b = { sessionId: 'b' };
  assert.deepEqual(mergeTables([a, b], [b, { sessionId: 'c' }]).map((t) => t.sessionId), ['a', 'b', 'c']);
  assert.equal(playerColor('0000-0000'), 'hsl(0 80% 68%)');
  assert.equal(playerColor('8000-1234'), 'hsl(180 80% 68%)');
  assert.equal(playerColor('ef2d-4477'), playerColor('ef2d-0000'), 'só os 2 primeiros bytes contam');
  assert.equal(playerColor('???'), 'hsl(0 0% 70%)');
  assert.equal(initialOf('ana'), 'A');
  assert.equal(initialOf('  élio'), 'É');
  assert.equal(initialOf('🪓 Thorin'), '🪓');
  assert.equal(initialOf(''), '?');
  assert.equal(initialOf(null), '?');
});

test('suas mesas: mesa vai para o topo sem repetir e mantém o que já se sabia dela', () => {
  const items = [{ sessionId: 'a', name: 'A', lastActiveAt: '1' }, { sessionId: 'b', name: 'B', lastActiveAt: '0' }];
  assert.deepEqual(upsertTable(items, { sessionId: 'b', lastActiveAt: '2' }),
    [{ sessionId: 'b', name: 'B', lastActiveAt: '2' }, { sessionId: 'a', name: 'A', lastActiveAt: '1' }]);
  assert.deepEqual(upsertTable(items, { sessionId: 'c' }).map((t) => t.sessionId), ['c', 'a', 'b']);
  assert.deepEqual(upsertTable([], { sessionId: 'c' }), [{ sessionId: 'c' }]);
  assert.equal(items[0].sessionId, 'a', 'não muda a lista original');
});

test('suas mesas: página da tela sobre o que já veio do servidor', () => {
  const items = Array.from({ length: 14 }, (_, i) => ({ sessionId: String(i) }));
  const first = tablesPage(items, 0, 6, false);
  assert.deepEqual(first.slice.map((t) => t.sessionId), ['0', '1', '2', '3', '4', '5']);
  assert.deepEqual([first.page, first.from, first.to, first.hasPrev, first.hasNext, first.needsFetch], [0, 0, 6, false, true, false]);
  const last = tablesPage(items, 2, 6, false);
  assert.deepEqual([last.from, last.to, last.hasPrev, last.hasNext, last.needsFetch], [12, 14, true, false, false]);
  // Página incompleta com mais no servidor: busca a próxima página do cursor.
  const partial = tablesPage(items, 2, 6, true);
  assert.deepEqual([partial.hasNext, partial.needsFetch], [true, true]);
  // Removeu mesas e a página ficou além do fim: volta para a última.
  assert.equal(tablesPage(items.slice(0, 5), 2, 6, false).page, 0);
  assert.deepEqual(tablesPage([], 0, 6, false), { slice: [], page: 0, from: 0, to: 0, hasPrev: false, hasNext: false, needsFetch: false });
  assert.equal(tablesPage([], 0, 6, true).needsFetch, true);
});

test('suas mesas: cache vale só para a mesma identidade e dentro do prazo', () => {
  const cache = { identityKey: 'k', fetchedAt: 1_000, items: [], next: null };
  assert.equal(tablesCacheFresh(cache, 'k', 1_000 + 599_999, 600_000), true);
  assert.equal(tablesCacheFresh(cache, 'k', 1_000 + 600_000, 600_000), false);
  assert.equal(tablesCacheFresh(cache, 'outra', 1_500, 600_000), false);
  assert.equal(tablesCacheFresh(cache, 'k', 500, 600_000), false, 'relógio voltou: não confia');
  assert.equal(tablesCacheFresh(null, 'k', 1_500, 600_000), false);
  assert.equal(tablesCacheFresh({ identityKey: 'k', fetchedAt: 1_000 }, 'k', 1_500, 600_000), false);
  assert.equal(tablesCacheFresh(cache, undefined, 1_500, 600_000), false);
});

test('seed gerada tem 16 caracteres base32 Crockford', () => {
  assert.match(generateSeed(), /^[0-9a-hjkmnp-tv-z]{16}$/);
  assert.equal(generateSeed((n) => new Uint8Array(n)), '0'.repeat(16));
});

const fetchOk = async () => ({ beacon: { ...validProof.beacon }, endpoint: 'https://api.drand.sh' });
const validProofV2 = vectors.proofVectorsV2.find((v) => v.id === 'proof-v2-valid-linked-phone').proof;
const fetchOkV2 = async () => ({ beacon: { ...validProofV2.beacon }, endpoint: 'https://api.drand.sh' });

test('checklist todo verde para uma prova válida', async () => {
  const checks = await runChecks({ proof: validProof, verifyProof, fetchRound: fetchOk, verifyBls: async () => true, timeOf });
  assert.deepEqual(checks.map((c) => [c.id, c.ok]), [['proof', true], ['drand', true], ['bls', true], ['future', true]]);
});

test('checklist acusa drand divergente, drand fora, BLS inválido e prova adulterada', async () => {
  const other = async () => ({ beacon: { ...validProof.beacon, randomness: '0'.repeat(64) }, endpoint: 'https://api2.drand.sh' });
  let checks = await runChecks({ proof: validProof, verifyProof, fetchRound: other, verifyBls: async () => false, timeOf });
  assert.deepEqual(checks.map((c) => c.ok), [true, false, false, true]);

  const down = async () => { throw new Error('round indisponível'); };
  checks = await runChecks({ proof: validProof, verifyProof, fetchRound: down, verifyBls: async () => true, timeOf });
  assert.equal(checks[1].ok, false);
  assert.equal(checks[1].detail, 'round indisponível');

  const tampered = vectors.proofVectors.find((v) => v.id === 'proof-summary-total').proof;
  checks = await runChecks({ proof: tampered, verifyProof, fetchRound: fetchOk, verifyBls: async () => true, timeOf });
  assert.equal(checks[0].ok, false);
  assert.match(checks[0].detail, /^PROOF_SUMMARY_MISMATCH/);
});

test('chain-v2: checklist mostra quem assinou e acusa assinatura inválida', async () => {
  const deps = { verifyProof: verifyProofV2, fetchRound: fetchOkV2, verifyBls: async () => true, timeOf, fingerprint };
  let checks = await runChecks({ proof: validProofV2, ...deps });
  assert.deepEqual(checks.map((c) => [c.id, c.ok]), [['proof', true], ['player', true], ['drand', true], ['bls', true], ['future', true]]);
  assert.equal(checks[1].label, 'Pedido assinado por Ana');
  assert.match(checks[1].detail, new RegExp(await fingerprint(validProofV2.roll.player.identityKey)));
  assert.match(checks[1].detail, /aparelho vinculado/);

  const forged = vectors.proofVectorsV2.find((v) => v.id === 'proof-v2-signature-flipped').proof;
  checks = await runChecks({ proof: forged, ...deps });
  assert.deepEqual(checks.slice(0, 2).map((c) => c.ok), [false, false]);
  assert.match(checks[0].detail, /^CHAIN_PLAYER_SIGNATURE/);
});

test('histórico traz o jogador de cada pedido da chain-v2', () => {
  const entries = vectors.logVectorsV2.find((v) => v.id === 'log-v2-valid-full').entries;
  const history = historyFromLog(entries, Date.parse('2030-01-01T00:00:00Z'));
  assert.deepEqual(history.map((h) => h.player.name), ['Ana', 'Ana', 'Thorin 🪓', 'Ana']);
  // Computador, celular e tablet da Ana: aparelhos diferentes, a mesma identidade.
  const ana = history.filter((h) => h.player.name === 'Ana');
  assert.equal(new Set(ana.map((h) => h.player.identityKey)).size, 1);
  assert.equal(new Set(ana.map((h) => h.player.publicKey)).size, 3);
  const v1 = historyFromLog(vectors.logVectors.find((v) => v.id === 'log-valid-full').entries);
  assert.ok(v1.every((h) => h.player === null));
});

test('resumo do log separa pendentes', () => {
  const entries = vectors.logVectors.find((v) => v.id === 'log-valid-full').entries;
  assert.deepEqual(summarizeLog(entries), { entries: 9, requests: 3, resolved: 3, pending: [] });
  assert.deepEqual(summarizeLog(entries.slice(0, 2)).pending.length, 2);
});

test('expressão montada pelo seletor sai canônica e respeita os limites', () => {
  assert.equal(buildExpression({ count: 1, sides: 20 }), '1d20');
  assert.equal(buildExpression({ count: 2, sides: 6, modifier: 3 }), '2d6+3');
  assert.equal(buildExpression({ count: 4, sides: 100, modifier: -100 }), '4d100-100');
  assert.throws(() => buildExpression({ count: 21, sides: 6 }), /DICE_COUNT_OUT_OF_RANGE/);
  assert.throws(() => buildExpression({ count: 1, sides: 7 }), /UNSUPPORTED_DIE/);
  assert.throws(() => buildExpression({ count: 1, sides: 6, modifier: 101 }), /MODIFIER_OUT_OF_RANGE/);
});

test('rollId a partir de ID ou de link', () => {
  assert.equal(parseRollInput('  0123456789ABCDEF '), '0123456789abcdef');
  assert.equal(parseRollInput('https://dices.nimbos.solutions/r/0123456789abcdef'), '0123456789abcdef');
  assert.equal(parseRollInput('/r/0123456789abcdef?x=1'), '0123456789abcdef');
  assert.equal(parseRollInput('0123456789abcdeu'), null); // u não é Crockford
  assert.equal(parseRollInput('/r/0123456789abcdef0'), null);
  assert.equal(parseRollInput(''), null);
});

test('histórico vem do log, do mais novo para o mais antigo, com pendentes e atrasados', () => {
  const entries = vectors.logVectors.find((v) => v.id === 'log-valid-full').entries;
  const history = historyFromLog(entries, Date.parse('2030-01-01T00:00:00Z'));
  assert.deepEqual(history.map((h) => h.nonce), [3, 2, 1]);
  assert.ok(history.every((h) => h.status === 'RESOLVED' && Array.isArray(h.dice) && Number.isInteger(h.total)));

  const requestOnly = entries.filter((e) => e.type === 'REQUEST').slice(0, 1);
  const target = timeOf(requestOnly[0].payload.targetRound);
  assert.equal(historyFromLog(requestOnly, target + 59_000)[0].status, 'PENDING');
  assert.equal(historyFromLog(requestOnly, target + 61_000)[0].status, 'EXPIRED');
});

test('crítico e falha crítica só em d20', () => {
  assert.equal(criticalOf('1d20', [20]), 'crit');
  assert.equal(criticalOf('2d20+5', [3, 20]), 'crit');
  assert.equal(criticalOf('1d20', [1]), 'fumble');
  assert.equal(criticalOf('2d20', [1, 7]), null);
  assert.equal(criticalOf('1d6', [6]), null);
  assert.equal(criticalOf('1d20', null), null);
});

test('tempo relativo e aspas de shell', () => {
  const now = Date.parse('2026-01-01T12:00:00Z');
  assert.equal(relativeTime('2026-01-01T11:59:50Z', now), 'agora');
  assert.equal(relativeTime('2026-01-01T11:57:00Z', now), 'há 3 min');
  assert.equal(relativeTime('2026-01-01T10:00:00Z', now), 'há 2 h');
  assert.equal(relativeTime('2025-12-31T12:00:00Z', now), 'há 1 dia');
  assert.equal(shellQuote("it's"), "'it'\\''s'");
  assert.equal(drandRoundUrl(7), 'https://api.drand.sh/52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971/public/7');
});

for (const v of vectors.rollVectors) {
  test(`passo a passo bate com roll(): ${v.id}`, async () => {
    const actual = await roll(v);
    const e = await explainRoll(v);
    assert.deepEqual(e.dice, actual.dice);
    assert.equal(e.total, actual.total);
    assert.equal(e.words.length, actual.wordsConsumed);
    assert.deepEqual(e.words.filter((w) => w.rejected).map((w) => ({ wordIndex: w.index, value: w.value })), actual.rejections);
    assert.equal(e.blocks[0].input, `${v.randomness}|${v.clientSeed}|${v.nonce}|0`);
    for (const w of e.words) assert.equal(parseInt(w.hex, 16), w.value);
  });
}

test('mesa salva que não existe mais: cria outra e repete o roll (regressão do 404)', async () => {
  const notFound = Object.assign(new Error('Mesa ou rolagem não encontrada.'), { status: 404 });
  const calls = [];
  const createTable = async () => { calls.push('create'); return 'mesa-nova'; };
  const postRoll = async (id) => {
    calls.push(`roll:${id}`);
    if (id === 'mesa-velha') throw notFound;
    return { rollId: 'r1' };
  };
  assert.deepEqual(await requestRoll({ sessionId: 'mesa-velha', createTable, postRoll }),
    { receipt: { rollId: 'r1' }, sessionId: 'mesa-nova', recreated: true, reason: 'missing' });
  assert.deepEqual(calls, ['roll:mesa-velha', 'create', 'roll:mesa-nova']);

  // Mesa chain-v1: só leitura, a próxima rolagem vai para uma mesa nova.
  calls.length = 0;
  const readOnly = Object.assign(new Error('antiga'), { status: 409, code: 'TABLE_READ_ONLY' });
  const postRollV1 = async (id) => {
    calls.push(`roll:${id}`);
    if (id === 'mesa-v1') throw readOnly;
    return { rollId: 'r2' };
  };
  assert.deepEqual(await requestRoll({ sessionId: 'mesa-v1', createTable, postRoll: postRollV1 }),
    { receipt: { rollId: 'r2' }, sessionId: 'mesa-nova', recreated: true, reason: 'read-only' });
  assert.deepEqual(calls, ['roll:mesa-v1', 'create', 'roll:mesa-nova']);

  calls.length = 0;
  assert.deepEqual(await requestRoll({ sessionId: null, createTable, postRoll }),
    { receipt: { rollId: 'r1' }, sessionId: 'mesa-nova', recreated: false });
  assert.deepEqual(calls, ['create', 'roll:mesa-nova']);

  const busy = Object.assign(new Error('Mesa ocupada. Tente de novo.'), { status: 409 });
  await assert.rejects(requestRoll({ sessionId: 'x', createTable, postRoll: async () => { throw busy; } }), /Mesa ocupada/);
});
