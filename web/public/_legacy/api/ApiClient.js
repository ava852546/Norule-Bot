export function createApiClient() {
  return async function api(url, options = {}) {
    const response = await fetch(url, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json', ...(options.headers || {}) },
      ...options
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
      throw new Error(data?.error || data?.message || `HTTP ${response.status}`);
    }
    return data;
  };
}
