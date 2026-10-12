// k6 load test for the pgms stack. Runs in the grafana/k6 docker image on the pgms network (no local install):
//
//   ./scripts/loadtest/run.sh                         # defaults: ~35s read mix at 6 req/s + 8-way contention
//   RATE=10 DURATION=60s ./scripts/loadtest/run.sh
//
// Scenarios
//   read_mix    constant arrival rate of authenticated reads: dashboard (30%), available-beds search (40%),
//               tenants list (30%). One login in setup(), token shared by all VUs - the auth endpoints are limited
//               to ~20 requests/min per IP (nginx + backend), and all k6 traffic comes from one container IP.
//               The rate stays below nginx's 20 r/s per-IP API limit so we measure the app, not the limiter.
//   contention  after the read mix, CONTENTION_VUS VUs try to reserve the SAME available bed at the same moment
//               for different (freshly created) tenants. Expected: exactly one 201, all others 409.
//
// Side effects (cleaned up in teardown): CONTENTION_VUS tenants named "Loadtest <run> #n" are created; the winning
// reservation is cancelled and the tenants are soft-deleted afterwards.
//
// Env: BASE_URL (default http://nginx), LOADTEST_EMAIL (owner@sunrise.example.com), LOADTEST_PASSWORD (required),
//      RATE (6 iterations/s), DURATION (35s), READ_VUS (4), CONTENTION_VUS (8), BUSINESS_UTC_OFFSET_MINUTES (330)
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = (__ENV.BASE_URL || 'http://nginx').replace(/\/$/, '');
const EMAIL = __ENV.LOADTEST_EMAIL || 'owner@sunrise.example.com';
const PASSWORD = __ENV.LOADTEST_PASSWORD;
const RATE = parseInt(__ENV.RATE || '6', 10);
const DURATION = __ENV.DURATION || '35s';
const READ_VUS = parseInt(__ENV.READ_VUS || '4', 10);
const CONTENTION_VUS = parseInt(__ENV.CONTENTION_VUS || '8', 10);
const OFFSET_MIN = parseInt(__ENV.BUSINESS_UTC_OFFSET_MINUTES || '330', 10);

const durationSeconds = (d) => {
  const m = /^(\d+)(s|m)$/.exec(d);
  if (!m) throw new Error(`DURATION must look like 40s or 2m, got ${d}`);
  return parseInt(m[1], 10) * (m[2] === 'm' ? 60 : 1);
};

const reserveCreated = new Counter('reserve_created');      // 201
const reserveConflict = new Counter('reserve_conflict');    // 409 (expected for all losers)
const reserveUnexpected = new Counter('reserve_unexpected'); // anything else
const reserveLatency = new Trend('reserve_duration', true);

export const options = {
  scenarios: {
    read_mix: {
      executor: 'constant-arrival-rate',
      exec: 'readMix',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: READ_VUS,
      maxVUs: READ_VUS + 2,
      gracefulStop: '2s',
    },
    contention: {
      executor: 'per-vu-iterations',
      exec: 'reserveSameBed',
      vus: CONTENTION_VUS,
      iterations: 1,
      startTime: `${durationSeconds(DURATION) + 3}s`,
      maxDuration: '10s',
    },
  },
  thresholds: {
    // 409s from the contention scenario are declared expected (responseCallback), so they don't count as failures
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:read_mix}': ['p(95)<1500', 'p(99)<3000'],
    'http_req_duration{scenario:read_mix,endpoint:beds_search}': ['p(95)<800'],
    'http_req_duration{scenario:read_mix,endpoint:tenants_list}': ['p(95)<800'],
    'http_req_duration{scenario:read_mix,endpoint:dashboard}': ['p(95)<2500'],
    checks: ['rate>0.99'],
    reserve_created: ['count==1'],
    reserve_unexpected: ['count==0'],
    reserve_conflict: [`count==${CONTENTION_VUS - 1}`],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // setup creates tenants one by one; keep generous
  setupTimeout: '60s',
  teardownTimeout: '60s',
};

const json = (token) => ({
  headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
});

function businessToday() {
  return new Date(Date.now() + OFFSET_MIN * 60 * 1000).toISOString().slice(0, 10);
}

