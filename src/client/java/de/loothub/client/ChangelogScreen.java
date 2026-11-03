package de.loothub.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Seite „Changelog“: alle Änderungen an Loothub+, nach Version gruppiert (neueste oben).
 * Quelle sind die Commits und Build-Läufe des GitHub-Repositorys; geladen einmal pro Spielstart.
 */
public class ChangelogScreen extends LoothubScreen {
	private static final String REPO = "Kaledoss/Loothub-";
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMANY);

	/** Eine Version mit ihren Änderungen. version == null: noch nicht als Version erschienen. */
	private record Group(String version, long time, List<String> changes) {
	}

	private record Line(FormattedCharSequence text, int color, int indent, boolean header, String tag, int tagColor) {
	}

	private static volatile List<Group> groups;
	private static volatile String error;
	private static volatile boolean loading;
	private static volatile int generation;

	private static final int LINE_H = 11;
	private static final int GREEN = 0xFF55E06A;
	private static final int GOLD = 0xFFF2C14E;

	private List<Line> lines = List.of();
	private int builtFor = -1;
	private int builtWidth = -1;
	private int scroll;
	private int listTop;

	public ChangelogScreen() {
		super(Page.CHANGELOG);
	}

	@Override
	protected void initPage() {
		listTop = ct;
		load(false);
		rebuild();
	}

	@Override
	protected Component headerRight() {
		return Component.translatable("loothub.changelog.installed", Updater.currentVersion());
	}

	// ------------------------------------------------------------------ Laden

	private static void load(boolean force) {
		if (loading || (groups != null && !force)) {
			return;
		}
		loading = true;
		error = null;
		CompletableFuture<String> commits = get("https://api.github.com/repos/" + REPO + "/commits?sha=main&per_page=100");
		CompletableFuture<String> runs = get("https://api.github.com/repos/" + REPO
				+ "/actions/runs?branch=main&event=push&status=success&per_page=100")
				.exceptionally(t -> null); // ohne Build-Läufe wird nach Tagen gruppiert
		commits.thenCombine(runs, ChangelogScreen::parse).whenComplete((result, t) -> {
			if (t != null) {
				Throwable c = t.getCause() != null ? t.getCause() : t;
				error = c instanceof IllegalStateException ? c.getMessage() : "offline";
				Loothub.LOGGER.warn("Changelog konnte nicht geladen werden: {}", c.toString());
			} else {
				groups = result;
			}
			loading = false;
			generation++;
		});
	}

	private static CompletableFuture<String> get(String url) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "Loothub-Mod/" + Updater.currentVersion())
				.GET()
				.build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(r -> {
			if (r.statusCode() == 403 || r.statusCode() == 429) {
				throw new IllegalStateException("rate_limited");
			}
			if (r.statusCode() != 200) {
				throw new IllegalStateException("HTTP " + r.statusCode());
			}
			return r.body();
		});
	}

	private static List<Group> parse(String commitsJson, String runsJson) {
		// Build-Lauf je Commit: Laufnummer = dritte Stelle der Version
		Map<String, Integer> versionAt = new HashMap<>();
		if (runsJson != null) {
			for (JsonElement el : JsonParser.parseString(runsJson).getAsJsonObject().getAsJsonArray("workflow_runs")) {
				JsonObject run = el.getAsJsonObject();
				String sha = run.get("head_sha").getAsString();
				int number = run.get("run_number").getAsInt();
				versionAt.merge(sha, number, Math::max);
			}
		}
		boolean byVersion = !versionAt.isEmpty();
		List<Group> out = new ArrayList<>();
		Group current = null;
		for (JsonElement el : JsonParser.parseString(commitsJson).getAsJsonArray()) {
			JsonObject c = el.getAsJsonObject();
			JsonObject commit = c.getAsJsonObject("commit");
			String message = commit.get("message").getAsString();
			String subject = message.split("\\r?\\n", 2)[0].strip();
			long time = Instant.parse(commit.getAsJsonObject("committer").get("date").getAsString()).toEpochMilli();
			if (subject.isEmpty() || subject.contains("[skip ci]") || subject.startsWith("Merge ")) {
				continue;
			}
			if (byVersion) {
				Integer run = versionAt.get(c.get("sha").getAsString());
				if (run != null) {
					current = new Group("1.0." + run, time, new ArrayList<>());
					out.add(current);
				} else if (current == null) {
					current = new Group(null, time, new ArrayList<>()); // gerade im Bau
					out.add(current);
				}
			} else {
				String day = day(time);
				if (current == null || !day(current.time()).equals(day)) {
					current = new Group(null, time, new ArrayList<>());
					out.add(current);
				}
			}
			current.changes().add(subject);
		}
		return List.copyOf(out);
	}

	private static String day(long time) {
		return DAY.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
	}

	// ------------------------------------------------------------------ Darstellung

	private void rebuild() {
		builtFor = generation;
		builtWidth = cw;
		List<Group> data = groups;
		List<Line> out = new ArrayList<>();
		if (data == null) {
			lines = out;
			return;
		}
		String installed = Updater.currentVersion();
		int wrap = Math.max(60, cw - 24);
		for (Group g : data) {
			if (!out.isEmpty()) {
				out.add(new Line(FormattedCharSequence.EMPTY, 0, 0, false, null, 0));
			}
			String title;
			String tag = null;
			int tagColor = 0;
			if (g.version() != null) {
				title = Component.translatable("loothub.changelog.version", g.version(), day(g.time())).getString();
				int cmp = Updater.compare(g.version(), installed);
				if (cmp == 0) {
					tag = Component.translatable("loothub.changelog.tag.installed").getString();
					tagColor = GREEN;
				} else if (cmp > 0) {
					tag = Component.translatable("loothub.changelog.tag.new").getString();
					tagColor = GOLD;
				}
			} else if (data.stream().anyMatch(x -> x.version() != null)) {
				title = Component.translatable("loothub.changelog.building").getString();
			} else {
				title = day(g.time());
			}
			out.add(new Line(Component.literal(title).getVisualOrderText(), TEXT, 0, true, tag, tagColor));
			for (String change : g.changes()) {
				List<FormattedCharSequence> parts = font.split(Component.literal(change), wrap);
				for (int i = 0; i < parts.size(); i++) {
					out.add(new Line(parts.get(i), MUTED, i == 0 ? 8 : 18, false, i == 0 ? "•" : null, 0));
				}
			}
		}
		lines = out;
	}

	@Override
	public void tick() {
		super.tick();
		if (builtFor != generation || builtWidth != cw) {
			rebuild();
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		if (lines.isEmpty()) {
			Component msg;
			if (error != null) {
				msg = Component.translatable("loothub.changelog.error", Updater.reasonText(error));
			} else {
				msg = Component.translatable("loothub.changelog.loading");
			}
			g.drawString(font, msg, cl + (cw - font.width(msg)) / 2, listTop + 20, FAINT, false);
			return;
		}
		int visible = Math.max(1, (cb - listTop) / LINE_H);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
		g.enableScissor(cl, listTop, cr, cb);
		for (int i = 0; i <= visible && i + scroll < lines.size(); i++) {
			Line l = lines.get(i + scroll);
			int y = listTop + i * LINE_H;
			if (l.header()) {
				g.fill(cl, y - 1, cr, y + LINE_H - 1, 0x22FFFFFF);
				g.fill(cl, y - 1, cl + 2, y + LINE_H - 1, RED);
				g.drawString(font, l.text(), cl + 6, y + 1, l.color(), false);
				if (l.tag() != null) {
					g.drawString(font, l.tag(), cr - 6 - font.width(l.tag()), y + 1, l.tagColor(), false);
				}
			} else {
				if (l.tag() != null) {
					g.drawString(font, l.tag(), cl + l.indent(), y + 1, RED, false);
				}
				g.drawString(font, l.text(), cl + l.indent() + 10, y + 1, l.color(), false);
			}
		}
		g.disableScissor();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (my >= listTop && my < cb) {
			scroll = Math.max(0, scroll - (int) Math.signum(vertical) * 3);
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}
}
