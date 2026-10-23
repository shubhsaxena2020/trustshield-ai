/* Shared constants for the TrustShield extension. Pure data/CommonJS-style exports, no chrome.* calls, no deps.
 * Loaded by every context (background service worker, content scripts, popup, options, block/warn pages) via a
 * plain <script> / importScripts tag, so this file must stay a classic script (no ES "export", no "import"). */
(function (root) {
  "use strict";

  // ---- decision tiers -------------------------------------------------------------------------------------------
  var TIER = { ALLOW: "allow", WARN: "warn", BLOCK: "block" };

  // Mission thresholds (docs/finals/MISSION.md W5): block only risk>=85 AND >=2 independent strong signals.
  var BLOCK_SCORE_FLOOR = 85;
  var BLOCK_MIN_STRONG_SIGNALS = 2;
  var WARN_SCORE_FLOOR = 50; // below this and with no strong signal at all, a site is allowed outright.

  // Evidence categories considered "independent strong signals" for the block AND-gate. Each category counted once
  // per evaluation even if multiple evidence entries share it (so two homograph hits do not fake "two signals").
  var STRONG_SIGNAL_CATEGORIES = [
    "safe_browsing_listed",
    "domain_mismatch",
    "lookalike_domain",
    "homograph_domain",
    "disposable_tunnel_host",
    "ip_credential_combo",
  ];

  // ---- storage schema (chrome.storage.local), single root key to keep migrations/inspection simple --------------
  var STORAGE_ROOT_KEY = "trustshield";
  var STORAGE_VERSION = 1;

  function defaultStorageState() {
    return {
      version: STORAGE_VERSION,
      settings: {
        offlineMode: false, // true = never call the API, bundled rules only
        apiBaseUrl: "http://localhost:8000", // configurable; see options page. Only the registered domain is ever sent.
        savedSitesScanEnabled: false, // opt-in; gates the optional "history"/"bookmarks" permission request
        habitTrustDaysThreshold: 5, // distinct calendar days before a site is treated as habit-trusted
        habitTrustWindowDays: 60, // only days within this trailing window count (old visits age out)
      },
      allow: {}, // registeredDomain -> { addedAt: isoString, source: "manual"|"unblock" }
      block: {}, // registeredDomain -> { addedAt: isoString, source: "manual" }
      habit: {}, // registeredDomain -> { days: [isoDateString, ...] } (deduped calendar days, see domain.js)
      pausedMarkers: {}, // registeredDomain -> true  (per-site pause for the reversible ad/link page markers)
    };
  }

  // ---- runtime message types (chrome.runtime.sendMessage / onMessage) -------------------------------------------
  // Every message is {type, ...payload}; every response is {ok: true, ...} or {ok: false, error: string}.
  var MSG = {
    EVALUATE_URL: "TS_EVALUATE_URL", // {type, url} -> {ok, tier, score, strongSignals, evidence, reason, registeredDomain}
    GET_TAB_STATUS: "TS_GET_TAB_STATUS", // {type} (popup->background, uses sender.tab) -> {ok, ...same as EVALUATE_URL, paused}
    ALLOW_DOMAIN: "TS_ALLOW_DOMAIN", // {type, domain, source} -> {ok, state}
    BLOCK_DOMAIN: "TS_BLOCK_DOMAIN", // {type, domain} -> {ok, state}
    UNBLOCK_AND_CONTINUE: "TS_UNBLOCK_AND_CONTINUE", // {type, domain, returnUrl} -> {ok} ; background then tabs.update
    REMOVE_OVERRIDE: "TS_REMOVE_OVERRIDE", // {type, domain} -> {ok, state}  (removes a manual allow/block entry)
    GET_SETTINGS: "TS_GET_SETTINGS", // {type} -> {ok, settings}
    SET_SETTINGS: "TS_SET_SETTINGS", // {type, patch} -> {ok, settings}
    TOGGLE_MARKER_PAUSE: "TS_TOGGLE_MARKER_PAUSE", // {type, domain, paused} -> {ok}
    SCAN_SAVED_SITES: "TS_SCAN_SAVED_SITES", // {type} -> {ok, scanned, flagged: [{url, tier, score}]}
    SHOW_WARN_BANNER: "TS_SHOW_WARN_BANNER", // background -> content script: {type, info}
    HIDE_WARN_BANNER: "TS_HIDE_WARN_BANNER", // background -> content script: {type}
  };

  // ---- offline/online evidence confidence weights, mirrored from services/analysis/fusion/engine.py SEVERITY_WEIGHT
  // so the JS noisy-OR scorer produces risk numbers on the same 0-100 scale the backend uses. Kept intentionally small
  // and local (no runtime dependency on the Python package); see extension/src/risk/signals.js for the evaluators.
  var SEVERITY_WEIGHT = { informational: 0.0, low: 0.06, medium: 0.16, high: 0.38, critical: 0.55 };

  var api = {
    TIER: TIER,
    BLOCK_SCORE_FLOOR: BLOCK_SCORE_FLOOR,
    BLOCK_MIN_STRONG_SIGNALS: BLOCK_MIN_STRONG_SIGNALS,
    WARN_SCORE_FLOOR: WARN_SCORE_FLOOR,
    STRONG_SIGNAL_CATEGORIES: STRONG_SIGNAL_CATEGORIES,
    STORAGE_ROOT_KEY: STORAGE_ROOT_KEY,
    STORAGE_VERSION: STORAGE_VERSION,
    defaultStorageState: defaultStorageState,
    MSG: MSG,
    SEVERITY_WEIGHT: SEVERITY_WEIGHT,
  };

  if (typeof module !== "undefined" && module.exports) {
    module.exports = api; // CommonJS, used only by node-side tests/tools, never bundled into the extension
  } else {
    root.TrustShieldConstants = api; // classic-script global, used by extension contexts
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
