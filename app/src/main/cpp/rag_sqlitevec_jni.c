#include <jni.h>
#include <sqlite3.h>
#include <android/log.h>

#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifndef NDEBUG
#define SQLITE_VEC_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "SQLITE_VEC_DEBUG", "SQLITE_VEC_DEBUG | " __VA_ARGS__)
#define SQLITE_VEC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SQLITE_VEC_DEBUG", "SQLITE_VEC_DEBUG | " __VA_ARGS__)
#else
#define SQLITE_VEC_LOGD(...) ((void)0)
#define SQLITE_VEC_LOGE(...) ((void)0)
#endif

static long long elapsed_realtime_ms(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return (long long)value.tv_sec * 1000LL + value.tv_nsec / 1000000LL;
}

static const char *kDocumentsSql =
    "CREATE TABLE IF NOT EXISTS rag_documents ("
    "document_id TEXT PRIMARY KEY,"
    "display_name TEXT NOT NULL,"
    "text TEXT NOT NULL,"
    "chunk_count INTEGER NOT NULL)";

static const char *kMetaSql =
    "CREATE TABLE IF NOT EXISTS rag_meta ("
    "key TEXT PRIMARY KEY,"
    "value TEXT NOT NULL)";

typedef struct RagNativeStore {
    sqlite3 *db;
    int vector_dimension;
} RagNativeStore;

extern int sqlite3_vec_init(sqlite3 *db, char **pzErrMsg,
                            const sqlite3_api_routines *pApi);

static void throw_runtime(JNIEnv *env, const char *message) {
    jclass clazz = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (clazz != NULL) {
        (*env)->ThrowNew(env, clazz, message);
    }
}

static char *copy_jstring(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf == NULL) return NULL;
    size_t len = strlen(utf);
    char *copy = (char *)calloc(len + 1, sizeof(char));
    if (copy != NULL) {
        memcpy(copy, utf, len);
    }
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return copy;
}

static void json_append_escaped(sqlite3_str *builder, const unsigned char *text) {
    sqlite3_str_append(builder, "\"", 1);
    if (text != NULL) {
        for (const unsigned char *p = text; *p; ++p) {
            switch (*p) {
                case '\\': sqlite3_str_append(builder, "\\\\", 2); break;
                case '\"': sqlite3_str_append(builder, "\\\"", 2); break;
                case '\b': sqlite3_str_append(builder, "\\b", 2); break;
                case '\f': sqlite3_str_append(builder, "\\f", 2); break;
                case '\n': sqlite3_str_append(builder, "\\n", 2); break;
                case '\r': sqlite3_str_append(builder, "\\r", 2); break;
                case '\t': sqlite3_str_append(builder, "\\t", 2); break;
                default:
                    sqlite3_str_append(builder, (const char *)p, 1);
                    break;
            }
        }
    }
    sqlite3_str_append(builder, "\"", 1);
}

static RagNativeStore *store_from_handle(jlong handle) {
    return (RagNativeStore *)(intptr_t)handle;
}

static int exec_sql(sqlite3 *db, const char *sql) {
    char *err = NULL;
    int rc = sqlite3_exec(db, sql, NULL, NULL, &err);
    if (rc != SQLITE_OK && err != NULL) {
        sqlite3_free(err);
    }
    return rc;
}

static int create_chunks_table(sqlite3 *db, int dimension) {
    char sql[256];
    snprintf(sql, sizeof(sql),
             "CREATE VIRTUAL TABLE IF NOT EXISTS rag_chunks USING vec0("
             "embedding float[%d],"
             "document_id TEXT,"
             "document_name TEXT,"
             "chunk_index INTEGER,"
             "text TEXT"
             ")",
             dimension);
    SQLITE_VEC_LOGD("Creating/opening vec0 table | table=rag_chunks | dimensions=%d", dimension);
    int rc = exec_sql(db, sql);
    if (rc == SQLITE_OK) {
        SQLITE_VEC_LOGD("vec0 table ready | table=rag_chunks | dimensions=%d", dimension);
    } else {
        SQLITE_VEC_LOGE("vec0 table FAILED | table=rag_chunks | dimensions=%d | rc=%d | error=%s",
                        dimension, rc, sqlite3_errmsg(db));
    }
    return rc;
}

