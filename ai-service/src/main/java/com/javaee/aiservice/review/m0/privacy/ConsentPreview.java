package com.javaee.aiservice.review.m0.privacy;

import com.javaee.aiservice.review.m0.model.RemoteConsent;

public record ConsentPreview(
        RemoteConsent consent,
        RemotePreviewView preview,
        boolean currentConfirmation
) {
}
