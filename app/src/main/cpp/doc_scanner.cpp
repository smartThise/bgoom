#include "doc_scanner.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <numeric>
#include <queue>
#include <vector>

namespace bgoom {
namespace {

inline uint8_t clampByte(float v) {
    if (v <= 0.0f) return 0;
    if (v >= 255.0f) return 255;
    return static_cast<uint8_t>(v + 0.5f);
}

inline int clampInt(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

float cross(const Point2f& o, const Point2f& a, const Point2f& b) {
    return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
}

float dist2(const Point2f& a, const Point2f& b) {
    float dx = a.x - b.x;
    float dy = a.y - b.y;
    return dx * dx + dy * dy;
}

float dist(const Point2f& a, const Point2f& b) {
    return std::sqrt(dist2(a, b));
}

float polygonArea(const std::vector<Point2f>& poly) {
    if (poly.size() < 3) return 0.0f;
    double area = 0.0;
    for (size_t i = 0; i < poly.size(); ++i) {
        const auto& p1 = poly[i];
        const auto& p2 = poly[(i + 1) % poly.size()];
        area += static_cast<double>(p1.x) * p2.y - static_cast<double>(p2.x) * p1.y;
    }
    return static_cast<float>(std::abs(area) * 0.5);
}

std::array<Point2f, 4> orderQuad(const std::array<Point2f, 4>& pts) {
    std::array<Point2f, 4> out{};
    int tl = 0, br = 0, tr = 0, bl = 0;
    float minSum = std::numeric_limits<float>::max();
    float maxSum = -std::numeric_limits<float>::max();
    float minDiff = std::numeric_limits<float>::max();
    float maxDiff = -std::numeric_limits<float>::max();

    for (int i = 0; i < 4; ++i) {
        float s = pts[i].x + pts[i].y;
        float d = pts[i].y - pts[i].x;
        if (s < minSum) { minSum = s; tl = i; }
        if (s > maxSum) { maxSum = s; br = i; }
        if (d < minDiff) { minDiff = d; tr = i; }
        if (d > maxDiff) { maxDiff = d; bl = i; }
    }

    // Fallback to angle sort around centroid if indices collided
    if (tl == tr || tl == bl || tl == br || tr == bl || tr == br || bl == br) {
        float cx = 0.25f * (pts[0].x + pts[1].x + pts[2].x + pts[3].x);
        float cy = 0.25f * (pts[0].y + pts[1].y + pts[2].y + pts[3].y);
        std::array<int, 4> idx = {0, 1, 2, 3};
        std::sort(idx.begin(), idx.end(), [&](int a, int b) {
            return std::atan2(pts[a].y - cy, pts[a].x - cx) <
                   std::atan2(pts[b].y - cy, pts[b].x - cx);
        });
        // Find top-left among sorted clockwise points
        int best = 0;
        float bestScore = std::numeric_limits<float>::max();
        for (int i = 0; i < 4; ++i) {
            float sc = pts[idx[i]].x + pts[idx[i]].y;
            if (sc < bestScore) { bestScore = sc; best = i; }
        }
        for (int i = 0; i < 4; ++i) {
            out[i] = pts[idx[(best + i) % 4]];
        }
        return out;
    }

    out[0] = pts[tl];
    out[1] = pts[tr];
    out[2] = pts[br];
    out[3] = pts[bl];
    return out;
}

std::vector<Point2f> convexHull(std::vector<Point2f> pts) {
    if (pts.size() <= 3) return pts;
    std::sort(pts.begin(), pts.end(), [](const Point2f& a, const Point2f& b) {
        return a.x < b.x || (a.x == b.x && a.y < b.y);
    });
    std::vector<Point2f> lower, upper;
    for (const auto& p : pts) {
        while (lower.size() >= 2 && cross(lower[lower.size() - 2], lower.back(), p) <= 0.0f) {
            lower.pop_back();
        }
        lower.push_back(p);
    }
    for (int i = static_cast<int>(pts.size()) - 1; i >= 0; --i) {
        const auto& p = pts[i];
        while (upper.size() >= 2 && cross(upper[upper.size() - 2], upper.back(), p) <= 0.0f) {
            upper.pop_back();
        }
        upper.push_back(p);
    }
    lower.pop_back();
    upper.pop_back();
    lower.insert(lower.end(), upper.begin(), upper.end());
    return lower;
}

// Simplify convex hull to 4 corners by iteratively dropping the vertex with minimum triangle area loss
std::array<Point2f, 4> simplifyHullToQuad(std::vector<Point2f> hull) {
    while (hull.size() > 4) {
        size_t n = hull.size();
        size_t removeIdx = 0;
        float minArea = std::numeric_limits<float>::max();
        for (size_t i = 0; i < n; ++i) {
            const auto& prev = hull[(i + n - 1) % n];
            const auto& curr = hull[i];
            const auto& next = hull[(i + 1) % n];
            float tri = std::abs(cross(prev, curr, next)) * 0.5f;
            if (tri < minArea) {
                minArea = tri;
                removeIdx = i;
            }
        }
        hull.erase(hull.begin() + static_cast<std::ptrdiff_t>(removeIdx));
    }
    std::array<Point2f, 4> quad = {hull[0], hull[1], hull[2], hull[3]};
    return orderQuad(quad);
}

bool solveLinear8x8(double A[8][8], double b[8], double x[8]) {
    for (int col = 0; col < 8; ++col) {
        int pivot = col;
        double maxAbs = std::abs(A[col][col]);
        for (int row = col + 1; row < 8; ++row) {
            double v = std::abs(A[row][col]);
            if (v > maxAbs) {
                maxAbs = v;
                pivot = row;
            }
        }
        if (maxAbs < 1e-12) return false;
        if (pivot != col) {
            for (int j = col; j < 8; ++j) std::swap(A[col][j], A[pivot][j]);
            std::swap(b[col], b[pivot]);
        }
        double diag = A[col][col];
        for (int row = col + 1; row < 8; ++row) {
            double factor = A[row][col] / diag;
            if (factor == 0.0) continue;
            for (int j = col; j < 8; ++j) {
                A[row][j] -= factor * A[col][j];
            }
            b[row] -= factor * b[col];
        }
    }
    for (int row = 7; row >= 0; --row) {
        double sum = b[row];
        for (int j = row + 1; j < 8; ++j) {
            sum -= A[row][j] * x[j];
        }
        x[row] = sum / A[row][row];
    }
    return true;
}

bool computeHomography(
    const std::array<Point2f, 4>& src,
    const std::array<Point2f, 4>& dst,
    double H[9]
) {
    double A[8][8] = {};
    double b[8] = {};
    for (int i = 0; i < 4; ++i) {
        double sx = src[i].x;
        double sy = src[i].y;
        double dx = dst[i].x;
        double dy = dst[i].y;

        A[2 * i][0] = sx;
        A[2 * i][1] = sy;
        A[2 * i][2] = 1.0;
        A[2 * i][6] = -dx * sx;
        A[2 * i][7] = -dx * sy;
        b[2 * i] = dx;

        A[2 * i + 1][3] = sx;
        A[2 * i + 1][4] = sy;
        A[2 * i + 1][5] = 1.0;
        A[2 * i + 1][6] = -dy * sx;
        A[2 * i + 1][7] = -dy * sy;
        b[2 * i + 1] = dy;
    }
    double sol[8] = {};
    if (!solveLinear8x8(A, b, sol)) return false;
    for (int i = 0; i < 8; ++i) H[i] = sol[i];
    H[8] = 1.0;
    return true;
}

// Fast O(1)-per-pixel box mean using 64-bit integral image
void boxMeanChannel(
    const std::vector<float>& src,
    int w,
    int h,
    int radius,
    std::vector<float>& dst
) {
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
        int y0 = std::max(0, y - radius);
        int y1 = std::min(h - 1, y + radius);
        const double* r0 = integ.data() + static_cast<size_t>(y0) * stride;
        const double* r1 = integ.data() + static_cast<size_t>(y1 + 1) * stride;
        float* dRow = dst.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; ++x) {
            int x0 = std::max(0, x - radius);
            int x1 = std::min(w - 1, x + radius);
            double sum = r1[x1 + 1] - r1[x0] - r0[x1 + 1] + r0[x0];
            int count = (x1 - x0 + 1) * (y1 - y0 + 1);
            dRow[x] = static_cast<float>(sum / count);
        }
    }
}

void unsharpMaskLuma(uint8_t* rgba, int w, int h, float amount) {
    const size_t n = static_cast<size_t>(w) * h;
    std::vector<float> luma(n);
    for (size_t i = 0; i < n; ++i) {
        luma[i] = 0.299f * rgba[4 * i] + 0.587f * rgba[4 * i + 1] + 0.114f * rgba[4 * i + 2];
    }
    std::vector<float> blurred;
    boxMeanChannel(luma, w, h, 1, blurred);
    for (size_t i = 0; i < n; ++i) {
        float detail = (luma[i] - blurred[i]) * amount;
        rgba[4 * i] = clampByte(rgba[4 * i] + detail);
        rgba[4 * i + 1] = clampByte(rgba[4 * i + 1] + detail);
        rgba[4 * i + 2] = clampByte(rgba[4 * i + 2] + detail);
    }
}

} // namespace

