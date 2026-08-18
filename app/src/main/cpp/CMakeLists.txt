cmake_minimum_required(VERSION 3.22.1)
project(thinair)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine)

add_library(thinair SHARED
    jni_bridge.c
    ${ENGINE_DIR}/src/physiology.c
)

target_include_directories(thinair PRIVATE ${ENGINE_DIR}/include)
find_library(log-lib log)
target_link_libraries(thinair ${log-lib} m)
