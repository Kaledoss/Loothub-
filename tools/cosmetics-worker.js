// Loothub-Cosmetics-Server (Cloudflare Worker)
// Bindung: KV-Namespace unter dem Variablennamen COSMETICS.
//
// GET  /all      → alle Profile { "<uuid>": { set, values, skins, name, t } } (60 s zwischengespeichert)
// POST /profile  → eigenes Profil setzen, unterschrieben mit dem von Mojang beglaubigten Profil-Schlüssel
//                  (derselbe wie für Chat-Signaturen). Keine Rückfrage bei Mojang nötig, kein Zugangsschlüssel.
// POST /share      → Schematic (Litematica) an einen anderen Loothub-Spieler teilen bzw. aktualisieren
// POST /inbox      → mit mir geteilte Schematics (ohne Datei)
// POST /share-get  → eine mit mir geteilte Schematic samt Datei holen
// POST /share-drop → eine mit mir geteilte Schematic ablehnen/entfernen
// POST /unshare    → Teilen mit einem Spieler beenden (nur der Absender)
// Alle Bau-Anfragen sind wie /profile unterschrieben.
// POST /lh, GET /lh/board, GET /lh/tags → Erfolge, Quests, Punkte, Shop, Ranglisten (D1, Bindung AH)

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
	// Skins: Java-Namen oder Bedrock-Namen mit Floodgate-Präfix („.“ oder „*“)
	if (Array.isArray(profile.skins)) {
		out.skins = profile.skins.slice(0, MAX_SKINS)
			.map((s) => (s === null ? null : typeof s === "string" && /^[.*]?[A-Za-z0-9_]{0,16}$/.test(s) ? s : null));
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

// ------------------------------------------------------------------ Gemeinsam bauen

const SHARE_TTL = 60 * 24 * 60 * 60; // 60 Tage
const MAX_FILE_B64 = 2_800_000; // ~2 MB Schematic
const MAX_SETTINGS = 20_000;

async function sha256Hex(text) {
	const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
	return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Gemeinsame Prüfung der Bau-Anfragen: Felder, Zeit, Unterschrift. Gibt [body, null] oder [null, Antwort]. */
async function signedBody(request, env, message) {
	let body;
	try {
		body = await request.json();
	} catch {
		return [null, json({ error: "bad json" }, 400)];
	}
	const { uuid, ts, publicKey, expiresAt, keySignature, signature } = body || {};
	if (typeof uuid !== "string" || !UUID_RE.test(uuid) || typeof ts !== "number" || typeof publicKey !== "string"
		|| typeof expiresAt !== "number" || typeof keySignature !== "string" || typeof signature !== "string") {
		return [null, json({ error: "bad request" }, 400)];
	}
	const now = Date.now();
	if (Math.abs(now - ts) > 5 * 60_000 || expiresAt < now) {
		return [null, json({ error: "stale" }, 400)];
	}
	const text = await message(body);
	if (text === null) {
		return [null, json({ error: "bad request" }, 400)];
	}
	const why = await verifySigned(env, uuid, publicKey, expiresAt, keySignature, signature, text);
	if (why) {
		return [null, json({ error: why }, why === "mojang keys unavailable" ? 503 : 403)];
	}
	return [body, null];
}

/** UUID eines Loothub-Spielers aus seinem Namen (nur wer Loothub+ nutzt, steht in „all“). */
async function uuidOfName(env, name) {
	const all = JSON.parse((await env.COSMETICS.get("all")) || "{}");
	const lower = String(name).toLowerCase();
	for (const [id, p] of Object.entries(all)) {
		if (p && typeof p.name === "string" && p.name.toLowerCase() === lower) {
			return id;
		}
	}
	return null;
}

/**
 * Posteingang je Spieler als eigene Liste („inbox:<uuid>“ = [ids]) statt KV-list(): list() ist im kostenlosen
 * Cloudflare-Tarif auf 1.000 Aufrufe am Tag begrenzt – bei Abfragen alle paar Minuten war das schnell aufgebraucht
 * (Fehler 500). Lesen (get) ist dagegen reichlich frei.
 */
async function inboxIds(env, uuid) {
	const raw = await env.COSMETICS.get(`inbox:${uuid}`);
	if (raw !== null) {
		try {
			const ids = JSON.parse(raw);
			return Array.isArray(ids) ? ids.filter((id) => typeof id === "string" && UUID_RE.test(id)) : [];
		} catch {
			return [];
		}
	}
	// alter Stand ohne Liste: einmal per list() übernehmen (darf scheitern)
	try {
		const list = await env.COSMETICS.list({ prefix: `share:${uuid}:` });
		const ids = list.keys.map((k) => k.name.slice(`share:${uuid}:`.length)).filter((id) => UUID_RE.test(id));
		await env.COSMETICS.put(`inbox:${uuid}`, JSON.stringify(ids), { expirationTtl: SHARE_TTL });
		return ids;
	} catch {
		return [];
	}
}

async function setInbox(env, uuid, ids) {
	await env.COSMETICS.put(`inbox:${uuid}`, JSON.stringify(ids.slice(0, 50)), { expirationTtl: SHARE_TTL });
}

async function handleBuild(url, request, env) {
	if (url.pathname === "/share") {
		const [body, err] = await signedBody(request, env, async (b) => {
			if (typeof b.to !== "string" || !NAME.test(b.to) || typeof b.id !== "string" || !UUID_RE.test(b.id)
				|| typeof b.settings !== "string" || typeof b.file !== "string" || typeof b.name !== "string") {
				return null;
			}
			return `loothub-share\n${b.uuid}\n${b.ts}\n${b.to.toLowerCase()}\n${b.id}\n${await sha256Hex(b.settings + "\n" + b.file)}`;
		});
		if (err) return err;
		if (body.settings.length > MAX_SETTINGS || body.file.length > MAX_FILE_B64 || body.name.length > 100) {
			return json({ error: "too large" }, 413);
		}
		const to = await uuidOfName(env, body.to);
		if (!to) {
			return json({ error: "not a loothub player" }, 404);
		}
		// Nur wer eine Schematic zuerst geteilt hat, darf sie ändern
		const owner = await env.COSMETICS.get(`owner:${body.id}`);
		if (owner && owner !== body.uuid) {
			return json({ error: "not owner" }, 403);
		}
		if (body.file.length > 0) {
			await env.COSMETICS.put(`file:${body.id}`, JSON.stringify({ file: body.file, fileName: String(body.fileName || "").slice(0, 80) }),
				{ expirationTtl: SHARE_TTL });
		} else if (!(await env.COSMETICS.get(`file:${body.id}`))) {
			return json({ error: "file missing" }, 400);
		}
		await env.COSMETICS.put(`owner:${body.id}`, body.uuid, { expirationTtl: SHARE_TTL });
		const me = typeof body.me === "string" && NAME.test(body.me) ? body.me : "?";
		await env.COSMETICS.put(`share:${to}:${body.id}`, JSON.stringify({
			id: body.id, from: body.uuid, fromName: me, name: body.name, rev: Date.now(), settings: body.settings,
		}), { expirationTtl: SHARE_TTL });
		const ids = await inboxIds(env, to);
		if (!ids.includes(body.id)) {
			ids.push(body.id);
			await setInbox(env, to, ids);
		}
		return json({ ok: true });
	}

	if (url.pathname === "/inbox") {
		const [body, err] = await signedBody(request, env, async (b) => `loothub-inbox\n${b.uuid}\n${b.ts}`);
		if (err) return err;
		const ids = await inboxIds(env, body.uuid);
		const shares = [];
		const alive = [];
		for (const id of ids.slice(0, 50)) {
			const v = await env.COSMETICS.get(`share:${body.uuid}:${id}`);
			if (v) {
				alive.push(id);
				try {
					const s = JSON.parse(v);
					shares.push({ id: s.id, fromName: s.fromName, name: s.name, rev: s.rev });
				} catch {
					// kaputter Eintrag
				}
			}
		}
		if (alive.length !== ids.length) {
			await setInbox(env, body.uuid, alive); // abgelaufene Einträge austragen (nur dann schreiben)
		}
		return json({ shares });
	}

	if (url.pathname === "/share-get") {
		const [body, err] = await signedBody(request, env, async (b) =>
			typeof b.id === "string" && UUID_RE.test(b.id) ? `loothub-share-get\n${b.uuid}\n${b.ts}\n${b.id}` : null);
		if (err) return err;
		const v = await env.COSMETICS.get(`share:${body.uuid}:${body.id}`);
		const f = await env.COSMETICS.get(`file:${body.id}`);
		if (!v || !f) {
			return json({ error: "not found" }, 404);
		}
		const s = JSON.parse(v);
		const file = JSON.parse(f);
		return json({ settings: s.settings, file: file.file, fileName: file.fileName, rev: s.rev });
	}

	if (url.pathname === "/share-drop") {
		const [body, err] = await signedBody(request, env, async (b) =>
			typeof b.id === "string" && UUID_RE.test(b.id) ? `loothub-share-drop\n${b.uuid}\n${b.ts}\n${b.id}` : null);
		if (err) return err;
		await env.COSMETICS.delete(`share:${body.uuid}:${body.id}`);
		const ids = await inboxIds(env, body.uuid);
		if (ids.includes(body.id)) {
			await setInbox(env, body.uuid, ids.filter((id) => id !== body.id));
		}
		return json({ ok: true });
	}

	if (url.pathname === "/unshare") {
		const [body, err] = await signedBody(request, env, async (b) =>
			typeof b.id === "string" && UUID_RE.test(b.id) && typeof b.to === "string" && NAME.test(b.to)
				? `loothub-unshare\n${b.uuid}\n${b.ts}\n${b.id}\n${b.to.toLowerCase()}` : null);
		if (err) return err;
		const to = await uuidOfName(env, body.to);
		if (to) {
			const v = await env.COSMETICS.get(`share:${to}:${body.id}`);
			if (v && JSON.parse(v).from === body.uuid) {
				await env.COSMETICS.delete(`share:${to}:${body.id}`);
				const ids = await inboxIds(env, to);
				if (ids.includes(body.id)) {
					await setInbox(env, to, ids.filter((id) => id !== body.id));
				}
			}
		}
		return json({ ok: true });
	}
	return null;
}


// ======================================================================= Auktionshaus-Verlauf (lückenlos)
//
// Ein Zeitplan („Cron Trigger“, z. B. alle 3 Minuten) ruft die öffentliche OPSUCHT-Auktions-API ab – unabhängig
// davon, ob jemand spielt. Verschwindet ein Sofortkauf-Angebot vor seinem Ende, gilt es als verkauft.
// Gezählt wird je Item-Name + Item-Art („bohrer v3|diamond_pickaxe“). Gespeichert in einer D1-Datenbank (Bindung „AH“):
//   state  – letzte Angebote (zum Vergleich)            → 1 Zeile
//   sum    – fertige Zusammenfassung für die Mod (JSON)  → 1 Zeile, wird bei /ah unverändert ausgeliefert
//   sales  – jeder einzelne Verkauf (für später)        → 1 Zeile je Verkauf
// So bleibt jeder Lauf klein (wenig Rechenzeit, wenige Datenbankzeilen).

const AH_API = "https://api.opsucht.net/auctions/active";
const AH_DAYS = 8; // Tageswerte für „letzte 7 Tage“

function ahText(e) {
	if (e === null || e === undefined) return "";
	if (typeof e === "string") {
		const t = e.trim();
		if (t.startsWith("{") || t.startsWith("[")) {
			try { return ahText(JSON.parse(t)); } catch { return e; }
		}
		return e;
	}
	if (Array.isArray(e)) return e.map(ahText).join("");
	if (typeof e === "object") return ahText(e.text) + ahText(e.extra);
	return String(e);
}

function ahName(item) {
	for (const k of ["displayName", "customName", "name", "display_name", "itemName"]) {
		const s = ahText(item[k]);
		if (s && s.trim()) return s;
	}
	for (const k of ["meta", "components", "itemMeta"]) {
		const o = item[k];
		if (o && typeof o === "object") {
			const s = ahName(o) || ahText(o["minecraft:custom_name"]);
			if (s && s.trim()) return s;
		}
	}
	return "";
}

function ahKey(name, material, stars) {
	const n = name.replace(/[§&][0-9a-fk-orx]/gi, "").replace(/\s+/g, " ").trim().toLowerCase();
	if (!n) return "";
	const k = `${n}|${String(material || "").toLowerCase()}`;
	return stars >= 0 ? `${k}|${stars}*` : k;
}

// Gefüllte Sterne in der „Zustand:“-Zeile (✯ voll, ✩ leer), oder -1 ohne Zustand
function ahStars(item) {
	let lore = item.lore;
	if (typeof lore === "string") { try { lore = JSON.parse(lore); } catch { lore = [lore]; } }
	if (!Array.isArray(lore)) return -1;
	for (const l of lore) {
		const t = ahText(l).replace(/[§&][0-9a-fk-orx]/gi, "");
		const i = t.indexOf("Zustand");
		if (i < 0) continue;
		let n = 0;
		for (const c of t.slice(i)) if (c === "\u272F" || c === "\u2605") n++;
		return n;
	}
	return -1;
}

function ahEnd(a) {
	for (const k of ["endTime", "endsAt", "end", "expiresAt", "expiry", "endDate"]) {
		const v = a[k];
		if (typeof v === "number") return v < 1e10 ? v * 1000 : v;
		if (typeof v === "string") { const t = Date.parse(v); if (!isNaN(t)) return t; }
	}
	return 0;
}

async function ahRun(env) {
	if (!env.AH) return;
	const db = env.AH;
	await db.batch([
		db.prepare("CREATE TABLE IF NOT EXISTS ah_kv (k TEXT PRIMARY KEY, v TEXT)"),
		db.prepare("CREATE TABLE IF NOT EXISTS ah_sales (key TEXT, price REAL, t INTEGER)"),
	]);
	const res = await fetch(AH_API, { headers: { "User-Agent": "Loothub-Server/1.0" } });
	if (!res.ok) return;
	const list = await res.json();
	if (!Array.isArray(list)) return;
	const now = Date.now();
	const cur = {};
	const offers = {};
	for (const a of list) {
		const item = a && a.item;
		const price = a && a.instantBuyPrice;
		if (!item || !(price > 0)) continue;
		const key = ahKey(ahName(item), item.material, ahStars(item));
		if (!key) continue;
		const per = price / Math.max(1, item.amount || 1);
		const o = offers[key] || (offers[key] = [per, 0]);
		if (per < o[0]) o[0] = per;
		o[1]++;
		const id = a.id ?? a.uuid ?? a.auctionId ?? a.uid ?? a._id;
		if (id !== undefined) cur[String(id)] = [key, per, ahEnd(a)];
	}
	const rows = await db.prepare("SELECT k, v FROM ah_kv WHERE k IN ('state', 'sum')").all();
	let prev = {};
	let sum = {};
	for (const r of rows.results || []) {
		try {
			if (r.k === "state") prev = JSON.parse(r.v).prev || {};
			if (r.k === "sum") sum = JSON.parse(r.v).items || {};
		} catch { /* neu anfangen */ }
	}
	const day = new Date(now).toISOString().slice(0, 10);
	const oldest = new Date(now - AH_DAYS * 86400000).toISOString().slice(0, 10);
	const sold = [];
	for (const id in prev) {
		if (cur[id]) continue;
		const [key, per, end] = prev[id];
		if (end && end <= now + 60000) continue; // regulär abgelaufen, kein Verkauf
		sold.push([key, per]);
		const e = sum[key] || (sum[key] = { n: 0, s: 0, mn: per, mx: per, since: now, d: {} });
		e.mn = e.n === 0 ? per : Math.min(e.mn, per);
		e.mx = e.n === 0 ? per : Math.max(e.mx, per);
		e.n++;
		e.s += per;
		e.lp = per;
		e.lt = now;
		const d = e.d[day] || (e.d[day] = [0, 0]);
		d[0]++;
		d[1] += per;
	}
	for (const key in sum) {
		const e = sum[key];
		for (const k in e.d) if (k < oldest) delete e.d[k];
		if (offers[key]) e.o = offers[key]; else delete e.o;
	}
	for (const key in offers) {
		if (!sum[key]) sum[key] = { n: 0, s: 0, mn: 0, mx: 0, since: now, d: {}, o: offers[key] };
	}
	const stmts = [
		db.prepare("INSERT OR REPLACE INTO ah_kv (k, v) VALUES ('state', ?)").bind(JSON.stringify({ prev: cur })),
		db.prepare("INSERT OR REPLACE INTO ah_kv (k, v) VALUES ('sum', ?)").bind(JSON.stringify({ updated: now, items: sum })),
	];
	// Einzelverkäufe in Päckchen (höchstens 100 Platzhalter je Anweisung)
	for (let i = 0; i < sold.length; i += 30) {
		const part = sold.slice(i, i + 30);
		stmts.push(db.prepare("INSERT INTO ah_sales (key, price, t) VALUES " + part.map(() => "(?, ?, ?)").join(", "))
			.bind(...part.flatMap(([k, p]) => [k, p, now])));
	}
	await db.batch(stmts.slice(0, 45));
}

async function ahServe(request, env, ctx) {
	const cacheKey = new Request(new URL("/ah-cache", request.url).toString());
	const hit = await caches.default.match(cacheKey);
	if (hit) return hit;
	if (!env.AH) return json({ error: "no-db" }, 503);
	let body;
	try {
		const row = await env.AH.prepare("SELECT v FROM ah_kv WHERE k = 'sum'").first();
		body = row ? row.v : JSON.stringify({ updated: 0, items: {} });
	} catch {
		return json({ error: "busy" }, 503);
	}
	const res = new Response(body, {
		headers: { "content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=120" },
	});
	ctx.waitUntil(caches.default.put(cacheKey, res.clone()));
	return res;
}

// ------------------------------------------------------------------ Loothub-Fortschritt
// Erfolge, Quests (alle 4 Stunden, wöchentlich, Gemeinschaft), Streak, Punkte, Shop, Ranglisten.
// Speicher: D1 (Bindung AH). Die Mod meldet nur Zähler-Summen; der Server rechnet Fortschritt und Punkte selbst
// und kappt unplausible Sprünge.
//
// POST /lh         → unterschrieben: {payload: "{op: sync|buy|equip, …}"} → eigener Stand
// GET  /lh/board?b=points|ach|login|dquests|wquests|blocks|mobs|fish|play|jobday&p=d|w|m|a → beste 50 (5 min zwischengespeichert)
// GET  /lh/tags    → {uuid: [titel, abzeichen]} aller Spieler, die etwas angelegt haben (5 min)

const LH_HOUR = 3600_000;
const LH_DAY = 24 * LH_HOUR;
// Tages- und Quest-Grenzen nach deutscher Uhrzeit (0, 4, 8 … Uhr), mit Sommer- und Winterzeit
const LH_FMT = new Intl.DateTimeFormat("en-US", { timeZone: "Europe/Berlin", hourCycle: "h23", year: "numeric",
	month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
function lhTz(now) {
	const p = Object.fromEntries(LH_FMT.formatToParts(new Date(now)).map((x) => [x.type, x.value]));
	const local = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute);
	return Math.round((local - Math.floor(now / 60000) * 60000) / LH_HOUR) * LH_HOUR;
}
const LH_SLOT = 4 * LH_HOUR;
const LH_STATS = ["blocks", "placed", "mobs", "fish", "play", "dist", "treasures", "jobmoney", "itemdex", "eat", "jumps", "picked", "dropped"];
// höchstens so viel Zuwachs pro Sekunde zwischen zwei Meldungen (dazu ein Puffer von 2 Minuten)
const LH_RATE = { blocks: 25, placed: 25, mobs: 6, fish: 0.3, play: 0.025, dist: 120, treasures: 1, jobmoney: 50000,
	itemdex: 5, eat: 2, jumps: 6, picked: 60, dropped: 60 };
const LH_FIRST_MAX = { blocks: 5e7, placed: 5e7, mobs: 5e6, fish: 1e6, play: 2e6, dist: 5e8, treasures: 1e6,
	jobmoney: 1e12, itemdex: 1e5, eat: 1e6, jumps: 5e7, picked: 1e8, dropped: 1e8 };
const LH_TIER_POINTS = [10, 25, 50, 100, 200, 400, 800];
const LH_HISTORY_SHARE = 0.25; // Anteil der Taler für Erfolgsstufen, die beim ersten Abgleich schon erreicht sind
const LH_ACH = {
	blocks: [1000, 10000, 50000, 250000, 1000000, 5000000],
	placed: [500, 5000, 25000, 100000, 500000],
	picked: [1000, 10000, 100000, 500000, 1000000, 5000000],
	mobs: [100, 1000, 5000, 25000, 100000],
	fish: [50, 500, 2500, 10000, 50000],
	play: [600, 3000, 12000, 30000, 60000],
	treasures: [10, 100, 500, 2500],
	jobmoney: [100000, 1000000, 10000000, 100000000, 1000000000],
	eat: [100, 1000, 5000, 20000],
	jumps: [1000, 10000, 100000, 500000],
	dquests: [1, 3, 5, 10, 25, 50, 75, 100],
	wquests: [1, 3, 6, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100],
	login: [1, 3, 7, 14, 30, 50, 75, 100],
};
// Quest-Ziele: [leicht, mittel, schwer] je 4 Stunden bzw. je Woche
const LH_Q = {
	blocks: [[300, 1000, 3000], [10000, 25000, 60000]],
	placed: [[200, 600, 1500], [5000, 15000, 40000]],
	mobs: [[30, 100, 250], [800, 2000, 5000]],
	fish: [[10, 30, 60], [150, 400, 900]],
	play: [[30, 60, 120], [300, 600, 1200]],
	jumps: [[200, 500, 1000], [3000, 8000, 20000]],
	eat: [[10, 25, 50], [100, 250, 500]],
	treasures: [[1, 3, 6], null], // Jobschätze nicht als Wochenquest
};
const LH_Q_POINTS = [[10, 20, 40], [100, 175, 300]];
const LH_COMM = { blocks: 500000, mobs: 50000, fish: 5000, placed: 250000 };
const LH_COMM_REWARD = 150;
const LH_SHOP = [
	["s_freeze", "freeze", 400], // Login-Schutz: verbrauchbar, mehrfach kaufbar
	["t_miner", "title", 2000], ["t_hunter", "title", 2000], ["t_angler", "title", 2000], ["t_builder", "title", 2000],
	["t_explorer", "title", 2000], ["t_collector", "title", 4000], ["t_night", "title", 4000], ["t_trader", "title", 7000],
	["t_lootking", "title", 16000], ["t_legend", "title", 30000],
	["b_star", "badge", 600], ["b_heart", "badge", 600], ["b_diamond", "badge", 600], ["b_sun", "badge", 1000],
	["b_moon", "badge", 1000], ["b_flower", "badge", 1000], ["b_sword", "badge", 1600], ["b_bolt", "badge", 1600],
	["b_note", "badge", 1600], ["b_skull", "badge", 3000], ["b_crown", "badge", 10000],
	["c_hearts", "steps", 2400], ["c_notes", "steps", 2400], ["c_snow", "steps", 3600], ["c_magic", "steps", 5000],
	["c_soul", "steps", 7000], ["c_stars", "steps", 10000],
];
const LH_SHOP_MAP = Object.fromEntries(LH_SHOP.map(([id, kind, cost]) => [id, { kind, cost }]));
// Fußspuren-Stile aus dem Shop (Wert in den Cosmetics → nötiger Shop-Artikel)
const LH_STEP_ITEMS = { hearts: "c_hearts", notes: "c_notes", snow: "c_snow", magic: "c_magic", soul: "c_soul", stars: "c_stars" };
const LH_BOARDS = ["points", "ach", "login", "dquests", "wquests", "blocks", "placed", "picked", "dropped", "mobs", "fish", "play", "jobday"];

const LH_DROP_MIN_PLAY = { d: 30, w: 180, m: 600, a: 1200 }; // Mindest-Spielminuten für „am wenigsten weggeworfen“
const LH_PERIODS = ["d", "w", "m"]; // täglich, wöchentlich, monatlich (dazu „a“ = gesamt)
let lhReady = false;
async function lhInit(db) {
	if (lhReady) return;
	await db.batch([
		db.prepare(`CREATE TABLE IF NOT EXISTS lh_player (uuid TEXT PRIMARY KEY, name TEXT, data TEXT, points INTEGER DEFAULT 0,
			spent INTEGER DEFAULT 0, title TEXT DEFAULT '', badge TEXT DEFAULT '', ach INTEGER DEFAULT 0, quests INTEGER DEFAULT 0,
			streak INTEGER DEFAULT 0, blocks INTEGER DEFAULT 0, mobs INTEGER DEFAULT 0, fish INTEGER DEFAULT 0, play INTEGER DEFAULT 0,
			dist INTEGER DEFAULT 0, jobmoney INTEGER DEFAULT 0, itemdex INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)`),
		db.prepare("CREATE TABLE IF NOT EXISTS lh_comm (week INTEGER PRIMARY KEY, stat TEXT, goal INTEGER, sum INTEGER DEFAULT 0)"),
		db.prepare("CREATE TABLE IF NOT EXISTS lh_meta (k TEXT PRIMARY KEY, v TEXT)"),
	]);
	// später dazugekommene Spalten: Ranglisten-Werte gesamt und je Tag/Woche/Monat samt Zeitraum-Kennung
	const have = new Set(((await db.prepare("PRAGMA table_info(lh_player)").all()).results || []).map((c) => c.name));
	const want = [...LH_BOARDS, "points_a", "pd", "pw", "pm"];
	for (const b of LH_BOARDS) for (const p of LH_PERIODS) want.push(`${b}_${p}`);
	for (const col of want) {
		if (!have.has(col)) {
			try { await db.prepare(`ALTER TABLE lh_player ADD COLUMN ${col} INTEGER DEFAULT 0`).run(); } catch { /* vorhanden */ }
		}
	}
	lhReady = true;
}

// Zufall mit festem Startwert: alle Spieler bekommen dieselben Quests
function lhRand(seed) {
	let h = 1779033703 ^ seed.length;
	for (let i = 0; i < seed.length; i++) {
		h = Math.imul(h ^ seed.charCodeAt(i), 3432918353);
		h = (h << 13) | (h >>> 19);
	}
	let a = h >>> 0;
	return () => {
		a = (a + 0x6D2B79F5) | 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

function lhSlotId(now) { return Math.floor((now + lhTz(now)) / LH_SLOT); }
function lhDayId(now) { return Math.floor((now + lhTz(now)) / LH_DAY); }
function lhWeekId(now) { return Math.floor((lhDayId(now) - 4) / 7); } // Wochen ab Montag 0 Uhr
function lhLocalToUtc(t) { return t - lhTz(t - LH_HOUR); }
function lhMonthId(now) { const t = new Date(now + lhTz(now)); return t.getUTCFullYear() * 12 + t.getUTCMonth(); }
function lhSlotEnd(slot) { return lhLocalToUtc((slot + 1) * LH_SLOT); }
function lhWeekEnd(week) { return lhLocalToUtc(((week + 1) * 7 + 4) * LH_DAY); }

/** Drei Quests (je eine leichte, mittlere, schwere) für einen 4-Stunden-Abschnitt (kind 0) oder eine Woche (kind 1). */
function lhQuests(kind, id) {
	const rnd = lhRand(`lh-${kind}-${id}`);
	const stats = Object.keys(LH_Q).filter((s) => LH_Q[s][kind]);
	const out = [];
	for (let d = 0; d < 3; d++) {
		let s;
		do { s = stats[Math.floor(rnd() * stats.length)]; } while (out.some((q) => q.stat === s));
		out.push({ stat: s, goal: LH_Q[s][kind][d], points: LH_Q_POINTS[kind][d], diff: d });
	}
	return out;
}

function lhCommunity(week) {
	const stats = Object.keys(LH_COMM);
	const s = stats[Math.floor(lhRand(`lh-comm-${week}`)() * stats.length)];
	return { stat: s, goal: LH_COMM[s] };
}

function lhCatalog() {
	return { ach: LH_ACH, tierPoints: LH_TIER_POINTS, shop: LH_SHOP, boards: LH_BOARDS,
		commReward: LH_COMM_REWARD };
}

function lhTier(stat, v) {
	const t = LH_ACH[stat];
	let n = 0;
	while (n < t.length && v >= t[n]) n++;
	return n;
}

/** Gemeldete Summen übernehmen: Zuwachs je Zähler, gekappt; nach einem Zurücksetzen der Statistik zählt der neue Wert. */
function lhAbsorb(d, reported, now) {
	d.raw = d.raw || {};
	d.tot = d.tot || {};
	const first = !d.last;
	d.delta = {};
	d.first = first; // erster Abgleich: Erfolge aus alter Statistik bringen nur einen Teil der Taler
	const secs = Math.min(6 * 3600, Math.max(0, (now - (d.last || now)) / 1000)) + 120;
	for (const s of LH_STATS) {
		let v = Number(reported[s]);
		if (!Number.isFinite(v) || v < 0) continue;
		v = Math.floor(v);
		const prev = d.raw[s];
		let delta;
		if (first || prev === undefined) delta = Math.min(v, LH_FIRST_MAX[s]);
		else delta = v >= prev ? v - prev : v; // kleiner als vorher: Statistik zurückgesetzt
		if (!first && prev !== undefined) delta = Math.min(delta, Math.ceil(LH_RATE[s] * secs));
		d.raw[s] = v;
		d.tot[s] = (d.tot[s] || 0) + delta;
		d.delta[s] = first ? 0 : delta;
	}
	d.last = now;
}

function lhCopy(tot) { return Object.fromEntries(LH_STATS.map((s) => [s, tot[s] || 0])); }

/** Fortschritt durchrechnen: Erfolge, Quests, Streak, Gemeinschaft. Gibt die neu verdienten Punkte und Neuigkeiten. */
async function lhProgress(db, d, now) {
	const news = [];
	let earned = 0;
	const tot = d.tot;
	d.ach = d.ach || {};
	d.quests = d.quests || 0;
	d.dq = d.dq || 0; // erledigte 4-Stunden-Quests
	d.wq = d.wq || 0; // erledigte Wochenquests
	d.login = d.login || { cur: 0, best: 0, day: -1 };
	d.freeze = d.freeze || 0;

	// Login-Serie: jeden Tag mindestens einmal online
	const day0 = lhDayId(now);
	if (d.login.day !== day0) {
		// verpasste Tage mit Login-Schutz überbrücken (ein Schutz je verpasstem Tag)
		const missed = d.login.day >= 0 ? day0 - d.login.day - 1 : 0;
		if (missed > 0 && d.login.cur > 0 && missed <= d.freeze) {
			d.freeze -= missed;
			d.login.day = day0 - 1;
			news.push({ k: "freeze_used", n: missed, p: 0 });
		}
		d.login.cur = d.login.day === day0 - 1 ? d.login.cur + 1 : 1;
		d.login.day = day0;
		d.login.best = Math.max(d.login.best, d.login.cur);
		// Tagesbelohnung steigt nur mit jeder erreichten Stufe der Login-Serie (1, 3, 7, 14 … Tage): +2 Taler je Stufe
		const bonus = 2 * Math.max(1, lhTier("login", d.login.cur));
		earned += bonus;
		news.push({ k: "login", n: d.login.cur, p: bonus });
	}

	// 4-Stunden-Quests
	const slot = lhSlotId(now);
	if (!d.slot || d.slot.id !== slot) d.slot = { id: slot, base: lhCopy(tot), done: [false, false, false] };
	const today = lhDayId(now);
	lhQuests(0, slot).forEach((q, i) => {
		if (d.slot.done[i]) return;
		if ((tot[q.stat] || 0) - (d.slot.base[q.stat] || 0) >= q.goal) {
			d.slot.done[i] = true;
			earned += q.points;
			d.quests++;
			d.dq++;
			news.push({ k: "quest", stat: q.stat, goal: q.goal, p: q.points });
		}
	});

	// Wochenquests und Gemeinschaftsquest
	const week = lhWeekId(now);
	if (d.week && d.week.id !== week) {
		// Belohnung der vergangenen Woche nachholen, falls das Ziel erreicht wurde
		const old = await db.prepare("SELECT stat, goal, sum FROM lh_comm WHERE week = ?").bind(d.week.id).first();
		if (old && !d.week.paid && old.sum >= old.goal && (d.week.comm || 0) >= Math.ceil(old.goal / 200)) {
			earned += LH_COMM_REWARD;
			news.push({ k: "comm", stat: old.stat, p: LH_COMM_REWARD });
		}
		d.week = null;
	}
	if (!d.week) d.week = { id: week, base: lhCopy(tot), done: [false, false, false], comm: 0, paid: false };
	lhQuests(1, week).forEach((q, i) => {
		if (d.week.done[i]) return;
		if ((tot[q.stat] || 0) - (d.week.base[q.stat] || 0) >= q.goal) {
			d.week.done[i] = true;
			earned += q.points;
			d.quests++;
			d.wq++;
			news.push({ k: "week", stat: q.stat, goal: q.goal, p: q.points });
		}
	});
	const cq = lhCommunity(week);
	const mine = Math.max(0, (tot[cq.stat] || 0) - (d.week.base[cq.stat] || 0));
	const add = mine - (d.week.comm || 0);
	let comm = await db.prepare("SELECT sum FROM lh_comm WHERE week = ?").bind(week).first();
	if (add > 0) {
		await db.prepare("INSERT INTO lh_comm (week, stat, goal, sum) VALUES (?, ?, ?, ?) ON CONFLICT(week) DO UPDATE SET sum = sum + excluded.sum")
			.bind(week, cq.stat, cq.goal, add).run();
		comm = { sum: (comm ? comm.sum : 0) + add };
	}
	d.week.comm = mine;
	const commSum = comm ? comm.sum : 0;
	if (!d.week.paid && commSum >= cq.goal && mine >= Math.ceil(cq.goal / 200)) {
		d.week.paid = true;
		earned += LH_COMM_REWARD;
		news.push({ k: "comm", stat: cq.stat, p: LH_COMM_REWARD });
	}

	// Bester Job-Tag: Verdienst je Tag (deutsche Zeit) seit dem ersten Abgleich des Tages
	const jd = d.jd || (d.jd = { day: -1, start: 0, best: 0 });
	const dayNow = lhDayId(now);
	if (jd.day !== dayNow) { jd.day = dayNow; jd.start = tot.jobmoney || 0; }
	jd.best = Math.max(jd.best, (tot.jobmoney || 0) - jd.start);

	// Erfolge
	const metas = { dquests: d.dq, wquests: d.wq, login: d.login.best };
	for (const stat of Object.keys(LH_ACH)) {
		const v = stat in metas ? metas[stat] : (tot[stat] || 0);
		const t = lhTier(stat, v);
		const had = d.ach[stat] || 0;
		for (let k = had; k < t; k++) {
			const full = LH_TIER_POINTS[Math.min(k, LH_TIER_POINTS.length - 1)];
			const p = d.first ? Math.round(full * LH_HISTORY_SHARE) : full;
			earned += p;
			news.push({ k: "ach", stat, tier: k + 1, p });
			if (stat === "login") {
				d.freeze = (d.freeze || 0) + 1; // jede Login-Erfolgsstufe gibt einen Login-Schutz
				news.push({ k: "freeze", n: 1, p: 0 });
			}
		}
		if (t > had) d.ach[stat] = t;
	}
	return { earned, news, commSum, cq };
}

function lhView(row, d, extra, now) {
	const slot = lhSlotId(now), week = lhWeekId(now);
	const q4 = lhQuests(0, slot).map((q, i) => ({ ...q, have: Math.max(0, (d.tot[q.stat] || 0) - (d.slot.base[q.stat] || 0)), done: !!d.slot.done[i] }));
	const qw = lhQuests(1, week).map((q, i) => ({ ...q, have: Math.max(0, (d.tot[q.stat] || 0) - (d.week.base[q.stat] || 0)), done: !!d.week.done[i] }));
	return {
		now, points: row.points, balance: row.points - row.spent, title: row.title, badge: row.badge,
		tot: lhCopy(d.tot), ach: d.ach, quests: d.quests,
		dquests: d.dq || 0, wquests: d.wq || 0, jobday: Math.floor((d.jd && d.jd.best) || 0),
		login: { cur: (d.login || {}).cur || 0, best: (d.login || {}).best || 0, freeze: d.freeze || 0 },
		slot: { ends: lhSlotEnd(slot), list: q4 },
		week: { ends: lhWeekEnd(week), list: qw },
		comm: extra ? { stat: extra.cq.stat, goal: extra.cq.goal, sum: extra.commSum, mine: d.week.comm || 0,
			need: Math.ceil(extra.cq.goal / 200), paid: !!d.week.paid, reward: LH_COMM_REWARD } : null,
		owned: d.owned || [], news: extra ? extra.news : [], cat: lhCatalog(),
	};
}

/** Rücksetzungen aller Spieler: {"board|zeitraum": Zeitpunkt}. */
async function lhResets(db) {
	try {
		const r = await db.prepare("SELECT v FROM lh_meta WHERE k = 'resets'").first();
		return r ? JSON.parse(r.v) : {};
	} catch { return {}; }
}

/** Spieler-Zeile mit allen Ranglisten-Werten speichern. */
async function lhSave(db, row, d, now, name, resets) {
	const tot = d.tot || {};
	const achCount = Object.values(d.ach || {}).reduce((a, n) => a + n, 0);
	const bv = lhBoardValues(d, row, achCount, now, resets || await lhResets(db));
	const cols = {
		...bv.all, uuid: row.uuid, name: name || row.name || "", data: JSON.stringify(d), spent: row.spent || 0,
		title: row.title || "", badge: row.badge || "", quests: d.quests || 0, jobmoney: tot.jobmoney || 0, updated: now,
		pd: bv.ids.d, pw: bv.ids.w, pm: bv.ids.m,
	};
	// „points“ = alle je verdienten Taler (für das Guthaben); die Rangliste „gesamt“ liest points_a (nach Rücksetzung neu)
	cols.points_a = bv.all.points;
	cols.points = row.points || 0;
	for (const p of LH_PERIODS) for (const b of LH_BOARDS) cols[`${b}_${p}`] = bv.per[p][b];
	const names = Object.keys(cols);
	await db.prepare(`INSERT INTO lh_player (${names.join(", ")}) VALUES (${names.map(() => "?").join(", ")})
		ON CONFLICT(uuid) DO UPDATE SET ${names.filter((n) => n !== "uuid").map((n) => `${n} = excluded.${n}`).join(", ")}`)
		.bind(...names.map((n) => cols[n])).run();
}

/** Ist diese UUID Admin? Liste in der Cloudflare-Variable LH_ADMINS (UUIDs, durch Komma getrennt). */
function lhIsAdmin(env, uuid) {
	const list = String(env.LH_ADMINS || "").toLowerCase().split(/[\s,;]+/).map((x) => x.replace(/-/g, "")).filter(Boolean);
	return list.includes(uuid.replace(/-/g, "").toLowerCase());
}

/** Admin: Taler eines Spielers ändern, Ranglisten zurücksetzen. Gibt {ok, msg} oder {error}. */
async function lhAdmin(db, env, p, now) {
	const target = async () => {
		if (typeof p.target !== "string" || !NAME.test(p.target)) return null;
		const t = await db.prepare("SELECT * FROM lh_player WHERE lower(name) = lower(?) ORDER BY updated DESC LIMIT 1").bind(p.target).first();
		if (!t) return null;
		let td;
		try { td = JSON.parse(t.data || "{}"); } catch { td = {}; }
		return [t, td];
	};
	if (p.op === "admin_taler") {
		const amount = Math.trunc(Number(p.amount));
		if (!Number.isFinite(amount) || Math.abs(amount) > 1e9 || !["add", "set"].includes(p.mode)) return { error: "bad amount" };
		const found = await target();
		if (!found) return { error: "no player" };
		const [t, td] = found;
		const balance = (t.points || 0) - (t.spent || 0);
		// Guthaben über „ausgegeben“ ändern – die verdienten Taler (Rangliste) bleiben gleich
		const want = p.mode === "set" ? Math.max(0, amount) : Math.max(0, balance + amount);
		t.spent = (t.points || 0) - want;
		await lhSave(db, t, td, now, t.name);
		return { ok: true, name: t.name, balance: want };
	}
	if (p.op === "admin_reset") {
		const b = p.board, per = p.period;
		if (!LH_BOARDS.includes(b) || !(per === "a" || LH_PERIODS.includes(per))) return { error: "bad board" };
		const key = `${b}|${per}`;
		const col = per === "a" ? (b === "points" ? "points_a" : b) : `${b}_${per}`;
		if (p.target) {
			const found = await target();
			if (!found) return { error: "no player" };
			const [t, td] = found;
			const resets = { ...(await lhResets(db)), [key]: now };
			td.rs = { ...(td.rs || {}) };
			delete td.rs[key];
			td.delta = {}; // kein Abgleich: nichts Neues dazugekommen
			await lhSave(db, t, td, now, t.name, resets);
			return { ok: true, name: t.name };
		}
		const resets = await lhResets(db);
		resets[key] = now;
		await db.batch([
			db.prepare("INSERT OR REPLACE INTO lh_meta (k, v) VALUES ('resets', ?)").bind(JSON.stringify(resets)),
			db.prepare(`UPDATE lh_player SET ${col} = 0`),
		]);
		return { ok: true };
	}
	return { error: "bad op" };
}

async function lhHandle(request, env) {
	if (!env.AH) return json({ error: "no-db" }, 503);
	const [body, err] = await signedBody(request, env, async (b) =>
		typeof b.payload === "string" && b.payload.length < 4000 ? `loothub-lh\n${b.uuid}\n${b.ts}\n${b.payload}` : null);
	if (err) return err;
	let p;
	try { p = JSON.parse(body.payload); } catch { return json({ error: "bad payload" }, 400); }
	const db = env.AH;
	await lhInit(db);
	const uuid = body.uuid;
	const name = typeof body.me === "string" && NAME.test(body.me) ? body.me : "";
	const now = Date.now();
	let row = await db.prepare("SELECT * FROM lh_player WHERE uuid = ?").bind(uuid).first();
	if (!row) row = { uuid, name, data: "{}", points: 0, spent: 0, title: "", badge: "" };
	let d;
	try { d = JSON.parse(row.data || "{}"); } catch { d = {}; }
	d.owned = Array.isArray(d.owned) ? d.owned : [];
	let extra = null;
	let error = null;

	if (typeof p.op === "string" && p.op.startsWith("admin_")) {
		if (!lhIsAdmin(env, uuid)) return json({ error: "not admin" }, 403);
		return json(await lhAdmin(db, env, p, now));
	}
	if (p.op === "sync") {
		if (!p.stats || typeof p.stats !== "object") return json({ error: "bad payload" }, 400);
		lhAbsorb(d, p.stats, now);
		extra = await lhProgress(db, d, now);
		row.points = (row.points || 0) + extra.earned;
	} else {
		if (!d.tot || !d.slot || !d.week) return json({ error: "sync first" }, 409);
		if (p.op === "buy") {
			const item = LH_SHOP_MAP[p.item];
			if (!item) error = "unknown";
			else if (item.kind === "freeze") {
				if (row.points - row.spent < item.cost) error = "poor";
				else { d.freeze = (d.freeze || 0) + 1; row.spent += item.cost; }
			} else if (d.owned.includes(p.item)) error = "owned";
			else if (row.points - row.spent < item.cost) error = "poor";
			else { d.owned.push(p.item); row.spent += item.cost; }
		} else if (p.op === "equip") {
			const t = typeof p.title === "string" ? p.title : row.title;
			const b = typeof p.badge === "string" ? p.badge : row.badge;
			if ((t && !(d.owned.includes(t) && LH_SHOP_MAP[t].kind === "title")) || (b && !(d.owned.includes(b) && LH_SHOP_MAP[b].kind === "badge"))) error = "not owned";
			else { row.title = t; row.badge = b; }
		} else {
			return json({ error: "bad op" }, 400);
		}
	}
	if (!error) await lhSave(db, row, d, now, name);
	const view = lhView(row, d, extra, now);
	if (lhIsAdmin(env, uuid)) view.admin = true;
	if (error) view.error = error;
	return json(view);
}

/**
 * Ranglisten-Werte: gesamt und für den laufenden Tag, die Woche und den Monat. Zähler zählen ab Beginn des Zeitraums
 * (Stand beim ersten Abgleich darin), die Login-Serie zählt mit ihrer aktuellen Länge, der beste Job-Tag als bester
 * Tag im Zeitraum (täglich: heute).
 */
function lhBoardValues(d, row, achCount, now, resets = {}) {
	const tot = d.tot || {};
	const jd = d.jd || { day: -1, start: 0, best: 0 };
	const today = Math.max(0, (tot.jobmoney || 0) - (jd.start || 0));
	const raw = {
		points: row.points || 0, ach: achCount, login: (d.login && d.login.best) || 0, dquests: d.dq || 0, wquests: d.wq || 0,
		blocks: tot.blocks || 0, placed: tot.placed || 0, picked: tot.picked || 0, dropped: tot.dropped || 0,
		mobs: tot.mobs || 0, fish: tot.fish || 0, play: tot.play || 0, jobday: Math.floor(jd.best || 0),
	};
	const ids = { d: lhDayId(now), w: lhWeekId(now), m: lhMonthId(now) };
	d.per = d.per || {};
	// Rücksetzungen durch einen Admin (alle Spieler über lh_meta, einzelne direkt): ab jetzt wieder bei 0 zählen
	d.rs = d.rs || {};
	d.off = d.off || {};
	if (d.created === undefined) d.created = d.first ? now : 0; // wer erst nach einer Rücksetzung dazukommt, ist nicht betroffen
	for (const key of Object.keys(resets)) {
		if ((d.rs[key] || 0) >= resets[key]) continue;
		if (resets[key] < d.created) { d.rs[key] = resets[key]; continue; }
		d.rs[key] = resets[key];
		const [b, p] = key.split("|");
		if (p === "a") {
			if (b === "login") { if (d.login) d.login.best = d.login.cur || 0; }
			else if (b === "jobday") { if (d.jd) d.jd.best = 0; raw.jobday = 0; }
			else {
				// ab jetzt bei 0 – was seit dem letzten Abgleich dazukam, zählt schon; Tag/Woche/Monat laufen weiter
				const newOff = Math.max(0, (raw[b] || 0) - ((d.delta && d.delta[b]) || 0));
				const shift = newOff - (d.off[b] || 0);
				d.off[b] = newOff;
				for (const q of LH_PERIODS) if (d.per[q] && d.per[q].base) d.per[q].base[b] = (d.per[q].base[b] || 0) - shift;
			}
		} else if (d.per[p] && d.per[p].id === ids[p]) {
			if (b === "jobday") d.per[p].job = 0;
			else d.per[p].base[b] = (raw[b] || 0) - (d.off[b] || 0) - ((d.delta && d.delta[b]) || 0);
		}
	}
	const all = {};
	for (const b of Object.keys(raw)) all[b] = Math.max(0, (raw[b] || 0) - (b === "login" || b === "jobday" ? 0 : (d.off[b] || 0)));
	all.login = (d.login && d.login.best) || 0;
	const out = { all, ids, per: {} };
	for (const p of LH_PERIODS) {
		let pe = d.per[p];
		if (!pe || pe.id !== ids[p]) pe = d.per[p] = { id: ids[p], base: { ...all }, job: 0 };
		pe.job = Math.max(pe.job || 0, today);
		const v = {};
		for (const b of LH_BOARDS) {
			if (b === "login") v[b] = (d.login && d.login.cur) || 0;
			else if (b === "jobday") v[b] = Math.floor(p === "d" ? today : pe.job);
			else v[b] = Math.max(0, (all[b] || 0) - (pe.base[b] || 0));
		}
		out.per[p] = v;
	}
	return out;
}

async function lhCached(request, ctx, key, ttl, make) {
	const cacheKey = new Request(new URL(key, request.url).toString());
	const hit = await caches.default.match(cacheKey);
	if (hit) return hit;
	const res = new Response(JSON.stringify(await make()), {
		headers: { "content-type": "application/json; charset=utf-8", "cache-control": `public, max-age=${ttl}` },
	});
	ctx.waitUntil(caches.default.put(cacheKey, res.clone()));
	return res;
}

async function lhBoard(request, env, ctx, url) {
	if (!env.AH) return json({ error: "no-db" }, 503);
	const b = url.searchParams.get("b") || "points";
	const p = url.searchParams.get("p") || "a";
	if (!LH_BOARDS.includes(b) || !(p === "a" || LH_PERIODS.includes(p))) return json({ error: "unknown board" }, 400);
	await lhInit(env.AH);
	const now = Date.now();
	const id = p === "d" ? lhDayId(now) : p === "w" ? lhWeekId(now) : p === "m" ? lhMonthId(now) : 0;
	return lhCached(request, ctx, `/lh-board-cache/${b}/${p}/${id}`, 300, async () => {
		const col = p === "a" ? (b === "points" ? "points_a" : b) : `${b}_${p}`;
		let where = p === "a" ? `${col} > 0` : `p${p} = ${id} AND ${col} > 0`;
		let order = `${col} DESC`;
		if (b === "dropped") {
			// wer am wenigsten wegwirft – aber nur mit genug Spielzeit im Zeitraum
			const play = p === "a" ? "play" : `play_${p}`;
			where = `${p === "a" ? "1 = 1" : `p${p} = ${id}`} AND ${play} >= ${LH_DROP_MIN_PLAY[p]}`;
			order = `${col} ASC, ${play} DESC`;
		}
		const r = await env.AH.prepare(`SELECT uuid, name, badge, ${col} AS v FROM lh_player WHERE ${where} ORDER BY ${order} LIMIT 50`).all();
		return { b, p, t: Date.now(), minPlay: b === "dropped" ? LH_DROP_MIN_PLAY[p] : 0, list: (r.results || []).map((x) => [x.uuid, x.name, x.badge || "", x.v]) };
	});
}

async function lhTags(request, env, ctx) {
	if (!env.AH) return json({});
	await lhInit(env.AH);
	return lhCached(request, ctx, "/lh-tags-cache", 300, async () => {
		const r = await env.AH.prepare("SELECT uuid, title, badge FROM lh_player WHERE title != '' OR badge != ''").all();
		const out = {};
		for (const x of r.results || []) out[x.uuid] = [x.title || "", x.badge || ""];
		return out;
	});
}

/** Shop-Fußspuren nur für Besitzer: nicht gekaufte Stile aus dem Cosmetics-Profil entfernen. */
async function lhCheckProfile(env, uuid, profile) {
	const steps = profile && profile.values && profile.values.footsteps;
	const need = steps && LH_STEP_ITEMS[steps];
	if (!need) return;
	let owned = [];
	try {
		if (env.AH) {
			await lhInit(env.AH);
			const row = await env.AH.prepare("SELECT data FROM lh_player WHERE uuid = ?").bind(uuid).first();
			owned = row ? (JSON.parse(row.data).owned || []) : [];
		}
	} catch { owned = []; }
	if (!owned.includes(need)) {
		delete profile.values.footsteps;
		profile.set = (profile.set || []).filter((s) => s !== "footsteps");
	}
}

export default {
	async scheduled(controller, env, ctx) {
		ctx.waitUntil(ahRun(env).catch(() => {}));
	},

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
			if (profile) await lhCheckProfile(env, uuid, profile);
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

		if (request.method === "POST" && ["/share", "/inbox", "/share-get", "/share-drop", "/unshare"].includes(url.pathname)) {
			try {
				const res = await handleBuild(url, request, env);
				if (res) return res;
			} catch (e) {
				// z. B. Tageslimit von Cloudflare erreicht – verständlich antworten statt abzustürzen
				return json({ error: "busy", detail: String(e && e.message || e).slice(0, 200) }, 503);
			}
		}

		if (request.method === "GET" && url.pathname === "/ah") {
			return ahServe(request, env, ctx);
		}

		if (url.pathname === "/lh" && request.method === "POST") {
			try {
				return await lhHandle(request, env);
			} catch (e) {
				return json({ error: "busy", detail: String(e && e.message || e).slice(0, 200) }, 503);
			}
		}
		if (url.pathname === "/lh/board" && request.method === "GET") {
			try { return await lhBoard(request, env, ctx, url); } catch { return json({ error: "busy" }, 503); }
		}
		if (url.pathname === "/lh/tags" && request.method === "GET") {
			try { return await lhTags(request, env, ctx); } catch { return json({}); }
		}

		return new Response("Loothub Cosmetics", { status: 200 });
	},
};
