(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const audio = $('audio');
  const state = { queue: [], index: -1, resolution: 0, searching: false, evidence: [] };
  const prefsKey = 'blackyt.preferences.v1';
  const prefFields = ['mode', 'include', 'exclude'];
  const safeText = (value, fallback = '') => typeof value === 'string' ? value : fallback;
  const terms = (value) => value.split(',').map(s => s.trim()).filter(Boolean);
  const log = (event, detail = {}) => {
    state.evidence.push({ at: new Date().toISOString(), event, ...detail });
    if (state.evidence.length > 500) state.evidence.shift();
  };
  function savePrefs() {
    try { localStorage.setItem(prefsKey, JSON.stringify({ mode: $('mode').value, include: $('include').value, exclude: $('exclude').value })); } catch (_) {}
  }
  function loadPrefs() {
    try {
      const saved = JSON.parse(localStorage.getItem(prefsKey) || '{}');
      if (saved && typeof saved === 'object') {
        if (saved.mode === 'music' || saved.mode === 'interests') $('mode').value = saved.mode;
        for (const name of ['include', 'exclude']) if (typeof saved[name] === 'string') $(name).value = saved[name];
      }
    } catch (_) {}
  }
  for (const id of prefFields) $(id).addEventListener('change', savePrefs);
  function requestFilters() { return { mode: $('mode').value, include: terms($('include').value), exclude: terms($('exclude').value) }; }
  async function api(path, body) {
    let response;
    try {
      response = await fetch(path, body ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : { headers: { 'Accept': 'application/json' } });
    } catch (error) { throw Object.assign(new Error('The player service could not be reached. Check your connection and try again.'), { code: 'NETWORK' }); }
    let data = {};
    try { data = await response.json(); } catch (_) {}
    if (!response.ok) {
      const error = safeText(data.error, `Request failed (${response.status}).`);
      const detail = safeText(data.detail);
      throw Object.assign(new Error(detail ? `${error} ${detail}` : error), { code: safeText(data.code, `HTTP_${response.status}`), status: response.status });
    }
    return data;
  }
  function notice(id, text, error = false) { const el = $(id); el.textContent = text; el.classList.toggle('error', error); }
  function mediaError(error) {
    const code = error && error.code;
    const messages = {
      NETWORK: 'Could not reach the player. Check your connection and retry.',
      NOT_FOUND: 'No playable stream was found for this track. Try another result.',
      CATEGORY: 'This result did not pass the music category check. Choose another track or switch to Interests.',
      EXPIRED: 'That stream link expired. Play the track again to request a fresh one.',
      RATE_LIMITED: 'The stream service is busy. Wait a moment, then retry.',
      UNSUPPORTED: 'The returned stream format cannot be played by this browser.',
      PLAY_REJECTED: 'Your browser did not start playback. Tap Play on the audio controls to try again.',
      HOST: 'The service returned a stream outside the allowed YouTube audio host. Playback was blocked.'
    };
    return messages[code] || (error && error.message) || 'Could not start this track. Try again or choose another result.';
  }
  function drawQueue() {
    $('queue-count').textContent = `(${state.queue.length})`;
    const root = $('queue-items'); root.replaceChildren();
    if (!state.queue.length) { const p = document.createElement('p'); p.className = 'notice'; p.textContent = 'Add a search result to start a queue.'; root.append(p); return; }
    state.queue.forEach((item, index) => {
      const row = document.createElement('div'); row.className = 'queue-row';
      const label = document.createElement('button'); label.type = 'button'; label.className = 'queue-select'; label.textContent = `${index === state.index ? '▶ ' : ''}${item.title} · ${item.channel}`; label.title = item.title; label.setAttribute('aria-label', `Play ${item.title} by ${item.channel}`);
      const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = 'Remove'; remove.setAttribute('aria-label', `Remove ${item.title} from queue`);
      remove.addEventListener('click', () => {
        const wasCurrent = index === state.index; state.queue.splice(index, 1);
        if (index < state.index) state.index--;
        else if (wasCurrent) { ++state.resolution; audio.pause(); audio.removeAttribute('src'); audio.load(); state.index = -1; $('now-title').textContent = 'Choose a track'; $('now-sub').textContent = 'Your queue is ready when you are.'; $('stream-host').textContent = 'No stream loaded'; if ('mediaSession' in navigator) { try { navigator.mediaSession.metadata = null; navigator.mediaSession.playbackState = 'none'; } catch (_) {} } }
        drawQueue(); log('queue-remove', { position: index + 1 });
      });
      label.addEventListener('click', () => startAt(index));
      row.append(label, remove); root.append(row);
    });
  }
  function addToQueue(item, playNow = false) {
    if (!item || typeof item.id !== 'string' || !item.id) return;
    const normalized = { id: item.id, title: safeText(item.title, 'Untitled track'), channel: safeText(item.channel, 'Unknown channel'), duration: safeText(item.duration), thumbnail: safeText(item.thumbnail), matchBasis: safeText(item.matchBasis) };
    state.queue.push(normalized); drawQueue(); log('queue-add', { position: state.queue.length });
    if (playNow) startAt(state.queue.length - 1);
  }
  function setMediaMetadata(item) {
    if (!('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.metadata = new MediaMetadata({ title: item.title, artist: item.channel, album: 'BlackYT', artwork: item.thumbnail ? [{ src: item.thumbnail }] : [] });
    } catch (_) {}
  }
  function setPlaybackState(value) { if ('mediaSession' in navigator) { try { navigator.mediaSession.playbackState = value; } catch (_) {} } }
  async function startAt(index) {
    if (index < 0 || index >= state.queue.length) return;
    const item = state.queue[index], ticket = ++state.resolution;
    state.index = index; drawQueue(); audio.pause(); audio.removeAttribute('src'); audio.load();
    $('now-title').textContent = item.title; $('now-sub').textContent = `${item.channel}${item.duration ? ` · ${item.duration}` : ''} · finding direct audio…`; $('stream-host').textContent = 'Resolving…';
    notice('search-notice', 'Finding a direct audio stream…');
    log('resolve-start', { position: index + 1, mode: $('mode').value });
    try {
      const result = await api('/api/resolve', { id: item.id, ...requestFilters() });
      if (ticket !== state.resolution || state.queue[state.index]?.id !== item.id) return;
      let parsed;
      try { parsed = new URL(result.url); } catch (_) { throw Object.assign(new Error('The stream link was invalid.'), { code: 'HOST' }); }
      if (parsed.protocol !== 'https:' || !parsed.hostname.toLowerCase().endsWith('.googlevideo.com')) throw Object.assign(new Error('Stream host validation failed.'), { code: 'HOST' });
      if (!result.mime || !/^audio\//i.test(result.mime) && !/^video\/mp4/i.test(result.mime)) throw Object.assign(new Error('The stream format is not a supported audio source.'), { code: 'UNSUPPORTED' });
      audio.src = parsed.href; audio.load(); $('stream-host').textContent = parsed.hostname;
      $('now-title').textContent = safeText(result.title, item.title); $('now-sub').textContent = `${safeText(result.channel, item.channel)} · Direct stream${result.expiresAt ? ` · Expires ${new Date(result.expiresAt).toLocaleTimeString()}` : ''}`;
      setMediaMetadata({ title: $('now-title').textContent, channel: safeText(result.channel, item.channel), thumbnail: item.thumbnail });
      try { await audio.play(); setPlaybackState('playing'); notice('search-notice', 'Playing direct audio.'); log('play', { position: index + 1, host: parsed.hostname, mode: $('mode').value }); }
      catch (error) { if (ticket !== state.resolution) return; setPlaybackState('paused'); throw Object.assign(error, { code: 'PLAY_REJECTED' }); }
    } catch (error) {
      if (ticket !== state.resolution) return;
      $('now-sub').textContent = mediaError(error); $('stream-host').textContent = 'No playable stream'; notice('search-notice', mediaError(error), true); log('play-error', { code: safeText(error.code, 'UNKNOWN'), position: index + 1 });
    }
  }
  function drawResults(items) {
    const root = $('results'); root.replaceChildren();
    if (!items.length) { const p = document.createElement('p'); p.className = 'notice'; p.textContent = 'No results came back. Try a broader search.'; root.append(p); return; }
    items.forEach(item => {
      const card = document.createElement('article'); card.className = 'track';
      const img = document.createElement('img'); img.className = 'thumb'; img.alt = ''; img.loading = 'lazy'; if (safeText(item.thumbnail)) img.src = item.thumbnail; else img.src = '/icons/icon.svg';
      img.addEventListener('error', () => { img.src = '/icons/icon.svg'; });
      const info = document.createElement('div'); const title = document.createElement('div'); title.className = 'track-title'; title.textContent = safeText(item.title, 'Untitled track');
      const sub = document.createElement('div'); sub.className = 'track-sub'; sub.textContent = [safeText(item.channel, 'Unknown channel'), safeText(item.duration), safeText(item.matchBasis)].filter(Boolean).join(' · '); info.append(title, sub);
      const actions = document.createElement('div'); actions.style.cssText = 'display:flex;gap:6px;flex-wrap:wrap;justify-content:flex-end';
      const play = document.createElement('button'); play.className = 'primary add'; play.type = 'button'; play.textContent = 'Play'; play.setAttribute('aria-label', `Play ${title.textContent}`); play.addEventListener('click', () => addToQueue(item, true));
      const add = document.createElement('button'); add.className = 'line-button add'; add.type = 'button'; add.textContent = 'Add'; add.setAttribute('aria-label', `Add ${title.textContent} to queue`); add.addEventListener('click', () => addToQueue(item)); actions.append(play, add);
      card.append(img, info, actions); root.append(card);
    });
  }
  $('search-form').addEventListener('submit', async event => {
    event.preventDefault(); const query = $('query').value.trim(); if (!query || state.searching) return;
    state.searching = true; $('search-button').disabled = true; $('search-button').textContent = 'Searching…'; $('results').replaceChildren(); notice('search-notice', 'Searching for live results…');
    log('search', { mode: $('mode').value, includeCount: terms($('include').value).length, excludeCount: terms($('exclude').value).length }); savePrefs();
    try { const result = await api('/api/search', { query, ...requestFilters() }); drawResults(Array.isArray(result.items) ? result.items : []); notice('search-notice', safeText(result.notice)); }
    catch (error) { notice('search-notice', mediaError(error), true); }
    finally { state.searching = false; $('search-button').disabled = false; $('search-button').textContent = 'Search tracks'; }
  });
  $('next').addEventListener('click', () => { if (state.index + 1 < state.queue.length) startAt(state.index + 1); else { ++state.resolution; audio.pause(); notice('search-notice', 'End of queue. Add another track to keep listening.'); } });
  $('previous').addEventListener('click', () => { if (audio.currentTime > 3) { audio.currentTime = 0; return; } if (state.index > 0) startAt(state.index - 1); });
  $('clear-queue').addEventListener('click', () => { ++state.resolution; state.queue = []; state.index = -1; audio.pause(); audio.removeAttribute('src'); audio.load(); $('now-title').textContent = 'Choose a track'; $('now-sub').textContent = 'Your queue is ready when you are.'; $('stream-host').textContent = 'No stream loaded'; setPlaybackState('none'); if ('mediaSession' in navigator) { try { navigator.mediaSession.metadata = null; } catch (_) {} } drawQueue(); log('queue-clear'); });
  audio.addEventListener('ended', () => { setPlaybackState('none'); log('ended', { position: state.index + 1 }); if (state.index + 1 < state.queue.length) startAt(state.index + 1); else notice('search-notice', 'Track finished. Add another track to keep listening.'); });
  audio.addEventListener('play', () => { setPlaybackState('playing'); log('audio-play-request'); });
  audio.addEventListener('playing', () => { log('playing'); });
  audio.addEventListener('pause', () => { setPlaybackState('paused'); log('pause'); });
  audio.addEventListener('error', () => { if (audio.currentSrc) { notice('search-notice', 'The browser could not play this stream. Try another result.', true); $('now-sub').textContent = 'This browser could not play the direct stream.'; log('audio-error', { mediaError: audio.error?.code || null }); } });
  audio.addEventListener('timeupdate', () => { if ('mediaSession' in navigator && Number.isFinite(audio.duration) && audio.duration > 0) { try { navigator.mediaSession.setPositionState({ duration: audio.duration, playbackRate: audio.playbackRate, position: audio.currentTime }); } catch (_) {} } });
  if ('mediaSession' in navigator) for (const [action, handler] of Object.entries({ play: () => audio.play().catch(error => { log('media-session-play-error', { code: error?.name || 'UNKNOWN' }); notice('search-notice', 'Your browser could not resume playback. Use the player controls to retry.', true); }), pause: () => audio.pause(), nexttrack: () => $('next').click(), previoustrack: () => $('previous').click(), seekto: details => { if (Number.isFinite(details.seekTime)) audio.currentTime = details.seekTime; } })) { try { navigator.mediaSession.setActionHandler(action, handler); } catch (_) {} }
  function connection() { const online = navigator.onLine; $('net-pill').textContent = online ? 'Online' : 'Offline · streams unavailable'; $('net-pill').style.color = online ? 'var(--mint)' : 'var(--red)'; }
  window.addEventListener('online', () => { connection(); log('network', { state: 'online' }); });
  window.addEventListener('offline', () => { connection(); log('network', { state: 'offline' }); });
  document.addEventListener('visibilitychange', () => log('visibility', { state: document.visibilityState }));
  let deferredInstall = null;
  const installDialog = $('install-dialog');
  window.addEventListener('beforeinstallprompt', event => { event.preventDefault(); deferredInstall = event; });
  $('install-app').addEventListener('click', async () => {
    if (deferredInstall) {
      deferredInstall.prompt();
      try { const choice = await deferredInstall.userChoice; log('install-choice', { outcome: choice.outcome }); } catch (_) {}
      deferredInstall = null;
    } else if (typeof installDialog.showModal === 'function') installDialog.showModal();
  });
  $('close-install').addEventListener('click', () => installDialog.close());
  $('export-evidence').addEventListener('click', () => {
    const payload = { product: 'BlackYT PWA', exportedAt: new Date().toISOString(), note: 'Visibility events do not prove screen-off playback. Add a manual phone result after testing on the Android device.', androidScreenOffTest: 'Owner-reported pass: two minutes in Android Chrome over Wi-Fi; not instrumented', events: state.evidence };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = `blackyt-phone-evidence-${new Date().toISOString().slice(0,10)}.json`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); log('evidence-export');
  });
  async function boot() {
    loadPrefs(); connection();
    try { const info = await api('/api/status'); $('system-note').textContent = info.ok && info.directOnly === true ? `Player service ready${info.version ? ` · ${info.version}` : ''}. Direct stream mode is enabled.` : 'Player service is not ready for direct-only playback.'; }
    catch (error) { $('system-note').textContent = `Player service unavailable. Search and playback need the service: ${mediaError(error)}`; }
    if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1')) navigator.serviceWorker.register('/sw.js').catch(() => {});
  }
  boot();
})();
