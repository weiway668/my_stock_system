package com.trading.domain.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CorporateActionTypeConverter implements AttributeConverter<CorporateActionEntity.CorporateActionType, Integer> {

    @Override
    public Integer convertToDatabaseColumn(CorporateActionEntity.CorporateActionType actionType) {
        if (actionType == null) {
            return null;
        }
        return actionType.ordinal();
    }

    @Override
    public CorporateActionEntity.CorporateActionType convertToEntityAttribute(Integer dbData) {
        if (dbData == null) {
            return null;
        }
        return CorporateActionEntity.CorporateActionType.values()[dbData];
    }
}
