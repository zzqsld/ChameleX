cmake_minimum_required(VERSION 3.22.1)
project(chamelex_crypto C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")
endif()

find_package(Threads REQUIRED)

# ---- 算法库：darkside / nested / staticnested / mfkey32 ----
add_library(chamelex_crypto SHARED
    jni_bridge.c
    common/common.c
    common/crapto1.c
    common/crypto1.c
    common/bucketsort.c
    common/parity.c
    common/mfkey.c
    common/nested_util.c
    common/darkside_core.c
    common/nested_core.c
    common/mfkey32_core.c
)
target_include_directories(chamelex_crypto PRIVATE . common)
target_compile_definitions(chamelex_crypto PRIVATE _GNU_SOURCE)
target_link_libraries(chamelex_crypto Threads::Threads log m)

# ---- hardnested 库（与上游一致使用其自带的 crapto1/crypto1，与上面符号冲突故独立 .so） ----
add_library(chamelex_hn SHARED
    jni_hn_bridge.c
    hardnested/cmdhfmfhard.c
    hardnested/crapto1.c
    hardnested/crypto1.c
    hardnested/hardnested/hardnested_bf_core.c
    hardnested/hardnested/hardnested_bitarray_core.c
    hardnested/hardnested/hardnested_bruteforce.c
    hardnested/hardnested/tables.c
    hardnested/pm3/ui.c
    hardnested/pm3/util.c
    hardnested/pm3/commonutil.c
    hardnested/pm3/util_posix.c
    xz/xz_mini.c
    xz/xz_dec_stream.c
    xz/xz_dec_lzma2.c
    xz/xz_dec_bcj.c
    xz/xz_crc32.c
    xz/xz_crc64.c
)
target_include_directories(chamelex_hn PRIVATE . hardnested hardnested/hardnested hardnested/pm3 xz)
target_compile_definitions(chamelex_hn PRIVATE _GNU_SOURCE XZ_USE_CRC64)
target_link_libraries(chamelex_hn Threads::Threads log m)
