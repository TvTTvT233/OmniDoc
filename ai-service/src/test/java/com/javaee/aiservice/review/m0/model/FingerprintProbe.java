package com.javaee.aiservice.review.m0.model;

public final class FingerprintProbe {
    private FingerprintProbe() {
    }

    public static void main(String[] args) {
        System.out.print(ReviewInputFingerprint.calculate(
                ReviewM0ModelContractTest.fingerprintSpec(true)).sha256());
    }
}
