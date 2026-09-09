import type { Key } from 'react';

export class TreePositionMutationCoordinator {
  private readonly pendingKeys = new Set<Key>();

  run(key: Key, updatePosition: () => Promise<void>, refreshTree: () => Promise<unknown>): Promise<boolean> {
    if (this.pendingKeys.has(key)) {
      return Promise.resolve(false);
    }

    this.pendingKeys.add(key);
    return Promise.resolve()
      .then(updatePosition)
      .then(refreshTree)
      .then(() => true)
      .finally(() => {
        this.pendingKeys.delete(key);
      });
  }
}