std::array<Point2f, 4> detectDocumentQuad(
    const uint8_t* rgba,
    int width,
    int height,
    bool* found
) {
    const std::array<Point2f, 4> fallback = {{
        {0.08f, 0.08f},
        {0.92f, 0.08f},
        {0.92f, 0.92f},
        {0.08f, 0.92f},
    }};
    if (found) *found = false;
    if (!rgba || width < 32 || height < 32) {
        return fallback;
    }

    // 1. Downsample to max side 320px grayscale
    const int maxDim = 320;
    const float scale = (std::max(width, height) > maxDim)
                            ? static_cast<float>(maxDim) / static_cast<float>(std::max(width, height))
                            : 1.0f;
    const int sw = std::max(32, static_cast<int>(std::round(width * scale)));
    const int sh = std::max(32, static_cast<int>(std::round(height * scale)));
    std::vector<float> gray(static_cast<size_t>(sw) * sh);

    for (int y = 0; y < sh; ++y) {
        int sy = std::min(height - 1, static_cast<int>(y / scale));
        const uint8_t* row = rgba + static_cast<size_t>(sy) * width * 4;
        for (int x = 0; x < sw; ++x) {
            int sx = std::min(width - 1, static_cast<int>(x / scale));
            const uint8_t* p = row + sx * 4;
            gray[static_cast<size_t>(y) * sw + x] =
                0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2];
        }
    }

    // 2. Separable 5x5 Gaussian blur [1 4 6 4 1] / 16
    std::vector<float> tmp(gray.size());
    std::vector<float> blur(gray.size());
    constexpr float k[5] = {1.0f / 16.0f, 4.0f / 16.0f, 6.0f / 16.0f, 4.0f / 16.0f, 1.0f / 16.0f};
    for (int y = 0; y < sh; ++y) {
        for (int x = 0; x < sw; ++x) {
            float acc = 0.0f;
            for (int i = -2; i <= 2; ++i) {
                int xx = clampInt(x + i, 0, sw - 1);
                acc += gray[static_cast<size_t>(y) * sw + xx] * k[i + 2];
            }
            tmp[static_cast<size_t>(y) * sw + x] = acc;
        }
    }
    for (int y = 0; y < sh; ++y) {
        for (int x = 0; x < sw; ++x) {
            float acc = 0.0f;
            for (int i = -2; i <= 2; ++i) {
                int yy = clampInt(y + i, 0, sh - 1);
                acc += tmp[static_cast<size_t>(yy) * sw + x] * k[i + 2];
            }
            blur[static_cast<size_t>(y) * sw + x] = acc;
        }
    }

    // 3. Sobel gradient magnitude + adaptive threshold (top ~12% edge strength)
    std::vector<float> mag(gray.size(), 0.0f);
    std::vector<float> samples;
    samples.reserve(gray.size() / 4);
    for (int y = 1; y < sh - 1; ++y) {
        for (int x = 1; x < sw - 1; ++x) {
            float tl = blur[(y - 1) * sw + (x - 1)];
            float tc = blur[(y - 1) * sw + x];
            float tr = blur[(y - 1) * sw + (x + 1)];
            float ml = blur[y * sw + (x - 1)];
            float mr = blur[y * sw + (x + 1)];
            float bl = blur[(y + 1) * sw + (x - 1)];
            float bc = blur[(y + 1) * sw + x];
            float br = blur[(y + 1) * sw + (x + 1)];

            float gx = -tl + tr - 2.0f * ml + 2.0f * mr - bl + br;
            float gy = -tl - 2.0f * tc - tr + bl + 2.0f * bc + br;
            float m = std::hypot(gx, gy);
            mag[y * sw + x] = m;
            if ((x & 1) == 0 && (y & 1) == 0) {
                samples.push_back(m);
            }
        }
    }

    if (samples.empty()) return fallback;
    size_t p85Idx = static_cast<size_t>(samples.size() * 0.85);
    std::nth_element(samples.begin(), samples.begin() + p85Idx, samples.end());
    float highThresh = std::max(28.0f, samples[p85Idx] * 1.15f);
    float lowThresh = highThresh * 0.45f;

    // 4. Hysteresis edge map + 3x3 dilation to connect minor paper edge gaps
    std::vector<uint8_t> edges(gray.size(), 0);
    std::queue<int> bfs;
    for (int y = 2; y < sh - 2; ++y) {
        for (int x = 2; x < sw - 2; ++x) {
            int idx = y * sw + x;
            if (mag[idx] >= highThresh) {
                edges[idx] = 2;
                bfs.push(idx);
            } else if (mag[idx] >= lowThresh) {
                edges[idx] = 1;
            }
        }
    }
    while (!bfs.empty()) {
        int cur = bfs.front();
        bfs.pop();
        int cx = cur % sw;
        int cy = cur / sw;
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                if (dx == 0 && dy == 0) continue;
                int nx = cx + dx;
                int ny = cy + dy;
                if (nx <= 1 || nx >= sw - 2 || ny <= 1 || ny >= sh - 2) continue;
                int nidx = ny * sw + nx;
                if (edges[nidx] == 1) {
                    edges[nidx] = 2;
                    bfs.push(nidx);
                }
            }
        }
    }

    std::vector<uint8_t> dilated(gray.size(), 0);
    for (int y = 2; y < sh - 2; ++y) {
        for (int x = 2; x < sw - 2; ++x) {
            if (edges[y * sw + x] == 2) {
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        dilated[(y + dy) * sw + (x + dx)] = 1;
                    }
                }
            }
        }
    }

    // 5. Find connected edge components and score their simplified 4-point convex hulls
    std::vector<uint8_t> visited(gray.size(), 0);
    const float totalArea = static_cast<float>(sw * sh);
    float bestScore = 0.0f;
    std::array<Point2f, 4> bestQuad = fallback;
    bool hasCandidate = false;

    for (int y = 2; y < sh - 2; ++y) {
        for (int x = 2; x < sw - 2; ++x) {
            int startIdx = y * sw + x;
            if (!dilated[startIdx] || visited[startIdx]) continue;

            std::vector<Point2f> pts;
            std::queue<int> q;
            visited[startIdx] = 1;
            q.push(startIdx);

            int minX = x, maxX = x, minY = y, maxY = y;
            while (!q.empty()) {
                int cur = q.front();
                q.pop();
                int cx = cur % sw;
                int cy = cur / sw;
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
                pts.push_back({static_cast<float>(cx), static_cast<float>(cy)});

                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx;
                        int ny = cy + dy;
                        if (nx < 1 || nx >= sw - 1 || ny < 1 || ny >= sh - 1) continue;
                        int nidx = ny * sw + nx;
                        if (dilated[nidx] && !visited[nidx]) {
                            visited[nidx] = 1;
                            q.push(nidx);
                        }
                    }
                }
            }

            float bboxArea = static_cast<float>((maxX - minX) * (maxY - minY));
            if (pts.size() < 40 || bboxArea < totalArea * 0.10f) continue;

            auto hull = convexHull(std::move(pts));
            if (hull.size() < 4) continue;

            float hullArea = polygonArea(hull);
            if (hullArea < totalArea * 0.12f || hullArea > totalArea * 0.96f) continue;

            auto quad = simplifyHullToQuad(hull);
            std::vector<Point2f> qVec(quad.begin(), quad.end());
            float quadArea = polygonArea(qVec);
            float fillRatio = quadArea / std::max(1.0f, hullArea);
            if (fillRatio < 0.72f) continue;

            // Validate reasonable quadrilateral proportions (avoid razor-thin slivers)
            float top = dist(quad[0], quad[1]);
            float right = dist(quad[1], quad[2]);
            float bottom = dist(quad[2], quad[3]);
            float left = dist(quad[3], quad[0]);
            float minEdge = std::min(std::min(top, bottom), std::min(left, right));
            if (minEdge < std::min(sw, sh) * 0.18f) continue;

            // Slight preference for centered, large, rectangular quads
            float aspectScore = 1.0f - 0.25f * std::abs(top - bottom) / std::max(top, bottom)
                                     - 0.25f * std::abs(left - right) / std::max(left, right);
            float score = quadArea * fillRatio * std::max(0.4f, aspectScore);
            if (score > bestScore) {
                bestScore = score;
                bestQuad = quad;
                hasCandidate = true;
            }
        }
    }

    if (!hasCandidate) {
        return fallback;
    }

    if (found) *found = true;
    std::array<Point2f, 4> normalized{};
    for (int i = 0; i < 4; ++i) {
        normalized[i].x = std::clamp(bestQuad[i].x / static_cast<float>(sw - 1), 0.0f, 1.0f);
        normalized[i].y = std::clamp(bestQuad[i].y / static_cast<float>(sh - 1), 0.0f, 1.0f);
    }
    return orderQuad(normalized);
}

