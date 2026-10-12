import fs from 'node:fs/promises';
import type { Download, Page } from '@playwright/test';
import { DEMO_PROPERTY } from './support/env';
import { expect, test } from './support/fixtures';
import { releaseTenantStay } from './support/cleanup';
import { field, idFromUrl, pageSubtitle, toast, uniquePhone, uniqueSuffix } from './support/helpers';

/**
 * Full resident lifecycle as a manager: register -> reserve -> check in from the reservation -> collect the deposit
 * (incl. a payment whose response is lost and is retried with the same Idempotency-Key) -> PDF receipt ->
 * check out with a deduction -> settlement.
 */
let createdTenantId = 0;

// a failed run must not leave a bed of the shared demo property reserved/occupied
test.afterEach(async ({ apiAs }, testInfo) => {
  if (createdTenantId && testInfo.status !== testInfo.expectedStatus) await releaseTenantStay(await apiAs('manager'), createdTenantId);
  createdTenantId = 0;
});

test('manager runs a resident from registration to check-out', async ({ openAs }) => {
  test.setTimeout(150_000);
  const page = await openAs('manager');
  const suffix = uniqueSuffix();
  const name = `E2E Resident ${suffix}`;
  let tenantId = 0;
  let bedLabel = '';
  let depositInvoice = '';

  await test.step('register the tenant', async () => {
    await page.goto('/tenants/new');
    await expect(page.getByRole('heading', { level: 1, name: 'Register tenant' })).toBeVisible();
    await field(page, 'Property').selectOption({ label: DEMO_PROPERTY });
    await field(page, 'Full name').fill(name);
    await field(page, 'Phone').fill(uniquePhone());
    await field(page, 'Email').fill(`e2e.${suffix}@example.com`);
    await field(page, 'Occupation').fill('QA engineer');
    await page.getByRole('button', { name: 'Register tenant' }).click();

    await expect(toast(page, 'Tenant registered')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
    await expect(page).toHaveURL(/\/tenants\/\d+$/);
    tenantId = idFromUrl(page, '/tenants');
    createdTenantId = tenantId;
    await expect(pageSubtitle(page)).toContainText('Prospective');
  });

  await test.step('reserve an available bed', async () => {
    await page.getByRole('button', { name: 'Reserve a bed' }).click();
    const dialog = page.getByRole('dialog', { name: 'Reserve a bed' });
    await expect(dialog.getByText(`Resident: ${name}`)).toBeVisible();
    const bed = field(dialog, 'Bed');
    await expect(bed).toBeEnabled();
    expect(await bed.locator('option').count(), `no AVAILABLE bed left in ${DEMO_PROPERTY}`).toBeGreaterThan(1);
    await bed.selectOption({ index: 1 });
    const optionText = (await bed.locator('option:checked').textContent()) ?? '';
    bedLabel = optionText.split(' — ')[0]!.trim(); // "Room 103 · Bed B"
    expect(bedLabel).toMatch(/^Room .+ · Bed .+$/);

    await dialog.getByRole('button', { name: 'Reserve', exact: true }).click();
    await expect(toast(page, 'Bed reserved')).toBeVisible();
    await expect(dialog).toBeHidden();
    await expect(page.getByText(new RegExp(`^Reserved \\S+: ${bedLabel}$`))).toBeVisible();
  });

  await test.step('check in from the reservation', async () => {
    await page.getByRole('button', { name: 'Check in now' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /^Check in / })).toBeVisible();
    await expect(dialog).toContainText(name);
    await dialog.getByRole('button', { name: 'Check in', exact: true }).click();

    await expect(dialog.getByRole('heading', { name: 'Check-in complete' })).toBeVisible();
    await expect(toast(page, 'Checked in')).toBeVisible();
    const deposit = dialog.getByRole('listitem').filter({ hasText: 'Security deposit' });
    await expect(deposit).toHaveCount(1);
    await expect(dialog.getByRole('listitem').filter({ hasText: 'Rent' })).toHaveCount(1);
    depositInvoice = (await deposit.getByRole('link').textContent())!.trim();
    await dialog.getByRole('button', { name: 'Done' }).click();

    await expect(pageSubtitle(page)).toContainText('Active');
    await expect(page.getByText(bedLabel, { exact: true })).toBeVisible();
    await page.getByRole('tab', { name: /Dues/ }).click();
    await page.getByRole('link', { name: new RegExp(depositInvoice) }).click();
    await expect(page.getByRole('heading', { level: 1, name: depositInvoice })).toBeVisible();
    await expect(pageSubtitle(page)).toContainText('Issued');
  });

  await test.step('record a part payment on the deposit invoice', async () => {
    await page.getByRole('link', { name: 'Record payment' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Record payment' })).toBeVisible();
    const amount = field(page, 'Amount (₹)');
    await expect(amount).not.toHaveValue('0');
    await amount.fill('1000');
    await field(page, 'Method').selectOption('CASH');
    await field(page, 'Reference (UTR / cheque no.)').fill(`E2E-${suffix}-1`);

    const created = page.waitForResponse((r) => r.url().endsWith('/api/v1/payments') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Record payment' }).click();
    const res = await created;
    expect(res.status()).toBe(201);
    expect(res.headers()['idempotent-replayed']).toBeUndefined();

    await expect(page.getByRole('heading', { level: 1, name: 'Payment recorded' })).toBeVisible();
    await expect(page.getByText('The payment was applied to the invoice.')).toBeVisible();
    await expect(toast(page, 'Payment recorded')).toBeVisible();
  });

  await test.step('lost response: "Retry safely" replays the same Idempotency-Key and charges once', async () => {
    await page.getByRole('link', { name: 'View invoice' }).click();
    await expect(pageSubtitle(page)).toContainText('Partially paid');
    await page.getByRole('link', { name: 'Record payment' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Record payment' })).toBeVisible();
    await field(page, 'Reference (UTR / cheque no.)').fill(`E2E-${suffix}-2`);

    // The first attempt reaches the backend (the payment IS recorded) but the browser never sees the answer.
    const keys: string[] = [];
    await page.route('**/api/v1/payments', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      keys.push(route.request().headers()['idempotency-key'] ?? '');
      if (keys.length === 1) {
        const upstream = await route.fetch();
        expect(upstream.status()).toBe(201);
        return route.abort('connectionreset');
      }
      return route.fallback();
    });

    await page.getByRole('button', { name: 'Record payment' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'We could not confirm whether the payment was saved.' })).toBeVisible();
    const retry = page.getByRole('button', { name: 'Retry safely' });
    await expect(retry).toBeVisible();
    await expect(page.getByText(`Idempotency-Key: ${keys[0]}`)).toBeVisible();

    const replay = page.waitForResponse((r) => r.url().endsWith('/api/v1/payments') && r.request().method() === 'POST');
    await retry.click();
    const res = await replay;
    expect(res.status()).toBe(200);
    expect(res.headers()['idempotent-replayed']).toBe('true');
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
    await page.unroute('**/api/v1/payments');

    await expect(page.getByRole('heading', { level: 1, name: 'Payment already recorded' })).toBeVisible();
    await expect(page.getByText(/nothing was charged twice/)).toBeVisible();
  });

  await test.step('open the PDF receipt', async () => {
    // capture the exact bytes the SPA receives for the receipt
    let pdf: { status: number; type: string; body: Buffer } | null = null;
    await page.route(/\/api\/v1\/payments\/\d+\/receipt$/, async (route) => {
      const upstream = await route.fetch();
      pdf = { status: upstream.status(), type: upstream.headers()['content-type'] ?? '', body: await upstream.body() };
      await route.fulfill({ response: upstream });
    });
    // ReceiptButton opens a tab synchronously (pop-up blocker safe) and points it at a blob: URL of the PDF;
    // headless Chromium hands a PDF navigation to the download manager, so the blob arrives as a download there.
    const opened = new Promise<{ tab: Page; download: Download }>((resolve) => {
      page.context().once('page', (tab) => tab.once('download', (download) => resolve({ tab, download })));
    });
    await page.getByRole('button', { name: 'Open PDF receipt' }).click();
    const { tab, download } = await opened;
    expect(download.url()).toMatch(/^blob:/);
    const saved = await fs.readFile(await download.path());
    expect(saved.subarray(0, 5).toString('latin1')).toBe('%PDF-');

    expect(pdf).not.toBeNull();
    const { status, type, body } = pdf!;
    expect(status).toBe(200);
    expect(type).toContain('application/pdf');
    expect(body.subarray(0, 5).toString('latin1')).toBe('%PDF-');
    expect(body.length).toBeGreaterThan(500);
    expect(saved.equals(body)).toBe(true);
    await tab.close();
    await page.unroute(/\/api\/v1\/payments\/\d+\/receipt$/);
  });

  await test.step('the invoice is paid with exactly two payments (no duplicate)', async () => {
    await page.getByRole('link', { name: 'View invoice' }).click();
    await expect(page.getByRole('heading', { level: 1, name: depositInvoice })).toBeVisible();
    await expect(pageSubtitle(page)).toContainText(/^Paid/);
    await expect(page.getByRole('row', { name: /Balance due/ })).toContainText('₹0');
    await expect(page.getByRole('button', { name: 'Receipt', exact: true })).toHaveCount(2);
    await expect(page.getByRole('link', { name: 'Record payment' })).toHaveCount(0);
  });

  await test.step('check out with a deduction and see the settlement', async () => {
    await page.goto(`/tenants/${tenantId}`);
    await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
    await page.getByRole('button', { name: 'Check out' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: 'Check out' })).toBeVisible();
    await expect(dialog).toContainText('Deposit held: ₹');
    await dialog.getByRole('button', { name: 'Add deduction' }).click();
    await dialog.getByLabel('Deduction 1 description').fill('Broken chair');
    await dialog.getByLabel('Deduction 1 amount').fill('500');
    await dialog.getByRole('button', { name: 'Review & check out' }).click();
    await expect(dialog).toContainText(/with 1 deduction\(s\) totalling\s*₹500/);
    await dialog.getByRole('button', { name: 'Confirm check-out' }).click();

    await expect(dialog.getByRole('heading', { name: 'Deposit settlement' })).toBeVisible();
    await expect(toast(page, 'Checked out')).toBeVisible();
    await expect(dialog.getByText('Deposit held')).toBeVisible();
    await expect(dialog.getByText('Outstanding dues')).toBeVisible();
    await expect(dialog.getByText('Deductions', { exact: true })).toBeVisible();
    await expect(dialog.getByText('− ₹500', { exact: true })).toBeVisible();
    await expect(dialog.getByText('Refund due to resident')).toBeVisible();
    await expect(dialog.getByText('Payable by resident')).toBeVisible();
    await expect(dialog.getByRole('listitem').filter({ hasText: 'Broken chair: ₹500' })).toBeVisible();
    await dialog.getByRole('button', { name: 'Done' }).click();

    await expect(dialog).toBeHidden();
    await expect(page.getByText('Not currently staying in a bed.')).toBeVisible();
    await expect(pageSubtitle(page)).toContainText('Moved out');
  });
});
