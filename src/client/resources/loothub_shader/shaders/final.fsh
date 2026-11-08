#version 120
// Schritt 4: Gluehen dazu, Farbstimmung, sanfte Tonkurve, Vignette
#include "/common.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform float viewWidth;
uniform float viewHeight;

varying vec2 texcoord;

vec3 tonemap(vec3 x) {
	// weiche Kurve: dunkle Toene unveraendert, Spitzlichter laufen sanft aus statt hart abzuschneiden
	return x / (1.0 + max(x - 0.8, 0.0) * 0.9);
}

void main() {
	vec3 color = texture2D(colortex0, texcoord).rgb;
#ifdef BLOOM
	vec3 glow = texture2D(colortex1, texcoord).rgb;
	// Gluehen nur dazugeben, wo es heller ist als das Bild darunter (keine milchige Schicht ueber weissen Flaechen)
	color += max(glow - color * 0.5, 0.0) * BLOOM_STRENGTH;
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
	vec3 airTint = vec3(0.93, 0.96, 1.0);
	color = color + (airTint - color) * AIRY * (1.0 - luma(color)) * 0.6;

	// Vignette
	vec2 c = texcoord - 0.5;
	c.x *= viewWidth / viewHeight;
	float v = 1.0 - smoothstep(0.25, 0.85, length(c));
	color *= mix(1.0, v, VIGNETTE);

	gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
