#include "portrait_refine.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <queue>
#include <vector>

namespace bgoom {
namespace {

inline uint8_t clampByte(float v) {
    if (v <= 0.0f) return 0;
    if (v >= 255.0f) return 255;
    return static_cast<uint8_t>(v + 0.5f);
}

// Fast O(1)-per-pixel box mean using 64-bit integral image
void boxMean(const std::vector<float>& src, int w, int h, int r, std::vector<float>& dst) {
    const int stride = w + 1;
    std::vector<double> integ(static_cast<size_t>(stride) * (h + 1), 0.0);
    for (int y = 0; y < h; ++y) {
        double rowSum = 0.0;
        const float* sRow = src.data() + static_cast<size_t>(y) * w;
        double* iRow = integ.data() + static_cast<size_t>(y + 1) * stride;
        const double* iPrev = integ.data() + static_cast<size_t>(y) * stride;
        for (int x = 0; x < w; ++x) {
            rowSum += sRow[x];
            iRow[x + 1] = iPrev[x + 1] + rowSum;
        }
    }
    dst.resize(static_cast<size_t>(w) * h);
    for (int y = 0; y < h; ++y) {
        int y0 = std::max(0, y - r);
        int y1 = std::min(h - 1, y + r);
        const double* r0 = integ.data() + static_cast<size_t>(y0) * stride;
        const double* r1 = integ.data() + static_cast<size_t>(y1 + 1) * stride;
        float* dRow = dst.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; ++x) {
            int x0 = std::max(0, x - r);
            int x1 = std::min(w - 1, x + r);
            double sum = r1[x1 + 1] - r1[x0] - r0[x1 + 1] + r0[x0];
            int count = (x1 - x0 + 1) * (y1 - y0 + 1);
            dRow[x] = static_cast<float>(sum / count);
        }
    }
}

void boxSum(const std::vector<float>& src, int w, int h, int r, std::vector<float>& dst) {
    const int stride = w + 1;
    std::vector<double> integ(static_cast<size_t>(stride) * (h + 1), 0.0);
    for (int y = 0; y < h; ++y) {
        double rowSum = 0.0;
        const float* sRow = src.data() + static_cast<size_t>(y) * w;
        double* iRow = integ.data() + static_cast<size_t>(y + 1) * stride;
        const double* iPrev = integ.data() + static_cast<size_t>(y) * stride;
        for (int x = 0; x < w; ++x) {
            rowSum += sRow[x];
            iRow[x + 1] = iPrev[x + 1] + rowSum;
        }
    }
    dst.resize(static_cast<size_t>(w) * h);
    for (int y = 0; y < h; ++y) {
        int y0 = std::max(0, y - r);
        int y1 = std::min(h - 1, y + r);
        const double* r0 = integ.data() + static_cast<size_t>(y0) * stride;
        const double* r1 = integ.data() + static_cast<size_t>(y1 + 1) * stride;
        float* dRow = dst.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; ++x) {
            int x0 = std::max(0, x - r);
            int x1 = std::min(w - 1, x + r);
            dRow[x] = static_cast<float>(r1[x1 + 1] - r1[x0] - r0[x1 + 1] + r0[x0]);
        }
    }
}

// Fast grayscale Guided Filter (He et al. 2013) in O(N)
void guidedFilterGray(
    const std::vector<float>& guide,
    const std::vector<float>& p,
    int w,
    int h,
    int radius,
    float eps,
    std::vector<float>& out
) {
    const size_t n = static_cast<size_t>(w) * h;
    std::vector<float> meanI, meanP, corrI(n), corrIP(n), meanII, meanIP;
    for (size_t i = 0; i < n; ++i) {
        corrI[i] = guide[i] * guide[i];
        corrIP[i] = guide[i] * p[i];
    }
    boxMean(guide, w, h, radius, meanI);
    boxMean(p, w, h, radius, meanP);
    boxMean(corrI, w, h, radius, meanII);
    boxMean(corrIP, w, h, radius, meanIP);

    std::vector<float> a(n), b(n);
    for (size_t i = 0; i < n; ++i) {
        float varI = std::max(0.0f, meanII[i] - meanI[i] * meanI[i]);
        float covIP = meanIP[i] - meanI[i] * meanP[i];
        a[i] = covIP / (varI + eps);
        b[i] = meanP[i] - a[i] * meanI[i];
    }

    std::vector<float> meanA, meanB;
    boxMean(a, w, h, radius, meanA);
    boxMean(b, w, h, radius, meanB);

    out.resize(n);
    for (size_t i = 0; i < n; ++i) {
        out[i] = std::clamp(meanA[i] * guide[i] + meanB[i], 0.0f, 1.0f);
    }
}

