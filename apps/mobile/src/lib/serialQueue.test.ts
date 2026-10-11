import { describe, expect, it } from 'vitest';
import { createSerialQueue } from './serialQueue';

const tick = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

describe('createSerialQueue', () => {
  it('runs tasks in order even when an earlier task is slower', async () => {
    const enqueue = createSerialQueue();
    const log: string[] = [];
    const first = enqueue(async () => {
      await tick(20);
      log.push('first');
    });
    const second = enqueue(async () => {
      log.push('second');
    });
    await Promise.all([first, second]);
    expect(log).toEqual(['first', 'second']);
  });

  it('never overlaps two tasks', async () => {
    const enqueue = createSerialQueue();
    let running = 0;
    let peak = 0;
    const job = async () => {
      running += 1;
      peak = Math.max(peak, running);
      await tick(5);
      running -= 1;
    };
    await Promise.all([enqueue(job), enqueue(job), enqueue(job)]);
    expect(peak).toBe(1);
  });

  it('keeps going after a failure and reports it to the right caller', async () => {
    const enqueue = createSerialQueue();
    const failed = enqueue(async () => {
      throw new Error('boom');
    });
    const next = enqueue(async () => 'ok');
    await expect(failed).rejects.toThrow('boom');
    await expect(next).resolves.toBe('ok');
  });
});
