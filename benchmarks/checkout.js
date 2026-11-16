import http from 'k6/http';
import {check} from 'k6';
import exec from 'k6/execution';
// Supply independently created, unexpired quotes with their session and CSRF credentials.
// One fixture per attempt. Reusing a fixture intentionally benchmarks idempotency only.
const fixtures = JSON.parse(open(__ENV.FIXTURES));
export const options = {
    scenarios: {
        checkout: {
            executor: 'shared-iterations',
            vus: Number(__ENV.VUS || 10),
            iterations: fixtures.length,
            maxDuration: '4m'
        }
    }, thresholds: {checks: ['rate>0.99']}
};
export default function () {
    const f = fixtures[exec.scenario.iterationInTest];
    const r = http.post((__ENV.BASE_URL || 'http://host.docker.internal:8097') + '/api/v1/checkouts', JSON.stringify({quoteId: f.quoteId}), {
        headers: {
            'Content-Type': 'application/json',
            'Cookie': f.cookie,
            'X-CSRF-TOKEN': f.csrf,
            'Idempotency-Key': f.key
        }
    });
    check(r, {'accepted or admission limited': () => [200, 409, 429].includes(r.status)});
}
