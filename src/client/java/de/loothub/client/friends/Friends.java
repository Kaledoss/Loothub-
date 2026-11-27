package de.loothub.client.friends;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.loothub.client.jobs.JobTracker;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.progress.Progress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Freundesystem: Freundschaft nur, wenn beide zustimmen. Ist es eingeschaltet, meldet die Mod jede Minute „online“
 * (und – nur wenn gewünscht – den aktuellen Job) und holt die Freundesliste. Den Status sehen nur Freunde.
 */
public final class Friends {
	private static final long POLL_MS = 60_000L;

	public record Friend(String uuid, String name, boolean online, long seen, String act) {
	}

	public record Person(String uuid, String name) {
	}

	private static List<Friend> friends = List.of();
	private static List<Person> incoming = List.of(), outgoing = List.of();
	private static Set<String> wasOnline;
	private static long lastPoll;
	private static boolean busy;
	private static boolean loaded;
	private static int incomingSeen;

	private Friends() {
	}

	public static void register() {
		de.loothub.client.util.Guard.tick("Friends", mc -> {
			if (mc.player == null || !WidgetConfig.get().friendsOn) {
				return;
			}
			if (System.currentTimeMillis() - lastPoll > POLL_MS) {
				refresh(null);
			}
		});
	}

	public static List<Friend> friends() {
		return friends;
	}

	public static List<Person> incoming() {
		return incoming;
	}

	public static List<Person> outgoing() {
		return outgoing;
	}

	public static boolean loaded() {
		return loaded;
	}

	public static int onlineCount() {
		int n = 0;
		for (Friend f : friends) {
			if (f.online()) {
				n++;
			}
		}
		return n;
	}

	/** Aktuelle Tätigkeit für Freunde (nur wenn geteilt): „Holzfäller · Level 58“. */
	private static String activity() {
		JobTracker.Snapshot s = JobTracker.snapshot();
		if (s == null || s.job() == null) {
			return "";
		}
		return s.level() >= 0 ? s.job() + " · Lvl " + s.level() : s.job();
	}

	/** Liste neu holen (und eigenen Online-Stand melden). done bekommt null oder einen Fehlerschlüssel. */
	public static void refresh(Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "friends");
		WidgetConfig c = WidgetConfig.get();
		p.addProperty("share", c.friendsShare);
		if (c.friendsShare) {
			p.addProperty("act", activity());
		}
		call(p, done);
	}

	public static void add(String name, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "friend_add");
		p.addProperty("target", name.trim());
		call(p, done);
	}

	/** op: friend_accept, friend_decline oder friend_remove. */
	public static void act(String op, String uuid, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", op);
		p.addProperty("u", uuid);
		call(p, done);
	}

	private static void call(JsonObject p, Consumer<String> done) {
		if (busy && "friends".equals(p.get("op").getAsString())) {
			return;
		}
		busy = true;
		lastPoll = System.currentTimeMillis();
		Progress.request(p, res -> {
			busy = false;
			String err;
			if (res == null) {
				err = "offline";
			} else {
				if (res.has("friends")) {
					apply(res);
				}
				err = res.has("error") ? res.get("error").getAsString() : res.has("msg") ? "msg_" + res.get("msg").getAsString() : null;
			}
			if (done != null) {
				done.accept(err);
			}
		});
	}

	private static void apply(JsonObject res) {
		List<Friend> fl = new ArrayList<>();
		for (JsonElement e : arr(res, "friends")) {
			JsonObject o = e.getAsJsonObject();
			fl.add(new Friend(str(o, "u"), str(o, "name"), o.has("online") && o.get("online").getAsBoolean(),
					o.has("seen") ? o.get("seen").getAsLong() : 0, str(o, "act")));
		}
		List<Person> in = people(arr(res, "incoming")), out = people(arr(res, "outgoing"));
		// Hinweise: Freund kommt online, neue Freundschaftsanfrage
		Set<String> now = new HashSet<>();
		for (Friend f : fl) {
			if (f.online()) {
				now.add(f.uuid());
			}
		}
		WidgetConfig c = WidgetConfig.get();
		if (wasOnline != null && c.friendsNotify) {
			for (Friend f : fl) {
				if (f.online() && !wasOnline.contains(f.uuid())) {
					toast(Component.translatable("loothub.friends.toast.online.title"),
							Component.translatable("loothub.friends.toast.online", f.name()));
				}
			}
		}
		if (loaded && in.size() > incomingSeen) {
			toast(Component.translatable("loothub.friends.toast.request.title"),
					Component.translatable("loothub.friends.toast.request", in.get(in.size() - 1).name()));
		}
		incomingSeen = in.size();
		wasOnline = now;
		friends = fl;
		incoming = in;
		outgoing = out;
		loaded = true;
	}

	private static void toast(Component title, Component text) {
		Minecraft mc = Minecraft.getInstance();
		mc.getToastManager().addToast(new SystemToast(SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, text));
	}

	private static List<Person> people(JsonArray a) {
		List<Person> out = new ArrayList<>();
		for (JsonElement e : a) {
			JsonObject o = e.getAsJsonObject();
			out.add(new Person(str(o, "u"), str(o, "name")));
		}
		return out;
	}

	private static JsonArray arr(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}
}