static void log_runtime_versions(sqlite3 *db) {
    SQLITE_VEC_LOGD("SQLite version: %s | sourceId=%s", sqlite3_libversion(), sqlite3_sourceid());
    sqlite3_stmt *stmt = NULL;
    int rc = sqlite3_prepare_v2(db, "SELECT vec_version();", -1, &stmt, NULL);
    if (rc == SQLITE_OK && sqlite3_step(stmt) == SQLITE_ROW) {
        const unsigned char *version = sqlite3_column_text(stmt, 0);
        SQLITE_VEC_LOGD("sqlite-vec initialization SUCCESS | runtimeVersion=%s",
                        version != NULL ? (const char *)version : "unknown");
    } else {
        SQLITE_VEC_LOGE("sqlite-vec initialization FAILED | rc=%d | error=%s", rc, sqlite3_errmsg(db));
    }
    if (stmt != NULL) sqlite3_finalize(stmt);
}

static int read_vector_dimension(sqlite3 *db) {
    sqlite3_stmt *stmt = NULL;
    int dimension = 0;
    int rc = sqlite3_prepare_v2(db, "SELECT value FROM rag_meta WHERE key = 'vector_dimension';", -1, &stmt, NULL);
    if (rc != SQLITE_OK) return 0;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        dimension = sqlite3_column_int(stmt, 0);
    }
    sqlite3_finalize(stmt);
    return dimension;
}

static int write_vector_dimension(sqlite3 *db, int dimension) {
    sqlite3_stmt *stmt = NULL;
    int rc = sqlite3_prepare_v2(
        db,
        "INSERT INTO rag_meta(key, value) VALUES('vector_dimension', ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value;",
        -1,
        &stmt,
        NULL
    );
    if (rc != SQLITE_OK) return rc;
    sqlite3_bind_int(stmt, 1, dimension);
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    return rc == SQLITE_DONE ? SQLITE_OK : rc;
}

static int ensure_vector_schema(sqlite3 *db, int dimension) {
    int stored_dimension = read_vector_dimension(db);
    if (stored_dimension > 0 && stored_dimension != dimension) {
        int rc = exec_sql(db, "DROP TABLE IF EXISTS rag_chunks;");
        if (rc != SQLITE_OK) return rc;
    }

    int rc = create_chunks_table(db, dimension);
    if (rc != SQLITE_OK) return rc;
    rc = write_vector_dimension(db, dimension);
    return rc;
}

static int ensure_schema(sqlite3 *db) {
    int rc = exec_sql(db, "PRAGMA journal_mode=WAL;");
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, "PRAGMA synchronous=NORMAL;");
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, kDocumentsSql);
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, kMetaSql);
    if (rc != SQLITE_OK) return rc;
    int dimension = read_vector_dimension(db);
    if (dimension <= 0) dimension = 384;
    rc = ensure_vector_schema(db, dimension);
    return rc;
}

static int initialize_fresh_schema(sqlite3 *db, int dimension) {
    int rc = exec_sql(db, "PRAGMA journal_mode=WAL;");
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, "PRAGMA synchronous=NORMAL;");
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, kDocumentsSql);
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, kMetaSql);
    if (rc != SQLITE_OK) return rc;
    rc = create_chunks_table(db, dimension);
    if (rc != SQLITE_OK) return rc;
    return write_vector_dimension(db, dimension);
}

