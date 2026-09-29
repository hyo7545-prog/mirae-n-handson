// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// - 규칙 ID 는 docs/item-bank/BUSINESS-RULES.md 의 BR-xx 다.
// - 대상 주소는 lib/target.mjs 가 정한다(TARGET_BASE_URL, 없으면 레거시 기본 주소). 주소를 여기 적지 않는다.
// - 기대값을 손으로 적지 않는다. 지금 레거시의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 레거시와 이관 후 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기를 두지 않는다.
// - 경고 문구 · 조건 요약은 정규화 결과에 들어가지 않는다. 그래서 경계 케이스는 rows · count 가
//   달라지는 값으로 골랐다.
// - 시드(db/mariadb/init/02-seed.sql)에서 공개(A)이면서 난이도 1~4 인 문항은 정확히 20건(= 페이지 크기)이다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

// BR-05 키워드 최대 길이(100자) 경계. '%' 는 LIKE 와일드카드로 동작한다(BR-04).
//   100자: 잘리지 않는다 → 'z' 를 포함하는 문항만 남는다.
//   101자: 앞 100자('%' 만)로 잘린다 → 'z' 조건이 사라진다.
const KEYWORD_MAX_LENGTH = 100;
const KEYWORD_EXACT_MAX = '%'.repeat(KEYWORD_MAX_LENGTH - 1) + 'z';
const KEYWORD_OVER_MAX = '%'.repeat(KEYWORD_MAX_LENGTH) + 'z';

function search(params) {
  return fetchNormalized(MODULE, PATH, params);
}

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상 입력', () => {
    it('키워드 단독 — 부분 일치 · 공개만 · 빈 난이도는 5 제외 · 기본 정렬 (BR-01 BR-04 BR-07 BR-15)', async () => {
      expect(await search({ q: '분수' })).toMatchSnapshot();
    });

    it('단원 + 난이도 AND 결합 (BR-02 BR-06 BR-08)', async () => {
      expect(await search({ unit: 'M5-1', level: '3' })).toMatchSnapshot();
    });

    it('태그 완전 일치 + 단원 정렬 내림차순, 2차 키 level DESC 고정 (BR-10 BR-17)', async () => {
      expect(await search({ tag: '계산', sort: 'unit', dir: 'desc' })).toMatchSnapshot();
    });
  });

  describe('경계값', () => {
    it('난이도 5 명시 — 빈 난이도에서 빠지는 5 가 나온다 (BR-07 BR-08)', async () => {
      expect(await search({ level: '5' })).toMatchSnapshot();
    });

    it('난이도 6 — 범위 바로 밖, 경고 후 level=6 조회 (BR-09)', async () => {
      expect(await search({ level: '6' })).toMatchSnapshot();
    });

    it('난이도 05 — 정규식은 통과 못 하지만 정수 변환으로 5 (BR-08 BR-09)', async () => {
      expect(await search({ level: '05' })).toMatchSnapshot();
    });

    it('2페이지 — 기본 검색이 딱 20건이라 페이지 크기 경계 (BR-13 BR-19 BR-20 BR-21)', async () => {
      expect(await search({ page: '2' })).toMatchSnapshot();
    });

    it('0페이지 — 1 미만은 경고 없이 1페이지로 보정 (BR-20)', async () => {
      expect(await search({ page: '0' })).toMatchSnapshot();
    });

    it('키워드 정확히 100자 — 잘리지 않음 (BR-04 BR-05)', async () => {
      expect(await search({ q: KEYWORD_EXACT_MAX })).toMatchSnapshot();
    });

    it('키워드 101자 — 앞 100자로 잘려 와일드카드만 남음 (BR-04 BR-05)', async () => {
      expect(await search({ q: KEYWORD_OVER_MAX })).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('파라미터 없음 — 조건 없는 검색도 난이도 5 제외 · 기본 정렬 (BR-01 BR-07 BR-14 BR-15 BR-19)', async () => {
      expect(await search({})).toMatchSnapshot();
    });

    it('모든 파라미터 빈 값 — 폼을 그대로 제출한 모양 (BR-03 BR-07 BR-15 BR-20)', async () => {
      expect(
        await search({ q: '', unit: '', level: '', tag: '', sort: '', dir: '', page: '' }),
      ).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('음수 페이지 -1 — 경고 후 1페이지 (BR-20)', async () => {
      expect(await search({ page: '-1' })).toMatchSnapshot();
    });

    it('없는 단원 코드 Z9-99 — 오류 아닌 0건 안내 (BR-06 BR-13)', async () => {
      expect(await search({ unit: 'Z9-99' })).toMatchSnapshot();
    });

    it('난이도 3abc — 정수 변환으로 난이도 3 조회 (BR-09)', async () => {
      expect(await search({ level: '3abc' })).toMatchSnapshot();
    });

    it('배열 태그 + 모르는 정렬 — 첫 값만 쓰고 기본 정렬로 (BR-03 BR-16)', async () => {
      expect(await search({ 'tag[]': ['계산', '도형'], sort: 'foo' })).toMatchSnapshot();
    });
  });
});
