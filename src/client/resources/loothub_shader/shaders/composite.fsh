#version 120
// Schritt 1: Dunst nach Entfernung und helle Stellen fuer das Gluehen heraussuchen
#include "/common.glsl"

uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform sampler2D colortex2;
uniform mat4 gbufferProjectionInverse;
uniform vec3 fogColor;
uniform float far;
uniform int isEyeInWater;
uniform float rainStrength;

varying vec2 texcoord;

void main() {
	vec3 color = texture2D(colortex0, texcoord).rgb;
	float depth = texture2D(depthtex0, texcoord).r;

	// Dunst: nur auf Bloecken (nicht am Himmel), ueber Wasser, nimmt mit der Entfernung zu
	if (depth < 1.0 && isEyeInWater == 0 && HAZE > 0.0) {
		vec4 view = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
		float dist = length(view.xyz / view.w);
		float d = clamp(dist / max(far, 16.0), 0.0, 1.0);
		float amount = (1.0 - exp(-d * d * 4.0)) * HAZE * (1.0 + rainStrength * 0.6);
		// Dunst fast farblos (sonst faerbt der Abendhimmel alles lila)
		vec3 haze = mix(fogColor, vec3(max(luma(fogColor), 0.6)), 0.75);
		color = mix(color, haze, clamp(amount, 0.0, 0.85));
	}

	// helle Stellen (weiche Schwelle)
	vec3 bright = vec3(0.0);
#ifdef BLOOM
	float l = luma(color);
	float knee = 0.05;
	float soft = clamp((l - BLOOM_THRESHOLD + knee) / (2.0 * knee), 0.0, 1.0);
	float w = max(soft * soft * knee * 2.0, l - BLOOM_THRESHOLD) / max(l, 0.0001);
	// nur Lichtquellen (Glowstone, Laternen, Lava, Sonne ...) - nicht jede weisse Wand
	float mask = texture2D(colortex2, texcoord).r;
	bright = color * clamp(w, 0.0, 1.0) * mask;
#endif

	/*
	const int colortex1Format = RGBA16F;
	const bool colortex1Clear = true;
	const int colortex2Format = R8;
	const bool colortex2Clear = true;
	*/
	/* RENDERTARGETS: 0,1 */
	gl_FragData[0] = vec4(color, 1.0);
	gl_FragData[1] = vec4(bright, 1.0);
}
