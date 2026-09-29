package com.example.item;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * {@code GET /api/items/search} 응답. 모양은 동작 보존 테스트의 정규화 결과 {@code {status, rows, count, message}} 와 같다.
 *
 * @param status  HTTP 상태 코드와 같은 값
 * @param rows    현재 페이지 행
 * @param count   조건에 맞는 전체 건수(페이지와 무관)
 * @param message 전체 건수가 0 이면 안내 문구, 아니면 null
 */
public record ItemSearchResponse(int status, List<ItemSearchRow> rows, long count, String message) {

    /** 결과가 0건일 때 안내 문구(BR-13, 레거시 {@code search.php:658}). */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    static ItemSearchResponse of(List<ItemSearchRow> rows, long count) {
        return new ItemSearchResponse(HttpStatus.OK.value(), rows, count, count == 0 ? NO_RESULT_MESSAGE : null);
    }
}
