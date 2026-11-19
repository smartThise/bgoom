cmake_minimum_required(VERSION 3.22.1)
project(bgoom_scanner LANGUAGES CXX)

add_library(bgoom_scanner SHARED
    doc_scanner.cpp
    portrait_refine.cpp
    jni_bridge.cpp
)

target_compile_features(bgoom_scanner PRIVATE cxx_std_17)
target_compile_options(bgoom_scanner PRIVATE -O3 -ffast-math)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(bgoom_scanner
    ${jnigraphics-lib}
    ${log-lib}
)
