package com.example.omp.proto;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.proto.common.Money;
import com.example.omp.proto.inventory.InventoryServiceGrpc;
import com.example.omp.proto.inventory.Reservation;
import com.example.omp.proto.inventory.ReservationStatus;
import com.example.omp.proto.payment.PaymentServiceGrpc;
import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.MethodDescriptor.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Compares protoc output to the existing v1 API surface, allowing additive changes. */
class ProtoCompatibilityTest {
    private static FileDescriptorSet baseline() throws Exception {
        try (var stream =
                ProtoCompatibilityTest.class.getResourceAsStream("/compatibility/v1.protobin")) {
            assertThat(stream).as("Published v1 descriptor fixture").isNotNull();
            return FileDescriptorSet.parseFrom(stream);
        }
    }

    @Test
    void generatedProtocolPreservesPublishedV1FieldsEnumsAndRpcContracts() throws Exception {
        var path = Path.of("target/generated-resources/protobuf/descriptor-sets/platform.protobin");
        assertThat(path).isRegularFile();
        var current = FileDescriptorSet.parseFrom(Files.readAllBytes(path));
        compatible(baseline(), current);
        String basePath = System.getProperty("proto.compatibility.baseline");
        if (basePath != null) {
            var base = Path.of(basePath);
            assertThat(base).as("Exact pull request base descriptor").isRegularFile();
            compatible(FileDescriptorSet.parseFrom(Files.readAllBytes(base)), current);
        }
    }

    private static void compatible(FileDescriptorSet previous, FileDescriptorSet current) {
        for (var oldFile : previous.getFileList()) {
            var newFile =
                    current.getFileList().stream()
                            .filter(file -> file.getName().equals(oldFile.getName()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "Removed proto file " + oldFile.getName()));
            assertThat(newFile.getPackage()).isEqualTo(oldFile.getPackage());
            assertThat(newFile.getSyntax()).isEqualTo(oldFile.getSyntax());
            assertThat(newFile.getOptions().getJavaPackage())
                    .isEqualTo(oldFile.getOptions().getJavaPackage());
            assertThat(newFile.getOptions().getJavaOuterClassname())
                    .isEqualTo(oldFile.getOptions().getJavaOuterClassname());
            assertThat(newFile.getOptions().getJavaMultipleFiles())
                    .isEqualTo(oldFile.getOptions().getJavaMultipleFiles());
            messages(oldFile.getMessageTypeList(), newFile.getMessageTypeList());
            enums(oldFile.getEnumTypeList(), newFile.getEnumTypeList());
            for (var oldService : oldFile.getServiceList()) {
                var newService =
                        newFile.getServiceList().stream()
                                .filter(service -> service.getName().equals(oldService.getName()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new AssertionError(
                                                        "Removed service " + oldService.getName()));
                for (var method : oldService.getMethodList()) {
                    var replacement =
                            newService.getMethodList().stream()
                                    .filter(
                                            candidate ->
                                                    candidate.getName().equals(method.getName()))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new AssertionError(
                                                            "Removed RPC " + method.getName()));
                    assertThat(replacement.getInputType()).isEqualTo(method.getInputType());
                    assertThat(replacement.getOutputType()).isEqualTo(method.getOutputType());
                    assertThat(replacement.getClientStreaming())
                            .isEqualTo(method.getClientStreaming());
                    assertThat(replacement.getServerStreaming())
                            .isEqualTo(method.getServerStreaming());
                }
            }
        }
    }

