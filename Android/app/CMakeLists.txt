cmake_minimum_required(VERSION 3.22.1)
project(MesenCore CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Mesen2 root is two levels up from Android/app/
set(MESEN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../..")

# ── Source globs ──────────────────────────────────────────────────────────────
file(GLOB_RECURSE CORE_SRC
    "${MESEN_ROOT}/Core/*.cpp"
)

file(GLOB_RECURSE UTIL_CPP_SRC
    "${MESEN_ROOT}/Utilities/*.cpp"
)
file(GLOB_RECURSE UTIL_C_SRC
    "${MESEN_ROOT}/Utilities/*.c"
)

file(GLOB_RECURSE SEVENZIP_SRC
    "${MESEN_ROOT}/SevenZip/*.c"
)

file(GLOB_RECURSE LUA_SRC
    "${MESEN_ROOT}/Lua/*.c"
)

# JNI + platform bridge sources
file(GLOB JNI_SRC
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/*.cpp"
)

# ── Library target ────────────────────────────────────────────────────────────
add_library(MesenCore SHARED
    ${CORE_SRC}
    ${UTIL_CPP_SRC}
    ${UTIL_C_SRC}
    ${SEVENZIP_SRC}
    ${LUA_SRC}
    ${JNI_SRC}
)

# ── Include paths ─────────────────────────────────────────────────────────────
target_include_directories(MesenCore PRIVATE
    "${MESEN_ROOT}"
    "${MESEN_ROOT}/Core"
    "${MESEN_ROOT}/Utilities"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
)

# ── Compile definitions ───────────────────────────────────────────────────────
target_compile_definitions(MesenCore PRIVATE
    __ANDROID__=1
    # Disable NetPlay networking on Android (uses UPnP/sockets not needed)
    MESEN_NO_NETPLAY=1
)

# ── Compile options ───────────────────────────────────────────────────────────
target_compile_options(MesenCore PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:
        -Wall
        -Wno-switch
        -Wno-unused-variable
        -Wno-unused-parameter
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
    >
    $<$<COMPILE_LANGUAGE:C>:
        -Wall
        -Wno-unused-variable
        -Wno-unused-parameter
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
    >
    $<$<AND:$<COMPILE_LANGUAGE:CXX>,$<CONFIG:Release>>:-O3 -DNDEBUG>
    $<$<AND:$<COMPILE_LANGUAGE:C>,  $<CONFIG:Release>>:-O3 -DNDEBUG>
    $<$<AND:$<COMPILE_LANGUAGE:CXX>,$<CONFIG:Debug>>:-O0 -g>
    $<$<AND:$<COMPILE_LANGUAGE:C>,  $<CONFIG:Debug>>:-O0 -g>
)

# ── Linker options ────────────────────────────────────────────────────────────
target_link_options(MesenCore PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

# ── System libraries ──────────────────────────────────────────────────────────
find_library(log-lib    log)
find_library(android-lib android)
find_library(aaudio-lib aaudio)
find_library(gles3-lib  GLESv3)
find_library(egl-lib    EGL)
find_library(z-lib      z)

target_link_libraries(MesenCore
    ${log-lib}
    ${android-lib}
    ${aaudio-lib}
    ${gles3-lib}
    ${egl-lib}
    ${z-lib}
    c++_shared
)
