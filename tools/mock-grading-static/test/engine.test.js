import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { GradingSession, MODES, COMPLETED_IMAGE, parseScore } from '../engine.js';

test('six valid submissions advance exactly once and finish with a stable distinct image', () => {
  const session = new GradingSession();
  const images = new Set();
  for (let index = 1; index <= 6; index++) {
    const before = session.snapshot();
    assert.equal(before.phase, 'ready');
    assert.equal(before.answerSequence, index);
    images.add(before.imagePath);
    const reply = session.submit(before.answerId, String(index % 5));
    assert.equal(reply.accepted, true);
    assert.equal(reply.state.confirmedCount, index);
    assert.equal(reply.state.receipt.answerId, before.answerId);
    assert.equal(reply.state.receipt.score, index % 5);
  }
  assert.equal(images.size, 6);
  const completed = session.snapshot();
  assert.equal(completed.phase, 'completed');
  assert.equal(completed.answerSequence, 0);
  assert.equal(completed.answerId, '');
  assert.equal(completed.imagePath, COMPLETED_IMAGE);
  assert.equal(images.has(completed.imagePath), false);
  assert.equal(completed.history.length, 6);
  for (let index = 0; index < 20; index++) {
    assert.equal(session.submit('', '3').accepted, false);
    assert.equal(session.next('').accepted, false);
    assert.deepEqual(session.snapshot(), completed);
  }
});

test('invalid scores do not mutate state or create records', () => {
  const invalid = ['', ' ', '-1', '5', '1.5', '1e0', '0x1', '４', '2x', null, undefined, NaN, Infinity, 1.5, [], {}];
  const session = new GradingSession();
  const before = session.snapshot();
  for (const value of invalid) {
    assert.equal(parseScore(value), null);
    assert.equal(session.submit(before.answerId, value).code, 'INVALID_SCORE');
    assert.deepEqual(session.snapshot(), before);
  }
  for (const value of [0, 1, 2, 3, 4, '0', '4', ' 3 ']) {
    assert.equal(typeof parseScore(value), 'number');
  }
});

test('duplicate and stale answer identifiers cannot submit against the next answer', () => {
  const session = new GradingSession();
  const first = session.snapshot().answerId;
  assert.equal(session.submit('MOCK-0002', '4').code, 'STALE_ANSWER');
  assert.equal(session.submit(first, '3').accepted, true);
  const second = session.snapshot();
  assert.equal(session.submit(first, '4').code, 'DUPLICATE_SUBMISSION');
  assert.equal(session.submit('MOCK-9999', '4').code, 'STALE_ANSWER');
  assert.deepEqual(session.snapshot(), second);
});

test('manual mode needs next for the first five, but last submit completes immediately', () => {
  const session = new GradingSession(MODES.MANUAL);
  for (let index = 1; index <= 6; index++) {
    const id = session.snapshot().answerId;
    assert.equal(session.next(id).code, 'NEXT_NOT_ALLOWED');
    const reply = session.submit(id, '2');
    assert.equal(reply.accepted, true);
    assert.equal(session.submit(id, '4').code, 'DUPLICATE_SUBMISSION');
    if (index < 6) {
      assert.equal(reply.state.phase, 'submitted');
      assert.equal(reply.state.answerSequence, index);
      assert.equal(session.next('WRONG').code, 'STALE_ANSWER');
      assert.equal(session.next(id).accepted, true);
      assert.equal(session.next(id).accepted, false);
      assert.equal(session.snapshot().answerSequence, index + 1);
    } else {
      assert.equal(reply.state.phase, 'completed');
      assert.equal(session.next(id).code, 'COMPLETED');
    }
  }
  assert.equal(session.snapshot().confirmedCount, 6);
});

test('restart clears receipts and scores, and can explicitly change mode', () => {
  const session = new GradingSession();
  session.submit(session.snapshot().answerId, '4');
  const restarted = session.restart(MODES.MANUAL);
  assert.equal(restarted.mode, MODES.MANUAL);
  assert.equal(restarted.phase, 'ready');
  assert.equal(restarted.answerId, 'MOCK-0001');
  assert.equal(restarted.confirmedCount, 0);
  assert.equal(restarted.receipt, null);
  assert.deepEqual(restarted.history, []);
  assert.equal(session.submit(restarted.answerId, '0').accepted, true);
});

test('instances and returned snapshots cannot affect another run', () => {
  const first = new GradingSession();
  const second = new GradingSession();
  const untouched = second.snapshot();
  first.submit(first.snapshot().answerId, '4');
  assert.deepEqual(second.snapshot(), untouched);
  const snapshot = first.snapshot();
  assert.throws(() => snapshot.history.push({ score: 0 }), TypeError);
  assert.throws(() => { snapshot.history[0].score = 0; }, TypeError);
  first.restart();
  assert.equal(snapshot.history[0].score, 4);
  assert.equal(second.snapshot().confirmedCount, 0);
});

test('bad mode is rejected without erasing an existing run', () => {
  assert.throws(() => new GradingSession('invalid'), TypeError);
  const session = new GradingSession();
  session.submit(session.snapshot().answerId, '4');
  const before = session.snapshot();
  assert.throws(() => session.restart('invalid'), TypeError);
  assert.deepEqual(session.snapshot(), before);
});

test('public runtime contains no network or persistence APIs and uses relative asset references', async () => {
  const root = new URL('../', import.meta.url);
  const html = await readFile(new URL('index.html', root), 'utf8');
  const css = await readFile(new URL('styles.css', root), 'utf8');
  const app = await readFile(new URL('app.js', root), 'utf8');
  const engine = await readFile(new URL('engine.js', root), 'utf8');
  assert.doesNotMatch(app + engine, /\b(fetch|XMLHttpRequest|WebSocket|EventSource|localStorage|sessionStorage|setInterval|setTimeout)\b|document\.cookie/);
  assert.doesNotMatch(html, /<script(?![^>]*\bsrc=)[^>]*>|\bstyle=|\bon\w+=/i);
  const references = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map(match => match[1]);
  assert.ok(references.length > 0);
  assert.ok(references.every(reference => reference.startsWith('./')));
  assert.match(css, /#answer \{[^}]*left: 16px; top: 144px; width: 720px; height: 220px;/);
  assert.match(css, /#score \{[^}]*left: 16px; top: 402px; width: 160px; height: 44px;/);
  assert.match(css, /#submit \{[^}]*left: 192px; top: 402px; width: 144px; height: 44px;/);
  const names = await readdir(new URL('assets/answers/', root));
  assert.equal(names.filter(name => /^handwritten-answer-0[1-6]\.png$/.test(name)).length, 6);
});

test('all six PNGs are byte-identical copies of the provided fixtures and retain their dimensions', async () => {
  const root = new URL('../', import.meta.url);
  const hashes = new Set();
  for (let index = 1; index <= 6; index++) {
    const name = 'handwritten-answer-' + String(index).padStart(2, '0') + '.png';
    const copy = await readFile(new URL('assets/answers/' + name, root));
    const original = await readFile(new URL('../mock-grading-site/src/main/resources/answers/' + name, root));
    assert.deepEqual(copy, original);
    assert.deepEqual(copy.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
    assert.equal(copy.readUInt32BE(16), 828);
    assert.equal(copy.readUInt32BE(20), 209);
    hashes.add(createHash('sha256').update(copy).digest('hex'));
  }
  assert.equal(hashes.size, 6);
});
