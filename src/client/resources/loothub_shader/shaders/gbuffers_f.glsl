#include "/common.glsl"
// Gemeinsamer Fragment-Teil: Textur * Farbe * Licht, Nebel wie Vanilla
// Schalter je Programm: TEXTURED (Textur), LIT (Lichtkarte), SHADED (Sonne mit Richtung + Schatten),
// ENTITY (rot bei Schaden), NOFOG, WATER
uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform vec4 entityColor;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float rainStrength;
uniform int isEyeInWater;
uniform float far;
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 shadowLightPosition;
uniform mat4 gbufferModelViewInverse;
#if defined SHADED && defined SHADOWS
uniform sampler2D shadowtex1;
#endif
#ifdef SKY
uniform int renderStage;
#include "/sky.glsl"
#endif
#ifdef WATER
uniform mat4 gbufferModelView;
uniform mat4 gbufferProjectionInverse;
uniform vec3 skyColor;
uniform float frameTimeCounter;
uniform float viewWidth;
uniform float viewHeight;
uniform sampler2D depthtex1;
#endif

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying float fogDist;
varying float emissive;
#ifdef SHADED
varying vec3 normalView;
varying float plant;
varying vec3 shadowPos;
#endif
#ifdef WATER
varying vec3 viewPos;
varying vec3 worldPos;
varying float isWater;
#endif

vec3 neutral(vec3 c) {
	// Fackel-/Glowstone-Licht ist in Minecraft warm-orange - je nach Einstellung Richtung weiss
	return mix(c, vec3(max(c.r, max(c.g, c.b))), LIGHT_NEUTRAL);
}

// Sonnenstand: 1 = Tag, 0 = Nacht; Abendrot rund um Sonnenauf- und -untergang
float sunHeight() {
	return dot(normalize(sunPosition), normalize(upPosition));
}

#if defined SHADED && defined SHADOWS
float shadowAt(vec3 sp) {
	float edge = length(sp.xy);
	if (edge > 1.0) {
		return 1.0;
	}
	vec3 p = distortShadow(sp) * 0.5 + 0.5;
	if (p.z >= 1.0) {
		return 1.0;
	}
	float texel = SHADOW_SOFT / float(shadowMapResolution);
	float z = p.z - 0.0001;
	float s = step(z, texture2D(shadowtex1, p.xy + vec2(-0.6, -0.4) * texel).r)
			+ step(z, texture2D(shadowtex1, p.xy + vec2(0.4, -0.6) * texel).r)
			+ step(z, texture2D(shadowtex1, p.xy + vec2(0.6, 0.4) * texel).r)
			+ step(z, texture2D(shadowtex1, p.xy + vec2(-0.4, 0.6) * texel).r);
	// zum Rand der Schattenreichweite sanft ausblenden
	return mix(s * 0.25, 1.0, smoothstep(0.8, 1.0, edge));
}
#endif

#ifdef SHADED
// Licht aus drei Teilen: Grundhelligkeit, Fackellicht (Block) und Himmelslicht.
// Nur das Himmelslicht bekommt Sonnenrichtung, Schatten und Tageszeit-Farbe.
vec3 shadedLight(out float litOut) {
	vec2 mn = vec2(0.5 / 16.0);
	vec3 lMin = texture2D(lightmap, mn).rgb;
	vec3 lBlock = texture2D(lightmap, vec2(lmcoord.x, mn.y)).rgb;
	vec3 lSky = texture2D(lightmap, vec2(mn.x, lmcoord.y)).rgb;
	vec3 block = neutral(max(lBlock - lMin, 0.0));
	vec3 sky = max(lSky - lMin, 0.0);

	float h = sunHeight();
	float day = smoothstep(-0.05, 0.20, h);
	float sunset = day * (1.0 - smoothstep(0.05, 0.40, h));
	// Sonne/Mond knapp am Horizont: Schatten weich ausblenden statt endlos lang
	float dir = smoothstep(0.0, 0.12, abs(h)) * (1.0 - rainStrength * 0.85) * mix(0.45, 1.0, day);
#ifndef SHADOWS
	dir *= 0.5;
#endif

	vec3 n = normalize(normalView);
	vec3 nWorld = mat3(gbufferModelViewInverse) * n;
	float lit = plant > 0.5 ? 0.75 : clamp(dot(n, normalize(shadowLightPosition)), 0.0, 1.0);
#ifdef SHADOWS
	if (lit > 0.0 && dir > 0.0) {
		lit *= shadowAt(shadowPos);
	}
#endif
	litOut = lit * dir;

	// Seiten und Unterseiten etwas dunkler (ersetzt die alte Vanilla-Flaechenschattierung)
	float side = 0.80 - 0.08 * abs(nWorld.x);
	float face = plant > 0.5 ? 0.92 : (nWorld.y >= 0.0 ? mix(side, 1.0, nWorld.y) : mix(side, 0.55, -nWorld.y));

	vec3 sunCol = mix(vec3(1.0, 0.97, 0.92), vec3(1.0, 0.70, 0.45), sunset);
	vec3 moonCol = vec3(0.62, 0.74, 1.0);
	vec3 directCol = mix(moonCol, sunCol, day);
	// Schattenseiten am Tag leicht himmelblau, nachts insgesamt kuehl
	vec3 shadeTint = mix(vec3(0.80, 0.88, 1.10), vec3(0.88, 0.94, 1.08), day);
	vec3 skyMul = face * mix(1.0, SHADOW_LEVEL, dir) * mix(vec3(1.0), shadeTint, dir)
			+ directCol * lit * dir * SUN_LIGHT;
	sky *= skyMul * mix(NIGHT_LIGHT, 1.0, day);

	// Fackel- und Himmelslicht zusammen (laeuft weich gegen hell statt einfach zu addieren)
	vec3 both = block + sky - block * min(sky, 1.0);
	return neutral(lMin) * face + both;
}
#endif

