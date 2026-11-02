// Chunks are streamed into Android private storage while the response is playing.
// The bridge returns false on any failed write; a partial file is never published.
export function createClipArchive(bridge, title, mime, onError = () => {}) {
  if (!title?.trim()) return null; // Idle animation is not a user reaction.
  let id;
  try { id = bridge?.beginClip(title, mime); } catch { id = ''; }
  if (!id) { onError(); return null; }
  let pending = Promise.resolve();
  let failed = false;
  const fail = () => {
    if (failed) return;
    failed = true;
    try { bridge.abortClip(id); } catch {}
    onError();
  };
  return {
    append(blob) {
      pending = pending.then(async () => {
        if (failed || !blob?.size) return;
        const bytes = new Uint8Array(await blob.arrayBuffer());
        for (let offset = 0; offset < bytes.length; offset += 96 * 1024) {
          const part = bytes.subarray(offset, offset + 96 * 1024);
          let binary = '';
          for (let i = 0; i < part.length; i += 8192)
            binary += String.fromCharCode(...part.subarray(i, i + 8192));
          if (!bridge.appendClip(id, btoa(binary))) throw new Error('Clip write failed');
        }
      }).catch(fail);
    },
    finish() {
      pending = pending.then(() => {
        if (!failed && !bridge.finishClip(id)) throw new Error('Clip finalization failed');
      }).catch(fail);
      return pending;
    },
  };
}
