import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, BaseRouteReuseStrategy, Params } from '@angular/router';

/**
 * Recreates a view when only its route parameters change.
 *
 * Angular's default strategy reuses the component instance whenever the
 * route config is the same, so a link from /title/1 to /title/2 (Similar
 * Titles), /artist/1 to /artist/2 (band members), a playlist to its
 * duplicate, or the player to the next episode changed the URL and
 * nothing else: each view reads its id once when it is created.
 * Comparing the path parameters as well makes those navigations load
 * like any other. Query parameters are not compared, so the search page
 * keeps its component while `?q=` changes.
 */
@Injectable()
export class ParamChangeRouteReuseStrategy extends BaseRouteReuseStrategy {
  override shouldReuseRoute(future: ActivatedRouteSnapshot, curr: ActivatedRouteSnapshot): boolean {
    return future.routeConfig === curr.routeConfig && sameParams(future.params, curr.params);
  }
}

function sameParams(a: Params, b: Params): boolean {
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every(k => a[k] === b[k]);
}
