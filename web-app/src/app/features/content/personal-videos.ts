import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import {
  CatalogService, FamilyVideoCard, FamilySortMode,
} from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';
import { TimezoneService } from '../../core/timezone.service';

@Component({
  selector: 'app-personal-videos',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatChipsModule, MatProgressSpinnerModule],
  templateUrl: './personal-videos.html',
  styleUrl: './personal-videos.scss',
})
export class PersonalVideosComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly tz = inject(TimezoneService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly videos = signal<FamilyVideoCard[]>([]);
  readonly total = signal(0);
  readonly allMembers = signal<{ id: number; name: string }[]>([]);

  readonly playableOnly = signal(false);
  readonly selectedMembers = signal<Set<number>>(new Set());
  readonly sortMode = signal<FamilySortMode>('date_desc');

  readonly sortOptions: { value: FamilySortMode; label: string }[] = [
    { value: 'date_desc', label: 'Newest' },
    { value: 'date_asc', label: 'Oldest' },
    { value: 'name', label: 'Name' },
    { value: 'recent', label: 'Recent' },
  ];

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async togglePlayable(): Promise<void> {
    this.playableOnly.update(v => !v);
    await this.refresh();
  }

  async toggleMember(memberId: number): Promise<void> {
    this.selectedMembers.update(set => {
      const next = new Set(set);
      if (next.has(memberId)) next.delete(memberId); else next.add(memberId);
      return next;
    });
    await this.refresh();
  }

  async clearMembers(): Promise<void> {
    this.selectedMembers.set(new Set());
    await this.refresh();
  }

  async setSort(mode: FamilySortMode): Promise<void> {
    this.sortMode.set(mode);
    await this.refresh();
  }

  formatDate(dateStr: string): string {
    return this.tz.formatDate(dateStr);
  }

  private async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const members = this.selectedMembers();
      const data = await this.catalog.getFamilyVideos({
        sort: this.sortMode(),
        members: members.size > 0 ? [...members] : undefined,
        playableOnly: this.playableOnly(),
      });
      this.videos.set(data.videos);
      this.total.set(data.total);
      this.allMembers.set(data.family_members);
    } catch {
      this.error.set('Failed to load family videos');
    } finally {
      this.loading.set(false);
    }
  }
}
