/* Downloads work without JavaScript. Clipboard and release metadata are enhancements. */
const language = document.documentElement.lang.startsWith('zh') ? 'zh' : 'en';
const strings = language === 'zh' ? {
  copied: '校验值已复制', copyFailed: '浏览器未允许复制，请手动选择上方校验值。', preview: '预览版',
} : {
  copied: 'Checksum copied', copyFailed: 'Copying is not allowed in this browser. Select the checksum above to copy it manually.', preview: 'Preview',
};
const requestedLanguage = new URLSearchParams(location.search).get('lang')
  || (location.pathname.endsWith('/en.html') ? 'en' : null);
let savedLanguage;
try { savedLanguage = localStorage.getItem('sms-ferry-language'); } catch { /* Language links still work without storage. */ }
if (requestedLanguage === 'zh' || requestedLanguage === 'en') {
  savedLanguage = requestedLanguage;
  try { localStorage.setItem('sms-ferry-language', requestedLanguage); } catch { /* URL preserves this choice. */ }
}
const preferredLanguage = ['zh', 'en'].includes(savedLanguage) ? savedLanguage
  : ((navigator.languages?.[0] || navigator.language || 'en').toLowerCase().startsWith('zh') ? 'zh' : 'en');
if (preferredLanguage !== language) {
  location.replace(`${preferredLanguage === 'zh' ? './' : './en.html'}?lang=${preferredLanguage}${location.hash}`);
}
document.querySelectorAll('[data-language]').forEach(link => {
  link.addEventListener('click', () => {
    try { localStorage.setItem('sms-ferry-language', link.dataset.language); } catch { /* Native navigation remains available. */ }
    // Keep the reader's current section when changing language.
    link.hash = location.hash;
  });
});
const checksum = document.querySelector('#apk-checksum');
const copyButton = document.querySelector('#copy-checksum');
const copyResult = document.querySelector('#copy-result');

copyButton.addEventListener('click', async () => {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
    await navigator.clipboard.writeText(checksum.textContent.trim());
    copyResult.textContent = strings.copied;
  } catch {
    copyResult.textContent = strings.copyFailed;
  }
});

fetch('./release.json')
  .then(response => {
    if (!response.ok) throw new Error(`Release metadata: ${response.status}`);
    return response.json();
  })
  .then(release => {
    // Only repository release links can become download targets.
    const expectedPrefix = 'https://github.com/soBigRice/sms-ferry/releases/';
    if (typeof release.version !== 'string' || !/^\d+\.\d+\.\d+$/.test(release.version) ||
        typeof release.apkUrl !== 'string' || !release.apkUrl.startsWith(`${expectedPrefix}download/`) ||
        typeof release.releaseUrl !== 'string' || !release.releaseUrl.startsWith(`${expectedPrefix}tag/`) ||
        typeof release.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(release.sha256) ||
        !Number.isSafeInteger(release.bytes) || release.bytes <= 0 ||
        typeof release.minAndroid !== 'string' || !/^\d+\.\d+$/.test(release.minAndroid)) throw new Error('Invalid release metadata');
    const label = `${release.version} ${strings.preview} · Android ${release.minAndroid}+ · ${(release.bytes / 1_000_000).toFixed(1)} MB`;
    document.querySelectorAll('[data-release-meta]').forEach(node => { node.textContent = label; });
    document.querySelectorAll('[data-download]').forEach(node => { node.href = release.apkUrl; });
    document.querySelectorAll('[data-release-link]').forEach(node => { node.href = release.releaseUrl; });
    checksum.textContent = release.sha256;
  })
  .catch(error => { console.warn('Release metadata unavailable; using the bundled download links.', error); });
