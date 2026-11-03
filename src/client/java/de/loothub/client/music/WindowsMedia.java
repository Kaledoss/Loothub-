package de.loothub.client.music;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import de.loothub.Loothub;

/**
 * „Läuft gerade“ über die Windows-Medienanzeige (dieselbe Info wie im Lautstärke-Fenster). Funktioniert mit
 * YouTube Music im Browser bzw. als installierte Web-App, aber auch mit anderen Playern. Nur unter Windows;
 * ein PowerShell-Prozess im Hintergrund meldet alle 2 Sekunden Titel, Interpret und Stand. Nur lesend.
 */
public final class WindowsMedia {
	private static final String SCRIPT = String.join("\n",
			"$ErrorActionPreference='SilentlyContinue'",
			"Add-Type -AssemblyName System.Runtime.WindowsRuntime",
			"$asTask=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0]",
			"function Await($op,[Type]$t){$m=$asTask.MakeGenericMethod($t);$task=$m.Invoke($null,@($op));$task.Wait(-1)|Out-Null;$task.Result}",
			"[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]|Out-Null",
			"$mgr=Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
			"while($true){",
			" $s=$mgr.GetCurrentSession()",
			" if($s){",
			"  $p=Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
			"  $pb=$s.GetPlaybackInfo();$tl=$s.GetTimelineProperties()",
			"  @{title=$p.Title;artist=$p.Artist;app=$s.SourceAppUserModelId;status=[string]$pb.PlaybackStatus;pos=$tl.Position.TotalSeconds;end=$tl.EndTime.TotalSeconds}|ConvertTo-Json -Compress",
			" } else { '{}' }",
			" [Console]::Out.Flush()",
			" Start-Sleep -Seconds 2",
			"}");

	private static volatile YtMusic.Song song;
	private static volatile Process process;
	private static volatile Thread thread;

	private WindowsMedia() {
	}

	public static boolean supported() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	public static YtMusic.Song song() {
		return song;
	}

	public static synchronized void ensure(boolean enabled) {
		if (!enabled || !supported()) {
			stop();
			return;
		}
		if (thread != null && thread.isAlive()) {
			return;
		}
		Thread t = new Thread(WindowsMedia::run, "Loothub-WindowsMedia");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	public static synchronized void stop() {
		Thread t = thread;
		thread = null;
		Process p = process;
		process = null;
		if (p != null) {
			p.destroy();
		}
		if (t != null) {
			t.interrupt();
		}
		song = null;
	}

	private static void run() {
		Thread me = Thread.currentThread();
		while (thread == me) {
			try {
				Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", "-")
						.redirectErrorStream(false).start();
				process = p;
				try (OutputStream os = p.getOutputStream()) {
					os.write(SCRIPT.getBytes(StandardCharsets.UTF_8));
				}
				try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while (thread == me && (line = r.readLine()) != null) {
						parse(line.strip());
					}
				}
			} catch (IOException | RuntimeException e) {
				Loothub.LOGGER.debug("Windows-Medieninfo nicht verfügbar", e);
			}
			song = null;
			try {
				Thread.sleep(10_000); // Prozess beendet → später neu starten
			} catch (InterruptedException e) {
				break;
			}
		}
	}

	private static void parse(String line) {
		if (!line.startsWith("{")) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(line).getAsJsonObject();
			if (!o.has("title")) {
				song = null;
				return;
			}
			String title = o.get("title").isJsonNull() ? "" : o.get("title").getAsString();
			if (title.isBlank()) {
				song = null;
				return;
			}
			String artist = o.has("artist") && !o.get("artist").isJsonNull() ? o.get("artist").getAsString() : "";
			String status = o.has("status") && !o.get("status").isJsonNull() ? o.get("status").getAsString() : "";
			double pos = o.has("pos") && !o.get("pos").isJsonNull() ? o.get("pos").getAsDouble() : 0;
			double end = o.has("end") && !o.get("end").isJsonNull() ? o.get("end").getAsDouble() : 0;
			song = new YtMusic.Song(title, artist, !status.equalsIgnoreCase("Playing"), pos, end, System.currentTimeMillis());
		} catch (RuntimeException ignored) {
			// unvollständige Zeile
		}
	}
}
