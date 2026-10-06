package com.example.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.BatchSize;

/**
 * 문항. 테이블 {@code item}. 태그는 조인 테이블 {@code item_tag(item_id, tag_id)} 로 잇는다.
 */
@Entity
@Table(name = "item")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private Unit unit;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String stem;

    /** 난이도 1~5. */
    @Column(nullable = false)
    private Integer level;

    /** 상태 코드 — {@link ItemStatus}. */
    @Column(nullable = false, length = 1)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "item_tag",
        joinColumns = @JoinColumn(name = "item_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id"))
    @OrderBy("id ASC")
    @BatchSize(size = ItemSearchCondition.PAGE_SIZE)
    private List<Tag> tags = new ArrayList<>();

    protected Item() {
        // JPA
    }

    public Item(Unit unit, String title, String stem, Integer level, String status, LocalDateTime createdAt) {
        this.unit = unit;
        this.title = title;
        this.stem = stem;
        this.level = level;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public Integer getId() {
        return id;
    }

    public Unit getUnit() {
        return unit;
    }

    public String getTitle() {
        return title;
    }

    public String getStem() {
        return stem;
    }

    public Integer getLevel() {
        return level;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void addTag(Tag tag) {
        this.tags.add(tag);
    }
}
