cmake_minimum_required(VERSION 3.22.1)
project(djengine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(djengine SHARED engine.cpp)
target_compile_options(djengine PRIVATE -O3 -ffast-math)
target_link_libraries(djengine oboe::oboe android log)
