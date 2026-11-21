cmake_minimum_required(VERSION 3.22.1)
project(openblue_native CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# FMOD Engine runtime libraries (FMOD Studio by Firelight Technologies Pty Ltd), shipped in
# jniLibs and packaged from there; here they're only linked against.
set(FMOD_LIBS ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
add_library(fmod SHARED IMPORTED)
set_target_properties(fmod PROPERTIES IMPORTED_LOCATION ${FMOD_LIBS}/libfmod.so)
add_library(fmodstudio SHARED IMPORTED)
set_target_properties(fmodstudio PROPERTIES IMPORTED_LOCATION ${FMOD_LIBS}/libfmodstudio.so)

add_library(openblue_fmod SHARED fmod_bridge.cpp)
target_link_libraries(openblue_fmod fmodstudio fmod log m)
# 16 KB pages (Android 15+ devices): align like the FMOD libraries are
target_link_options(openblue_fmod PRIVATE "-Wl,-z,max-page-size=16384")

# The shared native sound engine (soundcore/: copied from virtual-car-sound/soundcore by its
# sync_to_app.sh -- edit it there), behind a small JNI bridge (SoundCore.kt). Always optimised:
# it synthesises every sample, and debug builds would otherwise run it at -O0.
file(GLOB SOUNDCORE_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/soundcore/*.cpp)
add_library(soundcore_jni SHARED soundcore_jni.cpp ${SOUNDCORE_SOURCES})
target_compile_options(soundcore_jni PRIVATE -O2)
target_link_libraries(soundcore_jni log m)
target_link_options(soundcore_jni PRIVATE "-Wl,-z,max-page-size=16384")
