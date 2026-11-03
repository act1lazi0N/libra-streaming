// Test-only harness for the M33 browser slice; not product UI. Every call below is a real request this page makes
// from its own origin through the repository ingress (or, for the source bytes, to the signed storage URL). Nothing
// here returns a credential, a ticket, a signed URL or an identity body to the test: the session's ticket cookie is
// HttpOnly, and the grant never leaves upload().
/* global Hls */
"use strict";

(() => {
  const core = "/api/core/v1";
  const fetchOptions = { credentials: "same-origin", cache: "no-store", redirect: "error" };

  async function csrf() {
    const response = await fetch(core + "/auth/csrf", fetchOptions);
    if (!response.ok) {
      throw new Error("CSRF token request failed: " + response.status);
    }
    return response.json();
  }

  // One Core call. Returns { status, body }; the body is only parsed when it is JSON.
  async function call(method, path, body, headers) {
    const sent = { ...(headers || {}) };
    if (method !== "GET") {
      const token = await csrf();
      sent[token.headerName] = token.token;
    }
    if (body !== undefined) {
      sent["Content-Type"] = "application/json";
    }
    const response = await fetch(core + path, {
      ...fetchOptions,
      method,
      headers: sent,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const type = response.headers.get("Content-Type") || "";
    const value = type.includes("json") ? await response.json() : null;
    return { status: response.status, body: value };
  }

  // Credential-bearing answers stay in the page: only the status reaches the test.
  async function status(method, path, body, headers) {
    return (await call(method, path, body, headers)).status;
  }

  function hex(buffer) {
    return Array.from(new Uint8Array(buffer), (b) => b.toString(16).padStart(2, "0")).join("");
  }

  // The administrator's upload of the file chosen in the file input: intent, grant, the direct PUT, completion.
  async function upload(contentId, expectedVersion, storageOrigin) {
    const file = document.getElementById("source").files[0];
    if (!file) {
      return { step: "file", status: 0 };
    }
    const sha256 = hex(await crypto.subtle.digest("SHA-256", await file.arrayBuffer()));
    const created = await call("POST", `/admin/catalog/${contentId}/uploads`, {
      requestId: crypto.randomUUID(),
      expectedVersion,
      byteLength: file.size,
      sha256,
    });
    if (created.status !== 201) {
      return { step: "create", status: created.status };
    }
    const uploadId = created.body.uploadId;
    const grant = await call("POST", `/admin/uploads/${uploadId}/upload-url`);
    if (grant.status !== 200) {
      return { step: "grant", status: grant.status };
    }
    const { method, url, requiredHeaders } = grant.body;
    const target = new URL(url);
    if (method !== "PUT" || target.origin !== storageOrigin || !target.pathname.startsWith("/libra-source/staging/")) {
      return { step: "grant-target", status: 0 };
    }
    let put;
    try {
      // A cross-origin PUT: the browser sends a CORS preflight to the storage gateway first.
      put = await fetch(url, {
        method,
        headers: requiredHeaders,
        body: file,
        mode: "cors",
        credentials: "omit",
        cache: "no-store",
        redirect: "error",
        referrerPolicy: "no-referrer",
      });
    } catch {
      // A refused preflight or a dropped connection: the browser exposes neither status nor reason to the page.
      return { step: "put", status: 0 };
    }
    if (!put.ok) {
      return { step: "put", status: put.status };
    }
    const completed = await call("POST", `/admin/uploads/${uploadId}/complete`);
    return {
      step: "complete",
      status: completed.status,
      uploadId,
      assetState: completed.body && completed.body.assetState,
      byteLength: file.size,
      sha256,
      signedHeaderNames: Object.keys(requiredHeaders).sort(),
    };
  }

  // fragments: one entry per segment load, from the request start (FRAG_LOADING) to its arrival (FRAG_LOADED).
  const playback = {
    hls: null, frames: 0, fragments: [], errors: [], levels: [], segments: 0, seeked: 0, seekedTo: null,
    renewedAt: null,
  };

  function countFrames(video) {
    video.requestVideoFrameCallback(() => {
      playback.frames += 1;
      countFrames(video);
    });
  }

  // hls.js plays the session's master playlist through the ingress; the HttpOnly ticket cookie is the credential.
  function play(manifestPath) {
    const video = document.getElementById("player");
    if (!Hls.isSupported()) {
      return { supported: false };
    }
    // A short forward buffer, so the last segment is still unfetched when the test renews the ticket.
    const hls = new Hls({ maxBufferLength: 4, maxMaxBufferLength: 4, startPosition: 0 });
    playback.hls = hls;
    hls.on(Hls.Events.MANIFEST_PARSED, (_, data) => {
      playback.levels = data.levels.map((level) => ({
        height: level.height,
        videoCodec: level.videoCodec,
        audioCodec: level.audioCodec,
      }));
    });
    hls.on(Hls.Events.LEVEL_LOADED, (_, data) => {
      playback.segments = data.details.fragments.length;
    });
    hls.on(Hls.Events.FRAG_LOADING, (_, data) => {
      playback.fragments.push({ frag: data.frag, sn: data.frag.sn, startedAt: performance.now(), loadedAt: null });
    });
    hls.on(Hls.Events.FRAG_LOADED, (_, data) => {
      const entry = playback.fragments.find((f) => f.frag === data.frag && f.loadedAt === null);
      if (entry) {
        entry.loadedAt = performance.now();
      }
    });
    video.addEventListener("seeked", () => {
      playback.seeked += 1;
      playback.seekedTo = video.currentTime;
    });
    hls.on(Hls.Events.ERROR, (_, data) => {
      playback.errors.push({
        type: data.type,
        details: data.details,
        fatal: data.fatal,
        status: data.response ? data.response.code : null,
      });
    });
    countFrames(video);
    hls.loadSource(manifestPath);
    hls.attachMedia(video);
    video.muted = true;
    video.play().catch(() => playback.errors.push({ type: "play", details: "rejected", fatal: true, status: null }));
    return { supported: true, version: Hls.version };
  }

  // Colour statistics of the frame on screen: a decoded test pattern has many distinct colours, a black or frozen
  // surface does not.
  function sample() {
    const video = document.getElementById("player");
    const canvas = document.getElementById("frame");
    const context = canvas.getContext("2d", { willReadFrequently: true });
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    const colours = new Set();
    let luma = 0;
    for (let i = 0; i < pixels.length; i += 4) {
      colours.add(((pixels[i] >> 4) << 8) | ((pixels[i + 1] >> 4) << 4) | (pixels[i + 2] >> 4));
      luma += 0.299 * pixels[i] + 0.587 * pixels[i + 1] + 0.114 * pixels[i + 2];
    }
    return { distinctColours: colours.size, meanLuma: luma / (pixels.length / 4) };
  }

  function state() {
    const video = document.getElementById("player");
    const quality = video.getVideoPlaybackQuality();
    const renewedAt = playback.renewedAt;
    return {
      currentTime: video.currentTime,
      duration: video.duration,
      paused: video.paused,
      ended: video.ended,
      seeking: video.seeking,
      readyState: video.readyState,
      videoWidth: video.videoWidth,
      videoHeight: video.videoHeight,
      presentedFrames: playback.frames,
      decodedFrames: quality.totalVideoFrames,
      droppedFrames: quality.droppedVideoFrames,
      segments: playback.segments,
      seeked: playback.seeked,
      seekedTo: playback.seekedTo,
      fragments: playback.fragments.length,
      loadingSegments: playback.fragments.filter((f) => f.loadedAt === null).length,
      loadedSegments: Array.from(new Set(playback.fragments.filter((f) => f.loadedAt !== null).map((f) => f.sn)))
        .sort((a, b) => a - b),
      // Segments whose request started after renewal and that arrived.
      segmentsAfterRenewal: renewedAt === null ? [] : Array.from(new Set(playback.fragments
        .filter((f) => f.startedAt > renewedAt && f.loadedAt !== null).map((f) => f.sn))),
      errors: playback.errors,
      levels: playback.levels,
    };
  }

  // Pauses the presentation and stops hls.js loading; an aborted in-flight load does not arrive.
  function hold() {
    document.getElementById("player").pause();
    playback.hls.stopLoad();
    for (const entry of playback.fragments) {
      if (entry.loadedAt === null) {
        entry.aborted = true;
      }
    }
    playback.fragments = playback.fragments.filter((f) => !f.aborted);
  }

  // Seeks while paused, then resumes loading and playback from there.
  function resumeAt(seconds) {
    const video = document.getElementById("player");
    video.currentTime = seconds;
    playback.hls.startLoad(seconds);
    video.play().catch(() => playback.errors.push({ type: "play", details: "rejected", fatal: true, status: null }));
  }

  // Renewal through Core; the new ticket arrives as an HttpOnly cookie the page cannot read.
  async function renew(sessionId) {
    const result = await status("POST", `/playback/sessions/${sessionId}/renew`);
    if (result === 200) {
      playback.renewedAt = performance.now();
    }
    return result;
  }

  // A stream request from this page without its cookies.
  async function anonymousStream(path) {
    const response = await fetch(path, { credentials: "omit", cache: "no-store", redirect: "error" });
    const type = response.headers.get("Content-Type") || "";
    const body = type.startsWith("application/problem+json") ? await response.json() : null;
    return { status: response.status, type, code: body && body.code };
  }

  window.m33 = { call, status, upload, play, sample, state, hold, resumeAt, renew, anonymousStream };
})();
