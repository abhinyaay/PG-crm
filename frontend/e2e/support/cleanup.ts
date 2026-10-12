import type { APIRequestContext } from '@playwright/test';

const todayInKolkata = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());

/**
 * Best-effort release of a test resident's bed (cancel a reservation / end an active stay) so a failed run does not
 * leave beds of the shared demo property occupied. Uses an operator API client; never throws.
 */
export async function releaseTenantStay(api: APIRequestContext, tenantId: number): Promise<void> {
  try {
    const allocations = await api.get(`/api/v1/tenants/${tenantId}/allocations`);
    if (allocations.ok()) {
      for (const a of (await allocations.json()) as { id: number; status: string }[]) {
        if (a.status === 'ACTIVE') {
          await api.post(`/api/v1/allocations/${a.id}/check-out`, { data: { checkOutDate: todayInKolkata(), deductions: [], sendBedToMaintenance: false } });
        }
      }
    }
    const bookings = await api.get('/api/v1/bookings', { params: { tenantId, status: 'RESERVED' } });
    if (bookings.ok()) {
      for (const b of ((await bookings.json()) as { content: { id: number }[] }).content) {
        await api.post(`/api/v1/bookings/${b.id}/cancel`, { data: { reason: 'e2e cleanup' } });
      }
    }
  } catch {
    /* best effort */
  }
}
