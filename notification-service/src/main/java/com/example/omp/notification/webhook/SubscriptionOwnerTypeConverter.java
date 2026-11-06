package com.example.omp.notification.webhook;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Keeps the enum mapping compatible with the existing lowercase database constraint. */
@Converter
public class SubscriptionOwnerTypeConverter
        implements AttributeConverter<SubscriptionOwnerType, String> {
    @Override
    public String convertToDatabaseColumn(SubscriptionOwnerType value) {
        return value == null ? null : value.dbValue();
    }

    @Override
    public SubscriptionOwnerType convertToEntityAttribute(String value) {
        return value == null ? null : SubscriptionOwnerType.fromDb(value);
    }
}
