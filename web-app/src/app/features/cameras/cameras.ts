import {
  Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy,
} from '@angular/core';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { CatalogService, CameraInfo } from '../../core/catalog.service';

@Component({
  selector: 'app-cameras',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatChipsModule, MatProgressSpinnerModule, MatIconModule],
  templateUrl: './cameras.html',
  styleUrl: './cameras.scss',
})
export class CamerasComponent implements OnInit, OnDestroy {
  private readonly catalog = inject(CatalogService);

  readonly loading = signal(true);
  readonly error = signal('');
  readonly cameras = signal<CameraInfo[]>([]);
  readonly useMjpeg = signal(false);
  readonly fullscreenCamera = signal<CameraInfo | null>(null);

  private snapshotTimers: ReturnType<typeof setInterval>[] = [];

  async ngOnInit(): Promise<void> {
    try {
      const data = await this.catalog.getCameras();
      this.cameras.set(data.cameras);
    } catch {
      this.error.set('Failed to load cameras');
    } finally {
      this.loading.set(false);
    }
  }

  ngOnDestroy(): void {
    this.clearTimers();
  }

  toggleMode(): void {
    this.clearTimers();
    this.useMjpeg.update(v => !v);
    if (!this.useMjpeg()) {
      this.startSnapshotRefresh();
    }
  }

  snapshotUrl(camera: CameraInfo): string {
    return `/cam/${camera.id}/snapshot.jpg?t=${Date.now()}`;
  }

  mjpegUrl(camera: CameraInfo): string {
    return `/cam/${camera.id}/mjpeg`;
  }

  openFullscreen(camera: CameraInfo): void {
    this.fullscreenCamera.set(camera);
  }

  closeFullscreen(): void {
    this.fullscreenCamera.set(null);
  }

  private startSnapshotRefresh(): void {
    for (const cam of this.cameras()) {
      const timer = setInterval(() => {
        const imgs = document.querySelectorAll(`img[data-cam-id="${cam.id}"]`);
        imgs.forEach(img => (img as HTMLImageElement).src = this.snapshotUrl(cam));
      }, 3000);
      this.snapshotTimers.push(timer);
    }
  }

  private clearTimers(): void {
    this.snapshotTimers.forEach(t => clearInterval(t));
    this.snapshotTimers = [];
  }
}
