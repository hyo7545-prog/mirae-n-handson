# assignment-thymeleaf ERD — 새 배포 화면

> 대상: `legacy/assignment-thymeleaf/` (읽기 전용 분석 결과)
> **범위: "새 배포" 화면 하나** — `GET /distributions/new`(E1), `POST /distributions`(E2)가 읽고 쓰는 테이블만. 목록 · 재배포 화면은 범위 밖이다.
> 스키마 근거: `db/mariadb/init/01-schema.sql`(MariaDB `itembank`). 코드 근거: `legacy/assignment-thymeleaf/src/main/` 기준 상대 경로이고, Java 는 `java/com/example/assign/` 를, 템플릿은 `resources/templates/` 를 생략한다. `01-schema.sql` · `02-seed.sql` 은 `db/mariadb/init/` 아래 파일이다.

## 1. 다이어그램

```mermaid
erDiagram
    unit ||--o{ assignment : "unit_id (FK 선언)"
    assignment ||--o{ distribution : "assignment_id (FK 선언)"
    class ||--o{ distribution : "class_id (FK 선언)"

    unit {
        INT id PK
        VARCHAR code UK "VARCHAR(16)"
        VARCHAR name "VARCHAR(100)"
    }
    assignment {
        INT id PK
        VARCHAR title "VARCHAR(200)"
        INT unit_id FK
        DATETIME due_at "NOT NULL"
        CHAR status "CHAR(1) DEFAULT 'O'"
    }
    class {
        INT id PK
        VARCHAR name "VARCHAR(50)"
        VARCHAR teacher_id "VARCHAR(20)"
    }
    distribution {
        INT id PK "AUTO_INCREMENT 없음"
        INT assignment_id FK
        INT class_id FK
        DATETIME distributed_at
        TINYINT redistributed "DEFAULT 0"
    }
```

- 컬럼은 범위 안 SQL 이 쓰는 것만 적었다. `unit.grade`(`01-schema.sql:15`)는 범위 안 SQL 이 쓰지 않아서 뺐다.
- `submission` 테이블(`01-schema.sql:105-115`)은 범위 안 SQL 이 읽지도 쓰지도 않아서 뺐다.

## 2. 테이블 · 키 근거 (선언 / 추정 구분)

"선언"은 DDL 에 제약으로 있는 것이고, "추정"은 코드 SQL 에서만 확인되는 것이다.

| 테이블 | 키 · 관계 | 구분 | 근거 |
|---|---|---|---|
| `unit` | PK `id`, UNIQUE `code` | 선언 | `01-schema.sql:16-17` |
| `assignment` | PK `id` | 선언 | `01-schema.sql:88` |
| `assignment` → `unit` | `unit_id` → `unit.id` | 선언 (FK `fk_assignment_unit`) | `01-schema.sql:89`. 코드 JOIN: `dao/AssignmentDao.java:24` (`LEFT JOIN unit u ON u.id = a.unit_id`) |
| `class` | PK `id` | 선언 | `01-schema.sql:79` |
| `distribution` | PK `id` | 선언 | `01-schema.sql:98` |
| `distribution` → `assignment` | `assignment_id` → `assignment.id` | 선언 (FK `fk_distribution_assignment`) | `01-schema.sql:101`. 코드: `dao/AssignmentDao.java:23` 서브쿼리 `WHERE d.assignment_id = a.id` |
| `distribution` → `class` | `class_id` → `class.id` | 선언 (FK `fk_distribution_class`) | `01-schema.sql:102` |
| `distribution` (`assignment_id`, `class_id`) 한 쌍당 1건 | 유일성 | **추정 (코드에서만 막음, DDL 에 UNIQUE 없음)** | 검사: `dao/DistributionDao.java:47-52`, `service/DistributionService.java:59`. DDL `01-schema.sql:92-103` 에 UNIQUE 없음. 시드에 이미 중복 쌍이 있다: `02-seed.sql:133-134` (과제 2 · 학급 1 두 건) |
| `distribution.id` 채번 | `MAX(id)+1` | **추정 (코드에서만 채번, AUTO_INCREMENT 없음)** | `dao/DistributionDao.java:54-57`, DDL `01-schema.sql:93` |
| `assignment.status` 값 목록 | `O` · `C` (스키마 주석) / `O` · `X` · `C` · `D` (코드) | **추정 (CHECK 제약 없음)** | 주석 `01-schema.sql:87`, 코드 `dao/AssignmentDao.java:36`, `service/DistributionService.java:56`, `web/DistributionController.java:61` |

## 3. 진입점별 읽기 · 쓰기(R/W)

| 진입점 | `assignment` | `unit` | `class` | `distribution` | 근거 |
|---|---|---|---|---|---|
| E1 `GET /distributions/new` | R | R | R | R (과제별 학급 수 서브쿼리) | `dao/AssignmentDao.java:21-24, 34-38`, `dao/ClassDao.java:20-22` |
| E2 `POST /distributions` | R (2회) | R (2회, JOIN) | R | R (중복 개수 · `MAX(id)`), **W (INSERT)** | `web/DistributionController.java:53`, `service/DistributionService.java:48, 52, 59, 62, 63` → `dao/AssignmentDao.java:41-48`, `dao/ClassDao.java:24-30`, `dao/DistributionDao.java:47-63` |

### SQL 목록 (범위 안)

| SQL | 종류 | 테이블 | 호출 위치 |
|---|---|---|---|
| `AssignmentDao.BASE_SELECT + WHERE a.status IN ('O','X','C') ORDER BY a.due_at ASC, a.id ASC` | SELECT | assignment, unit, distribution | `dao/AssignmentDao.java:21-24, 34-38` ← E1 |
| `AssignmentDao.BASE_SELECT + WHERE a.id = ?` | SELECT | assignment, unit, distribution | `dao/AssignmentDao.java:41-48` ← E2 두 번 |
| ``SELECT id, name, teacher_id FROM `class` ORDER BY id ASC`` | SELECT | class | `dao/ClassDao.java:21` ← E1 |
| ``SELECT id, name, teacher_id FROM `class` WHERE id = ?`` | SELECT | class | `dao/ClassDao.java:25` ← E2 |
| `SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?` | SELECT | distribution | `dao/DistributionDao.java:49` ← E2 |
| `SELECT COALESCE(MAX(id), 0) FROM distribution` | SELECT | distribution | `dao/DistributionDao.java:55` ← E2 |
| `INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)` | INSERT | distribution | `dao/DistributionDao.java:61` ← E2 |

- 모든 SQL 은 `JdbcTemplate` 로 실행하고 접속 정보는 `resources/application.properties:2-4` 에 있다. 기본 계정은 쓰기 계정 `app` 이다.
- `BASE_SELECT` 의 `class_cnt` 서브쿼리(`dao/AssignmentDao.java:23`)는 매핑은 되지만(`:62`) 범위 안 화면 · 컨트롤러는 `classCount` 를 쓰지 않는다(`distribution_form.html:14-18` 에 없음).
