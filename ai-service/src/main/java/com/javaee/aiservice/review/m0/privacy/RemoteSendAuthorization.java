package com.javaee.aiservice.review.m0.privacy;

import com.javaee.aiservice.review.m0.model.RemoteCall;

public record RemoteSendAuthorization(
        Disposition disposition,
        byte[] payloadBytes,
        String payloadDigest,
        RemoteCall remoteCall
) {
    public RemoteSendAuthorization {
        payloadBytes = payloadBytes == null ? null : payloadBytes.clone();
    }

    @Override
    public byte[] payloadBytes() {
        return payloadBytes == null ? null : payloadBytes.clone();
    }

    public enum Disposition {
        SEND_AUTHORIZED,
        RESPONSE_ALREADY_RECORDED
    }
}
