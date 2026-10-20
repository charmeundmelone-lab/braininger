# Spracherkennung am Handy: whisper.cpp (MIT-Lizenz, github.com/ggml-org/whisper.cpp).
# Der Quellcode liegt nicht im Repo; der Bau auf GitHub lädt v1.9.5 nach android/app/whisper.cpp.
cmake_minimum_required(VERSION 3.22)
project(braininger_whisper C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../whisper.cpp)

# Gemessen am 20.10.2026 auf dem Phone (3a): 8-Bit-Befehle (i8mm) machen large-v3-turbo q8_0 dreimal schneller
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper-build)

add_library(braininger_whisper SHARED whisper_jni.cpp)
target_link_libraries(braininger_whisper whisper log)
