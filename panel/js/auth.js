// Sign-in with the authorization code flow and PKCE (RFC 7636) against the realm's OIDC endpoints (ADR-0010). The
// tokens live in this module's memory only: never in localStorage, sessionStorage or a cookie (reference 17). A
// reload asks the user to sign in again (one click while the IdP session lasts). Only the one-time PKCE verifier and
// the state cross the redirect, in sessionStorage, and are removed as soon as the redirect comes back.
import { CONFIG } from './config.js';
import { prefs } from './store.js';

const PENDING = 'verso.panel.pkce';
let session = null; // { accessToken, refreshToken, idToken, expiresAt, claims, identity }
let refreshing = null; // the one refresh in flight: the realm revokes a refresh token on use (verso-realm.json)

export function base64Url(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function randomString(byteCount = 32) {
  return base64Url(crypto.getRandomValues(new Uint8Array(byteCount)));
}

export async function challengeOf(verifier) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

/** The payload of a JWT, without verifying it: the API verifies; the panel only reads roles and expiry. */
export function claimsOf(jwt) {
  const part = jwt.split('.')[1] ?? '';
  const json = atob(part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '='));
  return JSON.parse(new TextDecoder().decode(Uint8Array.from(json, (c) => c.charCodeAt(0))));
}

/**
 * The IdP's authorization, token and logout endpoints: the configured ones (any OIDC IdP, e.g. Entra ID's v2.0
 * endpoints, ADR-0041), else Keycloak's paths under the issuer. Only https, or http on a loopback host (the demo IdP):
 * the code, the PKCE verifier and the tokens travel to these addresses.
 */
export function endpointsOf(config) {
  const keycloak = (name) => `${config.issuer}/protocol/openid-connect/${name}`;
  const endpoints = {
    auth: config.authorizationEndpoint ?? keycloak('auth'),
    token: config.tokenEndpoint ?? keycloak('token'),
    logout: config.endSessionEndpoint ?? keycloak('logout'),
  };
  for (const url of Object.values(endpoints)) {
    const { protocol, hostname } = new URL(url);
    if (protocol !== 'https:' && !(protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(hostname))) {
      throw new Error('IdP endpoints must be https');
    }
  }
  return endpoints;
}

const endpoint = (name) => endpointsOf(CONFIG)[name];

/**
 * Sends the browser to the IdP. With `prompt: 'login'` the IdP shows its sign-in form even when it still has a session
 * (the intro's button: the user expects the sign-in step); without it an existing IdP session signs in silently (the
 * automatic sign-in after a 401).
 */
export async function signIn({ prompt } = {}) {
  const verifier = randomString(48);
  const state = randomString(16);
  sessionStorage.setItem(PENDING, JSON.stringify({ verifier, state }));
  const params = new URLSearchParams({
    client_id: CONFIG.clientId, response_type: 'code', scope: CONFIG.scope, redirect_uri: CONFIG.redirectUri, state,
    code_challenge: await challengeOf(verifier), code_challenge_method: 'S256',
    // the IdP's sign-in page opens in the intro screen's language (Turkish unless EN was picked), whatever the browser's
    ui_locales: prefs.lang(),
  });
  if (prompt === 'login') params.set('prompt', 'login');
  location.assign(`${endpoint('auth')}?${params}`);
}

/** Completes a redirect from the IdP; returns true when a session was established. */
export async function completeSignIn() {
  const query = new URLSearchParams(location.search);
  if (!query.has('code') && !query.has('error')) return false;
  const pending = JSON.parse(sessionStorage.getItem(PENDING) ?? 'null');
  sessionStorage.removeItem(PENDING);
  history.replaceState(null, '', CONFIG.redirectUri);
  if (query.has('error') || !pending || query.get('state') !== pending.state) return false;
  await tokenRequest({ grant_type: 'authorization_code', code: query.get('code'), redirect_uri: CONFIG.redirectUri,
    code_verifier: pending.verifier });
  return true;
}

async function tokenRequest(form) {
  const res = await fetch(endpoint('token'), { method: 'POST',
    body: new URLSearchParams({ client_id: CONFIG.clientId, ...form }) });
  if (!res.ok) throw new Error(`token ${res.status}`);
  const json = await res.json();
  session = { accessToken: json.access_token, refreshToken: json.refresh_token, idToken: json.id_token,
    expiresAt: Date.now() + json.expires_in * 1000, claims: claimsOf(json.access_token),
    // Display data (the user name) comes from the ID token, which goes only back to the IdP (as the logout hint);
    // the access token sent to the API carries no name (deploy/keycloak/realm/verso-realm.json, client verso-panel).
    identity: json.id_token ? claimsOf(json.id_token) : (session?.identity ?? {}) };
}

/** A valid access token, refreshed 30 s before it expires; null when signed out. Concurrent callers share a refresh. */
export async function accessToken() {
  if (!session) return null;
  if (Date.now() > session.expiresAt - 30_000) {
    refreshing ??= tokenRequest({ grant_type: 'refresh_token', refresh_token: session.refreshToken })
      .catch(() => { session = null; })
      .finally(() => { refreshing = null; });
    await refreshing;
  }
  return session?.accessToken ?? null;
}

export function currentClaims() {
  return session?.claims ?? null;
}

export function currentIdentity() {
  return session?.identity ?? {};
}

export function expiresAt() {
  return session?.expiresAt ?? 0;
}

export function signOut() {
  const idToken = session?.idToken;
  session = null;
  const params = new URLSearchParams({ client_id: CONFIG.clientId, post_logout_redirect_uri: CONFIG.redirectUri });
  if (idToken) params.set('id_token_hint', idToken);
  location.assign(`${endpoint('logout')}?${params}`);
}
