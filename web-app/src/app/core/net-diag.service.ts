import { Injectable, signal } from '@angular/core';

/**
 * Diagnostic for HTTP/1.1 6-connection-per-origin throttling. Off by default;
 * enable with `?netdiag=1` (or `?netdiag=20` for a custom stall threshold in ms).
 *
 * Detects throttling by combining two signals:
 *   1. PerformanceObserver on resource entries — flags any request where the
 *      gap between `fetchStart` and `requestStart` exceeds the threshold.
 *      That gap is queue/connection-stall time; >50 ms is the classic
 *      signature of "all 6 sockets are busy, this request is waiting."
 *   2. HttpInterceptor in-flight counter — when a stall lands while
 *      in-flight is at 6, the diagnosis is unambiguous.
 *
 * Outputs to `console.warn` with a `[netdiag]` prefix, plus exposes
 * `inFlight` and `lastStall` as signals for any future HUD overlay.
 */
@Injectable({ providedIn: 'root' })
export class NetDiagService {
  /** Current in-flight HTTP request count, maintained by the interceptor. */
  readonly inFlight = signal(0);
  /** Peak in-flight count seen since enable; useful for spot-checks. */
  readonly peakInFlight = signal(0);
  /** Most recent stall observation (ms queued, url, in-flight at observe time). */
  readonly lastStall = signal<{ queuedMs: number; url: string; inFlight: number } | null>(null);

  private enabled = false;
  private threshold = 50;

  constructor() {
    if (typeof window === 'undefined') return;
    const params = new URLSearchParams(window.location.search);
    const flag = params.get('netdiag');
    if (flag === null) return;

    this.enabled = true;
    const t = Number(flag);
    if (Number.isFinite(t) && t > 0) this.threshold = t;
    this.startObserver();
    console.info(`[netdiag] enabled, stall threshold = ${this.threshold}ms`);
  }

  /** Called by the interceptor on every outgoing request. */
  noteRequestStart(): void {
    if (!this.enabled) return;
    const next = this.inFlight() + 1;
    this.inFlight.set(next);
    if (next > this.peakInFlight()) this.peakInFlight.set(next);
  }

  /** Called by the interceptor on response or error. */
  noteRequestEnd(): void {
    if (!this.enabled) return;
    this.inFlight.set(Math.max(0, this.inFlight() - 1));
  }

  isEnabled(): boolean { return this.enabled; }

  private startObserver(): void {
    try {
      const obs = new PerformanceObserver(list => {
        for (const e of list.getEntriesByType('resource') as PerformanceResourceTiming[]) {
          // requestStart - fetchStart is the time spent waiting for a
          // connection (queue + connect + tls). Subtracting the actual
          // connect cost would be more precise, but on a warm origin
          // every successive request reuses a socket and connect is ~0,
          // so the raw delta is a clean stall signal.
          const queued = e.requestStart - e.fetchStart;
          if (queued >= this.threshold) {
            const inFlight = this.inFlight();
            this.lastStall.set({ queuedMs: queued, url: e.name, inFlight });
            // Flag the 6-connection ceiling explicitly when we hit it.
            const note = inFlight >= 6 ? ' (in-flight=6 → likely HTTP/1.1 limit)' : '';
            console.warn(
              `[netdiag] stall ${queued.toFixed(0)}ms ${e.nextHopProtocol || '?'} in-flight=${inFlight}${note} ${e.name}`
            );
          }
        }
      });
      obs.observe({ type: 'resource', buffered: true });
    } catch (err) {
      console.warn('[netdiag] PerformanceObserver setup failed', err);
    }
  }
}
