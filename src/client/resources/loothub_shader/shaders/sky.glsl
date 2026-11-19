// Nachthimmel: farbige Nebel und funkelnde, leicht unterschiedlich farbige Sterne.
// Alles wird zur Laufzeit berechnet und dreht sich mit Sonne und Mond mit (am Himmel fest).
uniform mat4 gbufferProjectionInverse;
uniform float viewWidth;
uniform float viewHeight;
uniform float frameTimeCounter;

float hash13(vec3 p) {
	p = fract(p * 0.1031);
	p += dot(p, p.zyx + 31.32);
	return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
	p = fract(p * vec3(0.1031, 0.1030, 0.0973));
	p += dot(p, p.yxz + 33.33);
	return fract((p.xxy + p.yxx) * p.zyx);
}

float vnoise(vec3 p) {
	vec3 i = floor(p);
	vec3 f = fract(p);
	f = f * f * (3.0 - 2.0 * f);
	float a = mix(mix(hash13(i), hash13(i + vec3(1, 0, 0)), f.x), mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), f.x), f.y);
	float b = mix(mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), f.x), mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), f.x), f.y);
	return mix(a, b, f.z);
}

float fbm(vec3 p) {
	float v = 0.0;
	float amp = 0.5;
	for (int i = 0; i < 5; i++) {
		v += vnoise(p) * amp;
		p = p * 2.03 + vec3(1.7, 9.2, 3.1);
		amp *= 0.5;
	}
	return v;
}

// Blickrichtung (Welt) → Himmelskoordinaten, die mit der Sonnenbahn mitdrehen
vec3 skyCoords(vec3 dir, vec3 sunWorld) {
	float r = radians(sunPathRotation);
	// Drehachse der Sonnenbahn; Vorzeichen so, dass die Sonne immer senkrecht zur Achse steht
	vec3 a1 = vec3(0.0, sin(r), cos(r));
	vec3 a2 = vec3(0.0, -sin(r), cos(r));
	vec3 axis = abs(dot(sunWorld, a1)) < abs(dot(sunWorld, a2)) ? a1 : a2;
	vec3 e1 = normalize(sunWorld - axis * dot(sunWorld, axis));
	vec3 e2 = cross(axis, e1);
	return vec3(dot(dir, axis), dot(dir, e1), dot(dir, e2));
}

vec3 nebula(vec3 q) {
	vec3 p = q * 1.6;
	// verwirbelt: Nebel wirken wie Rauch statt wie Flecken
	vec3 warp = vec3(fbm(p + 3.1), fbm(p + 7.7), fbm(p + 1.3));
	float n = fbm(p * 1.4 + warp * 1.8);
	float detail = fbm(p * 4.0 + warp);
	// dichter entlang eines breiten Bands quer ueber den Himmel (wie die Milchstrasse)
	float band = exp(-pow(dot(q, normalize(vec3(0.35, 0.25, 1.0))) * 2.2, 2.0));
	float density = smoothstep(0.42, 0.85, n) * (0.35 + 0.65 * band);
	vec3 deep = vec3(0.10, 0.08, 0.38);
	vec3 violet = vec3(0.45, 0.14, 0.55);
	vec3 rose = vec3(0.85, 0.30, 0.55);
	vec3 col = mix(deep, violet, smoothstep(0.3, 0.7, detail));
	col = mix(col, rose, smoothstep(0.62, 0.85, n) * 0.6);
	return col * density * (0.6 + 0.6 * detail);
}

vec3 stars(vec3 q, float t) {
	vec3 sum = vec3(0.0);
	// zwei Ebenen: viele kleine und wenige helle Sterne
	for (int layer = 0; layer < 2; layer++) {
		float scale = layer == 0 ? 170.0 : 70.0;
		float chance = layer == 0 ? 0.03 : 0.012;
		vec3 p = q * scale;
		vec3 cell = floor(p);
		vec3 h = hash33(cell + float(layer) * 17.0);
		if (h.x < chance * STAR_AMOUNT) {
			vec3 center = cell + 0.25 + 0.5 * hash33(cell + 5.0);
			float d = length(p - center);
			float size = layer == 0 ? 0.16 : 0.22;
			float b = smoothstep(size, 0.0, d);
			// funkeln: jeder Stern in seinem eigenen Takt
			float tw = 0.65 + 0.35 * sin(t * (1.5 + h.y * 3.0) + h.z * 40.0);
			// Farbe: blaeulich, weiss oder warm
			vec3 col = h.y < 0.33 ? vec3(0.75, 0.85, 1.0) : (h.y < 0.75 ? vec3(1.0) : vec3(1.0, 0.85, 0.65));
			sum += col * b * tw * (layer == 0 ? 0.9 : 1.6);
		}
	}
	return sum;
}

// Blickrichtung dieses Bildpunkts in Weltrichtung
vec3 skyDir(mat4 modelViewInverse) {
	vec3 ndc = vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), 1.0) * 2.0 - 1.0;
	vec4 v = gbufferProjectionInverse * vec4(ndc, 1.0);
	return normalize(mat3(modelViewInverse) * (v.xyz / v.w));
}

// Nachthimmel (Nebel + Sterne) fuer eine Blickrichtung; 0 am Tag. Laeuft auch unter dem Horizont weiter,
// damit Bereiche ohne geladene Chunks denselben Himmel zeigen.
vec3 nightSkyDir(vec3 dir, vec3 sunWorld, float sunH, float rain) {
	float night = 1.0 - smoothstep(-0.25, 0.05, sunH);
	if (night <= 0.0) {
		return vec3(0.0);
	}
	vec3 q = skyCoords(dir, sunWorld);
	float fade = night * (1.0 - rain * 0.9);
	vec3 c = vec3(0.0);
#ifdef NEBULAS
	c += nebula(q) * NEBULA_STRENGTH;
#endif
#ifdef CUSTOM_STARS
	c += stars(q, frameTimeCounter);
#endif
	return c * fade;
}

vec3 nightSky(mat4 modelViewInverse, vec3 sunView, float sunH, float rain) {
	return nightSkyDir(skyDir(modelViewInverse), normalize(mat3(modelViewInverse) * sunView), sunH, rain);
}
