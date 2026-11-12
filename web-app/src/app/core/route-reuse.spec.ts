import { ActivatedRouteSnapshot, Route } from '@angular/router';
import { ParamChangeRouteReuseStrategy } from './route-reuse';

function snapshot(routeConfig: Route, params: Record<string, string>): ActivatedRouteSnapshot {
  return { routeConfig, params } as unknown as ActivatedRouteSnapshot;
}

describe('ParamChangeRouteReuseStrategy', () => {
  const strategy = new ParamChangeRouteReuseStrategy();
  const title: Route = { path: 'title/:titleId' };
  const search: Route = { path: 'search' };

  it('reuses the view when the route and its parameters are unchanged', () => {
    expect(strategy.shouldReuseRoute(snapshot(title, { titleId: '1' }), snapshot(title, { titleId: '1' }))).toBe(true);
  });

  it('recreates the view when a parameter changes', () => {
    expect(strategy.shouldReuseRoute(snapshot(title, { titleId: '2' }), snapshot(title, { titleId: '1' }))).toBe(false);
  });

  it('recreates the view when the route config differs', () => {
    expect(strategy.shouldReuseRoute(snapshot(search, {}), snapshot(title, { titleId: '1' }))).toBe(false);
  });

  it('keeps a parameterless view while only its query changes', () => {
    expect(strategy.shouldReuseRoute(snapshot(search, {}), snapshot(search, {}))).toBe(true);
  });
});
