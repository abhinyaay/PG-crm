import { isRouteErrorResponse, Link, useRouteError } from 'react-router-dom';

/** Last-resort error screen (e.g. a lazy chunk failed to load after a deploy). */
export function RouteErrorBoundary() {
  const error = useRouteError();
  const chunkError = error instanceof Error && /dynamically imported module|Loading chunk|Failed to fetch/i.test(error.message);
  const message = isRouteErrorResponse(error) ? `${error.status} ${error.statusText}` : error instanceof Error ? error.message : 'Unknown error';
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-3 p-6 text-center">
      <h1 className="text-xl font-semibold text-slate-900 dark:text-white">{chunkError ? 'A new version is available' : 'Something went wrong'}</h1>
      <p className="max-w-md text-sm text-slate-600 dark:text-slate-300">{chunkError ? 'Reload the page to get the latest version.' : message}</p>
      <div className="flex gap-3">
        <button type="button" onClick={() => window.location.reload()} className="min-h-touch rounded-xl bg-brand-600 px-4 text-sm font-medium text-white">
          Reload
        </button>
        <Link to="/" className="inline-flex min-h-touch items-center rounded-xl border border-slate-300 px-4 text-sm font-medium dark:border-slate-700">
          Home
        </Link>
      </div>
    </div>
  );
}