void computeOutputSize(
    int srcW,
    int srcH,
    const std::array<Point2f, 4>& normQuad,
    int maxSide,
    int* outW,
    int* outH
) {
    auto q = orderQuad(normQuad);
    std::array<Point2f, 4> px = {{
        {q[0].x * (srcW - 1), q[0].y * (srcH - 1)},
        {q[1].x * (srcW - 1), q[1].y * (srcH - 1)},
        {q[2].x * (srcW - 1), q[2].y * (srcH - 1)},
        {q[3].x * (srcW - 1), q[3].y * (srcH - 1)},
    }};
    float wTop = dist(px[0], px[1]);
    float wBot = dist(px[3], px[2]);
    float hLeft = dist(px[0], px[3]);
    float hRight = dist(px[1], px[2]);

    float rawW = std::max(64.0f, std::max(wTop, wBot));
    float rawH = std::max(64.0f, std::max(hLeft, hRight));

    float cap = static_cast<float>(std::max(256, maxSide));
    float longest = std::max(rawW, rawH);
    if (longest > cap) {
        float s = cap / longest;
        rawW *= s;
        rawH *= s;
    }
    *outW = std::max(64, static_cast<int>(std::round(rawW)));
    *outH = std::max(64, static_cast<int>(std::round(rawH)));
}

