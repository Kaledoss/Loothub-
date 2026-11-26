#version 120
// Schritt 3: Gluehen senkrecht weichzeichnen und dazu, Farbstimmung, sanfte Tonkurve, Vignette
// (der senkrechte Weichzeichner laeuft hier mit, das spart einen ganzen Bilddurchgang)
#include "/common.glsl"
#include "/blur.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex3;

// filmische Kurve (ACES-Naeherung)
vec3 filmic(vec3 x) {
	return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

vec3 tonemap(vec3 x) {
	// dunkle und mittlere Toene bleiben unveraendert, Spitzlichter laufen weich gegen Weiss aus
	// (ueber den hellsten Farbkanal, damit sich Farbtoene nicht verschieben)
	float m = max(x.r, max(x.g, x.b));
	float k = 0.72;
	if (m <= k) {
		return x;
	}
	float t = k + (1.0 - k) * (1.0 - exp(-(m - k) / (1.0 - k)));
	return x * (t / m);
}

void main() {
	vec3 color = texture2D(colortex0, texcoord).rgb;
#ifdef BLOOM
	vec3 glow = blur(vec2(0.0, 1.0));
	// Gluehen nur dazugeben, wo es heller ist als das Bild darunter (keine milchige Schicht ueber weissen Flaechen)
	color += glow * BLOOM_STRENGTH;
#endif
	color *= EXPOSURE;
	// automatische Belichtung (in composite1 nachgefuehrt)
	color *= max(texture2D(colortex3, vec2(0.5)).r, 0.05);

	// Farbbearbeitung in Lichtwerten (linear) statt in Bildschirmwerten – wirkt wie eine Kamera, nicht wie ein Gemaelde
	vec3 lin = pow(max(color, 0.0), vec3(2.2));
	// Waerme: Rot/Gelb leicht anheben, Blau leicht senken (oder umgekehrt)
	lin *= vec3(1.0 + WARMTH, 1.0 + WARMTH * 0.35, 1.0 - WARMTH);
	// Kontrast um 18 % Grau (wie Belichtung: dunkle Stellen dunkler, helle heller, ohne Farben zu verschieben)
	lin = 0.18 * pow(lin / 0.18 + 1e-5, vec3(CONTRAST));
	float l = luma(lin);
	lin = max(mix(vec3(l), lin, SATURATION), 0.0);
	// sehr knallige Farben (Gras, Erde, rosa Schweine) natuerlich daempfen
	float mx = max(lin.r, max(lin.g, lin.b));
	float mn = min(lin.r, min(lin.g, lin.b));
	float chroma = (mx - mn) / max(mx, 1e-4);
	lin = mix(lin, vec3(luma(lin)), smoothstep(0.55, 1.0, chroma) * 0.10);
	// filmische Kurve: Mitten fast unveraendert, Lichter laufen weich aus und entsaettigen leicht
	// so abgestimmt, dass mittleres Grau bleibt, wie es ist (vorher wurde alles leicht aufgehellt)
	lin = filmic(lin * 0.6) / filmic(vec3(0.96)).x;
	color = pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.2));

#if SPLIT_TONE > 0
	// Lichter leicht golden, Schatten leicht blau – der warme Abend-Look, ohne die Mitten zu verfaerben
	float lt = luma(color);
	color += (vec3(0.06, 0.025, -0.04) * smoothstep(0.45, 1.0, lt) + vec3(-0.025, -0.005, 0.035) * (1.0 - smoothstep(0.0, 0.40, lt)))
			* SPLIT_TONE;
#endif

	// luftig: Schatten etwas anheben und leicht kuehl-weiss toenen (heller, klarer Look)
	vec3 airTint = vec3(0.97, 0.98, 1.0);
	color = color + (airTint - color) * AIRY * (1.0 - luma(color)) * 0.6;

	// Vignette
	vec2 c = texcoord - 0.5;
	c.x *= viewWidth / viewHeight;
	float v = 1.0 - smoothstep(0.25, 0.85, length(c));
	color *= mix(1.0, v, VIGNETTE);

#if DEBUG_VIEW == 1
	color = texture2D(colortex0, texcoord).rgb; // ohne jede Nachbearbeitung
#elif DEBUG_VIEW == 3
	color = blur(vec2(0.0, 1.0)) * 3.0; // nur das Gluehen
#elif DEBUG_VIEW == 4
	color = texture2D(colortex0, texcoord).rgb; // Dunst und Grundbild ohne Farbstimmung
#endif
	gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
