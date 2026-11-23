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

// Ein D1-Wert darf höchstens ~2 MB groß sein: große JSON-Daten werden auf mehrere Zeilen verteilt
const AH_PART = 700_000;

/** Objekt {schlüssel: wert} in Teile zerlegen, die serialisiert je unter AH_PART bleiben. */
function ahSplit(items) {
	const parts = [];
	let cur = {}, size = 2;
	for (const k of Object.keys(items)) {
		const len = k.length + JSON.stringify(items[k]).length + 4;
		if (size + len > AH_PART && size > 2) { parts.push(cur); cur = {}; size = 2; }
		cur[k] = items[k];
		size += len;
	}
	parts.push(cur);
	return parts;
}

/** Gespeichertes, ggf. aufgeteiltes Objekt lesen: Zeilen name, name.1, name.2 … */
async function ahLoad(db, name, field) {
	const rows = await db.prepare("SELECT k, v FROM ah_kv WHERE k = ? OR k LIKE ?").bind(name, `${name}.%`).all();
	const out = {};
	let meta = {};
	for (const r of rows.results || []) {
		try {
			const o = JSON.parse(r.v);
			if (r.k === name) meta = o;
			Object.assign(out, o[field] || {});
		} catch { /* kaputter Teil: überspringen */ }
	}
	return { meta, items: out };
}

/** Objekt aufgeteilt speichern; alte überzählige Teile löschen. */
function ahStore(db, name, field, items, extra) {
	const parts = ahSplit(items);
	const stmts = parts.map((part, i) => db.prepare("INSERT OR REPLACE INTO ah_kv (k, v) VALUES (?, ?)")
		.bind(i === 0 ? name : `${name}.${i}`, JSON.stringify(i === 0 ? { ...extra, parts: parts.length, [field]: part } : { [field]: part })));
	stmts.push(db.prepare("DELETE FROM ah_kv WHERE k LIKE ? AND CAST(substr(k, ?) AS INTEGER) >= ?")
		.bind(`${name}.%`, name.length + 2, parts.length));
	return stmts;
}

async function ahRun(env) {
	if (!env.AH) return;
	const db = env.AH;
	try {
		await ahCollect(db);
	} catch (e) {
		// Fehler merken, damit /ah ihn anzeigt (statt still veraltete Daten zu liefern)
		try {
			await db.prepare("INSERT OR REPLACE INTO ah_kv (k, v) VALUES ('err', ?)")
				.bind(JSON.stringify({ t: Date.now(), e: String(e && e.message || e).slice(0, 300) })).run();
		} catch { /* nichts zu machen */ }
	}
}

async function ahCollect(db) {
	await db.batch([
		db.prepare("CREATE TABLE IF NOT EXISTS ah_kv (k TEXT PRIMARY KEY, v TEXT)"),
		db.prepare("CREATE TABLE IF NOT EXISTS ah_sales (key TEXT, price REAL, t INTEGER)"),
	]);
	const res = await fetch(AH_API, { headers: { "User-Agent": "Loothub-Server/1.0" } });
	if (!res.ok) throw new Error(`OPSUCHT-API antwortet mit ${res.status}`);
	const list = await res.json();
	if (!Array.isArray(list)) throw new Error("OPSUCHT-API: unerwartetes Format");
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
	const prev = (await ahLoad(db, "state", "prev")).items;
	const sum = (await ahLoad(db, "sum", "items")).items;
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
		// nie verkauft und gerade nicht im Angebot: nichts zu merken
		if (!e.n && !e.o) { delete sum[key]; continue; }
		// Zahlen kürzen (spart Platz)
		e.s = Math.round(e.s * 100) / 100;
		for (const k in e.d) e.d[k][1] = Math.round(e.d[k][1] * 100) / 100;
	}
	for (const key in offers) {
		if (!sum[key]) sum[key] = { n: 0, s: 0, mn: 0, mx: 0, since: now, d: {}, o: offers[key] };
	}
	const stmts = [
		...ahStore(db, "state", "prev", cur, {}),
		...ahStore(db, "sum", "items", sum, { updated: now }),
		db.prepare("DELETE FROM ah_kv WHERE k = 'err'"),
	];
	// Einzelverkäufe in Päckchen (höchstens 100 Platzhalter je Anweisung)
	for (let i = 0; i < sold.length; i += 30) {
		const part = sold.slice(i, i + 30);
		stmts.push(db.prepare("INSERT INTO ah_sales (key, price, t) VALUES " + part.map(() => "(?, ?, ?)").join(", "))
			.bind(...part.flatMap(([k, p]) => [k, p, now])));
	}
	await db.batch(stmts.slice(0, 80));
}

