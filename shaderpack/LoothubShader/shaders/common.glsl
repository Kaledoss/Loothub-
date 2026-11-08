// ---------------------------------------------------------------- Einstellungen (Iris: Shader-Optionen)
#define EXPOSURE 1.00         // [0.70 0.80 0.90 1.00 1.10 1.20 1.30]
#define CONTRAST 1.08         // [0.90 0.95 1.00 1.04 1.08 1.12 1.16 1.20]
#define SATURATION 1.18       // [0.80 0.90 1.00 1.10 1.18 1.25 1.35 1.50]
#define WARMTH 0.04           // [-0.10 -0.06 -0.03 0.00 0.02 0.04 0.06 0.08 0.12]
#define BLOOM                 // Gluehen an/aus
#define BLOOM_STRENGTH 0.55   // [0.10 0.20 0.30 0.40 0.55 0.70 0.85 1.00 1.30]
#define BLOOM_THRESHOLD 0.62  // [0.40 0.50 0.56 0.62 0.70 0.80 0.90]
#define BLOOM_RADIUS 1.6      // [0.8 1.0 1.3 1.6 2.0 2.5 3.0]
#define VIGNETTE 0.35         // [0.00 0.15 0.25 0.35 0.45 0.60 0.80]
#define HAZE 0.25             // [0.00 0.10 0.18 0.25 0.35 0.50 0.70]

float luma(vec3 c) {
	return dot(c, vec3(0.2126, 0.7152, 0.0722));
}
