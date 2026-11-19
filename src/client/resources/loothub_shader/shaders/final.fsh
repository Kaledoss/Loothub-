#version 120
// Schritt 3: Gluehen senkrecht weichzeichnen und dazu, Farbstimmung, sanfte Tonkurve, Vignette
// (der senkrechte Weichzeichner laeuft hier mit, das spart einen ganzen Bilddurchgang)
#include "/common.glsl"
#include "/blur.glsl"

uniform sampler2D colortex0;

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

	// Waerme: Rot/Gelb leicht anheben, Blau leicht senken (oder umgekehrt)
	color *= vec3(1.0 + WARMTH, 1.0 + WARMTH * 0.35, 1.0 - WARMTH);

	// Kontrast um Mittelgrau, Saettigung ueber die Helligkeit
	color = (color - 0.5) * CONTRAST + 0.5;
	float l = luma(color);
	color = mix(vec3(l), color, SATURATION);

	color = tonemap(max(color, 0.0));

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
