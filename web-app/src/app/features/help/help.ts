import { Component, ChangeDetectionStrategy } from '@angular/core';
import { BuildBadgeComponent } from '../../core/build-badge';

@Component({
  selector: 'app-help',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BuildBadgeComponent],
  templateUrl: './help.html',
  styleUrl: './help.scss',
})
export class HelpComponent {}
