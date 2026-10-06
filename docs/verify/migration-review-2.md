판정: 반려

검증 대상: `upstream/main...HEAD` (커밋 4개: 91932d3 · 6f5b83a · 8f213de · 9cc62de, 변경 파일 24개)
요청 문장: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew cleanTest test` → 46개 중 통과 46 · 실패 0 · 오류 0 · skipped 0. `cd characterization && npm test`(레거시 8081) → 30 통과 · 0 실패. `TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) → 29 통과 · 1 skipped(1차와 같은 `example-units.test.js`). 1차와 수가 같다. 기존 테스트를 지우거나 skip 으로 바꾼 곳 없음 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. SQL 은 Criteria 바인딩, 비밀값 · 데이터 삭제 · 덮어쓰기 코드 없음 |
| 요청 범위 이탈 없음 | 미충족 | 이슈 1, 2, 3. 1차 반려 사유가 그대로 남아 있고, 이관 코드가 아닌 파일이 12개에서 13개로 늘었다(`docs/verify/migration-review-1.md` 추가) |
| 컨벤션 준수 | 충족 | 위반 없음. 제안 수준(이슈 6, 7)만 있다 |

## 1차 리뷰 이후 바뀐 것

- 새 커밋은 9cc62de `fix: 검증루프 1차 리뷰 반영` 하나뿐이고, 변경은 `docs/verify/migration-review-1.md` 추가(47줄) 한 파일이다(`git diff 8f213de HEAD --stat`).
- `modern/` 의 diff 는 1차와 바이트 단위로 같다(`git diff upstream/main...HEAD -- modern/` 와 `git diff 1bb4f2f 8f213de -- modern/` 의 md5 일치).
- 그래서 1차 이슈 1~4 는 그대로 이월한다(아래 1 · 2 · 6 · 7). 커밋 제목은 "리뷰 반영"이지만 이슈를 고친 변경은 없다(이슈 3).

## 변경 파일 분류

| 구분 | 파일 |
|---|---|
| modern/api (main 7) | `ItemController` · `ItemRepository` · `ItemService`(수정), `ItemSearchCondition` · `ItemSearchResponse` · `ItemSearchRow` · `ItemSpecifications`(신규) |
| modern/api (test 4) | `ItemControllerTest` · `ItemRepositoryTest` · `ItemServiceTest` · `ItemSearchConditionTest` |
| characterization (2) | `tests/item-bank.test.js` · `__snapshots__/item-bank.test.js.snap` (둘 다 신규, 91932d3) |
| 그 밖 (11) | `CLAUDE.md`, `.claude/skills/convention-check/SKILL.md`, `.claude/skills/document-module/SKILL.md`, `docs/item-bank/*`(4), `docs/assignment/*`(3), `docs/verify/migration-review-1.md`(이번에 추가) |

