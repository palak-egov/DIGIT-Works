package org.egov.works.mukta.adapter.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import org.egov.common.models.individual.Individual;
import org.egov.tracer.model.CustomException;
import org.egov.works.mukta.adapter.config.Constants;
import org.egov.works.mukta.adapter.config.MuktaAdaptorConfig;
import org.egov.works.mukta.adapter.constants.Error;
import org.egov.works.mukta.adapter.enrichment.PaymentInstructionEnrichment;
import org.egov.works.mukta.adapter.kafka.MuktaAdaptorProducer;
import org.egov.works.mukta.adapter.repository.DisbursementRepository;
import org.egov.works.mukta.adapter.util.*;
import org.egov.works.mukta.adapter.validators.DisbursementValidator;
import org.egov.works.mukta.adapter.web.models.*;
import org.egov.works.mukta.adapter.web.models.bankaccount.BankAccount;
import org.egov.works.mukta.adapter.web.models.bill.*;
import org.egov.works.mukta.adapter.web.models.enums.PaymentStatus;
import org.egov.works.mukta.adapter.web.models.enums.Status;
import org.egov.works.mukta.adapter.web.models.enums.StatusCode;
import org.egov.works.mukta.adapter.web.models.jit.Beneficiary;
import org.egov.works.mukta.adapter.web.models.organisation.Organisation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PaymentInstructionService {
    private final BillUtils billUtils;
    private final PaymentInstructionEnrichment piEnrichment;
    private final BankAccountUtils bankAccountUtils;
    private final OrganisationUtils organisationUtils;
    private final IndividualUtils individualUtils;
    private final MdmsUtil mdmsUtil;
    private final DisbursementRepository disbursementRepository;
    private final ProgramServiceUtil programServiceUtil;
    private final MuktaAdaptorProducer muktaAdaptorProducer;
    private final MuktaAdaptorConfig muktaAdaptorConfig;
    private final EncryptionDecryptionUtil encryptionDecryptionUtil;
    private final ObjectMapper objectMapper;
    private final DisbursementValidator disbursementValidator;

    @Autowired
    public PaymentInstructionService(BillUtils billUtils, PaymentInstructionEnrichment piEnrichment, BankAccountUtils bankAccountUtils, OrganisationUtils organisationUtils, IndividualUtils individualUtils, MdmsUtil mdmsUtil, DisbursementRepository disbursementRepository, ProgramServiceUtil programServiceUtil, MuktaAdaptorProducer muktaAdaptorProducer, MuktaAdaptorConfig muktaAdaptorConfig, EncryptionDecryptionUtil encryptionDecryptionUtil, ObjectMapper objectMapper, PaymentService paymentService, DisbursementValidator disbursementValidator) {
        this.billUtils = billUtils;
        this.piEnrichment = piEnrichment;
        this.bankAccountUtils = bankAccountUtils;
        this.organisationUtils = organisationUtils;
        this.individualUtils = individualUtils;
        this.mdmsUtil = mdmsUtil;
        this.disbursementRepository = disbursementRepository;
        this.programServiceUtil = programServiceUtil;
        this.muktaAdaptorProducer = muktaAdaptorProducer;
        this.muktaAdaptorConfig = muktaAdaptorConfig;
        this.encryptionDecryptionUtil = encryptionDecryptionUtil;
        this.objectMapper = objectMapper;
        this.disbursementValidator = disbursementValidator;
    }

    public Disbursement processDisbursementCreate(PaymentRequest paymentRequest) {
        log.info("Processing payment instruction on failure");
        disbursementValidator.isValidForDisbursementCreate(paymentRequest);
        log.info("Creating new disbursement for the payment id : " + paymentRequest.getReferenceId());
        Disbursement disbursement = processPaymentInstruction(paymentRequest);
        DisbursementCreateRequest disbursementRequest = DisbursementCreateRequest.builder().disbursement(disbursement).requestInfo(paymentRequest.getRequestInfo()).build();
//        programServiceUtil.callProgramServiceDisbursement(disbursementRequest);
        log.info("Pushing disbursement request to the kafka topic");
        muktaAdaptorProducer.push(muktaAdaptorConfig.getDisburseCreateTopic(), disbursementRequest);
        return disbursement;
    }

    public Disbursement processPaymentInstruction(PaymentRequest paymentRequest) {
        log.info("Processing payment instruction");
        Disbursement disbursement = null;
        try {
            // Check if payment details are not present in the request
            if(paymentRequest.getPayment() == null && paymentRequest.getReferenceId() != null && paymentRequest.getTenantId() != null) {
                log.info("Fetching payment details by using reference id and tenant id");
                List<Payment> payments = billUtils.fetchPaymentDetails(paymentRequest.getRequestInfo(), paymentRequest.getReferenceId(), paymentRequest.getTenantId());

                // If no payments are found, throw a custom exception
                if (payments == null || payments.isEmpty()) {
                    throw new CustomException(Error.PAYMENT_NOT_FOUND, Error.PAYMENT_NOT_FOUND_MESSAGE);
                }
                log.info("Payments fetched for the disbursement request : " + payments);
                paymentRequest.setPayment(payments.get(0));
            }

            // Fetch MDMS data
            Map<String, Map<String, JSONArray>> mdmsData = mdmsUtil.fetchMdmsData(paymentRequest.getRequestInfo(), paymentRequest.getPayment().getTenantId());

            // Get beneficiaries from payment
            disbursement = getBeneficiariesFromPayment(paymentRequest, mdmsData);

            log.info("Encrypting Disbursement Object");
            // Encrypt the disbursement object
            JsonNode node = encryptionDecryptionUtil.encryptObject(disbursement, muktaAdaptorConfig.getStateLevelTenantId(), muktaAdaptorConfig.getMuktaAdapterEncryptionKey(), JsonNode.class);

            // Convert the encrypted object back to Disbursement
            disbursement = objectMapper.convertValue(node, Disbursement.class);
        } catch (Exception e) {
            log.error("Error occurred while processing the payment instruction", e);

            // If disbursement is not null, enrich its status
            if (disbursement != null) {
                piEnrichment.enrichDisbursementStatus(disbursement, StatusCode.FAILED);
            } else {
                // If disbursement is null, log the error and throw a custom exception
                log.error("Disbursement is null. Cannot enrich status.");
                throw new CustomException(Error.DISBURSEMENT_NOT_FOUND, Error.DISBURSEMENT_NOT_FOUND_MESSAGE);
            }
        }
        log.info("Disbursement request is " + disbursement);
        return disbursement;
    }

    private Disbursement getBeneficiariesFromPayment(PaymentRequest paymentRequest, Map<String, Map<String, JSONArray>> mdmsData) {
        log.info("Started executing getBeneficiariesFromPayment");

        // Fetching SSU details and Head codes from MDMS data
        JSONArray ssuDetails = mdmsData.get(Constants.MDMS_IFMS_MODULE_NAME).get(Constants.MDMS_SSU_DETAILS_MASTER);
        JSONArray headCodes = mdmsData.get(Constants.MDMS_EXPENSE_MODULE_NAME).get(Constants.MDMS_HEAD_CODES_MASTER);

        // Creating a map of head code categories
        HashMap<String,String> headCodeCategoryMap = getHeadCodeCategoryMap(headCodes);

        // Converting SSU details to JsonNode
        JsonNode ssuNode = objectMapper.valueToTree(ssuDetails.get(0));

        // Fetching the list of bills based on payment request
        List<Bill> billList = billUtils.fetchBillsFromPayment(paymentRequest);

        // If no bills are found, throw a custom exception
        if (billList == null || billList.isEmpty()) {
            throw new CustomException(Error.BILLS_NOT_FOUND , Error.BILLS_NOT_FOUND_MESSAGE);
        }

        // Filtering bills based on line item status
        billList = filterBillsPayableLineItemByPayments(paymentRequest.getPayment(), billList);
        log.info("Bills are filtered based on line item status, and sending back."+ billList);

        // Fetching beneficiaries from bills
        List<Beneficiary> beneficiaryList = piEnrichment.getBeneficiariesFromBills(billList, paymentRequest, mdmsData);

        // If no beneficiaries are found, throw a custom exception
        if (beneficiaryList == null || beneficiaryList.isEmpty()) {
            throw new CustomException(Error.BENEFICIARIES_NOT_FOUND, Error.BENEFICIARIES_NOT_FOUND_MESSAGE);
        }

        // Fetching all beneficiary ids from payment request
        List<String> individualIds = new ArrayList<>();
        List<String> orgIds = new ArrayList<>();
        for (Bill bill : billList) {
            for (BillDetail billDetail : bill.getBillDetails()) {
                Party payee = billDetail.getPayee();

                // If payee is an individual, add to individualIds list
                if (payee != null && payee.getType().equals(Constants.PAYEE_TYPE_INDIVIDUAL)) {
                    individualIds.add(billDetail.getPayee().getIdentifier());
                }
                // If payee is an organization, add to orgIds list
                else if (payee != null) {
                    orgIds.add(billDetail.getPayee().getIdentifier());
                }
            }
        }

        // If no individual or organization ids are found, throw a custom exception
        if (individualIds.isEmpty() && orgIds.isEmpty()) {
            throw new CustomException(Error.NO_BENEFICIARY_IDS_FOUND, Error.NO_BENEFICIARY_IDS_FOUND_MESSAGE);
        }

        // Enriching beneficiaries data and returning the disbursement
        return getBeneficiariesEnrichedData(paymentRequest, beneficiaryList, orgIds, individualIds,ssuNode,headCodeCategoryMap);
    }
    private HashMap<String, String> getHeadCodeCategoryMap(JSONArray headCodes) {
        HashMap<String,String> headCodeCategoryMap = new HashMap<>();
        for (Object headCode : headCodes) {
            JsonNode headCodeNode = objectMapper.valueToTree(headCode);
            headCodeCategoryMap.put(headCodeNode.get("code").asText(),headCodeNode.get(Constants.HEAD_CODE_CATEGORY_KEY).asText());
        }
        return headCodeCategoryMap;
    }

    private Disbursement getBeneficiariesEnrichedData(PaymentRequest paymentRequest, List<Beneficiary> beneficiaryList, List<String> orgIds, List<String> individualIds,JsonNode ssuNode,HashMap<String,String> headCodeCategoryMap) {
        log.info("Started executing getBeneficiariesEnrichedData");

        // Collecting beneficiary ids from the beneficiary list
        List<String> beneficiaryIds = new ArrayList<>();
        for (Beneficiary beneficiary : beneficiaryList) {
            beneficiaryIds.add(beneficiary.getBeneficiaryId());
        }

        List<Organisation> organizations = new ArrayList<>();
        List<Individual> individuals = new ArrayList<>();

        // Fetching bank account details by beneficiary ids
        List<BankAccount> bankAccounts = bankAccountUtils.getBankAccountsByIdentifier(paymentRequest.getRequestInfo(), beneficiaryIds, paymentRequest.getPayment().getTenantId());
        if (bankAccounts == null || bankAccounts.isEmpty()) {
            throw new CustomException(Error.BANK_ACCOUNTS_NOT_FOUND, Error.BANK_ACCOUNTS_NOT_FOUND_MESSAGE);
        }
        log.info("Bank accounts fetched for the beneficiary ids : " + bankAccounts);

        // Fetching organization details if orgIds are not empty
        if (orgIds != null && !orgIds.isEmpty()) {
            organizations = organisationUtils.getOrganisationsById(paymentRequest.getRequestInfo(), orgIds, paymentRequest.getPayment().getTenantId());
            log.info("Organizations fetched for the org ids : " + organizations);
        }

        // Fetching individual details if individualIds are not empty
        if (individualIds != null && !individualIds.isEmpty()) {
            individuals = individualUtils.getIndividualById(paymentRequest.getRequestInfo(), individualIds, paymentRequest.getPayment().getTenantId());
            log.info("Individuals fetched for the individual ids : " + individuals);
        }

        // Enriching disbursement request with beneficiary bank account details
        Disbursement disbursementRequest = piEnrichment.enrichBankaccountOnBeneficiary(beneficiaryList, bankAccounts, individuals, organizations, paymentRequest,ssuNode,headCodeCategoryMap);
        if (disbursementRequest == null) {
            throw new CustomException(Error.DISBURSEMENT_ENRICHMENT_FAILED, Error.DISBURSEMENT_ENRICHMENT_FAILED_MESSAGE);
        }
        log.info("Beneficiaries are enriched, sending back beneficiaryList");

        return disbursementRequest;
    }


    public List<Bill> filterBillsPayableLineItemByPayments(Payment payment, List<Bill> billList) {
        log.info("Started executing filterBillsPayableLineItemByPayments");

        Map<String, BillDetail> billDetailMap = billList.stream()
                .map(Bill::getBillDetails)
                .flatMap(Collection::stream)
                .collect(Collectors.toMap(BillDetail::getId, Function.identity()));
        Map<String, LineItem> billPayableLineItemMap = billList.stream()
                .map(Bill::getBillDetails)
                .flatMap(Collection::stream)
                .map(BillDetail::getPayableLineItems)
                .flatMap(Collection::stream)
                .collect(Collectors.toMap(LineItem::getId, Function.identity()));
        for (PaymentBill paymentBill : payment.getBills()) {
            for (PaymentBillDetail paymentBillDetail : paymentBill.getBillDetails()) {
                List<LineItem> lineItems = new ArrayList<>();
                for (PaymentLineItem payableLineItem : paymentBillDetail.getPayableLineItems()) {
                    LineItem lineItem = billPayableLineItemMap.get(payableLineItem.getLineItemId());
                    if (lineItem != null && lineItem.getStatus().equals(Status.ACTIVE) && (payableLineItem.getStatus().equals(PaymentStatus.INITIATED) || payableLineItem.getStatus().equals(PaymentStatus.FAILED)))
                        lineItems.add(lineItem);
                }
                billDetailMap.get(paymentBillDetail.getBillDetailId()).setPayableLineItems(lineItems);
            }
        }
        log.info("Bills are filtered based on line item status, and sending back.");
        return billList;
    }

    public List<Disbursement> processDisbursementSearch(DisbursementSearchRequest disbursementSearchRequest) {
        log.info("Searching for disbursements based on the criteria: "+ disbursementSearchRequest.getCriteria());
        return disbursementRepository.searchDisbursement(disbursementSearchRequest);
    }
}
