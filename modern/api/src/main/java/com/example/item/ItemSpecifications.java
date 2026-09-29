package com.example.item;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * 문항 검색 조건 → JPA 조건. 레거시 {@code search.php} 의 WHERE 조립을 옮긴다. 모든 조건은 AND 로 묶는다(BR-02).
 */
final class ItemSpecifications {

    private ItemSpecifications() {
    }

    static Specification<Item> matching(ItemSearchCondition condition) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // BR-01 공개 문항만 (레거시 뷰 v_item_public 의 status = 'A')
            predicates.add(cb.equal(root.get("status"), ItemStatus.ACTIVE));

            // BR-04 제목 또는 지문 부분 일치. % 와 _ 는 이스케이프하지 않고 와일드카드로 둔다.
            if (condition.keyword() != null) {
                String like = "%" + condition.keyword() + "%";
                predicates.add(cb.or(cb.like(root.get("title"), like), cb.like(root.get("stem"), like)));
            }

            // BR-06 단원 코드 완전 일치. 형식이 틀리거나 없는 코드여도 그대로 조회한다.
            if (condition.unitCode() != null) {
                Join<Item, Unit> unit = root.join("unit");
                predicates.add(cb.equal(unit.get("code"), condition.unitCode()));
            }

            // BR-07 · BR-08 · BR-09 난이도
            Long level = condition.level();
            if (level == null) {
                predicates.add(cb.lessThan(root.get("level"), ItemSearchCondition.EMPTY_LEVEL_UPPER_BOUND));
            } else if (level < Integer.MIN_VALUE || level > Integer.MAX_VALUE) {
                // 정수 컬럼과 같을 수 없는 값 — 레거시와 같이 0건
                predicates.add(cb.disjunction());
            } else {
                predicates.add(cb.equal(root.get("level"), level.intValue()));
            }

            // BR-10 태그 이름 완전 일치. join 대신 EXISTS 라 행이 중복되지 않는다.
            if (condition.tag() != null) {
                Subquery<Integer> tagged = query.subquery(Integer.class);
                Root<Item> sub = tagged.from(Item.class);
                Join<Item, Tag> tag = sub.join("tags");
                tagged.select(sub.get("id"))
                    .where(cb.equal(sub.get("id"), root.get("id")), cb.equal(tag.get("name"), condition.tag()));
                predicates.add(cb.exists(tagged));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
