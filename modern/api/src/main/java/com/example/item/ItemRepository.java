package com.example.item;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Integer>, JpaSpecificationExecutor<Item> {

    /** 단건 조회 — 단원 · 태그를 함께 가져온다(OSIV 꺼져 있음). */
    @EntityGraph(attributePaths = {"unit", "tags"})
    Optional<Item> findWithDetailsById(Integer id);

    /** 단원 코드 + 상태로 조회. 정렬은 난이도 내림차순, 같은 난이도면 id 오름차순. */
    @EntityGraph(attributePaths = {"unit", "tags"})
    @Query("""
        select i from Item i
        where i.unit.code = :unitCode and i.status = :status
        order by i.level desc, i.id asc
        """)
    List<Item> findByUnitCodeAndStatus(@Param("unitCode") String unitCode, @Param("status") String status);

    long countByUnitIdAndStatus(Integer unitId, String status);

    /** 조건 검색 — 단원을 함께 가져온다. 태그(컬렉션)는 페이징과 함께 fetch 하지 않는다. */
    @Override
    @EntityGraph(attributePaths = {"unit"})
    Page<Item> findAll(Specification<Item> spec, Pageable pageable);
}
