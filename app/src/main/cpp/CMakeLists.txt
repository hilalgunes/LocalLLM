cmake_minimum_required(VERSION 3.22.1)

project(rag_sqlitevec C)

add_library(rag_sqlitevec SHARED
    rag_sqlitevec_jni.c
    llamacpp_embedding_compat_jni.c
    sqlite-vec.c
    sqlite3.c
)

target_include_directories(rag_sqlitevec PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(rag_sqlitevec PRIVATE
    SQLITE_VEC_ENABLE_DISKANN=0
    SQLITE_VEC_ENABLE_RESCORE=0
)

find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(rag_sqlitevec
    ${log-lib}
    ${dl-lib}
)
