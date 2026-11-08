// Gaussscher Weichzeichner in eine Richtung (13 Abtastpunkte)
uniform sampler2D colortex1;
uniform float viewWidth;
uniform float viewHeight;
varying vec2 texcoord;

vec3 blur(vec2 dir) {
	vec2 px = dir / vec2(viewWidth, viewHeight) * BLOOM_RADIUS * (viewHeight / 1080.0) * 2.0;
	float weights[7];
	weights[0] = 0.1964825501511404;
	weights[1] = 0.2969069646728344;
	weights[2] = 0.09447039785044732;
	weights[3] = 0.010381362401148057;
	weights[4] = 0.06;
	weights[5] = 0.03;
	weights[6] = 0.012;
	vec3 sum = texture2D(colortex1, texcoord).rgb * weights[0];
	float total = weights[0];
	for (int i = 1; i < 7; i++) {
		float o = float(i) * 1.5;
		sum += texture2D(colortex1, texcoord + px * o).rgb * weights[i];
		sum += texture2D(colortex1, texcoord - px * o).rgb * weights[i];
		total += 2.0 * weights[i];
	}
	return sum / total;
}
