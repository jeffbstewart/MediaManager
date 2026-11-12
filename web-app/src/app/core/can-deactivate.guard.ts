import { CanDeactivateFn } from '@angular/router';

/**
 * Components that may block navigation when they have unsaved state
 * implement this interface and expose a `canDeactivate()` method.
 * Returning `true` allows the navigation; `false` cancels it silently;
 * a Promise that resolves to either leaves the decision async.
 *
 * Usage: attach [canDeactivateGuard] to the component's route in
 * app.routes.ts and have the component implement [CanDeactivateComponent].
 */
export interface CanDeactivateComponent {
  canDeactivate(): boolean | Promise<boolean>;
}

/** Functional CanDeactivate guard that defers entirely to the component. */
export const canDeactivateGuard: CanDeactivateFn<CanDeactivateComponent> =
  (component) => component.canDeactivate();
