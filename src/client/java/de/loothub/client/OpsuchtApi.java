package de.loothub.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Kleiner HTTP-Client für die öffentliche OPSUCHT-API (https://api.opsucht.net). Alle Abfragen laufen asynchron. */
public final class OpsuchtApi {
	private static final String BASE_URL = "https://api.opsucht.net";
	private static final String USER_AGENT = "Loothub-Mod/1.0 (Fabric 1.21.11; inoffiziell)";

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private OpsuchtApi() {
	}

	public static CompletableFuture<JsonElement> get(String path) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
				.timeout(Duration.ofSeconds(15))
				.header("Accept", "application/json")
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(response -> {
					if (response.statusCode() != 200) {
						throw new IllegalStateException("HTTP " + response.statusCode());
					}
					return JsonParser.parseString(response.body());
				});
	}

	static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	static double num(JsonObject o, String key) {
		JsonElement e = o.get(key);
		try {
			return e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
		} catch (RuntimeException ex) {
			return Double.NaN;
		}
	}
}
