package com.intentreactor.core.service;

/**
 * Session attribute keys used internally by the core execution engine.
 * All keys are package-private — external modules must not depend on these.
 */
final class CoreSessionKeys {

    static final String CONFIRMATION_REQUESTED_AT = "confirmationRequestedAt";
    static final String ORIGINAL_INTENT = "originalIntent";
    static final String PENDING_STEP = "pendingStep";
    static final String PENDING_MODIFIED_PARAMS = "pendingModifiedParameters";
    static final String THOUGHTS = "thoughts";

    private CoreSessionKeys() {
    }
}
