import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { AppRoutes } from '../../core/routes';
import { CatalogService } from '../../core/catalog.service';

interface AdminTag {
  id: number;
  name: string;
  bg_color: string;
  text_color: string;
  source_type: string;
  title_count: number;
}

// Tailwind-700 palette. The previous 500-level ladder failed WCAG AA
// when paired with the always-white pill text (most ran 2.5–3.8:1; the
// AA floor is 4.5:1 for normal text). 700-level darkens enough that
// white text clears the threshold across the whole row, so the pill
// no longer needs the per-color dark-text override the template used
// to apply for amber/yellow/lime.
const COLOR_PALETTE = [
  { name: 'Red', hex: '#B91C1C' },
  { name: 'Orange', hex: '#C2410C' },
  { name: 'Amber', hex: '#B45309' },
  { name: 'Yellow', hex: '#A16207' },
  { name: 'Lime', hex: '#4D7C0F' },
  { name: 'Green', hex: '#15803D' },
  { name: 'Emerald', hex: '#047857' },
  { name: 'Teal', hex: '#0F766E' },
  { name: 'Cyan', hex: '#0E7490' },
  { name: 'Sky', hex: '#0369A1' },
  { name: 'Blue', hex: '#1D4ED8' },
  { name: 'Indigo', hex: '#4338CA' },
  { name: 'Violet', hex: '#6D28D9' },
  { name: 'Purple', hex: '#7E22CE' },
  { name: 'Pink', hex: '#BE185D' },
  { name: 'Stone', hex: '#44403C' },
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
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly tags = signal<AdminTag[]>([]);
  readonly columns = ['tag', 'source', 'titles', 'actions'];

  // Dialog state
  readonly dialogOpen = signal(false);
  readonly dialogMode = signal<'create' | 'edit'>('create');
  readonly editingTag = signal<AdminTag | null>(null);
  readonly dialogName = signal('');
  readonly dialogColor = signal('#B91C1C');
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
    this.dialogColor.set('#B91C1C');
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
        // Create flows through catalog.createTag → AdminService.CreateTag.
        await this.catalog.createTag(name, this.dialogColor());
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
