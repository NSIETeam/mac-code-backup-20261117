// Percentages reflect received bytes; an unknown total remains indeterminate.
export async function readDownload(response, progress) {
  if (!response.ok) throw Error('文件链接已过期，请重新下载');
  const length = Number(response.headers.get('content-length'));
  const total = Number.isFinite(length) && length > 0 ? length : 0;
  const parts = []; let received = 0;
  progress(total ? 0 : null, 0);
  if (response.body?.getReader) {
    const reader = response.body.getReader();
    try { for (;;) { const {done,value} = await reader.read(); if (done) break;
      parts.push(value); received += value.byteLength;
      progress(total ? Math.min(99, Math.floor(received * 100 / total)) : null, received);
    } } finally { reader.releaseLock(); }
  } else { const buffer = await response.arrayBuffer(); parts.push(buffer); received = buffer.byteLength; }
  if (!received || (total && received !== total)) throw Error('文件未下载完整，请重试');
  return new Blob(parts, {type: response.headers.get('content-type') || 'application/octet-stream'});
}
