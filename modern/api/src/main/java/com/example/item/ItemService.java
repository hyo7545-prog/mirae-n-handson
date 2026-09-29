package com.example.item;

import com.example.common.NotFoundException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문항 조회 서비스. 엔티티는 트랜잭션 안에서 DTO로 바꿔 돌려준다(OSIV 꺼져 있음).
 */
@Service
@Transactional(readOnly = true)
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final ItemRepository itemRepository;
    private final UnitRepository unitRepository;

    public ItemService(ItemRepository itemRepository, UnitRepository unitRepository) {
        this.itemRepository = itemRepository;
        this.unitRepository = unitRepository;
    }

    /** 문항 단건. 상태와 무관하게 id로 찾는다(관리 화면용). */
    public ItemResponse getItem(Integer id) {
        Item item = itemRepository.findWithDetailsById(id)
            .orElseThrow(() -> new NotFoundException("문항이 없습니다: id=" + id));
        return ItemResponse.from(item);
    }

    /** 단원의 공개({@code status='A'}) 문항 목록. 단원 코드가 없으면 404. */
    public List<ItemResponse> listActiveItemsByUnit(String unitCode) {
        Unit unit = unitRepository.findByCode(unitCode)
            .orElseThrow(() -> new NotFoundException("단원이 없습니다: code=" + unitCode));
        List<ItemResponse> items = itemRepository.findByUnitCodeAndStatus(unit.getCode(), ItemStatus.ACTIVE).stream()
            .map(ItemResponse::from)
            .toList();
        log.debug("unit {} has {} active items", unit.getCode(), items.size());
        return items;
    }

    /**
     * 문항 검색(레거시 {@code search.php} 이관). 잘못된 입력도 오류 없이 보정하거나 그대로 조회하고,
     * 없는 단원 · 범위 밖 난이도는 0건으로 돌려준다.
     *
     * @param params 쿼리 파라미터(이름 → 값 목록)
     */
    public ItemSearchResponse searchItems(Map<String, List<String>> params) {
        ItemSearchCondition condition = ItemSearchCondition.from(params);
        Page<Item> page = itemRepository.findAll(ItemSpecifications.matching(condition), condition.toPageable());
        List<ItemSearchRow> rows = page.getContent().stream()
            .map(ItemSearchRow::from)
            .toList();
        log.debug("item search matched {} items (page {})", page.getTotalElements(), condition.page());
        return ItemSearchResponse.of(rows, page.getTotalElements());
    }
}
