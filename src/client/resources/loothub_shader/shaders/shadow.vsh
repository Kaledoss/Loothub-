#version 120
// Schattenkarte: Welt aus Sicht der Sonne (Pflanzen wehen hier genauso mit)
#include "/common.glsl"
#include "/wave.glsl"
varying vec2 texcoord;
varying vec4 glcolor;
attribute vec4 mc_Entity;
attribute vec4 mc_midTexCoord;
uniform mat4 shadowModelView;
uniform mat4 shadowModelViewInverse;
uniform vec3 cameraPosition;
uniform float rainStrength;

void main() {
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	glcolor = gl_Color;
	vec4 player = shadowModelViewInverse * (gl_ModelViewMatrix * gl_Vertex);
	player.xyz += waveOffset(mc_Entity.x, player.xyz + cameraPosition, texcoord.y < mc_midTexCoord.y, rainStrength);
	gl_Position = gl_ProjectionMatrix * (shadowModelView * player);
	gl_Position.xyz = distortShadow(gl_Position.xyz);
}
