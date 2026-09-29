# item-bank-php 비즈니스 규칙 (코드 기준 교차 검증용)

- 읽은 파일: `legacy/item-bank-php/search.php`, `register.php`, `units.php`, `inc/db.php` (`vendor/` 와 `docs/` 는 읽지 않음)
- 원칙: 코드에 있는 것만 적었고, 주석과 코드가 다르면 코드를 따랐다.
- 경로는 모두 `legacy/item-bank-php/` 기준이다.

## 1. 검색 — 조건 조합

### CX-01 키워드는 제목 또는 지문에 부분 일치한다
앞뒤 공백을 제거한 키워드가 비어 있지 않으면 `title` 또는 `stem` 에 `LIKE %키워드%` 로 찾는다. 키워드는 100자까지만 쓴다.
- 근거: `search.php:85-89`, `search.php:97-101`
```php
$q = trim($q);
$like = '%' . $q . '%';
$where .= " AND (title LIKE ? OR stem LIKE ?)";
```

### CX-02 키워드의 `%` · `_` 는 이스케이프하지 않는다
와일드카드로 그대로 동작하고, 경고만 낸다.
- 근거: `search.php:94-97`
```php
if (strpos($q, '%') !== false || strpos($q, '_') !== false) {
    $warnings[] = '키워드의 % 와 _ 는 와일드카드로 처리됩니다.';
}
```

### CX-03 단원은 코드 정확 일치이고, 형식이 틀려도 그대로 조회한다
`unit_code = ?` 로 비교한다. 형식 위반 · 소문자 · 미등록 코드는 경고만 내고 입력값 그대로 조회한다(대개 0건). 대문자로 바꿔 주지 않는다.
- 근거: `search.php:109-121`, `search.php:141-143`
```php
if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {
    // 형식이 달라도 그대로 조회한다 (결과는 대개 0건)
$where .= " AND unit_code = ?";
```

### CX-04 난이도를 비우면 5등급 문항이 빠진다
난이도가 빈값이면 `level < 5` 조건이 붙는다. 그래서 난이도 5(최상) 문항은 난이도를 지정해야만 나온다. **주석은 "1~5 모두 포함"이라고 쓰여 있지만 코드는 5를 제외한다.** 코드를 기준으로 썼다.
- 근거: `search.php:213-216`
```php
// 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
if ($level == '') {
    $where .= " AND level < 5";
```

### CX-05 난이도 1~5 는 정확 일치
- 근거: `search.php:217-221`
```php
else if (preg_match('/^[1-5]$/', $level)) {
    $where .= " AND level = ?";
    $values[] = (int)$level;
```

### CX-06 난이도가 1~5 밖이면 경고 후 정수로 바꿔 그대로 비교한다
거부하지 않는다. 숫자가 아닌 값은 `(int)` 변환으로 0 이 되어 조회된다.
- 근거: `search.php:223-229`
```php
$warnings[] = '난이도는 1~5 사이여야 합니다.';
$where .= " AND level = ?";
$values[] = (int)$level;
```

### CX-07 태그는 이름 정확 일치이고 50자까지만 쓴다
태그 이름이 같은 `item_tag` 연결이 있는 문항만 나온다(`EXISTS`). 미등록 태그도 경고만 내고 조회한다(0건). 태그는 하나만 받는다.
- 근거: `search.php:236-247`
```php
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
```

### CX-08 조건은 모두 AND 로 결합하고, 파라미터는 배열이 와도 첫 값 하나만 쓴다
`WHERE 1=1` 에 키워드 · 단원 · 난이도 · 태그 조건을 차례로 `AND` 로 붙인다. 각 파라미터가 배열이면 `reset()` 으로 첫 값만 쓴다.
- 근거: `search.php:44`, `search.php:54-56`, `search.php:98`, `search.php:118`, `search.php:244`
```php
$where    = " WHERE 1=1";
$q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];
```

## 2. 목록 — 대상 · 정렬 · 페이지

### CX-09 검색 대상은 `v_item_public` 뷰 하나다
검색과 건수 집계 모두 이 뷰를 `FROM` 으로 쓴다. 뷰가 어떤 문항을 제외하는지는 이 폴더의 코드에 없다(DB 쪽 정의는 범위 밖). 아래 CX-19 는 `units.php` 가 `status='A'` 만 세는 근거다.
- 근거: `search.php:521-526`
```php
$from   = " FROM v_item_public";
$sql      = $select . $from . $where . $orderBy . $limit;
$countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
```

