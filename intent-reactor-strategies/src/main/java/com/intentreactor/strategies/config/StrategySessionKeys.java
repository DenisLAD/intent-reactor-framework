package com.intentreactor.strategies.config;

/**
 * Session attribute keys used by strategy planners.
 * Centralises all string constants to avoid magic strings across planner classes.
 */
public final class StrategySessionKeys {

    // Self-Ask
    public static final String SA_PHASE     = "sa_phase";
    public static final String SA_QUESTIONS = "sa_questions";
    public static final String SA_ANSWERS   = "sa_answers";
    public static final String SA_INDEX     = "sa_q_index";

    // Least-to-Most
    public static final String LTM_PHASE   = "ltm_phase";
    public static final String LTM_TASKS   = "ltm_tasks";
    public static final String LTM_RESULTS = "ltm_results";
    public static final String LTM_INDEX   = "ltm_index";

    // Plan-and-Solve
    public static final String PAS_PHASE     = "pas_phase";
    public static final String PAS_PLAN      = "pas_plan";
    public static final String PAS_STEP      = "pas_step";
    public static final String PAS_GOAL      = "pas_goal";
    public static final String PAS_MSG_START = "pas_msg_start";

    // Reflection
    public static final String REFLECTION_COUNT = "reflection_count";

    // Step-Back
    public static final String STEP_BACK_DONE = "step_back_done";

    // CoT / Zero-shot CoT
    public static final String COT_INJECTED    = "cot_injected";
    public static final String ZS_COT_INJECTED = "zs_cot_injected";

    // Self-Discover
    public static final String SD_PHASE   = "sd_phase";
    public static final String SD_MODULES = "sd_selected_modules";
    public static final String SD_PLAN    = "sd_plan";

    // STORM
    public static final String STORM_PHASE          = "storm_phase";
    public static final String STORM_PERSPECTIVES   = "storm_perspectives";
    public static final String STORM_PERSONA_INDEX  = "storm_persona_index";
    public static final String STORM_RESEARCH_STEPS = "storm_research_steps";
    public static final String STORM_GOAL           = "storm_goal";

    // Tree of Thoughts
    public static final String TOT_TREE = "tot_tree";
    public static final String TOT_GOAL = "tot_goal";

    // Graph of Thoughts
    public static final String GOT_GRAPH = "got_graph";
    public static final String GOT_GOAL  = "got_goal";

    // ReTreVal
    public static final String RETREVAL_TREE      = "retreval_tree";
    public static final String RETREVAL_PHASE     = "retreval_phase";
    public static final String RETREVAL_FRONTIER  = "retreval_frontier";
    public static final String RETREVAL_CUR_NODE  = "retreval_cur_node";
    public static final String RETREVAL_PATTERNS  = "retreval_patterns";
    public static final String RETREVAL_BACKTRACK = "retreval_backtrack";
    public static final String RETREVAL_GOAL      = "retreval_goal";

    // HTP
    public static final String HTP_PHASE            = "htp_phase";
    public static final String HTP_SUBGOALS         = "htp_subgoals";
    public static final String HTP_SUBGOAL_IDX      = "htp_subgoal_idx";
    public static final String HTP_STEPS            = "htp_steps";
    public static final String HTP_STEP_IDX         = "htp_step_idx";
    public static final String HTP_RESULTS          = "htp_results";
    public static final String HTP_REFINEMENT_COUNT = "htp_refine_count";
    public static final String HTP_NODE_MSG_START   = "htp_node_msg_start";

    // MAP
    public static final String MAP_PHASE = "map_phase";

    // KnowAgent
    public static final String KNOWAGENT_KB           = "knowagent_kb";
    public static final String KNOWAGENT_INITIALIZED  = "knowagent_initialized";
    public static final String KNOWAGENT_LAST_CONTEXT = "knowagent_last_context";

    // SAND
    public static final String SAND_TRAINING_LOG = "sand_training_log";
    public static final String SAND_STEP_COUNT   = "sand_step_count";

    private StrategySessionKeys() {}
}
