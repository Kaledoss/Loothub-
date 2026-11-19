#include "/common.glsl"
#include "/wave.glsl"
// Gemeinsamer Vertex-Teil fuer alle gbuffers-Programme
// Schalter: SHADED (eigenes Sonnenlicht mit Richtung/Schatten), WAVING_PROGRAM (Pflanzen bewegen), WATER
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
attribute vec4 mc_Entity;
attribute vec4 mc_midTexCoord;
uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform vec3 cameraPosition;
uniform float rainStrength;
#if defined SHADED && defined SHADOWS
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
#endif

void main() {
	gl_Position = ftransform();
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	// Lichtwerte: 0 = automatisch, 1 = ueber Texturmatrix, 2 = roh/256, 3 = aus (volles Tageslicht, zum Testen)
	vec2 viaMatrix = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	vec2 raw = (gl_MultiTexCoord1.xy + 8.0) / 256.0;
#if LIGHT_MODE == 1
	lmcoord = viaMatrix;
#elif LIGHT_MODE == 2
	lmcoord = raw;
#elif LIGHT_MODE == 3
	lmcoord = vec2(0.5 / 16.0, 15.5 / 16.0);
#else
	lmcoord = max(viaMatrix.x, viaMatrix.y) > 1.01 ? raw : viaMatrix;
#endif
	lmcoord = clamp(lmcoord, 0.5 / 16.0, 15.5 / 16.0);
	float id = mc_Entity.x;
	// selbstleuchtende Bloecke (block.properties: 10001) fuer das Gluehen markieren
	emissive = abs(id - 10001.0) < 0.5 ? 1.0 : 0.0;
	glcolor = gl_Color;

	vec4 view = gl_ModelViewMatrix * gl_Vertex;
	vec3 player = (gbufferModelViewInverse * view).xyz;
#ifdef WAVING_PROGRAM
	vec3 wave = waveOffset(id, player + cameraPosition, texcoord.y < mc_midTexCoord.y, rainStrength);
	if (dot(wave, wave) > 0.0) {
		player += wave;
		view = gbufferModelView * vec4(player, 1.0);
		gl_Position = gl_ProjectionMatrix * view;
	}
#endif
	fogDist = length(view.xyz);

#ifdef SHADED
	normalView = normalize(gl_NormalMatrix * gl_Normal);
	plant = isPlant(id) ? 1.0 : 0.0;
	shadowPos = vec3(0.0);
#ifdef SHADOWS
	// etwas entlang der Flaeche verschieben, damit Flaechen sich nicht selbst beschatten
	vec3 n = plant > 0.5 ? vec3(0.0, 1.0, 0.0) : mat3(gbufferModelViewInverse) * normalView;
	vec3 p = player + n * (0.03 + length(player) * 0.0035);
	shadowPos = (shadowProjection * (shadowModelView * vec4(p, 1.0))).xyz;
#endif
#endif
#ifdef WATER
	viewPos = view.xyz;
	worldPos = player + cameraPosition;
	isWater = abs(id - 10010.0) < 0.5 ? 1.0 : 0.0;
#endif
}
