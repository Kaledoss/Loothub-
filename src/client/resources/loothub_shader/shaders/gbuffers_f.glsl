// Gemeinsamer Fragment-Teil: Textur * Farbe * Licht, Nebel wie Vanilla
// Schalter je Programm: TEXTURED (Textur), LIT (Lichtkarte), ENTITY (rot bei Schaden), NOFOG
uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform vec4 entityColor;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying float fogDist;

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
#ifdef LIT
	color *= texture2D(lightmap, lmcoord);
#endif
#ifndef NOFOG
	if (fogEnd > fogStart) {
		float f = clamp((fogDist - fogStart) / (fogEnd - fogStart), 0.0, 1.0);
		color.rgb = mix(color.rgb, fogColor, f);
	}
#endif
	/* RENDERTARGETS: 0 */
	gl_FragData[0] = color;
}
