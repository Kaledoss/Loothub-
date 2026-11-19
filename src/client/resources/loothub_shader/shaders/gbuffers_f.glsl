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
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 shadowLightPosition;
uniform mat4 gbufferModelViewInverse;
#if defined SHADED && defined SHADOWS
uniform sampler2D shadowtex1;
#endif
#ifdef WATER
uniform mat4 gbufferModelView;
uniform vec3 skyColor;
uniform float frameTimeCounter;
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
		vec3 nW = mat3(gbufferModelViewInverse) * normalize(normalView);
		vec3 v = normalize(viewPos);
		vec3 n = normalize(normalView);
		if (nW.y > 0.5) {
			// kleine Wellen nur auf der Oberflaeche (Normale wird gekippt, keine Geometrie)
			float t = frameTimeCounter;
			vec2 p = worldPos.xz;
			float dx = cos(p.x * 1.3 + t * 1.6) * 0.6 + cos(p.x * 2.7 + p.y * 1.1 + t * 2.3) * 0.3 + cos((p.x + p.y) * 4.1 - t * 2.9) * 0.15;
			float dz = cos(p.y * 1.1 - t * 1.4) * 0.6 + cos(p.y * 2.9 - p.x * 0.9 + t * 2.1) * 0.3 + cos((p.y - p.x) * 3.7 + t * 3.1) * 0.15;
			vec3 waveN = normalize(vec3(-dx * 0.06, 1.0, -dz * 0.06));
			n = normalize(mat3(gbufferModelView) * waveN);
		}
		float facing = clamp(dot(-v, n), 0.0, 1.0);
		float fresnel = 0.02 + 0.98 * pow(1.0 - facing, 5.0);
		// Spiegelung: Himmelsfarbe, aber nur wo Himmel ist (nicht in Hoehlen)
		float skyLit = clamp((lmcoord.y - 0.5 / 16.0) * 16.0 / 15.0, 0.0, 1.0);
		vec3 refl = skyColor * (0.6 + 0.6 * smoothstep(-0.05, 0.2, sunHeight())) * skyLit * skyLit;
		color.rgb = mix(color.rgb, refl, fresnel * WATER_REFLECT);
		color.a = mix(color.a * 0.85, 1.0, fresnel * 0.7);
		// Sonnenglanz
		float spec = pow(clamp(dot(reflect(v, n), normalize(shadowLightPosition)), 0.0, 1.0), 180.0);
		float h = sunHeight();
		vec3 glint = mix(vec3(0.6, 0.7, 0.9) * 0.3, mix(vec3(1.0, 0.95, 0.85), vec3(1.0, 0.65, 0.35), 1.0 - smoothstep(0.05, 0.4, h)), smoothstep(-0.05, 0.2, h));
		color.rgb += glint * spec * 2.5 * lit;
		color.a = max(color.a, min(spec * lit * 2.0, 1.0));
	}
#endif

#if DEBUG_VIEW == 2
	// Lichtwerte sichtbar machen: rot = Fackellicht, gruen = Himmelslicht
	color.rgb = vec3(lmcoord, 0.0);
#elif DEBUG_VIEW == 5
	// Sonnenlicht/Schatten sichtbar machen
	color.rgb = vec3(lit);
#endif
#ifndef NOFOG
	if (fogEnd > fogStart) {
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
#else
	/* RENDERTARGETS: 0 */
	gl_FragData[0] = color;
#endif
}
