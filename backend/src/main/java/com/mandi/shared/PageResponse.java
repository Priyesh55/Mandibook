package com.mandi.shared;

import java.util.List;

public record PageResponse<T>(List<T> content, long totalElements, int page, int size) {
}
