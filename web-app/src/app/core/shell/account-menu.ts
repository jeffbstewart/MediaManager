import {
  ChangeDetectionStrategy, Component, ElementRef, OnInit, computed, inject, output, signal, viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { AppRoutes } from '../routes';
import { AccountService } from '../account.service';

/**
 * The account pill in the header and the user sheet it opens (spec
 * 3.10): a native `<dialog>` with Profile, Help, Report a Problem and
 * Sign Out. The last two are emitted to the shell, which owns the
 * report dialog and the sign-out call. The sheet closes on any link,
 * on a backdrop click, and on Escape (native `cancel`).
 */
@Component({
  selector: 'app-account-menu',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule],
  templateUrl: './account-menu.html',
  styleUrl: './account-menu.scss',
})
export class AccountMenuComponent implements OnInit {
  private readonly accounts = inject(AccountService);
  readonly routes = AppRoutes;

  readonly reportProblem = output<void>();
  readonly signOut = output<void>();

  private readonly sheet = viewChild.required<ElementRef<HTMLDialogElement>>('sheet');

  /** Null until the profile loads (or if it fails); the pill then shows only the icon. */
  readonly account = this.accounts.account;
  readonly sheetOpen = signal(false);

  readonly initial = computed(() => this.account()?.username.charAt(0).toUpperCase() ?? '');
  readonly roleLabel = computed(() => (this.account()?.is_admin ? 'Admin' : 'Viewer'));

  ngOnInit(): void {
    void this.accounts.load();
  }

  open(): void {
    const d = this.sheet().nativeElement;
    if (!d.open) d.showModal();
    this.sheetOpen.set(true);
  }

  close(): void {
    const d = this.sheet().nativeElement;
    if (d.open) d.close();
  }

  /** Fired by the dialog itself for every close path, Escape included. */
  onClosed(): void {
    this.sheetOpen.set(false);
  }

  /**
   * A click whose point lies outside the panel's box landed on the
   * ::backdrop (the dialog element receives those events), so close.
   */
  onDialogClick(e: MouseEvent): void {
    const d = this.sheet().nativeElement;
    if (e.target !== d) return;
    const r = d.getBoundingClientRect();
    if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) {
      this.close();
    }
  }

  onReportProblem(): void {
    this.close();
    this.reportProblem.emit();
  }

  onSignOut(): void {
    this.close();
    this.signOut.emit();
  }
}
