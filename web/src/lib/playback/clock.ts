/**
 * Expiry of a session in CLIENT time, immune to a wrong client clock: the server states both its own
 * time (`Date` header) and the expiry, so the remaining lifetime is their difference, counted from
 * the moment the response arrived. Without a usable `Date` header the raw expiry is used.
 */
export function expiryInClientTime(expiresAt: string, dateHeader: string | null, clientNowAtReceiptMs: number): number {
  const expires = Date.parse(expiresAt);
  const serverNow = dateHeader === null ? Number.NaN : Date.parse(dateHeader);
  if (!Number.isFinite(expires) || !Number.isFinite(serverNow)) {
    return expires;
  }
  return clientNowAtReceiptMs + (expires - serverNow);
}

/** Collects the Date header of a response together with the client time it arrived. */
export interface ReceiptClock {
  onHeaders(headers: Headers): void;
  expiryOf(expiresAt: string): number;
}

export function receiptClock(now: () => number): ReceiptClock {
  let dateHeader: string | null = null;
  let receivedAt = 0;
  return {
    onHeaders(headers) {
      dateHeader = headers.get("Date");
      receivedAt = now();
    },
    expiryOf(expiresAt) {
      return expiryInClientTime(expiresAt, dateHeader, dateHeader === null ? now() : receivedAt);
    },
  };
}
