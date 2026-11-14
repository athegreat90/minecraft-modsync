import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Landing page; the server overview arrives with the backend administration API. */
@Component({
  selector: 'ms-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<h1>Servers</h1>',
})
export class Overview {}
