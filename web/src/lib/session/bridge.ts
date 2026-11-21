export interface AuthHandlers {
  signedOut(): void;
  remoteSignedOut(): void;
  signedIn(): void;
}

export interface AuthBridge extends AuthHandlers {
  /** Swap the handlers (called from an effect once React state setters are available). */
  assign(next: AuthHandlers): void;
}

/** Late-bound handlers so the core client can be created before the provider's effects run. */
export function createAuthBridge(): AuthBridge {
  let handlers: AuthHandlers = {
    signedOut: () => undefined,
    remoteSignedOut: () => undefined,
    signedIn: () => undefined,
  };
  return {
    assign(next) {
      handlers = next;
    },
    signedOut: () => handlers.signedOut(),
    remoteSignedOut: () => handlers.remoteSignedOut(),
    signedIn: () => handlers.signedIn(),
  };
}
