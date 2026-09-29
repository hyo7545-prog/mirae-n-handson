package com.example.item;

import java.util.List;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항 API. 컨트롤러는 서비스만 호출하고, 예외는 {@code GlobalExceptionHandler} 가 처리한다.
 */
@RestController
@RequestMapping("/api")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    /**
     * {@code GET /api/items/search} — 문항 검색. 파라미터는 레거시 {@code search.php} 와 같다
     * ({@code q · unit · level · tag · sort · dir · page}, 배열 표기 {@code tag[]} 포함).
     * 값 해석이 레거시 규칙을 따라야 해서 타입 변환 없이 문자열 그대로 서비스에 넘긴다.
     */
    @GetMapping("/items/search")
    public ItemSearchResponse searchItems(@RequestParam MultiValueMap<String, String> params) {
        return itemService.searchItems(params);
    }

    /** {@code GET /api/items/{id}} — 문항 단건. */
    @GetMapping("/items/{id}")
    public ItemResponse getItem(@PathVariable Integer id) {
        return itemService.getItem(id);
    }

    /** {@code GET /api/units/{code}/items} — 단원의 공개 문항 목록. */
    @GetMapping("/units/{code}/items")
    public List<ItemResponse> listItemsByUnit(@PathVariable String code) {
        return itemService.listActiveItemsByUnit(code);
    }
}
