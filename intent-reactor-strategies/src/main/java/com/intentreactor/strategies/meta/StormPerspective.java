package com.intentreactor.strategies.meta;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents one expert persona in the STORM planner with accumulated research notes.
 */
@Data
public class StormPerspective {

    private String name;
    private String viewpoint;
    private List<String> notes = new ArrayList<>();

    public StormPerspective() {
    }

    public StormPerspective(String name, String viewpoint) {
        this.name = name;
        this.viewpoint = viewpoint;
    }
}
