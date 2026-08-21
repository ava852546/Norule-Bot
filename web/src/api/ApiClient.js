export function createApiClient() {
  let csrfToken = '';

  return async function api(url, options = {}) {
    const method = String(options.method || 'GET').toUpperCase();
    const headers = new Headers(options.headers || {});
    headers.set('Accept', 'application/json');
    if (csrfToken && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
      headers.set('X-CSRF-Token', csrfToken);
    }
    const response = await fetch(url, {
      credentials: 'same-origin',
      ...options,
      headers
    });
    const text = await response.text();
    let data = {};
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        data = { error: text };
      }
    }
    if (!response.ok) {
      if (response.status === 401) csrfToken = '';
      throw new Error(data?.error || data?.message || `HTTP ${response.status}`);
    }
    if (String(url).split('?', 1)[0] === '/api/me') {
      csrfToken = typeof data?.csrfToken === 'string' ? data.csrfToken : '';
    }
    return data;
  };
}
