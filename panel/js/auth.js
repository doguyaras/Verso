// Sign-in with the authorization code flow and PKCE (RFC 7636) against the realm's OIDC endpoints (ADR-0010). The
// tokens live in this module's memory only: never in localStorage, sessionStorage or a cookie (reference 17). A
// reload signs in again (silently while the IdP session lasts). Only the one-time PKCE verifier and the state cross
// the redirect, in sessionStorage, and are removed as soon as the code is exchanged.
import { CONFIG } from './config.js';

const PENDING = 'verso.panel.pkce';
let session = null; // { accessToken, refreshToken, idToken, expiresAt, claims, identity }

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

const endpoint = (name) => `${CONFIG.issuer}/protocol/openid-connect/${name}`;

export async function signIn({ silent = false } = {}) {
  const verifier = randomString(48);
  const state = randomString(16);
  sessionStorage.setItem(PENDING, JSON.stringify({ verifier, state }));
  const params = new URLSearchParams({
    client_id: CONFIG.clientId, response_type: 'code', scope: 'openid', redirect_uri: CONFIG.redirectUri, state,
    code_challenge: await challengeOf(verifier), code_challenge_method: 'S256',
  });
  if (silent) params.set('prompt', 'none');
  location.assign(`${endpoint('auth')}?${params}`);
}

/** Completes a redirect from the IdP; returns true when a session was established. */
export async function completeSignIn() {
  const query = new URLSearchParams(location.search);
  const pending = JSON.parse(sessionStorage.getItem(PENDING) ?? 'null');
  if (!query.has('code') && !query.has('error')) return false;
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
    // Display data (the user name) comes from the ID token, which never leaves the browser; the access token sent to
    // the API carries no name (deploy/keycloak/realm/verso-realm.json, client verso-panel).
    identity: json.id_token ? claimsOf(json.id_token) : (session?.identity ?? {}) };
}

/** A valid access token, refreshed 30 s before it expires; null when signed out. */
export async function accessToken() {
  if (!session) return null;
  if (Date.now() > session.expiresAt - 30_000) {
    try {
      await tokenRequest({ grant_type: 'refresh_token', refresh_token: session.refreshToken });
    } catch {
      session = null;
      return null;
    }
  }
  return session.accessToken;
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
