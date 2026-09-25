package com.kingdom.api.dto;

import java.util.List;

public record EndSnapshotDto(
        List<Object> survivors,
        int keepHp
) {
}
