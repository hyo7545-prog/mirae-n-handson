package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** 검색 파라미터 해석 — 레거시 search.php 의 입력 보정 규칙(BR-03 ~ BR-20). */
class ItemSearchConditionTest {

    private static Map<String, List<String>> params(String... keyValues) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.computeIfAbsent(keyValues[i], k -> new ArrayList<>()).add(keyValues[i + 1]);
        }
        return map;
    }

    private static ItemSearchCondition parse(String... keyValues) {
        return ItemSearchCondition.from(params(keyValues));
    }

    @Test
    @DisplayName("파라미터가 없으면 조건 없음 · 빈 난이도 · 1페이지 · 기본 정렬(level DESC, id ASC)")
    void emptyParamsUseDefaults() {
        ItemSearchCondition condition = parse();

        assertThat(condition.keyword()).isNull();
        assertThat(condition.unitCode()).isNull();
        assertThat(condition.level()).isNull();
        assertThat(condition.tag()).isNull();
        assertThat(condition.page()).isEqualTo(1);
        assertThat(condition.sort()).isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
    }

    @Test
    @DisplayName("빈 문자열 파라미터는 없는 것과 같다")
    void blankParamsEqualMissing() {
        ItemSearchCondition condition = parse("q", "", "unit", " ", "level", "", "tag", "", "sort", "", "dir", "",
            "page", "");

        assertThat(condition).isEqualTo(parse());
    }

    @Test
    @DisplayName("난이도: 1~5 는 그대로, 그 밖은 PHP (int) 변환 값")
    void levelFollowsPhpIntCast() {
        assertThat(parse("level", "5").level()).isEqualTo(5L);
        assertThat(parse("level", " 3 ").level()).isEqualTo(3L);
        assertThat(parse("level", "05").level()).isEqualTo(5L);
        assertThat(parse("level", "3abc").level()).isEqualTo(3L);
        assertThat(parse("level", "6").level()).isEqualTo(6L);
        assertThat(parse("level", "abc").level()).isEqualTo(0L);
        assertThat(parse("level", "-2").level()).isEqualTo(-2L);
        assertThat(parse("level", "3.7").level()).isEqualTo(3L);
        assertThat(parse("level", "99999999999999999999").level()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("키워드: 앞뒤 공백 제거 후 100자까지(코드포인트 기준), 101자는 앞 100자로 자른다")
    void keywordIsTruncatedTo100() {
        String exact = "%".repeat(99) + "z";
        String over = "%".repeat(100) + "z";

        assertThat(parse("q", exact).keyword()).isEqualTo(exact);
        assertThat(parse("q", over).keyword()).isEqualTo("%".repeat(100));
        assertThat(parse("q", "  분수 ").keyword()).isEqualTo("분수");
    }

    @Test
    @DisplayName("태그: 50자를 넘으면 앞 50자로 자른다")
    void tagIsTruncatedTo50() {
        assertThat(parse("tag", "가".repeat(51)).tag()).isEqualTo("가".repeat(50));
    }

    @Test
    @DisplayName("PHP trim 은 유니코드 공백(전각 공백)을 지우지 않는다")
    void phpTrimKeepsIdeographicSpace() {
        assertThat(parse("unit", "　M5-1").unitCode()).isEqualTo("　M5-1");
    }

    @Test
    @DisplayName("배열 표기(tag[])는 첫 값, 같은 이름 반복은 마지막 값")
    void arrayTakesFirstRepeatedTakesLast() {
        assertThat(parse("tag[]", "계산", "tag[]", "도형").tag()).isEqualTo("계산");
        assertThat(parse("tag", "계산", "tag", "도형").tag()).isEqualTo("도형");
    }

    @Test
    @DisplayName("페이지: 0 · 음수 · 숫자 아님은 1, 999 초과는 999, 앞 공백은 숫자로 보지 않는다")
    void pageIsClamped() {
        assertThat(parse("page", "2").page()).isEqualTo(2);
        assertThat(parse("page", "0").page()).isEqualTo(1);
        assertThat(parse("page", "-1").page()).isEqualTo(1);
        assertThat(parse("page", "abc").page()).isEqualTo(1);
        assertThat(parse("page", " 2").page()).isEqualTo(1);
        assertThat(parse("page", "2\n").page()).isEqualTo(2);
        assertThat(parse("page", "1000").page()).isEqualTo(999);
        assertThat(parse("page", "99999999999999999999").page()).isEqualTo(999);
    }

    @Test
    @DisplayName("toPageable: 20건씩, (page - 1) 번째 페이지")
    void pageableUses20PerPage() {
        Pageable pageable = parse("page", "3").toPageable();

        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("정렬: unit 은 방향과 무관하게 2차 키 level DESC, 모르는 기준은 기본 정렬, 기본 정렬은 dir 무시")
    void sortRules() {
        assertThat(parse("sort", "unit", "dir", "desc").sort()).isEqualTo(
            Sort.by(Sort.Order.desc("unit.code"), Sort.Order.desc("level"), Sort.Order.asc("id")));
        assertThat(parse("sort", "unit").sort()).isEqualTo(
            Sort.by(Sort.Order.asc("unit.code"), Sort.Order.desc("level"), Sort.Order.asc("id")));
        assertThat(parse("sort", "LEVEL").sort()).isEqualTo(
            Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
        assertThat(parse("sort", "created", "dir", "up").sort()).isEqualTo(
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        assertThat(parse("sort", "id", "dir", "desc").sort()).isEqualTo(Sort.by(Sort.Order.desc("id")));
        assertThat(parse("sort", "foo").sort()).isEqualTo(ItemSearchCondition.DEFAULT_SORT);
        assertThat(parse("dir", "asc").sort()).isEqualTo(ItemSearchCondition.DEFAULT_SORT);
    }
}
