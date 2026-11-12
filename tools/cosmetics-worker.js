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

// Nur erlaubte, kurze Werte übernehmen – in derselben Reihenfolge, wie die Mod sie schickt.
// Ein leeres Profil bleibt stehen: Es zeigt, dass der Spieler Loothub nutzt (Krönchen in der Tab-Liste).
function clean(profile) {
	if (!profile || typeof profile !== "object") return null;
	const out = {};
	const set = Array.isArray(profile.set) ? profile.set : [];
	out.set = set.filter((s) => typeof s === "string" && /^[a-z]{1,24}$/.test(s)).slice(0, MAX_SET);
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

// Mojangs Prüfschlüssel für Spieler-Zertifikate (Stand November 2026, aus api.minecraftservices.com/publickeys).
// Ersatz, falls die Adresse für Cloudflare gesperrt ist; sonst wird einmal am Tag der aktuelle Stand geholt.
const BUILTIN_KEYS = [
	"MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAylB4B6m5lz7jwrcFz6Fd/fnfUhcvlxsTSn5kIK/2aGG1C3kMy4VjhwlxF6BFUSnfxhNswPjh3ZitkBxEAFY25uzkJFRwHwVA9mdwjashXILtR6OqdLXXFVyUPIURLOSWqGNBtb08EN5fMnG8iFLgEJIBMxs9BvF3s3/FhuHyPKiVTZmXY0WY4ZyYqvoKR+XjaTRPPvBsDa4WI2u1zxXMeHlodT3lnCzVvyOYBLXL6CJgByuOxccJ8hnXfF9yY4F0aeL080Jz/3+EBNG8RO4ByhtBf4Ny8NQ6stWsjfeUIvH7bU/4zCYcYOq4WrInXHqS8qruDmIl7P5XXGcabuzQstPf/h2CRAUpP/PlHXcMlvewjmGU6MfDK+lifScNYwjPxRo4nKTGFZf/0aqHCh/EAsQyLKrOIYRE0lDG3bzBh8ogIMLAugsAfBb6M3mqCqKaTMAf/VAjh5FFJnjS+7bE+bZEV0qwax1CEoPPJL1fIQjOS8zj086gjpGRCtSy9+bTPTfTR/SJ+VUB5G2IeCItkNHpJX2ygojFZ9n5Fnj7R9ZnOM+L8nyIjPu3aePvtcrXlyLhH/hvOfIOjPxOlqW+O5QwSFP4OEcyLAUgDdUgyW36Z5mB285uKW/ighzZsOTevVUG2QwDItObIV6i8RCxFbN2oDHyPaO5j1tTaBNyVt8CAwEAAQ==",
	"MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAt4t9NPuu7cktclnaH7eZj0omkLcJHeLz5MKsyJEntHZ0INtuBjSSul3Pp3pBeJN8k3ADdcdBLUN90bcAi7WsQqTx3Ft363q3W7TbM8j2iTEdp/0uVspoRt/DP1tkaWFs/w2WwUv9jbVoBUzfUc4pSTIxRwdjmqjZQfvjwKNDbOx3IhP2H0WXodbISejPi1wBZqNW4m1rnZAXp/EpUguxA8mobCa4vUCBkyFDyXdl69/wUSJHyCPmgcMJ364OlAhIqtwVPShBZObvrK/f0BYk6ShJD3N7TFDatSYsIIdcTKRknaIm91s+EsMrdB9U4Yw+ZJ/pyCB4S3vk8zfDCnb0DWIxYH3/EMzaxl77djmTmMzi/JDITup5z3jfWtRZmrAhU2/+W5IO5hEpo3/bCS9PXIY5xb41Lmp2ZO8dXKtyD66Chchy0W129n8vPl2GIruOdrxsjZAHnneyAb9jm0uaGaphwnEnuecX/qgHY6ZMtayvLLsPst8PO6R1vufMy8WqjK+j7LnC1krL7CPDg0NEhyQTmw5l+NCNjSlvB1juM9V4PARg0bYCOkGXm7ydRCjSSH8CJXZpwnd5cBB5WKAX3KPzutRgMi/LFwNSMZzFuUyXaYOZPpD259yqph1LmGqegEdDriACVU+dVEONFMm8eIuBofe7ljmsAFKW9BINwK0CAwEAAQ==",
];

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
	return stored ? [...new Set([...stored.keys, ...BUILTIN_KEYS])] : BUILTIN_KEYS;
}

