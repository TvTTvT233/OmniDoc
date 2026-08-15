package com.javaee.aiservice.review.m0.privacy;

import com.javaee.aiservice.review.m0.model.RemotePreview;

import java.util.List;

public record RemotePreviewView(RemotePreview preview, List<RemotePreviewSegment> segments) {
    public RemotePreviewView {
        segments = List.copyOf(segments);
    }
}
