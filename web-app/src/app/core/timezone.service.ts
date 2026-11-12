import { Injectable } from '@angular/core';

/**
 * Centralized timestamp formatting using the browser's local timezone.
 * The server sends UTC ISO-8601 timestamps (with Z suffix); this service
 * formats them for display in the user's device timezone.
 */
@Injectable({ providedIn: 'root' })
export class TimezoneService {

  /** Format an ISO-8601 UTC timestamp as date + time (e.g., "Apr 3, 2026, 2:45 PM"). */
  formatDateTime(isoString: string | null | undefined): string {
    if (!isoString) return '\u2014';
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return '\u2014';
    return d.toLocaleString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric',
      hour: 'numeric', minute: '2-digit',
    });
  }

  /** Format an ISO-8601 UTC timestamp as time only (e.g., "2:45 PM"). */
  formatTime(isoString: string | null | undefined): string {
    if (!isoString) return '\u2014';
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return '\u2014';
    return d.toLocaleTimeString('en-US', {
      hour: 'numeric', minute: '2-digit', hour12: true,
    });
  }

  /** Format a date-only string (e.g., "2026-04-03") without timezone conversion. */
  formatDate(dateStr: string | null | undefined): string {
    if (!dateStr) return '\u2014';
    const d = new Date(dateStr + 'T00:00:00');
    if (isNaN(d.getTime())) return '\u2014';
    return d.toLocaleDateString('en-US', {
      year: 'numeric', month: 'long', day: 'numeric',
    });
  }
}
