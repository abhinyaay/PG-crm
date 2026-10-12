import { ApiError, parseErrorResponse } from './errors';
import type { SessionManager } from './session';

export type QueryValue = string | number | boolean | null | undefined | readonly (string | number)[];
export type Query = Record<string, QueryValue>;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  query?: Query;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** Send the bearer token (default true). */
  auth?: boolean;
  /** Retry once after refreshing on 401 (default true). */
  retryOn401?: boolean;
  responseType?: 'json' | 'blob' | 'text' | 'none';
}

export interface ApiResponse<T> {
  data: T;
  status: number;
  headers: Headers;
}

export function buildQuery(query?: Query): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      for (const v of value) params.append(key, String(v));
    } else {
      params.append(key, String(value));
    }
  }
  const s = params.toString();
  return s ? `?${s}` : '';
}

/**
 * 401 codes that mean "your access token is bad" (refreshing can help). Other 401 codes are business answers,
 * e.g. INVALID_CREDENTIALS from POST /auth/change-password with a wrong current password.
 */
function isTokenProblem(error: ApiError): boolean {
  return error.code === 'UNAUTHORIZED' || error.code === 'HTTP_401' || error.code === 'INVALID_TOKEN';
}

/**
 * Typed fetch wrapper. All URLs are relative (`/api/v1/...`, `/analytics/...`) because in every environment the SPA
 * is served from the same origin as the APIs (Vite proxy in dev, nginx in production).
 */
export class ApiClient {
  constructor(
    private readonly session: SessionManager,
    private readonly fetchImpl: typeof fetch = (...args) => fetch(...args),
    private readonly baseUrl = '',
  ) {}

  async request<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
    const { auth = true, retryOn401 = true } = options;
    const token = auth ? await this.session.getValidAccessToken() : null;
    let response = await this.send(path, options, token);

    if (response.status === 401 && auth && retryOn401 && this.session.hasRefreshToken()) {
      const error = await parseErrorResponse(response.clone());
      if (isTokenProblem(error)) {
        const newToken = await this.session.refresh(token);
        response = await this.send(path, options, newToken);
        if (response.status === 401) {
          const second = await parseErrorResponse(response);
          if (isTokenProblem(second)) this.session.clear('expired');
          throw second;
        }
      }
    }

    if (!response.ok) throw await parseErrorResponse(response);
    return { data: await this.readBody<T>(response, options.responseType), status: response.status, headers: response.headers };
  }

  get<T>(path: string, query?: Query, signal?: AbortSignal): Promise<T> {
    return this.request<T>(path, { query, signal }).then((r) => r.data);
  }

  post<T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return this.request<T>(path, { ...options, method: 'POST', body }).then((r) => r.data);
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, { method: 'PUT', body }).then((r) => r.data);
  }

  patch<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, { method: 'PATCH', body }).then((r) => r.data);
  }

  delete(path: string): Promise<void> {
    return this.request<void>(path, { method: 'DELETE', responseType: 'none' }).then(() => undefined);
  }

  /** Authenticated binary download (e.g. PDF receipts). */
  blob(path: string): Promise<ApiResponse<Blob>> {
    return this.request<Blob>(path, { responseType: 'blob', headers: { Accept: 'application/pdf, */*' } });
  }

  private async send(path: string, options: RequestOptions, token: string | null): Promise<Response> {
    const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    if (token) headers.Authorization = `Bearer ${token}`;
    try {
      return await this.fetchImpl(`${this.baseUrl}${path}${buildQuery(options.query)}`, {
        method: options.method ?? 'GET',
        headers,
        body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
        signal: options.signal,
        credentials: 'same-origin',
      });
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') throw e;
      throw new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'Network error: could not reach the server.' });
    }
  }

  private async readBody<T>(response: Response, type: RequestOptions['responseType'] = 'json'): Promise<T> {
    if (type === 'none' || response.status === 204) return undefined as T;
    if (type === 'blob') return (await response.blob()) as T;
    const text = await response.text();
    if (type === 'text') return text as T;
    return (text ? JSON.parse(text) : undefined) as T;
  }
}
