import http from 'k6/http';
import { check } from 'k6';
const rate = Number(__ENV.RATE || 100);
export const options = {
  discardResponseBodies: true,
  scenarios: { publish: { executor: 'constant-arrival-rate', rate, timeUnit: '1s',
    duration: __ENV.DURATION || '60s', preAllocatedVUs: Math.max(20, rate), maxVUs: rate * 4 } },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0; return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16); });
}
const headers = { 'Content-Type': 'application/json', 'X-Auth-User': 'bench-client',
  'X-Auth-Tenant': 'loadtest', 'X-Auth-Scopes': 'audit-event:write' };
export default function () {
  const event = { eventId: uuid(), eventType: 'api.call', sourceSystem: 'labs64io/loadtest',
    eventTime: new Date().toISOString(), correlationId: `loadtest-${__VU}-${__ITER}`,
    extra: { vendorNumber: 'V00000000', actionMethod: 'loadtest/publish', statusCode: 200,
      actionStatus: 'SUCCESS', durationMs: 1 } };
  const res = http.post('http://backend:8080/audit/publish', JSON.stringify(event), { headers });
  check(res, { '200': (r) => r.status === 200 });
}