static int recreate_vector_store(RagNativeStore *store, int dimension) {
    const char *currentPath = sqlite3_db_filename(store->db, "main");
    char *dbPath = currentPath != NULL ? sqlite3_mprintf("%s", currentPath) : NULL;
    if (dbPath == NULL) return SQLITE_NOMEM;

    int rc = sqlite3_close(store->db);
    if (rc != SQLITE_OK) {
        sqlite3_free(dbPath);
        return rc;
    }
    store->db = NULL;

    char *walPath = sqlite3_mprintf("%s-wal", dbPath);
    char *shmPath = sqlite3_mprintf("%s-shm", dbPath);
    (void)remove(dbPath);
    if (walPath != NULL) (void)remove(walPath);
    if (shmPath != NULL) (void)remove(shmPath);
    sqlite3_free(walPath);
    sqlite3_free(shmPath);

    rc = sqlite3_open(dbPath, &store->db);
    sqlite3_free(dbPath);
    if (rc != SQLITE_OK) return rc;

    rc = initialize_fresh_schema(store->db, dimension);
    if (rc == SQLITE_OK) {
        store->vector_dimension = dimension;
    }
    return rc;
}

static int begin_tx(sqlite3 *db) {
    return exec_sql(db, "BEGIN IMMEDIATE TRANSACTION;");
}

static int commit_tx(sqlite3 *db) {
    return exec_sql(db, "COMMIT;");
}

static int rollback_tx(sqlite3 *db) {
    return exec_sql(db, "ROLLBACK;");
}

static void maybe_rollback(sqlite3 *db) {
    if (db != NULL && !sqlite3_get_autocommit(db)) {
        (void)rollback_tx(db);
    }
}

static void throw_sqlite_stage_error(JNIEnv *env, sqlite3 *db, const char *stage, int rc) {
    const char *message = db != NULL ? sqlite3_errmsg(db) : "sqlite error";
    char buffer[512];
    snprintf(buffer, sizeof(buffer), "%s (stage=%s, rc=%d)", message != NULL ? message : "sqlite error", stage, rc);
    throw_runtime(env, buffer);
}

static int clear_vec0_rows(sqlite3 *db) {
    sqlite3_stmt *deleteStmt = NULL;
    int rc = sqlite3_prepare_v2(db, "DELETE FROM rag_chunks;", -1, &deleteStmt, NULL);
    if (rc != SQLITE_OK) {
        SQLITE_VEC_LOGD("vec0 clear prepare unsupported | rc=%d | error=%s", rc, sqlite3_errmsg(db));
        return rc;
    }

    rc = sqlite3_step(deleteStmt);
    if (rc != SQLITE_DONE) {
        SQLITE_VEC_LOGD("vec0 clear step unsupported | rc=%d | error=%s", rc, sqlite3_errmsg(db));
    }
    sqlite3_finalize(deleteStmt);
    return rc == SQLITE_DONE ? SQLITE_OK : rc;
}

JNIEXPORT jlong JNICALL
Java_com_hilal_llm_rag_RagVectorStoreNative_open(JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    char *db_path = copy_jstring(env, path);
    if (db_path == NULL) {
        throw_runtime(env, "Database path is null");
        return 0;
    }

    RagNativeStore *store = (RagNativeStore *)calloc(1, sizeof(RagNativeStore));
    if (store == NULL) {
        free(db_path);
        throw_runtime(env, "Out of memory");
        return 0;
    }

    SQLITE_VEC_LOGD("Opening vector store");
    int rc = sqlite3_open(db_path, &store->db);
    free(db_path);
    if (rc != SQLITE_OK) {
        const char *message = sqlite3_errmsg(store->db);
        throw_runtime(env, message != NULL ? message : "Unable to open sqlite database");
        if (store->db != NULL) sqlite3_close(store->db);
        free(store);
        return 0;
    }

    log_runtime_versions(store->db);

    rc = ensure_schema(store->db);
    if (rc != SQLITE_OK) {
        const char *message = sqlite3_errmsg(store->db);
        throw_runtime(env, message != NULL ? message : "Unable to initialize sqlite-vec schema");
        sqlite3_close(store->db);
        free(store);
        return 0;
    }
    store->vector_dimension = read_vector_dimension(store->db);
    if (store->vector_dimension <= 0) {
        store->vector_dimension = 384;
    }

    return (jlong)(intptr_t)store;
}

