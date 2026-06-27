package com.intentreactor.strategies.search;

import lombok.Data;

/**
 * Captured reasoning pattern accumulated by {@link ReTreValPlanner} during a session.
 * Records whether a reasoning step succeeded or failed and why, to guide future tree expansions.
 */
@Data
public class RetrevalPattern {

    private String type;        // SUCCESS | FAILURE
    private String stepContent;
    private String failureType; // null for SUCCESS
    private double score;

    public RetrevalPattern() {
    }

    public RetrevalPattern(String type, String stepContent, String failureType, double score) {
        this.type = type;
        this.stepContent = stepContent;
        this.failureType = failureType;
        this.score = score;
    }
}
