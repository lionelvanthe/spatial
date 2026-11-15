#include <jni.h>
#include <cstring>
#include "SpatialModelEngine.h"

namespace {

inline spatial::SpatialModelEngine* fromHandle(jlong handle) {
    return reinterpret_cast<spatial::SpatialModelEngine*>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeCreateModel(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    auto* engine = new spatial::SpatialModelEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeDestroyModel(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    delete fromHandle(handle);
}

JNIEXPORT jintArray JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeInitializeMesh(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jbyteArray depthBytes,
    jint depthWidth,
    jint depthHeight,
    jfloat imageAspect,
    jint gridSize
) {
    auto* engine = fromHandle(handle);
    if (!engine || !depthBytes) return nullptr;

    jsize len = env->GetArrayLength(depthBytes);
    if (len < depthWidth * depthHeight) return nullptr;

    jbyte* ptr = env->GetByteArrayElements(depthBytes, nullptr);
    if (!ptr) return nullptr;

    engine->initialize(
        reinterpret_cast<const uint8_t*>(ptr),
        static_cast<int>(depthWidth),
        static_cast<int>(depthHeight),
        static_cast<float>(imageAspect),
        static_cast<int>(gridSize)
    );

    env->ReleaseByteArrayElements(depthBytes, ptr, JNI_ABORT);

    jint counts[2] = {
        static_cast<jint>(engine->getVertexCount()),
        static_cast<jint>(engine->getIndexCount())
    };
    jintArray result = env->NewIntArray(2);
    if (result) {
        env->SetIntArrayRegion(result, 0, 2, counts);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeCopyIndices(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobject indexBuffer
) {
    auto* engine = fromHandle(handle);
    if (!engine || !indexBuffer) return;

    void* dst = env->GetDirectBufferAddress(indexBuffer);
    if (!dst) return;

    const int count = engine->getIndexCount();
    if (count > 0) {
        std::memcpy(dst, engine->getIndices(), static_cast<size_t>(count) * sizeof(uint16_t));
    }
}

JNIEXPORT void JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeSetProperties(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jfloat intensity,
    jfloat depthCenter,
    jboolean rInverted,
    jfloat zoom,
    jfloat zoom3D,
    jfloat elasticity
) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    engine->setProperties(
        static_cast<float>(intensity),
        static_cast<float>(depthCenter),
        rInverted == JNI_TRUE,
        static_cast<float>(zoom),
        static_cast<float>(zoom3D),
        static_cast<float>(elasticity)
    );
}

JNIEXPORT void JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeUpdateRotate(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobject vertexBuffer,
    jfloat tiltX,
    jfloat tiltY
) {
    auto* engine = fromHandle(handle);
    if (!engine || !vertexBuffer) return;
    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(vertexBuffer));
    if (!dst) return;
    engine->updateRotate(dst, static_cast<float>(tiltX), static_cast<float>(tiltY));
}

JNIEXPORT void JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeUpdateShake(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobject vertexBuffer,
    jfloat dt,
    jfloat tiltX,
    jfloat tiltY
) {
    auto* engine = fromHandle(handle);
    if (!engine || !vertexBuffer) return;
    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(vertexBuffer));
    if (!dst) return;
    engine->updateShake(dst, static_cast<float>(dt), static_cast<float>(tiltX), static_cast<float>(tiltY));
}

JNIEXPORT void JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeUpdateAnimated(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobject vertexBuffer,
    jfloat progress,
    jint patternId
) {
    auto* engine = fromHandle(handle);
    if (!engine || !vertexBuffer) return;
    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(vertexBuffer));
    if (!dst) return;
    engine->updateAnimated(dst, static_cast<float>(progress), static_cast<int>(patternId));
}

JNIEXPORT void JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeUpdateZoomPan(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobject vertexBuffer,
    jfloat zoom,
    jfloat panX,
    jfloat panY
) {
    auto* engine = fromHandle(handle);
    if (!engine || !vertexBuffer) return;
    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(vertexBuffer));
    if (!dst) return;
    engine->updateZoomPan(dst, static_cast<float>(zoom), static_cast<float>(panX), static_cast<float>(panY));
}

JNIEXPORT void JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeApplyImpulse(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jfloat impulseX,
    jfloat impulseY
) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    engine->applyImpulse(static_cast<float>(impulseX), static_cast<float>(impulseY));
}

JNIEXPORT void JNICALL
Java_com_example_spatial_gl_SpatialModelBridge_nativeResetPhysics(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    engine->resetPhysics();
}

} // extern "C"