JNIEXPORT void JNICALL
Java_com_hilal_llm_rag_RagVectorStoreNative_close(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    RagNativeStore *store = store_from_handle(handle);
    if (store == NULL) return;
    if (store->db != NULL) {
        sqlite3_close(store->db);
    }
    free(store);
}

static int bind_text(sqlite3_stmt *stmt, int index, const char *value) {
    return sqlite3_bind_text(stmt, index, value != NULL ? value : "", -1, SQLITE_TRANSIENT);
}

JNIEXPORT jint JNICALL
Java_com_hilal_llm_rag_RagVectorStoreNative_replaceAll(
    JNIEnv *env,
    jobject thiz,
    jlong handle,
    jobjectArray docIds,
    jobjectArray docNames,
    jobjectArray docTexts,
    jintArray docChunkCounts,
    jobjectArray chunkDocIds,
    jobjectArray chunkDocNames,
    jintArray chunkIndices,
    jobjectArray chunkTexts,
    jobjectArray chunkEmbeddings
) {
    (void)thiz;
    RagNativeStore *store = store_from_handle(handle);
    if (store == NULL || store->db == NULL) {
        throw_runtime(env, "sqlite store is not open");
        return SQLITE_ERROR;
    }

    long long indexingStartedAt = elapsed_realtime_ms();
    jsize docCount = docIds != NULL ? (*env)->GetArrayLength(env, docIds) : 0;
    jsize chunkCount = chunkDocIds != NULL ? (*env)->GetArrayLength(env, chunkDocIds) : 0;
    int vectorDimension = store->vector_dimension > 0 ? store->vector_dimension : 384;
    if (chunkCount > 0 && chunkEmbeddings != NULL) {
        jbyteArray firstEmbedding = (jbyteArray)(*env)->GetObjectArrayElement(env, chunkEmbeddings, 0);
        jsize firstEmbeddingLen = firstEmbedding != NULL ? (*env)->GetArrayLength(env, firstEmbedding) : 0;
        if (firstEmbedding != NULL) {
            (*env)->DeleteLocalRef(env, firstEmbedding);
        }
        if (firstEmbeddingLen <= 0 || firstEmbeddingLen % 4 != 0) {
            throw_runtime(env, "Invalid embedding byte length for sqlite-vec.");
            return SQLITE_ERROR;
        }
        vectorDimension = firstEmbeddingLen / 4;
        int schemaRc = ensure_vector_schema(store->db, vectorDimension);
        if (schemaRc != SQLITE_OK) {
            throw_sqlite_stage_error(env, store->db, "ensure vector schema", schemaRc);
            return SQLITE_ERROR;
        }
        store->vector_dimension = vectorDimension;
    }
    SQLITE_VEC_LOGD("Native indexing started | documents=%d | chunks=%d | embeddingDimension=%d",
                    (int)docCount, (int)chunkCount, vectorDimension);
    if (docCount == 0 && chunkCount == 0) {
        SQLITE_VEC_LOGD("Clearing vector store");
    }
    int insertedVectorCount = 0;
    jint *docCounts = docChunkCounts != NULL ? (*env)->GetIntArrayElements(env, docChunkCounts, NULL) : NULL;
    jint *indices = chunkIndices != NULL ? (*env)->GetIntArrayElements(env, chunkIndices, NULL) : NULL;

    int rc = begin_tx(store->db);
    if (rc != SQLITE_OK) {
        if (docCounts != NULL) (*env)->ReleaseIntArrayElements(env, docChunkCounts, docCounts, JNI_ABORT);
        if (indices != NULL) (*env)->ReleaseIntArrayElements(env, chunkIndices, indices, JNI_ABORT);
        throw_sqlite_stage_error(env, store->db, "begin transaction", rc);
        return SQLITE_ERROR;
    }

    const char *errorStage = "delete rag_chunks";
    rc = clear_vec0_rows(store->db);
    if (rc == SQLITE_ERROR) {
        SQLITE_VEC_LOGD("vec0 DELETE unsupported; recreating vector store | dimensions=%d", vectorDimension);
        maybe_rollback(store->db);
        errorStage = "recreate vector store after vec0 delete failure";
        rc = recreate_vector_store(store, vectorDimension);
        if (rc == SQLITE_OK) {
            errorStage = "begin transaction after vector store recreation";
            rc = begin_tx(store->db);
        }
    }
    if (rc == SQLITE_OK) {
        errorStage = "delete rag_documents";
        rc = exec_sql(store->db, "DELETE FROM rag_documents;");
    }

    sqlite3_stmt *docStmt = NULL;
    sqlite3_stmt *chunkStmt = NULL;
    if (rc == SQLITE_OK) {
        errorStage = "prepare document insert";
        rc = sqlite3_prepare_v2(store->db,
                                "INSERT INTO rag_documents(document_id, display_name, text, chunk_count) VALUES (?, ?, ?, ?);",
                                -1, &docStmt, NULL);
    }
    if (rc == SQLITE_OK) {
        errorStage = "prepare chunk insert";
        rc = sqlite3_prepare_v2(store->db,
                                "INSERT INTO rag_chunks(embedding, document_id, document_name, chunk_index, text) VALUES (?, ?, ?, ?, ?);",
                                -1, &chunkStmt, NULL);
    }

    if (rc == SQLITE_OK) {
        for (jsize i = 0; i < docCount; ++i) {
            errorStage = "insert document";
            jstring docId = (jstring)(*env)->GetObjectArrayElement(env, docIds, i);
            jstring docName = (jstring)(*env)->GetObjectArrayElement(env, docNames, i);
            jstring docText = (jstring)(*env)->GetObjectArrayElement(env, docTexts, i);
            const char *docIdUtf = docId != NULL ? (*env)->GetStringUTFChars(env, docId, NULL) : "";
            const char *docNameUtf = docName != NULL ? (*env)->GetStringUTFChars(env, docName, NULL) : "";
            const char *docTextUtf = docText != NULL ? (*env)->GetStringUTFChars(env, docText, NULL) : "";

            sqlite3_reset(docStmt);
            sqlite3_clear_bindings(docStmt);
            sqlite3_bind_text(docStmt, 1, docIdUtf, -1, SQLITE_TRANSIENT);
            sqlite3_bind_text(docStmt, 2, docNameUtf, -1, SQLITE_TRANSIENT);
            sqlite3_bind_text(docStmt, 3, docTextUtf, -1, SQLITE_TRANSIENT);
            sqlite3_bind_int(docStmt, 4, docCounts != NULL ? docCounts[i] : 0);
            rc = sqlite3_step(docStmt);

            if (docId != NULL) (*env)->ReleaseStringUTFChars(env, docId, docIdUtf);
            if (docName != NULL) (*env)->ReleaseStringUTFChars(env, docName, docNameUtf);
            if (docText != NULL) (*env)->ReleaseStringUTFChars(env, docText, docTextUtf);
            if (rc != SQLITE_DONE) {
                break;
            }
            rc = SQLITE_OK;
        }
    }

    if (rc == SQLITE_OK) {
        for (jsize i = 0; i < chunkCount; ++i) {
            errorStage = "insert chunk";
            jstring chunkDocId = (jstring)(*env)->GetObjectArrayElement(env, chunkDocIds, i);
            jstring chunkDocName = (jstring)(*env)->GetObjectArrayElement(env, chunkDocNames, i);
            jstring chunkText = (jstring)(*env)->GetObjectArrayElement(env, chunkTexts, i);
            jobject chunkEmbeddingObj = (*env)->GetObjectArrayElement(env, chunkEmbeddings, i);
            jbyteArray chunkEmbedding = (jbyteArray)chunkEmbeddingObj;
            const char *docIdUtf = chunkDocId != NULL ? (*env)->GetStringUTFChars(env, chunkDocId, NULL) : "";
            const char *docNameUtf = chunkDocName != NULL ? (*env)->GetStringUTFChars(env, chunkDocName, NULL) : "";
            const char *chunkTextUtf = chunkText != NULL ? (*env)->GetStringUTFChars(env, chunkText, NULL) : "";
            jbyte *embeddingBytes = chunkEmbedding != NULL ? (*env)->GetByteArrayElements(env, chunkEmbedding, NULL) : NULL;
            jsize embeddingLen = chunkEmbedding != NULL ? (*env)->GetArrayLength(env, chunkEmbedding) : 0;
            if (embeddingLen != vectorDimension * 4) {
                rc = SQLITE_MISMATCH;
                errorStage = "embedding dimension mismatch";
            }

            if (rc == SQLITE_OK) {
                sqlite3_reset(chunkStmt);
                sqlite3_clear_bindings(chunkStmt);
                if (embeddingBytes != NULL) {
                    sqlite3_bind_blob(chunkStmt, 1, embeddingBytes, embeddingLen, SQLITE_TRANSIENT);
                } else {
                    sqlite3_bind_null(chunkStmt, 1);
                }
                bind_text(chunkStmt, 2, docIdUtf);
                bind_text(chunkStmt, 3, docNameUtf);
                sqlite3_bind_int(chunkStmt, 4, indices != NULL ? indices[i] : 0);
                bind_text(chunkStmt, 5, chunkTextUtf);
                rc = sqlite3_step(chunkStmt);
            }

            if (chunkEmbedding != NULL) {
                (*env)->ReleaseByteArrayElements(env, chunkEmbedding, embeddingBytes, JNI_ABORT);
            }
            if (chunkDocId != NULL) (*env)->ReleaseStringUTFChars(env, chunkDocId, docIdUtf);
            if (chunkDocName != NULL) (*env)->ReleaseStringUTFChars(env, chunkDocName, docNameUtf);
            if (chunkText != NULL) (*env)->ReleaseStringUTFChars(env, chunkText, chunkTextUtf);
            if (rc != SQLITE_DONE) {
                break;
            }
            insertedVectorCount++;
            rc = SQLITE_OK;
        }
    }

    if (docStmt != NULL) sqlite3_finalize(docStmt);
    if (chunkStmt != NULL) sqlite3_finalize(chunkStmt);

    if (rc == SQLITE_OK) {
        errorStage = "commit transaction";
        rc = commit_tx(store->db);
    } else {
        maybe_rollback(store->db);
    }

    if (docCounts != NULL) (*env)->ReleaseIntArrayElements(env, docChunkCounts, docCounts, JNI_ABORT);
    if (indices != NULL) (*env)->ReleaseIntArrayElements(env, chunkIndices, indices, JNI_ABORT);

    if (rc != SQLITE_OK) {
        SQLITE_VEC_LOGE("Native indexing FAILED | stage=%s | rc=%d | inserted=%d | error=%s",
                        errorStage, rc, insertedVectorCount, sqlite3_errmsg(store->db));
        throw_sqlite_stage_error(env, store->db, errorStage, rc);
    } else {
        SQLITE_VEC_LOGD("Native indexing completed | vectors inserted=%d | elapsedMs=%lld",
                        insertedVectorCount, elapsed_realtime_ms() - indexingStartedAt);
        if (docCount == 0 && chunkCount == 0) {
            SQLITE_VEC_LOGD("Vector store cleared | vectors=0");
        }
    }

    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_hilal_llm_rag_RagVectorStoreNative_snapshotJson(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    RagNativeStore *store = store_from_handle(handle);
    if (store == NULL || store->db == NULL) {
        throw_runtime(env, "sqlite store is not open");
        return NULL;
    }

    sqlite3_stmt *docStmt = NULL;
    sqlite3_stmt *chunkStmt = NULL;
    int rc = sqlite3_prepare_v2(store->db,
                                "SELECT document_id, display_name, text, chunk_count FROM rag_documents ORDER BY display_name COLLATE NOCASE ASC;",
                                -1, &docStmt, NULL);
    if (rc != SQLITE_OK) {
        throw_runtime(env, sqlite3_errmsg(store->db));
        return NULL;
    }
    rc = sqlite3_prepare_v2(store->db,
                            "SELECT document_id, document_name, chunk_index, text FROM rag_chunks ORDER BY document_name COLLATE NOCASE ASC, chunk_index ASC, rowid ASC;",
                            -1, &chunkStmt, NULL);
    if (rc != SQLITE_OK) {
        sqlite3_finalize(docStmt);
        throw_runtime(env, sqlite3_errmsg(store->db));
        return NULL;
    }

    sqlite3_str *json = sqlite3_str_new(store->db);
    sqlite3_str_appendall(json, "{\"documents\":[");
    int first = 1;
    while ((rc = sqlite3_step(docStmt)) == SQLITE_ROW) {
        if (!first) sqlite3_str_appendall(json, ",");
        first = 0;
        sqlite3_str_appendall(json, "{\"documentId\":");
        json_append_escaped(json, sqlite3_column_text(docStmt, 0));
        sqlite3_str_appendall(json, ",\"displayName\":");
        json_append_escaped(json, sqlite3_column_text(docStmt, 1));
        sqlite3_str_appendall(json, ",\"text\":");
        json_append_escaped(json, sqlite3_column_text(docStmt, 2));
        sqlite3_str_appendf(json, ",\"chunkCount\":%d}", sqlite3_column_int(docStmt, 3));
    }
    sqlite3_str_appendall(json, "],\"chunks\":[");
    first = 1;
    while ((rc = sqlite3_step(chunkStmt)) == SQLITE_ROW) {
        if (!first) sqlite3_str_appendall(json, ",");
        first = 0;
        sqlite3_str_appendall(json, "{\"documentId\":");
        json_append_escaped(json, sqlite3_column_text(chunkStmt, 0));
        sqlite3_str_appendall(json, ",\"documentName\":");
        json_append_escaped(json, sqlite3_column_text(chunkStmt, 1));
        sqlite3_str_appendf(json, ",\"chunkIndex\":%d,\"text\":", sqlite3_column_int(chunkStmt, 2));
        json_append_escaped(json, sqlite3_column_text(chunkStmt, 3));
        sqlite3_str_appendall(json, "}");
    }
    sqlite3_str_appendall(json, "]}");

    sqlite3_finalize(docStmt);
    sqlite3_finalize(chunkStmt);
    if (rc != SQLITE_DONE) {
        sqlite3_str_finish(json);
        throw_runtime(env, sqlite3_errmsg(store->db));
        return NULL;
    }

    char *result = sqlite3_str_finish(json);
    if (result == NULL) {
        throw_runtime(env, "Unable to build snapshot json");
        return NULL;
    }
    jstring output = (*env)->NewStringUTF(env, result);
    sqlite3_free(result);
    return output;
}

JNIEXPORT jstring JNICALL
Java_com_hilal_llm_rag_RagVectorStoreNative_rank(
    JNIEnv *env,
    jobject thiz,
    jlong handle,
    jbyteArray questionEmbedding,
    jint limit
) {
    (void)thiz;
    RagNativeStore *store = store_from_handle(handle);
    if (store == NULL || store->db == NULL) {
        throw_runtime(env, "sqlite store is not open");
        return NULL;
    }

    long long knnStartedAt = elapsed_realtime_ms();
    jbyte *embeddingBytes = (*env)->GetByteArrayElements(env, questionEmbedding, NULL);
    jsize embeddingLen = (*env)->GetArrayLength(env, questionEmbedding);
    if (embeddingBytes == NULL) {
        throw_runtime(env, "question embedding is empty");
        return NULL;
    }
    if (store->vector_dimension > 0 && embeddingLen != store->vector_dimension * 4) {
        (*env)->ReleaseByteArrayElements(env, questionEmbedding, embeddingBytes, JNI_ABORT);
        throw_runtime(env, "question embedding dimension does not match sqlite-vec index dimension");
        return NULL;
    }
    SQLITE_VEC_LOGD("Executing sqlite-vec KNN | table=rag_chunks | queryDimension=%d | requestedK=%d",
                    (int)(embeddingLen / 4), (int)limit);

    sqlite3_stmt *stmt = NULL;
    const char *sql =
        "SELECT document_id, document_name, chunk_index, text, distance "
        "FROM rag_chunks "
        "WHERE embedding MATCH ? "
        "ORDER BY distance "
        "LIMIT ?;";
    int rc = sqlite3_prepare_v2(store->db, sql, -1, &stmt, NULL);
    if (rc != SQLITE_OK) {
        (*env)->ReleaseByteArrayElements(env, questionEmbedding, embeddingBytes, JNI_ABORT);
        throw_runtime(env, sqlite3_errmsg(store->db));
        return NULL;
    }

    sqlite3_bind_blob(stmt, 1, embeddingBytes, embeddingLen, SQLITE_TRANSIENT);
    sqlite3_bind_int(stmt, 2, limit);
    (*env)->ReleaseByteArrayElements(env, questionEmbedding, embeddingBytes, JNI_ABORT);

    sqlite3_str *json = sqlite3_str_new(store->db);
    sqlite3_str_appendall(json, "[");
    int first = 1;
    int resultCount = 0;
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        if (!first) sqlite3_str_appendall(json, ",");
        first = 0;
        sqlite3_str_appendall(json, "{\"documentId\":");
        json_append_escaped(json, sqlite3_column_text(stmt, 0));
        sqlite3_str_appendall(json, ",\"documentName\":");
        json_append_escaped(json, sqlite3_column_text(stmt, 1));
        sqlite3_str_appendf(json, ",\"chunkIndex\":%d,\"text\":", sqlite3_column_int(stmt, 2));
        json_append_escaped(json, sqlite3_column_text(stmt, 3));
        sqlite3_str_appendall(json, "}");
        SQLITE_VEC_LOGD("result[%d] | documentId=%s | documentName=%s | chunkIndex=%d | distance=%.8f",
                        resultCount,
                        sqlite3_column_text(stmt, 0) != NULL ? (const char *)sqlite3_column_text(stmt, 0) : "",
                        sqlite3_column_text(stmt, 1) != NULL ? (const char *)sqlite3_column_text(stmt, 1) : "",
                        sqlite3_column_int(stmt, 2),
                        sqlite3_column_double(stmt, 4));
        resultCount++;
    }
    sqlite3_str_appendall(json, "]");
    sqlite3_finalize(stmt);

    if (rc != SQLITE_DONE) {
        SQLITE_VEC_LOGE("KNN FAILED | rc=%d | error=%s", rc, sqlite3_errmsg(store->db));
        sqlite3_str_finish(json);
        throw_runtime(env, sqlite3_errmsg(store->db));
        return NULL;
    }
    SQLITE_VEC_LOGD("KNN native completed | resultCount=%d | elapsedMs=%lld",
                    resultCount, elapsed_realtime_ms() - knnStartedAt);

    char *result = sqlite3_str_finish(json);
    if (result == NULL) {
        throw_runtime(env, "Unable to build rank json");
        return NULL;
    }
    jstring output = (*env)->NewStringUTF(env, result);
    sqlite3_free(result);
    return output;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    SQLITE_VEC_LOGD("Native library loaded | library=librag_sqlitevec.so");
    SQLITE_VEC_LOGD("sqlite-vec initialization started | operation=sqlite3_auto_extension registration");
    int rc = sqlite3_auto_extension((void (*)(void))sqlite3_vec_init);
    if (rc != SQLITE_OK) {
        SQLITE_VEC_LOGE("sqlite-vec initialization FAILED | operation=auto_extension registration | rc=%d", rc);
        return JNI_ERR;
    }
    SQLITE_VEC_LOGD("sqlite-vec auto-extension registration SUCCESS");
    return JNI_VERSION_1_6;
}
