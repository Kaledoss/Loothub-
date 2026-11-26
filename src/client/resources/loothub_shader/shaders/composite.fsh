#version 120
// Schritt 1: Dunst nach Entfernung und helle Stellen fuer das Gluehen heraussuchen
#include "/common.glsl"
#include "/sky.glsl"

uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform sampler2D colortex2;
uniform mat4 gbufferModelViewInverse;
uniform vec3 sunPosition;
uniform vec3 shadowLightPosition;
uniform mat4 gbufferProjection;
uniform vec3 upPosition;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform float far;
uniform int isEyeInWater;
uniform float nightVision;
uniform float rainStrength;

varying vec2 texcoord;

void main() {
	vec3 color = texture2D(colortex0, texcoord).rgb;
	float depth = texture2D(depthtex0, texcoord).r;

	// Himmel auch dort, wo nichts gezeichnet ist (unter dem Horizont, nicht geladene Chunks), und weit entfernte
	// Landschaft am Rand der Sichtweite weich in diesen Himmel ausblenden – statt in die (mit Nachtsicht hellblaue) Nebelfarbe
	float maskV = texture2D(colortex2, texcoord).r;
	bool cloud = abs(maskV - 0.5) < 0.2;
	if (isEyeInWater == 0 && !cloud) {
		vec3 dir = skyDir(gbufferModelViewInverse);
		float sunH = dot(normalize(sunPosition), normalize(upPosition));
		float w;
		if (depth >= 1.0) {
			w = 1.0 - smoothstep(-0.04, 0.04, dir.y);
		} else {
			vec4 vp = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
			// schmaler Rand am Ende der Sichtweite (hoechstens 20 Bloecke)
			w = smoothstep(far - min(far * 0.2, 20.0), far - 2.0, length(vp.xyz / vp.w));
		}
		if (w > 0.0) {
			vec3 fill = skyGradient(dir, skyColor, fogColor) + nightSkyDir(dir, normalize(mat3(gbufferModelViewInverse) * sunPosition), sunH, rainStrength);
			color = mix(color, fill, w);
		}
	}

	// Unter Wasser: Wasser schluckt mit der Entfernung erst Rot, dann Gruen; die Sicht truebt weich ein
	if (isEyeInWater == 1) {
		float dist = far;
		if (depth < 1.0) {
			vec4 vw = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
			dist = length(vw.xyz / vw.w);
		}
		// ruhige Wasserfarbe: etwas entsaettigt und nicht greller als noetig (Nachtsicht)
		vec3 wc = mix(vec3(luma(fogColor)), fogColor, 0.6);
		wc = mix(wc, vec3(0.06, 0.20, 0.26) * (0.3 + 2.0 * luma(fogColor)), 0.35);
		float wl = luma(wc);
		if (wl > 0.40) {
			wc *= 0.40 / wl;
		}
		// mit Nachtsicht etwas weiter sehen (gut ein Drittel), aber nicht glasklar
		float clear = UNDERWATER_FOG * (1.0 - 0.35 * clamp(nightVision, 0.0, 1.0));
		vec3 absorb = exp(-dist * vec3(0.10, 0.04, 0.025) * clear);
		float murk = 1.0 - exp(-dist * 0.05 * clear);
		color = mix(color * absorb, wc, murk);
	}

	// Farbe des Sonnen- bzw. Mondlichts: tagsueber warmweiss, tief stehend golden-orange, nachts kuehl und schwach
	float sunUp = dot(normalize(sunPosition), normalize(upPosition));
	vec3 lightCol = sunUp > 0.0
			? mix(vec3(1.0, 0.52, 0.22), vec3(1.0, 0.90, 0.76), smoothstep(0.0, 0.40, sunUp))
			: vec3(0.35, 0.45, 0.70) * 0.35;
	float lightAmt = (sunUp > 0.0 ? smoothstep(-0.02, 0.10, sunUp) : 0.5 * smoothstep(0.02, 0.15, -sunUp)) * (1.0 - rainStrength * 0.85);

	// Dunst: nur auf Bloecken (nicht am Himmel), ueber Wasser, nimmt mit der Entfernung zu
	if (depth < 1.0 && isEyeInWater == 0 && HAZE > 0.0) {
		vec4 view = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
		float dist = length(view.xyz / view.w);
		// in festen Bloecken statt relativ zur Sichtweite (sonst bei kleiner Sichtweite schon nach wenigen Bloecken dicht)
		float d = clamp(dist / 240.0, 0.0, 1.0);
		float amount = (1.0 - exp(-d * d * 4.0)) * HAZE * (1.0 + rainStrength * 0.6);
		// Dunst fast farblos (sonst faerbt der Abendhimmel alles lila)
		// Dunst in der (leicht entsaettigten) Himmelsfarbe statt als hellgrauer Schleier
		vec3 haze = mix(fogColor, vec3(luma(fogColor)), 0.3);
#if SUN_GLOW > 0
		// in Richtung der Sonne leuchtet der Dunst warm auf (Streulicht), abgewandt bleibt er kuehl
		float toward = max(dot(normalize(view.xyz), normalize(shadowLightPosition)), 0.0);
		haze = mix(haze, lightCol * max(luma(fogColor) * 1.6, 0.25), pow(toward, 6.0) * SUN_GLOW * lightAmt);
#endif
		color = mix(color, haze, clamp(amount, 0.0, 0.85));
	}

#if GODRAYS > 0
	// Lichtstrahlen: vom Bildpunkt Richtung Sonne/Mond nachschauen, wie viel freier Himmel dazwischen liegt
	if (isEyeInWater == 0 && lightAmt > 0.01) {
		vec4 sp = gbufferProjection * vec4(shadowLightPosition, 1.0);
		if (sp.w > 0.0) {
			vec2 sun = sp.xy / sp.w * 0.5 + 0.5;
			vec2 asp = vec2(viewWidth / viewHeight, 1.0);
			float fall = 1.0 - smoothstep(0.0, 0.85, length((texcoord - sun) * asp));
			if (fall > 0.0) {
				vec2 stepv = (sun - texcoord) / float(GODRAYS) * 0.9;
				float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
				vec2 uv = texcoord + stepv * jitter;
				float open = 0.0;
				for (int i = 0; i < GODRAYS; i++) {
					if (uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0) {
						open += texture2D(depthtex0, uv).r >= 1.0 ? 1.0 : 0.0;
					}
					uv += stepv;
				}
				open /= float(GODRAYS);
				color += lightCol * open * fall * fall * GODRAY_STRENGTH * lightAmt;
			}
		}
	}
#endif

	// helle Stellen (weiche Schwelle)
	vec3 bright = vec3(0.0);
#ifdef BLOOM
	float l = luma(color);
	float knee = 0.05;
	float soft = clamp((l - BLOOM_THRESHOLD + knee) / (2.0 * knee), 0.0, 1.0);
	float w = max(soft * soft * knee * 2.0, l - BLOOM_THRESHOLD) / max(l, 0.0001);
	// nur Lichtquellen (Glowstone, Laternen, Lava, Sonne ...) - nicht jede weisse Wand
	float mask = maskV > 0.75 ? 1.0 : 0.0; // nur echte Lichtquellen (Wolken sind mit 0,5 markiert)
	bright = color * clamp(w, 0.0, 1.0) * mask;
#endif

	/*
	const int colortex0Format = RGBA16F;
	const int colortex1Format = RGBA16F;
	const bool colortex1Clear = true;
	const int colortex2Format = R8;
	const bool colortex2Clear = true;
	*/
	/* RENDERTARGETS: 0,1 */
	gl_FragData[0] = vec4(color, 1.0);
	gl_FragData[1] = vec4(bright, 1.0);
}
