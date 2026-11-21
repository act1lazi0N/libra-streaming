import { AUTH_CHANNEL_NAME, type AuthMessage, type ChannelLike } from "../core/client.ts";

export interface LazyChannel extends ChannelLike {
  open(): void;
  shutdown(): void;
}

/**
 * A BroadcastChannel adapter that can be handed to the core client during render but only touches
 * the browser API once opened from an effect (and is closed again on cleanup).
 */
export function createLazyChannel(): LazyChannel {
  let real: BroadcastChannel | null = null;
  const lazy: LazyChannel = {
    onmessage: null,
    postMessage(message: AuthMessage) {
      real?.postMessage(message);
    },
    open() {
      if (real || typeof BroadcastChannel === "undefined") {
        return;
      }
      real = new BroadcastChannel(AUTH_CHANNEL_NAME);
      real.onmessage = (event: MessageEvent) => lazy.onmessage?.({ data: event.data });
    },
    shutdown() {
      real?.close();
      real = null;
    },
  };
  return lazy;
}