/**
 * Prüft, ob {@code message} mit dem von Mojang für diese UUID beglaubigten Spieler-Schlüssel unterschrieben ist.
 * Gibt null zurück, wenn alles stimmt, sonst den Grund.
 */
async function verifySigned(env, uuid, publicKey, expiresAt, keySignature, signature, messageText) {
	// 1) Ist der Spieler-Schlüssel von Mojang für diese UUID beglaubigt?
	const keyBytes = b64(publicKey);
	const certified = new Uint8Array(24 + keyBytes.length);
	certified.set(uuidBytes(uuid), 0);
	new DataView(certified.buffer).setBigInt64(16, BigInt(expiresAt));
	certified.set(keyBytes, 24);
	const mojangKeys = await playerCertificateKeys(env);
	if (mojangKeys.length === 0) {
		return "mojang keys unavailable";
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
		return "key not certified";
	}
	// 2) Hat dieser Schlüssel genau diese Nachricht unterschrieben?
	const playerKey = await crypto.subtle.importKey("spki", keyBytes, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
	if (!(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", playerKey, b64(signature), new TextEncoder().encode(messageText)))) {
		return "bad signature";
	}
	return null;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
/** Gesten werden nur kurz im Cache des Rechenzentrums gehalten (kein KV – das wäre zu langsam und begrenzt). */
const EMOTE_TTL = 15;

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
			const why = await verifySigned(env, uuid, publicKey, expiresAt, keySignature, signature,
				`loothub-cosmetics\n${uuid}\n${ts}\n${profileJson}`);
			if (why) {
				return json({ error: why }, why === "mojang keys unavailable" ? 503 : 403);
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

		// Geste aus dem Emote-Rad melden: nur unterschrieben, nur eigene UUID, höchstens kurz gespeichert
		if (url.pathname === "/emote" && request.method === "POST") {
			let body;
			try {
				body = await request.json();
			} catch {
				return json({ error: "bad json" }, 400);
			}
			const { uuid, ts, emote, publicKey, expiresAt, keySignature, signature } = body || {};
			if (typeof uuid !== "string" || !UUID_RE.test(uuid) || typeof ts !== "number" || !Number.isInteger(emote)
				|| emote < 1 || emote > 63 || typeof publicKey !== "string" || typeof expiresAt !== "number"
				|| typeof keySignature !== "string" || typeof signature !== "string") {
				return json({ error: "bad request" }, 400);
			}
			const now = Date.now();
			if (Math.abs(now - ts) > 30_000 || expiresAt < now) {
				return json({ error: "stale" }, 400);
			}
			const why = await verifySigned(env, uuid, publicKey, expiresAt, keySignature, signature,
				`loothub-emote\n${uuid}\n${ts}\n${emote}`);
			if (why) {
				return json({ error: why }, why === "mojang keys unavailable" ? 503 : 403);
			}
			const key = new Request(`${url.origin}/emote-cache/${uuid}`);
			ctx.waitUntil(caches.default.put(key, new Response(JSON.stringify({ e: emote, t: now }), {
				headers: { "content-type": "application/json", "cache-control": `public, max-age=${EMOTE_TTL}` },
			})));
			return json({ ok: true });
		}

		// Gesten mehrerer Spieler abfragen: {uuid: {e, age}} – nur die der letzten Sekunden
		if (url.pathname === "/emotes" && request.method === "GET") {
			const ids = (url.searchParams.get("ids") || "").split(",").filter((id) => UUID_RE.test(id)).slice(0, 20);
			const out = {};
			const now = Date.now();
			await Promise.all(ids.map(async (id) => {
				const hit = await caches.default.match(new Request(`${url.origin}/emote-cache/${id}`));
				if (hit) {
					try {
						const v = await hit.json();
						if (now - v.t < EMOTE_TTL * 1000) {
							out[id] = { e: v.e, age: now - v.t };
						}
					} catch {
						// kaputter Eintrag – ignorieren
					}
				}
			}));
			return new Response(JSON.stringify(out), {
				headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
			});
		}

		return new Response("Loothub Cosmetics", { status: 200 });
	},
};
