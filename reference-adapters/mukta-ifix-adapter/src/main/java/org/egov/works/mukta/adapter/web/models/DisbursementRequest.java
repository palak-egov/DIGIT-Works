package org.egov.works.mukta.adapter.web.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.Valid;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DisbursementRequest {
    @JsonProperty("signature")
    private String signature;
    @JsonProperty("header")
    @Valid
    private MsgHeader header;
    @JsonProperty("message")
    @Valid
    private Disbursement message;
}