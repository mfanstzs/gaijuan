export const MODES = Object.freeze({
  AUTOMATIC: 'submit-and-advance',
  MANUAL: 'submit-then-next'
});

export const TOTAL_ANSWERS = 6;
export const COMPLETED_IMAGE = './assets/completed.svg';

export function parseScore(raw) {
  if (typeof raw !== 'string' && typeof raw !== 'number') return null;
  const text = String(raw).trim();
  if (!/^\d+$/.test(text)) return null;
  const number = Number(text);
  return Number.isSafeInteger(number) && number >= 0 && number <= 4 ? number : null;
}

const answerId = sequence => 'MOCK-' + String(sequence).padStart(4, '0');
const checkedMode = mode => {
  if (mode !== MODES.AUTOMATIC && mode !== MODES.MANUAL) {
    throw new TypeError('Unknown navigation mode');
  }
  return mode;
};

/** In-memory fixture only. No network, persistence, billing, or authoritative server receipt. */
export class GradingSession {
  #mode;
  #sequence = 1;
  #phase = 'ready';
  #history = [];

  constructor(mode = MODES.AUTOMATIC) {
    this.#mode = checkedMode(mode);
  }

  snapshot() {
    const complete = this.#phase === 'completed';
    const history = Object.freeze(this.#history.map(entry => Object.freeze({ ...entry })));
    return Object.freeze({
      mode: this.#mode,
      phase: this.#phase,
      answerId: complete ? '' : answerId(this.#sequence),
      answerSequence: complete ? 0 : this.#sequence,
      imagePath: complete
        ? COMPLETED_IMAGE
        : './assets/answers/handwritten-answer-' + String(this.#sequence).padStart(2, '0') + '.png',
      confirmedCount: history.length,
      totalCount: TOTAL_ANSWERS,
      receipt: history.length ? history[history.length - 1] : null,
      history
    });
  }

  #reply(accepted, code) {
    return Object.freeze({ accepted, code, state: this.snapshot() });
  }

  submit(expectedAnswerId, rawScore) {
    if (this.#history.some(entry => entry.answerId === expectedAnswerId)) {
      return this.#reply(false, 'DUPLICATE_SUBMISSION');
    }
    if (this.#phase === 'completed') return this.#reply(false, 'COMPLETED');
    if (expectedAnswerId !== answerId(this.#sequence)) {
      return this.#reply(false, 'STALE_ANSWER');
    }
    if (this.#phase !== 'ready') return this.#reply(false, 'ALREADY_SUBMITTED');
    const score = parseScore(rawScore);
    if (score === null) return this.#reply(false, 'INVALID_SCORE');
    this.#history.push({ answerId: expectedAnswerId, sequence: this.#sequence, score });
    if (this.#sequence === TOTAL_ANSWERS) {
      this.#phase = 'completed';
    } else if (this.#mode === MODES.AUTOMATIC) {
      this.#sequence++;
    } else {
      this.#phase = 'submitted';
    }
    return this.#reply(true, 'SUBMITTED');
  }

  next(expectedAnswerId) {
    if (this.#phase === 'completed') return this.#reply(false, 'COMPLETED');
    if (expectedAnswerId !== answerId(this.#sequence)) {
      return this.#reply(false, 'STALE_ANSWER');
    }
    if (this.#mode !== MODES.MANUAL || this.#phase !== 'submitted') {
      return this.#reply(false, 'NEXT_NOT_ALLOWED');
    }
    this.#sequence++;
    this.#phase = 'ready';
    return this.#reply(true, 'ADVANCED');
  }

  restart(mode = this.#mode) {
    this.#mode = checkedMode(mode);
    this.#sequence = 1;
    this.#phase = 'ready';
    this.#history = [];
    return this.snapshot();
  }
}
