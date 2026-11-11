package com.thanhdatpb.listingguard.api.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The customizer that keeps the nullable members of Spring's {@code ProblemDetail} in the document (ADR-020). */
class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void theFourMembersGetNullAfterTheirTypeWhicheverWayTheTypeIsStored() {
        for (boolean typeStoredAsTypes : List.of(true, false)) {
            OpenAPI document = document(typeStoredAsTypes);

            config.nullableProblemDetailMembers().customise(document);

            Schema<?> problem = problem(document);
            assertThat(OpenApiConfig.PROBLEM_DETAIL_NULLABLE_MEMBERS).containsExactly("title", "detail", "instance", "properties");
            assertThat(problem.getProperties().get("title").getTypes()).containsExactly("string", "null");
            assertThat(problem.getProperties().get("detail").getTypes()).containsExactly("string", "null");
            assertThat(problem.getProperties().get("instance").getTypes()).containsExactly("string", "null");
            assertThat(problem.getProperties().get("properties").getTypes()).containsExactly("object", "null");
        }
    }

    @Test
    void otherMembersAndOtherSchemasAreLeftAlone() {
        OpenAPI document = document(true);

        config.nullableProblemDetailMembers().customise(document);

        Schema<?> problem = problem(document);
        assertThat(problem.getProperties().get("type").getTypes()).containsExactly("string");
        assertThat(problem.getProperties().get("status").getTypes()).containsExactly("integer");
        assertThat(problem.getProperties().get("instance").getFormat()).isEqualTo("uri");
        assertThat(document.getComponents().getSchemas().get("Other").getTypes()).containsExactly("string");
    }

    @Test
    void runningItAgainChangesNothing() {
        OpenAPI document = document(true);

        config.nullableProblemDetailMembers().customise(document);
        config.nullableProblemDetailMembers().customise(document);

        assertThat(problem(document).getProperties().get("title").getTypes()).containsExactly("string", "null");
    }

    @Test
    void aMemberThatIsAlreadyNullableStaysAsItIs() {
        OpenAPI document = document(true);
        problem(document).getProperties().get("detail").addType("null");

        config.nullableProblemDetailMembers().customise(document);

        assertThat(problem(document).getProperties().get("detail").getTypes()).containsExactly("string", "null");
    }

    @Test
    void aMissingSchemaOrMemberIsAnErrorNotASilentDrift() {
        OpenAPI withoutSchema = document(true);
        withoutSchema.getComponents().getSchemas().remove("ProblemDetail");
        assertThatThrownBy(() -> config.nullableProblemDetailMembers().customise(withoutSchema))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ProblemDetail");

        OpenAPI withoutMember = document(true);
        problem(withoutMember).getProperties().remove("instance");
        assertThatThrownBy(() -> config.nullableProblemDetailMembers().customise(withoutMember))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ProblemDetail.instance");
    }

    /** The shape springdoc 3.2.0 generates on Spring Framework 7: each member has one type and none is nullable. */
    private static OpenAPI document(boolean typeStoredAsTypes) {
        Schema<?> problem = new Schema<>();
        problem.addProperty("type", typed("string", typeStoredAsTypes).format("uri"));
        problem.addProperty("title", typed("string", typeStoredAsTypes));
        problem.addProperty("status", typed("integer", typeStoredAsTypes).format("int32"));
        problem.addProperty("detail", typed("string", typeStoredAsTypes));
        problem.addProperty("instance", typed("string", typeStoredAsTypes).format("uri"));
        problem.addProperty("properties", typed("object", typeStoredAsTypes).additionalProperties(new Schema<>()));
        Components components = new Components()
                .addSchemas("ProblemDetail", problem)
                .addSchemas("Other", typed("string", typeStoredAsTypes));
        return new OpenAPI().components(components);
    }

    private static Schema<?> typed(String type, boolean typeStoredAsTypes) {
        Schema<?> schema = new Schema<>();
        if (typeStoredAsTypes) {
            schema.addType(type);
        } else {
            schema.setType(type);
        }
        return schema;
    }

    private static Schema<?> problem(OpenAPI document) {
        return document.getComponents().getSchemas().get("ProblemDetail");
    }
}
