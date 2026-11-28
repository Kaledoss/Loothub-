package de.loothub.client.cosmetics;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Haustiere von Freunden spielen miteinander: Stehen du und ein Loothub-Freund (beide mit Haustieren) höchstens 8 Blöcke
 * auseinander, treffen sich eure Haustiere ab und zu in der Mitte, beschnuppern sich kurz und jagen sich dann im Kreis.
 * Nur auf deinem Bildschirm, nur bei Freunden (Freundesliste), nicht beim Fliegen oder Schlafen.
 */
public final class Meet {
	private static final long PERIOD_MS = 45_000L, MEET_MS = 11_000L, SNIFF_MS = 2_000L;
	private static final double RANGE = 8.0;
	private static Set<String> friendIds = Set.of();
	private static int friendsVersion = -1;
	private static long cacheTick = -1;
	private static final java.util.Map<Integer, Integer> PARTNER = new java.util.HashMap<>();

	private Meet() {
	}

	/** Zielplatz (Ebenen-Raum des Spielers {x, y, z}) für Haustier {@code slot}, oder null, wenn gerade kein Treffen ist. */
	public static float[] target(AvatarRenderState s, int slot) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !de.loothub.client.jobs.WidgetConfig.get().friendsOn
				|| de.loothub.client.friends.Friends.friends().isEmpty()) {
			return null;
		}
		Entity self = mc.level.getEntity(s.id);
		if (!(self instanceof Player p)) {
			return null;
		}
		// Partner nur einmal je Tick suchen (sonst für jedes Haustier in jedem Bild)
		long tick = mc.level.getGameTime();
		if (tick != cacheTick) {
			cacheTick = tick;
			PARTNER.clear();
		}
		int pid = PARTNER.computeIfAbsent(p.getId(), k -> {
			Player o = partner(mc, p);
			return o == null ? -1 : o.getId();
		});
		Player partner = pid < 0 ? null : (mc.level.getEntity(pid) instanceof Player pp ? pp : null);
		if (partner == null) {
			return null;
		}
		// beide Seiten rechnen mit derselben Zeit: Treffen starten gleichzeitig
		int a = Math.min(p.getId(), partner.getId()), b = Math.max(p.getId(), partner.getId());
		long phase = Math.floorMod(System.currentTimeMillis() + (long) (a * 31 + b) * 7_919L, PERIOD_MS);
		if (phase >= MEET_MS) {
			return null;
		}
		double mx = (p.getX() + partner.getX()) / 2.0, mz = (p.getZ() + partner.getZ()) / 2.0;
		double my = Math.min(p.getY(), partner.getY());
		boolean first = p.getId() == a;
		double angle;
		double radius;
		if (phase < SNIFF_MS) {
			// beschnuppern: gegenüber, eng beieinander
			angle = Math.atan2(p.getZ() - partner.getZ(), p.getX() - partner.getX()) + (slot == 0 ? 0.35 : -0.35);
			radius = 0.45;
		} else {
			// fangen spielen: alle Haustiere auf einem Kreis um die Mitte, versetzt
			double t = (phase - SNIFF_MS) / 1000.0 * 2.2;
			angle = t + (first ? 0 : Math.PI) + slot * Math.PI / 2;
			radius = 0.9;
		}
		double wx = mx + Math.cos(angle) * radius, wz = mz + Math.sin(angle) * radius;
		return Followers.worldToLayer(s, wx, my, wz);
	}

	/** Nächster Freund in Reichweite mit Haustieren; der Freund des eigenen Spielers bzw. man selbst für den Freund. */
	private static Player partner(Minecraft mc, Player p) {
		refreshFriends();
		Player best = null;
		double bestD = RANGE * RANGE;
		if (p == mc.player) {
			for (Player o : mc.level.players()) {
				if (o == p || !friendIds.contains(o.getUUID().toString()) || !hasPets(o)) {
					continue;
				}
				double d = o.distanceToSqr(p);
				if (d < bestD) {
					bestD = d;
					best = o;
				}
			}
			return best;
		}
		// Haustiere eines Freundes: Treffpartner ist man selbst (nur, wenn man selbst Haustiere hat)
		if (friendIds.contains(p.getUUID().toString()) && hasPets(mc.player) && p.distanceToSqr(mc.player) < bestD) {
			// nur wenn man auch der nächste Freund dieses Spielers aus eigener Sicht ist
			Player mine = partner(mc, mc.player);
			return mine == p ? mc.player : null;
		}
		return null;
	}

	private static boolean hasPets(Player pl) {
		Cosmetics.Snapshot snap = Cosmetics.of(pl.getId());
		if (snap == null || !snap.set().contains(Cosmetics.PETS)) {
			return false;
		}
		String v = snap.values().get(Cosmetics.PETS);
		return v != null && !v.replace(",", "").isBlank();
	}

	private static void refreshFriends() {
		var list = de.loothub.client.friends.Friends.friends();
		if (list.hashCode() == friendsVersion) {
			return;
		}
		friendsVersion = list.hashCode();
		Set<String> ids = new HashSet<>();
		for (var f : list) {
			ids.add(f.uuid().toLowerCase(java.util.Locale.ROOT));
		}
		friendIds = ids;
	}
}
