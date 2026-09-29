package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

/** 저장소 슬라이스 — H2(MariaDB 모드)에 실제 SQL 을 날린다. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private UnitRepository unitRepository;

    private Unit fraction;

    @BeforeEach
    void seed() {
        fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분모가 같은 분수의 덧셈", 2, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, calc, word));
        entityManager.persist(ItemFixtures.item(null, fraction, "검수 중 문항", 3, ItemStatus.REVIEWING));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 문항", 5, ItemStatus.DELETED));
        entityManager.persist(ItemFixtures.item(null, fraction, "난이도 5 분수", 5, ItemStatus.ACTIVE, word));
        entityManager.persist(ItemFixtures.item(null, decimal, "소수 곱셈", 1, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("findByUnitCodeAndStatus: 공개 문항만, level DESC · id ASC, 태그 함께 로드")
    void findsActiveItemsOrderedWithTags() {
        List<Item> items = itemRepository.findByUnitCodeAndStatus("M5-1", ItemStatus.ACTIVE);

        assertThat(items).hasSize(3);
        assertThat(items).extracting(Item::getTitle)
            .containsExactly("난이도 5 분수", "분수 문장제", "분모가 같은 분수의 덧셈");
        assertThat(items).allSatisfy(item -> assertThat(item.getStatus()).isEqualTo("A"));
        assertThat(items.get(1).getTags()).extracting(Tag::getName).containsExactly("계산", "문장제");
    }

    @Test
    @DisplayName("findWithDetailsById: 단원 · 태그가 한 번에 로드된다(지연 로딩 아님)")
    void findWithDetailsLoadsUnitAndTags() {
        Integer id = itemRepository.findByUnitCodeAndStatus("M5-1", ItemStatus.ACTIVE).get(1).getId();
        entityManager.clear();

        Item item = itemRepository.findWithDetailsById(id).orElseThrow();

        assertThat(Hibernate.isInitialized(item.getUnit())).isTrue();
        assertThat(Hibernate.isInitialized(item.getTags())).isTrue();
        assertThat(item.getUnit().getCode()).isEqualTo("M5-1");
        assertThat(item.getTags()).hasSize(2);
    }

    @Test
    @DisplayName("countByUnitIdAndStatus: 상태별 문항 수")
    void countsByStatus() {
        assertThat(itemRepository.countByUnitIdAndStatus(fraction.getId(), ItemStatus.ACTIVE)).isEqualTo(3);
        assertThat(itemRepository.countByUnitIdAndStatus(fraction.getId(), ItemStatus.DELETED)).isEqualTo(1);
        assertThat(unitRepository.findByCode("M5-3")).isPresent();
    }

    private Page<Item> search(String... keyValues) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(keyValues[i], List.of(keyValues[i + 1]));
        }
        ItemSearchCondition condition = ItemSearchCondition.from(params);
        return itemRepository.findAll(ItemSpecifications.matching(condition), condition.toPageable());
    }

    @Test
    @DisplayName("검색 조건 없음: 공개 문항 중 난이도 5 를 빼고 level DESC · id ASC")
    void searchWithoutConditionExcludesLevel5() {
        Page<Item> page = search();

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Item::getTitle)
            .containsExactly("분수 문장제", "분모가 같은 분수의 덧셈", "소수 곱셈");
    }

    @Test
    @DisplayName("검색 난이도 5 명시: 난이도 5 공개 문항만")
    void searchWithLevel5() {
        assertThat(search("level", "5").getContent()).extracting(Item::getTitle).containsExactly("난이도 5 분수");
    }

    @Test
    @DisplayName("검색 태그: 태그가 여러 개인 문항도 한 번만 나온다(EXISTS)")
    void searchByTagHasNoDuplicates() {
        Page<Item> page = search("tag", "계산");

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Item::getTitle)
            .containsExactly("분수 문장제", "분모가 같은 분수의 덧셈");
    }

    @Test
    @DisplayName("검색 키워드: 제목 · 지문 부분 일치, % 는 와일드카드")
    void searchByKeywordWithWildcard() {
        assertThat(search("q", "분수").getContent()).extracting(Item::getTitle)
            .containsExactly("분수 문장제", "분모가 같은 분수의 덧셈");
        assertThat(search("q", "소%곱").getContent()).extracting(Item::getTitle).containsExactly("소수 곱셈");
    }

    @Test
    @DisplayName("검색 단원 + 난이도: AND 결합, 없는 단원 · 범위 밖 난이도는 0건")
    void searchByUnitAndLevel() {
        assertThat(search("unit", "M5-1", "level", "2").getContent()).extracting(Item::getTitle)
            .containsExactly("분모가 같은 분수의 덧셈");
        assertThat(search("unit", "Z9-99").getTotalElements()).isZero();
        assertThat(search("level", "6").getTotalElements()).isZero();
        assertThat(search("level", "99999999999999999999").getTotalElements()).isZero();
    }

    @Test
    @DisplayName("검색 단원 정렬 내림차순: 단원 코드 DESC, 같은 단원이면 level DESC")
    void searchSortedByUnitDesc() {
        assertThat(search("sort", "unit", "dir", "desc").getContent()).extracting(Item::getTitle)
            .containsExactly("소수 곱셈", "분수 문장제", "분모가 같은 분수의 덧셈");
    }

    @Test
    @DisplayName("검색 전체 페이지를 넘는 번호: 행은 없고 전체 건수는 그대로")
    void searchBeyondLastPageKeepsTotal() {
        Page<Item> page = search("page", "2");

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
    }
}
