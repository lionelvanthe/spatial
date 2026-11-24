cmake_minimum_required(VERSION 3.22.1)
project(spatial_mesh LANGUAGES CXX)

add_library(
    spatial_mesh
    SHARED
    PatternAnimations.cpp
    AdaptiveMeshBuilder.cpp
    SpatialModelEngine.cpp
    jni_bridge.cpp
)

target_compile_features(spatial_mesh PRIVATE cxx_std_17)
target_compile_options(spatial_mesh PRIVATE -O3 -Wall -Wextra)
target_link_options(spatial_mesh PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)

target_link_libraries(
    spatial_mesh
    ${log-lib}
)