// Operate on a 512x512 alpha map to remove disconnected stray blobs and fill interior clothing holes
void cleanupAndFillHoles512(std::vector<float>& alpha, int s) {
    const int n = s * s;
    std::vector<uint8_t> bin(n, 0);
    for (int i = 0; i < n; ++i) {
        if (alpha[i] >= 0.45f) bin[i] = 1;
    }

    // 1. Keep only the largest foreground connected component (+ anything within 8px of it)
    std::vector<int> label(n, 0);
    int curLabel = 0;
    int bestLabel = 0;
    int bestCount = 0;
    std::vector<int> queue;
    queue.reserve(n / 4);

    for (int i = 0; i < n; ++i) {
        if (!bin[i] || label[i] != 0) continue;
        curLabel++;
        queue.clear();
        queue.push_back(i);
        label[i] = curLabel;
        size_t head = 0;
        while (head < queue.size()) {
            int idx = queue[head++];
            int x = idx % s;
            int y = idx / s;
            const int dirs[4] = {-1, 1, -s, s};
            for (int d = 0; d < 4; ++d) {
                int nx = x + (d == 0 ? -1 : (d == 1 ? 1 : 0));
                int ny = y + (d == 2 ? -1 : (d == 3 ? 1 : 0));
                if (nx < 0 || nx >= s || ny < 0 || ny >= s) continue;
                int nidx = ny * s + nx;
                if (bin[nidx] && label[nidx] == 0) {
                    label[nidx] = curLabel;
                    queue.push_back(nidx);
                }
            }
        }
        if (static_cast<int>(queue.size()) > bestCount) {
            bestCount = static_cast<int>(queue.size());
            bestLabel = curLabel;
        }
    }

    if (bestLabel == 0 || bestCount < 200) return;

    // Dilate main component by 10px so fine hair wisps adjacent to the head are preserved
    std::vector<float> mainMask(n, 0.0f);
    for (int i = 0; i < n; ++i) {
        if (label[i] == bestLabel) mainMask[i] = 1.0f;
    }
    std::vector<float> dilated;
    boxSum(mainMask, s, s, 10, dilated);
    for (int i = 0; i < n; ++i) {
        if (dilated[i] <= 0.5f) {
            alpha[i] = 0.0f;
        }
    }

    // 2. Interior hole fill (HivisionIDPhotos hollow_out_fix):
    // Flood-fill outside background starting from padded border over pixels with alpha < 0.5
    const int ps = s + 2;
    std::vector<uint8_t> outside(ps * ps, 0);
    queue.clear();
    queue.push_back(0);
    outside[0] = 1;
    size_t head = 0;
    while (head < queue.size()) {
        int idx = queue[head++];
        int px = idx % ps;
        int py = idx / ps;
        const int dx[4] = {-1, 1, 0, 0};
        const int dy[4] = {0, 0, -1, 1};
        for (int d = 0; d < 4; ++d) {
            int nx = px + dx[d];
            int ny = py + dy[d];
            if (nx < 0 || nx >= ps || ny < 0 || ny >= ps) continue;
            int nidx = ny * ps + nx;
            if (outside[nidx]) continue;
            bool isBorder = (nx == 0 || ny == 0 || nx == ps - 1 || ny == ps - 1);
            bool isBg = isBorder || (alpha[(ny - 1) * s + (nx - 1)] < 0.50f);
            if (isBg) {
                outside[nidx] = 1;
                queue.push_back(nidx);
            }
        }
    }

    // Any pixel NOT reachable from border is an interior hole inside clothes/face -> restore alpha = 1.0
    for (int y = 0; y < s; ++y) {
        for (int x = 0; x < s; ++x) {
            if (!outside[(y + 1) * ps + (x + 1)]) {
                alpha[y * s + x] = std::max(alpha[y * s + x], 0.99f);
            }
        }
    }
}

} // namespace

