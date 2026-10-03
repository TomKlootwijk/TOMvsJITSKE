/* Runs inside functions.exec; consumes only completed local upload receipts. */
async function runDriveReadback(tools, hashCode, emit, pause, config) {
  if (!config || !config.evidenceRoot) throw Error('An explicit evidenceRoot snapshot directory is required');
  const quote = value => "'" + String(value).replaceAll("'", "''") + "'";
  const snapshotArgs = ' --root ' + quote(config.evidenceRoot)
    + (config.journals || ['google-drive-files.jsonl']).map(name => ' --journal ' + quote(name)).join('');
  const {decodeBase64, encodeAsciiBase64, sha256} = new Function(hashCode
    + ';return {decodeBase64,encodeAsciiBase64,sha256};')();
  const python = 'C:\\Users\\ietsm\\.cache\\codex-runtimes\\codex-primary-runtime\\dependencies\\python\\python.exe';
  const helper = 'tools\\drive_readback_state.py';
  const started = Date.now();
  const failures = new Map();
  let checked = 0, bytesChecked = 0, lastNotice = 0;
  async function state() {
    const result = await tools.exec_command({cmd: "& '" + python + "' '" + helper + "' next --limit 8" + snapshotArgs, max_output_tokens: 5000});
    if (result.exit_code !== 0) throw Error('Cannot read verification checkpoint: ' + result.output);
    return JSON.parse(result.output);
  }
  async function persist(items) {
    if (!items.length) return;
    const payload = encodeAsciiBase64(JSON.stringify(items));
    const result = await tools.exec_command({cmd: "& '" + python + "' '" + helper + "' record '" + payload + "'" + snapshotArgs, max_output_tokens: 300});
    if (result.exit_code !== 0) throw Error('Cannot save verification checkpoint: ' + result.output);
  }
  for (;;) {
    const current = await state();
    if (current.verified >= current.expected) {
      emit({complete:true, expected:current.expected, verified:current.verified});
      return;
    }
    if (!current.batch.length) {
      if (config.stopWhenCaughtUp) {
        emit({caughtUp:true, uploaded:current.uploaded, verified:current.verified, expected:current.expected});
        return;
      }
      if (Date.now() - lastNotice >= 60000) {
        emit({waitingForUploads:true, uploaded:current.uploaded, verified:current.verified,
          expected:current.expected, sessionVerified:checked, sessionBytes:bytesChecked});
        lastNotice = Date.now();
      }
      await pause(30000);
      continue;
    }
    const successes = [], errors = [];
    let cursor = 0;
    async function worker() {
      while (cursor < current.batch.length) {
        const item = current.batch[cursor++];
        try {
          const response = await tools.mcp__codex_apps__google_drive_fetch({
            url:'https://drive.google.com/file/d/' + item.id + '/view',
            download_raw_file:true, include_base64:true,
          });
          if (response.isError) throw Error('Connector reported an error');
          const b64 = (response.structuredContent || {}).b64_string;
          if (typeof b64 !== 'string') throw Error('Connector did not return complete inline bytes');
          const bytes = decodeBase64(b64), actual = sha256(bytes);
          if (bytes.length !== item.size || actual !== item.sha256) {
            throw Error('REMOTE BYTE MISMATCH: size ' + bytes.length + ', sha256 ' + actual);
          }
          successes.push({...item, verified:true, expected_size:item.size,
            expected_sha256:item.sha256, verified_at_utc:new Date().toISOString(),
            method:'Drive raw-file readback; base64 decoded and SHA-256 computed over all bytes'});
          checked++; bytesChecked += bytes.length; failures.delete(item.id);
        } catch (failure) {
          const attempts = (failures.get(item.id) || 0) + 1;
          failures.set(item.id, attempts);
          errors.push({id:item.id, relative_path:item.relative_path, attempts,
            error:String(failure.message || failure)});
        }
      }
    }
    await Promise.all(Array.from({length:4}, worker));
    await persist(successes);
    emit({verified:current.verified + successes.length, uploaded:current.uploaded,
      expected:current.expected, batchVerified:successes.length,
      elapsedSeconds:Math.round((Date.now()-started)/1000), errors});
    lastNotice = Date.now();
    if (errors.some(item => item.error.includes('MISMATCH') || item.attempts >= 3)) return;
  }
}

if (typeof module !== 'undefined') module.exports = {runDriveReadback};
