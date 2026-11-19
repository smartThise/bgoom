#pragma once

#include <array>
#include <cstdint>

namespace bgoom {

struct Point2f {
    float x;
    float y;
};

enum class ScanFilter : int32_t {
    ORIGINAL = 0,
    MAGIC_COLOR = 1,
    SHADOW_FREE_GRAY = 2,
    CRISP_BW = 3,
    HIGH_CONTRAST_COLOR = 4,
};

/**
 * Detect the dominant document quadrilateral from an RGBA_8888 image buffer.
 * Returns normalized [0..1] coordinates ordered as: TL, TR, BR, BL.
 * `found` is set to true if a confident document boundary was detected; otherwise a clean inset fallback is returned.
 */
std::array<Point2f, 4> detectDocumentQuad(
    const uint8_t* rgba,
    int width,
    int height,
    bool* found = nullptr
);

/**
 * Compute recommended output dimensions (preserving aspect ratio, capped to maxSide)
 * for a given normalized quadrilateral on an image of size (srcW, srcH).
 */
void computeOutputSize(
    int srcW,
    int srcH,
    const std::array<Point2f, 4>& normQuad,
    int maxSide,
    int* outW,
    int* outH
);

/**
 * Perspective-rectify the RGBA image according to `normQuad` (TL, TR, BR, BL in [0..1])
 * into `dstRgba` (dstW x dstH x 4) and apply the selected document enhancement filter.
 */
bool warpAndFilterDocument(
    const uint8_t* srcRgba,
    int srcW,
    int srcH,
    uint8_t* dstRgba,
    int dstW,
    int dstH,
    const std::array<Point2f, 4>& normQuad,
    ScanFilter filter
);

/**
 * Apply a document filter in-place on an already-rectified RGBA_8888 buffer.
 */
void applyFilterInPlace(
    uint8_t* rgba,
    int width,
    int height,
    ScanFilter filter
);

} // namespace bgoom
