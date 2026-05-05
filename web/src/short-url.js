const form = document.getElementById('shortUrlForm');
const urlInput = document.getElementById('targetUrl');
const codeInput = document.getElementById('customCode');
const submitButton = document.getElementById('createShortUrlBtn');
const resultCard = document.getElementById('resultCard');
const resultUrl = document.getElementById('resultUrl');
const resultTarget = document.getElementById('resultTarget');
const errorText = document.getElementById('errorText');
const copyButton = document.getElementById('copyShortUrlBtn');
const uiLangSelect = document.getElementById('uiLangSelect');

const shortUrlTitle = document.getElementById('shortUrlTitle');
const shortUrlSubtitle = document.getElementById('shortUrlSubtitle');
const targetUrlLabel = document.getElementById('targetUrlLabel');
const customCodeLabel = document.getElementById('customCodeLabel');
const resultTitle = document.getElementById('resultTitle');
const resultTargetLabel = document.getElementById('resultTargetLabel');
const uiLangLabel = document.getElementById('uiLangLabel');

if (!form
  || !urlInput
  || !submitButton
  || !resultCard
  || !resultUrl
  || !errorText
  || !copyButton
  || !uiLangSelect
  || !shortUrlTitle
  || !shortUrlSubtitle
  || !targetUrlLabel
  || !customCodeLabel
  || !resultTitle
  || !resultTargetLabel
  || !uiLangLabel) {
  throw new Error('Short URL page is missing required DOM elements.');
}

const LANG_STORAGE_KEY = 'norule.shorturl.ui.lang';
const DEFAULT_LANG = 'zh-TW';
const I18N = {
  'zh-TW': {
    pageTitle: '\u77ed\u7db2\u5740',
    uiLangLabel: '\u8a9e\u8a00',
    title: '\u77ed\u7db2\u5740',
    subtitle: '\u5efa\u7acb\u77ed\u9023\u7d50\u4e26\u7acb\u5373\u5206\u4eab\u3002',
    targetUrlLabel: '\u76ee\u6a19\u7db2\u5740',
    targetUrlPlaceholder: 'https://example.com/path',
    customCodeLabel: '\u81ea\u8a02\u4ee3\u78bc\uff08\u9078\u586b\uff09',
    customCodePlaceholder: 'my-link',
    createButton: '\u5efa\u7acb\u77ed\u7db2\u5740',
    creatingButton: '\u5efa\u7acb\u4e2d...',
    resultTitle: '\u7d50\u679c',
    resultTargetLabel: '\u76ee\u6a19',
    copyButton: '\u8907\u88fd',
    copiedButton: '\u5df2\u8907\u88fd',
    errorEnterUrl: '\u8acb\u8f38\u5165\u7db2\u5740\u3002',
    errorCreateFailed: '\u5efa\u7acb\u77ed\u7db2\u5740\u5931\u6557\u3002',
    errorRequestFailed: '\u8acb\u6c42\u5931\u6557\u3002',
    errorClipboardDenied: '\u526a\u8cbc\u7c3f\u6b0a\u9650\u88ab\u62d2\u7d55\u3002',
    errorInvalidUrlOrCode: '\u7db2\u5740\u6216\u81ea\u8a02\u4ee3\u78bc\u7121\u6548\u3002',
    errorMissingUrl: '\u7f3a\u5c11\u7db2\u5740\u53c3\u6578\u3002',
    errorMethodNotAllowed: '\u4e0d\u652f\u63f4\u7684\u8acb\u6c42\u65b9\u5f0f\u3002',
    errorTargetAlreadyShortened: '\u6b64\u7db2\u5740\u5df2\u5b58\u5728\u77ed\u9023\u7d50\uff1a{url}'
  },
  en: {
    pageTitle: 'Short URL',
    uiLangLabel: 'Language',
    title: 'Short URL',
    subtitle: 'Create a short link and share it instantly.',
    targetUrlLabel: 'Target URL',
    targetUrlPlaceholder: 'https://example.com/path',
    customCodeLabel: 'Custom Code (Optional)',
    customCodePlaceholder: 'my-link',
    createButton: 'Create Short URL',
    creatingButton: 'Creating...',
    resultTitle: 'Result',
    resultTargetLabel: 'Target',
    copyButton: 'Copy',
    copiedButton: 'Copied',
    errorEnterUrl: 'Please enter a URL.',
    errorCreateFailed: 'Failed to create short URL.',
    errorRequestFailed: 'Request failed.',
    errorClipboardDenied: 'Clipboard permission denied.',
    errorInvalidUrlOrCode: 'Invalid URL or custom code.',
    errorMissingUrl: 'Missing URL parameter.',
    errorMethodNotAllowed: 'Method not allowed.',
    errorTargetAlreadyShortened: 'This URL already has a short link: {url}'
  }
};

