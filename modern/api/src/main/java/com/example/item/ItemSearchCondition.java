package com.example.item;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 문항 검색 조건. 레거시 {@code search.php} 의 파라미터 해석을 그대로 옮긴다(docs/item-bank/BUSINESS-RULES.md).
 * 잘못된 입력은 오류가 아니라 보정하거나 그대로 조회한다(BR-05 · BR-06 · BR-09 · BR-12).
 *
 * @param keyword  제목 · 지문 부분 일치 키워드. 없으면 null
 * @param unitCode 단원 코드 완전 일치. 없으면 null
 * @param level    난이도. null 이면 "빈 난이도"로 {@code level < 5} 조건이 붙는다(BR-07)
 * @param tag      태그 이름 완전 일치. 없으면 null
 * @param sort     정렬(마지막 키까지 포함)
 * @param page     1 ~ 999 로 보정한 페이지 번호
 */
public record ItemSearchCondition(String keyword, String unitCode, Long level, String tag, Sort sort, int page) {

    /** 키워드 최대 글자 수(BR-05). */
    static final int KEYWORD_MAX_LENGTH = 100;
    /** 태그 이름 최대 글자 수(BR-11). */
    static final int TAG_MAX_LENGTH = 50;
    /** 난이도를 비웠을 때 이 값 미만만 찾는다(BR-07). */
    static final int EMPTY_LEVEL_UPPER_BOUND = 5;
    /** 한 페이지 건수(BR-19). */
    static final int PAGE_SIZE = 20;
    /** 최대 페이지 번호(BR-20). */
    static final int MAX_PAGE = 999;

    private static final String DEFAULT_PAGE = "1";
    private static final String ASC = "asc";
    private static final String DESC = "desc";

    /** PHP {@code trim()} 이 지우는 문자: 공백 · \t · \n · \r · \0 · \x0B. */
    private static final String PHP_TRIM_CHARS = " \t\n\r\0\u000B";

    /** 난이도 1~5 한 글자(BR-08, {@code search.php:217}). */
    private static final Pattern VALID_LEVEL = Pattern.compile("[1-5]");

    /** 페이지 숫자 형식. PHP 정규식 {@code /^[0-9]+$/} 의 {@code $} 는 끝 줄바꿈 하나를 허용한다. */
    private static final Pattern PAGE_DIGITS = Pattern.compile("([0-9]+)\n?");

    /** PHP 7.4 숫자 문자열 앞부분(앞 공백 · 부호 · 소수점 · 지수). {@code (int)"3abc"} 은 3 이다. */
    private static final Pattern PHP_NUMERIC_PREFIX =
        Pattern.compile("^[ \t\n\r\u000B\f]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");

    /** 기본 정렬: 난이도 내림차순, 같은 난이도면 번호 오름차순(BR-15). */
    static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id"));

    /**
     * 요청 파라미터 → 검색 조건.
     *
     * @param params 쿼리 파라미터(이름 → 값 목록, 들어온 순서 유지). {@code tag[]} 처럼 배열 표기도 받는다.
     */
    public static ItemSearchCondition from(Map<String, List<String>> params) {
        String q = phpTrim(firstOrLast(params, "q", ""));
        if (q.codePointCount(0, q.length()) > KEYWORD_MAX_LENGTH) {
            q = truncate(q, KEYWORD_MAX_LENGTH);
        }

        String unit = phpTrim(firstOrLast(params, "unit", ""));

        String levelRaw = phpTrim(firstOrLast(params, "level", ""));
        Long level;
        if (levelRaw.isEmpty()) {
            level = null;
        } else if (VALID_LEVEL.matcher(levelRaw).matches()) {
            level = Long.valueOf(levelRaw);
        } else {
            level = phpIntval(levelRaw);
        }

        String tag = phpTrim(firstOrLast(params, "tag", ""));
        if (tag.codePointCount(0, tag.length()) > TAG_MAX_LENGTH) {
            tag = truncate(tag, TAG_MAX_LENGTH);
        }

        String sortKey = asciiLower(phpTrim(firstOrLast(params, "sort", "")));
        String dir = asciiLower(phpTrim(firstOrLast(params, "dir", "")));
        if (!ASC.equals(dir) && !DESC.equals(dir)) {
            dir = "";
        }

        int page = resolvePage(firstOrLast(params, "page", DEFAULT_PAGE));

        return new ItemSearchCondition(
            q.isEmpty() ? null : q,
            unit.isEmpty() ? null : unit,
            level,
            tag.isEmpty() ? null : tag,
            resolveSort(sortKey, dir),
            page);
    }

