판정: 승인

검증 대상: `git diff upstream/main...HEAD -- modern characterization` (HEAD d528e88, 범위 안 커밋 2개: 91932d3 · 8f213de, 변경 파일 13개)
요청 문장: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | HEAD 를 별도 worktree 로 꺼내 실행했다. `cd modern/api && ./gradlew test` → 46개 중 통과 46 · 실패 0 · 오류 0 · skipped 0. `cd characterization && npm test`(레거시 8081) → 30 통과 · 0 실패. `TARGET_BASE_URL=http://localhost:8090 npm test`(HEAD 를 8090 에 bootRun) → 29 통과 · 0 실패 · 1 skipped. skipped 는 범위 밖 `example-units.test.js:21` 의 `skipIf(legacyOnly)` 이다. 새 public 메서드(`ItemController.searchItems` · `ItemService.searchItems` · `ItemSearchCondition.from` · `toPageable` · `ItemRepository.findAll` 재정의)마다 호출하는 테스트가 있다. 지우거나 skip 으로 바꾼 테스트는 없다 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. SQL 은 Criteria API 파라미터 바인딩이다(`ItemSpecifications.java:28` 의 `"%" + keyword + "%"` 는 바인딩 값). 비밀값 · 데이터 삭제 · 덮어쓰기 · 인증 변경 없음 |
| 요청 범위 이탈 없음 | 충족 | 경로 필터 안 13개 파일이 모두 `search.php` → `GET /api/items/search` 이관과 그 동작 보존 테스트로 설명된다. 기존 파일(`ItemController` · `ItemRepository` · `ItemService`)의 변경은 기능 추가와 그에 필요한 import 뿐이다. 의존성 · 스키마 · `application.yml` · `legacy/` 변경 없음. 단, 아래 "확인 필요" 1 참고 |
| 컨벤션 준수 | 충족 | 4-2 · 4-3 · 4-4 위반 없음: 컨트롤러에 Repository 없음, `ItemService` 는 같은 도메인의 `ItemRepository` · `UnitRepository` 만 쓴다. catch · `System.out` · `printStackTrace` 없음. 와일드카드 import · 인자 없는 `now()` · 120자 넘는 줄 없음. 4-5 경고 1건(이슈 2)은 충족 여부에 반영하지 않는다 |

## 변경 파일 분류

| 구분 | 파일 |
|---|---|
| modern/api main (7) | `ItemController` · `ItemRepository` · `ItemService`(수정), `ItemSearchCondition` · `ItemSearchResponse` · `ItemSearchRow` · `ItemSpecifications`(신규) |
| modern/api test (4) | `ItemControllerTest` · `ItemRepositoryTest` · `ItemSearchConditionTest` · `ItemServiceTest` |
| characterization (2) | `tests/item-bank.test.js` · `__snapshots__/item-bank.test.js.snap` (둘 다 신규, 베이스라인 커밋 91932d3) |

