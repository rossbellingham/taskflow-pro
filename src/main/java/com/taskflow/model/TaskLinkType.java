package com.taskflow.model;

/**
 * A link goes from a source task to a target task:
 *  BLOCKS      source blocks target (so the target is "blocked by" the source)
 *  DUPLICATES  source is a duplicate of target
 *  RELATES_TO  loosely related (treated as two-way: A->B and B->A count as the same link)
 */
public enum TaskLinkType {
    BLOCKS,
    RELATES_TO,
    DUPLICATES
}
