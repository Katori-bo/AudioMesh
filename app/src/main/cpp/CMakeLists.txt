cmake_minimum_required(VERSION 3.22)
project(audiomesh_native)

# ── C++17 ─────────────────────────────────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Optimisation ──────────────────────────────────────────────────────────────
# -O3         : max optimisation
# -ffast-math : safe for DSP (reorders float ops)
add_compile_options(-O3 -ffast-math)

# ── Sources ───────────────────────────────────────────────────────────────────
add_library(
    audiomesh_native
    SHARED
    AudioMeshJNI.cpp
    sender/SenderEngine.cpp
    sender/MediaCodecDecoder.cpp
    receiver/ReceiverEngine.cpp
    calib/CalibEngine.cpp
)

# ── Include paths ─────────────────────────────────────────────────────────────
target_include_directories(audiomesh_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/common
    ${CMAKE_CURRENT_SOURCE_DIR}/sender
    ${CMAKE_CURRENT_SOURCE_DIR}/receiver
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
    ${CMAKE_CURRENT_SOURCE_DIR}/calib

)

# ── System libraries ──────────────────────────────────────────────────────────
find_library(log-lib     log)
find_library(android-lib android)

target_link_libraries(
    audiomesh_native
    aaudio
    mediandk
    ${log-lib}
    ${android-lib}
)

# ── 16KB page alignment — required for Play Store (Android 15+) ───────────────
target_compile_options(audiomesh_native PRIVATE
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)