/* Downloads work without JavaScript. Clipboard and release metadata are enhancements. */
const checksum = document.querySelector('#apk-checksum');
const copyButton = document.querySelector('#copy-checksum');
const copyResult = document.querySelector('#copy-result');

copyButton.addEventListener('click', async () => {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
    await navigator.clipboard.writeText(checksum.textContent.trim());
    copyResult.textContent = '校验值已复制';
  } catch {
    copyResult.textContent = '浏览器未允许复制，请手动选择上方校验值。';
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
        !Number.isSafeInteger(release.bytes) || release.bytes <= 0) throw new Error('Invalid release metadata');
    const label = `${release.version} 预览版 · Android 8.0+ · ${(release.bytes / 1_000_000).toFixed(1)} MB`;
    document.querySelectorAll('[data-release-meta]').forEach(node => { node.textContent = label; });
    document.querySelectorAll('[data-download]').forEach(node => { node.href = release.apkUrl; });
    document.querySelectorAll('[data-release-link]').forEach(node => { node.href = release.releaseUrl; });
    checksum.textContent = release.sha256;
  })
  .catch(error => { console.warn('Release metadata unavailable; using the bundled download links.', error); });
