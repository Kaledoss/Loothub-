#version 120
// Schritt 3: Gluehen senkrecht weichzeichnen
#include "/common.glsl"
#include "/blur.glsl"

void main() {
	/* RENDERTARGETS: 1 */
#ifdef BLOOM
	gl_FragData[0] = vec4(blur(vec2(0.0, 1.0)), 1.0);
#else
	gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0);
#endif
}
