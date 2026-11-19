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
	// Durchschnittshelligkeit aus der kleinsten Mipmap-Stufe (ein Wert fuers ganze Bild)
	float avg = luma(texture2D(colortex0, vec2(0.5), 12.0).rgb);
	float target = 1.0;
#if AUTO_EXPOSURE > 0
	// hell (weisser Raum, Schnee) -> abdunkeln, dunkel (Hoehle) -> aufhellen; sanft begrenzt
	target = clamp(pow(0.42 / max(avg, 0.01), float(AUTO_EXPOSURE) * 0.25), 0.70, 1.45);
#endif
	float old = texture2D(colortex3, vec2(0.5)).r;
	// wie ein Auge: passt sich in etwa einer Sekunde an
	float ev = old <= 0.01 ? target : mix(old, target, 1.0 - exp(-frameTime * 2.2));
	gl_FragData[1] = vec4(ev, 0.0, 0.0, 1.0);
}
