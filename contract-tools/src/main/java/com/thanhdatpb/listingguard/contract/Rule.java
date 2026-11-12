package com.thanhdatpb.listingguard.contract;

import static com.thanhdatpb.listingguard.contract.ChangeClass.ADDITIVE;
import static com.thanhdatpb.listingguard.contract.ChangeClass.BREAKING;
import static com.thanhdatpb.listingguard.contract.ChangeClass.NON_BREAKING;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every kind of change the check reports, with its class. ADR-022 "Rules of the check" lists the same ids with the same
 * classes, and {@code ContractRulesDocumentedTest} fails when the two differ.
 */
public enum Rule {

    OPERATION_ADDED("operation-added", ADDITIVE),
    OPERATION_REMOVED("operation-removed", BREAKING),
    OPERATION_ID_CHANGED("operation-id-changed", BREAKING),
    OPERATION_DEPRECATED("operation-deprecated", NON_BREAKING),
    SECURITY_ADDED("security-added", BREAKING),
    SECURITY_CHANGED("security-changed", BREAKING),
    SECURITY_REMOVED("security-removed", NON_BREAKING),

    OPTIONAL_PARAMETER_ADDED("optional-parameter-added", ADDITIVE),
    REQUIRED_PARAMETER_ADDED("required-parameter-added", BREAKING),
    PARAMETER_REMOVED("parameter-removed", BREAKING),
    PARAMETER_BECAME_REQUIRED("parameter-became-required", BREAKING),
    PARAMETER_BECAME_OPTIONAL("parameter-became-optional", NON_BREAKING),
    PARAMETER_DEPRECATED("parameter-deprecated", NON_BREAKING),

    OPTIONAL_REQUEST_BODY_ADDED("optional-request-body-added", ADDITIVE),
    REQUIRED_REQUEST_BODY_ADDED("required-request-body-added", BREAKING),
    REQUEST_BODY_REMOVED("request-body-removed", BREAKING),
    REQUEST_BODY_BECAME_REQUIRED("request-body-became-required", BREAKING),
    REQUEST_BODY_BECAME_OPTIONAL("request-body-became-optional", NON_BREAKING),
    REQUEST_MEDIA_TYPE_ADDED("request-media-type-added", ADDITIVE),
    REQUEST_MEDIA_TYPE_REMOVED("request-media-type-removed", BREAKING),

    SUCCESS_RESPONSE_ADDED("success-response-added", BREAKING),
    SUCCESS_RESPONSE_REMOVED("success-response-removed", BREAKING),
    ERROR_RESPONSE_ADDED("error-response-added", ADDITIVE),
    ERROR_RESPONSE_REMOVED("error-response-removed", NON_BREAKING),
    RESPONSE_MEDIA_TYPE_ADDED("response-media-type-added", ADDITIVE),
    RESPONSE_MEDIA_TYPE_REMOVED("response-media-type-removed", BREAKING),
    RESPONSE_HEADER_ADDED("response-header-added", ADDITIVE),
    RESPONSE_HEADER_REMOVED("response-header-removed", BREAKING),

    REQUEST_OPTIONAL_PROPERTY_ADDED("request-optional-property-added", ADDITIVE),
    REQUEST_REQUIRED_PROPERTY_ADDED("request-required-property-added", BREAKING),
    REQUEST_PROPERTY_REMOVED("request-property-removed", BREAKING),
    REQUEST_PROPERTY_BECAME_REQUIRED("request-property-became-required", BREAKING),
    REQUEST_PROPERTY_BECAME_OPTIONAL("request-property-became-optional", NON_BREAKING),
    REQUEST_TYPE_CHANGED("request-type-changed", BREAKING),
    REQUEST_TYPE_WIDENED("request-type-widened", NON_BREAKING),
    REQUEST_NULL_REJECTED("request-null-rejected", BREAKING),
    REQUEST_NULL_ACCEPTED("request-null-accepted", NON_BREAKING),
    REQUEST_ENUM_VALUE_ADDED("request-enum-value-added", ADDITIVE),
    REQUEST_ENUM_VALUE_REMOVED("request-enum-value-removed", BREAKING),
    REQUEST_ENUM_ADDED("request-enum-added", BREAKING),
    REQUEST_ENUM_REMOVED("request-enum-removed", NON_BREAKING),
    REQUEST_FORMAT_CHANGED("request-format-changed", BREAKING),
    REQUEST_CONSTRAINT_TIGHTENED("request-constraint-tightened", BREAKING),
    REQUEST_CONSTRAINT_RELAXED("request-constraint-relaxed", NON_BREAKING),
    REQUEST_ADDITIONAL_PROPERTIES_REJECTED("request-additional-properties-rejected", BREAKING),

    RESPONSE_PROPERTY_ADDED("response-property-added", ADDITIVE),
    RESPONSE_PROPERTY_REMOVED("response-property-removed", BREAKING),
    RESPONSE_PROPERTY_BECAME_OPTIONAL("response-property-became-optional", BREAKING),
    RESPONSE_PROPERTY_BECAME_REQUIRED("response-property-became-required", NON_BREAKING),
    RESPONSE_TYPE_CHANGED("response-type-changed", BREAKING),
    RESPONSE_TYPE_NARROWED("response-type-narrowed", NON_BREAKING),
    RESPONSE_BECAME_NULLABLE("response-became-nullable", BREAKING),
    RESPONSE_NO_LONGER_NULLABLE("response-no-longer-nullable", NON_BREAKING),
    RESPONSE_ENUM_VALUE_ADDED("response-enum-value-added", ADDITIVE),
    RESPONSE_ENUM_VALUE_REMOVED("response-enum-value-removed", NON_BREAKING),
    RESPONSE_ENUM_REMOVED("response-enum-removed", BREAKING),
    RESPONSE_ENUM_ADDED("response-enum-added", NON_BREAKING),
    RESPONSE_FORMAT_CHANGED("response-format-changed", BREAKING),

    PROPERTY_DEPRECATED("property-deprecated", NON_BREAKING),
    SCHEMA_RENAMED("schema-renamed", NON_BREAKING),
    SCHEMA_UNION_CHANGED("schema-union-changed", BREAKING),

    PROBLEM_CODE_ADDED("problem-code-added", ADDITIVE),
    PROBLEM_CODE_REMOVED("problem-code-removed", BREAKING),
    PROBLEM_STATUS_CHANGED("problem-status-changed", BREAKING),
    PROBLEM_TYPE_CHANGED("problem-type-changed", BREAKING),
    PROBLEM_MEMBER_ADDED("problem-member-added", ADDITIVE),
    PROBLEM_MEMBER_REMOVED("problem-member-removed", BREAKING),

    INPUT_DEFINITION_ADDED("input-definition-added", ADDITIVE),
    INPUT_DEFINITION_REMOVED("input-definition-removed", BREAKING);

    private final String id;
    private final ChangeClass changeClass;

    Rule(String id, ChangeClass changeClass) {
        this.id = id;
        this.changeClass = changeClass;
    }

    /** The id used in reports, in {@code docs/api/breaking-changes.yaml} and in ADR-022. */
    public String id() {
        return id;
    }

    public ChangeClass changeClass() {
        return changeClass;
    }

    public static Optional<Rule> byId(String id) {
        return Arrays.stream(values()).filter(rule -> rule.id.equals(id)).findFirst();
    }
}
