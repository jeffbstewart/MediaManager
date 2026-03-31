import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule } from '@angular/material/table';
import { firstValueFrom } from 'rxjs';

interface FamilyMemberRow {
  id: number; name: string;
  notes: string | null; video_count: number;
}

@Component({
  selector: 'app-family-members',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatButtonModule, MatTableModule],
  templateUrl: './family-members.html',
  styleUrl: './family-members.scss',
})
export class FamilyMembersComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly members = signal<FamilyMemberRow[]>([]);
  readonly columns = ['name', 'videos', 'notes', 'actions'];

  // Dialog
  readonly dialogOpen = signal(false);
  readonly dialogTitle = signal('');
  readonly editId = signal<number | null>(null);
  readonly editName = signal('');
  readonly editNotes = signal('');
  readonly editError = signal('');

  // Delete
  readonly deleteOpen = signal(false);
  readonly deleteTarget = signal<FamilyMemberRow | null>(null);

  async ngOnInit(): Promise<void> { await this.refresh(); }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const d = await firstValueFrom(this.http.get<{ members: FamilyMemberRow[] }>('/api/v2/admin/family-members'));
      this.members.set(d.members);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  openCreate(): void {
    this.editId.set(null);
    this.editName.set('');
    this.editNotes.set('');
    this.editError.set('');
    this.dialogTitle.set('New Family Member');
    this.dialogOpen.set(true);
  }

  openEdit(row: FamilyMemberRow): void {
    this.editId.set(row.id);
    this.editName.set(row.name);
    this.editNotes.set(row.notes ?? '');
    this.editError.set('');
    this.dialogTitle.set('Edit Family Member');
    this.dialogOpen.set(true);
  }

  closeDialog(): void { this.dialogOpen.set(false); }

  updateName(event: Event): void { this.editName.set((event.target as HTMLInputElement).value); }
  updateNotes(event: Event): void { this.editNotes.set((event.target as HTMLTextAreaElement).value); }

  async save(): Promise<void> {
    const name = this.editName().trim();
    if (!name) { this.editError.set('Name is required'); return; }
    this.editError.set('');

    const body: Record<string, unknown> = { name, notes: this.editNotes() || null };
    try {
      const id = this.editId();
      if (id) {
        await firstValueFrom(this.http.post(`/api/v2/admin/family-members/${id}`, body));
      } else {
        await firstValueFrom(this.http.post('/api/v2/admin/family-members', body));
      }
      this.dialogOpen.set(false);
      await this.refresh();
    } catch (e: unknown) {
      this.editError.set((e as { error?: { error?: string } })?.error?.error ?? 'Save failed');
    }
  }

  openDelete(row: FamilyMemberRow): void {
    this.deleteTarget.set(row);
    this.deleteOpen.set(true);
  }

  closeDelete(): void { this.deleteOpen.set(false); }

  async confirmDelete(): Promise<void> {
    const target = this.deleteTarget();
    if (!target) return;
    await firstValueFrom(this.http.delete(`/api/v2/admin/family-members/${target.id}`));
    this.deleteOpen.set(false);
    await this.refresh();
  }

  initial(name: string): string { return name.charAt(0).toUpperCase(); }
}