### CX-10 기본 정렬은 난이도 내림차순, 같으면 id 오름차순
`sort` 가 비었거나 알 수 없는 값이어도 같은 정렬을 쓴다(알 수 없는 값은 경고 추가).
- 근거: `search.php:317-326`
```php
case '':
    $orderBy = " ORDER BY level DESC, id ASC";
default:
    $orderBy = " ORDER BY level DESC, id ASC";
```

### CX-11 정렬 지정 시 방향 기본값과 보조 키
`dir` 이 `asc`/`desc` 가 아니면 비운다(값이 있으면 경고). 방향을 비우면 `level` · `created` 는 내림차순, 나머지는 오름차순이다. 동률이면 `id ASC`(단, `id` 정렬은 방향을 따름). `unit` 정렬은 `unit_code` 다음에 `level DESC`, `id ASC`.
- 근거: `search.php:268-273`, `search.php:293-299`, `search.php:301-315`
```php
$orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
$orderBy = " ORDER BY level DESC, id ASC";       // level, dir != asc
$orderBy = " ORDER BY created_at DESC, id ASC";  // created, dir != asc
```

### CX-12 한 페이지 20건, 페이지 번호는 1~999
숫자가 아니면 1, 0 이하는 1, 999 초과는 999 로 보정한다(경고 추가).
- 근거: `search.php:336-349`, `search.php:523`
```php
if ($page > 999) { $page = 999; ...
$limit  = " LIMIT 20 OFFSET " . (int)$offset;
```

## 3. 등록 검증 (`register.php`)

수정(update) 코드는 이 폴더에 없다. 등록 검증만 있다.

### CX-13 제목은 앞뒤 공백 제거 후 5~200자
- 근거: `register.php:41`, `register.php:49-54`
```php
if (mb_strlen($title, 'UTF-8') < 5) {
if (mb_strlen($title, 'UTF-8') > 200) {
```

### CX-14 지문은 필수
공백만 있으면 trim 후 빈 문자열이라 거부된다.
- 근거: `register.php:42`, `register.php:56-58`
```php
if ($stem === '') {
    $errors[] = '지문을 입력해야 합니다.';
```

### CX-15 단원은 `unit` 테이블에 있는 id 여야 한다
- 근거: `register.php:60-68`
```php
if ((string)$u['id'] === $unitId) { $unitOk = true; }
if (!$unitOk) { $errors[] = '단원을 선택해야 합니다.';
```

### CX-16 난이도는 1~5 정수 문자열만 허용(등록은 거부)
검색(CX-06)과 달리 등록은 오류로 막는다.
- 근거: `register.php:70-72`
```php
if (!preg_match('/^[1-5]$/', $level)) {
    $errors[] = '난이도는 1~5 사이여야 합니다.';
```

### CX-17 목록에 없는 태그 id 는 오류 없이 버린다
유효한 id 만 저장하고 나머지는 조용히 무시한다. 중복 제거는 하지 않는다.
- 근거: `register.php:74-81`
```php
if ((string)$t['id'] === (string)$tid) {
    $validTagIds[] = (int)$tid;
```

### CX-18 새 문항은 검수중(`R`) 상태로 저장된다
`status = 'R'` 로 넣고, 주석과 안내 문구는 검수 완료 전에는 검색에 나오지 않는다고 한다. (상태 `R` 을 검색에서 걸러내는 조건 자체는 이 폴더 코드에서 확인되지 않는다. CX-09 참고.)
- 근거: `register.php:84`, `register.php:92-94`, `register.php:116`
```php
VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())
$notice = '... 등록했습니다. 검수 완료 후 검색에 노출됩니다.';
```

## 4. 제외 조건

### CX-19 단원 목록의 문항 수는 `status = 'A'`(공개) 문항만 센다
삭제 · 검수중 문항은 세지 않는다.
- 근거: `units.php:15-17`
```php
// 단원별 공개(status='A') 문항 수. 삭제 · 검수중 문항은 세지 않는다.
(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
```

## 참고 — 코드에서 확인되지 않은 것
- `v_item_public` 뷰의 제외 조건: 정의가 이 폴더에 없어 단정하지 않았다.
- 문항 수정 시 검증: 수정 화면 · 쿼리가 이 폴더에 없다.
- 등록 시 제목 중복 검사: 없다.
