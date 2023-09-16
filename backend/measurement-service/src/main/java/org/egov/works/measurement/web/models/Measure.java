package org.egov.works.measurement.web.models;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.*;
import org.egov.works.measurement.web.models.AuditDetails;
import org.egov.works.measurement.web.models.Document;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.*;

/**
 * Entity captures dimensions for a work item measurement
 */
@Schema(description = "Entity captures dimensions for a work item measurement")
@Validated
@javax.annotation.Generated(value = "org.egov.codegen.SpringBootCodegen", date = "2023-09-14T11:43:34.268+05:30[Asia/Calcutta]")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class Measure {

    @JsonProperty("id")
    @Valid
    private UUID id = null;

    @JsonProperty("referenceId")
    @Size(min = 2, max = 64)
    private String referenceId = null;

    @JsonProperty("targetId")
    @NotNull
    @Size(min = 2, max = 64)
    private String targetId = null;

    @JsonProperty("length")
    @Valid
    private BigDecimal length = new BigDecimal(1);

    @JsonProperty("breadth")
    @Valid
    private BigDecimal breadth = new BigDecimal(1);

    @JsonProperty("height")
    @Valid
    private BigDecimal height = new BigDecimal(1);

    @JsonProperty("numItems")
    @Valid
    private BigDecimal numItems = new BigDecimal(1);

    @JsonProperty("currentValue")
    @NotNull
    @Valid
    private BigDecimal currentValue = null;

    @JsonProperty("cumulativeValue")
    @Valid
    private BigDecimal cumulativeValue = null;

    @JsonProperty("isActive")
    private Boolean isActive = null;

    @JsonProperty("comments")
    @Size(min = 2, max = 256)
    private String comments = null;

    @JsonProperty("documents")
    @Valid
    @Size(min = 1)
    private List<Document> documents = null;

    @JsonProperty("auditDetails")
    @Valid
    private AuditDetails auditDetails = null;

    @JsonProperty("additionalDetails")
    private Object additionalDetails = null;


    public Measure addDocumentsItem(Document documentsItem) {
        if (this.documents == null) {
            this.documents = new ArrayList<>();
        }
        this.documents.add(documentsItem);
        return this;
    }

}