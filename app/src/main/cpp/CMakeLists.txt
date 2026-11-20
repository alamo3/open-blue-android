cmake_minimum_required(VERSION 3.22.1)
project(openblue_fmod CXX)

# FMOD Engine runtime libraries (FMOD Studio by Firelight Technologies Pty Ltd), shipped in
# jniLibs and packaged from there; here they're only linked against.
set(FMOD_LIBS ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
add_library(fmod SHARED IMPORTED)
set_target_properties(fmod PROPERTIES IMPORTED_LOCATION ${FMOD_LIBS}/libfmod.so)
add_library(fmodstudio SHARED IMPORTED)
set_target_properties(fmodstudio PROPERTIES IMPORTED_LOCATION ${FMOD_LIBS}/libfmodstudio.so)

add_library(openblue_fmod SHARED fmod_bridge.cpp)
target_link_libraries(openblue_fmod fmodstudio fmod log)
# 16 KB pages (Android 15+ devices): align like the FMOD libraries are
target_link_options(openblue_fmod PRIVATE "-Wl,-z,max-page-size=16384")