PortraitBbox refinePortraitMatteInPlace(
    uint8_t* rgba,
    int width,
    int height,
    const float* alpha512,
    int alphaSize,
    bool studioLighting
) {
    PortraitBbox bbox{0.08f, 0.98f, 0.1f, 0.9f, 0.5f};
    if (!rgba || !alpha512 || width < 16 || height < 16 || alphaSize < 32) {
        return bbox;
    }

    // 1. Clean up stray blobs & fill interior holes at 512x512 resolution (<4ms)
    std::vector<float> clean512(alpha512, alpha512 + static_cast<size_t>(alphaSize) * alphaSize);
    for (auto& v : clean512) v = std::clamp(v, 0.0f, 1.0f);
    cleanupAndFillHoles512(clean512, alphaSize);

    // 2. Bilinearly upsample cleaned alpha to full portrait size (w x h) + build luminance guide
    const size_t n = static_cast<size_t>(width) * height;
    std::vector<float> alphaFull(n);
    std::vector<float> guide(n);

    for (int y = 0; y < height; ++y) {
        float ay = (static_cast<float>(y) + 0.5f) * alphaSize / height - 0.5f;
        ay = std::clamp(ay, 0.0f, static_cast<float>(alphaSize - 1));
        int y0 = static_cast<int>(ay);
        int y1 = std::min(alphaSize - 1, y0 + 1);
        float fy = ay - y0;

        for (int x = 0; x < width; ++x) {
            float ax = (static_cast<float>(x) + 0.5f) * alphaSize / width - 0.5f;
            ax = std::clamp(ax, 0.0f, static_cast<float>(alphaSize - 1));
            int x0 = static_cast<int>(ax);
            int x1 = std::min(alphaSize - 1, x0 + 1);
            float fx = ax - x0;

            float a00 = clean512[y0 * alphaSize + x0];
            float a10 = clean512[y0 * alphaSize + x1];
            float a01 = clean512[y1 * alphaSize + x0];
            float a11 = clean512[y1 * alphaSize + x1];
            size_t idx = static_cast<size_t>(y) * width + x;
            alphaFull[idx] = a00 * (1.0f - fx) * (1.0f - fy) +
                             a10 * fx * (1.0f - fy) +
                             a01 * (1.0f - fx) * fy +
                             a11 * fx * fy;

            const uint8_t* p = rgba + idx * 4;
            guide[idx] = (0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]) / 255.0f;
        }
    }

    // 3. Guided Filter hair/shoulder edge sharpening (radius proportional to image resolution)
    const int gfRadius = std::clamp(std::max(width, height) / 220, 3, 7);
    std::vector<float> refinedAlpha;
    guidedFilterGray(guide, alphaFull, width, height, gfRadius, 0.0025f, refinedAlpha);

    // Blend guided edge with core interior so solid body interior stays 100% opaque
    for (size_t i = 0; i < n; ++i) {
        float core = alphaFull[i];
        float a = (core > 0.92f) ? std::max(core, refinedAlpha[i])
                : (core < 0.03f) ? 0.0f
                : refinedAlpha[i];
        // Clean mild S-curve on alpha extremes to prevent foggy translucent ghost edges
        if (a < 0.03f) a = 0.0f;
        else if (a > 0.97f) a = 1.0f;
        else a = (a - 0.03f) / 0.94f;
        refinedAlpha[i] = a;
    }

    // 4. Foreground Color Decontamination on semi-transparent hair pixels (0.02 < alpha < 0.96)
    // Computes weighted average RGB of nearby opaque foreground (alpha >= 0.92) in O(N)
    std::vector<float> wMask(n), wR(n), wG(n), wB(n);
    for (size_t i = 0; i < n; ++i) {
        float a = refinedAlpha[i];
        float w = (a >= 0.90f) ? (a * a) : 0.0f;
        wMask[i] = w;
        wR[i] = w * rgba[4 * i + 0];
        wG[i] = w * rgba[4 * i + 1];
        wB[i] = w * rgba[4 * i + 2];
    }
    const int deconRadius = std::clamp(std::max(width, height) / 140, 5, 11);
    std::vector<float> sumW, sumR, sumG, sumB;
    boxSum(wMask, width, height, deconRadius, sumW);
    boxSum(wR, width, height, deconRadius, sumR);
    boxSum(wG, width, height, deconRadius, sumG);
    boxSum(wB, width, height, deconRadius, sumB);

    // Optional subtle studio exposure polish computed strictly over foreground skin/clothing luminance
    float fgLumaSum = 0.0f;
    size_t fgPixels = 0;
    if (studioLighting) {
        for (size_t i = 0; i < n; ++i) {
            if (refinedAlpha[i] > 0.85f) {
                fgLumaSum += guide[i];
                fgPixels++;
            }
        }
    }
    float fgMeanLuma = (fgPixels > 100) ? (fgLumaSum / static_cast<float>(fgPixels)) : 0.55f;
    // Gently lift underexposed portraits toward clean studio brightness (~0.56) without blowing highlights
    float gain = studioLighting ? std::clamp(0.56f / std::max(0.28f, fgMeanLuma), 0.96f, 1.24f) : 1.0f;

    int minX = width, maxX = 0, minY = height, maxY = 0;
    double sumX = 0.0;
    size_t solidCount = 0;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            size_t i = static_cast<size_t>(y) * width + x;
            float a = refinedAlpha[i];
            float r = rgba[4 * i + 0];
            float g = rgba[4 * i + 1];
            float b = rgba[4 * i + 2];

            // Replace contaminated halo color on semi-transparent boundary pixels with nearby clean foreground RGB
            if (a > 0.01f && a < 0.94f && sumW[i] > 1.5f) {
                float fgR = sumR[i] / sumW[i];
                float fgG = sumG[i] / sumW[i];
                float fgB = sumB[i] / sumW[i];
                // Stronger decontamination as alpha gets lower (where background bleed is highest)
                float blend = std::clamp(1.0f - a * 0.88f, 0.0f, 0.92f);
                r = r * (1.0f - blend) + fgR * blend;
                g = g * (1.0f - blend) + fgG * blend;
                b = b * (1.0f - blend) + fgB * blend;
            }

            if (studioLighting && a > 0.01f) {
                // Soft filmic shoulder to lift shadows while protecting bright highlights
                auto lift = [gain](float v) -> float {
                    float u = v / 255.0f;
                    float lifted = u * gain;
                    // Smooth highlight roll-off above 0.82
                    if (lifted > 0.82f) {
                        lifted = 0.82f + (lifted - 0.82f) / (1.0f + (lifted - 0.82f) * 2.2f);
                    }
                    // Gentle contrast crispness
                    float sCurve = lifted + 0.04f * std::sin(lifted * 3.1415926f * 2.0f - 3.1415926f);
                    return std::clamp(sCurve, 0.0f, 1.0f) * 255.0f;
                };
                r = lift(r);
                g = lift(g);
                b = lift(b);
            }

            // Android ARGB_8888 Bitmap memory (via AndroidBitmap_lockPixels) uses premultiplied alpha.
            // Premultiply cleaned foreground RGB by alpha so Skia SRC_OVER composites cleanly onto any ID background.
            uint8_t aByte = clampByte(a * 255.0f);
            float aNorm = static_cast<float>(aByte) / 255.0f;
            rgba[4 * i + 0] = clampByte(r * aNorm);
            rgba[4 * i + 1] = clampByte(g * aNorm);
            rgba[4 * i + 2] = clampByte(b * aNorm);
            rgba[4 * i + 3] = aByte;

            if (a > 0.55f) {
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                sumX += x;
                solidCount++;
            }
        }
    }

    if (solidCount > 100) {
        bbox.topRatio = static_cast<float>(minY) / height;
        bbox.bottomRatio = static_cast<float>(maxY) / height;
        bbox.leftRatio = static_cast<float>(minX) / width;
        bbox.rightRatio = static_cast<float>(maxX) / width;
        bbox.centerXRatio = static_cast<float>(sumX / solidCount) / width;
    }
    return bbox;
}

} // namespace bgoom
