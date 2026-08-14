package com.scanops.subscription;

public enum SubscriptionStatus {
    /** 7일 무료체험 중. PRO 플랜에서만 가능. */
    TRIALING,
    ACTIVE,
    CANCELED,
    PAST_DUE
}