modern/web · legacy/ · db/ · `build.gradle` · `application.yml` 변경은 없다.

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `docs/assignment/ARCHITECTURE.md:1` (`BUSINESS-RULES.md:1`, `ERD.md:1`) | (1차 이슈 1 이월) item-bank 검색 이관과 무관한 assignment 문서 3개가 범위에 있다. `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `docs/item-bank/*` 도 이관 코드가 아니다. `git ls-remote upstream main` = `1bb4f2f` 로 비교 기준은 최신이다. 즉 이 파일들은 기준이 뒤처져서 잡힌 것이 아니라 이 브랜치의 커밋(6f5b83a 등)에 실제로 들어 있다 | 별도 브랜치 · PR 로 분리하거나, 사람이 "이 파일들을 이번 변경에 포함한다"고 확정한다 |
| 2 | 경고 | `characterization/tests/item-bank.test.js:1`, `characterization/__snapshots__/item-bank.test.js.snap:1` | (1차 이슈 2 이월) characterization 테스트 · 스냅샷이 diff 에 있다(`CLAUDE.md` 완료 기준). 91932d3 에서 신규 추가된 뒤 수정 이력이 없어 통과시키려고 고친 흔적은 없다 | 사람이 "베이스라인 선행 커밋"으로 인정하면 기각 가능 |
| 3 | 경고 | `docs/verify/migration-review-1.md:1` | 9cc62de 의 제목은 `fix: 검증루프 1차 리뷰 반영`이지만 리뷰 문서 추가만 있고 코드 · 범위 정리는 없다. 커밋 설명과 diff 가 다르고, 이관 코드가 아닌 파일이 하나 더 늘었다 | 리뷰 기록은 이관 PR 밖(별도 커밋 · 문서 브랜치)에 두거나, 커밋 메시지를 실제 내용(`docs: 1차 리뷰 기록`)에 맞춘다 |
| 4 | 경고 | `modern/api/src/main/java/com/example/item/ItemController.java:29` | 실행 확인. 쿼리스트링에 `[` `]` 를 인코딩하지 않고 보내면 레거시는 200, 새 API 는 Tomcat 이 컨트롤러 전에 400(HTML 오류 페이지)을 돌려준다. `?tag[]=도형` · `?q[]=분수` · `?level[]=3` · `?page[]=2` 네 경우 모두 같다. `%5B%5D` 로 인코딩하면 6개 경우 모두 레거시와 같은 결과다. Javadoc(26행)은 `tag[]` 지원을 명시하고 있다. 레거시 화면이 만드는 링크는 `[]` 를 쓰지 않으므로(`search.php:414,430`) 브라우저 사용자에게는 영향이 없고, 손으로 만든 URL · 외부 연동 · 즐겨찾기에만 해당한다. 원인은 이번 diff 가 아니라 서버 기본 설정이다(`/api/items/1?x[]=1` 도 400) | 그런 호출자가 있는지 사람이 확인한다. 있으면 `server.tomcat.relaxed-query-chars: "[,]"` 를 검토한다. 설정 변경이므로 이번 이관 범위에 넣을지 따로 정한다 |
| 5 | 경고 | `modern/api/src/main/java/com/example/common/GlobalExceptionHandler.java:53` | 실행 확인. `POST · PUT · DELETE /api/items/search` 가 500 `서버 내부 오류`를 돌려준다. 레거시 `search.php` 는 메서드를 보지 않고 `$_GET` 만 읽어(`search.php:722`) 200 을 준다. 405 가 아니라 500 인 것은 기존 `handleUnexpected(Exception)` 가 `HttpRequestMethodNotSupportedException` 을 받아서로 보인다(응답 본문 `"message":"서버 내부 오류"` 는 이 핸들러의 문구다. 서버 로그는 파이프로 나가 확인하지 못했다). 이번 diff 밖의 기존 동작이다(`POST /api/items/1` · `/api/units` 도 500). 팀 `CLAUDE.md` §2 의 "나머지 500" 규칙과도 맞는다. 레거시 폼은 `method="get"`(`search.php:632`)이라 정상 경로에는 영향이 없다 | 이관 동작 차이로 기록만 하거나, 별도 변경에서 405 로 매핑한다. 이번 이관에서는 고치지 않는다 |
| 6 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRow.java:17` | (1차 이슈 3 이월) `item.getTags()` 가 LAZY(`Item.java:54`)라 페이지당 최대 20건의 태그 조회가 추가로 나간다(N+1) | `@BatchSize` 또는 id 목록으로 태그를 한 번에 조회하는 방안 검토. 동작이 바뀌지 않는 범위에서만 |
| 7 | 제안 | `modern/api/src/main/java/com/example/item/ItemSpecifications.java:28` | (1차 이슈 4 이월) 앞 `%` LIKE 로 인덱스를 못 타고, `%` · `_` 가 와일드카드로 동작한다. 실행 확인: `q=_` 가 레거시 · 새 API 모두 전체 결과를 돌려준다. 레거시 BR-04 와 같은 동작이다 | 이관 중에는 고치지 않는다. 의심 동작으로 남긴다 |

## 실행으로 확인한 동작 비교 (레거시 8081 vs 새 API 8080)

characterization 스냅샷에 없는 입력만 골라 두 서버에 같은 쿼리를 보내고, 저장소의 `characterization/lib/normalize.mjs` 로 정규화한 결과(status · rows · count · message)를 비교했다. 인코딩 · 메서드 문제를 빼면 21건 중 차이는 0건이다.

| 입력 | 결과 |
|---|---|
| `q=분수&q=도형`, `level=1&level=3` (같은 키 반복) | 같음 (둘 다 마지막 값 사용) |
| `page=99999`, `page=3`, `page=1.9`, `page=%202` | 같음 |
| `sort=level&dir=bogus`, `sort=level&dir=asc`, `sort=UNIT&dir=DESC` | 같음 |
| `q=%20분수%20`, `q=_`, `level=0`, `level=-1`, `level=%203`, `unit=m5-1`, `foo=bar` | 같음 |
| `tag%5B%5D=도형`, `tag=계산&tag%5B%5D=도형`, `q%5B%5D=분수`, `level%5B%5D=3`, `page%5B%5D=2`, `tag%5Bx%5D=도형` | 같음 |
| `tag[]=도형` 등 `[]` 인코딩 안 함 (4건) | **다름** — 레거시 200, 새 API 400 (이슈 4) |
| `POST` · `PUT` · `DELETE` | **다름** — 레거시 200, 새 API 500 (이슈 5). `HEAD` 는 둘 다 200 |

## 의심 동작 (레거시 보존, 이관 중 수정하지 않음)

- 키워드 `%` · `_` 를 이스케이프하지 않고 와일드카드로 쓴다(`ItemSpecifications.java:28`, BR-04).
- `level=6` · `3abc` 같은 값을 오류 없이 PHP `(int)` 변환으로 조회한다(`ItemSearchCondition.java` `phpIntval`, BR-09).
- `page` 에만 trim 을 하지 않는다(`ItemSearchCondition.java` `resolvePage`). 실행 재현: `page=%202` 가 두 서버 모두 1페이지(18번부터 20건)를 돌려준다. 기본 검색은 정확히 20건이라 진짜 2페이지는 비어 있다(스냅샷 `2페이지` 항목 `rows: []`, `page=3` 실행 결과도 `rows: []`). `page=1.9` → 1페이지도 같은 PHP `(int)` 변환 계열이다.

## 1차 "확인 필요" 처리 결과

- 새 API 서버가 HEAD 소스로 떠 있는지 — **해소.** 8080 은 `modern/api/build/classes` 를 쓰는 JVM(pid 5753, 2026-10-06 12:30 기동)이고, `com/example/item/*.java` 전부 대응 `.class` 보다 오래됐으며(`-nt` 비교로 stale 0건), `src/main` 에 클래스보다 새 파일이 없고, `modern/` 은 git 기준 변경이 없다.
- `upstream/main` 이 뒤처졌는지 — **해소.** `git ls-remote upstream main` = `1bb4f2f` = merge-base. 비교 기준은 맞다.
- `characterization/tests/example-units.test.js` skip — **이월.** 이번에도 새 API 대상 1건 skipped. diff 밖 기존 파일의 `it.skipIf(legacyOnly)` 로 보이며 사람이 확인한다.

## 확인 필요

- 이슈 4 — `[]` 를 인코딩하지 않고 `/search.php` 를 호출하는 외부 호출자(연동 · 즐겨찾기)가 있는지. 줄번호로 확인할 수 없는 운영 정보다.
- 이슈 5 의 원인 — bootRun 콘솔(또는 로그)에서 `unhandled exception on /api/items/search` 와 함께 `HttpRequestMethodNotSupportedException` 이 찍히는지 확인한다.
- `.claude/skills/verify/` — 작업 트리에 추적되지 않은 상태(`??`)다. 이 리뷰가 따른 절차가 커밋돼 있지 않아 다른 PC 의 리뷰어는 같은 Skill 을 쓰지 못한다. 이번 diff 대상은 아니다.
- `modern/web` — 변경 없음. 테스트를 실행하지 않았다.

## 반려 사유와 다음 단계

1차와 같은 한 기준, "요청 범위 이탈 없음" 때문에 반려다. 9cc62de 는 리뷰 기록만 추가했고 범위 정리는 하지 않아 상태가 그대로다. 아래 중 하나가 있어야 이 기준이 바뀐다.

1. 이관 코드(main 7 · test 4)만 남긴 브랜치로 다시 올린다. characterization 2개는 선행 베이스라인 PR 로 분리한다. 또는
2. 사람이 "문서 · Skill · CLAUDE.md · characterization 베이스라인 · 리뷰 기록을 이번 PR 에 포함한다"고 명시적으로 확정한다.

이관 코드 자체는 테스트 · 스냅샷 · 추가 실행 비교에서 레거시와 같은 결과를 냈다. 다만 이슈 4 · 5 의 동작 차이(인코딩 안 한 `[]` → 400, 비GET → 500)는 "동작을 바꾸지 않고"라는 요청에 비춰 사람이 수용 여부를 정해야 한다.

## 검증 방법 메모

- 저장소 `.claude/skills/verify/SKILL.md` 절차와 출력 형식을 따랐다(1차와 비교할 수 있도록).
- 두 서버는 이미 떠 있던 것을 그대로 썼다(레거시는 docker `mirae-n-handson-item-bank-1`, 새 API 는 bootRun JVM). `./gradlew clean` 은 실행 중인 JVM 의 `build/classes` 를 지우므로 쓰지 않고 `cleanTest` 만 썼다.
- 이 리뷰 세션은 코드를 수정하지 않았다. 추가한 파일은 이 문서 하나다.
