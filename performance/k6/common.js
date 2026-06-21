import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1').replace(/\/$/, '');
export const RUN_ID = __ENV.RUN_ID || 'RG-PT-20260621-104152';

export function registerAndLoadData() {
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
  const username = `pt_${RUN_ID.replace(/[^A-Za-z0-9]/g, '_')}_${suffix}`.slice(0, 64);
  const password = `Pt${suffix}Aa9!`;
  const registerResponse = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({
      username,
      email: `${username}@example.test`,
      password,
      confirmPassword: password,
    }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'auth_register_setup' },
    },
  );

  const registered = check(registerResponse, {
    'setup registration succeeds': (response) => response.status === 200,
  });
  if (!registered) {
    fail(`Registration failed: HTTP ${registerResponse.status} ${registerResponse.body}`);
  }

  const token = registerResponse.json('data.accessToken');
  const refreshToken = registerResponse.json('data.refreshToken');
  const accessTokenExpiresInSeconds = Number(
    registerResponse.json('data.accessTokenExpiresInSeconds') || 900,
  );
  if (!token || !refreshToken) {
    fail('Registration response did not contain an access/refresh token pair');
  }

  const headers = { Authorization: `Bearer ${token}` };
  const listResponse = http.get(`${BASE_URL}/api/v1/reviews?page=1&pageSize=100`, {
    headers,
    tags: { name: 'review_list_setup' },
  });
  const loaded = check(listResponse, {
    'setup review list succeeds': (response) => response.status === 200,
    'setup review list has items': (response) => (response.json('data.items') || []).length > 0,
  });
  if (!loaded) {
    fail(`Review list setup failed: HTTP ${listResponse.status} ${listResponse.body}`);
  }

  const ids = listResponse.json('data.items').map((item) => item.id);
  return {
    username,
    password,
    ids,
  };
}

export function authHeaders(data) {
  return {
    headers: {
      Authorization: `Bearer ${data.token}`,
      Accept: 'application/json',
    },
  };
}

export function refreshAuthSession(session) {
  const response = http.post(
    `${BASE_URL}/api/v1/auth/refresh`,
    JSON.stringify({ refreshToken: session.refreshToken }),
    {
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      tags: { name: 'auth_refresh' },
    },
  );
  const refreshed = check(response, {
    'auth refresh returns 200': (value) => value.status === 200,
    'auth refresh succeeds': (value) => value.status === 200 && value.json('success') === true,
    'auth refresh returns token pair': (value) =>
      Boolean(value.json('data.accessToken')) && Boolean(value.json('data.refreshToken')),
  });
  if (!refreshed) {
    fail(`Authentication refresh failed: HTTP ${response.status} ${response.body}`);
  }
  session.token = response.json('data.accessToken');
  session.refreshToken = response.json('data.refreshToken');
  session.accessTokenExpiresInSeconds = Number(
    response.json('data.accessTokenExpiresInSeconds') || session.accessTokenExpiresInSeconds || 900,
  );
  session.tokenIssuedAtMs = Date.now();
  return session;
}

export function loginAuthSession(credentials) {
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({
      account: credentials.username,
      password: credentials.password,
      remember: false,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      tags: { name: 'auth_login_session' },
    },
  );
  const loggedIn = check(response, {
    'session login returns 200': (value) => value.status === 200,
    'session login succeeds': (value) => value.status === 200 && value.json('success') === true,
    'session login returns token pair': (value) =>
      Boolean(value.json('data.accessToken')) && Boolean(value.json('data.refreshToken')),
  });
  if (!loggedIn) {
    fail(`Session login failed: HTTP ${response.status} ${response.body}`);
  }
  return {
    token: response.json('data.accessToken'),
    refreshToken: response.json('data.refreshToken'),
    accessTokenExpiresInSeconds: Number(
      response.json('data.accessTokenExpiresInSeconds') || 900,
    ),
    tokenIssuedAtMs: Date.now(),
  };
}

export function chooseId(data) {
  return data.ids[Math.floor(Math.random() * data.ids.length)];
}
