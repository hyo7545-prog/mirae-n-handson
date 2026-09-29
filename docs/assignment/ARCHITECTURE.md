# assignment-thymeleaf 아키텍처 — 새 배포 화면

> 대상: `legacy/assignment-thymeleaf/` (읽기 전용 분석 결과)
> **범위: "새 배포" 화면 하나** — `GET /distributions/new`(폼 표시)와 그 폼의 제출 `POST /distributions`(등록), 그리고 이 둘이 호출하는 코드 · 테이블만.
> 배포 목록(`GET /distributions`) · 재배포(`POST /distributions/{id}/redistribute`) · 과제 목록(`GET /assignments`)은 범위 밖이다. 등록 성공 후 목록으로 리다이렉트되는 사실만 적는다.
> 줄번호 표기: Java · 템플릿 · 설정 파일은 `legacy/assignment-thymeleaf/src/main/` 기준 상대 경로다. 2절부터는 Java 의 `java/com/example/assign/` 와 템플릿의 `resources/templates/` 를 생략한다. 스키마 · 시드는 저장소 루트 기준 경로다.

## 1. 진입점

| # | 메서드 · URL | 처리 메서드 | 화면(뷰) | 근거 |
|---|---|---|---|---|
| E1 | `GET /distributions/new` | `DistributionController.form(Model)` | `distribution_form` → `resources/templates/distribution_form.html` | `java/com/example/assign/web/DistributionController.java:40-47` |
| E2 | `POST /distributions` (파라미터 `assignmentId`, `classId`) | `DistributionController.create(long, long, RedirectAttributes)` | 뷰 없음. 성공 시 `redirect:/distributions`, 실패 시 `redirect:/distributions/new` + flash | `java/com/example/assign/web/DistributionController.java:49-77` |

- E2 는 E1 화면의 폼이 제출한다: `<form method="post" th:action="@{/distributions}">` (`resources/templates/distribution_form.html:8`). 필드 이름은 `assignmentId`(`:11`), `classId`(`:23`)이다.
- E1 화면으로 들어가는 메뉴 링크: `resources/templates/layout.html:26` (`@{/distributions/new}`).
- E2 가 `redirect:/distributions/new` 로 돌아오면 E1 이 다시 렌더링되고, flash 속성 `error` 는 `layout :: flash` 조각에서 보여 준다(`resources/templates/layout.html:28-34`, 포함 위치 `distribution_form.html:7`).
- 공통 예외 처리 진입점: `@ControllerAdvice DbErrorAdvice.dbError` 가 컨트롤러 밖으로 나온 `DataAccessException` 을 받아 `db_error` 뷰를 돌려준다(`java/com/example/assign/web/DbErrorAdvice.java:8-17`).
- 애플리케이션 main: `java/com/example/assign/AssignApplication.java:9-11`. 포트는 `server.port=8080`(`resources/application.properties:1`)이다. 컨테이너 밖에서는 8082 로 매핑된다(CLAUDE.md 기준, compose 파일은 범위 밖이라 확인하지 않음).

## 2. 의존 관계

화살표는 "왼쪽이 오른쪽을 호출 · 포함 · 조회한다"는 뜻이다. 실제 호출 줄이 있는 것만 이었다.

### E1 `GET /distributions/new`

```
DistributionController.form                                   web/DistributionController.java:41
 ├─→ AssignmentDao.findAllForSelect()                         :42
 │     └─→ SQL SELECT assignment a LEFT JOIN unit u
 │           + 서브쿼리 COUNT(DISTINCT d.class_id) FROM distribution    dao/AssignmentDao.java:21-24, 34-38
 ├─→ ClassDao.findAll()                                       :43
 │     └─→ SQL SELECT id, name, teacher_id FROM `class`       dao/ClassDao.java:20-22
 ├─→ AppClock.now()                                           :44   (AppClock.java:14-20)
 └─→ 뷰 distribution_form.html
       ├─→ layout :: head('새 배포')                          distribution_form.html:3  → layout.html:3
       ├─→ layout :: nav(${menu})                             distribution_form.html:5  → layout.html:23
       ├─→ layout :: flash                                    distribution_form.html:7  → layout.html:28
       └─→ layout :: footer                                   distribution_form.html:32 → layout.html:35

(조건부) DataAccessException 발생 시 → DbErrorAdvice.dbError → 뷰 db_error.html     web/DbErrorAdvice.java:11-16
```

