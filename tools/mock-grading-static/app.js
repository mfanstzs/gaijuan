import { GradingSession, MODES, parseScore } from './engine.js';

const byId = id => document.getElementById(id);
const session = new GradingSession();
const image = byId('answer');
const score = byId('score');
const submit = byId('submit');
const next = byId('next');
let state = session.snapshot();
let imageReady = false;
let imageFailed = false;

function controls() {
  const ready = state.phase === 'ready' && imageReady && !imageFailed;
  score.disabled = !ready;
  submit.disabled = !ready || parseScore(score.value) === null;
  next.disabled = state.phase !== 'submitted' || !imageReady || imageFailed;
}

function render(updated) {
  const changed = updated.answerId !== state.answerId || updated.history.length === 0;
  state = updated;
  if (changed || state.phase === 'completed') score.value = '';
  byId('answer-id').textContent = state.answerId || '已完成';
  byId('count').textContent = String(state.confirmedCount);
  byId('total').textContent = String(state.totalCount);
  byId('state-label').textContent = state.phase === 'completed'
    ? '全部 6 份答题图片已完成，模拟已停止'
    : state.phase === 'submitted'
      ? '本页提交已记录，请点击下一份'
      : '当前答卷已就绪';
  byId('ack').textContent = state.receipt
    ? state.receipt.answerId + '，' + state.receipt.score + ' 分（仅本页记录）'
    : '无';
  byId('score-empty').hidden = state.history.length > 0;
  const entries = state.history.map(entry => {
    const item = document.createElement('li');
    item.textContent = String(entry.sequence).padStart(2, '0') + '：' + entry.score + ' 分';
    return item;
  });
  byId('score-list').replaceChildren(...entries);
  next.hidden = state.mode !== MODES.MANUAL;
  submit.textContent = state.mode === MODES.AUTOMATIC ? '提交并下一份' : '提交分数';
  image.alt = state.phase === 'completed' ? '全部六份答题图片已完成' : '答题图片 ' + state.answerSequence;
  if (image.getAttribute('src') !== state.imagePath) {
    imageReady = false;
    imageFailed = false;
    image.src = state.imagePath;
  }
  controls();
}

image.addEventListener('load', () => {
  imageReady = image.complete && image.naturalWidth > 0 && image.naturalHeight > 0;
  imageFailed = !imageReady;
  controls();
});
image.addEventListener('error', () => {
  imageReady = false;
  imageFailed = true;
  byId('message').textContent = '图片加载失败，已禁止提交。请刷新页面后重试。';
  controls();
});
score.addEventListener('input', controls);

submit.addEventListener('click', () => {
  if (submit.disabled) return;
  const result = session.submit(state.answerId, score.value);
  byId('message').textContent = result.accepted ? '' : '提交未接受，请检查当前答卷和 0–4 整数分数。';
  render(result.state);
});
next.addEventListener('click', () => {
  if (next.disabled) return;
  const result = session.next(state.answerId);
  byId('message').textContent = result.accepted ? '' : '当前不能翻页，请先提交分数。';
  render(result.state);
});
byId('restart').addEventListener('click', () => {
  byId('message').textContent = '已清空本页记录，从第 1 份重新开始。';
  render(session.restart());
});
byId('navigation-mode').addEventListener('change', event => {
  byId('message').textContent = '导航方式已切换，本页记录已清空。';
  render(session.restart(event.target.value));
});

render(state);