export function setup() {
  const RUN_ID = Date.now().toString(36);
  if (!PASSWORD) fail('LOADTEST_PASSWORD is not set (use APP_DEMO_PASSWORD from .env)');

  const login = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email: EMAIL, password: PASSWORD }),
    { ...json(), tags: { endpoint: 'login' } });
  if (login.status !== 200) fail(`login failed: ${login.status} ${login.body}`);
  const token = login.json('accessToken');

  const props = http.get(`${BASE_URL}/api/v1/properties?sort=code&size=50`, { ...json(token), tags: { endpoint: 'setup' } });
  if (props.status !== 200) fail(`properties: ${props.status}`);
  const propertyIds = props.json('content').map((p) => p.id);
  if (propertyIds.length === 0) fail('no properties visible - is the demo profile enabled?');

  // pick a random AVAILABLE bed (not content[0], which smoke-test.sh uses) for the contention scenario
  let bed = null;
  for (const pid of propertyIds) {
    const r = http.get(`${BASE_URL}/api/v1/beds?propertyId=${pid}&status=AVAILABLE&size=50`, { ...json(token), tags: { endpoint: 'setup' } });
    const beds = r.status === 200 ? r.json('content') : [];
    if (beds.length > 0) { bed = beds[Math.floor(Math.random() * beds.length)]; break; }
  }
  if (!bed) fail('no AVAILABLE bed found for the contention scenario');
  const bedPropertyId = bed.propertyId || propertyIds[0];

  const tenants = [];
  for (let i = 0; i < CONTENTION_VUS; i++) {
    const digits = `${Date.now()}${i}`.slice(-8);
    const body = {
      propertyId: bedPropertyId,
      fullName: `Loadtest ${RUN_ID} #${i + 1}`,
      email: `loadtest.${RUN_ID}.${i + 1}@example.com`,
      phone: `+9196${digits}`,
      gender: 'MALE',
    };
    const r = http.post(`${BASE_URL}/api/v1/tenants`, JSON.stringify(body), { ...json(token), tags: { endpoint: 'setup' } });
    if (r.status !== 201) fail(`create tenant ${i + 1}: ${r.status} ${r.body}`);
    tenants.push(r.json('id'));
  }
  console.log(`run ${RUN_ID}: bed ${bed.id} (property ${bedPropertyId}), ${tenants.length} contending tenants, reads on properties ${propertyIds.join(',')}`);
  return { runId: RUN_ID, token, propertyIds, bedId: bed.id, tenants, checkIn: businessToday() };
}

export function readMix(data) {
  const params = json(data.token);
  const roll = Math.random();
  let res;
  if (roll < 0.3) {
    res = http.get(`${BASE_URL}/api/v1/dashboard`, { ...params, tags: { endpoint: 'dashboard' } });
  } else if (roll < 0.7) {
    const pid = data.propertyIds[Math.floor(Math.random() * data.propertyIds.length)];
    res = http.get(`${BASE_URL}/api/v1/beds?propertyId=${pid}&status=AVAILABLE&size=20`,
      { ...params, tags: { endpoint: 'beds_search', name: `${BASE_URL}/api/v1/beds?propertyId=:id&status=AVAILABLE` } });
  } else {
    const page = Math.floor(Math.random() * 5);
    res = http.get(`${BASE_URL}/api/v1/tenants?page=${page}&size=20&sort=fullName`,
      { ...params, tags: { endpoint: 'tenants_list', name: `${BASE_URL}/api/v1/tenants?page=:n` } });
  }
  check(res, {
    'read: status 200': (r) => r.status === 200,
    'read: has body': (r) => r.body && r.body.length > 2,
  });
}

export function reserveSameBed(data) {
  const i = exec.vu.idInTest % data.tenants.length;
  // all contention VUs start at the same instant (same startTime), so the POSTs overlap
  const res = http.post(`${BASE_URL}/api/v1/bookings`,
    JSON.stringify({ tenantId: data.tenants[i], bedId: data.bedId, expectedCheckInDate: data.checkIn }),
    { ...json(data.token), tags: { endpoint: 'reserve' }, responseCallback: http.expectedStatuses(201, 409) });
  reserveLatency.add(res.timings.duration);
  if (res.status === 201) reserveCreated.add(1);
  else if (res.status === 409) reserveConflict.add(1);
  else { reserveUnexpected.add(1); console.error(`unexpected reserve status ${res.status}: ${res.body}`); }
  check(res, { 'reserve: 201 or 409': (r) => r.status === 201 || r.status === 409 });
}

export function teardown(data) {
  if (!data || !data.token) return;
  const params = { ...json(data.token), tags: { endpoint: 'teardown' } };
  // cancel whatever reservation won, then soft-delete the synthetic tenants
  for (const tenantId of data.tenants) {
    const r = http.get(`${BASE_URL}/api/v1/bookings?tenantId=${tenantId}&status=RESERVED`, params);
    if (r.status === 200) {
      for (const b of r.json('content')) {
        const c = http.post(`${BASE_URL}/api/v1/bookings/${b.id}/cancel`, JSON.stringify({ reason: `k6 load test ${data.runId} cleanup` }), params);
        console.log(`cancelled booking ${b.id} (bed ${b.bedId}) -> ${c.status}`);
      }
    }
  }
  let deleted = 0;
  for (const tenantId of data.tenants) {
    if (http.del(`${BASE_URL}/api/v1/tenants/${tenantId}`, null, params).status === 204) deleted++;
  }
  console.log(`soft-deleted ${deleted}/${data.tenants.length} load-test tenants`);
}