    private static void messages(List<DescriptorProto> previous, List<DescriptorProto> current) {
        for (var oldMessage : previous) {
            var newMessage =
                    current.stream()
                            .filter(message -> message.getName().equals(oldMessage.getName()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "Removed message " + oldMessage.getName()));
            assertThat(newMessage.getOptions().getMapEntry())
                    .isEqualTo(oldMessage.getOptions().getMapEntry());
            for (int index = 0; index < oldMessage.getOneofDeclCount(); index++) {
                assertThat(newMessage.getOneofDeclCount()).isGreaterThan(index);
                assertThat(newMessage.getOneofDecl(index).getName())
                        .isEqualTo(oldMessage.getOneofDecl(index).getName());
            }
            for (var field : oldMessage.getFieldList()) {
                var replacement =
                        newMessage.getFieldList().stream()
                                .filter(candidate -> candidate.getNumber() == field.getNumber())
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new AssertionError(
                                                        "Removed/renumbered field "
                                                                + oldMessage.getName()
                                                                + "."
                                                                + field.getName()));
                assertThat(replacement.getName()).isEqualTo(field.getName());
                assertThat(replacement.getType()).isEqualTo(field.getType());
                assertThat(replacement.getTypeName()).isEqualTo(field.getTypeName());
                assertThat(replacement.getLabel()).isEqualTo(field.getLabel());
                assertThat(replacement.getJsonName()).isEqualTo(field.getJsonName());
                assertThat(replacement.hasOneofIndex()).isEqualTo(field.hasOneofIndex());
                assertThat(replacement.getOneofIndex()).isEqualTo(field.getOneofIndex());
                assertThat(replacement.getProto3Optional()).isEqualTo(field.getProto3Optional());
                assertThat(replacement.getDefaultValue()).isEqualTo(field.getDefaultValue());
            }
            assertThat(newMessage.getReservedNameList())
                    .containsAll(oldMessage.getReservedNameList());
            assertThat(newMessage.getReservedRangeList())
                    .containsAll(oldMessage.getReservedRangeList());
            messages(oldMessage.getNestedTypeList(), newMessage.getNestedTypeList());
            enums(oldMessage.getEnumTypeList(), newMessage.getEnumTypeList());
        }
    }

    private static void enums(
            List<EnumDescriptorProto> previous, List<EnumDescriptorProto> current) {
        for (var oldEnum : previous) {
            var newEnum =
                    current.stream()
                            .filter(value -> value.getName().equals(oldEnum.getName()))
                            .findFirst()
                            .orElseThrow(
                                    () -> new AssertionError("Removed enum " + oldEnum.getName()));
            assertThat(newEnum.getValue(0).getName()).isEqualTo(oldEnum.getValue(0).getName());
            assertThat(newEnum.getValue(0).getNumber()).isEqualTo(oldEnum.getValue(0).getNumber());
            for (var value : oldEnum.getValueList()) {
                assertThat(newEnum.getValueList())
                        .anySatisfy(
                                candidate -> {
                                    assertThat(candidate.getName()).isEqualTo(value.getName());
                                    assertThat(candidate.getNumber()).isEqualTo(value.getNumber());
                                });
            }
            assertThat(newEnum.getReservedNameList()).containsAll(oldEnum.getReservedNameList());
            assertThat(newEnum.getReservedRangeList()).containsAll(oldEnum.getReservedRangeList());
        }
    }

    @Test
    void compatibilityGuardRejectsRenumberingButAllowsNewFields() throws Exception {
        var previous = baseline();
        var changed = previous.toBuilder();
        changed.getFileBuilder(0).getMessageTypeBuilder(0).getFieldBuilder(0).setNumber(999);
        assertThatThrownBy(() -> compatible(previous, changed.build()))
                .isInstanceOf(AssertionError.class);
        var additive = previous.toBuilder();
        additive.getFileBuilder(0)
                .getMessageTypeBuilder(0)
                .addField(
                        FieldDescriptorProto.newBuilder()
                                .setName("future_field")
                                .setJsonName("futureField")
                                .setNumber(999)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING));
        compatible(previous, additive.build());
    }

    @Test
    void compatibilityGuardRejectsChangedStreamingMode() throws Exception {
        var previous = baseline();
        var changed = previous.toBuilder();
        var file =
                changed.getFileBuilderList().stream()
                        .filter(value -> value.getServiceCount() > 0)
                        .findFirst()
                        .orElseThrow();
        file.getServiceBuilder(0).getMethodBuilder(0).setClientStreaming(true);
        assertThatThrownBy(() -> compatible(previous, changed.build()))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void compatibilityGuardPreservesReservedFieldsAndEnumValues() {
        var message =
                DescriptorProto.newBuilder()
                        .setName("Example")
                        .addReservedName("removed_field")
                        .addReservedRange(
                                DescriptorProto.ReservedRange.newBuilder().setStart(7).setEnd(8))
                        .build();
        assertThatThrownBy(
                        () ->
                                messages(
                                        List.of(message),
                                        List.of(message.toBuilder().clearReservedName().build())))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(
                        () ->
                                messages(
                                        List.of(message),
                                        List.of(message.toBuilder().clearReservedRange().build())))
                .isInstanceOf(AssertionError.class);
        var enumeration =
                EnumDescriptorProto.newBuilder()
                        .setName("Status")
                        .addValue(
                                EnumValueDescriptorProto.newBuilder()
                                        .setName("UNKNOWN")
                                        .setNumber(0))
                        .addReservedName("REMOVED")
                        .addReservedRange(
                                EnumDescriptorProto.EnumReservedRange.newBuilder()
                                        .setStart(7)
                                        .setEnd(7))
                        .build();
        assertThatThrownBy(
                        () ->
                                enums(
                                        List.of(enumeration),
                                        List.of(
                                                enumeration.toBuilder()
                                                        .clearReservedName()
                                                        .build())))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(
                        () ->
                                enums(
                                        List.of(enumeration),
                                        List.of(
                                                enumeration.toBuilder()
                                                        .clearReservedRange()
                                                        .build())))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void compatibilityGuardRejectsRenamedOneofAndChangedMapEncoding() {
        var message =
                DescriptorProto.newBuilder()
                        .setName("Example")
                        .addOneofDecl(OneofDescriptorProto.newBuilder().setName("selection"))
                        .build();
        var renamed = message.toBuilder();
        renamed.getOneofDeclBuilder(0).setName("replacement");
        assertThatThrownBy(() -> messages(List.of(message), List.of(renamed.build())))
                .isInstanceOf(AssertionError.class);
        var map = message.toBuilder();
        map.getOptionsBuilder().setMapEntry(true);
        assertThatThrownBy(() -> messages(List.of(message), List.of(map.build())))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void generatedStubsExposeTheDeclaredCallPatterns() {
        assertThat(InventoryServiceGrpc.getBulkReserveMethod().getType())
                .isEqualTo(MethodType.CLIENT_STREAMING);
        assertThat(InventoryServiceGrpc.getStreamStockUpdatesMethod().getType())
                .isEqualTo(MethodType.SERVER_STREAMING);
        assertThat(InventoryServiceGrpc.getReserveStockMethod().getType())
                .isEqualTo(MethodType.UNARY);
        assertThat(PaymentServiceGrpc.getCreatePaymentIntentMethod().getType())
                .isEqualTo(MethodType.UNARY);
        assertThat(PaymentServiceGrpc.getStreamPaymentEventsMethod().getType())
                .isEqualTo(MethodType.SERVER_STREAMING);
    }

    @Test
    void moneyRoundTripIsExactAndUnknownFieldsArePreserved() throws Exception {
        var unknown =
                UnknownFieldSet.newBuilder()
                        .addField(900, UnknownFieldSet.Field.newBuilder().addVarint(7).build())
                        .build();
        var message =
                Money.newBuilder()
                        .setAmount("999999999999999.9999")
                        .setCurrency("USD")
                        .setUnknownFields(unknown)
                        .build();
        var restored = Money.parseFrom(message.toByteArray());
        assertThat(restored).isEqualTo(message);
        assertThat(restored.getUnknownFields()).isEqualTo(unknown);
        var reservation = Reservation.newBuilder().setStatusValue(999).build();
        assertThat(Reservation.parseFrom(reservation.toByteArray()).getStatus())
                .isEqualTo(ReservationStatus.UNRECOGNIZED);
        assertThat(Reservation.parseFrom(reservation.toByteArray()).getStatusValue())
                .isEqualTo(999);
    }
}
