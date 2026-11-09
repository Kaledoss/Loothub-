// Loothub-Cosmetics-Server (Cloudflare Worker)
// Bindung: KV-Namespace unter dem Variablennamen COSMETICS.
//
// GET  /all      → alle Profile { "<uuid>": { set, values, skins, name, t } } (60 s zwischengespeichert)
// POST /profile  → eigenes Profil setzen, unterschrieben mit dem von Mojang beglaubigten Profil-Schlüssel
//                  (derselbe wie für Chat-Signaturen). Keine Rückfrage bei Mojang nötig, kein Zugangsschlüssel.

const MAX_SET = 30;
const MAX_VALUES = 30;
const MAX_SKINS = 6;
const SAFE = /^[A-Za-z0-9_#,.:\- ]{0,200}$/;
const NAME = /^[A-Za-z0-9_]{1,16}$/;

function json(data, status = 200, extra = {}) {
	return new Response(JSON.stringify(data), {
		status,
		headers: { "content-type": "application/json; charset=utf-8", ...extra },
	});
}

function dashed(id) {
	const h = id.replace(/-/g, "").toLowerCase();
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

// Nur erlaubte, kurze Werte übernehmen – in derselben Reihenfolge, wie die Mod sie schickt
function clean(profile) {
	if (!profile || typeof profile !== "object") return null;
	const out = {};
	const set = Array.isArray(profile.set) ? profile.set : [];
	out.set = set.filter((s) => typeof s === "string" && /^[a-z]{1,24}$/.test(s)).slice(0, MAX_SET);
	if (out.set.length === 0) return null;
	out.values = {};
	const values = profile.values && typeof profile.values === "object" ? profile.values : {};
	for (const k of Object.keys(values).slice(0, MAX_VALUES)) {
		const v = values[k];
		if (/^[a-z]{1,24}$/.test(k) && typeof v === "string" && SAFE.test(v)) out.values[k] = v;
	}
	if (Array.isArray(profile.skins)) {
		out.skins = profile.skins.slice(0, MAX_SKINS)
			.map((s) => (s === null ? null : typeof s === "string" && /^[A-Za-z0-9_]{0,16}$/.test(s) ? s : null));
	}
	return out;
}

function b64(s) {
	const bin = atob(s);
	const out = new Uint8Array(bin.length);
	for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
	return out;
}

function uuidBytes(uuid) {
	const h = uuid.replace(/-/g, "");
	const out = new Uint8Array(16);
	for (let i = 0; i < 16; i++) out[i] = parseInt(h.substr(i * 2, 2), 16);
	return out;
}

// Mojangs Prüfschlüssel für Spieler-Zertifikate: einmal am Tag holen und im KV-Speicher merken
let keyCache = null;
async function playerCertificateKeys(env) {
	if (keyCache && keyCache.until > Date.now()) return keyCache.keys;
	let stored = null;
	try {
		stored = JSON.parse((await env.COSMETICS.get("mojang-keys")) || "null");
	} catch {
		stored = null;
	}
	if (stored && stored.until > Date.now()) {
		keyCache = stored;
		return stored.keys;
	}
	try {
		const r = await fetch("https://api.minecraftservices.com/publickeys", { headers: { "User-Agent": "Loothub-Cosmetics/1.0" } });
		if (r.ok) {
			const data = await r.json();
			const keys = (data.playerCertificateKeys || []).map((k) => k.publicKey).filter((k) => typeof k === "string");
			if (keys.length > 0) {
				keyCache = { keys, until: Date.now() + 24 * 3600_000 };
				await env.COSMETICS.put("mojang-keys", JSON.stringify(keyCache));
				return keys;
			}
		}
	} catch {
		// unten: alter Stand
	}
	return stored ? stored.keys : [];
}

export default {
	async fetch(request, env, ctx) {
		const url = new URL(request.url);
		const cacheKey = new Request(url.origin + "/all");

		if (url.pathname === "/all" && request.method === "GET") {
			const cache = caches.default;
			let res = await cache.match(cacheKey);
			if (!res) {
				const data = (await env.COSMETICS.get("all")) || "{}";
				res = new Response(data, {
					headers: { "content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=60" },
				});
				ctx.waitUntil(cache.put(cacheKey, res.clone()));
			}
			return res;
		}

		if (url.pathname === "/profile" && request.method === "POST") {
			let body;
			try {
				body = await request.json();
			} catch {
				return json({ error: "bad json" }, 400);
			}
			const { uuid, name, ts, profile: profileJson, publicKey, expiresAt, keySignature, signature } = body || {};
			if (typeof uuid !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(uuid)
				|| typeof name !== "string" || !NAME.test(name) || typeof ts !== "number" || typeof profileJson !== "string"
				|| typeof publicKey !== "string" || typeof expiresAt !== "number" || typeof keySignature !== "string"
				|| typeof signature !== "string") {
				return json({ error: "bad request" }, 400);
			}
			if (profileJson.length > 4000) {
				return json({ error: "too large" }, 413);
			}
			const now = Date.now();
			if (Math.abs(now - ts) > 5 * 60_000) {
				return json({ error: "clock" }, 400);
			}
			if (expiresAt < now) {
				return json({ error: "key expired" }, 403);
			}
			// 1) Ist der Spieler-Schlüssel von Mojang für diese UUID beglaubigt?
			const keyBytes = b64(publicKey);
			const certified = new Uint8Array(24 + keyBytes.length);
			certified.set(uuidBytes(uuid), 0);
			new DataView(certified.buffer).setBigInt64(16, BigInt(expiresAt));
			certified.set(keyBytes, 24);
			const mojangKeys = await playerCertificateKeys(env);
			if (mojangKeys.length === 0) {
				return json({ error: "mojang keys unavailable" }, 503);
			}
			let ok = false;
			for (const k of mojangKeys) {
				try {
					const key = await crypto.subtle.importKey("spki", b64(k), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"]);
					if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64(keySignature), certified)) {
						ok = true;
						break;
					}
				} catch {
					// nächster Schlüssel
				}
			}
			if (!ok) {
				return json({ error: "key not certified" }, 403);
			}
			// 2) Hat dieser Schlüssel genau diese Auswahl unterschrieben?
			const playerKey = await crypto.subtle.importKey("spki", keyBytes, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
			const message = new TextEncoder().encode(`loothub-cosmetics\n${uuid}\n${ts}\n${profileJson}`);
			if (!(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", playerKey, b64(signature), message))) {
				return json({ error: "bad signature" }, 403);
			}

			let parsed = null;
			try {
				parsed = JSON.parse(profileJson);
			} catch {
				return json({ error: "bad profile" }, 400);
			}
			const all = JSON.parse((await env.COSMETICS.get("all")) || "{}");
			const profile = clean(parsed);
			if (profile) {
				all[uuid] = { ...profile, name, t: now };
			} else {
				delete all[uuid];
			}
			await env.COSMETICS.put("all", JSON.stringify(all));
			ctx.waitUntil(caches.default.delete(cacheKey));
			return json({ ok: true });
		}

		return new Response("Loothub Cosmetics", { status: 200 });
	},
};
