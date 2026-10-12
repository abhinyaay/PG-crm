import { test as base, expect, request, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test';
import { BASE_URL, type RoleName } from './env';
import { SessionBroker } from './session-broker';

export interface RoleFixtures {
  /**
   * Opens a new browser context + page signed in as `role` (the session restored from the setup project's
   * storageState via the {@link SessionBroker}). Several roles can be open at once in one test; the contexts inherit
   * the project's device settings (viewport, touch, baseURL) and are closed after the test.
   */
  openAs: (role: RoleName) => Promise<Page>;
  /** API client authenticated as `role` (for arranging test data where the UI is not under test). */
  apiAs: (role: RoleName) => Promise<APIRequestContext>;
}

// fixture callbacks name Playwright's `use` "provide" so the React hooks lint rule does not mistake it for React.use
export const test = base.extend<RoleFixtures, { broker: SessionBroker }>({
  broker: [
    // eslint-disable-next-line no-empty-pattern
    async ({}, provide) => {
      await provide(new SessionBroker());
    },
    { scope: 'worker' },
  ],

  openAs: async ({ browser, broker }, provide) => {
    const contexts: BrowserContext[] = [];
    await provide(async (role) => {
      const context = await browser.newContext({ storageState: await broker.storageState(role) });
      contexts.push(context);
      await broker.attach(context, role);
      return context.newPage();
    });
    await Promise.all(contexts.map((c) => c.close()));
  },

  apiAs: async ({ broker }, provide) => {
    const clients: APIRequestContext[] = [];
    await provide(async (role) => {
      const t = await broker.tokens(role);
      const client = await request.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Authorization: `Bearer ${t.accessToken}` } });
      clients.push(client);
      return client;
    });
    await Promise.all(clients.map((c) => c.dispose()));
  },
});

export { expect };
