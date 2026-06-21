import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE_URL, RUN_ID } from './common.js';

const adminApiKey = __ENV.ADMIN_API_KEY || '';
const adminHeaderName = __ENV.ADMIN_HEADER || 'X-RepoGuard-Admin-Key';
const concurrency = Number(__ENV.CONCURRENCY || 100);
const startPrNumber = Number(__ENV.START_PR_NUMBER || 500000000);
const runSuffix = __ENV.RUN_SUFFIX || `${Date.now()}-${Math.floor(Math.random() * 1000000)}`;

export const createdResponses = new Counter('manual_create_created_responses');
export const existingResponses = new Counter('manual_create_existing_responses');
export const publishFailedResponses = new Counter('manual_create_publish_failed_responses');
export const unexpected5xx = new Counter('manual_create_unexpected_5xx');

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    burst_create_unique_reviews: {
      executor: 'shared-iterations',
      exec: 'createUniqueReview',
      vus: concurrency,
      iterations: concurrency,
      maxDuration: __ENV.MAX_DURATION || '60s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    'http_req_failed{name:review_create_burst}': ['rate<0.005'],
    'http_req_duration{name:review_create_burst}': ['p(95)<500', 'p(99)<1000'],
    manual_create_unexpected_5xx: ['count==0'],
    manual_create_publish_failed_responses: ['count==0'],
  },
};

export function setup() {
  if (!adminApiKey) {
    throw new Error('ADMIN_API_KEY is required');
  }
  return { runSuffix, startPrNumber, concurrency };
}

export function createUniqueReview(data) {
  const iteration = exec.scenario.iterationInTest;
  const prNumber = data.startPrNumber + iteration;
  const commit = (`${String(prNumber).padStart(8, '0')}0123456789abcdef0123456789abcdef`).slice(0, 40);
  const payload = {
    organization: 'pt-org',
    repository: 'repo-01',
    prNumber,
    title: `PT ${RUN_ID} mq burst ${data.runSuffix} ${prNumber}`,
    commit,
    branch: `perf/mq-burst-${data.runSuffix}-${iteration}`,
    source: 'PERFORMANCE_TEST',
  };
  const response = http.post(`${BASE_URL}/api/v1/reviews/manual`, JSON.stringify(payload), {
    headers: {
      [adminHeaderName]: adminApiKey,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    tags: { name: 'review_create_burst' },
  });

  if (response.status >= 500) {
    unexpected5xx.add(1);
  }

  const existing = response.json('data.existing');
  const status = response.json('data.status');
  if (existing === true) {
    existingResponses.add(1);
  } else {
    createdResponses.add(1);
  }
  if (status === 'publish_failed') {
    publishFailedResponses.add(1);
  }

  check(response, {
    'manual create returns 200': (value) => value.status === 200,
    'manual create succeeds': (value) => value.status === 200 && value.json('success') === true,
    'manual create has task id': (value) => Boolean(value.json('data.taskId')),
    'manual create is new task': (value) => value.json('data.existing') !== true,
    'manual create has no publish failure': (value) => value.json('data.status') !== 'publish_failed',
  });
}

export function handleSummary(data) {
  delete data.setup_data;
  data.test_metadata = { runSuffix, startPrNumber, concurrency };
  return {
    stdout: `${JSON.stringify(data.metrics, null, 2)}\n`,
    [__ENV.SUMMARY_FILE || `review-burst-${concurrency}.json`]: JSON.stringify(data, null, 2),
  };
}
