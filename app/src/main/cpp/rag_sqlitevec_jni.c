#include <jni.h>
#include <sqlite3.h>

#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static const char *kDocumentsSql =
    "CREATE TABLE IF NOT EXISTS rag_documents ("
    "document_id TEXT PRIMARY KEY,"
    "display_name TEXT NOT NULL,"
    "text TEXT NOT NULL,"
    "chunk_count INTEGER NOT NULL)";

static const char *kChunksSql =
    "CREATE VIRTUAL TABLE IF NOT EXISTS rag_chunks USING vec0("
    "embedding float[384],"
    "document_id TEXT,"
    "document_name TEXT,"
    "chunk_index INTEGER,"
    "text TEXT"
    ")";

typedef struct RagNativeStore {
    sqlite3 *db;
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

static int ensure_schema(sqlite3 *db) {
    int rc = exec_sql(db, "PRAGMA journal_mode=WAL;");
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, "PRAGMA synchronous=NORMAL;");
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, "DROP TABLE IF EXISTS rag_chunks;");
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, "DROP TABLE IF EXISTS rag_documents;");
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, kDocumentsSql);
    if (rc != SQLITE_OK) return rc;
    rc = exec_sql(db, kChunksSql);
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

    int rc = sqlite3_open(db_path, &store->db);
    free(db_path);
    if (rc != SQLITE_OK) {
        const char *message = sqlite3_errmsg(store->db);
        throw_runtime(env, message != NULL ? message : "Unable to open sqlite database");
        if (store->db != NULL) sqlite3_close(store->db);
        free(store);
        return 0;
    }

    rc = ensure_schema(store->db);
    if (rc != SQLITE_OK) {
        const char *message = sqlite3_errmsg(store->db);
        throw_runtime(env, message != NULL ? message : "Unable to initialize sqlite-vec schema");
        sqlite3_close(store->db);
        free(store);
        return 0;
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

    jsize docCount = docIds != NULL ? (*env)->GetArrayLength(env, docIds) : 0;
    jsize chunkCount = chunkDocIds != NULL ? (*env)->GetArrayLength(env, chunkDocIds) : 0;
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
    rc = exec_sql(store->db, "DELETE FROM rag_chunks;");
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

            if (chunkEmbedding != NULL) {
                (*env)->ReleaseByteArrayElements(env, chunkEmbedding, embeddingBytes, JNI_ABORT);
            }
            if (chunkDocId != NULL) (*env)->ReleaseStringUTFChars(env, chunkDocId, docIdUtf);
            if (chunkDocName != NULL) (*env)->ReleaseStringUTFChars(env, chunkDocName, docNameUtf);
            if (chunkText != NULL) (*env)->ReleaseStringUTFChars(env, chunkText, chunkTextUtf);
            if (rc != SQLITE_DONE) {
                break;
            }
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
        throw_sqlite_stage_error(env, store->db, errorStage, rc);
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

    jbyte *embeddingBytes = (*env)->GetByteArrayElements(env, questionEmbedding, NULL);
    jsize embeddingLen = (*env)->GetArrayLength(env, questionEmbedding);
    if (embeddingBytes == NULL) {
        throw_runtime(env, "question embedding is empty");
        return NULL;
    }

    sqlite3_stmt *stmt = NULL;
    const char *sql =
        "SELECT document_id, document_name, chunk_index, text "
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
    }
    sqlite3_str_appendall(json, "]");
    sqlite3_finalize(stmt);

    if (rc != SQLITE_DONE) {
        sqlite3_str_finish(json);
        throw_runtime(env, sqlite3_errmsg(store->db));
        return NULL;
    }

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
    sqlite3_auto_extension((void (*)(void))sqlite3_vec_init);
    return JNI_VERSION_1_6;
}
