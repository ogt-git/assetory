package com.project.assetory.analysis.dart;

import java.util.List;

/**
 * 공시검색(list.json) 응답 1페이지. 013(데이터 없음)은 items 빈 목록 · totalPage 0
 */
public record DartListPage(int pageNo, int totalPage, int totalCount, List<DartDisclosure> items) {

    static DartListPage empty() {
        return new DartListPage(1, 0, 0, List.of());
    }
}
