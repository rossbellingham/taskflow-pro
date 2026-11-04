package com.taskflow.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "task_links")
public class TaskLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_task_id", nullable = false)
    private Task source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_task_id", nullable = false)
    private Task target;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 20)
    private TaskLinkType type;

    public Long getId() { return id; }

    public Task getSource() { return source; }
    public void setSource(Task source) { this.source = source; }

    public Task getTarget() { return target; }
    public void setTarget(Task target) { this.target = target; }

    public TaskLinkType getType() { return type; }
    public void setType(TaskLinkType type) { this.type = type; }
}
