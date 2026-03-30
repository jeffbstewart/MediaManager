import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { AppRoutes } from '../../core/routes';

interface AdminTag {
  id: number;
  name: string;
  bg_color: string;
  text_color: string;
  source_type: string;
  title_count: number;
}

const COLOR_PALETTE = [
  { name: 'Red', hex: '#EF4444' },
  { name: 'Orange', hex: '#F97316' },
  { name: 'Amber', hex: '#F59E0B' },
  { name: 'Yellow', hex: '#EAB308' },
  { name: 'Lime', hex: '#84CC16' },
  { name: 'Green', hex: '#22C55E' },
  { name: 'Emerald', hex: '#10B981' },
  { name: 'Teal', hex: '#14B8A6' },
  { name: 'Cyan', hex: '#06B6D4' },
  { name: 'Sky', hex: '#0EA5E9' },
  { name: 'Blue', hex: '#3B82F6' },
  { name: 'Indigo', hex: '#6366F1' },
  { name: 'Violet', hex: '#8B5CF6' },
  { name: 'Purple', hex: '#A855F7' },
  { name: 'Pink', hex: '#EC4899' },
  { name: 'Stone', hex: '#78716C' },
];

@Component({
  selector: 'app-tag-management',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule, MatTableModule, MatButtonModule],
  templateUrl: './tag-management.html',
  styleUrl: './tag-management.scss',
})
export class TagManagementComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly tags = signal<AdminTag[]>([]);
  readonly columns = ['tag', 'source', 'titles', 'actions'];

  // Dialog state
  readonly dialogOpen = signal(false);
  readonly dialogMode = signal<'create' | 'edit'>('create');
  readonly editingTag = signal<AdminTag | null>(null);
  readonly dialogName = signal('');
  readonly dialogColor = signal('#EF4444');
  readonly dialogError = signal('');

  // Delete dialog
  readonly deleteDialogOpen = signal(false);
  readonly deletingTag = signal<AdminTag | null>(null);

  readonly palette = COLOR_PALETTE;

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(this.http.get<{ tags: AdminTag[] }>('/api/v2/admin/tags'));
      this.tags.set(data.tags);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  openCreate(): void {
    this.dialogMode.set('create');
    this.editingTag.set(null);
    this.dialogName.set('');
    this.dialogColor.set('#EF4444');
    this.dialogError.set('');
    this.dialogOpen.set(true);
  }

  openEdit(tag: AdminTag): void {
    this.dialogMode.set('edit');
    this.editingTag.set(tag);
    this.dialogName.set(tag.name);
    this.dialogColor.set(tag.bg_color);
    this.dialogError.set('');
    this.dialogOpen.set(true);
  }

  closeDialog(): void {
    this.dialogOpen.set(false);
  }

  updateName(event: Event): void {
    this.dialogName.set((event.target as HTMLInputElement).value);
  }

  selectColor(hex: string): void {
    this.dialogColor.set(hex);
  }

  async saveTag(): Promise<void> {
    const name = this.dialogName().trim();
    if (!name) { this.dialogError.set('Name is required'); return; }
    this.dialogError.set('');

    const body = { name, bg_color: this.dialogColor() };
    try {
      if (this.dialogMode() === 'edit') {
        const result = await firstValueFrom(this.http.put<{ ok: boolean; error?: string }>(`/api/v2/admin/tags/${this.editingTag()!.id}`, body));
        if (!result.ok) { this.dialogError.set(result.error ?? 'Failed'); return; }
      } else {
        const result = await firstValueFrom(this.http.post<{ ok: boolean; error?: string }>('/api/v2/admin/tags', body));
        if (!result.ok) { this.dialogError.set(result.error ?? 'Failed'); return; }
      }
      this.closeDialog();
      await this.refresh();
    } catch {
      this.dialogError.set('Request failed');
    }
  }

  openDelete(tag: AdminTag): void {
    this.deletingTag.set(tag);
    this.deleteDialogOpen.set(true);
  }

  closeDelete(): void {
    this.deleteDialogOpen.set(false);
    this.deletingTag.set(null);
  }

  async confirmDelete(): Promise<void> {
    const tag = this.deletingTag();
    if (!tag) return;
    await firstValueFrom(this.http.delete(`/api/v2/admin/tags/${tag.id}`));
    this.closeDelete();
    await this.refresh();
  }

  sourceLabel(type: string): string {
    switch (type) {
      case 'GENRE': return 'Genre';
      case 'COLLECTION': return 'Collection';
      case 'EVENT_TYPE': return 'Event Type';
      default: return 'Manual';
    }
  }
}
