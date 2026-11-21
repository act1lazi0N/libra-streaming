import type { HlsEngine, PlayerFatalError } from "./controller.ts";

/**
 * Loads hls.js lazily (browser only) and adapts it to the controller's HlsEngine port. Requests are
 * same-origin XHR so the HttpOnly ticket cookie travels by itself; nothing is added to URLs.
 */
export async function loadHlsEngine(): Promise<HlsEngine> {
  const { default: Hls } = await import("hls.js");
  return {
    isSupported: () => Hls.isSupported(),
    create({ manifestPath, startPositionSeconds, video, onFatal }) {
      const hls = new Hls({ startPosition: startPositionSeconds });
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (!data.fatal) {
          return;
        }
        const kind: PlayerFatalError["kind"] =
          data.type === Hls.ErrorTypes.NETWORK_ERROR
            ? "network"
            : data.type === Hls.ErrorTypes.MEDIA_ERROR
              ? "media"
              : "other";
        onFatal({ kind, httpStatus: data.response?.code ?? null });
      });
      hls.loadSource(manifestPath);
      hls.attachMedia(video as unknown as HTMLVideoElement);
      return {
        startLoad: () => hls.startLoad(),
        recoverMediaError: () => hls.recoverMediaError(),
        destroy: () => hls.destroy(),
      };
    },
  };
}
