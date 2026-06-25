package com.intentreactor.strategies.search;

import lombok.Data;

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