let currentLang = DEFAULT_LANG;

const resolveLang = (value) => {
  if (!value) return DEFAULT_LANG;
  return I18N[value] ? value : DEFAULT_LANG;
};

const t = (key) => I18N[currentLang]?.[key] ?? I18N[DEFAULT_LANG][key] ?? key;

const setError = (message) => {
  errorText.textContent = message || '';
  errorText.classList.toggle('hidden', !message);
};

const resetResult = () => {
  resultUrl.textContent = '';
  resultUrl.removeAttribute('href');
  resultTarget.textContent = '';
  resultCard.classList.add('hidden');
};

const setLoading = (loading) => {
  submitButton.disabled = loading;
  submitButton.textContent = loading ? t('creatingButton') : t('createButton');
};

const mapBackendError = (message) => {
  const raw = String(message || '').trim();
  const key = raw.toLowerCase();
  if (!raw) return t('errorCreateFailed');
  if (key.includes('invalid url or code')) return t('errorInvalidUrlOrCode');
  if (key.includes('missing url')) return t('errorMissingUrl');
  if (key.includes('method not allowed')) return t('errorMethodNotAllowed');
  return raw;
};

const mapBackendPayloadError = (payload) => {
  const code = String(payload?.errorCode || '').trim().toUpperCase();
  if (code === 'TARGET_ALREADY_SHORTENED') {
    return t('errorTargetAlreadyShortened').replace('{url}', String(payload?.url || '').trim() || '-');
  }
  if (code === 'INVALID_URL_OR_CODE') return t('errorInvalidUrlOrCode');
  if (code === 'MISSING_URL') return t('errorMissingUrl');
  if (code === 'METHOD_NOT_ALLOWED') return t('errorMethodNotAllowed');
  return mapBackendError(payload?.error);
};

const applyLanguage = (lang) => {
  currentLang = resolveLang(lang);
  localStorage.setItem(LANG_STORAGE_KEY, currentLang);
  document.documentElement.lang = currentLang;
  document.title = t('pageTitle');

  uiLangSelect.value = currentLang;
  uiLangLabel.textContent = t('uiLangLabel');
  shortUrlTitle.textContent = t('title');
  shortUrlSubtitle.textContent = t('subtitle');
  targetUrlLabel.textContent = t('targetUrlLabel');
  customCodeLabel.textContent = t('customCodeLabel');
  resultTitle.textContent = t('resultTitle');
  resultTargetLabel.textContent = t('resultTargetLabel');

  urlInput.placeholder = t('targetUrlPlaceholder');
  codeInput.placeholder = t('customCodePlaceholder');

  copyButton.textContent = t('copyButton');
  setLoading(submitButton.disabled);
};

const showResult = (shortUrl, targetUrl) => {
  resultUrl.textContent = shortUrl;
  resultUrl.href = shortUrl;
  resultTarget.textContent = targetUrl;
  resultCard.classList.remove('hidden');
};

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const url = urlInput.value.trim();
  const code = codeInput ? codeInput.value.trim() : '';

  if (!url) {
    setError(t('errorEnterUrl'));
    return;
  }

  setError('');
  resetResult();
  setLoading(true);

  try {
    const body = new URLSearchParams();
    body.set('url', url);
    if (code) {
      body.set('code', code);
    }

    const response = await fetch('/api/short', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
      },
      body: body.toString()
    });

    let payload = null;
    try {
      payload = await response.json();
    } catch {
      payload = null;
    }

    if (!response.ok || !payload?.ok || !payload?.url) {
      const errorCode = String(payload?.errorCode || '').trim().toUpperCase();
      if (errorCode === 'TARGET_ALREADY_SHORTENED' && payload?.url) {
        showResult(String(payload.url), String(payload?.target || url));
      }
      setError(mapBackendPayloadError(payload || {}));
      return;
    }

    showResult(payload.url, payload.target || url);
  } catch (error) {
    setError(error instanceof Error ? error.message : t('errorRequestFailed'));
  } finally {
    setLoading(false);
  }
});

copyButton.addEventListener('click', async () => {
  const text = resultUrl.textContent?.trim();
  if (!text) {
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    copyButton.textContent = t('copiedButton');
    setTimeout(() => {
      copyButton.textContent = t('copyButton');
    }, 1200);
  } catch {
    setError(t('errorClipboardDenied'));
  }
});

uiLangSelect.addEventListener('change', () => {
  applyLanguage(uiLangSelect.value);
});

applyLanguage(resolveLang(localStorage.getItem(LANG_STORAGE_KEY) || DEFAULT_LANG));
