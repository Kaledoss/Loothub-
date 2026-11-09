// Loothub-Cosmetics-Server (Cloudflare Worker)
// Bindung: KV-Namespace unter dem Variablennamen COSMETICS.
//
// GET  /all      → alle Profile { "<uuid>": { set, values, skins, name, t } } (60 s zwischengespeichert)
// POST /profile  → eigenes Profil setzen: { name, serverId, profile } (profile = null löscht)
//                  Wer man ist, wird bei Mojang geprüft (hasJoined) – ohne Passwort, ohne Zugangsschlüssel.

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
			const { name, serverId } = body || {};
			if (typeof name !== "string" || !NAME.test(name) || typeof serverId !== "string" || !/^[0-9a-f]{1,64}$/.test(serverId)) {
				return json({ error: "bad request" }, 400);
			}
			if (JSON.stringify(body.profile ?? null).length > 4000) {
				return json({ error: "too large" }, 413);
			}
			// Mojang: Hat sich dieser Spieler gerade mit diesem Code angemeldet?
			const check = await fetch(
				`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${encodeURIComponent(serverId)}`,
			);
			if (check.status !== 200) {
				return json({ error: "not verified" }, 403);
			}
			const who = await check.json();
			const uuid = dashed(who.id);

			const all = JSON.parse((await env.COSMETICS.get("all")) || "{}");
			const profile = clean(body.profile);
			if (profile) {
				all[uuid] = { ...profile, name: who.name, t: Date.now() };
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