### E2 `POST /distributions`

```
DistributionController.create                                 web/DistributionController.java:50
 ├─→ AssignmentDao.findById(assignmentId)                     :53
 │     └─→ SQL SELECT ... FROM assignment a LEFT JOIN unit u ... WHERE a.id = ?   dao/AssignmentDao.java:41-48
 ├─→ AppClock.now()                                           :58
 ├─→ AppClock.fmt(dueAt)          (마감 초과 오류 메시지 만들 때만)  :62   (AppClock.java:22-27)
 └─→ DistributionService.distribute(assignmentId, classId)    :67   (@Transactional, service/DistributionService.java:46-68)
       ├─→ AssignmentDao.findById(assignmentId)   (두 번째 조회)       :48
       ├─→ ClassDao.findById(classId)                          :52   → SQL dao/ClassDao.java:24-30
       ├─→ DistributionDao.countByAssignmentAndClass(a, c)     :59   → SQL dao/DistributionDao.java:47-52
       ├─→ DistributionDao.nextId()                            :62   → SQL dao/DistributionDao.java:54-57
       ├─→ AppClock.now()                                      :63
       └─→ DistributionDao.insert(id, a, c, now)               :63   → SQL dao/DistributionDao.java:59-63

결과 → redirect:/distributions (성공, :76) | redirect:/distributions/new + flash error (실패, :56 · :63 · :71 · :74)
```

- E2 의 `DataAccessException` 은 `distribute` 호출 구간에서만 컨트롤러가 잡는다(`DistributionController.java:66-75`). 그보다 앞의 `assignmentDao.findById`(`:53`)에서 나면 잡히지 않고 `DbErrorAdvice` 로 간다. 추정, 실행 확인 안 함.
- 컨트롤러가 서비스를 거치지 않고 `AssignmentDao` · `ClassDao` 를 직접 주입받아 호출한다(`DistributionController.java:23-29, 42-43, 53`). E1 은 서비스를 전혀 호출하지 않는다.
- 범위 안 코드가 호출하지 **않는** 것: `DistributionDao.BASE_SELECT` · `findAllForList` · `findById` · `markRedistributed` · `findSubmissions`, `DistributionService.listAll` · `get` · `redistribute`, 모델 `DistributionRow` · `SubmissionRow` · `RedistributeResult`.

## 3. 데이터 흐름 요약

자세한 내용은 `ERD.md` 에 있다.

1. **E1 폼 표시**: `assignment`(+`unit` JOIN, `distribution` 서브쿼리) 중 상태가 `O` · `X` · `C` 인 과제를 마감 빠른 순으로 읽고, `class` 는 전부 id 순으로 읽는다. 쓰기는 없다. 마감 여부는 템플릿이 `AppClock.now()` 와 비교해서 옵션을 비활성화한다(`distribution_form.html:16`).
2. **E2 등록**: 컨트롤러가 `assignment` 를 한 번 읽고 마감을 검사한다. 이어서 서비스(`@Transactional`)가 `assignment` 를 한 번 더 읽고 `class` 를 읽은 뒤, `distribution` 에서 같은 과제 · 학급 쌍이 있는지 세고 `MAX(id)+1` 로 id 를 정해 `distribution` 에 1행을 INSERT 한다(`redistributed = 0`, `distributed_at = AppClock.now()`).
3. 쓰는 테이블은 `distribution` 하나뿐이고, 쓰는 곳은 E2 의 `DistributionDao.insert`(`dao/DistributionDao.java:59-63`) 하나뿐이다.
4. 시각은 모두 `AppClock.now()` 에서 얻는다. 이 값은 시스템 속성 `app.clock=system` 이 없으면 `2026-09-15 00:00:00` 으로 고정된다(`AppClock.java:9, 14-19`). 그래서 마감 판단과 `distributed_at` 이 모두 고정 시각을 기준으로 한다.