modern/web · legacy/ · db/ · `build.gradle` · `application.yml` 변경은 없다. characterization 테스트 · 스냅샷은 베이스라인 커밋 91932d3 에서만 추가됐고, 이관 커밋 8f213de 는 이 파일들을 건드리지 않았다. 통과시키려고 고친 흔적이 아니라 이관 전 레거시 응답을 찍어 둔 베이스라인이다. `PATH_ALIASES['/search.php']` 는 범위 밖인 `characterization/lib/target.mjs:22` 에 이미 있다.

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchRow.java:17` (호출 `ItemService.java:57`) | `Item.tags` 는 LAZY(`Item.java:54`)이고 검색용 `findAll` 은 `unit` 만 같이 가져온다(`ItemRepository.java:33`). 행마다 태그 SELECT 가 한 번씩 나가 한 페이지(20건)에 쿼리가 최대 22번 실행된다. Hikari max 5 환경에서 트랜잭션이 커넥션을 쥐는 시간이 길어진다 | `Item.tags` 에 `@BatchSize`, 또는 페이지 id 목록으로 태그를 한 번에 조회. 작업 폴더에 `@BatchSize` 를 붙인 미커밋 수정이 있지만 검증 대상(HEAD) 밖이라 반영 여부는 판정하지 않았다 |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchResponse.java:19` | 체크리스트 4-5 는 "응답 DTO 변환 메서드는 `from`" 이다. 응답 DTO 의 팩토리 이름이 `of` 다. 엔티티 → DTO 변환이 아니라 행 목록 + 건수를 감싸는 팩토리라서 CLAUDE.md 의 `XxxResponse.from(entity)` 규칙에 딱 걸리지는 않는다. 4-5 문장을 글자 그대로 읽어 경고로 기록한다 | 사람이 규칙 적용 대상인지 정한다. 대상이면 `from` 으로 바꾼다(테스트 호출부 포함) |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:112` | `tag[]=a&tag=b&tag[]=c` 처럼 두 표기가 섞이면, 레거시는 마지막 `tag[]` 가 배열을 다시 만들어 `reset` 결과가 c 다(`legacy/item-bank-php/search.php:66-67`). 현행은 키별로 묶인 맵을 돌아 b 다. 스냅샷에 없는 극단 케이스다 | 의심 동작 · 알려진 차이로 기록하고, 맞출지는 사람이 정한다 |
| 4 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchResponse.java:14` | 본문의 `status` 는 HTTP 상태와 같은 값이다. 정규화는 HTTP 상태만 쓰므로 비교에는 영향이 없다 | 그대로 둔다. 프론트와 응답 계약을 정할 때 다시 본다 |

## 레거시와 다른 동작 · 의심 동작 (이관 중 고치지 않은 것)

- 의심 동작(레거시 그대로 보존):
  - `legacy/item-bank-php/search.php:213-215` — 빈 난이도는 `level < 5` 라서 5 가 빠진다(주석은 "1~5 전체").
  - `search.php:337` — page 만 trim 하지 않는다(`" 2"` → 1페이지).
  - `search.php:94-95` — 키워드의 `%` · `_` 를 와일드카드로 쓴다.
- 알려진 차이(레거시와 다름):
  - 이슈 3.
  - DB 오류 시 레거시는 HTTP 200 + 오류 HTML(`search.php:721-729`), 현행은 `GlobalExceptionHandler` 를 거쳐 500. CLAUDE.md 예외 처리 규칙을 따른 결과이고 characterization 케이스에는 없다.

## 확인 필요

- 경로 필터(`-- modern characterization`) — 2차 리뷰(`docs/verify/migration-review-2.md`)의 반려 사유는 필터 밖 파일(`CLAUDE.md` · `.claude/skills/*` · `docs/*` 등)이 같은 범위에 섞인 것이었다. 이번 판정은 필터 안 13개 파일만 대상으로 한다. 브랜치 전체를 머지하려면 필터 밖 파일의 범위 판단이 따로 필요하다.
- `ItemController.java:30` — 클라이언트가 `tag[]` 의 `[` `]` 를 인코딩하지 않고 보내면 Tomcat 이 400 을 돌려줄 수 있다. 실제 클라이언트가 어떻게 보내는지 확인이 필요하다(characterization 은 `%5B%5D` 로 인코딩해 영향 없음).
- 검증 독립성 — 검증 루프는 만든 쪽과 다른 컨텍스트에서 검증하도록 정하고 있다. 이번 검증을 한 세션은 같은 대화에서 이슈 1 의 미커밋 수정(`Item.java`)을 만든 세션이다. HEAD 의 이관 코드는 이 세션이 만들지 않았지만, 엄격히 분리하려면 새 세션에서 한 번 더 돌린다.
- `vendor-prs/*.patch` — 3개 모두 HEAD 기준 `git apply --check` 통과. 이번 변경으로 새로 실패하는 patch 는 없다.
