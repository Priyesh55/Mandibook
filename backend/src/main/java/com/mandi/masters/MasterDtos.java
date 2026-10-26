package com.mandi.masters;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class MasterDtos {
    private MasterDtos() {
    }

    public record MasterRequest(
            @NotBlank(message = "{validation.name.required}")
            @Size(max = 200, message = "{validation.name.size}") String name,
            @Size(max = 200, message = "{validation.nameHi.size}") String nameHi,
            /** Farmers only: the village the farmer belongs to (must be a village of the active firm). */
            Long villageId,
            /** Expense heads only: PER_PIECE with a rate, or both empty. */
            @Size(max = 12, message = "{validation.name.size}") String rateBasis,
            @jakarta.validation.constraints.PositiveOrZero(message = "{validation.number.nonNegative}")
            @jakarta.validation.constraints.Digits(integer = 9, fraction = 4, message = "{validation.number.digits}") java.math.BigDecimal rate) {
    }

    public record ActiveRequest(boolean active) {
    }

    /** villageId and villageName are filled for farmers that have a village, null otherwise. */
    public record MasterResponse(Long id, String name, String nameHi, boolean active, Long villageId, String villageName,
                                 String rateBasis, java.math.BigDecimal rate) {
        static MasterResponse of(MasterEntity e) {
            return of(e, null);
        }

        static MasterResponse of(MasterEntity e, String villageName) {
            Long villageId = e instanceof Farmer f ? f.getVillageId() : null;
            ExpenseHead h = e instanceof ExpenseHead x ? x : null;
            return new MasterResponse(e.getId(), e.getName(), e.getNameHi(), e.isActive(), villageId, villageName,
                    h == null ? null : h.getRateBasis(), h == null ? null : h.getRate());
        }
    }

    /** Compact reference embedded in bill responses. */
    public record MasterRef(Long id, String name, String nameHi) {
        public static MasterRef of(MasterEntity e) {
            return e == null ? null : new MasterRef(e.getId(), e.getName(), e.getNameHi());
        }
    }
}
