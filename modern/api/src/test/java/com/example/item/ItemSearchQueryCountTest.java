package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

/** 검색 결과의 태그(LAZY) 로딩 쿼리 수 회귀 — 행마다 태그 SELECT 가 나가는 N+1 이 없어야 한다. */
@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemSearchQueryCountTest {

    /** 검색 결과 한 페이지에 들어갈 태그 있는 문항 수(한 페이지 20건 이하). */
    private static final int TAGGED_ITEM_COUNT = 6;
    /** 난이도 2(빈 난이도 조건 level < 5 에 걸리는 값). */
    private static final int LEVEL = 2;
    /** 한 페이지의 태그를 모두 읽는 데 허용하는 SELECT 수. */
    private static final long EXPECTED_TAG_SELECTS = 1L;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    private Statistics statistics;

    @BeforeEach
    void seed() {
        Unit unit = entityManager.persist(ItemFixtures.unit(2, "M5-2", "분수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));
        for (int i = 0; i < TAGGED_ITEM_COUNT; i++) {
            Tag[] tags = i % 2 == 0 ? new Tag[] {calc} : new Tag[] {calc, word};
            entityManager.persist(ItemFixtures.item(null, unit, "분수 곱셈 " + i, LEVEL, ItemStatus.ACTIVE, tags));
        }
        entityManager.flush();
        entityManager.clear();

        statistics = entityManager.getEntityManager().getEntityManagerFactory()
            .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    @DisplayName("검색 한 페이지의 행마다 태그를 읽어도 태그 SELECT 는 행 수만큼 늘지 않는다(N+1 회귀)")
    void searchPageLoadsTagsWithoutNPlusOne() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("unit", List.of("M5-2"));
        ItemSearchCondition condition = ItemSearchCondition.from(params);
        Page<Item> page = itemRepository.findAll(ItemSpecifications.matching(condition), condition.toPageable());
        assertThat(page.getContent()).hasSize(TAGGED_ITEM_COUNT);

        statistics.clear();
        List<ItemSearchRow> rows = page.getContent().stream()
            .map(ItemSearchRow::from)
            .toList();

        assertThat(rows).allSatisfy(row -> assertThat(row.tags()).isNotEmpty().startsWith("계산"));
        assertThat(rows).filteredOn(row -> row.tags().size() > 1).isNotEmpty();
        assertThat(statistics.getPrepareStatementCount())
            .as("태그 로딩 SELECT 수(행 %d건)", TAGGED_ITEM_COUNT)
            .isEqualTo(EXPECTED_TAG_SELECTS);
    }
}