bool warpAndFilterDocument(
    const uint8_t* srcRgba,
    int srcW,
    int srcH,
    uint8_t* dstRgba,
    int dstW,
    int dstH,
    const std::array<Point2f, 4>& normQuad,
    ScanFilter filter
) {
    if (!srcRgba || !dstRgba || srcW < 2 || srcH < 2 || dstW < 2 || dstH < 2) {
        return false;
    }

    auto q = orderQuad(normQuad);
    std::array<Point2f, 4> dstCorners = {{
        {0.0f, 0.0f},
        {static_cast<float>(dstW - 1), 0.0f},
        {static_cast<float>(dstW - 1), static_cast<float>(dstH - 1)},
        {0.0f, static_cast<float>(dstH - 1)},
    }};
    std::array<Point2f, 4> srcCorners = {{
        {q[0].x * (srcW - 1), q[0].y * (srcH - 1)},
        {q[1].x * (srcW - 1), q[1].y * (srcH - 1)},
        {q[2].x * (srcW - 1), q[2].y * (srcH - 1)},
        {q[3].x * (srcW - 1), q[3].y * (srcH - 1)},
    }};

    double H[9] = {};
    if (!computeHomography(dstCorners, srcCorners, H)) {
        return false;
    }

    // Perspective warp with bilinear interpolation
    for (int y = 0; y < dstH; ++y) {
        uint8_t* dstRow = dstRgba + static_cast<size_t>(y) * dstW * 4;
        for (int x = 0; x < dstW; ++x) {
            double denom = H[6] * x + H[7] * y + H[8];
            double invD = (std::abs(denom) > 1e-9) ? (1.0 / denom) : 1.0;
            float sx = static_cast<float>((H[0] * x + H[1] * y + H[2]) * invD);
            float sy = static_cast<float>((H[3] * x + H[4] * y + H[5]) * invD);

            sx = std::clamp(sx, 0.0f, static_cast<float>(srcW - 1));
            sy = std::clamp(sy, 0.0f, static_cast<float>(srcH - 1));

            int x0 = static_cast<int>(sx);
            int y0 = static_cast<int>(sy);
            int x1 = std::min(srcW - 1, x0 + 1);
            int y1 = std::min(srcH - 1, y0 + 1);
            float fx = sx - x0;
            float fy = sy - y0;

            const uint8_t* p00 = srcRgba + (static_cast<size_t>(y0) * srcW + x0) * 4;
            const uint8_t* p10 = srcRgba + (static_cast<size_t>(y0) * srcW + x1) * 4;
            const uint8_t* p01 = srcRgba + (static_cast<size_t>(y1) * srcW + x0) * 4;
            const uint8_t* p11 = srcRgba + (static_cast<size_t>(y1) * srcW + x1) * 4;

            float w00 = (1.0f - fx) * (1.0f - fy);
            float w10 = fx * (1.0f - fy);
            float w01 = (1.0f - fx) * fy;
            float w11 = fx * fy;

            dstRow[4 * x + 0] = clampByte(p00[0] * w00 + p10[0] * w10 + p01[0] * w01 + p11[0] * w11);
            dstRow[4 * x + 1] = clampByte(p00[1] * w00 + p10[1] * w10 + p01[1] * w01 + p11[1] * w11);
            dstRow[4 * x + 2] = clampByte(p00[2] * w00 + p10[2] * w10 + p01[2] * w01 + p11[2] * w11);
            dstRow[4 * x + 3] = 255;
        }
    }

    if (filter != ScanFilter::ORIGINAL) {
        applyFilterInPlace(dstRgba, dstW, dstH, filter);
    }
    return true;
}

