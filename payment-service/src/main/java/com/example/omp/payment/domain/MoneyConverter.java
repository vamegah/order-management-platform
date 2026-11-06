package com.example.omp.payment.domain;

import com.example.omp.shared.domain.Money;
import java.math.BigDecimal;
import java.util.Currency;
import org.springframework.stereotype.Component;

/**
 * Converts between the proto {@code Money} message and the platform's {@link Money} value object.
 *
 * <p>The proto message is generated from {@code common/money.proto} (see shared/proto). The domain
 * value object lives in {@code shared/domain}.
 *
 * <p>This is the first place where proto types and shared-domain types meet. The conversion is
 * deliberately explicit — no reflection, no implicit mapping — so that any change to either side
 * surfaces here.
 *
 * <p><b>Why not reuse one type for both?</b> Because they serve different purposes:
 *
 * <ul>
 *   <li>The proto message is a wire format. It has generated builder methods, {@code
 *       getDefaultInstance()} semantics, and no validation or business logic.
 *   <li>The domain value object has validation (non-negative amounts, valid currency codes),
 *       arithmetic operations ({@code add}, {@code subtract}, {@code multiply}), and immutability.
 * </ul>
 *
 * Conflating them would force the domain to depend on protobuf-java (a concern we've deliberately
 * avoided in {@code shared/domain}).
 *
 * <p><b>Serialization note:</b> proto {@code Money.amount} is a string, not a numeric type.
 * Protobuf has no decimal; {@code double} loses precision. The string is parsed into {@link
 * BigDecimal} on the way in and serialized via {@code toPlainString()} on the way out.
 */
@Component
public class MoneyConverter {

    /**
     * Converts a proto {@link com.example.omp.proto.common.Money} to the platform's {@link Money}.
     *
     * @throws IllegalArgumentException if the amount or currency is invalid
     */
    public Money toDomain(com.example.omp.proto.common.Money protoMoney) {
        if (protoMoney == null || !protoMoney.getAmount().matches("[0-9]{1,15}(\\.[0-9]{1,4})?")) {
            throw new IllegalArgumentException("Amount must be a bounded positive decimal");
        }
        Currency currency = Currency.getInstance(protoMoney.getCurrency());
        int scale = currency.getDefaultFractionDigits();
        if (scale < 0) throw new IllegalArgumentException("Unsupported currency");
        try {
            long minor =
                    new BigDecimal(protoMoney.getAmount()).movePointRight(scale).longValueExact();
            if (minor <= 0) throw new IllegalArgumentException("Amount must be positive");
            return MinorUnits.toMoney(minor, currency.getCurrencyCode());
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "Amount must contain exact currency minor units", failure);
        }
    }

    /**
     * Converts the platform's {@link Money} to a proto message.
     *
     * <p>Serializes the amount via {@code toPlainString()} — never {@code toString()}, which can
     * produce scientific notation for very large or very small values.
     */
    public com.example.omp.proto.common.Money toProto(Money domainMoney) {
        if (domainMoney == null) {
            return com.example.omp.proto.common.Money.getDefaultInstance();
        }

        return com.example.omp.proto.common.Money.newBuilder()
                .setAmount(domainMoney.getAmount().toPlainString())
                .setCurrency(domainMoney.getCurrencyCode())
                .build();
    }
}
