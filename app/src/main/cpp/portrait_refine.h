#pragma once

#include <cstdint>

namespace bgoom {

struct PortraitBbox {
    float topRatio;
    float bottomRatio;
    float leftRatio;
    float rightRatio;
    float centerXRatio;
};

/**
 * Studio-grade portrait alpha matte refinement (zero OpenCV, <25ms on 1200x1600):
 * 1. Bilinear upsample of 512x512 MODNet matte to full portrait resolution
 * 2. Keep largest foreground connected component (removes stray background blobs)
 * 3. Interior hole filling (HivisionIDPhotos hollow_out_fix: fixes white shirt/collar dropouts)
 * 4. Fast O(1) Guided Filter guided by full-res portrait luminance (snaps alpha to hair strands)
 * 5. Foreground color decontamination on semi-transparent hair pixels (eliminates background halos)
 * 6. Optional subtle studio lighting polish
 *
 * Writes straight ARGB/RGBA output into `rgba` in-place (RGB cleaned, A = refined matte).
 */
PortraitBbox refinePortraitMatteInPlace(
    uint8_t* rgba,
    int width,
    int height,
    const float* alpha512,
    int alphaSize,
    bool studioLighting
);

} // namespace bgoom
