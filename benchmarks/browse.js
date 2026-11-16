import http from 'k6/http';
import {check} from 'k6';

export const options = {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
    scenarios: {
        read: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 20),
            timeUnit: '1s',
            duration: __ENV.DURATION || '10s',
            preAllocatedVUs: Number(__ENV.VUS || 10),
            maxVUs: Number(__ENV.MAX_VUS || 50)
        }
    },
    thresholds: {http_req_failed: ['rate<0.01'], checks: ['rate>0.99']}
};
export default function () {
    const route = __ENV.MODE === 'search' ? '/api/v1/search/products?q=headphones' : '/api/v1/products';
    const r = http.get((__ENV.BASE_URL || 'http://host.docker.internal:8097') + route);
    check(r, {'catalog responds': r.status === 200, 'results are an array': () => Array.isArray(r.json())});
}
