package com.donotmiss.backend.mq;

public enum DomainEventOutboxStatus {
    PENDING,
    FAILED,
    SENT
}
