cmake_minimum_required(VERSION 3.22.1)
project(lastsurvival)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_INSTALL ON CACHE BOOL "" FORCE)
set(SDL2_DISABLE_UNINSTALL ON CACHE BOOL "" FORCE)
set(SDL2_DISABLE_SDL2MAIN ON CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    SDL2
    URL https://github.com/libsdl-org/SDL/releases/download/release-2.30.11/SDL2-2.30.11.tar.gz
    URL_HASH SHA256=8b8d4aef2038533da814965220f88f77d60dfa0f32685f80ead65e501337da7f
)
FetchContent_MakeAvailable(SDL2)

add_library(main SHARED
    game.cpp
    audio.cpp
)

if (TARGET SDL2::SDL2)
    set(_sdl SDL2::SDL2)
elseif (TARGET SDL2)
    set(_sdl SDL2)
else()
    message(FATAL_ERROR "SDL2 target missing")
endif()

target_include_directories(main PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(main PRIVATE MA_NO_RUNTIME_LINKING)
target_link_libraries(main
    ${_sdl}
    android
    log
    aaudio
    OpenSLES
)
