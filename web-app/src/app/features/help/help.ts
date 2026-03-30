import { Component, ChangeDetectionStrategy } from '@angular/core';

@Component({
  selector: 'app-help',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './help.html',
  styleUrl: './help.scss',
})
export class HelpComponent {}
