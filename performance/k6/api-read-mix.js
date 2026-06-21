import http from 'k6/http';
import { check } from 'k6';
import {
  BASE_URL,
  authHeaders,
  chooseId,
  loginAuthSession,
  refreshAuthSession,
  registerAndLoadData,
} from './common.js';

const targetRps = Number(__ENV.TARGET_RPS || 10);
const duration = __ENV.TEST_DURATION || '10m';
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || Math.max(20, targetRps * 2));
const maxVUs = Number(__ENV.MAX_VUS || Math.max(100, targetRps * 5));
const tokenRefreshSkewSeconds = Number(__ENV.TOKEN_REFRESH_SKEW_SECONDS || 60);
let authSession;

export const options = {
  scenarios: {
    mixed_read: {
      executor: 'constant-arrival-rate',
      rate: targetRps,
      timeUnit: '1s',
      duration,
      preAllocatedVUs,
      maxVUs,
    },
  },
  thresholds: {
    checks: ['rate>0.995'],
    http_req_failed: ['rate<0.005'],
    'http_req_duration{name:review_list}': ['p(95)<1000', 'p(99)<2000'],
    'http_req_duration{name:review_status}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{name:review_detail}': ['p(95)<1500', 'p(99)<3000'],
    'http_req_duration{name:dashboard_overview}': ['p(95)<2000', 'p(99)<4000'],
    'http_req_duration{name:comment_preview}': ['p(95)<1000', 'p(99)<2000'],
  },
};

export function setup() {
  return registerAndLoadData();
}

function request(url, data, name) {
  const session = ensureAuthSession(data);
  const response = http.get(url, {
    ...authHeaders(session),
    tags: { name },
  });
  check(response, {
    [`${name} returns 200`]: (value) => value.status === 200,
    [`${name} succeeds`]: (value) => value.status === 200 && value.json('success') === true,
  });
}

function ensureAuthSession(data) {
  if (!authSession) {
    authSession = loginAuthSession(data);
  }
  const refreshAtMs =
    authSession.tokenIssuedAtMs +
    Math.max(1, authSession.accessTokenExpiresInSeconds - tokenRefreshSkewSeconds) * 1000;
  if (Date.now() >= refreshAtMs) {
    refreshAuthSession(authSession);
  }
  return authSession;
}

export default function (data) {
  const roll = Math.random() * 100;
  if (roll < 30) {
    request(`${BASE_URL}/api/v1/reviews?page=1&pageSize=20`, data, 'review_list');
  } else if (roll < 60) {
    request(`${BASE_URL}/api/v1/reviews/${chooseId(data)}/status`, data, 'review_status');
  } else if (roll < 75) {
    request(`${BASE_URL}/api/v1/reviews/${chooseId(data)}`, data, 'review_detail');
  } else if (roll < 85) {
    request(`${BASE_URL}/api/v1/dashboard/overview`, data, 'dashboard_overview');
  } else if (roll < 90) {
    request(
      `${BASE_URL}/api/v1/reviews/${chooseId(data)}/github-comments/preview`,
      data,
      'comment_preview',
    );
  } else if (roll < 95) {
    request(`${BASE_URL}/api/v1/message-queue/health`, data, 'message_queue_health');
  } else {
    request(`${BASE_URL}/api/v1/auth/me`, data, 'current_user');
  }
}

export function handleSummary(data) {
  delete data.setup_data;
  return {
    stdout: `${JSON.stringify(data.metrics, null, 2)}\n`,
    [__ENV.SUMMARY_FILE || 'api-read-mix-summary-sanitized.json']: JSON.stringify(data, null, 2),
  };
}
