// ---------------------------------------------------------------- Einstellungen (Iris: Shader-Optionen)
#define EXPOSURE 1.00         // [0.70 0.80 0.90 0.95 1.00 1.05 1.10 1.15 1.20 1.30]
#define CONTRAST 1.05         // [0.85 0.90 0.94 0.98 1.00 1.02 1.04 1.05 1.08 1.10 1.12 1.16 1.20]
#define SATURATION 1.06       // [0.80 0.85 0.90 0.93 0.96 1.00 1.04 1.06 1.10 1.18 1.25 1.35 1.50]
#define WARMTH 0.00           // [-0.10 -0.06 -0.04 -0.02 0.00 0.02 0.04 0.06 0.08 0.12]
#define BLOOM                 // Gluehen an/aus
#define BLOOM_STRENGTH 0.55   // [0.10 0.20 0.30 0.40 0.45 0.55 0.70 0.85 1.00 1.30]
#define BLOOM_THRESHOLD 0.45  // [0.20 0.30 0.45 0.60 0.75 0.90]
#define BLOOM_RADIUS 2.0      // [0.8 1.0 1.3 1.6 2.0 2.5 3.0]
#define VIGNETTE 0.12         // [0.00 0.06 0.12 0.15 0.20 0.28 0.35 0.45 0.60 0.80]
#define HAZE 0.10             // [0.00 0.04 0.08 0.10 0.15 0.25 0.35 0.50 0.70]
#define AIRY 0.00             // [0.00 0.05 0.10 0.15 0.20 0.30]

// Licht (Fassung 9): Sonne mit Richtung, Schatten, kuehle Schattenseiten, warmes Abendlicht
#define SHADOWS               // echte Schatten von Sonne und Mond
#define SUN_LIGHT 0.60        // [0.30 0.40 0.50 0.60 0.70 0.80 0.90 1.00 1.20]
#define SHADOW_LEVEL 0.58     // [0.30 0.35 0.40 0.45 0.50 0.58 0.65 0.72 0.80]
#define SHADOW_SOFT 1.5       // [0.5 1.0 1.5 2.0 3.0]
#define NIGHT_LIGHT 1.00      // [0.60 0.80 1.00 1.20 1.40]
#define SUN_SIZE 0.60         // [0.35 0.45 0.60 0.75 0.90 1.00]
#define WAVING                // wehende Blaetter und Pflanzen
#define WAVE_STRENGTH 1.00    // [0.50 0.75 1.00 1.30 1.60 2.00]
#define WATER_FX              // Wellen, Spiegelung und Sonnenglanz auf Wasser
#define WATER_REFLECT 0.70    // [0.30 0.50 0.70 0.85 1.00]
#define WATER_WAVES 1.00      // [0.50 0.75 1.00 1.50 2.00 2.50]
#define WATER_DEPTH 1.00      // [0.50 0.75 1.00 1.50 2.00]
#define WATER_FOAM            // Schaum am Ufer
#define TEX_SMOOTH            // Texturen in der Ferne glaetten (kein flimmerndes Gitter)
#define ICE_FX                // Eis spiegelt, glaenzt und funkelt
#define ICE_SPARKLE 1.00      // [0.00 0.50 1.00 1.50 2.00]
#define UNDERWATER_FOG 1.00   // [0.50 0.75 1.00 1.30 1.60 2.00]

// Nachthimmel
#define NEBULAS               // farbige Nebel am Nachthimmel
#define NEBULA_STRENGTH 0.80  // [0.30 0.50 0.65 0.80 1.00 1.30 1.60]
#define CUSTOM_STARS          // funkelnde Sterne in leicht unterschiedlichen Farben
#define STAR_AMOUNT 1.00      // [0.50 0.75 1.00 1.50 2.00]

const int shadowMapResolution = 1536;   // [1024 1536 2048 3072 4096]
const float shadowDistance = 80.0;      // [48.0 64.0 80.0 96.0 128.0 160.0]
const float shadowDistanceRenderMul = 1.0;
const float sunPathRotation = -25.0;    // [-40.0 -30.0 -25.0 -15.0 0.0 15.0 25.0]
const bool shadowtex1Nearest = true;

#define LIGHT_NEUTRAL 0.75    // [0.00 0.25 0.50 0.75 0.90 1.00]
#define LIGHT_MODE 0          // [0 1 2 3]
#define DEBUG_VIEW 0          // [0 1 2 3 4 5]
#define PACK_VERSION 10       // [10]

float luma(vec3 c) {
	return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Schattenkarte: in der Naehe feiner aufgeloest als in der Ferne
vec3 distortShadow(vec3 p) {
	float f = length(p.xy) * 0.85 + 0.15;
	return vec3(p.xy / f, p.z * 0.2);
}
