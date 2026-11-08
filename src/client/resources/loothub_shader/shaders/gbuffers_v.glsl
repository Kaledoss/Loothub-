#include "/common.glsl"
// Gemeinsamer Vertex-Teil fuer alle gbuffers-Programme (wie Vanilla)
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying float fogDist;
varying float emissive;
attribute vec4 mc_Entity;

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
	// selbstleuchtende Bloecke (block.properties: 10001) fuer das Gluehen markieren
	emissive = abs(mc_Entity.x - 10001.0) < 0.5 ? 1.0 : 0.0;
	glcolor = gl_Color;
	fogDist = length((gl_ModelViewMatrix * gl_Vertex).xyz);
}
