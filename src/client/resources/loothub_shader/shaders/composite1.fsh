#version 120
// Schritt 2: Gluehen waagerecht weichzeichnen und die automatische Belichtung nachfuehren
#include "/common.glsl"
#include "/blur.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex3;
uniform float frameTime;

/*
const bool colortex0MipmapEnabled = true;
const int colortex3Format = R16F;
const bool colortex3Clear = false;
*/

void main() {
	/* RENDERTARGETS: 1,3 */
#ifdef BLOOM
	gl_FragData[0] = vec4(blur(vec2(1.0, 0.0)), 1.0);
#else
	gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0);
#endif
	// Durchschnittshelligkeit aus einer kleinen Mipmap-Stufe, mittig gewichtet: Hand, Item und Bildraender zaehlen
	// kaum (sonst aendert schon ein Schwertschwung die Belichtung)
	float avg = 0.0;
	avg += luma(texture2D(colortex0, vec2(0.50, 0.50), 6.0).rgb) * 0.36;
	avg += luma(texture2D(colortex0, vec2(0.35, 0.40), 6.0).rgb) * 0.16;
	avg += luma(texture2D(colortex0, vec2(0.65, 0.40), 6.0).rgb) * 0.16;
	avg += luma(texture2D(colortex0, vec2(0.35, 0.62), 6.0).rgb) * 0.16;
	avg += luma(texture2D(colortex0, vec2(0.65, 0.62), 6.0).rgb) * 0.16;
	float target = 1.0;
#if AUTO_EXPOSURE > 0
	// hell (weisser Raum, Schnee) -> abdunkeln, dunkel (Hoehle) -> aufhellen; sanft begrenzt
	target = clamp(pow(0.42 / max(avg, 0.01), float(AUTO_EXPOSURE) * 0.2), 0.78, 1.32);
#endif
	float old = texture2D(colortex3, vec2(0.5)).r;
	float ev;
	if (old <= 0.01 || old > 3.0) {
		ev = target; // erster Frame
	} else {
		// kleine Schwankungen ignorieren, groessere langsam (ca. 2 Sekunden) nachfuehren
		float diff = log(target / old);
		float dead = 0.06;
		float step = abs(diff) <= dead ? 0.0 : sign(diff) * (abs(diff) - dead);
		ev = old * exp(step * (1.0 - exp(-frameTime * 1.1)));
	}
	gl_FragData[1] = vec4(ev, 0.0, 0.0, 1.0);
}
