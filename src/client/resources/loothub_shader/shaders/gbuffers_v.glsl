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
	lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	// Manche Iris-Versionen liefern die Lichtwerte roh (0..240) statt 0..1 - dann selbst umrechnen,
	// sonst bekommt jeder Block volles Fackellicht (alles warm, keine Schatten)
	if (max(lmcoord.x, lmcoord.y) > 1.01) {
		lmcoord = (gl_MultiTexCoord1.xy + 8.0) / 256.0;
	}
	lmcoord = clamp(lmcoord, 0.5 / 16.0, 15.5 / 16.0);
	// selbstleuchtende Bloecke (block.properties: 10001) fuer das Gluehen markieren
	emissive = abs(mc_Entity.x - 10001.0) < 0.5 ? 1.0 : 0.0;
	glcolor = gl_Color;
	fogDist = length((gl_ModelViewMatrix * gl_Vertex).xyz);
}
