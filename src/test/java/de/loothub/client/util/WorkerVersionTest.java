package de.loothub.client.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Mod und Worker müssen denselben Stand nennen – sonst sähen Admins dauerhaft „Worker veraltet“. */
class WorkerVersionTest {
	@Test
	void modAndWorkerAgree() throws Exception {
		String worker = Files.readString(Path.of("tools/cosmetics-worker.js"));
		String mod = Files.readString(Path.of("src/client/java/de/loothub/client/progress/Progress.java"));
		Matcher w = Pattern.compile("const WORKER_VERSION = (\\d+);").matcher(worker);
		Matcher m = Pattern.compile("WORKER_NEEDED = (\\d+);").matcher(mod);
		w.find();
		m.find();
		assertEquals(w.group(1), m.group(1), "WORKER_VERSION (Worker) und WORKER_NEEDED (Mod) unterscheiden sich");
	}
}
