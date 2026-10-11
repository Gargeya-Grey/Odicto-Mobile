/**
 * Runs async tasks one at a time, in the order they were queued. A failed
 * task does not stop later tasks, and each caller still sees its own result.
 */
export function createSerialQueue() {
  let tail: Promise<unknown> = Promise.resolve();
  return function enqueue<T>(task: () => Promise<T>): Promise<T> {
    const run = tail.then(task);
    tail = run.catch(() => undefined);
    return run;
  };
}