void main() {
#if defined SKY && defined CUSTOM_STARS && defined MC_RENDER_STAGE_STARS
	if (renderStage == MC_RENDER_STAGE_STARS) {
		discard; // eigene Sterne statt der Vanilla-Sterne
	}
#endif
	vec4 color = glcolor;
#ifdef TEXTURED
	color *= texture2D(gtexture, texcoord);
#endif
	if (color.a < 0.1) {
		discard;
	}
#ifdef ENTITY
	color.rgb = mix(color.rgb, entityColor.rgb, entityColor.a);
#endif
	float lit = 0.0;
#ifdef LIT
#ifdef SHADED
	color.rgb *= shadedLight(lit);
#else
	color.rgb *= neutral(texture2D(lightmap, lmcoord).rgb);
#endif
#endif

#if defined WATER && defined WATER_FX
	if (isWater > 0.5) {
		vec3 v = normalize(viewPos);
		vec3 n = normalize(normalView);
		vec3 nW = mat3(gbufferModelViewInverse) * n;
		float dist = length(viewPos);
		float t = frameTimeCounter;
		vec3 nFlat = n;
		// in der Ferne wird die Oberflaeche glatt (sonst flimmert sie wie ein Fernseher ohne Empfang)
		float far01 = smoothstep(6.0, 56.0, dist);
		if (nW.y > 0.5 && far01 < 1.0) {
			// Wellen: wenige, eher grosse Wellenzuege; feine Anteile verschwinden mit der Entfernung
			vec2 p = worldPos.xz;
			float dx = 0.0, dz = 0.0;
			vec2 dirs[4];
			dirs[0] = vec2(1.0, 0.25);
			dirs[1] = vec2(-0.45, 1.0);
			dirs[2] = vec2(0.8, -0.7);
			dirs[3] = vec2(-0.9, -0.35);
			float freq = 0.32, amp = 1.0, speed = 0.9;
			for (int i = 0; i < 4; i++) {
				vec2 d = normalize(dirs[i]);
				float ph = dot(d, p) * freq + t * speed;
				// Anteil, der in dieser Entfernung kleiner als ein paar Pixel waere, ausblenden
				float keep = clamp(1.0 - freq * dist / 22.0, 0.0, 1.0);
				float c = cos(ph) * amp * freq * keep;
				dx += d.x * c;
				dz += d.y * c;
				freq *= 1.7;
				amp *= 0.55;
				speed *= 1.25;
			}
			float k = WATER_WAVES * 0.16 * (1.0 - far01);
			n = normalize(mat3(gbufferModelView) * normalize(vec3(-dx * k, 1.0, -dz * k)));
		}
		// Wassertiefe entlang des Blicks (Boden aus depthtex1): flach klar und tuerkis, tief dunkelblau
		vec2 uv = gl_FragCoord.xy / vec2(viewWidth, viewHeight);
		float dBottom = texture2D(depthtex1, uv).r;
		vec4 pb = gbufferProjectionInverse * vec4(uv * 2.0 - 1.0, dBottom * 2.0 - 1.0, 1.0);
		float thick = dBottom >= 1.0 ? 64.0 : max(length(pb.xyz / pb.w) - dist, 0.0);
		float absorb = 1.0 - exp(-thick * 0.22 * WATER_DEPTH);
		vec3 tint = glcolor.rgb; // Biom-Wasserfarbe
		vec3 lightNow = color.rgb / max(texture2D(gtexture, texcoord).rgb * glcolor.rgb, vec3(0.05)); // Licht an dieser Stelle
		float lum = clamp(luma(lightNow), 0.05, 1.5);
		vec3 shallow = mix(tint, vec3(0.35, 0.85, 0.85), 0.35) * 1.05 * lum;
		vec3 deep = tint * vec3(0.18, 0.32, 0.48) * lum;
		vec3 body = mix(shallow, deep, absorb);
		// etwas Textur behalten, aber ruhiger als Vanilla
		body *= mix(1.0, luma(texture2D(gtexture, texcoord).rgb) * 1.6, 0.25 * (1.0 - far01));
		float alpha = mix(0.30, 0.94, absorb);

		// Schaum am Ufer und an flachen Stellen
		float foam = 0.0;
#ifdef WATER_FOAM
		if (nW.y > 0.5 && thick < 0.35 && dBottom < 1.0) {
			float wob = 0.5 + 0.5 * sin(worldPos.x * 3.1 + t * 2.0) * sin(worldPos.z * 2.7 - t * 1.7);
			foam = (1.0 - thick / 0.35) * (0.55 + 0.45 * wob);
		}
#endif

		// Spiegelung: Himmelsverlauf je nach Richtung des gespiegelten Strahls, nur wo Himmel ist
		vec3 r = reflect(v, n);
		// Himmelsverlauf aus der glatten Flaeche (springt sonst bei jeder Welle zwischen Himmel und Horizont)
		float ry = (mat3(gbufferModelViewInverse) * reflect(v, nFlat)).y;
		float h = sunHeight();
		float day = smoothstep(-0.05, 0.2, h);
		float skyLit = clamp((lmcoord.y - 0.5 / 16.0) * 16.0 / 15.0, 0.0, 1.0);
		vec3 refl = mix(fogColor, skyColor * 1.15, smoothstep(0.0, 0.45, ry)) * (0.5 + 0.6 * day) * skyLit * skyLit;
		float facing = clamp(dot(-v, normalize(mix(n, nFlat, 0.5))), 0.0, 1.0);
		float fresnel = 0.02 + 0.98 * pow(1.0 - facing, 5.0);
		vec3 col = mix(body, refl, clamp(fresnel * WATER_REFLECT * 1.15, 0.0, 1.0));

		// Sonnen-/Mondglanz: scharfer Punkt und weicher Schimmer
		float sl = clamp(dot(r, normalize(shadowLightPosition)), 0.0, 1.0);
		// in der Ferne breiter und schwaecher statt einzelner aufblitzender Pixel
		float spec = pow(sl, mix(420.0, 60.0, far01)) * mix(4.0, 0.8, far01) + pow(sl, 40.0) * 0.25;
		vec3 glint = mix(vec3(0.6, 0.7, 0.9) * 0.35, mix(vec3(1.0, 0.95, 0.85), vec3(1.0, 0.62, 0.32), 1.0 - smoothstep(0.05, 0.4, h)), day);
		col += glint * spec * lit * skyLit;

		col = mix(col, vec3(0.92, 0.96, 1.0) * lum, foam * 0.8);
		color.rgb = col;
		color.a = clamp(max(max(mix(alpha, 1.0, fresnel * 0.75), spec * lit), foam * 0.9), 0.0, 1.0);
	}
#endif

#ifdef SKY
#ifdef MC_RENDER_STAGE_SKY
	if (renderStage == MC_RENDER_STAGE_SKY) {
		color.rgb += nightSky(gbufferModelViewInverse, sunPosition, sunHeight(), rainStrength);
	}
#else
	color.rgb += nightSky(gbufferModelViewInverse, sunPosition, sunHeight(), rainStrength);
#endif
#endif

#if DEBUG_VIEW == 2
	// Lichtwerte sichtbar machen: rot = Fackellicht, gruen = Himmelslicht
	color.rgb = vec3(lmcoord, 0.0);
#elif DEBUG_VIEW == 5
	// Sonnenlicht/Schatten sichtbar machen
	color.rgb = vec3(lit);
#endif
#ifndef NOFOG
	// Vanilla-Nebel nur noch unter Wasser/Lava oder bei Blindheit/Dunkelheit; am Rand der Sichtweite blendet
	// stattdessen der Composite-Schritt in den Himmel aus
	if (fogEnd > fogStart && (isEyeInWater != 0 || fogEnd < far * 0.6)) {
		// Nebel erst ganz am Rand der Sichtweite (sonst faerbt der Abendhimmel nahe Waende lila)
		float start = max(fogStart, fogEnd * 0.85);
		float f = clamp((fogDist - start) / (fogEnd - start), 0.0, 1.0);
		color.rgb = mix(color.rgb, fogColor, f);
	}
#endif
#ifdef EMISSIVE_MASK
	/* RENDERTARGETS: 0,2 */
	gl_FragData[0] = color;
#ifdef ALWAYS_GLOW
	gl_FragData[1] = vec4(1.0, 0.0, 0.0, 1.0);
#else
	gl_FragData[1] = vec4(emissive, 0.0, 0.0, 1.0);
#endif
#elif defined CLOUD_MASK
	/* RENDERTARGETS: 0,2 */
	gl_FragData[0] = color;
	gl_FragData[1] = vec4(0.5, 0.0, 0.0, 1.0); // Wolke: nicht in den Himmel ausblenden, nicht gluehen
#else
	/* RENDERTARGETS: 0 */
	gl_FragData[0] = color;
#endif
}