    /** 페이지 번호 · 크기 · 정렬. */
    public Pageable toPageable() {
        return PageRequest.of(page - 1, PAGE_SIZE, sort);
    }

    /**
     * PHP {@code $_GET} 처럼 값을 꺼낸다.
     * {@code name[]} (배열 표기)이면 첫 값(BR-03), {@code name} 이 여러 번이면 마지막 값(PHP 는 뒤 값으로 덮어쓴다).
     * 두 표기가 섞이면 먼저 나온 이름의 해석을 쓰되 {@code name} 은 배열을 덮어쓴다.
     */
    static String firstOrLast(Map<String, List<String>> params, String name, String absent) {
        String value = null;
        boolean array = false;
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            String key = entry.getKey();
            if (key.equals(name)) {
                value = values.get(values.size() - 1);
                array = false;
            } else if (key.startsWith(name + "[") && !array) {
                value = values.get(0);
                array = true;
            }
        }
        return value == null ? absent : value;
    }

    /** PHP {@code trim()} — Java {@code strip()} 과 달리 유니코드 공백은 남긴다. */
    static String phpTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && PHP_TRIM_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && PHP_TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** {@code mb_substr($s, 0, n, 'UTF-8')} — 코드포인트 기준으로 자른다. */
    private static String truncate(String value, int maxCodePoints) {
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    /** PHP {@code strtolower()} (기본 로캘) — ASCII 대문자만 바꾼다. */
    private static String asciiLower(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return sb.toString();
    }

    /**
     * PHP 7.4 {@code (int)$string}. 앞쪽 숫자 부분만 읽고({@code "3abc"} → 3), 숫자가 없으면 0.
     * 소수 · 지수 표기는 실수로 읽어 소수점 아래를 버리고, long 범위를 넘으면 최대 · 최소값으로 고정한다.
     */
    static long phpIntval(String value) {
        Matcher m = PHP_NUMERIC_PREFIX.matcher(value);
        if (!m.find()) {
            return 0L;
        }
        String number = m.group(1);
        boolean integral = number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0;
        if (integral) {
            BigInteger parsed = new BigInteger(number.startsWith("+") ? number.substring(1) : number);
            return clampToLong(new BigDecimal(parsed));
        }
        double parsed = Double.parseDouble(number);
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
            return 0L;
        }
        return clampToLong(BigDecimal.valueOf(parsed));
    }

    private static long clampToLong(BigDecimal value) {
        if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValue();
    }

    /**
     * 페이지 번호(BR-20). 숫자가 아니면 1, 1 미만이면 1, 999 초과면 999.
     * 레거시는 page 에만 trim 을 하지 않는다({@code " 2"} → 1).
     */
    private static int resolvePage(String raw) {
        Matcher m = PAGE_DIGITS.matcher(raw);
        if (!m.matches()) {
            return 1;
        }
        BigInteger page = new BigInteger(m.group(1));
        if (page.compareTo(BigInteger.ONE) < 0) {
            return 1;
        }
        if (page.compareTo(BigInteger.valueOf(MAX_PAGE)) > 0) {
            return MAX_PAGE;
        }
        return page.intValue();
    }

    /**
     * 정렬(BR-15 · BR-16 · BR-17). 모르는 기준이면 기본 정렬, 기본 정렬은 dir 을 무시한다.
     * dir 이 없으면 id · title · unit 은 오름차순, level · created 는 내림차순.
     */
    private static Sort resolveSort(String sortKey, String dir) {
        boolean desc = DESC.equals(dir);
        boolean asc = ASC.equals(dir);
        Sort.Order byId = Sort.Order.asc("id");
        return switch (sortKey) {
            case "id" -> Sort.by(desc ? Sort.Order.desc("id") : byId);
            case "title" -> Sort.by(desc ? Sort.Order.desc("title") : Sort.Order.asc("title"), byId);
            case "unit" -> Sort.by(
                desc ? Sort.Order.desc("unit.code") : Sort.Order.asc("unit.code"), Sort.Order.desc("level"), byId);
            case "level" -> Sort.by(asc ? Sort.Order.asc("level") : Sort.Order.desc("level"), byId);
            case "created" -> Sort.by(asc ? Sort.Order.asc("createdAt") : Sort.Order.desc("createdAt"), byId);
            default -> DEFAULT_SORT;
        };
    }
}
