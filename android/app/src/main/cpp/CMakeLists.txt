cmake_minimum_required(VERSION 3.25)
project(rexauto_android LANGUAGES C CXX ASM)

# ---------------------------------------------------------------------------
# ReXGlue / Project paths
# ---------------------------------------------------------------------------

set(REX_PROJECT_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../.."
    CACHE PATH "rexauto checkout")

set(REXSDK_DIR
    "${REX_PROJECT_ROOT}/android/sdk/rexglue-sdk"
    CACHE PATH "patched ReXGlue SDK tree")

set(REX_PORT_DIR
    ""
    CACHE PATH "rexauto port dir (autoports/<name>/port)")

set(REX_APP_NAME
    "game"
    CACHE STRING "rexglue project name")

if(NOT EXISTS "${REXSDK_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "ReXGlue SDK not found at ${REXSDK_DIR} - "
        "run tools/android_sdk.sh first")
endif()

if(NOT EXISTS "${REX_PORT_DIR}/generated/default/sources.cmake")
    message(FATAL_ERROR
        "No generated code at ${REX_PORT_DIR}/generated/default - "
        "run the desktop pipeline (rexauto.py --until build) first")
endif()


# ---------------------------------------------------------------------------
# C++ configuration
# ---------------------------------------------------------------------------

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)


# ---------------------------------------------------------------------------
# ReXGlue features
# ---------------------------------------------------------------------------

set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)

# Vulkan renderer
set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)

# D3D12 is not used on Android
set(REXGLUE_USE_D3D12 OFF CACHE BOOL "" FORCE)


# ---------------------------------------------------------------------------
# ARM64 / CPU / Linker optimizations
#
# Important:
#   - ARM64 already has AdvSIMD/NEON as part of the Android arm64 baseline.
#   - We intentionally do NOT use -mfpu=neon because that is for 32-bit ARM.
#   - We intentionally do NOT enable global -ffast-math because it can alter
#     floating-point behavior and cause physics/rendering problems.
# ---------------------------------------------------------------------------

if(CMAKE_BUILD_TYPE STREQUAL "Release")

    # Enable CMake IPO/LTO for targets that support it.
    set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)
    set(CMAKE_INTERPROCEDURAL_OPTIMIZATION_RELEASE ON)

    # General native optimization.
    add_compile_options(
        -O3
        -DNDEBUG

        # Reduce function-call/frame overhead.
        -fomit-frame-pointer

        # Avoid unnecessary errno handling for math operations.
        -fno-math-errno

        # Allow optimizations assuming floating-point exceptions are not
        # being used for program control flow.
        -fno-trapping-math

        # Reduce duplicate constants where possible.
        -fmerge-all-constants
    )

    # -----------------------------------------------------------------------
    # ARM64 ISA selection
    #
    # Default:
    #   ARMv8-A is the safest baseline for arm64-v8a.
    #
    # Optional:
    #   Set REX_ANDROID_BASELINE_ARM in the environment to use the conservative
    #   ARMv8-A path.
    #
    # A separate high-end build can explicitly select ARMv8.2-A + extensions
    # after confirming the target devices support them.
    # -----------------------------------------------------------------------

    if(DEFINED ENV{REX_ANDROID_BASELINE_ARM})
        add_compile_options(
            -march=armv8-a
        )
    else()
        add_compile_options(
            -march=armv8.2-a+fp16+dotprod
            -mtune=cortex-a76
        )
    endif()

    # -----------------------------------------------------------------------
    # Linker optimization
    # -----------------------------------------------------------------------

    add_link_options(
        -Wl,-O2
        -Wl,--gc-sections
        -Wl,--icf=all
        -Wl,--as-needed
    )

endif()


# ---------------------------------------------------------------------------
# ReXGlue SDK
# ---------------------------------------------------------------------------

add_subdirectory(
    "${REXSDK_DIR}"
    rexglue-sdk
)


# ---------------------------------------------------------------------------
# libadrenotools
#
# Used for custom Turnip/Adreno Vulkan driver loading when available.
# ---------------------------------------------------------------------------

set(ADRENOTOOLS_DIR
    "${REXSDK_DIR}/../libadrenotools"
    CACHE PATH "libadrenotools checkout")

if(EXISTS "${ADRENOTOOLS_DIR}/CMakeLists.txt")

    set(BUILD_SHARED_LIBS ON)

    add_subdirectory(
        "${ADRENOTOOLS_DIR}"
        adrenotools
    )

    set(REX_HAVE_ADRENOTOOLS ON)

