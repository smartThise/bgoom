#include <android/bitmap.h>
#include <jni.h>
#include <array>
#include <cstring>

#include "doc_scanner.h"
#include "portrait_refine.h"

namespace {

std::array<bgoom::Point2f, 4> readQuad(JNIEnv* env, jfloatArray quadArr) {
    std::array<bgoom::Point2f, 4> q = {{
        {0.0f, 0.0f}, {1.0f, 0.0f}, {1.0f, 1.0f}, {0.0f, 1.0f}
    }};
    if (!quadArr || env->GetArrayLength(quadArr) < 8) return q;
    jfloat buf[8];
    env->GetFloatArrayRegion(quadArr, 0, 8, buf);
    for (int i = 0; i < 4; ++i) {
        q[i] = {buf[2 * i], buf[2 * i + 1]};
    }
    return q;
}

} // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_bgoom_app_scanner_NativeDocScanner_nativeDetectQuad(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap
) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return nullptr;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }

    bool found = false;
    auto quad = bgoom::detectDocumentQuad(
        static_cast<const uint8_t*>(pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        &found
    );
    AndroidBitmap_unlockPixels(env, bitmap);

    // Return 9 floats: [x0,y0, x1,y1, x2,y2, x3,y3, foundFlag]
    jfloat out[9] = {
        quad[0].x, quad[0].y,
        quad[1].x, quad[1].y,
        quad[2].x, quad[2].y,
        quad[3].x, quad[3].y,
        found ? 1.0f : 0.0f
    };
    jfloatArray res = env->NewFloatArray(9);
    if (res) {
        env->SetFloatArrayRegion(res, 0, 9, out);
    }
    return res;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bgoom_app_scanner_NativeDocScanner_nativeComputeOutputSize(
    JNIEnv* env,
    jobject /* thiz */,
    jint srcW,
    jint srcH,
    jfloatArray quadArr,
    jint maxSide
) {
    auto quad = readQuad(env, quadArr);
    int outW = 0, outH = 0;
    bgoom::computeOutputSize(srcW, srcH, quad, maxSide, &outW, &outH);
    jint dims[2] = {outW, outH};
    jintArray res = env->NewIntArray(2);
    if (res) env->SetIntArrayRegion(res, 0, 2, dims);
    return res;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bgoom_app_scanner_NativeDocScanner_nativeWarpAndFilter(
    JNIEnv* env,
    jobject /* thiz */,
    jobject srcBitmap,
    jobject dstBitmap,
    jfloatArray quadArr,
    jint filterMode
) {
    AndroidBitmapInfo srcInfo, dstInfo;
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }

    void* srcPixels = nullptr;
    void* dstPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return JNI_FALSE;
    }

    auto quad = readQuad(env, quadArr);
    auto filter = static_cast<bgoom::ScanFilter>(filterMode);
    bool ok = bgoom::warpAndFilterDocument(
        static_cast<const uint8_t*>(srcPixels),
        static_cast<int>(srcInfo.width),
        static_cast<int>(srcInfo.height),
        static_cast<uint8_t*>(dstPixels),
        static_cast<int>(dstInfo.width),
        static_cast<int>(dstInfo.height),
        quad,
        filter
    );

    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bgoom_app_scanner_NativeDocScanner_nativeApplyFilterInPlace(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jint filterMode
) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return;
    }
    bgoom::applyFilterInPlace(
        static_cast<uint8_t*>(pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        static_cast<bgoom::ScanFilter>(filterMode)
    );
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_bgoom_app_scanner_NativeDocScanner_nativeRefinePortraitMatte(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jfloatArray alpha512Arr,
    jint alphaSize,
    jboolean studioLighting
) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        !alpha512Arr ||
        env->GetArrayLength(alpha512Arr) < alphaSize * alphaSize) {
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }
    jfloat* alphaPtr = env->GetFloatArrayElements(alpha512Arr, nullptr);
    bgoom::PortraitBbox bbox = bgoom::refinePortraitMatteInPlace(
        static_cast<uint8_t*>(pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        alphaPtr,
        static_cast<int>(alphaSize),
        studioLighting == JNI_TRUE
    );
    env->ReleaseFloatArrayElements(alpha512Arr, alphaPtr, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, bitmap);

    jfloat out[5] = {
        bbox.topRatio,
        bbox.bottomRatio,
        bbox.leftRatio,
        bbox.rightRatio,
        bbox.centerXRatio
    };
    jfloatArray res = env->NewFloatArray(5);
    if (res) env->SetFloatArrayRegion(res, 0, 5, out);
    return res;
}
