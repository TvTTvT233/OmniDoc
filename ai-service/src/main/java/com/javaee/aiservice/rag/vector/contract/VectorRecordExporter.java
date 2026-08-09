package com.javaee.aiservice.rag.vector.contract;

/**
 * Internal migration source. This interface must not be exposed as a public user API.
 */
public interface VectorRecordExporter {

    VectorRecordPage exportPage(VectorExportRequest request);
}
