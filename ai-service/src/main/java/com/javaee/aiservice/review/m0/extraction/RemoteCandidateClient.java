package com.javaee.aiservice.review.m0.extraction;

@FunctionalInterface
public interface RemoteCandidateClient {
    RemoteExtractionResult extract(byte[] confirmedPayloadBytes) throws RemoteCandidateClientException;
}