void applyFilterInPlace(uint8_t* rgba, int width, int height, ScanFilter filter) {
    if (!rgba || width < 2 || height < 2 || filter == ScanFilter::ORIGINAL) return;

    const size_t n = static_cast<size_t>(width) * height;
    const int bgRadius = std::clamp(std::max(width, height) / 28, 12, 64);

    if (filter == ScanFilter::MAGIC_COLOR) {
        // Illumination-division white-paper restoration preserving ink colors (blue pen / red seal)
        std::vector<float> rCh(n), gCh(n), bCh(n);
        for (size_t i = 0; i < n; ++i) {
            rCh[i] = rgba[4 * i + 0];
            gCh[i] = rgba[4 * i + 1];
            bCh[i] = rgba[4 * i + 2];
        }
        std::vector<float> rBg, gBg, bBg;
        boxMeanChannel(rCh, width, height, bgRadius, rBg);
        boxMeanChannel(gCh, width, height, bgRadius, gBg);
        boxMeanChannel(bCh, width, height, bgRadius, bBg);

        for (size_t i = 0; i < n; ++i) {
            // Boost background estimate slightly towards paper white so paper flattens to 255
            float nr = (rCh[i] * 250.0f) / std::max(28.0f, rBg[i] * 0.96f);
            float ng = (gCh[i] * 250.0f) / std::max(28.0f, gBg[i] * 0.96f);
            float nb = (bCh[i] * 250.0f) / std::max(28.0f, bBg[i] * 0.96f);

            // Clean highlight snap + crisp text darkening curve
            auto curve = [](float v) -> float {
                float u = std::clamp(v / 255.0f, 0.0f, 1.0f);
                if (u > 0.88f) return 255.0f;
                // Mild gamma darkening on dark text strokes
                float adj = std::pow(u / 0.88f, 1.35f) * 255.0f;
                return adj;
            };

            rgba[4 * i + 0] = clampByte(curve(nr));
            rgba[4 * i + 1] = clampByte(curve(ng));
            rgba[4 * i + 2] = clampByte(curve(nb));
            rgba[4 * i + 3] = 255;
        }
        unsharpMaskLuma(rgba, width, height, 0.45f);
        return;
    }

    if (filter == ScanFilter::SHADOW_FREE_GRAY) {
        std::vector<float> luma(n);
        for (size_t i = 0; i < n; ++i) {
            luma[i] = 0.299f * rgba[4 * i] + 0.587f * rgba[4 * i + 1] + 0.114f * rgba[4 * i + 2];
        }
        std::vector<float> bg;
        boxMeanChannel(luma, width, height, bgRadius, bg);
        for (size_t i = 0; i < n; ++i) {
            float v = (luma[i] * 250.0f) / std::max(24.0f, bg[i] * 0.96f);
            float u = std::clamp(v / 255.0f, 0.0f, 1.0f);
            float out = (u > 0.87f) ? 255.0f : std::pow(u / 0.87f, 1.55f) * 255.0f;
            uint8_t b = clampByte(out);
            rgba[4 * i + 0] = b;
            rgba[4 * i + 1] = b;
            rgba[4 * i + 2] = b;
            rgba[4 * i + 3] = 255;
        }
        unsharpMaskLuma(rgba, width, height, 0.55f);
        return;
    }

    if (filter == ScanFilter::CRISP_BW) {
        // Sauvola local adaptive thresholding in O(1) per pixel via integral images of I and I^2
        std::vector<float> luma(n);
        std::vector<float> lumaSq(n);
        for (size_t i = 0; i < n; ++i) {
            float v = 0.299f * rgba[4 * i] + 0.587f * rgba[4 * i + 1] + 0.114f * rgba[4 * i + 2];
            luma[i] = v;
            lumaSq[i] = v * v;
        }
        const int radius = std::clamp(std::max(width, height) / 48, 8, 28);
        std::vector<float> mean, meanSq;
        boxMeanChannel(luma, width, height, radius, mean);
        boxMeanChannel(lumaSq, width, height, radius, meanSq);

        constexpr float kSauvola = 0.22f;
        constexpr float rMax = 128.0f;
        constexpr float softBand = 14.0f;

        for (size_t i = 0; i < n; ++i) {
            float m = mean[i];
            float var = std::max(0.0f, meanSq[i] - m * m);
            float stddev = std::sqrt(var);
            float thresh = m * (1.0f + kSauvola * (stddev / rMax - 1.0f));
            // Suppress noise in flat bright background areas
            if (stddev < 6.0f && luma[i] > m - 10.0f) {
                rgba[4 * i + 0] = 255;
                rgba[4 * i + 1] = 255;
                rgba[4 * i + 2] = 255;
                rgba[4 * i + 3] = 255;
                continue;
            }
            float delta = luma[i] - thresh;
            float out;
            if (delta <= -softBand) {
                out = 0.0f;
            } else if (delta >= softBand) {
                out = 255.0f;
            } else {
                float t = (delta + softBand) / (2.0f * softBand);
                // Smoothstep anti-aliased stroke edge
                out = (t * t * (3.0f - 2.0f * t)) * 255.0f;
            }
            uint8_t b = clampByte(out);
            rgba[4 * i + 0] = b;
            rgba[4 * i + 1] = b;
            rgba[4 * i + 2] = b;
            rgba[4 * i + 3] = 255;
        }
        return;
    }

    if (filter == ScanFilter::HIGH_CONTRAST_COLOR) {
        // Gentle white-balance stretch + punchy saturation & sharpening for colored handouts/slides
        std::array<int, 256> hist{};
        for (size_t i = 0; i < n; ++i) {
            int l = static_cast<int>(0.299f * rgba[4 * i] + 0.587f * rgba[4 * i + 1] + 0.114f * rgba[4 * i + 2]);
            hist[clampInt(l, 0, 255)]++;
        }
        int loTarget = static_cast<int>(n * 0.02);
        int hiTarget = static_cast<int>(n * 0.96);
        int acc = 0, lo = 0, hi = 255;
        for (int i = 0; i < 256; ++i) {
            acc += hist[i];
            if (acc >= loTarget) { lo = i; break; }
        }
        acc = 0;
        for (int i = 0; i < 256; ++i) {
            acc += hist[i];
            if (acc >= hiTarget) { hi = i; break; }
        }
        float invSpan = 255.0f / std::max(16, hi - lo);
        for (size_t i = 0; i < n; ++i) {
            float r = (rgba[4 * i + 0] - lo) * invSpan;
            float g = (rgba[4 * i + 1] - lo) * invSpan;
            float b = (rgba[4 * i + 2] - lo) * invSpan;
            float l = 0.299f * r + 0.587f * g + 0.114f * b;
            rgba[4 * i + 0] = clampByte(l + (r - l) * 1.18f);
            rgba[4 * i + 1] = clampByte(l + (g - l) * 1.18f);
            rgba[4 * i + 2] = clampByte(l + (b - l) * 1.18f);
            rgba[4 * i + 3] = 255;
        }
        unsharpMaskLuma(rgba, width, height, 0.5f);
    }
}

} // namespace bgoom