else()

    message(WARNING
        "libadrenotools not found at ${ADRENOTOOLS_DIR} - "
        "custom GPU drivers disabled")

endif()


# ---------------------------------------------------------------------------
# Generated recompiled guest code
# ---------------------------------------------------------------------------

set(REX_GEN_DIR
    "${REX_PORT_DIR}/generated/default")

include(
    "${REX_GEN_DIR}/sources.cmake"
)

set(REX_GEN_SOURCES "")

foreach(src ${GENERATED_SOURCES})

    if(IS_ABSOLUTE "${src}")

        list(APPEND
            REX_GEN_SOURCES
            "${src}")

    else()

        list(APPEND
            REX_GEN_SOURCES
            "${REX_GEN_DIR}/${src}")

    endif()

endforeach()


# ---------------------------------------------------------------------------
# Recompiled game code
#
# LTO is intentionally disabled here.
#
# The generated PPC -> C++ code is extremely large and contains enormous
# register/init tables. Applying whole-program LTO to this target can cause
# excessive memory usage and very long build times with little benefit.
# ---------------------------------------------------------------------------

add_library(
    recomp
    STATIC
    ${REX_GEN_SOURCES}
)

target_include_directories(
    recomp
    PRIVATE
        "${REX_PORT_DIR}"
        "${REX_PORT_DIR}/src"
        "${REX_GEN_DIR}"
)

target_precompile_headers(
    recomp
    PRIVATE
        "${REX_GEN_DIR}/${REX_APP_NAME}_pch.h"
)

target_compile_options(
    recomp
    PRIVATE

        # Native optimization
        -O3

        # No debug information in Release
        -g0

        # Remove RTTI overhead; generated code does not use dynamic_cast/typeid.
        -fno-rtti

        # Math optimizations that are safer than global -ffast-math.
        -fno-math-errno
        -fno-trapping-math

        # Generated-code warning suppression.
        -Wno-unused-variable
        -Wno-unused-label
        -Wno-unused-but-set-variable
        -Wno-unused-value
        -Wno-parentheses-equality
)

# Keep LTO disabled for the huge generated code target.
set_target_properties(
    recomp
    PROPERTIES
        INTERPROCEDURAL_OPTIMIZATION OFF
)

target_link_libraries(
    recomp
    PUBLIC
        rex::runtime
        rexui
)


# ---------------------------------------------------------------------------
# Main Android shared library
# ---------------------------------------------------------------------------

add_library(
    main
    SHARED

    android_gamepad.cpp
    android_main.cpp

    "${REX_PORT_DIR}/src/main.cpp"

    "${REXSDK_DIR}/src/ui/rex_app.cpp"
)

target_include_directories(
    main
    PRIVATE

        "${REX_PORT_DIR}"
        "${REX_PORT_DIR}/src"
        "${REX_GEN_DIR}"

        "${REXSDK_DIR}/include"
        "${REXSDK_DIR}/src/ui"
)

target_compile_options(
    main
    PRIVATE
        -Wno-unused-variable
)

target_compile_definitions(
    main
    PRIVATE

        REXGLUE_BUILD_CONFIG="$<CONFIG>"

        REX_APP_NAME="${REX_APP_NAME}"
)

target_link_libraries(
    main
    PRIVATE

        recomp
        rex::runtime
        rexui
)


# ---------------------------------------------------------------------------
# Optional libadrenotools integration
# ---------------------------------------------------------------------------

if(REX_HAVE_ADRENOTOOLS)

    target_compile_definitions(
        main
        PRIVATE
            REX_HAVE_ADRENOTOOLS=1
    )

    target_link_libraries(
        main
        PRIVATE
            adrenotools
    )

    add_dependencies(
        main
        main_hook
        file_redirect_hook
        gsl_alloc_hook
        hook_impl
    )

endif()


# ---------------------------------------------------------------------------
# Dynamic symbol export
# ---------------------------------------------------------------------------

target_link_options(
    main
    PRIVATE
        -Wl,--export-dynamic
)

add_dependencies(
    main
    rexgpu-xenos
)


# ---------------------------------------------------------------------------
# Symbol visibility
# ---------------------------------------------------------------------------

set_target_properties(
    main
    PROPERTIES
        CXX_VISIBILITY_PRESET default
        VISIBILITY_INLINES_HIDDEN OFF
)