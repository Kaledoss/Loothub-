// Wehende Pflanzen (block.properties): 10002 = nur obere Kante bewegt sich (Gras, Blumen),
// 10004 = ganzer Block (obere Haelfte hoher Pflanzen), 10003 = Blaetter und Ranken leicht
uniform float frameTimeCounter;

bool isPlant(float id) {
	return abs(id - 10002.0) < 0.5 || abs(id - 10004.0) < 0.5;
}

vec3 waveOffset(float id, vec3 world, bool top, float rain) {
#ifdef WAVING
	float t = frameTimeCounter;
	float strength = WAVE_STRENGTH * (1.0 + rain * 0.8);
	if ((abs(id - 10002.0) < 0.5 && top) || abs(id - 10004.0) < 0.5) {
		float a = sin(t * 1.7 + world.x * 0.6 + world.z * 0.4) + 0.5 * sin(t * 2.9 + world.x * 1.3 - world.z * 0.9);
		float b = cos(t * 1.3 + world.z * 0.7 - world.x * 0.3) + 0.5 * sin(t * 2.3 + world.z * 1.1);
		return vec3(a, 0.0, b) * 0.045 * strength;
	}
	if (abs(id - 10003.0) < 0.5) {
		float a = sin(t * 1.9 + world.x * 0.8 + world.y * 0.6 + world.z * 0.5);
		float b = sin(t * 2.3 + world.z * 0.9 - world.y * 0.7 + world.x * 0.3);
		float c = sin(t * 1.6 + world.x * 0.5 + world.z * 0.8);
		return vec3(a, c * 0.5, b) * 0.022 * strength;
	}
#endif
	return vec3(0.0);
}