async function ahServe(request, env, ctx) {
	const cacheKey = new Request(new URL("/ah-cache", request.url).toString());
	const hit = await caches.default.match(cacheKey);
	if (hit) return hit;
	if (!env.AH) return json({ error: "no-db" }, 503);
	let body;
	try {
		const { meta, items } = await ahLoad(env.AH, "sum", "items");
		const err = await env.AH.prepare("SELECT v FROM ah_kv WHERE k = 'err'").first();
		body = JSON.stringify({ updated: meta.updated || 0, error: err ? JSON.parse(err.v) : undefined, items });
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
const LH_STATS = ["blocks", "placed", "mobs", "fish", "play", "treasures", "jobmoney", "jumps", "picked", "dropped", "dist", "nether",
	"deep"];
// höchstens so viel Zuwachs pro Sekunde zwischen zwei Meldungen (dazu ein Puffer von 2 Minuten)
const LH_RATE = { blocks: 25, placed: 25, mobs: 6, fish: 0.3, play: 0.025, dist: 120, treasures: 1, jobmoney: 50000,
	itemdex: 5, eat: 2, jumps: 6, picked: 60, dropped: 60, nether: 0.025, deep: 0.025 };
const LH_FIRST_MAX = { blocks: 5e7, placed: 5e7, mobs: 5e6, fish: 1e6, play: 2e6, dist: 5e8, treasures: 1e6,
	jobmoney: 1e12, itemdex: 1e5, eat: 1e6, jumps: 5e7, picked: 1e8, dropped: 1e8, nether: 2e6, deep: 2e6 };
const LH_TIER_POINTS = [10, 12, 14, 16, 19, 23, 27, 32, 38, 44, 52, 62, 73, 86, 100, 120, 140, 170, 200, 230, 270, 320, 380, 450, 530, 630, 740, 870, 1000, 1200];
const LH_HISTORY_SHARE = 0.25; // Anteil der Taler für Erfolgsstufen, die beim ersten Abgleich schon erreicht sind
// Erfolge: je 30 Stufen
const LH_ACH = {
	blocks: [1000, 10000, 50000, 250000, 1000000, 5000000, 5700000, 6400000, 7300000, 8200000, 9300000, 11000000, 12000000, 14000000, 15000000, 17000000, 20000000, 22000000, 25000000, 29000000, 33000000, 37000000, 42000000, 47000000, 54000000, 61000000, 69000000, 78000000, 88000000, 100000000],
	placed: [500, 5000, 25000, 100000, 500000, 600000, 720000, 870000, 1000000, 1300000, 1500000, 1800000, 2200000, 2600000, 3200000, 3800000, 4600000, 5500000, 6600000, 7900000, 9500000, 11000000, 14000000, 17000000, 20000000, 24000000, 29000000, 35000000, 42000000, 50000000],
	picked: [1000, 10000, 100000, 500000, 1000000, 5000000, 5700000, 6400000, 7300000, 8200000, 9300000, 11000000, 12000000, 14000000, 15000000, 17000000, 20000000, 22000000, 25000000, 29000000, 33000000, 37000000, 42000000, 47000000, 54000000, 61000000, 69000000, 78000000, 88000000, 100000000],
	mobs: [100, 1000, 5000, 25000, 100000, 120000, 140000, 170000, 210000, 250000, 300000, 360000, 440000, 520000, 630000, 760000, 910000, 1100000, 1300000, 1600000, 1900000, 2300000, 2800000, 3300000, 4000000, 4800000, 5800000, 6900000, 8300000, 10000000],
	fish: [50, 500, 2500, 10000, 50000, 58000, 67000, 78000, 90000, 100000, 120000, 140000, 160000, 190000, 220000, 250000, 290000, 340000, 390000, 460000, 530000, 610000, 710000, 830000, 960000, 1100000, 1300000, 1500000, 1700000, 2000000],
	play: [600, 3000, 12000, 30000, 60000, 68000, 76000, 86000, 97000, 110000, 120000, 140000, 160000, 180000, 200000, 220000, 250000, 280000, 320000, 360000, 410000, 460000, 520000, 580000, 660000, 740000, 840000, 940000, 1100000, 1200000],
	treasures: [10, 100, 500, 2500, 2900, 3300, 3800, 4400, 5100, 5900, 6700, 7800, 9000, 10000, 12000, 14000, 16000, 18000, 21000, 24000, 28000, 32000, 37000, 43000, 49000, 57000, 65000, 75000, 87000, 100000],
	jobmoney: [100000, 1000000, 10000000, 100000000, 1000000000, 1200000000, 1400000000, 1700000000, 2100000000, 2500000000, 3000000000, 3600000000, 4400000000, 5200000000, 6300000000, 7600000000, 9100000000, 11000000000, 13000000000, 16000000000, 19000000000, 23000000000, 28000000000, 33000000000, 40000000000, 48000000000, 58000000000, 69000000000, 83000000000, 100000000000],
	jumps: [1000, 10000, 100000, 500000, 600000, 710000, 850000, 1000000, 1200000, 1400000, 1700000, 2100000, 2500000, 2900000, 3500000, 4200000, 5000000, 6000000, 7100000, 8500000, 10000000, 12000000, 14000000, 17000000, 21000000, 25000000, 29000000, 35000000, 42000000, 50000000],
	dquests: [1, 3, 5, 10, 25, 50, 75, 100, 130, 160, 210, 260, 330, 420, 540, 690, 870, 1100, 1400, 1800, 2300, 2900, 3700, 4700, 6000, 7600, 9700, 12000, 16000, 20000],
	wquests: [1, 3, 6, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 120, 140, 160, 190, 220, 260, 300, 360, 420, 490, 580, 680, 790, 930, 1100, 1300, 1500],
	login: [1, 3, 7, 14, 30, 50, 75, 100, 120, 140, 160, 190, 230, 270, 310, 370, 440, 510, 600, 710, 840, 990, 1200, 1400, 1600, 1900, 2200, 2600, 3100, 3700],
};
// Quest-Ziele: [leicht, mittel, schwer] je 4 Stunden bzw. je Woche
const LH_Q = {
	blocks: [[300, 1000, 3000], [10000, 25000, 60000]],
	placed: [[200, 600, 1500], [5000, 15000, 40000]],
	mobs: [[30, 100, 250], [800, 2000, 5000]],
	fish: [[10, 30, 60], [150, 400, 900]],
	play: [[30, 60, 120], [300, 600, 1200]],
	jumps: [[200, 500, 1000], [3000, 8000, 20000]],
	// Erkundung: Strecke (Meter), Minuten im Nether, Minuten unter Höhe 0
	dist: [[1000, 2500, 5000], [20000, 50000, 100000]],
	nether: [[10, 20, 45], [120, 300, 600]],
	deep: [[10, 20, 45], [120, 300, 600]],
};
const LH_Q_POINTS = [[10, 20, 40], [100, 175, 300]];
// Besondere Quests: bestimmte Blöcke abbauen (b), setzen (p) oder bestimmte Mobs besiegen (m) – nur Häufiges, das man
// ohne lange Suche findet. Ziel = Grundziel der Schwierigkeit × Faktor (gerundet).
const LH_QS = {
	b: { base: [[400, 1200, 3000], [8000, 20000, 50000]], ids: { stone: 1, deepslate: 1, netherrack: 1, dirt: 0.5, sand: 0.5,
		gravel: 0.3, end_stone: 0.5, oak_log: 0.2, spruce_log: 0.2, birch_log: 0.2, coal_ore: 0.05, iron_ore: 0.04, clay: 0.1,
		wheat: 0.5, ice: 0.1, obsidian: 0.02 } },
	p: { base: [[200, 600, 1500], [4000, 12000, 30000]], ids: { cobblestone: 1, stone: 1, dirt: 1, oak_planks: 0.6,
		stone_bricks: 0.5, glass: 0.4, sand: 0.5, oak_log: 0.3 } },
	m: { base: [[15, 40, 100], [250, 600, 1500]], ids: { zombie: 1, skeleton: 1, creeper: 0.6, spider: 0.8, cow: 0.6, pig: 0.6,
		sheep: 0.6, chicken: 0.6, enderman: 0.4, drowned: 0.4, slime: 0.5 } },
	// Items aufsammeln (Ernte, Mob-Drops, Fische)
	i: { base: [[200, 600, 1500], [4000, 12000, 30000]], ids: { pumpkin: 0.3, melon_slice: 1, carrot: 1, potato: 1, sugar_cane: 1,
		cactus: 0.5, bamboo: 1, kelp: 1, sweet_berries: 0.5, bone: 0.3, string: 0.3, gunpowder: 0.2, rotten_flesh: 0.5,
		egg: 0.1, apple: 0.05 } }, // keine Fische: Angeln zählt nur als Menge („fish“)
};
// gesetzte Blöcke (p) und aufgesammelte Items (i) je Art: Rohwerte aus der Mod, Zuwachs gekappt auf den Gesamtzähler
const LH_QX = { p: { src: "placed", ids: new Set(Object.keys(LH_QS.p.ids)) }, i: { src: "picked", ids: new Set(Object.keys(LH_QS.i.ids)) } };
function lhQRound(v) { return v >= 100 ? Math.round(v / 10) * 10 : Math.max(1, Math.round(v)); }
/** Aktueller Stand eines Quest-Zählers („blocks“ oder „b:stone“, „p:glass“, „m:zombie“). */
function lhQVal(d, stat) {
	const i = stat.indexOf(":");
	if (i < 0) return (d.tot && d.tot[stat]) || 0;
	const kind = stat.slice(0, i), id = stat.slice(i + 1);
	if (LH_QX[kind]) return (d.qx && d.qx[kind] && d.qx[kind].tot[id]) || 0;
	return (d.col && d.col[kind] && d.col[kind][id]) || 0;
}
/** Startwerte einer Quest-Periode: alle Gesamtzähler, dazu die besonderen Zähler der Quests. */
function lhQBase(d, quests) {
	const base = lhCopy(d.tot || {});
	for (const q of quests) if (q.stat.includes(":")) base[q.stat] = lhQVal(d, q.stat);
	return base;
}
function lhQHave(d, per, stat) {
	if (!(stat in per.base)) per.base[stat] = lhQVal(d, stat); // Periode begann vor der neuen Quest-Art
	return Math.max(0, lhQVal(d, stat) - per.base[stat]);
}
const LH_COMM = { blocks: 500000, mobs: 50000, fish: 5000, placed: 250000 };
const LH_COMM_REWARD = 150;
// Questketten: jede Woche eine kleine Geschichte in drei Schritten (Texte in der Mod), Belohnung je Schritt
const LH_CHAINS = [
	{ id: "treasure", steps: [["blocks", 2000], ["fish", 30], ["mobs", 150]] },
	{ id: "fortress", steps: [["placed", 1500], ["blocks", 1500], ["mobs", 100]] },
	{ id: "storm", steps: [["play", 120], ["placed", 800], ["fish", 25]] },
	{ id: "hunt", steps: [["mobs", 200], ["fish", 40], ["mobs", 300]] },
];
const LH_CHAIN_REWARD = [50, 100, 300];
// Geheimnisse (Easter Eggs): die Mod erkennt sie und meldet sie; „generous“ erkennt der Server (3 Geschenke)
const LH_SECRETS = ["midnight_fish", "jumper", "night_owl", "sky_high", "deep_down", "chatty", "dizzy", "storm_fisher", "lucky", "generous"];
const LH_SECRET_REWARD = 250;
function lhSecret(d, row, id, news) {
	d.secrets = d.secrets || [];
	if (!LH_SECRETS.includes(id) || d.secrets.includes(id)) return false;
	d.secrets.push(id);
	row.points = (row.points || 0) + LH_SECRET_REWARD;
	news.push({ k: "secret", id, p: LH_SECRET_REWARD });
	d.owned = d.owned || [];
	if (d.secrets.length >= LH_SECRETS.length && !d.owned.includes("a_secrets")) { d.owned.push("a_secrets"); news.push({ k: "unlock", id: "a_secrets" }); }
	return true;
}

// Minispiele: tägliches Quiz (Fragen und Antworten stehen in der Mod, hier nur die richtige Antwort) und Block-Raten
const LH_QUIZ = [1, 2, 0, 3, 1, 2, 0, 1, 3, 2, 0, 3, 1, 0, 2, 3, 1, 2, 0, 3];
const LH_QUIZ_REWARD = 30;
const LH_GUESS = ["stone", "cobblestone", "dirt", "sand", "gravel", "oak_log", "birch_log", "spruce_log", "coal_ore", "iron_ore",
	"gold_ore", "diamond_ore", "emerald_ore", "lapis_ore", "redstone_ore", "copper_ore", "obsidian", "netherrack", "glowstone", "end_stone",
	"clay", "deepslate", "tuff", "calcite", "granite", "diorite", "andesite", "bricks", "bookshelf", "ice", "packed_ice", "snow",
	"amethyst_block", "terracotta", "soul_sand", "blackstone", "oak_planks", "mud", "sponge", "honeycomb_block", "moss_block", "dripstone_block"];
const LH_GUESS_REWARD = [40, 25, 15, 5];
// Minispiele mit Rangliste (je Woche). minMs: so lange dauert ein Punkt mindestens (grobe Plausibilität), max: höchstens.
// reward: Taler für Platz 1–3 der Vorwoche (nur Snake).
const LH_MG = {
	snake: { minMs: 300, max: 3000, reward: [300, 200, 100] },
	tower: { minMs: 120, max: 5000 },
	sheep: { minMs: 300, max: 5000 },
	elf: { minMs: 20, max: 200000 },
	memory: { minMs: 400, min: 18, max: 1000, asc: true }, // Züge: weniger ist besser
	craft: { minMs: 2500, max: 300 },
	word: { minMs: 900, min: 15, max: 3600, asc: true }, // Sekunden: weniger ist besser
};
// Fischpreise: Google-Tabelle (veröffentlicht als CSV), Spalten Fisch | Größe | Seltenheit | Preis.
// Wird alle ~9 Minuten neu geholt; eine leere Zelle bei Größe oder Seltenheit gilt für alle.
const FISH_CSV = "https://docs.google.com/spreadsheets/d/e/2PACX-1vQfzmNp1YDBQwgAuKyB5aZhQId8Gxqn7a5MozPZV3OFf_32aIgVWCq5lVk94m_EAvBZMQSZPf8ZBHzt/pub?output=csv";
const FISH_EVERY = 9 * 60 * 1000;

/** CSV-Zeilen lesen (Anführungszeichen, Kommas und Zeilenumbrüche in Feldern). */
function csvRows(text) {
	const rows = [];
	let row = [], cell = "", q = false;
	for (let i = 0; i < text.length; i++) {
		const ch = text[i];
		if (q) {
			if (ch === '"' && text[i + 1] === '"') { cell += '"'; i++; }
			else if (ch === '"') q = false;
			else cell += ch;
		} else if (ch === '"') q = true;
		else if (ch === ",") { row.push(cell); cell = ""; }
		else if (ch === "\n" || ch === "\r") {
			if (ch === "\r" && text[i + 1] === "\n") i++;
			row.push(cell); rows.push(row); row = []; cell = "";
		} else cell += ch;
	}
	if (cell !== "" || row.length) { row.push(cell); rows.push(row); }
	return rows;
}
/** Preis wie „2,50“, „1.234,50“, „2.5“ oder „12 $“ lesen. */
function fishPrice(v) {
	let t = String(v || "").replace(/[^0-9.,-]/g, "");
	if (!t) return NaN;
	if (t.includes(",")) t = t.replace(/\./g, "").replace(",", ".");
	else if ((t.match(/\./g) || []).length > 1) t = t.replace(/\./g, "");
	return Number(t);
}
function fishParse(text) {
	const rows = csvRows(text).map((r) => r.map((c) => c.trim()));
	if (!rows.length) return [];
	// Kopfzeile suchen (darf auch weiter unten stehen, z. B. nach Leerzeilen oder einem Titel)
	const isHead = (r) => r.some((c) => /preis|price|wert/i.test(c)) && r.some((c) => /fisch|fish|name/i.test(c));
	const hi = Math.max(0, rows.findIndex(isHead));
	const head = rows[hi].map((h) => h.toLowerCase());
	const col = (...names) => head.findIndex((h) => names.some((n) => h.includes(n)));
	let iF = col("fisch", "fish", "name"), iS = col("größe", "groesse", "grösse", "size"), iR = col("seltenheit", "rarity"),
		iP = col("preis", "price", "wert", "value");
	let start = hi + 1;
	if (iF < 0 || iP < 0) { iF = 0; iS = 1; iR = 2; iP = 3; start = 0; } // ohne Kopfzeile: feste Reihenfolge
	const out = [];
	for (const r of rows.slice(start)) {
		const name = (r[iF] || "").trim();
		const price = fishPrice(r[iP]);
		if (!name || !Number.isFinite(price) || price < 0) continue; // unlesbare Zeile überspringen
		out.push([name.toLowerCase(), iS >= 0 ? (r[iS] || "").trim().toLowerCase() : "", iR >= 0 ? (r[iR] || "").trim().toLowerCase() : "",
			Math.round(price * 100) / 100]);
		if (out.length >= 5000) break;
	}
	return out;
}
const FISH_RARITIES = new Set(["trödel", "schatz", "häufig", "episch", "legendär", "spektakulär", "sensationell"]);
const FISH_SIZES = new Set(["", "klein", "mittel", "groß", "riesig"]);
/** Neue Fang-Arten eines Spielers in die gemeinsame Liste (je Spieler und Art nur einmal gezählt). */
async function lhFishKnown(db, d, list) {
	if (!Array.isArray(list) || !list.length) return;
	const mine = new Set(Array.isArray(d.fk) ? d.fk : []);
	const add = [];
	for (const e of list.slice(0, 600)) {
		if (!Array.isArray(e) || e.length < 3) continue;
		const fish = String(e[0] || "").trim().slice(0, 40), size = String(e[1] || "").trim().toLowerCase(),
			rarity = String(e[2] || "").trim().toLowerCase();
		if (!fish || !/^[\p{L}\p{N} '\-]+$/u.test(fish) || !FISH_SIZES.has(size) || !FISH_RARITIES.has(rarity)) continue;
		const key = `${fish.toLowerCase()}|${size}|${rarity}`;
		if (mine.has(key)) continue;
		mine.add(key);
		add.push([fish, size, rarity]);
	}
	if (!add.length) return;
	const now = Date.now();
	const stmts = add.slice(0, 80).map(([f, z, r]) => db.prepare(`INSERT INTO lh_fish (fish, size, rarity, n, at) VALUES (?, ?, ?, 1, ?)
		ON CONFLICT(fish, size, rarity) DO UPDATE SET n = n + 1`).bind(f, z, r, now));
	await db.batch(stmts);
	d.fk = [...mine].slice(0, 1500);
}

/** Preisliste aus D1 (und bei Bedarf frisch von Google). */
async function fishRefresh(env, force) {
	if (!env.AH) return null;
	await env.AH.prepare("CREATE TABLE IF NOT EXISTS ah_kv (k TEXT PRIMARY KEY, v TEXT)").run();
	const row = await env.AH.prepare("SELECT v FROM ah_kv WHERE k = 'fish'").first();
	let cur = null;
	try { cur = row ? JSON.parse(row.v) : null; } catch { cur = null; }
	if (!force && cur && Date.now() - (cur.tried || cur.at || 0) < FISH_EVERY) return cur;
	try {
		const res = await fetch(FISH_CSV, { redirect: "follow", cf: { cacheTtl: 60 } });
		if (!res.ok) throw new Error(`HTTP ${res.status}`);
		const list = fishParse(await res.text());
		cur = { at: Date.now(), tried: Date.now(), list };
	} catch (e) {
		cur = { ...(cur || { at: 0, list: [] }), tried: Date.now(), err: String(e && e.message || e).slice(0, 200) };
	}
	await env.AH.prepare("INSERT INTO ah_kv (k, v) VALUES ('fish', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").bind(JSON.stringify(cur)).run();
	return cur;
}

// Duo-Quest: jede Woche ein gemeinsames Ziel für zwei Spieler, die sich gegenseitig eintragen
const LH_DUO = { blocks: 40000, placed: 20000, mobs: 2500, fish: 500, play: 1200, dist: 100000 };
const LH_DUO_REWARD = 200;
function lhDuoQuest(week) {
	const stats = Object.keys(LH_DUO);
	const s = stats[Math.floor(lhRand(`lh-duo-${week}`)() * stats.length)];
	return { stat: s, goal: LH_DUO[s], reward: LH_DUO_REWARD };
}
/** Eigener Beitrag zur Duo-Quest dieser Woche (Zuwachs seit Wochenbeginn). */
function lhDuoMine(d, week) {
	if (!d.week || d.week.id !== week) return 0;
	const q = lhDuoQuest(week);
	return lhQHave(d, d.week, q.stat);
}
/** Duo-Stand neu berechnen (Partner lesen), ggf. Belohnung auszahlen. Ergebnis in d.duoView. */
async function lhDuoUpdate(db, uuid, d, now, out) {
	const week = lhWeekId(now);
	const q = lhDuoQuest(week);
	if (!d.duo || d.duo.week !== week) d.duo = { week, partner: "", pname: "", paid: false };
	// alter Stand (vor dem Zählen ab Annahme): neu beginnen
	if (d.duo.partner && d.duo.v !== 2) { d.duo.paid = false; delete d.duo.pairedAt; }
	d.duo.v = 2;
	const view = { ...q, week, partner: d.duo.pname, paired: false, mine: 0, theirs: 0, paid: d.duo.paid,
		ends: lhWeekEnd(week), invites: [] };
	if (d.duo.partner) {
		const t = await db.prepare("SELECT name, data FROM lh_player WHERE uuid = ?").bind(d.duo.partner).first();
		let td = null;
		try { td = t ? JSON.parse(t.data || "{}") : null; } catch { td = null; }
		if (t) view.partner = t.name || d.duo.pname;
		if (td && td.duo && td.duo.week === week && td.duo.partner === uuid) {
			view.paired = true;
			// gezählt wird erst ab Annahme (beide haben sich eingetragen): Startwerte beider Spieler merken
			if (!d.duo.pairedAt) {
				d.duo.pairedAt = now;
				d.duo.mbase = lhQVal(d, q.stat);
				d.duo.tbase = lhQVal(td, q.stat);
			}
			view.mine = Math.max(0, lhQVal(d, q.stat) - (d.duo.mbase || 0));
			view.theirs = Math.max(0, lhQVal(td, q.stat) - (d.duo.tbase || 0));
		} else if (d.duo.pairedAt) {
			delete d.duo.pairedAt; // Partner hat sich ausgetragen
		}
	}
	// Einladungen: wer mich diese Woche eingetragen hat, ohne dass ich ihn eingetragen habe
	try {
		const r = await db.prepare("SELECT uuid, name, data FROM lh_player WHERE data LIKE ? LIMIT 20").bind(`%"partner":"${uuid}"%`).all();
		for (const x of r.results || []) {
			if (x.uuid === uuid || x.uuid === d.duo.partner) continue;
			let xd = null;
			try { xd = JSON.parse(x.data || "{}"); } catch { xd = null; }
			if (xd && xd.duo && xd.duo.week === week && xd.duo.partner === uuid && x.name) view.invites.push(x.name);
			if (view.invites.length >= 5) break;
		}
	} catch { /* ohne Einladungen */ }
	if (view.paired && !d.duo.paid && view.mine + view.theirs >= q.goal) {
		d.duo.paid = true;
		view.paid = true;
		if (out) {
			out.earned += q.reward;
			out.news.push({ k: "duo", stat: q.stat, p: q.reward });
		}
	}
	d.duoView = view;
	return view;
}

// Serien-Ranglisten (ohne Belohnung): längste Serie richtiger Antworten an aufeinanderfolgenden Tagen
const LH_STREAK_GAMES = ["quiz", "guess"];

/** Serie fortschreiben: richtig am Tag nach dem letzten Treffer → +1, sonst neu ab 1 (falsch → 0). */
function lhStreak(d, game, day, ok) {
	d.streak2 = d.streak2 || {};
	const s = d.streak2[game] || { cur: 0, best: 0, day: -1 };
	if (!ok) s.cur = 0;
	else s.cur = s.day === day - 1 ? s.cur + 1 : 1;
	if (ok) s.day = day;
	s.best = Math.max(s.best, s.cur);
	d.streak2[game] = s;
	return s;
}
function lhStreakView(d, game, day) {
	const s = (d.streak2 && d.streak2[game]) || { cur: 0, best: 0, day: -1 };
	// gestern und heute nicht richtig → Serie ist gerissen
	return { cur: s.day >= day - 1 ? s.cur : 0, best: s.best };
}
async function lhMgStore(db, game, week, uuid, name, score, now) {
	const asc = !!(LH_MG[game] && LH_MG[game].asc);
	await db.prepare(`INSERT INTO lh_mg (game, week, uuid, name, score, at) VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(game, week, uuid) DO UPDATE SET name = excluded.name,
		at = CASE WHEN excluded.score ${asc ? "<" : ">"} score THEN excluded.at ELSE at END,
		score = ${asc ? "MIN" : "MAX"}(score, excluded.score)`).bind(game, week, uuid, name, score, now).run();
}
/** Belohnung der Vorwoche (Platz 1–3) einmal auszahlen. */
async function lhMgPayout(db, uuid, d, now, out) {
	const prev = lhWeekId(now) - 1;
	d.mgpaid = d.mgpaid || {};
	for (const [game, g] of Object.entries(LH_MG)) {
		if (!g.reward || d.mgpaid[game] >= prev) continue;
		d.mgpaid[game] = prev;
		const top = (await db.prepare("SELECT uuid FROM lh_mg WHERE game = ? AND week = ? ORDER BY score DESC, at ASC LIMIT 3")
			.bind(game, prev).all()).results || [];
		const rank = top.findIndex((x) => x.uuid === uuid);
		if (rank >= 0) {
			out.earned += g.reward[rank];
			out.news.push({ k: "mg", g: game, r: rank + 1, p: g.reward[rank] });
		}
	}
}
function lhGuessDay(day) {
	const r = lhRand(`guess-${day}`);
	const answer = LH_GUESS[Math.floor(r() * LH_GUESS.length) % LH_GUESS.length];
	const opts = [answer];
	while (opts.length < 4) { const o = LH_GUESS[Math.floor(r() * LH_GUESS.length) % LH_GUESS.length]; if (!opts.includes(o)) opts.push(o); }
	for (let i = opts.length - 1; i > 0; i--) { const j = Math.floor(r() * (i + 1)); [opts[i], opts[j]] = [opts[j], opts[i]]; }
	return { answer, opts };
}
function lhQuizDay(day) { return Math.floor(lhRand(`quiz-${day}`)() * LH_QUIZ.length) % LH_QUIZ.length; }

function lhChain(week) { return LH_CHAINS[Math.floor(lhRand(`chain-${week}`)() * LH_CHAINS.length) % LH_CHAINS.length]; }
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
	// zweite Welle: von allem etwas
	["t_stormborn", "title", 3000], ["t_shadow", "title", 3000], ["t_deep", "title", 4000], ["t_dragonslayer", "title", 8000],
	["t_archmaster", "title", 12000], ["t_emperor", "title", 50000],
	["b_leaf", "badge", 800], ["b_fire", "badge", 800], ["b_potion", "badge", 1200], ["b_shield", "badge", 1600],
	["b_emerald", "badge", 2400], ["b_trident", "badge", 6000],
	["c_cherry", "steps", 3000], ["c_glow", "steps", 4000], ["c_totem", "steps", 8000], ["c_rainbow", "steps", 14000],
	["k_flower", "crown", 4000], ["k_ice", "crown", 6000], ["k_nether", "crown", 9000], ["k_halo", "crown", 18000],
	["e_rainbow", "eyes", 8000],
	["p_phoenix", "pet", 20000], ["p_golddragon", "pet", 25000], ["p_starbear", "pet", 5000],
	// Geheimniskrämer: alle Geheimnisse gefunden
	["a_secrets", "title", 0, { secrets: true }],
	// Titelfarben (statt Gold)
	["tc_emerald", "tcolor", 3000], ["tc_ice", "tcolor", 3000], ["tc_fire", "tcolor", 4000], ["tc_violet", "tcolor", 4000],
	["tc_rainbow", "tcolor", 12000],
	// Winter-Saison (1. Dezember bis 6. Januar)
	["t_snowking", "title", 4000, { season: "winter" }], ["c_candy", "steps", 5000, { season: "winter" }],
	["k_holly", "crown", 6000, { season: "winter" }], ["p_xmaspenguin", "pet", 15000, { season: "winter" }],
	// Ostern (20. März bis 20. April)
	["t_eggseeker", "title", 3000, { season: "easter" }], ["c_eggs", "steps", 4000, { season: "easter" }],
	["k_easter", "crown", 6000, { season: "easter" }], ["p_chick", "pet", 12000, { season: "easter" }],
	// Sommer (21. Juni bis 31. August)
	["t_sunseeker", "title", 3000, { season: "summer" }], ["c_bubbles", "steps", 4000, { season: "summer" }],
	["k_shell", "crown", 6000, { season: "summer" }], ["p_parrot", "pet", 12000, { season: "summer" }],
	// Halloween (15. Oktober bis 2. November)
	["t_pumpkinking", "title", 4000, { season: "halloween" }], ["c_spooky", "steps", 5000, { season: "halloween" }],
	["k_pumpkin", "crown", 6000, { season: "halloween" }], ["p_bat", "pet", 15000, { season: "halloween" }],
	// Ruhmestitel: nicht kaufbar, bei „Legendär“ (letzte Stufe) einer Heldentat
	...["blocks", "placed", "picked", "mobs", "fish", "play", "treasures", "jobmoney", "jumps", "dquests", "wquests", "login"]
		.map((st) => [`a_${st}`, "title", 0, { ach: st }]),
	// Sammel-Abzeichen: nicht kaufbar
	["b_ores", "badge", 0, { col: "ores" }], ["b_hunter", "badge", 0, { col: "hunter" }],
];
const LH_SHOP_MAP = Object.fromEntries(LH_SHOP.map(([id, kind, cost, extra]) => [id, { kind, cost, extra: extra || {} }]));
// Saisons: [Monat, Tag] von – bis (deutsche Zeit, über den Jahreswechsel möglich)
const LH_SEASONS = { easter: [[3, 20], [4, 20]], summer: [[6, 21], [8, 31]], halloween: [[10, 15], [11, 2]], winter: [[12, 1], [1, 6]] };
const LH_OFFER_OFF = 0.25; // Angebot des Tages: 25 % günstiger

function lhSeasonOn(season, now) {
	const t = new Date(now + lhTz(now));
	const md = (t.getUTCMonth() + 1) * 100 + t.getUTCDate();
	const [[m1, d1], [m2, d2]] = LH_SEASONS[season] || [[0, 0], [0, 0]];
	const a = m1 * 100 + d1, b = m2 * 100 + d2;
	return a <= b ? md >= a && md <= b : md >= a || md <= b;
}

/** Angebot des Tages: ein kaufbarer Artikel (kein Schutz, keine Saison, keine Belohnung), für alle gleich. */
function lhOffer(now) {
	const pool = LH_SHOP.filter(([, kind, cost, extra]) => kind !== "freeze" && cost > 0 && !extra);
	const [id, , cost] = pool[Math.floor(lhRand(`offer-${lhDayId(now)}`)() * pool.length) % pool.length];
	return { id, cost: Math.round((cost * (1 - LH_OFFER_OFF)) / 10) * 10 };
}

/** Preis jetzt (mit Angebot des Tages) oder null, wenn gerade nicht kaufbar. */
function lhPrice(id, now) {
	const it = LH_SHOP_MAP[id];
	if (!it || it.extra.ach || it.extra.col || it.extra.secrets) return null;
	if (it.extra.season && !lhSeasonOn(it.extra.season, now)) return null;
	const o = lhOffer(now);
	return o.id === id ? o.cost : it.cost;
}

// Sammel-Abzeichen: alle Erze (normal und Tiefenschiefer zusammen) mindestens 50-mal, alle Monster mindestens 5-mal
const LH_ORES = ["coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald"];
const LH_HOSTILE = ["blaze", "cave_spider", "creeper", "drowned", "enderman", "ghast", "guardian", "hoglin", "husk", "magma_cube",
	"phantom", "piglin", "pillager", "shulker", "silverfish", "skeleton", "slime", "spider", "stray", "witch", "wither_skeleton",
	"zombie", "zombie_villager", "zombified_piglin"];
function lhColBadge(kind, d) {
	const b = (d.col && d.col.b) || {}, m = (d.col && d.col.m) || {};
	if (kind === "ores") {
		return LH_ORES.every((o) => (b[`${o}_ore`] || 0) + (b[`deepslate_${o}_ore`] || 0) >= 50)
			&& (b.nether_gold_ore || 0) >= 50 && (b.nether_quartz_ore || 0) >= 50 && (b.ancient_debris || 0) >= 50;
	}
	if (kind === "hunter") return LH_HOSTILE.every((x) => (m[x] || 0) >= 5);
	return false;
}

/** Fortschritt der Sammel-Abzeichen für die Anzeige: [geschafft, gesamt]. */
function lhColBadgeProgress(kind, d) {
	const b = (d.col && d.col.b) || {}, m = (d.col && d.col.m) || {};
	if (kind === "ores") {
		const all = [...LH_ORES.map((o) => (b[`${o}_ore`] || 0) + (b[`deepslate_${o}_ore`] || 0)),
			b.nether_gold_ore || 0, b.nether_quartz_ore || 0, b.ancient_debris || 0];
		return [all.filter((v) => v >= 50).length, all.length];
	}
	return [LH_HOSTILE.filter((x) => (m[x] || 0) >= 5).length, LH_HOSTILE.length];
}

// Sammlung: Blöcke und Mobs, die nicht (mehr) dazugehören – gleiche Regeln wie in der Mod
const LH_WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "crimson", "warped", "bamboo", "azalea"];
const LH_NO_COLLECT = new Set(["farmland", "dirt_path", "pitcher_pod", "crafting_table", "smithing_table", "fletching_table",
	"cartography_table", "loom", "stonecutter", "grindstone", "enchanting_table", "anvil", "chipped_anvil", "damaged_anvil",
	"brewing_stand", "furnace", "blast_furnace", "smoker", "composter", "barrel", "lectern", "cauldron", "crafter", "observer",
	"piston", "sticky_piston", "dispenser", "dropper", "hopper", "comparator", "repeater", "redstone", "redstone_torch",
	"redstone_lamp", "daylight_detector", "target", "lever", "tripwire_hook", "string", "note_block", "rail", "powered_rail",
	"detector_rail", "activator_rail", "trapped_chest", "short_grass", "tall_grass", "fern", "large_fern", "seagrass", "dandelion",
	"poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "sunflower",
	"lilac", "rose_bush", "peony", "torchflower", "pitcher_plant", "pink_petals", "spore_blossom", "closed_eyeblossom",
	"open_eyeblossom", "wildflowers", "cactus_flower", "chorus_flower", "mangrove_propagule", "flower_pot", "mossy_cobblestone",
	"deepslate_tiles", "cracked_deepslate_tiles", "azalea", "flowering_azalea"]);
const LH_NO_MOBS = new Set(["wither", "illusioner", "giant", "copper_golem", "wandering_trader", "ender_dragon", "happy_ghast",
	"phantom", "trader_llama"]);
function lhColExcluded(kind, id) {
	if (kind === "m") return LH_NO_MOBS.has(id);
	if (LH_NO_COLLECT.has(id) || /(_seeds|_stairs|_slab|_wall|_sapling|_leaves|_wool|_carpet|_tulip)$/.test(id) || id.includes("brick")
		|| id.includes("glass") || /^(infested_|polished_|chiseled_|cracked_|smooth_|cut_|stripped_)/.test(id)) return true;
	if (/(_bed|_head|_skull|_banner|campfire|_candle|chest|shulker_box|_bucket|_chain)$/.test(id) || (id.includes("lantern") && id !== "sea_lantern")
		|| ["bell", "candle", "resin_clump", "chain", "lightning_rod"].includes(id) || id.includes("glazed_terracotta")) return true;
	if (["iron_block", "gold_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block", "coal_block", "netherite_block",
		"quartz_block", "resin_block", "respawn_anchor", "beacon", "beehive", "bookshelf", "cake", "sculk_sensor", "calibrated_sculk_sensor",
		"carved_pumpkin", "conduit"].includes(id) || /^raw_.*_block$/.test(id)
		|| /(_froglight|_concrete|_concrete_powder)$/.test(id)) return true;
	if (["slime_block", "brown_mushroom", "red_mushroom", "bamboo_block", "heavy_core", "lodestone"].includes(id)) return true;
	if (id.startsWith("dead_") || ["decorated_pot", "dried_kelp_block", "end_rod", "frogspawn", "bush", "honeycomb_block"].includes(id)
		|| /(_bush|_roots|_pressure_plate)$/.test(id)) return true;
	if (/(_door|_trapdoor|_bars|_sprouts)$/.test(id) || id.includes("pillar")
		|| ["leaf_litter", "ladder", "packed_mud", "pale_hanging_moss", "pointed_dripstone", "scaffolding", "rooted_dirt"].includes(id)
		|| (id.includes("amethyst") && id !== "amethyst_block" && id !== "budding_amethyst")) return true;
	if (id.includes("sculk") || id.includes("grass") || id === "sea_pickle" || id === "jukebox") return true;
	if (id.includes("dripleaf") || id.includes("torch") || id.includes("vine") || id.includes("lightning_rod") || /(sponge|_egg|_button)$/.test(id)
		|| id.startsWith("suspicious_") || ["snow", "tnt", "vault", "trial_spawner"].includes(id)) return true;
	if (id.includes("copper") && !id.endsWith("copper_ore")) return true;
	if (id.includes("deepslate") && id !== "deepslate" && id !== "cobbled_deepslate" && !id.endsWith("_ore")) return true;
	return LH_WOODS.some((w) => id.startsWith(`${w}_`) && !/(_log|_stem|_nylium)$/.test(id));
}
// Cosmetics aus dem Shop: Eintrag in den Cosmetics → Wert → nötiger Shop-Artikel (Haustiere je Platz, durch Kommas getrennt)
const LH_PETS_SHOP = { phoenix: "p_phoenix", golddragon: "p_golddragon", xmaspenguin: "p_xmaspenguin", chick: "p_chick", bat: "p_bat",
	parrot: "p_parrot", starbear: "p_starbear" };
const LH_COSM_ITEMS = {
	footsteps: { hearts: "c_hearts", notes: "c_notes", snow: "c_snow", magic: "c_magic", soul: "c_soul", stars: "c_stars",
		cherry: "c_cherry", glow: "c_glow", totem: "c_totem", rainbow: "c_rainbow", candy: "c_candy",
		eggs: "c_eggs", bubbles: "c_bubbles", spooky: "c_spooky" },
	crownstyle: { flower: "k_flower", ice: "k_ice", nether: "k_nether", halo: "k_halo", holly: "k_holly", easter: "k_easter",
		shell: "k_shell", pumpkin: "k_pumpkin" },
	eyes: { rainbow: "e_rainbow" },
	pets: LH_PETS_SHOP,
	headpets: LH_PETS_SHOP,
};
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
		db.prepare(`CREATE TABLE IF NOT EXISTS lh_fish (fish TEXT, size TEXT, rarity TEXT, n INTEGER DEFAULT 0, at INTEGER DEFAULT 0,
			PRIMARY KEY (fish, size, rarity))`),
		db.prepare(`CREATE TABLE IF NOT EXISTS lh_mg (game TEXT, week INTEGER, uuid TEXT, name TEXT, score INTEGER DEFAULT 0,
			at INTEGER DEFAULT 0, PRIMARY KEY (game, week, uuid))`),
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
	if (!have.has("tcolor")) {
		try { await db.prepare("ALTER TABLE lh_player ADD COLUMN tcolor TEXT DEFAULT ''").run(); } catch { /* vorhanden */ }
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
// Quest-Arten, die ältere Mod-Versionen (ohne „mv“ im Abgleich) noch nicht anzeigen können
const LH_Q_NEW = new Set(["dist", "nether", "deep"]);
function lhQuests(kind, id, legacy) {
	const rnd = lhRand(`lh-${kind}-${id}`);
	const stats = Object.keys(LH_Q).filter((s) => LH_Q[s][kind] && !(legacy && LH_Q_NEW.has(s)));
	const out = [];
	if (legacy) {
		// genau wie früher: nur allgemeine Zähler
		for (let d = 0; d < 3; d++) {
			let s;
			do { s = stats[Math.floor(rnd() * stats.length)]; } while (out.some((q) => q.stat === s));
			out.push({ stat: s, goal: LH_Q[s][kind][d], points: LH_Q_POINTS[kind][d], diff: d });
		}
		return out;
	}
	for (let d = 0; d < 3; d++) {
		// etwa jede dritte Quest ist eine besondere (bestimmter Block/Mob)
		if (rnd() < 0.35) {
			const kinds = Object.keys(LH_QS);
			const k = kinds[Math.floor(rnd() * kinds.length)];
			const ids = Object.keys(LH_QS[k].ids);
			const id = ids[Math.floor(rnd() * ids.length)];
			const stat = `${k}:${id}`;
			if (!out.some((q) => q.stat === stat)) {
				out.push({ stat, goal: lhQRound(LH_QS[k].base[kind][d] * LH_QS[k].ids[id]), points: LH_Q_POINTS[kind][d], diff: d });
				continue;
			}
		}
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

function lhCatalog(now = Date.now()) {
	const seasons = {};
	for (const k of Object.keys(LH_SEASONS)) seasons[k] = { on: lhSeasonOn(k, now), until: LH_SEASONS[k][1] };
	return { ach: LH_ACH, tierPoints: LH_TIER_POINTS, colTiers: LH_COL_TIERS, colPoints: LH_COL_POINTS, shop: LH_SHOP, boards: LH_BOARDS,
		commReward: LH_COMM_REWARD, secrets: LH_SECRETS, secretReward: LH_SECRET_REWARD, seasons, offer: lhOffer(now), offerOff: LH_OFFER_OFF };
}

function lhTier(stat, v) {
	const t = LH_ACH[stat];
	let n = 0;
	while (n < t.length && v >= t[n]) n++;
	return n;
}

// Sammlung: je abbaubarem Block (b) und je Mob (m) eigene Erfolge mit 15 Stufen und kleineren Belohnungen
const LH_COL_TIERS = {
	b: [50, 100, 250, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000, 250000, 500000, 1000000, 2500000],
	m: [5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000, 250000],
};
const LH_COL_POINTS = [1, 1, 2, 2, 3, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25];
const LH_COL_SRC = { b: "blocks", m: "mobs" }; // Zuwachs je Block/Mob zusammen höchstens so viel wie der Gesamtzähler
const LH_COL_MAX = 1500; // höchstens so viele verschiedene Blöcke bzw. Mobs je Spieler
const LH_COL_ID = /^[a-z0-9_]{1,48}$/;

function lhColTier(kind, v) {
	const t = LH_COL_TIERS[kind];
	let n = 0;
	while (n < t.length && v >= t[n]) n++;
	return n;
}

/** Zähler je Block und je Mob übernehmen (nach lhAbsorb). Der Zuwachs wird auf den Zuwachs des Gesamtzählers gekappt. */
function lhAbsorbCol(d, reported) {
	const rep = reported && typeof reported === "object" ? reported : {};
	// gesetzte Blöcke (p) und aufgesammelte Items (i) je Art – nur die für besondere Quests
	delete d.cpl; delete d.cplraw; // alte Felder
	d.qx = d.qx || {};
	for (const [kind, x] of Object.entries(LH_QX)) {
		const src = rep[kind];
		if (!src || typeof src !== "object") continue;
		const q = d.qx[kind] || (d.qx[kind] = { raw: null, tot: {} });
		const first = !q.raw;
		q.raw = q.raw || {};
		const deltas = {};
		let sum = 0;
		for (const id of x.ids) {
			let v = Number(src[id]);
			if (!Number.isFinite(v) || v < 0) continue;
			v = Math.floor(v);
			const prev = q.raw[id];
			q.raw[id] = v;
			if (first) continue; // alte Statistik zählt für Quests nicht
			// bisher nie gemeldet = bisher 0 (die Mod meldet nur Arten mit Zähler > 0) – sonst ginge der erste Schwung
			// einer neuen Blockart verloren (z. B. Steinziegel erst für die Quest gesetzt)
			const delta = prev === undefined ? v : v >= prev ? v - prev : v;
			if (delta > 0) { deltas[id] = delta; sum += delta; }
		}
		const budget = Math.ceil(((d.delta && d.delta[x.src]) || 0) * 1.1) + 20;
		const f = sum > budget ? budget / sum : 1;
		for (const [id, delta] of Object.entries(deltas)) q.tot[id] = (q.tot[id] || 0) + Math.floor(delta * f);
	}
	d.colFirst = false;
	if (!rep.b && !rep.m) return; // ältere Mod-Version ohne Sammlung: nichts anlegen, damit später die Historie zählt
	// erster Abgleich der Sammlung (auch bei Spielern, die schon vorher dabei waren): alte Statistik übernehmen
	const first = d.colFirst = !d.craw;
	d.craw = d.craw || { b: {}, m: {} };
	d.col = d.col || { b: {}, m: {} };
	for (const kind of ["b", "m"]) {
		const src = rep[kind] && typeof rep[kind] === "object" ? rep[kind] : {};
		const raw = d.craw[kind] || (d.craw[kind] = {});
		const col = d.col[kind] || (d.col[kind] = {});
		const deltas = {};
		let sum = 0, known = Object.keys(col).length;
		// Aufräumen: Blöcke/Mobs, die nicht mehr zur Sammlung gehören
		for (const id of Object.keys(col)) if (lhColExcluded(kind, id)) delete col[id];
		for (const id of Object.keys(raw)) if (lhColExcluded(kind, id)) delete raw[id];
		if (d.cach && d.cach[kind]) for (const id of Object.keys(d.cach[kind])) if (lhColExcluded(kind, id)) delete d.cach[kind][id];
		for (const [id, x] of Object.entries(src)) {
			if (!LH_COL_ID.test(id) || lhColExcluded(kind, id)) continue;
			let v = Number(x);
			if (!Number.isFinite(v) || v < 0) continue;
			v = Math.floor(v);
			if (!(id in col)) {
				if (known >= LH_COL_MAX) continue;
				known++;
			}
			const prev = raw[id];
			const delta = first || prev === undefined ? v : v >= prev ? v - prev : v;
			raw[id] = v;
			if (delta > 0) { deltas[id] = delta; sum += delta; }
		}
		const budget = first ? LH_FIRST_MAX[LH_COL_SRC[kind]] : Math.ceil((d.delta[LH_COL_SRC[kind]] || 0) * 1.1) + 20;
		const f = sum > budget ? budget / sum : 1;
		for (const [id, delta] of Object.entries(deltas)) col[id] = (col[id] || 0) + Math.floor(delta * f);
	}
}

/** Gemeldete Summen übernehmen: Zuwachs je Zähler, gekappt; nach einem Zurücksetzen der Statistik zählt der neue Wert. */
function lhAbsorb(d, reported, now) {
	d.raw = d.raw || {};
	d.tot = d.tot || {};
	// Aufräumen: Werte, die nicht mehr gezählt werden (Essen, Strecke, Itemdex)
	for (const o of [d.raw, d.tot, d.off || {}]) for (const k of Object.keys(o)) if (!LH_STATS.includes(k) && !LH_BOARDS.includes(k)) delete o[k];
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
		if (first) delta = Math.min(v, LH_FIRST_MAX[s]);
		else if (prev === undefined) delta = 0; // neu eingeführter Zähler: alte Statistik zählt nicht (sonst springen Quests)
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
	for (const s of Object.keys(d.ach)) if (!(s in LH_ACH)) delete d.ach[s]; // entfernte Erfolge (z. B. Essen) nicht mehr mitzählen
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
	if (!d.slot || d.slot.id !== slot) d.slot = { id: slot, base: lhQBase(d, lhQuests(0, slot, d.legacy)), done: [false, false, false] };
	const today = lhDayId(now);
	lhQuests(0, slot, d.legacy).forEach((q, i) => {
		if (d.slot.done[i]) return;
		if (lhQHave(d, d.slot, q.stat) >= q.goal) {
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
	if (!d.week) d.week = { id: week, base: lhQBase(d, lhQuests(1, week, d.legacy)), done: [false, false, false], comm: 0, paid: false };
	lhQuests(1, week, d.legacy).forEach((q, i) => {
		if (d.week.done[i]) return;
		if (lhQHave(d, d.week, q.stat) >= q.goal) {
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

	// Sammlung (Blöcke und Mobs): kleine Belohnungen, bei vielen Stufen auf einmal nur eine Sammelmeldung
	d.cach = d.cach || { b: {}, m: {} };
	const colNews = [];
	let colSum = 0;
	for (const kind of ["b", "m"]) {
		const got = d.cach[kind] || (d.cach[kind] = {});
		for (const [id, v] of Object.entries((d.col && d.col[kind]) || {})) {
			const t = lhColTier(kind, v), had = got[id] || 0;
			for (let k = had; k < t; k++) {
				const full = LH_COL_POINTS[k];
				const p = d.colFirst ? Math.round(full * LH_HISTORY_SHARE) : full;
				colSum += p;
				colNews.push({ k: "col", c: kind, id, tier: k + 1, p });
			}
			if (t > had) got[id] = t;
		}
	}
	earned += colSum;
	if (colNews.length > 3) news.push({ k: "col_many", n: colNews.length, p: colSum });
	else news.push(...colNews);
	delete d.colFirst;

	// Questkette der Woche: Schritt für Schritt, gezählt ab Beginn des jeweiligen Schritts
	{
		const wk = lhWeekId(now), ch = lhChain(wk);
		if (!d.chain || d.chain.week !== wk) d.chain = { week: wk, step: 0, base: tot[ch.steps[0][0]] || 0 };
		while (d.chain.step < ch.steps.length) {
			const [st, goal] = ch.steps[d.chain.step];
			if ((tot[st] || 0) - d.chain.base < goal) break;
			const p = LH_CHAIN_REWARD[d.chain.step];
			earned += p;
			d.chain.step++;
			news.push({ k: "chain", id: ch.id, step: d.chain.step, of: ch.steps.length, p });
			if (d.chain.step < ch.steps.length) d.chain.base = tot[ch.steps[d.chain.step][0]] || 0;
		}
	}

	// Geschenke von anderen Spielern
	if (d.inbox && d.inbox.length) { news.push(...d.inbox); d.inbox = []; }

	// Freischaltungen: Ruhmestitel bei „Legendär“, Sammel-Abzeichen
	d.owned = d.owned || [];
	for (const [id, , , extra] of LH_SHOP) {
		if (!extra || d.owned.includes(id)) continue;
		const ok = extra.ach ? (d.ach[extra.ach] || 0) >= LH_ACH[extra.ach].length : extra.col ? lhColBadge(extra.col, d)
			: extra.secrets ? (d.secrets || []).length >= LH_SECRETS.length : false;
		if (ok) { d.owned.push(id); news.push({ k: "unlock", id }); }
	}
	return { earned, news, commSum, cq };
}

function lhView(row, d, extra, now) {
	const slot = lhSlotId(now), week = lhWeekId(now);
	const q4 = lhQuests(0, slot, d.legacy).map((q, i) => ({ ...q, have: lhQHave(d, d.slot, q.stat), done: !!d.slot.done[i] }));
	const qw = lhQuests(1, week, d.legacy).map((q, i) => ({ ...q, have: lhQHave(d, d.week, q.stat), done: !!d.week.done[i] }));
	return {
		now, points: row.points, balance: row.points - row.spent, title: row.title, badge: row.badge, tcolor: row.tcolor || "",
		tot: lhCopy(d.tot), ach: d.ach, quests: d.quests,
		col: { b: (d.col && d.col.b) || {}, m: (d.col && d.col.m) || {} },
		cach: { b: (d.cach && d.cach.b) || {}, m: (d.cach && d.cach.m) || {} },
		colBadges: { ores: lhColBadgeProgress("ores", d), hunter: lhColBadgeProgress("hunter", d) },
		secrets: d.secrets || [],
		quiz: (() => {
			const day = lhDayId(now), q = lhQuizDay(day), done = !!(d.quiz && d.quiz.day === day);
			return { q, done, a: done ? d.quiz.a : -1, ok: done ? d.quiz.ok : false, right: done ? LH_QUIZ[q] : -1, reward: LH_QUIZ_REWARD,
				streak: lhStreakView(d, "quiz", day) };
		})(),
		duo: d.duoView && d.duoView.week === lhWeekId(now) ? d.duoView : null,
		guess: (() => {
			const day = lhDayId(now), g = lhGuessDay(day), st = d.guess && d.guess.day === day ? d.guess : { picked: [], done: false };
			return { opts: g.opts, picked: st.picked, done: st.done, answer: st.done ? g.answer : "", hint: g.answer, rewards: LH_GUESS_REWARD,
				streak: lhStreakView(d, "guess", day) };
		})(),
		chain: (() => {
			const ch = lhChain(week), c = d.chain && d.chain.week === week ? d.chain : { step: 0, base: d.tot[ch.steps[0][0]] || 0 };
			const cur = ch.steps[Math.min(c.step, ch.steps.length - 1)];
			return { id: ch.id, step: c.step, steps: ch.steps.map(([st, g], i) => [st, g, LH_CHAIN_REWARD[i]]),
				have: c.step < ch.steps.length ? Math.max(0, (d.tot[cur[0]] || 0) - c.base) : 0 };
		})(),
		dquests: d.dq || 0, wquests: d.wq || 0, jobday: Math.floor((d.jd && d.jd.best) || 0),
		login: { cur: (d.login || {}).cur || 0, best: (d.login || {}).best || 0, freeze: d.freeze || 0 },
		slot: { ends: lhSlotEnd(slot), list: q4 },
		week: { ends: lhWeekEnd(week), list: qw },
		comm: extra && extra.cq ? { stat: extra.cq.stat, goal: extra.cq.goal, sum: extra.commSum, mine: d.week.comm || 0,
			need: Math.ceil(extra.cq.goal / 200), paid: !!d.week.paid, reward: LH_COMM_REWARD } : null,
		owned: d.owned || [], news: extra ? extra.news : [], cat: lhCatalog(now),
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
		title: row.title || "", badge: row.badge || "", tcolor: row.tcolor || "", quests: d.quests || 0, jobmoney: tot.jobmoney || 0, updated: now,
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
		typeof b.payload === "string" && b.payload.length < 120000 ? // mit Sammlung (Zähler je Block und Mob) deutlich größer
		 `loothub-lh\n${b.uuid}\n${b.ts}\n${b.payload}` : null);
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
		d.legacy = !p.mv; // ältere Mod ohne Feature-Stand: nur Quests, die sie anzeigen kann
		// gefangene Fisch-Arten (Fisch|Größe|Seltenheit) für die gemeinsame Liste aller bekannten Fänge
		try { await lhFishKnown(db, d, p.fk); } catch { /* nächstes Mal */ }
		if (!p.stats || typeof p.stats !== "object") return json({ error: "bad payload" }, 400);
		lhAbsorb(d, p.stats, now);
		lhAbsorbCol(d, p.col);
		extra = await lhProgress(db, d, now);
		try { await lhMgPayout(db, uuid, d, now, extra); } catch { /* nächstes Mal */ }
		try { await lhDuoUpdate(db, uuid, d, now, extra); } catch { /* nächstes Mal */ }
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
			else {
				const price = lhPrice(p.item, now);
				if (price === null) error = item.extra.season ? "season" : "locked";
				else if (row.points - row.spent < price) error = "poor";
				else { d.owned.push(p.item); row.spent += price; }
			}
		} else if (p.op === "gift") {
			// Geschenk: der Schenkende zahlt, der Beschenkte bekommt den Artikel beim nächsten Abgleich gemeldet
			const item = LH_SHOP_MAP[p.item];
			const price = item ? (item.kind === "freeze" ? item.cost : lhPrice(p.item, now)) : null;
			if (!item) error = "unknown";
			else if (price === null) error = item.extra.season ? "season" : "locked";
			else if (typeof p.target !== "string" || !NAME.test(p.target)) error = "no player";
			else {
				const t = await db.prepare("SELECT uuid, data FROM lh_player WHERE lower(name) = lower(?) ORDER BY updated DESC LIMIT 1").bind(p.target).first();
				let td = null;
				try { td = t ? JSON.parse(t.data || "{}") : null; } catch { td = null; }
				if (!t || !td) error = "no player";
				else if (t.uuid === uuid) error = "self";
				else if (item.kind !== "freeze" && (td.owned || []).includes(p.item)) error = "has it";
				else if (row.points - row.spent < price) error = "poor";
				else {
					if (item.kind === "freeze") td.freeze = (td.freeze || 0) + 1;
					else (td.owned = td.owned || []).push(p.item);
					td.inbox = [...(td.inbox || []), { k: "gift", id: p.item, from: name || row.name || "?" }].slice(-20);
					await db.prepare("UPDATE lh_player SET data = ? WHERE uuid = ?").bind(JSON.stringify(td), t.uuid).run();
					row.spent += price;
					extra = { news: [{ k: "gift_sent", id: p.item, to: p.target }] };
					d.gifts = (d.gifts || 0) + 1;
					if (d.gifts >= 3) lhSecret(d, row, "generous", extra.news);
				}
			}
		} else if (p.op === "secret") {
			extra = { news: [] };
			if (!lhSecret(d, row, String(p.id || ""), extra.news)) error = "known";
		} else if (p.op === "quiz") {
			const day = lhDayId(now), q = lhQuizDay(day), a = Math.trunc(Number(p.a));
			extra = { news: [] };
			if (d.quiz && d.quiz.day === day) error = "done";
			else if (!(a >= 0 && a < 4)) error = "bad";
			else {
				const ok = a === LH_QUIZ[q];
				d.quiz = { day, a, ok };
				const st = lhStreak(d, "quiz", day, ok);
				await lhMgStore(db, "quiz", 0, uuid, name || row.name || "", st.best, now);
				if (ok) { row.points = (row.points || 0) + LH_QUIZ_REWARD; extra.news.push({ k: "quiz", p: LH_QUIZ_REWARD }); }
			}
		} else if (p.op === "guess") {
			const day = lhDayId(now), g = lhGuessDay(day), c = String(p.c || "");
			extra = { news: [] };
			if (!d.guess || d.guess.day !== day) d.guess = { day, picked: [], done: false };
			if (d.guess.done) error = "done";
			else if (!g.opts.includes(c) || d.guess.picked.includes(c)) error = "bad";
			else {
				d.guess.picked.push(c);
				// Serie: nur ein Treffer beim ersten Versuch zählt
				if (d.guess.picked.length === 1) {
					const st = lhStreak(d, "guess", day, c === g.answer);
					await lhMgStore(db, "guess", 0, uuid, name || row.name || "", st.best, now);
				}
				if (c === g.answer) {
					const p2 = LH_GUESS_REWARD[Math.min(d.guess.picked.length - 1, LH_GUESS_REWARD.length - 1)];
					d.guess.done = true; d.guess.ok = true;
					row.points = (row.points || 0) + p2;
					extra.news.push({ k: "guess", p: p2 });
				}
			}
		} else if (p.op === "duo") {
			// Partner eintragen (oder mit leerem Namen austragen); zählt, sobald er einen auch einträgt
			const week = lhWeekId(now);
			const target = String(p.target || "");
			extra = { news: [], earned: 0 };
			if (d.duo && d.duo.week === week && d.duo.paid) error = "duo done";
			else if (!target) d.duo = { week, partner: "", pname: "", paid: !!(d.duo && d.duo.week === week && d.duo.paid), v: 2 };
			else if (!NAME.test(target)) error = "no player";
			else {
				const t = await db.prepare("SELECT uuid, name FROM lh_player WHERE lower(name) = lower(?) ORDER BY updated DESC LIMIT 1").bind(target).first();
				if (!t) error = "no player";
				else if (t.uuid === uuid) error = "duo self";
				else if (!d.duo || d.duo.week !== week || d.duo.partner !== t.uuid) d.duo = { week, partner: t.uuid, pname: t.name, paid: false, v: 2 };
			}
			if (!error) {
				await lhDuoUpdate(db, uuid, d, now, extra);
				row.points = (row.points || 0) + extra.earned;
			}
		} else if (p.op === "mg") {
			const game = String(p.game || ""), g = LH_MG[game];
			const score = Math.trunc(Number(p.score)), ms = Math.trunc(Number(p.ms));
			if (!g) error = "unknown";
			else if (!(score > 0 && score >= (g.min || 1) && score <= g.max && ms > 0 && ms >= score * g.minMs)) error = "bad";
			else if (d.mgAt && now - d.mgAt < 5000) error = "busy";
			else {
				d.mgAt = now;
				await lhMgStore(db, game, lhWeekId(now), uuid, name || row.name || "", score, now);
			}
		} else if (p.op === "equip") {
			const t = typeof p.title === "string" ? p.title : row.title;
			const b = typeof p.badge === "string" ? p.badge : row.badge;
			const c = typeof p.tcolor === "string" ? p.tcolor : (row.tcolor || "");
			const ok = (id, kind) => !id || (d.owned.includes(id) && LH_SHOP_MAP[id] && LH_SHOP_MAP[id].kind === kind);
			if (!ok(t, "title") || !ok(b, "badge") || !ok(c, "tcolor")) error = "not owned";
			else { row.title = t; row.badge = b; row.tcolor = c; }
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
	const col = p === "a" ? (b === "points" ? "points_a" : b) : `${b}_${p}`;
	const play = p === "a" ? "play" : `play_${p}`;
	let where = p === "a" ? `${col} > 0` : `p${p} = ${id} AND ${col} > 0`;
	let order = `${col} DESC`;
	if (b === "dropped") {
		// wer am wenigsten wegwirft – aber nur mit genug Spielzeit im Zeitraum
		where = `${p === "a" ? "1 = 1" : `p${p} = ${id}`} AND ${play} >= ${LH_DROP_MIN_PLAY[p]}`;
		order = `${col} ASC, ${play} DESC`;
	}
	const res = await lhCached(request, ctx, `/lh-board-cache/${b}/${p}/${id}`, 300, async () => {
		const r = await env.AH.prepare(`SELECT uuid, name, badge, ${col} AS v FROM lh_player WHERE ${where} ORDER BY ${order} LIMIT 50`).all();
		return { b, p, t: Date.now(), minPlay: b === "dropped" ? LH_DROP_MIN_PLAY[p] : 0, list: (r.results || []).map((x) => [x.uuid, x.name, x.badge || "", x.v]) };
	});
	// eigene Position (nicht zwischengespeichert, eine kleine Abfrage)
	const u = String(url.searchParams.get("u") || "").toLowerCase();
	if (!UUID_RE.test(u)) return res;
	const out = await res.json();
	try {
		const me = await env.AH.prepare(`SELECT ${col} AS v, ${play} AS pl FROM lh_player WHERE uuid = ? AND ${where}`).bind(u).first();
		if (me) {
			const better = b === "dropped" ? `(${col} < ? OR (${col} = ? AND ${play} > ?))` : `${col} > ?`;
			const args = b === "dropped" ? [me.v, me.v, me.pl] : [me.v];
			const r = await env.AH.prepare(`SELECT COUNT(*) AS n FROM lh_player WHERE ${where} AND ${better}`).bind(...args).first();
			out.me = { rank: (r ? r.n : 0) + 1, v: me.v };
		}
	} catch { /* ohne eigene Position */ }
	return json(out);
}

/** Minispiel-Rangliste: GET /lh/mg?g=snake&w=0|1 (diese/letzte Woche) bzw. g=quiz|guess (Serien, gesamt), u = eigene UUID. */
async function lhMgBoard(request, env, ctx, url) {
	if (!env.AH) return json({ error: "no-db" }, 503);
	const game = url.searchParams.get("g") || "";
	const streak = LH_STREAK_GAMES.includes(game);
	if (!LH_MG[game] && !streak) return json({ error: "unknown game" }, 400);
	await lhInit(env.AH);
	const now = Date.now();
	const all = !streak && url.searchParams.get("w") === "a"; // Bestenliste aller Zeiten (bester Wert je Spieler)
	const week = streak ? 0 : lhWeekId(now) - (url.searchParams.get("w") === "1" ? 1 : 0);
	const asc = !!(LH_MG[game] && LH_MG[game].asc);
	const agg = asc ? "MIN" : "MAX";
	// Ansicht: je Spieler ein Wert, frühester Zeitpunkt bei Gleichstand
	const src = all
		? `(SELECT uuid, MAX(name) AS name, ${agg}(score) AS score, MIN(at) AS at FROM lh_mg WHERE game = ?1 AND week > 0 AND score > 0 GROUP BY uuid)`
		: `(SELECT uuid, name, score, at FROM lh_mg WHERE game = ?1 AND week = ?2 AND score > 0)`;
	const res = await lhCached(request, ctx, `/lh-mg-cache/${game}/${all ? "all" : week}`, 60, async () => {
		const st = env.AH.prepare(`SELECT uuid, name, score FROM ${src} ORDER BY score ${asc ? "ASC" : "DESC"}, at ASC LIMIT 10`);
		const r = await (all ? st.bind(game) : st.bind(game, week)).all();
		return { g: game, week: all ? -1 : week, ends: streak || all ? 0 : lhWeekEnd(week),
			reward: !all && LH_MG[game] && LH_MG[game].reward || [], asc, list: (r.results || []).map((x) => [x.uuid, x.name, x.score]) };
	});
	const u = String(url.searchParams.get("u") || "").toLowerCase();
	if (!UUID_RE.test(u)) return res;
	const out = await res.json();
	try {
		const ms = env.AH.prepare(`SELECT score, at FROM ${src} WHERE uuid = ?3`);
		const me = await (all ? ms.bind(game, 0, u) : ms.bind(game, week, u)).first();
		if (me && me.score > 0) {
			const ns = env.AH.prepare(`SELECT COUNT(*) AS n FROM ${src} WHERE (score ${asc ? "<" : ">"} ?3 OR (score = ?3 AND at < ?4))`);
			const n = await (all ? ns.bind(game, 0, me.score, me.at) : ns.bind(game, week, me.score, me.at)).first();
			out.me = { rank: (n ? n.n : 0) + 1, v: me.score };
		}
	} catch { /* ohne eigene Position */ }
	return json(out);
}

/** Spielerkarte: öffentliche Werte eines Spielers (für die Rangliste). */
async function lhCard(request, env, ctx, url) {
	if (!env.AH) return json({ error: "no-db" }, 503);
	const u = String(url.searchParams.get("u") || "").toLowerCase();
	if (!UUID_RE.test(u)) return json({ error: "bad uuid" }, 400);
	await lhInit(env.AH);
	return lhCached(request, ctx, `/lh-card-cache/${u}`, 60, async () => {
		const x = await env.AH.prepare(`SELECT name, title, badge, tcolor, points_a, points, ach, login, dquests, wquests, blocks, placed,
			picked, mobs, fish, play, jobday, updated, data FROM lh_player WHERE uuid = ?`).bind(u).first();
		if (!x) return { error: "unknown" };
		let d = {};
		try { d = JSON.parse(x.data || "{}"); } catch { d = {}; }
		return {
			name: x.name, title: x.title || "", badge: x.badge || "", tcolor: x.tcolor || "", earned: x.points || 0, ach: x.ach || 0,
			achMax: Object.values(LH_ACH).reduce((a, t) => a + t.length, 0),
			loginCur: (d.login && d.login.cur) || 0, loginBest: x.login || 0, dquests: x.dquests || 0, wquests: x.wquests || 0,
			blocks: x.blocks || 0, placed: x.placed || 0, picked: x.picked || 0, mobs: x.mobs || 0, fish: x.fish || 0,
			play: x.play || 0, jobday: x.jobday || 0, owned: (d.owned || []).length, updated: x.updated || 0,
			achTiers: d.ach || {}, legendary: Object.keys(LH_ACH).filter((st) => (d.ach && d.ach[st] || 0) >= LH_ACH[st].length).length,
			colTiers: Object.values((d.cach && d.cach.b) || {}).reduce((a, n) => a + n, 0) + Object.values((d.cach && d.cach.m) || {}).reduce((a, n) => a + n, 0),
		};
	});
}

async function lhTags(request, env, ctx) {
	if (!env.AH) return json({});
	await lhInit(env.AH);
	return lhCached(request, ctx, "/lh-tags-cache", 300, async () => {
		const r = await env.AH.prepare("SELECT uuid, title, badge, tcolor FROM lh_player WHERE title != '' OR badge != ''").all();
		const out = {};
		for (const x of r.results || []) out[x.uuid] = [x.title || "", x.badge || "", x.tcolor || ""];
		return out;
	});
}

/** Shop-Fußspuren nur für Besitzer: nicht gekaufte Stile aus dem Cosmetics-Profil entfernen. */
async function lhCheckProfile(env, uuid, profile) {
	const values = profile && profile.values;
	if (!values) return;
	const needs = (key, v) => String(v).split(",").map((x) => LH_COSM_ITEMS[key][x.trim()]).filter(Boolean);
	if (!Object.keys(LH_COSM_ITEMS).some((k) => k in values && needs(k, values[k]).length)) return;
	let owned = [];
	try {
		if (env.AH) {
			await lhInit(env.AH);
			const row = await env.AH.prepare("SELECT data FROM lh_player WHERE uuid = ?").bind(uuid).first();
			owned = row ? (JSON.parse(row.data).owned || []) : [];
		}
	} catch { owned = []; }
	// nicht gekaufte Shop-Cosmetics entfernen (bei Haustieren nur den betroffenen Platz leeren)
	for (const key of Object.keys(LH_COSM_ITEMS)) {
		if (!(key in values)) continue;
		const parts = String(values[key]).split(",");
		const kept = parts.map((x) => { const need = LH_COSM_ITEMS[key][x.trim()]; return need && !owned.includes(need) ? "" : x; });
		if (kept.join("") === parts.join("")) continue;
		if (kept.every((x) => !x.trim())) {
			delete values[key];
			profile.set = (profile.set || []).filter((s) => s !== key);
		} else values[key] = kept.join(",");
	}
}

export default {
	async scheduled(controller, env, ctx) {
		ctx.waitUntil(ahRun(env).catch(() => {}));
		ctx.waitUntil(fishRefresh(env, false).catch(() => {}));
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

		if (request.method === "GET" && url.pathname === "/fish/known.csv") {
			// für Google Tabellen: =IMPORTDATA("…/fish/known.csv")
			// Google zeigt #N/A bei jeder Nicht-200-Antwort – deshalb bei DB-Fehlern die letzte gute CSV ausliefern.
			const csvHeaders = { "content-type": "text/csv; charset=utf-8", "access-control-allow-origin": "*" };
			const lastGood = new Request(`${url.origin}/fish-known-cache/last.csv`);
			try {
				await lhInit(env.AH);
				const r = await env.AH.prepare("SELECT fish, size, rarity, n FROM lh_fish ORDER BY rarity, fish, size").all();
				const q = (v) => `"${String(v).replace(/"/g, '""')}"`;
				const lines = ["Fisch,Größe,Seltenheit,Spieler", ...(r.results || []).map((x) => [x.fish, x.size, x.rarity, x.n].map(q).join(","))];
				const body = lines.join("\n");
				ctx.waitUntil(caches.default.put(lastGood, new Response(body, {
					headers: { ...csvHeaders, "cache-control": "public, max-age=2592000" },
				})));
				return new Response(body, { headers: csvHeaders });
			} catch {
				const hit = await caches.default.match(lastGood).catch(() => null);
				if (hit) {
					return new Response(await hit.text(), { headers: csvHeaders });
				}
				return new Response("error", { status: 503 });
			}
		}
		if (request.method === "GET" && url.pathname === "/fish/known") {
			try {
				await lhInit(env.AH);
				const r = await env.AH.prepare("SELECT fish, size, rarity, n, at FROM lh_fish ORDER BY rarity, fish, size").all();
				return json({ list: (r.results || []).map((x) => [x.fish, x.size, x.rarity, x.n, x.at]) });
			} catch {
				return json({ error: "busy" }, 503);
			}
		}
		if (request.method === "GET" && url.pathname === "/fish") {
			try {
				const data = await fishRefresh(env, false);
				return json(data || { at: 0, list: [] });
			} catch {
				return json({ error: "busy" }, 503);
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
		if (url.pathname === "/lh/mg" && request.method === "GET") {
			try { return await lhMgBoard(request, env, ctx, url); } catch { return json({ error: "busy" }, 503); }
		}
		if (url.pathname === "/lh/card" && request.method === "GET") {
			try { return await lhCard(request, env, ctx, url); } catch { return json({ error: "busy" }, 503); }
		}
		if (url.pathname === "/lh/tags" && request.method === "GET") {
			try { return await lhTags(request, env, ctx); } catch { return json({}); }
		}

		return new Response("Loothub Cosmetics", { status: 200 });
	},
};
