package com.donotmiss.backend.abilityscore;

import java.math.BigDecimal;

public enum AbilityEvidenceSourceType {
    SELF_REPORT("0.4500"),
    PRIVATE_PERSONAL_RECORD("0.5000"),
    PUBLIC_CODE_REPOSITORY("0.7000"),
    RUNNABLE_PERSONAL_DEMO("0.8000"),
    SCHOOL_COURSE_OR_ASSIGNMENT("0.7500"),
    COMPANY_INTERNSHIP_OR_WORK("0.8500"),
    ORGANIZATION_ACTIVITY_RECORD("0.7500"),
    KNOWN_CONTEST_PLATFORM("0.9000"),
    OFFICIAL_CERTIFICATE_AUTHORITY("0.9000"),
    VERIFIED_THIRD_PARTY_RECORD("0.9500"),
    OTHER("0.5000");

    private final BigDecimal credibilityPrior;

    AbilityEvidenceSourceType(String credibilityPrior) {
        this.credibilityPrior = new BigDecimal(credibilityPrior);
    }

    public BigDecimal credibilityPrior() {
        return credibilityPrior;
    }
}
